package com.yuzhihuihe.apiplatform.platform.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationBroadcastService {
    private static final Set<String> STATUSES = Set.of(
            "DRAFT", "SCHEDULED", "SENDING", "COMPLETED", "CANCELED");

    private final NotificationBroadcastRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    NotificationBroadcastService(
            NotificationBroadcastRepository repository, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public BroadcastView create(CreateCommand command, UUID actor) {
        UUID id = UUID.randomUUID();
        ValidCommand valid = validate(command.title(), command.body(), command.maintainTime(),
                businessNo(command.providerBusinessNo(), id), command.tenantId(),
                command.audienceKind(), command.userIds());
        validateAudience(valid);
        executeBusinessNoWrite(() -> repository.insertBroadcast(
                id, valid.title(), valid.body(), valid.maintainTime(), valid.providerBusinessNo(),
                valid.audienceKind().name(), valid.tenantId(), audienceJson(valid.userIds()), actor,
                now(), now()));
        audit(id, valid.tenantId(), actor, "CREATED", "DRAFT", 0);
        return detail(id);
    }

    static String businessNo(String requested, UUID id) {
        return requested == null || requested.isBlank() ? "broadcast-" + id : requested;
    }

    private void executeBusinessNoWrite(Runnable write) {
        try {
            write.run();
        } catch (DataIntegrityViolationException error) {
            if (isBusinessNoConflict(error)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "notification_broadcast_business_no_conflict", error);
            }
            throw error;
        }
    }

    private static boolean isBusinessNoConflict(DataIntegrityViolationException error) {
        String message = error.getMostSpecificCause().getMessage();
        return message != null
                && message.contains("uq_notification_broadcast_provider_business_no");
    }

    @Transactional
    public BroadcastView edit(UUID id, EditCommand command, UUID actor) {
        BroadcastRow current = lock(id);
        requireStatus(current, "DRAFT", "broadcast_not_editable");
        ValidCommand valid = validate(command.title(), command.body(), command.maintainTime(),
                command.providerBusinessNo(), command.tenantId(), command.audienceKind(),
                command.userIds());
        validateAudience(valid);
        executeBusinessNoWrite(() -> repository.updateBroadcast(
                id, valid.title(), valid.body(), valid.maintainTime(), valid.providerBusinessNo(),
                valid.audienceKind().name(), valid.tenantId(), audienceJson(valid.userIds()), now()));
        audit(id, valid.tenantId(), actor, "EDITED", "DRAFT", 0);
        return detail(id);
    }

    @Transactional
    public BroadcastView schedule(UUID id, Instant scheduledAt, UUID actor) {
        BroadcastRow row = lock(id);
        requireStatus(row, "DRAFT", "invalid_broadcast_transition");
        requireFutureSchedule(scheduledAt);
        return freezeRecipients(id, row, scheduledAt, actor, "SCHEDULED");
    }

    @Transactional
    public BroadcastView publish(UUID id, UUID actor) {
        BroadcastRow row = lock(id);
        requireStatus(row, "DRAFT", "invalid_broadcast_transition");
        return freezeRecipients(id, row, clock.instant(), actor, "PUBLISHED");
    }

    private BroadcastView freezeRecipients(
            UUID id, BroadcastRow row, Instant scheduledAt, UUID actor, String auditAction) {
        List<NotificationBroadcastRepository.UserScopeRow> recipients = resolveActiveRecipients(row);
        if (row.audienceKind() == AudienceKind.USER_IDS
                && recipients.size() != row.userIds().size()) {
            throw invalid("invalid_broadcast_audience");
        }
        for (NotificationBroadcastRepository.UserScopeRow recipient : recipients) {
            repository.insertRecipient(
                    UUID.randomUUID(), id, recipient.tenantId(), recipient.userId(),
                    scheduledAt, now());
        }
        repository.markScheduled(id, scheduledAt, now());
        audit(id, row.tenantId(), actor, auditAction, "SCHEDULED", recipients.size());
        return detail(id);
    }

    @Transactional
    public BroadcastView pause(UUID id, UUID actor) {
        BroadcastRow row = lock(id);
        if (!Set.of("SCHEDULED", "SENDING").contains(row.status()) || row.pausedAt() != null) {
            throw invalid("invalid_broadcast_transition");
        }
        repository.pause(id, now(), now());
        repository.resetProcessingRecipients(id);
        audit(id, row.tenantId(), actor, "PAUSED", row.status(), recipientCount(id));
        return detail(id);
    }

    @Transactional
    public BroadcastView resume(UUID id, Instant scheduledAt, UUID actor) {
        BroadcastRow row = lock(id);
        if (!Set.of("SCHEDULED", "SENDING").contains(row.status()) || row.pausedAt() == null) {
            throw invalid("invalid_broadcast_transition");
        }
        requireFutureSchedule(scheduledAt);
        repository.resume(id, scheduledAt, now());
        repository.reschedulePendingRecipients(id, scheduledAt);
        audit(id, row.tenantId(), actor, "RESUMED", row.status(), recipientCount(id));
        return detail(id);
    }

    @Transactional
    public BroadcastView cancel(UUID id, UUID actor) {
        BroadcastRow row = lock(id);
        if (!Set.of("DRAFT", "SCHEDULED").contains(row.status())) {
            throw invalid("invalid_broadcast_transition");
        }
        repository.cancel(id, now());
        repository.cancelPendingRecipients(id, now());
        audit(id, row.tenantId(), actor, "CANCELED", "CANCELED", recipientCount(id));
        return detail(id);
    }

    public PageResult list(int page, int size, String status) {
        if (page < 0 || size < 1 || size > 100) throw invalid("invalid_pagination");
        String normalized = status == null || status.isBlank()
                ? null : status.strip().toUpperCase(Locale.ROOT);
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw invalid("invalid_broadcast_status");
        }
        NotificationBroadcastRepository.BroadcastPageRows rows = repository.list(
                normalized, size, Math.multiplyExact(page, size));
        List<BroadcastView> items = new ArrayList<>(rows.items().size());
        rows.items().forEach(row -> items.add(view(row)));
        return new PageResult(items, rows.total(), page, size);
    }

    public BroadcastView detail(UUID id) {
        return repository.findDetail(id).map(this::view)
                .orElseThrow(() -> notFound("notification_broadcast_not_found"));
    }

    private BroadcastView view(NotificationBroadcastRepository.BroadcastViewRow row) {
        return new BroadcastView(
                row.id(), row.eventType(), row.title(), row.body(), row.maintainTime(),
                row.providerBusinessNo(), row.tenantId(), AudienceKind.valueOf(row.audienceType()),
                row.status(), row.scheduledAt(), row.paused(), row.recipientCount());
    }

    private BroadcastRow lock(UUID id) {
        NotificationBroadcastRepository.BroadcastLockRow row = repository.lock(id)
                .orElseThrow(() -> notFound("notification_broadcast_not_found"));
        return new BroadcastRow(
                row.id(), row.status(), row.tenantId(), AudienceKind.valueOf(row.audienceType()),
                userIds(row.audienceFilter()), row.pausedAt());
    }

    private List<NotificationBroadcastRepository.UserScopeRow> resolveActiveRecipients(
            BroadcastRow row) {
        return repository.findActiveRecipients(
                row.tenantId(), row.audienceKind() == AudienceKind.USER_IDS, row.userIds());
    }

    private void validateAudience(ValidCommand command) {
        if (command.audienceKind() == AudienceKind.USER_IDS) {
            BroadcastRow candidate = new BroadcastRow(null, "DRAFT", command.tenantId(),
                    command.audienceKind(), command.userIds(), null);
            if (resolveActiveRecipients(candidate).size() != command.userIds().size()) {
                throw invalid("invalid_broadcast_audience");
            }
        }
    }

    private ValidCommand validate(String title, String body, String maintainTime,
            String providerBusinessNo, UUID tenantId, AudienceKind kind, List<UUID> ids) {
        String validTitle = text(title, 200, "invalid_broadcast_title");
        String validBody = text(body, 1000, "invalid_broadcast_body");
        String validMaintainTime = text(maintainTime, 200, "invalid_broadcast_maintain_time");
        String businessNo = text(providerBusinessNo, 128, "invalid_provider_business_no");
        if (!businessNo.matches("[A-Za-z0-9._:-]+")) {
            throw invalid("invalid_provider_business_no");
        }
        if (kind == null) throw invalid("invalid_broadcast_audience");
        if (ids != null && ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("invalid_broadcast_audience");
        }
        List<UUID> userIds = ids == null ? List.of() : List.copyOf(new LinkedHashSet<>(ids));
        if (kind == AudienceKind.ALL_ACTIVE_USERS && !userIds.isEmpty()
                || kind == AudienceKind.USER_IDS && userIds.isEmpty()) {
            throw invalid("invalid_broadcast_audience");
        }
        return new ValidCommand(validTitle, validBody, validMaintainTime, businessNo,
                tenantId, kind, userIds);
    }

    private String text(String value, int max, String reason) {
        if (value == null || value.isBlank()) throw invalid(reason);
        String normalized = value.strip();
        if (normalized.length() > max || normalized.indexOf('<') >= 0
                || normalized.indexOf('>') >= 0 || normalized.chars()
                .anyMatch(character -> Character.isISOControl(character)
                        && character != '\n' && character != '\r' && character != '\t')) {
            throw invalid(reason);
        }
        return normalized;
    }

    private void requireStatus(BroadcastRow row, String expected, String reason) {
        if (!expected.equals(row.status())) throw invalid(reason);
    }

    private void requireFutureSchedule(Instant scheduledAt) {
        if (scheduledAt == null || !scheduledAt.isAfter(clock.instant())) {
            throw invalid("invalid_broadcast_schedule");
        }
    }

    private void audit(UUID id, UUID tenantId, UUID actor, String action,
            String status, long recipientCount) {
        repository.insertAudit(id, tenantId, actor, action, status, recipientCount);
    }

    private long recipientCount(UUID id) {
        return repository.recipientCount(id);
    }

    private String audienceJson(List<UUID> ids) {
        try {
            return mapper.writeValueAsString(java.util.Map.of("userIds", ids));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("broadcast_audience_serialization_failed", error);
        }
    }

    private List<UUID> userIds(String json) {
        try {
            var node = mapper.readTree(json).path("userIds");
            List<UUID> result = new ArrayList<>();
            node.forEach(value -> result.add(UUID.fromString(value.asText())));
            return List.copyOf(result);
        } catch (Exception error) {
            throw new IllegalStateException("broadcast_audience_deserialization_failed", error);
        }
    }

    private Instant now() { return clock.instant(); }
    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
    private ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }

    public enum AudienceKind { ALL_ACTIVE_USERS, USER_IDS }
    public record CreateCommand(String title, String body, String maintainTime,
            String providerBusinessNo, UUID tenantId, AudienceKind audienceKind,
            List<UUID> userIds) { }
    public record EditCommand(String title, String body, String maintainTime,
            String providerBusinessNo, UUID tenantId, AudienceKind audienceKind,
            List<UUID> userIds) { }
    public record BroadcastView(UUID id, String eventType, String title, String body,
            String maintainTime, String providerBusinessNo, UUID tenantId,
            AudienceKind audienceKind, String status, Instant scheduledAt,
            boolean paused, long recipientCount) { }
    public record PageResult(List<BroadcastView> items, long total, int page, int size) { }
    private record ValidCommand(String title, String body, String maintainTime,
            String providerBusinessNo, UUID tenantId, AudienceKind audienceKind,
            List<UUID> userIds) { }
    private record BroadcastRow(UUID id, String status, UUID tenantId,
            AudienceKind audienceKind, List<UUID> userIds, Instant pausedAt) { }
}
