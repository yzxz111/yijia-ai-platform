package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PaymentAdapterRegistryTest {

    @Test
    void resolvesOnlyTheExactProviderProductOwner() {
        StubAdapter mock = new StubAdapter(
                PaymentProvider.MOCK, PaymentEnvironment.LOCAL, Set.of(PaymentProduct.MOCK));
        PaymentAdapterRegistry registry = new PaymentAdapterRegistry(List.of(mock));

        assertThat(registry.require(PaymentProvider.MOCK, PaymentProduct.MOCK)).isSameAs(mock);
        assertThatThrownBy(() -> registry.require(
                PaymentProvider.WECHAT_PAY, PaymentProduct.ALIPAY_PAGE))
                .hasMessage("payment_product_provider_mismatch");
        assertThatThrownBy(() -> registry.require(
                PaymentProvider.WECHAT_PAY, PaymentProduct.WECHAT_NATIVE))
                .hasMessage("payment_adapter_not_configured");
    }

    @Test
    void rejectsDuplicateAndMisownedProductRegistrations() {
        StubAdapter first = new StubAdapter(
                PaymentProvider.MOCK, PaymentEnvironment.LOCAL, Set.of(PaymentProduct.MOCK));
        StubAdapter duplicate = new StubAdapter(
                PaymentProvider.MOCK, PaymentEnvironment.LOCAL, Set.of(PaymentProduct.MOCK));
        StubAdapter misowned = new StubAdapter(
                PaymentProvider.WECHAT_PAY, PaymentEnvironment.SANDBOX,
                Set.of(PaymentProduct.ALIPAY_PAGE));

        assertThatThrownBy(() -> new PaymentAdapterRegistry(List.of(first, duplicate)))
                .hasMessage("duplicate_payment_adapter_registration");
        assertThatThrownBy(() -> new PaymentAdapterRegistry(List.of(misowned)))
                .hasMessage("payment_product_provider_mismatch");
    }

    @Test
    void paymentActionsAreHttpsAllowlistedAndImmutable() {
        Map<String, String> source = new java.util.HashMap<>(Map.of(
                "method", "alipay.trade.page.pay", "biz_content", "{}"));
        PaymentAdapter.PaymentAction action = new PaymentAdapter.PaymentAction(
                PaymentActionType.HTML_FORM, URI.create("https://openapi.alipay.com/gateway.do"),
                source, null, Instant.parse("2030-01-01T00:05:00Z"));
        source.put("secret", "must-not-leak");

        assertThat(action.fields()).containsOnlyKeys("method", "biz_content");
        assertThatThrownBy(() -> action.fields().put("sign", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new PaymentAdapter.PaymentAction(
                PaymentActionType.REDIRECT_URL, URI.create("http://pay.example/checkout"),
                Map.of(), null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentAdapter.PaymentAction(
                PaymentActionType.NONE, URI.create("https://pay.example/checkout"),
                Map.of(), null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentAdapter.PaymentAction(
                PaymentActionType.HTML_FORM, URI.create("https://pay.example/checkout"),
                Map.of("credential", "secret"), null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mockAdapterOwnsOnlyLocalMockAndReturnsNoClientActionSecrets() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        MockPaymentAdapter adapter = new MockPaymentAdapter(
                new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC), "local-callback-secret");
        PaymentAdapter.PaymentCommand command = new PaymentAdapter.PaymentCommand(
                "pay_mock_1", "ord_mock_1", 1900, "CNY", PaymentProduct.MOCK,
                URI.create("https://localhost/console/platform/payments/mock/callback"),
                "127.0.0.1", now.plusSeconds(900));

        PaymentAdapter.PaymentCreation created = adapter.createPayment(command);

        assertThat(adapter.provider()).isEqualTo(PaymentProvider.MOCK);
        assertThat(adapter.environment()).isEqualTo(PaymentEnvironment.LOCAL);
        assertThat(adapter.products()).containsExactly(PaymentProduct.MOCK);
        assertThat(adapter.configuration().healthy()).isTrue();
        assertThat(adapter.configuration().credentialFingerprint())
                .doesNotContain("local-callback-secret");
        assertThat(created.outcome()).isEqualTo(ProviderOutcome.PENDING);
        assertThat(created.action().type()).isEqualTo(PaymentActionType.NONE);
        assertThat(created.action().targetUrl()).isNull();
        assertThat(created.action().fields()).isEmpty();
        assertThat(created.action().qrContent()).isNull();
        assertThat(created.action().expiresAt()).isEqualTo(now.plusSeconds(300));
    }

    @Test
    void configuredAdapterRequiresCompleteSanitizedReadinessMetadata() {
        Instant validatedAt = Instant.parse("2030-01-01T00:00:00Z");

        assertThatThrownBy(() -> new PaymentAdapter.AdapterConfiguration(
                true, "fingerprint", null, validatedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("validationStatus is invalid");
        assertThatThrownBy(() -> new PaymentAdapter.AdapterConfiguration(
                true, "fingerprint", "VALID\nsecret", validatedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("validationStatus is not sanitized");
    }

    @Test
    void billDownloadsSortEntriesForBoundedAdjacentGroupProcessing() {
        Instant occurredAt = Instant.parse("2030-01-01T00:00:00Z");
        PaymentAdapter.BillDownload download = new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED,
                LocalDate.of(2030, 1, 1),
                "a".repeat(64),
                List.of(
                        new PaymentAdapter.BillEntry(
                                "refund-z", BillEntryType.REFUND, null, "ref_z",
                                400, "CNY", ProviderOutcome.SUCCEEDED, occurredAt),
                        new PaymentAdapter.BillEntry(
                                "payment-z-2", BillEntryType.PAYMENT, "pay_z", null,
                                300, "CNY", ProviderOutcome.SUCCEEDED, occurredAt),
                        new PaymentAdapter.BillEntry(
                                "payment-a", BillEntryType.PAYMENT, "pay_a", null,
                                100, "CNY", ProviderOutcome.SUCCEEDED, occurredAt),
                        new PaymentAdapter.BillEntry(
                                "payment-z-1", BillEntryType.PAYMENT, "pay_z", null,
                                200, "CNY", ProviderOutcome.SUCCEEDED, occurredAt)));

        assertThat(download.entries())
                .extracting(entry -> entry.entryType() + ":"
                        + (entry.transactionNo() == null
                        ? entry.refundNo() : entry.transactionNo())
                        + ":" + entry.providerRecordId())
                .containsExactly(
                        "PAYMENT:pay_a:payment-a",
                        "PAYMENT:pay_z:payment-z-1",
                        "PAYMENT:pay_z:payment-z-2",
                        "REFUND:ref_z:refund-z");
    }

    private static final class StubAdapter implements PaymentAdapter {
        private final PaymentProvider provider;
        private final PaymentEnvironment environment;
        private final Set<PaymentProduct> products;

        private StubAdapter(
                PaymentProvider provider,
                PaymentEnvironment environment,
                Set<PaymentProduct> products) {
            this.provider = provider;
            this.environment = environment;
            this.products = products;
        }

        @Override
        public PaymentProvider provider() {
            return provider;
        }

        @Override
        public PaymentEnvironment environment() {
            return environment;
        }

        @Override
        public Set<PaymentProduct> products() {
            return products;
        }

        @Override
        public AdapterConfiguration configuration() {
            return new AdapterConfiguration(
                    true, "fingerprint", "VALID", Instant.parse("2030-01-01T00:00:00Z"));
        }

        @Override
        public PaymentCreation createPayment(PaymentCommand command) {
            return null;
        }

        @Override
        public ProviderPayment verifyAndParseCallback(CallbackEnvelope callback) {
            return null;
        }

        @Override
        public ProviderPayment queryPayment(PaymentQuery query) {
            return null;
        }

        @Override
        public ProviderPayment closePayment(PaymentQuery query) {
            return null;
        }

        @Override
        public ProviderRefund createRefund(RefundCommand command) {
            return null;
        }

        @Override
        public ProviderRefund queryRefund(RefundQuery query) {
            return null;
        }

        @Override
        public BillDownload downloadBill(LocalDate billDate) {
            return null;
        }
    }
}
