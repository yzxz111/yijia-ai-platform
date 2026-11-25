package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
final class TerminalPresenceService {

    private static final String PREFIX = "identity:terminal-presence:v1:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    @Autowired
    TerminalPresenceService(
            ObjectProvider<StringRedisTemplate> redis,
            @Value("${identity.terminal-session.presence-ttl:120s}") Duration ttl) {
        this(redis.getIfAvailable(), ttl);
    }

    TerminalPresenceService(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    void markPresent(UUID userId, UUID sessionId) {
        try {
            if (redis == null) throw new IllegalStateException("redis_unavailable");
            redis.opsForValue().set(key(userId, sessionId), "1", ttl);
        } catch (RuntimeException error) {
            throw IdentityApiException.loginHandoffUnavailable();
        }
    }

    boolean isOnline(UUID userId, UUID sessionId) {
        try {
            return redis == null || Boolean.TRUE.equals(redis.hasKey(key(userId, sessionId)));
        } catch (RuntimeException error) {
            return true;
        }
    }

    private String key(UUID userId, UUID sessionId) {
        return PREFIX + userId + ":" + sessionId;
    }
}
