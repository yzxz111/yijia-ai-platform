package com.yuzhihuihe.apiplatform.platform.subscription;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 读取续费将要成交的版本与价格。
 *
 * <p>订阅绑的是**计划**而不是版本（设计 §7.3）：版本会随发布演进——这个仓库的常态，
 * V61/V63/V64 都是「发布新版本 + 把旧版本置 RETIRED」。因此每次续费都必须重新解析
 * 当前 PUBLISHED 版本，而不能把开通时的版本 ID 记在订阅上，否则一个计划换版本之后
 * 所有存量订阅都会因为指向已 RETIRED 的版本而失败。
 *
 * <p>只读工具，不写任何东西：真正的下单与扣款仍走既有的
 * {@code OrderApplicationService} 与 {@code BalanceOrderPaymentService}。
 */
@Component
class SubscriptionPlanReader {

    private final JdbcTemplate jdbc;

    SubscriptionPlanReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 解析某计划当前 PUBLISHED 且价格 &gt; 0 的版本。
     *
     * <p>返回 {@code null} 表示该计划当前不可续费（已归档、无已发布版本、或价格为 0）。
     * 调用方据此跳过并记录原因，而不是抛异常——「计划下架」是正常的运营动作。
     */
    PlanPrice readPrice(UUID packagePlanVersionId) {
        return jdbc.query("""
                        SELECT version.id, version.price_amount, version.currency, version.duration_days,
                               version.usage_dimension,
                               plan.code AS plan_code, plan.status AS plan_status
                        FROM platform.package_plan_version version
                        JOIN platform.package_plan plan ON plan.id = version.package_plan_id
                        WHERE version.id = ?
                          AND version.status = 'PUBLISHED'
                          AND plan.status = 'ACTIVE'
                        """, (result, row) -> new PlanPrice(
                        result.getObject("id", UUID.class),
                        result.getLong("price_amount"),
                        result.getString("currency"),
                        result.getInt("duration_days"),
                        result.getString("usage_dimension"),
                        result.getString("plan_code")),
                packagePlanVersionId)
                .stream().findFirst().orElse(null);
    }

    /** 解析某计划当前 PUBLISHED 版本的 ID（用于「按计划续费」）。 */
    UUID readPublishedVersionId(UUID packagePlanId) {
        return jdbc.query("""
                        SELECT version.id
                        FROM platform.package_plan_version version
                        JOIN platform.package_plan plan ON plan.id = version.package_plan_id
                        WHERE version.package_plan_id = ?
                          AND version.status = 'PUBLISHED'
                          AND plan.status = 'ACTIVE'
                        """, (result, row) -> result.getObject("id", UUID.class), packagePlanId)
                .stream().findFirst().orElse(null);
    }

    /**
     * 由版本 ID 反查计划 ID。
     *
     * <p>开通流程手里只有用户点选的那个版本 ID，而订阅绑的是**计划**（设计 §7.3），
     * 因此需要这一次反查。{@code PackageSnapshot} 不含计划 ID，所以不能从快照里拿。
     */
    UUID readPlanId(UUID packagePlanVersionId) {
        return jdbc.query("""
                        SELECT package_plan_id FROM platform.package_plan_version WHERE id = ?
                        """, (result, row) -> result.getObject("package_plan_id", UUID.class),
                packagePlanVersionId)
                .stream().findFirst().orElse(null);
    }

    /** 计划名称（展示用）。查不到返回 null，由调用方决定回退文案。 */
    String readPlanName(UUID packagePlanId) {
        return jdbc.query("""
                        SELECT name FROM platform.package_plan WHERE id = ?
                        """, (result, row) -> result.getString("name"), packagePlanId)
                .stream().findFirst().orElse(null);
    }

    /**
     * 由订单号反查订单 ID。
     *
     * <p>{@code OrderApplicationService.OrderCreated} 只回传订单号，而账期行对
     * {@code customer_order} 的外键用的是 {@code order_id}，因此需要这一次反查。
     */
    UUID readOrderId(String orderNo) {
        return jdbc.query("""
                        SELECT id FROM platform.customer_order WHERE order_no = ?
                        """, (result, row) -> result.getObject("id", UUID.class), orderNo)
                .stream().findFirst().orElse(null);
    }

    record PlanPrice(
            UUID packagePlanVersionId,
            long amount,
            String currency,
            int durationDays,
            /** 该版本的用量维度（TOKEN / VIDEO_SECOND / IMAGE）；活跃实例守卫按它收窄。 */
            String usageDimension,
            String planCode) {
    }
}
