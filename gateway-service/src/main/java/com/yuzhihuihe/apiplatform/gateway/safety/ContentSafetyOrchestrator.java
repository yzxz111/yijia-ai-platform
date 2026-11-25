package com.yuzhihuihe.apiplatform.gateway.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Service
public final class ContentSafetyOrchestrator {
    private static final HexFormat HEX = HexFormat.of();
    private final LocalTextSafetyScanner local;
    private final SemanticSafetyProviderRegistry semantic;
    private final PolicySnapshotClient policies;
    private final ContentSafetyProperties properties;
    private final ContentSafetyCheckService checks;
    private final ContentSafetyEventOutboxService outbox;
    private final ContentSafetyMetrics metrics;
    private final FileSafetyScanner fileScanner;
    private final Scheduler contentSafetyScheduler;
    private final Scheduler fileSafetyScheduler;
    private final SafetyInputExtractor extractor = new SafetyInputExtractor();

    public ContentSafetyOrchestrator(LocalTextSafetyScanner local,
            SemanticSafetyProviderRegistry semantic, PolicySnapshotClient policies,
            ContentSafetyProperties properties, ContentSafetyCheckService checks,
            ContentSafetyEventOutboxService outbox, ContentSafetyMetrics metrics,
            FileSafetyScanner fileScanner) {
        this(local, semantic, policies, properties, checks, outbox, metrics, fileScanner,
                Schedulers.immediate(), Schedulers.immediate());
    }

    @Autowired
    public ContentSafetyOrchestrator(LocalTextSafetyScanner local,
            SemanticSafetyProviderRegistry semantic, PolicySnapshotClient policies,
            ContentSafetyProperties properties, ContentSafetyCheckService checks,
            ContentSafetyEventOutboxService outbox, ContentSafetyMetrics metrics,
            FileSafetyScanner fileScanner,
            @Qualifier("contentSafetyScheduler") Scheduler contentSafetyScheduler,
            @Qualifier("fileSafetyScheduler") Scheduler fileSafetyScheduler) {
        this.local = local;
        this.semantic = semantic;
        this.policies = policies;
        this.properties = properties;
        this.checks = checks;
        this.outbox = outbox;
        this.metrics = metrics;
        this.fileScanner = fileScanner;
        this.contentSafetyScheduler = contentSafetyScheduler;
        this.fileSafetyScheduler = fileSafetyScheduler;
    }

    public Mono<Decision> inspectInput(ApiKeyService.ApiKeyContext apiKey, JsonNode body, String requestId) {
        if (!properties.isEnabled()) return Mono.just(Decision.allow());
        return Mono.defer(() -> inspect(extractor.extract(body), apiKey, requestId, "INPUT"))
                .subscribeOn(contentSafetyScheduler);
    }

    public Mono<Decision> inspectNormalizedInput(
            ApiKeyService.ApiKeyContext apiKey, JsonNode body, String requestId) {
        if (!properties.isEnabled()) return Mono.just(Decision.allow());
        return Mono.defer(() -> inspect(
                        extractor.extract(body), apiKey, requestId, "INPUT_EXTRACTED"))
                .subscribeOn(contentSafetyScheduler);
    }

    public Mono<Decision> inspectOutput(ApiKeyService.ApiKeyContext apiKey, String text, String requestId) {
        if (!properties.isEnabled()) return Mono.just(Decision.allow());
        SafetyInput input = new SafetyInput(List.of(new TextSafetyInput.Field("assistant", text)), List.of());
        return inspect(input, apiKey, requestId, "OUTPUT");
    }

    public Mono<FileDecision> inspectFile(ApiKeyService.ApiKeyContext apiKey, byte[] bytes,
            String filename, String mimeType, String requestId) {
        long started = System.nanoTime();
        PolicySnapshot policy = currentPolicy();
        if (policy == null) {
            return finishFile(new FileSafetyResult(
                            FileSafetyResult.Result.UNAVAILABLE, "content_safety_unavailable", 0),
                    apiKey, requestId, fallbackPolicy(), started);
        }
        return Mono.fromCallable(() -> fileScanner.scan(bytes, filename, mimeType))
                .subscribeOn(fileSafetyScheduler)
                .flatMap(result -> finishFile(result, apiKey, requestId, policy, started));
    }

