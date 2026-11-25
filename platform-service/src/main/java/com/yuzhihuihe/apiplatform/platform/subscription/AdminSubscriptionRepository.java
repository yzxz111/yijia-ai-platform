package com.yuzhihuihe.apiplatform.platform.subscription;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 管理端的订阅读取与人工操作。
 *
 * <p>与 {@code SubscriptionRepository} 分开：那个类的每个读写都必须带
 * {@code tenant_id + user_id}（租户隔离），而管理端刻意要越过这层隔离去看任意用户的订阅。
 * 把两种语义放在不同类里而不是同一个类加开关，是为了让「越权读」在代码审查时一眼可见。
 */
@Repository
class AdminSubscriptionRepository {

    private final JdbcTemplate jdbc;

    AdminSubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 分页查询，可按状态与计划代码过滤。
     *
     * <p>列表里带上用户邮箱与计划代码：管理端最常问的两个问题是「这个用户订了什么」
     * 与「这个套餐有多少人在续」，两者都需要这两列，缺一个就得再点进详情页。
     */
    PageResult<AdminSubscriptionView> list(
            String status, String planCode, UUID userId, int page, int size) {
        List<Object> parameters = new ArrayList<>();
        String where = buildWhere(status, planCode, userId, parameters);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM platform.subscription subscription "
                        + "JOIN platform.package_plan plan ON plan.id = subscription.package_plan_id "
                        + "JOIN platform.app_user app_user ON app_user.id = subscription.user_id "
                        + where,
                Long.class, parameters.toArray());
        parameters.add(size);
        parameters.add(Math.multiplyExact(page, size));
        List<AdminSubscriptionView> items = jdbc.query("""
                SELECT subscription.id, subscription.tenant_id, subscription.user_id,
                       app_user.email AS user_email, subscription.package_plan_id,
                       plan.code AS plan_code, plan.name AS plan_name,
                       subscription.status, subscription.auto_renew,
                       subscription.cancel_at_period_end, subscription.current_period_start,
                       subscription.current_period_end, subscription.next_charge_at,
                       subscription.grace_until, subscription.consecutive_failures,
                       subscription.renewal_count, subscription.last_charged_amount,
                       subscription.last_skip_reason, subscription.claim_token
                FROM platform.subscription subscription
                JOIN platform.package_plan plan ON plan.id = subscription.package_plan_id
                JOIN platform.app_user app_user ON app_user.id = subscription.user_id
                """ + where + " ORDER BY subscription.next_charge_at, subscription.id LIMIT ? OFFSET ?",
                ADMIN_MAPPER, parameters.toArray());
        return new PageResult<>(items, total == null ? 0 : total, page, size);
    }

    Optional<AdminSubscriptionView> findView(UUID subscriptionId) {
        return jdbc.query("""
                        SELECT subscription.id, subscription.tenant_id, subscription.user_id,
                               app_user.email AS user_email, subscription.package_plan_id,
                               plan.code AS plan_code, plan.name AS plan_name,
                               subscription.status, subscription.auto_renew,
                               subscription.cancel_at_period_end, subscription.current_period_start,
                               subscription.current_period_end, subscription.next_charge_at,
                               subscription.grace_until, subscription.consecutive_failures,
                               subscription.renewal_count, subscription.last_charged_amount,
                               subscription.last_skip_reason, subscription.claim_token
                        FROM platform.subscription subscription
                        JOIN platform.package_plan plan ON plan.id = subscription.package_plan_id
                        JOIN platform.app_user app_user ON app_user.id = subscription.user_id
                        WHERE subscription.id = ?
                        """, ADMIN_MAPPER, subscriptionId)
                .stream().findFirst();
    }

    /**
     * 管理端人工终止。
     *
     * <p>不做状态前置条件：管理端的场景恰恰包括「这个订阅状态不对，我要停掉它」，
     * 要求它处于某个特定状态才能停会挡住真正的运维需求。同时清租约——终止之后
     * 不该再有任何在途扣款。
     */
    void terminateByAdmin(UUID subscriptionId, String reason) {
        jdbc.update("""
                UPDATE platform.subscription
                SET status = 'TERMINATED',
                    auto_renew = false,
                    cancel_at_period_end = true,
                    last_skip_reason = ?,
                    last_evaluated_at = now(),
                    claim_token = NULL,
                    claimed_until = NULL,
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ?
                """, reason, subscriptionId);
    }
    /** 人工重试：把失败账期的重试水位推到现在，并清掉失败原因，让它重新可被领取。 */
    int requeueFailedCycle(UUID cycleId) {
        return jdbc.update("""
                UPDATE platform.subscription_cycle
                SET status = 'PENDING',
                    next_attempt_at = now(),
                    failure_reason = NULL,
                    last_error = NULL,
                    updated_at = now()
                WHERE id = ? AND status = 'FAILED'
                """, cycleId);
    }

    /**
     * 人工重试必须同时把订阅从终态拉回来，否则账期再可领取也没用——
     * 调度器的候选条件要求订阅处于 {@code ACTIVE} 或 {@code PAST_DUE}。
     *
     * @return 实际更新的行数；0 表示订阅不处于可恢复状态
     */
    int reopenForRetry(UUID subscriptionId) {
        return jdbc.update("""
                UPDATE platform.subscription
                SET status = 'PAST_DUE',
                    next_charge_at = now(),
                    grace_until = COALESCE(grace_until, now() + interval '7 days'),
                    claim_token = NULL,
                    claimed_until = NULL,
                    row_version = row_version + 1,
                    updated_at = now()
                WHERE id = ? AND status IN ('PAST_DUE', 'EXPIRED', 'TERMINATED')
                """, subscriptionId);
    }

    /** 人工操作审计。与仓库既有 30+ 处写法一致：在同一事务里直接写表。 */
    void insertAudit(
            UUID tenantId,
            UUID actorUserId,
            String action,
            String resourceId,
            String detailJson) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, ?, ?, 'SUBSCRIPTION', ?, 'SUCCESS', ?::jsonb)
                """, tenantId, actorUserId, action, resourceId, detailJson);
    }

    private String buildWhere(
            String status, String planCode, UUID userId, List<Object> parameters) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null && !status.isBlank()) {
            where.append(" AND subscription.status = ?");
            parameters.add(status);
        }
        if (planCode != null && !planCode.isBlank()) {
            where.append(" AND plan.code = ?");
            parameters.add(planCode);
        }
        if (userId != null) {
            where.append(" AND subscription.user_id = ?");
            parameters.add(userId);
        }
        return where.toString();
    }

    private static final RowMapper<AdminSubscriptionView> ADMIN_MAPPER = (result, rowNumber) ->
            new AdminSubscriptionView(
                    result.getObject("id", UUID.class),
                    result.getObject("tenant_id", UUID.class),
                    result.getObject("user_id", UUID.class),
                    result.getString("user_email"),
                    result.getObject("package_plan_id", UUID.class),
                    result.getString("plan_code"),
                    result.getString("plan_name"),
                    result.getString("status"),
                    result.getBoolean("auto_renew"),
                    result.getBoolean("cancel_at_period_end"),
                    instant(result, "current_period_start"),
                    instant(result, "current_period_end"),
                    instant(result, "next_charge_at"),
                    instant(result, "grace_until"),
                    result.getInt("consecutive_failures"),
                    result.getInt("renewal_count"),
                    result.getObject("last_charged_amount", Long.class),
                    result.getString("last_skip_reason"),
                    // 是否正在被某个调度实例处理。后台最需要这个信号：
                    // 它区分「任务没跑」与「正在跑」。
                    result.getObject("claim_token", UUID.class) != null);

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * 管理端订阅视图。
     *
     * <p>{@code charged} 是「是否正在被调度器处理」而不是「扣过款没有」：租约在订阅行上，
     * 因此一个非空租约恰好等价于「有实例正在处理它」。
     */
    record AdminSubscriptionView(
            UUID id,
            UUID tenantId,
            UUID userId,
            String userEmail,
            UUID packagePlanId,
            String planCode,
            String planName,
            String status,
            boolean autoRenew,
            boolean cancelAtPeriodEnd,
            Instant currentPeriodStart,
            Instant currentPeriodEnd,
            Instant nextChargeAt,
            Instant graceUntil,
            int consecutiveFailures,
            int renewalCount,
            Long lastChargedAmount,
            String lastSkipReason,
            boolean charged) {
    }

    record PageResult<T>(List<T> items, long total, int page, int size) {
    }
}
