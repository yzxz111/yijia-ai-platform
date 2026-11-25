package com.yuzhihuihe.apiplatform.platform.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class BalancePostingServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000002");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000003");
    private static final UUID ORDER_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000004");
    private static final UUID RECHARGE_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000005");
    private static final UUID REFUND_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000006");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    BalancePostingService service;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("TRUNCATE platform.app_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'balance-posting@example.test', 'hash', 'Wallet User', 'ACTIVE')
                """, USER_ID, TENANT_ID);
        insertAccount(0);
    }

    @Test
    void positiveRechargeCreditsTheAccount() {
        BalancePostingService.PostingResult result = service.post(recharge(10_000));

        assertThat(result.ledgerId()).isNotNull();
        assertThat(result.entryNo()).startsWith("wle_");
        assertThat(result.balanceBefore()).isZero();
        assertThat(result.balanceAfter()).isEqualTo(10_000);
        assertThat(result.createdAt()).isNotNull();
        assertThat(balance()).isEqualTo(10_000);
    }

    @Test
    void negativePackagePurchaseDebitsTheAccount() {
        setBalance(12_000);
        double debitBefore = counter(
                "platform.wallet.debit", "entryType", "PACKAGE_PURCHASE");

        BalancePostingService.PostingResult result = service.post(packageDebit(
                ORDER_ID, -9_900, "ord_1", "wallet-pay:ord_1"));
        service.post(packageDebit(ORDER_ID, -9_900, "ord_1", "wallet-pay:ord_1"));

        assertThat(result.balanceBefore()).isEqualTo(12_000);
        assertThat(result.balanceAfter()).isEqualTo(2_100);
        assertThat(balance()).isEqualTo(2_100);
        assertThat(counter("platform.wallet.debit", "entryType", "PACKAGE_PURCHASE"))
                .isEqualTo(debitBefore + 1);
    }

    @Test
    void debitMetricIsEmittedOnlyAfterTheOuterTransactionCommits() {
        setBalance(12_000);
        double debitBefore = counter(
                "platform.wallet.debit", "entryType", "PACKAGE_PURCHASE");
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        transactions.executeWithoutResult(status -> {
            service.post(packageDebit(
                    ORDER_ID, -9_900, "ord_metric_debit", "wallet-pay:ord_metric_debit"));
            assertThat(counter("platform.wallet.debit", "entryType", "PACKAGE_PURCHASE"))
                    .isEqualTo(debitBefore);
            status.setRollbackOnly();
        });

        assertThat(counter("platform.wallet.debit", "entryType", "PACKAGE_PURCHASE"))
                .isEqualTo(debitBefore);

        transactions.executeWithoutResult(status -> {
            service.post(packageDebit(
                    ORDER_ID, -9_900, "ord_metric_debit", "wallet-pay:ord_metric_debit"));
            assertThat(counter("platform.wallet.debit", "entryType", "PACKAGE_PURCHASE"))
                    .isEqualTo(debitBefore);
        });

        assertThat(counter("platform.wallet.debit", "entryType", "PACKAGE_PURCHASE"))
                .isEqualTo(debitBefore + 1);
    }

    @Test
    void packageRefundCreditsEmitExactlyOneRefundMetricOnReplay() {
        double refundBefore = counter(
                "platform.wallet.refund", "entryType", "PACKAGE_REFUND");
        BalancePostingService.PostingCommand refund =
                new BalancePostingService.PostingCommand(
                        TENANT_ID, USER_ID, "PACKAGE_REFUND", 1_234, 0, 0,
                        "REFUND", REFUND_ID, "ref_1", "refund:ref_1");

        service.post(refund);
        service.post(refund);

        assertThat(balance()).isEqualTo(1_234);
        assertThat(counter("platform.wallet.refund", "entryType", "PACKAGE_REFUND"))
                .isEqualTo(refundBefore + 1);
    }

    @Test
    void refundMetricIsEmittedOnlyAfterTheOuterTransactionCommits() {
        double refundBefore = counter(
                "platform.wallet.refund", "entryType", "PACKAGE_REFUND");
        BalancePostingService.PostingCommand refund =
                new BalancePostingService.PostingCommand(
                        TENANT_ID, USER_ID, "PACKAGE_REFUND", 1_234, 0, 0,
                        "REFUND", REFUND_ID, "ref_metric", "refund:ref_metric");
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        transactions.executeWithoutResult(status -> {
            service.post(refund);
            assertThat(counter("platform.wallet.refund", "entryType", "PACKAGE_REFUND"))
                    .isEqualTo(refundBefore);
            status.setRollbackOnly();
        });

        assertThat(counter("platform.wallet.refund", "entryType", "PACKAGE_REFUND"))
                .isEqualTo(refundBefore);

        transactions.executeWithoutResult(status -> {
            service.post(refund);
            assertThat(counter("platform.wallet.refund", "entryType", "PACKAGE_REFUND"))
                    .isEqualTo(refundBefore);
        });

        assertThat(counter("platform.wallet.refund", "entryType", "PACKAGE_REFUND"))
                .isEqualTo(refundBefore + 1);
    }

    @Test
    void insufficientFundsLeavesTheAccountAndLedgerUntouched() {
        setBalance(5_000);
        double insufficientBefore = counter(
                "platform.wallet.insufficient.balance",
                "entryType", "PACKAGE_PURCHASE");

        assertThatThrownBy(() -> service.post(packageDebit(
                ORDER_ID, -9_900, "ord_1", "wallet-pay:ord_1")))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
                    assertThat(error.getReason()).isEqualTo("wallet_insufficient_balance");
                });

        assertThat(balance()).isEqualTo(5_000);
        assertThat(accountVersion()).isZero();
        assertThat(ledgerCount()).isZero();
        assertThat(counter(
                "platform.wallet.insufficient.balance",
                "entryType", "PACKAGE_PURCHASE"))
                .isEqualTo(insufficientBefore + 1);
    }

    @Test
    void insufficientBalanceMetricRemainsImmediateWhenTheTransactionRollsBack() {
        setBalance(5_000);
        double insufficientBefore = counter(
                "platform.wallet.insufficient.balance",
                "entryType", "PACKAGE_PURCHASE");
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> service.post(
                packageDebit(ORDER_ID, -9_900, "ord_metric_insufficient",
                        "wallet-pay:ord_metric_insufficient"))))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("wallet_insufficient_balance"));

        assertThat(counter(
                "platform.wallet.insufficient.balance", "entryType", "PACKAGE_PURCHASE"))
                .isEqualTo(insufficientBefore + 1);
    }

    @Test
    void missingScopedAccountReturnsStableNotFound() {
        jdbcTemplate.update("""
                DELETE FROM platform.account_balance
                WHERE tenant_id = ? AND user_id = ? AND id = ?
                """, TENANT_ID, USER_ID, ACCOUNT_ID);

        assertThatThrownBy(() -> service.post(recharge(10_000)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
                    assertThat(error.getReason()).isEqualTo("wallet_not_found");
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.balance_ledger", Integer.class)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPostingCommands")
    void rejectsInvalidPostingCommandsBeforeMutation(
            String scenario, BalancePostingService.PostingCommand command) {
        assertThatThrownBy(() -> service.post(command))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
                    assertThat(error.getReason()).isEqualTo("invalid_wallet_posting");
                });

        assertThat(balance()).isZero();
        assertThat(accountVersion()).isZero();
        assertThat(ledgerCount()).isZero();
    }

    @Test
    void exactReplayReturnsTheStoredResultWithoutMutatingAgain() {
        BalancePostingService.PostingCommand command = recharge(10_000);

        BalancePostingService.PostingResult first = service.post(command);
        BalancePostingService.PostingResult replay = service.post(command);

        assertThat(replay).isEqualTo(first);
        assertThat(balance()).isEqualTo(10_000);
        assertThat(accountVersion()).isEqualTo(1);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void conflictingBusinessReplayReturnsStableConflict() {
        service.post(recharge(10_000));
        BalancePostingService.PostingCommand conflict = new BalancePostingService.PostingCommand(
                TENANT_ID, USER_ID, "RECHARGE", 20_000,
                20_000, 0, "RECHARGE_ORDER", RECHARGE_ID, "rch_1", "wallet-recharge:other");

        assertLedgerConflict(conflict);
        assertThat(balance()).isEqualTo(10_000);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void conflictingIdempotencyReplayReturnsStableConflict() {
        service.post(recharge(10_000));
        BalancePostingService.PostingCommand conflict = new BalancePostingService.PostingCommand(
                TENANT_ID, USER_ID, "RECHARGE", 10_001,
                10_001, 0, "RECHARGE_ORDER", UUID.randomUUID(), "rch_2", "wallet-recharge:rch_1");

        assertLedgerConflict(conflict);
        assertThat(balance()).isEqualTo(10_000);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void ledgerStoresExactBeforeAndAfterSnapshots() {
        setBalance(15_000);

        BalancePostingService.PostingResult result = service.post(packageDebit(
                ORDER_ID, -9_900, "ord_1", "wallet-pay:ord_1"));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT amount_delta, balance_before, balance_after
                FROM platform.balance_ledger
                WHERE tenant_id = ? AND user_id = ? AND account_id = ? AND id = ?
                """, TENANT_ID, USER_ID, ACCOUNT_ID, result.ledgerId()))
                .containsEntry("amount_delta", -9_900L)
                .containsEntry("balance_before", 15_000L)
                .containsEntry("balance_after", 5_100L);
    }

    @Test
    void concurrentDebitsSerializeAndCannotOverdraw() throws Exception {
        setBalance(10_000);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<PostingAttempt> first = executor.submit(() -> attemptDebit(
                    ready, start, UUID.randomUUID(), -7_000, "ord_concurrent_1"));
            Future<PostingAttempt> second = executor.submit(() -> attemptDebit(
                    ready, start, UUID.randomUUID(), -6_000, "ord_concurrent_2"));
            ready.await();
            start.countDown();

            PostingAttempt firstResult = first.get();
            PostingAttempt secondResult = second.get();

            int successCount = (firstResult.succeeded() ? 1 : 0)
                    + (secondResult.succeeded() ? 1 : 0);
            assertThat(successCount).isEqualTo(1);
            PostingAttempt failure = firstResult.succeeded() ? secondResult : firstResult;
            assertThat(failure.error()).isInstanceOfSatisfying(
                    ResponseStatusException.class,
                    error -> assertThat(error.getReason())
                            .isEqualTo("wallet_insufficient_balance"));
        }

        assertThat(balance()).isGreaterThanOrEqualTo(0).isIn(3_000L, 4_000L);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    private PostingAttempt attemptDebit(
            CountDownLatch ready, CountDownLatch start, UUID businessId,
            long amount, String businessNo) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return new PostingAttempt(true, service.post(packageDebit(
                    businessId, amount, businessNo, "wallet-pay:" + businessNo)), null);
        } catch (RuntimeException error) {
            return new PostingAttempt(false, null, error);
        }
    }

    private void assertLedgerConflict(BalancePostingService.PostingCommand command) {
        assertThatThrownBy(() -> service.post(command))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
                    assertThat(error.getReason()).isEqualTo("wallet_ledger_conflict");
                });
    }

    private BalancePostingService.PostingCommand recharge(long amount) {
        return new BalancePostingService.PostingCommand(
                TENANT_ID, USER_ID, "RECHARGE", amount,
                amount, 0, "RECHARGE_ORDER", RECHARGE_ID, "rch_1", "wallet-recharge:rch_1");
    }

    private BalancePostingService.PostingCommand packageDebit(
            UUID businessId, long amount, String businessNo, String idempotencyKey) {
        return new BalancePostingService.PostingCommand(
                TENANT_ID, USER_ID, "PACKAGE_PURCHASE", amount,
                0, 0, "ORDER", businessId, businessNo, idempotencyKey);
    }

    private static Stream<Arguments> invalidPostingCommands() {
        return Stream.of(
                invalid("null tenant", null, USER_ID, "RECHARGE", 100, 100, 0,
                        "RECHARGE_ORDER", RECHARGE_ID, "rch", "idem"),
                invalid("null user", TENANT_ID, null, "RECHARGE", 100, 100, 0,
                        "RECHARGE_ORDER", RECHARGE_ID, "rch", "idem"),
                invalid("null business id", TENANT_ID, USER_ID, "RECHARGE", 100, 100, 0,
                        "RECHARGE_ORDER", null, "rch", "idem"),
                invalid("blank business no", TENANT_ID, USER_ID, "RECHARGE", 100, 100, 0,
                        "RECHARGE_ORDER", RECHARGE_ID, "  ", "idem"),
                invalid("oversized business no", TENANT_ID, USER_ID, "RECHARGE", 100, 100, 0,
                        "RECHARGE_ORDER", RECHARGE_ID, "b".repeat(65), "idem"),
                invalid("blank idempotency", TENANT_ID, USER_ID, "RECHARGE", 100, 100, 0,
                        "RECHARGE_ORDER", RECHARGE_ID, "rch", "\t"),
                invalid("oversized idempotency", TENANT_ID, USER_ID, "RECHARGE", 100, 100, 0,
                        "RECHARGE_ORDER", RECHARGE_ID, "rch", "i".repeat(129)),
                invalid("unsupported entry type", TENANT_ID, USER_ID, "WITHDRAWAL", -100, 0, 0,
                        "ORDER", RECHARGE_ID, "withdrawal", "idem"),
                invalid("unsupported business type", TENANT_ID, USER_ID, "RECHARGE", 100, 100, 0,
                        "RECHARGE", RECHARGE_ID, "rch", "idem"),
                invalid("zero delta", TENANT_ID, USER_ID, "PACKAGE_PURCHASE", 0, 0, 0,
                        "ORDER", ORDER_ID, "ord", "idem"),
                invalid("package purchase credit", TENANT_ID, USER_ID,
                        "PACKAGE_PURCHASE", 100, 0, 0, "ORDER", ORDER_ID, "ord", "idem"),
                invalid("package purchase paid amount", TENANT_ID, USER_ID,
                        "PACKAGE_PURCHASE", -100, 100, 0, "ORDER", ORDER_ID, "ord", "idem"),
                invalid("package purchase bonus", TENANT_ID, USER_ID,
                        "PACKAGE_PURCHASE", -100, 0, 1, "ORDER", ORDER_ID, "ord", "idem"),
                invalid("recharge debit", TENANT_ID, USER_ID, "RECHARGE", -100, 100, 0,
                        "RECHARGE_ORDER", RECHARGE_ID, "rch", "idem"),
                invalid("recharge missing paid amount", TENANT_ID, USER_ID,
                        "RECHARGE", 100, 0, 0, "RECHARGE_ORDER", RECHARGE_ID, "rch", "idem"),
                invalid("recharge amount mismatch", TENANT_ID, USER_ID,
                        "RECHARGE", 100, 99, 0, "RECHARGE_ORDER", RECHARGE_ID, "rch", "idem"),
                invalid("recharge bonus", TENANT_ID, USER_ID, "RECHARGE", 100, 99, 1,
                        "RECHARGE_ORDER", RECHARGE_ID, "rch", "idem"),
                invalid("recharge overflow", TENANT_ID, USER_ID,
                        "RECHARGE", Long.MAX_VALUE, Long.MAX_VALUE, 1,
                        "RECHARGE_ORDER", RECHARGE_ID, "rch", "idem"),
                invalid("package refund debit", TENANT_ID, USER_ID,
                        "PACKAGE_REFUND", -100, 0, 0, "REFUND", RECHARGE_ID, "ref", "idem"),
                invalid("package refund paid amount", TENANT_ID, USER_ID,
                        "PACKAGE_REFUND", 100, 1, 0, "REFUND", RECHARGE_ID, "ref", "idem"),
                invalid("package refund bonus", TENANT_ID, USER_ID,
                        "PACKAGE_REFUND", 100, 0, 1, "REFUND", RECHARGE_ID, "ref", "idem"));
    }

    private static Arguments invalid(
            String scenario, UUID tenantId, UUID userId, String entryType,
            long amountDelta, long paidAmount, long bonusAmount,
            String businessType, UUID businessId, String businessNo, String idempotencyKey) {
        return Arguments.of(scenario, new BalancePostingService.PostingCommand(
                tenantId, userId, entryType, amountDelta, paidAmount, bonusAmount,
                businessType, businessId, businessNo, idempotencyKey));
    }

    private void insertAccount(long amount) {
        jdbcTemplate.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, version)
                VALUES (?, ?, ?, 'CNY', ?, 0)
                """, ACCOUNT_ID, TENANT_ID, USER_ID, amount);
    }

    private double counter(String name, String... tags) {
        io.micrometer.core.instrument.Counter counter =
                meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
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

    private int ledgerCount() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.balance_ledger
                WHERE tenant_id = ? AND user_id = ? AND account_id = ?
                """, Integer.class, TENANT_ID, USER_ID, ACCOUNT_ID);
    }

    private record PostingAttempt(
            boolean succeeded, BalancePostingService.PostingResult result, RuntimeException error) {
    }
}