    private Mono<FileDecision> finishFile(FileSafetyResult result, ApiKeyService.ApiKeyContext apiKey,
            String requestId, PolicySnapshot policy, long started) {
        String outcome = result.result().name();
        boolean allowed = result.result() == FileSafetyResult.Result.ALLOW;
        boolean retryable = result.result() == FileSafetyResult.Result.UNAVAILABLE;
        FileDecision decision = new FileDecision(allowed, retryable, result.reasonCode());
        String fingerprint = sha256(requestId + "|FILE|" + result.reasonCode());
        metrics.checked("FILE", outcome);
        Map<String, Object> fileResult = Map.of(
                "result", outcome,
                "reasonCode", result.reasonCode(),
                "findingCount", result.result() == FileSafetyResult.Result.BLOCK ? 1 : 0,
                "inspectedBytes", result.inspectedBytes());
        Mono<Void> record = Mono.defer(() -> checks.record(requestId, apiKey == null ? null : apiKey.tenantId(),
                apiKey == null ? null : apiKey.userId(), apiKey == null ? null : apiKey.apiKeyId(), "FILE",
                outcome, policy.version(), Map.of(), Map.of(), fileResult,
                (System.nanoTime() - started) / 1_000_000, fingerprint))
                .onErrorMap(error -> new PersistenceFailure("check", error));
        Mono<Void> event = result.result() == FileSafetyResult.Result.BLOCK
                ? Mono.defer(() -> outbox.enqueue(new SafetyEventPayload(
                        "gw-" + fingerprint, "CONTENT_SAFETY_FILE", requestId,
                        apiKey == null ? null : apiKey.tenantId(), apiKey == null ? null : apiKey.userId(),
                        "FILE", "LOCAL", "FILE", "HIGH", "BLOCK", policy.version(), fingerprint,
                        null, result.reasonCode(), null, null, null)))
                        .onErrorMap(error -> new PersistenceFailure("outbox", error))
                : Mono.empty();
        return Mono.when(record, event).thenReturn(decision)
                .onErrorResume(PersistenceFailure.class, error -> {
                    metrics.persistenceFailed(error.kind());
                    return Mono.just(new FileDecision(false, true, "content_safety_unavailable"));
                });
    }

    public Mono<Decision> inspect(SafetyInput input, ApiKeyService.ApiKeyContext apiKey,
            String requestId, String direction) {
        return Mono.defer(() -> inspectCurrent(input, apiKey, requestId, direction))
                .subscribeOn(contentSafetyScheduler);
    }

    private Mono<Decision> inspectCurrent(SafetyInput input, ApiKeyService.ApiKeyContext apiKey,
            String requestId, String direction) {
        long started = System.nanoTime();
        PolicySnapshot policy = currentPolicy();
        if (policy == null) {
            return finish(Decision.block("content_safety_unavailable", "SAFETY_POLICY"), apiKey,
                    requestId, direction, fallbackPolicy(),
                    new SafetyScanResult(SafetyScanResult.Result.ALLOW, List.of()), null, started);
        }
        if (!input.complete()) {
            return finish(Decision.block(
                            "content_safety_unavailable", "SAFETY_INPUT_LIMIT"),
                    apiKey, requestId, direction, policy,
                    new SafetyScanResult(SafetyScanResult.Result.ALLOW, List.of()), null, started);
        }
        TextSafetyInput text = new TextSafetyInput(input.fields());
        SafetyScanResult localResult = local.scan(text, policy);
        if (localResult.result() == SafetyScanResult.Result.BLOCK) {
            return finish(Decision.block("content_safety_blocked", localResult.findings().getFirst().category()),
                    apiKey, requestId, direction, policy, localResult, null, started);
        }
        String combined = input.fields().stream().map(TextSafetyInput.Field::value)
                .collect(Collectors.joining("\n"));
        SafetyPayload payload = new SafetyPayload(combined, "request", input.fileReferences(), direction);
        if (combined.isBlank()) {
            return finish(Decision.allow(), apiKey, requestId, direction, policy, localResult, null, started);
        }
        return semantic.current().inspect(payload,
                        new SafetyContext(parseRequestId(requestId), apiKey == null ? null : apiKey.tenantId(), policy.version()))
                .timeout(properties.getSemantic().getTotalTimeout())
                .onErrorReturn(SemanticSafetyResult.unavailable("TIMEOUT"))
                .flatMap(result -> {
                    if (result.status() == SemanticSafetyResult.Status.BLOCK) {
                        return finish(Decision.block("content_safety_blocked", result.category()), apiKey,
                                requestId, direction, policy, localResult, result, started);
                    }
                    if (result.status() == SemanticSafetyResult.Status.UNAVAILABLE && properties.isFailClosed()) {
                        return finish(Decision.block("content_safety_unavailable", "SAFETY_PROVIDER"), apiKey,
                                requestId, direction, policy, localResult, result, started);
                    }
                    return finish(Decision.allow(), apiKey, requestId, direction, policy, localResult, result, started);
                });
    }

