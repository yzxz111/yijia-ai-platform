package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Fixed-window download-session throttle keyed by identity ({@code api_key_id} or {@code user_id})
 * plus the device hash, mirroring {@code ClientBootstrapRateLimiter} (design R-19/R-34).
 *
 * <p>Redis unavailable → fail closed with {@code 503 skill_storage_unavailable}; the limiter never
 * degrades into "let everything through".
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillDownloadRateLimiter {

    private static final String KEY_PREFIX = "gateway:skill-download:";
    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return count
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final int limit;
    private final Duration window;
    private final Duration timeout;

    @Autowired
    public SkillDownloadRateLimiter(
            ObjectProvider<ReactiveStringRedisTemplate> redisProvider, SkillProperties properties) {
        this(redisProvider.getIfAvailable(),
                properties.getDownloadLimit(),
                properties.getDownloadWindow(),
                properties.getDownloadRateLimitTimeout());
    }

    SkillDownloadRateLimiter(
            ReactiveStringRedisTemplate redis, int limit, Duration window, Duration timeout) {
        if (limit <= 0 || invalid(window) || invalid(timeout)) {
            throw new IllegalArgumentException("skill_download_rate_limit_configuration_invalid");
        }
        this.redis = redis;
        this.limit = limit;
        this.window = window;
        this.timeout = timeout;
    }

    /** Consumes one slot of the caller's window; never invoked for {@code Range} segment reads. */
    public Mono<Void> acquire(String identity, String deviceIdHash) {
        if (identity == null || identity.isBlank()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "invalid_api_key"));
        }
        if (redis == null) {
            return Mono.error(unavailable());
        }
        String device = deviceIdHash == null || deviceIdHash.isBlank()
                ? "no-device"
                : deviceIdHash.toLowerCase(Locale.ROOT);
        String key = KEY_PREFIX + "{" + identity + "}:" + device;
        return redis.execute(
                        ACQUIRE,
                        List.of(key),
                        List.of(Integer.toString(limit), Long.toString(window.toMillis())))
                .next()
                .timeout(timeout)
                .switchIfEmpty(Mono.error(unavailable()))
                .flatMap(count -> count <= limit
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new RateLimitExceededException(window)))
                .onErrorMap(error -> !(error instanceof ResponseStatusException), error -> unavailable());
    }

    private static boolean invalid(Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "skill_storage_unavailable");
    }

    static final class RateLimitExceededException extends ResponseStatusException {

        private final HttpHeaders headers = new HttpHeaders();

        private RateLimitExceededException(Duration window) {
            super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
            long retryAfterSeconds = window.getSeconds();
            if (window.getNano() > 0 && retryAfterSeconds < Long.MAX_VALUE) {
                retryAfterSeconds++;
            }
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfterSeconds)));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}

