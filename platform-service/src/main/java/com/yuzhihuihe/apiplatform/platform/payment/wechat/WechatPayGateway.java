package com.yuzhihuihe.apiplatform.platform.payment.wechat;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import com.yuzhihuihe.apiplatform.platform.payment.BillEntryType;

public interface WechatPayGateway {

    Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    NativePrepay prepayNative(PrepayCommand request);

    H5Prepay prepayH5(PrepayCommand request);

    PaymentResult queryPayment(String merchantOrderNo);

    PaymentResult closePayment(String merchantOrderNo);

    RefundResult createRefund(RefundRequest request);

    RefundResult queryRefund(String merchantRefundNo);

    BillResult downloadBill(LocalDate billDate);

    record PrepayCommand(
            String appId,
            String merchantId,
            String merchantOrderNo,
            long amount,
            String currency,
            String description,
            URI notifyUrl,
            String clientIp,
            Instant expiresAt) {
    }

    record NativePrepay(String providerStatus, String codeUrl) {
    }

    record H5Prepay(String providerStatus, URI h5Url) {
    }

    record PaymentResult(
            PaymentStatus status,
            String appId,
            String merchantId,
            String merchantOrderNo,
            String transactionId,
            long amount,
            String currency,
            Instant paidAt,
            String providerCode) {
    }

    record RefundRequest(
            String merchantOrderNo,
            String transactionId,
            String merchantRefundNo,
            long totalAmount,
            long amount,
            String currency,
            String reason,
            URI notifyUrl) {
    }

    record RefundResult(
            RefundStatus status,
            String merchantRefundNo,
            String refundId,
            long amount,
            String providerCode) {
    }

    record BillResult(LocalDate billDate, String sha256, List<BillLine> entries) {
        public BillResult {
            entries = List.copyOf(entries);
        }
    }

    record BillLine(
            String providerRecordId,
            BillEntryType entryType,
            String merchantOrderNo,
            String merchantRefundNo,
            long amount,
            String currency,
            long feeAmount,
            String feeCurrency,
            boolean feeKnown,
            PaymentStatus status,
            Instant occurredAt) {
        BillLine(String providerRecordId, BillEntryType entryType, String merchantOrderNo,
                String merchantRefundNo, long amount, String currency,
                PaymentStatus status, Instant occurredAt) {
            this(providerRecordId, entryType, merchantOrderNo, merchantRefundNo, amount,
                    currency, 0, currency, false, status, occurredAt);
        }
        BillLine(String providerRecordId, BillEntryType entryType, String merchantOrderNo,
                String merchantRefundNo, long amount, String currency, long feeAmount,
                String feeCurrency, PaymentStatus status, Instant occurredAt) {
            this(providerRecordId, entryType, merchantOrderNo, merchantRefundNo, amount,
                    currency, feeAmount, feeCurrency, true, status, occurredAt);
        }
    }

    enum PaymentStatus {
        SUCCESS,
        REFUND,
        NOTPAY,
        CLOSED,
        REVOKED,
        USERPAYING,
        PAYERROR,
        ACCEPT,
        NOT_FOUND,
        UNKNOWN
    }

    enum RefundStatus {
        SUCCESS,
        CLOSED,
        PROCESSING,
        ABNORMAL,
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
            super("wechat_pay_gateway_" + kind.name().toLowerCase(java.util.Locale.ROOT));
            this.kind = java.util.Objects.requireNonNull(kind, "kind");
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
