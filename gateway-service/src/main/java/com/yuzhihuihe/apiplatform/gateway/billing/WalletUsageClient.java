package com.yuzhihuihe.apiplatform.gateway.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class WalletUsageClient {

    private static final HexFormat HEX = HexFormat.of();
    private static final int MAX_ERROR_BODY_BYTES = 4096;
    private static final Set<String> APPROVED_ERROR_CODES = Set.of(
            "billing_reconciliation_required",
            "invalid_internal_signature",
            "invalid_wallet_usage_reservation",
            "invalid_wallet_usage_settlement",
            "wallet_insufficient_balance",
            "wallet_not_found",
            "wallet_operation_failed",
            "wallet_usage_idempotency_mismatch",
            "wallet_usage_not_releasable",
            "wallet_usage_not_settleable",
            "wallet_usage_reservation_not_found");
    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final String secret;

    public WalletUsageClient(WebClient.Builder builder, ObjectMapper objectMapper,
            @Value("${platform.wallet.internal-base-url:http://platform-service:8080}") String baseUrl,
            @Value("${security.internal.hmac-secret:}") String secret) {
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.secret = secret == null ? "" : secret;
    }

    public Mono<WalletResponse> reserve(WalletRequest request) {
        return call("POST", "/internal/platform/wallet/usage-reservations", request,
                WalletResponse.class);
    }

    public Mono<WalletResponse> settle(UUID reservationId, WalletSettlement request) {
        return call("POST", "/internal/platform/wallet/usage-reservations/" + reservationId + "/settle",
                request, WalletResponse.class);
    }

    public Mono<WalletResponse> release(UUID reservationId) {
        return call("POST", "/internal/platform/wallet/usage-reservations/" + reservationId + "/release",
                null, WalletResponse.class);
    }

    public Mono<WalletEligibility> eligibility(UUID tenantId, UUID userId) {
        String path = "/internal/platform/wallet/eligibility/" + tenantId + "/" + userId;
        return callWithoutBody("GET", path, WalletEligibility.class);
    }

    private <T> Mono<T> call(String method, String path, Object payload, Class<T> responseType) {
        return Mono.defer(() -> {
            try {
                byte[] body = payload == null ? new byte[0] : objectMapper.writeValueAsBytes(payload);
                UUID requestId = UUID.randomUUID();
                long timestamp = Instant.now().getEpochSecond();
                String hash = sha256(body);
                String signature = hmac(timestamp + "\n" + requestId + "\n" + method + "\n" + path + "\n" + hash);
                WebClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.valueOf(method))
                        .uri(path).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Timestamp", Long.toString(timestamp))
                        .header("X-Internal-Request-Id", requestId.toString())
                        .header("X-Internal-Content-Sha256", hash)
                        .header("X-Internal-Signature", signature);
                return (payload == null ? spec.bodyValue(new byte[0]) : spec.bodyValue(body))
                        .retrieve()
                        .bodyToMono(responseType)
                        .onErrorMap(WebClientResponseException.class, this::responseError);
            } catch (Exception error) {
                return Mono.error(new WalletUsageClientException("wallet_billing_unavailable", null, error));
            }
        });
    }

    private <T> Mono<T> callWithoutBody(String method, String path, Class<T> responseType) {
        return Mono.defer(() -> {
            try {
                byte[] body = new byte[0];
                UUID requestId = UUID.randomUUID();
                long timestamp = Instant.now().getEpochSecond();
                String hash = sha256(body);
                String signature = hmac(timestamp + "\n" + requestId + "\n" + method
                        + "\n" + path + "\n" + hash);
                return client.method(HttpMethod.valueOf(method))
                        .uri(path)
                        .header("X-Internal-Timestamp", Long.toString(timestamp))
                        .header("X-Internal-Request-Id", requestId.toString())
                        .header("X-Internal-Content-Sha256", hash)
                        .header("X-Internal-Signature", signature)
                        .retrieve()
                        .bodyToMono(responseType)
                        .onErrorMap(WebClientResponseException.class, this::responseError);
            } catch (Exception error) {
                return Mono.error(new WalletUsageClientException(
                        "wallet_billing_unavailable", null, error));
            }
        });
    }

    private WalletUsageClientException responseError(WebClientResponseException error) {
        String code = "wallet_billing_unavailable";
        byte[] body = error.getResponseBodyAsByteArray();
        if (body.length <= MAX_ERROR_BODY_BYTES) {
            try {
                String candidate = objectMapper.readTree(body).path("code").asText();
                if (APPROVED_ERROR_CODES.contains(candidate)) {
                    code = candidate;
                }
            } catch (Exception ignored) {
                // 格式错误的内部响应按依赖不可用处理。
            }
        }
        return new WalletUsageClientException(code, error.getStatusCode().value());
    }

    private String sha256(byte[] body) throws Exception {
        return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private String hmac(String canonical) throws Exception {
        if (secret.isBlank()) {
            throw new IllegalStateException("wallet_billing_unavailable");
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    public record WalletRequest(UUID reservationId, String requestId, UUID tenantId, UUID userId,
            String modelCode, long estimatedAmount, long inputTokens, long outputTokens,
            String priceVersion, String idempotencyKey, Instant expiresAt) {
        public WalletRequest(UUID reservationId, String requestId, UUID tenantId, UUID userId,
                String modelCode, long estimatedAmount, long inputTokens, long outputTokens,
                String priceVersion, String idempotencyKey) {
            this(reservationId, requestId, tenantId, userId, modelCode, estimatedAmount,
                    inputTokens, outputTokens, priceVersion, idempotencyKey, null);
        }
    }

    public record WalletSettlement(UUID tenantId, UUID userId, long settledAmount,
            long inputTokens, long outputTokens, String billingMode) { }

    public record WalletResponse(UUID id, String requestId, long estimatedAmount,
            Long settledAmount, String status, String modelCode, String priceVersion) { }

    public record WalletEligibility(boolean usable, long availableAmount, String currency) { }

    public static class WalletUsageClientException extends RuntimeException {
        private final String code;
        private final Integer status;

        public WalletUsageClientException(String code, Integer status) {
            super(code);
            this.code = code;
            this.status = status;
        }

        public WalletUsageClientException(String code, Integer status, Throwable cause) {
            super(code, cause);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public Integer status() {
            return status;
        }
    }
}
