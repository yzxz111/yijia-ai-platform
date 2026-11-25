package com.yuzhihuihe.apiplatform.platform.notification;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationEventService {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "phone", "phonenumber", "mobile", "password", "token", "accesstoken", "refreshtoken",
            "apikey", "secret", "prompt", "response", "content", "attachment",
            "filebytes", "rawip", "ip", "ipaddress", "useragent", "device", "deviceid");

    private final NotificationEventRepository repository;
    private final ObjectMapper objectMapper;

    NotificationEventService(NotificationEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RecordedEvent record(
            String eventKey,
            String eventType,
            UUID tenantId,
            UUID userId,
            String aggregateType,
            String aggregateId,
            Map<String, ?> payload,
            OffsetDateTime occurredAt) {
        validateIdentity(eventKey, tenantId, userId, aggregateType, aggregateId, occurredAt);
        OffsetDateTime normalizedOccurredAt = normalizeOccurredAt(occurredAt);
        NotificationTypeRegistry.TypeDefinition definition = NotificationTypeRegistry.require(eventType);
        JsonNode payloadNode = validateAndCanonicalize(definition, payload);
        String canonicalPayload = json(payloadNode);
        String payloadHash = sha256(canonicalPayload);
        UUID eventId = UUID.randomUUID();

        boolean inserted = repository.insert(
                eventId, eventKey, definition.category().name(), eventType, tenantId, userId,
                aggregateType, aggregateId, canonicalPayload, normalizedOccurredAt);
        if (inserted) {
            return new RecordedEvent(
                    eventId, eventKey, eventType, definition.category(), payloadHash,
                    normalizedOccurredAt);
        }
        return replay(
                eventKey, eventType, tenantId, userId, aggregateType, aggregateId,
                normalizedOccurredAt, payloadHash);
    }

    @Transactional
    public RecordOutcome recordAuthoritativeBusinessEvent(
            String eventKey,
            String eventType,
            UUID tenantId,
            UUID userId,
            String aggregateType,
            String aggregateId,
            Map<String, ?> payload,
            OffsetDateTime occurredAt) {
        validateIdentity(eventKey, tenantId, userId, aggregateType, aggregateId, occurredAt);
        OffsetDateTime normalizedOccurredAt = normalizeOccurredAt(occurredAt);
        NotificationTypeRegistry.TypeDefinition definition = NotificationTypeRegistry.require(eventType);
        JsonNode payloadNode = validateAndCanonicalize(definition, payload);
        String canonicalPayload = json(payloadNode);
        String payloadHash = sha256(canonicalPayload);
        UUID eventId = UUID.randomUUID();

        boolean inserted = repository.insert(
                eventId, eventKey, definition.category().name(), eventType, tenantId, userId,
                aggregateType, aggregateId, canonicalPayload, normalizedOccurredAt);
        if (inserted) {
            return new RecordOutcome(new RecordedEvent(
                    eventId, eventKey, eventType, definition.category(), payloadHash,
                    normalizedOccurredAt), true);
        }
        return new RecordOutcome(replayAuthoritative(
                eventKey, eventType, tenantId, userId, aggregateType, aggregateId,
                payloadHash), false);
    }

    private RecordedEvent replay(
            String eventKey,
            String eventType,
            UUID tenantId,
            UUID userId,
            String aggregateType,
            String aggregateId,
            OffsetDateTime occurredAt,
            String candidatePayloadHash) {
        ExistingEvent event = existingEvent(eventKey);
        String existingHash = sha256(canonicalJson(event.payload()));
        boolean sameIdentity = event.eventType().equals(eventType)
                && Objects.equals(event.tenantId(), tenantId)
                && Objects.equals(event.userId(), userId)
                && event.aggregateType().equals(aggregateType)
                && event.aggregateId().equals(aggregateId)
                && event.occurredAt().toInstant().equals(occurredAt.toInstant());
        if (!sameIdentity || !constantTimeEquals(existingHash, candidatePayloadHash)) {
            throw idempotencyMismatch();
        }
        return new RecordedEvent(
                event.id(), event.eventKey(), event.eventType(), event.category(),
                existingHash, event.occurredAt());
    }

    private RecordedEvent replayAuthoritative(
            String eventKey,
            String eventType,
            UUID tenantId,
            UUID userId,
            String aggregateType,
            String aggregateId,
            String candidatePayloadHash) {
        ExistingEvent event = existingEvent(eventKey);
        String existingHash = sha256(canonicalJson(event.payload()));
        boolean sameIdentity = event.eventType().equals(eventType)
                && Objects.equals(event.tenantId(), tenantId)
                && Objects.equals(event.userId(), userId)
                && event.aggregateType().equals(aggregateType)
                && event.aggregateId().equals(aggregateId);
        if (!sameIdentity || !constantTimeEquals(existingHash, candidatePayloadHash)) {
            throw idempotencyMismatch();
        }
        return new RecordedEvent(
                event.id(), event.eventKey(), event.eventType(), event.category(),
                existingHash, event.occurredAt());
    }

    private ExistingEvent existingEvent(String eventKey) {
        NotificationEventRepository.ExistingEventRow row = repository.findByEventKey(eventKey)
                .orElseThrow(() ->
                        new IllegalStateException("notification_event_replay_missing"));
        return new ExistingEvent(
                row.id(), row.eventKey(), row.eventType(),
                NotificationTypeRegistry.Category.valueOf(row.category()),
                row.tenantId(), row.userId(), row.aggregateType(), row.aggregateId(),
                row.payload(), row.occurredAt());
    }

    private JsonNode validateAndCanonicalize(
            NotificationTypeRegistry.TypeDefinition definition, Map<String, ?> payload) {
        if (payload == null || !payload.keySet().equals(definition.allowedPayloadKeys())) {
            throw new IllegalArgumentException("notification_payload_keys_mismatch");
        }
        JsonNode node = objectMapper.valueToTree(payload);
        if (!node.isObject()) {
            throw new IllegalArgumentException("invalid_notification_payload");
        }
        rejectSensitiveKeys(node);
        return canonical(node);
    }

    private void rejectSensitiveKeys(JsonNode node) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String normalized = entry.getKey().replaceAll("[^A-Za-z0-9]", "")
                        .toLowerCase(Locale.ROOT);
                if (SENSITIVE_KEYS.contains(normalized)) {
                    throw new IllegalArgumentException("sensitive_notification_payload");
                }
                rejectSensitiveKeys(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::rejectSensitiveKeys);
        }
    }

    private String canonicalJson(String json) {
        try {
            return this.json(canonical(objectMapper.readTree(json)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("notification_payload_deserialization_failed", exception);
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), canonical(entry.getValue())));
            fields.forEach(sorted::set);
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(value -> array.add(canonical(value)));
            return array;
        }
        if (node.isFloatingPointNumber()) {
            BigDecimal normalized = node.decimalValue().stripTrailingZeros();
            return DecimalNode.valueOf(normalized);
        }
        return node;
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writer()
                    .with(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                    .writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("notification_payload_serialization_failed", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("notification_payload_hash_failed", exception);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseStatusException idempotencyMismatch() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "notification_event_idempotency_mismatch");
    }

    private OffsetDateTime normalizeOccurredAt(OffsetDateTime occurredAt) {
        OffsetDateTime utc = occurredAt.withOffsetSameInstant(ZoneOffset.UTC);
        return utc.withNano(utc.getNano() / 1_000 * 1_000);
    }

    private void validateIdentity(
            String eventKey,
            UUID tenantId,
            UUID userId,
            String aggregateType,
            String aggregateId,
            OffsetDateTime occurredAt) {
        if (eventKey == null || eventKey.isBlank() || eventKey.length() > 160) {
            throw new IllegalArgumentException("invalid_notification_event_key");
        }
        if (userId != null && tenantId == null) {
            throw new IllegalArgumentException("invalid_notification_event_scope");
        }
        if (aggregateType == null || !aggregateType.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid_notification_aggregate_type");
        }
        if (aggregateId == null || aggregateId.isBlank() || aggregateId.length() > 160) {
            throw new IllegalArgumentException("invalid_notification_aggregate_id");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("invalid_notification_occurred_at");
        }
    }

    public record RecordedEvent(
            UUID id,
            String eventKey,
            String eventType,
            NotificationTypeRegistry.Category category,
            String payloadHash,
            OffsetDateTime occurredAt) {
    }

    public record RecordOutcome(RecordedEvent event, boolean created) {
    }

    private record ExistingEvent(
            UUID id,
            String eventKey,
            String eventType,
            NotificationTypeRegistry.Category category,
            UUID tenantId,
            UUID userId,
            String aggregateType,
            String aggregateId,
            String payload,
            OffsetDateTime occurredAt) {
    }
}
