package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;

class NotificationEventServiceTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");
    private static final DataSource DATA_SOURCE;

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
    private final NotificationEventService service =
            new NotificationEventService(
                    new NotificationEventRepository(jdbc), new ObjectMapper());
    private final TransactionTemplate transactions = new TransactionTemplate(
            new DataSourceTransactionManager(DATA_SOURCE));

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE platform.notification_event, platform.app_user CASCADE");
    }

    @Test
    void canonicalPayloadReplayReturnsTheExistingEventAndStableHash() {
        OffsetDateTime occurredAt = OffsetDateTime.of(
                2026, 8, 8, 10, 30, 0, 0, ZoneOffset.ofHours(8));
        Map<String, Object> firstPayload = new LinkedHashMap<>();
        firstPayload.put("logicalModel", "model-a");
        firstPayload.put("amountFen", 250);
        firstPayload.put("availableBalanceFen", 800);
        firstPayload.put("inputTokens", 11);
        firstPayload.put("outputTokens", 13);
        firstPayload.put("requestId", "request-1");

        NotificationEventService.RecordedEvent first = service.record(
                "wallet-settled:usage-1", "WALLET_USAGE_SETTLED", null, null,
                "WALLET_USAGE", "usage-1", firstPayload, occurredAt);
        NotificationEventService.RecordedEvent replay = service.record(
                "wallet-settled:usage-1", "WALLET_USAGE_SETTLED", null, null,
                "WALLET_USAGE", "usage-1",
                Map.of("logicalModel", "model-a", "amountFen", 250,
                        "availableBalanceFen", 800, "inputTokens", 11,
                        "outputTokens", 13, "requestId", "request-1"),
                occurredAt.withOffsetSameInstant(ZoneOffset.UTC));

        assertThat(replay).isEqualTo(first);
        assertThat(first.payloadHash()).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.notification_event", Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT payload::text FROM platform.notification_event", String.class))
                .isEqualTo("{\"amountFen\": 250, \"requestId\": \"request-1\", "
                        + "\"inputTokens\": 11, \"logicalModel\": \"model-a\", \"outputTokens\": 13, "
                        + "\"availableBalanceFen\": 800}");
    }

    @Test
    void sameEventKeyWithDifferentCanonicalPayloadIsRejected() {
        recordLowBalance("balance-low:1", "10.00");

        assertThatThrownBy(() -> recordLowBalance("balance-low:1", "9.99"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("notification_event_idempotency_mismatch");
    }

    @Test
    void replayRequiresTheSameTypeScopeAggregateAndOccurrenceInstant() {
        UUID tenantId = UUID.randomUUID();
        UUID firstUserId = UUID.randomUUID();
        UUID secondUserId = UUID.randomUUID();
        insertUser(tenantId, firstUserId);
        insertUser(tenantId, secondUserId);
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-08T02:30:00Z");
        Map<String, Object> payload = Map.of(
                "logicalModel", "model-a", "amountFen", 250,
                "availableBalanceFen", 800, "inputTokens", 11,
                "outputTokens", 13, "requestId", "request-identity");
        service.record(
                "wallet-settled:identity", "WALLET_USAGE_SETTLED", tenantId, firstUserId,
                "WALLET_USAGE", "usage-1", payload, occurredAt);

        assertMismatch(() -> service.record(
                "wallet-settled:identity", "WALLET_USAGE_DAILY_SUMMARY", tenantId, firstUserId,
                "WALLET_USAGE", "usage-1",
                Map.of("costMoney", "2.50", "balance", "8.00"), occurredAt));
        assertMismatch(() -> service.record(
                "wallet-settled:identity", "WALLET_USAGE_SETTLED", UUID.randomUUID(), null,
                "WALLET_USAGE", "usage-1", payload, occurredAt));
        assertMismatch(() -> service.record(
                "wallet-settled:identity", "WALLET_USAGE_SETTLED", tenantId, secondUserId,
                "WALLET_USAGE", "usage-1", payload, occurredAt));
        assertMismatch(() -> service.record(
                "wallet-settled:identity", "WALLET_USAGE_SETTLED", tenantId, firstUserId,
                "OTHER_AGGREGATE", "usage-1", payload, occurredAt));
        assertMismatch(() -> service.record(
                "wallet-settled:identity", "WALLET_USAGE_SETTLED", tenantId, firstUserId,
                "WALLET_USAGE", "usage-2", payload, occurredAt));
        NotificationEventService.RecordedEvent subMicrosecondReplay = service.record(
                "wallet-settled:identity", "WALLET_USAGE_SETTLED", tenantId, firstUserId,
                "WALLET_USAGE", "usage-1", payload, occurredAt.plusNanos(1));
        assertThat(subMicrosecondReplay.occurredAt()).isEqualTo(occurredAt);
        assertMismatch(() -> service.record(
                "wallet-settled:identity", "WALLET_USAGE_SETTLED", tenantId, firstUserId,
                "WALLET_USAGE", "usage-1", payload, occurredAt.plusNanos(1_000)));

        NotificationEventService.RecordedEvent sameInstant = service.record(
                "wallet-settled:identity", "WALLET_USAGE_SETTLED", tenantId, firstUserId,
                "WALLET_USAGE", "usage-1", payload,
                occurredAt.withOffsetSameInstant(ZoneOffset.ofHours(8)));
        assertThat(sameInstant.occurredAt()).isEqualTo(occurredAt);
        assertThat(jdbc.queryForMap("""
                SELECT tenant_id, user_id, aggregate_type, aggregate_id
                FROM platform.notification_event
                """))
                .containsEntry("tenant_id", tenantId)
                .containsEntry("user_id", firstUserId)
                .containsEntry("aggregate_type", "WALLET_USAGE")
                .containsEntry("aggregate_id", "usage-1");
    }

    @Test
    void occurredAtUsesTruncatedPostgresMicrosecondPrecisionEverywhere() {
        List<OffsetDateTime> values = List.of(
                OffsetDateTime.parse("2026-08-08T02:30:00.000000001Z"),
                OffsetDateTime.parse("2026-08-08T02:30:00.123456789Z"),
                OffsetDateTime.parse("2026-08-08T02:30:00.999999999Z"));

        for (int index = 0; index < values.size(); index++) {
            OffsetDateTime input = values.get(index);
            OffsetDateTime expected = input.withNano(input.getNano() / 1_000 * 1_000);
            String eventKey = "balance-low:occurred-at:" + index;

            NotificationEventService.RecordedEvent first = service.record(
                    eventKey, "BALANCE_LOW", null, null, "WALLET", "wallet-1",
                    Map.of("warnMoney", "10.00"), input);
            NotificationEventService.RecordedEvent identicalReplay = service.record(
                    eventKey, "BALANCE_LOW", null, null, "WALLET", "wallet-1",
                    Map.of("warnMoney", "10.00"), input);
            NotificationEventService.RecordedEvent sameMicrosecondReplay = service.record(
                    eventKey, "BALANCE_LOW", null, null, "WALLET", "wallet-1",
                    Map.of("warnMoney", "10.00"), expected.plusNanos(999));

            assertThat(first.occurredAt()).isEqualTo(expected);
            assertThat(identicalReplay).isEqualTo(first);
            assertThat(sameMicrosecondReplay).isEqualTo(first);
            assertThat(jdbc.queryForObject("""
                    SELECT occurred_at FROM platform.notification_event WHERE event_key = ?
                    """, OffsetDateTime.class, eventKey)).isEqualTo(expected);
            assertMismatch(() -> service.record(
                    eventKey, "BALANCE_LOW", null, null, "WALLET", "wallet-1",
                    Map.of("warnMoney", "10.00"), expected.plusNanos(1_000)));
        }
    }

    @Test
    void numericCanonicalizationSurvivesJsonbRoundTripsWithoutExponentDrift() {
        List<NumericCase> cases = List.of(
                new NumericCase("positive-exponent", new BigDecimal("1E+3"), 1_000L, "1000"),
                new NumericCase("negative-exponent", new BigDecimal("1E-7"), 0.0000001d, "0.0000001"),
                new NumericCase("large", new BigDecimal("123456789012345678901234567890.000"),
                        new BigInteger("123456789012345678901234567890"),
                        "123456789012345678901234567890"),
                new NumericCase("zero", new BigDecimal("0E+12"), 0, "0"));

        for (NumericCase numericCase : cases) {
            String eventKey = "balance-low:numeric:" + numericCase.name();
            NotificationEventService.RecordedEvent first = recordLowBalance(
                    eventKey, numericCase.first());
            NotificationEventService.RecordedEvent replay = recordLowBalance(
                    eventKey, numericCase.replay());

            assertThat(replay).as(numericCase.name()).isEqualTo(first);
            assertThat(jdbc.queryForObject("""
                    SELECT payload->>'warnMoney' FROM platform.notification_event
                    WHERE event_key = ?
                    """, String.class, eventKey)).isEqualTo(numericCase.expectedPlain());
        }
    }

    @Test
    void rejectsUnknownKeysAndSensitiveKeysAtAnyDepth() {
        assertThatThrownBy(() -> service.record(
                "balance-low:unknown", "BALANCE_LOW", null, null,
                "WALLET", "wallet-1", Map.of("warnMoney", "10.00", "currency", "CNY"),
                OffsetDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("notification_payload_keys_mismatch");

        for (String sensitive : new String[] {
                "phone", "phoneNumber", "mobile", "password", "token", "accessToken", "refreshToken",
                "apiKey", "secret", "prompt", "response", "content", "attachment",
                "fileBytes", "rawIp", "ip", "ipAddress", "userAgent", "device", "deviceId"}) {
            assertThatThrownBy(() -> service.record(
                    "balance-low:sensitive:" + sensitive, "BALANCE_LOW", null, null,
                    "WALLET", "wallet-1",
                    Map.of("warnMoney", Map.of("nested", Map.of(sensitive, "forbidden"))),
                    OffsetDateTime.now(ZoneOffset.UTC)))
                    .as("sensitive key %s", sensitive)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("sensitive_notification_payload");
        }
    }

    @Test
    void callerRollbackLeavesNoEvent() {
        transactions.executeWithoutResult(status -> {
            recordLowBalance("balance-low:rollback", "10.00");
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.notification_event", Integer.class)).isZero();
    }

    @Test
    void authoritativeBusinessReplayReportsOneWinnerForConcurrentDifferentOccurrences()
            throws Exception {
        String eventKey = "payment-failed:concurrent-authoritative";
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> first = executor.submit(() -> transactions.execute(status -> {
                await(start);
                return service.recordAuthoritativeBusinessEvent(
                        eventKey, "ORDER_PAYMENT_FAILED", null, null,
                        "PAYMENT_TRANSACTION", "payment-1", Map.of("payMoney", "123.45"),
                        OffsetDateTime.parse("2026-08-08T02:30:00Z")).created();
            }));
            Future<Boolean> second = executor.submit(() -> transactions.execute(status -> {
                await(start);
                return service.recordAuthoritativeBusinessEvent(
                        eventKey, "ORDER_PAYMENT_FAILED", null, null,
                        "PAYMENT_TRANSACTION", "payment-1", Map.of("payMoney", "123.45"),
                        OffsetDateTime.parse("2026-08-08T02:30:01Z")).created();
            }));

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event WHERE event_key = ?
                """, Integer.class, eventKey)).isOne();
    }

    @Test
    void authoritativeBusinessReplayReportsFalseForTheSameOccurrence() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-08T02:30:00Z");

        NotificationEventService.RecordOutcome first = service.recordAuthoritativeBusinessEvent(
                "payment-failed:same-occurrence", "ORDER_PAYMENT_FAILED", null, null,
                "PAYMENT_TRANSACTION", "payment-2", Map.of("payMoney", "123.45"), occurredAt);
        NotificationEventService.RecordOutcome replay = service.recordAuthoritativeBusinessEvent(
                "payment-failed:same-occurrence", "ORDER_PAYMENT_FAILED", null, null,
                "PAYMENT_TRANSACTION", "payment-2", Map.of("payMoney", "123.45"), occurredAt);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.event()).isEqualTo(first.event());
    }

    @Test
    void authoritativePaymentAndRefundReplaysCommitTheirOuterTransaction() {
        OffsetDateTime firstOccurrence = OffsetDateTime.parse("2026-08-08T02:30:00Z");
        service.recordAuthoritativeBusinessEvent(
                "payment-failed:outer-commit", "ORDER_PAYMENT_FAILED", null, null,
                "PAYMENT_TRANSACTION", "payment-outer", Map.of("payMoney", "123.45"),
                firstOccurrence);
        service.recordAuthoritativeBusinessEvent(
                "refund-completed:outer-commit", "REFUND_SUCCEEDED", null, null,
                "REFUND", "refund-outer", Map.of("refundMoney", "5.05"), firstOccurrence);

        transactions.executeWithoutResult(status -> {
            assertThat(service.recordAuthoritativeBusinessEvent(
                    "payment-failed:outer-commit", "ORDER_PAYMENT_FAILED", null, null,
                    "PAYMENT_TRANSACTION", "payment-outer", Map.of("payMoney", "123.45"),
                    firstOccurrence.plusSeconds(1)).created()).isFalse();
            assertThat(service.recordAuthoritativeBusinessEvent(
                    "refund-completed:outer-commit", "REFUND_SUCCEEDED", null, null,
                    "REFUND", "refund-outer", Map.of("refundMoney", "5.05"),
                    firstOccurrence.plusSeconds(1)).created()).isFalse();
            jdbc.update("""
                    INSERT INTO platform.admin_audit_log(
                        action, resource_type, resource_id, outcome, detail)
                    VALUES ('NOTIFICATION_REPLAY_COMMITTED', 'NOTIFICATION',
                            'outer-commit', 'SUCCESS', '{}'::jsonb)
                    """);
        });

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'NOTIFICATION_REPLAY_COMMITTED'
                """, Integer.class)).isOne();
    }

    private NotificationEventService.RecordedEvent recordLowBalance(
            String eventKey, String warnMoney) {
        return recordLowBalance(eventKey, (Object) warnMoney);
    }

    private NotificationEventService.RecordedEvent recordLowBalance(
            String eventKey, Object warnMoney) {
        return service.record(
                eventKey, "BALANCE_LOW", null, null, "WALLET", "wallet-1",
                Map.of("warnMoney", warnMoney), OffsetDateTime.parse("2026-08-08T02:30:00Z"));
    }

    private void insertUser(UUID tenantId, UUID userId) {
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name)
                VALUES (?, ?, ?, 'test-hash', 'Notification test user')
                """, userId, tenantId, userId + "@example.invalid");
    }

    private void assertMismatch(org.assertj.core.api.ThrowableAssert.ThrowingCallable replay) {
        assertThatThrownBy(replay)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("notification_event_idempotency_mismatch");
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception exception) {
            throw new IllegalStateException("notification_test_barrier_failed", exception);
        }
    }

    private record NumericCase(String name, Object first, Object replay, String expectedPlain) {
    }
}
