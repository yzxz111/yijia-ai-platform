package com.yuzhihuihe.apiplatform.platform.sms;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ReadListener;
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
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class InternalSmsHmacFilter implements Filter {

    static final String AUTHENTICATED_REQUEST_ID_ATTRIBUTE =
            InternalSmsHmacFilter.class.getName() + ".authenticatedRequestId";
    private static final Set<String> AUTHENTICATED_PATHS = Set.of(
            "/internal/platform/sms/proofs/consume",
            "/internal/platform/sms/signals/package-expiry",
            "/internal/platform/sms/signals/rate-limit");
    private static final HexFormat HEX = HexFormat.of();
    private final byte[] secret;
    private final Duration allowedClockSkew;
    private final Clock clock;
    private final Predicate<UUID> replayClaim;
    private final int maximumBodySizeBytes;

    @Autowired
    public InternalSmsHmacFilter(
            @Value("${security.internal.hmac-secret:}") String secret,
            @Value("${security.internal.allowed-clock-skew:5m}") Duration allowedClockSkew,
            @Value("${security.internal.request-replay-ttl:11m}") Duration replayTtl,
            @Value("${security.internal.maximum-body-size-bytes:1048576}") int maximumBodySizeBytes,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        this(secret, allowedClockSkew, Clock.systemUTC(),
                replayClaim(redisProvider, validateReplayTtl(allowedClockSkew, replayTtl)),
                maximumBodySizeBytes);
    }

    InternalSmsHmacFilter(
            String secret,
            Duration allowedClockSkew,
            Clock clock,
            Predicate<UUID> replayClaim) {
        this(secret, allowedClockSkew, clock, replayClaim, 1_048_576);
    }

    InternalSmsHmacFilter(
            String secret,
            Duration allowedClockSkew,
            Clock clock,
            Predicate<UUID> replayClaim,
            int maximumBodySizeBytes) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.allowedClockSkew = allowedClockSkew;
        this.clock = clock;
        this.replayClaim = replayClaim;
        if (maximumBodySizeBytes <= 0) {
            throw new IllegalArgumentException("internal_sms_maximum_body_size_required");
        }
        this.maximumBodySizeBytes = maximumBodySizeBytes;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)
                || !AUTHENTICATED_PATHS.contains(httpRequest.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = httpRequest.getInputStream().readNBytes(maximumBodySizeBytes + 1);
        if (body.length > maximumBodySizeBytes) {
            httpResponse.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        UUID requestId = verify(httpRequest, body);
        if (requestId == null || !replayClaim.test(requestId)) {
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        CachedBodyRequest authenticatedRequest = new CachedBodyRequest(httpRequest, body);
        authenticatedRequest.setAttribute(AUTHENTICATED_REQUEST_ID_ATTRIBUTE, requestId);
        chain.doFilter(authenticatedRequest, response);
    }

    private UUID verify(HttpServletRequest request, byte[] body) {
        String timestamp = request.getHeader("X-Internal-Timestamp");
        String requestId = request.getHeader("X-Internal-Request-Id");
        String contentSha256 = request.getHeader("X-Internal-Content-Sha256");
        String signature = request.getHeader("X-Internal-Signature");
        if (secret.length == 0 || timestamp == null || requestId == null
                || !lowercaseHash(contentSha256) || !lowercaseHash(signature)) {
            return null;
        }
        try {
            long epochSecond = Long.parseLong(timestamp);
            if (Duration.between(Instant.ofEpochSecond(epochSecond), clock.instant()).abs()
                    .compareTo(allowedClockSkew) > 0) {
                return null;
            }
            UUID parsedRequestId = UUID.fromString(requestId);
            if (!parsedRequestId.toString().equals(requestId)) {
                return null;
            }
            byte[] actualHash = MessageDigest.getInstance("SHA-256").digest(body);
            if (!MessageDigest.isEqual(actualHash, HEX.parseHex(contentSha256))) {
                return null;
            }
            String canonical = timestamp + "\n" + parsedRequestId + "\n"
                    + request.getMethod().toUpperCase(Locale.ROOT) + "\n"
                    + request.getRequestURI() + "\n" + contentSha256;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return MessageDigest.isEqual(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)),
                    HEX.parseHex(signature)) ? parsedRequestId : null;
        } catch (Exception invalidRequest) {
            return null;
        }
    }

    private boolean lowercaseHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static Predicate<UUID> replayClaim(
            ObjectProvider<StringRedisTemplate> redisProvider, Duration replayTtl) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return ignored -> false;
        }
        RedisInternalRequestReplayGuard guard =
                new RedisInternalRequestReplayGuard(redis, replayTtl);
        return guard::claim;
    }

    private static Duration validateReplayTtl(
            Duration allowedClockSkew, Duration replayTtl) {
        if (allowedClockSkew == null || allowedClockSkew.isNegative()) {
            throw new IllegalArgumentException("internal_sms_allowed_clock_skew_required");
        }
        Duration minimumReplayTtl;
        try {
            minimumReplayTtl = allowedClockSkew.multipliedBy(2);
        } catch (ArithmeticException invalidWindow) {
            throw new IllegalArgumentException(
                    "internal_sms_allowed_clock_skew_required", invalidWindow);
        }
        if (replayTtl == null || replayTtl.isZero() || replayTtl.isNegative()
                || replayTtl.compareTo(minimumReplayTtl) <= 0) {
            throw new IllegalArgumentException("internal_sms_replay_ttl_too_short");
        }
        return replayTtl;
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // 同步的 servlet 请求体。
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }
    }
}
