package com.yuzhihuihe.apiplatform.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yuzhihuihe.apiplatform.gateway.billing.ModelBillingPriceService;
import com.yuzhihuihe.apiplatform.gateway.billing.WalletUsageClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class QuotaServiceTest {

    private QuotaReservationRepository repository;
    private ModelBillingPriceService prices;
    private WalletUsageClient wallet;
    private QuotaService service;

    @BeforeEach
    void setUp() {
        repository = mock(QuotaReservationRepository.class);
        prices = mock(ModelBillingPriceService.class);
        wallet = mock(WalletUsageClient.class);
        TransactionalOperator transactions = mock(TransactionalOperator.class);
        doAnswer(invocation -> invocation.getArgument(0))
                .when(transactions).transactional(any(Mono.class));
        service = new QuotaService(
                repository, mock(PackageActivationService.class), transactions, prices, wallet);
    }

    @Test
    void fullyCoveredPackageUsageDoesNotRequirePriceOrWallet() {
        UUID reservationId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        QuotaService.ReserveCommand command = new QuotaService.ReserveCommand(
                reservationId, "request-free", tenantId, userId, apiKeyId, "free-model",
                100, Instant.now().plusSeconds(60), 50, 50);
        QuotaReservationRepository.LockedPackage packageQuota =
                new QuotaReservationRepository.LockedPackage(
                        UUID.randomUUID(), userId, 10_000, 0, 0, Instant.now().plusSeconds(3600),
                        "TOKEN");

        doReturn(Mono.empty()).when(repository).lockExisting(reservationId, command.requestId());
        doReturn(Mono.empty()).when(repository).idempotencyLock(reservationId, command.requestId());
        doReturn(Mono.empty()).when(repository).advisoryLock(userId);
        doReturn(Mono.just(false)).when(repository).hasOpenRefundHold(command);
        doReturn(Mono.just(packageQuota)).when(repository).lockActivePackage(command);
        doReturn(Mono.empty()).when(repository).lockModelQuota(packageQuota.id(), command.modelCode());
        doReturn(Mono.empty()).when(repository).addReservation(
                eq(command), eq(packageQuota), isNull(QuotaReservationRepository.ModelQuota.class),
                eq(100L), eq(0L), isNull(String.class), eq("TOKEN"));

        QuotaService.Reservation reservation = service.reserve(command).block();

        assertThat(reservation).isNotNull();
        assertThat(reservation.packageInstanceId()).isEqualTo(packageQuota.id());
        verify(prices, never()).current(command.modelCode());
        verify(wallet, never()).reserve(any());
    }

    @Test
    void requiredTokenDimensionRejectsSecondsPackageBeforeReservation() {
        UUID reservationId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        QuotaService.ReserveCommand command = new QuotaService.ReserveCommand(
                reservationId, "request-video-package", tenantId, userId, apiKeyId,
                "video-basic", 10, Instant.now().plusSeconds(60), 10, 0, "TOKEN");
        QuotaReservationRepository.LockedPackage packageQuota =
                new QuotaReservationRepository.LockedPackage(
                        UUID.randomUUID(), userId, 10_000, 0, 0, Instant.now().plusSeconds(3600),
                        "VIDEO_SECOND");

        doReturn(Mono.empty()).when(repository).lockExisting(reservationId, command.requestId());
        doReturn(Mono.empty()).when(repository).idempotencyLock(reservationId, command.requestId());
        doReturn(Mono.empty()).when(repository).advisoryLock(userId);
        doReturn(Mono.just(false)).when(repository).hasOpenRefundHold(command);
        doReturn(Mono.just(packageQuota)).when(repository).lockActivePackage(command);
        doReturn(Mono.empty()).when(repository).lockModelQuota(packageQuota.id(), command.modelCode());

        assertThatThrownBy(() -> service.reserve(command).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("usage_dimension_mismatch");
                });
        verify(wallet, never()).reserve(any());
    }

    @ParameterizedTest
    @CsvSource({
            "wallet_insufficient_balance, 422, 422, wallet_insufficient_balance",
            "wallet_not_found, 404, 422, billing_source_required",
            "wallet_usage_idempotency_mismatch, 409, 409, wallet_usage_idempotency_mismatch",
            "billing_reconciliation_required, 409, 409, billing_reconciliation_required",
            "wallet_usage_not_settleable, 409, 409, wallet_usage_state_conflict",
            "invalid_wallet_usage_settlement, 422, 503, wallet_billing_request_invalid",
            "unapproved_internal_failure, 422, 503, wallet_billing_unavailable"
    })
    void mapsWalletFailuresByApprovedCodeInsteadOfHttpStatus(
            String code, int walletStatus, int expectedStatus, String expectedReason) {
        ResponseStatusException mapped = service.mapWalletError(
                new WalletUsageClient.WalletUsageClientException(code, walletStatus));

        assertThat(mapped.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(mapped.getReason()).isEqualTo(expectedReason);
        assertThat(mapped.getStatusCode()).isEqualTo(HttpStatus.valueOf(expectedStatus));
    }

}
