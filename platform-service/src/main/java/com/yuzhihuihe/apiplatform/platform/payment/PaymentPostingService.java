package com.yuzhihuihe.apiplatform.platform.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentPostingService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final PaymentPostingRepository repository;
    private final PackagePaymentPostingService packagePostingService;
    private final RechargePaymentPostingService rechargePostingService;
    private final Clock clock;
    private final Duration anomalyWindow;

    PaymentPostingService(
            PaymentPostingRepository repository,
            PackagePaymentPostingService packagePostingService,
            RechargePaymentPostingService rechargePostingService,
            Clock clock,
            @Value("${payment.callback.anomaly-window:1h}") Duration anomalyWindow) {
        this.repository = repository;
        this.packagePostingService = packagePostingService;
        this.rechargePostingService = rechargePostingService;
        this.clock = clock;
        if (anomalyWindow == null || anomalyWindow.isNegative()) {
            throw new IllegalArgumentException("payment_callback_anomaly_window_invalid");
        }
        this.anomalyWindow = anomalyWindow;
    }

    @Transactional
    public PostingResult postSucceeded(PaymentAdapter.ProviderPayment payment) {
        return postSucceeded(null, payment);
    }

    @Transactional
    public PostingResult postSucceeded(
            PaymentProvider expectedProvider,
            PaymentAdapter.ProviderPayment payment) {
        validateSucceeded(payment);
        PaymentProvider provider = resolveProvider(payment.transactionNo());
        if (expectedProvider != null && provider != expectedProvider) {
            throw conflict("payment_provider_mismatch");
        }
        lockExternalTradeNo(provider, payment.externalTradeNo());

        LockedPaymentTransaction transaction = lockTransaction(payment.transactionNo());
        if (transaction.provider() != provider
                || (expectedProvider != null && transaction.provider() != expectedProvider)) {
            throw conflict("payment_provider_changed");
        }
        validateTransactionIdentity(transaction, payment);
        requireExternalTradeOwnership(transaction, payment.externalTradeNo());

        boolean packageOwned = transaction.orderId() != null;
        boolean rechargeOwned = transaction.rechargeOrderId() != null;
        if (packageOwned == rechargeOwned) {
            throw conflict("payment_owner_invalid");
        }
        if (packageOwned) {
            return packagePostingService.postSucceeded(transaction, payment);
        }
        return rechargePostingService.postSucceeded(transaction, payment);
    }

    /**
     * 记录一次非法回调异常。
     *
     * <p>指纹刻意 <em>不</em> 包含载荷哈希。控制器
     * 交给本方法的是攻击者可控制的请求体，因此以请求体派生的键会让调用方每改动一个字节
     * 就能插入一条永久记录。这里改用
     * {@code sha256("CALLBACK_SIGNATURE_INVALID" + provider + coarse time bucket)} 作为键：同一
     * 时间窗内来自同一提供方的每一次非法回调都会合并到同一条 OPEN 记录中，其
     * {@code attempt_count} 记录合并了多少次尝试。把
     * {@code payment.callback.anomaly-window} 设为 {@code 0s}，即可在需要更细粒度证据时
     * 重新按载荷哈希作为键（每个不同的请求体一条 open 记录）。
     */
    @Transactional
    public void recordInvalidCallback(PaymentProvider provider, byte[] rawBody) {
        if (provider == null || rawBody == null) {
            throw new IllegalArgumentException("invalid_callback_anomaly");
        }
        String fingerprint = sha256(
                "CALLBACK_SIGNATURE_INVALID\n" + provider.name() + "\n" + deduplicationKey(rawBody));
        repository.insertInvalidCallback(UUID.randomUUID(), fingerprint, provider);
    }

    /**
     * 本次回调落入的时间窗分桶；当时间窗被禁用时，则为载荷哈希。
     */
    private String deduplicationKey(byte[] rawBody) {
        long windowSeconds = anomalyWindow.getSeconds();
        if (windowSeconds <= 0) {
            return sha256(rawBody);
        }
        return Long.toString(clock.instant().getEpochSecond() / windowSeconds);
    }

    private void validateSucceeded(PaymentAdapter.ProviderPayment payment) {
        if (payment == null || payment.outcome() != ProviderOutcome.SUCCEEDED) {
            throw new IllegalArgumentException("payment_result_not_succeeded");
        }
        if (payment.externalTradeNo() == null
                || payment.paidAt() == null
                || payment.payloadHash() == null
                || !SHA256.matcher(payment.payloadHash()).matches()) {
            throw new IllegalArgumentException("invalid_succeeded_payment_result");
        }
    }

    private PaymentProvider resolveProvider(String transactionNo) {
        String value = repository.findProvider(transactionNo)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "payment_transaction_not_found"));
        try {
            return PaymentProvider.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw conflict("payment_provider_invalid");
        }
    }

    private void lockExternalTradeNo(PaymentProvider provider, String externalTradeNo) {
        repository.lockExternalTradeNo(provider, externalTradeNo);
    }

    private LockedPaymentTransaction lockTransaction(String transactionNo) {
        return repository.lockTransaction(transactionNo)
                .map(row -> new LockedPaymentTransaction(
                        row.id(), row.transactionNo(), row.orderId(), row.rechargeOrderId(),
                        row.provider(), row.paymentProduct(), row.amount(), row.status(),
                        row.externalTradeNo(), row.callbackHash(), row.requestPayloadHash(),
                        row.expiresAt(), row.appliedToOrder()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "payment_transaction_not_found"));
    }

    private void validateTransactionIdentity(
            LockedPaymentTransaction transaction,
            PaymentAdapter.ProviderPayment payment) {
        if (transaction.amount() != payment.amount()) {
            throw conflict("payment_amount_mismatch");
        }
        if (transaction.paymentProduct() != null) {
            PaymentProduct product;
            try {
                product = PaymentProduct.valueOf(transaction.paymentProduct());
            } catch (IllegalArgumentException exception) {
                throw conflict("payment_product_invalid");
            }
            if (product.provider() != transaction.provider()) {
                throw conflict("payment_product_provider_mismatch");
            }
        }
        if (transaction.requestPayloadHash() != null
                && !SHA256.matcher(transaction.requestPayloadHash()).matches()) {
            throw conflict("payment_request_payload_invalid");
        }
        if (transaction.externalTradeNo() != null
                && !transaction.externalTradeNo().equals(payment.externalTradeNo())) {
            throw conflict("payment_external_trade_mismatch");
        }
    }

    private void requireExternalTradeOwnership(
            LockedPaymentTransaction transaction,
            String externalTradeNo) {
        boolean ownershipConflict = repository.externalTradeOwnedByAnother(
                transaction.provider(), externalTradeNo, transaction.id());
        if (ownershipConflict) {
            throw conflict("payment_external_trade_conflict");
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("payment_hash_failed", exception);
        }
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    public record PostingResult(
            String orderNo,
            String orderStatus,
            String transactionNo,
            boolean appliedToOrder,
            boolean replay) {
    }

    record LockedPaymentTransaction(
            UUID id,
            String transactionNo,
            UUID orderId,
            UUID rechargeOrderId,
            PaymentProvider provider,
            String paymentProduct,
            long amount,
            String status,
            String externalTradeNo,
            String callbackHash,
            String requestPayloadHash,
            Instant expiresAt,
            boolean appliedToOrder) {
    }
}
