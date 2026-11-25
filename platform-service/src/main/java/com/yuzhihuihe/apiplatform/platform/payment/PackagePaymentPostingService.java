package com.yuzhihuihe.apiplatform.platform.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.order.PackageOrderSettlementService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PackagePaymentPostingService {

    private final PackagePaymentPostingRepository repository;
    private final PackageOrderSettlementService settlementService;

    PackagePaymentPostingService(
            PackagePaymentPostingRepository repository,
            PackageOrderSettlementService settlementService) {
        this.repository = repository;
        this.settlementService = settlementService;
    }

    PaymentPostingService.PostingResult postSucceeded(
            PaymentPostingService.LockedPaymentTransaction transaction,
            PaymentAdapter.ProviderPayment payment) {
        PackageOrderSettlementService.LockedOrder order =
                settlementService.lockOrder(transaction.orderId());
        validateOrderIdentity(order, transaction, payment);
        validateRequestPayloadIdentity(order, transaction);

        if ("SUCCEEDED".equals(transaction.status())) {
            if (!constantTimeEquals(transaction.callbackHash(), payment.payloadHash())) {
                throw conflict("payment_callback_payload_mismatch");
            }
            return new PaymentPostingService.PostingResult(
                    order.orderNo(), order.status(), transaction.transactionNo(),
                    transaction.appliedToOrder(), true);
        }

        boolean alreadyPaid = "PAID".equals(order.status())
                && !transaction.id().equals(order.successfulPaymentTransactionId());
        if (alreadyPaid) {
            markTransactionSucceeded(transaction.id(), payment, false);
            recordDuplicatePayment(transaction.provider(), order, transaction);
            return new PaymentPostingService.PostingResult(
                    order.orderNo(), order.status(), transaction.transactionNo(), false, false);
        }
        if (!"PENDING".equals(order.status())) {
            throw conflict("order_not_payable");
        }

        markTransactionSucceeded(transaction.id(), payment, true);
        settlementService.settle(
                order.id(),
                PackageOrderSettlementService.SettlementCommand.external(
                        payment.paidAt(), transaction.provider(),
                        transaction.id(), transaction.transactionNo()));
        return new PaymentPostingService.PostingResult(
                order.orderNo(), "PAID", transaction.transactionNo(), true, false);
    }

    private void validateOrderIdentity(
            PackageOrderSettlementService.LockedOrder order,
            PaymentPostingService.LockedPaymentTransaction transaction,
            PaymentAdapter.ProviderPayment payment) {
        if (order.amount() != transaction.amount() || order.amount() != payment.amount()) {
            throw conflict("payment_amount_mismatch");
        }
        if (!order.currency().equals(payment.currency())) {
            throw conflict("payment_currency_mismatch");
        }
    }

    private void validateRequestPayloadIdentity(
            PackageOrderSettlementService.LockedOrder order,
            PaymentPostingService.LockedPaymentTransaction transaction) {
        if (transaction.requestPayloadHash() == null) {
            if (order.idempotencyKey() != null) {
                throw conflict("payment_request_payload_missing");
            }
            return;
        }
        if (transaction.paymentProduct() == null || transaction.expiresAt() == null) {
            throw conflict("payment_request_payload_invalid");
        }
        String canonical = String.join("\n",
                transaction.transactionNo(), order.orderNo(),
                Long.toString(transaction.amount()), order.currency(),
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
            PackageOrderSettlementService.LockedOrder order,
            PaymentPostingService.LockedPaymentTransaction transaction) {
        String fingerprint = sha256(
                "DUPLICATE_PAYMENT\n" + provider.name() + "\n"
                        + transaction.transactionNo());
        repository.insertDuplicateAnomaly(
                UUID.randomUUID(), fingerprint, provider, order.id(), transaction.id(),
                order.orderNo(), transaction.transactionNo());
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

}
