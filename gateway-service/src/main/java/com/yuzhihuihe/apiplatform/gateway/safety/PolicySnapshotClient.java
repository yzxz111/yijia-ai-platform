package com.yuzhihuihe.apiplatform.gateway.safety;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class PolicySnapshotClient {

    private static final String POLICY_PATH = "/internal/gateway/content-safety/policy";
    private static final HexFormat HEX = HexFormat.of();
    private final String signingSecret;
    private final String requestSigningSecret;
    private final WebClient client;
    private final Duration timeout;
    private final Clock clock;
    private final AtomicReference<AcceptedSnapshot> current = new AtomicReference<>();

    @Autowired
    public PolicySnapshotClient(
            WebClient.Builder builder,
            @Value("${content-safety.policy-signing-secret:${SECURITY_INTERNAL_HMAC_SECRET:}}") String signingSecret,
            @Value("${security.internal.hmac-secret:}") String requestSigningSecret,
            @Value("${platform.content-safety.internal-base-url:http://platform-service:8080}") String baseUrl,
            @Value("${content-safety.policy-refresh-timeout:3s}") Duration timeout) {
        this.signingSecret = signingSecret == null ? "" : signingSecret;
        this.requestSigningSecret = requestSigningSecret == null ? "" : requestSigningSecret;
        if (baseUrl == null || baseUrl.isBlank() || timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("content_safety_policy_client_configuration_invalid");
        }
        this.client = builder.baseUrl(baseUrl).build();
        this.timeout = timeout;
        this.clock = Clock.systemUTC();
    }

    public PolicySnapshotClient(String signingSecret) {
        this(signingSecret, Clock.systemUTC());
    }

    public PolicySnapshotClient(String signingSecret, boolean testOnly) {
        this(signingSecret, Clock.systemUTC());
    }

    PolicySnapshotClient(String signingSecret, Clock clock) {
        this.signingSecret = signingSecret == null ? "" : signingSecret;
        this.requestSigningSecret = "";
        this.client = null;
        this.timeout = Duration.ofSeconds(1);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean accept(PolicySnapshot candidate) {
        if (candidate == null || !candidate.hasValidSignature(signingSecret)) {
            return false;
        }
        while (true) {
            AcceptedSnapshot previous = current.get();
            if (previous == null || candidate.version() > previous.snapshot().version()) {
                AcceptedSnapshot accepted = new AcceptedSnapshot(candidate, clock.instant());
                if (current.compareAndSet(previous, accepted)) {
                    return true;
                }
                continue;
            }
            if (candidate.version() == previous.snapshot().version()
                    && candidate.equals(previous.snapshot())) {
                AcceptedSnapshot refreshed = new AcceptedSnapshot(previous.snapshot(), clock.instant());
                if (current.compareAndSet(previous, refreshed)) {
                    return false;
                }
                continue;
            }
            return false;
        }
    }

    public Optional<PolicySnapshot> current() {
        return Optional.ofNullable(current.get()).map(AcceptedSnapshot::snapshot);
    }

    public Optional<PolicySnapshot> current(Duration ttl) {
        AcceptedSnapshot accepted = current.get();
        if (accepted == null || ttl == null || ttl.isZero() || ttl.isNegative()
                || !accepted.acceptedAt().plus(ttl).isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(accepted.snapshot());
    }

    public Mono<Boolean> refresh() {
        if (client == null || requestSigningSecret.isBlank()) {
            return Mono.error(new IllegalStateException("content_safety_policy_sync_unavailable"));
        }
        return Mono.defer(() -> {
            UUID requestId = UUID.randomUUID();
            byte[] body = new byte[0];
            String timestamp = Long.toString(clock.instant().getEpochSecond());
            String contentHash = sha256(body);
            String signature = hmac(timestamp + "\n" + requestId + "\nGET\n"
                    + POLICY_PATH + "\n" + contentHash, requestSigningSecret);
            return client.method(HttpMethod.GET).uri(POLICY_PATH)
                    .header("X-Internal-Timestamp", timestamp)
                    .header("X-Internal-Request-Id", requestId.toString())
                    .header("X-Internal-Content-Sha256", contentHash)
                    .header("X-Internal-Signature", signature)
                    .retrieve()
                    .bodyToMono(RemotePolicySnapshot.class)
                    .timeout(timeout)
                    .map(this::acceptRemote);
        });
    }

    private boolean acceptRemote(RemotePolicySnapshot response) {
        if (response == null || response.rules() == null || response.signature() == null) {
            throw new IllegalStateException("content_safety_policy_invalid");
        }
        List<PolicySnapshot.Rule> rules = response.rules().stream()
                .map(rule -> new PolicySnapshot.Rule(rule.ruleCode(), rule.ruleType(),
                        rule.category(), rule.severity(), rule.action()))
                .toList();
        PolicySnapshot candidate = new PolicySnapshot(response.version(), response.configSha256(), rules,
                response.publishedAt(), response.signature());
        return accept(candidate);
    }

    private String sha256(byte[] body) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception exception) {
            throw new IllegalStateException("content_safety_policy_hash_failed", exception);
        }
    }

    private String hmac(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("content_safety_policy_signature_failed", exception);
        }
    }

    private record AcceptedSnapshot(PolicySnapshot snapshot, Instant acceptedAt) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RemotePolicySnapshot(
            long version, java.util.Map<String, Object> config, String configSha256,
            Instant publishedAt, List<RemoteRule> rules, String signature) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RemoteRule(
            String ruleCode, String ruleType, String category, String severity, String action) { }
}
