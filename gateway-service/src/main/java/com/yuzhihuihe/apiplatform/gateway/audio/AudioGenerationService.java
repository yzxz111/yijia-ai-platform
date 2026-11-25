package com.yuzhihuihe.apiplatform.gateway.audio;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 音频生成：语音转写、配音合成、口型替换。
 *
 * <p>本类把三个能力接到同一条**预留 → 调用 → 结算 → 释放**链路上。
 * 那条链路是网关既有的、按单位计费的那一套（与生图路径共用
 * {@link QuotaService}），而音频与生图的差别只在两处：
 *
 * <ul>
 *   <li>计费单位是**秒**而不是张，因此维度是 {@code AUDIO_SECOND}；
 *   <li>时长有两个方向——转写按**输入**音频秒计费，合成与换口型按**产出**
 *       秒计费。同一个维度承载两个方向是可以的，因为费率行是按别名定价的，
 *       而每个别名只做一个方向。</li>
 * </ul>
 *
 * <p><b>幂等键就是 request id。</b>重放同一个 request id 会返回既有结果，
 * 而不是再买一次调用。这条规则对同步能力同样必要：一次同步调用在网关超时
 * 之后仍可能已经在上游被计费，而客户端会重试。因此三个能力都要在跨越
 * 厂商边界**之前**落下锚点，这与
 * {@link com.yuzhihuihe.apiplatform.gateway.image.ImageGenerationService}
 * 的做法相同。
 *
 * <p><b>预留秒数由调用方给出，而它是这一整套里最需要解释的一个输入。</b>
 * 按秒计费的维度无法像按张那样知道「这次要花几个单位」：
 * 音频有多长，只有音频自己知道。因此：
 *
 * <ul>
 *   <li>转写——客户提交音频位置时同时声明它的秒数。这与
 *       {@code BailianAudioTranscriptionAdapter} 在同步路径上
 *       「没有时长字段时回退到预留秒数」是同一件事的两面：客户声明多久，
 *       就预留多久，也按多久结算。</li>
 *   <li>合成——客户提交文案，网关按
 *       {@link AudioSynthesisAdapter#estimateSecondsFromCharacters} 估算，
 *       这个常量与费率卡的成本折算共用，因此毛利不随文案密度漂移。</li>
 *   <li>换口型——预留取音频与视频两者中较短的时长，因为厂商默认按较短的
 *       一方截断；客户若开启 {@code extendVideo}，则取音频长度。</li>
 * </ul>
 *
 * <p>声明得比实际长只会多预留一阵子，结算会把差额释放掉；
 * 声明得比实际短则会让一次调用的成本超出预留上限，而
 * {@code ck_model_usage_price_margin} 与结算路径都以预留为上限，
 * 因此那是**少收**的方向。服务层在结算时以预留为硬上限，
 * 正是为了让一次谎报无法变成一笔亏账。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class AudioGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AudioGenerationService.class);

    /** 与 V92 的词表、V93 的费率行一致。 */
    static final String DIMENSION = "AUDIO_SECOND";

    /** 三个能力的能力名，供路由查询与适配器匹配使用。 */
    static final String CAPABILITY_TRANSCRIPTION_JSON = "[\"AUDIO_TRANSCRIBE\"]";
    static final String CAPABILITY_SYNTHESIS_JSON = "[\"AUDIO_SYNTHESIS\"]";
    static final String CAPABILITY_LIP_SYNC_JSON = "[\"LIP_SYNC\"]";

    /**
     * 幂等锚点的存活时长。与生图路径同为 30 分钟：它必须长于任何一次上游调用
     * 的最坏情况，又必须短到一次被遗弃的预留能被及时回收。
     */
    static final Duration TASK_TTL = Duration.ofMinutes(30);

    /**
     * 单次请求允许的最大音频秒数。
     *
     * <p>厂商对换口型的输入限制是 2 &lt; 时长 &lt; 120 秒，而转写支持长音频。
     * 这里的上限取转写侧能接受的一个保守值：一次超过它的请求几乎一定是
     * 客户少报了秒数，而按一个荒谬的秒数预留会把整个套餐额度锁住。
     */
    static final int MAX_SECONDS = 12 * 60 * 60;

    /** 稳定的失败分类，供日志与支持使用，绝不是厂商的原始文本。 */
    static final String UPSTREAM_REJECTED = "upstream_rejected";
    static final String UPSTREAM_FAILED = "upstream_failed";

    /**
     * 错误分类：厂商已产出、但网关无法向客户结算的一次调用。
     *
     * <p>与 {@code gateway_task_expired} 不同：产出确实存在，而客户被拒绝交付，
     * 这是需要对账的事件，而不是一个单纯超时的任务。与视频路径的
     * {@code VideoGenerationService.SETTLEMENT_REFUSED} 是同一个分类名，
     * 因为运营报表要能把两者放在同一栏里看。
     */
    static final String SETTLEMENT_REFUSED = "gateway_settlement_refused";

    /**
     * 轮询时用来构造适配器请求的占位媒体位置。
     *
     * <p>{@code LipSyncAdapter.poll} 只读渠道与上游 task id，调用方的输入媒体
     * 不参与轮询。但 {@code LipSyncRequest} 的构造器会校验两个 URL 是绝对的
     * http(s)，因此这里必须给出形式上合法的东西——用 {@code .invalid} 顶级域
     * （RFC 2606 保留，永不解析）而不是一个看起来像真的地址，
     * 免得它被误当成一个可用的默认值。
     */
    private static final String POLL_PLACEHOLDER_URL = "https://poll-placeholder.invalid/media";

    private final QuotaService quotaService;
    private final AudioTaskRepository taskRepository;
    private final AudioRouteRepository routeRepository;
    private final AudioAdapterRegistry adapterRegistry;
    private final AudioEconomicsEvidenceWriter economicsEvidence;
    private final PackageSecondRateLimitService packageUnitRateLimitService;
    private final ObjectMapper objectMapper;

    @Autowired
    AudioGenerationService(
            QuotaService quotaService,
            AudioTaskRepository taskRepository,
            AudioRouteRepository routeRepository,
            AudioAdapterRegistry adapterRegistry,
            AudioEconomicsEvidenceWriter economicsEvidence,
            ObjectMapper objectMapper,
            PackageSecondRateLimitService packageUnitRateLimitService) {
        this.quotaService = quotaService;
        this.taskRepository = taskRepository;
        this.routeRepository = routeRepository;
        this.adapterRegistry = adapterRegistry;
        this.objectMapper = objectMapper;
        // 有意不做 null 兜底：一个「悄悄不写证据」的写入器正是本类此前让
        // 音频成本对平台完全不可见的那个缺陷，因此缺失的协作者必须是启动期失败。
        // 这与 ImageGenerationService:161-163 是同一条取舍。
        this.economicsEvidence = economicsEvidence;
        // 与生图路径同一取舍：没有售出每分钟速率时，额度本身仍然有效。
        this.packageUnitRateLimitService = packageUnitRateLimitService == null
                ? PackageSecondRateLimitService.unlimited() : packageUnitRateLimitService;
    }

    /**
     * 没有已售出的每分钟秒数速率需要强制执行：套餐的秒数额度依然有效。
     *
     * <p>存在是为了让不关心经济性证据的测试不必构造第四个协作者；
     * 它仍然要求一个非 null 的写入器，因为缺失写入器必须是启动期失败。
     */
    AudioGenerationService(
            QuotaService quotaService,
            AudioTaskRepository taskRepository,
            AudioRouteRepository routeRepository,
            AudioAdapterRegistry adapterRegistry,
            AudioEconomicsEvidenceWriter economicsEvidence,
            ObjectMapper objectMapper) {
        this(quotaService, taskRepository, routeRepository, adapterRegistry,
                economicsEvidence, objectMapper,
                PackageSecondRateLimitService.unlimited());
    }

    // -----------------------------------------------------------------------
    // 语音转写
    // -----------------------------------------------------------------------

    /**
     * 转写一段音频。
     *
     * <p>request id 就是幂等键：重放它返回既有结果，而不是再买一次转写。
     */
    public Mono<TranscriptionView> transcribe(TranscribeCommand command) {
        validateTranscribe(command);
        return taskRepository.findByRequestId(command.requestId())
                .flatMap(existing -> replayTranscription(existing, command))
                .switchIfEmpty(Mono.defer(() -> transcribeNew(command)));
    }

    private Mono<TranscriptionView> transcribeNew(TranscribeCommand command) {
        // 已售出的「秒/分钟」速率在此处即作为准入条件，早于秒数被预留、
        // 也早于任何渠道被查找：套餐的分钟额度付不起的一次转写，
        // 既不得占用配额，也不得触达厂商。
        return requireBillingUnit(command.modelAlias())
                .then(admitUnitRate(command.apiKey()))
                .then(Mono.defer(() -> routeRepository
                        .candidates(command.apiKey(), command.modelAlias(),
                                CAPABILITY_TRANSCRIPTION_JSON)
                        .filter(route -> adapterRegistry.supportsTranscription(route.providerType()))
                        .next()
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, "audio_channel_unavailable")))
                        .flatMap(route -> transcribeAtRoute(command, route))));
    }

    private Mono<TranscriptionView> transcribeAtRoute(
            TranscribeCommand command, AudioRouteRepository.AudioRoute route) {
        AudioTranscriptionAdapter adapter = adapterRegistry.transcriptionFor(route.providerType());
        AudioTranscriptionAdapter.TranscriptionRequest upstream =
                new AudioTranscriptionAdapter.TranscriptionRequest(
                        transcriptionChannel(route),
                        command.requestId(),
                        command.audioUrl(),
                        command.audioFormat(),
                        command.sampleRate(),
                        command.languageHints(),
                        command.reservedSeconds(),
                        route.timeoutOrDefault());
        // 供应商约束是确定性的，必须在存在任何秒数占用之前校验。
        adapter.validateSubmission(upstream);
        return reserve(command.apiKey(), command.modelAlias(),
                        command.requestId(), command.reservedSeconds())
                .flatMap(reservation -> {
                    if (!DIMENSION.equals(reservation.usageDimension())) {
                        // 该套餐授予的是 token 或张数，而不是音频秒。对它计费会用
                        // 秒数去消耗另一种单位的余额，因此拒绝。
                        return quotaService.release(
                                        reservation.id(),
                                        QuotaService.ReleaseReason.CLIENT_CANCELLED)
                                .then(Mono.error(new ResponseStatusException(
                                        HttpStatus.UNPROCESSABLE_ENTITY,
                                        "audio_requires_audio_denominated_package")));
                    }
                    return persistIntent(command, route, reservation,
                                    AudioTaskRepository.KIND_TRANSCRIPTION)
                            .flatMap(intent -> intent.created()
                                    ? adapter.transcribe(upstream)
                                            .onErrorResume(error -> releaseRejected(
                                                    intent.task(), reservation, error))
                                            .flatMap(result -> completeTranscription(
                                                    intent.task(), result))
                                    : replayTranscription(intent.task(), command));
                });
    }

    /**
     * 把一次成功的转写落库并结算。
     *
     * <p>结算秒数以**预留**为硬上限：厂商报告一个比预留更长的时长只可能来自
     * 客户少报，而按它结算会突破套餐额度。多出来的部分不被收取，
     * 这一点是刻意的——网关宁可少收一次，也不让一次谎报把余额扣穿。
     *
     * <p>转写的产物全部落在 {@code result_payload} 上，{@code output_url} 保持
     * 为空：这个能力交出的是**文本与时间戳**，不是一个可下载的位置。
     * 把它塞进 output_url 会让下游以为那里有一个文件可拉。
     */
    private Mono<TranscriptionView> completeTranscription(
            AudioTaskRepository.TaskRow task,
            AudioTranscriptionAdapter.TranscriptionResult result) {
        int charged = Math.min(result.billedSeconds(), task.reservedSeconds());
        return taskRepository
                .completeTerminal(
                        task.requestId(), "SUCCEEDED", charged, null,
                        serializeTranscription(result), null)
                .flatMap(updated -> updated == 1L
                        ? settle(task, charged)
                                // 证据只在认领了终态转换的那一次写入，与视频、
                                // 图片路径同一条规则：带守卫的 UPDATE 就是认领。
                                .then(economicsEvidence.write(task, "SUCCEEDED", charged))
                                .then(Mono.just(transcriptionView(
                                        task, charged, result)))
                        // 并发的重放赢得了这次终态转换：返回它刚落库的那一行，
                        // 而不是再结算一次。
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "audio_generation_concurrent")));
    }

    private Mono<TranscriptionView> replayTranscription(
            AudioTaskRepository.TaskRow existing, TranscribeCommand command) {
        return Mono.defer(() -> {
            requireSameOwner(existing, command.apiKey(), "audio_request_id_conflict");
            if (!existing.terminal()) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "audio_generation_outcome_unknown"));
            }
            if (!existing.succeeded()) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "audio_generation_previously_failed"));
            }
            // 终态行可能领先于它的配额转换（并发的重放会看到这一点），
            // 因此在把结果交出去之前，先把那次转换补上。
            return compensateTerminalIfNeeded(existing).then(Mono.defer(() -> {
                if (existing.resultPayload() == null) {
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.CONFLICT, "audio_generation_result_not_replayable"));
                }
                return Mono.just(transcriptionView(
                        existing, existing.chargedSeconds(),
                        parseTranscription(existing.resultPayload())));
            }));
        });
    }

    // -----------------------------------------------------------------------
    // 配音合成
    // -----------------------------------------------------------------------

    /**
     * 把一段文案合成为配音。
     *
     * <p>与转写的结构完全相同，只有两处不同：预留秒数由文案估算而来，
     * 以及结果是音频位置而不是文本。
     */
    public Mono<SynthesisView> synthesize(SynthesizeCommand command) {
        validateSynthesize(command);
        return taskRepository.findByRequestId(command.requestId())
                .flatMap(existing -> replaySynthesis(existing, command))
                .switchIfEmpty(Mono.defer(() -> synthesizeNew(command)));
    }

    private Mono<SynthesisView> synthesizeNew(SynthesizeCommand command) {
        return requireBillingUnit(command.modelAlias())
                .then(admitUnitRate(command.apiKey()))
                .then(Mono.defer(() -> routeRepository
                        .candidates(command.apiKey(), command.modelAlias(),
                                CAPABILITY_SYNTHESIS_JSON)
                        .filter(route -> adapterRegistry.supportsSynthesis(route.providerType()))
                        .next()
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, "audio_channel_unavailable")))
                        .flatMap(route -> synthesizeAtRoute(command, route))));
    }

    private Mono<SynthesisView> synthesizeAtRoute(
            SynthesizeCommand command, AudioRouteRepository.AudioRoute route) {
        AudioSynthesisAdapter adapter = adapterRegistry.synthesisFor(route.providerType());
        int reservedSeconds = AudioSynthesisAdapter.estimateSecondsFromCharacters(
                command.text().length());
        AudioSynthesisAdapter.SynthesisRequest upstream =
                new AudioSynthesisAdapter.SynthesisRequest(
                        synthesisChannel(route),
                        command.requestId(),
                        command.text(),
                        command.voice(),
                        command.languageType(),
                        command.audioFormat(),
                        reservedSeconds,
                        route.timeoutOrDefault());
        adapter.validateSubmission(upstream);
        return reserve(command.apiKey(), command.modelAlias(),
                        command.requestId(), reservedSeconds)
                .flatMap(reservation -> {
                    if (!DIMENSION.equals(reservation.usageDimension())) {
                        return quotaService.release(
                                        reservation.id(),
                                        QuotaService.ReleaseReason.CLIENT_CANCELLED)
                                .then(Mono.error(new ResponseStatusException(
                                        HttpStatus.UNPROCESSABLE_ENTITY,
                                        "audio_requires_audio_denominated_package")));
                    }
                    return persistIntent(command, route, reservation,
                                    AudioTaskRepository.KIND_SYNTHESIS)
                            .flatMap(intent -> intent.created()
                                    ? adapter.synthesize(upstream)
                                            .onErrorResume(error -> releaseRejected(
                                                    intent.task(), reservation, error))
                                            .flatMap(result -> completeSynthesis(
                                                    intent.task(), result))
                                    : replaySynthesis(intent.task(), command));
                });
    }

    private Mono<SynthesisView> completeSynthesis(
            AudioTaskRepository.TaskRow task,
            AudioSynthesisAdapter.SynthesisResult result) {
        int charged = Math.min(result.billedSeconds(), task.reservedSeconds());
        return taskRepository
                .completeTerminal(
                        task.requestId(), "SUCCEEDED", charged, null,
                        null, result.audioUrl())
                .flatMap(updated -> updated == 1L
                        ? settle(task, charged)
                                .then(economicsEvidence.write(task, "SUCCEEDED", charged))
                                .then(Mono.just(new SynthesisView(
                                        task.requestId(),
                                        result.audioUrl(),
                                        charged,
                                        // 厂商不交回音频时长，因此这个标记必须一路带到
                                        // 调用方：一条估算的计费在运营报表里必须能与
                                        // 实测的区分开，否则对账时无从判断偏差从哪来。
                                        result.estimatedDuration())))
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "audio_generation_concurrent")));
    }

    private Mono<SynthesisView> replaySynthesis(
            AudioTaskRepository.TaskRow existing, SynthesizeCommand command) {
        return Mono.defer(() -> {
            requireSameOwner(existing, command.apiKey(), "audio_request_id_conflict");
            if (!existing.terminal()) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "audio_generation_outcome_unknown"));
            }
            if (!existing.succeeded()) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "audio_generation_previously_failed"));
            }
            return compensateTerminalIfNeeded(existing).then(Mono.defer(() -> {
                if (existing.outputUrl() == null) {
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.CONFLICT, "audio_generation_result_not_replayable"));
                }
                return Mono.just(new SynthesisView(
                        existing.requestId(),
                        existing.outputUrl(),
                        existing.chargedSeconds(),
                        true));
            }));
        });
    }

    // -----------------------------------------------------------------------
    // 口型替换（唯一的异步能力）
    // -----------------------------------------------------------------------

    /**
     * 提交一次口型替换作业。
     *
     * <p>与另外两个能力的差别是它在**非终态时也会成功返回**：客户拿到的是
     * 一个已受理的作业，随后用 {@link #lipSyncStatus} 轮询。因此这个方法
     * 绝不会阻塞到厂商完成，也绝不会在预留还活着的时候返回结果。
     *
     * <p><b>重放一个仍在运行的作业不是错误。</b>这与视频路径不同，而是刻意的：
     * 一个异步接口的客户端在超时后重试，本来就是想知道「我那次提交怎么样了」，
     * 对它回答 409 会让客户以为提交丢了，于是换一个 request id 再买一次。
     * 因此非终态的重放返回当前状态。
     */
    public Mono<LipSyncView> submitLipSync(LipSyncCommand command) {
        validateLipSync(command);
        return taskRepository.findByRequestId(command.requestId())
                .flatMap(existing -> replayLipSync(existing, command))
                .switchIfEmpty(Mono.defer(() -> lipSyncNew(command)));
    }

    private Mono<LipSyncView> lipSyncNew(LipSyncCommand command) {
        return requireBillingUnit(command.modelAlias())
                .then(admitUnitRate(command.apiKey()))
                .then(Mono.defer(() -> routeRepository
                        .candidates(command.apiKey(), command.modelAlias(),
                                CAPABILITY_LIP_SYNC_JSON)
                        .filter(route -> adapterRegistry.supportsLipSync(route.providerType()))
                        .next()
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, "audio_channel_unavailable")))
                        .flatMap(route -> lipSyncAtRoute(command, route))));
    }

    private Mono<LipSyncView> lipSyncAtRoute(
            LipSyncCommand command, AudioRouteRepository.AudioRoute route) {
        LipSyncAdapter adapter = adapterRegistry.lipSyncFor(route.providerType());
        int reservedSeconds = reservedSecondsFor(command);
        LipSyncAdapter.LipSyncRequest upstream = new LipSyncAdapter.LipSyncRequest(
                lipSyncChannel(route),
                command.requestId(),
                command.videoUrl(),
                command.audioUrl(),
                command.referenceImageUrl(),
                command.extendVideo(),
                reservedSeconds,
                route.timeoutOrDefault());
        adapter.validateSubmission(upstream);
        return reserve(command.apiKey(), command.modelAlias(),
                        command.requestId(), reservedSeconds)
                .flatMap(reservation -> {
                    if (!DIMENSION.equals(reservation.usageDimension())) {
                        return quotaService.release(
                                        reservation.id(),
                                        QuotaService.ReleaseReason.CLIENT_CANCELLED)
                                .then(Mono.error(new ResponseStatusException(
                                        HttpStatus.UNPROCESSABLE_ENTITY,
                                        "audio_requires_audio_denominated_package")));
                    }
                    return persistLipSyncIntent(command, route, reservation, reservedSeconds)
                            .flatMap(intent -> intent.created()
                                    ? adapter.submit(upstream)
                                            .onErrorResume(error -> releaseRejected(
                                                    intent.task(), reservation, error))
                                            .flatMap(submitted -> taskRepository
                                                    .markRunning(intent.task().requestId(),
                                                            submitted.upstreamTaskId(), null)
                                                    .then(taskRepository.findByRequestId(
                                                            intent.task().requestId())))
                                            // map 而不是 flatMap：LipSyncView.of 返回的是
                                            // LipSyncView 本身（见本文件末尾的记录定义），
                                            // 不是 Mono。写成 flatMap 会让编译器去要一个
                                            // Mono<? extends R>，从而以"无法推断类型变量 R"
                                            // 失败，而错误信息不会指出真正的原因。
                                            .map(LipSyncView::of)
                                    : replayLipSync(intent.task(), command));
                });
    }

    private int reservedSecondsFor(LipSyncCommand command) {
        return reservedSecondsForLipSync(
                command.videoSeconds(), command.audioSeconds(), command.extendVideo());
    }

    /**
     * 重放一个已存在的口型替换作业。
     *
     * <p>三种结果：已成功的返回它的位置；仍在运行或排队的返回当前状态
     * （见 {@link #submitLipSync} 的类注释）；已失败的返回冲突，因为同一个键下
     * 的第二次提交会是第二次付费作业。
     */
    private Mono<LipSyncView> replayLipSync(
            AudioTaskRepository.TaskRow existing, LipSyncCommand command) {
        return Mono.defer(() -> {
            requireSameOwner(existing, command.apiKey(), "audio_request_id_conflict");
            if (!existing.terminal()) {
                return Mono.just(LipSyncView.of(existing));
            }
            if (!existing.succeeded()) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "audio_generation_previously_failed"));
            }
            return compensateTerminalIfNeeded(existing).then(Mono.just(LipSyncView.of(existing)));
        });
    }

    /**
     * 查询一个口型替换作业；非终态时向上游轮询一次。
     *
     * <p>任务的 id 不是一种能力凭证：他人的任务被报告为**不存在**，
     * 而不是被禁止，因此该端点无法被用来探测哪些 id 存在。
     */
    public Mono<LipSyncView> lipSyncStatus(
            ApiKeyService.ApiKeyContext apiKey, String requestId) {
        return ownedLipSyncTask(apiKey, requestId)
                .flatMap(task -> {
                    if (task.terminal()) {
                        return compensateTerminalIfNeeded(task).thenReturn(LipSyncView.of(task));
                    }
                    if (task.upstreamTaskId() == null || task.upstreamTaskId().isBlank()) {
                        // 提交跨过了厂商边界却没有留下 task id：结果未知，
                        // 因此既不能结算也不能释放。保持 fail-closed。
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "audio_submission_outcome_unknown"));
                    }
                    return pollLipSyncUpstream(task).map(LipSyncView::of);
                });
    }

    /**
     * 解析一次已完成作业的可交付位置。
     *
     * <p>厂商自己的 URL 留在网关之后：它可能绑定平台的凭证，也可能过期
     * （VideoRetalk 的位置 24 小时失效），两者都不属于客户可见的契约。
     */
    public Mono<String> lipSyncContentUrl(
            ApiKeyService.ApiKeyContext apiKey, String requestId) {
        return ownedLipSyncTask(apiKey, requestId).flatMap(task -> {
            if (!task.succeeded() || task.outputUrl() == null) {
                // 作业存在但没有可取的内容：仍在运行或已失败。
                // 409 表达的是「现在不行 / 不是这一个」，而不是假装该 id 未知。
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "audio_output_not_ready"));
            }
            return compensateTerminalIfNeeded(task).thenReturn(task.outputUrl());
        });
    }

    private Mono<AudioTaskRepository.TaskRow> ownedLipSyncTask(
            ApiKeyService.ApiKeyContext apiKey, String requestId) {
        if (apiKey == null || requestId == null || requestId.isBlank()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_audio_request_id"));
        }
        return taskRepository.findByRequestId(requestId)
                .filter(task -> AudioTaskRepository.KIND_LIP_SYNC.equals(task.kind()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "audio_task_not_found")))
                .flatMap(task -> task.userId().equals(apiKey.userId())
                        && task.tenantId().equals(apiKey.tenantId())
                        ? Mono.just(task)
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "audio_task_not_found")));
    }

    /** 向上游轮询一次，若因此产生了终态则完成结算。 */
    private Mono<AudioTaskRepository.TaskRow> pollLipSyncUpstream(
            AudioTaskRepository.TaskRow task) {
        return routeRepository
                .candidates(ownerContext(task), task.modelCode(), CAPABILITY_LIP_SYNC_JSON)
                .filter(route -> route.channelId().equals(task.channelId()))
                .filter(route -> adapterRegistry.supportsLipSync(route.providerType()))
                .next()
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "audio_channel_unavailable")))
                .flatMap(route -> {
                    LipSyncAdapter adapter = adapterRegistry.lipSyncFor(route.providerType());
                    return adapter.poll(
                            lipSyncRequestFor(task, route), task.upstreamTaskId());
                })
                .flatMap(status -> applyLipSync(task, status));
    }

    /**
     * 应用一个上游状态：记录进度，或执行一次性的终态转换与结算。
     *
     * <p><b>只结算一次。</b>带守卫的终态转换就是认领：看到
     * {@code rowsUpdated == 1} 的调用方是唯一会结算的调用方，因此对已完成作业
     * 轮询任意多次都只计费一次。若改为在转换之前结算，并发的轮询方就可能去
     * 结算另一个调用方已经结算过的预留，而它的拒绝随后会被看成失败，
     * 从而中断一个实际上已经付款的作业。
     *
     * <p><b>绝不交付未付费的内容。</b>若胜出者自己的扣费被拒绝，
     * 作业就以 FAILED 关闭并带上 {@link #SETTLEMENT_REFUSED}，于是客户被告知
     * 该产出不可用，而不是拿到一段平台从未计费的视频。
     */
    private Mono<AudioTaskRepository.TaskRow> applyLipSync(
            AudioTaskRepository.TaskRow task, LipSyncAdapter.LipSyncStatus status) {
        if (!status.terminal()) {
            return taskRepository.markRunning(
                            task.requestId(), task.upstreamTaskId(), null)
                    .then(taskRepository.findByRequestId(task.requestId()))
                    .flatMap(Mono::just);
        }
        String terminalStatus = switch (status.state()) {
            case SUCCEEDED -> "SUCCEEDED";
            case CANCELLED -> "CANCELLED";
            default -> "FAILED";
        };
        int billedSeconds = clampSeconds(status.billedSeconds(), task.reservedSeconds());
        // 只有成功的作业才可以携带位置；数据库检查强制执行这一点，
        // 因此为失败上报位置的厂商无法造出一行看起来可获取的记录。
        String outputUrl = "SUCCEEDED".equals(terminalStatus) ? status.outputUrl() : null;
        return taskRepository
                .completeTerminal(
                        task.requestId(), terminalStatus, billedSeconds,
                        status.errorCategory(), null, outputUrl)
                .flatMap(updated -> updated == 1L
                        ? settleLipSync(task, billedSeconds)
                                .onErrorResume(AudioSettlementRefusedException.class, refused -> {
                                    log.warn("audio_task_settlement_refused requestId={} reason={}",
                                            task.requestId(), refused.getMessage());
                                    return taskRepository
                                            .markSettlementRefused(task.requestId())
                                            .then(Mono.error(refused));
                                })
                                // 证据在结算之后写入，因此这一行记录的是一次真实
                                // 存在的结算：一次套餐拒绝付款的生成不是经营事实，
                                // 而是一个失败的任务。
                                .then(economicsEvidence.write(task, terminalStatus, billedSeconds))
                                .then(Mono.just(true))
                        : Mono.just(false))
                .then(taskRepository.findByRequestId(task.requestId()));
    }

    /**
     * 结算一次换口型作业，并把「结算被拒」与「结算本身出错」区分开。
     *
     * <p>被拒（例如套餐在这一刻已耗尽）必须让作业以 FAILED 关闭，
     * 否则客户会拿到一段平台从未计费的视频。这个区分与视频路径
     * {@code UnsettleableVideoTaskException} 相同，而它必须存在：
     * 把两者混为一谈，会让一次瞬时故障也把一次真实产出标记为失败并丢弃。
     */
    private Mono<Void> settleLipSync(
            AudioTaskRepository.TaskRow task, int chargedSeconds) {
        return settle(task, chargedSeconds)
                .onErrorMap(error -> new AudioSettlementRefusedException(
                        "audio_generation_unsettleable:" + error.getMessage(), error));
    }

    /** 一次无法完成的结算：该作业必须被拒绝交付，而不是交付出去。 */
    static final class AudioSettlementRefusedException extends RuntimeException {
        AudioSettlementRefusedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 计费秒数：厂商实际计费的秒数，并以预留为上限。
     *
     * <p>厂商报告更多只可能来自客户少报（预留取的是音频与视频中较短的一方，
     * 而厂商扩展了画面）。按它结算会突破套餐额度，因此多出的部分不被收取。
     *
     * <p>{@code null} 与 0 都映射到 0：厂商没有报告用量就没有证据表明它消耗了
     * 可计费的时间，因此不收费而不是按预留全额收费。这与视频路径
     * {@code VideoGenerationService.chargedSeconds} 是同一条规则。
     *
     * <p>静态且只依赖两个入参，因此它能被直接测试——而这个算术决定的是钱，
     * 不该只能靠一次端到端调用去验证。
     */
    static int clampSeconds(Integer vendorSeconds, int reservedSeconds) {
        if (vendorSeconds == null || vendorSeconds <= 0) {
            return 0;
        }
        return Math.min(vendorSeconds, Math.max(0, reservedSeconds));
    }

    /**
     * 一次口型替换提交据以预留的秒数。
     *
     * <p>厂商在音频与视频长度不一致时默认按**较短**的一方截断；
     * 客户开启 {@code extendVideo} 时才会按音频长度扩展画面
     * （用原视频画面「倒放-正放」交替填充）。因此预留取较短的一方，
     * 除非客户明确要求按音频扩展。
     *
     * <p>这条规则的重要性在于预留是**结算上限**：用较长的一方预留，
     * 会让一次短视频 + 长音频的调用白白占住额度，而那条额度正是别的客户
     * 在同一分钟内被限流的原因。
     */
    static int reservedSecondsForLipSync(
            int videoSeconds, int audioSeconds, boolean extendVideo) {
        return extendVideo ? audioSeconds : Math.min(videoSeconds, audioSeconds);
    }

    private Mono<SubmissionIntent> persistLipSyncIntent(
            LipSyncCommand command,
            AudioRouteRepository.AudioRoute route,
            QuotaService.Reservation reservation,
            int requestedSeconds) {
        return persist(command.apiKey(), command.requestId(), command.modelAlias(),
                route, reservation, AudioTaskRepository.KIND_LIP_SYNC, requestedSeconds);
    }

    private LipSyncAdapter.LipSyncRequest lipSyncRequestFor(
            AudioTaskRepository.TaskRow task, AudioRouteRepository.AudioRoute route) {
        // 轮询只需要渠道与 task id，调用方的输入媒体不参与。
        return new LipSyncAdapter.LipSyncRequest(
                lipSyncChannel(route),
                task.requestId(),
                POLL_PLACEHOLDER_URL,
                POLL_PLACEHOLDER_URL,
                null,
                false,
                task.reservedSeconds(),
                route.timeoutOrDefault());
    }

    private LipSyncAdapter.Channel lipSyncChannel(AudioRouteRepository.AudioRoute route) {
        return new LipSyncAdapter.Channel(
                route.channelId(), route.providerId(), route.providerType(),
                route.baseUrl(), route.apiKey(), route.upstreamModel());
    }

    /**
     * 为轮询重建刚好够用的 key 上下文：作业在提交时已经通过准入，
     * 因此只需要租户与用户身份就能重新解析渠道。
     */
    private ApiKeyService.ApiKeyContext ownerContext(AudioTaskRepository.TaskRow task) {
        return new ApiKeyService.ApiKeyContext(
                task.apiKeyId(), task.tenantId(), task.userId(),
                task.packageInstanceId(),
                task.modelCode() == null ? null : java.util.Set.of(task.modelCode()));
    }

    private void validateLipSync(LipSyncCommand command) {
        if (command == null || blank(command.requestId())
                || blank(command.modelAlias()) || blank(command.videoUrl())
                || blank(command.audioUrl())
                || command.videoSeconds() <= 0 || command.audioSeconds() <= 0
                || command.videoSeconds() > MAX_SECONDS
                || command.audioSeconds() > MAX_SECONDS) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_lip_sync_request");
        }
    }

    // -----------------------------------------------------------------------
    // 共用：准入、预留、结算
    // -----------------------------------------------------------------------

    /**
     * 该别名必须登记过计费单位。
     *
     * <p>空并**不**表示「不计量」：一个没有登记单位的别名无法按其消耗计费，
     * 那时的正确答案是拒绝而不是猜。这与生图路径的
     * {@code image_model_size_unregistered} 是同一类 fail-closed。
     */
    private Mono<Void> requireBillingUnit(String modelAlias) {
        return routeRepository.billingUnit(modelAlias)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "audio_model_unit_unregistered")))
                .then();
    }

    /**
     * 在套餐售出了「秒/分钟」速率时强制执行它。
     *
     * <p>{@code AUDIO_SECOND} 实例上的 {@code package_instance.tokens_per_minute}
     * 是每分钟**秒数**，与 {@code VIDEO_SECOND} 上的每秒钟数同形。
     * {@link PackageSecondRateLimitService} 自行解析套餐的维度与速率
     * （见 {@code PackageRateLimits}，它认三个按单位计费的维度），
     * 因此秒数绝不会被当作 token 计费。
     */
    private Mono<Void> admitUnitRate(ApiKeyService.ApiKeyContext apiKey) {
        return packageUnitRateLimitService.admit(
                apiKey.packageInstanceId(), apiKey.userId());
    }

    private Mono<QuotaService.Reservation> reserve(
            ApiKeyService.ApiKeyContext apiKey,
            String modelAlias,
            String requestId,
            int seconds) {
        Instant expiresAt = Instant.now().plus(TASK_TTL);
        return quotaService.reserve(new QuotaService.ReserveCommand(
                UUID.randomUUID(),
                requestId,
                apiKey.tenantId(),
                apiKey.userId(),
                apiKey.apiKeyId(),
                modelAlias,
                seconds,
                expiresAt,
                seconds,
                0,
                DIMENSION));
    }

    /**
     * 在调用非幂等的厂商 API 之前，创建持久化的 request id 锚点。
     *
     * <p>如果插入在唯一键竞争中落败，则调用方就是一次重放，绝不能向上游提交。
     */
    private Mono<SubmissionIntent> persistIntent(
            TranscribeCommand command,
            AudioRouteRepository.AudioRoute route,
            QuotaService.Reservation reservation,
            String kind) {
        return persist(command.apiKey(), command.requestId(), command.modelAlias(),
                route, reservation, kind, command.reservedSeconds());
    }

    private Mono<SubmissionIntent> persistIntent(
            SynthesizeCommand command,
            AudioRouteRepository.AudioRoute route,
            QuotaService.Reservation reservation,
            String kind) {
        return persist(command.apiKey(), command.requestId(), command.modelAlias(),
                route, reservation, kind,
                AudioSynthesisAdapter.estimateSecondsFromCharacters(command.text().length()));
    }

    private Mono<SubmissionIntent> persist(
            ApiKeyService.ApiKeyContext apiKey,
            String requestId,
            String modelAlias,
            AudioRouteRepository.AudioRoute route,
            QuotaService.Reservation reservation,
            String kind,
            int requestedSeconds) {
        AudioTaskRepository.NewTask task = new AudioTaskRepository.NewTask(
                UUID.randomUUID(),
                requestId,
                reservation.id(),
                apiKey.tenantId(),
                apiKey.userId(),
                apiKey.apiKeyId(),
                apiKey.packageInstanceId(),
                modelAlias,
                route.providerId(),
                route.channelId(),
                route.upstreamModel(),
                kind,
                requestedSeconds,
                (int) reservation.reservedTokens(),
                "SUBMITTED",
                reservation.expiresAt());
        return taskRepository.insert(task)
                .flatMap(inserted -> inserted == 1L
                        ? taskRepository.findByRequestId(requestId)
                                .map(row -> new SubmissionIntent(row, true))
                        // 插入落败：并发的或重放的请求已经建了这一行，
                        // 因此调用方必须走重放路径，绝不能向上游提交。
                        : taskRepository.findByRequestId(requestId)
                                .map(row -> new SubmissionIntent(row, false))
                                .switchIfEmpty(Mono.error(new IllegalStateException(
                                        "audio_task_insert_lost_without_a_row"))));
    }

    /**
     * 结算一次已成功的调用。
     *
     * <p>按秒计费的维度把秒数放在 input/output 两个计数器里（与视频、图片
     * 路径完全相同，见 {@code VideoGenerationService}）：维度为 TOKEN 以外的行
     * 其 token 计数器必须为零，而秒数由 {@code usage_units} 承载。
     * 这里传的是 {@code TokenUsage(billed, 0, billed)}，与生图路径
     * 传 {@code TokenUsage(chargedImages, 0, chargedImages)} 同形。
     */
    private Mono<Void> settle(AudioTaskRepository.TaskRow task, int chargedSeconds) {
        return quotaService.settle(
                        task.reservationId(),
                        new QuotaService.TokenUsage(chargedSeconds, 0, chargedSeconds),
                        QuotaService.UsageQuality.ACTUAL)
                .doOnNext(settlement -> packageUnitRateLimitService.record(
                        task.packageInstanceId(), settlement.chargedTokens()))
                .then();
    }

    /**
     * 终态行可能领先于它的配额转换，因此交付之前把那次转换补上。
     *
     * <p>一个已经终态且预留仍是 RESERVED 的任务，只可能来自「任务更新已提交、
     * 配额转换未完成」这一种交错。同步补一次结算，而不是交付一份未付费的结果。
     */
    private Mono<Void> compensateTerminalIfNeeded(AudioTaskRepository.TaskRow task) {
        return taskRepository.reservationIsReserved(task.reservationId())
                .flatMap(reserved -> reserved
                        ? settle(task, task.chargedSeconds())
                        : Mono.empty());
    }

    /**
     * 处理一次上游调用失败：把作业落到终态，并按「拒绝是否确定」决定释放预留。
     *
     * <p><b>两种失败都必须落地到终态，区别只在预留。</b></p>
     *
     * <p><b>确定性的拒绝</b>（上游显式的 4xx）——厂商拒绝了该请求、没有任何音频
     * 被处理，因此没有消耗任何秒数，释放预留是诚实的；把客户余额扣住整整一个
     * TTL 就是在撒谎。HTTP 408 被排除在「确定性」之外：请求超时意味着厂商
     * 可能已经接单。
     *
     * <p><b>结果未知的故障</b>（5xx、客户端/网络超时、连接重置、2xx 协议失败）
     * ——预留**保持**，因为厂商可能已经完成了一次结果始终未到达的处理；
     * 释放一个结果未知的预留，会让重试针对同一套餐再买第二次，
     * 而第一次的产出可能已经存在。
     *
     * <p>但预留保持不等于作业停在原地。两种情况都写入终态：结果未知的那条
     * 落到 {@code FAILED} 并计零秒。没有这一步，一条停在 {@code SUBMITTED} 的
     * 行既不结算也不释放（客户端可能拿不到响应），客户看到的是一个永远
     * 不会有下文的「已受理」作业，直到 TTL 过期才由恢复清扫收走。
     * 计零秒是保守方向：平台放弃这次可能的产出，而不是对一次结果未知的
     * 处理收费。
     */
    private <T> Mono<T> releaseRejected(
            AudioTaskRepository.TaskRow task,
            QuotaService.Reservation reservation,
            Throwable error) {
        if (!isDefiniteUpstreamRejection(error)) {
            // 结果未知：预留保持不动（见上面的理由），但作业必须**落地**到一个
            // 终态，否则客户端永远无法得知发生了什么。
            //
            // 这一点对音频比视频更要紧：视频有一个状态端点可以让客户重新查询，
            // 而音频的三个能力共用一个内部状态机——一条停在 SUBMITTED 的行
            // 既不结算也不释放，只能等 TTL 过期后由恢复清扫收走。
            // 客户看到的则是一个「已受理」的作业，永远不会有下文。
            //
            // 落到 FAILED 并计零秒是**保守**方向：平台放弃了这次可能的产出，
            // 而不是对一次结果未知的处理收费。这与
            // VideoGenerationService 对非终态故障的处理一致。
            return taskRepository
                    .completeTerminal(task.requestId(), "FAILED", 0, UPSTREAM_FAILED,
                            null, null)
                    .then(Mono.error(error));
        }
        log.warn("audio_generation_rejected reservationId={} reason={}",
                reservation.id(), error.getMessage());
        return taskRepository
                .completeTerminal(task.requestId(), "CANCELLED", 0, UPSTREAM_REJECTED,
                        null, null)
                .then(quotaService.release(
                        reservation.id(), QuotaService.ReleaseReason.UPSTREAM_FAILURE))
                .then(Mono.error(error));
    }

    /**
     * 该错误是否是一次**确定性的**上游拒绝，而不是结果未知的故障。
     *
     * <p>408 被显式排除在「确定性」之外：请求超时意味着厂商可能已经接单。
     * 这与生图路径 {@code ImageGenerationService.isDefiniteUpstreamRejection}
     * 是同一条判据，而它必须一致——两条按单位计费的路径对「能否释放预留」
     * <p><b>本方法与 {@code ImageGenerationService.isDefiniteUpstreamRejection}
     * 逐字等价，而这不是巧合：它是两份副本，必须同时改。</b>
     * {@code AudioGenerationBillingTest.onlyAnExplicitNonTimeoutClientRejectionIsDefinite}
     * 钉住了音频这一份的边界（含 408 与 null 的两个特殊形态）。
     * 两份不一致的后果是具体的：同一类上游故障会在图片路径上退款、
     * 在音频路径上扣款，而两条路径各自的测试都会通过。
     *
     * <p>之所以没有把两者抽成一个共享谓词：这两条路径的付费语义将来可能真的分家
     * （例如某个厂商对失败的图片调用不计费、对失败的音频调用计费），
     * 而一个被共享的谓词会强迫它们保持一致，那时反而要先拆开它。
     * 在拆开之前，这条注释就是那份「必须同时改」的契约。
     */
    static boolean isDefiniteUpstreamRejection(Throwable error) {
        if (!(error instanceof ProviderAdapter.ProviderException provider)) {
            return false;
        }
        int status = provider.status();
        return status >= 400 && status < 500 && status != 408;
    }

    /** 恢复清扫每次处理的作业数，与视频路径同量级。 */
    static final int RECOVERY_BATCH_SIZE = 25;

    /**
     * 错误分类：一个一直没到终态、且预留已经过期的作业。
     *
     * <p>与视频路径的 {@code gateway_task_expired} 同名，因为运营报表要把
     * 两者放在同一栏里看：它们描述的是同一件事——客户离开了，
     * 而厂商最终没有给出结果。
     */
    static final String TASK_EXPIRED = "gateway_task_expired";

    /** 尚未到终态的作业，供恢复清扫使用。 */
    Flux<AudioTaskRepository.TaskRow> openTasks(int limit) {
        return taskRepository.openTasksAboutToExpire(limit);
    }

    /**
     * 处理一个预留已过期的非终态作业。
     *
     * <p><b>先向上游轮询一次，再决定释放。</b>顺序很重要：一个在客户端离开之后
     * 才完成的作业**必须**被计费，而不是被当成失败白送出去。直接释放会让
     * 客户免费拿到一段已经产出的视频，并让平台承担它的成本。
     *
     * <p>轮询之后仍非终态（厂商仍在跑，或状态暂时不可得）才释放并落到 FAILED：
     * 一次判断为「已经不可能有结果」的作业，继续持有它的预留只是在无意义地
     * 占用别人的额度。这与视频路径 {@code recoverExpired} 的取舍相同。
     *
     * <p>轮询本身失败（网络、上游故障）也走释放：此时预留已经过期，
     * 而继续持有它不会有新的信息到达——那是通用预留清扫会做的事，
     * 而它对非 TOKEN 维度是 fail-closed 的。
     */
    Mono<Void> recoverExpired(AudioTaskRepository.TaskRow task) {
        if (task.terminal()) {
            return Mono.empty();
        }
        if (task.upstreamTaskId() == null || task.upstreamTaskId().isBlank()) {
            // 没有上游 task id = 一次**同步**作业（合成/转写）在调用中途断了。
            // 网关手上没有任何句柄可以事后去查，因此「让下一次清扫再试」是没有
            // 意义的：下一次清扫会走到同一行、看到同样的空，做出同样的决定。
            // 任务已经过了 TTL，就按「已经不可能有结果」收尾。
            log.warn("audio_task_recovery_without_upstream_handle requestId={} "
                            + "action=release_expired",
                    task.requestId());
            return expireAndRelease(task);
        }
        // 先轮询一次。一个在客户端离开之后才完成的作业必须被计费，
        // 因此这一步的结果（而不是它的失败）才是决定是否释放的依据。
        // 轮询失败被吞掉并记录：预留已经过期，继续持有它不会有新的信息到达。
        Mono<Void> pollOnce = pollLipSyncUpstream(task)
                .then()
                .onErrorResume(error -> {
                    log.warn("audio_task_recovery_poll_failed requestId={} error={}",
                            task.requestId(), error.getClass().getSimpleName());
                    return Mono.empty();
                });
        // 无论轮询结果如何都执行释放：若刚才那次轮询把它推到了终态，
        // 这里的 UPDATE 会被它的状态守卫拒绝（0 行受影响），
        // 而 release 对一个已结算的预留是幂等的。
        return pollOnce.then(expireAndRelease(task));
    }

    /**
     * 释放一个预留已过期、且轮询之后仍无结果的作业。
     *
     * <p>带状态的 UPDATE 先执行，而**它的结果决定是否释放**。这不是风格问题：
     * 若并发的轮询在我们刚轮询完的那一刻把它推到了终态并结算了预留，
     * 那么无条件释放会去释放一个已经结算的预留——那要么是一次无操作，
     * 要么（取决于 {@code QuotaService} 的实现）是一次对已结算行的非法转换。
     * 因此只有「我们确实把一行非终态改成了 EXPIRE」时才释放。
     *
     * <p>这正是「只结算一次」在恢复路径上的对应物：认领状态转换的调用方
     * 才是唯一有权处置预留的调用方。
     */
    private Mono<Void> expireAndRelease(AudioTaskRepository.TaskRow task) {
        return taskRepository
                .markExpired(task.requestId(), TASK_EXPIRED)
                .flatMap(claimed -> claimed == 1L
                        ? quotaService.release(
                                task.reservationId(),
                                QuotaService.ReleaseReason.RECOVERY_TIMEOUT)
                        // 0 表示并发的轮询/交付刚刚把它推到了终态，
                        // 而那个调用方拥有这次预留的处置权。
                        : Mono.empty());
    }

    // -----------------------------------------------------------------------
    // 校验与视图
    // -----------------------------------------------------------------------

    private void validateTranscribe(TranscribeCommand command) {
        if (command == null || blank(command.requestId())
                || blank(command.modelAlias()) || blank(command.audioUrl())
                || command.reservedSeconds() <= 0
                || command.reservedSeconds() > MAX_SECONDS) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_audio_transcription_request");
        }
    }

    private void validateSynthesize(SynthesizeCommand command) {
        if (command == null || blank(command.requestId())
                || blank(command.modelAlias()) || blank(command.text())
                || blank(command.voice())
                || command.text().length() > MAX_TEXT_CHARACTERS) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_audio_synthesis_request");
        }
    }

    /**
     * 单次合成允许的最大文案长度。
     *
     * <p>上游 Qwen-TTS 的上限是 512 token / 600 字符。取 600 字符作为网关侧的
     * 硬上限，让「文案太长」在本地就被拒绝，而不是先付一次调用再在上游失败。
     */
    static final int MAX_TEXT_CHARACTERS = 600;

    private void requireSameOwner(
            AudioTaskRepository.TaskRow existing,
            ApiKeyService.ApiKeyContext apiKey,
            String reason) {
        if (!existing.userId().equals(apiKey.userId())
                || !existing.tenantId().equals(apiKey.tenantId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, reason);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private AudioTranscriptionAdapter.Channel transcriptionChannel(
            AudioRouteRepository.AudioRoute route) {
        return new AudioTranscriptionAdapter.Channel(
                route.channelId(), route.providerId(), route.providerType(),
                route.baseUrl(), route.apiKey(), route.upstreamModel());
    }

    private AudioSynthesisAdapter.Channel synthesisChannel(
            AudioRouteRepository.AudioRoute route) {
        return new AudioSynthesisAdapter.Channel(
                route.channelId(), route.providerId(), route.providerType(),
                route.baseUrl(), route.apiKey(), route.upstreamModel());
    }

    /**
     * 把转写结果序列化成幂等锚点上的载荷。
     *
     * <p>存的是**结构化**的时间戳而不是一段格式化文本：重放时下游拿到的
     * 必须与首次调用完全相同，而把毫秒整数存成 {@code "00:00:01,200 -->"}
     * 再解析回来，会在格式与精度上引入一次没有必要的往返。
     */
    private String serializeTranscription(
            AudioTranscriptionAdapter.TranscriptionResult result) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("text", result.text());
        ArrayNode segments = root.putArray("segments");
        for (AudioTranscriptionAdapter.Segment segment : result.segments()) {
            ObjectNode entry = segments.addObject();
            entry.put("beginTimeMs", segment.beginTimeMs());
            entry.put("endTimeMs", segment.endTimeMs());
            entry.put("text", segment.text());
        }
        return root.toString();
    }

    private AudioTranscriptionAdapter.TranscriptionResult parseTranscription(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            List<AudioTranscriptionAdapter.Segment> segments = new java.util.ArrayList<>();
            for (JsonNode entry : root.path("segments")) {
                segments.add(new AudioTranscriptionAdapter.Segment(
                        entry.path("beginTimeMs").asLong(0),
                        entry.path("endTimeMs").asLong(0),
                        entry.path("text").asText("")));
            }
            return new AudioTranscriptionAdapter.TranscriptionResult(
                    root.path("text").asText(""), segments, 0);
        } catch (Exception error) {
            throw new IllegalStateException("audio_transcription_payload_unreadable", error);
        }
    }

    private TranscriptionView transcriptionView(
            AudioTaskRepository.TaskRow task,
            int chargedSeconds,
            AudioTranscriptionAdapter.TranscriptionResult result) {
        return new TranscriptionView(
                task.requestId(), result.text(), result.segments(), chargedSeconds);
    }

    // -----------------------------------------------------------------------
    // 命令与视图
    // -----------------------------------------------------------------------

    /**
     * 一次转写请求。
     *
     * @param reservedSeconds 客户声明的音频秒数，它就是预留上限与结算上限。
     *        声明得更长只会多预留一阵子（差额会被释放）；声明得更短会让
     *        一次调用的成本超出预留，而结算以预留为硬上限，因此那是少收的方向
     */
    public record TranscribeCommand(
            ApiKeyService.ApiKeyContext apiKey,
            String requestId,
            String modelAlias,
            String audioUrl,
            String audioFormat,
            String sampleRate,
            List<String> languageHints,
            int reservedSeconds) {

        public TranscribeCommand {
            audioFormat = audioFormat == null || audioFormat.isBlank()
                    ? "wav" : audioFormat.strip().toLowerCase(java.util.Locale.ROOT);
            sampleRate = sampleRate == null || sampleRate.isBlank()
                    ? "16000" : sampleRate.strip();
            languageHints = languageHints == null ? List.of() : List.copyOf(languageHints);
        }
    }

    /**
     * 一次合成请求。
     *
     * <p>不需要客户声明秒数：文案长度是网关手里的信息，而预留由它与费率卡
     * 共用的那一个字符→秒常量算出。
     */
    public record SynthesizeCommand(
            ApiKeyService.ApiKeyContext apiKey,
            String requestId,
            String modelAlias,
            String text,
            String voice,
            String languageType,
            String audioFormat) {

        public SynthesizeCommand {
            audioFormat = audioFormat == null || audioFormat.isBlank()
                    ? "wav" : audioFormat.strip().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * 转写结果。
     *
     * @param segments 带时间戳的分句；厂商不返回时间戳时为空列表。
     *        调用方必须把「空」读成「本次没有字幕」，而不是「字幕内容为空」
     */
    public record TranscriptionView(
            String requestId,
            String text,
            List<AudioTranscriptionAdapter.Segment> segments,
            int chargedSeconds) {

        public TranscriptionView {
            segments = segments == null ? List.of() : List.copyOf(segments);
        }

        public boolean hasTimestamps() {
            return !segments.isEmpty();
        }
    }

    /**
     * 合成结果。
     *
     * @param estimatedDuration 计费秒数是否是估算值而非厂商实测。
     *        这个端点从不回报音频时长，因此它**总是** true；保留这个字段是为了
     *        让一条估算计费在运营报表里能与将来的实测计费区分开，
     *        而不是让它混进同一列数字里
     */
    public record SynthesisView(
            String requestId,
            String audioUrl,
            int chargedSeconds,
            boolean estimatedDuration) { }

    /**
     * 一次口型替换请求。
     *
     * @param videoUrl 人物视频位置；厂商自己去拉它
     * @param audioUrl 配音音频位置
     * @param referenceImageUrl 可选的人脸参考图，用于视频中有多张人脸时指定目标
     * @param extendVideo 音频比视频长时是否扩展画面。{@code false} 时厂商会
     *        **截断音频**——这是一个产品可见的取舍，因此由调用方显式决定，
     *        而不是由网关挑一个默认值
     * @param videoSeconds 视频秒数。厂商按**生成视频的秒数**计费，而网关需要
     *        它才能预留；客户提供它，因为网关不下载媒体
     * @param audioSeconds 音频秒数，用途同上
     */
    public record LipSyncCommand(
            ApiKeyService.ApiKeyContext apiKey,
            String requestId,
            String modelAlias,
            String videoUrl,
            String audioUrl,
            String referenceImageUrl,
            boolean extendVideo,
            int videoSeconds,
            int audioSeconds) { }

    /**
     * 一次口型替换作业的状态。
     *
     * @param state 归一化后的状态名，供客户端判断是否还要继续轮询
     * @param outputUrl 成功时的可交付位置；网关自己的内容端点是它，
     *        而不是厂商的签名地址
     * @param chargedSeconds 已结算的秒数；非终态时为 0
     * @param errorCategory 失败时的稳定分类，绝不是厂商的原始文本
     */
    public record LipSyncView(
            String requestId,
            String state,
            String outputUrl,
            int chargedSeconds,
            String errorCategory) {

        static LipSyncView of(AudioTaskRepository.TaskRow task) {
            return new LipSyncView(
                    task.requestId(),
                    task.status(),
                    task.outputUrl(),
                    task.chargedSeconds(),
                    task.errorCategory());
        }

        /** 客户是否还要继续轮询。 */
        public boolean terminal() {
            return "SUCCEEDED".equals(state)
                    || "FAILED".equals(state)
                    || "CANCELLED".equals(state);
        }
    }

    private record SubmissionIntent(AudioTaskRepository.TaskRow task, boolean created) { }
}
