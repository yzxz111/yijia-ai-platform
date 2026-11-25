package com.yuzhihuihe.apiplatform.platform.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("local")
@ConditionalOnProperty(prefix = "payment.mock", name = "enabled", havingValue = "true")
public class MockPaymentAdapter implements PaymentAdapter {

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final byte[] callbackSecret;
    private final AdapterConfiguration configuration;

    public MockPaymentAdapter(
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${payment.mock.callback-secret:${security.internal.hmac-secret:}}")
            String callbackSecret) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.callbackSecret = callbackSecret == null
                ? new byte[0] : callbackSecret.getBytes(StandardCharsets.UTF_8);
        this.configuration = this.callbackSecret.length == 0
                ? new AdapterConfiguration(false, null, null, null)
                : new AdapterConfiguration(true, sha256(this.callbackSecret), "VALID", clock.instant());
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.MOCK;
    }

    @Override
    public PaymentEnvironment environment() {
        return PaymentEnvironment.LOCAL;
    }

    @Override
    public Set<PaymentProduct> products() {
        return Set.of(PaymentProduct.MOCK);
    }

    @Override
    public AdapterConfiguration configuration() {
        return configuration;
    }

    @Override
    public PaymentCreation createPayment(PaymentCommand command) {
        if (command.product() != PaymentProduct.MOCK) {
            throw new IllegalArgumentException("payment_product_provider_mismatch");
        }
        Instant actionExpiry = min(command.expiresAt(), clock.instant().plusSeconds(300));
        return new PaymentCreation(
                ProviderOutcome.PENDING,
                "mock-" + command.transactionNo(),
                "WAITING",
                new PaymentAction(
                        PaymentActionType.NONE, null, Map.of(), null, actionExpiry));
    }

    @Override
    public ProviderPayment verifyAndParseCallback(CallbackEnvelope callback) {
        MockCallback payload;
        try {
            payload = objectMapper.readValue(callback.rawBody(), MockCallback.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_payment_callback");
        }
        String signature = header(callback.headers(), "X-Payment-Signature");
        byte[] supplied;
        try {
            supplied = signature == null ? new byte[0] : HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException exception) {
            supplied = new byte[0];
        }
        byte[] expected = sign(payload.externalTradeNo(), payload.orderNo(), payload.amount());
        if (callbackSecret.length == 0 || !MessageDigest.isEqual(expected, supplied)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_payment_signature");
        }
        return new ProviderPayment(
                ProviderOutcome.SUCCEEDED,
                payload.transactionNo(),
                payload.externalTradeNo(),
                payload.amount(),
                payload.currency(),
                payload.paidAt() == null ? clock.instant() : payload.paidAt(),
                "SUCCEEDED", null, sha256(callback.rawBody()));
    }

    @Override
    public ProviderPayment queryPayment(PaymentQuery query) {
        Objects.requireNonNull(query, "query");
        return unresolvedPayment(ProviderOutcome.UNCERTAIN, query, "UNKNOWN");
    }

    @Override
    public ProviderPayment closePayment(PaymentQuery query) {
        Objects.requireNonNull(query, "query");
        return unresolvedPayment(ProviderOutcome.CLOSED, query, "CLOSED");
    }

    @Override
    public ProviderRefund createRefund(RefundCommand command) {
        return successfulRefund(command.refundNo(), command.amount());
    }

    @Override
    public ProviderRefund queryRefund(RefundQuery query) {
        return successfulRefund(query.refundNo(), query.amount());
    }

    private ProviderRefund successfulRefund(String refundNo, long amount) {
        return new ProviderRefund(
                ProviderOutcome.SUCCEEDED, refundNo, "mock-" + refundNo,
                amount, "SUCCEEDED", null);
    }

    @Override
    public BillDownload downloadBill(LocalDate billDate) {
        return new BillDownload(
                ProviderOutcome.SUCCEEDED, billDate, sha256(new byte[0]), List.of());
    }

    private ProviderPayment unresolvedPayment(
            ProviderOutcome outcome,
            PaymentQuery query,
            String status) {
        return new ProviderPayment(
                outcome, query.transactionNo(), null, query.amount(), query.currency(), null,
                status, null,
                sha256(query.transactionNo().getBytes(StandardCharsets.UTF_8)));
    }

    private String header(Map<String, List<String>> headers, String expectedName) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(expectedName))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst().orElse(null);
    }

    private byte[] sign(String externalTradeNo, String orderNo, long amount) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(callbackSecret, "HmacSHA256"));
            return mac.doFinal((externalTradeNo + "\n" + orderNo + "\n" + amount)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("payment_signature_failed", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("payment_hash_failed", exception);
        }
    }

    private Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private record MockCallback(
            String transactionNo,
            String externalTradeNo,
            String orderNo,
            long amount,
            String currency,
            Instant paidAt) {
    }
}
