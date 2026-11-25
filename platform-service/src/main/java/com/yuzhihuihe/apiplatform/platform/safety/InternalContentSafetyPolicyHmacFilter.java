package com.yuzhihuihe.apiplatform.platform.safety;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class InternalContentSafetyPolicyHmacFilter implements Filter {

    static final String PATH = "/internal/gateway/content-safety/policy";
    private static final String REPLAY_KEY_PREFIX = "internal:content-safety-policy:request-id:";
    private final InternalRequestSigner signer;
    private final Predicate<UUID> replayClaim;
    private final int maximumBodySizeBytes;

    @Autowired
    public InternalContentSafetyPolicyHmacFilter(
            InternalRequestSigner signer,
            @Value("${security.internal.request-replay-ttl:11m}") Duration replayTtl,
            @Value("${security.internal.maximum-body-size-bytes:1048576}") int maximumBodySizeBytes,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        this(signer, replayClaim(redisProvider, replayTtl), maximumBodySizeBytes);
        if (replayTtl == null || replayTtl.compareTo(Duration.ofMinutes(10)) <= 0) {
            throw new IllegalArgumentException("content_safety_policy_replay_ttl_too_short");
        }
    }

    InternalContentSafetyPolicyHmacFilter(
            InternalRequestSigner signer, Predicate<UUID> replayClaim, int maximumBodySizeBytes) {
        if (maximumBodySizeBytes <= 0) {
            throw new IllegalArgumentException("content_safety_policy_body_size_invalid");
        }
        this.signer = signer;
        this.replayClaim = replayClaim;
        this.maximumBodySizeBytes = maximumBodySizeBytes;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http)
                || !(response instanceof HttpServletResponse output)
                || !PATH.equals(http.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = http.getInputStream().readNBytes(maximumBodySizeBytes + 1);
        if (body.length > maximumBodySizeBytes) {
            output.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        UUID requestId = requestId(http, body);
        if (requestId == null || !claim(requestId)) {
            output.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }

    private UUID requestId(HttpServletRequest request, byte[] body) {
        if (!"GET".equalsIgnoreCase(request.getMethod()) || body.length != 0) {
            return null;
        }
        try {
            String value = request.getHeader("X-Internal-Request-Id");
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) {
                return null;
            }
            long timestamp = Long.parseLong(request.getHeader("X-Internal-Timestamp"));
            return signer.verify(timestamp, id, "GET", PATH, body,
                    request.getHeader("X-Internal-Content-Sha256"),
                    request.getHeader("X-Internal-Signature")) ? id : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private boolean claim(UUID requestId) {
        try {
            return replayClaim.test(requestId);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static Predicate<UUID> replayClaim(
            ObjectProvider<StringRedisTemplate> provider, Duration ttl) {
        StringRedisTemplate redis = provider.getIfAvailable();
        if (redis == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return ignored -> false;
        }
        return requestId -> Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                REPLAY_KEY_PREFIX + requestId, "1", ttl));
    }
}
