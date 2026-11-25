package com.yuzhihuihe.apiplatform.platform.payment.alipay;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import com.yuzhihuihe.apiplatform.platform.payment.BillEntryType;

public interface AlipayGateway {

    Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    String createPagePayment(PaymentRequest request);

    String createWapPayment(PaymentRequest request);

    PaymentResult queryPayment(String merchantOrderNo);

    PaymentResult closePayment(String merchantOrderNo);

    RefundResult createRefund(RefundRequest request);

    RefundResult queryRefund(RefundQueryRequest request);

    BillResult downloadBill(LocalDate billDate);

    record PaymentRequest(
            String appId,
            String sellerId,
            String merchantOrderNo,
            String totalAmount,
            String currency,
            String subject,
            URI notifyUrl,
            URI returnUrl,
            Instant expiresAt) {
    }

    record PaymentResult(
            PaymentStatus status,
            String appId,
            String sellerId,
            String merchantOrderNo,
            String tradeNo,
            String totalAmount,
            String currency,
            Instant paidAt,
            String providerCode) {
    }

    record RefundRequest(
            String appId,
            String sellerId,
            String merchantOrderNo,
            String tradeNo,
            String merchantRefundNo,
            String totalAmount,
            String refundAmount,
            String currency,
            String reason,
            URI notifyUrl) {
    }

    record RefundQueryRequest(
            String appId,
            String sellerId,
            String merchantOrderNo,
            String tradeNo,
            String merchantRefundNo,
            String refundAmount,
            String currency) {
    }

    record RefundResult(
            RefundStatus status,
            String appId,
            String sellerId,
            String merchantOrderNo,
            String merchantRefundNo,
            String tradeNo,
            String refundAmount,
            String currency,
            String providerCode) {
    }

    record BillResult(
            LocalDate billDate,
            String appId,
            String sellerId,
            String sha256,
            List<BillLine> entries) {
        public BillResult {
            entries = List.copyOf(entries);
        }
    }

    record BillLine(
            String providerRecordId,
            BillEntryType entryType,
            String merchantOrderNo,
            String merchantRefundNo,
            String amount,
            String currency,
            String feeAmount,
            String feeCurrency,
            boolean feeKnown,
            PaymentStatus status,
            Instant occurredAt) {
        BillLine(String providerRecordId, BillEntryType entryType, String merchantOrderNo,
                String merchantRefundNo, String amount, String currency,
                PaymentStatus status, Instant occurredAt) {
            this(providerRecordId, entryType, merchantOrderNo, merchantRefundNo, amount,
                    currency, "0", currency, false, status, occurredAt);
        }
        BillLine(String providerRecordId, BillEntryType entryType, String merchantOrderNo,
                String merchantRefundNo, String amount, String currency, String feeAmount,
                String feeCurrency, PaymentStatus status, Instant occurredAt) {
            this(providerRecordId, entryType, merchantOrderNo, merchantRefundNo, amount,
                    currency, feeAmount, feeCurrency, true, status, occurredAt);
        }
    }

    enum PaymentStatus {
        SUCCESS,
        WAIT_BUYER_PAY,
        CLOSED,
        FAILED,
        NOT_FOUND,
        UNKNOWN
    }

    enum RefundStatus {
        SUCCESS,
        PROCESSING,
        CLOSED,
        FAILED,
        NOT_FOUND,
        UNKNOWN
    }

    enum FailureKind {
        TIMEOUT,
        REJECTED,
        NOT_FOUND,
        INTERNAL
    }

    final class GatewayException extends RuntimeException {
        private final FailureKind kind;
        private final String providerCode;

        public GatewayException(FailureKind kind, String providerCode) {
            super("alipay_gateway_" + Objects.requireNonNull(kind, "kind")
                    .name().toLowerCase(Locale.ROOT));
            this.kind = kind;
            this.providerCode = sanitize(providerCode);
        }

        public FailureKind kind() {
            return kind;
        }

        public String providerCode() {
            return providerCode;
        }

        private static String sanitize(String code) {
            return code != null && SAFE_CODE.matcher(code).matches() ? code : "UNKNOWN";
        }
    }
}
