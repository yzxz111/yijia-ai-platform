package com.yuzhihuihe.apiplatform.platform.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationQueryService {

    /**
     * 从 {@link NotificationTypeRegistry.Category} 派生，而不是再抄一份字符串清单：
     * 手抄的清单会在新增分类时静默过滤掉该分类的通知（用户能看到通知中心里有未读，
     * 却筛不出这一类）。
     */
    private static final Set<String> CATEGORIES = Arrays.stream(
                    NotificationTypeRegistry.Category.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    private final NotificationQueryRepository repository;
    private final Clock clock;

    NotificationQueryService(NotificationQueryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public NotificationPage list(UUID tenantId, UUID userId, String category, Boolean read,
            int page, int size) {
        requireIdentity(tenantId, userId);
        if (page < 0) throw invalid("invalid_notification_page");
        if (size < 1 || size > 100) throw invalid("invalid_notification_page_size");
        if (page > Integer.MAX_VALUE / size) throw invalid("invalid_notification_page");
        String normalizedCategory = category(category);

        NotificationQueryRepository.NotificationPageRows rows = repository.list(
                tenantId, userId, normalizedCategory, read, size, Math.multiplyExact(page, size));
        List<NotificationView> items = new ArrayList<>(rows.items().size());
        rows.items().forEach(row -> items.add(view(row)));
        return new NotificationPage(items, rows.total(), page, size);
    }

    public long unreadCount(UUID tenantId, UUID userId) {
        requireIdentity(tenantId, userId);
        return repository.unreadCount(tenantId, userId);
    }

    @Transactional
    public void markRead(UUID tenantId, UUID userId, UUID notificationId) {
        requireMutation(tenantId, userId, notificationId);
        repository.markRead(tenantId, userId, notificationId, clock.instant());
    }

    @Transactional
    public void markAllRead(UUID tenantId, UUID userId) {
        requireIdentity(tenantId, userId);
        repository.markAllRead(tenantId, userId, clock.instant());
    }

    @Transactional
    public void archive(UUID tenantId, UUID userId, UUID notificationId) {
        requireMutation(tenantId, userId, notificationId);
        repository.archive(tenantId, userId, notificationId, clock.instant());
    }

    private static NotificationView view(NotificationQueryRepository.NotificationRow row) {
        return new NotificationView(
                row.id(), row.category(), row.eventType(), row.title(), row.summary(),
                row.aggregateType(), row.aggregateId(), row.readAt(), row.archivedAt(),
                row.createdAt());
    }

    private static String category(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(normalized)) throw invalid("invalid_notification_category");
        return normalized;
    }

    private static void requireMutation(UUID tenantId, UUID userId, UUID notificationId) {
        requireIdentity(tenantId, userId);
        if (notificationId == null) throw invalid("invalid_notification_id");
    }

    private static void requireIdentity(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication is required");
        }
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public record NotificationPage(List<NotificationView> items, long total, int page, int size) { }

    public record NotificationView(
            UUID id,
            String category,
            String eventType,
            String title,
            String summary,
            String aggregateType,
            String aggregateId,
            Instant readAt,
            Instant archivedAt,
            Instant createdAt) { }
}
