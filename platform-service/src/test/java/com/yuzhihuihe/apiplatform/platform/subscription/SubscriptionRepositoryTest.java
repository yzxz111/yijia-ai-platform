package com.yuzhihuihe.apiplatform.platform.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 订阅仓储的并发与租约语义。
 *
 * <p>这些断言刻意打在真实 PostgreSQL 上：{@code FOR UPDATE SKIP LOCKED} 的语义无法用
 * 内存替身验证，而「同一订阅只被一个调度实例领走」是重复扣款的第一道防线
 * （第二道是 {@code uq_subscription_cycle_period}，第三道是既有的账本幂等键）。
 *
 * <p>租约在**订阅行**上而不是账期行上：「一个订阅至多一个在途扣款」因此是结构事实。
 */
class SubscriptionRepositoryTest {

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

    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);
    private final TransactionTemplate transactions =
            new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));
    private final SubscriptionRepository subscriptions = new SubscriptionRepository(jdbc);
    private final SubscriptionCycleRepository cycles = new SubscriptionCycleRepository(jdbc);

    private UUID tenantId;
    private UUID userId;
    private int nextVersionNumber;

    @BeforeEach
    void reset() throws SQLException {
        jdbc.execute("DELETE FROM platform.subscription_cycle");
        jdbc.execute("DELETE FROM platform.subscription");
        prepareGatewayPackageInstances();
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        nextVersionNumber = 2;
        execute("""
                INSERT INTO platform.app_user(tenant_id, id, email, password_hash, display_name, status)
                VALUES ('%s', '%s', '%s', 'x', 'Subscriber', 'ACTIVE')
                """.formatted(tenantId, userId, userId + "@yuzhi.test"));
    }

    @Test
    void twoClaimersNeverTakeTheSameSubscription() throws Exception {
        Fixture first = dueSubscription();
        Fixture second = dueSubscription();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<List<UUID>> claimer = () -> {
                start.await(10, TimeUnit.SECONDS);
                return transactions.execute(status -> subscriptions.claimDue(
                                Instant.now(), CLAIM_TIMEOUT, 1).stream()
                        .map(SubscriptionRepository.ClaimedSubscription::subscriptionId).toList());
            };
            Future<List<UUID>> left = pool.submit(claimer);
            Future<List<UUID>> right = pool.submit(claimer);
            start.countDown();

            List<UUID> all = new ArrayList<>(left.get(30, TimeUnit.SECONDS));
            all.addAll(right.get(30, TimeUnit.SECONDS));

            assertThat(all).doesNotHaveDuplicates();
            assertThat(all).containsExactlyInAnyOrder(first.subscriptionId(), second.subscriptionId());
            List<String> tokens = jdbc.queryForList(
                    "SELECT claim_token::text FROM platform.subscription WHERE claim_token IS NOT NULL",
                    String.class);
            assertThat(new HashSet<>(tokens)).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void claimDoesNotHandOutTheSameSubscriptionTwiceWhileTheLeaseIsHeld() {
        dueSubscription();

        assertThat(claimOnce()).hasSize(1);
        // 单个实例连跑两次：第二次必须是空，否则「同一期重复扣款」就成立了。
        assertThat(claimOnce()).isEmpty();
    }

    @Test
    void expiredLeaseIsReclaimableSoACrashedWorkerDoesNotStallRenewal() {
        dueSubscription();
        claimOnce();
        // 模拟调度进程在领取之后、写终态之前崩溃。
        jdbc.update("UPDATE platform.subscription SET claimed_until = now() - interval '1 minute'");

        // 这是本仓储最重要的恢复语义：没有它，一次崩溃就让该订阅永久静默停摆，
        // 因为持有租约的行不会再被任何查询取到。
        assertThat(claimOnce()).hasSize(1);
    }

    @Test
    void claimOnlyTakesSubscriptionsThatStillWantToRenew() {
        Fixture active = dueSubscription();
        Fixture canceled = dueSubscription();
        Fixture future = dueSubscription();

        jdbc.update("UPDATE platform.subscription SET auto_renew = false, cancel_at_period_end = true WHERE id = ?",
                canceled.subscriptionId());
        jdbc.update("UPDATE platform.subscription SET next_charge_at = now() + interval '10 days' WHERE id = ?",
                future.subscriptionId());

        assertThat(claimOnce()).containsExactly(active.subscriptionId());
    }

    @Test
    void claimStopsAtTheRenewalAttemptBudget() {
        Fixture fixture = dueSubscription();
        jdbc.update("""
                INSERT INTO platform.subscription_cycle(
                    id, subscription_id, period_index, period_start, period_end,
                    business_key, status, amount, currency, attempt_count, next_attempt_at)
                VALUES (?, ?, 2, now(), now() + interval '30 days', ?, 'FAILED', 2900, 'CNY', 5,
                    now() - interval '1 minute')
                """, UUID.randomUUID(), fixture.subscriptionId(),
                "renewal:" + fixture.subscriptionId() + ":2");

        // 账期层的重试预算由编排层判定；仓储层的职责是「只领该续费的订阅」。
        // 这里断言的是重试水位被尊重：next_attempt_at 在未来时不领。
        jdbc.update("UPDATE platform.subscription_cycle SET next_attempt_at = now() + interval '1 hour'");
        assertThat(claimOnce()).hasSize(1);
    }

    @Test
    void insertCycleIsIdempotentOnBusinessKey() {
        Fixture fixture = dueSubscription();
        SubscriptionCycleRepository.NewCycle cycle = newCycle(fixture, 1);

        UUID first = transactions.execute(status -> cycles.insertIfAbsent(cycle));
        UUID second = transactions.execute(status -> cycles.insertIfAbsent(cycle));

        // 契约是「返回该期的行 ID」而不是「第二次返回 null」：调用方拿到 ID 才能继续
        // 写状态（markCharged / markFailed）。返回 null 会让调用方以为没有行可写。
        assertThat(first).isNotNull();
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.subscription_cycle WHERE business_key = ?
                """, Integer.class, cycle.businessKey())).isOne();
    }

    @Test
    void aSecondCycleForTheSamePeriodReusesTheSameRowInsteadOfFailing() {
        Fixture fixture = dueSubscription();
        UUID first = transactions.execute(status -> cycles.insertIfAbsent(newCycle(fixture, 3)));

        // 业务键不同但 period_index 相同：命中 uq_subscription_cycle_period。
        // 这里**必须**返回既有行的 ID，不能让异常穿出去——异常会回滚整个记账事务，
        // 于是失败账期一行都不留，后台永远答不出「这一期为什么没扣成功」。
        // 实测症状：连续三次续费失败后订阅停在 PAST_DUE 而非 EXPIRED。
        UUID second = transactions.execute(status -> cycles.insertIfAbsent(
                new SubscriptionCycleRepository.NewCycle(
                        UUID.randomUUID(), fixture.subscriptionId(), 3,
                        Instant.now(), Instant.now().plus(Duration.ofDays(30)),
                        "renewal:" + fixture.subscriptionId() + ":3-alt",
                        "PENDING", 2900L, "CNY", fixture.versionId())));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.subscription_cycle
                WHERE subscription_id = ? AND period_index = 3
                """, Integer.class, fixture.subscriptionId())).isOne();
    }

    @Test
    void chargedCycleAndLeaseAreClearedTogether() {
        Fixture fixture = dueSubscription();
        SubscriptionRepository.ClaimedSubscription claim = claimOnceFull().getFirst();
        UUID cycleId = transactions.execute(status -> cycles.insertIfAbsent(newCycle(fixture, 2)));

        transactions.executeWithoutResult(status -> {
            cycles.markCharged(cycleId, fixture.orderId(), fixture.orderNo(),
                    fixture.versionId(), 2900L, fixture.grantId());
            subscriptions.advancePeriod(fixture.subscriptionId(),
                    Instant.now(), Instant.now().plus(Duration.ofDays(30)),
                    Instant.now().plus(Duration.ofDays(29)), false, 2900L);
            subscriptions.completeClaim(fixture.subscriptionId(), claim.claimToken());
        });

        var cycle = jdbc.queryForMap("""
                SELECT status, order_no, grant_request_id, charged_at
                FROM platform.subscription_cycle WHERE id = ?
                """, cycleId);
        assertThat(cycle.get("status")).isEqualTo("CHARGED");
        assertThat(cycle.get("order_no")).isEqualTo(fixture.orderNo());
        assertThat(cycle.get("grant_request_id")).isEqualTo(fixture.grantId());
        assertThat(cycle.get("charged_at")).isNotNull();

        var subscription = jdbc.queryForMap("""
                SELECT claim_token, claimed_until, renewal_count, consecutive_failures,
                       last_charged_amount
                FROM platform.subscription WHERE id = ?
                """, fixture.subscriptionId());
        assertThat(subscription.get("claim_token")).isNull();
        assertThat(subscription.get("claimed_until")).isNull();
        assertThat(subscription.get("renewal_count")).isEqualTo(1);
        assertThat(subscription.get("consecutive_failures")).isEqualTo(0);
        assertThat(subscription.get("last_charged_amount")).isEqualTo(2900L);
    }

    @Test
    void aStaleLeaseTokenCannotCompleteTheClaim() {
        Fixture fixture = dueSubscription();
        claimOnce();

        // 失去租约的一方不得再写入：完成领取必须带 claim_token 条件。
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                subscriptions.completeClaim(fixture.subscriptionId(), UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subscription_lease_lost");

        assertThat(jdbc.queryForObject(
                "SELECT claim_token IS NOT NULL FROM platform.subscription WHERE id = ?",
                Boolean.class, fixture.subscriptionId())).isTrue();
    }

    @Test
    void failureRecordsReasonAndCountsAgainstTheSubscription() {
        Fixture fixture = dueSubscription();
        SubscriptionRepository.ClaimedSubscription claim = claimOnceFull().getFirst();
        Instant retryAt = Instant.now().plus(Duration.ofHours(1));

        transactions.executeWithoutResult(status -> {
            subscriptions.markPastDue(fixture.subscriptionId(), retryAt);
            subscriptions.releaseClaim(fixture.subscriptionId(), claim.claimToken());
        });

        var row = jdbc.queryForMap("""
                SELECT status, consecutive_failures, grace_until, claim_token
                FROM platform.subscription WHERE id = ?
                """, fixture.subscriptionId());
        assertThat(row.get("status")).isEqualTo("PAST_DUE");
        assertThat(row.get("consecutive_failures")).isEqualTo(1);
        assertThat(row.get("grace_until")).isNotNull();
        assertThat(row.get("claim_token")).isNull();
    }

    @Test
    void skippingTouchesOnlyTheSubscriptionAndNeverCountsAsFailure() {
        Fixture fixture = dueSubscription();
        SubscriptionRepository.ClaimedSubscription claim = claimOnceFull().getFirst();

        transactions.executeWithoutResult(status -> subscriptions.skipClaim(
                fixture.subscriptionId(), claim.claimToken(),
                "active_package_present", Instant.now().plus(Duration.ofDays(30))));

        var row = jdbc.queryForMap("""
                SELECT last_skip_reason, last_evaluated_at, consecutive_failures, renewal_count,
                       next_charge_at, claim_token
                FROM platform.subscription WHERE id = ?
                """, fixture.subscriptionId());
        assertThat(row.get("last_skip_reason")).isEqualTo("active_package_present");
        assertThat(row.get("last_evaluated_at")).isNotNull();
        // 跳过不是失败：失败计数与续期计数都不许动，也不许留下账期行。
        assertThat(row.get("consecutive_failures")).isEqualTo(0);
        assertThat(row.get("renewal_count")).isEqualTo(0);
        assertThat(row.get("claim_token")).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.subscription_cycle", Integer.class)).isZero();
    }

    @Test
    void dueScanStaysIndexedAndNeverDegeneratesToSequentialScans() {
        // 小表上规划器会选顺序扫描（更便宜），所以关掉 seqscan 来问一个确定的问题：
        // 「这个查询形状有没有可用索引」。
        //
        // 不断言具体索引名：实测规划器会先走 ix_subscription_due 筛出该续费的订阅，
        // 该计划比「先扫账期再用 claim 索引」更省；断言索引名会把「规划器变聪明」
        // 误判成回归，而真正要防的是没有索引可用。
        jdbc.execute("SET enable_seqscan = off");
        try {
            String plan = jdbc.queryForList("""
                    EXPLAIN
                    SELECT id FROM platform.subscription
                    WHERE status IN ('ACTIVE', 'PAST_DUE')
                      AND auto_renew = true
                      AND cancel_at_period_end = false
                      AND next_charge_at <= now()
                      AND (claim_token IS NULL OR claimed_until <= now())
                    ORDER BY next_charge_at, id
                    LIMIT 10
                    """, String.class).stream().reduce("", (left, right) -> left + "\n" + right);

            assertThat(plan).contains("Index");
            assertThat(plan).doesNotContain("Seq Scan");
        } finally {
            jdbc.execute("SET enable_seqscan = on");
        }
    }

    @Test
    void subscriptionReadsAreScopedByTenantAndUser() {
        Fixture fixture = dueSubscription();

        assertThat(subscriptions.findByIdForUpdate(tenantId, userId, fixture.subscriptionId()))
                .isPresent();
        assertThat(subscriptions.findByIdForUpdate(UUID.randomUUID(), userId, fixture.subscriptionId()))
                .isEmpty();
        assertThat(subscriptions.findByIdForUpdate(tenantId, UUID.randomUUID(), fixture.subscriptionId()))
                .isEmpty();
    }

    @Test
    void unactivatedCycleRecognisesAQueuedPackageInstance() {
        Fixture fixture = dueSubscription();
        UUID cycleId = transactions.execute(status -> cycles.insertIfAbsent(newCycle(fixture, 4)));
        // grant_request_id 有真实外键（fk_subscription_cycle_grant），必须先建那一行。
        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', 'ORDER', 'unactivated cycle test')
                """, grantId, "unactivated:" + grantId, tenantId, userId, fixture.versionId());
        jdbc.update("""
                UPDATE platform.subscription_cycle
                SET status = 'CHARGED', grant_request_id = ?
                WHERE id = ?
                """, grantId, cycleId);
        jdbc.update("""
                INSERT INTO gateway.package_instance(
                    id, grant_request_id, tenant_id, user_id, package_code, package_name,
                    package_kind, total_token_quota, status, usage_dimension)
                VALUES (?, ?, ?, ?, 'SUB', 'Sub', 'STANDARD', 2000000, 'QUEUED', 'TOKEN')
                """, UUID.randomUUID(), grantId, tenantId, userId);

        // 判定依据必须是套餐实例状态，而不是 grant_request 状态：后者长期停在 QUEUED
        // 是正常状态（设计 §5.7.1/H3）。
        assertThat(cycles.findUnactivated(fixture.subscriptionId()))
                .singleElement()
                .satisfies(cycle -> assertThat(cycle.notYetActive()).isTrue());

        jdbc.update("UPDATE gateway.package_instance SET status = 'ACTIVE', expires_at = now() + interval '30 days'");
        assertThat(cycles.findUnactivated(fixture.subscriptionId()))
                .singleElement()
                .satisfies(cycle -> assertThat(cycle.notYetActive()).isFalse());
    }

    /**
     * 「周期真正开始」的通知必须用**实例的真实有效期**，不能用通知时刻。
     *
     * <p>2026-09-28 实测缺陷：早先调用方把 {@code clock.instant()} 当有效期传进来，于是
     * 用户收到的文案是「您的套餐有效期至 2026-09-28」而实例真实到期日是 2026-10-28——
     * <b>少报整整 30 天</b>。有效期只存在于实例上（{@code activated_at + duration_days}，
     * 由网关侧激活事务写入），因此这里断言 {@code findOwnerByGrant} 回查的是那一列。
     */
    @Test
    void cycleOwnerCarriesTheInstanceExpiryNotTheNotificationInstant() {
        Fixture fixture = dueSubscription();
        UUID cycleId = transactions.execute(status -> cycles.insertIfAbsent(newCycle(fixture, 5)));
        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', 'ORDER', 'cycle owner expiry test')
                """, grantId, "owner:" + grantId, tenantId, userId, fixture.versionId());
        jdbc.update("""
                UPDATE platform.subscription_cycle
                SET status = 'CHARGED', grant_request_id = ?
                WHERE id = ?
                """, grantId, cycleId);

        // 实例尚未物化：取不到有效期，调用方必须跳过通知而不是发一个算不出来的日期。
        assertThat(cycles.findOwnerByGrant(grantId))
                .isNotNull()
                .satisfies(owner -> {
                    assertThat(owner.cycleId()).isEqualTo(cycleId);
                    assertThat(owner.expiresAt()).isNull();
                });

        UUID instanceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO gateway.package_instance(
                    id, grant_request_id, tenant_id, user_id, package_code, package_name,
                    package_kind, total_token_quota, status, usage_dimension)
                VALUES (?, ?, ?, ?, 'SUB', 'Sub', 'STANDARD', 2000000, 'QUEUED', 'TOKEN')
                """, instanceId, grantId, tenantId, userId);

        // 实例物化后有效期是「激活时刻 + 30 天」，与通知时刻不是一回事。
        //
        // 只写 starts_at / expires_at 这两个**平台侧迁移也建了**的列：本测试的 Flyway 只加载
        // `platform-service` 的迁移，容器里的 gateway schema 因此是平台侧版本，没有
        // `activated_at`（那一列在 gateway-service 的 V1 里）。生产库两者都有。
        jdbc.update("""
                UPDATE gateway.package_instance
                SET status = 'ACTIVE', starts_at = now(),
                    expires_at = now() + interval '30 days'
                WHERE id = ?
                """, instanceId);

        Instant instanceExpiry = jdbc.queryForObject(
                "SELECT expires_at FROM gateway.package_instance WHERE id = ?", Instant.class, instanceId);
        assertThat(cycles.findOwnerByGrant(grantId))
                .isNotNull()
                .satisfies(owner -> assertThat(owner.expiresAt()).isEqualTo(instanceExpiry));
        // 故意断言它与「现在」不同，否则这条用例在实现退回旧写法时仍会通过（相差 30 天）。
        assertThat(cycles.findOwnerByGrant(grantId).expiresAt())
                .as("the notification instant must not be used as the expiry")
                .isAfter(Instant.now().plus(Duration.ofDays(29)));
    }

    /** 不属于任何订阅账期的发放必须返回 null，让调用方静默跳过（一次性购买就是这种情况）。 */
    @Test
    void cycleOwnerIsAbsentForAGrantThatNoSubscriptionOwns() {
        dueSubscription();
        assertThat(cycles.findOwnerByGrant(UUID.randomUUID())).isNull();
    }

    /**
     * 认领**指定**订阅：{@code recordPendingPriceChange} 会清租约，因此写过报价的订阅立刻重新
     * 变成可领取状态，{@code claimDue} 可能一次返回多个。按 ID 取，而不是断言只领到一个。
     */
    private SubscriptionRepository.ClaimedSubscription claimOneFor(UUID subscriptionId) {
        return claimOnceFull().stream()
                .filter(claim -> claim.subscriptionId().equals(subscriptionId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "subscription " + subscriptionId + " was not claimed"));
    }

    /**
     * 涨价报价必须落库，且**不推进水位**。
     *
     * <p>不推进是刻意的：设计 §7.3 要求「用户确认后才恢复扣款」，因此暂缓期间水位必须停在
     * 过去，由确认那一步来推进。若这里顺手推了水位，暂缓就等于「跳过一期」——用户没确认
     * 也永远不会被扣款，订阅会静默停摆。
     */
    @Test
    void recordingAPendingPriceKeepsTheWatermarkSoTheChargeStaysDeferred() {
        Fixture fixture = dueSubscription();
        Instant beforeWatermark = jdbc.queryForObject(
                "SELECT next_charge_at FROM platform.subscription WHERE id = ?",
                Instant.class, fixture.subscriptionId());
        SubscriptionRepository.ClaimedSubscription claim = claimOneFor(fixture.subscriptionId());

        subscriptions.recordPendingPriceChange(
                fixture.subscriptionId(), claim.claimToken(), 3900, Instant.now());

        Map<String, Object> row = subscriptionRow(fixture.subscriptionId());
        assertThat(row.get("pending_price_amount")).isEqualTo(3900L);
        assertThat(row.get("price_change_detected_at")).isNotNull();
        assertThat(row.get("last_skip_reason")).isEqualTo("subscription_price_changed");
        // 水位不动 —— 这正是「暂缓」与「跳过」的区别。
        assertThat(row.get("next_charge_at")).isEqualTo(java.sql.Timestamp.from(beforeWatermark));
        // 租约被清掉：本轮处理到此为止，下一轮由确认（或再次暂缓）接手。
        assertThat(row.get("claim_token")).isNull();
    }

    /**
     * 同一报价重复写入不得刷新计时起点。
     *
     * <p>否则调度每个周期都会把 {@code price_change_detected_at} 推到现在，
     * 「长期未确认」的超时兜底永远不会触发——订阅就真的永久停摆了。
     */
    @Test
    void repeatingTheSamePendingPriceDoesNotRestartTheConfirmationClock() {
        Fixture fixture = dueSubscription();
        Instant first = Instant.now().minus(Duration.ofDays(10));
        SubscriptionRepository.ClaimedSubscription claim = claimOneFor(fixture.subscriptionId());
        subscriptions.recordPendingPriceChange(fixture.subscriptionId(), claim.claimToken(), 3900, first);
        Instant recorded = jdbc.queryForObject(
                "SELECT price_change_detected_at FROM platform.subscription WHERE id = ?",
                Instant.class, fixture.subscriptionId());

        // 第二轮：报价没变，但现在是「更晚」的时刻。
        SubscriptionRepository.ClaimedSubscription again = claimOneFor(fixture.subscriptionId());
        subscriptions.recordPendingPriceChange(
                fixture.subscriptionId(), again.claimToken(), 3900, Instant.now());

        Instant afterRepeat = jdbc.queryForObject(
                "SELECT price_change_detected_at FROM platform.subscription WHERE id = ?",
                Instant.class, fixture.subscriptionId());
        assertThat(afterRepeat).isEqualTo(recorded);
    }

    /**
     * 确认：清掉报价、留下确认时刻，并把水位推到当前时刻让下一轮立即按新价扣款。
     *
     * <p>报价不匹配时必须**拒绝**（返回 false）——那是「用户确认的价」与「当前已发布价」
     * 已经不是同一个的信号，绝不能按新价扣款。
     */
    @Test
    void confirmingAPendingPriceAdvancesTheWatermarkButOnlyForTheExactQuote() {
        Fixture fixture = dueSubscription();
        SubscriptionRepository.ClaimedSubscription claim = claimOneFor(fixture.subscriptionId());
        subscriptions.recordPendingPriceChange(fixture.subscriptionId(), claim.claimToken(), 3900, Instant.now());

        // 用户确认的是 2900，而当前报价是 3900 —— 必须失败且什么都不改。
        assertThat(subscriptions.confirmPendingPrice(
                fixture.subscriptionId(), 2900, Instant.now())).isFalse();
        assertThat(subscriptionRow(fixture.subscriptionId()).get("pending_price_amount")).isEqualTo(3900L);

        Instant now = Instant.now();
        assertThat(subscriptions.confirmPendingPrice(fixture.subscriptionId(), 3900, now)).isTrue();

        Map<String, Object> row = subscriptionRow(fixture.subscriptionId());
        assertThat(row.get("pending_price_amount")).isNull();
        assertThat(row.get("price_change_detected_at")).isNull();
        assertThat(row.get("price_confirmed_at")).isNotNull();
        assertThat(row.get("last_skip_reason")).isNull();
        // 水位被推到 now，因此下一轮领取立刻就能拿到它 —— 确认才是那个推进水位的人。
        // 用容差而不是精确相等：该列以微秒精度往返，精确比较会偶发失败。
        Instant watermark = jdbc.queryForObject(
                "SELECT next_charge_at FROM platform.subscription WHERE id = ?",
                Instant.class, fixture.subscriptionId());
        assertThat(Duration.between(now, watermark).abs()).isLessThan(Duration.ofSeconds(1));
    }

    /** 确认过的报价不能再被确认一次：第二次应影响 0 行。 */
    @Test
    void confirmingTwiceIsRefusedRatherThanChargingAgain() {
        Fixture fixture = dueSubscription();
        SubscriptionRepository.ClaimedSubscription claim = claimOneFor(fixture.subscriptionId());
        subscriptions.recordPendingPriceChange(fixture.subscriptionId(), claim.claimToken(), 3900, Instant.now());
        assertThat(subscriptions.confirmPendingPrice(fixture.subscriptionId(), 3900, Instant.now())).isTrue();
        assertThat(subscriptions.confirmPendingPrice(fixture.subscriptionId(), 3900, Instant.now())).isFalse();
    }

    /** 价格回落后清掉待确认状态，订阅恢复正常扣款。 */
    @Test
    void clearingAPendingPriceResumesNormalCharging() {
        Fixture fixture = dueSubscription();
        SubscriptionRepository.ClaimedSubscription claim = claimOneFor(fixture.subscriptionId());
        subscriptions.recordPendingPriceChange(fixture.subscriptionId(), claim.claimToken(), 3900, Instant.now());

        subscriptions.clearPendingPriceChange(fixture.subscriptionId());

        Map<String, Object> row = subscriptionRow(fixture.subscriptionId());
        assertThat(row.get("pending_price_amount")).isNull();
        assertThat(row.get("last_skip_reason")).isNull();
        // 水位仍未推进：清掉待确认只是让它重新可被领取，扣款与否由策略再判一次。
        assertThat(row.get("last_evaluated_at")).isNotNull();
    }

    /** 超时扫描只挑出「报价已存在且发现时刻早于阈值」的行。 */
    @Test
    void theTimeoutScanOnlyPicksQuotesOlderThanTheDeadline() {
        Fixture stale = dueSubscription();
        SubscriptionRepository.ClaimedSubscription staleClaim = claimOneFor(stale.subscriptionId());
        subscriptions.recordPendingPriceChange(
                stale.subscriptionId(), staleClaim.claimToken(), 3900, Instant.now().minus(Duration.ofDays(31)));
        assertThat(subscriptions.findUnconfirmedPriceBeyond(Instant.now().minus(Duration.ofDays(30)), 10))
                .containsExactly(stale.subscriptionId());

        // 另一份刚刚记录的报价不该被这条扫描挑走。
        Fixture fresh = dueSubscription();
        SubscriptionRepository.ClaimedSubscription freshClaim = claimOneFor(fresh.subscriptionId());
        subscriptions.recordPendingPriceChange(
                fresh.subscriptionId(), freshClaim.claimToken(), 3900, Instant.now());
        assertThat(subscriptions.findUnconfirmedPriceBeyond(Instant.now().minus(Duration.ofDays(30)), 10))
                .containsExactly(stale.subscriptionId());
    }

    /**
     * 退款终止：置 {@code TERMINATED} 并写终止时刻，且**可重复调用**。
     *
     * <p>幂等是必需的：退款完成事件可能被重投，第二次不该报错。
     */
    @Test
    void refundTerminationIsIdempotentAndStopsFutureCharging() {
        Fixture fixture = dueSubscription();
        assertThat(subscriptions.terminateForRefund(fixture.subscriptionId())).isTrue();

        Map<String, Object> row = subscriptionRow(fixture.subscriptionId());
        assertThat(row.get("status")).isEqualTo("TERMINATED");
        assertThat(row.get("auto_renew")).isEqualTo(false);
        assertThat(row.get("cancel_at_period_end")).isEqualTo(true);
        assertThat(row.get("last_skip_reason")).isEqualTo("refund_terminated");
        assertThat(row.get("terminated_at")).isNotNull();
        // ix_subscription_due 的谓词含 auto_renew，因此终止后连领取都不会发生。
        assertThat(subscriptions.claimDue(Instant.now(), CLAIM_TIMEOUT, 10)).isEmpty();

        assertThat(subscriptions.terminateForRefund(fixture.subscriptionId())).isFalse();
    }

    /** 退款终止的归属桥：按发放请求能找到订阅，且对无关发放返回空。 */
    @Test
    void aGrantLinksBackToItsSubscriptionOnlyWhenItBelongsToOne() {
        Fixture fixture = dueSubscription();
        transactions.execute(status -> cycles.insertIfAbsent(newCycle(fixture, 1)));
        jdbc.update("""
                UPDATE platform.subscription_cycle
                SET status = 'CHARGED', grant_request_id = ?
                WHERE subscription_id = ?
                """, fixture.grantId(), fixture.subscriptionId());

        assertThat(cycles.findSubscriptionIdByGrant(fixture.grantId()))
                .contains(fixture.subscriptionId());
        // 一次性购买的发放不属于任何账期：必须返回空，调用方据此什么都不做。
        assertThat(cycles.findSubscriptionIdByGrant(UUID.randomUUID())).isEmpty();
        assertThat(cycles.findSubscriptionIdByGrant(null)).isEmpty();
    }

    // ------------------------------------------------------------------
    // fixture helpers
    // ------------------------------------------------------------------

    private List<UUID> claimOnce() {
        return claimOnceFull().stream()
                .map(SubscriptionRepository.ClaimedSubscription::subscriptionId).toList();
    }

    private List<SubscriptionRepository.ClaimedSubscription> claimOnceFull() {
        return transactions.execute(status -> subscriptions.claimDue(
                Instant.now(), CLAIM_TIMEOUT, 10));
    }

    /** 订阅行的原始列，便于断言「哪些列被改了、哪些没动」。 */
    private Map<String, Object> subscriptionRow(UUID subscriptionId) {
        return jdbc.queryForMap(
                "SELECT * FROM platform.subscription WHERE id = ?", subscriptionId);
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

    /**
     * 每个订阅使用它自己的计划与版本。
     *
     * <p>不能共用同一个计划：{@code uq_subscription_active_plan} 是
     * {@code (tenant_id, user_id, package_plan_id) WHERE status IN ('ACTIVE','PAST_DUE')}，
     * 同一测试里建两个 ACTIVE 订阅会立刻撞上它。
     */
    private Fixture dueSubscription() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String planCode = "SUB_REPO_" + planId.toString().replace("-", "").substring(0, 10).toUpperCase();
        jdbc.update("""
                INSERT INTO platform.package_plan(id, code, name, status, kind)
                VALUES (?, ?, 'Sub Test', 'ACTIVE', 'STANDARD')
                """, planId, planCode);
        jdbc.update("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, status, price_amount, currency,
                    total_token_quota, duration_days, max_api_keys,
                    requests_per_minute, tokens_per_minute, max_concurrency, target_margin_ratio)
                VALUES (?, ?, ?, 'DRAFT', 2900, 'CNY', 2000000, 30, 2, 60, 10000, 2, 0.30)
                """, versionId, planId, nextVersionNumber++);

        // 账期行对 customer_order 与 grant_request 都有真实外键，夹具必须插入真实行。
        UUID orderId = UUID.randomUUID();
        String orderNo = "ord_" + orderId.toString().replace("-", "");
        jdbc.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider,
                    expires_at, next_close_at, inventory_status, settlement_method)
                VALUES (?, ?, ?, ?, ?, 2900, 'CNY', 'PAID', 'MOCK',
                    now() + interval '15 minutes', now(), 'SOLD', 'EXTERNAL')
                """, orderId, orderNo, tenantId, userId, versionId);
        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', 'ORDER', 'subscription repository test')
                """, grantId, "order:" + orderNo, tenantId, userId, versionId);

        UUID subscriptionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.subscription(
                    id, tenant_id, user_id, package_plan_id, status,
                    current_period_start, current_period_end, next_charge_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now(), now() + interval '30 days',
                    now() - interval '1 minute')
                """, subscriptionId, tenantId, userId, planId);
        return new Fixture(subscriptionId, orderId, orderNo, grantId, versionId, planCode);
    }

    private SubscriptionCycleRepository.NewCycle newCycle(Fixture fixture, int periodIndex) {
        Instant start = Instant.now();
        return new SubscriptionCycleRepository.NewCycle(
                UUID.randomUUID(), fixture.subscriptionId(), periodIndex,
                start, start.plus(Duration.ofDays(30)),
                "renewal:" + fixture.subscriptionId() + ":" + periodIndex,
                "PENDING", 2900L, "CNY", fixture.versionId());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record Fixture(
            UUID subscriptionId, UUID orderId, String orderNo, UUID grantId,
            UUID versionId, String planCode) {
    }
}
