package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.wallet.WalletServiceTestFactory;
import com.yuzhihuihe.apiplatform.platform.wallet.WalletUsageReservationService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class WalletUsageNotificationIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");
    private static final DataSource DATA_SOURCE;
    private static final UUID TENANT = UUID.fromString("81000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("81000000-0000-4000-8000-000000000002");
    private static final UUID ACCOUNT = UUID.fromString("81000000-0000-4000-8000-000000000003");

    static {
        POSTGRES.start();
        DATA_SOURCE = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(DATA_SOURCE).defaultSchema("platform").schemas("platform")
                .locations("classpath:db/migration").load().migrate();
    }

    private final JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);
    private final TransactionTemplate tx = new TransactionTemplate(
            new DataSourceTransactionManager(DATA_SOURCE));
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-08-09T01:00:00Z"), ZoneOffset.UTC);
    private final WalletUsageNotificationService notifications = new WalletUsageNotificationService(
            new WalletUsageNotificationRepository(jdbc),
            new NotificationEventService(
                    new NotificationEventRepository(jdbc), new ObjectMapper()), clock);
    private final WalletUsageReservationService reservations =
            WalletServiceTestFactory.usageReservation(jdbc, null, notifications);

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE platform.notification_trigger_state, platform.notification_event, "
                + "platform.wallet_usage_reservation, platform.balance_ledger, "
                + "platform.account_balance, platform.app_user CASCADE");
        jdbc.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name)
                VALUES (?, ?, 'wallet-notification@example.test', 'hash', 'Wallet notification')
                """, USER, TENANT);
        jdbc.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, reserved_amount)
                VALUES (?, ?, ?, 'CNY', 2000, 0)
                """, ACCOUNT, TENANT, USER);
    }

    @Test
    void packageOnlyAndZeroWalletAmountDoNotNotify() {
        UUID packageOnly = reserve("package", 300);
        settle(packageOnly, 250, 3, 5, "PACKAGE_ONLY");
        settle(reserve("zero", 200), 0, 4, 6, "HYBRID");

        assertThat(eventCount("WALLET_USAGE_SETTLED")).isZero();
        assertThat(eventCount("BALANCE_LOW")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT billing_mode FROM platform.wallet_usage_reservation WHERE id=?
                """, String.class, packageOnly)).isEqualTo("PACKAGE_ONLY");
    }

    @Test
    void identicalReplaySucceedsButConflictingModeOrTokensAreRejectedWithoutSideEffects() {
        UUID reservation = reserve("replay-identity", 300);
        settle(reservation, 250, 11, 13, "HYBRID");
        settle(reservation, 250, 11, 13, "HYBRID");

        assertConflict(reservation, 249, 11, 13, "HYBRID");
        assertConflict(reservation, 250, 11, 13, "WALLET_ONLY");
        assertConflict(reservation, 250, 12, 13, "HYBRID");
        assertConflict(reservation, 250, 11, 14, "HYBRID");

        assertThat(eventCount("WALLET_USAGE_SETTLED")).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT settled_amount, actual_input_tokens, actual_output_tokens, billing_mode
                FROM platform.wallet_usage_reservation WHERE id=?
                """, reservation))
                .containsEntry("settled_amount", 250L)
                .containsEntry("actual_input_tokens", 11L)
                .containsEntry("actual_output_tokens", 13L)
                .containsEntry("billing_mode", "HYBRID");
    }

    @Test
    void incompletePersistedSettlementIdentityIsRejectedDeterministically() {
        UUID reservation = reserve("incomplete-replay", 300);
        settle(reservation, 250, 11, 13, "HYBRID");
        jdbc.update("""
                UPDATE platform.wallet_usage_reservation SET actual_input_tokens=NULL WHERE id=?
                """, reservation);

        assertConflict(reservation, 250, 11, 13, "HYBRID");
        assertThat(eventCount("WALLET_USAGE_SETTLED")).isOne();
    }

    @Test
    void hybridAndWalletOnlySettlementCreateOneSafeStableEventEach() {
        UUID hybrid = reserve("hybrid", 300);
        settle(hybrid, 250, 11, 13, "HYBRID");
        settle(hybrid, 250, 11, 13, "HYBRID");
        UUID wallet = reserve("wallet", 200);
        settle(wallet, 150, 17, 19, "WALLET_ONLY");

        assertThat(eventCount("WALLET_USAGE_SETTLED")).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
                SELECT event_key, payload FROM platform.notification_event
                WHERE aggregate_id = ?
                """, hybrid.toString()))
                .containsEntry("event_key", "wallet-usage-settled:" + hybrid);
        assertThat(jdbc.queryForObject("""
                SELECT payload = jsonb_build_object(
                    'logicalModel', 'model-a', 'amountFen', 250,
                    'availableBalanceFen', 1750, 'inputTokens', 11,
                    'outputTokens', 13, 'requestId', 'req-hybrid')
                FROM platform.notification_event WHERE aggregate_id = ?
                """, Boolean.class, hybrid.toString())).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT payload::text ~* '(api.?key|prompt|response|phone)'
                FROM platform.notification_event WHERE aggregate_id = ?
                """, Boolean.class, hybrid.toString())).isFalse();
    }

    @Test
    void lowBalanceCrossingContinuesQuietThenRearmsAndHonorsCooldown() {
        jdbc.update("UPDATE platform.account_balance SET available_amount=1200 WHERE id=?", ACCOUNT);
        settle(reserve("cross-1", 400), 300, 1, 1, "WALLET_ONLY");
        settle(reserve("continue", 100), 100, 1, 1, "WALLET_ONLY");
        assertThat(eventCount("BALANCE_LOW")).isOne();

        notifications.rearmAfterRecharge(TENANT, USER, 1100);
        jdbc.update("UPDATE platform.account_balance SET available_amount=1100 WHERE id=?", ACCOUNT);
        settle(reserve("cross-2", 200), 150, 1, 1, "WALLET_ONLY");
        assertThat(eventCount("BALANCE_LOW")).isOne();

        notifications.rearmAfterRecharge(TENANT, USER, 1100);
        jdbc.update("""
                UPDATE platform.notification_trigger_state
                SET state='{"armed":true}', last_triggered_at=?
                WHERE user_id=? AND trigger_type='LOW_BALANCE'
                """, java.sql.Timestamp.from(clock.instant().minusSeconds(25 * 60 * 60)), USER);
        jdbc.update("UPDATE platform.account_balance SET available_amount=1100 WHERE id=?", ACCOUNT);
        settle(reserve("cross-3", 200), 150, 1, 1, "WALLET_ONLY");
        assertThat(eventCount("BALANCE_LOW")).isEqualTo(2);
    }

    @Test
    void concurrentSettlementProducesOneUsageAndOneLowBalanceNotification() throws Exception {
        jdbc.update("UPDATE platform.account_balance SET available_amount=1200 WHERE id=?", ACCOUNT);
        UUID reservation = reserve("concurrent", 400);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> concurrentSettle(barrier, reservation));
            Future<?> second = executor.submit(() -> concurrentSettle(barrier, reservation));
            first.get();
            second.get();
        }
        assertThat(eventCount("WALLET_USAGE_SETTLED")).isOne();
        assertThat(eventCount("BALANCE_LOW")).isOne();
    }

    private void concurrentSettle(CyclicBarrier barrier, UUID reservation) {
        try {
            barrier.await();
            settle(reservation, 300, 2, 3, "WALLET_ONLY");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private UUID reserve(String suffix, long amount) {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(status -> reservations.reserve(
                new WalletUsageReservationService.ReserveCommand(
                        id, "req-" + suffix, TENANT, USER, "model-a", amount,
                        1, 1, "price-v1", "idem-" + suffix)));
        return id;
    }

    private void settle(UUID id, long amount, long input, long output, String mode) {
        tx.executeWithoutResult(status -> reservations.settle(id,
                new WalletUsageReservationService.SettleCommand(
                        TENANT, USER, amount, input, output, mode)));
    }

    private void assertConflict(UUID id, long amount, long input, long output, String mode) {
        assertThatThrownBy(() -> settle(id, amount, input, output, mode))
                .hasMessageContaining("wallet_usage_idempotency_mismatch");
    }

    private int eventCount(String type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform.notification_event WHERE event_type=?",
                Integer.class, type);
    }
}
