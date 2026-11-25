package com.yuzhihuihe.apiplatform.platform.payment;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public interface PaymentAdapter {

    int MAX_CALLBACK_BYTES = 1024 * 1024;
    int MAX_BILL_ENTRIES = 1_000_000;
    Pattern SAFE_PROVIDER_VALUE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    Set<String> HTML_FORM_FIELDS = Set.of(
            "app_id", "method", "format", "charset", "sign_type", "sign",
            "timestamp", "version", "notify_url", "return_url", "biz_content",
            "alipay_sdk");

    PaymentProvider provider();

    PaymentEnvironment environment();

    Set<PaymentProduct> products();

    default boolean supportsCheckout(PaymentProduct product) {
        return products().contains(product);
    }

    AdapterConfiguration configuration();

    PaymentCreation createPayment(PaymentCommand command);

    ProviderPayment verifyAndParseCallback(CallbackEnvelope callback);

    default VerifiedRefundCallback verifyAndParseRefundCallback(CallbackEnvelope callback) {
        Objects.requireNonNull(callback, "callback");
        throw new UnsupportedOperationException("refund callback is not supported");
    }

    ProviderPayment queryPayment(PaymentQuery query);

    ProviderPayment closePayment(PaymentQuery query);

    ProviderRefund createRefund(RefundCommand command);

    ProviderRefund queryRefund(RefundQuery query);

    BillDownload downloadBill(LocalDate billDate);

    record PaymentCommand(
            String transactionNo,
            String orderNo,
            long amount,
            String currency,
            PaymentProduct product,
            URI notifyUrl,
            String clientIp,
            Instant expiresAt) {
        public PaymentCommand {
            transactionNo = identifier(transactionNo, 64, "transactionNo");
            orderNo = identifier(orderNo, 64, "orderNo");
            positive(amount, "amount");
            currency = cny(currency);
            Objects.requireNonNull(product, "product");
            notifyUrl = https(notifyUrl, "notifyUrl");
            clientIp = identifier(clientIp, 64, "clientIp");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    record PaymentCreation(
            ProviderOutcome outcome,
            String externalTradeNo,
            String providerStatus,
            PaymentAction action) {
        public PaymentCreation {
            Objects.requireNonNull(outcome, "outcome");
            externalTradeNo = optionalIdentifier(externalTradeNo, 128, "externalTradeNo");
            providerStatus = providerValue(providerStatus, "providerStatus");
            Objects.requireNonNull(action, "action");
        }
    }

    record PaymentQuery(String transactionNo, long amount, String currency) {
        public PaymentQuery {
            transactionNo = identifier(transactionNo, 64, "transactionNo");
            positive(amount, "amount");
            currency = cny(currency);
        }
    }

    record PaymentAction(
            PaymentActionType type,
            URI targetUrl,
            Map<String, String> fields,
            String qrContent,
            Instant expiresAt) {
        public PaymentAction {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(expiresAt, "expiresAt");
            fields = immutableFields(fields);
            switch (type) {
                case NONE -> {
                    require(targetUrl == null && fields.isEmpty() && qrContent == null,
                            "NONE action cannot contain client data");
                }
                case QR_CODE -> {
                    require(targetUrl == null && fields.isEmpty(),
                            "QR_CODE action accepts only qrContent");
                    qrContent = identifier(qrContent, 4096, "qrContent");
                }
                case REDIRECT_URL -> {
                    targetUrl = https(targetUrl, "targetUrl");
                    require(fields.isEmpty() && qrContent == null,
                            "REDIRECT_URL action accepts only targetUrl");
                }
                case HTML_FORM -> {
                    targetUrl = https(targetUrl, "targetUrl");
                    require(!fields.isEmpty() && qrContent == null,
                            "HTML_FORM action requires fields and no qrContent");
                }
                default -> throw new IllegalArgumentException("unsupported payment action");
            }
        }
    }

    record CallbackEnvelope(byte[] rawBody, Map<String, List<String>> headers) {
        public CallbackEnvelope {
            Objects.requireNonNull(rawBody, "rawBody");
            require(rawBody.length <= MAX_CALLBACK_BYTES, "callback body is too large");
            rawBody = rawBody.clone();
            headers = immutableHeaders(headers);
        }

        @Override
        public byte[] rawBody() {
            return rawBody.clone();
        }
    }

    record VerifiedRefundCallback(String refundNo) {
        public VerifiedRefundCallback {
            refundNo = identifier(refundNo, 64, "refundNo");
        }
    }

    record ProviderPayment(
            ProviderOutcome outcome,
            String transactionNo,
            String externalTradeNo,
            long amount,
            String currency,
            Instant paidAt,
            String providerStatus,
            String providerErrorCode,
            String payloadHash) {
        public ProviderPayment {
            Objects.requireNonNull(outcome, "outcome");
            transactionNo = identifier(transactionNo, 64, "transactionNo");
            externalTradeNo = optionalIdentifier(externalTradeNo, 128, "externalTradeNo");
            positive(amount, "amount");
            currency = cny(currency);
            providerStatus = providerValue(providerStatus, "providerStatus");
            providerErrorCode = providerValue(providerErrorCode, "providerErrorCode");
            payloadHash = sha256(payloadHash, "payloadHash");
            if (outcome == ProviderOutcome.SUCCEEDED) {
                identifier(externalTradeNo, 128, "externalTradeNo");
                Objects.requireNonNull(paidAt, "paidAt");
            }
        }
    }

    record RefundCommand(
            String transactionNo,
            String externalTradeNo,
            String refundNo,
            long totalAmount,
            long amount,
            String currency,
            String reason,
            URI notifyUrl) {
        public RefundCommand {
            transactionNo = identifier(transactionNo, 64, "transactionNo");
            externalTradeNo = identifier(externalTradeNo, 128, "externalTradeNo");
            refundNo = identifier(refundNo, 64, "refundNo");
            positive(totalAmount, "totalAmount");
            positive(amount, "amount");
            require(amount <= totalAmount, "amount cannot exceed totalAmount");
            currency = cny(currency);
            reason = identifier(reason, 500, "reason");
            notifyUrl = https(notifyUrl, "notifyUrl");
        }
    }

    record RefundQuery(
            String transactionNo,
            String externalTradeNo,
            String refundNo,
            long amount,
            String currency) {
        public RefundQuery {
            transactionNo = identifier(transactionNo, 64, "transactionNo");
            externalTradeNo = identifier(externalTradeNo, 128, "externalTradeNo");
            refundNo = identifier(refundNo, 64, "refundNo");
            positive(amount, "amount");
            currency = cny(currency);
        }
    }

    record ProviderRefund(
            ProviderOutcome outcome,
            String refundNo,
            String externalRefundNo,
            long amount,
            String providerStatus,
            String providerErrorCode) {
        public ProviderRefund {
            Objects.requireNonNull(outcome, "outcome");
            refundNo = identifier(refundNo, 64, "refundNo");
            externalRefundNo = optionalIdentifier(externalRefundNo, 128, "externalRefundNo");
            positive(amount, "amount");
            providerStatus = providerValue(providerStatus, "providerStatus");
            providerErrorCode = providerValue(providerErrorCode, "providerErrorCode");
        }
    }

    record BillDownload(
            ProviderOutcome outcome,
            LocalDate billDate,
            String sha256,
            List<BillEntry> entries) {
        public BillDownload {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(billDate, "billDate");
            sha256 = PaymentAdapter.sha256(sha256, "sha256");
            entries = Objects.requireNonNull(entries, "entries").stream()
                    .sorted(Comparator
                            .comparing((BillEntry entry) -> entry.entryType().name())
                            .thenComparing(PaymentAdapter::billBusinessNo)
                            .thenComparing(BillEntry::providerRecordId))
                    .toList();
            require(entries.size() <= MAX_BILL_ENTRIES, "bill entry limit exceeded");
        }
    }

    record BillEntry(
            String providerRecordId,
            BillEntryType entryType,
            String transactionNo,
            String refundNo,
            long amount,
            String currency,
            long feeAmount,
            String feeCurrency,
            boolean feeKnown,
            ProviderOutcome outcome,
            Instant occurredAt) {
        public BillEntry(
                String providerRecordId,
                BillEntryType entryType,
                String transactionNo,
                String refundNo,
                long amount,
                String currency,
                ProviderOutcome outcome,
                Instant occurredAt) {
            this(providerRecordId, entryType, transactionNo, refundNo, amount, currency,
                    0, currency, false, outcome, occurredAt);
        }

        public BillEntry(
                String providerRecordId, BillEntryType entryType, String transactionNo,
                String refundNo, long amount, String currency, long feeAmount,
                String feeCurrency, ProviderOutcome outcome, Instant occurredAt) {
            this(providerRecordId, entryType, transactionNo, refundNo, amount, currency,
                    feeAmount, feeCurrency, true, outcome, occurredAt);
        }

        public BillEntry {
            providerRecordId = identifier(providerRecordId, 128, "providerRecordId");
            Objects.requireNonNull(entryType, "entryType");
            transactionNo = optionalIdentifier(transactionNo, 64, "transactionNo");
            refundNo = optionalIdentifier(refundNo, 64, "refundNo");
            positive(amount, "amount");
            currency = cny(currency);
            nonNegative(feeAmount, "feeAmount");
            feeCurrency = cny(feeCurrency);
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(occurredAt, "occurredAt");
            require(entryType == BillEntryType.PAYMENT ? transactionNo != null : refundNo != null,
                    "bill entry business number is required");
        }
    }

    record AdapterConfiguration(
            boolean configured,
            String credentialFingerprint,
            String validationStatus,
            Instant validatedAt) {
        public AdapterConfiguration {
            credentialFingerprint = optionalIdentifier(
                    credentialFingerprint, 128, "credentialFingerprint");
            validationStatus = providerValue(validationStatus, "validationStatus");
            if (configured) {
                identifier(credentialFingerprint, 128, "credentialFingerprint");
                providerValue(
                        identifier(validationStatus, 128, "validationStatus"),
                        "validationStatus");
                Objects.requireNonNull(validatedAt, "validatedAt");
            }
        }

        public boolean healthy() {
            return configured && "VALID".equals(validationStatus);
        }
    }

    private static String identifier(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String optionalIdentifier(String value, int maxLength, String name) {
        return value == null ? null : identifier(value, maxLength, name);
    }

    private static String billBusinessNo(BillEntry entry) {
        return entry.entryType() == BillEntryType.PAYMENT
                ? entry.transactionNo() : entry.refundNo();
    }

    private static void positive(long value, String name) {
        require(value > 0, name + " must be positive");
    }

    private static void nonNegative(long value, String name) {
        require(value >= 0, name + " must be non-negative");
    }

    private static String cny(String value) {
        require(value != null && "CNY".equals(value.toUpperCase(Locale.ROOT)),
                "currency must be CNY");
        return "CNY";
    }

    private static URI https(URI value, String name) {
        require(value != null && "https".equalsIgnoreCase(value.getScheme())
                && value.getHost() != null, name + " must be an HTTPS URI");
        return value;
    }

    private static String providerValue(String value, String name) {
        if (value != null && !SAFE_PROVIDER_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not sanitized");
        }
        return value;
    }

    private static String sha256(String value, String name) {
        if (value != null && !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
        return value;
    }

    private static Map<String, String> immutableFields(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        Objects.requireNonNull(source, "fields").forEach((key, value) -> {
            require(HTML_FORM_FIELDS.contains(key), "payment form field is not allowlisted");
            result.put(key, identifier(value, 4096, "form field value"));
        });
        return Map.copyOf(result);
    }

    private static Map<String, List<String>> immutableHeaders(
            Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Objects.requireNonNull(source, "headers").forEach((name, values) -> {
            String safeName = identifier(name, 128, "header name");
            List<String> safeValues = new ArrayList<>();
            Objects.requireNonNull(values, "header values").forEach(
                    value -> safeValues.add(identifier(value, 4096, "header value")));
            result.put(safeName, List.copyOf(safeValues));
        });
        require(result.size() <= 200, "too many callback headers");
        return Map.copyOf(result);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
