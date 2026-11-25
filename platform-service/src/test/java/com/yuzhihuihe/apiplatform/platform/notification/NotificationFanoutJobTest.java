package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class NotificationFanoutJobTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final DataSource DATA_SOURCE;
    static {
        POSTGRES.start();
        DATA_SOURCE = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(DATA_SOURCE).defaultSchema("platform").schemas("platform")
                .locations("classpath:db/migration").load().migrate();
    }
    private final JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-08T04:00:00Z"), ZoneOffset.UTC);

    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE platform.notification_channel_delivery, platform.in_app_notification, platform.notification_event, platform.app_user CASCADE");
    }

    @Test
    void reclaimsExpiredLeaseCreatesInAppFirstAndDoesNotDuplicateOnRetry() {
        UUID tenant = UUID.randomUUID(); UUID user = UUID.randomUUID(); UUID event = UUID.randomUUID();
        jdbc.update("INSERT INTO platform.app_user(id,tenant_id,email,password_hash,display_name) VALUES (?,?,?,'x','user')", user, tenant, user + "@example.invalid");
        jdbc.update("INSERT INTO platform.notification_event(id,event_key,category,event_type,tenant_id,user_id,aggregate_type,aggregate_id,payload,status,next_attempt_at,claim_token,claimed_until,occurred_at) VALUES (?,?, 'USAGE','WALLET_USAGE_SETTLED',?,?,'USAGE',?,'{\"costMoney\":\"1.00\",\"balance\":\"9.00\"}'::jsonb,'PROCESSING',?::timestamptz,?,?::timestamptz,now())",
                event, "event:" + event, tenant, user, event.toString(), "2026-08-08T03:00:00Z", UUID.randomUUID(), "2026-08-08T03:59:00Z");
        NotificationSmsDispatcher sms = new NotificationSmsDispatcher(
                jdbc, new com.fasterxml.jackson.databind.ObjectMapper(),
                (com.yuzhihuihe.apiplatform.platform.sms.SmsNotificationService) null, clock);
        NotificationFanoutJob job = new NotificationFanoutJob(jdbc, new DataSourceTransactionManager(DATA_SOURCE), sms, clock, 10, Duration.ofMinutes(1));

        assertThat(job.runOnce()).isOne();
        jdbc.update("UPDATE platform.notification_event SET status='PENDING', processed_at=NULL WHERE id=?", event);
        assertThat(job.runOnce()).isOne();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.in_app_notification WHERE event_id=?", Integer.class, event)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.notification_channel_delivery WHERE event_id=? AND channel='IN_APP'", Integer.class, event)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM platform.notification_event WHERE id=?", String.class, event)).isEqualTo("PROCESSED");
    }

    @Test
    void commitsInAppBeforeSmsFailureAndRetryDoesNotDuplicateIt() {
        UUID tenant = UUID.randomUUID(); UUID user = UUID.randomUUID(); UUID event = UUID.randomUUID();
        jdbc.update("INSERT INTO platform.app_user(id,tenant_id,email,password_hash,display_name,sms_notifications_enabled) VALUES (?,?,?,'x','user',true)",
                user, tenant, user + "@example.invalid");
        jdbc.update("INSERT INTO platform.notification_event(id,event_key,category,event_type,tenant_id,user_id,aggregate_type,aggregate_id,payload,next_attempt_at,occurred_at) VALUES (?,?, 'BILLING','BALANCE_LOW',?,?,'BALANCE',?,'{\"warnMoney\":\"10.00\"}'::jsonb,?::timestamptz,now())",
                event, "event:" + event, tenant, user, event.toString(), "2026-08-08T03:00:00Z");
        NotificationSmsDispatcher throwing = new NotificationSmsDispatcher(
                jdbc, new com.fasterxml.jackson.databind.ObjectMapper(),
                (com.yuzhihuihe.apiplatform.platform.sms.SmsNotificationService) null, clock) {
            @Override public void dispatch(UUID ignored) {
                new org.springframework.transaction.support.TransactionTemplate(
                        new DataSourceTransactionManager(DATA_SOURCE)).executeWithoutResult(status -> {
                            status.setRollbackOnly();
                            throw new IllegalStateException("provider secret");
                        });
            }
        };

        NotificationFanoutJob first = new NotificationFanoutJob(
                jdbc, new DataSourceTransactionManager(DATA_SOURCE), throwing, clock, 10, Duration.ofMinutes(1));
        assertThat(first.runOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.in_app_notification WHERE event_id=?", Integer.class, event)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM platform.notification_event WHERE id=?", String.class, event)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM platform.notification_event WHERE id=?", String.class, event))
                .isEqualTo("SMS_DISPATCH_FAILED");

        jdbc.update("UPDATE platform.notification_event SET next_attempt_at=?::timestamptz WHERE id=?", "2026-08-08T03:00:00Z", event);
        NotificationSmsDispatcher succeeding = new NotificationSmsDispatcher(
                jdbc, new com.fasterxml.jackson.databind.ObjectMapper(),
                (com.yuzhihuihe.apiplatform.platform.sms.SmsNotificationService) null, clock);
        NotificationFanoutJob retry = new NotificationFanoutJob(
                jdbc, new DataSourceTransactionManager(DATA_SOURCE), succeeding, clock, 10, Duration.ofMinutes(1));

        assertThat(retry.runOnce()).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.in_app_notification WHERE event_id=?", Integer.class, event)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM platform.notification_event WHERE id=?", String.class, event)).isEqualTo("PROCESSED");
    }

    @Test
    void repeatedPermanentFailureBecomesDeadAtMaximumAttempts() {
        UUID tenant = UUID.randomUUID(); UUID user = UUID.randomUUID(); UUID event = insertPendingLowBalance(tenant, user);
        NotificationSmsDispatcher throwing = throwingDispatcher();
        NotificationFanoutJob job = new NotificationFanoutJob(
                jdbc, new DataSourceTransactionManager(DATA_SOURCE), throwing, clock, 10,
                Duration.ofMinutes(1), Duration.ofSeconds(10), Duration.ofHours(1), 2);

        assertThat(job.runOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM platform.notification_event WHERE id=?", String.class, event)).isEqualTo("FAILED");
        jdbc.update("UPDATE platform.notification_event SET next_attempt_at=?::timestamptz WHERE id=?", "2026-08-08T03:00:00Z", event);
        assertThat(job.runOnce()).isZero();

        assertThat(jdbc.queryForObject("SELECT status FROM platform.notification_event WHERE id=?", String.class, event)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM platform.notification_event WHERE id=?", Integer.class, event)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT last_error FROM platform.notification_event WHERE id=?", String.class, event)).isEqualTo("SMS_DISPATCH_FAILED");
    }

    @Test
    void expiredLeaseAtMaximumAttemptsBecomesDeadWithoutReclaim() {
        UUID tenant = UUID.randomUUID(); UUID user = UUID.randomUUID(); UUID event = insertPendingLowBalance(tenant, user);
        jdbc.update("UPDATE platform.notification_event SET status='PROCESSING',attempt_count=2,claim_token=?,claimed_until=?::timestamptz WHERE id=?",
                UUID.randomUUID(), "2026-08-08T03:00:00Z", event);
        NotificationFanoutJob job = new NotificationFanoutJob(
                jdbc, new DataSourceTransactionManager(DATA_SOURCE), throwingDispatcher(), clock, 10,
                Duration.ofMinutes(1), Duration.ofSeconds(10), Duration.ofHours(1), 2);

        assertThat(job.runOnce()).isZero();

        assertThat(jdbc.queryForObject("SELECT status FROM platform.notification_event WHERE id=?", String.class, event)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT last_error FROM platform.notification_event WHERE id=?", String.class, event)).isEqualTo("LEASE_EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT claim_token IS NULL AND claimed_until IS NULL FROM platform.notification_event WHERE id=?", Boolean.class, event)).isTrue();
    }

    private UUID insertPendingLowBalance(UUID tenant, UUID user) {
        UUID event = UUID.randomUUID();
        jdbc.update("INSERT INTO platform.app_user(id,tenant_id,email,password_hash,display_name,sms_notifications_enabled) VALUES (?,?,?,'x','user',true)",
                user, tenant, user + "@example.invalid");
        jdbc.update("INSERT INTO platform.notification_event(id,event_key,category,event_type,tenant_id,user_id,aggregate_type,aggregate_id,payload,next_attempt_at,occurred_at) VALUES (?,?, 'BILLING','BALANCE_LOW',?,?,'BALANCE',?,'{\"warnMoney\":\"10.00\"}'::jsonb,?::timestamptz,now())",
                event, "event:" + event, tenant, user, event.toString(), "2026-08-08T03:00:00Z");
        return event;
    }

    private NotificationSmsDispatcher throwingDispatcher() {
        return new NotificationSmsDispatcher(jdbc, new com.fasterxml.jackson.databind.ObjectMapper(),
                (com.yuzhihuihe.apiplatform.platform.sms.SmsNotificationService) null, clock) {
            @Override public void dispatch(UUID ignored) { throw new IllegalStateException("permanent secret"); }
        };
    }
}
