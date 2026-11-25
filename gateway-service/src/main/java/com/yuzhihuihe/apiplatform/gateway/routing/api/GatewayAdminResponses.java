package com.yuzhihuihe.apiplatform.gateway.routing.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class GatewayAdminResponses {

    private GatewayAdminResponses() {}

    public record PageResult<T>(List<T> items, long total, int page, int size) {}

    /**
     * 控制台所见的某个视频别名的计费视图。
     *
     * <p>当该别名已注册分辨率、但没有当前价目表行时，价格列均为 null，这也是通过控制台 新增的别名所处的初始状态：它能够被路由和调用，但在这些数字填好之前无法定价。
     */
    public record VideoAliasBillingView(
            String modelCode,
            String resolutionTier,
            Integer providerUnitCost,
            Integer unitPrice,
            java.math.BigDecimal retryRate,
            java.math.BigDecimal targetMarginRatio,
            Integer minAbsoluteMargin,
            String priceVersion) {}

    public record ModelView(
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
            String routingStrategy,
            List<ModelRouteView> routes) {

        public ModelView {
            // 顺序必须是确定的：这两个字段会序列化成 JSON 数组交给控制台与客户端。
            // 这里刻意不用 Set.copyOf——它返回的不可变 Set 迭代顺序未定义
            // （OpenJDK 按进程启动时的随机盐），会让"同一条数据在不同进程里
            // 渲染成不同顺序"，客户端的相等比较/缓存与测试都会变成偶发。
            aliases = aliases == null ? Set.of() : sortedCopy(aliases);
            capabilities = capabilities == null ? Set.of() : sortedCopy(capabilities);
            routes = routes == null ? List.of() : List.copyOf(routes);
        }

        private static Set<String> sortedCopy(Set<String> values) {
            return Collections.unmodifiableSortedSet(new TreeSet<>(values));
        }

        public ModelView(
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
                String routingStrategy) {
            this(
                    id,
                    code,
                    displayName,
                    aliases,
                    capabilities,
                    contextWindow,
                    maxOutputTokens,
                    premium,
                    enabled,
                    requestsPerMinute,
                    maxConcurrency,
                    routingStrategy,
                    List.of());
        }

        public ModelView(
                UUID id,
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
                    id,
                    code,
                    displayName,
                    Set.of(code),
                    capabilities,
                    contextWindow,
                    maxOutputTokens,
                    premium,
                    enabled,
                    requestsPerMinute,
                    maxConcurrency,
                    "DIRECT",
                    List.of());
        }
    }

    public record ModelRouteView(
            String componentModelCode,
            String componentDisplayName,
            UUID providerId,
            String providerCode,
            String providerName,
            UUID channelId,
            String channelCode,
            String channelName,
            String upstreamModel,
            String environment,
            String baseUrl,
            boolean enabled,
            String healthStatus,
            boolean credentialConfigured,
            int priority,
            int weight,
            int timeoutMs,
            int requestsPerMinute,
            int maxConcurrency,
            boolean eligible) {

        public ModelRouteView(
                String componentModelCode,
                String componentDisplayName,
                UUID providerId,
                String providerCode,
                String providerName,
                UUID channelId,
                String channelCode,
                String channelName,
                String upstreamModel,
                String environment,
                boolean enabled,
                String healthStatus,
                boolean credentialConfigured,
                int priority,
                int weight,
                boolean eligible) {
            this(
                    componentModelCode,
                    componentDisplayName,
                    providerId,
                    providerCode,
                    providerName,
                    channelId,
                    channelCode,
                    channelName,
                    upstreamModel,
                    environment,
                    "",
                    enabled,
                    healthStatus,
                    credentialConfigured,
                    priority,
                    weight,
                    60_000,
                    300,
                    50,
                    eligible);
        }
    }

    public record ProviderView(
            UUID id,
            String code,
            String name,
            String providerType,
            String baseUrl,
            boolean enabled) {}

    public record ChannelView(
            UUID id,
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
            String lastHealthStatus,
            boolean credentialConfigured) {}

    /**
     * 渠道连接测试的结果。
     *
     * <p>{@code status} 仍是渠道健康态，取值落在 {@code ck_channel_health} 允许的 {@code UNKNOWN / HEALTHY /
     * DEGRADED / UNHEALTHY} 里；{@code modelInvoked} 说明本次 结果是「真的向上游发了一次模型调用」还是「只探了
     * DNS/TCP/TLS」——这两件事的结论 强度不同，界面上必须能区分，否则一把错误的密钥会被读成 HEALTHY。
     *
     * <p>{@code reason} 是稳定原因码（{@code credential_rejected} 等），界面据此给中文， 而不是拿上游原文做字符串匹配。{@code
     * upstreamMessage} 是实测得到的上游应答片段， 上游常常用 200 + 错误对象回答，那种情况只有它说得清哪里不对。
     */
    public record ChannelTestView(
            UUID channelId,
            boolean configured,
            String status,
            boolean reachable,
            boolean modelInvoked,
            boolean modelOk,
            String modelCode,
            String upstreamModel,
            String reason,
            Integer httpStatus,
            Long latencyMs,
            Long inputTokens,
            Long outputTokens,
            boolean healthPersisted,
            String upstreamMessage) {

        /** 兼容只做了可达性探测的旧契约（没有模型信息）。 */
        public ChannelTestView(UUID channelId, boolean configured, String status) {
            this(
                    channelId,
                    configured,
                    status,
                    "HEALTHY".equals(status),
                    false,
                    "HEALTHY".equals(status),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    !"UNKNOWN".equals(status),
                    null);
        }

        /** 整个测试（含真实模型调用）是否通过。 */
        public boolean ok() {
            return modelInvoked ? modelOk : "HEALTHY".equals(status);
        }
    }

    /**
     * 一次真实的模型调用测试：用渠道里已保存的密钥，按运营指定的上游模型名发一条 最小对话请求（{@code max_tokens=1}），并把上游的答复作为证据回给控制台。
     *
     * <p>它存在的理由是「连接测试通过」并不等于「模型能用」：DNS/TCP/TLS 探测对一把错误 的密钥、一个拼错的模型名、一个 404 的地址，结论同样是
     * HEALTHY。只有真发一次请求 才能把这三件事区分开。
     *
     * <p><b>{@code ok} 一律由 {@link #ok()} 判定</b>（{@code modelInvoked && modelOk}），
     * 不要在界面上直接读这个分量：只探可达性时它不成立，而「地址通」恰恰是最容易被误读成 「模型可用」的那种假阳性。
     */
    public record ChannelModelTestView(
            UUID channelId,
            String channelCode,
            String providerType,
            boolean credentialConfigured,
            boolean reachable,
            boolean modelInvoked,
            boolean modelOk,
            String status,
            String modelCode,
            String upstreamModel,
            String reason,
            Integer httpStatus,
            Long latencyMs,
            Long inputTokens,
            Long outputTokens,
            boolean healthPersisted,
            String upstreamMessage,
            List<ChannelModelChoiceView> models) {

        /**
         * 整个测试是否通过：必须真的调用过模型，且那次调用成功。
         *
         * <p>必须显式标注：Jackson 默认只序列化记录的<b>分量</b>，方法不会被当成属性， 少了这个注解，控制台读到的 {@code ok} 就会凭空消失。
         */
        @JsonProperty("ok")
        public boolean ok() {
            return modelInvoked && modelOk;
        }
    }

    /** 一条可以被真实调用的「渠道 ← 别名 ← 上游模型」映射。 */
    public record ChannelModelChoiceView(
            String modelCode,
            String displayName,
            String upstreamModel,
            boolean mappingEnabled,
            boolean modelEnabled,
            boolean textCapable,
            boolean probeable) {
        public ChannelModelChoiceView(
                String modelCode,
                String displayName,
                String upstreamModel,
                boolean mappingEnabled,
                boolean modelEnabled,
                boolean textCapable) {
            this(
                    modelCode,
                    displayName,
                    upstreamModel,
                    mappingEnabled,
                    modelEnabled,
                    textCapable,
                    mappingEnabled && modelEnabled && textCapable);
        }
    }

    public record CircuitHealthView(
            String key,
            String state,
            float failureRate,
            int bufferedCalls,
            UUID channelId,
            String modelCode) {

        public CircuitHealthView(String key, String state, float failureRate, int bufferedCalls) {
            this(key, state, failureRate, bufferedCalls, null, null);
        }
    }

    public record RoutingTargetView(
            UUID channelId,
            String channelCode,
            String channelName,
            String providerName,
            String upstreamModel,
            boolean channelEnabled,
            boolean mappingEnabled,
            String healthStatus,
            boolean credentialConfigured,
            boolean selected,
            boolean eligible) {}

    public record RoutingHealth(List<ChannelView> channels, List<CircuitHealthView> circuits) {}

    /**
     * 一个供应商类型能服务什么。
     *
     * <p>{@code chatCapabilities} 是对话适配器声明的输入模态（TEXT/IMAGE/FILE）；
     * 三类生成能力分别来自生图、视频与音频适配器。运营在「新增服务商」时据此选型， 避免把能读图的 key 挂到一个没有对话适配器的类型下。
     *
     * <p>{@code audioCapabilities} 是**具体的**音频能力名（{@code AUDIO_TRANSCRIBE} / {@code AUDIO_SYNTHESIS}
     * / {@code LIP_SYNC}），而不是一个布尔值： 一个供应商完全可以只做转写而不做换口型，而运营在控制台里要看的正是 那个粒度。这与 {@code
     * chatCapabilities} 用具体模态名是同一个理由—— 「能做音频」这句话在选型时没有信息量。
     */
    public record ProviderTypeCapabilitiesView(
            String providerType,
            Set<String> chatCapabilities,
            boolean imageGeneration,
            boolean videoGeneration,
            Set<String> audioCapabilities) {}

    /**
     * 用渠道里已保存的密钥向上游 {@code GET {baseUrl}/models} 探一次的结果。
     *
     * <p>{@code keyValid=false} 时 {@code httpStatus} 是上游的应答码（401/403 表示 密钥无效），{@code error}
     * 给出稳定原因；这把「填进去的 key 到底能不能用」 从「等真实调用失败」提前到了保存的那一刻。
     */
    public record ChannelModelProbeView(
            UUID channelId, boolean keyValid, int httpStatus, List<String> models, String error) {}

    /**
     * 按能力逐条探测的结果：这条渠道上，每种能力现在能不能用。
     *
     * <p>存在的理由是 {@link ChannelModelProbeView} 的盲区：它只会请求 {@code
     * {base}/models}，而那是一条只属于**对话/视觉**的端点——百炼的音频、视频、 生图模型都不在它上面。于是一把正确的 key 填进音频渠道、点「检测密钥」，得到的 是
     * {@code HTTP 404 upstream_rejected}：一个看起来像"key 错了"、实际与 key 毫无 关系的结论。
     *
     * <p>本视图对每种能力都打**它自己真正会用的那个端点**，并按下面的判据给出结论：
     *
     * <ul>
     *   <li>{@code credentialAccepted=true} —— 上游没有拒绝凭据。注意 400/422 也属于
     *       这一类：探测体刻意用空输入，上游在参数校验处停下，恰恰证明鉴权已经通过。 一个没通过鉴权的请求不可能走到参数校验。
     *   <li>{@code credentialAccepted=false} 且 {@code httpStatus} 为 401/403 —— 凭据有问题，这才是真正该去换 key
     *       的情况。
     *   <li>{@code error=endpoint_not_found} —— 地址错了，与 key 无关。
     *   <li>{@code error=upstream_unreachable} —— 连不上，与 key 无关。
     * </ul>
     *
     * <p>{@code url} 回显了实际请求的地址，让"地址填错"能被一眼认出来，而不是靠猜。 探测体带的是占位素材，**不会产生一次真实的生成或计费**。
     */
    public record CapabilityProbeView(
            String capability,
            String method,
            String url,
            int httpStatus,
            boolean credentialAccepted,
            String error) {}

    /**
     * 批量接入里每条路由的结果。
     *
     * <p>{@code status} 取值：{@code CREATED}（新建映射）、{@code UPDATED}（改写既有映射）、 {@code
     * REJECTED}（被能力守卫或校验拒绝，{@code reason} 说明原因）。
     */
    public record RouteOnboardingResult(
            String modelCode, String upstreamModel, String status, String reason) {}

    /**
     * 批量接入的应答。
     *
     * <p>{@code notes} 是「接完了但还不能用」的提示：例如视频别名还缺按秒计价， 或某个别名已存在优先级更高的渠道。它们不是失败，但必须让运营看见。
     */
    public record RoutesOnboardingView(
            UUID channelId, List<RouteOnboardingResult> routes, List<String> notes) {}

    /**
     * 删除服务商的结果：删掉了什么，一眼可见。
     *
     * <p>删除是不可逆的破坏性操作，因此应答里带上各类被删行数，审计日志里也留一份快照。
     */
    public record ProviderDeletionView(
            UUID id, String code, long deletedChannels, long deletedRoutes, long deletedPrices) {}
}
