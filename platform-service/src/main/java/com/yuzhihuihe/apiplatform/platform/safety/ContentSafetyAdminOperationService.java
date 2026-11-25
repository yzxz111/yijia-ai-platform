package com.yuzhihuihe.apiplatform.platform.safety;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContentSafetyAdminOperationService {

    private static final HexFormat HEX = HexFormat.of();

    private final ContentSafetyAdminOperationRepository repository;
    private final ObjectMapper objectMapper;

    ContentSafetyAdminOperationService(
            ContentSafetyAdminOperationRepository repository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public <T> T execute(
            UUID actorUserId,
            String idempotencyKey,
            String action,
            Object request,
            Class<T> responseType,
            Supplier<T> mutation,
            Function<T, AuditTarget> auditTarget) {
        validate(actorUserId, idempotencyKey, action, responseType, mutation, auditTarget);
        String requestSha256 = requestSha256(request);
        UUID operationId = UUID.randomUUID();
        int created = repository.tryCreate(
                operationId, actorUserId, action, idempotencyKey, requestSha256);
        if (created == 0) {
            return replay(actorUserId, idempotencyKey, action, requestSha256, responseType);
        }

        T response = mutation.get();
        AuditTarget target = auditTarget.apply(response);
        validateTarget(target);
        repository.complete(
                operationId, json(response), target.resourceType(), target.resourceId());
        repository.auditSuccess(
                operationId, target.tenantId(), actorUserId, action,
                target.resourceType(), target.resourceId(), idempotencyKey, requestSha256);
        return response;
    }

    private <T> T replay(
            UUID actorUserId,
            String idempotencyKey,
            String action,
            String requestSha256,
            Class<T> responseType) {
        ContentSafetyAdminOperationRepository.OperationRow existing =
                repository.lock(actorUserId, action, idempotencyKey);
        if (existing == null || existing.responseJson() == null) {
            throw new IllegalStateException("content_safety_operation_incomplete");
        }
        if (!MessageDigest.isEqual(
                existing.requestSha256().getBytes(StandardCharsets.US_ASCII),
                requestSha256.getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "content_safety_idempotency_payload_mismatch");
        }
        try {
            return objectMapper.readValue(existing.responseJson(), responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("content_safety_operation_response_corrupt", exception);
        }
    }

    private String requestSha256(Object request) {
        try {
            byte[] canonicalRequest = objectMapper.writeValueAsBytes(
                    canonical(objectMapper.valueToTree(request)));
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalRequest));
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_content_safety_operation", exception);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("content_safety_operation_hash_failed", exception);
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.nullNode();
        }
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), canonical(entry.getValue())));
            fields.forEach(sorted::set);
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(value -> array.add(canonical(value)));
            return array;
        }
        return node;
    }

    private String json(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("content_safety_operation_response_serialization_failed", exception);
        }
    }

    private void validate(
            UUID actorUserId,
            String idempotencyKey,
            String action,
            Class<?> responseType,
            Supplier<?> mutation,
            Function<?, AuditTarget> auditTarget) {
        if (actorUserId == null || action == null || action.isBlank() || action.length() > 128
                || responseType == null || mutation == null || auditTarget == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_content_safety_operation");
        }
        if (idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
                || idempotencyKey.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_idempotency_key");
        }
    }

    private void validateTarget(AuditTarget target) {
        if (target == null || target.resourceType() == null || target.resourceType().isBlank()
                || target.resourceType().length() > 64
                || target.resourceId() != null && target.resourceId().length() > 128) {
            throw new IllegalStateException("invalid_content_safety_audit_target");
        }
    }

    public record AuditTarget(UUID tenantId, String resourceType, String resourceId) {
    }
}
