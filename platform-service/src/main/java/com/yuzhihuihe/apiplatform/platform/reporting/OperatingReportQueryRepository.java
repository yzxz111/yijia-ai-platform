package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OperatingReportQueryRepository {

    private final JdbcTemplate jdbc;

    OperatingReportQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<DailyRow> load(Filter filter) {
        StringBuilder filters = new StringBuilder();
        List<Object> arguments = new ArrayList<>(List.of(filter.from(), filter.to()));
        append(filters, arguments, "tenant_id", filter.tenantId());
        append(filters, arguments, "user_id", filter.userId());
        append(filters, arguments, "package_plan_version_id", filter.packagePlanVersionId());
        append(filters, arguments, "logical_model", filter.logicalModel());
        append(filters, arguments, "upstream_model", filter.upstreamModel());
        append(filters, arguments, "channel", filter.channel());
        append(filters, arguments, "provider", filter.provider());
        append(filters, arguments, "billing_mode", filter.billingMode());
        append(filters, arguments, "currency", filter.currency());
        return jdbc.query(aggregateSql(filter.grain(), filters.toString()),
                (result, row) -> new DailyRow(
                result.getObject("business_date", LocalDate.class),
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getObject("package_plan_version_id", UUID.class),
                result.getString("logical_model"), result.getString("upstream_model"),
                result.getString("channel"), result.getString("provider"),
                result.getString("billing_mode"), result.getString("currency"),
                result.getBigDecimal("confirmed_revenue_amount"),
                result.getBigDecimal("cash_in_amount"), result.getBigDecimal("refund_amount"),
                result.getBigDecimal("provider_cost_amount"),
                result.getBigDecimal("payment_provider_fee_amount"),
                result.getBigDecimal("refund_provider_fee_amount"),
                result.getBigDecimal("allocated_usage_revenue_amount"),
                result.getLong("input_tokens"), result.getLong("output_tokens"),
                result.getLong("total_tokens"), result.getLong("fact_count"),
                result.getLong("estimated_count"), result.getLong("missing_source_count")),
                arguments.toArray());
    }

    static String aggregateSql(Grain grain, String filters) {
        String period = switch (grain) {
            case DAY -> "business_date";
            case WEEK -> "date_trunc('week', business_date::timestamp)::date";
            case MONTH -> "date_trunc('month', business_date::timestamp)::date";
        };
        return """
                SELECT %s AS business_date,
                       NULL::uuid tenant_id, NULL::uuid user_id,
                       NULL::uuid package_plan_version_id,
                       NULL::varchar logical_model, NULL::varchar upstream_model,
                       NULL::varchar channel, NULL::varchar provider,
                       NULL::varchar billing_mode, min(currency) currency,
                       sum(confirmed_revenue_amount) confirmed_revenue_amount,
                       sum(cash_in_amount) cash_in_amount,
                       sum(refund_amount) refund_amount,
                       sum(provider_cost_amount) provider_cost_amount,
                       sum(payment_provider_fee_amount) payment_provider_fee_amount,
                       sum(refund_provider_fee_amount) refund_provider_fee_amount,
                       sum(allocated_usage_revenue_amount) allocated_usage_revenue_amount,
                       sum(input_tokens) input_tokens, sum(output_tokens) output_tokens,
                       sum(total_tokens) total_tokens, sum(fact_count) fact_count,
                       sum(estimated_count) estimated_count,
                       sum(missing_source_count) missing_source_count
                FROM platform.operating_daily_aggregate
                WHERE business_date >= ? AND business_date <= ? AND NOT needs_recompute%s
                GROUP BY 1
                ORDER BY 1
                LIMIT 366
                """.formatted(period, filters);
    }

    private static void append(
            StringBuilder sql, List<Object> arguments, String column, Object value) {
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" = ?");
        arguments.add(value);
    }

    enum Grain {
        DAY,
        WEEK,
        MONTH
    }

    record Filter(
            LocalDate from,
            LocalDate to,
            Grain grain,
            UUID tenantId,
            UUID userId,
            UUID packagePlanVersionId,
            String logicalModel,
            String upstreamModel,
            String channel,
            String provider,
            String billingMode,
            String currency) {
    }

    record DailyRow(
            LocalDate businessDate, UUID tenantId, UUID userId, UUID packagePlanVersionId,
            String logicalModel, String upstreamModel, String channel, String provider,
            String billingMode, String currency,
            BigDecimal confirmedRevenueAmount, BigDecimal cashInAmount, BigDecimal refundAmount,
            BigDecimal providerCostAmount, BigDecimal paymentProviderFeeAmount,
            BigDecimal refundProviderFeeAmount, BigDecimal allocatedUsageRevenueAmount,
            long inputTokens, long outputTokens, long totalTokens, long factCount,
            long estimatedCount, long missingSourceCount) {
    }
}
