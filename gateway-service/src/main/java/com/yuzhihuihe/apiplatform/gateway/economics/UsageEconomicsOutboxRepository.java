package com.yuzhihuihe.apiplatform.gateway.economics;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class UsageEconomicsOutboxRepository {

    private final DatabaseClient database;

    public UsageEconomicsOutboxRepository(DatabaseClient database) {
        this.database = database;
    }

    Mono<Long> insert(UUID eventId, String fingerprint, String payload, String payloadHash) {
        return database.sql("""
                        INSERT INTO gateway.usage_economics_outbox(
                            event_id, event_fingerprint, payload, payload_hash)
                        VALUES (:event_id, :fingerprint, CAST(:payload AS jsonb), :payload_hash)
                        ON CONFLICT DO NOTHING
                        """)
                .bind("event_id", eventId)
                .bind("fingerprint", fingerprint)
                .bind("payload", payload)
                .bind("payload_hash", payloadHash)
                .fetch()
                .rowsUpdated();
    }

    Mono<Boolean> existingMatches(UUID eventId, String fingerprint, String payloadHash) {
        return database.sql("""
                        SELECT event_id::text AS event_id, event_fingerprint, payload_hash
                        FROM gateway.usage_economics_outbox
                        WHERE event_id = :event_id OR event_fingerprint = :fingerprint
                        """)
                .bind("event_id", eventId)
                .bind("fingerprint", fingerprint)
                .map((row, metadata) -> eventId.toString().equals(row.get("event_id", String.class))
                        && fingerprint.equals(row.get("event_fingerprint", String.class))
                        && payloadHash.equals(row.get("payload_hash", String.class)))
                .one()
                .defaultIfEmpty(false);
    }

    Mono<UsageSnapshotRow> findUsageSnapshot(UUID usageLogId) {
        return database.sql("""
                        SELECT u.id, u.request_id, u.reservation_id, u.tenant_id, u.user_id,
                               u.package_instance_id, u.model_code, u.upstream_model,
                               u.provider_id, u.channel_id, u.input_tokens, u.output_tokens,
                               u.total_tokens, u.usage_estimated,
                               u.usage_dimension, u.usage_units,
                               -- Dimension-carrying: for a VIDEO_SECOND package this column
                               -- holds the SECONDS the package paid for (V41/V42), which is
                               -- the settled quantity in the dimension the usage row names.
                               q.package_settled_tokens,
                               q.billing_mode, q.wallet_settled_amount,
                               c.provider_price_id, c.currency AS provider_cost_currency,
                               c.provider_exchange_rate, c.provider_input_cost_amount,
                               c.provider_output_cost_amount, c.amount AS provider_total_cost_amount,
                               c.provider_original_input_cost_amount,
                               c.provider_original_output_cost_amount,
                               c.provider_original_cost_amount, c.provider_original_cost_currency
                        FROM gateway.usage_log u
                        JOIN gateway.quota_reservation q ON q.id = u.reservation_id
                        LEFT JOIN gateway.provider_cost_record c ON c.usage_log_id = u.id
                        WHERE u.id = :usage_log_id
                        """)
                .bind("usage_log_id", usageLogId)
                .map((row, metadata) -> new UsageSnapshotRow(
                        row.get("id", UUID.class),
                        row.get("request_id", String.class),
                        row.get("reservation_id", UUID.class),
                        row.get("tenant_id", UUID.class),
                        row.get("user_id", UUID.class),
                        row.get("package_instance_id", UUID.class),
                        row.get("model_code", String.class),
                        row.get("upstream_model", String.class),
                        row.get("provider_id", UUID.class),
                        row.get("channel_id", UUID.class),
                        number(row.get("input_tokens", Long.class)),
                        number(row.get("output_tokens", Long.class)),
                        number(row.get("total_tokens", Long.class)),
                        row.get("usage_dimension", String.class),
                        number(row.get("usage_units", Long.class)),
                        Boolean.TRUE.equals(row.get("usage_estimated", Boolean.class)),
                        row.get("billing_mode", String.class),
                        number(row.get("package_settled_tokens", Long.class)),
                        number(row.get("wallet_settled_amount", Long.class)),
                        row.get("provider_price_id", UUID.class),
                        trimmed(row.get("provider_cost_currency", String.class)),
                        row.get("provider_exchange_rate", BigDecimal.class),
                        row.get("provider_input_cost_amount", Long.class),
                        row.get("provider_output_cost_amount", Long.class),
                        row.get("provider_total_cost_amount", Long.class),
                        row.get("provider_original_input_cost_amount", BigDecimal.class),
                        row.get("provider_original_output_cost_amount", BigDecimal.class),
                        row.get("provider_original_cost_amount", BigDecimal.class),
                        trimmed(row.get("provider_original_cost_currency", String.class))))
                .one();
    }

    Mono<List<UsageEconomicsEvent.Stage>> findStages(UUID usageLogId) {
        return database.sql("""
                        SELECT stage_index, stage_type, logical_model_code, upstream_model,
                               provider_id, channel_id, input_tokens, output_tokens, total_tokens,
                               usage_estimated, usage_dimension, usage_units,
                               provider_price_id, provider_cost_currency,
                               provider_exchange_rate, provider_input_cost_amount,
                               provider_output_cost_amount, provider_cost_amount,
                               provider_original_input_cost_amount,
                               provider_original_output_cost_amount,
                               provider_original_cost_amount, provider_original_cost_currency,
                               cache_hit_tokens, cache_miss_tokens, cache_write_tokens,
                               reasoning_tokens, text_tokens, image_tokens, audio_tokens,
                               usage_source, provider_request_id, customer_billable,
                               provider_billable, reconciliation_status,
                               provider_uncached_input_cost_amount,
                               provider_cache_read_cost_amount, provider_cache_write_cost_amount,
                               provider_normal_output_cost_amount, provider_reasoning_cost_amount
                        FROM gateway.usage_stage WHERE usage_log_id = :usage_log_id
                        ORDER BY stage_index
                        """)
                .bind("usage_log_id", usageLogId)
                .map((row, metadata) -> new UsageEconomicsEvent.Stage(
                        requiredInt(row.get("stage_index", Integer.class)),
                        row.get("stage_type", String.class),
                        row.get("logical_model_code", String.class),
                        row.get("upstream_model", String.class),
                        row.get("provider_id", UUID.class),
                        row.get("channel_id", UUID.class),
                        number(row.get("input_tokens", Long.class)),
                        number(row.get("output_tokens", Long.class)),
                        number(row.get("total_tokens", Long.class)),
                        row.get("provider_price_id", UUID.class),
                        trimmed(row.get("provider_cost_currency", String.class)),
                        row.get("provider_exchange_rate", BigDecimal.class),
                        row.get("provider_input_cost_amount", Long.class),
                        row.get("provider_output_cost_amount", Long.class),
                        row.get("provider_cost_amount", Long.class),
                        row.get("provider_original_input_cost_amount", BigDecimal.class),
                        row.get("provider_original_output_cost_amount", BigDecimal.class),
                        row.get("provider_original_cost_amount", BigDecimal.class),
                        trimmed(row.get("provider_original_cost_currency", String.class)),
                        Boolean.TRUE.equals(row.get("usage_estimated", Boolean.class)),
                        row.get("provider_price_id", UUID.class) == null,
                        row.get("cache_hit_tokens", Long.class),
                        row.get("cache_miss_tokens", Long.class),
                        row.get("cache_write_tokens", Long.class),
                        row.get("reasoning_tokens", Long.class),
                        row.get("text_tokens", Long.class),
                        row.get("image_tokens", Long.class),
                        row.get("audio_tokens", Long.class),
                        row.get("usage_source", String.class),
                        row.get("provider_request_id", String.class),
                        Boolean.TRUE.equals(row.get("customer_billable", Boolean.class)),
                        Boolean.TRUE.equals(row.get("provider_billable", Boolean.class)),
                        row.get("reconciliation_status", String.class),
                        row.get("provider_uncached_input_cost_amount", Long.class),
                        row.get("provider_cache_read_cost_amount", Long.class),
                        row.get("provider_cache_write_cost_amount", Long.class),
                        row.get("provider_normal_output_cost_amount", Long.class),
                        row.get("provider_reasoning_cost_amount", Long.class),
                        dimension(row.get("usage_dimension", String.class)),
                        number(row.get("usage_units", Long.class))))
                .all()
                .collectList();
    }

    Flux<UUID> findPendingUsageIds(int limit) {
        return database.sql("""
                        SELECT usage.id
                        FROM gateway.usage_log usage
                        JOIN gateway.quota_reservation reservation
                          ON reservation.id = usage.reservation_id
                        WHERE reservation.status = 'SETTLED'
                          AND usage.economics_event_pending
                        ORDER BY usage.created_at, usage.id
                        LIMIT :batch_size
                        """)
                .bind("batch_size", limit)
                .map((row, metadata) -> row.get("id", UUID.class))
                .all();
    }

    Mono<Long> markEnqueued(UUID usageId) {
        return database.sql("""
                        UPDATE gateway.usage_log
                        SET economics_event_pending = false
                        WHERE id = :usage_id AND economics_event_pending
                        """)
                .bind("usage_id", usageId)
                .fetch()
                .rowsUpdated();
    }

    private static long number(Long value) {
        return value == null ? 0 : value;
    }

    /** V49 之前写入的行没有维度，而 TOKEN 是当时唯一存在的维度。 */
    private static String dimension(String value) {
        return value == null ? UsageEconomicsEvent.DIMENSION_TOKEN : value;
    }

    private static int requiredInt(Integer value) {
        if (value == null) {
            throw new IllegalStateException("usage_economics_stage_index_missing");
        }
        return value;
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    record UsageSnapshotRow(
            UUID id,
            String requestId,
            UUID reservationId,
            UUID tenantId,
            UUID userId,
            UUID packageId,
            String modelCode,
            String upstreamModel,
            UUID providerId,
            UUID channelId,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            String usageDimension,
            long usageUnits,
            boolean usageEstimated,
            String billingMode,
            long packageSettledTokens,
            long walletSettledAmount,
            UUID providerPriceId,
            String providerCostCurrency,
            BigDecimal providerExchangeRate,
            Long providerInputCostAmount,
            Long providerOutputCostAmount,
            Long providerTotalCostAmount,
            BigDecimal providerOriginalInputCostAmount,
            BigDecimal providerOriginalOutputCostAmount,
            BigDecimal providerOriginalCostAmount,
            String providerOriginalCostCurrency) {}
}
