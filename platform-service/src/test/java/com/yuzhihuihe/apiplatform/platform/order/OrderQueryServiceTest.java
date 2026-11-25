package com.yuzhihuihe.apiplatform.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentActionType;
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
class OrderQueryServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("97000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("97000000-0000-4000-8000-000000000002");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("97000000-0000-4000-8000-000000000003");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("97000000-0000-4000-8000-000000000004");
    private static final UUID VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final Instant CREATED_AT = Instant.parse("2026-08-05T08:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2030-08-05T08:15:00Z");

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
    ObjectMapper objectMapper;

    @Autowired
    OrderQueryService service;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("TRUNCATE platform.admin_audit_log, platform.outbox_event, "
                + "platform.grant_request, platform.app_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'order-query@example.test', 'hash', 'Order Query User', 'ACTIVE')
                """, USER_ID, TENANT_ID);
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'order-query-other@example.test', 'hash', 'Other User', 'ACTIVE')
                """, OTHER_USER_ID, TENANT_ID);
        jdbcTemplate.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, version)
                VALUES (?, ?, ?, 'CNY', 8_100, 1)
                """, ACCOUNT_ID, TENANT_ID, USER_ID);
    }

    @Test
    void listAndDetailExposeCanonicalSettlementMethodAndProviderPairs() {
        insertOrder("ord_query_unpaid", "PENDING", "UNSELECTED", "UNSELECTED", null);
        insertOrder("ord_query_external", "PAID", "EXTERNAL", "ALIPAY", null);
        UUID walletOrderId = insertOrder(
                "ord_query_wallet", "PENDING", "UNSELECTED", "UNSELECTED", null);
        UUID ledgerId = insertWalletDebit(walletOrderId, "ord_query_wallet");
        jdbcTemplate.update("""
                UPDATE platform.customer_order
                SET status = 'PAID', paid_at = ?, settlement_method = 'BALANCE',
                    payment_provider = 'UNSELECTED', balance_debit_ledger_id = ?,
                    lock_version = lock_version + 1
                WHERE id = ?
                """, Timestamp.from(CREATED_AT.plusSeconds(60)), ledgerId, walletOrderId);

        Map<String, OrderQueryService.OrderView> orders = service
                .list(TENANT_ID, USER_ID, 0, 20, null)
                .items().stream()
                .collect(Collectors.toMap(
                        OrderQueryService.OrderView::orderNo, Function.identity()));

        assertThat(orders.get("ord_query_unpaid").settlementMethod()).isEqualTo("UNSELECTED");
        assertThat(orders.get("ord_query_unpaid").paymentProvider()).isEqualTo("UNSELECTED");
        assertThat(orders.get("ord_query_external").settlementMethod()).isEqualTo("EXTERNAL");
        assertThat(orders.get("ord_query_external").paymentProvider()).isEqualTo("ALIPAY");
        assertThat(orders.get("ord_query_wallet").settlementMethod()).isEqualTo("BALANCE");
        assertThat(orders.get("ord_query_wallet").paymentProvider()).isEqualTo("UNSELECTED");
        assertThat(service.detail(TENANT_ID, USER_ID, "ord_query_wallet")
                .order().settlementMethod()).isEqualTo("BALANCE");
    }

    @Test
    void listAndDetailKeepThePackageNameCapturedAtOrderCreation() {
        UUID orderId = insertOrder(
                "ord_query_snapshot", "PAID", "EXTERNAL", "ALIPAY", null);
        jdbcTemplate.update("""
                UPDATE platform.customer_order
                SET package_snapshot = jsonb_set(
                    package_snapshot, '{packageName}', to_jsonb(?::text))
                WHERE id = ?
                """, "Starter Historical", orderId);

        OrderQueryService.OrderView listed = service
                .list(TENANT_ID, USER_ID, 0, 20, null)
                .items().getFirst();
        OrderQueryService.OrderView detailed = service
                .detail(TENANT_ID, USER_ID, "ord_query_snapshot")
                .order();

        assertThat(listed.packageName()).isEqualTo("Starter Historical");
        assertThat(detailed.packageName()).isEqualTo("Starter Historical");
    }

    @Test
    void serializedOrderDtosNeverExposeWalletOrIdempotencyIdentifiers() throws Exception {
        UUID walletOrderId = insertOrder(
                "ord_query_private", "PENDING", "UNSELECTED", "UNSELECTED", null);
        UUID ledgerId = insertWalletDebit(walletOrderId, "ord_query_private");
        jdbcTemplate.update("""
                UPDATE platform.customer_order
                SET status = 'PAID', paid_at = ?, settlement_method = 'BALANCE',
                    balance_debit_ledger_id = ?
                WHERE id = ?
                """, Timestamp.from(CREATED_AT.plusSeconds(60)), ledgerId, walletOrderId);

        String listJson = objectMapper.writeValueAsString(
                service.list(TENANT_ID, USER_ID, 0, 20, null));
        String detailJson = objectMapper.writeValueAsString(
                service.detail(TENANT_ID, USER_ID, "ord_query_private"));

        assertThat(listJson).contains("\"settlementMethod\":\"BALANCE\"");
        assertThat(detailJson).contains("\"settlementMethod\":\"BALANCE\"");
        assertThat(listJson + detailJson).doesNotContain(
                "balanceDebitLedgerId", "accountId", "idempotencyKey", ledgerId.toString());
    }

    @Test
    void detailRestoresOnlyOwnedActivePaymentActions() {
        UUID orderId = insertOrder(
                "ord_query_resume", "PENDING", "EXTERNAL", "WECHAT_PAY", null);
        jdbcTemplate.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, expires_at, payment_action, created_at, updated_at)
                VALUES (?, 'pay_query_resume', ?, 'WECHAT_PAY', 1900, 'PENDING',
                        'WECHAT_NATIVE', ?, ?::jsonb, ?, ?)
                """, UUID.randomUUID(), orderId, Timestamp.from(EXPIRES_AT), """
                {
                  "type":"QR_CODE",
                  "targetUrl":null,
                  "fields":{},
                  "qrContent":"weixin://pay/query-resume",
                  "expiresAt":"2030-08-05T08:15:00Z"
                }
                """, Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));

        OrderQueryService.PaymentAttemptView attempt = service
                .detail(TENANT_ID, USER_ID, "ord_query_resume")
                .paymentAttempts().getFirst();

        assertThat(attempt.action()).isNotNull();
        assertThat(attempt.action().type()).isEqualTo(PaymentActionType.QR_CODE);
        assertThat(attempt.action().qrContent()).isEqualTo("weixin://pay/query-resume");
        assertThatThrownBy(() -> service.detail(TENANT_ID, OTHER_USER_ID, "ord_query_resume"))
                .hasMessageContaining("order_not_found");

        jdbcTemplate.update("""
                UPDATE platform.payment_transaction SET status = 'SUCCEEDED'
                WHERE transaction_no = 'pay_query_resume'
                """);
        assertThat(service.detail(TENANT_ID, USER_ID, "ord_query_resume")
                .paymentAttempts().getFirst().action()).isNull();
    }

    private UUID insertOrder(
            String orderNo,
            String status,
            String settlementMethod,
            String paymentProvider,
            UUID balanceDebitLedgerId) {
        UUID orderId = UUID.randomUUID();
        Instant paidAt = "PAID".equals(status) ? CREATED_AT.plusSeconds(60) : null;
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, paid_at,
                    package_snapshot, idempotency_key, expires_at, next_close_at,
                    settlement_method, balance_debit_ledger_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1900, 'CNY', ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                """, orderId, orderNo, TENANT_ID, USER_ID, VERSION_ID,
                status, paymentProvider, timestamp(paidAt), snapshot(), "create:" + orderNo,
                Timestamp.from(EXPIRES_AT), Timestamp.from(EXPIRES_AT), settlementMethod,
                balanceDebitLedgerId, Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
        return orderId;
    }

    private UUID insertWalletDebit(UUID orderId, String orderNo) {
        UUID ledgerId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.balance_ledger(
                    id, entry_no, tenant_id, user_id, account_id,
                    entry_type, amount_delta, paid_amount, bonus_amount,
                    balance_before, balance_after, business_type, business_id,
                    business_no, idempotency_key)
                VALUES (?, ?, ?, ?, ?, 'PACKAGE_PURCHASE', -1900, 0, 0,
                        10000, 8100, 'ORDER', ?, ?, ?)
                """, ledgerId, "wle_" + orderNo, TENANT_ID, USER_ID, ACCOUNT_ID,
                orderId, orderNo, "wallet:" + orderNo);
        return ledgerId;
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String snapshot() {
        return """
                {
                  "packageVersionId":"%s",
                  "packageCode":"STARTER",
                  "packageName":"Starter",
                  "priceAmount":1900,
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
                """.formatted(VERSION_ID);
    }
}
