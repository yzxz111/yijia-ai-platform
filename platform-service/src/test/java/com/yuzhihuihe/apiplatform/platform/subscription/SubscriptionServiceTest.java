package com.yuzhihuihe.apiplatform.platform.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 连续包月的开通 / 解约 / 恢复。
 *
 * <p>这一层是第一次真正把「订阅」接到既有的商业链路上：开通首期走的是
 * {@code OrderApplicationService} + {@code BalanceOrderPaymentService}，
 * 因此本类同时验证了**订阅没有绕过既有的下单、扣款、记账与发放**。
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "outbox.publisher.initial-delay-ms=3600000"
})
class SubscriptionServiceTest {

    private static final long PLAN_PRICE = 2_900L;
    private static final int PLAN_DURATION_DAYS = 30;

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
    SubscriptionService service;

    private UUID tenantId;
    private UUID userId;
    private UUID planId;
    private UUID versionId;
    private int nextVersionNumber;

    @BeforeEach
    void reset() {
        jdbc.update("TRUNCATE platform.subscription_cycle, platform.subscription, "
                + "platform.admin_audit_log, platform.outbox_event, platform.grant_request, "
                + "platform.balance_ledger, platform.customer_order, platform.package_plan RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM platform.account_balance");
        jdbc.update("DELETE FROM platform.app_user");
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        planId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        nextVersionNumber = 2;
        // gateway schema 不在平台侧迁移里，但既有购买策略会读它，因此必须建替身表。
        prepareGatewayPackageInstances();
        insertUser(userId, tenantId, "subscriber@yuzhi.test");
        insertPlan();
        insertPublishedVersion(versionId, PLAN_PRICE);
        insertAccount(tenantId, userId, PLAN_PRICE);
    }

