package com.yuzhihuihe.apiplatform.platform.reporting;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public final class InternalOperatingFactHmacFilter implements Filter {

    static final String PATH = "/internal/platform/operating-facts/usage-events";
    private static final HexFormat HEX = HexFormat.of();
    private final byte[] secret;
    private final Duration allowedClockSkew;
    private final Clock clock;
    private final Predicate<UUID> replayClaim;
    private final int maximumBodySizeBytes;

    @Autowired
    public InternalOperatingFactHmacFilter(
            @Value("${security.internal.hmac-secret:}") String secret,
            @Value("${security.internal.allowed-clock-skew:5m}") Duration allowedClockSkew,
            @Value("${security.internal.request-replay-ttl:11m}") Duration replayTtl,
            @Value("${security.internal.maximum-body-size-bytes:1048576}") int maximumBodySizeBytes,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        this(secret, allowedClockSkew, Clock.systemUTC(),
                replay(redisProvider, validateReplayTtl(allowedClockSkew, replayTtl)),
                maximumBodySizeBytes);
    }

    InternalOperatingFactHmacFilter(String secret, Duration allowedClockSkew, Clock clock,
            Predicate<UUID> replayClaim, int maximumBodySizeBytes) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.allowedClockSkew = Objects.requireNonNull(allowedClockSkew, "allowedClockSkew");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.replayClaim = Objects.requireNonNull(replayClaim, "replayClaim");
        if (allowedClockSkew.isNegative() || maximumBodySizeBytes <= 0) {
            throw new IllegalArgumentException("invalid_operating_fact_hmac_configuration");
        }
        this.maximumBodySizeBytes = maximumBodySizeBytes;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse output)
                || !PATH.equals(http.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = http.getInputStream().readNBytes(maximumBodySizeBytes + 1);
        if (body.length > maximumBodySizeBytes) {
            output.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        UUID requestId = verify(http, body);
        if (requestId == null || !claim(requestId)) {
            output.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(new CachedRequest(http, body), response);
    }

    private boolean claim(UUID requestId) {
        try { return replayClaim.test(requestId); }
        catch (RuntimeException unavailable) { return false; }
    }

    private UUID verify(HttpServletRequest request, byte[] body) {
        String timestamp = request.getHeader("X-Internal-Timestamp");
        String requestId = request.getHeader("X-Internal-Request-Id");
        String bodyHash = request.getHeader("X-Internal-Content-Sha256");
        String signature = request.getHeader("X-Internal-Signature");
        if (secret.length == 0 || timestamp == null || requestId == null
                || !lowerHash(bodyHash) || !lowerHash(signature)) return null;
        try {
            long epoch = Long.parseLong(timestamp);
            if (Duration.between(Instant.ofEpochSecond(epoch), clock.instant()).abs()
                    .compareTo(allowedClockSkew) > 0) return null;
            UUID id = UUID.fromString(requestId);
            if (!id.toString().equals(requestId)) return null;
            byte[] actualHash = MessageDigest.getInstance("SHA-256").digest(body);
            if (!MessageDigest.isEqual(actualHash, HEX.parseHex(bodyHash))) return null;
            String canonical = timestamp + "\n" + id + "\n"
                    + request.getMethod().toUpperCase(Locale.ROOT) + "\n"
                    + request.getRequestURI() + "\n" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)),
                    HEX.parseHex(signature)) ? id : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    private static Predicate<UUID> replay(
            ObjectProvider<StringRedisTemplate> provider, Duration ttl) {
        StringRedisTemplate redis = provider.getIfAvailable();
        if (redis == null || ttl == null || ttl.isZero() || ttl.isNegative()) return ignored -> false;
        return id -> Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                "internal:operating-fact:request-id:" + id, "1", ttl));
    }

    private static Duration validateReplayTtl(Duration skew, Duration ttl) {
        if (skew == null || skew.isNegative() || ttl == null || ttl.isZero() || ttl.isNegative()
                || ttl.compareTo(skew.multipliedBy(2)) <= 0) {
            throw new IllegalArgumentException("operating_fact_replay_ttl_too_short");
        }
        return ttl;
    }

    private static boolean lowerHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        CachedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() { return input.read(); }
            };
        }
    }
}
