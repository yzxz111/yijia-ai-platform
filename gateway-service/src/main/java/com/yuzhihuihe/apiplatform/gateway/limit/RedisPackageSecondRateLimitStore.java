package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Mono;

/**
 * 视频套餐已售每分钟速率的秒数主计数器。
 *
 * <p>它是 {@link RedisPackageTokenRateLimitStore} 的秒数孪生体——每个套餐实例一个 hash，
 * 保存它正在计数的分钟以及该分钟内已计入的秒数，窗口取自 Redis 服务器时钟，因此时钟偏移
 * 的网关主机无法以与其他主机不同的方式度量这一上限。该 key 故意与积分 key 分开：两个方向
 * 绝不共享同一个计数器，这正是 {@link PackageRateLimits} 的全部意义。
 */
public final class RedisPackageSecondRateLimitStore implements PackageSecondRateLimitService.Store {

    private static final String KEY_PREFIX = "gateway:limit:{gateway-limits}:";

    /** 两分钟的余量：窗口长度是一分钟，因此过期的计数器永远不会被再次读取。 */
    private static final long COUNTER_TTL_SECONDS = 120L;

    private static final RedisScript<Long> ADMIT = RedisScript.of("""
            local clock = redis.call('TIME')
            local window = math.floor(tonumber(clock[1]) / 60)
            local limit = tonumber(ARGV[1])
            local seconds = 0
            if tonumber(redis.call('HGET', KEYS[1], 'window') or '-1') == window then
                seconds = tonumber(redis.call('HGET', KEYS[1], 'seconds') or '0')
            end
            if seconds >= limit then
                return 0
            end
            return 1
            """, Long.class);

    private static final RedisScript<Long> RECORD = RedisScript.of("""
            local clock = redis.call('TIME')
            local window = math.floor(tonumber(clock[1]) / 60)
            local seconds = 0
            if tonumber(redis.call('HGET', KEYS[1], 'window') or '-1') == window then
                seconds = tonumber(redis.call('HGET', KEYS[1], 'seconds') or '0')
            end
            local updated = seconds + tonumber(ARGV[1])
            redis.call('HSET', KEYS[1], 'window', window, 'seconds', updated)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            return updated
            """, Long.class);

    private static final RedisScript<Long> USED = RedisScript.of("""
            local clock = redis.call('TIME')
            local window = math.floor(tonumber(clock[1]) / 60)
            if tonumber(redis.call('HGET', KEYS[1], 'window') or '-1') == window then
                return tonumber(redis.call('HGET', KEYS[1], 'seconds') or '0')
            end
            return 0
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;

    public RedisPackageSecondRateLimitStore(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Boolean> isAvailable() {
        return redis.hasKey(KEY_PREFIX + "availability-probe")
                .map(ignored -> true)
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Boolean> admit(
            RequestLimitService.PackageSecondLimit limit, UUID userId, Instant minute) {
        if (limit == null || !limit.enforced()) {
            return Mono.error(new IllegalArgumentException("invalid_package_second_limit"));
        }
        return redis.execute(ADMIT, List.of(counterKey(limit.packageInstanceId())), List.of(
                        Long.toString(limit.secondsPerMinute())))
                .next()
                .map(result -> result == 1L);
    }

    @Override
    public Mono<Void> record(UUID packageInstanceId, long chargedSeconds) {
        if (packageInstanceId == null || chargedSeconds <= 0) {
            return Mono.error(new IllegalArgumentException("invalid_package_second_record"));
        }
        return redis.execute(RECORD, List.of(counterKey(packageInstanceId)), List.of(
                        Long.toString(chargedSeconds),
                        Long.toString(COUNTER_TTL_SECONDS)))
                .then();
    }

    @Override
    public Mono<Long> used(
            RequestLimitService.PackageSecondLimit limit, UUID userId, Instant minute) {
        if (limit == null || !limit.enforced()) {
            return Mono.just(0L);
        }
        return redis.execute(USED, List.of(counterKey(limit.packageInstanceId())), List.of())
                .next()
                .defaultIfEmpty(0L);
    }

    String counterKey(UUID packageInstanceId) {
        return KEY_PREFIX + "seconds:package:" + packageInstanceId;
    }
}
