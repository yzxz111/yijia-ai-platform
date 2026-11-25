package com.yuzhihuihe.apiplatform.platform.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 自动续费调度的**行为**验证。
 *
 * <p>这是本功能最复杂的一块（三个事务边界、退避、跳过/失败/暂缓三态区分、排队兜底），
 * 因此断言刻意打在**用户可见的事实**上：钱包余额变了多少、账本有几行、订阅处于什么状态、
 * 下次什么时候扣——而不是打在内部方法调用上。
 *
 * <p>时钟被固定（`FixedClockConfiguration`），因此「到期」「宽限期」「退避」都可以被精确构造，
 * 不依赖 sleep。时间推进靠**改数据库里的时间戳**而不是等时钟走。
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "outbox.publisher.initial-delay-ms=3600000",
        "subscription.jobs.enabled=false",
        "subscription.jobs.batch-size=50",
        "subscription.jobs.claim-timeout=5m",
        "subscription.jobs.initial-backoff=1h",
        "subscription.jobs.max-backoff=24h",
        "subscription.jobs.max-attempts=3",
        "subscription.jobs.grace-period=7d",
        "subscription.jobs.charge-lead-time=24h",
        "subscription.jobs.max-renewals=36",
        "subscription.jobs.stuck-queued-alert-after=45d"
})
@Import(SubscriptionRenewalJobTest.FixedClockConfiguration.class)
class SubscriptionRenewalJobTest {

    /**
     * 本测试内部一致的「现在」。
     *
     * <p><b>这里必须取一次真实时刻，不能写成绝对常量。</b>fixture 用数据库的 {@code now()}
     * 构造水位（见 {@code makeDue}），而领取判定用的是数据库时间；但策略判定用的是这个
     * Bean 注入的固定时钟。两者一旦漂开，订阅就会被正确地判为「当期还没结束」而跳过，
     * 于是本类会**毫无征兆地集体变红**——而这与代码质量无关。
     *
     * <p>实测：写成 {@code 2026-09-24T00:00:00Z} 时，到 2026-09-28 真实时钟已漂出 4 天，
     * 13 个用例里 12 个失败（期望扣款 1 次、实际 0 次），把源码改动全部还原后**仍然失败**。
     * 改成启动时取一次 {@code Instant.now()} 后，两种代码状态下都恢复全绿。
     * 类加载时取一次即可：本类的所有 fixture 都以它为基准，测试之间不需要各自的时间原点。
     */
    static final Instant FIXED_NOW = Instant.now();

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

    @Autowired
    SubscriptionRenewalJob job;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    SubscriptionRepository subscriptions;

    private UUID tenantId;
    private UUID userId;
    private UUID planId;
    private UUID versionId;
    private int nextVersionNumber;

