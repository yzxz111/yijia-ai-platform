package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
class PaymentPostingServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000002");
    // STARTER v3（当前已发布版本）。原来的 ...102 是 STARTER v1，
    // V61__reprice_token_plans_to_thirty_percent_floor.sql:105 已把它改成
    // RETIRED，而履约只接受 PUBLISHED 版本 —— 用旧值会让这个测试以
    // 404 published_package_version_not_found 结束。
    private static final UUID VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000122");
    private static final Instant PAID_AT = Instant.parse("2030-03-04T05:06:07Z");
    private static final Instant PAYMENT_EXPIRES_AT =
            Instant.parse("2030-03-04T05:21:07Z");
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
    JdbcTemplate jdbcTemplate;

    @Autowired
    PaymentPostingService service;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("TRUNCATE platform.payment_anomaly, platform.admin_audit_log, "
                + "platform.outbox_event, platform.grant_request, platform.refund, "
                + "platform.payment_transaction, platform.customer_order, "
                + "platform.refresh_token, platform.user_role, platform.invitation, "
                + "platform.app_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'posting@example.com', 'hash', 'Posting User', 'ACTIVE')
                """, USER_ID, TENANT_ID);
    }

    @Test
    void callbackAndQueryResultsShareAtomicPostingAndSequentialReplay() {
        insertOrder("ord_post", 2990, snapshot(2990));
        insertTransaction("pay_post", "ord_post", "MOCK", 2990, "PENDING");

        PaymentPostingService.PostingResult posted = service.postSucceeded(
                payment("pay_post", "trade-post", 2990, "CNY", PAYLOAD_HASH));
        PaymentPostingService.PostingResult replay = service.postSucceeded(
                payment("pay_post", "trade-post", 2990, "CNY", PAYLOAD_HASH));

        assertThat(posted.orderStatus()).isEqualTo("PAID");
        assertThat(posted.appliedToOrder()).isTrue();
        assertThat(posted.replay()).isFalse();
        assertThat(replay).isEqualTo(new PaymentPostingService.PostingResult(
                "ord_post", "PAID", "pay_post", true, true));
        assertThat(singleLong("SELECT count(*) FROM platform.grant_request WHERE business_no = ?",
                "order:ord_post")).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM platform.outbox_event WHERE business_key = ?",
                "order:ord_post")).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM platform.admin_audit_log "
                + "WHERE action = 'PAYMENT_POSTED'")).isEqualTo(1);

        assertThatThrownBy(() -> service.postSucceeded(
                payment("pay_post", "trade-post", 2990, "CNY", "b".repeat(64))))
                .hasMessageContaining("payment_callback_payload_mismatch");
        assertThat(singleLong("SELECT count(*) FROM platform.grant_request WHERE business_no = ?",
                "order:ord_post")).isEqualTo(1);
    }

    @Test
    void rejectsNonSucceededResultBeforeLookingUpATransaction() {
        PaymentAdapter.ProviderPayment pending = new PaymentAdapter.ProviderPayment(
                ProviderOutcome.PENDING, "pay_missing", null, 2990, "CNY", null,
                "PENDING", null, PAYLOAD_HASH);

        assertThatThrownBy(() -> service.postSucceeded(pending))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment_result_not_succeeded");
    }

    @Test
    void alipayCallbackExpectationLookupUsesDurablePostgresqlFacts() {
        insertOrder("ord_alipay_expectation", 399, snapshot(399));
        insertTransaction(
                "pay_alipay_expectation", "ord_alipay_expectation", "ALIPAY", 399,
                "PENDING");
        var lookup = new AlipayPaymentConfiguration()
                .alipayPaymentExpectationLookup(jdbcTemplate);

        assertThat(lookup.require("pay_alipay_expectation"))
                .isEqualTo(new com.yuzhihuihe.apiplatform.platform.payment.alipay
                        .AlipayAdapter.ExpectedPayment(399, "CNY"));
        assertThatThrownBy(() -> lookup.require("pay_missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("alipay_payment_expectation_not_found");

        insertOrder("ord_other_provider", 399, snapshot(399));
        insertTransaction("pay_other_provider", "ord_other_provider", "MOCK", 399,
                "PENDING");
        assertThatThrownBy(() -> lookup.require("pay_other_provider"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("alipay_payment_expectation_not_found");
    }

    @Test
    void concurrentReplayAppliesOneReceiptAndOneGrant() throws Exception {
        insertOrder("ord_concurrent_post", 2990, snapshot(2990));
        insertTransaction("pay_concurrent_post", "ord_concurrent_post", "MOCK", 2990, "PENDING");
        PaymentAdapter.ProviderPayment payment = payment(
                "pay_concurrent_post", "trade-concurrent-post", 2990, "CNY", PAYLOAD_HASH);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<PaymentPostingService.PostingResult>> futures = List.of(
                    executor.submit(() -> {
                        start.await();
                        return service.postSucceeded(payment);
                    }),
                    executor.submit(() -> {
                        start.await();
                        return service.postSucceeded(payment);
                    }));
            start.countDown();

            List<PaymentPostingService.PostingResult> results = List.of(
                    futures.get(0).get(), futures.get(1).get());
            assertThat(results).extracting(PaymentPostingService.PostingResult::replay)
                    .containsExactlyInAnyOrder(false, true);
        }

        assertThat(singleLong("SELECT count(*) FROM platform.grant_request")).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM platform.payment_transaction "
                + "WHERE applied_to_order")).isEqualTo(1);
    }

    @Test
    void rejectsUnknownTransactionAndExactAmountCurrencyMismatch() {
        insertOrder("ord_mismatch", 2990, snapshot(2990));
        insertTransaction("pay_mismatch", "ord_mismatch", "MOCK", 2990, "PENDING");

        assertThatThrownBy(() -> service.postSucceeded(
                payment("pay_unknown", "trade-unknown", 2990, "CNY", PAYLOAD_HASH)))
                .hasMessageContaining("payment_transaction_not_found");
        assertThatThrownBy(() -> service.postSucceeded(
                payment("pay_mismatch", "trade-amount", 2991, "CNY", PAYLOAD_HASH)))
                .hasMessageContaining("payment_amount_mismatch");
        assertThatThrownBy(() -> service.postSucceeded(
                payment("pay_mismatch", "trade-currency", 2990, "USD", PAYLOAD_HASH)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency must be CNY");

        assertThat(singleString("SELECT status FROM platform.customer_order "
                + "WHERE order_no = 'ord_mismatch'")).isEqualTo("PENDING");
        assertThat(singleLong("SELECT count(*) FROM platform.grant_request")).isZero();
    }

    @Test
    void rejectsRequestPayloadIdentityMismatchWithoutMutation() {
        insertOrder("ord_request_identity", 2990, snapshot(2990));
        insertTransaction(
                "pay_request_identity", "ord_request_identity", "MOCK", 2990, "PENDING");
        jdbcTemplate.update("""
                UPDATE platform.payment_transaction
                SET request_payload_hash = ?
                WHERE transaction_no = 'pay_request_identity'
                """, "0".repeat(64));

        assertThatThrownBy(() -> service.postSucceeded(payment(
                "pay_request_identity", "trade-request-identity",
                2990, "CNY", PAYLOAD_HASH)))
                .hasMessageContaining("payment_request_payload_mismatch");

        assertThat(singleString("SELECT status FROM platform.payment_transaction "
                + "WHERE transaction_no = 'pay_request_identity'"))
                .isEqualTo("PENDING");
        assertThat(singleString("SELECT status FROM platform.customer_order "
                + "WHERE order_no = 'ord_request_identity'"))
                .isEqualTo("PENDING");
        assertThat(singleLong("SELECT count(*) FROM platform.grant_request")).isZero();
        assertThat(singleLong("SELECT count(*) FROM platform.outbox_event")).isZero();
    }

    @Test
    void sameProviderExternalTradeNumberCannotPayAnotherOrder() {
        insertOrder("ord_external_one", 2990, snapshot(2990));
        insertOrder("ord_external_two", 2990, snapshot(2990));
        insertTransaction("pay_external_one", "ord_external_one", "MOCK", 2990, "FAILED");
        insertTransaction("pay_external_two", "ord_external_two", "MOCK", 2990, "FAILED");
        service.postSucceeded(payment(
                "pay_external_one", "trade-shared", 2990, "CNY", PAYLOAD_HASH));

        assertThatThrownBy(() -> service.postSucceeded(payment(
                "pay_external_two", "trade-shared", 2990, "CNY", "b".repeat(64))))
                .hasMessageContaining("payment_external_trade_conflict");
        assertThat(singleString("SELECT status FROM platform.customer_order "
                + "WHERE order_no = 'ord_external_two'")).isEqualTo("PENDING");
    }

    @Test
    void twoProvidersRacingOneOrderRecordsOneDuplicatePaymentAnomaly() throws Exception {
        insertOrder("ord_provider_race", 2990, snapshot(2990));
        insertTransaction("pay_provider_mock", "ord_provider_race", "MOCK", 2990, "FAILED");
        insertTransaction("pay_provider_wechat", "ord_provider_race", "WECHAT_PAY", 2990, "CLOSED");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<PaymentPostingService.PostingResult>> futures = List.of(
                    executor.submit(() -> {
                        start.await();
                        return service.postSucceeded(payment(
                                "pay_provider_mock", "trade-provider-mock", 2990,
                                "CNY", "c".repeat(64)));
                    }),
                    executor.submit(() -> {
                        start.await();
                        return service.postSucceeded(payment(
                                "pay_provider_wechat", "trade-provider-wechat", 2990,
                                "CNY", "d".repeat(64)));
                    }));
            start.countDown();
            List<PaymentPostingService.PostingResult> results = List.of(
                    futures.get(0).get(), futures.get(1).get());
            assertThat(results).extracting(PaymentPostingService.PostingResult::appliedToOrder)
                    .containsExactlyInAnyOrder(true, false);
        }

        assertThat(singleLong("SELECT count(*) FROM platform.payment_transaction "
                + "WHERE status = 'SUCCEEDED'")).isEqualTo(2);
        assertThat(singleLong("SELECT count(*) FROM platform.payment_transaction "
                + "WHERE applied_to_order")).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM platform.grant_request")).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM platform.payment_anomaly "
                + "WHERE anomaly_type = 'DUPLICATE_PAYMENT' AND status = 'OPEN'"))
                .isEqualTo(1);
    }

    @Test
    void confirmedReceiptAfterAppliedPaymentIsPersistedAsDuplicateWithoutAnotherGrant() {
        insertOrder("ord_duplicate_receipt", 2990, snapshot(2990));
        insertTransaction("pay_duplicate_first", "ord_duplicate_receipt", "MOCK", 2990, "FAILED");
        insertTransaction(
                "pay_duplicate_second", "ord_duplicate_receipt", "ALIPAY", 2990, "CLOSED");

        PaymentPostingService.PostingResult first = service.postSucceeded(payment(
                "pay_duplicate_first", "trade-duplicate-first", 2990,
                "CNY", "e".repeat(64)));
        PaymentPostingService.PostingResult duplicate = service.postSucceeded(payment(
                "pay_duplicate_second", "trade-duplicate-second", 2990,
                "CNY", "f".repeat(64)));

        assertThat(first.appliedToOrder()).isTrue();
        assertThat(duplicate.appliedToOrder()).isFalse();
        assertThat(duplicate.replay()).isFalse();
        assertThat(singleLong("SELECT count(*) FROM platform.payment_transaction "
                + "WHERE status = 'SUCCEEDED'")).isEqualTo(2);
        assertThat(singleLong("SELECT count(*) FROM platform.grant_request")).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM platform.payment_anomaly "
                + "WHERE anomaly_type = 'DUPLICATE_PAYMENT' AND status = 'OPEN'"))
                .isEqualTo(1);
    }

    @Test
    void legacyOrderBackfillsStoredAmountSnapshotBeforeFulfillment() {
        insertOrder("ord_legacy_snapshot", 2990, null);
        insertTransaction("pay_legacy_snapshot", "ord_legacy_snapshot", "MOCK", 2990, "PENDING");

        service.postSucceeded(payment(
                "pay_legacy_snapshot", "trade-legacy", 2990, "CNY", PAYLOAD_HASH));

        assertThat(singleLong("SELECT (package_snapshot->>'priceAmount')::bigint "
                + "FROM platform.customer_order WHERE order_no = 'ord_legacy_snapshot'"))
                .isEqualTo(2990);
        assertThat(singleString("SELECT package_snapshot->>'currency' "
                + "FROM platform.customer_order WHERE order_no = 'ord_legacy_snapshot'"))
                .isEqualTo("CNY");
        assertThat(singleLong("SELECT count(*) FROM platform.admin_audit_log "
                + "WHERE action = 'PAYMENT_POSTED' "
                + "AND detail->>'legacySnapshotBackfill' = 'true'"))
                .isEqualTo(1);
    }

    @Test
    void rejectsMissingNewOrderSnapshotAndStoredSnapshotFactMismatch() {
        insertOrder("ord_new_missing_snapshot", 2990, null);
        jdbcTemplate.update("""
                UPDATE platform.customer_order
                SET idempotency_key = 'new-order-key'
                WHERE order_no = 'ord_new_missing_snapshot'
                """);
        insertTransaction(
                "pay_new_missing_snapshot", "ord_new_missing_snapshot", "MOCK", 2990, "PENDING");

        assertThatThrownBy(() -> service.postSucceeded(payment(
                "pay_new_missing_snapshot", "trade-new-missing", 2990,
                "CNY", "1".repeat(64))))
                .hasMessageContaining("order_package_snapshot_missing");

        insertOrder("ord_snapshot_mismatch", 2990, snapshot(2991));
        insertTransaction(
                "pay_snapshot_mismatch", "ord_snapshot_mismatch", "MOCK", 2990, "PENDING");
        assertThatThrownBy(() -> service.postSucceeded(payment(
                "pay_snapshot_mismatch", "trade-snapshot-mismatch", 2990,
                "CNY", "2".repeat(64))))
                .hasMessageContaining("order_package_snapshot_mismatch");

        assertThat(singleLong("SELECT count(*) FROM platform.payment_transaction "
                + "WHERE status = 'SUCCEEDED'")).isZero();
        assertThat(singleLong("SELECT count(*) FROM platform.grant_request")).isZero();
    }

    @Test
    void fulfillmentOutboxFailureRollsBackReceiptOrderAndGrant() {
        insertOrder("ord_post_rollback", 2990, snapshot(2990));
        insertTransaction("pay_post_rollback", "ord_post_rollback", "MOCK", 2990, "PENDING");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION platform.reject_posting_outbox()
                RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'forced_posting_outbox_failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_posting_outbox
                BEFORE INSERT ON platform.outbox_event
                FOR EACH ROW EXECUTE FUNCTION platform.reject_posting_outbox()
                """);
        try {
            assertThatThrownBy(() -> service.postSucceeded(payment(
                    "pay_post_rollback", "trade-rollback", 2990, "CNY", PAYLOAD_HASH)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbcTemplate.execute(
                    "DROP TRIGGER IF EXISTS reject_posting_outbox ON platform.outbox_event");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS platform.reject_posting_outbox()");
        }

        assertThat(singleString("SELECT status FROM platform.customer_order "
                + "WHERE order_no = 'ord_post_rollback'")).isEqualTo("PENDING");
        assertThat(singleString("SELECT status FROM platform.payment_transaction "
                + "WHERE transaction_no = 'pay_post_rollback'")).isEqualTo("PENDING");
        assertThat(singleLong("SELECT count(*) FROM platform.grant_request")).isZero();
        assertThat(singleLong("SELECT count(*) FROM platform.outbox_event")).isZero();
    }

    private void insertOrder(String orderNo, long amount, String packageSnapshot) {
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, package_snapshot,
                    expires_at, next_close_at)
                VALUES (?, ?, ?, ?, ?, ?, 'CNY', 'PENDING', 'MOCK', ?::jsonb,
                        ?, ?)
                """, UUID.randomUUID(), orderNo, TENANT_ID, USER_ID, VERSION_ID,
                amount, packageSnapshot, Timestamp.from(PAYMENT_EXPIRES_AT),
                Timestamp.from(PAYMENT_EXPIRES_AT));
    }

    private void insertTransaction(
            String transactionNo,
            String orderNo,
            String provider,
            long amount,
            String status) {
        String product = switch (provider) {
            case "WECHAT_PAY" -> "WECHAT_NATIVE";
            case "ALIPAY" -> "ALIPAY_PAGE";
            default -> "MOCK";
        };
        String requestHash = sha256(String.join("\n",
                transactionNo, orderNo, Long.toString(amount), "CNY",
                provider, product, PAYMENT_EXPIRES_AT.toString()));
        jdbcTemplate.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, expires_at, request_payload_hash)
                SELECT ?, ?, id, ?, ?, ?, ?, ?, ?
                FROM platform.customer_order WHERE order_no = ?
                """, UUID.randomUUID(), transactionNo, provider, amount, status,
                product, Timestamp.from(PAYMENT_EXPIRES_AT), requestHash, orderNo);
    }

    private PaymentAdapter.ProviderPayment payment(
            String transactionNo,
            String externalTradeNo,
            long amount,
            String currency,
            String payloadHash) {
        return new PaymentAdapter.ProviderPayment(
                ProviderOutcome.SUCCEEDED, transactionNo, externalTradeNo,
                amount, currency, PAID_AT, "SUCCESS", null, payloadHash);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("test_payment_request_hash_failed", exception);
        }
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

    private long singleLong(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }

    private String singleString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }
}
