package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Mono;

/**
 * 被阻断准入计数器：每个套餐实例一个 Redis hash，每个原因与 epoch 小时一个字段。
 *
 * <p>窗口是 24 个<em>完整</em>小时、按 epoch 小时对齐，而不是滑动的 24 小时，这正是小时要放在
 * 字段名里而不是放在 key 里的原因：一次拒绝会一直计数，直到它自己那个小时掉出窗口；hash 在最后
 * 一次拒绝之后 25 小时自行过期，因此不会有任何东西无界增长。读取时会汇总小时落在窗口内的字段，
 * 所以这个数字是对已记录拒绝的真实计数——当这些拒绝发生期间 Redis 不可用时，计数会少报，
 * 而它永远不会多报。
 */
public final class RedisBlockedAdmissionService implements BlockedAdmissionService {

    private static final Logger LOG = LoggerFactory.getLogger(RedisBlockedAdmissionService.class);

    private static final String KEY_PREFIX = "gateway:limit:{gateway-limits}:";

    /** 比窗口多一小时，因此某个拒绝所属的小时在被读取之前绝不会过期。 */
    private static final long COUNTER_TTL_SECONDS = 90000L;

    private static final int WINDOW_HOURS = 24;

    private static final int MAX_REASON_LENGTH = 64;

    private static final RedisScript<Long> RECORD = RedisScript.of("""
            local clock = redis.call('TIME')
            local hour = math.floor(tonumber(clock[1]) / 3600)
            redis.call('HINCRBY', KEYS[1], ARGV[1] .. ':' .. hour, 1)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            return 1
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final Duration timeout;
    private final Clock clock;

    public RedisBlockedAdmissionService(ReactiveStringRedisTemplate redis, Duration timeout) {
        this(redis, timeout, Clock.systemUTC());
    }

    public RedisBlockedAdmissionService(
            ReactiveStringRedisTemplate redis, Duration timeout, Clock clock) {
        if (redis == null || timeout == null || timeout.isZero() || timeout.isNegative()
                || clock == null) {
            throw new IllegalArgumentException("invalid_blocked_admission_service");
        }
        this.redis = redis;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public void record(UUID packageInstanceId, String reason) {
        if (packageInstanceId == null || reason == null) {
            return;
        }
        try {
            redis.execute(RECORD, List.of(counterKey(packageInstanceId)),
                            List.of(sanitized(reason), Long.toString(COUNTER_TTL_SECONDS)))
                    .timeout(timeout)
                    .subscribe(
                            ignored -> { },
                            failure -> logFailure("record", packageInstanceId, failure));
        } catch (RuntimeException failure) {
            logFailure("record", packageInstanceId, failure);
        }
    }

    @Override
    public Mono<Map<String, Long>> last24Hours(UUID packageInstanceId) {
        if (packageInstanceId == null) {
            return Mono.just(Map.of());
        }
        long cutoffHour = windowStartHour();
        return redis.<String, String>opsForHash()
                .entries(counterKey(packageInstanceId))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .timeout(timeout)
                .map(entries -> sum(entries, cutoffHour))
                // 这是一次用于上报的读取：读不到的内容按「未计数」上报，
                // 而不是让运维正在用来诊断该套餐的端点失败。
                .onErrorResume(failure -> {
                    logFailure("read", packageInstanceId, failure);
                    return Mono.empty();
                })
                .defaultIfEmpty(Map.of());
    }

    private Map<String, Long> sum(Map<String, String> entries, long cutoffHour) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String field = entry.getKey();
            int separator = field.lastIndexOf(':');
            if (separator <= 0 || separator == field.length() - 1) {
                continue;
            }
            long hour;
            long count;
            try {
                hour = Long.parseLong(field.substring(separator + 1));
                count = Long.parseLong(entry.getValue());
            } catch (NumberFormatException malformed) {
                continue;
            }
            if (hour < cutoffHour || count <= 0) {
                continue;
            }
            totals.merge(field.substring(0, separator), count, Long::sum);
        }
        return totals;
    }

    private long windowStartHour() {
        return Instant.now(clock).truncatedTo(ChronoUnit.HOURS).getEpochSecond() / 3600
                - (WINDOW_HOURS - 1);
    }

    private String sanitized(String reason) {
        String cleaned = reason.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (cleaned.isBlank()) {
            return UNKNOWN_REASON;
        }
        return cleaned.length() > MAX_REASON_LENGTH
                ? cleaned.substring(0, MAX_REASON_LENGTH) : cleaned;
    }

    private void logFailure(String operation, UUID packageInstanceId, Throwable failure) {
        LOG.warn("blocked-admission {} for package {} failed; the count under-reports only: {}",
                operation, packageInstanceId,
                failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    String counterKey(UUID packageInstanceId) {
        return KEY_PREFIX + "blocked:package:" + packageInstanceId;
    }
}
