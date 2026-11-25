package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;

class NotificationPreferenceServiceTest {

    private final JdbcTemplate jdbc = NotificationTestDatabase.jdbc();
    private final NotificationPreferenceService service = transactionalService();
    private UUID tenant;
    private UUID user;

    @BeforeEach
    void reset() {
        NotificationTestDatabase.reset();
        tenant = UUID.randomUUID();
        user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id,tenant_id,email,password_hash,display_name,sms_notifications_enabled)
                VALUES (?,?,?,'x','user',true)
                """, user, tenant, user + "@example.invalid");
    }

    @Test
    void insertsCompleteDefaultsAndPersistsCrossMidnightUpdateWithSafeAudit() {
        var defaults = service.list(tenant, user);
        // 从枚举派生而不是写死 10：写死会让「新增分类」变成一条需要手工同步的断言，
        // 而它的真实语义是「每个分类 × 每个渠道各一行」。
        assertThat(defaults).hasSize(
                NotificationTypeRegistry.Category.values().length * 2);
        assertThat(defaults).hasSize(12);
        assertThat(preference(defaults, "SECURITY", "SMS").enabled()).isTrue();
        assertThat(preference(defaults, "OPERATIONS", "SMS").enabled()).isFalse();
        // 新增的 SUBSCRIPTION 分类也必须拿到行——缺行会让派发器把它当作「不发短信」。
        assertThat(preference(defaults, "SUBSCRIPTION", "IN_APP").enabled()).isTrue();
        assertThat(preference(defaults, "SUBSCRIPTION", "SMS").enabled()).isTrue();

        service.update(tenant, user, List.of(new NotificationPreferenceService.PreferenceUpdate(
                "OPERATIONS", "SMS", true, "23:30", "07:15", "Asia/Shanghai")));

        var updated = preference(service.list(tenant, user), "OPERATIONS", "SMS");
        assertThat(updated.enabled()).isTrue();
        assertThat(updated.quietStart()).isEqualTo(LocalTime.of(23, 30));
        assertThat(updated.quietEnd()).isEqualTo(LocalTime.of(7, 15));
        assertThat(updated.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE tenant_id=? AND actor_user_id=?
                  AND action='USER_NOTIFICATION_PREFERENCES_UPDATED'
                  AND resource_type='USER_SELF'
                  AND jsonb_exists(detail, 'preferenceCount')
                  AND NOT jsonb_exists_any(detail, array['phone','phoneCiphertext','encryptionKey'])
                """, Integer.class, tenant, user)).isOne();
    }

    @Test
    void rejectsInvariantsAndRollsBackEarlierUpdateInSameBatch() {
        service.list(tenant, user);
        var validFirst = new NotificationPreferenceService.PreferenceUpdate(
                "BILLING", "SMS", false, "22:00", "08:00", "Asia/Shanghai");
        var invalidSecond = new NotificationPreferenceService.PreferenceUpdate(
                "SECURITY", "SMS", false, "22:00", "08:00", "Asia/Shanghai");

        assertThatThrownBy(() -> service.update(tenant, user, List.of(validFirst, invalidSecond)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("security_sms_required");
        assertThat(enabled("BILLING", "SMS")).isTrue();
        assertThat(auditCount()).isZero();

        assertInvalid(new NotificationPreferenceService.PreferenceUpdate(
                "USAGE", "IN_APP", false, "22:00", "08:00", "Asia/Shanghai"));
        assertInvalid(new NotificationPreferenceService.PreferenceUpdate(
                "SECURITY", "SMS", true, "21:00", "08:00", "Asia/Shanghai"));
        assertInvalid(new NotificationPreferenceService.PreferenceUpdate(
                "BILLING", "SMS", true, "22:00", "08:00", "UTC"));
        assertInvalid(new NotificationPreferenceService.PreferenceUpdate(
                "BILLING", "EMAIL", true, "22:00", "08:00", "Asia/Shanghai"));
        assertInvalid(new NotificationPreferenceService.PreferenceUpdate(
                "BILLING", "SMS", true, "25:00", "08:00", "Asia/Shanghai"));
        assertInvalid(new NotificationPreferenceService.PreferenceUpdate(
                "BILLING", "SMS", true, "08:00", "08:00", "Asia/Shanghai"));
        assertThat(auditCount()).isZero();
    }

    @Test
    void unsubscribeDisablesOnlyNonSecuritySmsAndRemainsIdempotentWithAudit() {
        service.list(tenant, user);
        jdbc.update("UPDATE platform.notification_preference SET enabled=true WHERE user_id=? AND channel='SMS'",
                user);

        service.unsubscribeSms(tenant, user);
        service.unsubscribeSms(tenant, user);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_preference
                WHERE tenant_id=? AND user_id=? AND channel='SMS' AND category<>'SECURITY' AND enabled
                """, Integer.class, tenant, user)).isZero();
        assertThat(enabled("SECURITY", "SMS")).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE tenant_id=? AND actor_user_id=?
                  AND action='USER_NOTIFICATION_SMS_UNSUBSCRIBED'
                  AND NOT jsonb_exists_any(detail, array['phone','phoneCiphertext','encryptionKey'])
                """, Integer.class, tenant, user)).isEqualTo(2);
    }

    private NotificationPreferenceService transactionalService() {
        var target = new NotificationPreferenceService(
                new NotificationPreferenceRepository(NotificationTestDatabase.jdbc()));
        var transactionManager = new DataSourceTransactionManager(NotificationTestDatabase.dataSource());
        var interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(interceptor);
        return (NotificationPreferenceService) proxy.getProxy();
    }

    private void assertInvalid(NotificationPreferenceService.PreferenceUpdate update) {
        assertThatThrownBy(() -> service.update(tenant, user, List.of(update)))
                .isInstanceOf(ResponseStatusException.class);
    }

    private boolean enabled(String category, String channel) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT enabled FROM platform.notification_preference
                WHERE tenant_id=? AND user_id=? AND category=? AND channel=?
                """, Boolean.class, tenant, user, category, channel));
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM platform.admin_audit_log WHERE actor_user_id=?",
                Integer.class, user);
    }

    private NotificationPreferenceService.PreferenceView preference(
            List<NotificationPreferenceService.PreferenceView> values,
            String category, String channel) {
        return values.stream().filter(value -> value.category().equals(category)
                && value.channel().equals(channel)).findFirst().orElseThrow();
    }
}
