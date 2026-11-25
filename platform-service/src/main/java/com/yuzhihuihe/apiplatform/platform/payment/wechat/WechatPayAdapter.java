package com.yuzhihuihe.apiplatform.platform.payment.wechat;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentActionType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentEnvironment;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class WechatPayAdapter implements PaymentAdapter {

    private static final long CALLBACK_WINDOW_SECONDS = 300;
    private static final int MAX_EXPECTED_PAYMENTS = 10_000;
    private static final Set<PaymentProduct> PRODUCTS = Set.of(
            PaymentProduct.WECHAT_NATIVE, PaymentProduct.WECHAT_H5);

    private final WechatPayGateway gateway;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String appId;
    private final String merchantId;
    private final byte[] apiV3Key;
    private final PublicKey platformPublicKey;
    private final String platformPublicKeyId;
    private final URI notifyUrl;
    private final AdapterConfiguration configuration;
    private final Map<String, ExpectedPayment> expectedPayments = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ExpectedPayment> eldest) {
                    return size() > MAX_EXPECTED_PAYMENTS;
                }
            });

    public WechatPayAdapter(
            WechatPayGateway gateway,
            ObjectMapper objectMapper,
            Clock clock,
            String appId,
            String merchantId,
            byte[] apiV3Key,
            PublicKey platformPublicKey,
            String platformPublicKeyId,
            URI notifyUrl,
            String credentialFingerprint) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.appId = required(appId);
        this.merchantId = required(merchantId);
        this.apiV3Key = Objects.requireNonNull(apiV3Key, "apiV3Key").clone();
        if (this.apiV3Key.length != 32) {
            throw new IllegalArgumentException("apiV3Key must contain 32 bytes");
        }
        this.platformPublicKey = Objects.requireNonNull(platformPublicKey, "platformPublicKey");
        this.platformPublicKeyId = required(platformPublicKeyId);
        this.notifyUrl = Objects.requireNonNull(notifyUrl, "notifyUrl");
        this.configuration = new AdapterConfiguration(
                true, required(credentialFingerprint), "VALID", clock.instant());
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.WECHAT_PAY;
    }

    @Override
    public PaymentEnvironment environment() {
        return PaymentEnvironment.PRODUCTION;
    }

    @Override
    public Set<PaymentProduct> products() {
        return PRODUCTS;
    }

    @Override
    public AdapterConfiguration configuration() {
        return configuration;
    }

    @Override
    public PaymentCreation createPayment(PaymentCommand command) {
        Objects.requireNonNull(command, "command");
        if (!PRODUCTS.contains(command.product())) {
            throw new IllegalArgumentException("payment_product_provider_mismatch");
        }
        WechatPayGateway.PrepayCommand request = new WechatPayGateway.PrepayCommand(
                appId, merchantId, command.transactionNo(), command.amount(), command.currency(),
                command.orderNo(), notifyUrl, command.clientIp(), command.expiresAt());
        expectedPayments.put(command.transactionNo(),
                new ExpectedPayment(command.amount(), command.currency()));
        if (command.product() == PaymentProduct.WECHAT_NATIVE) {
            WechatPayGateway.NativePrepay response;
            try {
                response = gateway.prepayNative(request);
            } catch (WechatPayGateway.GatewayException exception) {
                return failedCreation(command.expiresAt(), exception);
            } catch (RuntimeException exception) {
                return uncertainCreation(command.expiresAt());
            }
            if (response == null) {
                throw invalidResponse();
            }
            return new PaymentCreation(
                    ProviderOutcome.PENDING, null, safeCode(response.providerStatus()),
                    new PaymentAction(PaymentActionType.QR_CODE, null, Map.of(),
                            required(response.codeUrl()), command.expiresAt()));
        }
        WechatPayGateway.H5Prepay response;
        try {
            response = gateway.prepayH5(request);
        } catch (WechatPayGateway.GatewayException exception) {
            return failedCreation(command.expiresAt(), exception);
        } catch (RuntimeException exception) {
            return uncertainCreation(command.expiresAt());
        }
        if (response == null) {
            throw invalidResponse();
        }
        return new PaymentCreation(
                ProviderOutcome.PENDING, null, safeCode(response.providerStatus()),
                new PaymentAction(PaymentActionType.REDIRECT_URL,
                        Objects.requireNonNull(response.h5Url(), "h5Url"), Map.of(), null,
                        command.expiresAt()));
    }

    @Override
    public ProviderPayment verifyAndParseCallback(CallbackEnvelope callback) {
        try {
            VerifiedResource verified = verifyAndDecrypt(callback);
            WechatTransaction transaction = objectMapper.readValue(
                    verified.plaintext(), WechatTransaction.class);
            return toProviderPayment(transaction, sha256(verified.rawBody()));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCallback();
        }
    }

    @Override
    public VerifiedRefundCallback verifyAndParseRefundCallback(CallbackEnvelope callback) {
        try {
            WechatRefundNotification refund = objectMapper.readValue(
                    verifyAndDecrypt(callback).plaintext(), WechatRefundNotification.class);
            if (!merchantId.equals(refund.merchantId())) {
                throw invalidCallback();
            }
            return new VerifiedRefundCallback(refund.merchantRefundNo());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCallback();
        }
    }

    @Override
    public ProviderPayment queryPayment(PaymentQuery query) {
        Objects.requireNonNull(query, "query");
        String transactionNo = query.transactionNo();
        WechatPayGateway.PaymentResult result;
        try {
            result = gateway.queryPayment(transactionNo);
        } catch (WechatPayGateway.GatewayException exception) {
            return failedPayment(transactionNo, exception);
        } catch (RuntimeException exception) {
            return unresolvedPayment(
                    ProviderOutcome.UNCERTAIN, transactionNo, "UNKNOWN", "UNKNOWN");
        }
        if (result == null) {
            throw invalidResponse();
        }
        return toProviderPayment(result, query);
    }

    @Override
    public ProviderPayment closePayment(PaymentQuery query) {
        Objects.requireNonNull(query, "query");
        String transactionNo = query.transactionNo();
        WechatPayGateway.PaymentResult result;
        try {
            result = gateway.closePayment(transactionNo);
        } catch (WechatPayGateway.GatewayException exception) {
            return failedPayment(transactionNo, exception);
        } catch (RuntimeException exception) {
            return unresolvedPayment(
                    ProviderOutcome.UNCERTAIN, transactionNo, "UNKNOWN", "UNKNOWN");
        }
        if (result == null) {
            throw invalidResponse();
        }
        return toProviderPayment(result, query);
    }

    @Override
    public ProviderRefund createRefund(RefundCommand command) {
        Objects.requireNonNull(command, "command");
        WechatPayGateway.RefundResult result;
        try {
            result = gateway.createRefund(new WechatPayGateway.RefundRequest(
                    command.transactionNo(), command.externalTradeNo(), command.refundNo(),
                    command.totalAmount(), command.amount(), command.currency(), command.reason(),
                    command.notifyUrl()));
        } catch (WechatPayGateway.GatewayException exception) {
            return failedRefund(command.refundNo(), command.amount(), exception);
        } catch (RuntimeException exception) {
            return new ProviderRefund(
                    ProviderOutcome.UNCERTAIN, command.refundNo(), null, command.amount(),
                    "UNKNOWN", "UNKNOWN");
        }
        if (result == null) {
            throw invalidResponse();
        }
        return toProviderRefund(result, command.refundNo(), command.amount());
    }

    @Override
    public ProviderRefund queryRefund(RefundQuery query) {
        Objects.requireNonNull(query, "query");
        WechatPayGateway.RefundResult result;
        try {
            result = gateway.queryRefund(query.refundNo());
        } catch (WechatPayGateway.GatewayException exception) {
            return failedRefund(query.refundNo(), query.amount(), exception);
        } catch (RuntimeException exception) {
            return new ProviderRefund(
                    ProviderOutcome.UNCERTAIN, query.refundNo(), null, query.amount(),
                    "UNKNOWN", "UNKNOWN");
        }
        if (result == null) {
            throw invalidResponse();
        }
        return toProviderRefund(result, query.refundNo(), query.amount());
    }

    @Override
    public BillDownload downloadBill(LocalDate billDate) {
        Objects.requireNonNull(billDate, "billDate");
        WechatPayGateway.BillResult result;
        try {
            result = gateway.downloadBill(billDate);
        } catch (WechatPayGateway.GatewayException exception) {
            return new BillDownload(
                    outcome(exception), billDate, null, List.of());
        } catch (RuntimeException exception) {
            return new BillDownload(ProviderOutcome.UNCERTAIN, billDate, null, List.of());
        }
        if (result == null || !billDate.equals(result.billDate())) {
            throw invalidResponse();
        }
        List<BillEntry> entries = result.entries().stream().map(this::toBillEntry).toList();
        return new BillDownload(
                ProviderOutcome.SUCCEEDED, billDate, required(result.sha256()), entries);
    }

    private PaymentCreation failedCreation(
            Instant expiresAt, WechatPayGateway.GatewayException exception) {
        return new PaymentCreation(
                outcome(exception), null, exception.kind().name(),
                new PaymentAction(PaymentActionType.NONE, null, Map.of(), null, expiresAt));
    }

    private PaymentCreation uncertainCreation(Instant expiresAt) {
        return new PaymentCreation(
                ProviderOutcome.UNCERTAIN, null, "UNKNOWN",
                new PaymentAction(PaymentActionType.NONE, null, Map.of(), null, expiresAt));
    }

    private ProviderPayment failedPayment(
            String transactionNo, WechatPayGateway.GatewayException exception) {
        return unresolvedPayment(
                outcome(exception), transactionNo, exception.kind().name(),
                exception.providerCode());
    }

    private ProviderRefund failedRefund(
            String refundNo, long amount, WechatPayGateway.GatewayException exception) {
        return new ProviderRefund(
                outcome(exception), refundNo, null, amount,
                exception.kind().name(), exception.providerCode());
    }

    private ProviderOutcome outcome(WechatPayGateway.GatewayException exception) {
        return switch (exception.kind()) {
            case REJECTED -> ProviderOutcome.FAILED;
            case NOT_FOUND -> ProviderOutcome.NOT_FOUND;
            case TIMEOUT, INTERNAL -> ProviderOutcome.UNCERTAIN;
        };
    }

    private ProviderPayment toProviderPayment(
            WechatPayGateway.PaymentResult result, PaymentQuery expected) {
        String expectedTransactionNo = expected.transactionNo();
        if (result.status() == null
                || !appId.equals(result.appId())
                || !merchantId.equals(result.merchantId())
                || !expectedTransactionNo.equals(result.merchantOrderNo())
                || result.amount() != expected.amount()
                || !expected.currency().equalsIgnoreCase(result.currency())) {
            throw invalidResponse();
        }
        ProviderOutcome outcome = paymentOutcome(result.status());
        if (outcome == ProviderOutcome.SUCCEEDED) {
            validateSuccessfulPayment(
                    result.appId(), result.merchantId(), result.merchantOrderNo(),
                    expectedTransactionNo, result.transactionId(), result.amount(),
                    result.currency(), result.paidAt());
        }
        String transactionNo = result.merchantOrderNo();
        return new ProviderPayment(
                outcome, transactionNo, optional(result.transactionId()), result.amount(),
                result.currency(),
                outcome == ProviderOutcome.SUCCEEDED ? result.paidAt() : null,
                result.status() == null ? "UNKNOWN" : result.status().name(),
                safeCode(result.providerCode()),
                sha256((transactionNo + "\n" + safeCode(result.providerCode()))
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private ProviderPayment toProviderPayment(WechatTransaction transaction, String payloadHash) {
        WechatPayGateway.PaymentStatus status = paymentStatus(transaction.tradeState());
        ProviderOutcome outcome = paymentOutcome(status);
        long amount = transaction.amount() == null ? 0 : transaction.amount().total();
        String currency = transaction.amount() == null ? null : transaction.amount().currency();
        Instant paidAt = parseInstant(transaction.successTime());
        if (outcome == ProviderOutcome.SUCCEEDED) {
            validateSuccessfulPayment(
                    transaction.appId(), transaction.merchantId(), transaction.merchantOrderNo(),
                    transaction.merchantOrderNo(), transaction.transactionId(), amount,
                    currency, paidAt);
        }
        if (amount <= 0 || !"CNY".equalsIgnoreCase(currency)) {
            throw invalidResponse();
        }
        return new ProviderPayment(
                outcome, required(transaction.merchantOrderNo()), optional(transaction.transactionId()),
                amount, currency, outcome == ProviderOutcome.SUCCEEDED ? paidAt : null,
                status.name(), null, payloadHash);
    }

    private void validateSuccessfulPayment(
            String suppliedAppId,
            String suppliedMerchantId,
            String merchantOrderNo,
            String expectedMerchantOrderNo,
            String externalTradeNo,
            long amount,
            String currency,
            Instant paidAt) {
        if (!appId.equals(suppliedAppId)
                || !merchantId.equals(suppliedMerchantId)
                || merchantOrderNo == null
                || !merchantOrderNo.equals(expectedMerchantOrderNo)
                || externalTradeNo == null || externalTradeNo.isBlank()
                || amount <= 0
                || !"CNY".equalsIgnoreCase(currency)
                || paidAt == null) {
            throw invalidResponse();
        }
        ExpectedPayment expected = expectedPayments.get(merchantOrderNo);
        if (expected != null
                && (expected.amount() != amount
                || !expected.currency().equalsIgnoreCase(currency))) {
            throw invalidResponse();
        }
    }

    private ProviderRefund toProviderRefund(
            WechatPayGateway.RefundResult result, String expectedRefundNo, long expectedAmount) {
        if (result.status() == null
                || result.merchantRefundNo() == null
                || !result.merchantRefundNo().equals(expectedRefundNo)) {
            throw invalidResponse();
        }
        if (result.amount() != expectedAmount) {
            throw invalidResponse();
        }
        return new ProviderRefund(
                refundOutcome(result.status()), expectedRefundNo, optional(result.refundId()),
                result.amount(),
                result.status().name(), safeCode(result.providerCode()));
    }

    private BillEntry toBillEntry(WechatPayGateway.BillLine line) {
        return new BillEntry(
                required(line.providerRecordId()), Objects.requireNonNull(line.entryType()),
                optional(line.merchantOrderNo()), optional(line.merchantRefundNo()),
                line.amount(), line.currency(), line.feeAmount(), line.feeCurrency(),
                line.feeKnown(), paymentOutcome(line.status()), line.occurredAt());
    }

    private ProviderPayment unresolvedPayment(
            ProviderOutcome outcome,
            String transactionNo,
            String providerStatus,
            String providerErrorCode) {
        return new ProviderPayment(
                outcome, transactionNo, null, 1, "CNY", null,
                safeCode(providerStatus), safeCode(providerErrorCode),
                sha256(transactionNo.getBytes(StandardCharsets.UTF_8)));
    }

    private ProviderOutcome paymentOutcome(WechatPayGateway.PaymentStatus status) {
        if (status == null) {
            return ProviderOutcome.UNCERTAIN;
        }
        return switch (status) {
            case SUCCESS, REFUND -> ProviderOutcome.SUCCEEDED;
            case NOTPAY, USERPAYING, ACCEPT -> ProviderOutcome.PENDING;
            case CLOSED, REVOKED -> ProviderOutcome.CLOSED;
            case PAYERROR -> ProviderOutcome.FAILED;
            case NOT_FOUND -> ProviderOutcome.NOT_FOUND;
            case UNKNOWN -> ProviderOutcome.UNCERTAIN;
        };
    }

    private ProviderOutcome refundOutcome(WechatPayGateway.RefundStatus status) {
        return switch (status) {
            case SUCCESS -> ProviderOutcome.SUCCEEDED;
            case PROCESSING -> ProviderOutcome.PENDING;
            case CLOSED -> ProviderOutcome.CLOSED;
            case ABNORMAL -> ProviderOutcome.FAILED;
            case NOT_FOUND -> ProviderOutcome.NOT_FOUND;
            case UNKNOWN -> ProviderOutcome.UNCERTAIN;
        };
    }

    private WechatPayGateway.PaymentStatus paymentStatus(String status) {
        try {
            return WechatPayGateway.PaymentStatus.valueOf(required(status).toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            return WechatPayGateway.PaymentStatus.UNKNOWN;
        }
    }

    private byte[] decrypt(EncryptedResource resource) throws Exception {
        byte[] nonce = required(resource.nonce()).getBytes(StandardCharsets.UTF_8);
        byte[] associatedData = resource.associatedData() == null
                ? new byte[0] : resource.associatedData().getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = Base64.getDecoder().decode(required(resource.ciphertext()));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(apiV3Key, "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(associatedData);
        return cipher.doFinal(ciphertext);
    }

    private VerifiedResource verifyAndDecrypt(CallbackEnvelope callback) throws Exception {
        Objects.requireNonNull(callback, "callback");
        byte[] rawBody = callback.rawBody();
        String timestampValue = requiredHeader(callback.headers(), "Wechatpay-Timestamp");
        String nonce = requiredHeader(callback.headers(), "Wechatpay-Nonce");
        String signatureValue = requiredHeader(callback.headers(), "Wechatpay-Signature");
        String serial = requiredHeader(callback.headers(), "Wechatpay-Serial");
        String suppliedPublicKeyId = optionalHeader(
                callback.headers(), "Wechatpay-Public-Key-Id");
        Instant callbackTime = Instant.ofEpochSecond(Long.parseLong(timestampValue));
        Instant now = clock.instant();
        if (callbackTime.isBefore(now.minusSeconds(CALLBACK_WINDOW_SECONDS))
                || callbackTime.isAfter(now.plusSeconds(CALLBACK_WINDOW_SECONDS))
                || !platformPublicKeyId.equals(serial)
                || suppliedPublicKeyId != null
                && !platformPublicKeyId.equals(suppliedPublicKeyId)) {
            throw invalidCallback();
        }
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(platformPublicKey);
        verifier.update(signatureMessage(timestampValue, nonce, rawBody));
        if (!verifier.verify(Base64.getDecoder().decode(signatureValue))) {
            throw invalidCallback();
        }
        CallbackNotification notification = objectMapper.readValue(
                rawBody, CallbackNotification.class);
        if (notification.resource() == null
                || !"AEAD_AES_256_GCM".equals(notification.resource().algorithm())) {
            throw invalidCallback();
        }
        return new VerifiedResource(rawBody, decrypt(notification.resource()));
    }

    private byte[] signatureMessage(String timestamp, String nonce, byte[] rawBody) {
        byte[] prefix = (timestamp + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer message = ByteBuffer.allocate(prefix.length + rawBody.length + 1);
        return message.put(prefix).put(rawBody).put((byte) '\n').array();
    }

    private String requiredHeader(Map<String, List<String>> headers, String name) {
        String value = optionalHeader(headers, name);
        if (value == null) {
            throw invalidCallback();
        }
        return value;
    }

    private String optionalHeader(Map<String, List<String>> headers, String name) {
        List<String> values = new ArrayList<>();
        headers.forEach((headerName, headerValues) -> {
            if (headerName.equalsIgnoreCase(name)) {
                values.addAll(headerValues);
            }
        });
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() != 1 || values.getFirst().isBlank()) {
            throw invalidCallback();
        }
        return values.getFirst();
    }

    private String safeCode(String code) {
        return code != null && PaymentAdapter.SAFE_PROVIDER_VALUE.matcher(code).matches()
                ? code : "UNKNOWN";
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw invalidResponse();
        }
        return value;
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Instant parseInstant(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("payment_hash_failed");
        }
    }

    private IllegalStateException invalidResponse() {
        return new IllegalStateException("invalid_wechat_payment_response");
    }

    private ResponseStatusException invalidCallback() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_wechat_callback");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CallbackNotification(EncryptedResource resource) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EncryptedResource(
            String algorithm,
            String ciphertext,
            @JsonProperty("associated_data") String associatedData,
            String nonce) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WechatTransaction(
            @JsonProperty("appid") String appId,
            @JsonProperty("mchid") String merchantId,
            @JsonProperty("out_trade_no") String merchantOrderNo,
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("trade_state") String tradeState,
            @JsonProperty("success_time") String successTime,
            WechatAmount amount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WechatAmount(long total, String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WechatRefundNotification(
            @JsonProperty("mchid") String merchantId,
            @JsonProperty("out_refund_no") String merchantRefundNo) {
    }

    private record VerifiedResource(byte[] rawBody, byte[] plaintext) {
    }

    private record ExpectedPayment(long amount, String currency) {
    }
}
