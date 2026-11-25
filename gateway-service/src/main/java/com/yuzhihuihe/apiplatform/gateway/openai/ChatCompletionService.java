package com.yuzhihuihe.apiplatform.gateway.openai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import com.yuzhihuihe.apiplatform.gateway.quota.ReservationCeiling;
import com.yuzhihuihe.apiplatform.gateway.quota.TokenEstimator;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelCircuitRegistry;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ChatCompletionService {

    public static final String SUPPRESS_USAGE_ECONOMICS_CONTEXT_KEY =
            ChatCompletionUsageAccounting.SUPPRESS_USAGE_ECONOMICS_CONTEXT_KEY;

    private static final Duration TIMEOUT_SCHEDULING_MARGIN = Duration.ofMillis(25);

    /**
     * {@code gateway.chat.total-attempt-timeout} 如果没有被任何配置提供时的兜底值。
     *
     * <p><b>它是 300s 而不是 30s，这个改动本身就是一次线上故障的修复。</b>这个字段是**整条对话
     * 请求的总时间预算**，覆盖全部供应商尝试。它此前只有 30s，且只作为 {@code @Value} 的兜底
     * 默认值存在——{@code application.properties} 里根本没有这一项，所以运维既看不到它、
     * 也无从发现「所有对话都被一个 30 秒的硬墙框着」。
     *
     * <p>后果是：长任务（生成一份文档、写一个页面）的单轮模型调用经常超过 30 秒，流会在写到
     * 一半时被网关自己掐断，客户端看到「长任务执行到一半流式输出断了」。这不是供应商的问题，
     * 而是网关给自己设的预算太短。
     *
     * <p>三层必须同向，否则短的那层先断：nginx {@code proxy_read_timeout} 600s ≥ 本值
     * ≥ 渠道 {@code channel.timeout_ms}（对话渠道 60s）。客户端超时必须 ≥ 本值。
     * 调大本值等于放宽「一次请求最多占用多少额度预留时间」，需与
     * {@code gateway.chat.reservation-ttl} 一并核对。
     */
    private static final Duration DEFAULT_TOTAL_ATTEMPT_TIMEOUT = Duration.ofSeconds(300);

    private static final TargetRequestPreparer IDENTITY_PREPARER =
            (body, target, adapter) -> Mono.just(body);

    private final GatewayCatalog catalog;
    private final UsageLogWriter usageLogWriter;
    private final QuotaService quotaService;
    private final RequestLimitService requestLimitService;
    private final ChatCompletionUsageEstimator usageEstimator;
    private final ChannelRouter channelRouter;
    private final ChatCompletionProviderExecutor providerExecutor;
    private final ChatCompletionRequestValidator requestValidator;
    private final ChatCompletionResponseProtocol responseProtocol;
    private final ChatCompletionUsageAccounting usageAccounting;
    private final ChatPublicAdmissionLifecycle publicAdmissionLifecycle;
    private final Duration reservationTtl;
    private final Duration totalAttemptTimeout;
    private final Scheduler timeoutScheduler;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatCompletionService(
            GatewayCatalog catalog,
            UsageLogWriter usageLogWriter,
            QuotaService quotaService,
            RequestLimitService requestLimitService,
            ChatCompletionUsageEstimator usageEstimator,
            ChannelRouter channelRouter,
            ChatCompletionProviderExecutor providerExecutor,
            ChatCompletionRequestValidator requestValidator,
            ChatCompletionResponseProtocol responseProtocol,
            @org.springframework.beans.factory.annotation.Value("${gateway.chat.reservation-ttl:10m}")
            Duration reservationTtl,
            @org.springframework.beans.factory.annotation.Value("${gateway.chat.total-attempt-timeout:300s}")
            Duration totalAttemptTimeout) {
        this.catalog = catalog;
        this.usageLogWriter = usageLogWriter;
        this.quotaService = quotaService;
        this.requestLimitService = requestLimitService;
        this.usageEstimator = usageEstimator;
        this.channelRouter = channelRouter;
        this.providerExecutor = providerExecutor;
        this.requestValidator = requestValidator;
        this.responseProtocol = responseProtocol;
        this.usageAccounting = new ChatCompletionUsageAccounting();
        this.publicAdmissionLifecycle = new ChatPublicAdmissionLifecycle(
                requestLimitService, quotaService, requestValidator);
        this.reservationTtl = reservationTtl;
        if (totalAttemptTimeout == null || totalAttemptTimeout.isZero() || totalAttemptTimeout.isNegative()) {
            throw new IllegalArgumentException("invalid_total_attempt_timeout");
        }
        this.totalAttemptTimeout = totalAttemptTimeout;
        this.timeoutScheduler = Schedulers.parallel();
    }

    public ChatCompletionService(
            GatewayCatalog catalog,
            UsageLogWriter usageLogWriter,
            QuotaService quotaService,
            RequestLimitService requestLimitService,
            ChatCompletionUsageEstimator usageEstimator,
            ChannelRouter channelRouter,
            ChannelCircuitRegistry circuits,
            List<ProviderAdapter> adapters,
            ChatCompletionRequestValidator requestValidator,
            ChatCompletionResponseProtocol responseProtocol,
            Duration reservationTtl,
            Duration totalAttemptTimeout) {
        this(catalog, usageLogWriter, quotaService, requestLimitService,
                usageEstimator, channelRouter,
                new ChatCompletionProviderExecutor(circuits, adapters),
                requestValidator, responseProtocol, reservationTtl, totalAttemptTimeout);
    }

    public ChatCompletionService(
            GatewayCatalog catalog,
            UsageLogWriter usageLogWriter,
            QuotaService quotaService,
            RequestLimitService requestLimitService,
            TokenEstimator tokenEstimator,
            ChannelRouter channelRouter,
            ChannelCircuitRegistry circuits,
            List<ProviderAdapter> adapters,
            ChatCompletionRequestValidator requestValidator,
            ChatCompletionResponseProtocol responseProtocol,
            ObjectMapper objectMapper,
            Duration reservationTtl,
            Duration totalAttemptTimeout) {
        this(catalog, usageLogWriter, quotaService, requestLimitService,
                new ChatCompletionUsageEstimator(tokenEstimator, objectMapper),
                channelRouter, circuits, adapters, requestValidator, responseProtocol,
                reservationTtl, totalAttemptTimeout);
    }

    public ChatCompletionService(
            GatewayCatalog catalog,
            UsageLogWriter usageLogWriter,
            QuotaService quotaService,
            RequestLimitService requestLimitService,
            TokenEstimator tokenEstimator,
            ChannelRouter channelRouter,
            ChannelCircuitRegistry circuits,
            List<ProviderAdapter> adapters,
            ChatCompletionRequestValidator requestValidator,
            ObjectMapper objectMapper,
            Duration reservationTtl,
            Duration totalAttemptTimeout) {
        this(catalog, usageLogWriter, quotaService, requestLimitService, tokenEstimator,
                channelRouter, circuits, adapters, requestValidator,
                new ChatCompletionResponseProtocol(objectMapper),
                objectMapper, reservationTtl, totalAttemptTimeout);
    }

    public ChatCompletionService(
            GatewayCatalog catalog,
            UsageLogWriter usageLogWriter,
            QuotaService quotaService,
            RequestLimitService requestLimitService,
            TokenEstimator tokenEstimator,
            ChannelRouter channelRouter,
            ChannelCircuitRegistry circuits,
            List<ProviderAdapter> adapters,
            ObjectMapper objectMapper,
            Duration reservationTtl,
            Duration totalAttemptTimeout) {
        this(catalog, usageLogWriter, quotaService, requestLimitService, tokenEstimator,
                channelRouter, circuits, adapters, new ChatCompletionRequestValidator(),
                objectMapper, reservationTtl, totalAttemptTimeout);
    }

    ChatCompletionService(
            GatewayCatalog catalog,
            UsageLogWriter usageLogWriter,
            QuotaService quotaService,
            RequestLimitService requestLimitService,
            TokenEstimator tokenEstimator,
            ChannelRouter channelRouter,
            ChannelCircuitRegistry circuits,
            List<ProviderAdapter> adapters,
            ObjectMapper objectMapper,
            Duration reservationTtl) {
        this(catalog, usageLogWriter, quotaService, requestLimitService, tokenEstimator,
                channelRouter, circuits, adapters, objectMapper, reservationTtl, Duration.ofSeconds(30));
    }

    public Mono<PublicAdmission> admitPublic(
            ApiKeyService.ApiKeyContext apiKey, String publicModelAlias) {
        publicAdmissionLifecycle.validateForAdmission(apiKey, publicModelAlias);
        return catalog.preparePublic(apiKey, publicModelAlias)
                .switchIfEmpty(Mono.error(unprocessable("active_package_required")))
                .flatMap(prepared -> publicAdmissionLifecycle.acquire(
                                apiKey, publicModelAlias, prepared.rpm(), prepared.concurrency(),
                                prepared.dailyLimit(), prepared.tokenLimit())
                        .map(state -> new PublicAdmission(prepared, state)));
    }

    public Mono<Void> releasePublicAdmission(PublicAdmission admission) {
        return publicAdmissionLifecycle.release(state(admission));
    }

    public Mono<CompletionResult> complete(
            ApiKeyService.ApiKeyContext apiKey, JsonNode body, String requestId) {
        return completePreparedInternal(
                apiKey, body, requestId,
                requestValidator.validateChat(apiKey, body, requestId), IDENTITY_PREPARER);
    }

    public Mono<CompletionResult> completePrepared(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            Set<ChannelRouter.Capability> capabilities,
            TargetRequestPreparer requestPreparer) {
        return completePreparedInternal(
                apiKey, body, requestId,
                requestValidator.validatePrepared(apiKey, body, requestId, capabilities),
                requestPreparer == null ? IDENTITY_PREPARER : requestPreparer);
    }

    public Mono<CompletionResult> completeRouted(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            String publicModelAlias,
            String routingModelAlias,
            Set<ChannelRouter.Capability> capabilities,
            TargetRequestPreparer requestPreparer) {
        return completePreparedInternal(
                apiKey, body, requestId,
                requestValidator.validateRouted(apiKey, body, requestId, publicModelAlias,
                        routingModelAlias, capabilities),
                requestPreparer == null ? IDENTITY_PREPARER : requestPreparer, null);
    }

    public Mono<CompletionResult> completeRouted(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            String publicModelAlias,
            String routingModelAlias,
            Set<ChannelRouter.Capability> capabilities,
            TargetRequestPreparer requestPreparer,
            PublicAdmission admission) {
        return publicAdmissionLifecycle.withClaimedAdmission(
                state(admission),
                () -> {
                    ChatCompletionRequestValidator.RequestShape shape = requestValidator.validateRouted(
                            apiKey, body, requestId, publicModelAlias, routingModelAlias, capabilities);
                    publicAdmissionLifecycle.validateIdentity(
                            apiKey, publicModelAlias, admission.prepared().publicModelAlias(),
                            admission.state);
                    return shape;
                },
                shape -> completePreparedInternal(
                        apiKey, body, requestId, shape,
                        requestPreparer == null ? IDENTITY_PREPARER : requestPreparer, admission));
    }

    private Mono<CompletionResult> completePreparedInternal(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            ChatCompletionRequestValidator.RequestShape shape,
            TargetRequestPreparer requestPreparer) {
        return completePreparedInternal(
                apiKey, body, requestId, shape, requestPreparer, null);
    }

    private Mono<CompletionResult> completePreparedInternal(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            ChatCompletionRequestValidator.RequestShape shape,
            TargetRequestPreparer requestPreparer,
            PublicAdmission admission) {
        Instant startedAt = Instant.now();
        RequestLatency requestLatency = new RequestLatency();
        Mono<PreparedRequest> catalogPreparation = catalog.prepare(
                        apiKey, shape.publicModel(), shape.routingModel(), shape.capabilities())
                .switchIfEmpty(Mono.error(catalogUnavailable(admission)));
        if (admission != null) {
            catalogPreparation = catalogPreparation.onErrorResume(error ->
                    releasePublicAdmission(admission)
                            .onErrorResume(ignored -> Mono.empty())
                            .then(Mono.error(error)));
        }
        return catalogPreparation
                .flatMap(prepared -> {
                    if (admission != null && !samePublicModel(prepared, admission.prepared())) {
                        Throwable error = new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "invalid_multimodal_request");
                        return releasePublicAdmission(admission).then(Mono.error(error));
                    }
                    if (prepared.targets().isEmpty()) {
                        Throwable error = routeUnavailable(admission);
                        return releaseIfAdmitted(admission).then(Mono.error(error));
                    }
                    JsonNode providerBody;
                    ReservationCeiling.Ceiling outputCeiling;
                    int completions;
                    try {
                        outputCeiling = effectiveOutputCeiling(prepared, body);
                        // n 只放大预留量，从不放大转发出去的上限：发往上游的请求体保留
                        // 每个补全的 max_tokens，而下方的估算则为全部 n 个补全按最坏情况计费。
                        completions = usageEstimator.requestedCompletions(body);
                        providerBody = usageEstimator.boundOutputLimit(body, outputCeiling.tokens());
                    } catch (RuntimeException error) {
                        return releaseIfAdmitted(admission).then(Mono.error(error));
                    }
                    RouteTarget limitTarget = prepared.targets().getFirst();
                    Mono<RequestLimitService.LimitLease> leasePreparation = admission == null
                            ? acquire(apiKey, prepared, limitTarget)
                            : Mono.just(admission.lease());
                    return leasePreparation.flatMap(lease -> {
                        Mono<ReservedInvocation> setup = Mono.fromCallable(() ->
                                        usageEstimator.estimates(
                                                providerBody, (int) outputCeiling.tokens(), completions))
                                .flatMap(estimates -> reserve(
                                                apiKey, prepared, requestId,
                                                estimates.reservationEstimate(), estimates.inputEstimate(), admission)
                                        .map(reservation -> new ReservedInvocation(
                                                reservation, estimates.inputEstimate(),
                                                estimates.reservationEstimate())))
                                .onErrorResume(error -> releaseLease(lease, admission)
                                        .onErrorResume(ignored -> Mono.empty()).then(Mono.error(error)));
                        return setup.flatMap(invocation -> {
                            List<RouteTarget> plan = plan(prepared, shape.capabilities());
                            if (plan.isEmpty()) {
                                Throwable error = routeUnavailable(admission);
                                return releaseLease(lease, admission)
                                        .onErrorResume(ignored -> Mono.empty())
                                        .then(releaseFailed(
                                                 apiKey, prepared, requestId, invocation.reservation(),
                                                 limitTarget, invocation.reservationEstimate(),
                                                 startedAt, requestLatency, false, error))
                                        .then(Mono.error(error));
                            }
                            RouteTarget first = plan.getFirst();
                            return bindWithinDeadline(
                                            lease, first, startedAt, admission != null)
                                    .onErrorResume(error -> releaseLease(lease, admission)
                                            .onErrorResume(ignored -> Mono.empty())
                                            .then(releaseFailed(
                                                     apiKey, prepared, requestId, invocation.reservation(),
                                                     first, invocation.reservationEstimate(),
                                                     startedAt, requestLatency, false, error))
                                            .then(Mono.error(error)))
                                    .flatMap(boundLease -> {
                                        ChatCompletionSettlement settlement =
                                                new ChatCompletionSettlement(
                                                        usageAccounting,
                                                        quotaService,
                                                        requestLimitService,
                                                        new ChatCompletionSettlement.Context(
                                                                invocation.reservation().id(),
                                                                startedAt,
                                                                admission != null,
                                                                prepared.tokenLimit(),
                                                                outputCeiling.tokens(),
                                                                outputCeiling.source().wireName()),
                                                        completionTarget(prepared, first),
                                                        boundLease,
                                                        report -> usageLogWriter.write(
                                                                completionUsageEntry(
                                                                        apiKey,
                                                                        prepared,
                                                                        requestId,
                                                                        invocation.reservation(),
                                                                        requestLatency,
                                                                        report)));
                                        return completeAttempt(
                                                providerBody,
                                                requestId,
                                                prepared,
                                                plan,
                                                0,
                                                invocation.inputEstimate(),
                                                invocation.reservationEstimate(),
                                                requestPreparer,
                                                requestLatency,
                                                settlement);
                                    });
                        });
                    });
                });
    }

    public Flux<String> stream(ApiKeyService.ApiKeyContext apiKey, JsonNode body, String requestId) {
        return openStream(apiKey, body, requestId).flatMapMany(stream -> stream);
    }

    public Mono<Flux<String>> openStream(
            ApiKeyService.ApiKeyContext apiKey, JsonNode body, String requestId) {
        return openPreparedStreamInternal(
                apiKey, body, requestId,
                requestValidator.validateChat(apiKey, body, requestId), IDENTITY_PREPARER);
    }

    public Mono<Flux<String>> openPreparedStream(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            Set<ChannelRouter.Capability> capabilities,
            TargetRequestPreparer requestPreparer) {
        return openPreparedStreamInternal(
                apiKey, body, requestId,
                requestValidator.validatePrepared(apiKey, body, requestId, capabilities),
                requestPreparer == null ? IDENTITY_PREPARER : requestPreparer);
    }

    public Mono<Flux<String>> openRoutedStream(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            String publicModelAlias,
            String routingModelAlias,
            Set<ChannelRouter.Capability> capabilities,
            TargetRequestPreparer requestPreparer) {
        return openPreparedStreamInternal(
                apiKey, body, requestId,
                requestValidator.validateRouted(apiKey, body, requestId, publicModelAlias,
                        routingModelAlias, capabilities),
                requestPreparer == null ? IDENTITY_PREPARER : requestPreparer, null);
    }

    public Mono<Flux<String>> openRoutedStream(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            String publicModelAlias,
            String routingModelAlias,
            Set<ChannelRouter.Capability> capabilities,
            TargetRequestPreparer requestPreparer,
            PublicAdmission admission) {
        return publicAdmissionLifecycle.withClaimedAdmission(
                state(admission),
                () -> {
                    ChatCompletionRequestValidator.RequestShape shape = requestValidator.validateRouted(
                            apiKey, body, requestId, publicModelAlias, routingModelAlias, capabilities);
                    publicAdmissionLifecycle.validateIdentity(
                            apiKey, publicModelAlias, admission.prepared().publicModelAlias(),
                            admission.state);
                    return shape;
                },
                shape -> openPreparedStreamInternal(
                        apiKey, body, requestId, shape,
                        requestPreparer == null ? IDENTITY_PREPARER : requestPreparer, admission));
    }

    private Mono<Flux<String>> openPreparedStreamInternal(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            ChatCompletionRequestValidator.RequestShape shape,
            TargetRequestPreparer requestPreparer) {
        return openPreparedStreamInternal(
                apiKey, body, requestId, shape, requestPreparer, null);
    }

    private Mono<Flux<String>> openPreparedStreamInternal(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            ChatCompletionRequestValidator.RequestShape shape,
            TargetRequestPreparer requestPreparer,
            PublicAdmission admission) {
        return Mono.deferContextual(loggingContext -> Mono.defer(() -> {
            Instant startedAt = Instant.now();
            RequestLatency requestLatency = new RequestLatency();
            Mono<PreparedRequest> catalogPreparation = catalog.prepare(
                            apiKey, shape.publicModel(), shape.routingModel(), shape.capabilities())
                    .switchIfEmpty(Mono.error(catalogUnavailable(admission)));
            if (admission != null) {
                catalogPreparation = catalogPreparation.onErrorResume(error ->
                        releasePublicAdmission(admission)
                                .onErrorResume(ignored -> Mono.empty())
                                .then(Mono.error(error)));
            }
            return catalogPreparation
                    .flatMap(prepared -> {
                        if (admission != null && !samePublicModel(prepared, admission.prepared())) {
                            Throwable error = new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST, "invalid_multimodal_request");
                            return releasePublicAdmission(admission).then(Mono.error(error));
                        }
                        if (prepared.targets().isEmpty()) {
                            Throwable error = routeUnavailable(admission);
                            return releaseIfAdmitted(admission).then(Mono.error(error));
                        }
                        JsonNode providerBody;
                        ReservationCeiling.Ceiling outputCeiling;
                        int completions;
                        try {
                            outputCeiling = effectiveOutputCeiling(prepared, body);
                            // 与补全路径相同的拆分：转发出去的 max_tokens 是每个补全的限制，
                            // 预留量则覆盖全部 n 个补全。
                            completions = usageEstimator.requestedCompletions(body);
                            providerBody = usageEstimator.boundOutputLimit(body, outputCeiling.tokens());
                        } catch (RuntimeException error) {
                            return releaseIfAdmitted(admission).then(Mono.error(error));
                        }
                        RouteTarget limitTarget = prepared.targets().getFirst();
                        Mono<RequestLimitService.LimitLease> leasePreparation = admission == null
                                ? acquire(apiKey, prepared, limitTarget)
                                : Mono.just(admission.lease());
                        return leasePreparation
                                .flatMap(lease -> Mono.fromCallable(() ->
                                                usageEstimator.estimates(
                                                        providerBody, (int) outputCeiling.tokens(), completions))
                                        .flatMap(estimates -> reserve(
                                                apiKey, prepared, requestId,
                                                estimates.reservationEstimate(), estimates.inputEstimate(), admission)
                                                .flatMap(reservation -> {
                                                    List<RouteTarget> plan = plan(
                                                            prepared, shape.capabilities());
                                                    if (plan.isEmpty()) {
                                                        Throwable error = routeUnavailable(admission);
                                                        return releaseFailed(
                                                                        apiKey, prepared, requestId, reservation,
                                                                        limitTarget, estimates.reservationEstimate(),
                                                                        startedAt, requestLatency,
                                                                        true, error)
                                                                .then(Mono.error(error));
                                                    }
                                                    RouteTarget first = plan.getFirst();
                                                    return bindWithinDeadline(
                                                                    lease, first, startedAt,
                                                                    admission != null)
                                                            .onErrorResume(error -> releaseFailed(
                                                                            apiKey, prepared, requestId, reservation,
                                                                            first, estimates.reservationEstimate(),
                                                                            startedAt, requestLatency, true, error)
                                                                    .then(Mono.error(error)))
                                                            .map(boundLease -> {
                                                                ChatStreamSettlement settlement =
                                                                        new ChatStreamSettlement(
                                                                                usageEstimator,
                                                                                usageAccounting,
                                                                                quotaService,
                                                                                requestLimitService,
                                                                                new ChatStreamSettlement.Context(
                                                                                        reservation.id(),
                                                                                        estimates.inputEstimate(),
                                                                                        estimates.reservationEstimate(),
                                                                                        startedAt,
                                                                                        requestId,
                                                                                        loggingContext.getOrDefault(
                                                                                                "traceId", requestId),
                                                                                        loggingContext.getOrDefault(
                                                                                                "apiKeyPrefix", "unavailable"),
                                                                                        admission != null,
                                                                                        prepared.tokenLimit(),
                                                                                        outputCeiling.tokens(),
                                                                                        outputCeiling.source().wireName()),
                                                                                streamTarget(prepared, first),
                                                                                boundLease,
                                                                                report -> usageLogWriter.write(
                                                                                        streamUsageEntry(
                                                                                                apiKey,
                                                                                                prepared,
                                                                                                requestId,
                                                                                                reservation,
                                                                                                requestLatency,
                                                                                                report)));
                                                                 return streamAttempt(
                                                                         providerBody, prepared, plan, 0,
                                                                         requestPreparer, requestLatency, settlement)
                                                                        .doFinally(signal -> {
                                                                            if (signal == reactor.core.publisher.SignalType.CANCEL) {
                                                                                settlement.finish("CANCELLED", null);
                                                                            }
                                                                        });
                                                             });
                                                }))
                                        .onErrorResume(error -> releaseLease(lease, admission)
                                                .onErrorResume(ignored -> Mono.empty())
                                                .then(Mono.error(error))));
                    });
        }));
    }

    /**
     * 一个 <em>补全</em> 允许生成的 token 数，在调用供应商之前、在取得预留之前就已确定。
     *
     * <p>{@code min(client max_tokens, the plan's max_output_tokens when it sells one, the
     * model's declared cap, ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS)}。对于一个不售卖输出
     * 上限的套餐方案，起决定作用的是方案自身的上限：已发布的方案携带
     * {@code max_output_tokens IS NULL}，如果没有系统上限，这样的方案会让最坏情况无界，
     * 而这正是让旧估算无法结算的原因。
     *
     * <p>客户端**没有**指定上限时不是取系统上限，而是取
     * {@link ReservationCeiling#DEFAULT_MAX_OUTPUT_TOKENS}（8 192），再以套餐、模型与系统
     * 上限为界。系统上限现在是 100 万，若"没要求"也按它预留，每个默认请求都会冻结 100 万
     * token 的套餐额度——一个 FREE 套餐的总额度只有 10 万。能拿到 100 万的只有显式要
     * 100 万的那种请求，这正是这条规则想要的不对称。
     *
     * <p>这是针对每个补全的界定：它就是 {@code boundOutputLimit} 作为 {@code max_tokens}
     * 发往上游的那个值，而 OpenAI 语义使该值成为每个选项的界定，而不是整个响应的界定。
     * 预留量在 {@code ChatCompletionUsageEstimator.estimates} 中把它乘以客户端的 {@code n}；
     * 两个数字被有意分开，以免其中一个被误认为另一个。
     */
    private ReservationCeiling.Ceiling effectiveOutputCeiling(
            PreparedRequest prepared, JsonNode body) {
        return ReservationCeiling.ceiling(
                usageEstimator.requestedOutputLimit(body),
                prepared.packageMaxOutputTokens(),
                prepared.maxOutputTokens());
    }

    private Mono<CompletionResult> completeAttempt(
            JsonNode body,
            String requestId,
            PreparedRequest prepared,
            List<RouteTarget> plan,
            int index,
            long inputEstimate,
            long reservationEstimate,
            TargetRequestPreparer requestPreparer,
            RequestLatency requestLatency,
            ChatCompletionSettlement settlement) {
        RouteTarget target = plan.get(index);
        settlement.beginAttempt(completionTarget(prepared, target));
        Duration remaining = remainingAttemptTime(settlement.startedAt());
        if (remaining.isZero()) {
            Throwable error = attemptTimeout();
            return settlement.fail(error)
                    .onErrorResume(ignored -> Mono.empty())
                    .then(Mono.error(error));
        }
        return Mono.defer(() -> {
                    RouteTarget attemptedTarget = target.materialized();
                    ProviderAdapter adapter = providerExecutor.adapterFor(attemptedTarget.channel());
                    return requestPreparer.prepare(body, attemptedTarget, adapter)
                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                    "prepared_provider_body_required")))
                            .flatMap(preparedBody -> requestLatency.measure(
                                    providerExecutor.complete(
                                            adapter,
                                            new ProviderAdapter.RoutedRequest(
                                                    requestId, prepared.routingModelAlias(),
                                                    preparedBody, attemptedTarget.channel()),
                                            prepared.routingModelAlias(), remaining,
                                            settlement::upstreamStarted,
                                            response -> {
                                                settlement.actualUsage(response.usage());
                                                responseProtocol.validateCompletion(response.body());
                                            })));
                })
                .materialize()
                .flatMap(signal -> {
                    if (!signal.hasValue()) {
                        Throwable error = signal.isOnError()
                                ? signal.getThrowable()
                                : new ProviderAdapter.ProviderException(502, "empty_provider_response");
                        settlement.failedAttempt(error);
                        if (index + 1 < plan.size()) {
                            RouteTarget next = plan.get(index + 1);
                            return bindWithinDeadline(
                                            settlement.lease(), next, settlement.startedAt(),
                                            settlement.routeActivation())
                                    .onErrorResume(rebindError -> settlement.fail(rebindError)
                                            .onErrorResume(ignored -> Mono.empty())
                                            .then(Mono.error(rebindError)))
                                    .flatMap(boundLease -> {
                                        settlement.lease(boundLease);
                                        return completeAttempt(
                                                body,
                                                requestId,
                                                prepared,
                                                plan,
                                                index + 1,
                                                inputEstimate,
                                                reservationEstimate,
                                                requestPreparer,
                                                requestLatency,
                                                settlement);
                                    });
                        }
                        return settlement.fail(error)
                                .onErrorResume(ignored -> Mono.empty())
                                .then(Mono.error(error));
                    }
                    ProviderAdapter.ProviderResponse response = signal.get();
                    ProviderAdapter.TokenUsage usage = response.usage() == null
                            ? usageEstimator.estimatedCompletionUsage(
                                    inputEstimate, reservationEstimate, response.body())
                            : response.usage();
                    QuotaService.UsageQuality quality = response.usage() == null
                            ? QuotaService.UsageQuality.ESTIMATED : QuotaService.UsageQuality.ACTUAL;
                    JsonNode rewritten;
                    try {
                        rewritten = responseProtocol.rewriteCompletion(
                                response.body(), prepared.publicModelAlias(), usage);
                    } catch (RuntimeException invalidResponse) {
                        return settlement.failResponse(usage, quality, invalidResponse)
                                .onErrorResume(ignored -> Mono.empty())
                                .then(Mono.error(invalidResponse));
                    }
                    return settlement.succeed(usage, quality)
                            .thenReturn(new CompletionResult(rewritten, requestId));
                });
    }

    private Flux<String> streamAttempt(
            JsonNode body,
            PreparedRequest prepared,
            List<RouteTarget> plan,
            int index,
            TargetRequestPreparer requestPreparer,
            RequestLatency requestLatency,
            ChatStreamSettlement settlement) {
        RouteTarget target = plan.get(index);
        settlement.beginAttempt(streamTarget(prepared, target));
        Duration remaining = remainingAttemptTime(settlement.startedAt());
        if (remaining.isZero()) {
            Throwable error = attemptTimeout();
            return settlement.fail(error)
                    .thenMany(Flux.error(error));
        }
        boolean includeUsage = body.path("stream_options").path("include_usage").asBoolean(false);
        // 必须在 Flux.defer 之外声明：下面的 switchOnFirst 收尾时要读它，而 defer 内部的局部
        // 变量在那个作用域里已经不可见。
        AtomicBoolean truncationTripped = new AtomicBoolean();
        // 这条流到底读到过什么。两者合成一条判据：**既没有内容、也没有任何终止信号**的流必须失败，
        // 不能一路走到 [DONE]（见收尾处的零内容保障）。必须声明在 defer 之外，因为收尾的
        // switchOnFirst 在另一个作用域里读它们。
        //
        // 注意与执行器的 empty_provider_stream 守卫的分工：那一条管「一帧都没转发出去」
        // （纯填充帧组成的流会落在那儿，而且发生在首字节之前、还能换渠道），
        // 这一条管「转发过帧、但那些帧既没内容也没宣告任何结束」——后者已经过了首字节，换不了渠道。
        AtomicBoolean sawDeliverableContent = new AtomicBoolean();
        AtomicBoolean sawTerminalSignal = new AtomicBoolean();
        // 待发帧「是否含内容」的队列，由序列化端按产生顺序放入、由收尾端按发出顺序取出。
        //
        // **为什么不复用上面那个上游块的标志。** 一个上游块可以带多个 choice，网关会把它们拆成
        // 多帧依次发出，其中「只有 role 的那个」先发、带内容的随后才发。复用上游块的标志会把一个
        // 纯信封帧误记成「首段已发出」，于是本该换渠道的一次断流又被放弃。队列让「上游产出」与
        // 「实际发出」两个时刻各自保持准确，代价只是一次 offer/poll。
        Queue<Boolean> frameCarriesContent = new ArrayDeque<>();
        Flux<String> serialized = Flux.defer(() -> {
            RouteTarget attemptedTarget = target.materialized();
            ProviderAdapter adapter = providerExecutor.adapterFor(attemptedTarget.channel());
            return requestPreparer.prepare(body, attemptedTarget, adapter)
                    .switchIfEmpty(Mono.error(new IllegalStateException(
                            "prepared_provider_body_required")))
                    .flatMapMany(preparedBody -> requestLatency.measure(providerExecutor.stream(
                            adapter,
                            new ProviderAdapter.RoutedRequest(
                                    settlement.requestId(), prepared.routingModelAlias(),
                                    preparedBody, attemptedTarget.channel()),
                            prepared.routingModelAlias(), remaining,
                            settlement::upstreamStarted, chunk -> {
                                if (chunk.usage() != null) {
                                    settlement.actualUsage(chunk.usage());
                                }
                                responseProtocol.validateStreamChunk(chunk.data());
                                settlement.received(chunk.data());
                                // 顺手记下上游给的结束原因：这是排查"为什么这条流在 4096
                                // 就停了"时唯一能把它与自然结束分开的字段（T5）。
                                settlement.observeFinishReason(
                                        responseProtocol.finishReasonOf(chunk.data()));
                                // 先登记这条流读到了什么，再决定要不要转发这一帧。
                                // 顺序不能反：内容帧可能同时是终止帧，两者都要记。
                                if (responseProtocol.hasDeliverableContent(chunk.data())) {
                                    sawDeliverableContent.set(true);
                                }
                                if (chunk.usage() != null
                                        || responseProtocol.finishReasonOf(chunk.data()) != null) {
                                    sawTerminalSignal.set(true);
                                }
                                // **无信息填充帧：不计入响应。**
                                //
                                // 上游天翼云在**输出被长度截断**时会在结束块的前一格多发一个
                                // {@code choices:[]} 且没有 {@code usage} 的帧（2026-10-16 生产实测抓到的
                                // 真实帧序）。它既没有内容、也没有用量、也没有 finish_reason，
                                // 转发它不会有任何东西到达客户，因此直接跳过——这也顺手让
                                // validateStreamChunk 对空 choices 的容忍不至于把一帧垃圾送出去。
                                //
                                // 这一支同时覆盖了下面那种「空 choices 的 usage 尾帧」：后者同样没有
                                // choices 元素，只是多带一个 usage。两者的区别只在结算登记——
                                // 它发生在上面（登记在丢弃之前），所以这里可以只按「空 choices」一跳。
                                //
                                // 返回 null 是这条链上**合法**的「丢弃此帧」信号：执行器用的是
                                // Flux.handle 并且显式判空（见 ChatCompletionProviderExecutor）。
                                if (chunk.data().path("choices").isArray()
                                        && chunk.data().path("choices").isEmpty()) {
                                    return null;
                                }
                                ObjectNode rewritten = responseProtocol.rewriteStreamChunk(
                                        chunk.data(), prepared.publicModelAlias(),
                                        settlement.requestId(), settlement.startedAt());
                                // **这一块是不是把预留容量写满的那一块？**
                                //
                                // 判断必须锚在「刚刚收到的这一块」上，而不是留到流末尾再看一次
                                // truncatedByOutputCap()。两者在一种情况下结论相反，而那种情况
                                // 真实存在：容量在**第一块之前**就已耗尽（例如客户端只要 1 个
                                // token，而第一块本身就超了）。那时流末尾再看会说是截断，可实际上
                                // 一条内容都没交付过——把一个空回答标成 finish_reason=length。
                                //
                                // 只有这一块真的把容量写满，它才是「回答的最后一块」，才该被标记。
                                boolean trippedCap = settlement.truncatedByOutputCap();
                                if (trippedCap) {
                                    truncationTripped.set(true);
                                    responseProtocol.markTruncated(rewritten);
                                }
                                // 这一帧是否含内容，按产生顺序登记；发出的那一刻再按同样顺序取出，
                                // 用来判断「客户到底收到过内容没有」。为什么需要它，见 finishStream。
                                frameCarriesContent.offer(
                                        responseProtocol.hasDeliverableContent(rewritten));
                                return responseProtocol.writeJson(rewritten);
                            })
                            // 一旦上游流已经产出了预留量所覆盖的全部内容，就把它切断。供应商
                            // 已经被告知同一个上限 max_tokens；这是针对忽略该上限的供应商的
                            // 强制执行，使得任何请求都不可能生成超过客户预付余额已计费量的内容。
                            .takeUntil(value -> settlement.outputCapReached())));
        });
        return serialized.switchOnFirst((signal, inner) -> {
            if (signal.isOnError() || signal.isOnComplete()) {
                Throwable error = signal.isOnError()
                        ? signal.getThrowable() : new ProviderAdapter.ProviderException(502, "empty_provider_stream");
                settlement.failedAttempt(error);
                if (index + 1 < plan.size()) {
                    RouteTarget next = plan.get(index + 1);
                    return bindWithinDeadline(
                                    settlement.lease(), next, settlement.startedAt(),
                                    settlement.routeActivation())
                             .doOnNext(settlement::lease)
                            .map(boundLease -> streamAttempt(
                                     body, prepared, plan, index + 1,
                                     requestPreparer, requestLatency, settlement))
                            .onErrorResume(rebindError -> settlement.fail(rebindError)
                                    .then(Mono.error(rebindError)))
                            .flatMapMany(nextAttempt -> nextAttempt);
                }
                return settlement.fail(error)
                        .thenMany(Flux.error(error));
            }
            // **被输出上限切断的流，收尾方式必须与正常结束不同。**
            //
            // 上面的 takeUntil(outputCapReached) 会在写满预留容量时切断上游。此前切断之后
            // 照旧补一个 [DONE]，于是客户端读到的是「这段回答完整结束了」——而它只写了一半。
            // 这与本类修掉的「失败之后发 [DONE]」是同一类假完成信号，只是走第二条通路：
            // 桌面会把一个只做了一半的任务报成完成。
            //
            // 因此截断时：最后一个内容块的 finish_reason 改成 length（既然没有"结束后再写"
            // 这回事，[DONE] 也一并不发），并补一帧结构化失败帧说明原因。客户端因此拿到的
            // 是「回答到这里为止，被长度限制切断了」，而不是一个假装完整的成功。
            // **收尾必须惰性判定。** truncationTripped 是在 inner 真正流过时、由序列化器
            // 逐块设置的；如果在组装这一刻就读它，读到的必然是 false——那时流还没开始跑。
            // 这个顺序错误的表现极具迷惑性：最后一块老老实实带上了 finish_reason=length，
            // 紧跟其后的却仍是一个 [DONE]，等于把刚说出口的「被截断」又收回成「完整结束」。
            //
            // 截断时**不发失败帧**：截断的语义已经由最后一块的 finish_reason=length 完整表达，
            // 再补一帧 error 是多余且有害的——转换层看到 error 会报 response.failed，把
            // 「命中长度上限」说成「上游故障」，而两者的正确处置完全不同（前者要收窄需求或换
            // 更长的套餐，后者可重试）。用量块照旧在 [DONE] 之前发（客户端要结算信息），
            // 只把 [DONE] 这一个「完整结束」标记去掉。
            // **首段已发出**这个判断，只有在真的有内容交付给客户之后才成立。
            //
            // 从前这里无条件调用 firstByte()，而那一个调用同时是两件事：记录 first_token_ms，
            // 以及通过 markFirstByteSent() 关掉「换下一条渠道」的资格（见 ChannelRouter.AttemptCursor）。
            // 于是「响应头已经提交」被当成了「已经有内容发出」——可这两件事在流式里差得很远：
            // 上游完全可以先回一个只有 role 的空信封帧、响应因此提交，然后在吐出**第一个字**之前
            // 就把连接掐掉。这时客户一个字都没收到，换渠道既不会造成文字混合，也不会有重复计费，
            // 而那正是禁止续写要防的两件事。
            //
            // 2026-10-16 的生产数据把这个漏洞量了出来：历史上 11 次「上游流中断」的 first_token_ms
            // 全是 NULL——上游每次都是在吐出任何内容之前就断了，而每一次网关都直接放弃、没有换渠道。
            // 因此 firstByte() 改由真正交付内容的那一处触发（见下面 finishStream 的 doOnNext）。
            return finishStream(
                    body,
                    prepared,
                    plan,
                    index,
                    requestPreparer,
                    requestLatency,
                    settlement,
                    target.channel(),
                    includeUsage,
                    truncationTripped,
                    sawDeliverableContent,
                    sawTerminalSignal,
                    frameCarriesContent,
                    inner);
        });
    }

    /**
     * 上游在交付任何内容之前断掉时，换到下一条渠道继续这次请求。
     *
     * <p>契约上它是安全的，前提只有一条：客户还没收到任何内容（由调用方判定
     * {@code deliveredToClient == false}）。响应此时虽然已经提交（HTTP 200 与
     * {@code text/event-stream} 发出去了），但一个字节都没往下写过，因此换渠道对客户端
     * 完全不可见——它只是等得久一点。
     *
     * <p><b>干净地重置尝试状态是这里的关键。</b> 走 {@code beginAttempt} 而不是自己拼凑状态：
     * 它会把用量估算与这一轮的预留量一起重置，否则失败那一次尝试的估算会残留下来，
     * 让成功的这一次被按两次计费。锁的换绑（{@code bindWithinDeadline} + {@code lease}）
     * 与上面 switchOnFirst 那支用同一套，因此不会出现同一个上游被记两次或漏记。
     */
    private Flux<String> failoverAfterInterruption(
            JsonNode body,
            PreparedRequest prepared,
            List<RouteTarget> plan,
            int index,
            TargetRequestPreparer requestPreparer,
            RequestLatency requestLatency,
            ChatStreamSettlement settlement,
            Throwable error) {
        settlement.failedAttempt(error);
        RouteTarget next = plan.get(index + 1);
        return bindWithinDeadline(
                        settlement.lease(), next, settlement.startedAt(), settlement.routeActivation())
                .doOnNext(settlement::lease)
                .map(boundLease -> streamAttempt(
                        body, prepared, plan, index + 1, requestPreparer, requestLatency, settlement))
                .onErrorResume(rebindError -> settlement.fail(rebindError)
                        .then(Mono.error(rebindError)))
                .flatMapMany(nextAttempt -> nextAttempt);
    }

    /**
     * 一条已经连上的上游流的收尾：内容转发、用量尾帧、[DONE]，以及失败翻译。
     */
    /**
     * 一条已经连上的上游流的收尾：内容转发、用量尾帧、[DONE]，以及失败翻译。
     *
     * <p>它被单独提出来，是因为现在有**两个**入口需要走同一段收尾：正常连上的流（{@code switchOnFirst}
     * 拿到第一个信号之后），以及一条在交付任何内容之前就断掉的流——后者要先去换下一条渠道，
     * 换到了就递归回到这里（见 {@link #failoverAfterInterruption}）。
     *
     * <p><b>{@code deliveredToClient} 是这里的核心状态。</b> 它记录「有没有任何一个字节真的发给了客户」，
     * 并且刻意由转发那一刻设置，而不是由上游产出那一刻设置：上游产出了一块、但客户还没收到就断了，
     * 这种时序下换渠道同样会造成文字混合，所以宁可保守。它一旦为 true 就永不回退（换渠道随之永久关闭）。
     */
    private Flux<String> finishStream(
            JsonNode body,
            PreparedRequest prepared,
            List<RouteTarget> plan,
            int index,
            TargetRequestPreparer requestPreparer,
            RequestLatency requestLatency,
            ChatStreamSettlement settlement,
            ProviderAdapter.Channel attemptChannel,
            boolean includeUsage,
            AtomicBoolean truncationTripped,
            AtomicBoolean sawDeliverableContent,
            AtomicBoolean sawTerminalSignal,
            Queue<Boolean> frameCarriesContent,
            Flux<String> inner) {
        AtomicBoolean deliveredToClient = new AtomicBoolean();
        return inner
                    // 第一个真正交付给客户的帧：到这一刻才谈得上「首段已发出」。
                    // doOnNext 在元素向下游发出的那一刻执行，因此它比上游产出更贴近事实。
                    .doOnNext(frame -> {
                        if (Boolean.TRUE.equals(frameCarriesContent.poll())) {
                            deliveredToClient.set(true);
                            settlement.firstByte();
                        }
                    })
                    // **零内容兜底，位置不能挪。**
                    //
                    // 它必须排在用量尾帧与 [DONE] **之前**，理由是收尾顺序：这段链最后的
                    // onErrorResume 才是把失败翻译成「一帧失败帧 + 正常结束」的地方。若把它放到
                    // [DONE] 之后，异常会在 [DONE] 已经发出、流已经按成功结束之后才抛出来——
                    // 客户端于是同时拿到一个「完整结束」标记和一帧失败，而失败帧根本发不出去
                    // （响应已结束）。那等于用一次假完成换掉一次假失败，方向反了。
                    //
                    // 触发条件只有一种：**整条流既没有交付内容、也没有任何终止信号**，
                    // 且它**不是**一次截断。逐条说明为什么每一半都不能省：
                    //
                    //   * 「没有内容」单独不足以判定失败——一次**合法的空回答**（结束帧的 delta 是空的、
                    //     finish_reason=stop）全程也可以一个字都没有，它是正常结果（模型只产出了
                    //     推理过程、或上游自己截断）。按「没有内容」一刀切会把正常结果报成上游故障，
                    //     用一次假失败换掉一次假成功；
                    //   * 「不是截断」同理：零额度的截断同样没交付内容，但它是一个**正确的空回答**
                    //     （finish_reason=length 已经说明了原因），把它报成上游故障是另一种说谎。
                    //
                    // 这一支存在的理由是给 validateStreamChunk 的放宽做配套：那个放宽承认
                    // {@code choices:[]} 合法，代价是一条全由无操作帧组成的坏流不再于第一帧失败。
                    // 没有这一支，它会一路跑到 [DONE]，把一次零内容的上游故障包装成一次成功——
                    // 而 [DONE] 的语义正是「这段回答完整地结束了」。
                    //
                    // 「一帧都没转发出去」那种更极端的情况不归这里管：执行器的 empty_provider_stream
                    // 守卫在首字节之前就把它拦下了，那时还来得及换一条渠道，比在这里收尾更好。
                    .concatWith(Flux.<String>defer(() -> {
                        if (sawDeliverableContent.get()
                                || sawTerminalSignal.get()
                                || truncationTripped.get()) {
                            return Flux.empty();
                        }
                        return Flux.error(new ProviderAdapter.ProviderException(
                                502, "empty_provider_stream"));
                    }))
                    .concatWith(Flux.defer(() -> includeUsage
                            ? Flux.just(responseProtocol.writeJson(
                                    responseProtocol.usageChunk(
                                            prepared.publicModelAlias(),
                                            settlement.requestId(),
                                            settlement.startedAt(),
                                            settlement.usage())))
                            : Flux.<String>empty()))
                    .concatWith(Flux.defer(() -> truncationTripped.get()
                            ? Flux.<String>empty()
                            : Flux.just("[DONE]")))
                    // **首段之后的失败在这里被翻译成一个可解析的终态，而不是一次连接重置。**
                    //
                    // 走到这一支时 HTTP 200 与 text/event-stream 已经发出（响应提交发生在
                    // FirstChunkGate 之后），状态码改不了，把异常继续往下抛只会让客户端看到
                    // ECONNRESET —— 2026-10-14 桌面 A 样本记录的正是它：既没有原因，
                    // 也没有终态，客户端只能把一次失败读成一次可疑的成功。
                    // 因此这里转而做四件事：
                    //
                    //   * 结算**恰好一次**，走 fail()（它自行判定是否向客户计费：只有已收到
                    //     实际内容、或供应商已上报用量时才结算）；
                    //   * 发出一帧结构化失败帧，带稳定错误码、受控原因码与 request_id，
                    //     形状与非流式错误信封一致，客户端一个解析器即可覆盖两种形态；
                    //   * **不发 [DONE]** —— [DONE] 的语义是「这段回答完整结束」，
                    //     失败之后发它等于把断流重新包装成成功。注意 concatWithValues 在
                    //     onErrorResume **之前**，所以失败时它不会被求值；
                    //   * 不跨供应商续写：首段已发出后拼接另一家的答案会造成文字混合、
                    //     工具 ID 重复和重复计费（交接方案四.2）。
                    //
                    // 取消语义原样保留：fail() 的 finished CAS 保证只生效一次，因此
                    // doFinally 已把这次请求记为 CANCELLED 时这里不会重复结算；下游确实
                    // 已断开时这帧也只会被丢弃，不会有人继续写。
                    //
                    // 对外的原因用**流中断**来表述，而不是把内部异常原样
                    // 变成 internal_error：走到这里意味着「回答只写了一半就没了」，
                    // 而这个分类本身就告诉客户端「这是可重试的上游中断」。
                    // 传输层失败（上游掐断/SocketException）与网关自己的意外失败分开归类，
                    // 但都不把异常原文交给客户端——那既无用也可能带内部细节。
                    //
                    // **但兜底值不能冒充定责结论。** 此前这里对「不是传输层失败」的一切都写
                    // upstream_stream_interrupted，于是上游掐断、网关自身异常、客户端主动断开
                    // 三种含义挤在同一个词上，而且这条路径没有日志——2026-10-15 的三项复测
                    // 全部落在 3.4–9.6 秒、摘要全是这个词，排查因此只能往回猜。更要紧的是：
                    // 那个词按字面就是「上游流中断」，而这条分支恰恰是唯一证明不了上游中断的
                    // 地方，所以它把责任指向了唯一不该指向的对象。
                    // 现在改由 ChatStreamFailureClassifier 归类并留下证据（具体见该类注释）：
                    // 真传输层失败用 provider_unreachable / provider_attempt_timeout，客户端断开用
                    // client_aborted_stream，没人预料到的异常用 unknown_stream_failure，
                    // 同时把异常类名、根因与消息写进日志。error.code 不变，客户端分支无需改动。
                    // 日志只需要渠道的身份与超时配置，用未 materialized 的渠道即可：
                    // materialized() 会去解一次凭据，在失败收尾这一步不该再引入一次解密，
                    // 更不该让解密失败把本来能发出的失败帧再弄丢一次。
                    .onErrorResume(error -> {
                        // **上游在交付任何内容之前就断了 —— 先换下一条渠道，而不是认输。**
                        //
                        // 这是 2026-10-16 排查挖出来的那半个洞。走到这一支说明流已经过了第一个信号
                        // （不是一个块都没有，那种情况由上面 switchOnFirst 的第一支处理），
                        // 但 deliveredToClient 仍是 false：客户一个字都没收到。此时换渠道是安全的
                        // ——禁止跨供应商续写要防的是「文字混合」与「重复计费」，而两者都以
                        // 「已经交付过内容」为前提。生产数据里 11 次上游流中断的 first_token_ms
                        // 全是 NULL，全都是这种本可挽回的形状。
                        //
                        // 交付过内容之后仍然必须放弃：拼接另一家的答案会造成文字混合、
                        // 工具 ID 重复和重复计费（交接方案四.2）。那条契约由
                        // neverFailsOverAfterFirstByteAndReleasesInterruptedStream 钉住，不受本支影响。
                        if (!deliveredToClient.get() && index + 1 < plan.size()) {
                            return failoverAfterInterruption(
                                    body,
                                    prepared,
                                    plan,
                                    index,
                                    requestPreparer,
                                    requestLatency,
                                    settlement,
                                    error);
                        }
                        ProviderAdapter.ProviderException recorded =
                                ChatStreamFailureClassifier.classify(
                                        error,
                                        settlement.requestId(),
                                        index,
                                        prepared.publicModelAlias(),
                                        attemptChannel,
                                        settlement.startedAt());
                        return settlement.fail(recorded)
                                .onErrorResume(ignored -> Mono.empty())
                                .thenMany(failureFrame(recorded, settlement.requestId()));
                    })
                    .doOnComplete(() -> settlement.finish("SUCCEEDED", null));
    }

    /**
     * 把已提交的 SSE 流的失败收尾成「一帧失败帧 + 正常结束」，或彻底放弃发帧。
     *
     * <p>失败帧本身必须是**最后手段也不能反噬**的一步：它的序列化与下游写入都可能
     * 出错（帧构造失败、客户端正好在这一刻断开）。那时正确的行为是安静地结束，
     * 而**不是**把一个新的异常抛进一个已经提交的响应里——那会重新制造
     * {@code ECONNRESET}，也就是这段代码要消除的东西。
     */
    private Flux<String> failureFrame(Throwable error, String requestId) {
        return Flux.defer(() -> {
            try {
                return Flux.just(responseProtocol.writeJson(
                        responseProtocol.failureFrame(error, requestId)));
            } catch (RuntimeException unserializable) {
                return Flux.empty();
            }
        }).onErrorResume(ignored -> Flux.empty());
    }

    /**
     * 一个已解析的帧是不是失败帧。
     *
     * <p>给 {@link ResponsesService} 这类转换层用：它们必须能把聊天侧的失败
     * 翻译成自己的失败事件，而不是当成「没有 delta 的普通块」丢掉后照常报完成。
     * 判据只有一份（{@link ChatCompletionResponseProtocol#isFailureFrame}），
     * 免得两个包各自维护一个「像不像错误帧」的猜测。
     */
    boolean isFailureFrame(JsonNode value) {
        return responseProtocol.isFailureFrame(value);
    }

    /**
     * 一个已解析的帧是否宣告「被长度/预算截断」。
     *
     * <p>与 {@link #isFailureFrame} 同因：转换层必须能区分「正常写完」与「写了一半被上限
     * 切断」，否则它会在截断之后照常发完成事件。
     */
    boolean isTruncatedFrame(JsonNode value) {
        return responseProtocol.isTruncated(value);
    }

    private Mono<Void> releaseFailed(
            ApiKeyService.ApiKeyContext apiKey,
            PreparedRequest prepared,
            String requestId,
            QuotaService.Reservation reservation,
            RouteTarget target,
            long estimate,
            Instant startedAt,
            RequestLatency requestLatency,
            boolean stream,
            Throwable error) {
        ProviderAdapter.TokenUsage usage = usageEstimator.estimatedUsage(estimate);
        return quotaService.release(reservation.id(), QuotaService.ReleaseReason.UPSTREAM_FAILURE)
                .then(Mono.defer(() -> usageLogWriter.write(usageEntry(
                        apiKey, prepared, requestId, reservation, target, usage,
                        QuotaService.UsageQuality.ESTIMATED, stream, startedAt, requestLatency,
                        "FAILED", error))));
    }

    private Mono<RequestLimitService.LimitLease> acquire(
            ApiKeyService.ApiKeyContext apiKey, PreparedRequest prepared, RouteTarget target) {
        return requestLimitService.acquire(new RequestLimitService.LimitCommand(
                apiKey.apiKeyId(), apiKey.userId(), prepared.publicModelAlias(), target.channel().id(),
                prepared.rpm(), prepared.concurrency(), Duration.ofMinutes(2),
                prepared.dailyLimit(), prepared.tokenLimit()));
    }

    private Mono<RequestLimitService.LimitLease> rebindWithinDeadline(
            RequestLimitService.LimitLease lease, UUID channelId, Instant startedAt) {
        return Mono.defer(() -> {
            Duration remaining = remainingAttemptTime(startedAt);
            if (remaining.isZero()) {
                return Mono.error(attemptTimeout());
            }
            return requestLimitService.rebindChannel(lease, channelId)
                    .timeout(remaining, Mono.error(attemptTimeout()), timeoutScheduler);
        });
    }

    private Mono<RequestLimitService.LimitLease> bindWithinDeadline(
            RequestLimitService.LimitLease lease,
            RouteTarget target,
            Instant startedAt,
            boolean routeActivation) {
        if (!routeActivation) {
            return rebindWithinDeadline(lease, target.channel().id(), startedAt);
        }
        return Mono.defer(() -> {
            Duration remaining = remainingAttemptTime(startedAt);
            if (remaining.isZero()) {
                return Mono.error(attemptTimeout());
            }
            return requestLimitService.activateRoute(
                            lease, target.channel().id(),
                            target.rpmLimit(), target.concurrencyLimit())
                    .timeout(remaining, Mono.error(attemptTimeout()), timeoutScheduler);
        });
    }

    private Mono<Void> releaseIfAdmitted(PublicAdmission admission) {
        return publicAdmissionLifecycle.release(state(admission));
    }

    private Mono<Void> releaseLease(
            RequestLimitService.LimitLease lease, PublicAdmission admission) {
        return publicAdmissionLifecycle.releaseLease(lease, state(admission));
    }

    private boolean samePublicModel(
            PreparedRequest routed, PreparedPublicRequest admitted) {
        return publicAdmissionLifecycle.samePublicModel(
                routed.publicModelId(), routed.publicModelAlias(), routed.maxOutputTokens(),
                routed.packageMaxOutputTokens(),
                admitted.publicModelId(), admitted.publicModelAlias(), admitted.maxOutputTokens(),
                admitted.packageMaxOutputTokens());
    }

    private Mono<QuotaService.Reservation> reserve(
            ApiKeyService.ApiKeyContext apiKey,
            PreparedRequest prepared,
            String requestId,
            long estimate,
            long inputEstimate,
            PublicAdmission admission) {
        UUID reservationId = UUID.randomUUID();
        publicAdmissionLifecycle.associateReservation(state(admission), reservationId);
        return quotaService.reserve(new QuotaService.ReserveCommand(
                reservationId, requestId, apiKey.tenantId(), apiKey.userId(), apiKey.apiKeyId(),
                prepared.publicModelAlias(), estimate, Instant.now().plus(reservationTtl),
                Math.min(inputEstimate, estimate), Math.max(0, estimate - Math.min(inputEstimate, estimate)),
                "TOKEN"))
                // 即使真实的 QuotaService 会强制执行该命令所要求的
                // 维度，也保留这个检查。它保护替代的目录/配额实现
                // （并让对话边界独立地 fail-closed）。
                .flatMap(reservation -> "TOKEN".equals(reservation.usageDimension())
                        ? Mono.just(reservation)
                        // 绝不在这里释放不匹配的预留：它可能属于一个
                        // 已被接受、结果仍然未知的视频请求。
                        : Mono.error(unprocessable("usage_dimension_mismatch")));
    }

    private List<RouteTarget> plan(
            PreparedRequest prepared, Set<ChannelRouter.Capability> capabilities) {
        ChannelRouter.Capability routingCapability = capabilities.contains(ChannelRouter.Capability.FILE)
                ? ChannelRouter.Capability.FILE
                : capabilities.contains(ChannelRouter.Capability.IMAGE)
                        ? ChannelRouter.Capability.IMAGE : ChannelRouter.Capability.TEXT;
        List<RouteTarget> eligible = prepared.targets().stream()
                .filter(target -> target.candidate().capabilities().containsAll(capabilities))
                .toList();
        List<ChannelRouter.RouteCandidate> selected = channelRouter.plan(
                new ChannelRouter.RouteRequest(prepared.routingModelAlias(), routingCapability, 3),
                eligible.stream().map(RouteTarget::candidate).toList());
        Map<UUID, RouteTarget> byChannel = new LinkedHashMap<>();
        eligible.forEach(target -> byChannel.put(target.channel().id(), target));
        return selected.stream().map(candidate -> byChannel.get(candidate.channelId())).toList();
    }

    private ChatPublicAdmissionLifecycle.AdmissionState state(PublicAdmission admission) {
        return admission == null ? null : admission.state;
    }

    private Duration remainingAttemptTime(Instant startedAt) {
        Duration margin = totalAttemptTimeout.compareTo(TIMEOUT_SCHEDULING_MARGIN) > 0
                ? TIMEOUT_SCHEDULING_MARGIN : totalAttemptTimeout.dividedBy(2);
        Duration remaining = totalAttemptTimeout
                .minus(Duration.between(startedAt, Instant.now()))
                .minus(margin);
        return remaining.isNegative() || remaining.isZero() ? Duration.ZERO : remaining;
    }

    private ProviderAdapter.ProviderException attemptTimeout() {
        return new ProviderAdapter.ProviderException(504, "provider_attempt_timeout");
    }

    private UsageEntry usageEntry(
            ApiKeyService.ApiKeyContext apiKey,
            PreparedRequest prepared,
            String requestId,
            QuotaService.Reservation reservation,
            RouteTarget target,
            ProviderAdapter.TokenUsage usage,
            QuotaService.UsageQuality quality,
            boolean stream,
            Instant startedAt,
            RequestLatency requestLatency,
            String outcome,
            Throwable error) {
        return new UsageEntry(
                requestId, reservation.id(), apiKey.tenantId(), apiKey.userId(), apiKey.apiKeyId(),
                apiKey.packageInstanceId(), prepared.publicModelId(), prepared.publicModelAlias(),
                prepared.routingModelId(), prepared.routingModelAlias(),
                !prepared.publicModelId().equals(prepared.routingModelId()),
                target.channel().upstreamModel(), target.channel().providerId(), target.channel().id(),
                usage, quality, stream,
                null, Math.max(0, Duration.between(startedAt, Instant.now()).toMillis()),
                requestLatency.platformAddedLatencyMs(),
                "SUCCEEDED".equals(outcome) ? 200 : 502, outcome,
                error == null ? null : error.getClass().getSimpleName(),
                usageAccounting.safeErrorSummary(error),
                // 这条重载**没有请求体、也没有上限计算结果**：它在"上限还没算出来"的
                // 路径上使用，因此诊断字段只能留空（读作"未知"），而不是猜一个值填进去。
                // 猜一个值比留空更糟——运维会照着一个编造的上限去查上游。
                "SUCCEEDED".equals(outcome) ? "completed" : "failed",
                null,
                null,
                null,
                null,
                List.of());
    }

    private UsageEntry usageEntry(
            ApiKeyService.ApiKeyContext apiKey,
            PreparedRequest prepared,
            String requestId,
            QuotaService.Reservation reservation,
            RouteTarget target,
            ProviderAdapter.TokenUsage usage,
            QuotaService.UsageQuality quality,
            boolean stream,
            Instant startedAt,
            RequestLatency requestLatency,
            String outcome,
            Throwable error,
            List<UsageStage> stages) {
        return new UsageEntry(
                requestId, reservation.id(), apiKey.tenantId(), apiKey.userId(), apiKey.apiKeyId(),
                apiKey.packageInstanceId(), prepared.publicModelId(), prepared.publicModelAlias(),
                prepared.routingModelId(), prepared.routingModelAlias(),
                !prepared.publicModelId().equals(prepared.routingModelId()),
                target.channel().upstreamModel(), target.channel().providerId(), target.channel().id(),
                usage, quality, stream,
                null, Math.max(0, Duration.between(startedAt, Instant.now()).toMillis()),
                requestLatency.platformAddedLatencyMs(),
                "SUCCEEDED".equals(outcome) ? 200 : 502, outcome,
                error == null ? null : error.getClass().getSimpleName(),
                usageAccounting.safeErrorSummary(error),
                // 这条重载**没有请求体、也没有上限计算结果**：它在"上限还没算出来"的
                // 路径上使用，因此诊断字段只能留空（读作"未知"），而不是猜一个值填进去。
                // 猜一个值比留空更糟——运维会照着一个编造的上限去查上游。
                "SUCCEEDED".equals(outcome) ? "completed" : "failed",
                null,
                null,
                null,
                null,
                stages);
    }

    private ChatCompletionSettlement.Target completionTarget(
            PreparedRequest prepared, RouteTarget target) {
        return new ChatCompletionSettlement.Target(
                prepared.routingModelId(),
                prepared.routingModelAlias(),
                target.channel().upstreamModel(),
                target.channel().providerId(),
                target.channel().id());
    }

    private UsageEntry completionUsageEntry(
            ApiKeyService.ApiKeyContext apiKey,
            PreparedRequest prepared,
            String requestId,
            QuotaService.Reservation reservation,
            RequestLatency requestLatency,
            ChatCompletionSettlement.Report report) {
        ChatCompletionSettlement.Target target = report.target();
        List<UsageStage> stages = report.stages().stream()
                .map(stage -> new UsageStage(
                        "FINAL_RESPONSE",
                        stage.modelId(),
                        stage.logicalModelCode(),
                        stage.upstreamModel(),
                        stage.providerId(),
                        stage.channelId(),
                        stage.usage(),
                        stage.quality(),
                        stage.durationMs(),
                        stage.outcome(),
                        stage.errorCategory(),
                        stage.customerBillable(),
                        stage.providerBillable()))
                .toList();
        return new UsageEntry(
                requestId,
                reservation.id(),
                apiKey.tenantId(),
                apiKey.userId(),
                apiKey.apiKeyId(),
                apiKey.packageInstanceId(),
                prepared.publicModelId(),
                prepared.publicModelAlias(),
                prepared.routingModelId(),
                prepared.routingModelAlias(),
                !prepared.publicModelId().equals(prepared.routingModelId()),
                target.upstreamModel(),
                target.providerId(),
                target.channelId(),
                report.usage(),
                report.quality(),
                false,
                null,
                report.durationMs(),
                requestLatency.platformAddedLatencyMs(),
                "SUCCEEDED".equals(report.outcome()) ? 200 : 502,
                report.outcome(),
                report.error() == null ? null : report.error().getClass().getSimpleName(),
                usageAccounting.safeErrorSummary(report.error()),
                report.generationStatus(),
                report.finishReason(),
                report.effectiveMaxOutputTokens(),
                report.ceilingSource(),
                report.attemptCount(),
                stages);
    }

    private ChatStreamSettlement.Target streamTarget(
            PreparedRequest prepared, RouteTarget target) {
        return new ChatStreamSettlement.Target(
                prepared.routingModelId(),
                prepared.routingModelAlias(),
                target.channel().upstreamModel(),
                target.channel().providerId(),
                target.channel().id());
    }

    private UsageEntry streamUsageEntry(
            ApiKeyService.ApiKeyContext apiKey,
            PreparedRequest prepared,
            String requestId,
            QuotaService.Reservation reservation,
            RequestLatency requestLatency,
            ChatStreamSettlement.Report report) {
        ChatStreamSettlement.Target target = report.target();
        List<UsageStage> stages = report.stages().stream()
                .map(stage -> new UsageStage(
                        "FINAL_RESPONSE",
                        stage.modelId(),
                        stage.logicalModelCode(),
                        stage.upstreamModel(),
                        stage.providerId(),
                        stage.channelId(),
                        stage.usage(),
                        stage.quality(),
                        stage.durationMs(),
                        stage.outcome(),
                        stage.errorCategory(),
                        stage.customerBillable(),
                        stage.providerBillable()))
                .toList();
        return new UsageEntry(
                requestId,
                reservation.id(),
                apiKey.tenantId(),
                apiKey.userId(),
                apiKey.apiKeyId(),
                apiKey.packageInstanceId(),
                prepared.publicModelId(),
                prepared.publicModelAlias(),
                prepared.routingModelId(),
                prepared.routingModelAlias(),
                !prepared.publicModelId().equals(prepared.routingModelId()),
                target.upstreamModel(),
                target.providerId(),
                target.channelId(),
                report.usage(),
                report.quality(),
                true,
                report.firstTokenMs(),
                report.durationMs(),
                requestLatency.platformAddedLatencyMs(),
                "SUCCEEDED".equals(report.outcome()) ? 200 : 502,
                report.outcome(),
                report.error() == null ? null : report.error().getClass().getSimpleName(),
                usageAccounting.safeErrorSummary(report.error()),
                report.generationStatus(),
                report.finishReason(),
                report.effectiveMaxOutputTokens(),
                report.ceilingSource(),
                report.attemptCount(),
                stages);
    }

    private ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private ResponseStatusException catalogUnavailable(PublicAdmission admission) {
        return admission == null
                ? unavailable("no_usable_channel")
                : unavailable("multimodal_route_unavailable");
    }

    private ResponseStatusException routeUnavailable(PublicAdmission admission) {
        return unavailable(admission == null
                ? "no_usable_channel" : "multimodal_route_unavailable");
    }

    private ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }

    public interface GatewayCatalog {
        Mono<PreparedRequest> prepare(
                ApiKeyService.ApiKeyContext apiKey,
                String publicModelAlias,
                String routingModelAlias,
                Set<ChannelRouter.Capability> capabilities);

        default Mono<PreparedPublicRequest> preparePublic(
                ApiKeyService.ApiKeyContext apiKey, String publicModelAlias) {
            return Mono.error(new UnsupportedOperationException(
                    "public_admission_query_not_supported"));
        }
    }

    public interface UsageLogWriter {
        Mono<Void> write(UsageEntry entry);
    }

    @FunctionalInterface
    public interface TargetRequestPreparer {
        Mono<JsonNode> prepare(
                JsonNode body, RouteTarget target, ProviderAdapter adapter);
    }

    public record PreparedRequest(
            UUID publicModelId,
            String publicModelAlias,
            UUID routingModelId,
            String routingModelAlias,
            int maxOutputTokens,
            RequestLimitService.Thresholds rpm,
            RequestLimitService.Thresholds concurrency,
            RequestLimitService.PackageDailyLimit dailyLimit,
            RequestLimitService.PackageTokenLimit tokenLimit,
            List<RouteTarget> targets,
            Integer packageMaxOutputTokens) {
        public PreparedRequest {
            targets = List.copyOf(targets);
            dailyLimit = dailyLimit == null
                    ? RequestLimitService.PackageDailyLimit.unlimited() : dailyLimit;
            tokenLimit = tokenLimit == null
                    ? RequestLimitService.PackageTokenLimit.unlimited() : tokenLimit;
        }

        public PreparedRequest(
                UUID publicModelId,
                String publicModelAlias,
                UUID routingModelId,
                String routingModelAlias,
                int maxOutputTokens,
                RequestLimitService.Thresholds rpm,
                RequestLimitService.Thresholds concurrency,
                RequestLimitService.PackageDailyLimit dailyLimit,
                RequestLimitService.PackageTokenLimit tokenLimit,
                List<RouteTarget> targets) {
            this(publicModelId, publicModelAlias, routingModelId, routingModelAlias,
                    maxOutputTokens, rpm, concurrency, dailyLimit, tokenLimit, targets, null);
        }

        public PreparedRequest(
                UUID publicModelId,
                String publicModelAlias,
                UUID routingModelId,
                String routingModelAlias,
                int maxOutputTokens,
                RequestLimitService.Thresholds rpm,
                RequestLimitService.Thresholds concurrency,
                RequestLimitService.PackageDailyLimit dailyLimit,
                List<RouteTarget> targets) {
            this(publicModelId, publicModelAlias, routingModelId, routingModelAlias,
                    maxOutputTokens, rpm, concurrency, dailyLimit,
                    RequestLimitService.PackageTokenLimit.unlimited(), targets, null);
        }

        public PreparedRequest(
                UUID publicModelId,
                String publicModelAlias,
                UUID routingModelId,
                String routingModelAlias,
                int maxOutputTokens,
                RequestLimitService.Thresholds rpm,
                RequestLimitService.Thresholds concurrency,
                List<RouteTarget> targets) {
            this(publicModelId, publicModelAlias, routingModelId, routingModelAlias,
                    maxOutputTokens, rpm, concurrency,
                    RequestLimitService.PackageDailyLimit.unlimited(), targets);
        }

        public PreparedRequest(
                UUID modelId,
                String modelAlias,
                int maxOutputTokens,
                RequestLimitService.Thresholds rpm,
                RequestLimitService.Thresholds concurrency,
                List<RouteTarget> targets) {
            this(modelId, modelAlias, modelId, modelAlias,
                    maxOutputTokens, rpm, concurrency,
                    RequestLimitService.PackageDailyLimit.unlimited(), targets);
        }
    }

    public record PreparedPublicRequest(
            UUID publicModelId,
            String publicModelAlias,
            int maxOutputTokens,
            RequestLimitService.Thresholds rpm,
            RequestLimitService.Thresholds concurrency,
            RequestLimitService.PackageDailyLimit dailyLimit,
            RequestLimitService.PackageTokenLimit tokenLimit,
            Integer packageMaxOutputTokens) {

        public PreparedPublicRequest {
            dailyLimit = dailyLimit == null
                    ? RequestLimitService.PackageDailyLimit.unlimited() : dailyLimit;
            tokenLimit = tokenLimit == null
                    ? RequestLimitService.PackageTokenLimit.unlimited() : tokenLimit;
        }

        public PreparedPublicRequest(
                UUID publicModelId,
                String publicModelAlias,
                int maxOutputTokens,
                RequestLimitService.Thresholds rpm,
                RequestLimitService.Thresholds concurrency,
                RequestLimitService.PackageDailyLimit dailyLimit,
                RequestLimitService.PackageTokenLimit tokenLimit) {
            this(publicModelId, publicModelAlias, maxOutputTokens, rpm, concurrency,
                    dailyLimit, tokenLimit, null);
        }

        public PreparedPublicRequest(
                UUID publicModelId,
                String publicModelAlias,
                int maxOutputTokens,
                RequestLimitService.Thresholds rpm,
                RequestLimitService.Thresholds concurrency,
                RequestLimitService.PackageDailyLimit dailyLimit) {
            this(publicModelId, publicModelAlias, maxOutputTokens, rpm, concurrency, dailyLimit,
                    RequestLimitService.PackageTokenLimit.unlimited(), null);
        }

        public PreparedPublicRequest(
                UUID publicModelId,
                String publicModelAlias,
                int maxOutputTokens,
                RequestLimitService.Thresholds rpm,
                RequestLimitService.Thresholds concurrency) {
            this(publicModelId, publicModelAlias, maxOutputTokens, rpm, concurrency,
                    RequestLimitService.PackageDailyLimit.unlimited(), null);
        }
    }

    public static final class PublicAdmission {
        private final PreparedPublicRequest prepared;
        private final ChatPublicAdmissionLifecycle.AdmissionState state;

        public PublicAdmission(
                PreparedPublicRequest prepared, RequestLimitService.LimitLease lease) {
            this(prepared, new ChatPublicAdmissionLifecycle.AdmissionState(lease));
        }

        private PublicAdmission(
                PreparedPublicRequest prepared,
                ChatPublicAdmissionLifecycle.AdmissionState state) {
            if (prepared == null || state == null) {
                throw new IllegalArgumentException("invalid_public_admission");
            }
            this.prepared = prepared;
            this.state = state;
        }

        public PreparedPublicRequest prepared() {
            return prepared;
        }

        public RequestLimitService.LimitLease lease() {
            return state.lease();
        }

        public boolean claimed() {
            return state.claimed();
        }
    }

    @FunctionalInterface
    public interface ChannelCredential {
        String resolve();
    }

    public record RouteTarget(
            ChannelRouter.RouteCandidate candidate,
            ProviderAdapter.Channel channel,
            int rpmLimit,
            int concurrencyLimit,
            ChannelCredential credential) {
        public RouteTarget(
                ChannelRouter.RouteCandidate candidate,
                ProviderAdapter.Channel channel,
                int rpmLimit,
                int concurrencyLimit) {
            this(candidate, channel, rpmLimit, concurrencyLimit, channel::apiKey);
        }

        public RouteTarget(ChannelRouter.RouteCandidate candidate, ProviderAdapter.Channel channel) {
            this(candidate, channel, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        public ProviderAdapter.Channel materializedChannel() {
            return new ProviderAdapter.Channel(
                    channel.id(), channel.providerId(), channel.providerType(), channel.baseUrl(),
                    credential.resolve(), channel.timeoutMs(), channel.upstreamModel());
        }

        RouteTarget materialized() {
            return new RouteTarget(candidate, materializedChannel(), rpmLimit, concurrencyLimit);
        }
    }

    public record CompletionResult(JsonNode body, String requestId) { }

    public record UsageEntry(
            String requestId,
            UUID reservationId,
            UUID tenantId,
            UUID userId,
            UUID apiKeyId,
            UUID packageInstanceId,
            UUID modelId,
            String modelCode,
            UUID routingModelId,
            String routingModelCode,
            boolean composite,
            String upstreamModel,
            UUID providerId,
            UUID channelId,
            ProviderAdapter.TokenUsage usage,
            QuotaService.UsageQuality quality,
            boolean stream,
            Long firstTokenMs,
            long durationMs,
            long platformAddedLatencyMs,
            int httpStatus,
            String outcome,
            String errorCategory,
            String errorSummary,
            /**
             * 生成终态 {@code completed} / {@code failed} / {@code truncated}。
             *
             * <p>与 {@code outcome}（计量口径）和 {@code httpStatus}（传输口径）刻意分开：
             * 被输出上限截断的请求照常计量、照常 HTTP 200，但它并不是一次完成的生成。
             * 缺了这一列，导出报表就无法把两者分开——2026-10-15 的排查正是因为这一点，
             * 无法从中台数据回答"这次到底截断没有"，而桌面端对同一次请求明确记录了
             * {@code finish_reason=length} 与 {@code model_output_truncated}。
             */
            String generationStatus,
            /** 上游给出的结束原因（{@code stop} / {@code length} …）；未观察到为 null。 */
            String finishReason,
            /** 本次请求实际被准入的输出上限：四层 {@code min()} 的结果。 */
            Long effectiveMaxOutputTokens,
            /** 上限的来源：{@code client} / {@code model} / {@code package} / {@code system}。 */
            String ceilingSource,
            /** 这次请求一共产生了几次供应商调用（含重试与换渠道）。 */
            Integer attemptCount,
            List<UsageStage> stages) {

        public UsageEntry {
            stages = List.copyOf(stages);
        }

        /**
         * 不带诊断字段（T5/T3，V116）的构造：那些值一律为"未知"。
         *
         * <p>存在的理由是**调用点的数量**：新增五个字段会让每一处构造都要多写五个参数，
         * 而那些调用点大多与"这一次生成完成了没有、上限是谁定的"毫无关系（例如只关心
         * 日志不泄露敏感串的用例）。让它们显式写 null 并不会让测试更有意义，只会把
         * 这五个字段塞进每一处。
         *
         * <p>注意 {@code generationStatus} **不是**留空：它可以从 {@code outcome} 如实
         * 推出（SUCCEEDED 即 completed，否则 failed）。真正无法推出的是"是否被截断"
         * 与上限来源，那两者留 null 表示未知——而不是猜一个值。
         */
        public UsageEntry(
                String requestId,
                UUID reservationId,
                UUID tenantId,
                UUID userId,
                UUID apiKeyId,
                UUID packageInstanceId,
                UUID modelId,
                String modelCode,
                UUID routingModelId,
                String routingModelCode,
                boolean composite,
                String upstreamModel,
                UUID providerId,
                UUID channelId,
                ProviderAdapter.TokenUsage usage,
                QuotaService.UsageQuality quality,
                boolean stream,
                Long firstTokenMs,
                long durationMs,
                long platformAddedLatencyMs,
                int httpStatus,
                String outcome,
                String errorCategory,
                String errorSummary,
                List<UsageStage> stages) {
            this(requestId, reservationId, tenantId, userId, apiKeyId, packageInstanceId,
                    modelId, modelCode, routingModelId, routingModelCode, composite,
                    upstreamModel, providerId, channelId, usage, quality, stream,
                    firstTokenMs, durationMs, platformAddedLatencyMs, httpStatus, outcome,
                    errorCategory, errorSummary,
                    "SUCCEEDED".equals(outcome) ? "completed" : "failed",
                    null, null, null, null, stages);
        }

        public UsageEntry(
                String requestId,
                UUID reservationId,
                UUID tenantId,
                UUID userId,
                UUID apiKeyId,
                UUID packageInstanceId,
                UUID modelId,
                String modelCode,
                UUID routingModelId,
                String routingModelCode,
                boolean composite,
                String upstreamModel,
                UUID providerId,
                UUID channelId,
                ProviderAdapter.TokenUsage usage,
                QuotaService.UsageQuality quality,
                boolean stream,
                Long firstTokenMs,
                long durationMs,
                long platformAddedLatencyMs,
                int httpStatus,
                String outcome,
                String errorCategory,
                String errorSummary) {
            this(requestId, reservationId, tenantId, userId, apiKeyId, packageInstanceId,
                    modelId, modelCode, routingModelId, routingModelCode, composite,
                    upstreamModel, providerId, channelId, usage, quality, stream,
                    firstTokenMs, durationMs, platformAddedLatencyMs, httpStatus, outcome,
                    errorCategory, errorSummary,
                    // 这条辅助构造器（测试与内部调用用）没有上限上下文，因此生成终态只能
                    // 由 outcome 推出，上限与尝试次数留空表示"未知"。
                    "SUCCEEDED".equals(outcome) ? "completed" : "failed",
                    null, null, null, null,
                    composite ? List.of(new UsageStage(
                            "FINAL_RESPONSE", routingModelId, routingModelCode,
                            upstreamModel, providerId, channelId, usage, quality,
                            durationMs, outcome, errorCategory, true, true)) : List.of());
        }

        public UsageEntry(
                String requestId,
                UUID reservationId,
                UUID tenantId,
                UUID userId,
                UUID apiKeyId,
                UUID packageInstanceId,
                UUID modelId,
                String modelCode,
                String upstreamModel,
                UUID providerId,
                UUID channelId,
                ProviderAdapter.TokenUsage usage,
                QuotaService.UsageQuality quality,
                boolean stream,
                Long firstTokenMs,
                long durationMs,
                long platformAddedLatencyMs,
                int httpStatus,
                String outcome,
                String errorCategory,
                String errorSummary) {
            this(requestId, reservationId, tenantId, userId, apiKeyId, packageInstanceId,
                    modelId, modelCode, modelId, modelCode, false, upstreamModel,
                    providerId, channelId, usage, quality, stream, firstTokenMs,
                    durationMs, platformAddedLatencyMs, httpStatus, outcome,
                    errorCategory, errorSummary);
        }
    }

    public record UsageStage(
            String stageType,
            UUID modelId,
            String logicalModelCode,
            String upstreamModel,
            UUID providerId,
            UUID channelId,
            ProviderAdapter.TokenUsage usage,
            QuotaService.UsageQuality quality,
            long durationMs,
            String outcome,
            String errorCategory,
            boolean customerBillable,
            boolean providerBillable)
            implements ChatCompletionUsageAccounting.CustomerUsageStage {

        public UsageStage(
                String stageType,
                UUID modelId,
                String logicalModelCode,
                String upstreamModel,
                UUID providerId,
                UUID channelId,
                ProviderAdapter.TokenUsage usage,
                QuotaService.UsageQuality quality,
                long durationMs,
                String outcome,
                String errorCategory,
                boolean billable) {
            this(stageType, modelId, logicalModelCode, upstreamModel, providerId, channelId,
                    usage, quality, durationMs, outcome, errorCategory, billable, billable);
        }

        public boolean billable() {
            return customerBillable;
        }
    }

    private record ReservedInvocation(
            QuotaService.Reservation reservation, long inputEstimate, long reservationEstimate) { }

    private static final class RequestLatency {
        private final long startedNanos = System.nanoTime();
        private final AtomicLong providerNanos = new AtomicLong();

        <T> Mono<T> measure(Mono<T> providerCall) {
            return Mono.defer(() -> {
                long attemptStarted = System.nanoTime();
                AtomicBoolean recorded = new AtomicBoolean();
                Runnable record = () -> recordProviderTime(recorded, attemptStarted);
                return providerCall
                        .doOnSuccess(ignored -> record.run())
                        .doOnError(ignored -> record.run())
                        .doOnCancel(record);
            });
        }

        <T> Flux<T> measure(Flux<T> providerCall) {
            return Flux.defer(() -> {
                long attemptStarted = System.nanoTime();
                AtomicBoolean recorded = new AtomicBoolean();
                Runnable record = () -> recordProviderTime(recorded, attemptStarted);
                return providerCall
                        .doOnComplete(record)
                        .doOnError(ignored -> record.run())
                        .doOnCancel(record);
            });
        }

        private void recordProviderTime(AtomicBoolean recorded, long attemptStarted) {
            if (recorded.compareAndSet(false, true)) {
                providerNanos.addAndGet(Math.max(0, System.nanoTime() - attemptStarted));
            }
        }

        long platformAddedLatencyMs() {
            long totalNanos = Math.max(0, System.nanoTime() - startedNanos);
            return TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0, totalNanos - providerNanos.get()));
        }
    }
}
