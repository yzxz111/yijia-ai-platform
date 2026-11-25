package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class PaymentCallbackControllerTest {

    @Test
    void verifiedAndRejectedCallbacksEmitBoundedOutcomeMetrics() {
        PaymentAdapter adapter = mock(PaymentAdapter.class);
        when(adapter.provider()).thenReturn(PaymentProvider.WECHAT_PAY);
        when(adapter.environment()).thenReturn(PaymentEnvironment.SANDBOX);
        when(adapter.products()).thenReturn(Set.of(PaymentProduct.WECHAT_NATIVE));
        PaymentAdapter.ProviderPayment succeeded = new PaymentAdapter.ProviderPayment(
                ProviderOutcome.SUCCEEDED,
                "pay_callback_metric",
                "trade_callback_metric",
                1900,
                "CNY",
                Instant.parse("2030-03-04T05:06:07Z"),
                "SUCCESS",
                null,
                "a".repeat(64));
        when(adapter.verifyAndParseCallback(any()))
                .thenReturn(succeeded)
                .thenThrow(new IllegalArgumentException("callback body secret"));
        PaymentPostingService postingService = mock(PaymentPostingService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentCallbackController controller = new PaymentCallbackController(
                new PaymentAdapterRegistry(List.of(adapter)),
                postingService,
                new CommercialTransactionMetrics(registry));
        byte[] callbackBody = "private callback body".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(controller.wechatPayCallback(callbackBody, HttpHeaders.EMPTY).getStatusCode().value())
                .isEqualTo(200);
        assertThat(controller.wechatPayCallback(callbackBody, HttpHeaders.EMPTY).getStatusCode().value())
                .isEqualTo(401);

        verify(postingService).postSucceeded(PaymentProvider.WECHAT_PAY, succeeded);
        verify(postingService).recordInvalidCallback(PaymentProvider.WECHAT_PAY, callbackBody);
        assertThat(registry.get("platform.payment.callback")
                .tags("provider", "WECHAT_PAY", "outcome", "SUCCEEDED")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("platform.payment.callback")
                .tags("provider", "WECHAT_PAY", "outcome", "FAILED")
                .counter().count()).isEqualTo(1);
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getValue())
                        .doesNotContainIgnoringCase("callback", "secret", "private", "http"));
    }
}
