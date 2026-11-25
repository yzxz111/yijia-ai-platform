package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.security.SecretEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
        "spring.profiles.active=local",
        "payment.mock.enabled=true",
        // 本测试套件断言每一个不同的非法请求体都会记录一条异常。生产环境会把
        // 同一提供方在 payment.callback.anomaly-window（默认 1h）内的回调折叠为
        // 一行，因此这里禁用该窗口，以保持按请求体逐一断言的意义。
        "payment.callback.anomaly-window=0s",
        "outbox.publisher.initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@Import(MockPaymentIdempotencyTest.CallbackAdapterConfiguration.class)
class MockPaymentIdempotencyTest {

    private static final UUID USER_ID = UUID.fromString("91000000-0000-4000-8000-000000000001");
    // STARTER v3，也就是迁移链结束时**当前已发布**的那个版本。
    // 这里不能再用 STARTER v1 的 00000000-0000-4000-8000-000000000102：
    // V61__reprice_token_plans_to_thirty_percent_floor.sql:105 把它改成了
    // RETIRED，而 PackageSnapshotService.loadPublished 只认 PUBLISHED 版本，
    // 于是回调会在履约时以 404 published_package_version_not_found 失败。
    // 每次重新定价迁移（V61/V63 这类）都会换一批 UUID，届时这里要跟着更新
    // ——OrderControllerTest:56 与 OrderApplicationServiceTest:63 用的是同一代 ID。
    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-4000-8000-000000000122");
    private static final String CALLBACK_SECRET = "test-mock-callback-secret";
    private static final Instant ATTEMPT_EXPIRES_AT =
            Instant.parse("2030-03-04T05:21:07Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("security.internal.hmac-secret", () -> "test-internal-secret");
        registry.add("payment.mock.callback-secret", () -> CALLBACK_SECRET);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MockPaymentService paymentService;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("TRUNCATE platform.operating_fact_event_receipt, "
                + "platform.operating_fact, platform.payment_anomaly, "
                + "platform.admin_audit_log, platform.outbox_event, "
                + "platform.grant_request, platform.refund, platform.payment_transaction, "
                + "platform.customer_order, platform.refresh_token, platform.user_role, "
                + "platform.invitation, platform.app_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'payer@example.com', 'hash', 'Payer', 'ACTIVE')
                """, USER_ID, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider)
                VALUES (?, 'order-001', ?, ?, ?, 1900, 'CNY', 'PENDING', 'MOCK')
                """, UUID.randomUUID(), USER_ID, USER_ID, VERSION_ID);
        insertAttempt("order-001", "pay-order-001", "MOCK");
    }

    @Test
    void unsignedMockCallbackIsRejectedBeforeMutation() throws Exception {
        mockMvc.perform(post("/console/platform/payments/mock/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalTradeNo":"unsigned-trade","orderNo":"order-001","amount":1900}
                                """))
                .andExpect(status().isUnauthorized());

        assertThat(count("platform.payment_transaction")).isEqualTo(1);
        assertThat(transactionStatus("pay-order-001")).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM platform.customer_order WHERE order_no='order-001'", String.class))
                .isEqualTo("PENDING");
    }

    @Test
    void invalidMockCallbackSignatureIsRejectedBeforeMutation() throws Exception {
        mockMvc.perform(post("/console/platform/payments/mock/callback")
                        .header("X-Payment-Signature", "0".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalTradeNo":"forged-trade","orderNo":"order-001","amount":1900}
                                """))
                .andExpect(status().isUnauthorized());

