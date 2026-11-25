package com.yuzhihuihe.apiplatform.platform.sms;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminSmsService {

    private static final Set<String> DELIVERY_STATUSES =
            Set.of("PENDING", "SENDING", "SENT", "FAILED", "DEAD");
    private static final Pattern TEMPLATE_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern DELIVERY_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern PHONE_LAST4 = Pattern.compile("[0-9]{4}");
    private static final Pattern SANITIZED_ERROR =
            Pattern.compile("(?:TRANSIENT|PERMANENT):[A-Z0-9_-]{1,128}");

    private final AdminSmsRepository repository;
    private final SmsTemplateActivationService activationService;

    AdminSmsService(
            AdminSmsRepository repository,
            SmsTemplateActivationService activationService) {
        this.repository = repository;
        this.activationService = activationService;
    }

    public SmsTemplateActivationService.ActivationResult activateTemplate(
            SmsTemplateActivationService.ActivationRequest request, UUID actorUserId) {
        return activationService.activate(request, actorUserId);
    }

    public List<TemplateView> listTemplates() {
        return new ArrayList<>(repository.listTemplates().stream().map(row -> new TemplateView(
                row.templateKey(), row.templateCode(), row.templateType(),
                row.integrationStatus(), row.createdAt(), row.updatedAt())).toList());
    }

    public PageResult<DeliveryView> listDeliveries(DeliveryQuery request) {
        if (request == null) {
            throw invalid("invalid_sms_delivery_query");
        }
        PageRequest page = page(request.page(), request.size());
        String status = enumFilter(
                request.status(), DELIVERY_STATUSES, "invalid_sms_delivery_status");
        String templateKey = templateFilter(request.templateKey());
        SearchFilter search = search(request.query());
        SortOrder sort = order(request);
        AdminSmsRepository.PageRows<AdminSmsRepository.DeliveryRow> result =
                repository.listDeliveries(new AdminSmsRepository.DeliveryFilter(
                        status, templateKey,
                        search == null ? null : search.field(),
                        search == null ? null : search.value(),
                        sort.sort(), sort.direction(), page.size(), page.offset()));
        List<DeliveryView> items = new ArrayList<>(
                result.items().stream().map(this::delivery).toList());
        return new PageResult<>(items, result.total(), page.page(), page.size());
    }

    @Transactional
    public DeliveryView retry(UUID deliveryId, RetryRequest request, UUID actorUserId) {
        if (deliveryId == null || actorUserId == null || request == null
                || request.businessNo() == null || request.businessNo().isBlank()) {
            throw invalid("invalid_sms_delivery_retry");
        }
        String businessNo = request.businessNo().strip();
        if (businessNo.length() > 128 || !businessNo.startsWith("sms-retry-")) {
            throw invalid("invalid_sms_delivery_retry");
        }

        repository.lockRetryBusinessNo(businessNo);
        List<AdminSmsRepository.RetryAuditRow> previous = repository.retryAudits(businessNo);
        if (!previous.isEmpty()) {
            UUID originalId;
            try {
                originalId = UUID.fromString(previous.getFirst().resourceId());
            } catch (IllegalArgumentException malformedAudit) {
                throw conflict("sms_delivery_retry_result_unavailable");
            }
            if (!deliveryId.equals(originalId)) {
                throw conflict("sms_delivery_retry_business_no_conflict");
            }
            return findDelivery(deliveryId);
        }

        AdminSmsRepository.RetryTargetRow target = repository.lockRetryTarget(deliveryId);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "sms_delivery_not_found");
        }
        if (!Set.of("FAILED", "DEAD").contains(target.status())) {
            throw conflict("sms_delivery_not_retryable");
        }

        int updated = repository.resetForRetry(deliveryId);
        if (updated != 1) {
            throw conflict("sms_delivery_not_retryable");
        }
        repository.auditRetry(target, actorUserId, deliveryId, businessNo);
        return findDelivery(deliveryId);
    }

    private DeliveryView findDelivery(UUID id) {
        AdminSmsRepository.DeliveryRow row = repository.findDelivery(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "sms_delivery_not_found");
        }
        return delivery(row);
    }

    private DeliveryView delivery(AdminSmsRepository.DeliveryRow result) {
        return new DeliveryView(
                result.id(), result.userId(), result.userNo(), result.userDisplayName(),
                result.templateKey(), result.templateCode(), "*******" + result.phoneLast4(),
                result.status(), result.attemptCount(), result.nextAttemptAt(),
                sanitizedError(result.sanitizedError()), result.sentAt(),
                result.createdAt(), result.updatedAt());
    }

    private String sanitizedError(String value) {
        if (value == null) return null;
        return SANITIZED_ERROR.matcher(value).matches()
                ? value : "PERMANENT:UNCLASSIFIED_PROVIDER_ERROR";
    }

    private PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid("invalid_pagination");
        try {
            return new PageRequest(page, size, Math.multiplyExact(page, size));
        } catch (ArithmeticException overflow) {
            throw invalid("invalid_pagination");
        }
    }

    private SortOrder order(DeliveryQuery request) {
        String sort = blank(request.sort()) ? "createdAt" : request.sort().strip();
        AdminSmsRepository.Sort repositorySort = switch (sort) {
            case "createdAt" -> AdminSmsRepository.Sort.CREATED_AT;
            case "updatedAt" -> AdminSmsRepository.Sort.UPDATED_AT;
            case "status" -> AdminSmsRepository.Sort.STATUS;
            case "attemptCount" -> AdminSmsRepository.Sort.ATTEMPT_COUNT;
            case "nextAttemptAt" -> AdminSmsRepository.Sort.NEXT_ATTEMPT_AT;
            case "templateKey" -> AdminSmsRepository.Sort.TEMPLATE_KEY;
            default -> throw invalid("invalid_sms_delivery_sort");
        };
        String direction = blank(request.direction())
                ? "DESC" : request.direction().strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ASC", "DESC").contains(direction)) {
            throw invalid("invalid_sms_delivery_sort_direction");
        }
        return new SortOrder(repositorySort, direction);
    }

    private String enumFilter(String value, Set<String> allowed, String error) {
        if (blank(value)) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid(error);
        return normalized;
    }

    private String templateFilter(String value) {
        if (blank(value)) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!TEMPLATE_KEY.matcher(normalized).matches()) {
            throw invalid("invalid_sms_template_key");
        }
        return normalized;
    }

    private SearchFilter search(String value) {
        if (blank(value)) return null;
        String normalized = value.strip();
        if (DELIVERY_ID.matcher(normalized).matches()) {
            return new SearchFilter(
                    AdminSmsRepository.SearchField.DELIVERY_ID, UUID.fromString(normalized));
        }
        if (PHONE_LAST4.matcher(normalized).matches()) {
            return new SearchFilter(AdminSmsRepository.SearchField.PHONE_LAST4, normalized);
        }
        String templateKey = normalized.toUpperCase(Locale.ROOT);
        if (TEMPLATE_KEY.matcher(templateKey).matches()) {
            return new SearchFilter(AdminSmsRepository.SearchField.TEMPLATE_KEY, templateKey);
        }
        throw invalid("invalid_sms_delivery_query");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    public record TemplateView(
            String templateKey,
            String templateCode,
            String templateType,
            String integrationStatus,
            Instant createdAt,
            Instant updatedAt) { }

    public record DeliveryView(
            UUID id,
            UUID userId,
            String userNo,
            String userDisplayName,
            String templateKey,
            String templateCode,
            String maskedPhone,
            String status,
            int attemptCount,
            Instant nextAttemptAt,
            String sanitizedError,
            Instant sentAt,
            Instant createdAt,
            Instant updatedAt) { }

    public record DeliveryQuery(
            int page,
            int size,
            String query,
            String status,
            String templateKey,
            String sort,
            String direction) { }

    public record RetryRequest(String businessNo) { }
    public record PageResult<T>(List<T> items, long total, int page, int size) { }

    private record PageRequest(int page, int size, int offset) { }
    private record SearchFilter(AdminSmsRepository.SearchField field, Object value) { }
    private record SortOrder(AdminSmsRepository.Sort sort, String direction) { }
}
