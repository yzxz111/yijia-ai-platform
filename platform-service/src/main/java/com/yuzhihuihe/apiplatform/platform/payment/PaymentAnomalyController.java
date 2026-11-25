package com.yuzhihuihe.apiplatform.platform.payment;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/console/platform/admin/payment-anomalies")
public class PaymentAnomalyController {

    private static final Pattern ANOMALY_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");
    private static final Set<String> STATUSES = Set.of("OPEN", "INVESTIGATING", "RESOLVED");
    private static final Set<String> UPDATE_FIELDS = Set.of(
            "status", "assignee", "resolutionNote");

    private final PaymentAnomalyService service;

    public PaymentAnomalyController(PaymentAnomalyService service) {
        this.service = service;
    }

    @GetMapping
    PaymentAnomalyService.PageResult<PaymentAnomalyService.AnomalyView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String anomalyType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.list(new PaymentAnomalyService.AnomalyFilters(
                page, size, anomalyType(anomalyType), status(status), provider(provider),
                instant(from), instant(to),
                orderId, sort, direction));
    }

    @PatchMapping("/{id}")
    PaymentAnomalyService.AnomalyView update(
            @PathVariable UUID id,
            @RequestBody JsonNode body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        exactFields(body, UPDATE_FIELDS);
        String status = requiredText(body, "status");
        if (!STATUSES.contains(status)) {
            throw invalid("invalid_payment_anomaly_status");
        }
        JsonNode assigneeNode = body.get("assignee");
        JsonNode noteNode = body.get("resolutionNote");
        if (assigneeNode != null && !assigneeNode.isNull() && !assigneeNode.isTextual()) {
            throw invalid("invalid_payment_anomaly_assignee");
        }
        if (noteNode != null && !noteNode.isNull() && !noteNode.isTextual()) {
            throw invalid("invalid_payment_anomaly_resolution_note");
        }
        UUID assignee = assigneeNode == null || assigneeNode.isNull()
                ? null : uuid(assigneeNode.asText());
        String note = noteNode == null || noteNode.isNull() ? null : noteNode.asText();
        if (note != null && (note.isBlank() || note.length() > 1000)) {
            throw invalid("invalid_payment_anomaly_resolution_note");
        }
        return service.update(id, new PaymentAnomalyService.UpdateAnomalyCommand(
                status, assignee, note, expectedLockVersion(ifMatch)), actor(jwt));
    }

    private PaymentProvider provider(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return PaymentProvider.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid_payment_anomaly_provider");
        }
    }

    private String anomalyType(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        if (!ANOMALY_TYPE.matcher(normalized).matches()) {
            throw invalid("invalid_payment_anomaly_type");
        }
        return normalized;
    }

    private String status(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw invalid("invalid_payment_anomaly_status");
        }
        return normalized;
    }

    private Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException exception) {
            throw invalid("invalid_payment_anomaly_time");
        }
    }

    private void exactFields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw invalid("invalid_payment_anomaly_update");
        Set<String> actual = new HashSet<>();
        body.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(allowed)) throw invalid("invalid_payment_anomaly_update");
    }

    private long expectedLockVersion(String ifMatch) {
        if (ifMatch == null) throw invalid("invalid_payment_anomaly_lock_version");
        String value = ifMatch.strip();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty() || value.length() > 19
                || value.chars().anyMatch(character -> !Character.isDigit(character))) {
            throw invalid("invalid_payment_anomaly_lock_version");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid("invalid_payment_anomaly_lock_version");
        }
    }

    private String requiredText(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid("invalid_payment_anomaly_update");
        }
        return value.asText().strip().toUpperCase(java.util.Locale.ROOT);
    }

    private UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw invalid("invalid_payment_anomaly_assignee");
        }
    }

    private UUID actor(Jwt jwt) {
        if (jwt == null) throw invalid("invalid_payment_anomaly_actor");
        return uuid(jwt.getSubject());
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
