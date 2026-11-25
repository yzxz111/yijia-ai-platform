package com.yuzhihuihe.apiplatform.platform.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AdminAnalyticsRepository {

    private final JdbcTemplate jdbc;

    AdminAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    SummaryCounts summary(Instant monthStart, Instant nextMonthStart) {
        Timestamp from = Timestamp.from(monthStart);
        Timestamp to = Timestamp.from(nextMonthStart);
        return jdbc.queryForObject(
                """
                SELECT count(*) AS registered_users,
                       count(*) FILTER (WHERE status = 'ACTIVE') AS active_users,
                       (SELECT count(DISTINCT order_id)
                        FROM platform.payment_transaction
                        WHERE status='SUCCEEDED' AND paid_at IS NOT NULL) AS paid_orders,
                       (SELECT count(*)
                        FROM platform.customer_order
                        WHERE created_at >= ? AND created_at < ?) AS current_month_orders,
                       (SELECT count(*)
                        FROM platform.customer_order
                        WHERE status = 'PAID' AND paid_at >= ? AND paid_at < ?) AS current_month_paid_orders,
                       (SELECT count(*)
                        FROM platform.customer_order
                        WHERE created_at >= ? AND created_at < ?
                          AND (status IN ('CLOSED', 'REFUNDED', 'PARTIALLY_REFUNDED')
                           OR EXISTS (
                               SELECT 1 FROM platform.refund r
                               WHERE r.order_id = platform.customer_order.id
                                 AND r.status = 'COMPLETED'
                                 AND r.completed_at >= ? AND r.completed_at < ?
                           ))) AS current_month_invalid_or_refunded
                FROM platform.app_user
                """,
                (result, row) ->
                        new SummaryCounts(
                                result.getLong("registered_users"),
                                result.getLong("active_users"),
                                result.getLong("paid_orders"),
                                result.getLong("current_month_orders"),
                                result.getLong("current_month_paid_orders"),
                                result.getLong("current_month_invalid_or_refunded")),
                from,
                to,
                from,
                to,
                from,
                to,
                from,
                to);
    }

    List<MonthlyUserCount> monthly(
            LocalDate firstMonth, LocalDate currentMonth, Instant from, Instant to) {
        return jdbc.query(
                """
                WITH month_buckets AS (
                    SELECT generate_series(
                            CAST(? AS date), CAST(? AS date), interval '1 month')::date AS month
                ),
                user_monthly AS (
                    SELECT date_trunc('month', created_at AT TIME ZONE 'Asia/Shanghai')::date AS month,
                           count(*) AS new_users
                    FROM platform.app_user
                    WHERE created_at >= ? AND created_at < ?
                    GROUP BY 1
                )
                SELECT month_buckets.month,
                       COALESCE(user_monthly.new_users, 0) AS new_users
                FROM month_buckets
                LEFT JOIN user_monthly USING (month)
                ORDER BY month_buckets.month
                """,
                (result, row) ->
                        new MonthlyUserCount(
                                result.getObject("month", LocalDate.class),
                                result.getLong("new_users")),
                firstMonth,
                currentMonth,
                Timestamp.from(from),
                Timestamp.from(to));
    }

    record SummaryCounts(
            long registeredUsers,
            long activeUsers,
            long paidOrders,
            long currentMonthOrders,
            long currentMonthPaidOrders,
            long currentMonthInvalidOrRefunded) {}

    record MonthlyUserCount(LocalDate month, long newUsers) {}
}
