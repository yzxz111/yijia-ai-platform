package com.yuzhihuihe.apiplatform.platform.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
@ConditionalOnProperty(prefix = "payment.mock", name = "enabled", havingValue = "true")
public class MockPaymentService {

    private final MockPaymentRepository repository;
    private final PaymentPostingService postingService;
    private final Clock clock;
    private final byte[] callbackSecret;

    MockPaymentService(
            MockPaymentRepository repository,
            PaymentPostingService postingService,
            Clock clock,
            @Value("${payment.mock.callback-secret:${security.internal.hmac-secret:}}")
            String callbackSecret) {
        this.repository = repository;
        this.postingService = postingService;
        this.clock = clock;
        this.callbackSecret = callbackSecret == null
                ? new byte[0] : callbackSecret.getBytes(StandardCharsets.UTF_8);
    }

    public CallbackResult acceptVerified(CallbackRequest request, String signature) {
        validate(request);
        verifySignature(request, signature);
        MockAttempt attempt = loadCallbackAttempt(
                request.orderNo(), request.externalTradeNo());
        PaymentPostingService.PostingResult posted = postingService.postSucceeded(
                PaymentProvider.MOCK,
                new PaymentAdapter.ProviderPayment(
                        ProviderOutcome.SUCCEEDED,
                        attempt.transactionNo(),
                        request.externalTradeNo(),
                        request.amount(),
                        attempt.currency(),
                        clock.instant(),
                        "SUCCEEDED",
                        null,
                        payloadHash(request)));
        return new CallbackResult(posted.orderNo(), posted.orderStatus());
    }

    @Transactional
    public SimulationResult simulate(String orderNo, UUID actorUserId) {
        if (blank(orderNo) || orderNo.length() > 64 || actorUserId == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_payment_simulation");
        }
        MockAttempt attempt = loadSimulationAttempt(orderNo);
        String externalTradeNo = attempt.externalTradeNo() == null
                ? "admin-sim-" + UUID.randomUUID()
                : attempt.externalTradeNo();
        CallbackRequest request = new CallbackRequest(
                externalTradeNo, attempt.orderNo(), attempt.amount());
        CallbackResult result = acceptVerified(
                request, HexFormat.of().formatHex(sign(request)));
        repository.auditSimulation(
                attempt.tenantId(), actorUserId, result.orderNo(), externalTradeNo);
        return new SimulationResult(result.orderNo(), result.status(), externalTradeNo);
    }

    private MockAttempt loadCallbackAttempt(
            String orderNo, String externalTradeNo) {
        return requireAttempt(repository.findCallbackAttempt(orderNo, externalTradeNo));
    }

    private MockAttempt loadSimulationAttempt(String orderNo) {
        return requireAttempt(repository.findSimulationAttempt(orderNo));
    }

    private MockAttempt requireAttempt(List<MockPaymentRepository.AttemptRow> attempts) {
        return attempts.stream().findFirst().map(row -> new MockAttempt(
                        row.transactionNo(), row.externalTradeNo(), row.orderNo(),
                        row.tenantId(), row.amount(), row.currency()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "mock_payment_attempt_not_found"));
    }

    private void verifySignature(CallbackRequest request, String signature) {
        if (blank(signature)) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "payment_signature_required");
        }
        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException exception) {
            supplied = new byte[0];
        }
        if (callbackSecret.length == 0
                || !MessageDigest.isEqual(sign(request), supplied)) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "invalid_payment_signature");
        }
    }

    private void validate(CallbackRequest request) {
        if (request == null
                || blank(request.externalTradeNo())
                || blank(request.orderNo())
                || request.externalTradeNo().length() > 128
                || request.orderNo().length() > 64
                || request.amount() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_payment_callback");
        }
    }

    private byte[] sign(CallbackRequest request) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(callbackSecret, "HmacSHA256"));
            return mac.doFinal(signaturePayload(request).getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("payment_signature_failed", exception);
        }
    }

    private String payloadHash(CallbackRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(signaturePayload(request).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("payment_hash_failed", exception);
        }
    }

    private String signaturePayload(CallbackRequest request) {
        return request.externalTradeNo() + "\n" + request.orderNo() + "\n" + request.amount();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record CallbackRequest(String externalTradeNo, String orderNo, long amount) {
    }

    public record CallbackResult(String orderNo, String status) {
    }

    public record SimulationResult(String orderNo, String status, String externalTradeNo) {
    }

    private record MockAttempt(
            String transactionNo,
            String externalTradeNo,
            String orderNo,
            UUID tenantId,
            long amount,
            String currency) {
    }
}
