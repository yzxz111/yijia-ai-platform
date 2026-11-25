package com.yuzhihuihe.apiplatform.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.identity.IdentityService;
import com.yuzhihuihe.apiplatform.platform.identity.JwtTokenService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
@AutoConfigureMockMvc
class OrderControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("94000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("94000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_USER_ID = UUID.fromString("94000000-0000-4000-8000-000000000003");
    /**
     * STARTER 当前处于 PUBLISHED 状态的版本。V61 重新定价并停用了 v1（...102）；
     * V63 将其作为 v3（...122）重新发布以修正售出的速率限制，并停用了 v2。指定已被取代的
     * 版本的订单会被拒绝，错误码为 {@code published_package_version_not_found}。
     */
    private static final UUID STARTER_VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000122");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtTokenService jwtTokenService;

    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void resetData() {
        prepareGatewayPackageInstances();
        jdbcTemplate.update("TRUNCATE platform.admin_audit_log, platform.outbox_event, "
                + "platform.refund, platform.payment_transaction, platform.customer_order, "
                + "platform.grant_request, platform.refresh_token, platform.user_role, "
                + "platform.invitation, platform.app_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO platform.refund_policy_version(
                    version, fee_bps, minimum_fee, refund_window_days,
                    maximum_usage_bps, effective_at, published)
                VALUES (1, 500, 100, 7, 1000, now(), true)
                """);
        insertUser(USER_ID, "order.controller@example.com");
        insertUser(OTHER_USER_ID, "order.other.controller@example.com");
        insertAccount(USER_ID, 0);
        insertAccount(OTHER_USER_ID, 0);
    }

    private void prepareGatewayPackageInstances() {
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS gateway");
        jdbcTemplate.execute("DROP TABLE IF EXISTS gateway.package_instance CASCADE");
        jdbcTemplate.execute("""
                CREATE TABLE gateway.package_instance (
                    id uuid PRIMARY KEY,
                    grant_event_id uuid NOT NULL UNIQUE,
                    grant_request_id uuid NOT NULL UNIQUE,
                    tenant_id uuid NOT NULL,
                    user_id uuid NOT NULL,
                    package_plan_version_id uuid NOT NULL,
                    package_code varchar(64) NOT NULL,
                    package_name varchar(100) NOT NULL,
                    total_token_quota bigint NOT NULL,
                    reserved_tokens bigint NOT NULL DEFAULT 0,
                    used_tokens bigint NOT NULL DEFAULT 0,
                    audio_seconds_quota bigint,
                    audio_seconds_reserved bigint NOT NULL DEFAULT 0,
                    audio_seconds_used bigint NOT NULL DEFAULT 0,
                    status varchar(20) NOT NULL,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
    }

    @Test
    void rejectsMissingBlankAndOver128Utf8ByteIdempotencyKeys() throws Exception {
        String body = orderBody();
        String token = accessToken(USER_ID, "order.controller@example.com");

        mockMvc.perform(post("/console/platform/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/console/platform/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/console/platform/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "\u4e2d".repeat(43))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.customer_order", Integer.class)).isZero();
    }

    @Test
    void firstCreateReturns201AndReplayReturns200WithIdenticalBody() throws Exception {
        String token = accessToken(USER_ID, "order.controller@example.com");

        MvcResult first = createOrder(token, "controller-checkout")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(2900))
                .andExpect(jsonPath("$.packageSnapshot.packageCode").value("STARTER"))
                .andReturn();
        MvcResult replay = createOrder(token, "controller-checkout")
                .andExpect(status().isOk())
                .andReturn();

        assertThat(replay.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.customer_order", Integer.class)).isEqualTo(1);
    }

    @Test
    void orderCreationAndReplayEmitBoundedOutcomeMetrics() throws Exception {
        String token = accessToken(USER_ID, "order.controller@example.com");
        double createdBefore = counterValue("platform.orders.created", "status", "CREATED");
        double replayBefore = counterValue("platform.orders.created", "status", "REPLAY");
        double failedBefore = counterValue("platform.orders.created", "status", "FAILED");

        createOrder(token, "metered-checkout").andExpect(status().isCreated());
        createOrder(token, "metered-checkout").andExpect(status().isOk());
        mockMvc.perform(post("/console/platform/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody()))
                .andExpect(status().isUnprocessableEntity());

        assertThat(counterValue("platform.orders.created", "status", "CREATED"))
                .isEqualTo(createdBefore + 1);
        assertThat(counterValue("platform.orders.created", "status", "REPLAY"))
                .isEqualTo(replayBefore + 1);
        assertThat(counterValue("platform.orders.created", "status", "FAILED"))
                .isEqualTo(failedBefore + 1);
    }

    @Test
    void userCannotReadAnotherUsersOrder() throws Exception {
        String ownerToken = accessToken(USER_ID, "order.controller@example.com");
        JsonNode order = objectMapper.readTree(createOrder(ownerToken, "owned-checkout")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
        String otherToken = accessToken(OTHER_USER_ID, "order.other.controller@example.com");

        mockMvc.perform(get("/console/platform/orders/{orderNo}", order.path("orderNo").asText())
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void authenticatedOwnerCanCloseAnOrderThroughTheLifecycleEndpoint() throws Exception {
        String token = accessToken(USER_ID, "order.controller@example.com");
        JsonNode order = objectMapper.readTree(createOrder(token, "close-checkout")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());

        mockMvc.perform(post("/console/platform/orders/{orderNo}/close", order.path("orderNo").asText())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(order.path("orderNo").asText()))
                .andExpect(jsonPath("$.orderStatus").value("CLOSED"))
                .andExpect(jsonPath("$.paymentStatus").doesNotExist())
                .andExpect(jsonPath("$.retryScheduled").value(false));
    }

    @Test
    void authenticatedOwnerCanPayAnOrderFromBalanceWithImmediateGrantStatus() throws Exception {
        String token = accessToken(USER_ID, "order.controller@example.com");
        JsonNode order = objectMapper.readTree(createOrder(token, "balance-route-order")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
        // 恰为 STARTER 的已发布价格（29 元人民币），这正是使下面的 balanceAfter == 0
        // 有意义的原因。其它余额夹具都有意让订单资金不足。
        setBalance(USER_ID, 2_900);

        MvcResult response = mockMvc.perform(post(
                        "/console/platform/orders/{orderNo}/balance-payment",
                        order.path("orderNo").asText())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "balance-route-payment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(order.path("orderNo").asText()))
                .andExpect(jsonPath("$.orderStatus").value("PAID"))
                .andExpect(jsonPath("$.settlementMethod").value("BALANCE"))
                .andExpect(jsonPath("$.balanceBefore").value(2900))
                .andExpect(jsonPath("$.balanceAfter").value(0))
                .andExpect(jsonPath("$.grantRequestId").isString())
                .andExpect(jsonPath("$.grantStatus").value("QUEUED"))
                .andReturn();

        assertThat(response.getResponse().getContentAsString()).doesNotContain(
                "ledger", "accountId", "idempotencyKey");
        mockMvc.perform(post("/console/platform/orders/{orderNo}/balance-payment",
                        order.path("orderNo").asText())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "balance-route-payment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.balance_ledger", Long.class)).isEqualTo(1);
    }

    @Test
    void balancePaymentRejectsOver128Utf8ByteKeyWithoutMutation() throws Exception {
        String token = accessToken(USER_ID, "order.controller@example.com");
        JsonNode order = objectMapper.readTree(createOrder(token, "balance-long-key-order")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
        String orderNo = order.path("orderNo").asText();
        setBalance(USER_ID, 1_900);

        MvcResult rejected = mockMvc.perform(post(
                        "/console/platform/orders/{orderNo}/balance-payment", orderNo)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "\u4e2d".repeat(64)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        assertThat(rejected.getResolvedException())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("invalid_idempotency_key"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT available_amount FROM platform.account_balance
                WHERE tenant_id = ? AND user_id = ? AND currency = 'CNY'
                """, Long.class, TENANT_ID, USER_ID)).isEqualTo(1_900);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.balance_ledger", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.grant_request", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.outbox_event", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, settlement_method, paid_at,
                       balance_debit_ledger_id, lock_version
                FROM platform.customer_order WHERE order_no = ?
                """, orderNo))
                .containsEntry("status", "PENDING")
                .containsEntry("settlement_method", "UNSELECTED")
                .containsEntry("paid_at", null)
                .containsEntry("balance_debit_ledger_id", null)
                .containsEntry("lock_version", 0L);
    }

    @Test
    void balancePaymentRequiresAKeyAndPreservesStableScopeFundsAndStateErrors()
            throws Exception {
        String token = accessToken(USER_ID, "order.controller@example.com");
        String otherToken = accessToken(
                OTHER_USER_ID, "order.other.controller@example.com");
        JsonNode order = objectMapper.readTree(createOrder(token, "balance-errors-order")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());

        mockMvc.perform(post("/console/platform/orders/{orderNo}/balance-payment",
                        order.path("orderNo").asText())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/console/platform/orders/{orderNo}/balance-payment",
                        order.path("orderNo").asText())
                        .header("Authorization", "Bearer " + otherToken)
                        .header("Idempotency-Key", "balance-cross-scope"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/console/platform/orders/{orderNo}/balance-payment",
                        order.path("orderNo").asText())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "balance-no-funds"))
                .andExpect(status().isUnprocessableEntity());

        setBalance(USER_ID, 1_900);
        mockMvc.perform(post("/console/platform/orders/{orderNo}/close",
                        order.path("orderNo").asText())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/console/platform/orders/{orderNo}/balance-payment",
                        order.path("orderNo").asText())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "balance-closed-order"))
                .andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.balance_ledger", Long.class)).isZero();
    }

    @Test
    void detailContainsOrderedSanitizedPaymentGrantAndRefundState() throws Exception {
        String token = accessToken(USER_ID, "order.controller@example.com");
        JsonNode created = objectMapper.readTree(createOrder(token, "detail-checkout")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
        String orderNo = created.path("orderNo").asText();
        UUID orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform.customer_order WHERE order_no = ?", UUID.class, orderNo);
        UUID earlyPaymentId = UUID.randomUUID();
        UUID latePaymentId = UUID.randomUUID();
        Instant early = Instant.parse("2031-01-01T00:00:00Z");
        Instant late = Instant.parse("2031-01-01T00:01:00Z");
        insertPayment(
                earlyPaymentId, orderId, "pay_early", "WECHAT_PAY", "WECHAT_NATIVE",
                "FAILED", "PAYERROR", "BANK_REJECTED", null, early);
        insertPayment(
                latePaymentId, orderId, "pay_late", "ALIPAY", "ALIPAY_PAGE",
                "SUCCEEDED", "TRADE_SUCCESS", null, late, late);
        jdbcTemplate.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason, created_by, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'ORDER', 'paid order fulfillment', ?, ?)
                """, UUID.randomUUID(), "order:" + orderNo, TENANT_ID, USER_ID,
                STARTER_VERSION_ID, USER_ID, Timestamp.from(late.plusSeconds(30)));
        jdbcTemplate.update("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id,
                    amount, status, reason, completed_at, refund_type, policy_version,
                    calculation_snapshot, requested_by, reviewed_by, reviewed_at, provider,
                    gateway_hold_business_no, revocation_business_no, created_at, updated_at)
                VALUES (?, 'ref_detail', ?, ?, 500, 'COMPLETED', 'detail refund', ?,
                        'PARTIAL', 1, '{"fee":100}'::jsonb, ?, ?, ?, 'ALIPAY',
                        'hold:ref_detail', 'revoke:ref_detail', ?, ?)
                """, UUID.randomUUID(), orderId, latePaymentId,
                Timestamp.from(late.plusSeconds(120)), USER_ID, USER_ID,
                Timestamp.from(late.plusSeconds(60)), Timestamp.from(late.plusSeconds(40)),
                Timestamp.from(late.plusSeconds(120)));

        MvcResult response = mockMvc.perform(get("/console/platform/orders/{orderNo}", orderNo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.orderNo").value(orderNo))
                .andExpect(jsonPath("$.expiresAt").value(created.path("expiresAt").asText()))
                .andExpect(jsonPath("$.packageSnapshot.packageCode").value("STARTER"))
                .andExpect(jsonPath("$.paymentAttempts[0].transactionNo").value("pay_early"))
                .andExpect(jsonPath("$.paymentAttempts[0].provider").value("WECHAT_PAY"))
                .andExpect(jsonPath("$.paymentAttempts[0].product").value("WECHAT_NATIVE"))
                .andExpect(jsonPath("$.paymentAttempts[0].providerErrorCode").value("BANK_REJECTED"))
                .andExpect(jsonPath("$.paymentAttempts[1].transactionNo").value("pay_late"))
                .andExpect(jsonPath("$.grant.status").value("ACTIVE"))
                .andExpect(jsonPath("$.refund.refundNo").value("ref_detail"))
                .andExpect(jsonPath("$.refund.refundType").value("PARTIAL"))
                .andExpect(jsonPath("$.refund.amount").value(500))
                .andReturn();

        String json = response.getResponse().getContentAsString();
        assertThat(json).doesNotContain(
                "externalTradeNo", "callbackHash", "requestPayloadHash",
                "paymentUrl", "clientAction", "credential", "secret");
    }

    private org.springframework.test.web.servlet.ResultActions createOrder(String token, String key)
            throws Exception {
        return mockMvc.perform(post("/console/platform/orders")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(orderBody()));
    }

    private String orderBody() {
        return "{\"packageVersionId\":\"" + STARTER_VERSION_ID + "\"}";
    }

    private void insertUser(UUID userId, String email) {
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'test-password-hash', 'Order Controller User', 'ACTIVE')
                """, userId, TENANT_ID, email);
        Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform.app_role WHERE code = 'USER'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                VALUES (?, ?, ?)
                """, TENANT_ID, userId, roleId);
    }

    private void insertAccount(UUID userId, long amount) {
        jdbcTemplate.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, version)
                VALUES (?, ?, ?, 'CNY', ?, 0)
                """, UUID.randomUUID(), TENANT_ID, userId, amount);
    }

    private void setBalance(UUID userId, long amount) {
        jdbcTemplate.update("""
                UPDATE platform.account_balance
                SET available_amount = ?, version = 0, updated_at = now()
                WHERE tenant_id = ? AND user_id = ? AND currency = 'CNY'
                """, amount, TENANT_ID, userId);
    }

    private String accessToken(UUID userId, String email) {
        return jwtTokenService.createAccessToken(new IdentityService.UserProfile(
                userId, TENANT_ID, email, "Order Controller User", "USER"));
    }

    private void insertPayment(
            UUID id, UUID orderId, String transactionNo, String provider, String product,
            String status, String providerStatus, String providerErrorCode,
            Instant paidAt, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, external_trade_no,
                    amount, status, callback_hash, paid_at, created_at, updated_at,
                    payment_product, provider_status, provider_error_code,
                    request_payload_hash)
                VALUES (?, ?, ?, ?, ?, 2900, ?, 'private-callback-hash', ?, ?, ?, ?, ?, ?,
                        'private-request-payload-hash')
                """, id, transactionNo, orderId, provider, "private-trade-" + transactionNo,
                status, timestamp(paidAt), Timestamp.from(createdAt), Timestamp.from(createdAt),
                product, providerStatus, providerErrorCode);
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private double counterValue(String name, String tagName, String tagValue) {
        Counter counter = meterRegistry.find(name).tag(tagName, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }
}
