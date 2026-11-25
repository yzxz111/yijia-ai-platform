package com.yuzhihuihe.apiplatform.platform.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentPostingService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "outbox.publisher.initial-delay-ms=3600000"
})
class BalanceOrderPaymentServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("96000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("96000000-0000-4000-8000-000000000002");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("96000000-0000-4000-8000-000000000003");
    private static final UUID OTHER_TENANT_ID =
            UUID.fromString("96000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("96000000-0000-4000-8000-000000000005");
    private static final UUID OTHER_ACCOUNT_ID =
            UUID.fromString("96000000-0000-4000-8000-000000000006");
    private static final UUID VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final Instant FUTURE = Instant.parse("2030-08-05T08:15:00Z");
    private static final Instant PAST = Instant.parse("2020-08-05T08:15:00Z");
    private static final Instant EXTERNAL_PAID_AT = Instant.parse("2026-08-05T08:00:00Z");

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
    BalanceOrderPaymentService service;

    @Autowired
    PaymentPostingService paymentPostingService;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("TRUNCATE platform.admin_audit_log, platform.outbox_event, "
                + "platform.grant_request, platform.app_user RESTART IDENTITY CASCADE");
        insertUser(USER_ID, TENANT_ID, "wallet-buyer@example.test");
        insertUser(OTHER_USER_ID, OTHER_TENANT_ID, "other-wallet-buyer@example.test");
        insertAccount(ACCOUNT_ID, TENANT_ID, USER_ID, 0);
        insertAccount(OTHER_ACCOUNT_ID, OTHER_TENANT_ID, OTHER_USER_ID, 0);
    }

    @Test
    void exactBalancePaysAtomicallyAndEndsAtZero() {
        UUID orderId = insertOrder("ord_exact_wallet", TENANT_ID, USER_ID, 2_990, FUTURE);
        setBalance(2_990);

        BalanceOrderPaymentService.BalancePaymentResult result = service.pay(
                TENANT_ID, USER_ID, "ord_exact_wallet", "wallet-payment-exact");

        assertThat(result.orderNo()).isEqualTo("ord_exact_wallet");
        assertThat(result.orderStatus()).isEqualTo("PAID");
        assertThat(result.settlementMethod()).isEqualTo("BALANCE");
        assertThat(result.balanceBefore()).isEqualTo(2_990);
        assertThat(result.balanceAfter()).isZero();
        assertThat(result.grantRequestId()).isNotNull();
        assertThat(result.grantStatus()).isEqualTo("QUEUED");
        assertThat(balance()).isZero();

        Map<String, Object> order = jdbcTemplate.queryForMap("""
                SELECT status, paid_at, settlement_method, payment_provider,
                       successful_payment_transaction_id, balance_debit_ledger_id,
                       lock_version
                FROM platform.customer_order WHERE id = ?
                """, orderId);
        assertThat(order)
                .containsEntry("status", "PAID")
                .containsEntry("settlement_method", "BALANCE")
                .containsEntry("payment_provider", "UNSELECTED")
                .containsEntry("successful_payment_transaction_id", null)
                .containsEntry("lock_version", 1L);
        assertThat(order.get("paid_at")).isNotNull();
        assertThat(order.get("balance_debit_ledger_id")).isNotNull();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT entry_type, amount_delta, paid_amount, bonus_amount,
                       balance_before, balance_after, business_type, business_id,
                       business_no, idempotency_key
                FROM platform.balance_ledger
                """))
                .containsEntry("entry_type", "PACKAGE_PURCHASE")
                .containsEntry("amount_delta", -2_990L)
                .containsEntry("paid_amount", 0L)
                .containsEntry("bonus_amount", 0L)
                .containsEntry("balance_before", 2_990L)
                .containsEntry("balance_after", 0L)
                .containsEntry("business_type", "ORDER")
                .containsEntry("business_id", orderId)
                .containsEntry("business_no", "ord_exact_wallet")
                .containsEntry("idempotency_key", "wallet-payment-exact");
        assertThat(count("platform.payment_transaction")).isZero();
        assertPackageSideEffects("ord_exact_wallet", "BALANCE");
    }

    @Test
    void greaterBalanceStoresExactImmutableLedgerSnapshots() {
        UUID orderId = insertOrder("ord_greater_wallet", TENANT_ID, USER_ID, 2_990, FUTURE);
        setBalance(10_000);

        BalanceOrderPaymentService.BalancePaymentResult result = service.pay(
                TENANT_ID, USER_ID, "ord_greater_wallet", "wallet-payment-greater");

        assertThat(result.balanceBefore()).isEqualTo(10_000);
        assertThat(result.balanceAfter()).isEqualTo(7_010);
        assertThat(balance()).isEqualTo(7_010);
        UUID ledgerId = jdbcTemplate.queryForObject("""
                SELECT balance_debit_ledger_id
                FROM platform.customer_order WHERE id = ?
                """, UUID.class, orderId);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE platform.balance_ledger SET balance_after = 1 WHERE id = ?
                """, ledgerId)).isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT balance_after FROM platform.balance_ledger WHERE id = ?
                """, Long.class, ledgerId)).isEqualTo(7_010);
        assertThat(count("platform.balance_ledger")).isEqualTo(1);
    }

    @Test
    void insufficientBalanceLeavesEverySettlementFactUntouchedAndOrderExternallyPayable() {
        insertOrder("ord_insufficient_wallet", TENANT_ID, USER_ID, 2_990, FUTURE);
        setBalance(2_989);

        assertStatusReason(
                () -> service.pay(TENANT_ID, USER_ID, "ord_insufficient_wallet", "wallet-low"),
                HttpStatus.UNPROCESSABLE_ENTITY, "wallet_insufficient_balance");

        assertThat(balance()).isEqualTo(2_989);
        assertThat(accountVersion()).isZero();
        assertThat(count("platform.balance_ledger")).isZero();
        assertThat(count("platform.grant_request")).isZero();
        assertThat(count("platform.outbox_event")).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, settlement_method, payment_provider, paid_at,
                       balance_debit_ledger_id
                FROM platform.customer_order WHERE order_no = 'ord_insufficient_wallet'
                """))
                .containsEntry("status", "PENDING")
                .containsEntry("settlement_method", "UNSELECTED")
                .containsEntry("payment_provider", "UNSELECTED")
                .containsEntry("paid_at", null)
                .containsEntry("balance_debit_ledger_id", null);
    }

    @Test
    void over128Utf8ByteIdempotencyKeyIsRejectedWithoutSettlementMutation() {
        insertOrder("ord_wallet_long_key", TENANT_ID, USER_ID, 2_990, FUTURE);
        setBalance(10_000);

        assertStatusReason(
                () -> service.pay(TENANT_ID, USER_ID,
                        "ord_wallet_long_key", "\u4e2d".repeat(64)),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_idempotency_key");

        assertThat(balance()).isEqualTo(10_000);
        assertThat(accountVersion()).isZero();
        assertThat(count("platform.balance_ledger")).isZero();
        assertThat(count("platform.grant_request")).isZero();
        assertThat(count("platform.outbox_event")).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, settlement_method, paid_at,
                       balance_debit_ledger_id, lock_version
                FROM platform.customer_order
                WHERE order_no = 'ord_wallet_long_key'
                """))
                .containsEntry("status", "PENDING")
                .containsEntry("settlement_method", "UNSELECTED")
                .containsEntry("paid_at", null)
                .containsEntry("balance_debit_ledger_id", null)
                .containsEntry("lock_version", 0L);
    }

    @Test
    void missingAndCrossScopeOrdersAreHiddenAsNotFound() {
        insertOrder("ord_owned_elsewhere", OTHER_TENANT_ID, OTHER_USER_ID, 2_990, FUTURE);
        setBalance(10_000);

        assertStatusReason(
                () -> service.pay(TENANT_ID, USER_ID, "ord_missing", "wallet-missing"),
                HttpStatus.NOT_FOUND, "order_not_found");
        assertStatusReason(
                () -> service.pay(TENANT_ID, USER_ID,
                        "ord_owned_elsewhere", "wallet-cross-scope"),
                HttpStatus.NOT_FOUND, "order_not_found");
        assertThat(balance()).isEqualTo(10_000);
        assertThat(count("platform.balance_ledger")).isZero();
    }

    @Test
    void expiredClosedAndExternallyPaidOrdersCannotDebitTheWallet() {
        insertOrder("ord_expired_wallet", TENANT_ID, USER_ID, 2_990, PAST);
        insertOrder("ord_closed_wallet", TENANT_ID, USER_ID, 2_990, FUTURE);
        jdbcTemplate.update("""
                UPDATE platform.customer_order
                SET status = 'CLOSED', closed_at = now()
                WHERE order_no = 'ord_closed_wallet'
                """);
        insertOrder("ord_external_paid", TENANT_ID, USER_ID, 2_990, FUTURE);
        jdbcTemplate.update("""
                UPDATE platform.customer_order
                SET status = 'PAID', paid_at = ?, settlement_method = 'EXTERNAL',
                    payment_provider = 'MOCK', lock_version = lock_version + 1
                WHERE order_no = 'ord_external_paid'
                """, Timestamp.from(EXTERNAL_PAID_AT));
        setBalance(10_000);

        assertStatusReason(
                () -> service.pay(TENANT_ID, USER_ID,
                        "ord_expired_wallet", "wallet-expired"),
                HttpStatus.CONFLICT, "order_expired");
        assertStatusReason(
                () -> service.pay(TENANT_ID, USER_ID,
                        "ord_closed_wallet", "wallet-closed"),
                HttpStatus.CONFLICT, "order_not_payable");
        assertStatusReason(
                () -> service.pay(TENANT_ID, USER_ID,
                        "ord_external_paid", "wallet-external-paid"),
                HttpStatus.CONFLICT, "order_not_payable");
        assertThat(balance()).isEqualTo(10_000);
        assertThat(count("platform.balance_ledger")).isZero();
        assertThat(count("platform.grant_request")).isZero();
    }

    @Test
    void exactReplayReturnsTheSameResultWithoutASecondDebitOrFulfillment() {
        insertOrder("ord_wallet_replay", TENANT_ID, USER_ID, 2_990, FUTURE);
        setBalance(10_000);

        BalanceOrderPaymentService.BalancePaymentResult first = service.pay(
                TENANT_ID, USER_ID, "ord_wallet_replay", "wallet-replay-key");
        BalanceOrderPaymentService.BalancePaymentResult replay = service.pay(
                TENANT_ID, USER_ID, "ord_wallet_replay", "wallet-replay-key");

        assertThat(replay).isEqualTo(first);
        assertThat(balance()).isEqualTo(7_010);
        assertThat(accountVersion()).isEqualTo(1);
        assertThat(count("platform.balance_ledger")).isEqualTo(1);
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'PAYMENT_POSTED'
                """, Long.class)).isEqualTo(1);
    }

    @Test
    void replayPreservesOriginalResponseAfterGrantBecomesActive() {
        insertOrder("ord_wallet_active_replay", TENANT_ID, USER_ID, 2_990, FUTURE);
        setBalance(10_000);

        BalanceOrderPaymentService.BalancePaymentResult first = service.pay(
                TENANT_ID, USER_ID,
                "ord_wallet_active_replay", "wallet-active-replay-key");
        assertThat(first.grantStatus()).isEqualTo("QUEUED");
        jdbcTemplate.update("""
                UPDATE platform.grant_request
                SET status = 'ACTIVE', completed_at = now(), updated_at = now()
                WHERE id = ? AND status = 'QUEUED'
                """, first.grantRequestId());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM platform.grant_request WHERE id = ?
                """, String.class, first.grantRequestId())).isEqualTo("ACTIVE");

        BalanceOrderPaymentService.BalancePaymentResult replay = service.pay(
                TENANT_ID, USER_ID,
                "ord_wallet_active_replay", "wallet-active-replay-key");

        assertThat(replay).isEqualTo(first);
        assertThat(balance()).isEqualTo(7_010);
        assertThat(accountVersion()).isEqualTo(1);
        assertThat(count("platform.balance_ledger")).isEqualTo(1);
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
    }

    @Test
    void idempotencyKeyOrOrderPayloadReuseConflictsWithoutMutation() {
        insertOrder("ord_wallet_key_one", TENANT_ID, USER_ID, 2_990, FUTURE);
        insertOrder("ord_wallet_key_two", TENANT_ID, USER_ID, 1_990, FUTURE);
        setBalance(20_000);
        service.pay(TENANT_ID, USER_ID, "ord_wallet_key_one", "shared-wallet-key");

        assertStatusReason(
                () -> service.pay(TENANT_ID, USER_ID,
                        "ord_wallet_key_one", "different-wallet-key"),
                HttpStatus.CONFLICT, "wallet_payment_idempotency_payload_mismatch");
        assertStatusReason(
                () -> service.pay(TENANT_ID, USER_ID,
                        "ord_wallet_key_two", "shared-wallet-key"),
                HttpStatus.CONFLICT, "wallet_payment_idempotency_payload_mismatch");

        assertThat(balance()).isEqualTo(17_010);
        assertThat(count("platform.balance_ledger")).isEqualTo(1);
        assertThat(singleString("SELECT status FROM platform.customer_order "
                + "WHERE order_no = 'ord_wallet_key_two'"))
                .isEqualTo("PENDING");
        assertThat(count("platform.grant_request")).isEqualTo(1);
    }

    @Test
    void concurrentWalletReplaysSerializeOnOrderBeforeAccountAndSettleOnce() throws Exception {
        insertOrder("ord_wallet_concurrent", TENANT_ID, USER_ID, 2_990, FUTURE);
        setBalance(10_000);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<BalanceOrderPaymentService.BalancePaymentResult>> futures = List.of(
                    executor.submit(() -> payAfter(ready, start,
                            "ord_wallet_concurrent", "wallet-concurrent-key")),
                    executor.submit(() -> payAfter(ready, start,
                            "ord_wallet_concurrent", "wallet-concurrent-key")));
            ready.await();
            start.countDown();

            assertThat(futures.get(0).get()).isEqualTo(futures.get(1).get());
        }

        assertThat(balance()).isEqualTo(7_010);
        assertThat(accountVersion()).isEqualTo(1);
        assertThat(count("platform.balance_ledger")).isEqualTo(1);
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
    }

    @Test
    void externalAndWalletCompetitionCannotDoubleSettleOrOverdraw() throws Exception {
        UUID orderId = insertOrder("ord_wallet_external_race", TENANT_ID, USER_ID, 2_990, FUTURE);
        insertTransaction(orderId, "pay_wallet_external_race", 2_990);
        setBalance(10_000);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        WalletAttempt wallet;
        PaymentPostingService.PostingResult external;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<WalletAttempt> walletFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return new WalletAttempt(service.pay(
                            TENANT_ID, USER_ID, "ord_wallet_external_race", "wallet-race"), null);
                } catch (RuntimeException error) {
                    return new WalletAttempt(null, error);
                }
            });
            Future<PaymentPostingService.PostingResult> externalFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return paymentPostingService.postSucceeded(
                        PaymentProvider.MOCK,
                        providerPayment("pay_wallet_external_race", 2_990));
            });
            ready.await();
            start.countDown();
            wallet = walletFuture.get();
            external = externalFuture.get();
        }

        if (wallet.result() != null) {
            assertThat(external.appliedToOrder()).isFalse();
            assertThat(balance()).isEqualTo(7_010);
            assertThat(singleString("SELECT settlement_method FROM platform.customer_order "
                    + "WHERE id = '" + orderId + "'"))
                    .isEqualTo("BALANCE");
            assertThat(count("platform.balance_ledger")).isEqualTo(1);
        } else {
            assertThat(wallet.error()).isInstanceOfSatisfying(
                    ResponseStatusException.class,
                    error -> assertThat(error.getReason()).isEqualTo("order_not_payable"));
            assertThat(external.appliedToOrder()).isTrue();
            assertThat(balance()).isEqualTo(10_000);
            assertThat(singleString("SELECT settlement_method FROM platform.customer_order "
                    + "WHERE id = '" + orderId + "'"))
                    .isEqualTo("EXTERNAL");
            assertThat(count("platform.balance_ledger")).isZero();
        }
        assertThat(balance()).isGreaterThanOrEqualTo(0);
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'PAYMENT_POSTED'
                """, Long.class)).isEqualTo(1);
    }

    @Test
    void fulfillmentFailureRollsBackOrderDebitLedgerGrantOutboxAndAuditTogether() {
        insertOrder("ord_wallet_rollback", TENANT_ID, USER_ID, 2_990, FUTURE);
        setBalance(10_000);
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION platform.reject_wallet_payment_outbox()
                RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'forced_wallet_payment_outbox_failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_wallet_payment_outbox
                BEFORE INSERT ON platform.outbox_event
                FOR EACH ROW EXECUTE FUNCTION platform.reject_wallet_payment_outbox()
                """);
        try {
            assertThatThrownBy(() -> service.pay(
                    TENANT_ID, USER_ID, "ord_wallet_rollback", "wallet-rollback"))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_wallet_payment_outbox "
                    + "ON platform.outbox_event");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS "
                    + "platform.reject_wallet_payment_outbox()");
        }

        assertThat(balance()).isEqualTo(10_000);
        assertThat(accountVersion()).isZero();
        assertThat(count("platform.balance_ledger")).isZero();
        assertThat(count("platform.grant_request")).isZero();
        assertThat(count("platform.outbox_event")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action IN ('PAYMENT_POSTED', 'PACKAGE_GRANT_CREATED')
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, paid_at, settlement_method, balance_debit_ledger_id,
                       lock_version
                FROM platform.customer_order WHERE order_no = 'ord_wallet_rollback'
                """))
                .containsEntry("status", "PENDING")
                .containsEntry("paid_at", null)
                .containsEntry("settlement_method", "UNSELECTED")
                .containsEntry("balance_debit_ledger_id", null)
                .containsEntry("lock_version", 0L);
    }

    private BalanceOrderPaymentService.BalancePaymentResult payAfter(
            CountDownLatch ready, CountDownLatch start,
            String orderNo, String idempotencyKey) throws InterruptedException {
        ready.countDown();
        start.await();
        return service.pay(TENANT_ID, USER_ID, orderNo, idempotencyKey);
    }

    private void assertPackageSideEffects(String orderNo, String settlementMethod) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT source, reason, status
                FROM platform.grant_request WHERE business_no = ?
                """, "order:" + orderNo))
                .containsEntry("source", "ORDER")
                .containsEntry("reason", "payment posted")
                .containsEntry("status", "QUEUED");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT aggregate_type, event_type, business_key
                FROM platform.outbox_event WHERE business_key = ?
                """, "order:" + orderNo))
                .containsEntry("aggregate_type", "GRANT")
                .containsEntry("event_type", "PACKAGE_GRANTED_V1")
                .containsEntry("business_key", "order:" + orderNo);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'PAYMENT_POSTED'
                  AND detail->>'settlementMethod' = ?
                """, Long.class, settlementMethod)).isEqualTo(1);
        assertThat(count("platform.sms_delivery")).isZero();
    }

    private void assertStatusReason(
            Runnable call, HttpStatus status, String reason) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(status.value());
                    assertThat(error.getReason()).isEqualTo(reason);
                });
    }

    private void insertUser(UUID userId, UUID tenantId, String email) {
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'hash', 'Wallet Buyer', 'ACTIVE')
                """, userId, tenantId, email);
    }

    private void insertAccount(
            UUID accountId, UUID tenantId, UUID userId, long amount) {
        jdbcTemplate.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, version)
                VALUES (?, ?, ?, 'CNY', ?, 0)
                """, accountId, tenantId, userId, amount);
    }

    private UUID insertOrder(
            String orderNo, UUID tenantId, UUID userId,
            long amount, Instant expiresAt) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, package_snapshot,
                    idempotency_key, expires_at, next_close_at)
                VALUES (?, ?, ?, ?, ?, ?, 'CNY', 'PENDING', 'UNSELECTED', ?::jsonb,
                        ?, ?, ?)
                """, orderId, orderNo, tenantId, userId, VERSION_ID, amount,
                snapshot(amount), "create:" + orderNo,
                Timestamp.from(expiresAt), Timestamp.from(expiresAt));
        return orderId;
    }

    private void insertTransaction(UUID orderId, String transactionNo, long amount) {
        String requestHash = sha256(String.join("\n",
                transactionNo, "ord_wallet_external_race", Long.toString(amount), "CNY",
                "MOCK", "MOCK", FUTURE.toString()));
        jdbcTemplate.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, expires_at, request_payload_hash)
                VALUES (?, ?, ?, 'MOCK', ?, 'PENDING', 'MOCK', ?, ?)
                """, UUID.randomUUID(), transactionNo, orderId, amount,
                Timestamp.from(FUTURE), requestHash);
    }

    private PaymentAdapter.ProviderPayment providerPayment(String transactionNo, long amount) {
        return new PaymentAdapter.ProviderPayment(
                ProviderOutcome.SUCCEEDED, transactionNo, "trade-" + transactionNo,
                amount, "CNY", EXTERNAL_PAID_AT, "SUCCESS", null, "b".repeat(64));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("test_payment_hash_failed", exception);
        }
    }

    private void setBalance(long amount) {
        jdbcTemplate.update("""
                UPDATE platform.account_balance
                SET available_amount = ?, version = 0, updated_at = now()
                WHERE tenant_id = ? AND user_id = ? AND id = ?
                """, amount, TENANT_ID, USER_ID, ACCOUNT_ID);
    }

    private long balance() {
        return jdbcTemplate.queryForObject("""
                SELECT available_amount FROM platform.account_balance
                WHERE tenant_id = ? AND user_id = ? AND id = ?
                """, Long.class, TENANT_ID, USER_ID, ACCOUNT_ID);
    }

    private long accountVersion() {
        return jdbcTemplate.queryForObject("""
                SELECT version FROM platform.account_balance
                WHERE tenant_id = ? AND user_id = ? AND id = ?
                """, Long.class, TENANT_ID, USER_ID, ACCOUNT_ID);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private String singleString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private String snapshot(long priceAmount) {
        return """
                {
                  "packageVersionId":"%s",
                  "packageCode":"STARTER",
                  "packageName":"Starter",
                  "priceAmount":%d,
                  "currency":"CNY",
                  "totalTokenQuota":5000000,
                  "durationDays":30,
                  "maxApiKeys":3,
                  "requestsPerMinute":20,
                  "tokensPerMinute":100000,
                  "maxConcurrency":3,
                  "dailyRequestLimit":null,
                  "maxOutputTokens":null,
                  "maxFileSizeBytes":20971520,
                  "oncePerUser":false,
                  "models":[]
                }
                """.formatted(VERSION_ID, priceAmount);
    }

    private record WalletAttempt(
            BalanceOrderPaymentService.BalancePaymentResult result,
            RuntimeException error) {
    }
}
