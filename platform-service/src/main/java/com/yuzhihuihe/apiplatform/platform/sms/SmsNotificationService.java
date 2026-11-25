package com.yuzhihuihe.apiplatform.platform.sms;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.SecretEncryptionService;

@Service
@ConditionalOnProperty(prefix = "sms", name = "enabled", havingValue = "true")
public class SmsNotificationService {

    private static final int MAX_VARIABLE_VALUE_LENGTH = 256;
    private static final int MAX_VARIABLE_JSON_BYTES = 2048;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final SmsNotificationRepository repository;
    private final ObjectMapper objectMapper;
    private final SecretEncryptionService encryption;
    private final Clock clock;

    SmsNotificationService(
            SmsNotificationRepository repository,
            ObjectMapper objectMapper,
            SecretEncryptionService encryption,
            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.encryption = encryption;
        this.clock = clock;
    }

    public boolean enqueue(
            UUID tenantId,
            UUID userId,
            String templateKey,
            Map<String, ?> variables,
            String deduplicationKey) {
        requireScope(tenantId, userId);
        Template template = loadTemplate(templateKey, "NOTIFICATION");
        Map<String, Object> safeVariables = validateVariables(template, variables);
        List<Recipient> recipients = repository.notificationRecipients(tenantId, userId).stream()
                .map(row -> new Recipient(row.ciphertext(), row.last4())).toList();
        if (recipients.isEmpty()) {
            return false;
        }
        Recipient recipient = recipients.getFirst();
        return insert(
                tenantId, userId, template, recipient.ciphertext(), recipient.last4(),
                safeVariables, null, deduplicationKey);
    }

    /** 受控的通知中心入口；渠道策略由调度器评估。 */
    public UUID enqueueControlled(
            UUID tenantId,
            UUID userId,
            String templateKey,
            Map<String, ?> variables,
            String deduplicationKey,
            Instant nextAttemptAt) {
        requireScope(tenantId, userId);
        Template template = loadTemplate(templateKey, "NOTIFICATION");
        Map<String, Object> safeVariables = validateVariables(template, variables);
        List<Recipient> recipients = repository.controlledRecipients(tenantId, userId).stream()
                .map(row -> new Recipient(row.ciphertext(), row.last4())).toList();
        if (recipients.isEmpty()) {
            return null;
        }
        Recipient recipient = recipients.getFirst();
        return insertControlled(
                tenantId, userId, template, recipient.ciphertext(), recipient.last4(),
                safeVariables, null, deduplicationKey, nextAttemptAt);
    }

    public UUID enqueueAdminTest(
            String templateKey,
            PhoneProtectionService.ProtectedPhone recipient,
            Map<String, ?> variables,
            String deduplicationKey) {
        Template template = loadTemplate(templateKey);
        Map<String, Object> safeVariables = validateVariables(template, variables);
        if (recipient == null) {
            throw new IllegalArgumentException("invalid_sms_test_recipient");
        }
        Map<String, Object> publicVariables;
        byte[] sensitiveVariables;
        if ("NOTIFICATION".equals(template.type())) {
            publicVariables = safeVariables;
            sensitiveVariables = null;
        } else if ("VERIFICATION".equals(template.type())) {
            publicVariables = Map.of();
            sensitiveVariables = encryption.encrypt(
                    json(safeVariables).getBytes(StandardCharsets.UTF_8));
        } else {
            throw new IllegalArgumentException("sms_template_type_mismatch");
        }
        return insertControlled(
                null, null, template, recipient.ciphertext(), recipient.last4(),
                publicVariables, sensitiveVariables, deduplicationKey, clock.instant());
    }

    public boolean enqueueVerification(
            UUID tenantId,
            UUID userId,
            String templateKey,
            PhoneProtectionService.ProtectedPhone recipient,
            Map<String, ?> sensitiveVariables,
            String deduplicationKey) {
        if ((tenantId == null) != (userId == null)) {
            throw new IllegalArgumentException("invalid_sms_scope");
        }
        Template template = loadTemplate(templateKey, "VERIFICATION");
        Map<String, Object> safeSensitive = validateVariables(template, sensitiveVariables);
        byte[] encryptedVariables = encryption.encrypt(json(safeSensitive).getBytes(StandardCharsets.UTF_8));
        return insert(
                tenantId, userId, template, recipient.ciphertext(), recipient.last4(),
                Map.of(), encryptedVariables, deduplicationKey);
    }

    public boolean notifyPaymentFailed(
            UUID paymentId, UUID tenantId, UUID userId, long amountFen) {
        return enqueue(
                tenantId, userId, "ORDER_PAYMENT_FAILED",
                Map.of("payMoney", yuan(amountFen)), "payment-failed:" + paymentId);
    }

    public boolean notifyOrderTimedOut(
            UUID orderId, UUID tenantId, UUID userId, String orderNo) {
        return enqueue(
                tenantId, userId, "ORDER_TIMED_OUT",
                Map.of("orderNo", orderNo), "order-timeout:" + orderId);
    }

    public boolean notifyRefundSucceeded(
            UUID refundId, UUID tenantId, UUID userId, long amountFen) {
        return enqueue(
                tenantId, userId, "REFUND_SUCCEEDED",
                Map.of("refundMoney", yuan(amountFen)), "refund-completed:" + refundId);
    }