    private Mono<Decision> finish(Decision decision, ApiKeyService.ApiKeyContext apiKey, String requestId,
            String direction, PolicySnapshot policy, SafetyScanResult localResult,
            SemanticSafetyResult semanticResult, long started) {
        String fingerprint = sha256(requestId + "|" + direction + "|" + decision.code());
        metrics.checked(direction, decision.blocked() ? "BLOCK" : "ALLOW");
        Map<String, Object> localMap = Map.of("result", localResult.result().name(), "findingCount", localResult.findings().size());
        Map<String, Object> semanticMap = semanticResult == null ? Map.of() : Map.of(
                "result", semanticResult.status().name(), "provider", semanticResult.providerCode());
        Mono<Void> record = Mono.defer(() -> checks.record(requestId, apiKey == null ? null : apiKey.tenantId(),
                apiKey == null ? null : apiKey.userId(), apiKey == null ? null : apiKey.apiKeyId(), direction,
                decision.blocked() ? "BLOCK" : "ALLOW", policy.version(), localMap, semanticMap,
                Map.of("references", 0), (System.nanoTime() - started) / 1_000_000, fingerprint))
                .onErrorMap(error -> new PersistenceFailure("check", error));
        Mono<Void> event = decision.blocked()
                ? Mono.defer(() -> outbox.enqueue(new SafetyEventPayload(
                        "gw-" + fingerprint, "CONTENT_SAFETY_" + direction, requestId,
                        apiKey == null ? null : apiKey.tenantId(), apiKey == null ? null : apiKey.userId(),
                        direction, "LOCAL", "SAFETY", "HIGH", "BLOCK", policy.version(), fingerprint,
                        null, decision.code(), null, null, null)))
                        .onErrorMap(error -> new PersistenceFailure("outbox", error))
                : Mono.empty();
        return Mono.when(record, event).thenReturn(decision)
                .onErrorResume(PersistenceFailure.class, error -> {
                    metrics.persistenceFailed(error.kind());
                    return Mono.just(Decision.block("content_safety_unavailable", "SAFETY_AUDIT"));
                });
    }

    private PolicySnapshot currentPolicy() {
        var current = policies.current(properties.getPolicyCacheTtl());
        if (current.isPresent()) {
            return current.get();
        }
        metrics.policyExpired();
        return properties.isFailClosed() ? null : fallbackPolicy();
    }

    private PolicySnapshot fallbackPolicy() {
        return PolicySnapshot.signed(1, List.of(), Instant.EPOCH, "content-safety-local");
    }

    private UUID parseRequestId(String value) {
        try { return UUID.fromString(value); } catch (Exception ignored) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    }

    private String sha256(String value) {
        try { return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("content_safety_hash_failed", exception); }
    }

    public record Decision(boolean blocked, String code, String category) {
        public static Decision allow() { return new Decision(false, "ALLOW", ""); }
        public static Decision block(String code, String category) { return new Decision(true, code, category == null ? "SAFETY" : category); }
    }

    public record FileDecision(boolean allowed, boolean retryable, String code) { }

    private static final class PersistenceFailure extends RuntimeException {
        private final String kind;

        private PersistenceFailure(String kind, Throwable cause) {
            super(cause);
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        private String kind() {
            return kind;
        }
    }
}
