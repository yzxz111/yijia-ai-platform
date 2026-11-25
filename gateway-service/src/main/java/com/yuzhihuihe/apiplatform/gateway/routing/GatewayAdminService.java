package com.yuzhihuihe.apiplatform.gateway.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.AdminListQuery;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateChannelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateModelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateProviderCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.ModelRouteConfiguration;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.OnboardRouteCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.OnboardRoutesCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.SaveModelConfigurationCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.UpdateChannelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.UpdateModelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.UpstreamModelProbeCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelModelProbeView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelModelTestView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelTestView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ModelView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.PageResult;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ProviderDeletionView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ProviderTypeCapabilitiesView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ProviderView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.RouteOnboardingResult;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.RoutesOnboardingView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.RoutingTargetView;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class GatewayAdminService {

    private static final Set<String> CHANNEL_ENVIRONMENTS =
            Set.of("LOCAL", "SANDBOX", "PRODUCTION");
    private static final Set<String> PROVIDER_TYPES =
            Set.of("DEEPSEEK", "BAILIAN", "MOCK", "TIANYIYUN");

    private final GatewayAdminRepository repository;
    private final TransactionalOperator transactions;
    private final CredentialCipher cipher;
    private final PlatformPackageModelClient packageModels;
    private final ProviderCapabilityCatalog capabilities;
    private final WebClient.Builder webClientBuilder;
    private final ChannelConnectivityProbe connectivityProbe;

    /** 真实模型调用测试的宿主；测试构造器传 null 时该能力不可用（见 testChannelModel）。 */
    private final ChannelModelTestService modelTests;

    @Autowired
    public GatewayAdminService(
            GatewayAdminRepository repository,
            TransactionalOperator transactions,
            CredentialCipher cipher,
            PlatformPackageModelClient packageModels,
            ProviderCapabilityCatalog capabilities,
            WebClient.Builder webClientBuilder,
            ChannelModelTestService modelTests,
            @Value("${gateway.channel.local-host-allowlist:}") String localHostAllowlist) {
        this.repository = repository;
        this.transactions = transactions;
        this.cipher = cipher;
        this.packageModels = packageModels;
        this.capabilities = capabilities;
        this.webClientBuilder = webClientBuilder;
        this.modelTests = modelTests;
        this.connectivityProbe = new ChannelConnectivityProbe(localHostAllowlist);
    }

    GatewayAdminService(
            GatewayAdminRepository repository,
            TransactionalOperator transactions,
            CredentialCipher cipher,
            PlatformPackageModelClient packageModels) {
        this(
                repository,
                transactions,
                cipher,
                packageModels,
                null,
                null,
                null,
                "localhost,127.0.0.1,::1");
    }

    GatewayAdminService(
            GatewayAdminRepository repository,
            TransactionalOperator transactions,
            CredentialCipher cipher,
            PlatformPackageModelClient packageModels,
            ProviderCapabilityCatalog capabilities,
            WebClient.Builder webClientBuilder) {
        this(
                repository,
                transactions,
                cipher,
                packageModels,
                capabilities,
                webClientBuilder,
                null,
                "localhost,127.0.0.1,::1");
    }

    /** 供应商类型的能力目录：控制台在「新增服务商」时用它做选型， 保存路由时用它做校验（见 {@link #assertProviderCanServe}）。 */
    public List<ProviderTypeCapabilitiesView> providerTypeCapabilities() {
        if (capabilities == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "provider_capability_catalog_unavailable");
        }
        return capabilities.describe();
    }

    /**
     * 用渠道里已保存的密钥向上游列一次模型：这是「填进去 key 就马上能用」的第一道确认。
     *
     * <p>密钥从库里解密后只用于这一次请求，绝不回显；上游 401/403 会被如实报告为 {@code keyValid=false}，而不是像连通性探测那样把错误密钥也显示成
     * HEALTHY。
     */
    public Mono<ChannelModelProbeView> probeChannelModels(UUID channelId) {
        if (channelId == null) {
            return Mono.error(invalid("channel_not_found"));
        }
        return repository
                .channelProbe(channelId)
                .switchIfEmpty(Mono.error(notFound("channel_not_found")))
                .flatMap(
                        row -> {
                            byte[] encrypted = row.encryptedApiKey();
                            if (encrypted == null || encrypted.length == 0) {
                                return Mono.error(invalid("channel_credential_required"));
                            }
                            if (webClientBuilder == null) {
                                return Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "channel_probe_unavailable"));
                            }
                            String key;
                            try {
                                key = cipher.decrypt(encrypted);
                            } catch (RuntimeException undecryptable) {
                                return Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "credential_decryption_failed"));
                            }
                            String baseUrl =
                                    row.baseUrl() == null
                                            ? ""
                                            : row.baseUrl().replaceAll("/+$", "");
                            if (baseUrl.isBlank()) {
                                return Mono.error(invalid("invalid_channel_base_url"));
                            }
                            // 渠道的 base_url 有的带 /v1 有的不带（例如天翼生图渠道存的是
                            // https://ai.ctaigw.cn）。只试一种拼法会把一个可用的 key 报成 401，
                            // 正是这个接口要消灭的那种误导，因此逐个候选试到成功为止。
                            final String resolvedBaseUrl = baseUrl;
                            return Flux.fromIterable(probeCandidates(resolvedBaseUrl))
                                    .concatMap(
                                            url ->
                                                    probeModelsOnce(
                                                            channelId, url, key, row.timeoutMs()))
                                    .takeUntil(ChannelModelProbeView::keyValid)
                                    .last();
                        });
    }

    /**
     * 上游模型清单的候选地址，按优先级排列。
     *
     * <p>OpenAI 兼容端点的根是 {@code {base}/models}；而很多渠道的 base_url 已经带了 版本段，也有一些（如天翼云）只存了裸域名，两者都要能试。
     */
    static List<String> probeCandidates(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        List<String> candidates = new ArrayList<>(2);
        candidates.add(normalized + "/models");
        if (!normalized.matches(".*/v\\d+$")) {
            candidates.add(normalized + "/v1/models");
        }
        return List.copyOf(candidates);
    }

    private Mono<ChannelModelProbeView> probeModelsOnce(
            UUID channelId, String url, String key, int timeoutMs) {
        return webClientBuilder
                .build()
                .get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(
                        response ->
                                response.statusCode().isError()
                                        ? response.releaseBody()
                                                .thenReturn(
                                                        new ChannelModelProbeView(
                                                                channelId,
                                                                false,
                                                                response.statusCode().value(),
                                                                List.of(),
                                                                "upstream_rejected"))
                                        : response.bodyToMono(JsonNode.class)
                                                .map(
                                                        body ->
                                                                new ChannelModelProbeView(
                                                                        channelId,
                                                                        true,
                                                                        200,
                                                                        modelIds(body),
                                                                        null)))
                .timeout(Duration.ofMillis(Math.min(Math.max(timeoutMs, 1_000), 30_000)))
                .onErrorReturn(
                        new ChannelModelProbeView(
                                channelId, false, 0, List.of(), "upstream_unreachable"));
    }

    /**
     * 用「还没保存的」地址与密钥探一次上游模型清单。
     *
     * <p>新增服务商时渠道还不存在，而「填进去能不能用」必须在保存之前就能回答， 否则运营只能先存一把错 key、再去踩一次 401。密钥只用于这次请求，不写库。
     */
    public Mono<ChannelModelProbeView> probeUpstreamModels(UpstreamModelProbeCommand command) {
        if (command == null || command.baseUrl() == null || command.baseUrl().isBlank()) {
            return Mono.error(invalid("invalid_channel_base_url"));
        }
        if (command.apiKey() == null || command.apiKey().isBlank()) {
            return Mono.error(invalid("channel_credential_required"));
        }
        if (webClientBuilder == null) {
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE, "channel_probe_unavailable"));
        }
        String baseUrl = command.baseUrl().strip().replaceAll("/+$", "");
        if (baseUrl.isBlank()) {
            return Mono.error(invalid("invalid_channel_base_url"));
        }
        return Flux.fromIterable(probeCandidates(baseUrl))
                .concatMap(url -> probeModelsOnce(null, url, command.apiKey().strip(), 15_000))
                .takeUntil(ChannelModelProbeView::keyValid)
                .last();
    }

    /**
     * 批量接入：把「平台别名 ← 上游模型」一次写全，并顺手把「接完还不能用」的原因讲清楚。
     *
     * <p>每条路由都过一遍能力守卫：渠道的供应商类型服务不了这个模型时**不会写入**， 而是以 {@code REJECTED} 返回原因。{@code notes}
     * 收集那些「写进去了但还没法真正 服务客户」的情况——视频别名缺按秒计价、同别名存在优先级不低的旧渠道， 它们不是失败，但沉默地接完才是真正的事故来源。
     */
    public Mono<RoutesOnboardingView> onboardRoutes(UUID channelId, OnboardRoutesCommand command) {
        if (channelId == null) {
            return Mono.error(invalid("channel_not_found"));
        }
        if (command == null || command.routes() == null || command.routes().isEmpty()) {
            return Mono.error(invalid("invalid_model_route"));
        }
        return repository
                .channelProbe(channelId)
                .switchIfEmpty(Mono.error(notFound("channel_not_found")))
                .flatMap(
                        channel ->
                                Flux.fromIterable(command.routes())
                                        .concatMap(
                                                route ->
                                                        onboardRoute(
                                                                channelId,
                                                                channel.providerType(),
                                                                route))
                                        .collectList()
                                        .flatMap(
                                                results ->
                                                        notesFor(channelId, results)
                                                                .map(
                                                                        notes ->
                                                                                new RoutesOnboardingView(
                                                                                        channelId,
                                                                                        results,
                                                                                        notes))));
    }

    private Mono<RouteOnboardingResult> onboardRoute(
            UUID channelId, String providerType, OnboardRouteCommand route) {
        String modelCode =
                route == null || route.modelCode() == null ? "" : route.modelCode().strip();
        String upstreamModel =
                route == null || route.upstreamModel() == null ? "" : route.upstreamModel().strip();
        if (modelCode.isBlank() || upstreamModel.isBlank()) {
            return Mono.just(
                    new RouteOnboardingResult(
                            modelCode, upstreamModel, "REJECTED", "invalid_model_route"));
        }
        boolean enabled = route.enabled() == null || route.enabled();
        return repository
                .modelId(modelCode)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(
                        maybeModelId ->
                                maybeModelId
                                        .map(
                                                modelId ->
                                                        onboardKnownModel(
                                                                channelId,
                                                                providerType,
                                                                route,
                                                                modelCode,
                                                                upstreamModel,
                                                                enabled,
                                                                modelId))
                                        .orElseGet(
                                                () ->
                                                        Mono.just(
                                                                new RouteOnboardingResult(
                                                                        modelCode,
                                                                        upstreamModel,
                                                                        "REJECTED",
                                                                        "model_not_found"))));
    }

    private Mono<RouteOnboardingResult> onboardKnownModel(
            UUID channelId,
            String providerType,
            OnboardRouteCommand route,
            String modelCode,
            String upstreamModel,
            boolean enabled,
            UUID modelId) {
        return repository
                .model(modelId)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(
                        maybeModel -> {
                            if (maybeModel.isEmpty()) {
                                return Mono.just(
                                        new RouteOnboardingResult(
                                                modelCode,
                                                upstreamModel,
                                                "REJECTED",
                                                "model_not_found"));
                            }
                            ModelView model = maybeModel.get();
                            if (capabilities != null) {
                                String reason =
                                        capabilities.unavailableReason(
                                                providerType, model.capabilities());
                                if (reason != null) {
                                    return Mono.just(
                                            new RouteOnboardingResult(
                                                    modelCode, upstreamModel, "REJECTED", reason));
                                }
                            }
                            return repository
                                    .mappingExists(channelId, modelId)
                                    .flatMap(
                                            existed ->
                                                    repository
                                                            .upsertChannelRoute(
                                                                    channelId,
                                                                    modelId,
                                                                    upstreamModel,
                                                                    enabled,
                                                                    route.priority(),
                                                                    route.weight())
                                                            .map(
                                                                    rows ->
                                                                            new RouteOnboardingResult(
                                                                                    modelCode,
                                                                                    upstreamModel,
                                                                                    rows != null
                                                                                                    && rows
                                                                                                            > 0
                                                                                            ? (existed
                                                                                                    ? "UPDATED"
                                                                                                    : "CREATED")
                                                                                            : "REJECTED",
                                                                                    rows != null
                                                                                                    && rows
                                                                                                            > 0
                                                                                            ? null
                                                                                            : "model_not_found")));
                        });
    }

    /**
     * 接入之后仍需运营处理的提示，逐条说清而不是让别名静默地不可售。
     *
     * <p>「缺一行按单位计价的费率卡」这条提示原本只认视频（{@code VIDEO} 能力）， 而**方案 C** 让音频别名也成了可以挂进套餐的东西——一个音频别名接入之后 没有
     * {@code AUDIO_SECOND} 费率行，症状与视频完全相同：路由建好了、 目录里看得见，直到平台发布守卫以 {@code provider_price_missing} 拒绝
     * 那个版本时才会暴露，而那已经是另一个工具、另一个时刻。 因此按**能力**分派到各自的维度，并把提示指向真正能修好它的地方： 视频有管理端点（{@code PUT
     * /video-aliases/{code}}），音频没有—— 它的费率行目前只由迁移播种，提示必须这么说，而不是指一个不存在的端点。
     */
    private Mono<List<String>> notesFor(UUID channelId, List<RouteOnboardingResult> results) {
        List<String> notes = new ArrayList<>();
        return Flux.fromIterable(results)
                .filter(result -> !"REJECTED".equals(result.status()))
                .concatMap(
                        result ->
                                repository
                                        .modelId(result.modelCode())
                                        .flatMap(modelId -> repository.model(modelId))
                                        .flatMap(
                                                model -> {
                                                    String pricedDimension =
                                                            pricedDimensionOf(model.capabilities());
                                                    Mono<Void> priceNote =
                                                            pricedDimension == null
                                                                    ? Mono.empty()
                                                                    : repository
                                                                            .hasCurrentUsagePrice(
                                                                                    result
                                                                                            .modelCode(),
                                                                                    pricedDimension)
                                                                            .flatMap(
                                                                                    has -> {
                                                                                        if (!has) {
                                                                                            notes
                                                                                                    .add(
                                                                                                            priceNoteFor(
                                                                                                                    result
                                                                                                                            .modelCode(),
                                                                                                                    pricedDimension));
                                                                                        }
                                                                                        return Mono
                                                                                                .empty();
                                                                                    });
                                                    return priceNote.then(
                                                            repository
                                                                    .higherPriorityChannels(
                                                                            result.modelCode(),
                                                                            channelId)
                                                                    .collectList()
                                                                    .flatMap(
                                                                            channels -> {
                                                                                if (!channels
                                                                                        .isEmpty()) {
                                                                                    notes.add(
                                                                                            result
                                                                                                            .modelCode()
                                                                                                    + " 还挂在优先级不低的渠道上："
                                                                                                    + String
                                                                                                            .join(
                                                                                                                    ", ",
                                                                                                                    channels)
                                                                                                    + "；新渠道的 priority 需要更高才会被选中");
                                                                                }
                                                                                return Mono.empty();
                                                                            }));
                                                })
                                        .then())
                .then(Mono.fromSupplier(() -> List.copyOf(notes)));
    }

    /**
     * 这组能力按哪个维度计价；不需要按单位计价的别名返回 null。
     *
     * <p>判据与平台的三个能力白名单一致：{@code VIDEO} 是按秒的视频， 三个音频能力按时长。两者都有时以视频为准（这种别名不存在，但顺序必须是
     * 确定的，否则提示会随机地指向两个维度之一）。
     */
    private static String pricedDimensionOf(Set<String> capabilities) {
        if (capabilities == null) {
            return null;
        }
        if (capabilities.contains("VIDEO")) {
            return "VIDEO_SECOND";
        }
        if (capabilities.contains("AUDIO_TRANSCRIBE")
                || capabilities.contains("AUDIO_SYNTHESIS")
                || capabilities.contains("LIP_SYNC")) {
            return "AUDIO_SECOND";
        }
        return null;
    }

    private static String priceNoteFor(String modelCode, String pricedDimension) {
        return "AUDIO_SECOND".equals(pricedDimension)
                ? modelCode
                        + " 是按秒计费的音频别名，还缺一行 AUDIO_SECOND 费率卡"
                        + "（目前由迁移播种，没有管理端点），在此之前它只能路由、不能计价"
                : modelCode
                        + " 是按秒计费的视频别名，还缺分辨率档位与"
                        + "每秒价格（PUT /video-aliases/"
                        + modelCode
                        + "），在此之前它只能路由、不能计价";
    }

    /**
     * 从上游应答里取模型 id。
     *
     * <p>同时容忍三种常见形状：OpenAI 的 {@code {"data":[{"id":...}]}}、 简写的 {@code {"data":["a","b"]}}，以及
     * {@code {"models":[...]}} —— 互联的第三方网关并不统一，而这里多容忍一种形状就少一次人工排查。
     */
    static List<String> modelIds(JsonNode body) {
        Set<String> ids = new LinkedHashSet<>();
        collectIds(body == null ? null : body.get("data"), ids);
        collectIds(body == null ? null : body.get("models"), ids);
        return List.copyOf(ids);
    }

    private static void collectIds(JsonNode node, Set<String> target) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            if (!node.asText().isBlank()) {
                target.add(node.asText().strip());
            }
            return;
        }
        if (!node.isArray()) {
            return;
        }
        for (JsonNode entry : node) {
            if (entry.isTextual() && !entry.asText().isBlank()) {
                target.add(entry.asText().strip());
            } else if (entry.isObject()
                    && entry.path("id").isTextual()
                    && !entry.path("id").asText().isBlank()) {
                target.add(entry.path("id").asText().strip());
            }
        }
    }

    /**
     * 保存路由前的能力守卫。
     *
     * <p>没有它，一个「能读图的 key 挂到没有对话适配器的供应商类型下」的配置会被静默保存， 然后在每一次真实请求上以 503 失败——控制台只显示渠道 HEALTHY。
     */
    private void assertProviderCanServe(ProviderView provider, Set<String> modelCapabilities) {
        if (capabilities == null) {
            return;
        }
        String reason = capabilities.unavailableReason(provider.providerType(), modelCapabilities);
        if (reason != null) {
            throw invalid(reason);
        }
    }

    public Mono<PageResult<ModelView>> listModels(AdminListQuery request) {
        return repository.listModels(request);
    }

    public Flux<ProviderView> listProviders() {
        return repository.listProviders();
    }

    public Mono<ProviderView> createProvider(CreateProviderCommand command, UUID actorUserId) {
        validateProvider(command);
        UUID id = UUID.randomUUID();
        ProviderView created =
                new ProviderView(
                        id,
                        command.code().strip(),
                        command.name().strip(),
                        command.providerType(),
                        command.baseUrl().strip(),
                        command.enabled());
        Mono<ProviderView> mutation =
                repository
                        .insertProvider(created)
                        .filter(updated -> updated == 1L)
                        .switchIfEmpty(Mono.error(conflict("provider_already_exists")))
                        .then(
                                repository.audit(
                                        actorUserId,
                                        "GATEWAY_PROVIDER_CREATED",
                                        "GATEWAY_PROVIDER",
                                        id,
                                        null,
                                        created,
                                        null))
                        .thenReturn(created);
        return transactions
                .transactional(mutation)
                .onErrorMap(
                        DataIntegrityViolationException.class,
                        error -> conflict("provider_already_exists"));
    }

    public Mono<Void> auditCircuitReset(
            String key, ChannelCircuitRegistry.CircuitHealth after, UUID actorUserId) {
        return repository.audit(
                actorUserId, "GATEWAY_CIRCUIT_RESET", "GATEWAY_CIRCUIT", key, null, after, null);
    }

    public Flux<RoutingTargetView> listRoutingTargets(String key) {
        return repository.routingTargets(routingModelAlias(key));
    }

    public Mono<RoutingTargetView> switchRoute(String key, UUID channelId, UUID actorUserId) {
        String modelAlias = routingModelAlias(key);
        return repository
                .selectedRoute(modelAlias)
                .map(this::routeSnapshot)
                .defaultIfEmpty(Map.of("mode", "AUTOMATIC"))
                .flatMap(
                        before ->
                                transactions.transactional(
                                        repository
                                                .switchRoute(modelAlias, channelId, actorUserId)
                                                .flatMap(
                                                        after ->
                                                                repository
                                                                        .audit(
                                                                                actorUserId,
                                                                                "GATEWAY_CIRCUIT_ROUTE_SWITCHED",
                                                                                "GATEWAY_CIRCUIT",
                                                                                key,
                                                                                before,
                                                                                routeSnapshot(
                                                                                        after),
                                                                                null)
                                                                        .thenReturn(after))));
    }

    public Mono<Void> restoreAutomaticRoute(String key, UUID actorUserId) {
        String modelAlias = routingModelAlias(key);
        return repository
                .selectedRoute(modelAlias)
                .map(this::routeSnapshot)
                .defaultIfEmpty(Map.of("mode", "AUTOMATIC"))
                .flatMap(
                        before ->
                                transactions.transactional(
                                        repository
                                                .clearRoute(modelAlias)
                                                .flatMap(
                                                        ignored ->
                                                                repository.audit(
                                                                        actorUserId,
                                                                        "GATEWAY_CIRCUIT_ROUTE_AUTOMATIC",
                                                                        "GATEWAY_CIRCUIT",
                                                                        key,
                                                                        before,
                                                                        Map.of("mode", "AUTOMATIC"),
                                                                        null))))
                .then();
    }

    private String routingModelAlias(String key) {
        if (blank(key)) {
            throw invalid("invalid_circuit_key");
        }
        int separator = key.indexOf(':');
        if (separator < 1 || separator == key.length() - 1) {
            throw invalid("invalid_circuit_key");
        }
        try {
            UUID.fromString(key.substring(0, separator));
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid_circuit_key");
        }
        return key.substring(separator + 1);
    }

    private Map<String, Object> routeSnapshot(RoutingTargetView target) {
        return Map.of(
                "channelId", target.channelId(),
                "channelCode", target.channelCode(),
                "channelName", target.channelName(),
                "providerName", target.providerName(),
                "upstreamModel", target.upstreamModel());
    }

    public Mono<ModelView> createModel(CreateModelCommand command, UUID actorUserId) {
        validateModel(
                command.code(),
                command.displayName(),
                command.capabilities(),
                command.contextWindow(),
                command.maxOutputTokens(),
                command.requestsPerMinute(),
                command.maxConcurrency());
        Set<String> aliases = aliases(command.code(), command.aliases());
        UUID id = UUID.randomUUID();
        Mono<ModelView> mutation =
                repository
                        .insertModel(id, command, aliases)
                        .then(repository.model(id))
                        .flatMap(
                                created ->
                                        repository
                                                .audit(
                                                        actorUserId,
                                                        "GATEWAY_MODEL_CREATED",
                                                        "GATEWAY_MODEL",
                                                        id,
                                                        null,
                                                        created,
                                                        null)
                                                .thenReturn(created));
        return transactions
                .transactional(mutation)
                .onErrorMap(
                        DataIntegrityViolationException.class,
                        error -> conflict("model_already_exists"));
    }

    public Mono<ModelView> updateModel(UpdateModelCommand command, UUID actorUserId) {
        if (command.id() == null) {
            return Mono.error(invalid("invalid_model"));
        }
        validateModel(
                "existing",
                command.displayName(),
                command.capabilities(),
                command.contextWindow(),
                command.maxOutputTokens(),
                command.requestsPerMinute(),
                command.maxConcurrency());
        Mono<ModelView> mutation =
                repository
                        .model(command.id())
                        .switchIfEmpty(Mono.error(notFound("model_not_found")))
                        .flatMap(
                                before -> {
                                    Set<String> aliases =
                                            command.aliases() == null
                                                    ? null
                                                    : aliases(before.code(), command.aliases());
                                    return repository
                                            .updateModel(command, before.code(), aliases)
                                            .then(repository.model(command.id()))
                                            .flatMap(
                                                    after ->
                                                            repository
                                                                    .audit(
                                                                            actorUserId,
                                                                            "GATEWAY_MODEL_UPDATED",
                                                                            "GATEWAY_MODEL",
                                                                            command.id(),
                                                                            before,
                                                                            after,
                                                                            null)
                                                                    .thenReturn(after));
                                });
        return transactions
                .transactional(mutation)
                .onErrorMap(
                        DataIntegrityViolationException.class,
                        error -> conflict("model_alias_already_exists"));
    }

    public Mono<ModelView> setModelEnabled(UUID id, boolean enabled, UUID actorUserId) {
        if (id == null || actorUserId == null) {
            return Mono.error(invalid("invalid_model"));
        }
        Mono<ModelView> mutation =
                repository
                        .model(id)
                        .switchIfEmpty(Mono.error(notFound("model_not_found")))
                        .flatMap(
                                before ->
                                        repository
                                                .updateModelEnabled(id, enabled)
                                                .then(repository.model(id))
                                                .flatMap(
                                                        after ->
                                                                repository
                                                                        .audit(
                                                                                actorUserId,
                                                                                enabled
                                                                                        ? "GATEWAY_MODEL_ENABLED"
                                                                                        : "GATEWAY_MODEL_DISABLED",
                                                                                "GATEWAY_MODEL",
                                                                                id,
                                                                                before,
                                                                                after,
                                                                                null)
                                                                        .thenReturn(after)));
        return transactions.transactional(mutation);
    }

    public Mono<Void> deleteModel(UUID id, UUID actorUserId, String accessToken) {
        if (id == null || actorUserId == null) {
            return Mono.error(invalid("invalid_model"));
        }
        return repository
                .model(id)
                .switchIfEmpty(Mono.error(notFound("model_not_found")))
                .flatMap(
                        before ->
                                repository
                                        .countCompositeModelReferences(id)
                                        .flatMap(
                                                compositeReferences -> {
                                                    if (compositeReferences > 0) {
                                                        return Mono.error(
                                                                conflict(
                                                                        "model_referenced_by_routing"));
                                                    }
                                                    return packageModels.references(
                                                            before.code(), accessToken);
                                                })
                                        .flatMap(
                                                references -> {
                                                    if (references.referenceCount() > 0) {
                                                        return Mono.error(
                                                                conflict(
                                                                        "model_referenced_by_package"));
                                                    }
                                                    Mono<Void> mutation =
                                                            repository
                                                                    .deleteModel(id)
                                                                    .onErrorMap(
                                                                            DataIntegrityViolationException
                                                                                    .class,
                                                                            error ->
                                                                                    conflict(
                                                                                            "model_has_usage_history"))
                                                                    .then(
                                                                            repository.audit(
                                                                                    actorUserId,
                                                                                    "GATEWAY_MODEL_DELETED",
                                                                                    "GATEWAY_MODEL",
                                                                                    id,
                                                                                    before,
                                                                                    Map.of(
                                                                                            "deleted",
                                                                                            true),
                                                                                    null));
                                                    return transactions.transactional(mutation);
                                                }));
    }

    /**
     * 删除一个服务商，用于清理废弃接入。
     *
     * <p>两条硬边界，都是为了让这项破坏性操作不伤到既有事实：
     *
     * <ul>
     *   <li>被用量、成本或生成任务引用过 → 409 {@code provider_has_usage_history}。
     *       这类服务商应当**停用**：那些行是计费与审计凭据，删掉服务商只会让它们变成孤儿。
     *   <li>名下还有渠道 → 409 {@code provider_has_channels}，除非显式 {@code cascade=true}。
     *       不默认级联是因为渠道可能还挂着在售模型，这个决定必须由调用方明确做出。
     * </ul>
     *
     * 级联删除只删配置（别名映射、渠道、该服务商的价格行），绝不触碰任何历史行； 被删内容计入审计快照与应答，便于事后核对。
     */
    public Mono<ProviderDeletionView> deleteProvider(
            UUID providerId, boolean cascade, UUID actorUserId) {
        if (providerId == null) {
            return Mono.error(invalid("provider_not_found"));
        }
        return repository
                .provider(providerId)
                .switchIfEmpty(Mono.error(notFound("provider_not_found")))
                .flatMap(
                        provider ->
                                repository
                                        .providerUsageReferences(providerId)
                                        .flatMap(
                                                references -> {
                                                    if (references > 0) {
                                                        return Mono.error(
                                                                conflict(
                                                                        "provider_has_usage_history"));
                                                    }
                                                    return repository
                                                            .channelIdsOfProvider(providerId)
                                                            .collectList()
                                                            .flatMap(
                                                                    channelIds -> {
                                                                        if (!channelIds.isEmpty()
                                                                                && !cascade) {
                                                                            return Mono.error(
                                                                                    conflict(
                                                                                            "provider_has_channels"));
                                                                        }
                                                                        return deleteProviderConfiguration(
                                                                                provider,
                                                                                channelIds,
                                                                                actorUserId);
                                                                    });
                                                }));
    }

    private Mono<ProviderDeletionView> deleteProviderConfiguration(
            ProviderView provider, List<UUID> channelIds, UUID actorUserId) {
        Mono<ProviderDeletionView> mutation =
                repository
                        .countChannelModels(channelIds)
                        .flatMap(
                                routes ->
                                        repository
                                                .deleteChannelScopedRows(channelIds)
                                                .then(
                                                        repository.deleteChannelsOfProvider(
                                                                provider.id()))
                                                .flatMap(
                                                        channels ->
                                                                repository
                                                                        .deleteProviderPrices(
                                                                                provider.id())
                                                                        .flatMap(
                                                                                prices ->
                                                                                        repository
                                                                                                .deleteProvider(
                                                                                                        provider
                                                                                                                .id())
                                                                                                .flatMap(
                                                                                                        deleted ->
                                                                                                                deleted
                                                                                                                                > 0
                                                                                                                        ? repository
                                                                                                                                .audit(
                                                                                                                                        actorUserId,
                                                                                                                                        "GATEWAY_PROVIDER_DELETED",
                                                                                                                                        "GATEWAY_PROVIDER",
                                                                                                                                        provider
                                                                                                                                                .id(),
                                                                                                                                        provider,
                                                                                                                                        Map
                                                                                                                                                .of(
                                                                                                                                                        "deletedChannels",
                                                                                                                                                                channels,
                                                                                                                                                        "deletedRoutes",
                                                                                                                                                                routes,
                                                                                                                                                        "deletedPrices",
                                                                                                                                                                prices),
                                                                                                                                        null)
                                                                                                                                .thenReturn(
                                                                                                                                        new ProviderDeletionView(
                                                                                                                                                provider
                                                                                                                                                        .id(),
                                                                                                                                                provider
                                                                                                                                                        .code(),
                                                                                                                                                channels,
                                                                                                                                                routes,
                                                                                                                                                prices))
                                                                                                                        : Mono
                                                                                                                                .error(
                                                                                                                                        notFound(
                                                                                                                                                "provider_not_found"))))));
        return transactions.transactional(mutation);
    }

    public Mono<ModelView> saveModelConfiguration(
            SaveModelConfigurationCommand command, UUID actorUserId) {
        if (command == null || command.route() == null) {
            return Mono.error(invalid("invalid_model_configuration"));
        }
        boolean creating = command.id() == null;
        validateModel(
                creating ? command.code() : "existing",
                command.displayName(),
                command.capabilities(),
                command.contextWindow(),
                command.maxOutputTokens(),
                command.requestsPerMinute(),
                command.maxConcurrency());
        validateModelRoute(command.route());
        UUID modelId = creating ? UUID.randomUUID() : command.id();

        Mono<ModelMutationContext> modelMutation =
                creating
                        ? repository
                                .insertModel(
                                        modelId,
                                        new CreateModelCommand(
                                                command.code().strip(),
                                                command.displayName().strip(),
                                                aliases(command.code(), command.aliases()),
                                                command.capabilities(),
                                                command.contextWindow(),
                                                command.maxOutputTokens(),
                                                command.premium(),
                                                command.enabled(),
                                                command.requestsPerMinute(),
                                                command.maxConcurrency()),
                                        aliases(command.code(), command.aliases()))
                                .thenReturn(new ModelMutationContext(null, command.code().strip()))
                        : repository
                                .model(modelId)
                                .switchIfEmpty(Mono.error(notFound("model_not_found")))
                                .flatMap(
                                        before -> {
                                            Set<String> updatedAliases =
                                                    aliases(before.code(), command.aliases());
                                            return repository
                                                    .updateModel(
                                                            new UpdateModelCommand(
                                                                    modelId,
                                                                    command.displayName().strip(),
                                                                    updatedAliases,
                                                                    command.capabilities(),
                                                                    command.contextWindow(),
                                                                    command.maxOutputTokens(),
                                                                    command.premium(),
                                                                    command.enabled(),
                                                                    command.requestsPerMinute(),
                                                                    command.maxConcurrency()),
                                                            before.code(),
                                                            updatedAliases)
                                                    .thenReturn(
                                                            new ModelMutationContext(
                                                                    before, before.code()));
                                        });

        Mono<ModelView> mutation =
                repository
                        .provider(command.route().providerId())
                        .switchIfEmpty(Mono.error(invalid("invalid_provider")))
                        .flatMap(
                                provider -> {
                                    // 能力守卫：在写入之前就把「这个供应商类型服务不了这个模型」
                                    // 拦下来，而不是等真实请求 503。
                                    assertProviderCanServe(provider, command.capabilities());
                                    return modelMutation.flatMap(
                                            context ->
                                                    saveConfiguredRoute(
                                                            creating,
                                                            modelId,
                                                            command,
                                                            actorUserId,
                                                            provider,
                                                            context));
                                });
        return transactions
                .transactional(mutation)
                .onErrorMap(
                        DataIntegrityViolationException.class,
                        error -> conflict("model_configuration_conflict"));
    }

    private Mono<ModelView> saveConfiguredRoute(
            boolean creating,
            UUID modelId,
            SaveModelConfigurationCommand command,
            UUID actorUserId,
            ProviderView provider,
            ModelMutationContext context) {
        ModelRouteConfiguration configuration = command.route();
        return configuredComponentModelId(creating, modelId, configuration)
                .flatMap(
                        componentModelId ->
                                saveConfiguredChannel(
                                                context.code(),
                                                command.displayName(),
                                                provider,
                                                configuration)
                                        .flatMap(
                                                channelId ->
                                                        repository
                                                                .upsertModelRoute(
                                                                        UUID.randomUUID(),
                                                                        channelId,
                                                                        componentModelId,
                                                                        configuration)
                                                                .filter(updated -> updated == 1L)
                                                                .switchIfEmpty(
                                                                        Mono.error(
                                                                                invalid(
                                                                                        "invalid_model_route"))))
                                        .then(repository.reconcileProviderRouting())
                                        .then(repository.model(modelId))
                                        .flatMap(
                                                after ->
                                                        repository
                                                                .audit(
                                                                        actorUserId,
                                                                        creating
                                                                                ? "GATEWAY_MODEL_CONFIGURATION_CREATED"
                                                                                : "GATEWAY_MODEL_CONFIGURATION_UPDATED",
                                                                        "GATEWAY_MODEL_CONFIGURATION",
                                                                        modelId,
                                                                        context.before(),
                                                                        after,
                                                                        !blank(
                                                                                configuration
                                                                                        .apiKey()))
                                                                .thenReturn(after)));
    }

    private Mono<UUID> configuredComponentModelId(
            boolean creating, UUID modelId, ModelRouteConfiguration configuration) {
        if (creating || blank(configuration.componentModelCode())) {
            return Mono.just(modelId);
        }
        return repository
                .modelId(configuration.componentModelCode())
                .switchIfEmpty(Mono.error(notFound("component_model_not_found")));
    }

    private Mono<UUID> saveConfiguredChannel(
            String modelCode,
            String modelName,
            ProviderView provider,
            ModelRouteConfiguration configuration) {
        if (configuration.channelId() == null) {
            UUID channelId = UUID.randomUUID();
            String channelCode = configuredChannelCode(modelCode, provider.code(), channelId);
            String channelName = configuredChannelName(modelName, provider.name());
            CreateChannelCommand channel =
                    new CreateChannelCommand(
                            provider.id(),
                            channelCode,
                            channelName,
                            configuration.environment(),
                            configuration.baseUrl().strip(),
                            configuration.apiKey(),
                            configuration.enabled(),
                            configuration.priority(),
                            configuration.weight(),
                            configuration.timeoutMs(),
                            configuration.requestsPerMinute(),
                            configuration.maxConcurrency());
            return repository
                    .insertChannel(channelId, channel, cipher.encrypt(configuration.apiKey()))
                    .filter(updated -> updated == 1L)
                    .switchIfEmpty(Mono.error(conflict("channel_already_exists")))
                    .thenReturn(channelId);
        }
        return repository
                .channel(configuration.channelId())
                .switchIfEmpty(Mono.error(notFound("channel_not_found")))
                .flatMap(
                        before -> {
                            if (!before.credentialConfigured() && blank(configuration.apiKey())) {
                                return Mono.error(invalid("channel_credential_required"));
                            }
                            byte[] encrypted =
                                    blank(configuration.apiKey())
                                            ? null
                                            : cipher.encrypt(configuration.apiKey());
                            return repository
                                    .updateChannelConfiguration(configuration, encrypted)
                                    .filter(updated -> updated == 1L)
                                    .switchIfEmpty(Mono.error(notFound("channel_not_found")))
                                    .thenReturn(configuration.channelId());
                        });
    }

    private void validateModelRoute(ModelRouteConfiguration configuration) {
        validateChannel(
                configuration.providerId(),
                "configured-route",
                "Configured route",
                configuration.environment(),
                configuration.baseUrl(),
                configuration.apiKey(),
                configuration.channelId() == null,
                configuration.weight(),
                configuration.timeoutMs(),
                configuration.requestsPerMinute(),
                configuration.maxConcurrency());
        if (blank(configuration.upstreamModel())
                || configuration.upstreamModel().length() > 128
                || configuration.priority() < 0
                || (!blank(configuration.componentModelCode())
                        && configuration.componentModelCode().length() > 128)) {
            throw invalid("invalid_model_route");
        }
    }

    private String configuredChannelCode(String modelCode, String providerCode, UUID channelId) {
        String prefix =
                (modelCode + "-" + providerCode)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9_-]+", "-")
                        .replaceAll("^-+|-+$", "");
        if (blank(prefix)) {
            prefix = "model-route";
        }
        String suffix = "-" + channelId.toString().substring(0, 8);
        return prefix.substring(0, Math.min(prefix.length(), 64 - suffix.length())) + suffix;
    }

    private String configuredChannelName(String modelName, String providerName) {
        String name = modelName.strip() + " / " + providerName.strip();
        return name.substring(0, Math.min(name.length(), 100));
    }

    public Mono<PageResult<ChannelView>> listChannels(AdminListQuery request) {
        return repository.listChannels(request);
    }

    Flux<ChannelView> listAllChannels() {
        return repository.listAllChannels();
    }

    public Mono<ChannelView> createChannel(CreateChannelCommand command, UUID actorUserId) {
        validateChannel(
                command.providerId(),
                command.code(),
                command.name(),
                command.environment(),
                command.baseUrl(),
                command.apiKey(),
                true,
                command.weight(),
                command.timeoutMs(),
                command.requestsPerMinute(),
                command.maxConcurrency());
        UUID id = UUID.randomUUID();
        Mono<ChannelView> mutation =
                repository
                        .providerExists(command.providerId())
                        .flatMap(
                                exists -> {
                                    if (!exists) {
                                        return Mono.error(invalid("invalid_provider"));
                                    }
                                    byte[] encrypted = cipher.encrypt(command.apiKey());
                                    return repository
                                            .insertChannel(id, command, encrypted)
                                            .onErrorMap(
                                                    DataIntegrityViolationException.class,
                                                    error -> invalid("invalid_channel"))
                                            .filter(updated -> updated == 1L)
                                            .switchIfEmpty(
                                                    Mono.error(conflict("channel_already_exists")))
                                            .then(repository.reconcileProviderRouting())
                                            .then(repository.channel(id))
                                            .flatMap(
                                                    created ->
                                                            repository
                                                                    .audit(
                                                                            actorUserId,
                                                                            "GATEWAY_CHANNEL_CREATED",
                                                                            "GATEWAY_CHANNEL",
                                                                            id,
                                                                            null,
                                                                            channelSnapshot(
                                                                                    created),
                                                                            true)
                                                                    .thenReturn(created));
                                });
        return transactions.transactional(mutation);
    }

    public Mono<ChannelView> updateChannel(UpdateChannelCommand command, UUID actorUserId) {
        if (command.id() == null) {
            return Mono.error(invalid("invalid_channel"));
        }
        boolean credentialChanged = !blank(command.apiKey());
        Mono<ChannelView> mutation =
                repository
                        .channel(command.id())
                        .switchIfEmpty(Mono.error(notFound("channel_not_found")))
                        .flatMap(
                                before -> {
                                    validateChannel(
                                            before.providerId(),
                                            "existing",
                                            command.name(),
                                            before.environment(),
                                            command.baseUrl(),
                                            command.apiKey(),
                                            false,
                                            command.weight(),
                                            command.timeoutMs(),
                                            command.requestsPerMinute(),
                                            command.maxConcurrency());
                                    byte[] encrypted =
                                            credentialChanged
                                                    ? cipher.encrypt(command.apiKey())
                                                    : null;
                                    return repository
                                            .updateChannel(command, encrypted)
                                            .filter(updated -> updated == 1L)
                                            .switchIfEmpty(
                                                    Mono.error(notFound("channel_not_found")))
                                            .then(repository.reconcileProviderRouting())
                                            .then(repository.channel(command.id()))
                                            .flatMap(
                                                    after ->
                                                            repository
                                                                    .audit(
                                                                            actorUserId,
                                                                            "GATEWAY_CHANNEL_UPDATED",
                                                                            "GATEWAY_CHANNEL",
                                                                            command.id(),
                                                                            channelSnapshot(before),
                                                                            channelSnapshot(after),
                                                                            credentialChanged)
                                                                    .thenReturn(after));
                                });
        return transactions.transactional(mutation);
    }

    public Mono<ChannelTestView> testChannel(UUID id) {
        return repository
                .channelTestConfig(id)
                .switchIfEmpty(Mono.error(notFound("channel_not_found")))
                .flatMap(
                        config -> {
                            if (!config.configured()) {
                                return updateChannelHealth(
                                                id, "UNHEALTHY", config.observedUpdatedAt())
                                        .thenReturn(new ChannelTestView(id, false, "INCOMPLETE"));
                            }
                            return connectivityProbe
                                    .probe(
                                            config.baseUrl(),
                                            config.environment(),
                                            config.timeoutMs())
                                    .flatMap(
                                            healthy -> {
                                                String status = healthy ? "HEALTHY" : "UNHEALTHY";
                                                return updateChannelHealth(
                                                                id,
                                                                status,
                                                                config.observedUpdatedAt())
                                                        .thenReturn(
                                                                new ChannelTestView(
                                                                        id, true, status));
                                            });
                        });
    }

    private Mono<Void> updateChannelHealth(
            UUID id, String status, OffsetDateTime observedUpdatedAt) {
        return repository
                .updateChannelHealth(id, status, observedUpdatedAt)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(conflict("channel_configuration_changed")))
                .then();
    }

    /**
     * 「测试模型」：真的向上游发一次最小对话请求，并按结果落审计。
     *
     * <p>与 {@link #testChannel} 的分工：那个只回答「地址通不通」，一把错误的密钥、 一个拼错的上游模型名在它那里同样是
     * HEALTHY。运营要的是「这个模型现在能不能用」， 那只有一次真实调用能回答——因此测试走线上同一条链路（同一个适配器、同一个地址、
     * 同一把解密后的密钥），并且把上游原文与耗时回给控制台，让失败可复核。
     *
     * <p>代价是每次测试都会在上游产生一次真实的 {@code max_tokens=1} 计费，所以它由管理端
     * 显式触发并留痕；同时刻意不写用量、不碰配额与钱包——运营的探测不该计入任何客户的账。
     */
    public Mono<ChannelModelTestView> testChannelModel(
            UUID id, String upstreamModel, boolean connectivityOnly, UUID actorUserId) {
        if (modelTests == null) {
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE, "channel_model_test_unavailable"));
        }
        return modelTests
                .test(id, upstreamModel, connectivityOnly)
                .flatMap(
                        result ->
                                repository
                                        .audit(
                                                actorUserId,
                                                "GATEWAY_CHANNEL_MODEL_TESTED",
                                                "GATEWAY_CHANNEL",
                                                id,
                                                null,
                                                new ChannelModelTestSnapshot(
                                                        result.providerType(),
                                                        result.upstreamModel(),
                                                        result.modelCode(),
                                                        result.modelInvoked(),
                                                        result.reason(),
                                                        result.httpStatus(),
                                                        result.latencyMs(),
                                                        result.status(),
                                                        result.healthPersisted()),
                                                null)
                                        .thenReturn(result));
    }

    /** 测试留痕的内容：测了什么、结论是什么，以及是否有真实请求发出。 */
    private record ChannelModelTestSnapshot(
            String providerType,
            String upstreamModel,
            String modelCode,
            boolean modelInvoked,
            String reason,
            Integer httpStatus,
            Long latencyMs,
            String status,
            boolean healthPersisted) {}

    private ChannelAuditSnapshot channelSnapshot(ChannelView channel) {
        return new ChannelAuditSnapshot(
                channel.providerId(),
                channel.code(),
                channel.name(),
                channel.environment(),
                channel.baseUrl(),
                channel.enabled(),
                channel.priority(),
                channel.weight(),
                channel.timeoutMs(),
                channel.requestsPerMinute(),
                channel.maxConcurrency(),
                channel.lastHealthStatus());
    }

    private void validateModel(
            String code,
            String name,
            Set<String> capabilities,
            int contextWindow,
            int maxOutput,
            int rpm,
            int concurrency) {
        if (blank(code)
                || blank(name)
                || capabilities == null
                || capabilities.isEmpty()
                || code.length() > 128
                || name.length() > 200
                // 音频的三个能力名由 V93 建立并挂在三个音频别名上。
                // 这个集合必须与 PackageActivationService.CAPABILITIES 和
                // platform 的 PackageVersionService.ALLOWED_CAPABILITIES 同步：
                // 三处不同步的后果是一个套餐在平台能发布、在网关被拒。
                || !Set.of(
                                "TEXT",
                                "IMAGE",
                                "FILE",
                                "VIDEO",
                                "IMAGE_GEN",
                                "AUDIO_TRANSCRIBE",
                                "AUDIO_SYNTHESIS",
                                "LIP_SYNC")
                        .containsAll(capabilities)
                || contextWindow <= 0
                || maxOutput <= 0
                || rpm <= 0
                || concurrency <= 0) {
            throw invalid("invalid_model");
        }
    }

    private Set<String> aliases(String code, Set<String> requested) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add(code);
        if (requested != null) {
            for (String alias : requested) {
                if (blank(alias) || alias.length() > 128) {
                    throw invalid("invalid_model_alias");
                }
                values.add(alias.strip());
            }
        }
        return Set.copyOf(values);
    }

    private void validateChannel(
            UUID providerId,
            String code,
            String name,
            String environment,
            String baseUrl,
            String apiKey,
            boolean requireCredential,
            int weight,
            int timeoutMs,
            int rpm,
            int concurrency) {
        if (providerId == null
                || (requireCredential && blank(apiKey))
                || blank(code)
                || code.length() > 64
                || blank(name)
                || name.length() > 100
                || !CHANNEL_ENVIRONMENTS.contains(environment)
                || blank(baseUrl)
                || baseUrl.length() > 1000
                || weight <= 0
                || timeoutMs <= 0
                || rpm <= 0
                || concurrency <= 0) {
            throw invalid("invalid_channel");
        }
        try {
            URI uri = URI.create(baseUrl);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean http = "http".equalsIgnoreCase(uri.getScheme());
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (!https && !http)
                    || ("PRODUCTION".equals(environment) && !https)) {
                throw invalid("invalid_channel");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid_channel");
        }
    }

    private void validateProvider(CreateProviderCommand command) {
        if (command == null
                || blank(command.code())
                || command.code().length() > 32
                || blank(command.name())
                || command.name().length() > 100
                || !PROVIDER_TYPES.contains(command.providerType())
                || blank(command.baseUrl())
                || command.baseUrl().length() > 1000) {
            throw invalid("invalid_provider");
        }
        try {
            URI uri = URI.create(command.baseUrl());
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw invalid("invalid_provider");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid_provider");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private record ChannelAuditSnapshot(
            UUID providerId,
            String code,
            String name,
            String environment,
            String baseUrl,
            boolean enabled,
            int priority,
            int weight,
            int timeoutMs,
            int requestsPerMinute,
            int maxConcurrency,
            String lastHealthStatus) {}

    private record ModelMutationContext(ModelView before, String code) {}
}
