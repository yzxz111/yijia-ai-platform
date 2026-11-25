package com.yuzhihuihe.apiplatform.platform.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.notification.WalletUsageNotificationService;
import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.reporting.OperatingFactService;
import com.yuzhihuihe.apiplatform.platform.reporting.PaymentFeeService;
import com.yuzhihuihe.apiplatform.platform.wallet.BalancePostingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RechargePaymentPostingService {

    private final RechargePaymentPostingRepository repository;
    private final BalancePostingService balancePostingService;
    private final CommercialNotificationService notifications;
    private final WalletUsageNotificationService walletUsageNotifications;
    private final CommercialTransactionMetrics metrics;
    private final OperatingFactService operatingFacts;
    private final PaymentFeeService paymentFees;

    @Autowired
    RechargePaymentPostingService(
            RechargePaymentPostingRepository repository,
            BalancePostingService balancePostingService,
            ObjectProvider<CommercialNotificationService> notifications,
            ObjectProvider<WalletUsageNotificationService> walletUsageNotifications,
            CommercialTransactionMetrics metrics,
            ObjectProvider<OperatingFactService> operatingFacts,
            ObjectProvider<PaymentFeeService> paymentFees) {
        this(repository, balancePostingService, notifications.getIfAvailable(),
                walletUsageNotifications.getIfAvailable(), metrics,
                operatingFacts.getIfAvailable(), paymentFees.getIfAvailable());
    }

    RechargePaymentPostingService(
            RechargePaymentPostingRepository repository,
            BalancePostingService balancePostingService,
            CommercialNotificationService notifications) {
        this(repository, balancePostingService, notifications,
                null, new CommercialTransactionMetrics(new SimpleMeterRegistry()), null, null);
    }

    RechargePaymentPostingService(
            RechargePaymentPostingRepository repository,
            BalancePostingService balancePostingService,
            CommercialNotificationService notifications,
            CommercialTransactionMetrics metrics) {
        this(repository, balancePostingService, notifications, null, metrics, null, null);
    }

    RechargePaymentPostingService(
            RechargePaymentPostingRepository repository,
            BalancePostingService balancePostingService,
            CommercialNotificationService notifications,
            CommercialTransactionMetrics metrics,
            OperatingFactService operatingFacts) {
        this(repository, balancePostingService, notifications, null, metrics, operatingFacts, null);
    }

    RechargePaymentPostingService(
            RechargePaymentPostingRepository repository,
            BalancePostingService balancePostingService,
            CommercialNotificationService notifications,
            CommercialTransactionMetrics metrics,
            OperatingFactService operatingFacts,
            PaymentFeeService paymentFees) {
        this(repository, balancePostingService, notifications, null, metrics,
                operatingFacts, paymentFees);
    }

    RechargePaymentPostingService(
            RechargePaymentPostingRepository repository,
            BalancePostingService balancePostingService,
            CommercialNotificationService notifications,
            WalletUsageNotificationService walletUsageNotifications,
            CommercialTransactionMetrics metrics,
            OperatingFactService operatingFacts,
            PaymentFeeService paymentFees) {
        this.repository = repository;
        this.balancePostingService = balancePostingService;
        this.notifications = notifications;
        this.walletUsageNotifications = walletUsageNotifications;
        this.metrics = metrics;
        this.operatingFacts = operatingFacts;
        this.paymentFees = paymentFees;
    }

    PaymentPostingService.PostingResult postSucceeded(
            PaymentPostingService.LockedPaymentTransaction transaction,
            PaymentAdapter.ProviderPayment payment) {
        RechargeOrder recharge = lockRecharge(transaction.rechargeOrderId());
        validateRechargeIdentity(recharge, transaction, payment);
        validateRequestPayloadIdentity(recharge, transaction);

        if ("SUCCEEDED".equals(transaction.status())) {
            // Verified callbacks may carry a new notify_time/signature after query-based
            // settlement. Immutable order, amount and external-trade identity are checked above.
            return new PaymentPostingService.PostingResult(
                    recharge.rechargeNo(), recharge.status(), transaction.transactionNo(),
                    transaction.appliedToOrder(), true);
        }

        if ("PAID".equals(recharge.status())) {
            markTransactionSucceeded(transaction.id(), payment, false);
            recordDuplicatePayment(transaction.provider(), recharge, transaction);
            return new PaymentPostingService.PostingResult(
                    recharge.rechargeNo(), recharge.status(), transaction.transactionNo(),
                    false, false);
        }
        if (!"PENDING".equals(recharge.status()) && !"CLOSED".equals(recharge.status())) {
            throw conflict("recharge_order_not_payable");
        }

        boolean latePayment = "CLOSED".equals(recharge.status());
        markTransactionSucceeded(transaction.id(), payment, true);
        int updated = repository.markRechargePaid(
                recharge.id(), recharge.tenantId(), recharge.userId(), payment.paidAt());
        if (updated != 1) {
            throw new IllegalStateException("payment_recharge_state_changed");
        }

        BalancePostingService.PostingResult credit = balancePostingService.post(
                new BalancePostingService.PostingCommand(
                        recharge.tenantId(), recharge.userId(), "RECHARGE",
                        recharge.paidAmount(), recharge.paidAmount(), 0,
                        "RECHARGE_ORDER", recharge.id(), recharge.rechargeNo(),
                        "recharge:" + recharge.rechargeNo()));
        if (notifications != null) {
            notifications.notifyRechargeSucceeded(
                    recharge.rechargeNo(), recharge.tenantId(), recharge.userId(),
                    recharge.paidAmount(), credit.balanceAfter());
        }
        if (walletUsageNotifications != null) {
            walletUsageNotifications.rearmAfterRecharge(
                    recharge.tenantId(), recharge.userId(), credit.balanceAfter());
        }
        if (operatingFacts != null) {
            operatingFacts.recordWalletRecharge(new OperatingFactService.WalletRecharge(
                    recharge.id(), recharge.tenantId(), recharge.userId(), transaction.id(),
                    recharge.paidAmount(), recharge.currency(), payment.paidAt()));
        }
        if (paymentFees != null) {
            paymentFees.recordEstimatedPayment(transaction.id(), "EXTERNAL", payment.paidAt());
        }
        repository.auditPosting(
                recharge.tenantId(), recharge.rechargeNo(), transaction.transactionNo(),
                transaction.provider(), payment.amount(), payment.currency(),
                credit.balanceAfter(), latePayment);
        metrics.rechargePaid(transaction.provider());
        return new PaymentPostingService.PostingResult(
                recharge.rechargeNo(), "PAID", transaction.transactionNo(), true, false);
    }

    private RechargeOrder lockRecharge(UUID rechargeOrderId) {
        return repository.lockRecharge(rechargeOrderId)
                .map(row -> new RechargeOrder(
                        row.id(), row.rechargeNo(), row.tenantId(), row.userId(),
                        row.paidAmount(), row.currency(), row.status()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "payment_recharge_order_not_found"));
    }

    private void validateRechargeIdentity(
            RechargeOrder recharge,
            PaymentPostingService.LockedPaymentTransaction transaction,
            PaymentAdapter.ProviderPayment payment) {
        if (recharge.paidAmount() != transaction.amount()
                || recharge.paidAmount() != payment.amount()) {
            throw conflict("payment_amount_mismatch");
        }
        if (!recharge.currency().equals(payment.currency())) {
            throw conflict("payment_currency_mismatch");
        }
    }

    private void validateRequestPayloadIdentity(
            RechargeOrder recharge,
            PaymentPostingService.LockedPaymentTransaction transaction) {
        if (transaction.requestPayloadHash() == null) {
            throw conflict("payment_request_payload_missing");
        }
        if (transaction.paymentProduct() == null || transaction.expiresAt() == null) {
            throw conflict("payment_request_payload_invalid");
        }
        String canonical = String.join("\n",
                transaction.transactionNo(), recharge.rechargeNo(),
                Long.toString(transaction.amount()), recharge.currency(),
                transaction.provider().name(), transaction.paymentProduct(),
                transaction.expiresAt().toString());
        if (!constantTimeEquals(transaction.requestPayloadHash(), sha256(canonical))) {
            throw conflict("payment_request_payload_mismatch");
        }
    }

    private void markTransactionSucceeded(
            UUID transactionId,
            PaymentAdapter.ProviderPayment payment,
            boolean appliedToOrder) {
        int updated = repository.markTransactionSucceeded(
                transactionId, payment, appliedToOrder);
        if (updated != 1) {
            throw new IllegalStateException("payment_transaction_state_changed");
        }
    }

    private void recordDuplicatePayment(
            PaymentProvider provider,
            RechargeOrder recharge,
            PaymentPostingService.LockedPaymentTransaction transaction) {
        String fingerprint = sha256(
                "DUPLICATE_PAYMENT\n" + provider.name() + "\n"
                        + transaction.transactionNo());
        repository.insertDuplicateAnomaly(
                UUID.randomUUID(), fingerprint, provider, transaction.id(),
                recharge.rechargeNo(), transaction.transactionNo());
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("payment_hash_failed", exception);
        }
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private record RechargeOrder(
            UUID id,
            String rechargeNo,
            UUID tenantId,
            UUID userId,
            long paidAmount,
            String currency,
            String status) {
    }
}
