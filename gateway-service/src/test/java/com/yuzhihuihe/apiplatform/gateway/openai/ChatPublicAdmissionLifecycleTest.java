package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

class ChatPublicAdmissionLifecycleTest {

    private static final UUID KEY_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000101");
    private static final UUID USER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000102");
    private static final UUID LEASE_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000103");
    private static final UUID RESERVATION_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000104");
    private static final String MODEL = "multimodal-basic";

    private final ApiKeyService.ApiKeyContext apiKey = new ApiKeyService.ApiKeyContext(
            KEY_ID, UUID.randomUUID(), USER_ID, UUID.randomUUID(), Set.of(MODEL));

    private RequestLimitService limits;
    private QuotaService quota;
    private ChatPublicAdmissionLifecycle lifecycle;
    private RequestLimitService.LimitLease lease;

    @BeforeEach
    void setUp() {
        limits = mock(RequestLimitService.class);
        quota = mock(QuotaService.class);
        lifecycle = new ChatPublicAdmissionLifecycle(
                limits, quota, new ChatCompletionRequestValidator());
        lease = new RequestLimitService.LimitLease(
                LEASE_ID, KEY_ID, USER_ID, MODEL,
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                100, 10, RequestLimitService.Store.REDIS);
        when(limits.release(any())).thenReturn(Mono.empty());
    }

    @Test
    void validatesAndAcquiresOnlyThePublicLimitPathWithStableCommand() {
        RequestLimitService.Thresholds rpm = RequestLimitService.Thresholds.all(100);
        RequestLimitService.Thresholds concurrency = RequestLimitService.Thresholds.all(10);
        when(limits.acquirePublic(any())).thenReturn(Mono.just(lease));

        lifecycle.validateForAdmission(apiKey, MODEL);
        ChatPublicAdmissionLifecycle.AdmissionState state = lifecycle.acquire(
                apiKey, MODEL, rpm, concurrency).block(Duration.ofSeconds(1));

        ArgumentCaptor<RequestLimitService.LimitCommand> command =
                ArgumentCaptor.forClass(RequestLimitService.LimitCommand.class);
        verify(limits).acquirePublic(command.capture());
        assertThat(command.getValue()).isEqualTo(new RequestLimitService.LimitCommand(
                KEY_ID, USER_ID, MODEL,
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                rpm, concurrency, Duration.ofMinutes(2)));
        assertThat(state.lease()).isSameAs(lease);
        assertThat(state.claimed()).isFalse();
    }

