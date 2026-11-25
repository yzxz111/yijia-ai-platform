package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class RechargePaymentPostingServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("97000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("97000000-0000-4000-8000-000000000002");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("97000000-0000-4000-8000-000000000003");
    private static final Instant CREATED_AT = Instant.parse("2030-03-04T05:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2030-03-04T05:15:00Z");
    private static final Instant PAID_AT = Instant.parse("2030-03-04T05:06:07Z");
    private static final String PAYLOAD_HASH = "a".repeat(64);

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
    JdbcTemplate jdbc;

    @Autowired
    PaymentPostingService service;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        jdbc.update("TRUNCATE platform.notification_event, platform.sms_delivery, platform.payment_anomaly, "
                + "platform.admin_audit_log, platform.outbox_event, platform.grant_request, "
                + "platform.balance_ledger, platform.payment_transaction, "
                + "platform.recharge_order, platform.account_balance, "
                + "platform.customer_order, platform.refresh_token, platform.user_role, "
                + "platform.invitation, platform.app_user RESTART IDENTITY CASCADE");
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'recharge-posting@example.test', 'hash',
                        'Recharge Posting User', 'ACTIVE')
                """, USER_ID, TENANT_ID);
        jdbc.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, version)
                VALUES (?, ?, ?, 'CNY', 500, 0)
                """, ACCOUNT_ID, TENANT_ID, USER_ID);
    }

    @Test
    void firstSuccessCreditsExactlyOnceAndReplayReturnsTheSameBusinessResult() {
        UUID rechargeId = insertRecharge("rch_post", 1_234);
        insertTransaction("pay_recharge_post", rechargeId, "MOCK", 1_234, "PENDING");
        PaymentAdapter.ProviderPayment providerPayment = payment(
                "pay_recharge_post", "trade-recharge-post", 1_234, PAYLOAD_HASH);
        double paidBefore = counter("platform.wallet.recharge.paid", "provider", "MOCK");

        PaymentPostingService.PostingResult posted = service.postSucceeded(providerPayment);
        PaymentPostingService.PostingResult replay = service.postSucceeded(providerPayment);

        assertThat(posted).isEqualTo(new PaymentPostingService.PostingResult(
                "rch_post", "PAID", "pay_recharge_post", true, false));
        assertThat(replay).isEqualTo(new PaymentPostingService.PostingResult(
                "rch_post", "PAID", "pay_recharge_post", true, true));
        assertThat(jdbc.queryForObject("""
                SELECT available_amount FROM platform.account_balance WHERE id = ?
                """, Long.class, ACCOUNT_ID)).isEqualTo(1_734);
        assertThat(jdbc.queryForMap("""
                SELECT entry_type, amount_delta, paid_amount, bonus_amount,
                       business_type, business_id, business_no, idempotency_key,
                       balance_before, balance_after
                FROM platform.balance_ledger
                """))
                .containsEntry("entry_type", "RECHARGE")
                .containsEntry("amount_delta", 1_234L)
                .containsEntry("paid_amount", 1_234L)
                .containsEntry("bonus_amount", 0L)
                .containsEntry("business_type", "RECHARGE_ORDER")
                .containsEntry("business_id", rechargeId)
                .containsEntry("business_no", "rch_post")
                .containsEntry("idempotency_key", "recharge:rch_post")
                .containsEntry("balance_before", 500L)
                .containsEntry("balance_after", 1_734L);
        assertThat(count("platform.balance_ledger")).isEqualTo(1);
        assertThat(count("platform.grant_request")).isZero();
        assertThat(count("platform.outbox_event")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'RECHARGE_PAYMENT_POSTED'
                  AND resource_type = 'RECHARGE_ORDER'
                  AND resource_id = 'rch_post'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT event_type, event_key, payload->>'payMoney' AS pay_money,
                       payload->>'balance' AS balance
                FROM platform.notification_event
                WHERE event_key = 'recharge-succeeded:rch_post'
                """))
                .containsEntry("event_type", "BALANCE_RECHARGE_SUCCEEDED")
                .containsEntry("event_key", "recharge-succeeded:rch_post")
                .containsEntry("pay_money", "12.34")
                .containsEntry("balance", "17.34");
        assertThat(counter("platform.wallet.recharge.paid", "provider", "MOCK"))
                .isEqualTo(paidBefore + 1);
    }

    @Test
    void paidMetricIsEmittedOnlyAfterTheOuterTransactionCommits() {
        UUID rechargeId = insertRecharge("rch_metric_paid", 1_234);
        insertTransaction("pay_metric_paid", rechargeId, "MOCK", 1_234, "PENDING");
        PaymentAdapter.ProviderPayment providerPayment = payment(
                "pay_metric_paid", "trade-metric-paid", 1_234, PAYLOAD_HASH);
        double paidBefore = counter("platform.wallet.recharge.paid", "provider", "MOCK");
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        transactions.executeWithoutResult(status -> {
            service.postSucceeded(providerPayment);
            assertThat(counter("platform.wallet.recharge.paid", "provider", "MOCK"))
                    .isEqualTo(paidBefore);
            status.setRollbackOnly();
        });

        assertThat(counter("platform.wallet.recharge.paid", "provider", "MOCK"))
                .isEqualTo(paidBefore);

        transactions.executeWithoutResult(status -> {
            service.postSucceeded(providerPayment);
            assertThat(counter("platform.wallet.recharge.paid", "provider", "MOCK"))
                    .isEqualTo(paidBefore);
        });

        assertThat(counter("platform.wallet.recharge.paid", "provider", "MOCK"))
                .isEqualTo(paidBefore + 1);
    }

    @Test
    void conflictingReplayAndImmutablePaymentFactsDoNotCreditAgain() {
        UUID rechargeId = insertRecharge("rch_conflict", 2_000);
        insertTransaction("pay_recharge_conflict", rechargeId, "MOCK", 2_000, "PENDING");
        service.postSucceeded(payment(
                "pay_recharge_conflict", "trade-recharge-conflict", 2_000, PAYLOAD_HASH));

        service.postSucceeded(payment(
                "pay_recharge_conflict", "trade-recharge-conflict", 2_000,
                "b".repeat(64)));
        assertThatThrownBy(() -> service.postSucceeded(payment(
                "pay_recharge_conflict", "other-trade", 2_000, PAYLOAD_HASH)))
                .hasMessageContaining("payment_external_trade_mismatch");

        assertThat(count("platform.balance_ledger")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT available_amount FROM platform.account_balance WHERE id = ?
                """, Long.class, ACCOUNT_ID)).isEqualTo(2_500);
    }

    @Test
    void sameProviderTradeIdentityCannotCreditAnotherRecharge() {
        UUID firstId = insertRecharge("rch_trade_first", 1_000);
        UUID secondId = insertRecharge("rch_trade_second", 1_000);
        insertTransaction("pay_trade_first", firstId, "MOCK", 1_000, "FAILED");
        insertTransaction("pay_trade_second", secondId, "MOCK", 1_000, "FAILED");
        service.postSucceeded(payment(
                "pay_trade_first", "trade-shared-recharge", 1_000, PAYLOAD_HASH));

        assertThatThrownBy(() -> service.postSucceeded(payment(
                "pay_trade_second", "trade-shared-recharge", 1_000, "c".repeat(64))))
                .hasMessageContaining("payment_external_trade_conflict");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM platform.recharge_order WHERE recharge_no = 'rch_trade_second'
                """, String.class)).isEqualTo("PENDING");
        assertThat(count("platform.balance_ledger")).isEqualTo(1);
    }

    @Test
    void postingFailureRollsBackReceiptRechargeAndNotification() {
        UUID rechargeId = insertRecharge("rch_no_wallet", 1_500);
        insertTransaction("pay_no_wallet", rechargeId, "MOCK", 1_500, "PENDING");
        jdbc.update("DELETE FROM platform.account_balance WHERE id = ?", ACCOUNT_ID);

        assertThatThrownBy(() -> service.postSucceeded(payment(
                "pay_no_wallet", "trade-no-wallet", 1_500, PAYLOAD_HASH)))
                .hasMessageContaining("wallet_not_found");

        assertThat(jdbc.queryForObject("""
                SELECT status FROM platform.payment_transaction
                WHERE transaction_no = 'pay_no_wallet'
                """, String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM platform.recharge_order WHERE recharge_no = 'rch_no_wallet'
                """, String.class)).isEqualTo("PENDING");
        assertThat(count("platform.balance_ledger")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event
                WHERE event_key = 'recharge-succeeded:rch_no_wallet'
                """, Integer.class)).isZero();
    }

    @Test
    void verifiedLateCallbackCreditsAClosedRechargeAndAuditsCompensation() {
        UUID rechargeId = insertRecharge("rch_expired_callback", 1_500);
        insertTransaction("pay_expired_callback", rechargeId, "MOCK", 1_500, "PENDING");
        jdbc.update("""
                UPDATE platform.recharge_order
                SET status = 'CLOSED', closed_at = now(),
                    created_at = now() - interval '30 minutes',
                    expires_at = now() - interval '1 second'
                WHERE id = ?
                """, rechargeId);

        PaymentPostingService.PostingResult result = service.postSucceeded(payment(
                "pay_expired_callback", "trade-expired-callback", 1_500, PAYLOAD_HASH));
        assertThat(result.appliedToOrder()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM platform.recharge_order WHERE id = ?
                """, String.class, rechargeId)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("""
                SELECT closed_at IS NULL FROM platform.recharge_order WHERE id = ?
                """, Boolean.class, rechargeId)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM platform.payment_transaction
                WHERE transaction_no = 'pay_expired_callback'
                """, String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT available_amount FROM platform.account_balance WHERE id = ?
                """, Long.class, ACCOUNT_ID)).isEqualTo(2_000);
        assertThat(count("platform.balance_ledger")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT detail->>'latePayment' FROM platform.admin_audit_log
                WHERE action = 'RECHARGE_PAYMENT_POSTED' AND resource_id = 'rch_expired_callback'
                """, String.class)).isEqualTo("true");
    }

    private UUID insertRecharge(String rechargeNo, long amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.recharge_order(
                    id, recharge_no, tenant_id, user_id, paid_amount, bonus_amount,
                    credited_amount, currency, status, idempotency_key,
                    expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, 'CNY', 'PENDING', ?, ?, ?, ?)
                """, id, rechargeNo, TENANT_ID, USER_ID, amount, amount,
                "idem_" + rechargeNo, Timestamp.from(EXPIRES_AT),
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
        return id;
    }

    private void insertTransaction(
            String transactionNo,
            UUID rechargeId,
            String provider,
            long amount,
            String status) {
        String product = "MOCK".equals(provider) ? "MOCK" : "WECHAT_NATIVE";
        String rechargeNo = jdbc.queryForObject("""
                SELECT recharge_no FROM platform.recharge_order WHERE id = ?
                """, String.class, rechargeId);
        String requestHash = sha256(String.join("\n",
                transactionNo, rechargeNo, Long.toString(amount), "CNY",
                provider, product, EXPIRES_AT.toString()));
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, recharge_order_id, provider,
                    amount, status, payment_product, expires_at, request_payload_hash)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), transactionNo, rechargeId, provider, amount,
                status, product, Timestamp.from(EXPIRES_AT), requestHash);
    }

    private PaymentAdapter.ProviderPayment payment(
            String transactionNo,
            String externalTradeNo,
            long amount,
            String payloadHash) {
        return new PaymentAdapter.ProviderPayment(
                ProviderOutcome.SUCCEEDED, transactionNo, externalTradeNo,
                amount, "CNY", PAID_AT, "SUCCESS", null, payloadHash);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private double counter(String name, String... tags) {
        io.micrometer.core.instrument.Counter counter =
                meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
