package com.yuzhihuihe.apiplatform.platform.notification;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
public class NotificationPreferenceService {

    /**
     * 从 {@link NotificationTypeRegistry.Category} 派生，而不是再抄一份字符串清单。
     *
     * <p>这个白名单原先手写为 5 个分类，与枚举、数据库 CHECK 约束三处各自维护——
     * 新增分类时只要漏掉其中一处，用户就会在偏好接口上收到 422 或看不到该分类，
     * 而派生的写法让「加枚举值」自动等于「接口接受该值」。
     */
    private static final Set<String> CATEGORIES = Arrays.stream(
                    NotificationTypeRegistry.Category.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> CHANNELS = Set.of("IN_APP", "SMS");
    private static final String TIMEZONE = "Asia/Shanghai";
    private static final LocalTime DEFAULT_QUIET_START = LocalTime.of(22, 0);
    private static final LocalTime DEFAULT_QUIET_END = LocalTime.of(8, 0);

    private final NotificationPreferenceRepository repository;

    NotificationPreferenceService(NotificationPreferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<PreferenceView> list(UUID tenantId, UUID userId) {
        requireIdentity(tenantId, userId);
        ensureDefaults(tenantId, userId);
        return load(tenantId, userId);
    }

    @Transactional
    public List<PreferenceView> update(
            UUID tenantId, UUID userId, List<PreferenceUpdate> updates) {
        requireIdentity(tenantId, userId);
        if (updates == null || updates.isEmpty()) throw invalid("invalid_notification_preferences");
        ensureDefaults(tenantId, userId);

        Set<String> identities = new HashSet<>();
        for (PreferenceUpdate candidate : updates) {
            ValidatedUpdate update = validate(candidate);
            if (!identities.add(update.category() + ':' + update.channel())) {
                throw invalid("duplicate_notification_preference");
            }
            repository.update(
                    tenantId, userId, update.category(), update.channel(), update.enabled(),
                    update.quietStart(), update.quietEnd(), TIMEZONE);
        }
        repository.audit(
                tenantId, userId, "USER_NOTIFICATION_PREFERENCES_UPDATED", updates.size());
        return load(tenantId, userId);
    }

    @Transactional
    public void unsubscribeSms(UUID tenantId, UUID userId) {
        requireIdentity(tenantId, userId);
        ensureDefaults(tenantId, userId);
        int changed = repository.unsubscribeSms(tenantId, userId);
        repository.audit(tenantId, userId, "USER_NOTIFICATION_SMS_UNSUBSCRIBED", changed);
    }

    private void ensureDefaults(UUID tenantId, UUID userId) {
        repository.ensureDefaults(tenantId, userId);
    }

    private List<PreferenceView> load(UUID tenantId, UUID userId) {
        List<NotificationPreferenceRepository.PreferenceRow> rows =
                repository.load(tenantId, userId);
        List<PreferenceView> preferences = new ArrayList<>(rows.size());
        rows.forEach(row -> preferences.add(new PreferenceView(
                row.category(), row.channel(), row.enabled(), row.quietStart(), row.quietEnd(),
                row.timezone())));
        return preferences;
    }

    private ValidatedUpdate validate(PreferenceUpdate value) {
        if (value == null || value.enabled() == null) {
            throw invalid("invalid_notification_preference");
        }
        String category = code(value.category(), CATEGORIES, "invalid_notification_category");
        String channel = code(value.channel(), CHANNELS, "invalid_notification_channel");
        if (!TIMEZONE.equals(value.timezone())) throw invalid("invalid_notification_timezone");
        LocalTime quietStart = time(value.quietStart());
        LocalTime quietEnd = time(value.quietEnd());
        if (quietStart.equals(quietEnd)) {
            throw invalid("invalid_notification_quiet_range");
        }
        if ("IN_APP".equals(channel) && !value.enabled()) {
            throw invalid("in_app_notification_required");
        }
        if ("SECURITY".equals(category)) {
            if ("SMS".equals(channel) && !value.enabled()) {
                throw invalid("security_sms_required");
            }
            if (!DEFAULT_QUIET_START.equals(quietStart) || !DEFAULT_QUIET_END.equals(quietEnd)) {
                throw invalid("security_quiet_behavior_immutable");
            }
        }
        return new ValidatedUpdate(category, channel, value.enabled(), quietStart, quietEnd);
    }

    private static String code(String value, Set<String> allowed, String reason) {
        if (value == null) throw invalid(reason);
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid(reason);
        return normalized;
    }

    private static LocalTime time(String value) {
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw invalid("invalid_notification_quiet_time");
        }
    }

    private static void requireIdentity(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication is required");
        }
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public record PreferenceUpdate(
            String category,
            String channel,
            Boolean enabled,
            String quietStart,
            String quietEnd,
            String timezone) { }

    public record PreferenceView(
            String category,
            String channel,
            boolean enabled,
            LocalTime quietStart,
            LocalTime quietEnd,
            String timezone) { }

    private record ValidatedUpdate(
            String category,
            String channel,
            boolean enabled,
            LocalTime quietStart,
            LocalTime quietEnd) { }
}