    private void prepareGatewayPackageInstances() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS gateway");
        jdbc.execute("DROP TABLE IF EXISTS gateway.package_instance");
        jdbc.execute("""
                CREATE TABLE gateway.package_instance (
                    id uuid PRIMARY KEY,
                    grant_request_id uuid UNIQUE,
                    tenant_id uuid NOT NULL,
                    user_id uuid NOT NULL,
                    package_code varchar(64) NOT NULL,
                    package_name varchar(100) NOT NULL,
                    package_kind varchar(20) NOT NULL DEFAULT 'STANDARD',
                    total_token_quota bigint NOT NULL,
                    reserved_tokens bigint NOT NULL DEFAULT 0,
                    used_tokens bigint NOT NULL DEFAULT 0,
                    audio_seconds_quota bigint,
                    audio_seconds_reserved bigint NOT NULL DEFAULT 0,
                    audio_seconds_used bigint NOT NULL DEFAULT 0,
                    usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN',
                    status varchar(20) NOT NULL,
                    starts_at timestamptz,
                    expires_at timestamptz,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
    }

    @Test
    void openingASubscriptionChargesTheWalletAndStartsTheFirstPeriod() {
        SubscriptionService.SubscriptionView view =
                service.open(tenantId, userId, versionId, "open-1");

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.autoRenew()).isTrue();
        assertThat(view.cancelAtPeriodEnd()).isFalse();
        assertThat(view.renewalCount()).isZero();
        // 首期已扣金额来自套餐价格快照。
        assertThat(view.lastChargedAmount()).isEqualTo(PLAN_PRICE);
        // 账期由 durationDays 推进，且下次扣款在到期前 24 小时。
        assertThat(ChronoUnit.DAYS.between(view.currentPeriodStart(), view.currentPeriodEnd()))
                .isEqualTo(PLAN_DURATION_DAYS);
        assertThat(ChronoUnit.HOURS.between(view.nextChargeAt(), view.currentPeriodEnd()))
                .isEqualTo(24);

        // 钱真的走了既有的账本链路：余额归零，且留了一条 PACKAGE_PURCHASE 流水。
        assertThat(balance()).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT entry_type, amount_delta, business_type
                FROM platform.balance_ledger
                """)).containsEntry("entry_type", "PACKAGE_PURCHASE")
                .containsEntry("amount_delta", -PLAN_PRICE)
                .containsEntry("business_type", "ORDER");

        // 订单走的是既有的余额结算路径，不是新造的一条。
        assertThat(jdbc.queryForMap("""
                SELECT status, settlement_method FROM platform.customer_order
                """)).containsEntry("status", "PAID").containsEntry("settlement_method", "BALANCE");

        // 首期账期行记在 period_index=1（约束要求 > 0），与续期的序号不冲突。
        assertThat(jdbc.queryForMap("""
                SELECT period_index, status, amount, order_no, grant_request_id
                FROM platform.subscription_cycle
                """)).containsEntry("period_index", 1)
                .containsEntry("status", "CHARGED")
                .containsEntry("amount", PLAN_PRICE);
        assertThat(jdbc.queryForObject("""
                SELECT order_no IS NOT NULL AND grant_request_id IS NOT NULL
                FROM platform.subscription_cycle
                """, Boolean.class)).isTrue();
    }

    @Test
    void openingTwiceReturnsTheSameSubscriptionWithoutChargingAgain() {
        SubscriptionService.SubscriptionView first =
                service.open(tenantId, userId, versionId, "open-1");
        SubscriptionService.SubscriptionView second =
                service.open(tenantId, userId, versionId, "open-2");

        assertThat(second.id()).isEqualTo(first.id());
        // 只有一份订阅、一个订单、一笔扣款流水。
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.subscription", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.customer_order", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.balance_ledger", Integer.class)).isOne();
        assertThat(balance()).isZero();
    }

    @Test
    void anInsufficientWalletLeavesNoSubscriptionBehind() {
        // 余额清零，让首期支付必然失败。
        jdbc.update("UPDATE platform.account_balance SET available_amount = 0");

        assertThatThrownBy(() -> service.open(tenantId, userId, versionId, "open-poor"))
                .isInstanceOf(ResponseStatusException.class);

        // 关键：整条链路在同一事务里回滚，不留下「有订阅但没付钱」的中间态。
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.subscription", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.subscription_cycle", Integer.class)).isZero();
        assertThat(balance()).isZero();
    }

    @Test
    void cancellingKeepsTheCurrentPeriodAndStopsFutureCharges() {
        SubscriptionService.SubscriptionView opened =
                service.open(tenantId, userId, versionId, "open-1");

        SubscriptionService.SubscriptionView cancelled =
                service.cancel(tenantId, userId, opened.id());

        // 当期用满为止：状态仍是 ACTIVE，但续费已关闭、账期不变。
        assertThat(cancelled.status()).isEqualTo("ACTIVE");
        assertThat(cancelled.autoRenew()).isFalse();
        assertThat(cancelled.cancelAtPeriodEnd()).isTrue();
        assertThat(cancelled.currentPeriodEnd()).isEqualTo(opened.currentPeriodEnd());
        // 取消标记与关闭自动续费必须同向（ck_subscription_cancel_marker）。
        assertThat(jdbc.queryForObject("""
                SELECT auto_renew = false AND cancel_at_period_end = true
                FROM platform.subscription
                """, Boolean.class)).isTrue();
    }

    @Test
    void resumingRestoresAutoRenewWithinThePeriod() {
        SubscriptionService.SubscriptionView opened =
                service.open(tenantId, userId, versionId, "open-1");
        service.cancel(tenantId, userId, opened.id());

        SubscriptionService.SubscriptionView resumed =
                service.resume(tenantId, userId, opened.id());

        assertThat(resumed.autoRenew()).isTrue();
        assertThat(resumed.cancelAtPeriodEnd()).isFalse();
        assertThat(resumed.status()).isEqualTo("ACTIVE");
    }

    @Test
    void resumingAfterThePeriodEndedIsRefused() {
        SubscriptionService.SubscriptionView opened =
                service.open(tenantId, userId, versionId, "open-1");
        service.cancel(tenantId, userId, opened.id());
        // 把整个账期窗口推到过去：ck_subscription_period 要求 end > start，
        // 因此不能只改其中一列。
        jdbc.update("""
                UPDATE platform.subscription
                SET current_period_start = now() - interval '31 days',
                    current_period_end = now() - interval '1 day',
                    next_charge_at = now() - interval '2 days'
                WHERE id = ?
                """, opened.id());

        assertThatThrownBy(() -> service.resume(tenantId, userId, opened.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("subscription_period_ended");
    }

    @Test
    void anotherUsersSubscriptionLooksLikeItDoesNotExist() {
        SubscriptionService.SubscriptionView opened =
                service.open(tenantId, userId, versionId, "open-1");

        // 404 而不是 403：不区分「不存在」与「不是你的」，与既有套餐实例接口一致。
        assertThatThrownBy(() -> service.detail(UUID.randomUUID(), userId, opened.id()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.detail(tenantId, UUID.randomUUID(), opened.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("subscription_not_found");
    }

    @Test
    void detailListsTheChargedCyclesInDescendingPeriodOrder() {
        SubscriptionService.SubscriptionView opened =
                service.open(tenantId, userId, versionId, "open-1");

        SubscriptionService.SubscriptionDetail detail =
                service.detail(tenantId, userId, opened.id());

        assertThat(detail.subscription().id()).isEqualTo(opened.id());
        assertThat(detail.cycles()).singleElement().satisfies(cycle -> {
            assertThat(cycle.periodIndex()).isEqualTo(1);
            assertThat(cycle.status()).isEqualTo("CHARGED");
            assertThat(cycle.amount()).isEqualTo(PLAN_PRICE);
            assertThat(cycle.orderNo()).isNotBlank();
        });
    }

    @Test
    void listReturnsOnlyTheCallersSubscriptions() {
        service.open(tenantId, userId, versionId, "open-1");

        assertThat(service.list(tenantId, userId)).hasSize(1);
        assertThat(service.list(UUID.randomUUID(), userId)).isEmpty();
        assertThat(service.list(tenantId, UUID.randomUUID())).isEmpty();
    }

    @Test
    void openingWithABlankIdempotencyKeyIsRejectedBeforeAnythingIsCharged() {
        assertThatThrownBy(() -> service.open(tenantId, userId, versionId, "  "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_idempotency_key");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.customer_order", Integer.class)).isZero();
        assertThat(balance()).isEqualTo(PLAN_PRICE);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private long balance() {
        Long value = jdbc.queryForObject("""
                SELECT available_amount FROM platform.account_balance
                WHERE tenant_id = ? AND user_id = ?
                """, Long.class, tenantId, userId);
        return value == null ? 0L : value;
    }

    private void insertUser(UUID id, UUID tenant, String email) {
        jdbc.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'x', 'Subscriber', 'ACTIVE')
                """, id, tenant, email);
    }

    private void insertAccount(UUID tenant, UUID user, long amount) {
        jdbc.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, reserved_amount, version)
                VALUES (?, ?, ?, 'CNY', ?, 0, 0)
                """, UUID.randomUUID(), tenant, user, amount);
    }

    private void insertPlan() {
        jdbc.update("""
                INSERT INTO platform.package_plan(id, code, name, status, kind)
                VALUES (?, ?, '连续包月测试套餐', 'ACTIVE', 'STANDARD')
                """, planId, "SUB_TEST_" + planId.toString().replace("-", "").substring(0, 10).toUpperCase());
    }

    private void insertPublishedVersion(UUID id, long price) {
        jdbc.update("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, status, price_amount, currency,
                    total_token_quota, duration_days, max_api_keys,
                    requests_per_minute, tokens_per_minute, max_concurrency,
                    target_margin_ratio, published_at)
                VALUES (?, ?, ?, 'PUBLISHED', ?, 'CNY', 2000000, ?, 2, 60, 10000, 2, 0.30, now())
                """, id, planId, nextVersionNumber++, price, PLAN_DURATION_DAYS);
    }
}
