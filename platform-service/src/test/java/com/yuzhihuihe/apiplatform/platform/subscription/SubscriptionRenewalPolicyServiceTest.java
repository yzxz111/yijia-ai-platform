package com.yuzhihuihe.apiplatform.platform.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 续费策略的行为。
 *
 * <p>重点不是「续费能通过」，而是**续费在既有购买策略会拒绝的完全相同条件下不被拒**：
 * 既有的 {@code PackagePurchasePolicyService} 有两条对自动续费的硬阻断
 * （当期还有额度就拒、累计 3 次就拒），连续包月第 4 个月必然撞上第二条。
 *
 * <p>「既有策略未被改松」的断言不在这里，而在
 * {@code platform.order.PackagePurchasePolicyIsolationTest} —— 那个类必须待在
 * {@code order} 包内才能构造包私有的仓储。两者合起来才是双向证明。
 */
class SubscriptionRenewalPolicyServiceTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");
    private static final DataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        DATA_SOURCE = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(DATA_SOURCE).defaultSchema("platform").schemas("platform")
                .locations("classpath:db/migration").load().migrate();
    }

    private static final int MAX_RENEWALS = 36;

    private final JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);
    private final SubscriptionRepository subscriptions = new SubscriptionRepository(jdbc);
    private final SubscriptionCycleRepository cycles = new SubscriptionCycleRepository(jdbc);
    private final SubscriptionPlanReader plans = new SubscriptionPlanReader(jdbc);
    private final SubscriptionRenewalPolicyService policy =
            new SubscriptionRenewalPolicyService(subscriptions, plans);

    private UUID tenantId;
    private UUID userId;
    private UUID planId;
    private UUID versionId;
    private String planCode;
    private int nextVersionNumber;

    @BeforeEach
    void reset() throws SQLException {
        jdbc.execute("DELETE FROM platform.subscription_cycle");
        jdbc.execute("DELETE FROM platform.subscription");
        // gateway schema 不在平台侧迁移里，必须自己建一张最小表。
        // 这是仓库既有做法（见 OrderApplicationServiceTest.prepareGatewayPackageInstances）。
        prepareGatewayPackageInstances();
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        planId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        nextVersionNumber = 2;
        planCode = "SUB_POLICY_" + planId.toString().replace("-", "").substring(0, 10).toUpperCase();
        execute("""
                INSERT INTO platform.app_user(tenant_id, id, email, password_hash, display_name, status)
                VALUES ('%s', '%s', '%s', 'x', 'Subscriber', 'ACTIVE')
                """.formatted(tenantId, userId, userId + "@yuzhi.test"));
        jdbc.update("""
                INSERT INTO platform.package_plan(id, code, name, status, kind)
                VALUES (?, ?, 'Sub Test', 'ACTIVE', 'STANDARD')
                """, planId, planCode);
        insertVersion(versionId, planId, 2900, "PUBLISHED");
    }

    /**
     * 平台侧测试容器里**没有** gateway schema（gateway 的迁移在另一个模块）。
     * 因此凡是会读到 {@code gateway.package_instance} 的用例都要先建一张最小替身表，
     * 且列必须覆盖被测查询真正用到的字段。
     */
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

    /**
     * 既有策略的第一个硬阻断点：只看「还有没有剩余额度」，完全不看 {@code expires_at}。
     *
     * <p>用户当月额度没用完不能成为下一期不能续费的理由。这里断言续费路径在这种
     * 「既有策略会拒绝」的状态下仍然给出**明确原因**的跳过，而不是抛异常。
     */
    @Test
    void renewalToleratesAnUnconsumedActivePackageThatWouldBlockCheckout() {
        jdbc.update("""
                INSERT INTO gateway.package_instance(
                    id, tenant_id, user_id, package_code, package_name, package_kind,
                    total_token_quota, used_tokens, reserved_tokens, status, usage_dimension)
                VALUES (?, ?, ?, ?, 'Sub Test', 'STANDARD', 2000000, 100, 0, 'ACTIVE', 'TOKEN')
                """, UUID.randomUUID(), tenantId, userId, planCode);
        SubscriptionRepository.SubscriptionRow subscription = activeSubscription(0);

        // 既有策略在这个状态下会抛 package_quota_remaining（见同目录的
        // PackagePurchasePolicyIsolationTest 的对应断言）。续费侧不抛异常，
        // 而是命中同维度单活跃实例守卫（§5.8）。
        SubscriptionRenewalPolicyService.Decision decision = evaluate(subscription);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.skipReason())
                .isEqualTo(SubscriptionRenewalPolicyService.SkipReason.ACTIVE_PACKAGE_PRESENT);
    }

    /**
     * 守卫**必须按维度收窄**。
     *
     * <p>本设计写成时 {@code uq_active_package_per_user} 是 {@code UNIQUE (user_id)}
     * （每用户全局一个活跃实例），所以「有任意活跃实例」与「该维度已有活跃实例」等价。
     * 但 {@code V80__dimension_scoped_active_packages.sql} 已把它收窄为
     * {@code UNIQUE (user_id, usage_dimension)}，跨维度并存成为既合法又预期的状态。
     *
     * <p>实测缺陷（2026-09-28）：只持有 VIDEO_SECOND 活跃实例的用户，其 TOKEN 订阅被
     * 永久跳过续费（{@code last_skip_reason=active_package_present}，钱包与订单数都不动）。
     * 本条用例锁住修复，防止有人把守卫改回「任意维度」。
     */
    @Test
    void anActiveInstanceInAnotherDimensionDoesNotBlockRenewal() {
        jdbc.update("""
                INSERT INTO gateway.package_instance(
                    id, tenant_id, user_id, package_code, package_name, package_kind,
                    total_token_quota, used_tokens, reserved_tokens, status, usage_dimension)
                VALUES (?, ?, ?, ?, 'Unrelated Video', 'STANDARD', 2000000, 100, 0, 'ACTIVE', 'VIDEO_SECOND')
                """, UUID.randomUUID(), tenantId, userId, planCode);
        SubscriptionRepository.SubscriptionRow subscription = activeSubscription(0);

        SubscriptionRenewalPolicyService.Decision decision = evaluate(subscription);

        assertThat(decision.allowed())
                .as("a VIDEO_SECOND instance must not block a TOKEN subscription")
                .isTrue();
        assertThat(decision.skipReason()).isNull();
    }

    /** 反向：同维度已有活跃实例时仍然跳过（用户已为该维度付过钱）。 */
    @Test
    void anActiveInstanceInTheSameDimensionStillSkipsRenewal() {
        jdbc.update("""
                INSERT INTO gateway.package_instance(
                    id, tenant_id, user_id, package_code, package_name, package_kind,
                    total_token_quota, used_tokens, reserved_tokens, status, usage_dimension)
                VALUES (?, ?, ?, ?, 'Same Dimension', 'STANDARD', 2000000, 100, 0, 'ACTIVE', 'TOKEN')
                """, UUID.randomUUID(), tenantId, userId, planCode);
        SubscriptionRepository.SubscriptionRow subscription = activeSubscription(0);

        SubscriptionRenewalPolicyService.Decision decision = evaluate(subscription);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.skipReason())
                .isEqualTo(SubscriptionRenewalPolicyService.SkipReason.ACTIVE_PACKAGE_PRESENT);
    }

    /**
     * 既有策略的第二个硬阻断点：{@code MAX_PURCHASES_PER_PACKAGE = 3}。
     * 这里用「已有 3 个 PENDING 订单」把计数顶到上限（策略把 PENDING 也算进购买次数）。
     */
    @Test
    void threePurchaseCapNeverBlocksRenewal() {
        for (int index = 0; index < 3; index++) {
            UUID orderId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO platform.customer_order(
                        id, order_no, tenant_id, user_id, package_plan_version_id,
                        amount, currency, status, payment_provider,
                        expires_at, next_close_at, inventory_status, settlement_method)
                    VALUES (?, ?, ?, ?, ?, 2900, 'CNY', 'PENDING', 'MOCK',
                        now() + interval '15 minutes', now(), 'RESERVED', 'UNSELECTED')
                    """, orderId, "ord_" + orderId.toString().replace("-", ""), tenantId, userId, versionId);
        }

        SubscriptionRenewalPolicyService.Decision decision = evaluate(activeSubscription(0));

        // 续费放行：renewal_count 是订阅自己的计数，与「买过几次」无关。
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.amount()).isEqualTo(2900L);
    }

    /**
     * 确认涨价之后**不得再次**判定为涨价 —— 否则用户点了确认也永远等不到扣款。
     *
     * <p>这是实测踩到过的死循环：确认只清了 pending 字段，而策略仍拿 {@code last_charged_amount}
     * （旧价）与已发布价比较，于是每一轮都重新暂缓一次。修法是让确认写入
     * {@code accepted_price_amount}，并把比较基准取为两者中较大的一个。
     */
    @Test
    void aConfirmedPriceIsNoLongerTreatedAsAnIncrease() {
        SubscriptionRepository.SubscriptionRow row = activeSubscription(1);
        jdbc.update("""
                UPDATE platform.subscription
                SET last_charged_amount = 2900, accepted_price_amount = 3900
                WHERE id = ?
                """, row.id());
        republishWithPrice(3900);

        // 已接受 3900，发布价也是 3900：应当直接放行，而不是再要一次确认。
        SubscriptionRenewalPolicyService.Decision decision = evaluate(reload(row));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.amount()).isEqualTo(3900L);
    }

    /**
     * 但确认**不能**变成永久豁免：确认之后管理员再涨价，必须再次要求确认。
     *
     * <p>否则用户确认一次就等于放弃了对该订阅的价格保护——那比原来的死循环更危险。
     */
    @Test
    void aFurtherIncreaseAfterAConfirmationStillRequiresConfirmation() {
        SubscriptionRepository.SubscriptionRow row = activeSubscription(1);
        jdbc.update("""
                UPDATE platform.subscription
                SET last_charged_amount = 2900, accepted_price_amount = 3900
                WHERE id = ?
                """, row.id());
        republishWithPrice(4500);

        SubscriptionRenewalPolicyService.Decision decision = evaluate(reload(row));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.failureReason()).isEqualTo("subscription_price_changed");
        assertThat(decision.previousAmount()).isEqualTo(3900L);
        assertThat(decision.newAmount()).isEqualTo(4500L);
    }

    @Test
    void skippedRenewalsCarryThePreciseReasonRatherThanLookingLikeFailures() {
        // §7.5：跳过必须可与失败区分，否则后台失败率告警会长期红着、运维随之忽略它。
        SubscriptionRepository.SubscriptionRow open = activeSubscription(0);

        jdbc.update("UPDATE platform.subscription SET auto_renew = false, cancel_at_period_end = true WHERE id = ?",
                open.id());
        assertThat(evaluate(reload(open)).skipReason())
                .isEqualTo(SubscriptionRenewalPolicyService.SkipReason.CANCELED_BY_USER);

        jdbc.update("UPDATE platform.subscription SET auto_renew = true, cancel_at_period_end = false WHERE id = ?",
                open.id());
        jdbc.update("UPDATE platform.app_user SET status = 'DISABLED' WHERE id = ?", userId);
        assertThat(evaluate(reload(open)).skipReason())
                .isEqualTo(SubscriptionRenewalPolicyService.SkipReason.ACCOUNT_NOT_ACTIVE);

        jdbc.update("UPDATE platform.app_user SET status = 'ACTIVE' WHERE id = ?", userId);
        // 把一个计划下的唯一已发布版本退役且不再发布新版本 ⇒ 该计划当前不可续费。
        retire(versionId);
        assertThat(evaluate(reload(open)).skipReason())
                .isEqualTo(SubscriptionRenewalPolicyService.SkipReason.PLAN_UNAVAILABLE);
    }

    @Test
    void aChargedPeriodWhoseInstanceIsNotCreatedYetDoesNotBlockTheNextRenewal() {
        // 反回归：曾经的实现里这里有一条「已有未激活账期就跳过」的守卫，它**恒为真**——
        // 首期账期在开通时就是 CHARGED，而它的套餐实例要到激活时才由网关创建，
        // 所以「最近一个账期已扣款但没有实例」是**正常状态**。那条守卫会让每一次续费
        // 都跳过，续费功能直接不工作。
        SubscriptionRepository.SubscriptionRow subscription = activeSubscription(0);
        jdbc.update("""
                INSERT INTO platform.subscription_cycle(
                    id, subscription_id, period_index, period_start, period_end,
                    business_key, status, amount, currency, package_plan_version_id)
                VALUES (?, ?, 1, now(), now() + interval '30 days', ?, 'CHARGED', 2900, 'CNY', ?)
                """, UUID.randomUUID(), subscription.id(),
                "renewal:" + subscription.id() + ":1", versionId);

        // 只有「已扣款的账期」而没有实例 —— 这是正常状态，必须放行。
        assertThat(evaluate(subscription).allowed()).isTrue();
    }

    @Test
    void anActivePackageInstanceStillSuppressesTheRenewal() {
        // 「用户已有活跃实例」这条守卫必须保留：它防的是用户刚手工买过同类套餐时
        // 再扣一次钱。
        SubscriptionRepository.SubscriptionRow subscription = activeSubscription(0);
        jdbc.update("""
                INSERT INTO gateway.package_instance(
                    id, tenant_id, user_id, package_code, package_name, package_kind,
                    total_token_quota, used_tokens, reserved_tokens, status, usage_dimension, expires_at)
                VALUES (?, ?, ?, ?, 'Sub Test', 'STANDARD', 2000000, 0, 0, 'ACTIVE', 'TOKEN',
                    now() + interval '30 days')
                """, UUID.randomUUID(), tenantId, userId, planCode);

        assertThat(evaluate(subscription).skipReason())
                .isEqualTo(SubscriptionRenewalPolicyService.SkipReason.ACTIVE_PACKAGE_PRESENT);
    }

    @Test
    void renewalLimitProducesAFailureNotASkip() {
        // 累计上限到顶是「拒绝」而不是「跳过」：它要触发 TERMINATED 与运营告警。
        SubscriptionRenewalPolicyService.Decision decision = evaluate(activeSubscription(MAX_RENEWALS));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.skipReason()).isNull();
        assertThat(decision.failureReason()).isEqualTo("subscription_renewal_limit_reached");
    }

    @Test
    void aPriceIncreaseRequiresConfirmationInsteadOfSilentlyChargingMore() {
        // §7.3 合规要求：自动续费不得在用户获知之前按新价扣款。
        // 已发布版本不可修改（触发器 reject_published_version_mutation），
        // 所以「涨价」必须通过「发布新版本 + 旧版本置 RETIRED」来表达 ——
        // 这也正是本仓库的常态发布方式。
        SubscriptionRepository.SubscriptionRow subscription = activeSubscription(0);
        jdbc.update("UPDATE platform.subscription SET last_charged_amount = 2900 WHERE id = ?", subscription.id());

        UUID raisedVersionId = republishWithPrice(3900);

        SubscriptionRenewalPolicyService.Decision raised = policy.evaluate(
                reload(subscription), raisedVersionId, true, MAX_RENEWALS, Instant.now());
        assertThat(raised.allowed()).isFalse();
        assertThat(raised.failureReason()).isEqualTo("subscription_price_changed");
        assertThat(raised.previousAmount()).isEqualTo(2900L);
        assertThat(raised.newAmount()).isEqualTo(3900L);

        // 价格下调不构成确认需求。
        UUID cheaperVersionId = republishWithPrice(1900);

        SubscriptionRenewalPolicyService.Decision cheaper = policy.evaluate(
                reload(subscription), cheaperVersionId, true, MAX_RENEWALS, Instant.now());
        assertThat(cheaper.allowed()).isTrue();
        assertThat(cheaper.amount()).isEqualTo(1900L);
    }

    @Test
    void aNewPublishedVersionIsPickedUpSoRetiringTheOldOneDoesNotStallRenewals() {
        // §7.3：订阅绑计划不绑版本。本仓库的常态是「发布新版本 + 旧版本置 RETIRED」，
        // 若把开通时的版本 ID 记在订阅上，计划一换版本所有存量订阅就全都失效。
        UUID replacementId = republishWithPrice(2900);

        assertThat(plans.readPublishedVersionId(planId)).isEqualTo(replacementId);
        assertThat(plans.readPrice(versionId)).isNull();
        assertThat(plans.readPrice(replacementId)).isNotNull();

        assertThat(policy.evaluate(activeSubscription(0), replacementId, true, MAX_RENEWALS, Instant.now())
                .allowed()).isTrue();
    }

    @Test
    void aZeroPricedPlanIsNotRenewableThroughThePaidPath() {
        // 零价套餐应当走免费领取通道，续费路径必须跳过而不是建一张 0 元订单。
        UUID freeVersionId = republishWithPrice(0);

        assertThat(policy.evaluate(activeSubscription(0), freeVersionId, true, MAX_RENEWALS, Instant.now())
                .skipReason())
                .isEqualTo(SubscriptionRenewalPolicyService.SkipReason.PLAN_UNAVAILABLE);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private SubscriptionRenewalPolicyService.Decision evaluate(
            SubscriptionRepository.SubscriptionRow subscription) {
        // 按**计划当前已发布的版本**求值，而不是构造时那个 versionId：
        // 涨价用例会 retire 旧版本并发布新版本，若仍拿旧 ID 会读到 null 而变成 PLAN_UNAVAILABLE，
        // 那样断言就测不到价格比较逻辑（实测踩过这个假失败）。
        UUID published = plans.readPublishedVersionId(subscription.packagePlanId());
        return policy.evaluate(
                subscription, published == null ? versionId : published,
                true, MAX_RENEWALS, Instant.now());
    }

    private SubscriptionRepository.SubscriptionRow reload(
            SubscriptionRepository.SubscriptionRow subscription) {
        return subscriptions.findByIdForUpdate(
                subscription.tenantId(), subscription.userId(), subscription.id()).orElseThrow();
    }

    private SubscriptionRepository.SubscriptionRow activeSubscription(int renewalCount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.subscription(
                    id, tenant_id, user_id, package_plan_id, status, renewal_count,
                    current_period_start, current_period_end, next_charge_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, now(), now() + interval '30 days',
                    now() - interval '1 day')
                """, id, tenantId, userId, planId, renewalCount);
        return subscriptions.findByIdForUpdate(tenantId, userId, id).orElseThrow();
    }

    /**
     * 把当前 PUBLISHED 版本换成新的一行。
     *
     * <p>顺序不能颠倒：{@code uq_published_package_plan_version} 是
     * {@code UNIQUE (package_plan_id) WHERE status='PUBLISHED'}，一个计划同时只能有一个
     * 已发布版本，所以必须**先退役旧的、再发布新的**。而退役是既有的
     * {@code reject_published_version_mutation} 触发器**唯一**允许的已发布行改动。
     */
    private UUID republishWithPrice(long price) {
        UUID replacementId = UUID.randomUUID();
        UUID previous = plans.readPublishedVersionId(planId);
        retire(previous);
        insertVersion(replacementId, planId, price, "PUBLISHED");
        return replacementId;
    }

    /**
     * 退役一个已发布版本。
     *
     * <p>{@code retired_at} 必须一起写：{@code reject_published_version_mutation} 只在
     * 「{@code status='RETIRED'} 且 {@code retired_at IS NOT NULL} 且其余列逐字节不变」时
     * 才放行（V45:43-49）。只改 status 的 UPDATE 会被判为「改动已发布版本」而抛
     * {@code published_version_immutable}。
     */
    private void retire(UUID targetVersionId) {
        jdbc.update("""
                UPDATE platform.package_plan_version
                SET status = 'RETIRED', retired_at = now()
                WHERE id = ? AND status = 'PUBLISHED'
                """, targetVersionId);
    }

    /**
     * 已发布版本不可修改（触发器 {@code reject_published_version_mutation}），
     * 因此凡是需要「改价」的用例都必须通过「发布新版本 + 旧版本置 RETIRED」表达。
     */
    private void insertVersion(UUID id, UUID targetPlanId, long price, String status) {
        jdbc.update("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, status, price_amount, currency,
                    total_token_quota, duration_days, max_api_keys,
                    requests_per_minute, tokens_per_minute, max_concurrency, target_margin_ratio)
                VALUES (?, ?, ?, ?, ?, 'CNY', 2000000, 30, 2, 60, 10000, 2, 0.30)
                """, id, targetPlanId, nextVersionNumber++, status, price);
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
