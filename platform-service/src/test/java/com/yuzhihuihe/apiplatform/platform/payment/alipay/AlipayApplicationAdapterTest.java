package com.yuzhihuihe.apiplatform.platform.payment.alipay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.yuzhihuihe.apiplatform.platform.payment.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class AlipayApplicationAdapterTest {
    private static final String DESKTOP = "2021000000000001";
    private static final String MOBILE = "2021000000000002";
    private final PaymentAdapter desktop = mock(PaymentAdapter.class);
    private final PaymentAdapter mobile = mock(PaymentAdapter.class);
    private final AlipayApplicationAdapter.ApplicationBindings bindings =
            mock(AlipayApplicationAdapter.ApplicationBindings.class);
    private AlipayApplicationAdapter adapter;

    @BeforeEach
    void setUp() {
        when(desktop.configuration())
                .thenReturn(
                        new PaymentAdapter.AdapterConfiguration(
                                true, "desktop", "VALID", Instant.now()));
        when(mobile.configuration())
                .thenReturn(
                        new PaymentAdapter.AdapterConfiguration(
                                true, "mobile", "VALID", Instant.now()));
        adapter = configured(true);
    }

    private AlipayApplicationAdapter configured(boolean enabled) {
        return new AlipayApplicationAdapter(
                DESKTOP, desktop, MOBILE, mobile, bindings, enabled, Clock.systemUTC(), () -> {});
    }

    @Test
    void bindsApplicationBeforeCreatingEachProduct() {
        for (PaymentProduct product :
                List.of(PaymentProduct.ALIPAY_PAGE, PaymentProduct.ALIPAY_WAP)) {
            String id = product == PaymentProduct.ALIPAY_PAGE ? DESKTOP : MOBILE;
            PaymentAdapter delegate = product == PaymentProduct.ALIPAY_PAGE ? desktop : mobile;
            when(bindings.bind("pay_1", id)).thenReturn(id);
            PaymentAdapter.PaymentCommand command = command(product);
            adapter.createPayment(command);
            var ordered = inOrder(bindings, delegate);
            ordered.verify(bindings).bind("pay_1", id);
            ordered.verify(delegate).createPayment(command);
        }
    }

    @Test
    void cannotReplaceExistingTransactionApplication() {
        when(bindings.bind("pay_1", MOBILE)).thenReturn(DESKTOP);
        assertThatThrownBy(() -> adapter.createPayment(command(PaymentProduct.ALIPAY_WAP)))
                .hasMessage("alipay_payment_application_mismatch");
        verify(mobile, never()).createPayment(any());
    }

    @Test
    void disabledMobileCheckoutRetainsItsOperationsAndDesktopCheckout() {
        adapter = configured(false);
        assertThat(adapter.supportsCheckout(PaymentProduct.ALIPAY_PAGE)).isTrue();
        assertThat(adapter.supportsCheckout(PaymentProduct.ALIPAY_WAP)).isFalse();
        assertThatThrownBy(() -> adapter.createPayment(command(PaymentProduct.ALIPAY_WAP)))
                .hasMessage("payment_checkout_unavailable");
        verify(bindings, never()).bind(anyString(), anyString());
        when(bindings.find("pay_1")).thenReturn(MOBILE);
        PaymentAdapter.PaymentQuery query = new PaymentAdapter.PaymentQuery("pay_1", 100, "CNY");
        adapter.queryPayment(query);
        adapter.closePayment(query);
        verify(mobile).queryPayment(query);
        verify(mobile).closePayment(query);
    }

    @Test
    void legacyPaymentsAndRefundsStayOnDesktop() {
        when(bindings.find("pay_1")).thenReturn(DESKTOP);
        PaymentAdapter.PaymentQuery query = new PaymentAdapter.PaymentQuery("pay_1", 100, "CNY");
        var refund =
                new PaymentAdapter.RefundCommand(
                        "pay_1",
                        "trade1",
                        "refund1",
                        100,
                        50,
                        "CNY",
                        "refund",
                        URI.create("https://pay.example.com/callback"));
        var refundQuery = new PaymentAdapter.RefundQuery("pay_1", "trade1", "refund1", 50, "CNY");
        adapter.queryPayment(query);
        adapter.createRefund(refund);
        adapter.queryRefund(refundQuery);
        verify(desktop).queryPayment(query);
        verify(desktop).createRefund(refund);
        verify(desktop).queryRefund(refundQuery);
        verify(mobile, never()).queryPayment(any());
    }

    @Test
    void commonCallbackAcceptsVerifiedMobileAppOnlyForItsOwnTransaction() {
        var envelope = new PaymentAdapter.CallbackEnvelope(new byte[0], Map.of());
        var payment =
                new PaymentAdapter.ProviderPayment(
                        ProviderOutcome.SUCCEEDED,
                        "pay_1",
                        "trade1",
                        100,
                        "CNY",
                        Instant.now(),
                        "TRADE_SUCCESS",
                        null,
                        "a".repeat(64));
        when(desktop.verifyAndParseCallback(envelope))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        when(mobile.verifyAndParseCallback(envelope)).thenReturn(payment);
        when(bindings.find("pay_1")).thenReturn(MOBILE);
        assertThat(adapter.verifyAndParseCallback(envelope)).isSameAs(payment);
        when(bindings.find("pay_1")).thenReturn(DESKTOP);
        assertThatThrownBy(() -> adapter.verifyAndParseCallback(envelope))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void unknownBoundAppCannotFallBackToAnotherApplication() {
        when(bindings.find("pay_1")).thenReturn("2021000000000003");
        assertThatThrownBy(
                        () ->
                                adapter.queryPayment(
                                        new PaymentAdapter.PaymentQuery("pay_1", 100, "CNY")))
                .hasMessage("alipay_payment_application_not_configured");
        verify(desktop, never()).queryPayment(any());
        verify(mobile, never()).queryPayment(any());
    }

    @Test
    void dailyBillsMergeAndDeduplicateWithoutCountingTwice() {
        LocalDate day = LocalDate.of(2026, 9, 20);
        var entry =
                new PaymentAdapter.BillEntry(
                        "trade1",
                        BillEntryType.PAYMENT,
                        "pay_1",
                        null,
                        100,
                        "CNY",
                        ProviderOutcome.SUCCEEDED,
                        Instant.now());
        when(desktop.downloadBill(day))
                .thenReturn(
                        new PaymentAdapter.BillDownload(
                                ProviderOutcome.SUCCEEDED, day, "a".repeat(64), List.of(entry)));
        when(mobile.downloadBill(day))
                .thenReturn(
                        new PaymentAdapter.BillDownload(
                                ProviderOutcome.SUCCEEDED, day, "b".repeat(64), List.of(entry)));
        assertThat(adapter.downloadBill(day).entries()).containsExactly(entry);
        when(mobile.downloadBill(day))
                .thenReturn(
                        new PaymentAdapter.BillDownload(
                                ProviderOutcome.UNCERTAIN, day, null, List.of()));
        assertThat(adapter.downloadBill(day).outcome()).isEqualTo(ProviderOutcome.UNCERTAIN);
        assertThat(adapter.downloadBill(day).entries()).isEmpty();
    }

    @Test
    void unusedDisabledMobileAppDoesNotBlockExistingReconciliation() {
        adapter = configured(false);
        LocalDate day = LocalDate.of(2026, 9, 20);
        var bill =
                new PaymentAdapter.BillDownload(
                        ProviderOutcome.SUCCEEDED, day, "a".repeat(64), List.of());
        when(desktop.downloadBill(day)).thenReturn(bill);
        assertThat(adapter.downloadBill(day)).isSameAs(bill);
        verify(mobile, never()).downloadBill(any());
    }

    private PaymentAdapter.PaymentCommand command(PaymentProduct product) {
        return new PaymentAdapter.PaymentCommand(
                "pay_1",
                "order1",
                100,
                "CNY",
                product,
                URI.create("https://pay.example.com/callback"),
                "127.0.0.1",
                Instant.now().plusSeconds(300));
    }
}
