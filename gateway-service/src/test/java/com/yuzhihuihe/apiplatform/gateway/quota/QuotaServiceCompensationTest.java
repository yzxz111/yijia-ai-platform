package com.yuzhihuihe.apiplatform.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuzhihuihe.apiplatform.gateway.billing.ModelBillingPriceCalculator;
import com.yuzhihuihe.apiplatform.gateway.billing.ModelBillingPriceService;
import com.yuzhihuihe.apiplatform.gateway.billing.WalletUsageClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

class QuotaServiceCompensationTest {

    @Test
    void releasesRemoteWalletReservationWhenLocalPersistenceFails() {
        QuotaReservationRepository repository = mock(QuotaReservationRepository.class);
        ModelBillingPriceService prices = mock(ModelBillingPriceService.class);
        WalletUsageClient wallet = mock(WalletUsageClient.class);
        TransactionalOperator transactions = mock(TransactionalOperator.class);
        doAnswer(invocation -> invocation.getArgument(0))
                .when(transactions).transactional(any(Mono.class));
        QuotaService service = new QuotaService(
                repository, mock(PackageActivationService.class), transactions, prices, wallet);

        UUID reservationId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(60);
        QuotaService.ReserveCommand command = new QuotaService.ReserveCommand(
                reservationId, "request-wallet", tenantId, userId, UUID.randomUUID(),
                "paid-model", 100, expiresAt, 60, 40);

        doReturn(Mono.empty()).when(repository).lockExisting(reservationId, command.requestId());
        doReturn(Mono.empty()).when(repository).idempotencyLock(reservationId, command.requestId());
        doReturn(Mono.empty()).when(repository).advisoryLock(userId);
        doReturn(Mono.just(false)).when(repository).hasOpenRefundHold(command);
        doReturn(Mono.empty()).when(repository).lockActivePackage(command);
        when(prices.current(command.modelCode())).thenReturn(Mono.just(
                new ModelBillingPriceCalculator.Price(
                        command.modelCode(), 10_000, 20_000, "price-v1")));
        when(wallet.reserve(any())).thenAnswer(invocation -> {
            WalletUsageClient.WalletRequest request = invocation.getArgument(0);
            assertThat(request.expiresAt()).isEqualTo(expiresAt);
            return Mono.just(new WalletUsageClient.WalletResponse(
                    reservationId, command.requestId(), request.estimatedAmount(), null,
                    "RESERVED", command.modelCode(), request.priceVersion()));
        });
        doReturn(Mono.error(new IllegalStateException("reservation_insert_failed")))
                .when(repository).addWalletReservation(command, 2L, "price-v1");
        when(wallet.release(reservationId)).thenReturn(Mono.just(
                new WalletUsageClient.WalletResponse(
                        reservationId, command.requestId(), 2L, null,
                        "RELEASED", command.modelCode(), "price-v1")));

        assertThatThrownBy(() -> service.reserve(command).block())
                .hasMessage("reservation_insert_failed");

        verify(wallet).release(reservationId);
    }
}
