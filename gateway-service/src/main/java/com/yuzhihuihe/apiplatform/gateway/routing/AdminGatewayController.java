package com.yuzhihuihe.apiplatform.gateway.routing;

import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.AdminListQuery;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.ChannelModelTestCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateChannelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateModelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateProviderCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.OnboardRoutesCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.SaveModelConfigurationCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.SaveVideoAliasBillingCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.SetModelStatusCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.SwitchRouteRequest;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.UpdateChannelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.UpdateModelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.UpstreamModelProbeCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelModelProbeView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelModelTestView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelTestView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.CircuitHealthView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ModelView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.PageResult;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ProviderDeletionView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ProviderTypeCapabilitiesView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ProviderView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.RoutesOnboardingView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.RoutingHealth;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.RoutingTargetView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.VideoAliasBillingView;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/console/gateway/admin")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class AdminGatewayController {

    private final GatewayAdminService admin;
    private final VideoAliasBillingService videoBilling;
    private final ChannelCircuitRegistry circuits;

    public AdminGatewayController(
            GatewayAdminService admin,
            VideoAliasBillingService videoBilling,
            ChannelCircuitRegistry circuits) {
        this.admin = admin;
        this.videoBilling = videoBilling;
        this.circuits = circuits;
    }

    /**
     * 每个已注册视频别名的按秒计费。
     *
     * <p>这正是运营人员为了让某个视频别名可售而填写的内容：它售卖的分辨率，以及套餐发布 守卫据以定价的成本/零售价。价格列为 null 表示「已路由但尚无法定价」。
     */
    @GetMapping("/video-aliases")
    Flux<VideoAliasBillingView> videoAliases() {
        return videoBilling.list();
    }

    @PutMapping("/video-aliases/{modelCode}")
    Mono<VideoAliasBillingView> saveVideoAliasBilling(
            @PathVariable String modelCode, @RequestBody SaveVideoAliasBillingCommand command) {
        return videoBilling.save(modelCode, command);
    }

    @GetMapping("/models")
    Mono<PageResult<ModelView>> models(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return admin.listModels(listQuery(page, size, query, status, sort, direction));
    }

    @PostMapping("/models")
    Mono<ModelView> createModel(
            @RequestBody CreateModelCommand command, @AuthenticationPrincipal Jwt jwt) {
        return admin.createModel(command, actor(jwt));
    }

    @PatchMapping("/models")
    Mono<ModelView> updateModel(
            @RequestBody UpdateModelCommand command, @AuthenticationPrincipal Jwt jwt) {
        return admin.updateModel(command, actor(jwt));
    }

    @PostMapping("/models/configuration")
    Mono<ModelView> saveModelConfiguration(
            @RequestBody SaveModelConfigurationCommand command, @AuthenticationPrincipal Jwt jwt) {
        return admin.saveModelConfiguration(command, actor(jwt));
    }

    @PatchMapping("/models/{id}/status")
    Mono<ModelView> setModelStatus(
            @PathVariable UUID id,
            @RequestBody SetModelStatusCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        return admin.setModelEnabled(id, command.enabled(), actor(jwt));
    }

    @DeleteMapping("/models/{id}")
    Mono<Void> deleteModel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return admin.deleteModel(id, actor(jwt), jwt.getTokenValue());
    }

    @GetMapping("/providers")
    Flux<ProviderView> providers() {
        return admin.listProviders();
    }

    @PostMapping("/providers")
    Mono<ProviderView> createProvider(
            @RequestBody CreateProviderCommand command, @AuthenticationPrincipal Jwt jwt) {
        return admin.createProvider(command, actor(jwt));
    }

    /**
     * 删除一个服务商（清理废弃接入）。
     *
     * <p>默认**不**级联：名下还有渠道时返回 409 {@code provider_has_channels}，因为那些渠道 可能还挂着在售模型；确认要连同渠道与别名映射一起删时传
     * {@code cascade=true}。 被用量、成本或生成任务引用过的服务商一律拒删（409 {@code provider_has_usage_history}），
     * 这类应当停用而不是删除，否则计费与审计历史会变成孤儿。
     */
    @DeleteMapping("/providers/{id}")
    Mono<ProviderDeletionView> deleteProvider(
            @PathVariable UUID id,
            @RequestParam(name = "cascade", defaultValue = "false") boolean cascade,
            @AuthenticationPrincipal Jwt jwt) {
        return admin.deleteProvider(id, cascade, actor(jwt));
    }

    @GetMapping("/channels")
    Mono<PageResult<ChannelView>> channels(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return admin.listChannels(listQuery(page, size, query, status, sort, direction));
    }

    @PostMapping("/channels")
    Mono<ChannelView> createChannel(
            @RequestBody CreateChannelCommand command, @AuthenticationPrincipal Jwt jwt) {
        return admin.createChannel(command, actor(jwt));
    }

    @PatchMapping("/channels")
    Mono<ChannelView> updateChannel(
            @RequestBody UpdateChannelCommand command, @AuthenticationPrincipal Jwt jwt) {
        return admin.updateChannel(command, actor(jwt));
    }

    @PostMapping("/channels/{id}/test")
    Mono<ChannelTestView> testChannel(@PathVariable UUID id) {
        return admin.testChannel(id);
    }

    /**
     * 测试模型：用这个渠道的配置真的向上游发一次最小对话请求（{@code max_tokens=1}）， 并把上游的答复与耗时作为证据回给控制台。
     *
     * <p>控制台的「连接测试」按的就是这个接口。可达性探测（{@code /channels/{id}/test}） 对错误的密钥、拼错的模型名、缺版本段的地址一律报
     * HEALTHY，那正是运营点下按钮时 最想排除的三种情况；真实调用是把它们区分开的唯一办法。测试会写渠道健康态 （成功 HEALTHY、暂时性故障 DEGRADED、配置本身不可用
     * UNHEALTHY）并落审计。
     *
     * <p>{@code connectivity=true} 时退化为只探可达性、不发真实请求。
     */
    @PostMapping("/channels/{id}/model-test")
    Mono<ChannelModelTestView> testChannelModel(
            @PathVariable UUID id,
            @RequestBody(required = false) ChannelModelTestCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        boolean connectivityOnly = command != null && Boolean.TRUE.equals(command.connectivity());
        String upstreamModel = command == null ? null : command.upstreamModel();
        return admin.testChannelModel(id, upstreamModel, connectivityOnly, actor(jwt));
    }

    /**
     * 供应商类型的能力目录：每种类型支持哪些对话模态、能否生图、能否生成视频。
     *
     * <p>新增服务商时先看这里，就不会出现「把能读图的 key 挂到一个没有对话适配器的 供应商类型下」这种事——那种配置在保存时看起来完全正常，却让每一次请求都 503。
     */
    @GetMapping("/provider-types")
    Flux<ProviderTypeCapabilitiesView> providerTypes() {
        return Flux.fromIterable(admin.providerTypeCapabilities());
    }

    /**
     * 用该渠道已保存的密钥向上游列一次模型，确认「填进去的 key 马上能用」。
     *
     * <p>与 {@code /channels/{id}/test} 的区别：那个只做 DNS/TCP/TLS 探测、不发 {@code Authorization} 头（错误密钥照样显示
     * HEALTHY）；这个真的带着密钥问上游， 401/403 会如实返回 {@code keyValid=false}，并给出这把 key 可用的模型清单，
     * 让运营直接把清单里的模型名填进路由。
     */
    @PostMapping("/channels/{id}/models")
    Mono<ChannelModelProbeView> probeChannelModels(@PathVariable UUID id) {
        return admin.probeChannelModels(id);
    }

    /**
     * 用尚未保存的地址与密钥探一次上游模型清单。
     *
     * <p>新增服务商时渠道还不存在，而「填进去能不能用」必须在保存之前就能回答—— 否则运营只能先存一把错 key，再等一次 401。
     */
    @PostMapping("/upstream-models")
    Mono<ChannelModelProbeView> probeUpstreamModels(
            @RequestBody UpstreamModelProbeCommand command) {
        return admin.probeUpstreamModels(command);
    }

    /**
     * 批量接入：一次把多条「平台别名 ← 上游模型」映射写好。
     *
     * <p>这是「填进 key 就能用」的落点：每条都过能力守卫（服务商类型服务不了就不写）， 并以 {@code notes} 返回「写进去了但还不能用」的原因（视频别名缺按秒计价、
     * 同别名存在优先级不低的旧渠道）。
     */
    @PostMapping("/channels/{id}/routes")
    Mono<RoutesOnboardingView> onboardRoutes(
            @PathVariable UUID id, @RequestBody OnboardRoutesCommand command) {
        return admin.onboardRoutes(id, command);
    }

    @GetMapping("/routing/health")
    Mono<RoutingHealth> routingHealth() {
        return admin.listAllChannels()
                .collectList()
                .map(
                        channels ->
                                new RoutingHealth(
                                        channels,
                                        circuits.health().stream()
                                                .map(this::circuitHealth)
                                                .toList()));
    }

    @PostMapping("/routing/circuits/{key}/reset")
    Mono<CircuitHealthView> resetCircuit(
            @PathVariable String key, @AuthenticationPrincipal Jwt jwt) {
        ChannelCircuitRegistry.CircuitHealth result = circuits.reset(key);
        return admin.auditCircuitReset(key, result, actor(jwt)).thenReturn(circuitHealth(result));
    }

    @GetMapping("/routing/circuits/{key}/targets")
    Flux<RoutingTargetView> routingTargets(@PathVariable String key) {
        return admin.listRoutingTargets(key);
    }

    @PostMapping("/routing/circuits/{key}/switch")
    Mono<RoutingTargetView> switchRoute(
            @PathVariable String key,
            @RequestBody SwitchRouteRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return admin.switchRoute(key, request.channelId(), actor(jwt));
    }

    @DeleteMapping("/routing/circuits/{key}/switch")
    Mono<Void> restoreAutomaticRoute(@PathVariable String key, @AuthenticationPrincipal Jwt jwt) {
        return admin.restoreAutomaticRoute(key, actor(jwt));
    }

    private AdminListQuery listQuery(
            int page, int size, String query, String status, String sort, String direction) {
        return new AdminListQuery(page, size, query, status, sort, direction);
    }

    private UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private CircuitHealthView circuitHealth(ChannelCircuitRegistry.CircuitHealth health) {
        return new CircuitHealthView(
                health.key(),
                health.state(),
                health.failureRate(),
                health.bufferedCalls(),
                health.channelId(),
                health.modelCode());
    }
}
