package com.yuzhihuihe.apiplatform.platform.notification;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.sms.SmsNotificationService;

@Service
public class NotificationSmsDispatcher {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime DEFAULT_START = LocalTime.of(22, 0);
    private static final LocalTime DEFAULT_END = LocalTime.of(8, 0);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SmsNotificationService sms;
    private final Clock clock;

    @Autowired
    public NotificationSmsDispatcher(
            JdbcTemplate jdbc, ObjectMapper mapper,
            ObjectProvider<SmsNotificationService> smsProvider, Clock clock) {
        this(jdbc, mapper, smsProvider.getIfAvailable(), clock);
    }

    public NotificationSmsDispatcher(
            JdbcTemplate jdbc, ObjectMapper mapper, SmsNotificationService sms, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.sms = sms;
        this.clock = clock;
    }

    @Transactional
    public void dispatch(UUID eventId) {
        Event event = load(eventId);
        if (hasDecision(event)) return;
        NotificationTypeRegistry.TypeDefinition type = NotificationTypeRegistry.require(event.eventType());
        if (type.smsTemplateKey() == null) {
            suppress(event, "NO_SMS_CHANNEL");
            return;
        }
        Preference preference = preference(event);
        if (!type.mandatorySms() && !enabled(type.category(), preference)) {
            suppress(event, "PREFERENCE_DISABLED");
            return;
        }
        SmsTemplate template = jdbc.queryForObject(
                "SELECT integration_status, variable_names::text FROM platform.sms_template WHERE template_key = ?",
                (result, row) -> new SmsTemplate(result.getString(1), variableNames(result.getString(2))),
                type.smsTemplateKey());
        if (!"ACTIVE".equals(template.status())) {
            suppress(event, "TEMPLATE_INACTIVE");
            return;
        }
        Map<String, Object> smsVariables = new LinkedHashMap<>();
        for (String variableName : template.variableNames()) {
            if (!event.payload().containsKey(variableName)) {
                suppress(event, "TEMPLATE_VARIABLES_MISSING");
                return;
            }
            smsVariables.put(variableName, event.payload().get(variableName));
        }
        Instant nextAttemptAt = type.mandatorySms() ? clock.instant() : quietEnd(preference);
        UUID smsDeliveryId = sms == null ? null : sms.enqueueControlled(
                event.tenantId(), event.userId(), type.smsTemplateKey(), smsVariables,
                "notification-event:" + event.id(), nextAttemptAt);
        if (smsDeliveryId == null) {
            suppress(event, "NO_VERIFIED_PHONE");
            return;
        }
        jdbc.update("""
                INSERT INTO platform.notification_channel_delivery(
                    id, event_id, tenant_id, user_id, channel, status,
                    sms_delivery_id, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SMS', 'PENDING', ?, ?, ?, ?)
                ON CONFLICT (event_id, user_id, channel) DO NOTHING
                """, UUID.randomUUID(), event.id(), event.tenantId(), event.userId(), smsDeliveryId,
                Timestamp.from(nextAttemptAt), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
    }

    private boolean enabled(NotificationTypeRegistry.Category category, Preference preference) {
        if (category == NotificationTypeRegistry.Category.OPERATIONS) {
            return Boolean.TRUE.equals(preference.categoryEnabled());
        }
        return preference.globalEnabled() && Boolean.TRUE.equals(preference.categoryEnabled());
    }

    private Instant quietEnd(Preference preference) {
        ZoneId zone;
        try {
            zone = preference.timezone() == null ? DEFAULT_ZONE : ZoneId.of(preference.timezone());
        } catch (Exception invalidZone) {
            zone = DEFAULT_ZONE;
        }
        LocalTime start = preference.quietStart() == null ? DEFAULT_START : preference.quietStart();
        LocalTime end = preference.quietEnd() == null ? DEFAULT_END : preference.quietEnd();
        ZonedDateTime now = clock.instant().atZone(zone);
        LocalTime local = now.toLocalTime();
        boolean quiet = start.equals(end) || (start.isBefore(end)
                ? !local.isBefore(start) && local.isBefore(end)
                : !local.isBefore(start) || local.isBefore(end));
        if (!quiet) return clock.instant();
        ZonedDateTime candidate = now.with(end);
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
        return candidate.toInstant();
    }

    private Preference preference(Event event) {
        return jdbc.query("""
                SELECT app.sms_notifications_enabled, preference.enabled,
                       preference.quiet_start, preference.quiet_end, preference.timezone
                FROM platform.app_user app
                LEFT JOIN platform.notification_preference preference
                  ON preference.user_id = app.id AND preference.category = ? AND preference.channel = 'SMS'
                WHERE app.tenant_id = ? AND app.id = ?
                """, (result, row) -> new Preference(
                result.getBoolean(1), (Boolean) result.getObject(2),
                localTime(result.getTime(3)), localTime(result.getTime(4)), result.getString(5)),
                event.category().name(), event.tenantId(), event.userId()).stream().findFirst()
                .orElse(new Preference(false, null, null, null, null));
    }

    private LocalTime localTime(Time value) { return value == null ? null : value.toLocalTime(); }

    private void suppress(Event event, String reason) {
        jdbc.update("""
                INSERT INTO platform.notification_channel_delivery(
                    id,event_id,tenant_id,user_id,channel,status,suppression_reason,
                    next_attempt_at,created_at,updated_at)
                VALUES (?,?,?,?,'SMS','SUPPRESSED',?,?,?,?)
                ON CONFLICT (event_id,user_id,channel) DO NOTHING
                """, UUID.randomUUID(), event.id(), event.tenantId(), event.userId(), reason,
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
    }

    private Event load(UUID id) {
        return jdbc.query("SELECT id,tenant_id,user_id,event_type,category,payload::text FROM platform.notification_event WHERE id=? FOR UPDATE",
                (rs, row) -> new Event(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4),
                        NotificationTypeRegistry.Category.valueOf(rs.getString(5)), payload(rs.getString(6))), id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("notification_event_not_found"));
    }

    private boolean hasDecision(Event event) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.notification_channel_delivery
                    WHERE event_id=? AND user_id=? AND channel='SMS')
                """, Boolean.class, event.id(), event.userId()));
    }

    private Map<String, Object> payload(String json) {
        try { return mapper.readValue(json, new TypeReference<Map<String, Object>>() { }); }
        catch (Exception error) { throw new IllegalStateException("invalid_notification_payload", error); }
    }

    private List<String> variableNames(String json) {
        try { return mapper.readValue(json, new TypeReference<List<String>>() { }); }
        catch (Exception error) { throw new IllegalStateException("invalid_sms_template_variable_names", error); }
    }

    private record Event(UUID id, UUID tenantId, UUID userId, String eventType,
                         NotificationTypeRegistry.Category category, Map<String, Object> payload) { }
    private record SmsTemplate(String status, List<String> variableNames) { }
    private record Preference(boolean globalEnabled, Boolean categoryEnabled,
                              LocalTime quietStart, LocalTime quietEnd, String timezone) { }
}
