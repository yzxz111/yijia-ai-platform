package com.yuzhihuihe.apiplatform.gateway.quota;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class PackageActivationService {

    // VIDEO 模型按秒计量（gateway.model_usage_price,
    // usage_dimension = 'VIDEO_SECOND'），图片按张计量（usage_dimension = 'IMAGE'），
    // 音频按秒计量（usage_dimension = 'AUDIO_SECOND'），而不是按 token。
    // 这个集合必须与 GatewayAdminService.validateModel 和
    // PackageVersionService 保持同步，否则视频/生图/音频套餐会被以
    // invalid_package_grant_event 拒绝。
    //
    // IMAGE_GEN 是**生成**能力，与输入模态 IMAGE 不同：生图别名同时携带
    // ["TEXT","IMAGE","IMAGE_GEN"]，其中 TEXT/IMAGE 描述输入、IMAGE_GEN 描述产出。
    //
    // 音频三个能力名同样是**产出**描述，且各自不同：转写、合成、换口型是
    // 三件不同的事，而管理端的能力守卫要能按具体能力回答。它们由
    // V93 建立并挂在三个音频别名上；三个名字必须一起加，
    // 否则某个音频套餐会在发布之后被网关拒绝。
    private static final Set<String> CAPABILITIES =
            Set.of("TEXT", "IMAGE", "FILE", "VIDEO", "IMAGE_GEN",
                    "AUDIO_TRANSCRIBE", "AUDIO_SYNTHESIS", "LIP_SYNC");
    private static final Set<String> CAPABILITIES_DIMENSIONS =
            Set.of("TOKEN", "VIDEO_SECOND", "IMAGE", "AUDIO_SECOND");

    /**
     * 客户端文本通道：{@code chat-standard}（普通）/ {@code chat-deep}（深度）。
     *
     * <p>V102 建立这两个别名，V103 把**其它所有**带 TEXT 的别名从客户端目录里
     * 收起（{@code client_visible = false}），于是它们成了 {@code /v1/models} 里
     * 唯一的两个文本入口。但"目录里露不露出名字"与"这份套餐有没有这个别名"是
     * 两件事：一个只被授予 {@code text-basic / vision-pro / multimodal-basic}
     * 的套餐，目录过滤之后**一个入口都没有**——客户端看到的是空列表，
     * 而套餐权益、上游路由、计费全都正常。2026-10-09 生产故障里客户端"模型全部
     * 不可用"就是这个形态（当时生产库是人工把这两个别名补进实例的，
     * 仓库里没有任何一步会产生它们）。
     *
     * <p>因此发放时按一条规则补齐：**这个版本卖文本，就带上这两个入口**。
     * 判据用授权模型自己的 {@code TEXT} 能力（与目录查询同一套能力词），
     * 并且只对 {@code TOKEN} 维度的版本生效——按秒/按张计费的视频、图片、音频
     * 版本本来就不进对话目录，给它们加文本别名只会让权益说明变得含混。
     *
     * <p>这条补齐不收紧任何人的既有权益，也不会把更贵的别名挤掉：两个通道的输出
     * 成本（413 分/百万 token，V102 按天翼同一个上游模型计价）是库里 TOKEN 别名中
     * 最低的一档（次低 `text-basic` 800），而平台按"最贵别名优先填满"评估毛利，
     * 因此它们只会按这个最低价占用桶里原本没有任何自额度覆盖的那部分；自额度也留空
     * （额度在套餐那一级）。它做的只是把"这个套餐卖文本"在客户端表达出来。
     */
    private static final List<ModelPermission> CLIENT_TEXT_CHANNELS = List.of(
            new ModelPermission("chat-standard", null, Set.of("TEXT"), null),
            new ModelPermission("chat-deep", null, Set.of("TEXT"), null));

    /**
     * 写进 {@code package_instance.allowed_models} 的最终清单：平台下发的授权，
     * 加上（若该版本卖文本）两个客户端文本通道。
     *
     * <p>刻意**只加不删**：平台的下发名单是权益的来源，网关不替它做减法；
     * 已经存在的条目（含每模型自额度与能力串）一字不动。已经带这两个别名的
     * 版本原样返回，因此同一条事件重放、或平台将来自己把它们发下来，
     * 都不会产生重复条目。
     *
     * <p>注意这里与网关迁移 V109 是**同一条规则的两次表达**（一次给新发放、
     * 一次给已经发放的实例）。两边必须一起改：V109 的断言会证明库里的实例
     * 都满足它。
     */
    private List<ModelPermission> effectiveModels(PackageSnapshot payload) {
        boolean sellsText = "TOKEN".equals(payload.usageDimension())
                && payload.models().stream()
                        .anyMatch(model -> model.capabilities() != null
                                && model.capabilities().contains("TEXT"));
        if (!sellsText) {
            return payload.models();
        }
        List<ModelPermission> models = new ArrayList<>(payload.models());
        for (ModelPermission channel : CLIENT_TEXT_CHANNELS) {
            boolean granted = models.stream()
                    .anyMatch(model -> channel.modelCode().equals(model.modelCode()));
            if (!granted) {
                models.add(channel);
            }
        }
        return List.copyOf(models);
    }

    private final PackageActivationRepository repository;
    private final TransactionalOperator transactions;
    private final ObjectMapper objectMapper;

    public PackageActivationService(
            PackageActivationRepository repository,
            TransactionalOperator transactions,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public Mono<ActivationResult> consume(PackageGrantedEvent event) {
        validate(event);
        String payloadHash = sha256(toJson(event.payload()));
        return transactions.transactional(insertConsumedEvent(event, payloadHash)
                .flatMap(inserted -> inserted ? consumeNew(event) : consumeDuplicate(event, payloadHash)));
    }

    /**
     * 推进该用户在 {@code usageDimension} 维度上的队列。
     *
     * <p>维度是必需的而不是可选的：队列、生效实例与激活候选都必须落在同一个维度上，
     * 否则一个文本实例终结时会激活一个排队的视频实例。
     */
    public Mono<ActivationResult> activateNext(UUID userId, String usageDimension) {
        if (userId == null) {
            return Mono.error(new IllegalArgumentException("user_id_required"));
        }
        if (usageDimension == null || usageDimension.isBlank()) {
            return Mono.error(new IllegalArgumentException("usage_dimension_required"));
        }
        return transactions.transactional(
                advisoryLock(userId).then(activateNextLocked(userId, usageDimension)));
    }

    private Mono<Boolean> insertConsumedEvent(PackageGrantedEvent event, String payloadHash) {
        return repository.insertConsumedEvent(event.eventId(), event.eventType(), payloadHash)
                .map(updated -> updated == 1L);
    }

    /**
     * 重复投递的确认路径。
     *
     * <p>平台对 {@code QUEUED} 结果每 5 分钟重投一次（OutboxPublisher 刻意不把排队
     * 当作投递完成）。如果这里只回读既有结果，排队实例就只剩「有新发放」和
     * 「另一个实例终结」两种激活触发 —— 而它等待的那个实例可能属于另一个维度，
     * 永远不会终结，于是永远排下去。因此重复投递同时也是「队列该往前走了吗」的
     * 检查点：确实还排着时先推进队列，再回读本事件的实例状态，这样平台拿到的
     * 始终是它自己那个实例的真实状态，投递记账不会被别的实例带偏。
     */
    private Mono<ActivationResult> consumeDuplicate(PackageGrantedEvent event, String payloadHash) {
        return repository.findConsumedEventHash(event.eventId())
                .switchIfEmpty(Mono.error(new IllegalStateException("consumed_event_missing")))
                .flatMap(existingHash -> {
                    if (!MessageDigest.isEqual(
                            existingHash.getBytes(StandardCharsets.US_ASCII),
                            payloadHash.getBytes(StandardCharsets.US_ASCII))) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "event_payload_mismatch"));
                    }
                    return existingResult(event.eventId());
                })
                .flatMap(existing -> "QUEUED".equals(existing.status())
                        ? advanceQueueThenReread(event)
                        : Mono.just(existing));
    }

    /**
     * 推进该事件所在维度的队列，然后回读这个事件自己的实例状态。
     *
     * <p>回读而不是沿用推进结果：推进激活的可能是队列里更早的另一个实例，
     * 而本事件是否生效只能由它自己的状态回答。
     */
    private Mono<ActivationResult> advanceQueueThenReread(PackageGrantedEvent event) {
        PackageSnapshot payload = event.payload();
        return advisoryLock(payload.userId())
                .then(activateNextLocked(payload.userId(), payload.usageDimension()))
                .then(repository.findExistingResult(event.eventId()))
                .map(row -> new ActivationResult(row.packageInstanceId(), row.status()));
    }

    private Mono<ActivationResult> consumeNew(PackageGrantedEvent event) {
        PackageSnapshot payload = event.payload();
        String usageDimension = payload.usageDimension();
        return advisoryLock(payload.userId())
                .then(activateNextLocked(payload.userId(), usageDimension))
                .then(rejectRepeatedFreeClaim(payload))
                .then(hasActivePackage(payload.userId(), usageDimension))
                .flatMap(hasActive -> insertPackage(event, hasActive ? "QUEUED" : "ACTIVE"))
                .flatMap(result -> "ACTIVE".equals(result.status())
                        ? initializeModelQuotas(
                                result.packageInstanceId(), payload.usageDimension(), payload.models())
                                .thenReturn(result)
                        : Mono.just(result));
    }

    private Mono<Void> advisoryLock(UUID userId) {
        return repository.lockUser(userId);
    }

    Mono<ActivationResult> activateNextLocked(UUID userId, String usageDimension) {
        return closeTerminalActive(userId, usageDimension)
                .then(hasActivePackage(userId, usageDimension))
                .flatMap(active -> active
                        ? currentActive(userId, usageDimension)
                        : activateOldestQueued(userId, usageDimension));
    }

    private Mono<Long> closeTerminalActive(UUID userId, String usageDimension) {
        return repository.closeTerminalActive(userId, usageDimension);
    }

    private Mono<ActivationResult> currentActive(UUID userId, String usageDimension) {
        return repository.findCurrentActive(userId, usageDimension)
                .map(row -> new ActivationResult(row.packageInstanceId(), row.status()));
    }

    private Mono<ActivationResult> activateOldestQueued(UUID userId, String usageDimension) {
        return repository.findOldestQueuedForUpdate(userId, usageDimension)
                .map(row -> new QueuedSnapshot(
                        row.id(), row.tenantId(), row.durationDays(),
                        row.usageDimension(), fromJsonModels(row.allowedModelsJson())))
                .flatMap(queued -> {
                    Instant activatedAt = Instant.now();
                    return repository.activateQueued(
                                    queued.id(), activatedAt,
                                    activatedAt.plus(queued.durationDays(), ChronoUnit.DAYS))
                            .filter(updated -> updated == 1L)
                            .switchIfEmpty(Mono.error(new IllegalStateException("queued_package_activation_failed")))
                            .then(initializeModelQuotas(
                                    queued.id(), queued.usageDimension(), queued.models()))
                            .then(rebindApiKeys(userId, queued))
                            .then(recordPackageSwitch(userId, queued.id()))
                            .thenReturn(new ActivationResult(queued.id(), "ACTIVE"));
                });
    }

    /**
     * 把「某个已终结实例 → 刚激活实例」记进台账。
     *
     * <p>只有存在已终结的前驱时才写：{@code ck_ledger_scope} 要求 SWITCH 行必须带
     * {@code source_package_instance_id}，没有前驱就没有 source，写入只会被约束拒绝。
     * 而「没有前驱」是真实存在的状态 —— 队列里的实例被激活时，它的上一个实例可能
     * 从未终结过。例如它此前一直被另一个维度的生效实例挡在队列里（维度唯一性修复前
     * 的既有行为），从来就没有「上一个实例」可言。此时激活本身必须成功，只是没有
     * 「切换」可记，因此这里跳过台账而不是抛错。
     */
    private Mono<Void> recordPackageSwitch(UUID userId, UUID targetPackageId) {
        return repository.hasTerminalPackage(userId)
                .flatMap(hasTerminal -> hasTerminal
                        ? repository.insertPackageSwitch(
                                        UUID.randomUUID(), "package-switch:" + targetPackageId,
                                        userId, targetPackageId)
                                .filter(updated -> updated == 1L)
                                .switchIfEmpty(Mono.error(
                                        new IllegalStateException("package_switch_ledger_failed")))
                                .then()
                        : Mono.<Void>empty());
    }

    private Mono<Void> rebindApiKeys(UUID userId, QueuedSnapshot queued) {
        return repository.rebindApiKeys(
                queued.id(), toJson(queued.models()), queued.tenantId(), userId,
                queued.usageDimension());
    }

    private Mono<Void> rejectRepeatedFreeClaim(PackageSnapshot payload) {
        if (!"FREE_CLAIM".equals(payload.packageKind())) {
            return Mono.empty();
        }
        return repository.hasFreeClaim(payload.identityKey())
                .flatMap(granted -> granted
                        ? Mono.error(new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY, "free_package_already_granted"))
                        : Mono.empty());
    }

    private Mono<Boolean> hasActivePackage(UUID userId, String usageDimension) {
        return repository.hasActivePackage(userId, usageDimension);
    }

    private Mono<ActivationResult> insertPackage(PackageGrantedEvent event, String status) {
        UUID instanceId = UUID.randomUUID();
        PackageSnapshot payload = event.payload();
        Instant activatedAt = "ACTIVE".equals(status) ? Instant.now() : null;
        Instant expiresAt = activatedAt == null
                ? null
                : activatedAt.plus(payload.durationDays(), ChronoUnit.DAYS);
        PackageActivationRepository.NewPackageRow row =
                new PackageActivationRepository.NewPackageRow(
                        instanceId,
                        event.eventId(),
                        payload.grantRequestId(),
                        payload.tenantId(),
                        payload.userId(),
                        payload.identityKey(),
                        payload.packageVersionId(),
                        payload.packageCode(),
                        payload.packageName(),
                        payload.packageKind(),
                        payload.priceAmount(),
                        payload.currency(),
                        toJson(effectiveModels(payload)),
                        payload.totalTokenQuota(),
                        payload.audioSecondsQuota(),
                        payload.maxApiKeys(),
                        payload.requestsPerMinute(),
                        payload.tokensPerMinute(),
                        payload.maxConcurrency(),
                        payload.durationDays(),
                        payload.dailyRequestLimit(),
                        payload.maxOutputTokens(),
                        payload.maxFileSizeBytes(),
                        payload.oncePerUser(),
                        payload.usageDimension(),
                        status,
                        activatedAt,
                        expiresAt);
        return repository.insertPackage(row)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("package_instance_insert_failed")))
                .thenReturn(new ActivationResult(instanceId, status));
    }

    /**
     * 给每个模型立一份自额度行，单位取**该模型自己的**维度。
     *
     * <p>方案 C 之前这里一律写版本的主维度，因为一个版本只卖一种单位。混合版本
     * （视频秒 + 音频秒）里那是个静默的错：音频别名的额度行会写着
     * {@code VIDEO_SECOND}，而它携带的秒数其实来自音频那一列。后果有两个方向——
     * 要么 {@code QuotaService} 按行的维度判断后拒绝每一次音频请求
     * （{@code usage_dimension_mismatch}），要么（若按调用方声明的维度走）
     * 用音频的秒数去和视频秒的子额度比大小，从而给出一个毫无意义的通过/拒绝。
     *
     * <p>模型没有声明维度时退回版本的主维度：这正是 V92 之前的发放事件里
     * 每个模型的真实形态（那时快照里没有这个字段），因此旧事件的行为完全不变。
     */
    private Mono<Void> initializeModelQuotas(
            UUID instanceId, String usageDimension, List<ModelPermission> models) {
        return Flux.fromIterable(models)
                .filter(model -> model.modelTokenQuota() != null)
                .concatMap(model -> repository.insertModelQuota(
                        UUID.randomUUID(), instanceId, model.modelCode(),
                        model.effectiveUsageDimension(usageDimension), model.modelTokenQuota()))
                .then();
    }

    private Mono<ActivationResult> existingResult(UUID eventId) {
        return repository.findExistingResult(eventId)
                .map(row -> new ActivationResult(row.packageInstanceId(), row.status()))
                .switchIfEmpty(Mono.error(new IllegalStateException("consumed_package_instance_missing")));
    }

    private void validate(PackageGrantedEvent event) {
        PackageSnapshot payload = event == null ? null : event.payload();
        if (event == null || event.eventId() == null || !"PACKAGE_GRANTED_V1".equals(event.eventType())
                || event.businessKey() == null || event.businessKey().isBlank()
                || payload == null || payload.grantRequestId() == null || payload.tenantId() == null
                || payload.userId() == null || payload.packageVersionId() == null
                || blank(payload.businessNo()) || blank(payload.identityKey())
                || blank(payload.packageCode()) || blank(payload.packageName())
                || !("STANDARD".equals(payload.packageKind())
                        || "FREE_CLAIM".equals(payload.packageKind()))
                || payload.priceAmount() < 0 || !"CNY".equals(payload.currency())
                || payload.totalTokenQuota() <= 0 || payload.durationDays() <= 0
                || payload.maxApiKeys() <= 0 || payload.requestsPerMinute() <= 0
                || payload.tokensPerMinute() <= 0 || payload.maxConcurrency() <= 0
                || payload.maxFileSizeBytes() <= 0 || payload.models() == null || payload.models().isEmpty()
                || !CAPABILITIES_DIMENSIONS.contains(payload.usageDimension())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_package_grant_event");
        }
        // 元素本身先过关：下面的两份额度检查与能力检查都要读每个模型的维度，
        // 让一个 null 元素在那里 NPE 会把它报成 500，而它本来是一个 422。
        for (ModelPermission model : payload.models()) {
            if (model == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_package_grant_event");
            }
        }
        // 第二份额度（方案 C）必须与这组模型一致，理由与平台侧
        // PackageVersionService.requireAudioSecondsBucket 逐字相同：
        // 有音频别名却没有音频额度，等于卖一个永远不可用的语音；
        // 没有音频别名却有额度，是一个无人能消费、却会在控制台上显示的承诺。
        // 平台已经拦过一次；这里再拦一次是因为网关才是**能计费的一方**——
        // 一个平台侧的回归会让错误在这里变成一次发放而不是一笔收不到钱的用量。
        //
        // 顺序是刻意的：它必须在自额度上限检查**之前**跑，因为那条检查要问
        // "这个模型的额度上限是哪一列"，而只有这里证明过那一列确实存在。
        requireAudioSecondsBucket(payload);
        Set<String> codes = new java.util.HashSet<>();
        for (ModelPermission model : payload.models()) {
            String modelDimension = model.effectiveUsageDimension(payload.usageDimension());
            if (blank(model.modelCode()) || model.modelCode().length() > 128
                    || !codes.add(model.modelCode())
                    || model.modelTokenQuota() != null && (model.modelTokenQuota() <= 0
                            || model.modelTokenQuota() > modelQuotaCeiling(payload, modelDimension))
                    || model.capabilities() == null || model.capabilities().isEmpty()
                    || !CAPABILITIES.containsAll(model.capabilities())
                    || !CAPABILITIES_DIMENSIONS.contains(modelDimension)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_package_grant_event");
            }
        }
        // 生成套餐绝不能把 token 模型纳入权益，反之亦然：一个
        // 版本只携带一个配额列，因此准入一个混合套餐
        // 会让 token 请求消耗秒数余额（或反之）。
        //
        // 映射必须是三维的，因为这四个词并不平行：{@code VIDEO} 是**生成能力**
        // （video-* 别名的 capabilities 是 ["TEXT","IMAGE","VIDEO"]，
        // 其中 TEXT/IMAGE 描述输入、VIDEO 描述产出），而 {@code IMAGE} 是
        // **输入模态**（视觉对话的图片入参）。生图需要自己的词
        // {@code IMAGE_GEN}，因此把 IMAGE 维度与视频一起判断，会把
        // 生图套餐判成不匹配：它携带的别名有 IMAGE_GEN 而没有 VIDEO。
        //
        // 方案 C 起这里按**每个模型自己的**维度判断，而不是按版本的主维度：
        // 混合版本里音频别名与视频别名的单位不同，用主维度去判会让音频别名
        // 以 package_dimension_capability_mismatch 被拒——即"平台允许发布、
        // 网关拒绝发放"，而客户已经付过款。
        for (ModelPermission model : payload.models()) {
            if (!dimensionMatchesCapabilities(
                    model.effectiveUsageDimension(payload.usageDimension()), model.capabilities())) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "package_dimension_capability_mismatch");
            }
        }
    }

    /**
     * 某个模型的自额度必须与**它自己那一列**比较。
     *
     * <p>用错列不会失败得很明显：一个混合版本里音频别名的自额度去和视频秒比大小，
     * 要么被一个与它无关的上限拒绝（600 秒的语音额度 > 100 秒的视频额度），
     * 要么被放行成一个远超音频总额度、永远无法兑现的承诺。
     *
     * <p>判据与 {@code QuotaReservationRepository.LockedPackage#usesSecondBucket}
     * 逐字相同——"这个维度住在第二份桶里，当且仅当它是 AUDIO_SECOND 而实例的主维度
     * 不是"。两处使用同一个规则，是为了让"哪一列"在发放与结算两侧回答一致。
     */
    private Long modelQuotaCeiling(PackageSnapshot payload, String modelDimension) {
        boolean secondBucket = "AUDIO_SECOND".equals(modelDimension)
                && !"AUDIO_SECOND".equals(payload.usageDimension());
        return secondBucket ? payload.audioSecondsQuota() : payload.totalTokenQuota();
    }

    /**
     * 音频秒那一列必须与这组模型一致（方案 C）。
     *
     * <p>三条规则，与平台侧逐字对应：
     *
     * <ul>
     *   <li>版本主维度是 {@code AUDIO_SECOND}（纯音频套餐）：额度在主列
     *       {@code totalTokenQuota} 里，第二列必须为空。填了两边，
     *       "这次请求扣哪一列"就有两个都说得通的答案。
     *   <li>含音频别名且主维度不是 {@code AUDIO_SECOND}（视频 + 音频）：第二列
     *       **必须**有值，否则音频请求全部以 {@code quota_exhausted} 结束。
     *   <li>不含音频别名：第二列必须为空——一个无人能消费的额度是假承诺。
     * </ul>
     */
    private void requireAudioSecondsBucket(PackageSnapshot payload) {
        String dimension = payload.usageDimension();
        boolean hasAudioModel = payload.models().stream()
                .anyMatch(model -> "AUDIO_SECOND".equals(
                        model.effectiveUsageDimension(dimension)));
        Long audioSecondsQuota = payload.audioSecondsQuota();
        if ("AUDIO_SECOND".equals(dimension)) {
            if (audioSecondsQuota != null) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "invalid_package_grant_event");
            }
            return;
        }
        if (hasAudioModel != (audioSecondsQuota != null)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_package_grant_event");
        }
    }

    /**
     * 该用量维度能否承载这组模型能力。
     *
     * <p>四个维度各有自己的判据：
     *
     * <ul>
     *   <li>{@code VIDEO_SECOND} —— 按秒计费的别名必须声明 {@code VIDEO}；
     *   <li>{@code IMAGE} —— 按张计费的别名必须声明 {@code IMAGE_GEN}，
     *       而不是 {@code IMAGE}（后者只表示它可以接收图片输入）；
     *   <li>{@code AUDIO_SECOND} —— 必须声明三个音频能力之一
     *       （{@code AUDIO_TRANSCRIBE} / {@code AUDIO_SYNTHESIS} /
     *       {@code LIP_SYNC}）。只要求「之一」而不是全部：一个音频套餐
     *       完全可以只卖转写，或只卖换口型；
     *   <li>{@code TOKEN} —— token 模型，不是生成秒数、图片或音频的。
     * </ul>
     *
     * <p>fail-closed：无法识别的维度不匹配任何东西，因此它绝不会
     * 因为「看起来像 TOKEN」而被放行。
     *
     * <p>TOKEN 分支必须把音频能力也排除掉。否则一个只带
     * {@code AUDIO_TRANSCRIBE} 的别名会被判为「token 模型」，
     * 于是它可以被塞进一个 token 套餐——而它没有 token 价格，
     * 结算时会以 {@code quota_exhausted} 或一次零元结算收场。
     */
    static boolean dimensionMatchesCapabilities(String usageDimension, Set<String> capabilities) {
        if (usageDimension == null || capabilities == null || capabilities.isEmpty()) {
            return false;
        }
        return switch (usageDimension) {
            case "VIDEO_SECOND" -> capabilities.contains("VIDEO");
            case "IMAGE" -> capabilities.contains("IMAGE_GEN");
            case "AUDIO_SECOND" -> capabilities.contains("AUDIO_TRANSCRIBE")
                    || capabilities.contains("AUDIO_SYNTHESIS")
                    || capabilities.contains("LIP_SYNC");
            case "TOKEN" -> !capabilities.contains("VIDEO")
                    && !capabilities.contains("IMAGE_GEN")
                    && !capabilities.contains("AUDIO_TRANSCRIBE")
                    && !capabilities.contains("AUDIO_SYNTHESIS")
                    && !capabilities.contains("LIP_SYNC");
            default -> false;
        };
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("package_snapshot_serialization_failed", exception);
        }
    }

    private List<ModelPermission> fromJsonModels(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<ModelPermission>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("package_snapshot_deserialization_failed", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("package_event_hash_failed", exception);
        }
    }

    public record PackageGrantedEvent(
            UUID eventId,
            String eventType,
            String businessKey,
            PackageSnapshot payload) {
    }

    /**
     * @param audioSecondsQuota 方案 C 的第二份额度。只有当 {@code usageDimension}
     *        是 {@code VIDEO_SECOND} 且本版本同时卖了音频秒时才非空。
     *        它是 {@code List} 之外唯一一个"一个版本两份额度"的载体：
     *        缺了它，一个混合套餐的音频秒就无处安放，而"音频秒用完不得阻塞
     *        视频生成"这条要求也就无法表达。
     */
    public record PackageSnapshot(
            UUID grantRequestId,
            String businessNo,
            UUID tenantId,
            UUID userId,
            String identityKey,
            UUID packageVersionId,
            String packageCode,
            String packageName,
            String packageKind,
            long priceAmount,
            String currency,
            long totalTokenQuota,
            Long audioSecondsQuota,
            int durationDays,
            int maxApiKeys,
            int requestsPerMinute,
            long tokensPerMinute,
            int maxConcurrency,
            Integer dailyRequestLimit,
            Integer maxOutputTokens,
            long maxFileSizeBytes,
            boolean oncePerUser,
            String usageDimension,
            List<ModelPermission> models) {
        public PackageSnapshot {
            packageKind = packageKind == null || packageKind.isBlank()
                    ? ("FREE".equals(packageCode) ? "FREE_CLAIM" : "STANDARD")
                    : packageKind;
            // 缺省表示 token，因此视频套餐出现之前产生的授予事件
            // 仍会完全按其原有方式激活。
            usageDimension = usageDimension == null || usageDimension.isBlank()
                    ? "TOKEN" : usageDimension;
        }
    }

    /**
     * @param usageDimension 该模型自己的计费单位（platform.model_usage_dimension）。
     *        为 null 表示它跟随版本的主维度，这也是它被下发之前每个模型的形态，
     *        因此旧事件的语义完全不变。它必须逐模型携带：混合版本里同一个版本的
     *        两个别名单位不同，而网关没有平台那张表可以自己查。
     */
    public record ModelPermission(
            String modelCode,
            Long modelTokenQuota,
            Set<String> capabilities,
            String usageDimension) {

        /**
         * 该模型真正的计费单位：自己声明的维度，否则跟随版本的主维度。
         *
         * <p>刻意把"缺省"放在这里而不是在两个调用点各写一次——本类里有两处
         * 需要它（能力守卫与自额度行），而两处用不同的默认值会让一个旧事件
         * 在其中一个地方被判成 token、在另一个地方被判成视频。
         */
        String effectiveUsageDimension(String packageUsageDimension) {
            return usageDimension == null || usageDimension.isBlank()
                    ? packageUsageDimension : usageDimension;
        }
    }

    public record ActivationResult(UUID packageInstanceId, String status) {
    }

    private record QueuedSnapshot(
            UUID id,
            UUID tenantId,
            int durationDays,
            String usageDimension,
            List<ModelPermission> models) {
    }
}
