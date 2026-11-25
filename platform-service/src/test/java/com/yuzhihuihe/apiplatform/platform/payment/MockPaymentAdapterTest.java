package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MockPaymentAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void localRefundCreateAndQuerySucceedDeterministically() {
        MockPaymentAdapter adapter = new MockPaymentAdapter(
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), "fixture-mock-secret");
        PaymentAdapter.RefundCommand command = new PaymentAdapter.RefundCommand(
                "pay-local-001", "mock-pay-local-001", "refund-local-001",
                1_900L, 1_900L, "CNY", "zero usage",
                URI.create("https://local.test/refund-callback"));

        PaymentAdapter.ProviderRefund created = adapter.createRefund(command);
        PaymentAdapter.ProviderRefund queried = adapter.queryRefund(
                new PaymentAdapter.RefundQuery(
                        command.transactionNo(), command.externalTradeNo(), command.refundNo(),
                        command.amount(), command.currency()));

        assertThat(created).isEqualTo(new PaymentAdapter.ProviderRefund(
                ProviderOutcome.SUCCEEDED, command.refundNo(),
                "mock-" + command.refundNo(), command.amount(), "SUCCEEDED", null));
        assertThat(queried).isEqualTo(created);
    }

    @Test
    void billEntryCarriesNonNegativeProviderFee() {
        PaymentAdapter.BillEntry entry = new PaymentAdapter.BillEntry(
                "mock-bill-1", BillEntryType.PAYMENT, "pay-local-001", null,
                1_900L, "CNY", 12L, "CNY", ProviderOutcome.SUCCEEDED, NOW);

        assertThat(entry.feeAmount()).isEqualTo(12L);
        assertThat(entry.feeCurrency()).isEqualTo("CNY");
        assertThatThrownBy(() -> new PaymentAdapter.BillEntry(
                "mock-bill-2", BillEntryType.REFUND, null, "refund-local-001",
                1_900L, "CNY", -1L, "CNY", ProviderOutcome.SUCCEEDED, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("feeAmount must be non-negative");
    }
}
