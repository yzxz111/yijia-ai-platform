package com.yuzhihuihe.apiplatform.gateway.video;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.limit.PackageSecondRateLimitService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 端到端地执行一次视频生成请求。
 *
 * <p>流程：预留所请求的秒数、持久化一条 request-id 意图、提交到上游、
 * 回填供应商的 task id，随后在任务进入终态时结算一次。调用方查询状态时
 * 网关按需向上游轮询，被遗弃的任务则由 {@link VideoTaskRecoveryJob} 清扫。
 * 终态的轮询会完成计费或结算；供应商结果未知时则保持预留，留待后续对账重试。
 *
 * <p>本类负责三个性质：
 *
 * <ol>
 *   <li><b>先预留、后花费。</b>秒数在上游调用之前就针对套餐预留，
 *       因此没有余额的用户无法发起一次最终要由平台买单的生成。
 *   <li><b>恰好结算一次。</b>终态转换是一条带守卫的 UPDATE，返回受影响行数；
 *       只有观测到 1 的调用方才执行结算。因此对已完成的任务轮询任意多次
 *       都只计费一次。
 *   <li><b>按上游实际收费计费。</b>对失败的生成仍会计费的厂商会报告实际尝试的
 *       时长，客户也就照此被计费；不计费的厂商报告零。费率卡的
 *       {@code retry_rate} 就是成本基础中与之对应的余量，因此两者不会被重复计算。
 * </ol>
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class VideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationService.class);

    /**
     * 已提交的任务在网关放弃它之前，最多可以保持非终态多久。
     *
     * <p>预留的持有时间严格长于该值，长出的部分为
     * {@link #RESERVATION_GRACE}，因此任务总是在其预留仍可结算时
     * 就被 {@code VideoTaskRecoveryJob} 处理掉。
     */
    static final Duration TASK_TTL = Duration.ofMinutes(30);

    /**
     * 预留秒数的持有时间比任务本身长出多少。
     *
     * <p>没有这段余量，两个截止时刻就是同一瞬间，而预留是先创建的，
     * 于是它总是先过期——并且由 {@code ReservationRecoveryJob} 让它过期，
     * 因为视频路径不会写入 {@code usage_log} 行供该轮清扫的证据查询发现。
     * 视频任务随后进入终态时已经没有可据以结算的预留，
     * 这会把一次慢生成变成一次未计费的生成。
     * 让秒数持有到任务截止时刻之后，结算得以先行，
     * 视频任务清扫也就成为先动的一方。通用预留清扫对 {@code VIDEO_SECOND} 行
     * 是 fail-closed 的，因为它无权推断某次上游生成从未被接受。
     */
    static final Duration RESERVATION_GRACE = Duration.ofMinutes(5);

    private static final String VIDEO_CAPABILITY_JSON = "[\"VIDEO\"]";
    private static final String DIMENSION = "VIDEO_SECOND";

    /**
     * 错误分类：厂商已产出、但网关无法向客户计费的生成。
     *
     * <p>与 {@code gateway_task_expired} 不同：视频确实存在，而客户被拒绝交付，
     * 这是需要对账的事件，而不是一个单纯超时的任务。
     */
    static final String SETTLEMENT_REFUSED = "gateway_settlement_refused";

    private final QuotaService quotaService;
    private final VideoTaskRepository taskRepository;
    private final VideoRouteRepository routeRepository;
    private final VideoAdapterRegistry adapterRegistry;
    private final VideoEconomicsEvidenceWriter economicsEvidence;
    private final PackageSecondRateLimitService packageSecondRateLimitService;

    @Autowired
    VideoGenerationService(
            QuotaService quotaService,
            VideoTaskRepository taskRepository,
            VideoRouteRepository routeRepository,
            VideoAdapterRegistry adapterRegistry,
            VideoEconomicsEvidenceWriter economicsEvidence,
            PackageSecondRateLimitService packageSecondRateLimitService) {
        this.quotaService = quotaService;
        this.taskRepository = taskRepository;
        this.routeRepository = routeRepository;
        this.adapterRegistry = adapterRegistry;
        this.economicsEvidence = economicsEvidence;
        this.packageSecondRateLimitService = packageSecondRateLimitService == null
                ? PackageSecondRateLimitService.unlimited() : packageSecondRateLimitService;
    }

    /** 没有已售出的秒数速率需要强制执行：套餐的每请求秒数配额依然有效。 */
    VideoGenerationService(
            QuotaService quotaService,
            VideoTaskRepository taskRepository,
            VideoRouteRepository routeRepository,
            VideoAdapterRegistry adapterRegistry,
            VideoEconomicsEvidenceWriter economicsEvidence) {
        this(quotaService, taskRepository, routeRepository, adapterRegistry, economicsEvidence,
                PackageSecondRateLimitService.unlimited());
    }

    /**
     * 提交一次生成任务。
     *
     * <p>request id 就是幂等键：重放同一个 request id 会返回既有任务，
     * 而不是再买一次生成。
     */
    public Mono<TaskView> submit(SubmitCommand command) {
        validate(command);
        return resolveInternalAlias(command)
                .flatMap(resolved -> taskRepository.findByRequestId(resolved.requestId())
                        .flatMap(existing -> existing.userId().equals(resolved.apiKey().userId())
                                && existing.tenantId().equals(resolved.apiKey().tenantId())
                                ? Mono.just(TaskView.of(existing))
                                // 任务 id 在整张表中唯一，因此与另一个账号的任务相撞的键
                                // 无法发起新的生成。这里 fail-closed，
                                // 而不是把一个陌生人的任务交回去。
                                : Mono.error(new ResponseStatusException(
                                        HttpStatus.CONFLICT, "video_request_id_conflict")))
                        .switchIfEmpty(Mono.defer(() -> submitNew(resolved))));
    }

    /**
     * V110 合并模型：把 {@code (model, resolution)} 解析成内部执行别名。
     *
     * <p>video-minimax 是客户端唯一的视频入口，480P 与 720P 分别映射到
     * video-budget 与 video-minimax 自身——档位校验、路由、计费全部沿用
     * 内部别名的既有链路，因此平台侧定价一行不动。没有映射行的别名
     * （第三方兼容客户端直接按内部别名调用）保持原样。
     */
    private Mono<SubmitCommand> resolveInternalAlias(SubmitCommand command) {
        ApiKeyService.ApiKeyContext key = command.apiKey();
        if (key == null || key.packageInstanceId() == null) {
            return Mono.just(command);
        }
        // null 守卫只对 mock 有意义（真实仓储从不返回 null）：
        // 它让没有映射行的别名保持原样，而不是在测试里空指针。
        Mono<String> resolved = routeRepository.internalAliasFor(
                key.packageInstanceId(), key.tenantId(), key.userId(),
                command.modelAlias(), command.resolutionTier());
        return (resolved == null ? Mono.<String>empty() : resolved)
                .defaultIfEmpty(command.modelAlias())
                .map(command::withModelAlias);
    }

    private Mono<TaskView> submitNew(SubmitCommand command) {
        // 别名所售的分辨率要在买下任何东西之前就确定，理由与速率相同：
        // 针对某一分辨率定价的套餐，不得被花在更贵的分辨率上。
        // 随后，已售出的「秒/分钟」速率在此处即作为准入条件，早于秒数被预留、
        // 也早于任何渠道被查找：套餐的分钟额度付不起的一次生成，
        // 既不得占用配额，也不得触达厂商。
        return requireSoldResolution(command)
                .then(admitSecondRate(command))
                .then(Mono.defer(() -> submitToChannel(command)));
    }

    /**
     * 拒绝分辨率与其别名所售不一致的请求。
     *
     * <p>每个视频别名都按分辨率定价：{@code video-budget} 是一个 480P 别名，
     * 每秒 35 分、1080P 为 115 分，而套餐版本的最坏情况毛利是
     * 依据目录声称其别名所售的档位计算的。接受另一个分辨率，
     * 就等于让客户花 480P 套餐去做 1080P 渲染，成本是其定价所依据的
     * 三倍以上，这正是该映射要被强制执行而非仅记录在文档里的原因（网关迁移 V54）。
     *
     * <p>没有登记档位的别名会以自己的错误码 fail-closed：目录对这样的别名
     * 同样没有成本基础，因此放行该请求就等于卖出无人能定价的秒数。
     * 什么也不回答的协作者按同样方式处理——空答案绝不能被读作
     * 「任何分辨率都允许」。
     */
    private Mono<Void> requireSoldResolution(SubmitCommand command) {
        Mono<String> sold = routeRepository.resolutionTier(command.modelAlias());
        return (sold == null ? Mono.<String>empty() : sold)
                .defaultIfEmpty("")
                .flatMap(tier -> {
                    if (tier.isEmpty()) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "video_model_resolution_unregistered"));
                    }
                    if (!tier.equals(command.resolutionTier())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "video_resolution_not_sold_by_model"));
                    }
                    return Mono.empty();
                })
                .then();
    }

    private Mono<TaskView> submitToChannel(SubmitCommand command) {
        return routeRepository
                .candidates(command.apiKey(), command.modelAlias(), VIDEO_CAPABILITY_JSON)
                .filter(channel -> adapterRegistry.supports(channel.providerType()))
                .next()
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "video_channel_unavailable")))
                .flatMap(channel -> {
                    VideoGenerationAdapter adapter = adapterRegistry.forProviderType(
                            channel.providerType());
                    VideoGenerationAdapter.TaskRequest upstreamRequest =
                            taskRequest(command, channel);
                    // 供应商约束是确定性的，必须在存在秒数占用之前校验。
                    // 特别是 Wan 2.5 只接受 5/10s，而 Kling 接受 3-15s
                    // 以及更窄的一组宽高比。
                    adapter.validateSubmission(upstreamRequest);
                    return reserve(command).flatMap(reservation -> {
                        if (!DIMENSION.equals(reservation.usageDimension())) {
                            // 该套餐授予的是 token，而不是秒数。对它计费会用秒数
                            // 去消耗 token 余额，因此拒绝。
                            return quotaService.release(
                                            reservation.id(), QuotaService.ReleaseReason.CLIENT_CANCELLED)
                                    .then(Mono.error(new ResponseStatusException(
                                            HttpStatus.UNPROCESSABLE_ENTITY,
                                            "video_requires_second_denominated_package")));
                        }
                        // 在跨越厂商边界之前，先持久化幂等锚点。
                        // 并发的请求，或在上报结果未知之后重试的请求，
                        // 会看到这一行，从而永远不会再买第二次生成。
                        return persistSubmissionIntent(command, channel, reservation)
                                .flatMap(intent -> {
                                    if (!intent.created()) {
                                        return Mono.just(TaskView.of(intent.task()));
                                    }
                                    return adapter.submit(upstreamRequest)
                                            .onErrorResume(error -> releaseRejectedSubmit(
                                                    intent.task(), reservation, error))
                                            .flatMap(submitted -> attachSubmittedTask(
                                                    intent.task(), submitted));
                                });
                    });
                });
    }

    /**
     * 在套餐售出了「秒/分钟」速率时强制执行它。
     *
     * <p>没有售出秒数速率的套餐——token 套餐，或每分钟数字实为对话 token 速率的
     * 视频套餐——在此处予以准入：单次生成的秒数已经由
     * {@link #reserve} 所校验的套餐秒数配额所约束，
     * 而依据产品从未售出的速率去拒绝，等于凭空发明一个上限。
     * {@code PackageSecondRateLimitService} 自行解析套餐的维度与速率，
     * 因此秒数绝不会被当作 token 计费。
     */
    private Mono<Void> admitSecondRate(SubmitCommand command) {
        return packageSecondRateLimitService.admit(
                command.apiKey().packageInstanceId(), command.apiKey().userId());
    }

    /**
     * 在厂商直接拒绝该次提交时释放预留。
     *
     * <p>只有上游显式的 4xx 拒绝才是确定性的拒绝：厂商拒绝了该请求、
     * 没有任何生成被接受，因此没有消耗任何秒数，
     * 而把客户余额扣住整整一个任务 TTL 就是在撒谎。HTTP 408 属于超时形态，
     * 它与所有 5xx、客户端/网络超时、连接重置以及 2xx 协议失败一样保持预留，
     * 因为厂商可能已经接受了一次响应始终未到达的提交；
     * 释放一个结果未知的预留，会让重试针对同一套餐再买第二次生成，
     * 而第一次仍在运行。
     */
    private <T> Mono<T> releaseRejectedSubmit(
            VideoTaskRepository.TaskRow task,
            QuotaService.Reservation reservation,
            Throwable error) {
        if (!isDefiniteUpstreamRejection(error)) {
            return Mono.error(error);
        }
        log.warn("video_submit_rejected reservationId={} reason={}",
                reservation.id(), error.getMessage());
        return taskRepository.completeTerminal(
                        task.requestId(), "CANCELLED", 0, "upstream_rejected", null)
                .onErrorResume(persistenceError -> {
                    log.warn("video_submit_rejection_persist_failed requestId={} error={}",
                            task.requestId(), persistenceError.getMessage());
                    return Mono.empty();
                })
                .then(quotaService.release(
                        reservation.id(), QuotaService.ReleaseReason.UPSTREAM_FAILURE))
                // 释放失败不得掩盖提交失败的原因：
                // 调用方需要看到的是厂商的显式拒绝。
                .onErrorResume(releaseError -> {
                    log.warn("video_submit_release_failed reservationId={} error={}",
                            reservation.id(), releaseError.getMessage());
                    return Mono.empty();
                })
                .then(Mono.error(error));
    }

    /**
     * 只有当上游显式拒绝了该请求时，供应商状态才是可释放的。
     * 协议失败被表示为 502，必须保持预留，因为即便响应体在其他方面格式良好，
     * 厂商的接受状态也无从得知。
     */
    static boolean isDefiniteUpstreamRejection(Throwable error) {
        return error instanceof ProviderAdapter.ProviderException provider
                && provider.status() >= 400
                && provider.status() < 500
                && provider.status() != 408;
    }

    private Mono<QuotaService.Reservation> reserve(SubmitCommand command) {
        Instant expiresAt = Instant.now().plus(TASK_TTL).plus(RESERVATION_GRACE);
        return quotaService.reserve(new QuotaService.ReserveCommand(
                UUID.randomUUID(),
                command.requestId(),
                command.apiKey().tenantId(),
                command.apiKey().userId(),
                command.apiKey().apiKeyId(),
                command.modelAlias(),
                command.durationSeconds(),
                expiresAt,
                command.durationSeconds(),
                0,
                DIMENSION));
    }

    /**
     * 在调用非幂等的厂商 API 之前，创建持久化的 request id 锚点。
     *
     * <p>如果插入在唯一键竞争中落败，或者其结果未知但随后能读到该行，
     * 则调用方就是一次重放，绝不能向上游提交。若两种结果都无法证明，
     * 操作就 fail-closed，同时预留仍然保持。
     */
    private Mono<SubmissionIntent> persistSubmissionIntent(
            SubmitCommand command,
            VideoGenerationAdapter.Channel channel,
            QuotaService.Reservation reservation) {
        VideoTaskRepository.NewTask task = new VideoTaskRepository.NewTask(
                UUID.randomUUID(),
                command.requestId(),
                reservation.id(),
                command.apiKey().tenantId(),
                command.apiKey().userId(),
                command.apiKey().apiKeyId(),
                command.apiKey().packageInstanceId(),
                command.modelAlias(),
                channel.providerId(),
                channel.id(),
                channel.upstreamModel(),
                command.resolutionTier(),
                command.durationSeconds(),
                command.durationSeconds(),
                null,
                "SUBMITTED",
                Instant.now().plus(TASK_TTL));
        return taskRepository.insert(task)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("video_task_insert_failed")))
                .then(taskRepository.findByRequestId(command.requestId()))
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "video_task_read_after_insert_failed")))
                .map(inserted -> new SubmissionIntent(inserted, true))
                .onErrorResume(error -> existingSubmissionIntent(command, error));
    }

    private Mono<SubmissionIntent> existingSubmissionIntent(
            SubmitCommand command, Throwable insertionError) {
        return taskRepository.findByRequestId(command.requestId())
                .flatMap(existing -> existing.userId().equals(command.apiKey().userId())
                        && existing.tenantId().equals(command.apiKey().tenantId())
                        ? Mono.just(new SubmissionIntent(existing, false))
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "video_request_id_conflict")))
                .switchIfEmpty(Mono.error(insertionError));
    }

    private Mono<TaskView> attachSubmittedTask(
            VideoTaskRepository.TaskRow intent,
            VideoGenerationAdapter.SubmittedTask submitted) {
        return taskRepository.attachUpstreamTaskId(
                        intent.requestId(), submitted.upstreamTaskId())
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "video_task_upstream_attach_failed")))
                .then(taskRepository.findByRequestId(intent.requestId()))
                .filter(task -> submitted.upstreamTaskId().equals(task.upstreamTaskId()))
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "video_task_upstream_attach_failed")))
                .map(TaskView::of)
                .doOnError(error -> log.error(
                        "video_task_upstream_attach_unknown requestId={} upstreamTaskId={} error={}",
                        intent.requestId(), submitted.upstreamTaskId(), error.getMessage()));
    }

    /**
     * 返回任务的当前状态，并在任务未进入终态时向上游轮询。
     *
     * <p>计费决策就发生在轮询处，因此「恰好一次」的守卫也在这里。
     */
    public Mono<TaskView> status(ApiKeyService.ApiKeyContext apiKey, String requestId) {
        return ownedTask(apiKey, requestId)
                .flatMap(task -> {
                    if (task.terminal()) {
                        return compensateTerminalIfNeeded(task).thenReturn(TaskView.of(task));
                    }
                    if (task.upstreamTaskId() == null || task.upstreamTaskId().isBlank()) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "video_submission_outcome_unknown"));
                    }
                    return pollUpstream(task).map(TaskView::of);
                });
    }

    /**
     * 解析一次已完成生成的厂商位置。
     *
     * <p>客户端收到的 {@code outputUrl} 就指向这里。厂商自己的
     * URL 留在网关之后：它可能绑定平台的凭证，也可能过期，
     * 两者都不属于客户端可见的契约。
     */
    public Mono<String> contentUrl(ApiKeyService.ApiKeyContext apiKey, String requestId) {
        return ownedTask(apiKey, requestId).flatMap(task -> {
            if (!"SUCCEEDED".equals(task.status()) || task.outputUrl() == null) {
                // 任务存在但没有可取的内容：仍在运行、已失败或
                // 已释放。409 表达的是「现在不行 / 不是这一个」，
                // 而不是假装该 id 未知。
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "video_output_not_ready"));
            }
            return compensateTerminalIfNeeded(task).thenReturn(task.outputUrl());
        });
    }

    /**
     * 加载调用方自己拥有的任务。
     *
     * <p>任务 id 不是一种能力凭证：他人的任务被报告为不存在，
     * 而不是被禁止，因此该端点无法被用来探测哪些 id 存在。
     */
    private Mono<VideoTaskRepository.TaskRow> ownedTask(
            ApiKeyService.ApiKeyContext apiKey, String requestId) {
        if (apiKey == null || requestId == null || requestId.isBlank()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_video_request_id"));
        }
        return taskRepository.findByRequestId(requestId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "video_task_not_found")))
                .flatMap(task -> task.userId().equals(apiKey.userId())
                        && task.tenantId().equals(apiKey.tenantId())
                        ? Mono.just(task)
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "video_task_not_found")));
    }

    /** 向上游轮询一次，若因此产生了终态则完成结算。 */
    Mono<VideoTaskRepository.TaskRow> pollUpstream(VideoTaskRepository.TaskRow task) {
        if (task.upstreamTaskId() == null || task.upstreamTaskId().isBlank()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "video_submission_outcome_unknown"));
        }
        return routeRepository
                .candidates(ownerContext(task), task.modelCode(), VIDEO_CAPABILITY_JSON)
                .filter(channel -> channel.id().equals(task.channelId()))
                .filter(channel -> adapterRegistry.supports(channel.providerType()))
                .next()
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "video_channel_unavailable")))
                .flatMap(channel -> {
                    VideoGenerationAdapter adapter = adapterRegistry.forProviderType(
                            channel.providerType());
                    return adapter.poll(upstreamRequest(task, channel), task.upstreamTaskId());
                })
                .flatMap(status -> apply(task, status));
    }

    private record SubmissionIntent(VideoTaskRepository.TaskRow task, boolean created) { }

    /**
     * 应用一个上游状态：记录进度，或执行一次性的终态转换与结算。
     *
     * <p>有两个性质必须同时成立，而本方法的第一个版本只做到了其中一个。
     *
     * <p><b>只结算一次。</b>带守卫的终态转换就是认领：看到
     * {@code rowsUpdated == 1} 的调用方是唯一会结算的调用方，因此对已完成
     * 任务轮询任意多次都只计费一次。若改为在转换之前结算，并发的轮询方
     * 就可能去结算另一个调用方已经结算过的预留，而它的拒绝随后会被看成失败，
     * 从而中断一个实际上已经付款的任务。
     *
     * <p><b>绝不交付未付费的内容。</b>过去终态转换就是最终结论，因此一次失败的
     * 结算会留下一个 SUCCEEDED 任务，其输出可被获取、却既未计费也无法再计费——
     * 转换守卫拒绝运行第二次。现在由胜出者做补偿：
     * 若它自己的扣费被拒绝，任务就以 FAILED 关闭并带上
     * {@link #SETTLEMENT_REFUSED}，于是客户被告知该生成不可用，
     * 而不是拿到一段平台从未计费的视频。
     *
     * <p><b>记录经济性。</b>同一个胜出者写入用量证据
     * （{@link VideoEconomicsEvidenceWriter}）——而且只有胜出者可以写，因为带守卫的
     * UPDATE 正是使该写入恰好一次的认领。它在扣费之后运行，
     * 因此这一行记录的是一次真实存在的结算：一次套餐拒绝付款的生成
     * 不是经营事实，而是一个失败的任务。写入失败不会撤销扣费；
     * 它会上报给调用方并记录日志，因为丢失观测绝不能被误认为丢失了钱。
     */
    Mono<VideoTaskRepository.TaskRow> apply(
            VideoTaskRepository.TaskRow task, VideoGenerationAdapter.TaskStatus status) {
        if (!status.terminal()) {
            return taskRepository.markRunning(task.requestId())
                    .then(taskRepository.findByRequestId(task.requestId()))
                    .flatMap(Mono::just);
        }
        String terminalStatus = switch (status.state()) {
            case SUCCEEDED -> "SUCCEEDED";
            case CANCELLED -> "CANCELLED";
            default -> "FAILED";
        };
        int billedSeconds = chargedSeconds(task, status.billedSeconds());
        // 只有成功的任务才可以携带位置；数据库检查强制执行这一点，
        // 因此为失败上报位置的厂商无法造出一行看起来可获取的记录。
        String outputUrl = "SUCCEEDED".equals(terminalStatus) ? status.outputUrl() : null;
        return taskRepository
                .completeTerminal(
                        task.requestId(), terminalStatus, billedSeconds, status.errorCategory(),
                        outputUrl)
                .flatMap(updated -> updated == 1L
                        ? settle(task, billedSeconds)
                                .onErrorResume(UnsettleableVideoTaskException.class, refused -> {
                                    log.warn("video_task_settlement_refused requestId={} reason={}",
                                            task.requestId(), refused.getMessage());
                                    return taskRepository
                                            .markSettlementRefused(task.requestId())
                                            .then(Mono.error(refused));
                                })
                                .then(Mono.defer(() -> economicsEvidence.write(
                                        task, terminalStatus, billedSeconds)))
                                .then(Mono.just(true))
                        : Mono.just(false))
                .then(taskRepository.findByRequestId(task.requestId()));
    }

    /** 一次无法完成的结算：该任务必须被拒绝交付，而不是交付出去。 */
    static final class UnsettleableVideoTaskException extends RuntimeException {
        UnsettleableVideoTaskException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 要计费的秒数：厂商实际计费的秒数，并以所请求的秒数为上限。
     *
     * <p>这个上限是针对「厂商产出少于所订」这一常见情况的有意收窄。
     * 厂商报告比请求<em>更多</em>的秒数是违约，不是四舍五入的差异，
     * 因此会被记录日志，而不是被悄悄吸收。
     */
    private int chargedSeconds(VideoTaskRepository.TaskRow task, Integer billed) {
        if (billed == null || billed <= 0) {
            return 0;
        }
        if (billed > task.requestedSeconds()) {
            log.warn("video_vendor_billed_more_than_requested requestId={} requested={} billed={}",
                    task.requestId(), task.requestedSeconds(), billed);
            return task.requestedSeconds();
        }
        return billed;
    }

    /**
     * 按厂商将计费的秒数结算该预留。
     *
     * <p>秒数以预留的单位携带，因此配额账本对待它们与 token 完全一致：
     * 套餐余额与按模型的秒数配额都从 reserved 转为 used。
     *
     * <p>拒绝会被包装，以便调用方能区分「这个预留已无法再计费」——
     * 这种状态必须中止交付——与可能被下一次轮询或恢复清扫解决的
     * 瞬时数据库故障。它会向上传播，使客户端看到 5xx 并重试，
     * 而不是拿到一段平台从未计费的视频。
     */
    private Mono<Void> settle(VideoTaskRepository.TaskRow task, int chargedSeconds) {
        return quotaService.settle(
                        task.reservationId(),
                        new QuotaService.TokenUsage(chargedSeconds, 0, chargedSeconds),
                        QuotaService.UsageQuality.ACTUAL)
                // 套餐实际被计费的秒数正是已售出的「秒/分钟」速率所统计的对象，
                // 与对话结算喂养信用速率完全一致。它运行在一次已经提交的结算的
                // 尾部并立即返回，因此既不会延迟、也不会使客户看到的扣费失败。
                .doOnNext(settlement -> packageSecondRateLimitService.record(
                        task.packageInstanceId(), settlement.chargedTokens()))
                .onErrorMap(error -> error instanceof ResponseStatusException
                        ? new UnsettleableVideoTaskException(
                                "video_task_unsettleable:" + error.getMessage(), error)
                        : error)
                .then();
    }

    /**
     * 按「已经不可能有结果」收尾一个任务，并释放它的预留。
     *
     * <p>它由 {@link #recoverExpired(VideoTaskRepository.TaskRow)} 在**最后一次
     * 轮询之后仍无终态**时调用。这一步曾经被有意排除在自动路径之外，理由是
     * 「已存在的任务行意味着 submit 已经返回了上游 task id，而非终态轮询、
     * 轮询报错或渠道缺失都无法证明拒绝」。那个理由依然成立——它解释了为什么
     * 不能在第一时间释放。但它没有上界，而没有上界的持有会变成一个客户看不见
     * 的故障：线上一个用户因此被冻住了全部剩余视频额度 17 天。
     * 见 {@link #recoverExpired(VideoTaskRepository.TaskRow)} 的说明。
     */
    Mono<Void> releaseExpired(VideoTaskRepository.TaskRow task) {
        return taskRepository
                .completeTerminal(task.requestId(), "CANCELLED", 0, "gateway_task_expired", null)
                .flatMap(updated -> updated == 1L
                        ? quotaService.release(
                                task.reservationId(), QuotaService.ReleaseReason.RECOVERY_TIMEOUT)
                        : Mono.empty());
    }

    /**
     * 对一个已过期但仍处于打开状态的任务做最后一次轮询，然后**收尾**。
     *
     * <p>先轮询的理由与音频路径相同：一个在客户端停止轮询之后才完成的任务
     * 必须被计费，而不是被当成失败白送出去。轮询把它推到终态时，
     * 这里的 UPDATE 会被状态守卫拒绝，处置权归那个调用方。
     *
     * <p><b>这里曾经是「永远保持」。</b>理由是网关无法证明一次已被接受的生成
     * 没有运行过，释放会让客户端重试并针对同一套餐余额买第二次生成。
     * 那个理由依然成立——它解释了为什么不能在第一时间释放。
     *
     * <p>但它没有上界，而一个没有上界的「保守」不是保守：线上用户
     * {@code 23837b95} 的 5 条视频预留从 2026-09-23 起一直停在 {@code RESERVED}，
     * 合计 50 秒，恰好等于他视频套餐**全部**的剩余额度（190 总量、140 已用）。
     * 也就是说他在那 17 天里一秒视频都生成不出来，而预留自己的
     * {@code expires_at} 早就过了。把记账风险无限期转嫁成一个客户看不见的故障，
     * 比偶尔少收一次钱的代价大得多。
     *
     * <p>所以现在的规则是**有上界的持有**：任务已经过了 TTL（这正是本方法的
     * 调用条件），最后一次轮询之后仍非终态或仍未知，就按「已经不可能有结果」
     * 收尾。代价可算——最多一次生成的收入，且只在我们确实已经付过厂商时
     * 才是真的损失。
     */
    Mono<Void> recoverExpired(VideoTaskRepository.TaskRow task) {
        return pollUpstream(task)
                .flatMap(current -> {
                    if (current.terminal()) {
                        return Mono.<Void>empty();
                    }
                    log.warn("video_task_recovery_non_terminal requestId={} status={} "
                                    + "action=release_expired",
                            task.requestId(), current.status());
                    return releaseExpired(task);
                })
                .onErrorResume(error -> {
                    log.warn("video_task_recovery_unknown requestId={} error={} "
                                    + "action=release_expired",
                            task.requestId(), error.getClass().getSimpleName());
                    return releaseExpired(task);
                });
    }

    /**
     * 返回视频预留仍为 {@code RESERVED} 的终态任务。
     *
     * <p>通用预留恢复任务有意跳过视频维度，因为仅凭一条预留
     * 无法证明某次上游生成被拒绝了。终态视频任务是此处所需的更窄的证据：
     * 它同时告诉我们供应商结果已知，以及哪种补偿是有效的。仓库还会过滤
     * 预留状态，使已被并发轮询处理过的任务不会被重放。
     */
    Flux<VideoTaskRepository.TaskRow> terminalTasksWithReservedQuota(int limit) {
        return taskRepository.findTerminalWithReservedQuota(limit);
    }

    /**
     * 为终态视频任务重试配额转换。
     *
     * <p>成功或厂商失败的任务按供应商报告的秒数结算
     * （包括零，它把占用转成一条已结算的零用量记录）。已取消的任务
     * 只针对供应商 4xx 提交拒绝所写入的显式 {@code upstream_rejected} 标记才释放。
     * 由提交/轮询结果未知导致的取消会被有意放过：它不是供应商没有运行该视频的证明。
     * 错误会传播给被调度的调用方，好让下一轮清扫重试；没有任何错误路径
     * 会把未知任务变成释放。
     */
    Mono<Void> compensateTerminal(VideoTaskRepository.TaskRow task) {
        if (task == null || !task.terminal()) {
            return Mono.empty();
        }
        if ("CANCELLED".equals(task.status())) {
            if (!"upstream_rejected".equals(task.errorCategory())
                    || !hasBilledSeconds(task, 0)) {
                return Mono.empty();
            }
            return quotaService.release(
                    task.reservationId(), QuotaService.ReleaseReason.UPSTREAM_FAILURE);
        }
        if (!"SUCCEEDED".equals(task.status()) && !"FAILED".equals(task.status())) {
            return Mono.empty();
        }
        // 结算被拒的任务已被终态路径有意做成不可获取。
        // 它的配额状态需要运维对账，而不是一次自动的
        // 零用量结算——那可能悄悄送掉一次本可计费的生成。
        if (SETTLEMENT_REFUSED.equals(task.errorCategory())) {
            return Mono.empty();
        }
        if (task.billedSeconds() == null || task.billedSeconds() < 0) {
            log.warn("video_terminal_compensation_skipped requestId={} reason=missing_billed_seconds",
                    task.requestId());
            return Mono.empty();
        }
        int billedSeconds = task.billedSeconds();
        return settle(task, billedSeconds)
                .then(Mono.defer(() -> economicsEvidence.write(
                        task, task.status(), billedSeconds)));
    }

    /**
     * 确保终态任务在其配额占用仍未解决时不会被暴露出来。
     *
     * <p>定时清扫在设计上就是最终一致的，但状态/内容调用位于交付路径上，
     * 可以同步地弥合这段空档。先读取预留状态可避免重放已结算的任务；
     * 缺行是仓库报出的错误，因此 fail-closed。
     */
    private Mono<Void> compensateTerminalIfNeeded(VideoTaskRepository.TaskRow task) {
        if (task == null || !task.terminal()) {
            return Mono.empty();
        }
        return taskRepository.reservationIsReserved(task.reservationId())
                .flatMap(reserved -> reserved ? compensateTerminal(task) : Mono.empty());
    }

    private boolean hasBilledSeconds(VideoTaskRepository.TaskRow task, int expected) {
        return task.billedSeconds() != null && task.billedSeconds() == expected;
    }

    Flux<VideoTaskRepository.TaskRow> openTasks(int limit) {
        return taskRepository.findOpen(limit);
    }

    private VideoGenerationAdapter.TaskRequest taskRequest(
            SubmitCommand command, VideoGenerationAdapter.Channel channel) {
        return new VideoGenerationAdapter.TaskRequest(
                channel,
                command.requestId(),
                command.prompt(),
                command.inputImageUrl(),
                command.durationSeconds(),
                command.resolutionTier(),
                command.aspectRatio(),
                command.withAudio(),
                60_000);
    }

    private VideoGenerationAdapter.TaskRequest upstreamRequest(
            VideoTaskRepository.TaskRow task, VideoGenerationAdapter.Channel channel) {
        return new VideoGenerationAdapter.TaskRequest(
                channel,
                task.requestId(),
                // prompt 不会被持久化，因此轮询只需要路由相关字段。
                "resume",
                null,
                task.requestedSeconds(),
                task.resolutionTier(),
                null,
                false,
                60_000);
    }

    /**
     * 为轮询重建刚好够用的 key 上下文：任务在提交时已经通过准入，
     * 因此只需要租户与用户身份就能重新解析渠道。
     */
    private ApiKeyService.ApiKeyContext ownerContext(VideoTaskRepository.TaskRow task) {
        return new ApiKeyService.ApiKeyContext(
                task.apiKeyId(), task.tenantId(), task.userId(),
                task.packageInstanceId(),
                task.modelCode() == null ? null : java.util.Set.of(task.modelCode()));
    }

    private void validate(SubmitCommand command) {
        if (command == null
                || command.requestId() == null || command.requestId().isBlank()
                || command.requestId().length() > 128
                || command.apiKey() == null
                || command.modelAlias() == null || command.modelAlias().isBlank()
                || command.prompt() == null || command.prompt().isBlank()
                || command.prompt().length() > 8000
                || command.durationSeconds() < VideoGenerationAdapter.PLATFORM_MIN_DURATION_SECONDS
                || command.durationSeconds() > VideoGenerationAdapter.PLATFORM_MAX_DURATION_SECONDS
                || !VideoGenerationAdapter.PLATFORM_RESOLUTION_TIERS.contains(
                        command.resolutionTier())
                || !validAspectRatio(command.aspectRatio())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_video_request");
        }
    }

    private boolean validAspectRatio(String aspectRatio) {
        return aspectRatio == null
                || aspectRatio.isBlank()
                // 空白画幅由各适配器按自己的默认值（16:9）补齐，
                // 因此空值合法、而白名单之外的取值不合法。
                || VideoGenerationAdapter.PLATFORM_ASPECT_RATIOS.contains(aspectRatio);
    }

    public record SubmitCommand(
            String requestId,
            ApiKeyService.ApiKeyContext apiKey,
            String modelAlias,
            String prompt,
            String inputImageUrl,
            int durationSeconds,
            String resolutionTier,
            String aspectRatio,
            boolean withAudio) {

        /** V110 档位解析后的命令：别名换成内部执行别名，其余字段不动。 */
        SubmitCommand withModelAlias(String alias) {
            return new SubmitCommand(
                    requestId, apiKey, alias, prompt, inputImageUrl,
                    durationSeconds, resolutionTier, aspectRatio, withAudio);
        }
    }

    /**
     * V110 合并模型映射的镜像：任务记录里存的是内部执行别名
     * （轮询与计费都要靠它），向客户端回显时翻译成它提交的合并模型名。
     * 与 {@code gateway.video_client_tier_mapping} 的种子逐行对应，
     * 由 {@code VideoConsolidatedModelMappingTest} 钉住两边一致。
     */
    static final Map<String, String> CLIENT_MODEL_BY_INTERNAL_ALIAS = Map.of(
            "video-budget", "video-minimax",
            "video-minimax", "video-minimax");

    /** 调用方所看到的内容。绝不暴露厂商的 task id 或原始错误文本。 */
    public record TaskView(
            String requestId,
            String model,
            String status,
            int requestedSeconds,
            Integer billedSeconds,
            String resolutionTier,
            String outputUrl,
            String errorCategory) {

        /** 已完成的生成可在本网关上从何处获取。 */
        static final String CONTENT_PATH_PREFIX = "/v1/videos/";

        static TaskView of(VideoTaskRepository.TaskRow task) {
            return new TaskView(
                    task.requestId(),
                    CLIENT_MODEL_BY_INTERNAL_ALIAS.getOrDefault(
                            task.modelCode(), task.modelCode()),
                    task.status(),
                    task.requestedSeconds(),
                    task.billedSeconds(),
                    task.resolutionTier(),
                    // 只有成功且存有厂商位置的任务才可获取。
                    "SUCCEEDED".equals(task.status()) && task.outputUrl() != null
                            ? CONTENT_PATH_PREFIX + task.requestId() + "/content"
                            : null,
                    task.errorCategory());
        }
    }
}
