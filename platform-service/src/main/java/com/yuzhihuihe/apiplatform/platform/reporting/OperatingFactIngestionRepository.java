package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OperatingFactIngestionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    OperatingFactIngestionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    Claim claim(UUID eventId, String payloadHash) {
        int inserted = jdbc.update("""
                INSERT INTO platform.operating_fact_event_receipt(
                    source_service, source_event_id, payload_hash)
                VALUES ('gateway', ?, ?)
                ON CONFLICT (source_service, source_event_id) DO NOTHING
                """, eventId.toString(), payloadHash);
        if (inserted == 1) {
            return Claim.CLAIMED;
        }
        String existing = jdbc.queryForObject("""
                SELECT payload_hash FROM platform.operating_fact_event_receipt
                WHERE source_service='gateway' AND source_event_id=?
                """, String.class, eventId.toString());
        return payloadHash.equals(existing) ? Claim.REPLAYED : Claim.CONFLICT;
    }

    boolean tenantUserExists(UUID tenantId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform.app_user WHERE tenant_id=? AND id=?)",
                Boolean.class, tenantId, userId));
    }

    WalletRow walletSnapshot(UUID tenantId, UUID userId, String requestId) {
        return jdbc.query("""
                SELECT reservation.settled_amount, reservation.actual_input_tokens,
                       reservation.actual_output_tokens
                FROM platform.wallet_usage_reservation reservation
                WHERE reservation.tenant_id=? AND reservation.user_id=? AND reservation.request_id=?
                  AND reservation.status='SETTLED'
                  AND EXISTS (SELECT 1 FROM platform.balance_ledger ledger
                              WHERE ledger.business_type='MODEL_USAGE'
                                AND ledger.business_id=reservation.id
                                AND ledger.paid_amount=reservation.settled_amount)
                """, (result, row) -> new WalletRow(
                result.getLong(1), result.getLong(2), result.getLong(3)),
                tenantId, userId, requestId).stream().findFirst().orElse(null);
    }

    /**
     * 这次用量所属套餐的**额度**，单位与事件自己的单位一致。
     *
     * <p>额度是承载维度的：一个 {@code VIDEO_SECOND} 套餐的
     * {@code total_token_quota} 存的是秒数，而 **方案 C** 之后一个版本可能同时卖
     * 视频秒与音频秒——那种版本的音频秒在 {@code audio_seconds_quota} 那一列里。
     * 因此这里必须按**事件的维度**取额度，而不是一律取主列：用视频秒去当音频秒
     * 的分母不会让任何东西失败，只会把一份套餐收入按错误的比例摊到每一次
     * 语音调用上（收入被高估或低估，取决于两个额度谁大）。
     *
     * <p>判据与发布守卫、{@code PublishedPlanMarginMonitor} 以及网关侧的
     * {@code LockedPackage.usesSecondBucket} 逐字相同：一个纯音频版本
     * （主维度就是 {@code AUDIO_SECOND}）的音频秒在主列里，没有第二份额度。
     *
     * <p>额度取不到（0 或 NULL）时**不猜**：{@code packageAllocationTotal} 会把这次
     * 归属记为 0 收入并留下一条日志，而不是拿一个错误的单位去做除法。
     *
     * @param usageDimension 本次事件的维度（V3 事件才有；V1/V2 是 TOKEN）
     */
    PackageAllocationRow packageAllocation(
            UUID packageInstanceId, UUID tenantId, UUID userId, String usageDimension) {
        if (packageInstanceId == null) {
            return null;
        }
        return jdbc.query("""
                SELECT CASE WHEN paid_order.id IS NOT NULL THEN paid_order.amount ELSE 0 END,
                       CASE WHEN ? = 'AUDIO_SECOND'
                                 AND instance.usage_dimension <> 'AUDIO_SECOND'
                            THEN COALESCE((paid_order.package_snapshot->>'audioSecondsQuota')::bigint,
                                          instance.audio_seconds_quota)
                            ELSE COALESCE((paid_order.package_snapshot->>'totalTokenQuota')::bigint,
                                          instance.total_token_quota) END,
                       CASE WHEN paid_order.id IS NOT NULL THEN 'PAID_ORDER'
                            ELSE grant_request.source || '_GRANT' END,
                       instance.package_plan_version_id
                FROM gateway.package_instance instance
                JOIN platform.grant_request grant_request
                  ON grant_request.id=instance.grant_request_id
                LEFT JOIN platform.customer_order paid_order
                  ON grant_request.source='ORDER'
                 AND grant_request.business_no='order:' || paid_order.order_no
                 AND paid_order.tenant_id=instance.tenant_id
                 AND paid_order.user_id=instance.user_id
                 AND paid_order.package_plan_version_id=instance.package_plan_version_id
                 AND paid_order.status IN ('PAID','PARTIALLY_REFUNDED','REFUNDED')
                WHERE instance.id=? AND instance.tenant_id=? AND instance.user_id=?
                """, (result, row) -> new PackageAllocationRow(
                result.getBigDecimal(1), result.getObject(2, Long.class), result.getString(3),
                result.getObject(4, UUID.class)),
                usageDimension, packageInstanceId, tenantId, userId).stream().findFirst().orElse(null);
    }

    void insertAll(List<FactRow> facts) {
        for (FactRow fact : facts) {
            insert(fact);
        }
    }

    private void insert(FactRow fact) {
        int inserted = jdbc.update("""
                INSERT INTO platform.operating_fact(
                  id, source_service, source_event_id, line_no, event_type, occurred_at, recognized_at,
                  tenant_id, user_id, package_plan_version_id, request_id,
                  logical_model, upstream_model, channel, provider,
                  input_tokens, output_tokens, total_tokens, usage_dimension, usage_units,
                  billing_mode, currency,
                  confirmed_revenue_amount, provider_input_cost_amount, provider_output_cost_amount,
                  allocated_usage_revenue_amount, estimated, missing_source, metadata)
                VALUES (?,'gateway',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'CNY',?,?,?,?,?,?,CAST(? AS jsonb))
                """, fact.id(), fact.eventId().toString(), fact.lineNo(),
                "PROVIDER_USAGE_COST", Timestamp.from(fact.recognizedAt()),
                Timestamp.from(fact.recognizedAt()), fact.tenantId(), fact.userId(),
                fact.packagePlanVersionId(), fact.requestId(), fact.logicalModel(),
                fact.upstreamModel(), fact.channel(), fact.provider(), fact.inputTokens(),
                fact.outputTokens(), fact.totalTokens(), fact.usageDimension(), fact.usageUnits(),
                fact.billingMode(),
                fact.confirmedRevenueAmount(), fact.providerInputCostAmount(),
                fact.providerOutputCostAmount(), fact.allocatedUsageRevenueAmount(),
                fact.estimated(), fact.missingSource(), json(fact.metadata()));
        if (inserted != 1) {
            throw new IllegalStateException("operating_fact_insert_failed");
        }
    }

    BigDecimal providerCostP95(String model, String provider, Instant before) {
        return jdbc.queryForObject("""
                SELECT percentile_cont(0.95) WITHIN GROUP (
                         ORDER BY provider_input_cost_amount + provider_output_cost_amount)
                FROM platform.operating_fact
                WHERE event_type='PROVIDER_USAGE_COST' AND logical_model=? AND provider=?
                  AND missing_source=false AND recognized_at >= ?
                  AND recognized_at < ?
                """, BigDecimal.class, model, provider,
                Timestamp.from(before.minus(7, ChronoUnit.DAYS)), Timestamp.from(before));
    }

    void audit(UUID eventId, String payloadHash, String reason) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                  action, resource_type, resource_id, outcome, detail)
                VALUES ('OPERATING_FACT_INGEST_REJECTED','USAGE_ECONOMICS_EVENT',?,'FAILURE',
                        jsonb_build_object('payloadHash',?,'reason',?))
                """, string(eventId), payloadHash, reason);
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "operating_fact_metadata_serialization_failed", error);
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    enum Claim {
        CLAIMED,
        REPLAYED,
        CONFLICT
    }

    record WalletRow(long settledAmount, long inputTokens, long outputTokens) {
    }

    /**
     * @param packageUnitQuota 这次用量要摊的那份额度，单位由调用方传入的
     *        事件维度决定。可为 null：一个声称 {@code AUDIO_SECOND} 而实例没有
     *        音频额度的用量是自相矛盾的（正常路径下预留在网关就被拒了），
     *        此时归属无法计算，调用方记为 0 收入并告警，而不是拿一个错误的
     *        分母去除。
     */
    record PackageAllocationRow(
            BigDecimal priceAmount,
            Long packageUnitQuota,
            String source,
            UUID packagePlanVersionId) {
    }

    record FactRow(
            UUID id, UUID eventId, int lineNo, Instant recognizedAt,
            UUID tenantId, UUID userId, UUID packagePlanVersionId,
            String requestId, String logicalModel, String upstreamModel,
            String channel, String provider, long inputTokens, long outputTokens,
            long totalTokens, long usageUnits, String usageDimension, String billingMode,
            BigDecimal confirmedRevenueAmount,
            BigDecimal providerInputCostAmount, BigDecimal providerOutputCostAmount,
            BigDecimal allocatedUsageRevenueAmount, boolean estimated,
            boolean missingSource, Map<String, Object> metadata) {

        /**
         * 未声明维度的行就是 token 行：此时 {@code usage_units} 就是
         * token 总数，这正是数据库检查所强制的不变式。
         */
        FactRow(
                UUID id, UUID eventId, int lineNo, Instant recognizedAt,
                UUID tenantId, UUID userId, UUID packagePlanVersionId,
                String requestId, String logicalModel, String upstreamModel,
                String channel, String provider, long inputTokens, long outputTokens,
                long totalTokens, String billingMode, BigDecimal confirmedRevenueAmount,
                BigDecimal providerInputCostAmount, BigDecimal providerOutputCostAmount,
                BigDecimal allocatedUsageRevenueAmount, boolean estimated,
                boolean missingSource, Map<String, Object> metadata) {
            this(id, eventId, lineNo, recognizedAt, tenantId, userId, packagePlanVersionId,
                    requestId, logicalModel, upstreamModel, channel, provider, inputTokens,
                    outputTokens, totalTokens, totalTokens, "TOKEN", billingMode,
                    confirmedRevenueAmount, providerInputCostAmount, providerOutputCostAmount,
                    allocatedUsageRevenueAmount, estimated, missingSource, metadata);
        }
    }
}
