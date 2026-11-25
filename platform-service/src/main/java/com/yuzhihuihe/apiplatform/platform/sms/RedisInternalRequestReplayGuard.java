package com.yuzhihuihe.apiplatform.platform.sms;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisInternalRequestReplayGuard {

    private static final String KEY_PREFIX = "internal:sms:request-id:";
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisInternalRequestReplayGuard(StringRedisTemplate redis, Duration ttl) {
        this.redis = Objects.requireNonNull(redis, "redis");
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("internal_request_replay_ttl_required");
        }
        this.ttl = ttl;
    }

    public boolean claim(UUID requestId) {
        if (requestId == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                    KEY_PREFIX + requestId, "1", ttl));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
