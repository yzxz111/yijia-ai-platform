package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class WalletUsageDigestJobTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");
    private static final DataSource DATA_SOURCE;
    private static final UUID TENANT = UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("82000000-0000-4000-8000-000000000002");
    private static final UUID ACCOUNT = UUID.fromString("82000000-0000-4000-8000-000000000003");

    static {
        POSTGRES.start();
        DATA_SOURCE = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(DATA_SOURCE).defaultSchema("platform").schemas("platform")
                .locations("classpath:db/migration").load().migrate();
    }

    private final JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-08-09T01:00:00Z"), ZoneOffset.UTC);
    private final WalletUsageDigestJob job = new WalletUsageDigestJob(
            jdbc, new DataSourceTransactionManager(DATA_SOURCE),
            new NotificationEventService(
                    new NotificationEventRepository(jdbc), new ObjectMapper()), clock);

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE platform.notification_trigger_state, platform.notification_event, "
                + "platform.wallet_usage_reservation, platform.account_balance, platform.app_user CASCADE");
        jdbc.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name)
                VALUES (?, ?, 'digest@example.test', 'hash', 'Digest user')
                """, USER, TENANT);
        jdbc.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, reserved_amount)
                VALUES (?, ?, ?, 'CNY', 875, 0)
                """, ACCOUNT, TENANT, USER);
    }

    @Test
    void aggregatesThePreviousShanghaiNaturalDayAndIsIdempotent() {
        usage("before-window", 99, "2026-08-01T15:59:59Z");
        usage("first", 125, "2026-08-07T16:00:00Z");
        usage("last", 250, "2026-08-08T15:59:59Z");
        usage("after", 999, "2026-08-08T16:00:00Z");

        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(job.runOnce()).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT event_key, payload->>'costMoney' cost, payload->>'balance' balance
                FROM platform.notification_event
                """))
                .containsEntry("event_key", "wallet-usage-digest:" + USER + ":2026-08-08")
                .containsEntry("cost", "3.75")
                .containsEntry("balance", "8.75");
        assertThat(jdbc.queryForObject("""
                SELECT last_digest_date FROM platform.notification_trigger_state
                WHERE user_id=? AND trigger_type='DAILY_DIGEST'
                """, LocalDate.class, USER)).isEqualTo(LocalDate.of(2026, 8, 8));
    }

    @Test
    void catchesUpMissedCompletedDaysOldestFirstWithoutCreatingZeroUsageDigests() {
        usage("missed-oldest", 125, "2026-08-05T16:00:00Z");
        usage("missed-later", 250, "2026-08-07T03:00:00Z");

        assertThat(job.runOnce()).isEqualTo(2);
        assertThat(job.runOnce()).isZero();
        assertThat(jdbc.queryForList("""
                SELECT event_key FROM platform.notification_event
                WHERE event_type='WALLET_USAGE_DAILY_SUMMARY' ORDER BY occurred_at, event_key
                """, String.class)).containsExactly(
                "wallet-usage-digest:" + USER + ":2026-08-06",
                "wallet-usage-digest:" + USER + ":2026-08-07");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event
                WHERE event_key LIKE ?
                """, Integer.class, "wallet-usage-digest:" + USER + ":2026-08-08"))
                .isZero();
    }

    @Test
    void concurrentRunsCreateAtMostOneDigestWithoutBroker() throws Exception {
        usage("concurrent", 400, "2026-08-08T03:00:00Z");
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> first = executor.submit(() -> run(barrier));
            Future<Integer> second = executor.submit(() -> run(barrier));
            assertThat(first.get() + second.get()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event
                WHERE event_type='WALLET_USAGE_DAILY_SUMMARY'
                """, Integer.class)).isOne();
    }

    private int run(CyclicBarrier barrier) throws Exception {
        barrier.await();
        return job.runOnce();
    }

    private void usage(String request, long amount, String settledAt) {
        // expires_at 必填：V46 把它 ADD COLUMN 之后又 SET NOT NULL，而且没有
        // 默认值，所以任何不写这一列的 INSERT 都会被 23502 拒绝。
        // 这一行是已结算的历史用量，expires_at 对摘要任务没有语义，
        // 给一个与 settled_at 同值的合法时间戳即可。
        Timestamp settled = Timestamp.from(Instant.parse(settledAt));
        jdbc.update("""
                INSERT INTO platform.wallet_usage_reservation(
                    id, tenant_id, user_id, account_id, request_id, estimated_amount,
                    settled_amount, status, model_code, actual_input_tokens,
                    actual_output_tokens, idempotency_key, billing_mode, settled_at,
                    expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'SETTLED', 'model-a', 1, 1, ?, 'WALLET_ONLY', ?, ?)
                """, UUID.randomUUID(), TENANT, USER, ACCOUNT, request, amount, amount,
                "idem-" + request, settled, settled);
    }
}