    public boolean notifyRechargeSucceeded(
            String rechargeNo,
            UUID tenantId,
            UUID userId,
            long amountFen,
            long balanceFen) {
        if (rechargeNo == null || !rechargeNo.matches("[A-Za-z0-9_-]{1,64}")
                || amountFen <= 0 || balanceFen < 0) {
            throw new IllegalArgumentException("invalid_recharge_notification");
        }
        return enqueue(
                tenantId, userId, "BALANCE_RECHARGE_SUCCEEDED",
                Map.of("payMoney", yuan(amountFen), "balance", yuan(balanceFen)),
                "recharge-succeeded:" + rechargeNo);
    }

    public boolean notifyGrantActive(UUID grantId) {
        List<GrantNotification> grants = repository.grant(grantId).stream()
                .map(row -> new GrantNotification(
                        row.tenantId(), row.userId(), row.completedAt(),
                        row.amountFen(), row.durationDays()))
                .toList();
        if (grants.isEmpty()) {
            return false;
        }
        GrantNotification grant = grants.getFirst();
        String expireDate = grant.completedAt().atZoneSameInstant(SHANGHAI).toLocalDate()
                .plusDays(grant.durationDays()).format(DateTimeFormatter.ISO_LOCAL_DATE);
        return enqueue(
                grant.tenantId(), grant.userId(), "PACKAGE_PAYMENT_SUCCEEDED",
                Map.of("payMoney", yuan(grant.amountFen()), "expireDate", expireDate),
                "grant-active:" + grantId);
    }

    private String yuan(long amountFen) {
        return BigDecimal.valueOf(amountFen, 2).setScale(2).toPlainString();
    }

    private boolean insert(
            UUID tenantId,
            UUID userId,
            Template template,
            byte[] phoneCiphertext,
            String phoneLast4,
            Map<String, Object> variables,
            byte[] sensitiveVariables,
            String deduplicationKey) {
        if (deduplicationKey == null || deduplicationKey.isBlank()
                || deduplicationKey.length() > 160) {
            throw new IllegalArgumentException("invalid_sms_deduplication_key");
        }
        int inserted = repository.insert(
                UUID.randomUUID(), tenantId, userId, template.key(), template.code(),
                phoneCiphertext, phoneLast4, json(variables), sensitiveVariables,
                deduplicationKey, clock.instant(), clock.instant(), clock.instant());
        return inserted == 1;
    }

    private UUID insertControlled(
            UUID tenantId,
            UUID userId,
            Template template,
            byte[] phoneCiphertext,
            String phoneLast4,
            Map<String, Object> variables,
            byte[] sensitiveVariables,
            String deduplicationKey,
            Instant nextAttemptAt) {
        if (deduplicationKey == null || deduplicationKey.isBlank()
                || deduplicationKey.length() > 160 || nextAttemptAt == null) {
            throw new IllegalArgumentException("invalid_sms_delivery_request");
        }
        UUID id = UUID.randomUUID();
        repository.insertControlled(
                id, tenantId, userId, template.key(), template.code(), phoneCiphertext,
                phoneLast4, json(variables), sensitiveVariables, deduplicationKey,
                nextAttemptAt, clock.instant(), clock.instant());
        return repository.findDeliveryId(deduplicationKey);
    }

    private Template loadTemplate(String templateKey, String requiredType) {
        Template template = loadTemplate(templateKey);
        if (!requiredType.equals(template.type())) {
            throw new IllegalArgumentException("sms_template_type_mismatch");
        }
        return template;
    }

    private Template loadTemplate(String templateKey) {
        if (templateKey == null || !templateKey.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid_sms_template_key");
        }
        List<Template> templates = repository.templates(templateKey).stream()
                .map(row -> new Template(
                        row.key(), row.code(), row.type(),
                        readVariableNames(row.variableNamesJson()), row.status()))
                .toList();
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("sms_template_not_found");
        }
        Template template = templates.getFirst();
        if (!"ACTIVE".equals(template.status())) {
            throw new IllegalStateException("sms_template_not_active");
        }
        return template;
    }

    private Map<String, Object> validateVariables(Template template, Map<String, ?> candidate) {
        if (candidate == null || !candidate.keySet().equals(template.variableNames())) {
            throw new IllegalArgumentException("sms_template_variables_mismatch");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (String name : template.variableNames()) {
            Object value = candidate.get(name);
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException("invalid_sms_template_variable");
            }
            String text = value.toString();
            if (text.isBlank() || text.length() > MAX_VARIABLE_VALUE_LENGTH
                    || text.chars().anyMatch(character -> Character.isISOControl(character))) {
                throw new IllegalArgumentException("invalid_sms_template_variable");
            }
            normalized.put(name, value);
        }
        if (json(normalized).getBytes(StandardCharsets.UTF_8).length > MAX_VARIABLE_JSON_BYTES) {
            throw new IllegalArgumentException("sms_template_variables_too_large");
        }
        return Map.copyOf(normalized);
    }

    private Set<String> readVariableNames(String json) {
        try {
            return Set.copyOf(objectMapper.readValue(json, new TypeReference<List<String>>() { }));
        } catch (Exception error) {
            throw new IllegalStateException("invalid_sms_template_registry", error);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("sms_variable_serialization_failed", error);
        }
    }

    private void requireScope(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new IllegalArgumentException("authenticated_sms_scope_required");
        }
    }

    private record Template(
            String key, String code, String type, Set<String> variableNames, String status) {
    }

    private record Recipient(byte[] ciphertext, String last4) {
    }

    private record GrantNotification(
            UUID tenantId,
            UUID userId,
            OffsetDateTime completedAt,
            long amountFen,
            int durationDays) {
    }
}
