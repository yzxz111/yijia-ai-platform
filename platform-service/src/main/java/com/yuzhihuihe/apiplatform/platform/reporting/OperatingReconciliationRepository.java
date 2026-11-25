package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OperatingReconciliationRepository {

    static final String SOURCE_SQL = """
            SELECT source_type, source_service, source_event_id, currency, amount, expected_count,
                   target_request_id, target_order_no, target_refund_id, target_recharge_no
            FROM (
              SELECT 'PACKAGE_ORDER' source_type, 'platform' source_service,
                     'customer-order:' || id || ':paid:v1' source_event_id, currency,
                     amount::numeric amount, 1 expected_count, paid_at occurred_at,
                     NULL::varchar target_request_id, order_no target_order_no,
                     NULL::varchar target_refund_id, NULL::varchar target_recharge_no
              FROM platform.customer_order WHERE status IN ('PAID','REFUNDED','PARTIALLY_REFUNDED')
              UNION ALL
              SELECT 'MODEL_USAGE_LEDGER', 'platform',
                     'wallet-reservation:' || ledger.business_id || ':settled:v1', 'CNY',
                     abs(ledger.amount_delta)::numeric, 1, ledger.created_at,
                     reservation.request_id, NULL, NULL, NULL
              FROM platform.balance_ledger ledger
              LEFT JOIN platform.wallet_usage_reservation reservation
                ON reservation.id=ledger.business_id
              WHERE ledger.entry_type='MODEL_USAGE'
              UNION ALL
              SELECT 'RECHARGE_LEDGER', 'platform',
                     'recharge-order:' || business_id || ':paid:v1', 'CNY',
                     paid_amount::numeric, 1, created_at, NULL, NULL, NULL, business_no
              FROM platform.balance_ledger WHERE entry_type='RECHARGE'
              UNION ALL
              SELECT 'COMPLETED_REFUND', 'platform',
                     'refund:' || refund.id || ':completed:v1', 'CNY', refund.amount::numeric,
                     1, refund.completed_at, NULL, customer_order.order_no, refund.refund_no, NULL
              FROM platform.refund refund
              JOIN platform.customer_order customer_order ON customer_order.id=refund.order_id
              WHERE refund.status='COMPLETED'
              UNION ALL
              SELECT 'PROVIDER_FEE_BILL', source_service, source_event_id, currency,
                     sum(payment_provider_fee_amount + refund_provider_fee_amount), count(*)::int,
                     min(occurred_at), max(request_id), NULL, NULL, NULL
              FROM platform.operating_fact
              WHERE event_type IN ('PAYMENT_PROVIDER_FEE_ACTUAL','REFUND_PROVIDER_FEE_ACTUAL')
              GROUP BY source_service, source_event_id, currency
            ) source
            WHERE occurred_at >= ? AND occurred_at < ?
            ORDER BY occurred_at, source_event_id
            """;

    static final String EVIDENCE_INSERT_SQL = """
            INSERT INTO platform.operating_reconciliation_item(
              run_id,difference_type,source_service,source_event_id,currency,
              source_amount,fact_amount,difference_amount,evidence)
            VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb))
            """;

    static final String FACT_HEALTH_SQL = """
            SELECT coalesce(sum(confirmed_revenue_amount * sign + cash_in_amount * sign
                       + refund_amount + provider_input_cost_amount * sign
                       + provider_output_cost_amount * sign + payment_provider_fee_amount * sign
                       + refund_provider_fee_amount * sign),0) financial,
                   coalesce(sum(allocated_usage_revenue_amount * sign),0) allocated
            FROM platform.operating_fact WHERE recognized_at >= ? AND recognized_at < ?
            """;

    private final JdbcTemplate jdbc;

    OperatingReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<SourceRow> sources(Instant from, Instant to) {
        return jdbc.query(SOURCE_SQL, (result, row) -> new SourceRow(
                result.getString("source_type"), result.getString("source_service"),
                result.getString("source_event_id"), result.getString("currency"),
                result.getBigDecimal("amount"), result.getInt("expected_count"),
                targetEvidence(result.getString("target_request_id"),
                        result.getString("target_order_no"),
                        result.getString("target_refund_id"),
                        result.getString("target_recharge_no"))),
                Timestamp.from(from), Timestamp.from(to));
    }

    List<FactRow> facts(Instant from, Instant to) {
        return jdbc.query("""
                SELECT source_service, source_event_id, currency,
                       sum(confirmed_revenue_amount + cash_in_amount + refund_amount
                           + provider_input_cost_amount + provider_output_cost_amount
                           + payment_provider_fee_amount + refund_provider_fee_amount) amount,
                       sum(allocated_usage_revenue_amount) allocated_amount,
                       count(*) fact_count, bool_or(estimated) estimated,
                       bool_or(missing_source) missing_source
                FROM platform.operating_fact
                WHERE occurred_at >= ? AND occurred_at < ?
                GROUP BY source_service, source_event_id, currency
                """, (result, row) -> new FactRow(
                result.getString("source_service"), result.getString("source_event_id"),
                result.getString("currency"), result.getBigDecimal("amount"),
                result.getBigDecimal("allocated_amount"), result.getInt("fact_count"),
                result.getBoolean("estimated"), result.getBoolean("missing_source")),
                Timestamp.from(from), Timestamp.from(to));
    }

    HealthRow health(Instant from, Instant to) {
        Map<String, Object> fact = jdbc.queryForMap(
                FACT_HEALTH_SQL, Timestamp.from(from), Timestamp.from(to));
        Map<String, Object> aggregate = jdbc.queryForMap("""
                SELECT coalesce(sum(confirmed_revenue_amount + cash_in_amount + refund_amount
                           + provider_cost_amount + payment_provider_fee_amount
                           + refund_provider_fee_amount),0) financial,
                       coalesce(sum(allocated_usage_revenue_amount),0) allocated
                FROM platform.operating_daily_aggregate
                WHERE business_date >= (? AT TIME ZONE 'Asia/Shanghai')::date
                  AND business_date < (? AT TIME ZONE 'Asia/Shanghai')::date
                  AND NOT needs_recompute
                """, Timestamp.from(from), Timestamp.from(to));
        return new HealthRow(
                decimal(fact.get("financial")), decimal(aggregate.get("financial")),
                decimal(fact.get("allocated")), decimal(aggregate.get("allocated")));
    }

    Map<String, GatewayReceiptRow> gatewayReceipts(List<String> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",",
                java.util.Collections.nCopies(eventIds.size(), "?"));
        return jdbc.query("""
                SELECT receipt.source_event_id, receipt.payload_hash, count(fact.id) fact_count,
                       max(fact.request_id) request_id
                FROM platform.operating_fact_event_receipt receipt
                LEFT JOIN platform.operating_fact fact
                  ON fact.source_service=receipt.source_service
                 AND fact.source_event_id=receipt.source_event_id
                WHERE receipt.source_service='gateway' AND receipt.source_event_id IN (%s)
                GROUP BY receipt.source_event_id, receipt.payload_hash
                """.formatted(placeholders), result -> {
            Map<String, GatewayReceiptRow> rows = new HashMap<>();
            while (result.next()) {
                rows.put(result.getString("source_event_id"), new GatewayReceiptRow(
                        result.getString("payload_hash"), result.getInt("fact_count"),
                        result.getString("request_id")));
            }
            return rows;
        }, eventIds.toArray());
    }

    void save(Instant from, Instant to, List<ItemRow> items) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.operating_reconciliation_run(
                  id,reconciliation_type,range_start,range_end,status,item_count,
                  difference_count,started_at,completed_at)
                VALUES (?,'OPERATING_FACT',?,?,'COMPLETED',?,?,now(),now())
                """, runId, Timestamp.from(from), Timestamp.from(to), items.size(), items.size());
        for (ItemRow item : items) {
            jdbc.update(EVIDENCE_INSERT_SQL,
                    runId, item.differenceType(), item.sourceService(), item.sourceEventId(),
                    item.currency(), item.sourceAmount().abs(), item.factAmount().abs(),
                    item.differenceAmount(), item.evidenceJson());
        }
    }

    private static Map<String, Object> targetEvidence(
            String requestId, String orderNo, String refundId, String rechargeNo) {
        Map<String, Object> evidence = new HashMap<>();
        if (!blank(requestId)) {
            evidence.put("requestId", requestId);
        }
        if (!blank(orderNo)) {
            evidence.put("orderNo", orderNo);
        }
        if (!blank(refundId)) {
            evidence.put("refundId", refundId);
        }
        if (!blank(rechargeNo)) {
            evidence.put("rechargeNo", rechargeNo);
        }
        return Map.copyOf(evidence);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal
                ? decimal : new BigDecimal(value.toString());
    }

    record SourceRow(
            String sourceType, String sourceService, String sourceEventId,
            String currency, BigDecimal amount, int expectedFactCount,
            Map<String, Object> evidence) {
    }

    record FactRow(
            String sourceService, String sourceEventId, String currency,
            BigDecimal amount, BigDecimal allocatedUsageRevenueAmount,
            int factCount, boolean estimated, boolean missingSource) {
    }

    record HealthRow(
            BigDecimal factFinancialAmount, BigDecimal aggregateFinancialAmount,
            BigDecimal factAllocatedUsageRevenue,
            BigDecimal aggregateAllocatedUsageRevenue) {
    }

    record GatewayReceiptRow(String payloadHash, int factCount, String requestId) {
    }

    record ItemRow(
            String differenceType, String sourceService, String sourceEventId,
            String currency, BigDecimal sourceAmount, BigDecimal factAmount,
            BigDecimal differenceAmount, String evidenceJson) {
    }
}
