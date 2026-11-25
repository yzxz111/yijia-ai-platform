package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
final class RedisLoginHandoffRateLimiter implements LoginHandoffRateLimiter {

    private static final String PREFIX = "identity:login-handoff:v1:";
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ACQUIRE = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return {count, redis.call('TTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final int limit;
    private final long windowSeconds;

    @Autowired
    RedisLoginHandoffRateLimiter(
            ObjectProvider<StringRedisTemplate> redis,
            @Value("${identity.terminal-session.handoff-limit:5}") int limit,
            @Value("${identity.terminal-session.handoff-window:10m}") Duration window) {
        this(redis.getIfAvailable(), limit, window);
    }

    RedisLoginHandoffRateLimiter(StringRedisTemplate redis, int limit, Duration window) {
        this.redis = redis;
        this.limit = limit;
        this.windowSeconds = window.toSeconds();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void acquire(UUID userId) {
        try {
            if (redis == null) throw new IllegalStateException("redis_unavailable");
            List<Object> result = redis.execute(
                    ACQUIRE, List.of(PREFIX + userId), Long.toString(windowSeconds));
            if (result == null || result.size() != 2) {
                throw new IllegalStateException("invalid_rate_limit_result");
            }
            long count = ((Number) result.get(0)).longValue();
            long ttl = Math.max(1L, ((Number) result.get(1)).longValue());
            if (count > limit) throw IdentityApiException.tooManyLoginHandoffs(Duration.ofSeconds(ttl));
        } catch (IdentityApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw IdentityApiException.loginHandoffUnavailable();
        }
    }
}
