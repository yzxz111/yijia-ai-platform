package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "outbox.publisher.initial-delay-ms=3600000",
        "payment.jobs.batch-size=2",
        "payment.jobs.claim-timeout=30s",
        "payment.jobs.initial-backoff=5s",
        "payment.jobs.max-backoff=1h",
        "payment.jobs.max-attempts=3",
        "payment.jobs.expired-order-delay-ms=3600000",
        "payment.jobs.compensation-delay-ms=3600000"
})
class PaymentJobsTest {

    private static final UUID TENANT_ID =
            UUID.fromString("95000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("95000000-0000-4000-8000-000000000002");
    private static final UUID VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Autowired
    ExpiredOrderJob expiredOrderJob;

    @Autowired
    PaymentCompensationJob compensationJob;

    @MockitoBean
    PaymentLifecycleService lifecycleService;

    @BeforeEach
    void resetState() {
        reset(lifecycleService);
        jdbcTemplate.update("TRUNCATE platform.payment_anomaly, platform.admin_audit_log, "
                + "platform.outbox_event, platform.grant_request, platform.refund, "
                + "platform.payment_transaction, platform.customer_order, "
                + "platform.refresh_token, platform.user_role, platform.invitation, "
                + "platform.app_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'jobs@example.com', 'hash', 'Jobs User', 'ACTIVE')
                """, USER_ID, TENANT_ID);
    }

    @Test
    void twoExpiredOrderWorkersClaimEachRecordOnceWithoutHoldingLocksDuringWork()
            throws Exception {
        insertOrder("ord_job_1", true);
        insertOrder("ord_job_2", true);
        insertOrder("ord_job_3", true);
        when(lifecycleService.resolveAndCloseSystem(anyString())).thenAnswer(invocation -> {
            String orderNo = invocation.getArgument(0);
            updateFromIndependentConnection(
                    "UPDATE platform.customer_order SET status = 'CLOSED', updated_at = now() "
                            + "WHERE order_no = ?",
                    orderNo);
            return new PaymentLifecycleService.CloseOrderResult(
                    orderNo, "CLOSED", null, false);
        });
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = List.of(
                    executor.submit(() -> {
                        start.await();
                        return expiredOrderJob.runOnce();
                    }),
                    executor.submit(() -> {
                        start.await();
                        return expiredOrderJob.runOnce();
                    }));
            start.countDown();
            assertThat(futures.get(0).get() + futures.get(1).get()).isEqualTo(3);
        }

        ArgumentCaptor<String> orders = ArgumentCaptor.forClass(String.class);
        verify(lifecycleService, times(3)).resolveAndCloseSystem(orders.capture());
        assertThat(orders.getAllValues()).containsExactlyInAnyOrder(
                "ord_job_1", "ord_job_2", "ord_job_3");
        assertThat(singleLong("SELECT count(*) FROM platform.customer_order "
                + "WHERE job_claim_token IS NOT NULL")).isZero();
    }

    @Test
    void expiredOrderClaimIsBoundedAndRecoversAnExpiredLease() {
        insertOrder("ord_batch_1", true);
        insertOrder("ord_batch_2", true);
        insertOrder("ord_batch_3", true);
        jdbcTemplate.update("""
                UPDATE platform.customer_order
                SET job_claim_token = ?, job_claimed_until = now() - interval '1 second'
                WHERE order_no = 'ord_batch_1'
                """, UUID.randomUUID());
        when(lifecycleService.resolveAndCloseSystem(anyString())).thenAnswer(invocation ->
                new PaymentLifecycleService.CloseOrderResult(
                        invocation.getArgument(0), "CLOSED", null, false));

        assertThat(expiredOrderJob.runOnce()).isEqualTo(2);

        verify(lifecycleService, times(2)).resolveAndCloseSystem(anyString());
        assertThat(singleLong("SELECT count(*) FROM platform.customer_order "
                + "WHERE close_attempt_count = 1")).isEqualTo(2);
    }

    @Test
    void expiredOrderRetriesUseExponentialBackoffAndStopAtTheMaximum() {
        insertOrder("ord_backoff", true);
        when(lifecycleService.resolveAndCloseSystem("ord_backoff")).thenReturn(
                new PaymentLifecycleService.CloseOrderResult(
                        "ord_backoff", "PENDING", "PENDING", true));

        assertThat(expiredOrderJob.runOnce()).isEqualTo(1);
        Duration firstDelay = durationUntil("customer_order", "next_close_at", "order_no", "ord_backoff");
        assertThat(firstDelay).isBetween(Duration.ofSeconds(3), Duration.ofSeconds(7));

        jdbcTemplate.update("UPDATE platform.customer_order "
                + "SET next_close_at = now() - interval '1 second' "
                + "WHERE order_no = 'ord_backoff'");
        assertThat(expiredOrderJob.runOnce()).isEqualTo(1);
        Duration secondDelay = durationUntil(
                "customer_order", "next_close_at", "order_no", "ord_backoff");
        assertThat(secondDelay).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(12));

        jdbcTemplate.update("""
                UPDATE platform.customer_order
                SET next_close_at = now() - interval '1 second', close_attempt_count = 2
                WHERE order_no = 'ord_backoff'
                """);
        assertThat(expiredOrderJob.runOnce()).isEqualTo(1);
        assertThat(singleLong("""
                SELECT count(*) FROM platform.payment_anomaly
                WHERE anomaly_type = 'ORDER_CLOSE_EXHAUSTED'
                """)).isEqualTo(1);
        assertThat(singleString("SELECT next_close_at::text FROM platform.customer_order "
                + "WHERE order_no = 'ord_backoff'")).isEqualTo("infinity");
    }

    @Test
    void compensationWorkersClaimOnceRecoverLeasesAndBackOff() throws Exception {
        insertOrder("ord_comp_1", false);
        insertOrder("ord_comp_2", false);
        insertTransaction("pay_comp_1", "ord_comp_1");
        insertTransaction("pay_comp_2", "ord_comp_2");
        jdbcTemplate.update("""
                UPDATE platform.payment_transaction
                SET job_claim_token = ?, job_claimed_until = now() - interval '1 second'
                WHERE transaction_no = 'pay_comp_1'
                """, UUID.randomUUID());
        when(lifecycleService.queryForCompensation(anyString())).thenAnswer(invocation -> {
            String transactionNo = invocation.getArgument(0);
            updateFromIndependentConnection(
                    "UPDATE platform.payment_transaction SET updated_at = now() "
                            + "WHERE transaction_no = ?",
                    transactionNo);
            return new PaymentLifecycleService.PaymentQueryResult(
                    transactionNo, "PENDING", true);
        });
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = List.of(
                    executor.submit(() -> {
                        start.await();
                        return compensationJob.runOnce();
                    }),
                    executor.submit(() -> {
                        start.await();
                        return compensationJob.runOnce();
                    }));
            start.countDown();
            assertThat(futures.get(0).get() + futures.get(1).get()).isEqualTo(2);
        }

        ArgumentCaptor<String> transactions = ArgumentCaptor.forClass(String.class);
        verify(lifecycleService, times(2)).queryForCompensation(transactions.capture());
        assertThat(transactions.getAllValues()).containsExactlyInAnyOrder(
                "pay_comp_1", "pay_comp_2");
        assertThat(singleLong("SELECT count(*) FROM platform.payment_transaction "
                + "WHERE job_claim_token IS NOT NULL")).isZero();
        assertThat(singleLong("SELECT count(*) FROM platform.payment_transaction "
                + "WHERE query_attempt_count = 1")).isEqualTo(2);
        assertThat(durationUntil(
                "payment_transaction", "next_query_at", "transaction_no", "pay_comp_1"))
                .isBetween(Duration.ofSeconds(3), Duration.ofSeconds(7));
    }

    @Test
    void compensationExhaustionCreatesOneOpenAnomalyAndStopsAutomaticMutation() {
        insertOrder("ord_comp_exhausted", false);
        insertTransaction("pay_comp_exhausted", "ord_comp_exhausted");
        jdbcTemplate.update("""
                UPDATE platform.payment_transaction
                SET query_attempt_count = 2,
                    next_query_at = now() - interval '1 second'
                WHERE transaction_no = 'pay_comp_exhausted'
                """);
        when(lifecycleService.queryForCompensation("pay_comp_exhausted")).thenReturn(
                new PaymentLifecycleService.PaymentQueryResult(
                        "pay_comp_exhausted", "PENDING", true));

        assertThat(compensationJob.runOnce()).isEqualTo(1);

        assertThat(singleString("""
                SELECT next_query_at::text FROM platform.payment_transaction
                WHERE transaction_no = 'pay_comp_exhausted'
                """)).isEqualTo("infinity");
        assertThat(singleLong("""
                SELECT count(*) FROM platform.payment_transaction
                WHERE transaction_no = 'pay_comp_exhausted'
                  AND status = 'PENDING'
                  AND query_attempt_count = 3
                  AND job_claim_token IS NULL
                  AND job_claimed_until IS NULL
                """)).isEqualTo(1);
        assertThat(singleLong("""
                SELECT count(*) FROM platform.payment_anomaly
                WHERE anomaly_type = 'PAYMENT_QUERY_EXHAUSTED'
                  AND status = 'OPEN'
                  AND provider = 'MOCK'
                  AND payment_transaction_id = (
                      SELECT id FROM platform.payment_transaction
                      WHERE transaction_no = 'pay_comp_exhausted')
                """)).isEqualTo(1);

        assertThat(compensationJob.runOnce()).isZero();
        verify(lifecycleService).queryForCompensation("pay_comp_exhausted");
    }

    @Test
    void compensationExhaustionSupportsRechargeOwnedPayment() {
        UUID rechargeId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.recharge_order(
                    id, recharge_no, tenant_id, user_id, paid_amount, bonus_amount,
                    credited_amount, currency, status, idempotency_key,
                    expires_at, created_at, updated_at)
                VALUES (?, 'rch_comp_exhausted', ?, ?, 2990, 0, 2990, 'CNY',
                        'PENDING', 'idem_rch_comp_exhausted',
                        now() + interval '15 minutes', now(), now())
                """, rechargeId, TENANT_ID, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, recharge_order_id, provider,
                    amount, status, payment_product, external_trade_no,
                    expires_at, request_payload_hash, next_query_at, query_attempt_count)
                VALUES (?, 'pay_recharge_exhausted', NULL, ?, 'MOCK', 2990,
                        'PENDING', 'MOCK', 'trade-pay_recharge_exhausted',
                        now() + interval '15 minutes', ?, now() - interval '1 second', 2)
                """, UUID.randomUUID(), rechargeId, "f".repeat(64));
        when(lifecycleService.queryForCompensation("pay_recharge_exhausted")).thenReturn(
                new PaymentLifecycleService.PaymentQueryResult(
                        "pay_recharge_exhausted", "PENDING", true));

        assertThat(compensationJob.runOnce()).isEqualTo(1);
        assertThat(singleLong("""
                SELECT count(*) FROM platform.payment_anomaly
                WHERE anomaly_type = 'PAYMENT_QUERY_EXHAUSTED'
                  AND payment_transaction_id = (
                      SELECT id FROM platform.payment_transaction
                      WHERE transaction_no = 'pay_recharge_exhausted')
                """)).isEqualTo(1);
    }

    private void insertOrder(String orderNo, boolean expired) {
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, package_snapshot,
                    expires_at, next_close_at)
                VALUES (?, ?, ?, ?, ?, 2990, 'CNY', 'PENDING', 'MOCK', '{}'::jsonb,
                        CASE WHEN ? THEN now() - interval '1 minute'
                             ELSE now() + interval '15 minutes' END,
                        now() - interval '1 second')
                """, UUID.randomUUID(), orderNo, TENANT_ID, USER_ID, VERSION_ID, expired);
    }

    private void insertTransaction(String transactionNo, String orderNo) {
        jdbcTemplate.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, external_trade_no, expires_at,
                    request_payload_hash, next_query_at)
                SELECT ?, ?, id, 'MOCK', amount, 'PENDING', 'MOCK', ?,
                       now() + interval '15 minutes', ?, now() - interval '1 second'
                FROM platform.customer_order WHERE order_no = ?
                """, UUID.randomUUID(), transactionNo, "trade-" + transactionNo,
                "f".repeat(64), orderNo);
    }

    private void updateFromIndependentConnection(String sql, String identifier)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement timeout = connection.prepareStatement(
                        "SET statement_timeout = '2s'");
                PreparedStatement update = connection.prepareStatement(sql)) {
            timeout.execute();
            update.setString(1, identifier);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private Duration durationUntil(
            String table, String timestampColumn, String keyColumn, String key) {
        OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT " + timestampColumn + " FROM platform." + table
                        + " WHERE " + keyColumn + " = ?",
                OffsetDateTime.class, key);
        return Duration.between(OffsetDateTime.now(), value);
    }

    private long singleLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private String singleString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }
}
