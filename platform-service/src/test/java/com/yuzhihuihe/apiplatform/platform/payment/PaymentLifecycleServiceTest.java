package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
        "payment.jobs.expired-order-delay-ms=3600000",
        "payment.jobs.compensation-delay-ms=3600000"
})
class PaymentLifecycleServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("94000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("94000000-0000-4000-8000-000000000002");
    private static final UUID VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final Instant PAID_AT = Instant.parse("2030-03-04T05:06:07Z");

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
    PaymentLifecycleService service;

    @MockitoBean
    PaymentAvailabilityService availabilityService;

    @MockitoBean
    PaymentPostingService postingService;

    PaymentAdapter adapter;

    @BeforeEach
    void resetState() {
        reset(availabilityService, postingService);
        adapter = org.mockito.Mockito.mock(PaymentAdapter.class);
        when(availabilityService.requireOperational(any(), any())).thenReturn(adapter);
        jdbcTemplate.update("TRUNCATE platform.payment_anomaly, platform.admin_audit_log, "
                + "platform.outbox_event, platform.grant_request, platform.refund, "
                + "platform.payment_transaction, platform.customer_order, "
                + "platform.refresh_token, platform.user_role, platform.invitation, "
                + "platform.app_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'lifecycle@example.com', 'hash', 'Lifecycle User', 'ACTIVE')
                """, USER_ID, TENANT_ID);
    }

    @Test
    void querySuccessPostsThroughTheAtomicPostingServiceAndNeverCloses() {
        insertOrder("ord_query_success", "PENDING");
        insertTransaction("pay_query_success", "ord_query_success", "PENDING");
        PaymentAdapter.ProviderPayment payment = payment(
                ProviderOutcome.SUCCEEDED, "pay_query_success", "trade-query-success");
        when(adapter.queryPayment(paymentQuery("pay_query_success"))).thenReturn(payment);
        when(postingService.postSucceeded(payment)).thenReturn(
                new PaymentPostingService.PostingResult(
                        "ord_query_success", "PAID", "pay_query_success", true, false));

        PaymentLifecycleService.CloseOrderResult result = service.resolveAndClose(
                "ord_query_success", actor());

        assertThat(result).isEqualTo(new PaymentLifecycleService.CloseOrderResult(
                "ord_query_success", "PAID", "SUCCEEDED", false));
        verify(postingService).postSucceeded(payment);
        verify(adapter, never()).closePayment(any());
    }

    @Test
    void compensationQueriesRechargeOwnedPaymentWithoutExposingItToPackageClose() {
        UUID rechargeId = insertRecharge("rch_query_success");
        insertRechargeTransaction("pay_recharge_query", rechargeId, "PENDING");
        PaymentAdapter.ProviderPayment payment = payment(
                ProviderOutcome.SUCCEEDED, "pay_recharge_query", "trade-recharge-query");
        when(adapter.queryPayment(paymentQuery("pay_recharge_query"))).thenReturn(payment);
        when(postingService.postSucceeded(payment)).thenReturn(
                new PaymentPostingService.PostingResult(
                        "rch_query_success", "PAID", "pay_recharge_query", true, false));

        assertThat(service.queryForCompensation("pay_recharge_query"))
                .isEqualTo(new PaymentLifecycleService.PaymentQueryResult(
                        "pay_recharge_query", "SUCCEEDED", false));
        verify(postingService).postSucceeded(payment);

        assertThatThrownBy(() -> service.resolveAndCloseSystem("rch_query_success"))
                .hasMessageContaining("order_not_found");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM platform.recharge_order WHERE id = ?
                """, String.class, rechargeId)).isEqualTo("PENDING");
    }

    @Test
    void rechargeWithoutPaymentAttemptClosesImmediately() {
        insertRecharge("rch_no_attempt");

        PaymentLifecycleService.CloseOrderResult result =
                service.resolveAndCloseRecharge("rch_no_attempt", actor());

        assertThat(result).isEqualTo(new PaymentLifecycleService.CloseOrderResult(
                "rch_no_attempt", "CLOSED", null, false));
        assertThat(rechargeStatus("rch_no_attempt")).isEqualTo("CLOSED");
        assertThat(singleLong("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'RECHARGE_ORDER_CLOSED'
                  AND resource_id = 'rch_no_attempt'
                """)).isEqualTo(1);
        verify(adapter, never()).queryPayment(any());
    }

    @Test
    void definitiveUnpaidRechargeClosesAttemptAndRecharge() {
        UUID rechargeId = insertRecharge("rch_confirmed_unpaid");
        insertRechargeTransaction("pay_recharge_unpaid", rechargeId, "PENDING");
        when(adapter.queryPayment(paymentQuery("pay_recharge_unpaid"))).thenReturn(
                payment(ProviderOutcome.CLOSED, "pay_recharge_unpaid", null));

        PaymentLifecycleService.CloseOrderResult result =
                service.resolveAndCloseRecharge("rch_confirmed_unpaid", actor());

        assertThat(result).isEqualTo(new PaymentLifecycleService.CloseOrderResult(
                "rch_confirmed_unpaid", "CLOSED", "CLOSED", false));
        assertThat(rechargeStatus("rch_confirmed_unpaid")).isEqualTo("CLOSED");
        assertThat(transactionStatus("pay_recharge_unpaid")).isEqualTo("CLOSED");
        assertThat(singleLong("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'RECHARGE_ORDER_CLOSED'
                  AND resource_id = 'rch_confirmed_unpaid'
                """)).isEqualTo(1);
        verify(adapter, never()).closePayment(any());
    }

    @Test
    void pendingRechargePaymentKeepsRechargeOpenAndSchedulesRetry() {
        UUID rechargeId = insertRecharge("rch_still_pending");
        insertRechargeTransaction("pay_recharge_pending", rechargeId, "PENDING");
        when(adapter.queryPayment(paymentQuery("pay_recharge_pending"))).thenReturn(
                payment(ProviderOutcome.PENDING, "pay_recharge_pending", null));

        PaymentLifecycleService.CloseOrderResult result =
                service.resolveAndCloseRecharge("rch_still_pending", actor());

        assertThat(result).isEqualTo(new PaymentLifecycleService.CloseOrderResult(
                "rch_still_pending", "PENDING", "PENDING", true));
        assertThat(rechargeStatus("rch_still_pending")).isEqualTo("PENDING");
        assertThat(transactionStatus("pay_recharge_pending")).isEqualTo("PENDING");
        assertThat(singleLong("SELECT count(*) FROM platform.admin_audit_log")).isZero();
        verify(adapter, never()).closePayment(any());
    }

    @Test
    void queryPendingKeepsTheOrderAndAttemptPending() {
        insertOrder("ord_query_pending", "PENDING");
        insertTransaction("pay_query_pending", "ord_query_pending", "PENDING");
        when(adapter.queryPayment(paymentQuery("pay_query_pending"))).thenReturn(
                payment(ProviderOutcome.PENDING, "pay_query_pending", null));

        PaymentLifecycleService.CloseOrderResult result = service.resolveAndClose(
                "ord_query_pending", actor());

        assertThat(result.orderStatus()).isEqualTo("PENDING");
        assertThat(result.paymentStatus()).isEqualTo("PENDING");
        assertThat(result.retryScheduled()).isTrue();
        assertThat(orderStatus("ord_query_pending")).isEqualTo("PENDING");
        assertThat(transactionStatus("pay_query_pending")).isEqualTo("PENDING");
        verify(adapter, never()).closePayment(any());
    }

    @ParameterizedTest
    @EnumSource(value = ProviderOutcome.class, names = {"FAILED", "CLOSED", "NOT_FOUND"})
    void definitiveUnpaidQueryClosesTheAttemptAndOrder(ProviderOutcome outcome) {
        String suffix = outcome.name().toLowerCase();
        String orderNo = "ord_query_" + suffix;
        String transactionNo = "pay_query_" + suffix;
        insertOrder(orderNo, "PENDING");
        insertTransaction(transactionNo, orderNo, "PENDING");
        when(adapter.queryPayment(paymentQuery(transactionNo))).thenReturn(
                payment(outcome, transactionNo, null));
        if (outcome == ProviderOutcome.FAILED) {
            when(adapter.closePayment(paymentQuery(transactionNo))).thenReturn(
                    payment(ProviderOutcome.CLOSED, transactionNo, null));
        }

        PaymentLifecycleService.CloseOrderResult result =
                service.resolveAndClose(orderNo, actor());

        assertThat(result.orderStatus()).isEqualTo("CLOSED");
        assertThat(result.paymentStatus()).isEqualTo("CLOSED");
        assertThat(result.retryScheduled()).isFalse();
        assertThat(orderStatus(orderNo)).isEqualTo("CLOSED");
        assertThat(transactionStatus(transactionNo)).isEqualTo("CLOSED");
        if (outcome == ProviderOutcome.FAILED) {
            verify(adapter).closePayment(paymentQuery(transactionNo));
        } else {
            verify(adapter, never()).closePayment(any());
        }
    }

    @Test
    void successDiscoveredByClosePostsThroughTheAtomicPostingService() {
        insertOrder("ord_close_success", "PENDING");
        insertTransaction("pay_close_success", "ord_close_success", "PENDING");
        when(adapter.queryPayment(paymentQuery("pay_close_success"))).thenReturn(
                payment(ProviderOutcome.FAILED, "pay_close_success", null));
        PaymentAdapter.ProviderPayment succeeded = payment(
                ProviderOutcome.SUCCEEDED, "pay_close_success", "trade-close-success");
        when(adapter.closePayment(paymentQuery("pay_close_success"))).thenReturn(succeeded);
        when(postingService.postSucceeded(succeeded)).thenReturn(
                new PaymentPostingService.PostingResult(
                        "ord_close_success", "PAID", "pay_close_success", true, false));

        PaymentLifecycleService.CloseOrderResult result = service.resolveAndClose(
                "ord_close_success", actor());

        assertThat(result).isEqualTo(new PaymentLifecycleService.CloseOrderResult(
                "ord_close_success", "PAID", "SUCCEEDED", false));
        verify(postingService).postSucceeded(succeeded);
        assertThat(singleLong("SELECT count(*) FROM platform.payment_anomaly")).isZero();
    }

    @Test
    void uncertainQueryAndTimeoutRetainPendingStateAndDeduplicateAnomalies() {
        insertOrder("ord_query_uncertain", "PENDING");
        insertTransaction("pay_query_uncertain", "ord_query_uncertain", "PENDING");
        when(adapter.queryPayment(paymentQuery("pay_query_uncertain")))
                .thenReturn(payment(ProviderOutcome.UNCERTAIN, "pay_query_uncertain", null))
                .thenThrow(new RuntimeException("provider timeout with secret response"));

        PaymentLifecycleService.CloseOrderResult first = service.resolveAndClose(
                "ord_query_uncertain", actor());
        PaymentLifecycleService.CloseOrderResult second = service.resolveAndClose(
                "ord_query_uncertain", actor());

        assertThat(first.retryScheduled()).isTrue();
        assertThat(second.retryScheduled()).isTrue();
        assertThat(orderStatus("ord_query_uncertain")).isEqualTo("PENDING");
        assertThat(transactionStatus("pay_query_uncertain")).isEqualTo("PENDING");
        assertThat(singleLong("""
                SELECT count(*) FROM platform.payment_anomaly
                WHERE anomaly_type = 'PAYMENT_QUERY_UNCERTAIN'
                  AND detail = '{"reason":"QUERY_UNCERTAIN"}'::jsonb
                  AND detail::text NOT LIKE '%secret%'
                """)).isEqualTo(1);
    }

    @Test
    void uncertainCloseAndTimeoutRetainPendingStateAndDeduplicateAnomalies() {
        insertOrder("ord_close_uncertain", "PENDING");
        insertTransaction("pay_close_uncertain", "ord_close_uncertain", "PENDING");
        when(adapter.queryPayment(paymentQuery("pay_close_uncertain")))
                .thenReturn(payment(ProviderOutcome.FAILED, "pay_close_uncertain", null));
        when(adapter.closePayment(paymentQuery("pay_close_uncertain")))
                .thenReturn(payment(ProviderOutcome.UNCERTAIN, "pay_close_uncertain", null))
                .thenThrow(new RuntimeException("provider timeout with secret response"));

        PaymentLifecycleService.CloseOrderResult first = service.resolveAndClose(
                "ord_close_uncertain", actor());
        PaymentLifecycleService.CloseOrderResult second = service.resolveAndClose(
                "ord_close_uncertain", actor());

        assertThat(first.retryScheduled()).isTrue();
        assertThat(second.retryScheduled()).isTrue();
        assertThat(orderStatus("ord_close_uncertain")).isEqualTo("PENDING");
        assertThat(transactionStatus("pay_close_uncertain")).isEqualTo("PENDING");
        assertThat(singleLong("""
                SELECT count(*) FROM platform.payment_anomaly
                WHERE anomaly_type = 'PAYMENT_CLOSE_UNCERTAIN'
                  AND provider = 'MOCK'
                  AND payment_transaction_id = (
                      SELECT id FROM platform.payment_transaction
                      WHERE transaction_no = 'pay_close_uncertain')
                  AND detail = '{"reason":"CLOSE_UNCERTAIN"}'::jsonb
                  AND detail::text NOT LIKE '%secret%'
                """)).isEqualTo(1);
    }

    @Test
    void callbackWinningWhileProviderCloseIsInFlightPreventsLocalClose() throws Exception {
        insertOrder("ord_close_race", "PENDING");
        UUID transactionId = insertTransaction(
                "pay_close_race", "ord_close_race", "PENDING");
        when(adapter.queryPayment(paymentQuery("pay_close_race"))).thenReturn(
                payment(ProviderOutcome.FAILED, "pay_close_race", null));
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        when(adapter.closePayment(paymentQuery("pay_close_race"))).thenAnswer(invocation -> {
            closeStarted.countDown();
            releaseClose.await();
            return payment(ProviderOutcome.CLOSED, "pay_close_race", null);
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<PaymentLifecycleService.CloseOrderResult> future = executor.submit(
                    () -> service.resolveAndClose("ord_close_race", actor()));
            closeStarted.await();
            jdbcTemplate.update("""
                    UPDATE platform.payment_transaction
                    SET status = 'SUCCEEDED', applied_to_order = true,
                        external_trade_no = 'race-trade', callback_hash = ?, paid_at = now()
                    WHERE id = ?
                    """, "a".repeat(64), transactionId);
            jdbcTemplate.update("""
                    UPDATE platform.customer_order
                    SET status = 'PAID', paid_at = now(),
                        successful_payment_transaction_id = ?
                    WHERE order_no = 'ord_close_race'
                    """, transactionId);
            releaseClose.countDown();

            PaymentLifecycleService.CloseOrderResult result = future.get();
            assertThat(result.orderStatus()).isEqualTo("PAID");
        }

        assertThat(orderStatus("ord_close_race")).isEqualTo("PAID");
        assertThat(transactionStatus("pay_close_race")).isEqualTo("SUCCEEDED");
    }

    @Test
    void orderWithoutAnyPaymentAttemptClosesLocally() {
        insertOrder("ord_no_attempt", "PENDING");

        PaymentLifecycleService.CloseOrderResult result = service.resolveAndClose(
                "ord_no_attempt", actor());

        assertThat(result).isEqualTo(new PaymentLifecycleService.CloseOrderResult(
                "ord_no_attempt", "CLOSED", null, false));
        assertThat(orderStatus("ord_no_attempt")).isEqualTo("CLOSED");
        verify(availabilityService, never()).requireOperational(any(), any());
    }

    @Test
    void createdAttemptCannotCloseBeforeProviderCreationFinishes() {
        insertOrder("ord_created_attempt", "PENDING");
        insertTransaction("pay_created_attempt", "ord_created_attempt", "CREATED");

        PaymentLifecycleService.CloseOrderResult result = service.resolveAndClose(
                "ord_created_attempt", actor());

        assertThat(result).isEqualTo(new PaymentLifecycleService.CloseOrderResult(
                "ord_created_attempt", "PENDING", "CREATED", true));
        assertThat(orderStatus("ord_created_attempt")).isEqualTo("PENDING");
        assertThat(transactionStatus("pay_created_attempt")).isEqualTo("CREATED");
        verify(availabilityService, never()).requireOperational(any(), any());
    }

    @Test
    void closeRechecksTheLatestAttemptAfterWaitingForOrderSerialization() throws Exception {
        insertOrder("ord_retry_race", "PENDING");
        insertTransaction("pay_retry_old", "ord_retry_race", "FAILED");
        when(adapter.queryPayment(paymentQuery("pay_retry_new"))).thenReturn(
                payment(ProviderOutcome.PENDING, "pay_retry_new", null));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid;
            try (var statement = blocker.prepareStatement("SELECT pg_backend_pid()");
                    var result = statement.executeQuery()) {
                result.next();
                blockerPid = result.getInt(1);
            }
            try (var statement = blocker.prepareStatement("""
                    SELECT id FROM platform.payment_transaction
                    WHERE transaction_no = 'pay_retry_old' FOR UPDATE
                    """)) {
                statement.executeQuery().close();
            }

            Future<PaymentLifecycleService.CloseOrderResult> closing = executor.submit(
                    () -> service.resolveAndClose("ord_retry_race", actor()));
            awaitBlockedBy(blockerPid);
            insertTransaction("pay_retry_new", "ord_retry_race", "PENDING");
            blocker.commit();

            PaymentLifecycleService.CloseOrderResult result = closing.get(10, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(new PaymentLifecycleService.CloseOrderResult(
                    "ord_retry_race", "PENDING", "PENDING", true));
        } finally {
            executor.shutdownNow();
        }

        assertThat(orderStatus("ord_retry_race")).isEqualTo("PENDING");
        assertThat(transactionStatus("pay_retry_new")).isEqualTo("PENDING");
        verify(adapter).queryPayment(paymentQuery("pay_retry_new"));
    }

    @Test
    void queryRejectsCurrencyCommittedBeforeOrderLockInsteadOfUsingStaleFacts()
            throws Exception {
        insertOrder("ord_currency_refresh", "PENDING");
        insertTransaction("pay_currency_refresh", "ord_currency_refresh", "PENDING");
        when(adapter.queryPayment(paymentQuery("pay_currency_refresh"))).thenReturn(
                payment(ProviderOutcome.PENDING, "pay_currency_refresh", null));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid;
            try (var statement = blocker.prepareStatement("SELECT pg_backend_pid()");
                    var result = statement.executeQuery()) {
                result.next();
                blockerPid = result.getInt(1);
            }
            try (var statement = blocker.prepareStatement("""
                    UPDATE platform.customer_order
                    SET currency = 'USD'
                    WHERE order_no = 'ord_currency_refresh'
                    """)) {
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }

            Future<PaymentLifecycleService.CloseOrderResult> closing = executor.submit(
                    () -> service.resolveAndClose("ord_currency_refresh", actor()));
            awaitBlockedBy(blockerPid);
            blocker.commit();

            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class,
                    () -> closing.get(10, TimeUnit.SECONDS));
            assertThat(failure).hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("currency must be CNY");
        } finally {
            executor.shutdownNow();
        }

        verify(adapter, never()).queryPayment(any());
    }

    private PaymentLifecycleService.Actor actor() {
        return new PaymentLifecycleService.Actor(TENANT_ID, USER_ID);
    }

    private PaymentAdapter.PaymentQuery paymentQuery(String transactionNo) {
        return new PaymentAdapter.PaymentQuery(transactionNo, 2990, "CNY");
    }

    private void insertOrder(String orderNo, String status) {
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, package_snapshot,
                    expires_at, next_close_at)
                VALUES (?, ?, ?, ?, ?, 2990, 'CNY', ?, 'MOCK', '{}'::jsonb,
                        now() - interval '1 minute', now() - interval '1 minute')
                """, UUID.randomUUID(), orderNo, TENANT_ID, USER_ID, VERSION_ID, status);
    }

    private UUID insertTransaction(String transactionNo, String orderNo, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, external_trade_no, expires_at,
                    request_payload_hash, next_query_at)
                SELECT ?, ?, id, 'MOCK', amount, ?, 'MOCK', ?,
                       now() - interval '1 minute', ?, now() - interval '1 minute'
                FROM platform.customer_order WHERE order_no = ?
                """, id, transactionNo, status, "trade-" + transactionNo,
                "f".repeat(64), orderNo);
        return id;
    }

    private UUID insertRecharge(String rechargeNo) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.recharge_order(
                    id, recharge_no, tenant_id, user_id, paid_amount, bonus_amount,
                    credited_amount, currency, status, idempotency_key,
                    expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 2990, 0, 2990, 'CNY', 'PENDING', ?,
                        now() + interval '15 minutes', now(), now())
                """, id, rechargeNo, TENANT_ID, USER_ID, "idem_" + rechargeNo);
        return id;
    }

    private void insertRechargeTransaction(
            String transactionNo, UUID rechargeId, String status) {
        jdbcTemplate.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, recharge_order_id, provider,
                    amount, status, payment_product, external_trade_no,
                    expires_at, request_payload_hash, next_query_at)
                VALUES (?, ?, NULL, ?, 'MOCK', 2990, ?, 'MOCK', ?,
                        now() + interval '15 minutes', ?, now() - interval '1 minute')
                """, UUID.randomUUID(), transactionNo, rechargeId, status,
                "trade-" + transactionNo, "f".repeat(64));
    }

    private PaymentAdapter.ProviderPayment payment(
            ProviderOutcome outcome,
            String transactionNo,
            String externalTradeNo) {
        return new PaymentAdapter.ProviderPayment(
                outcome, transactionNo, externalTradeNo, 2990, "CNY",
                outcome == ProviderOutcome.SUCCEEDED ? PAID_AT : null,
                outcome.name(), null, "b".repeat(64));
    }

    private String orderStatus(String orderNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM platform.customer_order WHERE order_no = ?",
                String.class, orderNo);
    }

    private String transactionStatus(String transactionNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM platform.payment_transaction WHERE transaction_no = ?",
                String.class, transactionNo);
    }

    private String rechargeStatus(String rechargeNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM platform.recharge_order WHERE recharge_no = ?",
                String.class, rechargeNo);
    }

    private long singleLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private void awaitBlockedBy(int blockerPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            boolean blocked = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    SELECT EXISTS(
                        SELECT 1 FROM pg_stat_activity
                        WHERE ? = ANY(pg_blocking_pids(pid)))
                    """, Boolean.class, blockerPid));
            if (blocked) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("close did not block on the expected database row lock");
    }
}
