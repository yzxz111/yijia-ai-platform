package com.yuzhihuihe.apiplatform.platform.notification;

import java.sql.Time;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class NotificationPreferenceRepository {

    private final JdbcTemplate jdbc;

    NotificationPreferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void ensureDefaults(UUID tenantId, UUID userId) {
        // 分类清单必须与 NotificationTypeRegistry.Category 及 V70 的
        // ck_notification_preference_category 保持一致：漏掉一个分类会让读过通知偏好页的
        // 用户缺少该分类的偏好行，而 NotificationSmsDispatcher.preference() 在缺行时
        // 回退为「不发短信」，于是缺行会静默地变成「收不到通知」。
        jdbc.update("""
                INSERT INTO platform.notification_preference(
                    tenant_id,user_id,category,channel,enabled,quiet_start,quiet_end,timezone)
                SELECT app_user.tenant_id,app_user.id,category.code,channel.code,
                    CASE WHEN channel.code='IN_APP' THEN true
                         WHEN category.code='SECURITY' THEN true
                         WHEN category.code='OPERATIONS' THEN false
                         ELSE app_user.sms_notifications_enabled END,
                    TIME '22:00',TIME '08:00','Asia/Shanghai'
                FROM platform.app_user
                CROSS JOIN (VALUES ('SECURITY'),('BILLING'),('USAGE'),('OPERATIONS'),('SUPPORT'),
                                   ('SUBSCRIPTION')) category(code)
                CROSS JOIN (VALUES ('IN_APP'),('SMS')) channel(code)
                WHERE app_user.tenant_id=? AND app_user.id=?
                ON CONFLICT (user_id,category,channel) DO NOTHING
                """, tenantId, userId);
    }

    List<PreferenceRow> load(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT category,channel,enabled,quiet_start,quiet_end,timezone
                FROM platform.notification_preference
                WHERE tenant_id=? AND user_id=?
                ORDER BY CASE category WHEN 'SECURITY' THEN 1 WHEN 'BILLING' THEN 2
                    WHEN 'USAGE' THEN 3 WHEN 'OPERATIONS' THEN 4
                    WHEN 'SUBSCRIPTION' THEN 5 ELSE 6 END,
                    CASE channel WHEN 'IN_APP' THEN 1 ELSE 2 END
                """, (result, row) -> new PreferenceRow(
                        result.getString("category"), result.getString("channel"),
                        result.getBoolean("enabled"), result.getTime("quiet_start").toLocalTime(),
                        result.getTime("quiet_end").toLocalTime(), result.getString("timezone")),
                tenantId, userId);
    }

    void update(
            UUID tenantId,
            UUID userId,
            String category,
            String channel,
            boolean enabled,
            LocalTime quietStart,
            LocalTime quietEnd,
            String timezone) {
        jdbc.update("""
                UPDATE platform.notification_preference
                SET enabled=?,quiet_start=?,quiet_end=?,timezone=?,updated_at=now()
                WHERE tenant_id=? AND user_id=? AND category=? AND channel=?
                """, enabled, Time.valueOf(quietStart), Time.valueOf(quietEnd), timezone,
                tenantId, userId, category, channel);
    }

    int unsubscribeSms(UUID tenantId, UUID userId) {
        return jdbc.update("""
                UPDATE platform.notification_preference
                SET enabled=false,updated_at=now()
                WHERE tenant_id=? AND user_id=? AND channel='SMS'
                  AND category<>'SECURITY' AND enabled
                """, tenantId, userId);
    }

    void audit(UUID tenantId, UUID userId, String action, int count) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id,actor_user_id,action,resource_type,resource_id,outcome,detail)
                VALUES (?, ?, ?, 'USER_SELF', ?, 'SUCCESS',
                        jsonb_build_object('preferenceCount', ?))
                """, tenantId, userId, action, userId.toString(), count);
    }

    record PreferenceRow(
            String category,
            String channel,
            boolean enabled,
            LocalTime quietStart,
            LocalTime quietEnd,
            String timezone) {
    }
}
