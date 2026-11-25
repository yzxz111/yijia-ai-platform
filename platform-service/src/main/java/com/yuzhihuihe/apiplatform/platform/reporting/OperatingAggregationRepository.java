package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OperatingAggregationRepository {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final String CLAIM_DIRTY_DATE_SQL = """
            WITH candidate AS (
                SELECT DISTINCT business_date
                FROM platform.operating_daily_aggregate
                WHERE needs_recompute
                ORDER BY business_date
            ), locked_date AS (
                SELECT business_date
                FROM candidate
                WHERE pg_try_advisory_xact_lock(
                    1357911, business_date - DATE '2000-01-01')
                ORDER BY business_date
                LIMIT 1
            )
            SELECT aggregate.business_date
            FROM platform.operating_daily_aggregate aggregate
            JOIN locked_date USING (business_date)
            WHERE aggregate.needs_recompute
            ORDER BY aggregate.id
            FOR UPDATE OF aggregate
            """;

    private final JdbcTemplate jdbc;

    OperatingAggregationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void markDirty(LocalDate date, String currency) {
        jdbc.update("""
                INSERT INTO platform.operating_daily_aggregate(
                    business_date, timezone, currency, needs_recompute)
                VALUES (?, 'Asia/Shanghai', ?, true)
                ON CONFLICT ON CONSTRAINT uq_operating_daily_dimensions
                DO UPDATE SET needs_recompute=true, updated_at=now()
                """, date, currency);
    }

    LocalDate claimDirtyDate() {
        return jdbc.query(CLAIM_DIRTY_DATE_SQL, result -> result.next()
                ? result.getObject("business_date", LocalDate.class) : null);
    }

    List<FactRow> facts(LocalDate date) {
        Instant from = date.atStartOfDay(ZONE).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZONE).toInstant();
        return jdbc.query("""
                SELECT tenant_id, user_id, package_plan_version_id,
                       logical_model, upstream_model, channel, provider,
                       billing_mode, currency, sign,
                       confirmed_revenue_amount, cash_in_amount, refund_amount,
                       provider_input_cost_amount, provider_output_cost_amount,
                       payment_provider_fee_amount, refund_provider_fee_amount,
                       allocated_usage_revenue_amount, input_tokens, output_tokens,
                       usage_dimension, usage_units,
                       estimated, missing_source
                FROM platform.operating_fact
                WHERE recognized_at >= ? AND recognized_at < ?
                """, (result, row) -> new FactRow(
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getObject("package_plan_version_id", UUID.class),
                result.getString("logical_model"), result.getString("upstream_model"),
                result.getString("channel"), result.getString("provider"),
                result.getString("billing_mode"), result.getString("currency"),
                result.getInt("sign"), result.getBigDecimal("confirmed_revenue_amount"),
                result.getBigDecimal("cash_in_amount"), result.getBigDecimal("refund_amount"),
                result.getBigDecimal("provider_input_cost_amount"),
                result.getBigDecimal("provider_output_cost_amount"),
                result.getBigDecimal("payment_provider_fee_amount"),
                result.getBigDecimal("refund_provider_fee_amount"),
                result.getBigDecimal("allocated_usage_revenue_amount"),
                result.getLong("input_tokens"), result.getLong("output_tokens"),
                result.getString("usage_dimension"), result.getLong("usage_units"),
                result.getBoolean("estimated"), result.getBoolean("missing_source")),
                Timestamp.from(from), Timestamp.from(to));
    }

    void replace(LocalDate date, List<AggregateRow> rows) {
        jdbc.update("DELETE FROM platform.operating_daily_aggregate WHERE business_date=?", date);
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO platform.operating_daily_aggregate(
                  business_date, timezone, tenant_id, user_id, package_plan_version_id,
                  logical_model, upstream_model, channel, provider, billing_mode, currency,
                  usage_dimension, usage_units,
                  confirmed_revenue_amount, cash_in_amount, refund_amount,
                  provider_cost_amount, payment_provider_fee_amount,
                  refund_provider_fee_amount, allocated_usage_revenue_amount, profit_amount,
                  input_tokens, output_tokens, total_tokens, fact_count, estimated_count,
                  missing_source_count, needs_recompute, recomputed_at, updated_at)
                VALUES (?, 'Asia/Shanghai', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, false, now(), now())
                """, rows.stream().map(this::arguments).toList());
    }

    private Object[] arguments(AggregateRow row) {
        return new Object[]{row.businessDate(), row.tenantId(), row.userId(),
                row.packagePlanVersionId(), row.logicalModel(), row.upstreamModel(),
                row.channel(), row.provider(), row.billingMode(), row.currency(),
                row.usageDimension(), row.usageUnits(),
                row.confirmedRevenueAmount(), row.cashInAmount(), row.refundAmount(),
                row.providerCostAmount(), row.paymentProviderFeeAmount(),
                row.refundProviderFeeAmount(), row.allocatedUsageRevenueAmount(),
                row.profitAmount(), row.inputTokens(), row.outputTokens(), row.totalTokens(),
                row.factCount(), row.estimatedCount(), row.missingSourceCount()};
    }

    record FactRow(
            UUID tenantId, UUID userId, UUID packagePlanVersionId,
            String logicalModel, String upstreamModel, String channel, String provider,
            String billingMode, String currency, int sign,
            BigDecimal confirmedRevenueAmount, BigDecimal cashInAmount, BigDecimal refundAmount,
            BigDecimal providerInputCostAmount, BigDecimal providerOutputCostAmount,
            BigDecimal paymentProviderFeeAmount, BigDecimal refundProviderFeeAmount,
            BigDecimal allocatedUsageRevenueAmount,
            long inputTokens, long outputTokens, String usageDimension, long usageUnits,
            boolean estimated, boolean missingSource) {
    }

    record AggregateRow(
            LocalDate businessDate, UUID tenantId, UUID userId, UUID packagePlanVersionId,
            String logicalModel, String upstreamModel, String channel, String provider,
            String billingMode, String currency, String usageDimension, long usageUnits,
            BigDecimal confirmedRevenueAmount, BigDecimal cashInAmount, BigDecimal refundAmount,
            BigDecimal providerCostAmount, BigDecimal paymentProviderFeeAmount,
            BigDecimal refundProviderFeeAmount, BigDecimal allocatedUsageRevenueAmount,
            BigDecimal profitAmount, long inputTokens, long outputTokens, long totalTokens,
            long factCount, long estimatedCount, long missingSourceCount) {
    }
}
