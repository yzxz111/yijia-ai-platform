package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Mono;

/**
 * 主用的每日计数器。每个套餐实例一个 Redis hash，其中保存它正在统计的上海时区日期以及
 * 该日期内迄今已准入的请求数，因此跨天切换与自增是原子的，也无需把时钟写进 key 名
 * （日期作为参数传递、key 保持稳定，从而在 Redis 集群中依然可用）。
 */
public final class RedisDailyRequestLimitStore implements DailyRequestLimitService.Store {

    private static final String KEY_PREFIX = "gateway:limit:{gateway-limits}:";

    private static final long COUNTER_TTL_SECONDS = 172800L;

    private static final RedisScript<Long> ADMIT = RedisScript.of("""
            local limit = tonumber(ARGV[1])
            local day = ARGV[2]
            local count = 0
            if redis.call('HGET', KEYS[1], 'day') == day then
                count = tonumber(redis.call('HGET', KEYS[1], 'count') or '0')
            end
            if count >= limit then
                return 0
            end
            redis.call('HSET', KEYS[1], 'day', day, 'count', count + 1)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            return 1
            """, Long.class);

    private static final RedisScript<Long> USED = RedisScript.of("""
            local day = ARGV[1]
            if redis.call('HGET', KEYS[1], 'day') == day then
                return tonumber(redis.call('HGET', KEYS[1], 'count') or '0')
            end
            return 0
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;

    public RedisDailyRequestLimitStore(ReactiveStringRedisTemplate redis) {
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
            RequestLimitService.PackageDailyLimit limit, UUID userId, LocalDate day) {
        if (limit == null || !limit.enforced() || day == null) {
            return Mono.error(new IllegalArgumentException("invalid_daily_request_limit"));
        }
        return redis.execute(ADMIT, List.of(counterKey(limit.packageInstanceId())), List.of(
                        Integer.toString(limit.dailyRequestLimit()),
                        day.toString(),
                        Long.toString(COUNTER_TTL_SECONDS)))
                .next()
                .map(result -> result == 1L);
    }

    @Override
    public Mono<Long> used(
            RequestLimitService.PackageDailyLimit limit, UUID userId, LocalDate day) {
        if (limit == null || !limit.enforced() || day == null) {
            return Mono.just(0L);
        }
        return redis.execute(USED, List.of(counterKey(limit.packageInstanceId())),
                        List.of(day.toString()))
                .next()
                .defaultIfEmpty(0L);
    }

    private String counterKey(UUID packageInstanceId) {
        return KEY_PREFIX + "daily:package:" + packageInstanceId;
    }
}
