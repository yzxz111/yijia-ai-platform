package com.yuzhihuihe.apiplatform.gateway.apikey;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ClientBootstrapRateLimiter {

    private static final String KEY_PREFIX = "gateway:client-bootstrap:";
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
    public ClientBootstrapRateLimiter(
            ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
            @Value("${gateway.client.bootstrap-limit:10}") int limit,
            @Value("${gateway.client.bootstrap-window:1m}") Duration window,
            @Value("${gateway.client.bootstrap-timeout:1s}") Duration timeout) {
        this(redisProvider.getIfAvailable(), limit, window, timeout);
    }

    ClientBootstrapRateLimiter(
            ReactiveStringRedisTemplate redis,
            int limit,
            Duration window,
            Duration timeout) {
        if (limit <= 0 || invalid(window) || invalid(timeout)) {
            throw new IllegalArgumentException("client_bootstrap_rate_limit_configuration_invalid");
        }
        this.redis = redis;
        this.limit = limit;
        this.window = window;
        this.timeout = timeout;
    }

    public Mono<Void> acquire(UUID userId, UUID sessionId) {
        if (userId == null || sessionId == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "invalid_credentials"));
        }
        if (redis == null) {
            return Mono.error(unavailable());
        }
        String key = KEY_PREFIX + "{" + userId + "}:" + sessionId;
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
                .onErrorMap(error -> !(error instanceof ResponseStatusException),
                        error -> unavailable());
    }

    private static boolean invalid(Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "client_bootstrap_unavailable");
    }

    private static final class RateLimitExceededException extends ResponseStatusException {
        private final HttpHeaders headers = new HttpHeaders();

        private RateLimitExceededException(Duration window) {
            super(HttpStatus.TOO_MANY_REQUESTS, "client_bootstrap_rate_limited");
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
