package com.yuzhihuihe.apiplatform.platform.reconciliation;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ReconciliationJobTest {

    @Test
    void runsPreviousProviderLocalDayAndIsolatesProviderFailures() {
        ReconciliationService service = mock(ReconciliationService.class);
        when(service.enabledProviders()).thenReturn(
                List.of(PaymentProvider.WECHAT_PAY, PaymentProvider.ALIPAY));
        LocalDate expectedDate = LocalDate.of(2026, 8, 1);
        doThrow(new IllegalStateException("wechat unavailable"))
                .when(service).start(PaymentProvider.WECHAT_PAY, expectedDate, null);
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-02T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
        ReconciliationJob job = new ReconciliationJob(service, "Asia/Shanghai", clock);

        job.runDaily();

        InOrder order = inOrder(service);
        order.verify(service).start(PaymentProvider.WECHAT_PAY, expectedDate, null);
        order.verify(service).start(PaymentProvider.ALIPAY, expectedDate, null);
    }
}
