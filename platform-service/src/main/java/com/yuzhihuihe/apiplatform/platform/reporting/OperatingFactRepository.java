package com.yuzhihuihe.apiplatform.platform.reporting;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OperatingFactRepository {

    private static final String SOURCE = "platform";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    OperatingFactRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    Claim claim(String sourceEventId, String payloadHash) {
        int inserted = jdbc.update("""
                INSERT INTO platform.operating_fact_event_receipt(
                    source_service, source_event_id, payload_hash)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, SOURCE, sourceEventId, payloadHash);
        if (inserted == 1) {
            return Claim.CLAIMED;
        }
        String existing = jdbc.queryForObject("""
                SELECT payload_hash FROM platform.operating_fact_event_receipt
                WHERE source_service=? AND source_event_id=?
                """, String.class, SOURCE, sourceEventId);
        return payloadHash.equals(existing) ? Claim.REPLAYED : Claim.CONFLICT;
    }

    void insert(FactRow fact) {
        // 由平台自身钱包台账写入的事实按 token 计价：其
        // 合格单位数就是其 token 总量，这正是 ck_operating_fact_usage_units
        // 对 TOKEN 行所要求的。
        jdbc.update("""
                INSERT INTO platform.operating_fact(
                  source_service, source_event_id, line_no, event_type, occurred_at, recognized_at,
                  tenant_id, user_id, order_id, package_plan_version_id, payment_transaction_id,
                  refund_id, request_id, logical_model, provider, input_tokens, output_tokens,
                  total_tokens, usage_dimension, usage_units, billing_mode, sign, reversal,
                  confirmed_revenue_amount,
                  cash_in_amount, refund_amount, currency, missing_source, metadata)
                VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TOKEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, SOURCE, fact.sourceEventId(), fact.eventType(),
                Timestamp.from(fact.occurredAt()), Timestamp.from(fact.recognizedAt()),
                fact.tenantId(), fact.userId(), fact.orderId(), fact.packagePlanVersionId(),
                fact.paymentTransactionId(), fact.refundId(), fact.requestId(),
                fact.logicalModel(), fact.provider(), fact.inputTokens(), fact.outputTokens(),
                fact.totalTokens(), fact.inputTokens() + fact.outputTokens(),
                fact.billingMode(), fact.sign(), fact.reversal(),
                fact.confirmedRevenueAmount(), fact.cashInAmount(), fact.refundAmount(),
                fact.currency(), fact.missingSource(), json(fact.metadata()));
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "operating_fact_metadata_serialization_failed", exception);
        }
    }

    enum Claim {
        CLAIMED,
        REPLAYED,
        CONFLICT
    }

    record FactRow(
            String sourceEventId,
            String eventType,
            Instant occurredAt,
            Instant recognizedAt,
            UUID tenantId,
            UUID userId,
            UUID orderId,
            UUID packagePlanVersionId,
            UUID paymentTransactionId,
            UUID refundId,
            String requestId,
            String logicalModel,
            String provider,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            String billingMode,
            int sign,
            boolean reversal,
            boolean missingSource,
            long confirmedRevenueAmount,
            long cashInAmount,
            long refundAmount,
            String currency,
            Map<String, Object> metadata) {
    }
}
