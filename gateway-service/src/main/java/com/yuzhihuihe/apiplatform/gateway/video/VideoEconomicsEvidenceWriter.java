package com.yuzhihuihe.apiplatform.gateway.video;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.economics.UsageEconomicsEvent;
import com.yuzhihuihe.apiplatform.gateway.economics.UsageEconomicsOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 为已结算的视频生成写入运营经济性证据。
 *
 * <p>对话路径通过 {@code UsageStageWriter} 产生证据：一行
 * {@code usage_log}、它的 {@code usage_stage}、一条 {@code provider_cost_record}，以及
 * outbox 上的一条经济性事件，全部在同一个事务内完成。视频结算路径过去调用
 * {@code QuotaService.settle} 之后就此停住，所以一个亏钱的视频套餐完全不产生
 * 任何运营事实，{@code OperatingAlertService.evaluatePackageMargin} 也永远
 * 不会为它触发。
 *
 * <p><b>计量单位。</b>一次视频生成消耗的是秒数，而不是 token。把这些秒数
 * 写进 token 计数器是旧契约唯一接受的形态，它把一个按秒计的量记成了 token 数，
 * 运营报表随后又对其求和。本写入器改为显式命名维度：{@code usage_dimension = 'VIDEO_SECOND'}，
 * 计费秒数放在 {@code usage_units}，所有 token 计数器为零，并发出一个
 * {@code USAGE_ECONOMICS_V3} 事件携带同一维度。因此 token 不变式按维度分别成立，
 * token 报表永远不会看到「秒」。
 *
 * <p><b>恰好一次。</b>本方法只从 {@link VideoGenerationService#apply} 的
 * {@code updated == 1L} 分支调用，所以那条带守卫的终态 UPDATE 就是对该次写入的唯一认领。
 * 除此之外，每条语句都做了冲突保护
 * （{@code usage_log.request_id} 唯一、{@code usage_stage} 基于
 * {@code (usage_log_id, stage_index)}、{@code provider_cost_record} 基于 {@code usage_log_id}，
 * outbox 则基于 {@code event_id} / {@code event_fingerprint}），并且整组语句处于同一个
 * 事务中，因此重跑不可能重复写入或只写一半。某一行若带着
 * {@code economics_event_pending} 提交，会被
 * {@code UsageEconomicsOutboxService.enqueueRecoveredSettledUsage} 拾取。
 *
 * <p><b>成本几何、收益几何。</b>供应商成本是费率卡的每秒成本 × 计费秒数，读取自
 * {@code gateway.video_effective_cost} —— 也就是套餐发布毛利守卫据以定价的同一个视图，
 * 其中 {@code effective_unit_cost} 已经包含了重试余量（V45）。这些秒数按费率卡价格计算的
 * 零售价值会作为定价依据一并记录；平台根据已结算的秒数自行分摊套餐的实际收入，
 * 因为套餐的价格是平台的口径，而不是网关的口径。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class VideoEconomicsEvidenceWriter {

    private static final Logger log = LoggerFactory.getLogger(VideoEconomicsEvidenceWriter.class);

    /** 视频「秒」自始至终所采用的计量维度。 */
    static final String DIMENSION = UsageEconomicsEvent.DIMENSION_VIDEO_SECOND;

    /** V49 为视频生成所允许的 stage 类型。 */
    static final String STAGE_TYPE = "VIDEO_GENERATION";

    private static final int STAGE_INDEX = 0;
    private static final String CURRENCY = "CNY";

    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactions;
    private final UsageEconomicsOutboxService economicsOutbox;
    private final ObjectMapper objectMapper;

    VideoEconomicsEvidenceWriter(
            DatabaseClient databaseClient,
            TransactionalOperator transactions,
            UsageEconomicsOutboxService economicsOutbox,
            ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.transactions = transactions;
        this.economicsOutbox = economicsOutbox;
        this.objectMapper = objectMapper;
    }

    /**
     * 为一个已进入终态的视频任务写入证据。
     *
     * @param task 终态转换之前读到的任务行
     * @param terminalStatus 带守卫的 UPDATE 所写入的状态（SUCCEEDED/FAILED/CANCELLED）
     * @param billedSeconds 向客户计费的秒数；该值为零时完全跳过证据写入，
     *        因为一次什么都没消耗的生成既没有数量、也没有成本和收入可报告
     */
    Mono<Void> write(VideoTaskRepository.TaskRow task, String terminalStatus, int billedSeconds) {
        if (billedSeconds <= 0) {
            return Mono.empty();
        }
        UUID usageLogId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        Mono<Void> work = resolveModelId(task.modelCode())
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "video_evidence_model_missing:" + task.modelCode())))
                .flatMap(modelId -> resolveRate(task)
                        .defaultIfEmpty(VideoRate.UNPRICED)
                        .flatMap(price -> insertUsageLog(
                                        usageLogId, task, modelId, terminalStatus, billedSeconds,
                                        occurredAt)
                                .filter(inserted -> inserted)
                                .flatMap(inserted -> insertStage(
                                                usageLogId, task, modelId, terminalStatus,
                                                billedSeconds, occurredAt, price)
                                        .then(insertProviderCost(
                                                usageLogId, task, billedSeconds, price))
                                        .then(economicsOutbox.enqueueUsage(usageLogId))
                                        .doOnSuccess(ignored -> log.info(
                                                "video_economics_evidence_written requestId={} "
                                                        + "dimension={} units={} providerCost={}",
                                                task.requestId(), DIMENSION, billedSeconds,
                                                price.totalAmount(billedSeconds)))))
                        .then());
        return transactions.transactional(work);
    }

    private Mono<UUID> resolveModelId(String modelCode) {
        return databaseClient.sql("""
                        SELECT COALESCE(
                            (SELECT model_id FROM gateway.model_alias WHERE alias = :model_code),
                            (SELECT id FROM gateway.model WHERE code = :model_code)) AS model_id
                        """)
                .bind("model_code", modelCode)
                .map((row, metadata) -> row.get("model_id", UUID.class))
                .one();
    }

    /**
     * 该别名与分辨率档位当前适用的每秒价格。
     *
     * <p>结果为空意味着该别名没有已发布的视频费率卡行。这是真实的运营故障，
     * 但并不是隐瞒整个事实的理由：生成确实发生了，平台也确实为此付了钱。证据会带着
     * {@code price_missing} 写入——平台把它记为来源缺失并据此告警——
     * 而不是伪造一个零成本。
     */
    private Mono<VideoRate> resolveRate(VideoTaskRepository.TaskRow task) {
        return databaseClient.sql("""
                        SELECT effective_unit_cost, provider_unit_cost, unit_price, retry_rate,
                               price_version, resolution_tier
                        FROM gateway.video_effective_cost
                        WHERE model_code = :model_code AND resolution_tier = :resolution_tier
                        """)
                .bind("model_code", task.modelCode())
                .bind("resolution_tier", task.resolutionTier())
                .map((row, metadata) -> new VideoRate(
                        row.get("effective_unit_cost", Long.class),
                        row.get("provider_unit_cost", Long.class),
                        row.get("unit_price", Long.class),
                        row.get("retry_rate", BigDecimal.class),
                        row.get("price_version", String.class),
                        row.get("resolution_tier", String.class)))
                .one();
    }

    private Mono<Boolean> insertUsageLog(
            UUID id,
            VideoTaskRepository.TaskRow task,
            UUID modelId,
            String terminalStatus,
            int billedSeconds,
            Instant occurredAt) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO gateway.usage_log(
                            id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                            package_instance_id, model_id, model_code, upstream_model,
                            provider_id, channel_id,
                            input_tokens, output_tokens, total_tokens,
                            usage_dimension, usage_units,
                            usage_estimated, stream, duration_ms, http_status, outcome,
                            error_category, economics_event_pending)
                        VALUES (
                            :id, :request_id, :reservation_id, :tenant_id, :user_id, :api_key_id,
                            :package_instance_id, :model_id, :model_code, :upstream_model,
                            :provider_id, :channel_id,
                            0, 0, 0,
                            :usage_dimension, :usage_units,
                            false, false, :duration_ms, 200, :outcome,
                            :error_category, false)
                        ON CONFLICT (request_id) DO NOTHING
                        """)
                .bind("id", id)
                .bind("request_id", task.requestId())
                .bind("reservation_id", task.reservationId())
                .bind("tenant_id", task.tenantId())
                .bind("user_id", task.userId())
                .bind("api_key_id", task.apiKeyId())
                .bind("package_instance_id", task.packageInstanceId())
                .bind("model_id", modelId)
                .bind("model_code", task.modelCode())
                .bind("upstream_model", task.upstreamModel())
                .bind("provider_id", task.providerId())
                .bind("channel_id", task.channelId())
                .bind("usage_dimension", DIMENSION)
                .bind("usage_units", billedSeconds)
                .bind("duration_ms", elapsedMs(task, occurredAt))
                .bind("outcome", terminalStatus);
        spec = bindNullable(spec, "error_category", task.errorCategory(), String.class);
        return spec.fetch().rowsUpdated().map(rows -> rows > 0);
    }

    private Mono<Void> insertStage(
            UUID usageLogId,
            VideoTaskRepository.TaskRow task,
            UUID modelId,
            String terminalStatus,
            int billedSeconds,
            Instant occurredAt,
            VideoRate price) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO gateway.usage_stage(
                            id, usage_log_id, stage_index, stage_type, logical_model_code,
                            model_id, provider_id, channel_id, upstream_model,
                            input_tokens, output_tokens, total_tokens,
                            usage_dimension, usage_units, usage_estimated,
                            duration_ms, outcome, error_category,
                            provider_cost_amount, provider_cost_currency, price_estimated,
                            provider_input_cost_amount, provider_output_cost_amount,
                            usage_source, raw_usage,
                            customer_billable, provider_billable, reconciliation_status)
                        VALUES (
                            :id, :usage_log_id, :stage_index, :stage_type, :logical_model_code,
                            :model_id, :provider_id, :channel_id, :upstream_model,
                            0, 0, 0,
                            :usage_dimension, :usage_units, false,
                            :duration_ms, :outcome, :error_category,
                            :provider_cost_amount, :provider_cost_currency, :price_estimated,
                            :provider_input_cost_amount, :provider_output_cost_amount,
                            'PROVIDER_ACTUAL', CAST(:raw_usage AS jsonb),
                            true, true, 'NOT_APPLICABLE')
                        ON CONFLICT (usage_log_id, stage_index) DO NOTHING
                        """)
                .bind("id", UUID.randomUUID())
                .bind("usage_log_id", usageLogId)
                .bind("stage_index", STAGE_INDEX)
                .bind("stage_type", STAGE_TYPE)
                .bind("logical_model_code", task.modelCode())
                .bind("model_id", modelId)
                .bind("provider_id", task.providerId())
                .bind("channel_id", task.channelId())
                .bind("upstream_model", task.upstreamModel())
                .bind("usage_dimension", DIMENSION)
                .bind("usage_units", billedSeconds)
                .bind("duration_ms", elapsedMs(task, occurredAt))
                .bind("outcome", terminalStatus)
                .bind("price_estimated", price.unpriced())
                .bind("raw_usage", pricingBasis(task, billedSeconds, price));
        spec = bindNullable(spec, "error_category", task.errorCategory(), String.class);
        // 全部按秒成本都是供应商对已生成秒数的收费；它被放在 output 桶中，因为视频没有
        // input/output token 之分，而 ck_usage_stage_cost_breakdown 要求两者与总额对上。
        spec = price.unpriced()
                ? spec.bindNull("provider_cost_amount", Long.class)
                        .bindNull("provider_cost_currency", String.class)
                        .bindNull("provider_input_cost_amount", Long.class)
                        .bindNull("provider_output_cost_amount", Long.class)
                : spec.bind("provider_cost_amount", price.totalAmount(billedSeconds))
                        .bind("provider_cost_currency", CURRENCY)
                        .bind("provider_input_cost_amount", 0L)
                        .bind("provider_output_cost_amount", price.totalAmount(billedSeconds));
        return spec.fetch().rowsUpdated().then();
    }

    private Mono<Void> insertProviderCost(
            UUID usageLogId, VideoTaskRepository.TaskRow task, int billedSeconds, VideoRate price) {
        if (price.unpriced()) {
            // 没有费率卡行就意味着没有金额可记；杜撰一个金额就是伪造成本。
            // stage 与事件改为携带 price_missing。
            return Mono.empty();
        }
        return databaseClient.sql("""
                        INSERT INTO gateway.provider_cost_record(
                            id, usage_log_id, provider_id, channel_id, model_code,
                            input_tokens, output_tokens, amount, currency, price_estimated,
                            provider_input_cost_amount, provider_output_cost_amount)
                        VALUES (
                            :id, :usage_log_id, :provider_id, :channel_id, :model_code,
                            0, 0, :amount, :currency, false,
                            0, :amount)
                        ON CONFLICT (usage_log_id) DO NOTHING
                        """)
                .bind("id", UUID.randomUUID())
                .bind("usage_log_id", usageLogId)
                .bind("provider_id", task.providerId())
                .bind("channel_id", task.channelId())
                .bind("model_code", task.modelCode())
                .bind("amount", price.totalAmount(billedSeconds))
                .bind("currency", CURRENCY)
                .fetch().rowsUpdated().then();
    }

    /**
     * 本行定价所依据的基础，作为证据保留在 stage 上。
     *
     * <p>这是唯一能记录已结算秒数之费率卡零售价值的地方：平台自行分摊套餐的
     * 实际收入，而事件契约不携带网关提供的零售金额。把依据保留在这里，意味着
     * 成本、费率卡价格以及它们来自哪个版本可以一起被审计。
     */
    private String pricingBasis(
            VideoTaskRepository.TaskRow task, int billedSeconds, VideoRate price) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("billedSeconds", billedSeconds);
        basis.put("resolutionTier", task.resolutionTier());
        basis.put("priced", !price.unpriced());
        basis.put("priceVersion", price.priceVersion());
        basis.put("providerUnitCost", price.unitCost());
        basis.put("retryRate", price.retryRate());
        basis.put("unitPrice", price.unitPrice());
        basis.put("providerCostAmount", price.unpriced() ? null : price.totalAmount(billedSeconds));
        basis.put("retailAmount", price.retailAmount(billedSeconds));
        try {
            return objectMapper.writeValueAsString(basis);
        } catch (Exception error) {
            throw new IllegalStateException("video_economics_pricing_basis_failed", error);
        }
    }

    /**
     * 从提交到观测到终态的那次轮询之间的挂钟毫秒数。
     *
     * <p>这不是视频时长：视频时长是 {@code billed_seconds}。这是网关实际等待的时间，
     * 也正是 {@code duration_ms} 在其他所有地方的含义。
     */
    private long elapsedMs(VideoTaskRepository.TaskRow task, Instant occurredAt) {
        if (task.submittedAt() == null) {
            return 0;
        }
        return Math.max(0, Duration.between(task.submittedAt(), occurredAt).toMillis());
    }

    private <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    /** 本次证据定价所依据的费率卡行。 */
    private record VideoRate(
            Long effectiveUnitCost,
            Long unitCost,
            Long unitPrice,
            BigDecimal retryRate,
            String priceVersion,
            String resolutionTier) {

        /** 被路由到的别名与档位没有已发布的视频费率卡行。 */
        static final VideoRate UNPRICED = new VideoRate(null, null, null, null, null, null);

        boolean unpriced() {
            return effectiveUnitCost == null || unitCost == null;
        }

        long totalAmount(int billedSeconds) {
            return effectiveUnitCost * billedSeconds;
        }

        Long retailAmount(int billedSeconds) {
            return unitPrice == null ? null : unitPrice * billedSeconds;
        }
    }
}
