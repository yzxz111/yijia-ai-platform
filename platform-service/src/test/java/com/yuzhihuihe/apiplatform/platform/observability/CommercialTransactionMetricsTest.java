package com.yuzhihuihe.apiplatform.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;

import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class CommercialTransactionMetricsTest {

    @Test
    void recordsTheStableCommercialMetersWithOnlyBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommercialTransactionMetrics metrics = new CommercialTransactionMetrics(registry);

        metrics.orderCreated("CREATED");
        metrics.paymentResult(
                PaymentProvider.MOCK, PaymentProduct.MOCK, ProviderOutcome.PENDING);
        metrics.callback(PaymentProvider.MOCK, ProviderOutcome.SUCCEEDED);
        metrics.queryDuration(
                PaymentProvider.MOCK, ProviderOutcome.SUCCEEDED,
                Duration.ofMillis(125));
        metrics.close(PaymentProvider.MOCK, ProviderOutcome.CLOSED);
        metrics.refundRequest("PENDING_APPROVAL");
        metrics.refundDecision("APPROVED");
        metrics.refundResult(PaymentProvider.MOCK, ProviderOutcome.SUCCEEDED);
        metrics.refundRevoke("DELIVERED");
        metrics.refundApprovalDuration("APPROVED", Duration.ofMillis(25));
        metrics.refundHoldDuration("READY", Duration.ofMillis(50));
        metrics.refundOutboxDuration("DELIVERED", Duration.ofMillis(75));
        metrics.reconciliationRun(
                PaymentProvider.MOCK, "COMPLETED", Duration.ofMillis(100));
        metrics.reconciliationDifference(
                PaymentProvider.MOCK, "PAYMENT_AMOUNT_MISMATCH", 2);
        metrics.rechargeCreated("CREATED");
        metrics.rechargePaid(PaymentProvider.MOCK);
        metrics.walletDebit("PACKAGE_PURCHASE");
        metrics.insufficientBalance("PACKAGE_PURCHASE");
        metrics.walletRefund("PACKAGE_REFUND");

        assertThat(registry.get("platform.orders.created").counter().count()).isEqualTo(1);
        assertThat(registry.get("platform.payment.result").counter().count()).isEqualTo(1);
        assertThat(registry.get("platform.payment.callback").counter().count()).isEqualTo(1);
        assertThat(registry.get("platform.payment.query.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("platform.payment.close").counter().count()).isEqualTo(1);
        assertThat(registry.get("platform.refund.request").counter().count()).isEqualTo(1);
        assertThat(registry.get("platform.refund.decision").counter().count()).isEqualTo(1);
        assertThat(registry.get("platform.refund.provider.result").counter().count()).isEqualTo(1);
        assertThat(registry.get("platform.refund.revoke").counter().count()).isEqualTo(1);
        assertThat(registry.get("platform.refund.approval.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("platform.refund.hold.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("platform.refund.outbox.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("platform.reconciliation.run.duration").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("platform.reconciliation.difference").counter().count())
                .isEqualTo(2);
        assertThat(registry.get("platform.wallet.recharge.created").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("platform.wallet.recharge.paid").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("platform.wallet.debit").counter().count()).isEqualTo(1);
        assertThat(registry.get("platform.wallet.insufficient.balance").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("platform.wallet.refund").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).extracting(meter -> meter.getId().getName())
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "platform.orders.created",
                        "platform.payment.result",
                        "platform.payment.callback",
                        "platform.payment.query.duration",
                        "platform.payment.close",
                        "platform.refund.request",
                        "platform.refund.decision",
                        "platform.refund.provider.result",
                        "platform.refund.revoke",
                        "platform.refund.approval.duration",
                        "platform.refund.hold.duration",
                        "platform.refund.outbox.duration",
                        "platform.reconciliation.run.duration",
                        "platform.reconciliation.difference",
                        "platform.wallet.recharge.created",
                        "platform.wallet.recharge.paid",
                        "platform.wallet.debit",
                        "platform.wallet.insufficient.balance",
                        "platform.wallet.refund"));
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .allSatisfy(tag -> {
                    assertThat(tag.getKey()).isIn(
                            "status", "provider", "product", "outcome", "differenceType",
                            "entryType");
                    assertThat(tag.getValue()).doesNotContainIgnoringCase(
                            "ord_", "rch_", "ref_", "@", "http://", "https://");
                });
    }

    @Test
    void rejectsUnboundedMetricValuesBeforeRegisteringAMeter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommercialTransactionMetrics metrics = new CommercialTransactionMetrics(registry);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> metrics.orderCreated("ord_123456789"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order metric status");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> metrics.refundRequest("ref_123456789"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refund request outcome");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> metrics.refundDecision("admin@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refund decision outcome");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> metrics.refundRevoke("refund-revoke:ref_123456789"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refund revoke outcome");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> metrics.reconciliationDifference(
                        PaymentProvider.MOCK, "order-123", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reconciliation difference");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> metrics.rechargeCreated("rch_123456789"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recharge creation outcome");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> metrics.walletDebit("user-123456789"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wallet debit entry type");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> metrics.insufficientBalance("ord_123456789"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insufficient balance entry type");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> metrics.walletRefund("ref_123456789"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wallet refund entry type");

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void applicationYamlDeclaresThePaymentLifecycleDefaults() throws Exception {
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))
                .getFirst();

        assertThat(properties.getProperty("payment.order-expiry")).isEqualTo("15m");
        assertThat(properties.getProperty("payment.jobs.batch-size")).isEqualTo(50);
        assertThat(properties.getProperty("payment.jobs.claim-timeout")).isEqualTo("30s");
        assertThat(properties.getProperty("payment.jobs.initial-backoff")).isEqualTo("5s");
        assertThat(properties.getProperty("payment.jobs.max-backoff")).isEqualTo("1h");
        assertThat(properties.getProperty("payment.jobs.max-attempts")).isEqualTo(12);
        assertThat(properties.getProperty("payment.jobs.expired-order-delay-ms")).isEqualTo(5000);
        assertThat(properties.getProperty("payment.jobs.compensation-delay-ms")).isEqualTo(5000);
        assertThat(properties.getProperty("reconciliation.daily-cron"))
                .isEqualTo("0 30 2 * * *");
        assertThat(properties.getProperty("reconciliation.zone")).isEqualTo("Asia/Shanghai");
        assertThat(properties.getProperty("reconciliation.max-bill-bytes")).isEqualTo(52428800);
        assertThat(properties.getProperty("reconciliation.max-records")).isEqualTo(1000000);
    }
}
