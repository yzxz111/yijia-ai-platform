package com.yuzhihuihe.apiplatform.platform.wallet;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentActionType;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAvailabilityService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.sms.SmsApiException;
import com.yuzhihuihe.apiplatform.platform.sms.SmsProofService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.Map;

class RechargePaymentProofTest {
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-13T08:00:00Z");
    private final RechargePaymentAttemptRepository repository = mock(RechargePaymentAttemptRepository.class);
    private final PaymentAvailabilityService availability = mock(PaymentAvailabilityService.class);
    private final PaymentAdapter adapter = mock(PaymentAdapter.class);
    private final SmsProofService proofs = mock(SmsProofService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    @BeforeEach
    void preparePayableOrder() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.lockRecharge(tenantId, userId, "rch_test"))
                .thenReturn(new RechargePaymentAttemptRepository.RechargeRow(
                        UUID.randomUUID(), "rch_test", 100, "CNY", "PENDING", now.plusSeconds(900)));
        when(repository.phoneVerified(tenantId, userId)).thenReturn(true);
        when(availability.requireCheckout(PaymentProvider.ALIPAY, PaymentProduct.ALIPAY_PAGE)).thenReturn(adapter);
    }

    @Test
    void missingOrExpiredProofIsClientErrorAndNeverReachesProvider() {
        when(proofs.consume(null, tenantId, userId, SmsPurpose.PAYMENT_CONFIRM))
                .thenThrow(new IllegalArgumentException("invalid_sms_proof"));
        assertRejected(service(proofs));
        verify(transactions).rollback(any());
    }

    @Test
    void unavailableProofServiceCannotBypassConfirmation() {
        assertRejected(service(null));
    }

    @Test
    void verifiedPaymentReachesProviderWithExactOrderAmount() {
        when(repository.updateTransaction(anyString(), anyString(), any(), anyString())).thenReturn(1);
        when(adapter.createPayment(any())).thenReturn(new PaymentAdapter.PaymentCreation(
                ProviderOutcome.PENDING, null, "WAIT_BUYER_PAY", new PaymentAdapter.PaymentAction(
                        PaymentActionType.NONE, null, Map.of(), null, now.plusSeconds(300))));
        var result = service(proofs).create(tenantId, userId, "rch_test",
                new RechargePaymentAttemptService.CreatePaymentAttemptRequest(
                        PaymentProvider.ALIPAY, PaymentProduct.ALIPAY_PAGE, "valid-proof"), "127.0.0.1");
        assertThat(result.status()).isEqualTo("PENDING");
        verify(proofs).consume("valid-proof", tenantId, userId, SmsPurpose.PAYMENT_CONFIRM);
        var command = org.mockito.ArgumentCaptor.forClass(PaymentAdapter.PaymentCommand.class);
        verify(adapter).createPayment(command.capture());
        assertThat(command.getValue().amount()).isEqualTo(100);
        assertThat(command.getValue().orderNo()).isEqualTo("rch_test");
    }

    @Test
    void unexpectedProofStorageFailureRemainsServerFailure() {
        when(proofs.consume(null, tenantId, userId, SmsPurpose.PAYMENT_CONFIRM))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> create(service(proofs))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(adapter);
    }

    private void assertRejected(RechargePaymentAttemptService service) {
        assertThatThrownBy(() -> create(service)).isInstanceOfSatisfying(SmsApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(error.code()).isEqualTo("invalid_sms_proof");
        });
        verifyNoInteractions(adapter);
        verify(repository, never()).insertTransaction(any(), anyString(), any(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    private void create(RechargePaymentAttemptService service) {
        service.create(tenantId, userId, "rch_test", new RechargePaymentAttemptService.CreatePaymentAttemptRequest(
                PaymentProvider.ALIPAY, PaymentProduct.ALIPAY_PAGE, null), "127.0.0.1");
    }

    private RechargePaymentAttemptService service(SmsProofService proofService) {
        return new RechargePaymentAttemptService(repository, availability, mock(CommercialTransactionMetrics.class),
                transactions, Clock.fixed(now, ZoneOffset.UTC), proofService);
    }
}
