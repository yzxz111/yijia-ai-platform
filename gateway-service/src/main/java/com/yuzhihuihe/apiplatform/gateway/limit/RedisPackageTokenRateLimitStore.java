package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Mono;

/**
 * 所售每分钟速率的主额度计数器。
 *
 * <p>每个套餐实例一个 Redis hash，存放它正在计数的那一分钟以及该分钟内已计费的额度。检查与
 * 记录都以 Redis 服务器时钟、使用与每分钟请求限流器相同的表达式（{@code math.floor(TIME()/60)}）
 * 读取窗口，因此时钟偏移的网关主机无法以不同方式度量这两个上限；并且窗口是每次写入的一部分
 * 而不是键名的一部分——这使键保持稳定且可用于集群。计数器会自行过期，因此空闲的套餐不会留下
 * 任何残留。
 */
public final class RedisPackageTokenRateLimitStore implements PackageTokenRateLimitService.Store {

    private static final String KEY_PREFIX = "gateway:limit:{gateway-limits}:";

    /** 两分钟的余量：窗口是一分钟，因此过期的计数器绝不会再被读到。 */
    private static final long COUNTER_TTL_SECONDS = 120L;

    private static final RedisScript<Long> ADMIT = RedisScript.of("""
            local clock = redis.call('TIME')
            local window = math.floor(tonumber(clock[1]) / 60)
            local limit = tonumber(ARGV[1])
            local credits = 0
            if tonumber(redis.call('HGET', KEYS[1], 'window') or '-1') == window then
                credits = tonumber(redis.call('HGET', KEYS[1], 'credits') or '0')
            end
            if credits >= limit then
                return 0
            end
            return 1
            """, Long.class);

    private static final RedisScript<Long> RECORD = RedisScript.of("""
            local clock = redis.call('TIME')
            local window = math.floor(tonumber(clock[1]) / 60)
            local credits = 0
            if tonumber(redis.call('HGET', KEYS[1], 'window') or '-1') == window then
                credits = tonumber(redis.call('HGET', KEYS[1], 'credits') or '0')
            end
            local updated = credits + tonumber(ARGV[1])
            redis.call('HSET', KEYS[1], 'window', window, 'credits', updated)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            return updated
            """, Long.class);

    private static final RedisScript<Long> USED = RedisScript.of("""
            local clock = redis.call('TIME')
            local window = math.floor(tonumber(clock[1]) / 60)
            if tonumber(redis.call('HGET', KEYS[1], 'window') or '-1') == window then
                return tonumber(redis.call('HGET', KEYS[1], 'credits') or '0')
            end
            return 0
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;

    public RedisPackageTokenRateLimitStore(ReactiveStringRedisTemplate redis) {
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
            RequestLimitService.PackageTokenLimit limit, UUID userId, Instant minute) {
        if (limit == null || !limit.enforced()) {
            return Mono.error(new IllegalArgumentException("invalid_package_token_limit"));
        }
        return redis.execute(ADMIT, List.of(counterKey(limit.packageInstanceId())), List.of(
                        Long.toString(limit.tokensPerMinute())))
                .next()
                .map(result -> result == 1L);
    }

    @Override
    public Mono<Void> record(RequestLimitService.PackageTokenLimit limit, long chargedCredits) {
        if (limit == null || !limit.enforced() || chargedCredits <= 0) {
            return Mono.error(new IllegalArgumentException("invalid_package_token_limit"));
        }
        return redis.execute(RECORD, List.of(counterKey(limit.packageInstanceId())), List.of(
                        Long.toString(chargedCredits),
                        Long.toString(COUNTER_TTL_SECONDS)))
                .then();
    }

    @Override
    public Mono<Long> used(
            RequestLimitService.PackageTokenLimit limit, UUID userId, Instant minute) {
        if (limit == null || !limit.enforced()) {
            return Mono.just(0L);
        }
        return redis.execute(USED, List.of(counterKey(limit.packageInstanceId())), List.of())
                .next()
                .defaultIfEmpty(0L);
    }

    String counterKey(UUID packageInstanceId) {
        return KEY_PREFIX + "credits:package:" + packageInstanceId;
    }
}
