package com.yuzhihuihe.apiplatform.platform.sms;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class RedisSmsChallengeIpRateLimiter implements SmsChallengeIpRateLimiter {

    private static final String KEY_PREFIX = "sms:challenge:ip-minute:v1:";
    private static final int LIMIT = 5;
    private static final long TTL_SECONDS = 120;
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    private static final RedisScript<Long> RESERVE = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            if count > tonumber(ARGV[2]) then
              redis.call('DECR', KEYS[1])
              return 0
            end
            return count
            """, Long.class);

    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count <= 0 then return 0 end
            if count == 1 then
              redis.call('DEL', KEYS[1])
              return 0
            end
            return redis.call('DECR', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public RedisSmsChallengeIpRateLimiter(
            ObjectProvider<StringRedisTemplate> redisProvider, Clock clock) {
        this(redisProvider.getIfAvailable(), clock);
    }

    RedisSmsChallengeIpRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Reservation reserve(Scope scope, String requestIpHash) {
        if (redis == null || scope == null || requestIpHash == null
                || !FINGERPRINT.matcher(requestIpHash).matches()) {
            throw unavailable();
        }
        String key = KEY_PREFIX + scope.name().toLowerCase(Locale.ROOT) + ":"
                + requestIpHash + ":" + clock.instant().getEpochSecond() / 60;
        try {
            Long result = redis.execute(
                    RESERVE, List.of(key), Long.toString(TTL_SECONDS), Integer.toString(LIMIT));
            if (result == null || result < 0 || result > LIMIT) {
                throw unavailable();
            }
            if (result == 0) {
                throw rateLimited();
            }
            return new Reservation(key);
        } catch (SmsApiException error) {
            throw error;
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    @Override
    public void release(Reservation reservation) {
        if (reservation == null || !reservation.beginRelease()) {
            return;
        }
        try {
            redis.execute(RELEASE, List.of(reservation.key()));
        } catch (RuntimeException ignored) {
            // 当 Redis 清理不可用时，桶的 TTL 会安全地清除该预留。
        }
    }

    private SmsApiException rateLimited() {
        return new SmsApiException(HttpStatus.TOO_MANY_REQUESTS, "sms_rate_limited_ip_minute");
    }

    private SmsApiException unavailable() {
        return new SmsApiException(HttpStatus.SERVICE_UNAVAILABLE, "sms_rate_limit_unavailable");
    }
}
