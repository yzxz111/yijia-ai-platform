package com.yuzhihuihe.apiplatform.platform.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapterRegistry;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentEnvironment;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class RefundCallbackControllerTest {

    @Test
    void verifiedCallbackQueriesProviderAndAcknowledgesTerminalResult() {
        PaymentAdapter adapter = adapter();
        when(adapter.verifyAndParseRefundCallback(any()))
                .thenReturn(new PaymentAdapter.VerifiedRefundCallback("ref_callback_1"));
        RefundExecutionService execution = mock(RefundExecutionService.class);
        when(execution.query("ref_callback_1"))
                .thenReturn(new RefundExecutionService.ExecutionResult("REVOKING", false));
        RefundCallbackController controller = controller(adapter, execution);

        var response = controller.wechatPayCallback(
                "signed body".getBytes(StandardCharsets.UTF_8), HttpHeaders.EMPTY);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("code", "SUCCESS");
        verify(execution).query("ref_callback_1");
    }

    @Test
    void invalidOrUnsettledCallbacksAreNotAcknowledged() {
        PaymentAdapter adapter = adapter();
        when(adapter.verifyAndParseRefundCallback(any()))
                .thenThrow(new IllegalArgumentException("invalid callback"))
                .thenReturn(new PaymentAdapter.VerifiedRefundCallback("ref_callback_2"));
        RefundExecutionService execution = mock(RefundExecutionService.class);
        when(execution.query("ref_callback_2"))
                .thenReturn(new RefundExecutionService.ExecutionResult("PROCESSING", true));
        RefundCallbackController controller = controller(adapter, execution);

        assertThat(controller.wechatPayCallback(new byte[0], HttpHeaders.EMPTY)
                .getStatusCode().value()).isEqualTo(401);
        assertThat(controller.wechatPayCallback(new byte[0], HttpHeaders.EMPTY)
                .getStatusCode().value()).isEqualTo(500);
    }

    private RefundCallbackController controller(
            PaymentAdapter adapter, RefundExecutionService execution) {
        return new RefundCallbackController(
                new PaymentAdapterRegistry(List.of(adapter)), execution);
    }

    private PaymentAdapter adapter() {
        PaymentAdapter adapter = mock(PaymentAdapter.class);
        when(adapter.provider()).thenReturn(PaymentProvider.WECHAT_PAY);
        when(adapter.environment()).thenReturn(PaymentEnvironment.PRODUCTION);
        when(adapter.products()).thenReturn(Set.of(PaymentProduct.WECHAT_NATIVE));
        return adapter;
    }
}
