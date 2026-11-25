package com.yuzhihuihe.apiplatform.gateway.safety;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class ContentSafetyEventPublisher {

    private static final HexFormat HEX = HexFormat.of();
    private final WebClient client;
    private final String secret;
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    public ContentSafetyEventPublisher(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${platform.content-safety.internal-base-url:http://platform-service:8080}") String baseUrl,
            @Value("${security.internal.hmac-secret:}") String secret) {
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.secret = secret == null ? "" : secret;
    }

    public Mono<Void> publish(SafetyEventPayload event) {
        UUID requestId = UUID.randomUUID();
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(event);
        } catch (JsonProcessingException exception) {
            return Mono.error(new IllegalArgumentException("content_safety_event_invalid", exception));
        }
        long timestamp = Instant.now().getEpochSecond();
        String hash = HEX.formatHex(sha256(body));
        String signature = hmac(timestamp + "\n" + requestId + "\nPOST\n/internal/platform/content-safety/events\n" + hash);
        return client.post().uri("/internal/platform/content-safety/events")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Timestamp", Long.toString(timestamp))
                .header("X-Internal-Request-Id", requestId.toString())
                .header("X-Internal-Content-Sha256", hash)
                .header("X-Internal-Signature", signature)
                .bodyValue(body)
                .retrieve().bodyToMono(Void.class).then();
    }

    private byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (Exception exception) {
            throw new IllegalStateException("content_safety_hash_failed", exception);
        }
    }

    private String hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("content_safety_signature_failed", exception);
        }
    }
}
