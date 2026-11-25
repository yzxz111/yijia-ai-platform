package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
class OperatingReportExportRepository {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;

    OperatingReportExportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long count(Filter filter, long limit) {
        Query query = query(filter, "SELECT 1", " LIMIT ?");
        query.arguments().add(limit);
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM (" + query.sql() + ") bounded",
                Long.class, query.arguments().toArray());
        return count == null ? 0 : count;
    }

    void stream(Filter filter, Consumer<ExportRow> sink) {
        Query query = query(filter, """
                SELECT (fact.recognized_at AT TIME ZONE 'Asia/Shanghai')::date business_date,
                       fact.source_service, fact.source_event_id, fact.id::text fact_id,
                       fact.event_type, fact.currency, fact.confirmed_revenue_amount,
                       fact.cash_in_amount, fact.refund_amount,
                       fact.provider_input_cost_amount + fact.provider_output_cost_amount provider_cost,
                       fact.payment_provider_fee_amount, fact.refund_provider_fee_amount,
                       fact.allocated_usage_revenue_amount, fact.estimated, fact.missing_source,
                       coalesce((SELECT string_agg(DISTINCT item.difference_type, '|' ORDER BY item.difference_type)
                         FROM platform.operating_reconciliation_item item
                         WHERE item.source_service=fact.source_service
                           AND item.source_event_id=fact.source_event_id), 'MATCHED') reconciliation_status
                """, " ORDER BY fact.recognized_at, fact.id LIMIT 50001");
        jdbc.query(query.sql(), (RowCallbackHandler) result -> sink.accept(new ExportRow(
                result.getObject("business_date", LocalDate.class),
                result.getString("source_service"), result.getString("source_event_id"),
                result.getString("fact_id"), result.getString("event_type"),
                result.getString("currency"), cents(result.getBigDecimal("confirmed_revenue_amount")),
                cents(result.getBigDecimal("cash_in_amount")),
                cents(result.getBigDecimal("refund_amount")),
                cents(result.getBigDecimal("provider_cost")),
                cents(result.getBigDecimal("payment_provider_fee_amount")),
                cents(result.getBigDecimal("refund_provider_fee_amount")),
                result.getBigDecimal("allocated_usage_revenue_amount"),
                result.getBoolean("estimated"), result.getBoolean("missing_source"),
                result.getString("reconciliation_status"))), query.arguments().toArray());
    }

    private static Query query(Filter filter, String select, String suffix) {
        StringBuilder sql = new StringBuilder(select)
                .append(" FROM platform.operating_fact fact WHERE ")
                .append("fact.recognized_at >= ? AND fact.recognized_at < ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(filter.from().atStartOfDay(ZONE).toInstant()));
        arguments.add(Timestamp.from(filter.to().plusDays(1).atStartOfDay(ZONE).toInstant()));
        append(sql, arguments, "tenant_id", filter.tenantId());
        append(sql, arguments, "user_id", filter.userId());
        append(sql, arguments, "package_plan_version_id", filter.packagePlanVersionId());
        append(sql, arguments, "logical_model", filter.logicalModel());
        append(sql, arguments, "upstream_model", filter.upstreamModel());
        append(sql, arguments, "channel", filter.channel());
        append(sql, arguments, "provider", filter.provider());
        append(sql, arguments, "billing_mode", filter.billingMode());
        append(sql, arguments, "currency", filter.currency());
        return new Query(sql.append(suffix).toString(), arguments);
    }

    private static void append(
            StringBuilder sql, List<Object> arguments, String column, Object value) {
        if (value == null) {
            return;
        }
        sql.append(" AND fact.").append(column).append(" = ?");
        arguments.add(value);
    }

    private static long cents(BigDecimal value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("financial_amount_is_not_integer_cents", exception);
        }
    }

    record Filter(
            LocalDate from, LocalDate to, UUID tenantId, UUID userId,
            UUID packagePlanVersionId, String logicalModel, String upstreamModel,
            String channel, String provider, String billingMode, String currency) {
    }

    record ExportRow(
            LocalDate businessDate, String sourceService, String sourceEventId,
            String factId, String eventType, String currency, long confirmedRevenueCents,
            long cashInCents, long refundCents, long providerCostCents,
            long paymentFeeCents, long refundFeeCents,
            BigDecimal allocatedUsageRevenueExact, boolean estimated,
            boolean missingSource, String reconciliationStatus) {
    }

    private record Query(String sql, List<Object> arguments) {
    }
}
