package com.yuzhihuihe.apiplatform.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class InternalHmacWebFilter implements WebFilter, Ordered {

    private static final String TIMESTAMP_HEADER = "X-Internal-Timestamp";
    private static final String REQUEST_ID_HEADER = "X-Internal-Request-Id";
    private static final String CONTENT_SHA256_HEADER = "X-Internal-Content-Sha256";
    private static final String SIGNATURE_HEADER = "X-Internal-Signature";
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] secret;
    private final long allowedClockSkewSeconds;
    private final int maximumBodySizeBytes;
    private final Clock clock;
    private final ReplayFence replayFence;
    private final Duration replayTtl;

    @Autowired
    public InternalHmacWebFilter(
            @Value("${security.internal.hmac-secret:}") String secret,
            @Value("${security.internal.allowed-clock-skew-seconds:300}") long allowedClockSkewSeconds,
            @Value("${security.internal.maximum-body-size-bytes:1048576}") int maximumBodySizeBytes,
            ObjectProvider<ReactiveStringRedisTemplate> redisProvider) {
        this(secret, allowedClockSkewSeconds, maximumBodySizeBytes, Clock.systemUTC(),
                redisFence(redisProvider.getIfAvailable()));
    }

    InternalHmacWebFilter(String secret, long allowedClockSkewSeconds) {
        this(secret, allowedClockSkewSeconds, 1_048_576, Clock.systemUTC(),
                new BoundedReplayFence(10_000));
    }

    InternalHmacWebFilter(
            String secret, long allowedClockSkewSeconds, int maximumBodySizeBytes, Clock clock) {
        this(secret, allowedClockSkewSeconds, maximumBodySizeBytes, clock,
                new BoundedReplayFence(10_000));
    }

    InternalHmacWebFilter(String secret, long allowedClockSkewSeconds,
            int maximumBodySizeBytes, Clock clock, ReplayFence replayFence) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.allowedClockSkewSeconds = allowedClockSkewSeconds;
        this.maximumBodySizeBytes = maximumBodySizeBytes;
        this.clock = clock;
        this.replayFence = replayFence;
        this.replayTtl = Duration.ofSeconds(Math.max(1, allowedClockSkewSeconds * 2));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/internal/")) {
            return chain.filter(exchange);
        }

        return DataBufferUtils.join(exchange.getRequest().getBody(), maximumBodySizeBytes)
                .map(buffer -> {
                    try {
                        byte[] body = new byte[buffer.readableByteCount()];
                        buffer.read(body);
                        return body;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> authenticate(exchange, chain, path, body))
                .onErrorResume(DataBufferLimitException.class,
                        exception -> reject(exchange, HttpStatus.PAYLOAD_TOO_LARGE));
    }

    private Mono<Void> authenticate(
            ServerWebExchange exchange, WebFilterChain chain, String path, byte[] body) {
        String timestamp = exchange.getRequest().getHeaders().getFirst(TIMESTAMP_HEADER);
        String requestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        String contentSha256 = exchange.getRequest().getHeaders().getFirst(CONTENT_SHA256_HEADER);
        String signature = exchange.getRequest().getHeaders().getFirst(SIGNATURE_HEADER);
        String method = exchange.getRequest().getMethod().name();
        if (!isValid(timestamp, requestId, contentSha256, signature, method, path, body)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }
        return replayFence.claim(requestId, replayTtl)
                .map(claimed -> claimed ? 1 : 0)
                .onErrorReturn(-1)
                .flatMap(result -> {
            if (result < 0) return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE);
            if (result == 0) return reject(exchange, HttpStatus.CONFLICT);
            CachedBodyServerHttpRequestDecorator request = new CachedBodyServerHttpRequestDecorator(
                    exchange.getRequest(), body, exchange.getResponse().bufferFactory());
            ServerWebExchange decorated = exchange.mutate().request(request).build();
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
            return chain.filter(decorated)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        });
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    private boolean isValid(
            String timestamp,
            String requestId,
            String contentSha256,
            String signature,
            String method,
            String path,
            byte[] body) {
        if (secret.length == 0
                || timestamp == null
                || requestId == null
                || contentSha256 == null
                || signature == null
                || !contentSha256.matches("[0-9a-f]{64}")
                || !signature.matches("[0-9a-f]{64}")) {
            return false;
        }
        try {
            long requestTime = Long.parseLong(timestamp);
            if (Duration.between(Instant.ofEpochSecond(requestTime), clock.instant()).abs().getSeconds()
                    > allowedClockSkewSeconds) {
                return false;
            }
            UUID parsedRequestId = UUID.fromString(requestId);
            if (!parsedRequestId.toString().equals(requestId)) {
                return false;
            }
            byte[] actualBodyHash = MessageDigest.getInstance("SHA-256").digest(body);
            if (!MessageDigest.isEqual(actualBodyHash, HEX.parseHex(contentSha256))) {
                return false;
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String canonical = timestamp + "\n" + parsedRequestId + "\n"
                    + method.toUpperCase(Locale.ROOT) + "\n" + path + "\n" + contentSha256;
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] supplied = HEX.parseHex(signature);
            return MessageDigest.isEqual(expected, supplied);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    interface ReplayFence {
        Mono<Boolean> claim(String requestId, Duration ttl);
    }

    private static ReplayFence redisFence(ReactiveStringRedisTemplate redis) {
        if (redis == null) return (requestId, ttl) -> Mono.error(
                new IllegalStateException("internal_hmac_replay_fence_unavailable"));
        return (requestId, ttl) -> redis.opsForValue()
                .setIfAbsent("internal:hmac:request:" + requestId, "1", ttl)
                .defaultIfEmpty(false);
    }

    /** 仅用于测试的有界重放围栏，由包级私有构造方法使用。 */
    private static final class BoundedReplayFence implements ReplayFence {
        private final int maximumEntries;
        private final Map<String, Instant> entries = new ConcurrentHashMap<>();
        private BoundedReplayFence(int maximumEntries) { this.maximumEntries = maximumEntries; }
        @Override public synchronized Mono<Boolean> claim(String requestId, Duration ttl) {
            Instant now = Instant.now();
            entries.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
            if (entries.containsKey(requestId) || entries.size() >= maximumEntries) {
                return Mono.just(false);
            }
            entries.put(requestId, now.plus(ttl));
            return Mono.just(true);
        }
    }
}