    @BeforeEach
    void reset() {
        jdbc.update("TRUNCATE platform.subscription_cycle, platform.subscription, "
                + "platform.notification_event, platform.notification_channel_delivery, "
                + "platform.in_app_notification, platform.notification_preference, "
                + "platform.admin_audit_log, platform.outbox_event, platform.grant_request, "
                + "platform.balance_ledger, platform.customer_order, platform.package_plan "
                + "RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM platform.account_balance");
        jdbc.update("DELETE FROM platform.app_user");
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        planId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        nextVersionNumber = 2;
        prepareGatewayPackageInstances();
        jdbc.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'x', 'Subscriber', 'ACTIVE')
                """, userId, tenantId, userId + "@yuzhi.test");
        jdbc.update("""
                INSERT INTO platform.package_plan(id, code, name, status, kind)
                VALUES (?, ?, '连续包月测试套餐', 'ACTIVE', 'STANDARD')
                """, planId, "JOB_TEST_" + planId.toString().replace("-", "").substring(0, 10).toUpperCase());
        insertPublishedVersion(versionId, PLAN_PRICE);
        // 首期支付需要钱包里有钱；后面的每个用例再按需调整余额。
        setBalance(PLAN_PRICE);
    }

    // ------------------------------------------------------------------
    // 续费成功
    // ------------------------------------------------------------------

    @Test
    void aDueSubscriptionIsRenewedChargingTheWalletExactlyOnce() {
        // 开通前 5800，开通扣掉一期后剩 2900 —— 正好够续一期，续完归零。
        UUID subscriptionId = openSubscription(PLAN_PRICE * 2);
        makeDue(subscriptionId);

        int processed = job.runOnce();

        assertThat(processed).isOne();
        // 钱恰好被扣了一次：首期 2900 + 续期 2900。
        assertThat(balance()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.balance_ledger", Integer.class)).isEqualTo(2);

        var subscription = subscriptionRow(subscriptionId);
        assertThat(subscription.get("renewal_count")).isEqualTo(1);
        assertThat(subscription.get("consecutive_failures")).isEqualTo(0);
        assertThat(subscription.get("status")).isEqualTo("ACTIVE");
        // 租约已释放，下一轮不会再领到它。
        assertThat(subscription.get("claim_token")).isNull();
        // 两期账期都留在历史上，且都是 CHARGED。
        assertThat(jdbc.queryForList("""
                SELECT status FROM platform.subscription_cycle
                WHERE subscription_id = ? ORDER BY period_index
                """, String.class, subscriptionId)).containsExactly("CHARGED", "CHARGED");
    }

    @Test
    void theNextChargeMovesForwardByOneFullPeriodSoItIsNotClaimedAgainImmediately() {
        UUID subscriptionId = openSubscription(PLAN_PRICE * 4);
        makeDue(subscriptionId);
        job.runOnce();

        // 第二轮必须什么都不做：水位已按 durationDays 前移，而不是留在原处。
        assertThat(job.runOnce()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.balance_ledger", Integer.class)).isEqualTo(2);

        // 第三轮把水位再次推到过去，才会产生第三期。
        makeDue(subscriptionId);
        assertThat(job.runOnce()).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.balance_ledger", Integer.class)).isEqualTo(3);
        assertThat(balance()).isEqualTo(PLAN_PRICE);
    }

    @Test
    void runningTheJobRepeatedlyNeverDoubleChargesOnePeriod() {
        UUID subscriptionId = openSubscription(PLAN_PRICE * 2);
        makeDue(subscriptionId);

        job.runOnce();
        job.runOnce();
        job.runOnce();

        // 这是「同一账期至多扣款一次」的端到端证据：三道防线（订阅租约、
        // uq_subscription_cycle_period、账本幂等键）任一单独都不足以说明问题。
        assertThat(balance()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.subscription_cycle", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.customer_order", Integer.class)).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // 余额不足 → 宽限期
    // ------------------------------------------------------------------

    @Test
    void anEmptyWalletPutsTheSubscriptionPastDueWithoutChargingOrExpiringIt() {
        UUID subscriptionId = openSubscription(PLAN_PRICE);
        makeDue(subscriptionId);
        // 首期已把余额用光，续费必然失败。
        setBalance(0);

        int processed = job.runOnce();

        assertThat(processed).isOne();
        var subscription = subscriptionRow(subscriptionId);
        assertThat(subscription.get("status")).isEqualTo("PAST_DUE");
        assertThat(subscription.get("consecutive_failures")).isEqualTo(1);
        assertThat(subscription.get("grace_until")).isNotNull();
        assertThat(subscription.get("expired_at")).isNull();
        // 失败未产生扣款，但留下了原因与重试水位。
        assertThat(jdbc.queryForMap("""
                SELECT status, failure_reason, next_attempt_at
                FROM platform.subscription_cycle WHERE subscription_id = ?
                ORDER BY period_index DESC LIMIT 1
                """, subscriptionId))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "insufficient_balance");
    }

    @Test
    void aFailedChargeIsNotRetriedUntilItsBackoffElapses() {
        UUID subscriptionId = openSubscription(PLAN_PRICE);
        makeDue(subscriptionId);
        setBalance(0);
        job.runOnce();

        // 水位已被推后一个账期，因此即使立刻再跑也不会重试；
        // 这正是「宽限期内每个账期一次机会」而不是「一次宽限期烧掉全部重试」。
        assertThat(job.runOnce()).isZero();
        assertThat(subscriptionRow(subscriptionId).get("consecutive_failures")).isEqualTo(1);

        // 下一个账期到期后，第二次尝试真的发生。
        makeDue(subscriptionId);
        assertThat(job.runOnce()).isOne();
        assertThat(subscriptionRow(subscriptionId).get("consecutive_failures")).isEqualTo(2);
    }

    @Test
    void toppingUpTheWalletLetsTheFailedRenewalSucceedAndRestoresActive() {
        UUID subscriptionId = openSubscription(PLAN_PRICE);
        makeDue(subscriptionId);
        setBalance(0);
        job.runOnce();
        assertThat(subscriptionRow(subscriptionId).get("status")).isEqualTo("PAST_DUE");

        // 充值 + 下一个账期到期 → 重试成功。
        addBalance(PLAN_PRICE);
        makeDue(subscriptionId);
        assertThat(job.runOnce()).isOne();

        var subscription = subscriptionRow(subscriptionId);
        assertThat(subscription.get("status")).isEqualTo("ACTIVE");
        assertThat(subscription.get("consecutive_failures")).isEqualTo(0);
        assertThat(subscription.get("grace_until")).isNull();
        assertThat(subscription.get("renewal_count")).isEqualTo(1);
        assertThat(balance()).isZero();
    }

    @Test
    void exhaustingTheAttemptBudgetExpiresTheSubscriptionAndStopsCharging() {
        UUID subscriptionId = openSubscription(PLAN_PRICE);
        // 首期已把余额用光；三次失败后进入 EXPIRED（max-attempts=3）。
        setBalance(0);
        for (int attempt = 0; attempt < 3; attempt++) {
            makeDue(subscriptionId);
            job.runOnce();
        }

        var subscription = subscriptionRow(subscriptionId);
        assertThat(subscription.get("status")).isEqualTo("EXPIRED");
        assertThat(subscription.get("auto_renew")).isEqualTo(false);
        // 三次失败留下了三个 FAILED 账期，账期序号 2 之后（1 是成功扣款的首期）。
        // 注意：失败账期由记账事务写入，而记账事务不会被扣款事务的回滚牵连——
        // 这正是三个事务边界分开的目的。
        assertThat(jdbc.queryForList("""
                SELECT status FROM platform.subscription_cycle
                WHERE subscription_id = ? AND period_index > 1 ORDER BY period_index
                """, String.class, subscriptionId)).containsExactly("FAILED", "FAILED", "FAILED");
        // 到期后不再被领取：水位即使在过去也不会产生新的扣款尝试。
        makeDue(subscriptionId);
        assertThat(job.runOnce()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.balance_ledger", Integer.class)).isOne();
    }

    // ------------------------------------------------------------------
    // 跳过：不扣款、不计数、不发通知
    // ------------------------------------------------------------------

    @Test
    void aCancelledSubscriptionIsNeverCharged() {
        UUID subscriptionId = openSubscription(PLAN_PRICE * 2);
        service.cancel(tenantId, userId, subscriptionId);
        makeDue(subscriptionId);

        assertThat(job.runOnce()).isZero();

        assertThat(subscriptionRow(subscriptionId).get("renewal_count")).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.subscription_cycle", Integer.class)).isOne();
        assertThat(balance()).isEqualTo(PLAN_PRICE);
    }

    @Test
    void aUserWhoAlreadyBoughtTheSamePackageIsSkippedWithoutCountingAsFailure() {
        UUID subscriptionId = openSubscription(PLAN_PRICE * 2);
        makeDue(subscriptionId);
        // 用户手工买了一份同类套餐并已生效：续费应当跳过，因为他已经付过钱了。
        jdbc.update("""
                INSERT INTO gateway.package_instance(
                    id, tenant_id, user_id, package_code, package_name, package_kind,
                    total_token_quota, status, usage_dimension, expires_at)
                SELECT ?, ?, ?, code, name, 'STANDARD', 2000000, 'ACTIVE', 'TOKEN',
                       now() + interval '30 days'
                FROM platform.package_plan WHERE id = ?
                """, UUID.randomUUID(), tenantId, userId, planId);

        assertThat(job.runOnce()).isOne();

        var subscription = subscriptionRow(subscriptionId);
        assertThat(subscription.get("last_skip_reason")).isEqualTo("active_package_present");
        // 跳过不是失败：不计数、不扣款、不建账期行。
        assertThat(subscription.get("consecutive_failures")).isEqualTo(0);
        assertThat(subscription.get("renewal_count")).isEqualTo(0);
        assertThat(subscription.get("status")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.subscription_cycle", Integer.class)).isOne();
        assertThat(balance()).isEqualTo(PLAN_PRICE);
    }

    // ------------------------------------------------------------------
    // 涨价：暂缓而不是失败
    // ------------------------------------------------------------------

    @Test
    void aPriceIncreaseDefersTheChargeWithoutBurningAnAttemptOrChargingTheOldPrice() {
        UUID subscriptionId = openSubscription(PLAN_PRICE * 2);
        makeDue(subscriptionId);
        // 发布更贵的新版本（已发布版本不可改，只能「先退役旧的、再发布新的」）。
        UUID raisedVersionId = UUID.randomUUID();
        retire(versionId);
        insertPublishedVersion(raisedVersionId, PLAN_PRICE + 1_000);

        int processed = job.runOnce();
        var subscription = subscriptionRow(subscriptionId);
        assertThat(processed).isOne();

        // 暂缓：不扣款、不计数、状态仍是 ACTIVE、水位留在原处。
        assertThat(subscription.get("status")).isEqualTo("ACTIVE");
        assertThat(subscription.get("consecutive_failures")).isEqualTo(0);
        assertThat(subscription.get("renewal_count")).isEqualTo(0);
        assertThat(subscription.get("last_skip_reason")).isEqualTo("subscription_price_changed");
        assertThat(balance()).isEqualTo(PLAN_PRICE);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.subscription_cycle", Integer.class)).isOne();
    }

    @Test
    void aRetiredPlanWithNoReplacementIsSkippedRatherThanCharged() {
        UUID subscriptionId = openSubscription(PLAN_PRICE * 2);
        makeDue(subscriptionId);
        retire(versionId);

        assertThat(job.runOnce()).isOne();

        var subscription = subscriptionRow(subscriptionId);
        assertThat(subscription.get("last_skip_reason")).isEqualTo("plan_unavailable");
        assertThat(subscription.get("consecutive_failures")).isEqualTo(0);
        assertThat(balance()).isEqualTo(PLAN_PRICE);
    }

    // ------------------------------------------------------------------
    // 排队超时兜底（§5.7.1 实测新增）
    // ------------------------------------------------------------------

    @Test
    void aRenewalWhoseEntitlementNeverActivatesStopsChargingAfterTheStuckWindow() {
        UUID subscriptionId = openSubscription(PLAN_PRICE * 4);
        makeDue(subscriptionId);
        job.runOnce();
        assertThat(subscriptionRow(subscriptionId).get("renewal_count")).isEqualTo(1);

        // 构造「钱扣了、实例一直排队」：账期已扣款但在网关侧仍是 QUEUED，
        // 且扣款时间已超过 45 天。
        UUID grantId = jdbc.queryForObject("""
                SELECT grant_request_id FROM platform.subscription_cycle
                WHERE subscription_id = ? ORDER BY period_index DESC LIMIT 1
                """, UUID.class, subscriptionId);
        jdbc.update("""
                INSERT INTO gateway.package_instance(
                    id, grant_request_id, tenant_id, user_id, package_code, package_name,
                    package_kind, total_token_quota, status, usage_dimension)
                SELECT ?, ?, ?, ?, code, name, 'STANDARD', 2000000, 'QUEUED', 'TOKEN'
                FROM platform.package_plan WHERE id = ?
                """, UUID.randomUUID(), grantId, tenantId, userId, planId);
        jdbc.update("""
                UPDATE platform.subscription_cycle
                SET charged_at = now() - interval '46 days'
                WHERE grant_request_id = ?
                """, grantId);

        makeDue(subscriptionId);
        job.runOnce();

        // 这是本功能唯一会「静默吞钱」的路径：钱扣了、服务没生效。
        // 兜底必须停止后续扣款，而不只是记一条日志。
        var subscription = subscriptionRow(subscriptionId);
        assertThat(subscription.get("status")).isEqualTo("TERMINATED");
        assertThat(subscription.get("auto_renew")).isEqualTo(false);
        assertThat(subscription.get("last_skip_reason")).isEqualTo("renewal_instance_stuck_queued");
        // 终止后不再产生新的扣款。
        int ledgerRows = jdbc.queryForObject(
                "SELECT count(*) FROM platform.balance_ledger", Integer.class);
        makeDue(subscriptionId);
        job.runOnce();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.balance_ledger", Integer.class)).isEqualTo(ledgerRows);
    }

    // ------------------------------------------------------------------
    // 开关与韧性
    // ------------------------------------------------------------------

    @Test
    void oneFailingSubscriptionDoesNotStopTheRestOfTheBatch() {
        // 同一用户同一计划只能有一份活跃订阅（uq_subscription_active_plan），
        // 所以「一批」需要两个不同的计划。
        UUID otherPlanId = UUID.randomUUID();
        UUID otherVersionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.package_plan(id, code, name, status, kind)
                VALUES (?, ?, '批次测试套餐', 'ACTIVE', 'STANDARD')
                """, otherPlanId, "JOB_BATCH_" + otherPlanId.toString().replace("-", "").substring(0, 10).toUpperCase());
        jdbc.update("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, status, price_amount, currency,
                    total_token_quota, duration_days, max_api_keys,
                    requests_per_minute, tokens_per_minute, max_concurrency,
                    target_margin_ratio, published_at)
                VALUES (?, ?, 1, 'PUBLISHED', ?, 'CNY', 2000000, 30, 2, 60, 10000, 2, 0.30, now())
                """, otherVersionId, otherPlanId, PLAN_PRICE);

        // 两份订阅各扣一期。余额在第一次开通前设定，之后不再重设——
        // 开通本身要扣钱，在开通后再设余额会把那次扣款「还原」掉。
        setBalance(PLAN_PRICE * 3);
        UUID healthy = service.open(tenantId, userId, versionId, "batch-a").id();
        seedFirstPeriodInstance();
        UUID broken = service.open(tenantId, userId, otherVersionId, "batch-b").id();
        seedFirstPeriodInstance();
        makeDue(healthy);
        makeDue(broken);
        // 只剩一份套餐的钱，因此两个里恰好一个成功、一个失败。
        setBalance(PLAN_PRICE);

        int processed = job.runOnce();

        assertThat(processed).isEqualTo(2);
        // 失败的不能拖垮成功的：一个 ACTIVE + 一个 PAST_DUE，而不是两个都失败。
        assertThat(subscriptionRow(healthy).get("status")).isEqualTo("ACTIVE");
        assertThat(subscriptionRow(broken).get("status")).isEqualTo("PAST_DUE");
        assertThat(balance()).isZero();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /**
     * 开通订阅并留下指定余额，同时补上「首期已生效」的套餐实例。
     *
     * <p>测试容器里没有网关，因此 `PACKAGE_GRANTED_V1` 事件不会被投递，
     * 首期实例不会自动出现。而 {@code active_package_present} 守卫的判定依据正是
     * 这个实例，所以必须手工补上，否则每个续费用例都会被正确地跳过。
     */
    private UUID openSubscription(long balanceBeforeOpen) {
        return openFor(versionId, balanceBeforeOpen).id();
    }

    /**
     * 开通订阅，并**在开通前**把余额设成给定值。
     *
     * <p>余额必须在开通**之前**设定：开通首期本身要扣一次钱，若在开通之后再设余额，
     * 就会把首期那次扣款「还原」掉，于是用例断言的余额与真实扣款行为对不上。
     * 这个坑我在探针里撞过一次——续费明明成功了，余额却看着没动。
     */
    private SubscriptionService.SubscriptionView openFor(UUID packageVersionId, long balanceBeforeOpen) {
        setBalance(balanceBeforeOpen);
        SubscriptionService.SubscriptionView view = service.open(
                tenantId, userId, packageVersionId, "open-" + UUID.randomUUID());
        seedFirstPeriodInstance();
        return view;
    }

    private void seedFirstPeriodInstance() {
        jdbc.update("""
                INSERT INTO gateway.package_instance(
                    id, tenant_id, user_id, package_code, package_name, package_kind,
                    total_token_quota, status, usage_dimension, expires_at)
                SELECT ?, ?, ?, code, name, 'STANDARD', 2000000, 'ACTIVE', 'TOKEN',
                       now() + interval '30 days'
                FROM platform.package_plan WHERE id = ?
                """, UUID.randomUUID(), tenantId, userId, planId);
    }

    /**
     * 把余额设成指定值。
     *
     * <p>用 upsert 而不是 UPDATE：钱包账户在开通时由既有链路按需创建，
     * 测试不该假设它已经存在（`uq_account_balance_owner_currency` 决定了
     * 一个 tenant × user × 币种只有一行）。
     */
    private void setBalance(long amount) {
        jdbc.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, reserved_amount, version)
                VALUES (?, ?, ?, 'CNY', ?, 0, 0)
                ON CONFLICT (tenant_id, user_id, currency)
                DO UPDATE SET available_amount = EXCLUDED.available_amount
                """, UUID.randomUUID(), tenantId, userId, amount);
    }

    /**
     * 把订阅推到「该扣款了」：整个账期窗口在过去，水位也在过去，首期实例已终结。
     *
     * <p><b>必须让首期实例失效，否则续费会被 `active_package_present` 守卫正确拦下</b>
     * ——那正是「用户手上还有生效中的权益，不要再扣一次钱」这条守卫的用途。
     * 测试要验证的是「当期用完、该续了」这个时刻。
     *
     * <p>实例的范围必须限定到**本计划**：不同计划在同一测试里各有自己的实例，
     * 无条件过期会把别的那份也一起终结掉。
     */
    private void makeDue(UUID subscriptionId) {
        jdbc.update("""
                UPDATE platform.subscription
                SET current_period_start = now() - interval '31 days',
                    current_period_end = now() - interval '1 day',
                    next_charge_at = now() - interval '2 days'
                WHERE id = ?
                """, subscriptionId);
        jdbc.update("""
                UPDATE gateway.package_instance instance
                SET status = 'EXPIRED', expires_at = now() - interval '1 day'
                FROM platform.package_plan plan,
                     platform.subscription subscription
                WHERE subscription.id = ?
                  AND plan.id = subscription.package_plan_id
                  AND instance.tenant_id = subscription.tenant_id
                  AND instance.user_id = subscription.user_id
                  AND instance.package_code = plan.code
                  AND instance.status = 'ACTIVE'
                """, subscriptionId);
    }

    private void addBalance(long amount) {
        jdbc.update("""
                UPDATE platform.account_balance SET available_amount = available_amount + ?
                WHERE tenant_id = ? AND user_id = ?
                """, amount, tenantId, userId);
    }

    private long balance() {
        Long value = jdbc.queryForObject("""
                SELECT available_amount FROM platform.account_balance
                WHERE tenant_id = ? AND user_id = ?
                """, Long.class, tenantId, userId);
        return value == null ? 0L : value;
    }

    private java.util.Map<String, Object> subscriptionRow(UUID subscriptionId) {
        return jdbc.queryForMap("""
                SELECT status, auto_renew, cancel_at_period_end, renewal_count,
                       consecutive_failures, grace_until, expired_at, last_skip_reason,
                       claim_token, next_charge_at
                FROM platform.subscription WHERE id = ?
                """, subscriptionId);
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

    private void insertAccount(UUID tenant, UUID user, long amount) {
        jdbc.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, reserved_amount, version)
                VALUES (?, ?, ?, 'CNY', ?, 0, 0)
                """, UUID.randomUUID(), tenant, user, amount);
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

    /**
     * 退役一个已发布版本。
     *
     * <p>{@code retired_at} 必须一起写：{@code reject_published_version_mutation} 只在
     * 「{@code status='RETIRED'} 且 {@code retired_at IS NOT NULL} 且其余列逐字节不变」时放行。
     */
    private void retire(UUID targetVersionId) {
        jdbc.update("""
                UPDATE platform.package_plan_version
                SET status = 'RETIRED', retired_at = now()
                WHERE id = ? AND status = 'PUBLISHED'
                """, targetVersionId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedSubscriptionClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
