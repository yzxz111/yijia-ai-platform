package com.yuzhihuihe.apiplatform.gateway.usage;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.economics.ProviderCostCalculator;
import com.yuzhihuihe.apiplatform.gateway.economics.ProviderCostCalculator.CostSnapshot;
import com.yuzhihuihe.apiplatform.gateway.economics.ProviderCostCalculator.UsageBreakdown;
import com.yuzhihuihe.apiplatform.gateway.economics.UsageEconomicsOutboxService;
import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class UsageStageWriter {

    private static final int MAX_PROVIDER_REQUEST_ID_LENGTH = 512;
    private static final int MAX_RAW_USAGE_BYTES = 16 * 1024;

    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactions;
    private final ProviderCostCalculator costCalculator;
    private final UsageEconomicsOutboxService economicsOutbox;

    @Autowired
    public UsageStageWriter(
            DatabaseClient databaseClient,
            TransactionalOperator transactions,
            ProviderCostCalculator costCalculator,
            UsageEconomicsOutboxService economicsOutbox) {
        this.databaseClient = databaseClient;
        this.transactions = transactions;
        this.costCalculator = costCalculator;
        this.economicsOutbox = economicsOutbox;
    }

    public UsageStageWriter(
            DatabaseClient databaseClient,
            TransactionalOperator transactions,
            ProviderCostCalculator costCalculator) {
        this(databaseClient, transactions, costCalculator, null);
    }

    public UsageStageWriter(DatabaseClient databaseClient, TransactionalOperator transactions) {
        this(databaseClient, transactions, new ProviderCostCalculator(databaseClient));
    }

    public UsageStageWriter(DatabaseClient databaseClient) {
        this(databaseClient, null, new ProviderCostCalculator(databaseClient));
    }

    public Mono<Void> write(ChatCompletionService.UsageEntry entry) {
        return Mono.deferContextual(context -> write(
                entry,
                !context.getOrDefault(
                        ChatCompletionService.SUPPRESS_USAGE_ECONOMICS_CONTEXT_KEY, false)));
    }

    private Mono<Void> write(ChatCompletionService.UsageEntry entry, boolean enqueueEconomics) {
        UUID parentId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        Mono<Void> work = insertParent(parentId, entry, !enqueueEconomics)
                .flatMap(inserted -> {
                    if (!inserted) {
                        return Mono.empty();
                    }
                    if (!entry.stages().isEmpty()) {
                        return Flux.fromIterable(entry.stages())
                            .index()
                            .concatMap(indexed -> writeStage(
                                    parentId, indexed.getT1().intValue(),
                                    indexed.getT2(), occurredAt))
                            .then(enqueue(parentId, enqueueEconomics));
                    }
                    return writeDirectStage(parentId, entry, occurredAt)
                            .then(enqueue(parentId, enqueueEconomics));
                });
        return transactions == null ? work : transactions.transactional(work);
    }

    private Mono<Void> enqueue(UUID parentId, boolean enabled) {
        return !enabled || economicsOutbox == null
                ? Mono.empty() : economicsOutbox.enqueueUsage(parentId);
    }

    private Mono<Boolean> insertParent(
            UUID id, ChatCompletionService.UsageEntry entry, boolean economicsEventPending) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO gateway.usage_log(
                            id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                            package_instance_id, model_id, model_code, upstream_model,
                            provider_id, channel_id,
                            input_tokens, output_tokens, total_tokens, usage_dimension, usage_units,
                            usage_estimated, stream,
                            first_token_ms, duration_ms, platform_added_latency_ms,
                            http_status, outcome, error_category, error_summary,
                            generation_status, finish_reason,
                            effective_max_output_tokens, ceiling_source, attempt_count,
                            economics_event_pending)
                        VALUES (
                            :id, :request_id, :reservation_id, :tenant_id, :user_id, :api_key_id,
                            :package_id, :model_id, :model_code, :upstream_model,
                            :provider_id, :channel_id,
                            :input_tokens, :output_tokens, :total_tokens, 'TOKEN', :usage_units,
                            :estimated, :stream,
                            :first_token_ms, :duration_ms, :platform_added_latency_ms,
                            :http_status, :outcome, :error_category, :error_summary,
                            :generation_status, :finish_reason,
                            :effective_max_output_tokens, :ceiling_source, :attempt_count,
                            :economics_event_pending)
                        ON CONFLICT (request_id) DO NOTHING
                        """)
                .bind("id", id)
                .bind("request_id", entry.requestId())
                .bind("reservation_id", entry.reservationId())
                .bind("tenant_id", entry.tenantId())
                .bind("user_id", entry.userId())
                .bind("api_key_id", entry.apiKeyId())
                .bind("model_id", entry.modelId())
                .bind("model_code", entry.modelCode())
                .bind("provider_id", entry.providerId())
                .bind("channel_id", entry.channelId())
                .bind("input_tokens", entry.usage().inputTokens())
                .bind("output_tokens", entry.usage().outputTokens())
                .bind("total_tokens", entry.usage().totalTokens())
                // 聊天路径以 token 计价，并把它明确写出来：在 TOKEN 维度下，
                // 合规的单位数量就是 token 总量，因此由该行构建的 V3 事件
                // 可以同时携带两者，而不需要推断出其中任何一个。
                .bind("usage_units", entry.usage().totalTokens())
                .bind("estimated", entry.quality() == QuotaService.UsageQuality.ESTIMATED)
                .bind("stream", entry.stream())
                .bind("duration_ms", entry.durationMs())
                .bind("platform_added_latency_ms", entry.platformAddedLatencyMs())
                .bind("http_status", entry.httpStatus())
                .bind("outcome", entry.outcome())
                .bind("economics_event_pending", economicsEventPending);
        spec = bindNullable(spec, "package_id", entry.packageInstanceId(), UUID.class);
        spec = bindNullable(spec, "upstream_model", entry.upstreamModel(), String.class);
        spec = bindNullable(spec, "first_token_ms", entry.firstTokenMs(), Long.class);
        spec = bindNullable(spec, "error_category", entry.errorCategory(), String.class);
        spec = bindNullable(spec, "error_summary", boundedSummary(entry.errorSummary()), String.class);
        // T5/T3（V116）：把"这一次生成到底完成了没有"和"上限是谁定的"落库。
        // 全部走 bindNullable——历史语义上它们是可空的，而把"未知"写成 0 或空串
        // 会让报表显示一个从未被采纳的上限。
        spec = bindNullable(spec, "generation_status", entry.generationStatus(), String.class);
        spec = bindNullable(spec, "finish_reason", boundedFinishReason(entry.finishReason()), String.class);
        spec = bindNullable(spec, "effective_max_output_tokens",
                entry.effectiveMaxOutputTokens(), Long.class);
        spec = bindNullable(spec, "ceiling_source", entry.ceilingSource(), String.class);
        spec = bindNullable(spec, "attempt_count", entry.attemptCount(), Integer.class);
        return spec.fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /**
     * {@code finish_reason} 的长度上限。
     *
     * <p>列宽是 {@code varchar(32)}，而写入方（{@code ChatStreamSettlement}）已经做过
     * 形状与长度过滤。这里再兜一次，是因为**一次超长写入会丢掉整条用量行**——
     * 那比丢一个结束原因严重得多：用量行是计费凭据。宁可截断这一个诊断字段，
     * 也不能让整行插不进去。
     */
    private static String boundedFinishReason(String reason) {
        if (reason == null) {
            return null;
        }
        String stripped = reason.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.length() <= 32 ? stripped : stripped.substring(0, 32);
    }

    private Mono<CostSnapshot> calculate(ChatCompletionService.UsageStage stage, Instant occurredAt) {
        return costCalculator.calculate(
                stage.providerId(), stage.modelId(),
                new UsageBreakdown(
                        stage.usage().inputTokens(), stage.usage().outputTokens(),
                        stage.usage().cacheHitTokens(), stage.usage().cacheMissTokens(),
                        stage.usage().cacheWriteTokens(), stage.usage().reasoningTokens()),
                occurredAt);
    }

    private Mono<Void> writeStage(
            UUID usageLogId, int stageIndex,
            ChatCompletionService.UsageStage stage, Instant occurredAt) {
        if (!stage.providerBillable()) {
            return insertStage(usageLogId, stageIndex, stage, null);
        }
        return calculate(stage, occurredAt)
                .flatMap(price -> insertStage(usageLogId, stageIndex, stage, price));
    }

    private Mono<Void> writeDirectStage(
            UUID usageLogId, ChatCompletionService.UsageEntry entry, Instant occurredAt) {
        ChatCompletionService.UsageStage stage = directStage(entry);
        if (!stage.providerBillable()) {
            return insertStage(usageLogId, 0, stage, null);
        }
        return calculate(stage, occurredAt)
                .flatMap(cost -> insertStage(usageLogId, 0, stage, cost)
                        .then(cost.missing()
                                ? Mono.empty()
                                : insertProviderCost(usageLogId, entry, cost)));
    }

    private ChatCompletionService.UsageStage directStage(ChatCompletionService.UsageEntry entry) {
        boolean customerBillable = "SUCCEEDED".equals(entry.outcome());
        boolean providerBillable = customerBillable
                || entry.usage().source() != ProviderAdapter.UsageSource.LOCAL_ESTIMATED;
        return new ChatCompletionService.UsageStage(
                "FINAL_RESPONSE", entry.modelId(), entry.modelCode(), entry.upstreamModel(),
                entry.providerId(), entry.channelId(), entry.usage(), entry.quality(),
                entry.durationMs(), entry.outcome(), entry.errorCategory(),
                customerBillable, providerBillable);
    }

    private Mono<Void> insertStage(
            UUID usageLogId, int stageIndex,
            ChatCompletionService.UsageStage stage, CostSnapshot price) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO gateway.usage_stage(
                            id, usage_log_id, stage_index, stage_type, logical_model_code,
                            model_id, provider_id, channel_id, upstream_model,
                            input_tokens, output_tokens, total_tokens, usage_dimension, usage_units,
                            usage_estimated,
                            duration_ms, outcome, error_category,
                            provider_cost_amount, provider_cost_currency, price_estimated,
                            provider_price_id, provider_input_cost_amount, provider_output_cost_amount,
                            provider_original_input_cost_amount, provider_original_output_cost_amount,
                            provider_original_cost_amount, provider_original_cost_currency,
                            provider_exchange_rate,
                            provider_request_id, usage_source,
                            cache_hit_tokens, cache_miss_tokens, cache_write_tokens,
                            reasoning_tokens, text_tokens, image_tokens, audio_tokens,
                            raw_usage, customer_billable, provider_billable,
                            reconciliation_status,
                            provider_uncached_input_cost_amount,
                            provider_cache_read_cost_amount, provider_cache_write_cost_amount,
                            provider_normal_output_cost_amount, provider_reasoning_cost_amount)
                        VALUES (
                            :id, :usage_log_id, :stage_index, :stage_type, :logical_model_code,
                            :model_id, :provider_id, :channel_id, :upstream_model,
                            :input_tokens, :output_tokens, :total_tokens, 'TOKEN', :usage_units,
                            :usage_estimated,
                            :duration_ms, :outcome, :error_category,
                            :cost_amount, :cost_currency, :price_estimated,
                            :provider_price_id, :input_cost, :output_cost,
                            :original_input_cost, :original_output_cost,
                            :original_total_cost, :original_currency, :exchange_rate,
                            :provider_request_id, :usage_source,
                            :cache_hit_tokens, :cache_miss_tokens, :cache_write_tokens,
                            :reasoning_tokens, :text_tokens, :image_tokens, :audio_tokens,
                            :raw_usage::jsonb, :customer_billable, :provider_billable,
                            'PENDING', :uncached_input_cost, :cache_read_cost,
                            :cache_write_cost, :normal_output_cost, :reasoning_cost)
                        ON CONFLICT (usage_log_id, stage_index) DO NOTHING
                        """)
                .bind("id", UUID.randomUUID())
                .bind("usage_log_id", usageLogId)
                .bind("stage_index", stageIndex)
                .bind("stage_type", stage.stageType())
                .bind("logical_model_code", stage.logicalModelCode())
                .bind("model_id", stage.modelId())
                .bind("provider_id", stage.providerId())
                .bind("channel_id", stage.channelId())
                .bind("input_tokens", stage.usage().inputTokens())
                .bind("output_tokens", stage.usage().outputTokens())
                .bind("total_tokens", stage.usage().totalTokens())
                .bind("usage_units", stage.usage().totalTokens())
                .bind("usage_estimated", stage.quality() == QuotaService.UsageQuality.ESTIMATED)
                .bind("duration_ms", stage.durationMs())
                .bind("outcome", stage.outcome())
                .bind("price_estimated", price != null && price.estimated())
                .bind("usage_source", stage.usage().source().name())
                .bind("customer_billable", stage.customerBillable())
                .bind("provider_billable", stage.providerBillable());
        spec = bindNullable(spec, "upstream_model", stage.upstreamModel(), String.class);
        spec = bindNullable(spec, "error_category", stage.errorCategory(), String.class);
        spec = bindNullable(spec, "cost_amount", price == null ? null : price.totalAmount(), Long.class);
        spec = bindNullable(spec, "cost_currency",
                price == null || price.missing() ? null : "CNY", String.class);
        spec = bindNullable(spec, "provider_price_id",
                price == null ? null : price.providerPriceId(), UUID.class);
        spec = bindNullable(spec, "input_cost", price == null ? null : price.inputAmount(), Long.class);
        spec = bindNullable(spec, "output_cost", price == null ? null : price.outputAmount(), Long.class);
        spec = bindNullable(spec, "original_input_cost",
                price == null ? null : price.originalInputAmount(), BigDecimal.class);
        spec = bindNullable(spec, "original_output_cost",
                price == null ? null : price.originalOutputAmount(), BigDecimal.class);
        spec = bindNullable(spec, "original_total_cost",
                price == null ? null : price.originalTotalAmount(), BigDecimal.class);
        spec = bindNullable(spec, "original_currency",
                price == null ? null : price.currency(), String.class);
        spec = bindNullable(spec, "exchange_rate",
                price == null ? null : price.exchangeRate(), BigDecimal.class);
        spec = bindNullable(spec, "provider_request_id",
                boundedProviderRequestId(stage.usage().providerRequestId()), String.class);
        spec = bindNullable(spec, "cache_hit_tokens", stage.usage().cacheHitTokens(), Long.class);
        spec = bindNullable(spec, "cache_miss_tokens", stage.usage().cacheMissTokens(), Long.class);
        spec = bindNullable(spec, "cache_write_tokens", stage.usage().cacheWriteTokens(), Long.class);
        spec = bindNullable(spec, "reasoning_tokens", stage.usage().reasoningTokens(), Long.class);
        spec = bindNullable(spec, "text_tokens", stage.usage().textTokens(), Long.class);
        spec = bindNullable(spec, "image_tokens", stage.usage().imageTokens(), Long.class);
        spec = bindNullable(spec, "audio_tokens", stage.usage().audioTokens(), Long.class);
        spec = bindNullable(spec, "raw_usage", boundedRawUsage(stage.usage().rawUsage()), String.class);
        spec = bindNullable(spec, "uncached_input_cost",
                price == null ? null : price.uncachedInputAmount(), Long.class);
        spec = bindNullable(spec, "cache_read_cost",
                price == null ? null : price.cacheReadAmount(), Long.class);
        spec = bindNullable(spec, "cache_write_cost",
                price == null ? null : price.cacheWriteAmount(), Long.class);
        spec = bindNullable(spec, "normal_output_cost",
                price == null ? null : price.normalOutputAmount(), Long.class);
        spec = bindNullable(spec, "reasoning_cost",
                price == null ? null : price.reasoningAmount(), Long.class);
        return spec.fetch().rowsUpdated().then();
    }

    private Mono<Void> insertProviderCost(
            UUID usageLogId, ChatCompletionService.UsageEntry entry, CostSnapshot cost) {
        return databaseClient.sql("""
                        INSERT INTO gateway.provider_cost_record(
                            id, usage_log_id, provider_id, channel_id, model_code,
                            input_tokens, output_tokens, amount, currency, price_estimated,
                            provider_price_id, provider_input_cost_amount, provider_output_cost_amount,
                            provider_original_input_cost_amount, provider_original_output_cost_amount,
                            provider_original_cost_amount, provider_original_cost_currency,
                            provider_exchange_rate)
                        VALUES (
                            :id, :usage_log_id, :provider_id, :channel_id, :model_code,
                            :input_tokens, :output_tokens, :amount, 'CNY', :price_estimated,
                            :provider_price_id, :input_cost, :output_cost,
                            :original_input_cost, :original_output_cost,
                            :original_total_cost, :original_currency, :exchange_rate)
                        ON CONFLICT (usage_log_id) DO NOTHING
                        """)
                .bind("id", UUID.randomUUID())
                .bind("usage_log_id", usageLogId)
                .bind("provider_id", entry.providerId())
                .bind("channel_id", entry.channelId())
                .bind("model_code", entry.modelCode())
                .bind("input_tokens", entry.usage().inputTokens())
                .bind("output_tokens", entry.usage().outputTokens())
                .bind("amount", cost.totalAmount())
                .bind("price_estimated", cost.estimated())
                .bind("provider_price_id", cost.providerPriceId())
                .bind("input_cost", cost.inputAmount())
                .bind("output_cost", cost.outputAmount())
                .bind("original_input_cost", cost.originalInputAmount())
                .bind("original_output_cost", cost.originalOutputAmount())
                .bind("original_total_cost", cost.originalTotalAmount())
                .bind("original_currency", cost.currency())
                .bind("exchange_rate", cost.exchangeRate())
                .fetch().rowsUpdated().then();
    }

    private String boundedSummary(String summary) {
        if (summary == null || summary.length() <= 500) {
            return summary;
        }
        return summary.substring(0, 500);
    }

    private String boundedProviderRequestId(String providerRequestId) {
        return providerRequestId != null
                && providerRequestId.length() <= MAX_PROVIDER_REQUEST_ID_LENGTH
                ? providerRequestId : null;
    }

    private String boundedRawUsage(com.fasterxml.jackson.databind.JsonNode rawUsage) {
        if (rawUsage == null || !rawUsage.isObject()) {
            return null;
        }
        String json = rawUsage.toString();
        return json.getBytes(StandardCharsets.UTF_8).length <= MAX_RAW_USAGE_BYTES ? json : null;
    }

    private <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

}