    @Test
    void claimsOnceBeforeValidationAndExecution() {
        ChatPublicAdmissionLifecycle.AdmissionState state = state();
        AtomicInteger validations = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();

        String result = lifecycle.withClaimedAdmission(
                        state,
                        () -> {
                            validations.incrementAndGet();
                            return "validated";
                        },
                        validated -> {
                            executions.incrementAndGet();
                            return Mono.just(validated);
                        })
                .block(Duration.ofSeconds(1));

        assertThat(result).isEqualTo("validated");
        assertThat(state.claimed()).isTrue();
        assertThatThrownBy(() -> lifecycle.withClaimedAdmission(
                        state,
                        () -> {
                            validations.incrementAndGet();
                            return "duplicate";
                        },
                        validated -> {
                            executions.incrementAndGet();
                            return Mono.just(validated);
                        })
                .block(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("public_admission_required");
        assertThat(validations).hasValue(1);
        assertThat(executions).hasValue(1);
    }

    @Test
    void validationFailureReleasesBeforePropagatingTheOriginalError() {
        ChatPublicAdmissionLifecycle.AdmissionState state = state();
        IllegalStateException failure = new IllegalStateException("invalid routed request");
        List<String> calls = new ArrayList<>();
        when(limits.release(lease)).thenReturn(Mono.defer(() -> {
            calls.add("release");
            return Mono.empty();
        }));

        Mono<String> result = lifecycle.<String, String>withClaimedAdmission(
                state,
                () -> {
                    calls.add("validate");
                    throw failure;
                },
                validated -> {
                    calls.add("execute");
                    return Mono.just(validated);
                });

        assertThatThrownBy(() -> result.block(Duration.ofSeconds(1))).isSameAs(failure);
        assertThat(calls).containsExactly("validate", "release");
    }

    @Test
    void validatesTheAdmissionIdentityWithoutChangingTheErrorEnvelope() {
        ChatPublicAdmissionLifecycle.AdmissionState state = state();

        lifecycle.validateIdentity(apiKey, MODEL, MODEL, state);

        assertThatThrownBy(() -> lifecycle.validateIdentity(
                apiKey, "different-model", MODEL, state))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.getReason()).isEqualTo("model_not_allowed");
                });
        assertThatThrownBy(() -> lifecycle.validateIdentity(
                apiKey, MODEL, "different-model", state))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason()).isEqualTo("invalid_multimodal_request");
                });
    }

    @Test
    void failedReleaseCanBeRetriedUntilOneReleaseSucceeds() {
        ChatPublicAdmissionLifecycle.AdmissionState state = state();
        AtomicInteger attempts = new AtomicInteger();
        when(limits.release(lease)).thenAnswer(ignored -> attempts.getAndIncrement() == 0
                ? Mono.error(new IllegalStateException("limit store unavailable"))
                : Mono.empty());

        assertThatThrownBy(() -> lifecycle.release(state).block(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("limit store unavailable");
        lifecycle.release(state).block(Duration.ofSeconds(1));
        lifecycle.release(state).block(Duration.ofSeconds(1));

        verify(limits, times(2)).release(lease);
    }

    @Test
    void cancelledReleaseReturnsToAvailableForRetry() {
        ChatPublicAdmissionLifecycle.AdmissionState state = state();
        when(limits.release(lease)).thenReturn(Mono.never(), Mono.empty());

        Disposable first = lifecycle.release(state).subscribe();
        first.dispose();
        lifecycle.release(state).block(Duration.ofSeconds(1));
        lifecycle.release(state).block(Duration.ofSeconds(1));

        verify(limits, times(2)).release(lease);
    }

    @Test
    void concurrentSuccessfulReleaseRemainsIdempotent() {
        ChatPublicAdmissionLifecycle.AdmissionState state = state();

        Mono.when(lifecycle.release(state), lifecycle.release(state))
                .block(Duration.ofSeconds(1));
        lifecycle.release(state).block(Duration.ofSeconds(1));

        verify(limits).release(lease);
    }

    @Test
    void cancellationReleasesReservationBeforeThePublicLease() {
        ChatPublicAdmissionLifecycle.AdmissionState state = state();
        List<String> calls = new ArrayList<>();
        lifecycle.associateReservation(state, RESERVATION_ID);
        when(quota.release(RESERVATION_ID, QuotaService.ReleaseReason.CLIENT_CANCELLED))
                .thenReturn(Mono.defer(() -> {
                    calls.add("reservation");
                    return Mono.empty();
                }));
        when(limits.release(lease)).thenReturn(Mono.defer(() -> {
            calls.add("lease");
            return Mono.empty();
        }));

        Disposable execution = lifecycle.withClaimedAdmission(
                        state, () -> "validated", ignored -> Mono.never())
                .subscribe();
        execution.dispose();

        verify(limits, org.mockito.Mockito.timeout(1_000)).release(lease);
        assertThat(calls).containsExactly("reservation", "lease");
        verify(quota).release(RESERVATION_ID, QuotaService.ReleaseReason.CLIENT_CANCELLED);
    }

    @Test
    void reservationCleanupFailureDoesNotPreventCancellationLeaseRelease() {
        ChatPublicAdmissionLifecycle.AdmissionState state = state();
        lifecycle.associateReservation(state, RESERVATION_ID);
        when(quota.release(RESERVATION_ID, QuotaService.ReleaseReason.CLIENT_CANCELLED))
                .thenReturn(Mono.error(new IllegalStateException("quota unavailable")));

        Disposable execution = lifecycle.withClaimedAdmission(
                        state, () -> "validated", ignored -> Mono.never())
                .subscribe();
        execution.dispose();

        verify(limits, org.mockito.Mockito.timeout(1_000)).release(lease);
    }

    @Test
    void releaseLeaseUsesTheAdmissionLeaseWhenPresentAndTheGivenLeaseOtherwise() {
        RequestLimitService.LimitLease direct = new RequestLimitService.LimitLease(
                UUID.randomUUID(), KEY_ID, USER_ID, MODEL, UUID.randomUUID(),
                100, 10, RequestLimitService.Store.DATABASE);
        ChatPublicAdmissionLifecycle.AdmissionState state = state();

        lifecycle.releaseLease(direct, state).block(Duration.ofSeconds(1));
        lifecycle.releaseLease(direct, null).block(Duration.ofSeconds(1));

        verify(limits).release(lease);
        verify(limits).release(direct);
    }

    @Test
    void publicModelIdentityIncludesIdAliasAndOutputLimit() {
        UUID modelId = UUID.randomUUID();

        assertThat(lifecycle.samePublicModel(
                modelId, MODEL, 8_192, modelId, MODEL, 8_192)).isTrue();
        assertThat(lifecycle.samePublicModel(
                UUID.randomUUID(), MODEL, 8_192, modelId, MODEL, 8_192)).isFalse();
        assertThat(lifecycle.samePublicModel(
                modelId, "other", 8_192, modelId, MODEL, 8_192)).isFalse();
        assertThat(lifecycle.samePublicModel(
                modelId, MODEL, 4_096, modelId, MODEL, 8_192)).isFalse();
    }

    private ChatPublicAdmissionLifecycle.AdmissionState state() {
        return new ChatPublicAdmissionLifecycle.AdmissionState(lease);
    }
}
