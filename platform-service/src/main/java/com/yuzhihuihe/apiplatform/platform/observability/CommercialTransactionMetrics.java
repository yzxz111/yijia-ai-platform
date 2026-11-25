package com.yuzhihuihe.apiplatform.platform.observability;

import java.time.Duration;
import java.util.Set;

import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class CommercialTransactionMetrics {

    private static final Set<String> ORDER_STATUSES = Set.of(
            "CREATED", "REPLAY", "FAILED");
    private static final Set<String> REFUND_APPROVAL_OUTCOMES = Set.of(
            "APPROVED", "REJECTED");
    private static final Set<String> REFUND_REQUEST_OUTCOMES = Set.of(
            "PREPARING", "PENDING_APPROVAL", "REJECTED", "FAILED");
    private static final Set<String> REFUND_DECISION_OUTCOMES = Set.of(
            "APPROVED", "REJECTED");
    private static final Set<String> REFUND_HOLD_OUTCOMES = Set.of(
            "READY", "WAITING", "RELEASED", "REJECTED");
    private static final Set<String> REFUND_REVOKE_OUTCOMES = Set.of(
            "DELIVERED", "RETRY", "FAILED", "REJECTED");
    private static final Set<String> RECONCILIATION_OUTCOMES = Set.of(
            "COMPLETED", "FAILED");
    private static final Set<String> RECONCILIATION_DIFFERENCE_TYPES = Set.of(
            "PROVIDER_SUCCESS_PLATFORM_MISSING",
            "PLATFORM_SUCCESS_PROVIDER_MISSING",
            "PAYMENT_AMOUNT_MISMATCH",
            "PAYMENT_STATUS_MISMATCH",
            "DUPLICATE_PROVIDER_RECEIPT",
            "REFUND_STATUS_MISMATCH",
            "REFUND_AMOUNT_MISMATCH",
            "PLATFORM_REFUND_SUCCESS_PROVIDER_MISSING",
            "LATE_PAYMENT_AFTER_ORDER_CLOSE",
            "UNLINKED_BILL_ROW");
    private static final Set<String> RECHARGE_CREATION_OUTCOMES = Set.of(
            "CREATED", "REPLAY");
    private static final Set<String> WALLET_DEBIT_ENTRY_TYPES = Set.of(
            "PACKAGE_PURCHASE");
    private static final Set<String> WALLET_REFUND_ENTRY_TYPES = Set.of(
            "PACKAGE_REFUND");

    private final MeterRegistry registry;

    public CommercialTransactionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void orderCreated(String status) {
        if (!ORDER_STATUSES.contains(status)) {
            throw new IllegalArgumentException("invalid order metric status");
        }
        counter("platform.orders.created", "status", status).increment();
    }

    public void paymentResult(
            PaymentProvider provider,
            PaymentProduct product,
            ProviderOutcome outcome) {
        require(provider, product, outcome);
        Counter.builder("platform.payment.result")
                .tags(
                        "provider", provider.name(),
                        "product", product.name(),
                        "outcome", outcome.name())
                .register(registry)
                .increment();
    }

    public void callback(PaymentProvider provider, ProviderOutcome outcome) {
        require(provider, outcome);
        counter(
                "platform.payment.callback",
                "provider", provider.name(),
                "outcome", outcome.name()).increment();
    }

    public void queryDuration(
            PaymentProvider provider,
            ProviderOutcome outcome,
            Duration duration) {
        require(provider, outcome);
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("invalid payment query duration");
        }
        Timer.builder("platform.payment.query.duration")
                .tags("provider", provider.name(), "outcome", outcome.name())
                .register(registry)
                .record(duration);
    }

    public void close(PaymentProvider provider, ProviderOutcome outcome) {
        require(provider, outcome);
        counter(
                "platform.payment.close",
                "provider", provider.name(),
                "outcome", outcome.name()).increment();
    }

    public void refundResult(PaymentProvider provider, ProviderOutcome outcome) {
        require(provider, outcome);
        counter(
                "platform.refund.provider.result",
                "provider", provider.name(),
                "outcome", outcome.name()).increment();
    }

    public void refundRequest(String outcome) {
        outcomeCounter(
                "platform.refund.request", REFUND_REQUEST_OUTCOMES, outcome,
                "invalid refund request outcome");
    }

    public void refundDecision(String outcome) {
        outcomeCounter(
                "platform.refund.decision", REFUND_DECISION_OUTCOMES, outcome,
                "invalid refund decision outcome");
    }

    public void refundRevoke(String outcome) {
        outcomeCounter(
                "platform.refund.revoke", REFUND_REVOKE_OUTCOMES, outcome,
                "invalid refund revoke outcome");
    }

    public void refundApprovalDuration(String outcome, Duration duration) {
        duration("platform.refund.approval.duration", REFUND_APPROVAL_OUTCOMES, outcome, duration);
    }

    public void refundHoldDuration(String outcome, Duration duration) {
        duration("platform.refund.hold.duration", REFUND_HOLD_OUTCOMES, outcome, duration);
    }

    public void refundOutboxDuration(String outcome, Duration duration) {
        duration("platform.refund.outbox.duration", REFUND_REVOKE_OUTCOMES, outcome, duration);
    }

    public void reconciliationRun(
            PaymentProvider provider, String outcome, Duration duration) {
        require(provider);
        if (!RECONCILIATION_OUTCOMES.contains(outcome)
                || duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("invalid reconciliation run metric");
        }
        Timer.builder("platform.reconciliation.run.duration")
                .tags("provider", provider.name(), "outcome", outcome)
                .register(registry)
                .record(duration);
    }

    public void reconciliationDifference(
            PaymentProvider provider, String differenceType, long count) {
        require(provider);
        if (!RECONCILIATION_DIFFERENCE_TYPES.contains(differenceType) || count < 1) {
            throw new IllegalArgumentException("invalid reconciliation difference metric");
        }
        Counter.builder("platform.reconciliation.difference")
                .tags("provider", provider.name(), "differenceType", differenceType)
                .register(registry)
                .increment(count);
    }

    public void rechargeCreated(String outcome) {
        outcomeCounterAfterCommit(
                "platform.wallet.recharge.created", RECHARGE_CREATION_OUTCOMES, outcome,
                "invalid recharge creation outcome");
    }

    public void rechargePaid(PaymentProvider provider) {
        require(provider);
        afterCommit(() -> counter(
                "platform.wallet.recharge.paid", "provider", provider.name()).increment());
    }

    public void walletDebit(String entryType) {
        entryTypeCounterAfterCommit(
                "platform.wallet.debit", WALLET_DEBIT_ENTRY_TYPES, entryType,
                "invalid wallet debit entry type");
    }

    public void insufficientBalance(String entryType) {
        entryTypeCounter(
                "platform.wallet.insufficient.balance", WALLET_DEBIT_ENTRY_TYPES, entryType,
                "invalid insufficient balance entry type");
    }

    public void walletRefund(String entryType) {
        entryTypeCounterAfterCommit(
                "platform.wallet.refund", WALLET_REFUND_ENTRY_TYPES, entryType,
                "invalid wallet refund entry type");
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private void outcomeCounter(
            String name, Set<String> allowedOutcomes, String outcome, String error) {
        if (!allowedOutcomes.contains(outcome)) {
            throw new IllegalArgumentException(error);
        }
        counter(name, "outcome", outcome).increment();
    }

    private void outcomeCounterAfterCommit(
            String name, Set<String> allowedOutcomes, String outcome, String error) {
        if (!allowedOutcomes.contains(outcome)) {
            throw new IllegalArgumentException(error);
        }
        afterCommit(() -> counter(name, "outcome", outcome).increment());
    }

    private void entryTypeCounter(
            String name, Set<String> allowedEntryTypes, String entryType, String error) {
        if (!allowedEntryTypes.contains(entryType)) {
            throw new IllegalArgumentException(error);
        }
        counter(name, "entryType", entryType).increment();
    }

    private void entryTypeCounterAfterCommit(
            String name, Set<String> allowedEntryTypes, String entryType, String error) {
        if (!allowedEntryTypes.contains(entryType)) {
            throw new IllegalArgumentException(error);
        }
        afterCommit(() -> counter(name, "entryType", entryType).increment());
    }

    private void afterCommit(Runnable metric) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            metric.run();
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            metric.run();
                        }
                    });
        }
    }

    private void duration(
            String name, Set<String> allowedOutcomes, String outcome, Duration duration) {
        if (!allowedOutcomes.contains(outcome)
                || duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("invalid commercial duration metric");
        }
        Timer.builder(name)
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
    }

    private void require(Object... values) {
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException("commercial metric tag is required");
            }
        }
    }
}
