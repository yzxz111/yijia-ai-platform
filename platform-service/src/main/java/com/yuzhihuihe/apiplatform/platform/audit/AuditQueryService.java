package com.yuzhihuihe.apiplatform.platform.audit;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuditQueryService {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "apikey", "api_key", "secret", "providersecret", "provider_secret",
            "authorization", "body", "payload", "base64", "fileurl", "file_url");
    private static final Set<String> SORTS = Set.of(
            "createdAt", "action", "resourceType", "outcome");

    private final AuditQueryRepository repository;
    private final ObjectMapper objectMapper;

    public AuditQueryService(AuditQueryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public PageResult<AuditEntry> query(
            PageRequest page, UUID actorUserId, String action, String resourceType,
            String resourceId, String outcome, Instant from, Instant to,
            String requestedSort, String requestedDirection) {
        PageRequest valid = page.validated();
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid_audit_time_range");
        }
        AuditQueryRepository.PageRows rows = repository.query(
                new AuditQueryRepository.PageSpec(valid.page(), valid.size(), valid.offset()),
                actorUserId, normalized(action), normalized(resourceType), normalized(resourceId),
                normalized(outcome), from, to, sort(requestedSort, requestedDirection));
        List<AuditEntry> items = rows.items().stream().map(this::toAuditEntry).toList();
        return new PageResult<>(items, rows.total(), rows.page(), rows.size());
    }

    private AuditQueryRepository.SortSpec sort(
            String requestedSort, String requestedDirection) {
        boolean defaultRequest = normalized(requestedSort) == null
                && normalized(requestedDirection) == null;
        String sort = normalized(requestedSort) == null ? "createdAt" : requestedSort.strip();
        if (!SORTS.contains(sort)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sort");
        }
        String direction = normalized(requestedDirection) == null
                ? "DESC" : requestedDirection.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ASC", "DESC").contains(direction)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sort_direction");
        }
        return new AuditQueryRepository.SortSpec(sort, direction, defaultRequest);
    }

    private AuditEntry toAuditEntry(AuditQueryRepository.AuditRow row) {
        return new AuditEntry(
                row.id(), row.source(), row.tenantId(), row.actorUserId(), row.actorUserNo(),
                row.actorDisplayName(), row.action(), row.resourceType(), row.resourceId(),
                row.requestId(), row.outcome(), redact(parseDetail(row.detailJson())), row.createdAt());
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private JsonNode parseDetail(String value) {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("invalid_audit_detail", error);
        }
    }

    private JsonNode redact(JsonNode value) {
        if (value == null || value.isNull()) {
            return value;
        }
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                if (SENSITIVE_KEYS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
                    result.put(field.getKey(), "[REDACTED]");
                } else {
                    result.set(field.getKey(), redact(field.getValue()));
                }
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(element -> result.add(redact(element)));
            return result;
        }
        return value.deepCopy();
    }

    public record PageRequest(int page, int size) {
        public PageRequest validated() {
            if (page < 0 || size < 1 || size > 100) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid_pagination");
            }
            if ((long) page * size > Integer.MAX_VALUE) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid_pagination");
            }
            return this;
        }

        int offset() {
            return Math.multiplyExact(page, size);
        }
    }

    public record PageResult<T>(List<T> items, long total, int page, int size) { }

    public record AuditEntry(
            long id, String source, UUID tenantId, UUID actorUserId,
            String actorUserNo, String actorDisplayName, String action, String resourceType,
            String resourceId, String requestId, String outcome, JsonNode detail,
            Instant createdAt) { }
}
