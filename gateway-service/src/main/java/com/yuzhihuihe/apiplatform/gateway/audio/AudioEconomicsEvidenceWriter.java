package com.yuzhihuihe.apiplatform.gateway.audio;

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
 * 为已结算的音频生成写入运营经济性证据。
 *
 * <p>与 {@code VideoEconomicsEvidenceWriter}、{@code ImageEconomicsEvidenceWriter}
 * 同形，并补上音频这一族此前完全缺失的一环：{@link AudioGenerationService}
 * 在结算之后就此停住，所以一个亏钱的音频套餐完全不产生任何运营事实，
 * {@code OperatingAlertService.evaluatePackageMargin} 也永远不会为它触发。
 * 这正是 {@code ImageGenerationService:161-163} 为图片点名过的那个缺陷
 * （「一个悄悄不写证据的写入器」），而音频与之完全同类。
 *
 * <p><b>计量单位是秒，但维度不是视频。</b>一次音频调用消耗的是秒数，写进
 * token 计数器就是把秒记成 token。本写入器显式命名维度
 * {@code usage_dimension = 'AUDIO_SECOND'}，计费秒数放在 {@code usage_units}，
 * 所有 token 计数器为零，并发出一个携带同一维度的 {@code USAGE_ECONOMICS_V3} 事件。
 *
 * <p><b>为什么不能复用 VIDEO_SECOND。</b>两者单位相同，而成本相差两个数量级：
 * 一个视频秒是 69 分，一个音频秒是 1 分（ASR/TTS）到 14 分（换口型）。
 * 合并维度会让运营报表把一段视频的秒数与一次配音的秒数加在一起求和，
 * 而那个和没有任何意义。
 *
 * <p><b>恰好一次。</b>本方法只从终态转换的 {@code updated == 1L} 分支调用，
 * 所以那条带守卫的 UPDATE 就是对该次写入的唯一认领。除此之外每条语句都做了
 * 冲突保护（{@code usage_log.request_id} 唯一、{@code usage_stage} 基于
 * {@code (usage_log_id, stage_index)}、{@code provider_cost_record} 基于
 * {@code usage_log_id}，outbox 基于 {@code event_id}/{@code event_fingerprint}），
 * 整组语句处于同一个事务中，因此重跑不可能重复写入或只写一半。
 *
 * <p><b>成本几何、收益几何。</b>供应商成本是费率卡的每秒成本 × 计费秒数，
 * 读取自 {@code gateway.audio_effective_cost} —— 也就是套餐发布毛利守卫
 * 据以定价的同一个视图，其中 {@code effective_unit_cost} 已经包含重试余量
 * （V92 的视图按 {@code ceil(原价 * (1 + retry_rate))} 计算）。
 * 这些秒数按费率卡价格计算的零售价值作为定价依据一并记录；平台根据已结算的
 * 秒数自行分摊套餐的实际收入，因为套餐的价格是平台的口径。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class AudioEconomicsEvidenceWriter {

    private static final Logger log = LoggerFactory.getLogger(AudioEconomicsEvidenceWriter.class);

    /** 音频「秒」自始至终所采用的计量维度。 */
    static final String DIMENSION = UsageEconomicsEvent.DIMENSION_AUDIO_SECOND;

    /** V92 为音频生成所允许的 stage 类型。 */
    static final String STAGE_TYPE = "AUDIO_GENERATION";

    private static final int STAGE_INDEX = 0;
    private static final String CURRENCY = "CNY";

    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactions;
    private final UsageEconomicsOutboxService economicsOutbox;
    private final ObjectMapper objectMapper;

    AudioEconomicsEvidenceWriter(
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
     * 为一个已进入终态的音频调用写入证据。
     *
     * @param task 终态转换之前读到的任务行
     * @param terminalStatus 带守卫的 UPDATE 所写入的状态
     * @param billedSeconds 向客户计费的秒数；该值为零时完全跳过证据写入，
     *        因为一次什么都没消耗的调用既没有数量、也没有成本和收入可报告
     */
    Mono<Void> write(AudioTaskRepository.TaskRow task, String terminalStatus, int billedSeconds) {
        if (billedSeconds <= 0) {
            return Mono.empty();
        }
        UUID usageLogId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        Mono<Void> work = resolveModelId(task.modelCode())
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "audio_evidence_model_missing:" + task.modelCode())))
                .flatMap(modelId -> resolveRate(task)
                        .defaultIfEmpty(AudioRate.UNPRICED)
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
                                                "audio_economics_evidence_written requestId={} "
                                                        + "kind={} dimension={} units={} "
                                                        + "providerCost={}",
                                                task.requestId(), task.kind(), DIMENSION,
                                                billedSeconds,
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
     * 该别名当前适用的每秒价格。
     *
     * <p>音频不按档位分档，因此查询只按 {@code model_code}；视图里
     * {@code resolution_tier} 恒为 {@code ''}（V92）。
     *
     * <p>结果为空意味着该别名没有已发布的音频费率卡行。这是真实的运营故障，
     * 但并不是隐瞒整个事实的理由：调用确实发生了，平台也确实为此付了钱。
     * 证据会带着 {@code priceMissing} 写入——平台把它记为来源缺失并据此告警——
     * 而不是伪造一个零成本。
     */
    private Mono<AudioRate> resolveRate(AudioTaskRepository.TaskRow task) {
        return databaseClient.sql("""
                        SELECT effective_unit_cost, provider_unit_cost, unit_price, retry_rate,
                               price_version, minimum_charge
                        FROM gateway.audio_effective_cost
                        WHERE model_code = :model_code
                        """)
                .bind("model_code", task.modelCode())
                .map((row, metadata) -> new AudioRate(
                        row.get("effective_unit_cost", Long.class),
                        // provider_unit_cost 在 V40:36 是 bigint，单位是分，因此按 Long 读。
                        // （曾经按 BigDecimal 读，注释还把它当成 numeric；那是一次未经验证的
                        // 跨类型读取——本路径没有任何测试覆盖。整数分就该按整数读。）
                        row.get("provider_unit_cost", Long.class),
                        row.get("unit_price", Long.class),
                        row.get("retry_rate", BigDecimal.class),
                        row.get("price_version", String.class),
                        row.get("minimum_charge", Long.class)))
                .one();
    }

    private Mono<Boolean> insertUsageLog(
            UUID id,
            AudioTaskRepository.TaskRow task,
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
                            :usage_estimated, false, :duration_ms, 200, :outcome,
                            :error_category, false)
                        ON CONFLICT (request_id) DO NOTHING
                        """)
                .bind("id", id)
                .bind("request_id", task.requestId())
                .bind("reservation_id", task.reservationId())
                .bind("tenant_id", task.tenantId())
                .bind("user_id", task.userId())
                .bind("model_id", modelId)
                .bind("model_code", task.modelCode())
                .bind("upstream_model", task.upstreamModel())
                .bind("provider_id", task.providerId())
                .bind("channel_id", task.channelId())
                .bind("usage_dimension", DIMENSION)
                .bind("usage_units", billedSeconds)
                // 合成的秒数是由字符数估算的（厂商不回报音频时长），
                // 因此那一类的证据必须自报为估算——一条无法与实测区分的
                // 估算计费，会让「为什么这个月的毛利不对」变成一个无法回答的问题。
                .bind("usage_estimated", estimated(task))
                .bind("duration_ms", elapsedMs(task, occurredAt))
                .bind("outcome", terminalStatus);
        spec = bindNullable(spec, "api_key_id", task.apiKeyId(), UUID.class);
        spec = bindNullable(spec, "package_instance_id", task.packageInstanceId(), UUID.class);
        spec = bindNullable(spec, "error_category", task.errorCategory(), String.class);
        return spec.fetch().rowsUpdated().map(rows -> rows > 0);
    }

    private Mono<Void> insertStage(
            UUID usageLogId,
            AudioTaskRepository.TaskRow task,
            UUID modelId,
            String terminalStatus,
            int billedSeconds,
            Instant occurredAt,
            AudioRate price) {
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
                            :usage_dimension, :usage_units, :usage_estimated,
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
                .bind("usage_estimated", estimated(task))
                .bind("duration_ms", elapsedMs(task, occurredAt))
                .bind("outcome", terminalStatus)
                .bind("price_estimated", price.unpriced())
                .bind("raw_usage", pricingBasis(task, billedSeconds, price));
        spec = bindNullable(spec, "error_category", task.errorCategory(), String.class);
        // 全部按秒成本都是供应商对已处理秒数的收费；它被放在 output 桶中，
        // 因为音频没有 input/output 之分，而
        // ck_usage_stage_cost_breakdown 要求两者与总额对上。
        // 这与视频写入器是同一条取值，因此两个维度的 stage 形状一致。
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
            UUID usageLogId, AudioTaskRepository.TaskRow task, int billedSeconds, AudioRate price) {
        if (price.unpriced()) {
            // 没有费率卡行就意味着没有金额可记；杜撰一个金额就是伪造成本。
            // stage 与事件改为携带 priceMissing。
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
     * 本次计费秒数是否是估算值。
     *
     * <p>合成的秒数由字符数折算而来（厂商只回报字符数），因此那一类必须
     * 自报为估算。转写与换口型的秒数来自厂商对媒体的度量，因此是实测。
     */
    private boolean estimated(AudioTaskRepository.TaskRow task) {
        return AudioTaskRepository.KIND_SYNTHESIS.equals(task.kind());
    }

    /**
     * 本行定价所依据的基础，作为证据保留在 stage 上。
     *
     * <p>这是唯一能记录已结算秒数之费率卡零售价值的地方：平台自行分摊套餐的
     * 实际收入，而事件契约不携带网关提供的零售金额。把依据保留在这里，意味着
     * 成本、费率卡价格、单价下限以及它们来自哪个版本可以一起被审计。
     */
    private String pricingBasis(
            AudioTaskRepository.TaskRow task, int billedSeconds, AudioRate price) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("billedSeconds", billedSeconds);
        basis.put("kind", task.kind());
        basis.put("quantityDirection", quantityDirection(task));
        basis.put("priced", !price.unpriced());
        basis.put("priceVersion", price.priceVersion());
        basis.put("providerUnitCost", price.unitCost());
        basis.put("retryRate", price.retryRate());
        basis.put("unitPrice", price.unitPrice());
        // 对按秒计费的维度，真正决定一次调用价格下限的是 minimum_charge
        // 而不是 unit_price（V93：ASR 的单位成本被 ceil 到 1 分，
        // 而一次请求的下限是 5 分）。把它一并记下来，否则一次
        // 「按秒看着很便宜、按次其实不便宜」的定价在审计时无法解释。
        basis.put("minimumCharge", price.minimumCharge());
        basis.put("estimatedDuration", estimated(task));
        basis.put("providerCostAmount", price.unpriced() ? null : price.totalAmount(billedSeconds));
        basis.put("retailAmount", price.retailAmount(billedSeconds));
        try {
            return objectMapper.writeValueAsString(basis);
        } catch (Exception error) {
            throw new IllegalStateException("audio_economics_pricing_basis_failed", error);
        }
    }

    /**
     * 计费秒数量的是输入还是产出。
     *
     * <p>这是音频与视频、图片的实质差别：视频与图片都只量产出，而音频的
     * 转写量的是**输入**音频的时长，合成与换口型量的是**产出**。
     * 两者单位相同、语义相反，因此对账时必须能分辨——否则「这个月音频只用了
     * 很少的秒数」这句话在一半流量上是错的。
     */
    private String quantityDirection(AudioTaskRepository.TaskRow task) {
        return AudioTaskRepository.KIND_TRANSCRIPTION.equals(task.kind())
                ? "INPUT_SECONDS" : "OUTPUT_SECONDS";
    }

    /**
     * 从提交到观测到终态之间的挂钟毫秒数。
     *
     * <p>这不是音频时长：音频时长是 {@code billed_seconds}。这是网关实际等待的
     * 时间，也正是 {@code duration_ms} 在其他所有地方的含义。
     */
    private long elapsedMs(AudioTaskRepository.TaskRow task, Instant occurredAt) {
        if (task.submittedAt() == null) {
            return 0;
        }
        return Math.max(0, Duration.between(task.submittedAt(), occurredAt).toMillis());
    }

    private <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    /**
     * 本次证据定价所依据的费率卡行。
     *
     * <p>{@code effectiveUnitCost} 与 {@code unitCost} 都是 {@code bigint}：视图对
     * ceil 之后的结果做整数转换，而 {@code provider_unit_cost} 本身就是 bigint
     * （V40:36）。**两者的单位都是分**，因此都按 {@code Long} 读。
     *
     * <p>这里曾经把 {@code unitCost} 读成 {@code BigDecimal}，理由是要保住 ASR 的
     * 0.022 分/秒原价不被取整成 0。那个前提是错的：列是 bigint，0.022 在**写入**时
     * 就已经被静默四舍五入成 0（V93 因此改成存 1 分/秒，见那边的说明），读的时候
     * 无论用什么类型都救不回来。用 BigDecimal 读一个 int8 列还引入了一次没有测试
     * 覆盖的跨类型转换。
     */
    private record AudioRate(
            Long effectiveUnitCost,
            Long unitCost,
            Long unitPrice,
            BigDecimal retryRate,
            String priceVersion,
            Long minimumCharge) {

        /** 被路由到的别名没有已发布的音频费率卡行。 */
        static final AudioRate UNPRICED =
                new AudioRate(null, null, null, null, null, null);

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
