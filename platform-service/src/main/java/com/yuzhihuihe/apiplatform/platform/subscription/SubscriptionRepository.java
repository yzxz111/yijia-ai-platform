package com.yuzhihuihe.apiplatform.platform.subscription;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 订阅协议的读写。
 *
 * <p>所有面向用户的读写都带 {@code tenant_id} 与 {@code user_id} 两个条件，
 * 查不到一律当作「不存在」（不区分「没有」与「不是你的」），与
 * {@code PackageLimitUsageController} 的既有约定一致。
 */
@Repository
class SubscriptionRepository {

    private final JdbcTemplate jdbc;

    SubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 ID 读取并加行锁。
     *
     * <p>{@code FOR UPDATE} 是必需的：开通、解约与续费可能同时到达同一行，
     * 没有行锁时「解约」与「扣款」会竞态，用户刚点完解约就被扣了一期。
     *
     * <p>注意：调用方必须处于**可写事务**中，只读事务里 PostgreSQL 会拒绝
     * {@code SELECT ... FOR UPDATE}（{@code cannot execute SELECT FOR UPDATE in a read-only transaction}）。
     * 只读查询请用 {@link #findById}。
     */
    Optional<SubscriptionRow> findByIdForUpdate(UUID tenantId, UUID userId, UUID subscriptionId) {
        return jdbc.query("""
                        SELECT """ + ' ' + SUBSCRIPTION_COLUMNS + """
                        FROM platform.subscription
                        WHERE id = ? AND tenant_id = ? AND user_id = ?
                        FOR UPDATE
                        """, SUBSCRIPTION_MAPPER, subscriptionId, tenantId, userId)
                .stream().findFirst();
    }

    /** 不加锁的按 ID 读取，供只读查询使用。 */
    Optional<SubscriptionRow> findById(UUID tenantId, UUID userId, UUID subscriptionId) {
        return jdbc.query("""
                        SELECT """ + ' ' + SUBSCRIPTION_COLUMNS + """
                        FROM platform.subscription
                        WHERE id = ? AND tenant_id = ? AND user_id = ?
                        """, SUBSCRIPTION_MAPPER, subscriptionId, tenantId, userId)
                .stream().findFirst();
    }

    /**
     * 领取到期的订阅。
     *
     * <p>租约在**订阅行**上，因此「同一订阅被两个调度实例同时扣款」在结构上不可能：
     * 一个订阅任何时刻只有一个租约，而账期行由 {@code uq_subscription_cycle_period}
     * 保证同一期只有一行。
     *
     * <p><b>必须包含「租约已过期的行」这条分支。</b>否则调度进程在领取之后、写终态之前
     * 崩溃时，该行会永久停在持有租约的状态且再无任何查询会取它，续费从此静默停摆。
     * 这与 {@code NotificationFanoutJob} 回收过期 {@code PROCESSING} 行是同一约定。
     *
     * @return 本次领取到的订阅；调用方必须持有事务，否则 {@code FOR UPDATE} 的锁立刻释放
     */
    List<ClaimedSubscription> claimDue(
            Instant now, Duration claimTimeout, int limit) {
        Timestamp at = Timestamp.from(now);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM platform.subscription
                    WHERE status IN ('ACTIVE', 'PAST_DUE')
                      AND auto_renew = true
                      AND cancel_at_period_end = false
                      AND next_charge_at <= ?
                      AND (claim_token IS NULL OR claimed_until <= ?)
                    ORDER BY next_charge_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE platform.subscription AS subscription
                SET claim_token = ?,
                    claimed_until = ?,
                    updated_at = now()
                FROM candidate
                WHERE subscription.id = candidate.id
                RETURNING subscription.id, subscription.claim_token,
                          subscription.tenant_id, subscription.user_id,
                          subscription.package_plan_id
                """, (result, row) -> new ClaimedSubscription(
                        result.getObject("id", UUID.class),
                        result.getObject("claim_token", UUID.class),
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("user_id", UUID.class),
                        result.getObject("package_plan_id", UUID.class)),
                at, at, limit, UUID.randomUUID(), Timestamp.from(now.plus(claimTimeout)));
    }

    /**
     * 扣款成功后释放租约。账期推进由 {@link #advancePeriod} 在同一事务里完成。
     *
     * <p>租约校验不是可选的：失去租约的调度实例可能只是卡了很久，如果没有这道条件，
     * 它醒来后会把另一个实例已经写好的结果覆盖掉。
     */
    void completeClaim(UUID subscriptionId, UUID claimToken) {
        int updated = jdbc.update("""
                UPDATE platform.subscription
                SET claim_token = NULL, claimed_until = NULL, updated_at = now()
                WHERE id = ? AND claim_token = ?
                """, subscriptionId, claimToken);
        if (updated != 1) {
            throw new IllegalStateException("subscription_lease_lost");
        }
    }

    /** 扣款失败后释放租约（保持重试资格，由账期行的 {@code next_attempt_at} 决定何时重试）。 */
    void releaseClaim(UUID subscriptionId, UUID claimToken) {
        jdbc.update("""
                UPDATE platform.subscription
                SET claim_token = NULL, claimed_until = NULL, updated_at = now()
                WHERE id = ? AND claim_token = ?
                """, subscriptionId, claimToken);
    }

    /**
     * 跳过并把水位推到期后（设计 §7.5）。
     *
     * <p>刻意**不**增加 {@code consecutive_failures}、**不**写账期行、**不**发通知。
     * 跳过在正常运营中会频繁发生（用户提前手工续费就是这种情况），若与「余额不足」
     * 混在同一语义里，后台的失败率告警会长期红着，运维就会开始忽略它。
     */
    void skipClaim(UUID subscriptionId, UUID claimToken, String skipReason, Instant nextChargeAt) {
        int updated = jdbc.update("""
                UPDATE platform.subscription
                SET last_skip_reason = ?,
                    next_charge_at = ?,
                    last_evaluated_at = now(),
                    claim_token = NULL,
                    claimed_until = NULL,
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ? AND claim_token = ?
                """, skipReason, Timestamp.from(nextChargeAt), subscriptionId, claimToken);
        if (updated != 1) {
            throw new IllegalStateException("subscription_lease_lost");
        }
    }

    /**
     * 锁定用户 + 计划这一组合，串行化「开通」与「续费建单」。
     *
     * <p>用 {@link ConnectionCallback} 而不是 {@code queryForObject}：{@code pg_advisory_xact_lock}
     * 返回 {@code void}，按标量读取会失败。写法与 {@code PackagePurchasePolicyRepository.lock}
     * 一致。
     */
    void lockUserPlan(UUID userId, UUID packagePlanId) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, userId + ":" + packagePlanId);
                statement.execute();
                return null;
            }
        });
    }

    /**
     * 该用户是否已持有**任意** ACTIVE 套餐实例。
     *
     * <p>这是设计 §5.8 的守卫。它**必须按维度判定**，不能只看「用户有没有任意 ACTIVE 实例」。
     *
     * <p>设计写成时 {@code uq_active_package_per_user} 是
     * {@code UNIQUE (user_id) WHERE status='ACTIVE'}（每用户全局一个活跃实例），因此
     * 「有任意活跃实例」与「该维度已有活跃实例」在当时是等价的。但
     * {@code V80__dimension_scoped_active_packages.sql} 已把唯一性收窄为
     * {@code UNIQUE (user_id, usage_dimension)}，该迁移的头注释写明
     * 「同时持有两种套餐的准确含义是**每个维度各一个** ACTIVE 实例」——
     * 跨维度并存现在是既合法又预期的状态。
     *
     * <p>仍然**不区分来源**：同一维度下用户可能刚手工买过同类套餐，跳过是**正确**行为
     * ——他已经付过钱了。
     *
     * <p>实测（2026-09-28）：一个只持有 VIDEO_SECOND 活跃实例的用户，其 TOKEN 订阅会被
     * 旧实现永久跳过（{@code last_skip_reason=active_package_present}、钱包与订单数都不动），
     * 即「拿着视频套餐的人文本订阅再也不续费」。按维度收窄后不再发生。
     */
    boolean hasActivePackageInstance(UUID tenantId, UUID userId, String usageDimension) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM gateway.package_instance
                    WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'
                      AND usage_dimension = ?)
                """, Boolean.class, tenantId, userId, usageDimension));
    }

    /** 账号是否仍可用（冻结/封禁后不再扣款）。 */
    boolean hasActiveUser(UUID tenantId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.app_user
                    WHERE id = ? AND tenant_id = ? AND status = 'ACTIVE')
                """, Boolean.class, userId, tenantId));
    }

    /**
     * 扣款成功后推进账期。
     *
     * <p>{@code next_charge_at} 只依据 {@code durationDays} 前移，**不等待实例激活**：
     * 续期实例会以 QUEUED 落库（设计 §5.5），有效期从激活时刻才起算，因此把激活当触发点
     * 会让账期永远停住。激活只用于事后校正（见 {@code SubscriptionPeriodReconciler}）。
     *
     * <p>同时也把 {@code cancel_at_period_end} 生效掉：用户解约后当期已用满，
     * 这个标记的使命结束，订阅进入终态。
     */
    void advancePeriod(
            UUID subscriptionId,
            Instant periodStart,
            Instant periodEnd,
            Instant nextChargeAt,
            boolean cancelAtPeriodEnd,
            long chargedAmount) {
        int updated = jdbc.update("""
                UPDATE platform.subscription
                SET current_period_start = ?,
                    current_period_end = ?,
                    next_charge_at = ?,
                    renewal_count = renewal_count + 1,
                    consecutive_failures = 0,
                    grace_until = NULL,
                    last_charged_amount = ?,
                    status = CASE WHEN ? THEN 'CANCELED' ELSE 'ACTIVE' END,
                    canceled_at = CASE WHEN ? THEN now() ELSE canceled_at END,
                    last_skip_reason = NULL,
                    last_evaluated_at = now(),
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ?
                """, Timestamp.from(periodStart), Timestamp.from(periodEnd), Timestamp.from(nextChargeAt),
                chargedAmount, cancelAtPeriodEnd, cancelAtPeriodEnd, subscriptionId);
        if (updated != 1) {
            throw new IllegalStateException("subscription_not_found_for_advance");
        }
    }

    /** 扣款失败：进入 PAST_DUE 并设置宽限期截止。 */
    void markPastDue(UUID subscriptionId, Instant graceUntil) {
        jdbc.update("""
                UPDATE platform.subscription
                SET status = 'PAST_DUE',
                    consecutive_failures = consecutive_failures + 1,
                    grace_until = ?,
                    last_evaluated_at = now(),
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ?
                """, Timestamp.from(graceUntil), subscriptionId);
    }

    /**
     * 扣款失败但仍在原账期内重试。
     *
     * <p>失败时 {@code next_charge_at} 推后的是**账期长度**，这样宽限期内每个账期
     * 都有一次尝试机会：把水位留在过去会让调度器在退避到期后立刻重试到次数上限，
     * 用户还没来得及充值订阅就已经作废。而账期本身不前进——那一次扣款并没有
     * 换来对应的服务时长。
     */
    void markChargeFailed(
            UUID subscriptionId, UUID claimToken, Instant nextChargeAt, Instant graceUntil) {
        int updated = jdbc.update("""
                UPDATE platform.subscription
                SET status = 'PAST_DUE',
                    consecutive_failures = consecutive_failures + 1,
                    next_charge_at = ?,
                    grace_until = ?,
                    claim_token = NULL,
                    claimed_until = NULL,
                    last_evaluated_at = now(),
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ? AND claim_token = ?
                """, Timestamp.from(nextChargeAt), Timestamp.from(graceUntil),
                subscriptionId, claimToken);
        if (updated != 1) {
            throw new IllegalStateException("subscription_lease_lost");
        }
    }

    /**
     * 暂缓（不消耗尝试预算、不算失败）：价格上调待用户确认等情形。
     *
     * <p>水位**留在原处**——用户一确认，下一轮调度就会按新价扣款。这里只清租约并
     * 把原因写进 {@code last_skip_reason} 供后台解释。
     */
    void markChargeDeferred(UUID subscriptionId, UUID claimToken, String reason) {
        int updated = jdbc.update("""
                UPDATE platform.subscription
                SET last_skip_reason = ?,
                    last_evaluated_at = now(),
                    claim_token = NULL,
                    claimed_until = NULL,
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ? AND claim_token = ?
                """, reason, subscriptionId, claimToken);
        if (updated != 1) {
            throw new IllegalStateException("subscription_lease_lost");
        }
    }

    /**
     * 记下「等待用户确认的续费报价」（设计 §7.3）。
     *
     * <p>为什么必须落库、而不是只发一条通知：<b>用户确认时要能校验「你确认的那个价」与
     * 「此刻的已发布价」是否还是同一个</b>。只发通知的话，管理员在用户点确认之前又调了一次价，
     * 系统就会按新价扣款——用户确认的是 A 价、扣的是 B 价，这正是设计 §7.3 要防的合规事故。
     *
     * <p>幂等：同一报价重复写入不改变 {@code price_change_detected_at}（超时兜底的计时起点不能
     * 因为每个调度周期都重写而被无限推后，否则「长期未确认」永远不会触发）。只有报价**变化**时才刷新它。
     */
    void recordPendingPriceChange(
            UUID subscriptionId, UUID claimToken, long pendingAmount, Instant now) {
        int updated = jdbc.update("""
                UPDATE platform.subscription
                SET pending_price_amount = ?,
                    price_change_detected_at =
                        CASE WHEN pending_price_amount = ? THEN price_change_detected_at ELSE ? END,
                    last_skip_reason = 'subscription_price_changed',
                    last_evaluated_at = ?,
                    claim_token = NULL,
                    claimed_until = NULL,
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ? AND claim_token = ?
                """, pendingAmount, pendingAmount, Timestamp.from(now), Timestamp.from(now),
                subscriptionId, claimToken);
        if (updated != 1) {
            throw new IllegalStateException("subscription_lease_lost");
        }
    }

    /**
     * 用户确认接受调整后的价格。
     *
     * <p>{@code AND pending_price_amount = ?} 是**乐观校验**：调用方传入它刚读到的报价；
     * 若期间价格又变了（或被别的路径清了），更新影响 0 行，调用方据此要求用户重新确认，
     * 而不是默默按一个用户没见过的价扣款。
     *
     * <p>同时把水位推到 {@code now}，让下一轮立即扣款——这正是 {@code markChargeDeferred}
     * 刻意没做的那一步（它把推进水位的责任留给「确认」，而确认此前并不存在）。
     *
     * @return 是否确认成功
     */
    boolean confirmPendingPrice(UUID subscriptionId, long confirmedAmount, Instant now) {
        int updated = jdbc.update("""
                UPDATE platform.subscription
                SET pending_price_amount = NULL,
                    price_change_detected_at = NULL,
                    price_confirmed_at = ?,
                    accepted_price_amount = ?,
                    next_charge_at = ?,
                    last_skip_reason = NULL,
                    last_evaluated_at = ?,
                    claim_token = NULL,
                    claimed_until = NULL,
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ? AND pending_price_amount = ?
                  AND status IN ('ACTIVE', 'PAST_DUE')
                """, Timestamp.from(now), confirmedAmount, Timestamp.from(now), Timestamp.from(now),
                subscriptionId, confirmedAmount);
        return updated == 1;
    }

    /**
     * 报价不再需要确认（已发布价已回落到用户上次实付价或更低）。
     *
     * <p>没有这一步的话，一次「涨价后又调回来」会让订阅永远停在待确认：策略侧已经不再判定为
     * 涨价，而 pending 字段还挂着，后台看起来像卡住了。
     */
    void clearPendingPriceChange(UUID subscriptionId) {
        jdbc.update("""
                UPDATE platform.subscription
                SET pending_price_amount = NULL,
                    price_change_detected_at = NULL,
                    price_confirmed_at = now(),
                    last_skip_reason = NULL,
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ? AND pending_price_amount IS NOT NULL
                """, subscriptionId);
    }

    /**
     * 报价在用户确认的瞬间又变了：刷新成新报价并要求重新确认。
     *
     * <p>与 {@link #recordPendingPriceChange} 的区别是**不需要租约**——调用来自用户确认接口，
     * 那里没有 {@code claim_token}。语义上这是「同一件事的新一轮报价」，因此
     * {@code price_change_detected_at} 重置为此刻：**确认时限应当从最新报价算起**，
     * 否则管理员反复调价会让用户在一个早已开始的 30 天窗口里被终止。
     */
    void refreshPendingPriceChange(UUID subscriptionId, long pendingAmount, Instant now) {
        jdbc.update("""
                UPDATE platform.subscription
                SET pending_price_amount = ?,
                    price_change_detected_at = ?,
                    last_skip_reason = 'subscription_price_changed',
                    last_evaluated_at = ?,
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ? AND pending_price_amount IS NOT NULL
                  AND status IN ('ACTIVE', 'PAST_DUE')
                """, pendingAmount, Timestamp.from(now), Timestamp.from(now), subscriptionId);
    }

    /**
     * 长期未确认的报价 —— 调度侧兜底扫描用。
     *
     * <p><b>这条兜底是必须的。</b>没有它，订阅会无限期停在「ACTIVE 但永不扣款」：用户以为在续费、
     * 实际早就停了，而且不会有任何一方收到告警（暂缓刻意不发失败通知，见 {@code markChargeDeferred}）。
     * 超过一个周期仍未确认，只能按终止处理。
     */
    List<UUID> findUnconfirmedPriceBeyond(Instant detectedBefore, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM platform.subscription
                WHERE pending_price_amount IS NOT NULL
                  AND price_change_detected_at <= ?
                  AND status IN ('ACTIVE', 'PAST_DUE')
                ORDER BY price_change_detected_at, id
                LIMIT ?
                """, UUID.class, Timestamp.from(detectedBefore), limit);
    }

    /** 宽限期用尽或排队超时：停止扣款。 */
    void markExpired(UUID subscriptionId) {
        jdbc.update("""
                UPDATE platform.subscription
                SET status = 'EXPIRED',
                    auto_renew = false,
                    expired_at = now(),
                    grace_until = NULL,
                    claim_token = NULL,
                    claimed_until = NULL,
                    last_evaluated_at = now(),
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ? AND status IN ('ACTIVE', 'PAST_DUE')
                """, subscriptionId);
    }

    /**
     * 终态终止：达到续期上限、排队超时、或后台人工终止。
     *
     * <p>同时清租约——终止之后不该再有任何在途扣款。
     */
    void markTerminated(UUID subscriptionId, String finalSkipReason) {
        jdbc.update("""
                UPDATE platform.subscription
                SET status = 'TERMINATED',
                    auto_renew = false,
                    cancel_at_period_end = true,
                    last_skip_reason = ?,
                    last_evaluated_at = now(),
                    terminated_at = now(),
                    claim_token = NULL,
                    claimed_until = NULL,
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ? AND status IN ('ACTIVE', 'PAST_DUE')
                """, finalSkipReason, subscriptionId);
    }

    /**
     * 退款成功后终止订阅（设计 §10）。
     *
     * <p>与 {@link #markTerminated} 的区别是**不需要租约**：调用来自退款链路，而不是调度链路，
     * 那里根本没有 {@code claim_token}。因此这里不碰租约字段——一个正在被调度持有的租约会在
     * 它自己的流程里清掉，而 {@code status='TERMINATED'} 已足以让 {@code ix_subscription_due}
     * 不再领取它（其谓词含 {@code auto_renew} 与 {@code status}），所以不存在「终止后又被扣一次」。
     *
     * <p>{@code WHERE status IN (...)} 让重复调用天然幂等：第二次影响 0 行，不报错。
     *
     * @return 是否真的改变了状态（false = 本来就不是活跃订阅，无操作）
     */
    boolean terminateForRefund(UUID subscriptionId) {
        int updated = jdbc.update("""
                UPDATE platform.subscription
                SET status = 'TERMINATED',
                    auto_renew = false,
                    cancel_at_period_end = true,
                    last_skip_reason = 'refund_terminated',
                    last_evaluated_at = now(),
                    terminated_at = now(),
                    claim_token = NULL,
                    claimed_until = NULL,
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ? AND status IN ('ACTIVE', 'PAST_DUE')
                """, subscriptionId);
        return updated == 1;
    }

    /**
     * 正常跳过：只记原因并推进水位。
     *
     * <p>刻意**不**增加 {@code consecutive_failures}、**不**写账期行、**不**发通知。
     * 这是设计 §7.5 的核心区分：跳过在正常运营中会频繁发生（用户提前手工续费就是这种情况），
     * 若与「余额不足」混在同一语义里，后台的失败率告警会长期红着，运维就会开始忽略它。
     *
     * <p>前提：该订阅的租约已被本实例持有，因此这里也必须清掉它。
     */
    /** 用户开关自动续费；关闭时同步 {@code cancel_at_period_end}（受 ck 约束约束）。 */
    void setAutoRenew(UUID tenantId, UUID userId, UUID subscriptionId, boolean autoRenew) {
        int updated = jdbc.update("""
                UPDATE platform.subscription
                SET auto_renew = ?,
                    cancel_at_period_end = ?,
                    canceled_at = CASE WHEN ? THEN now() ELSE NULL END,
                    last_evaluated_at = now(),
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND status IN ('ACTIVE', 'PAST_DUE')
                """, autoRenew, !autoRenew, !autoRenew, subscriptionId, tenantId, userId);
        if (updated != 1) {
            throw new IllegalStateException("subscription_not_open_for_update");
        }
    }

    /**
     * 按「用户 × 计划」读取活跃订阅。
     *
     * <p>与 {@link #findByIdForUpdate} 的区别是查询维度：开通流程手里只有计划 ID，
     * 还没拿到订阅 ID，所以必须先按这个维度找。返回的不加行锁——调用方若要修改，
     * 应先用 {@link #lockUserPlan} 串行化。
     */
    Optional<SubscriptionRow> findActiveByPlan(UUID tenantId, UUID userId, UUID packagePlanId) {
        return jdbc.query("""
                        SELECT """ + ' ' + SUBSCRIPTION_COLUMNS + """
                        FROM platform.subscription
                        WHERE tenant_id = ? AND user_id = ? AND package_plan_id = ?
                          AND status IN ('ACTIVE', 'PAST_DUE')
                        """, SUBSCRIPTION_MAPPER, tenantId, userId, packagePlanId)
                .stream().findFirst();
    }

    /**
     * 插入订阅行；已有活跃订阅时返回既有的那个。
     *
     * <p>{@code ON CONFLICT} 必须写成**索引谓词形式**：{@code uq_subscription_active_plan} 是
     * 部分唯一索引（{@code WHERE status IN ('ACTIVE','PAST_DUE')}），而 PostgreSQL 的
     * {@code ON CONFLICT (列列表)} 无法推断出部分索引，会报
     * 「there is no unique or exclusion constraint matching the ON CONFLICT specification」。
     */
    Optional<SubscriptionRow> insertIfAbsent(NewSubscription subscription) {
        jdbc.update("""
                INSERT INTO platform.subscription(
                    id, tenant_id, user_id, package_plan_id, auto_renew, cancel_at_period_end,
                    status, current_period_start, current_period_end, next_charge_at,
                    consecutive_failures, renewal_count, last_charged_amount)
                VALUES (?, ?, ?, ?, true, false, 'ACTIVE', ?, ?, ?, 0, 0, ?)
                ON CONFLICT (tenant_id, user_id, package_plan_id)
                    WHERE status IN ('ACTIVE', 'PAST_DUE')
                DO NOTHING
                """, subscription.id(), subscription.tenantId(), subscription.userId(),
                subscription.packagePlanId(), Timestamp.from(subscription.periodStart()),
                Timestamp.from(subscription.periodEnd()), Timestamp.from(subscription.nextChargeAt()),
                subscription.chargedAmount());
        return findActiveByPlan(
                subscription.tenantId(), subscription.userId(), subscription.packagePlanId());
    }

    /**
     * 该用户的活跃订阅，按到期日升序（最近要扣款的排前面）。
     *
     * <p>用于到期提醒的分流：一个用户可能同时持有文本与视频两份订阅，提醒应当挑
     * 最先要扣款的那一份。{@code CANCELED} 不在内——它意味着用户已经关了自动续费，
     * 对他喊「将自动续费」是错的。
     */
    List<SubscriptionRow> findActiveForUser(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT """ + ' ' + SUBSCRIPTION_COLUMNS + """
                FROM platform.subscription
                WHERE tenant_id = ? AND user_id = ?
                  AND status IN ('ACTIVE', 'PAST_DUE')
                  AND auto_renew = true
                  AND cancel_at_period_end = false
                ORDER BY next_charge_at, id
                """, SUBSCRIPTION_MAPPER, tenantId, userId);
    }
    /**
     * 按 ID 读取（不加租户/用户过滤）。**仅供管理端使用。**
     *
     * <p>与 {@link #findById} 的区别是刻意去掉归属条件：管理端要能看到任意用户的订阅，
     * 而面向用户的读取必须带上 {@code tenant_id + user_id}。把两者分成不同方法而不是
     * 加一个 boolean 开关——开关会让「某条用户路径忘了传参」变成静默的越权读。
     */
    Optional<SubscriptionRow> findByIdForAdmin(UUID subscriptionId) {
        return jdbc.query("""
                        SELECT """ + ' ' + SUBSCRIPTION_COLUMNS + """
                        FROM platform.subscription
                        WHERE id = ?
                        """, SUBSCRIPTION_MAPPER, subscriptionId)
                .stream().findFirst();
    }

    List<SubscriptionRow> listForUser(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT """ + ' ' + SUBSCRIPTION_COLUMNS + """
                FROM platform.subscription
                WHERE tenant_id = ? AND user_id = ?
                ORDER BY created_at DESC
                """, SUBSCRIPTION_MAPPER, tenantId, userId);
    }

    /**
     * {@link SubscriptionRow} 的列清单，被六处查询共用。
     *
     * <p>抽成常量是因为它此前是**复制六份**的字面量：加一列要改六个地方，漏一处就会在运行时抛
     * {@code ColumnNotFoundException}——而那六处只在各自被调用时才暴露，于是「加一列」这种小改动
     * 会变成一次逐接口排查。现在只有这一个地方需要维护。
     *
     * <p>与 {@code SubscriptionRow} 的字段**必须一一对应**，顺序即构造参数顺序；
     * {@code SubscriptionRepositoryTest} 对此有断言，漏加会直接测试失败而不是上线后 500。
     */
    private static final String SUBSCRIPTION_COLUMNS =
            "id, tenant_id, user_id, package_plan_id, auto_renew,"
                    + " cancel_at_period_end, status, current_period_start, current_period_end,"
                    + " next_charge_at, grace_until, consecutive_failures, renewal_count,"
                    + " last_charged_amount, last_skip_reason, last_evaluated_at, row_version,"
                    + " claim_token, claimed_until,"
                    + " pending_price_amount, price_change_detected_at, price_confirmed_at, accepted_price_amount"
                    // 末尾这个空格是必需的：调用点写的是
                    // `SELECT """ + ' ' + SUBSCRIPTION_COLUMNS + """ FROM ...`，
                    // 而 Java 文本块会吃掉续行处的换行，因此两边都得自带分隔，
                    // 否则会拼出 `SELECTid` 与 `claimed_untilFROM`。
                    + " ";

    private static final org.springframework.jdbc.core.RowMapper<SubscriptionRow> SUBSCRIPTION_MAPPER =
            (result, rowNumber) -> new SubscriptionRow(
                    result.getObject("id", UUID.class),
                    result.getObject("tenant_id", UUID.class),
                    result.getObject("user_id", UUID.class),
                    result.getObject("package_plan_id", UUID.class),
                    result.getBoolean("auto_renew"),
                    result.getBoolean("cancel_at_period_end"),
                    result.getString("status"),
                    instant(result, "current_period_start"),
                    instant(result, "current_period_end"),
                    instant(result, "next_charge_at"),
                    instant(result, "grace_until"),
                    result.getInt("consecutive_failures"),
                    result.getInt("renewal_count"),
                    result.getObject("last_charged_amount", Long.class),
                    result.getString("last_skip_reason"),
                    instant(result, "last_evaluated_at"),
                    result.getLong("row_version"),
                    result.getObject("claim_token", UUID.class),
                    instant(result, "claimed_until"),
                    result.getObject("pending_price_amount", Long.class),
                    instant(result, "price_change_detected_at"),
                    instant(result, "price_confirmed_at"),
                    result.getObject("accepted_price_amount", Long.class));

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record ClaimedSubscription(
            UUID subscriptionId,
            UUID claimToken,
            UUID tenantId,
            UUID userId,
            UUID packagePlanId) {
    }

    /** 开通一份新订阅所需的全部字段（活跃订阅的唯一性由部分唯一索引兜底）。 */
    record NewSubscription(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID packagePlanId,
            Instant periodStart,
            Instant periodEnd,
            Instant nextChargeAt,
            long chargedAmount) {
    }

    record SubscriptionRow(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID packagePlanId,
            boolean autoRenew,
            boolean cancelAtPeriodEnd,
            String status,
            Instant currentPeriodStart,
            Instant currentPeriodEnd,
            Instant nextChargeAt,
            Instant graceUntil,
            int consecutiveFailures,
            int renewalCount,
            Long lastChargedAmount,
            String lastSkipReason,
            Instant lastEvaluatedAt,
            long rowVersion,
            UUID claimToken,
            Instant claimedUntil,
            /**
             * 等待用户确认的续费报价（分）；非空即表示因涨价暂缓（设计 §7.3）。
             *
             * <p>字段顺序必须与 {@link #SUBSCRIPTION_COLUMNS} 一致。
             */
            Long pendingPriceAmount,
            Instant priceChangeDetectedAt,
            Instant priceConfirmedAt,
            /**
             * 用户已明确同意支付的续费价格（分）；未确认过涨价时为 null。
             *
             * <p>与 {@code lastChargedAmount} 分开是必需的：后者只能表示「实际扣过多少」，
             * 而价格比较还需要知道「用户同意付多少」。少了这一列，用户确认涨价之后调度会拿
             * 旧的实扣金额再判一次涨价，形成「确认了却永远不扣款」的死循环（实测踩到过）。
             */
            Long acceptedPriceAmount) {
    }
}
