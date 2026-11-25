package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.SecretEncryptionService;
import com.yuzhihuihe.apiplatform.platform.sms.PhoneProtectionService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsNotificationService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsServiceTestFactory;

class NotificationSmsDispatcherTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final DataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        DATA_SOURCE = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(DATA_SOURCE).defaultSchema("platform").schemas("platform")
                .locations("classpath:db/migration").load().migrate();
    }

    private final JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock quietClock = Clock.fixed(Instant.parse("2026-08-08T15:00:00Z"), ZoneOffset.UTC);
    private final SecretEncryptionService encryption = new SecretEncryptionService(
            Base64.getEncoder().encodeToString(new byte[32]));
    private final PhoneProtectionService phones = new PhoneProtectionService(
            encryption, Base64.getEncoder().encodeToString(new byte[32]));

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE platform.notification_channel_delivery, platform.notification_event, "
                + "platform.sms_delivery, platform.notification_preference, platform.app_user CASCADE");
    }

    @Test
    void securitySmsBypassesGlobalPreferenceCategoryPreferenceAndQuietHours() {
        Fixture fixture = fixture(false, "SECURITY", false, "ACCOUNT_FROZEN", "{}");
        activate("ACCOUNT_ANOMALY_FROZEN");

        dispatcher().dispatch(fixture.eventId());

        assertThat(status(fixture.eventId(), "SMS")).isEqualTo(new ChannelStatus("PENDING", null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.sms_delivery", Integer.class)).isOne();
    }

    @Test
    void nonSecuritySmsHonorsOptInAndCrossMidnightQuietHours() {
        Fixture fixture = fixture(true, "BILLING", true, "ORDER_PAYMENT_FAILED", "{\"payMoney\":\"12.00\"}");

        dispatcher().dispatch(fixture.eventId());

        assertThat(status(fixture.eventId(), "SMS")).isEqualTo(new ChannelStatus("PENDING", null));
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM platform.sms_delivery", Instant.class))
                .isEqualTo(Instant.parse("2026-08-09T00:00:00Z"));
    }

    @Test
    void billingSmsRequiresLegacyGlobalPreferenceAsWellAsCategoryPreference() {
        Fixture fixture = fixture(false, "BILLING", true, "ORDER_PAYMENT_FAILED", "{\"payMoney\":\"12.00\"}");

        dispatcher().dispatch(fixture.eventId());

        assertThat(status(fixture.eventId(), "SMS"))
                .isEqualTo(new ChannelStatus("SUPPRESSED", "PREFERENCE_DISABLED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.sms_delivery", Integer.class)).isZero();
    }

    @Test
    void billingSmsSuppressesWhenCategoryPreferenceIsMissing() {
        Fixture fixture = fixture(true, "BILLING", true, "ORDER_PAYMENT_FAILED", "{\"payMoney\":\"12.00\"}");
        jdbc.update("DELETE FROM platform.notification_preference WHERE user_id=(SELECT user_id FROM platform.notification_event WHERE id=?) AND category='BILLING' AND channel='SMS'", fixture.eventId());

        dispatcher().dispatch(fixture.eventId());

        assertThat(status(fixture.eventId(), "SMS"))
                .isEqualTo(new ChannelStatus("SUPPRESSED", "PREFERENCE_DISABLED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.sms_delivery", Integer.class)).isZero();
    }

    @Test
    void noChannelAndInactiveTemplateAreSuppressedAndRetryIsIdempotent() {
        Fixture noChannel = fixture(true, "USAGE", true, "WALLET_USAGE_SETTLED",
                "{\"costMoney\":\"1.00\",\"balance\":\"9.00\"}");
        Fixture inactive = fixture(true, "SUPPORT", true, "SUPPORT_TICKET_COMPLETED",
                "{\"ticketNo\":\"T-1\"}");
        jdbc.update("UPDATE platform.sms_template SET integration_status='PENDING_INTEGRATION' WHERE template_key='SUPPORT_TICKET_COMPLETED'");

        dispatcher().dispatch(noChannel.eventId());
        dispatcher().dispatch(inactive.eventId());
        dispatcher().dispatch(inactive.eventId());

        assertThat(status(noChannel.eventId(), "SMS")).isEqualTo(new ChannelStatus("SUPPRESSED", "NO_SMS_CHANNEL"));
        assertThat(status(inactive.eventId(), "SMS")).isEqualTo(new ChannelStatus("SUPPRESSED", "TEMPLATE_INACTIVE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.notification_channel_delivery", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.sms_delivery", Integer.class)).isZero();
    }

    @Test
    void suppressedDecisionRemainsTerminalAfterPolicyAndTemplateChange() {
        Fixture fixture = fixture(false, "BILLING", false, "BALANCE_LOW", "{\"warnMoney\":\"10.00\"}");

        dispatcher().dispatch(fixture.eventId());
        jdbc.update("UPDATE platform.app_user SET sms_notifications_enabled=true WHERE id=(SELECT user_id FROM platform.notification_event WHERE id=?)", fixture.eventId());
        jdbc.update("UPDATE platform.notification_preference SET enabled=true WHERE user_id=(SELECT user_id FROM platform.notification_event WHERE id=?) AND category='BILLING' AND channel='SMS'", fixture.eventId());
        activate("BALANCE_LOW");
        dispatcher().dispatch(fixture.eventId());

        assertThat(status(fixture.eventId(), "SMS"))
                .isEqualTo(new ChannelStatus("SUPPRESSED", "PREFERENCE_DISABLED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.notification_channel_delivery WHERE event_id=? AND channel='SMS'", Integer.class, fixture.eventId())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.sms_delivery", Integer.class)).isZero();
    }

    @Test
    void accountSecuritySmsFiltersNotificationOnlyMetadataForEmptyProviderTemplates() {
        Map<String, String> types = Map.of(
                "ACCOUNT_FROZEN", "ACCOUNT_ANOMALY_FROZEN",
                "ACCOUNT_BANNED", "ACCOUNT_BANNED",
                "ACCOUNT_UNFROZEN", "ACCOUNT_UNFROZEN");
        java.util.List<Fixture> fixtures = new java.util.ArrayList<>();
        types.forEach((eventType, templateKey) -> {
            activate(templateKey);
            fixtures.add(fixture(false, "SECURITY", true, eventType,
                    "{\"reasonCode\":\"RISK_REVIEW\",\"caseNo\":\"CASE-17\"}"));
        });

        fixtures.forEach(fixture -> dispatcher().dispatch(fixture.eventId()));

        assertThat(jdbc.queryForList("SELECT variables::text FROM platform.sms_delivery ORDER BY template_key", String.class))
                .containsExactly("{}", "{}", "{}");
        assertThat(jdbc.queryForList("SELECT payload::text FROM platform.notification_event ORDER BY event_type", String.class))
                .allSatisfy(payload -> assertThat(payload).contains("reasonCode", "caseNo", "RISK_REVIEW", "CASE-17"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.notification_channel_delivery WHERE channel='SMS' AND status='PENDING'", Integer.class)).isEqualTo(3);
    }

    @Test
    void missingRequiredProviderVariableIsSuppressedDeterministically() {
        Fixture fixture = fixture(false, "SECURITY", true, "RISK_LOGIN", "{}");
        activate("LOCATION_LOGIN_WARNING");

        dispatcher().dispatch(fixture.eventId());

        assertThat(status(fixture.eventId(), "SMS"))
                .isEqualTo(new ChannelStatus("SUPPRESSED", "TEMPLATE_VARIABLES_MISSING"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.sms_delivery", Integer.class)).isZero();
    }

    @Test
    void subscriptionPreChargeNoticeBypassesUserPreferencesAndQuietHours() {
        // 合规底线：扣款前通知不可被用户关闭（微信「预扣费通知」与《消费者权益保护法
        // 实施条例》对自动续费的要求）。因此用户同时关掉全局短信开关与分类偏好、
        // 且当前处于免打扰时段时，这条通知仍必须真的发出去。
        Fixture fixture = fixture(false, "SUBSCRIPTION", false, "SUBSCRIPTION_RENEWAL_DUE",
                "{\"renewMoney\":\"29.00\",\"renewDate\":\"2026-09-25\",\"planName\":\"STARTER\"}");
        activate("SUBSCRIPTION_RENEWAL_DUE");

        dispatcher().dispatch(fixture.eventId());

        assertThat(status(fixture.eventId(), "SMS")).isEqualTo(new ChannelStatus("PENDING", null));
        // 免打扰期间强制短信必须立即投递，不能推到次日——推迟会晚于扣款本身。
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM platform.sms_delivery", Instant.class))
                .isEqualTo(Instant.parse("2026-08-08T15:00:00Z"));
    }

    @Test
    void subscriptionPreChargeNoticeSurvivesMissingPreferenceRows() {
        // 偏好行缺失时 preference() 回退为 globalEnabled=false，非强制短信会被静默抑制。
        // 本用例锁死「强制」这一属性：即使整行偏好都不存在，通知也必须发出。
        Fixture fixture = fixture(false, "SUBSCRIPTION", false, "SUBSCRIPTION_RENEWAL_DUE",
                "{\"renewMoney\":\"29.00\",\"renewDate\":\"2026-09-25\",\"planName\":\"STARTER\"}");
        jdbc.update("DELETE FROM platform.notification_preference WHERE category='SUBSCRIPTION'");
        activate("SUBSCRIPTION_RENEWAL_DUE");

        dispatcher().dispatch(fixture.eventId());

        assertThat(status(fixture.eventId(), "SMS")).isEqualTo(new ChannelStatus("PENDING", null));
    }

    @Test
    void subscriptionRenewalFailureHonoursUserPreferences() {
        // 反面：只有扣款预告是强制的。「续费失败」这类通知必须尊重用户偏好，
        // 否则强制短信会从「合规必需」悄悄扩张成「所有订阅通知都关不掉」。
        Fixture fixture = fixture(false, "SUBSCRIPTION", false, "SUBSCRIPTION_RENEWAL_FAILED",
                "{\"failReason\":\"余额不足\",\"retryDate\":\"2026-09-26\"}");
        activate("SUBSCRIPTION_RENEWAL_FAILED");

        dispatcher().dispatch(fixture.eventId());

        assertThat(status(fixture.eventId(), "SMS"))
                .isEqualTo(new ChannelStatus("SUPPRESSED", "PREFERENCE_DISABLED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.sms_delivery", Integer.class)).isZero();
    }

    private NotificationSmsDispatcher dispatcher() {
        SmsNotificationService sms = SmsServiceTestFactory.notification(jdbc, mapper, encryption, quietClock);
        return new NotificationSmsDispatcher(jdbc, mapper, sms, quietClock);
    }

    private Fixture fixture(boolean global, String category, boolean categoryEnabled, String type, String payload) {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        jdbc.update("INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, sms_notifications_enabled) VALUES (?, ?, ?, 'x', 'user', ?)",
                user, tenant, user + "@example.invalid", global);
        String suffix = String.format("%08d", Math.floorMod(user.hashCode(), 100_000_000));
        PhoneProtectionService.ProtectedPhone phone = phones.protect("150" + suffix);
        jdbc.update("UPDATE platform.app_user SET phone_ciphertext=?, phone_lookup_hash=?, phone_last4=?, phone_verified_at=now() WHERE id=?",
                phone.ciphertext(), phone.lookupHash(), phone.last4(), user);
        if (!"SECURITY".equals(category)) {
            jdbc.update("INSERT INTO platform.notification_preference(tenant_id,user_id,category,channel,enabled) VALUES (?,?,?,'SMS',?)",
                    tenant, user, category, categoryEnabled);
        }
        jdbc.update("INSERT INTO platform.notification_event(id,event_key,category,event_type,tenant_id,user_id,aggregate_type,aggregate_id,payload,occurred_at) VALUES (?,?,?,?,?,?,'TEST',?,?::jsonb,now())",
                event, "event:" + event, category, type, tenant, user, event.toString(), payload);
        return new Fixture(event);
    }

    private void activate(String key) {
        jdbc.update("UPDATE platform.sms_template SET integration_status='ACTIVE' WHERE template_key=?", key);
    }

    private ChannelStatus status(UUID event, String channel) {
        return jdbc.query("SELECT status, suppression_reason FROM platform.notification_channel_delivery WHERE event_id=? AND channel=?",
                (rs, row) -> new ChannelStatus(rs.getString(1), rs.getString(2)), event, channel)
                .stream().findFirst().orElseThrow();
    }

    private record Fixture(UUID eventId) { }
    private record ChannelStatus(String status, String reason) { }
}
