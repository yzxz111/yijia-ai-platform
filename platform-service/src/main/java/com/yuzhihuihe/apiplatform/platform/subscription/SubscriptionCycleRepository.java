package com.yuzhihuihe.apiplatform.platform.subscription;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 订阅账期的读写。
 *
 * <p><b>账期行上没有租约列。</b>租约在 {@code platform.subscription} 行上
 * （见 {@code ck_subscription_claim}）：「一个订阅至多一个在途扣款」因此成为结构事实，
 * 而不是靠应用层记得去查「有没有未结账期」。本类只负责账期的历史与当前状态。
 */
@Repository
class SubscriptionCycleRepository {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionCycleRepository.class);

    private final JdbcTemplate jdbc;

    SubscriptionCycleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入账期行；同一期已存在时返回**既有行的 ID**（不抛异常）。
     *
     * <p>写成「先回读、再插入」而不是「插入 + 处理冲突」，有两个具体理由：
     *
     * <ol>
     *   <li>账期上有**两条**唯一索引：{@code uq_subscription_cycle_business_key} 与
     *       {@code uq_subscription_cycle_period(subscription_id, period_index)}，
     *       而 PostgreSQL 一条 INSERT 只允许**一个** {@code ON CONFLICT} 子句，
     *       没法同时点名两条（写两个会是语法错误）。漏掉的那条一旦冲突就抛异常。</li>
     *   <li>「抛异常后再查一次」在 PostgreSQL 里根本不可行：异常一旦抛出，同一事务内
     *       后续任何语句都会以 {@code 25P02 current transaction is aborted} 失败。</li>
     * </ol>
     *
     * <p>回读用 {@code (subscription_id, period_index)}：调用方正是按它推导期号的
     * （{@code latest.periodIndex() + 1}），因此回读键与调用方的判定口径一致。
     * 保留 {@code ON CONFLICT (business_key)} 作为并发兜底。
     *
     * <p>实测症状（改之前）：连续三次续费失败后订阅停在 {@code PAST_DUE} 而不是
     * {@code EXPIRED}，因为记账事务一直在回滚、失败账期一行都没留下。
     */
    UUID insertIfAbsent(NewCycle cycle) {
        UUID existing = existingCycleId(cycle);
        if (existing != null) {
            return existing;
        }
        jdbc.update("""
                INSERT INTO platform.subscription_cycle(
                    id, subscription_id, period_index, period_start, period_end,
                    business_key, status, amount, currency, package_plan_version_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (business_key) DO NOTHING
                """, cycle.id(), cycle.subscriptionId(), cycle.periodIndex(),
                Timestamp.from(cycle.periodStart()), Timestamp.from(cycle.periodEnd()),
                cycle.businessKey(), cycle.status(), cycle.amount(), cycle.currency(),
                cycle.packagePlanVersionId());
        return existingCycleId(cycle);
    }

    /** 按「订阅 × 期号」回读既有行 ID。 */
    private UUID existingCycleId(NewCycle cycle) {
        return jdbc.query("""
                        SELECT id FROM platform.subscription_cycle
                        WHERE subscription_id = ? AND period_index = ?
                        """, (result, row) -> result.getObject("id", UUID.class),
                        cycle.subscriptionId(), cycle.periodIndex())
                .stream().findFirst()
                .orElse(null);
    }

    /** 记账期扣款成功。 */
    void markCharged(
            UUID cycleId,
            UUID orderId,
            String orderNo,
            UUID packagePlanVersionId,
            long amount,
            UUID grantRequestId) {
        jdbc.update("""
                UPDATE platform.subscription_cycle
                SET status = 'CHARGED',
                    order_id = ?,
                    order_no = ?,
                    package_plan_version_id = ?,
                    amount = ?,
                    grant_request_id = ?,
                    charged_at = now(),
                    failure_reason = NULL,
                    last_error = NULL,
                    updated_at = now()
                WHERE id = ?
                """, orderId, orderNo, packagePlanVersionId, amount, grantRequestId, cycleId);
    }

    /** 记账期扣款失败并安排重试。 */
    void markFailed(UUID cycleId, String failureReason, String lastError, Instant nextAttemptAt) {
        jdbc.update("""
                UPDATE platform.subscription_cycle
                SET status = 'FAILED',
                    failure_reason = ?,
                    last_error = ?,
                    next_attempt_at = ?,
                    attempt_count = attempt_count + 1,
                    updated_at = now()
                WHERE id = ?
                """, failureReason, truncate(lastError), Timestamp.from(nextAttemptAt), cycleId);
    }

    /** 账期重试次数已用尽：把重试水位推到无穷远，让它不再被选中。 */
    void markExhausted(UUID cycleId, String failureReason) {
        jdbc.update("""
                UPDATE platform.subscription_cycle
                SET next_attempt_at = 'infinity'::timestamptz,
                    failure_reason = ?,
                    updated_at = now()
                WHERE id = ?
                """, failureReason, cycleId);
    }

    /** 实例已激活：把账期推进到终态 GRANTED，供后台区分「已扣款」与「已生效」。 */
    void markGranted(UUID cycleId) {
        jdbc.update("""
                UPDATE platform.subscription_cycle
                SET status = 'GRANTED', updated_at = now()
                WHERE id = ? AND status = 'CHARGED'
                """, cycleId);
    }

    /**
     * 由发放请求反查它所属的订阅账期。
     *
     * <p>用于「周期真正开始」的通知：实例激活的信号来自既有的发放链路，那边只知道
     * {@code grantRequestId}。查不到说明这笔发放不是订阅产生的，调用方应静默跳过。
     *
     * <p><b>主查询只碰 platform schema。</b>发放链路的成败绝不能取决于本服务能否
     * 读到 gateway schema —— 生产上两者可能是两个库、两个账号，而一个只迁移了
     * 平台库的环境里 gateway schema 根本不存在。一次跨 schema 的读失败会让整条
     * 发放投递卡在 PENDING（GrantOutboxTest 的三个失败正是它）。
     * 实例到期时间因此拆成一次**尽力而为**的补充查询。
     */
    CycleOwner findOwnerByGrant(UUID grantRequestId) {
        return jdbc.query("""
                        SELECT cycle.id AS cycle_id, subscription.tenant_id, subscription.user_id
                        FROM platform.subscription_cycle cycle
                        JOIN platform.subscription subscription ON subscription.id = cycle.subscription_id
                        WHERE cycle.grant_request_id = ?
                        """, (result, row) -> new CycleOwner(
                        result.getObject("cycle_id", UUID.class),
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("user_id", UUID.class),
                        null),
                        grantRequestId)
                .stream().findFirst()
                .map(owner -> new CycleOwner(
                        owner.cycleId(), owner.tenantId(), owner.userId(),
                        packageInstanceExpiry(grantRequestId)))
                .orElse(null);
    }

    /**
     * 该发放对应的套餐实例到期时间，**读不到就返回 null**。
     *
     * <p>这是补充信息，不是判断依据：调用方用它填通知里的到期日。
     * 读不到 gateway schema（不存在、无权限、或跨库不可达）时降级为 null，
     * 而不是让一次发放投递失败。上面那条主查询已经证明账期存在，
     * 因此这里吞掉异常不会掩盖「查无此发放」。
     */
    private Instant packageInstanceExpiry(UUID grantRequestId) {
        try {
            List<Instant> rows = jdbc.query("""
                            SELECT instance.expires_at AS expires_at
                            FROM platform.subscription_cycle cycle
                            LEFT JOIN gateway.package_instance instance
                                   ON instance.grant_request_id = cycle.grant_request_id
                            WHERE cycle.grant_request_id = ?
                            """, (result, row) -> {
                                Timestamp value = result.getTimestamp("expires_at");
                                return value == null ? null : value.toInstant();
                            },
                            grantRequestId);
            // 取值用 List 取首元素，**不要**写成 .stream().findFirst()：
            // 映射器在这一列是 NULL 时返回 null，而 findFirst() 遇到 null 元素会以
            // Optional.of(null) 抛 NullPointerException——实例尚未物化是**正常**情形
            // （订阅刚扣款、网关还没激活），因此那条路径必须能返回 null。
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException error) {
            log.warn("package instance expiry unavailable for grant {}: {}",
                    grantRequestId, error.getMessage());
            return null;
        }
    }

    /**
     * 由**发放请求**反查它属于哪一份订阅（退款交叉用，设计 §10）。
     *
     * <p>为什么用 {@code grant_request_id} 而不是 {@code order_id}：退款链路手里有发放请求 ID
     * （网关撤销的就是那次发放），而账期行对两个 ID 都有外键。实测本库首期与续期的账期行
     * **两个关联列都已填**，因此两者都可作为桥；选 grant 是因为它与退款的「撤销哪次发放」
     * 语义完全对应，不需要再经订单转一次。
     *
     * <p>查不到返回空：那说明这笔发放不是订阅产生的（一次性购买），调用方应**不做任何事**——
     * 这正是「退款交叉只影响订阅、不碰一次性购买」的落地方式。
     */
    Optional<UUID> findSubscriptionIdByGrant(UUID grantRequestId) {
        if (grantRequestId == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                        SELECT cycle.subscription_id
                        FROM platform.subscription_cycle cycle
                        WHERE cycle.grant_request_id = ?
                        """, (result, row) -> result.getObject("subscription_id", UUID.class),
                        grantRequestId)
                .stream().findFirst();
    }

    /**
     * 账期的归属者，外加**实例的权威有效期**。
     *
     * <p>{@code expiresAt} 直接取自 {@code gateway.package_instance}，因为只有实例自己知道
     * 有效期：它是 {@code activated_at + duration_days}，由网关侧的激活事务写入。
     *
     * <p><b>绝不能拿调用方的时钟当有效期。</b>那正是 2026-09-28 实测到的缺陷：通知把「现在」
     * 当作有效期报给用户，实测「有效期至 2026-09-28」而实例真实到期日是 2026-10-28，
     * <b>少报整整 30 天</b>——用户会以为套餐当天就到期了。
     *
     * <p>取不到时为 {@code null}（实例尚未物化），调用方应**跳过**这次通知而不是发一个错日期；
     * outbox 每个周期都会重投，下一轮会补上。
     */
    record CycleOwner(UUID cycleId, UUID tenantId, UUID userId, Instant expiresAt) {
    }
    /**
     * 该订阅累计失败的账期数 —— 重试预算的权威口径。
     *
     * <p><b>不能用「最新账期行的 attempt_count」当预算。</b>每次失败都是一个**新账期行**，
     * 而 markFailed 对每个新行都是从 0 加到 1，于是那个值恒为 1，
     * 重试上限永远达不到——订阅会永远停在 PAST_DUE，永远不会进入 EXPIRED，
     * 失败重试实际上无限期进行下去。
     *
     * <p>实测症状：连续三次续费失败后 consecutive_failures=3 而订阅仍是 PAST_DUE，
     * 三个账期行的 attempt_count 全是 1。
     */
    int countFailedCycles(UUID subscriptionId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM platform.subscription_cycle
                WHERE subscription_id = ? AND status = 'FAILED'
                """, Integer.class, subscriptionId);
        return count == null ? 0 : count;
    }
    /**
     * 该订阅最近一个账期（用于推导下一个 {@code period_index} 与判断是否有在途账期）。
     */
    java.util.Optional<CycleRow> findLatest(UUID subscriptionId) {
        return jdbc.query("""
                        SELECT id, subscription_id, period_index, period_start, period_end,
                               business_key, status, amount, currency, package_plan_version_id,
                               order_id, order_no, grant_request_id, attempt_count,
                               next_attempt_at, failure_reason, charged_at
                        FROM platform.subscription_cycle
                        WHERE subscription_id = ?
                        ORDER BY period_index DESC
                        LIMIT 1
                        """, CYCLE_MAPPER, subscriptionId)
                .stream().findFirst();
    }

    /** 按账期 ID 读取单行（管理端人工重试用）。 */
    java.util.Optional<CycleRow> findByCycleId(UUID cycleId) {
        return jdbc.query("""
                        SELECT id, subscription_id, period_index, period_start, period_end,
                               business_key, status, amount, currency, package_plan_version_id,
                               order_id, order_no, grant_request_id, attempt_count,
                               next_attempt_at, failure_reason, charged_at
                        FROM platform.subscription_cycle
                        WHERE id = ?
                        """, CYCLE_MAPPER, cycleId)
                .stream().findFirst();
    }
    List<CycleRow> listBySubscription(UUID subscriptionId) {
        return jdbc.query("""
                SELECT id, subscription_id, period_index, period_start, period_end,
                       business_key, status, amount, currency, package_plan_version_id,
                       order_id, order_no, grant_request_id, attempt_count,
                       next_attempt_at, failure_reason, charged_at
                FROM platform.subscription_cycle
                WHERE subscription_id = ?
                ORDER BY period_index DESC
                """, CYCLE_MAPPER, subscriptionId);
    }

    /**
     * 未激活的账期（用于 §5.6 的队列深度守卫与 §5.7.1 的排队超时兜底）。
     */
    List<UnactivatedCycle> findUnactivated(UUID subscriptionId) {
        // 别名不能叫 grant —— 它是 SQL 保留字，PostgreSQL 会直接报语法错误。
        return jdbc.query("""
                SELECT cycle.id, cycle.period_index, cycle.charged_at, cycle.order_no,
                       cycle.grant_request_id, grant_request.status AS grant_status,
                       instance.status AS instance_status, instance.expires_at
                FROM platform.subscription_cycle cycle
                LEFT JOIN platform.grant_request grant_request ON grant_request.id = cycle.grant_request_id
                LEFT JOIN gateway.package_instance instance ON instance.grant_request_id = grant_request.id
                WHERE cycle.subscription_id = ?
                  AND cycle.status IN ('CHARGED', 'GRANTED')
                ORDER BY cycle.period_index
                """, (result, row) -> new UnactivatedCycle(
                        result.getObject("id", UUID.class),
                        result.getInt("period_index"),
                        instant(result, "charged_at"),
                        result.getString("order_no"),
                        result.getObject("grant_request_id", UUID.class),
                        result.getString("grant_status"),
                        result.getString("instance_status"),
                        instant(result, "expires_at")),
                subscriptionId);
    }

    private static final RowMapper<CycleRow> CYCLE_MAPPER = (result, rowNumber) -> new CycleRow(
            result.getObject("id", UUID.class),
            result.getObject("subscription_id", UUID.class),
            result.getInt("period_index"),
            instant(result, "period_start"),
            instant(result, "period_end"),
            result.getString("business_key"),
            result.getString("status"),
            result.getLong("amount"),
            result.getString("currency"),
            result.getObject("package_plan_version_id", UUID.class),
            result.getObject("order_id", UUID.class),
            result.getString("order_no"),
            result.getObject("grant_request_id", UUID.class),
            result.getInt("attempt_count"),
            instant(result, "next_attempt_at"),
            result.getString("failure_reason"),
            instant(result, "charged_at"));

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** {@code last_error} 有 500 字符上限；超长截断而不是让写入失败。 */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    record NewCycle(
            UUID id,
            UUID subscriptionId,
            int periodIndex,
            Instant periodStart,
            Instant periodEnd,
            String businessKey,
            String status,
            long amount,
            String currency,
            UUID packagePlanVersionId) {
    }

    record CycleRow(
            UUID cycleId,
            UUID subscriptionId,
            int periodIndex,
            Instant periodStart,
            Instant periodEnd,
            String businessKey,
            String status,
            long amount,
            String currency,
            UUID packagePlanVersionId,
            UUID orderId,
            String orderNo,
            UUID grantRequestId,
            int attemptCount,
            Instant nextAttemptAt,
            String failureReason,
            Instant chargedAt) {

        boolean charged() {
            return "CHARGED".equals(status) || "GRANTED".equals(status);
        }
    }

    record UnactivatedCycle(
            UUID cycleId,
            int periodIndex,
            Instant chargedAt,
            String orderNo,
            UUID grantRequestId,
            String grantStatus,
            String instanceStatus,
            Instant instanceExpiresAt) {

        /**
         * 该账期的权益是否仍未生效。
         *
         * <p>判定依据必须是**套餐实例**的状态：{@code grant_request} 长期停在 {@code QUEUED}
         * 是正常状态（设计 §5.7.1/H3），拿它当生效依据会把「已生效」误判为「未生效」。
         * 实例侧为 null 说明事件尚未投递到网关，同样算未生效。
         */
        boolean notYetActive() {
            return instanceStatus == null || "QUEUED".equals(instanceStatus);
        }
    }
}
