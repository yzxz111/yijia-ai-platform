package com.yuzhihuihe.apiplatform.gateway.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ContentSafetyOrchestratorTest {

    private PolicySnapshotClient policies;
    private ContentSafetyCheckService checks;
    private ContentSafetyMetrics metrics;
    private ContentSafetyOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        LocalTextSafetyScanner local = mock(LocalTextSafetyScanner.class);
        when(local.scan(any(), any())).thenReturn(
                new SafetyScanResult(SafetyScanResult.Result.ALLOW, List.of()));
        SemanticSafetyProvider provider = mock(SemanticSafetyProvider.class);
        when(provider.inspect(any(), any())).thenReturn(Mono.just(SemanticSafetyResult.allow("TEST")));
        SemanticSafetyProviderRegistry semantic = mock(SemanticSafetyProviderRegistry.class);
        when(semantic.current()).thenReturn(provider);
        policies = mock(PolicySnapshotClient.class);
        ContentSafetyProperties properties = new ContentSafetyProperties();
        properties.setPolicyCacheTtl(Duration.ofSeconds(30));
        checks = mock(ContentSafetyCheckService.class);
        when(checks.record(any(), any(), any(), any(), any(), any(),
                anyLong(), any(), any(), any(), anyLong(), any()))
                .thenReturn(Mono.empty());
        ContentSafetyEventOutboxService outbox = mock(ContentSafetyEventOutboxService.class);
        when(outbox.enqueue(any())).thenReturn(Mono.empty());
        metrics = mock(ContentSafetyMetrics.class);
        orchestrator = new ContentSafetyOrchestrator(
                local, semantic, policies, properties, checks,
                outbox, metrics, mock(FileSafetyScanner.class));
    }

    @Test
    void blocksWhenAnAllowDecisionCannotBeAudited() {
        when(policies.current(any())).thenReturn(Optional.of(policy()));
        when(checks.record(any(), any(), any(), any(), any(), any(),
                anyLong(), any(), any(), any(), anyLong(), any()))
                .thenReturn(Mono.error(new IllegalStateException("audit unavailable")));

        ContentSafetyOrchestrator.Decision decision = orchestrator.inspect(
                new SafetyInput(List.of(new TextSafetyInput.Field("user", "hello")), List.of()),
                apiKey(), "request-audit-failure", "INPUT").block();

        assertThat(decision).isEqualTo(ContentSafetyOrchestrator.Decision.block(
                "content_safety_unavailable", "SAFETY_AUDIT"));
        verify(metrics).persistenceFailed("check");
    }

    @Test
    void blocksWhenNoFreshPolicySnapshotExists() {
        when(policies.current(any())).thenReturn(Optional.empty());

        ContentSafetyOrchestrator.Decision decision = orchestrator.inspect(
                new SafetyInput(List.of(new TextSafetyInput.Field("user", "hello")), List.of()),
                apiKey(), "request-policy-expired", "INPUT").block();

        assertThat(decision).isEqualTo(ContentSafetyOrchestrator.Decision.block(
                "content_safety_unavailable", "SAFETY_POLICY"));
        verify(metrics).policyExpired();
    }

    @Test
    void blocksIncompleteInputInsteadOfScanningOnlyTheVisiblePrefix() {
        when(policies.current(any())).thenReturn(Optional.of(policy()));

        ContentSafetyOrchestrator.Decision decision = orchestrator.inspect(
                new SafetyInput(
                        List.of(new TextSafetyInput.Field("user", "visible prefix")),
                        List.of(),
                        false),
                apiKey(), "request-input-limit", "INPUT").block();

        assertThat(decision).isEqualTo(ContentSafetyOrchestrator.Decision.block(
                "content_safety_unavailable", "SAFETY_INPUT_LIMIT"));
    }

    private PolicySnapshot policy() {
        return PolicySnapshot.signed(1, List.of(), Instant.now(), "test-secret");
    }

    private ApiKeyService.ApiKeyContext apiKey() {
        return new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Set.of("text-basic"));
    }
}
