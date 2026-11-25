package com.yuzhihuihe.apiplatform.gateway.limit;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * 统计某个套餐被拒绝的准入次数，从而无需一张会无界增长的表，也能回答
 * 「这个客户被拦截了多少次」。
 *
 * <p>它记录拒绝产生的位置 —— 也就是抛出 {@code 429} 的同一批位置 —— 并以套餐实例和拒绝自身的
 * 错误码为键来计数，因此面向运营人员的 {@code /internal/limit-usage} 端点能够说明某个套餐撞上了
 * 什么、频次如何，而不只是「有东西被拒绝了」。
 *
 * <p><b>它能承诺什么。</b>该计数器仅存在于 Redis，且只作参考：它的写入与读取都只用于报表，
 * 绝不用于准入决策，因此 Redis 故障只会丢失计数，而不会阻断流量（{@link #record} 是发后即忘，
 * {@link #last24Hours} 返回当时已有的内容）。所以计数在 Redis 故障期间以及键过期之后会少报；
 * 它绝不会多报。
 */
public interface BlockedAdmissionService {

    String DAILY_REQUEST_LIMIT_EXCEEDED = "daily_request_limit_exceeded";
    String TOKENS_PER_MINUTE_EXCEEDED = "tokens_per_minute_exceeded";
    String SECONDS_PER_MINUTE_EXCEEDED = "seconds_per_minute_exceeded";
    /**
     * 与 {@link #SECONDS_PER_MINUTE_EXCEEDED} 平行：{@code IMAGE} 维度的每分钟上限
     * 计的是**张**，把它报告成秒会让客户以为自己的视频额度用完了。
     */
    String IMAGES_PER_MINUTE_EXCEEDED = "images_per_minute_exceeded";
    String RATE_LIMIT_EXCEEDED = "rate_limit_exceeded";
    String UNKNOWN_REASON = "unknown";

    /**
     * 计数一次被拒绝的准入。立即返回，且绝不传播失败：调用方本来就在去抛出它所上报的那个错误的
     * 路上。
     */
    void record(UUID packageInstanceId, String reason);

    /**
     * 最近 24 小时内按原因分组的拒绝次数，最新窗口优先，并受计数器自身过期时间的限制。没有任何
     * 计数时返回空，Redis 不可用时报告的同样是空。
     */
    Mono<Map<String, Long>> last24Hours(UUID packageInstanceId);

    /**
     * 一个错误的拒绝码，也就是客户端看到的那个错误码：每一次准入拒绝都是一个
     * {@link ResponseStatusException}，其 reason 就是它文档化的错误码
     * （{@code daily_request_limit_exceeded}、{@code tokens_per_minute_exceeded}、
     * {@code seconds_per_minute_exceeded}、{@code images_per_minute_exceeded}、
     * {@code rate_limit_exceeded}）。
     */
    static String reasonOf(Throwable error) {
        if (error instanceof ResponseStatusException response
                && response.getStatusCode().equals(HttpStatus.TOO_MANY_REQUESTS)
                && response.getReason() != null
                && !response.getReason().isBlank()) {
            return response.getReason();
        }
        return UNKNOWN_REASON;
    }

    /** 一个丢弃所收内容的服务：在未配置任何计数器时使用。 */
    BlockedAdmissionService DISABLED = new BlockedAdmissionService() {

        @Override
        public void record(UUID packageInstanceId, String reason) {
        }

        @Override
        public Mono<Map<String, Long>> last24Hours(UUID packageInstanceId) {
            return Mono.just(Map.of());
        }
    };

    static BlockedAdmissionService disabled() {
        return DISABLED;
    }
}
