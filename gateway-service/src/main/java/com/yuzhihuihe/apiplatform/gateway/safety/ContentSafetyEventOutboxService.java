package com.yuzhihuihe.apiplatform.gateway.safety;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ContentSafetyEventOutboxService {

    private static final HexFormat HEX = HexFormat.of();
    private final ContentSafetyEventOutboxRepository repository;
    private final ObjectMapper objectMapper;

    public ContentSafetyEventOutboxService(
            ContentSafetyEventOutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> enqueue(SafetyEventPayload event) {
        String payload = json(event);
        String fingerprint = sha256(event.businessId());
        return repository.insert(
                new ContentSafetyEventOutboxRepository.OutboxRecord(
                        UUID.randomUUID(),
                        event.eventType(),
                        fingerprint,
                        payload,
                        sha256(payload)));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("content_safety_event_invalid", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HEX.formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("content_safety_hash_failed", exception);
        }
    }
}
