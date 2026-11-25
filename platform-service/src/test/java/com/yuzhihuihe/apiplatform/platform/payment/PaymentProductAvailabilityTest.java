package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;

class PaymentProductAvailabilityTest {
    @Test
    void mobileCheckoutIsRejectedBeforeAnAttemptButItsExistingOrdersRemainOperational() {
        PaymentAdapter adapter = mock(PaymentAdapter.class);
        when(adapter.provider()).thenReturn(PaymentProvider.ALIPAY);
        when(adapter.environment()).thenReturn(PaymentEnvironment.PRODUCTION);
        when(adapter.products())
                .thenReturn(Set.of(PaymentProduct.ALIPAY_PAGE, PaymentProduct.ALIPAY_WAP));
        when(adapter.supportsCheckout(PaymentProduct.ALIPAY_PAGE)).thenReturn(true);
        when(adapter.supportsCheckout(PaymentProduct.ALIPAY_WAP)).thenReturn(false);
        when(adapter.configuration())
                .thenReturn(
                        new PaymentAdapter.AdapterConfiguration(
                                true, "valid", "VALID", Instant.now()));
        PaymentAvailabilityRepository repository = mock(PaymentAvailabilityRepository.class);
        when(repository.isEnabled(PaymentProvider.ALIPAY, PaymentEnvironment.PRODUCTION))
                .thenReturn(true);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var service =
                new PaymentAvailabilityService(
                        repository, new PaymentAdapterRegistry(List.of(adapter)), transactions);
        assertThat(service.requireCheckout(PaymentProvider.ALIPAY, PaymentProduct.ALIPAY_PAGE))
                .isSameAs(adapter);
        assertThatThrownBy(
                        () ->
                                service.requireCheckout(
                                        PaymentProvider.ALIPAY, PaymentProduct.ALIPAY_WAP))
                .hasMessageContaining("payment_checkout_unavailable");
        assertThat(service.requireOperational(PaymentProvider.ALIPAY, PaymentProduct.ALIPAY_WAP))
                .isSameAs(adapter);
    }
}
