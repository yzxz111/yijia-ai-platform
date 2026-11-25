package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PaymentCompensationCheckoutTest {
    private final UUID paymentId = UUID.randomUUID();
    private final UUID rechargeId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-13T10:06:44Z");
    private final PaymentLifecycleRepository repository = mock(PaymentLifecycleRepository.class);
    private final PaymentAvailabilityService availability = mock(PaymentAvailabilityService.class);
    private final PaymentPostingService posting = mock(PaymentPostingService.class);
    private final PaymentAdapter adapter = mock(PaymentAdapter.class);

    private PaymentLifecycleService service(PaymentProduct product, boolean paymentWindowOpen) {
        var row = new PaymentLifecycleRepository.TransactionRow(paymentId, "pay_test", null, rechargeId,
                product.provider().name(), product.name(), UUID.randomUUID(), UUID.randomUUID(),
                100, "CNY", "PENDING", null);
        when(repository.findTransaction("pay_test")).thenReturn(Optional.of(row));
        when(repository.lockTransaction(paymentId)).thenReturn(Optional.of(row));
        when(repository.lockRechargeStatus(rechargeId)).thenReturn(Optional.of("PENDING"));
        when(repository.paymentWindowOpen(paymentId, now)).thenReturn(paymentWindowOpen);
        when(repository.updateQueryState(any(), anyString(), anyString(), any(), any(), any())).thenReturn(1);
        when(availability.requireOperational(product.provider(), product)).thenReturn(adapter);
        when(adapter.queryPayment(any())).thenReturn(new PaymentAdapter.ProviderPayment(
                ProviderOutcome.NOT_FOUND, "pay_test", null, 100, "CNY", null,
                "NOT_FOUND", "ACQ.TRADE_NOT_EXIST", null));
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new PaymentLifecycleService(repository, availability, posting, mock(PaymentAnomalyService.class),
                mock(CommercialTransactionMetrics.class), manager, Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofSeconds(5), (com.yuzhihuihe.apiplatform.platform.order.PackageInventoryService) null, null);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentProduct.class, names = {"ALIPAY_PAGE", "ALIPAY_WAP"})
    void notYetSubmittedFormRemainsPayableAndCanLaterSettle(PaymentProduct product) {
        var service = service(product, true);
        var pending = service.queryForCompensation("pay_test");
        assertThat(pending.paymentStatus()).isEqualTo("PENDING");
        assertThat(pending.retryScheduled()).isTrue();
        verify(adapter, never()).closePayment(any());
        verify(posting, never()).postSucceeded(any());

        var paid = new PaymentAdapter.ProviderPayment(ProviderOutcome.SUCCEEDED, "pay_test", "trade_test",
                100, "CNY", now.plusSeconds(10), "SUCCESS", "10000", "a".repeat(64));
        when(adapter.queryPayment(any())).thenReturn(paid);
        when(posting.postSucceeded(paid)).thenReturn(new PaymentPostingService.PostingResult(
                "rch_test", "PAID", "pay_test", true, false));
        var result = service.queryForCompensation("pay_test");
        assertThat(result.paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.retryScheduled()).isFalse();
        verify(posting).postSucceeded(paid);
    }

    @Test
    void nonexistentTradeAfterCheckoutExpiryCanStopPolling() {
        var result = service(PaymentProduct.ALIPAY_PAGE, false).queryForCompensation("pay_test");
        assertThat(result.paymentStatus()).isEqualTo("CLOSED");
        assertThat(result.retryScheduled()).isFalse();
    }

    @Test
    void confirmedProviderClosureIsTerminalEvenBeforeExpiry() {
        var service = service(PaymentProduct.ALIPAY_PAGE, true);
        when(adapter.queryPayment(any())).thenReturn(new PaymentAdapter.ProviderPayment(
                ProviderOutcome.CLOSED, "pay_test", "trade_test", 100, "CNY", null, "CLOSED", null, null));
        assertThat(service.queryForCompensation("pay_test").paymentStatus()).isEqualTo("CLOSED");
    }
}