        assertThat(count("platform.payment_transaction")).isEqualTo(1);
        assertThat(transactionStatus("pay-order-001")).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM platform.customer_order WHERE order_no='order-001'", String.class))
                .isEqualTo("PENDING");
    }

    @Test
    void malformedMockCallbackIsRejectedBeforeBusinessMutation() throws Exception {
        mockMvc.perform(post("/console/platform/payments/mock/callback")
                        .header("X-Payment-Signature", "0".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());

        assertThat(count("platform.payment_transaction")).isEqualTo(1);
        assertThat(transactionStatus("pay-order-001")).isEqualTo("PENDING");
        assertThat(count("platform.grant_request")).isZero();
        assertThat(orderStatus("order-001")).isEqualTo("PENDING");
    }

    @Test
    void signedMockCallbackRequiresAnExistingAttempt() throws Exception {
        jdbcTemplate.update("DELETE FROM platform.payment_transaction");

        signedCallback("missing-attempt", "order-001", 1900)
                .andExpect(status().isNotFound());

        assertThat(count("platform.payment_transaction")).isZero();
        assertThat(count("platform.grant_request")).isZero();
        assertThat(orderStatus("order-001")).isEqualTo("PENDING");
    }

    @Test
    void validSignedMockCallbackSucceedsAndReplayReturnsPriorResult() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            signedCallback("signed-trade", "order-001", 1900)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderNo").value("order-001"))
                    .andExpect(jsonPath("$.status").value("PAID"));
        }

        assertThat(count("platform.payment_transaction")).isEqualTo(1);
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM platform.grant_request WHERE business_no='order:order-001'", String.class))
                .isEqualTo("QUEUED");
    }

    @Test
    void validSignedMockRechargeCallbackCreditsWalletOnceOnReplay() throws Exception {
        jdbcTemplate.update("DELETE FROM platform.payment_transaction");
        UUID rechargeId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount)
                VALUES (?, ?, ?, 'CNY', 0)
                """, UUID.randomUUID(), USER_ID, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO platform.recharge_order(
                    id, recharge_no, tenant_id, user_id, paid_amount,
                    bonus_amount, credited_amount, currency, status,
                    idempotency_key, expires_at)
                VALUES (?, 'recharge-001', ?, ?, 5000, 0, 5000, 'CNY',
                        'PENDING', 'recharge-idempotency-001', ?)
                """, rechargeId, USER_ID, USER_ID, Timestamp.from(ATTEMPT_EXPIRES_AT));
        jdbcTemplate.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, recharge_order_id, provider, amount,
                    status, payment_product, external_trade_no, expires_at,
                    request_payload_hash)
                VALUES (?, 'pay-recharge-001', ?, 'MOCK', 5000, 'PENDING',
                        'MOCK', 'mock-recharge-trade-001', ?, ?)
                """, UUID.randomUUID(), rechargeId, Timestamp.from(ATTEMPT_EXPIRES_AT),
                requestHash("pay-recharge-001", "recharge-001", 5000,
                        "CNY", "MOCK", "MOCK"));

        for (int attempt = 0; attempt < 2; attempt++) {
            signedCallback("mock-recharge-trade-001", "recharge-001", 5000)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderNo").value("recharge-001"))
                    .andExpect(jsonPath("$.status").value("PAID"));
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT available_amount FROM platform.account_balance
                WHERE user_id = ?
                """, Long.class, USER_ID)).isEqualTo(5000L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.balance_ledger
                WHERE user_id = ? AND entry_type = 'RECHARGE'
                  AND business_no = 'recharge-001'
                """, Long.class, USER_ID)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM platform.recharge_order
                WHERE recharge_no = 'recharge-001'
                """, String.class)).isEqualTo("PAID");
    }

    @Test
    void adminSimulatesPaymentFromStoredOrderAndAuditsWithoutExposingSecret() throws Exception {
        mockMvc.perform(post("/console/platform/admin/orders/order-001/simulate-payment")
                        .with(jwt().jwt(token -> token
                                        .subject(USER_ID.toString())
                                        .claim("tenant_id", USER_ID.toString())
                                        .claim("auth_version", 0))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("order-001"))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.externalTradeNo").isString())
                .andExpect(jsonPath("$.signature").doesNotExist())
                .andExpect(jsonPath("$.secret").doesNotExist());

        assertThat(count("platform.payment_transaction")).isEqualTo(1);
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action = 'MOCK_PAYMENT_SIMULATED'
                  AND resource_type = 'ORDER'
                  AND detail->>'externalTradeNo' IS NOT NULL
                  AND detail::text NOT LIKE ?
                """, Integer.class, USER_ID, "%" + CALLBACK_SECRET + "%")).isEqualTo(1);
    }

    @Test
    void adminSimulationRequiresAnExistingMockAttempt() throws Exception {
        jdbcTemplate.update("DELETE FROM platform.payment_transaction");

        mockMvc.perform(post("/console/platform/admin/orders/order-001/simulate-payment")
                        .with(jwt().jwt(token -> token
                                        .subject(USER_ID.toString())
                                        .claim("tenant_id", USER_ID.toString())
                                        .claim("auth_version", 0))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());

        assertThat(count("platform.grant_request")).isZero();
        assertThat(orderStatus("order-001")).isEqualTo("PENDING");
    }

    @Test
    void repeatedMockCallbackGrantsPackageOnce() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            signedCallback("mock-trade-001", "order-001", 1900)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderNo").value("order-001"))
                    .andExpect(jsonPath("$.status").value("PAID"));
        }

        assertThat(count("platform.payment_transaction")).isEqualTo(1);
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM platform.customer_order WHERE order_no='order-001'", String.class))
                .isEqualTo("PAID");
    }

    @Test
    void concurrentDuplicateCallbackGrantsPackageOnce() throws Exception {
        MockPaymentService.CallbackRequest callback = new MockPaymentService.CallbackRequest(
                "mock-trade-concurrent", "order-001", 1900);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<MockPaymentService.CallbackResult>> results = List.of(
                    executor.submit(() -> {
                        start.await();
                        return paymentService.acceptVerified(callback,
                                signature("mock-trade-concurrent", "order-001", 1900));
                    }),
                    executor.submit(() -> {
                        start.await();
                        return paymentService.acceptVerified(callback,
                                signature("mock-trade-concurrent", "order-001", 1900));
                    }));
            start.countDown();

            for (Future<MockPaymentService.CallbackResult> result : results) {
                assertThat(result.get().status()).isEqualTo("PAID");
            }
        }

        assertThat(count("platform.payment_transaction")).isEqualTo(1);
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
    }

    @Test
    void sameExternalTradeNumberCannotPayAnotherOrder() throws Exception {
        insertOrder("order-002");
        callback("mock-trade-shared", "order-001", 1900).andExpect(status().isOk());

        callback("mock-trade-shared", "order-002", 1900)
                .andExpect(status().isConflict());

        assertThat(count("platform.payment_transaction")).isEqualTo(2);
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM platform.customer_order WHERE order_no='order-002'", String.class))
                .isEqualTo("PENDING");
    }

    @Test
    void amountMismatchDoesNotMutateOrderOrGrant() throws Exception {
        callback("mock-trade-wrong-amount", "order-001", 1901)
                .andExpect(status().isConflict());

        assertThat(count("platform.payment_transaction")).isEqualTo(1);
        assertThat(transactionStatus("pay-order-001")).isEqualTo("PENDING");
        assertThat(count("platform.grant_request")).isZero();
        assertThat(count("platform.outbox_event")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM platform.customer_order WHERE order_no='order-001'", String.class))
                .isEqualTo("PENDING");
    }

    @Test
    void zeroAmountIsRejectedWithoutMutation() throws Exception {
        callback("mock-trade-zero-amount", "order-001", 0)
                .andExpect(status().isUnprocessableEntity());

        assertThat(count("platform.payment_transaction")).isEqualTo(1);
        assertThat(transactionStatus("pay-order-001")).isEqualTo("PENDING");
        assertThat(count("platform.grant_request")).isZero();
        assertThat(count("platform.outbox_event")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM platform.customer_order WHERE order_no='order-001'", String.class))
                .isEqualTo("PENDING");
    }

    @Test
    void outboxFailureRollsBackPaymentAndGrant() throws Exception {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION platform.reject_test_outbox() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'forced_outbox_failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_test_outbox
                BEFORE INSERT ON platform.outbox_event
                FOR EACH ROW EXECUTE FUNCTION platform.reject_test_outbox()
                """);
        try {
            assertThatThrownBy(() -> paymentService.acceptVerified(
                    new MockPaymentService.CallbackRequest(
                            "mock-trade-outbox-failure", "order-001", 1900),
                    signature("mock-trade-outbox-failure", "order-001", 1900)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_test_outbox ON platform.outbox_event");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS platform.reject_test_outbox()");
        }

        assertThat(count("platform.payment_transaction")).isEqualTo(1);
        assertThat(transactionStatus("pay-order-001")).isEqualTo("PENDING");
        assertThat(count("platform.grant_request")).isZero();
        assertThat(count("platform.outbox_event")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM platform.customer_order WHERE order_no='order-001'", String.class))
                .isEqualTo("PENDING");
    }

    @Test
    void verifiedWechatCallbackReplayReturnsWechatSuccessAcknowledgement() throws Exception {
        replaceAttempt("order-001", "pay-wechat-001", "WECHAT_PAY");
        String body = providerCallbackBody(
                "pay-wechat-001", "wechat-trade-001", 1900);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/console/platform/payments/wechat-pay/callback")
                            .header("X-Test-Signature", "valid")
                            .header("X-Callback-Context", "complete")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.message").value("成功"));
        }

        assertThat(transactionStatus("pay-wechat-001")).isEqualTo("SUCCEEDED");
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
    }

    @Test
    void wechatCallbackCannotPostAnAlipayAttempt() throws Exception {
        replaceAttempt("order-001", "pay-alipay-cross-route", "ALIPAY");

        mockMvc.perform(post("/console/platform/payments/wechat-pay/callback")
                        .header("X-Test-Signature", "valid")
                        .header("X-Callback-Context", "complete")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(providerCallbackBody(
                                "pay-alipay-cross-route", "alipay-cross-route-trade", 1900)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("FAIL"));

        assertThat(transactionStatus("pay-alipay-cross-route")).isEqualTo("PENDING");
        assertThat(orderStatus("order-001")).isEqualTo("PENDING");
        assertThat(count("platform.grant_request")).isZero();
        assertThat(count("platform.outbox_event")).isZero();
    }

    @Test
    void lateCallbackResolvesTheOriginalAttemptByExternalTradeNumber() throws Exception {
        jdbcTemplate.update("""
                UPDATE platform.payment_transaction
                SET status = 'CLOSED', external_trade_no = 'old-mock-trade'
                WHERE transaction_no = 'pay-order-001'
                """);
        insertAttempt("order-001", "pay-order-retry", "MOCK");
        jdbcTemplate.update("""
                UPDATE platform.payment_transaction
                SET external_trade_no = 'new-mock-trade'
                WHERE transaction_no = 'pay-order-retry'
                """);

        signedCallback("old-mock-trade", "order-001", 1900)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        assertThat(transactionStatus("pay-order-001")).isEqualTo("SUCCEEDED");
        assertThat(transactionStatus("pay-order-retry")).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT applied_to_order
                FROM platform.payment_transaction
                WHERE transaction_no = 'pay-order-001'
                """, Boolean.class)).isTrue();
        assertThat(count("platform.grant_request")).isEqualTo(1);
    }

    @Test
    void verifiedAlipayCallbackReplayReturnsPlainSuccessAcknowledgement() throws Exception {
        replaceAttempt("order-001", "pay-alipay-001", "ALIPAY");
        String body = providerCallbackBody(
                "pay-alipay-001", "alipay-trade-001", 1900);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/console/platform/payments/alipay/callback")
                            .header("X-Test-Signature", "valid")
                            .header("X-Callback-Context", "complete")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(content().string("success"));
        }

        assertThat(transactionStatus("pay-alipay-001")).isEqualTo("SUCCEEDED");
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
    }

    @Test
    void unsignedAndMalformedProviderCallbacksRecordOnlyRedactedAnomalies() throws Exception {
        replaceAttempt("order-001", "pay-wechat-invalid", "WECHAT_PAY");
        String validBody = providerCallbackBody(
                "pay-wechat-invalid", "wechat-trade-invalid", 1900);

        mockMvc.perform(post("/console/platform/payments/wechat-pay/callback")
                        .header("X-Callback-Context", "complete")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(validBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("FAIL"));
        mockMvc.perform(post("/console/platform/payments/wechat-pay/callback")
                        .header("X-Test-Signature", "valid")
                        .header("X-Callback-Context", "complete")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("malformed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("FAIL"));
        mockMvc.perform(post("/console/platform/payments/wechat-pay/callback")
                        .header("X-Test-Signature", "valid")
                        .header("X-Callback-Context", "complete")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(new byte[0]))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("FAIL"));

        assertThat(transactionStatus("pay-wechat-invalid")).isEqualTo("PENDING");
        assertThat(orderStatus("order-001")).isEqualTo("PENDING");
        assertThat(count("platform.grant_request")).isZero();
        assertThat(count("platform.outbox_event")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.payment_anomaly
                WHERE anomaly_type = 'CALLBACK_SIGNATURE_INVALID'
                  AND provider = 'WECHAT_PAY'
                  AND detail = '{"reason":"SIGNATURE_INVALID"}'::jsonb
                  AND detail::text NOT LIKE '%wechat-trade-invalid%'
                  AND detail::text NOT LIKE '%malformed%'
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void encryptsWithRandomNonceAndRejectsTampering() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        SecretEncryptionService encryption = new SecretEncryptionService(key);
        byte[] first = encryption.encrypt("merchant-secret".getBytes(StandardCharsets.UTF_8));
        byte[] second = encryption.encrypt("merchant-secret".getBytes(StandardCharsets.UTF_8));

        assertThat(first).isNotEqualTo(second);
        assertThat(encryption.decrypt(first)).isEqualTo("merchant-secret".getBytes(StandardCharsets.UTF_8));
        first[first.length - 1] ^= 1;
        assertThatThrownBy(() -> encryption.decrypt(first))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("secret_decryption_failed");
        assertThatThrownBy(() -> new SecretEncryptionService(
                Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_secret_master_key");
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String transactionStatus(String transactionNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM platform.payment_transaction WHERE transaction_no = ?",
                String.class, transactionNo);
    }

    private String orderStatus(String orderNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM platform.customer_order WHERE order_no = ?",
                String.class, orderNo);
    }

    private org.springframework.test.web.servlet.ResultActions callback(
            String tradeNo, String orderNo, long amount) throws Exception {
        return mockMvc.perform(post("/console/platform/payments/mock/callback")
                .header("X-Payment-Signature", signature(tradeNo, orderNo, amount))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalTradeNo":"%s","orderNo":"%s","amount":%d}
                        """.formatted(tradeNo, orderNo, amount)));
    }

    private org.springframework.test.web.servlet.ResultActions signedCallback(
            String tradeNo, String orderNo, long amount) throws Exception {
        return mockMvc.perform(post("/console/platform/payments/mock/callback")
                .header("X-Payment-Signature", signature(tradeNo, orderNo, amount))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalTradeNo":"%s","orderNo":"%s","amount":%d}
                        """.formatted(tradeNo, orderNo, amount)));
    }

    private String signature(String tradeNo, String orderNo, long amount) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(CALLBACK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (tradeNo + "\n" + orderNo + "\n" + amount).getBytes(StandardCharsets.UTF_8)));
    }

    private void insertOrder(String orderNo) {
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider)
                VALUES (?, ?, ?, ?, ?, 1900, 'CNY', 'PENDING', 'MOCK')
                """, UUID.randomUUID(), orderNo, USER_ID, USER_ID, VERSION_ID);
        insertAttempt(orderNo, "pay-" + orderNo, "MOCK");
    }

    private void replaceAttempt(String orderNo, String transactionNo, String provider) {
        jdbcTemplate.update("DELETE FROM platform.payment_transaction WHERE order_id = "
                + "(SELECT id FROM platform.customer_order WHERE order_no = ?)", orderNo);
        jdbcTemplate.update(
                "UPDATE platform.customer_order SET payment_provider = ? WHERE order_no = ?",
                provider, orderNo);
        insertAttempt(orderNo, transactionNo, provider);
    }

    private void insertAttempt(String orderNo, String transactionNo, String provider) {
        String product = switch (provider) {
            case "WECHAT_PAY" -> "WECHAT_NATIVE";
            case "ALIPAY" -> "ALIPAY_PAGE";
            default -> "MOCK";
        };
        String requestHash = requestHash(
                transactionNo, orderNo, 1900, "CNY", provider, product);
        jdbcTemplate.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, expires_at, request_payload_hash)
                SELECT ?, ?, id, ?, amount, 'PENDING', ?, ?, ?
                FROM platform.customer_order WHERE order_no = ?
                """, UUID.randomUUID(), transactionNo, provider, product,
                Timestamp.from(ATTEMPT_EXPIRES_AT), requestHash, orderNo);
    }

    private String requestHash(
            String transactionNo,
            String orderNo,
            long amount,
            String currency,
            String provider,
            String product) {
        try {
            String canonical = String.join("\n",
                    transactionNo, orderNo, Long.toString(amount), currency,
                    provider, product, ATTEMPT_EXPIRES_AT.toString());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("test_payment_request_hash_failed", exception);
        }
    }

    private String providerCallbackBody(
            String transactionNo, String externalTradeNo, long amount) {
        return String.join("|", transactionNo, externalTradeNo,
                Long.toString(amount), "CNY", "2030-03-04T05:06:07Z");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CallbackAdapterConfiguration {

        @Bean
        PaymentAdapter testWechatCallbackAdapter() {
            return new CallbackOnlyAdapter(
                    PaymentProvider.WECHAT_PAY, PaymentProduct.WECHAT_NATIVE);
        }

        @Bean
        PaymentAdapter testAlipayCallbackAdapter() {
            return new CallbackOnlyAdapter(
                    PaymentProvider.ALIPAY, PaymentProduct.ALIPAY_PAGE);
        }
    }

    private static final class CallbackOnlyAdapter implements PaymentAdapter {

        private final PaymentProvider provider;
        private final PaymentProduct product;

        private CallbackOnlyAdapter(PaymentProvider provider, PaymentProduct product) {
            this.provider = provider;
            this.product = product;
        }

        @Override
        public PaymentProvider provider() {
            return provider;
        }

        @Override
        public PaymentEnvironment environment() {
            return PaymentEnvironment.LOCAL;
        }

        @Override
        public Set<PaymentProduct> products() {
            return Set.of(product);
        }

        @Override
        public AdapterConfiguration configuration() {
            return new AdapterConfiguration(
                    true, provider.name().toLowerCase() + "-test", "VALID", Instant.EPOCH);
        }

        @Override
        public PaymentCreation createPayment(PaymentCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderPayment verifyAndParseCallback(CallbackEnvelope callback) {
            if (!"valid".equals(header(callback.headers(), "X-Test-Signature"))
                    || !"complete".equals(header(callback.headers(), "X-Callback-Context"))) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "invalid_payment_signature");
            }
            String[] fields = new String(callback.rawBody(), StandardCharsets.UTF_8)
                    .split("\\|", -1);
            if (fields.length != 5) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "invalid_payment_callback");
            }
            return new ProviderPayment(
                    ProviderOutcome.SUCCEEDED, fields[0], fields[1],
                    Long.parseLong(fields[2]), fields[3], Instant.parse(fields[4]),
                    "SUCCESS", null, sha256(callback.rawBody()));
        }

        @Override
        public ProviderPayment queryPayment(PaymentQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderPayment closePayment(PaymentQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderRefund createRefund(RefundCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderRefund queryRefund(RefundQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BillDownload downloadBill(LocalDate billDate) {
            throw new UnsupportedOperationException();
        }

        private static String header(
                Map<String, List<String>> headers, String expectedName) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(expectedName))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst().orElse(null);
        }

        private static String sha256(byte[] body) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(body));
            } catch (Exception exception) {
                throw new IllegalStateException("test_callback_hash_failed", exception);
            }
        }
    }
}
