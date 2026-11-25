package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;

class CommercialNotificationServiceTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");
    private static final DataSource DATA_SOURCE;
    private static final UUID TENANT_ID =
            UUID.fromString("9c000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("9c000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-09T02:30:00Z");

    static {
        POSTGRES.start();
        DATA_SOURCE = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(DATA_SOURCE)
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private final JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);
    private final CommercialNotificationService service = new CommercialNotificationService(
            new CommercialNotificationRepository(jdbc),
            new NotificationEventService(
                    new NotificationEventRepository(jdbc), new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE platform.notification_event, platform.app_user CASCADE");
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name)
                VALUES (?, ?, 'commercial-notification@example.invalid',
                        'test-hash', 'Commercial notification user')
                """, USER_ID, TENANT_ID);
    }

    @Test
    void transactionNotificationsPreserveBusinessKeysVariablesAndReplayAsOneEvent() {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        assertThat(service.notifyPaymentFailed(
                paymentId, TENANT_ID, USER_ID, 12_345)).isTrue();
        assertThat(service.notifyPaymentFailed(
                paymentId, TENANT_ID, USER_ID, 12_345)).isFalse();
        assertThat(service.notifyOrderTimedOut(
                orderId, TENANT_ID, USER_ID, "ord_notification_1")).isTrue();
        assertThat(service.notifyOrderTimedOut(
                orderId, TENANT_ID, USER_ID, "ord_notification_1")).isFalse();
        assertThat(service.notifyRechargeSucceeded(
                "rch_notification_1", TENANT_ID, USER_ID, 50_505, 70_010)).isTrue();
        assertThat(service.notifyRechargeSucceeded(
                "rch_notification_1", TENANT_ID, USER_ID, 50_505, 70_010)).isFalse();
        assertThat(service.notifyRefundSucceeded(
                refundId, TENANT_ID, USER_ID, 505)).isTrue();
        assertThat(service.notifyRefundSucceeded(
                refundId, TENANT_ID, USER_ID, 505)).isFalse();

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_key, event_type, tenant_id, user_id,
                       aggregate_type, aggregate_id, payload::text
                FROM platform.notification_event
                ORDER BY event_key
                """);
        assertThat(events).hasSize(4);
        assertEvent(events, "payment-failed:" + paymentId,
                "ORDER_PAYMENT_FAILED", "PAYMENT_TRANSACTION", paymentId.toString(),
                "{\"payMoney\": \"123.45\"}");
        assertEvent(events, "order-timeout:" + orderId,
                "ORDER_TIMED_OUT", "CUSTOMER_ORDER", orderId.toString(),
                "{\"orderNo\": \"ord_notification_1\"}");
        assertEvent(events, "recharge-succeeded:rch_notification_1",
                "BALANCE_RECHARGE_SUCCEEDED", "RECHARGE_ORDER", "rch_notification_1",
                "{\"balance\": \"700.10\", \"payMoney\": \"505.05\"}");
        assertEvent(events, "refund-completed:" + refundId,
                "REFUND_SUCCEEDED", "REFUND", refundId.toString(),
                "{\"refundMoney\": \"5.05\"}");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.sms_delivery", Integer.class)).isZero();
    }

    @Test
    void internalSignalsRecordOneSafeUnifiedEventAcrossGatewayRetries() {
        UUID packageId = UUID.randomUUID();
        Instant expiresAt = NOW.plusSeconds(2 * 24 * 60 * 60);

        assertThat(service.notifyPackageExpiry(
                TENANT_ID, USER_ID, packageId, expiresAt)).isTrue();
        assertThat(service.notifyPackageExpiry(
                TENANT_ID, USER_ID, packageId, expiresAt)).isFalse();
        assertThat(service.notifyApiRateLimited(
                TENANT_ID, USER_ID, "gpt-5.1/private-model", 82_345)).isTrue();
        assertThat(service.notifyApiRateLimited(
                TENANT_ID, USER_ID, "gpt-5.1/private-model", 82_345)).isFalse();

        assertThat(jdbc.queryForList("""
                SELECT event_key, event_type, aggregate_type, aggregate_id, payload::text
                FROM platform.notification_event ORDER BY event_type
                """))
                .allSatisfy(event -> assertThat(event.toString())
                        .doesNotContain("gpt-5.1/private-model"))
                .anySatisfy(event -> {
                    assertThat(event.get("event_type")).isEqualTo("PACKAGE_EXPIRES_IN_3_DAYS");
                    assertThat(event.get("event_key"))
                            .isEqualTo("package-expiry:" + packageId + ":expires-in-3-days");
                    assertThat(event.get("payload").toString())
                            .isEqualTo("{\"expireDate\": \"2026-08-11\"}");
                })
                .anySatisfy(event -> {
                    assertThat(event.get("event_type")).isEqualTo("API_RATE_LIMITED");
                    assertThat(event.get("event_key").toString())
                            .startsWith("rate-limit:" + USER_ID + ":")
                            .endsWith(":82345");
                    assertThat(event.get("aggregate_type")).isEqualTo("API_RATE_LIMIT");
                    assertThat(event.get("payload").toString()).isEqualTo("{}");
                });
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.notification_event", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.sms_delivery", Integer.class)).isZero();
    }

    @Test
    void simultaneousFirstDeliveryReplaysAgainstTheWinningOccurrenceTime() throws Exception {
        UUID paymentId = UUID.randomUUID();
        NotificationEventService events = new NotificationEventService(
                new NotificationEventRepository(jdbc), new ObjectMapper());
        CommercialNotificationService first = new CommercialNotificationService(
                new CommercialNotificationRepository(jdbc),
                events, Clock.fixed(NOW, ZoneOffset.UTC));
        CommercialNotificationService second = new CommercialNotificationService(
                new CommercialNotificationRepository(jdbc),
                events, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> firstResult = executor.submit(() -> {
                start.await();
                return first.notifyPaymentFailed(paymentId, TENANT_ID, USER_ID, 12_345);
            });
            Future<Boolean> secondResult = executor.submit(() -> {
                start.await();
                return second.notifyPaymentFailed(paymentId, TENANT_ID, USER_ID, 12_345);
            });

            assertThat(List.of(firstResult.get(), secondResult.get()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event WHERE event_key = ?
                """, Integer.class, "payment-failed:" + paymentId)).isOne();
    }

    @Test
    void simultaneousFirstDeliveryWithTheSameOccurrenceStillHasOneWinner() throws Exception {
        UUID paymentId = UUID.randomUUID();
        NotificationEventService events = new NotificationEventService(
                new NotificationEventRepository(jdbc), new ObjectMapper());
        CommercialNotificationService service = new CommercialNotificationService(
                new CommercialNotificationRepository(jdbc),
                events, Clock.fixed(NOW, ZoneOffset.UTC));
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return service.notifyPaymentFailed(paymentId, TENANT_ID, USER_ID, 12_345);
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return service.notifyPaymentFailed(paymentId, TENANT_ID, USER_ID, 12_345);
            });

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event WHERE event_key = ?
                """, Integer.class, "payment-failed:" + paymentId)).isOne();
    }

    private void assertEvent(
            List<Map<String, Object>> events,
            String eventKey,
            String eventType,
            String aggregateType,
            String aggregateId,
            String payload) {
        assertThat(events).anySatisfy(event -> {
            assertThat(event.get("event_key")).isEqualTo(eventKey);
            assertThat(event.get("event_type")).isEqualTo(eventType);
            assertThat(event.get("tenant_id")).isEqualTo(TENANT_ID);
            assertThat(event.get("user_id")).isEqualTo(USER_ID);
            assertThat(event.get("aggregate_type")).isEqualTo(aggregateType);
            assertThat(event.get("aggregate_id")).isEqualTo(aggregateId);
            assertThat(event.get("payload").toString()).isEqualTo(payload);
        });
    }

}
