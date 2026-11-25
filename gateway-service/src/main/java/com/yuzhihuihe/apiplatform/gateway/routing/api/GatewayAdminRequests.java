package com.yuzhihuihe.apiplatform.gateway.routing.api;

import java.util.Set;
import java.util.UUID;

public final class GatewayAdminRequests {

    private GatewayAdminRequests() {}

    public record AdminListQuery(
            int page, int size, String query, String status, String sort, String direction) {}

    public record CreateProviderCommand(
            String code, String name, String providerType, String baseUrl, boolean enabled) {}

    public record CreateModelCommand(
            String code,
            String displayName,
            Set<String> aliases,
            Set<String> capabilities,
            int contextWindow,
            int maxOutputTokens,
            boolean premium,
            boolean enabled,
            int requestsPerMinute,
            int maxConcurrency) {

        public CreateModelCommand(
                String code,
                String displayName,
                Set<String> capabilities,
                int contextWindow,
                int maxOutputTokens,
                boolean premium,
                boolean enabled,
                int requestsPerMinute,
                int maxConcurrency) {
            this(
                    code,
                    displayName,
                    Set.of(),
                    capabilities,
                    contextWindow,
                    maxOutputTokens,
                    premium,
                    enabled,
                    requestsPerMinute,
                    maxConcurrency);
        }
    }

    public record UpdateModelCommand(
            UUID id,
            String displayName,
            Set<String> aliases,
            Set<String> capabilities,
            int contextWindow,
            int maxOutputTokens,
            boolean premium,
            boolean enabled,
            int requestsPerMinute,
            int maxConcurrency) {

        public UpdateModelCommand(
                UUID id,
                String displayName,
                Set<String> capabilities,
                int contextWindow,
                int maxOutputTokens,
                boolean premium,
                boolean enabled,
                int requestsPerMinute,
                int maxConcurrency) {
            this(
                    id,
                    displayName,
                    null,
                    capabilities,
                    contextWindow,
                    maxOutputTokens,
                    premium,
                    enabled,
                    requestsPerMinute,
                    maxConcurrency);
        }
    }

    public record SetModelStatusCommand(boolean enabled) {}

    public record SaveModelConfigurationCommand(
            UUID id,
            String code,
            String displayName,
            Set<String> aliases,
            Set<String> capabilities,
            int contextWindow,
            int maxOutputTokens,
            boolean premium,
            boolean enabled,
            int requestsPerMinute,
            int maxConcurrency,
            ModelRouteConfiguration route) {}

    public record ModelRouteConfiguration(
            UUID channelId,
            String componentModelCode,
            UUID providerId,
            String environment,
            String baseUrl,
            String apiKey,
            String upstreamModel,
            boolean enabled,
            int priority,
            int weight,
            int timeoutMs,
            int requestsPerMinute,
            int maxConcurrency) {}

    /**
     * 运营人员为某个 VIDEO 别名录入的按秒计费。
     *
     * <p>{@code resolutionTier} 是该别名唯一售卖的分辨率——网关会拒绝其他任何分辨率， 因为该别名是按分辨率定价的。{@code providerUnitCost}
     * 是售出一秒的有效成本，单位为 分（已包含失败容许量）；{@code unitPrice} 是每秒零售价，必须越过产品的 30% 毛利 下限。{@code retryRate}
     * 是成本背后记录的容许量，默认 15%。
     */
    public record SaveVideoAliasBillingCommand(
            String resolutionTier,
            int providerUnitCost,
            int unitPrice,
            java.math.BigDecimal retryRate) {}

    public record CreateChannelCommand(
            UUID providerId,
            String code,
            String name,
            String environment,
            String baseUrl,
            String apiKey,
            boolean enabled,
            int priority,
            int weight,
            int timeoutMs,
            int requestsPerMinute,
            int maxConcurrency) {}

    public record UpdateChannelCommand(
            UUID id,
            String name,
            String baseUrl,
            String apiKey,
            boolean enabled,
            int priority,
            int weight,
            int timeoutMs,
            int requestsPerMinute,
            int maxConcurrency) {}

    public record SwitchRouteRequest(UUID channelId) {}

    /**
     * 用「还没保存的」地址与密钥探一次上游模型清单。
     *
     * <p>新增服务商/模型时渠道尚不存在，因此这一步不能要求 channelId。密钥只用于这次 请求，不写库、不回显。
     */
    public record UpstreamModelProbeCommand(String baseUrl, String apiKey) {}

    /**
     * 一条「平台别名 ← 上游模型」的映射意图。
     *
     * <p>{@code priority}/{@code weight} 为空表示不改写渠道上的默认值。
     */
    public record OnboardRouteCommand(
            String modelCode,
            String upstreamModel,
            Boolean enabled,
            Integer priority,
            Integer weight) {}

    /** 一次性接入多条路由：这是「填进 key 就能用」的落点。 */
    public record OnboardRoutesCommand(java.util.List<OnboardRouteCommand> routes) {}

    /**
     * 真实模型调用的测试意图。
     *
     * <p>用的是哪把 key、打到哪个地址，都由渠道自己决定 —— 测试必须复用线上那条链路， 否则测过的配置和跑起来的配置可以不是同一个。{@code upstreamModel}
     * 是运营在渠道上 挂过的上游模型名（来自某条别名的映射）；为空表示不指定，由网关挑第一条可用的文本映射。 {@code connectivity} 为真表示只探可达性、不发真实请求。
     */
    public record ChannelModelTestCommand(String upstreamModel, Boolean connectivity) {
        public ChannelModelTestCommand(String upstreamModel) {
            this(upstreamModel, null);
        }
    }
}
