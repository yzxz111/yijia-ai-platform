package com.yuzhihuihe.apiplatform.platform.limitusage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.internalapi.GatewayInternalGet;
import com.yuzhihuihe.apiplatform.platform.internalapi.GatewayInternalUnavailableException;
import org.springframework.stereotype.Component;

/**
 * 读取网关为某个套餐实例维护的限流计数器。
 *
 * <p>只有网关知道某个套餐的每分钟与每日额度已消耗多少、以及准入被拒绝了多少次；平台
 * 不存储其中任何数据，也无法自行计算。因此当读取失败、或返回的是另一个实例的数据时，
 * 本客户端会抛出 {@link GatewayInternalUnavailableException}，而不是返回一份空的用量：
 * 控制台若渲染成零，就会把一个此刻正被限流的套餐显示成一个空闲套餐。
 *
 * <p>{@link LimitUsage} 既是网关的响应，也是控制台的响应体。把它逐字段映射到
 * 第二个 record 只会为这两份契约
 * 多创造一个可能彼此漂移的地方，
 * 因此这个已校验的 record 被原样交给 Jackson。
 */
@Component
public class GatewayLimitUsageClient {

    static final String PATH = "/internal/limit-usage";

    private final ObjectMapper objectMapper;
    private final GatewayInternalGet internalGet;

    public GatewayLimitUsageClient(ObjectMapper objectMapper, GatewayInternalGet internalGet) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.internalGet = Objects.requireNonNull(internalGet, "internalGet");
    }

    public LimitUsage fetch(UUID packageInstanceId) {
        Objects.requireNonNull(packageInstanceId, "packageInstanceId");
        byte[] body = internalGet.get(PATH, "packageInstanceId="
                + URLEncoder.encode(packageInstanceId.toString(), StandardCharsets.UTF_8));
        LimitUsage usage;
        try {
            usage = objectMapper.readValue(body, LimitUsage.class);
        } catch (Exception exception) {
            throw new GatewayInternalUnavailableException(
                    "gateway_limit_usage_payload_invalid", exception);
        }
        if (usage == null
                || !packageInstanceId.equals(usage.packageInstanceId())
                || usage.usageDimension() == null
                || usage.usageDimension().isBlank()) {
            throw new GatewayInternalUnavailableException("gateway_limit_usage_payload_invalid");
        }
        return usage;
    }

    /**
     * @param usageDimension 窗口以哪种单位计数（TOKEN 按每分钟入账的 token
     *                       数统计，VIDEO_SECOND 按秒数统计）；控制台需要它来为
     *                       这些数字加标签，因此该值为空会使响应体不可用
     */
    public record LimitUsage(
            UUID packageInstanceId,
            String usageDimension,
            Window perMinute,
            Window daily,
            long blockedLast24h,
            BlockedReasons blockedLast24HoursByReason) {

        /**
         * 刻意使用装箱类型：{@code null} 表示网关未给出数值（无限额的窗口，
         * 或该维度不适用于该窗口），
         * 而基本类型会悄悄把「未知」变成额度为零。
         */
        public record Window(Long limit, Long used) {
        }

        /**
         * 此处缺失的原因计数为零而非未知 —— 网关会统计拒绝次数，且从未因该原因拒绝过这个
         * 套餐 —— 因此这些字段保持基本类型。
         */
        public record BlockedReasons(
                @JsonProperty("daily_request_limit_exceeded") long dailyRequestLimitExceeded,
                @JsonProperty("tokens_per_minute_exceeded") long tokensPerMinuteExceeded) {
        }
    }
}
