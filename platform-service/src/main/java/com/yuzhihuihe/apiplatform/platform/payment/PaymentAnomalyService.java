package com.yuzhihuihe.apiplatform.platform.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentAnomalyService {

    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");
    private static final Pattern SAFE_DETAIL_VALUE = Pattern.compile("[A-Z0-9_:-]{1,128}");
    private static final Set<String> STATUSES = Set.of("OPEN", "INVESTIGATING", "RESOLVED");
    private static final Set<String> DETAIL_KEYS = Set.of(
            "reason", "expectedAmount", "actualAmount", "expectedStatus", "actualStatus",
            "expectedCurrency", "actualCurrency", "entryType", "duplicateCount",
            "recoveryOutcome");
    private static final Map<String, PaymentAnomalyRepository.Sort> SORTS = Map.of(
            "createdAt", PaymentAnomalyRepository.Sort.CREATED_AT,
            "updatedAt", PaymentAnomalyRepository.Sort.UPDATED_AT,
            "anomalyType", PaymentAnomalyRepository.Sort.ANOMALY_TYPE,
            "status", PaymentAnomalyRepository.Sort.STATUS);

    private final PaymentAnomalyRepository repository;
    private final ObjectMapper objectMapper;

    PaymentAnomalyService(PaymentAnomalyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void recordQueryUncertain(
            PaymentProvider provider,
            UUID orderId,
            UUID transactionId,
            String transactionNo) {
        recordQueryUncertain(provider, orderId, null, transactionId, transactionNo);
    }

    @Transactional
    public void recordQueryUncertain(
            PaymentProvider provider,
            UUID orderId,
            UUID rechargeOrderId,
            UUID transactionId,
            String transactionNo) {
        requirePaymentIdentity(orderId, rechargeOrderId, transactionId, transactionNo);
        record(new AnomalyCommand(
                "PAYMENT_QUERY_UNCERTAIN", provider, orderId, transactionId, null,
                Map.of("reason", "QUERY_UNCERTAIN")));
    }

    @Transactional
    public void recordCloseUncertain(
            PaymentProvider provider,
            UUID orderId,
            UUID transactionId,
            String transactionNo) {
        recordCloseUncertain(provider, orderId, null, transactionId, transactionNo);
    }

    @Transactional
    public void recordCloseUncertain(
            PaymentProvider provider,
            UUID orderId,
            UUID rechargeOrderId,
            UUID transactionId,
            String transactionNo) {
        requirePaymentIdentity(orderId, rechargeOrderId, transactionId, transactionNo);
        record(new AnomalyCommand(
                "PAYMENT_CLOSE_UNCERTAIN", provider, orderId, transactionId, null,
                Map.of("reason", "CLOSE_UNCERTAIN")));
    }

    @Transactional
    public void recordOrderCloseExhausted(UUID orderId, String orderNo) {
        requireBusinessIdentity(orderId, orderNo);
        record(new AnomalyCommand(
                "ORDER_CLOSE_EXHAUSTED", null, orderId, null, null,
                Map.of("reason", "MAXIMUM_ATTEMPTS")));
    }

    @Transactional
    public void recordPaymentQueryExhausted(
            PaymentProvider provider,
            UUID orderId,
            UUID transactionId,
            String transactionNo) {
        recordPaymentQueryExhausted(
                provider, orderId, null, transactionId, transactionNo);
    }

    @Transactional
    public void recordPaymentQueryExhausted(
            PaymentProvider provider,
            UUID orderId,
            UUID rechargeOrderId,
            UUID transactionId,
            String transactionNo) {
        requirePaymentIdentity(orderId, rechargeOrderId, transactionId, transactionNo);
        record(new AnomalyCommand(
                "PAYMENT_QUERY_EXHAUSTED", provider, orderId, transactionId, null,
                Map.of("reason", "MAXIMUM_ATTEMPTS")));
    }

    @Transactional
    public AnomalyView record(AnomalyCommand command) {
        AnomalyInsert insert = insertion(command);
        ValidatedAnomaly validated = insert.value();
        repository.insert(row(insert));
        return repository.findActiveByFingerprint(insert.fingerprint())
                .map(this::anomaly)
                .orElseGet(() -> find(insert.id()));
    }

    @Transactional
    public List<UUID> recordAll(List<AnomalyCommand> commands) {
        if (commands == null) {
            throw new IllegalArgumentException("invalid anomaly commands");
        }
        return repository.insertAll(commands.stream()
                .map(this::insertion)
                .map(this::row)
                .toList());
    }

    public PageResult<AnomalyView> list(AnomalyFilters filters) {
        AnomalyFilters valid = validate(filters);
        PaymentAnomalyRepository.PageRows result = repository.list(
                new PaymentAnomalyRepository.Query(
                        normalizeFilterType(valid.anomalyType()),
                        normalizeStatus(valid.status(), true),
                        valid.provider() == null ? null : valid.provider().name(),
                        valid.from(), valid.to(), valid.orderId(), sort(valid.sort()),
                        direction(valid.direction()), valid.size(),
                        Math.multiplyExact(valid.page(), valid.size())));
        return new PageResult<>(
                new ArrayList<>(result.items().stream().map(this::anomaly).toList()),
                result.total(), valid.page(), valid.size());
    }

    @Transactional
    public AnomalyView update(
            UUID id, UpdateAnomalyCommand command, UUID actorUserId) {
        if (id == null || actorUserId == null || command == null
                || command.expectedLockVersion() < 0) {
            throw invalid("invalid_payment_anomaly_update");
        }
        String newStatus = normalizeStatus(command.status(), false);
        String note = normalizedNote(command.resolutionNote());
        AnomalyView before = repository.lockById(id)
                .map(this::anomaly)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "payment_anomaly_not_found"));
        if (before.lockVersion() != command.expectedLockVersion()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "payment_anomaly_version_conflict");
        }
        validateTransition(before.status(), newStatus, command.assignee(), note);
        int updated = repository.updateState(
                id, command.expectedLockVersion(), newStatus, command.assignee(), note);
        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "payment_anomaly_version_conflict");
        }
        int audited = repository.auditUpdate(
                actorUserId, id,
                text(before.orderId()), text(before.paymentTransactionId()),
                text(before.refundId()), before.status(), newStatus,
                text(command.assignee()));
        if (audited != 1) {
            throw invalid("invalid_payment_anomaly_actor");
        }
        return find(id);
    }

    private void validateTransition(
            String currentStatus, String newStatus, UUID assignee, String note) {
        boolean investigating = "OPEN".equals(currentStatus)
                && "INVESTIGATING".equals(newStatus);
        boolean resolving = "INVESTIGATING".equals(currentStatus)
                && "RESOLVED".equals(newStatus);
        if (!investigating && !resolving) {
            throw invalid("invalid_payment_anomaly_transition");
        }
        if (assignee == null) {
            throw invalid("invalid_payment_anomaly_assignment");
        }
        if (investigating == (note != null)) {
            throw invalid("invalid_payment_anomaly_resolution");
        }
    }

    private AnomalyView find(UUID id) {
        return repository.findById(id)
                .map(this::anomaly)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "payment_anomaly_not_found"));
    }

    private ValidatedAnomaly validate(AnomalyCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("invalid anomaly command");
        }
        String anomalyType = normalizeType(command.anomalyType(), false);
        ObjectNode detail = objectMapper.createObjectNode();
        Map<String, ?> facts = command.detail() == null ? Map.of() : command.detail();
        if (facts.size() > DETAIL_KEYS.size()) {
            throw new IllegalArgumentException("anomaly detail is invalid");
        }
        facts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> addSafeFact(detail, entry.getKey(), entry.getValue()));
        return new ValidatedAnomaly(
                anomalyType,
                command.provider() == null ? null : command.provider().name(),
                command.orderId(), command.paymentTransactionId(), command.refundId(), detail);
    }

    private AnomalyInsert insertion(AnomalyCommand command) {
        ValidatedAnomaly validated = validate(command);
        return new AnomalyInsert(
                UUID.randomUUID(), fingerprint(validated), validated, write(validated.detail()));
    }

    private PaymentAnomalyRepository.InsertRow row(AnomalyInsert insert) {
        ValidatedAnomaly value = insert.value();
        return new PaymentAnomalyRepository.InsertRow(
                insert.id(), insert.fingerprint(), value.anomalyType(), value.provider(),
                value.orderId(), value.transactionId(), value.refundId(), insert.detailJson());
    }

    private void addSafeFact(ObjectNode target, String key, Object value) {
        if (!DETAIL_KEYS.contains(key) || value == null) {
            throw new IllegalArgumentException("anomaly detail is not allowlisted");
        }
        if (value instanceof String text) {
            if (!SAFE_DETAIL_VALUE.matcher(text).matches()) {
                throw new IllegalArgumentException("anomaly detail string is invalid");
            }
            target.put(key, text);
            return;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            target.put(key, ((Number) value).longValue());
            return;
        }
        if (value instanceof Boolean bool) {
            target.put(key, bool);
            return;
        }
        throw new IllegalArgumentException("anomaly detail value is invalid");
    }

    private AnomalyFilters validate(AnomalyFilters value) {
        if (value == null || value.page() < 0 || value.size() < 1 || value.size() > 100
                || (long) value.page() * value.size() > Integer.MAX_VALUE
                || (value.from() != null && value.to() != null
                && !value.from().isBefore(value.to()))) {
            throw invalid("invalid_payment_anomaly_filters");
        }
        return value;
    }

    private String fingerprint(ValidatedAnomaly value) {
        String canonical = String.join("\n",
                value.anomalyType(), value.provider() == null ? "NONE" : value.provider(),
                text(value.orderId()), text(value.transactionId()), text(value.refundId()),
                write(value.detail()));
        return sha256(canonical);
    }

    private PaymentAnomalyRepository.Sort sort(String sort) {
        String requestedSort = sort == null || sort.isBlank() ? "createdAt" : sort.strip();
        PaymentAnomalyRepository.Sort value = SORTS.get(requestedSort);
        if (value == null) {
            throw invalid("invalid_payment_anomaly_sort");
        }
        return value;
    }

    private PaymentAnomalyRepository.Direction direction(String direction) {
        String requestedDirection = direction == null || direction.isBlank()
                ? "DESC" : direction.strip().toUpperCase(Locale.ROOT);
        try {
            return PaymentAnomalyRepository.Direction.valueOf(requestedDirection);
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid_payment_anomaly_sort_direction");
        }
    }

    private AnomalyView anomaly(PaymentAnomalyRepository.AnomalyRow row) {
        return new AnomalyView(
                row.id(), row.fingerprint(), row.anomalyType(),
                row.provider() == null ? null : PaymentProvider.valueOf(row.provider()),
                row.orderId(), row.transactionId(), row.refundId(), row.status(),
                row.assignee(), parse(row.detailJson()), row.resolutionNote(),
                row.resolvedAt(), row.createdAt(), row.updatedAt(), row.lockVersion());
    }

    private String normalizeType(String value, boolean optional) {
        if (value == null || value.isBlank()) {
            if (optional) return null;
            throw new IllegalArgumentException("invalid anomaly type");
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!TYPE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid anomaly type");
        }
        return normalized;
    }

    private String normalizeFilterType(String value) {
        try {
            return normalizeType(value, true);
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid_payment_anomaly_type");
        }
    }

    private String normalizeStatus(String value, boolean optional) {
        if (value == null || value.isBlank()) {
            if (optional) return null;
            throw invalid("invalid_payment_anomaly_status");
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw invalid("invalid_payment_anomaly_status");
        }
        return normalized;
    }

    private String normalizedNote(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > 1000) {
            throw invalid("invalid_payment_anomaly_resolution_note");
        }
        return normalized;
    }

    private void requireBusinessIdentity(UUID orderId, String identifier) {
        if (orderId == null || identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("invalid payment anomaly identity");
        }
    }

    private void requirePaymentIdentity(
            UUID orderId,
            UUID rechargeOrderId,
            UUID transactionId,
            String identifier) {
        if ((orderId == null) == (rechargeOrderId == null)
                || transactionId == null
                || identifier == null
                || identifier.isBlank()) {
            throw new IllegalArgumentException("invalid payment anomaly identity");
        }
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("payment_anomaly_deserialization_failed", exception);
        }
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("payment_anomaly_serialization_failed", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("payment_anomaly_hash_failed", exception);
        }
    }

    private static String text(UUID value) {
        return value == null ? "NONE" : value.toString();
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public record AnomalyCommand(
            String anomalyType,
            PaymentProvider provider,
            UUID orderId,
            UUID paymentTransactionId,
            UUID refundId,
            Map<String, ?> detail) {
    }

    public record AnomalyFilters(
            int page,
            int size,
            String anomalyType,
            String status,
            PaymentProvider provider,
            Instant from,
            Instant to,
            UUID orderId,
            String sort,
            String direction) {
    }

    public record UpdateAnomalyCommand(
            String status,
            UUID assignee,
            String resolutionNote,
            long expectedLockVersion) {
    }

    public record AnomalyView(
            UUID id,
            String fingerprint,
            String anomalyType,
            PaymentProvider provider,
            UUID orderId,
            UUID paymentTransactionId,
            UUID refundId,
            String status,
            UUID assignee,
            JsonNode detail,
            String resolutionNote,
            Instant resolvedAt,
            Instant createdAt,
            Instant updatedAt,
            long lockVersion) {
    }

    public record PageResult<T>(List<T> items, long total, int page, int size) {
    }

    private record ValidatedAnomaly(
            String anomalyType,
            String provider,
            UUID orderId,
            UUID transactionId,
            UUID refundId,
            ObjectNode detail) {
    }

    private record AnomalyInsert(
            UUID id, String fingerprint, ValidatedAnomaly value, String detailJson) {
    }
}
