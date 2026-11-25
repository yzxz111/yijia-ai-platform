package com.yuzhihuihe.apiplatform.gateway.image;

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
 * 为已结算的图片生成写入运营经济性证据。
 *
 * <p>对话路径通过 {@code UsageStageWriter} 产生证据，视频路径通过
 * {@code VideoEconomicsEvidenceWriter} 产生同一组证据：一行 {@code usage_log}、
 * 它的 {@code usage_stage}、一条 {@code provider_cost_record}，以及 outbox 上的一条
 * 经济性事件，全部在同一个事务内完成。而图片结算路径此前只调用
 * {@code QuotaService.settle} 就停住：扣费是对的，但这次生成对平台完全不存在——
 * 「调用与额度」里看不到它，{@code platform.reporting} 也从未把供应商为这些图
 * 付掉的钱记成 {@code operating_fact}，于是平台的利润数字悄悄漏掉了生图成本。
 * 本写入器弥合的正是这一处。（在真实付费调用上验证过：一次
 * {@code image-basic} 调用结算成功，而上述四张表各为 0 行。）
 *
 * <p><b>计量单位。</b>一次图片生成消耗的是**张**，而不是 token。计费张数
 * （{@code billed_images}）放在 {@code usage_units} 并显式命名维度
 * {@code usage_dimension = 'IMAGE'}，三个 token 计数器一律为零，
 * 事件版本 {@code USAGE_ECONOMICS_V3} 携带同一维度。因此 token 不变式按维度分别
 * 成立，token 报表永远不会看到「张」。这不是新发明的形状：网关 V49 早已把
 * {@code IMAGE} 写进两个维度的 CHECK，V40/V81 也早已按 {@code 分/张} 给这个维度定价。
 *
 * <p><b>成本几何。</b>供应商成本是费率卡的每张成本 × 计费张数，读取自
 * {@code gateway.image_effective_cost} —— 也就是套餐发布毛利守卫据以定价的同一个视图，
 * 其中 {@code effective_unit_cost} 已经包含重试余量（V81）。解析顺序是
 * 「请求档位的行，其次别名已售档位的行」：{@code ImageGenerationService} 允许交付
 * **更小**的档位，而费率卡按别名出售的档位定价，因此一次 1K 渲染若没有自己的行，
 * 就按该别名 2K 的基准计成本——这是平台实际承担的最坏情况，绝不会低报。
 * 这些张数按费率卡零售价计算的价值作为定价依据记在 stage 的 {@code raw_usage} 上；
 * 平台按已结算张数自行分摊套餐的实际收入，因为套餐价格是平台的口径。
 *
 * <p><b>恰好一次。</b>本方法只从
 * {@link ImageGenerationService} 的带守卫终态转换胜出分支（{@code updated == 1L}）
 * 与补偿路径调用，那条带守卫的 UPDATE 就是对该次写入的唯一认领。除此之外，
 * 每条语句都做了冲突保护（{@code usage_log.request_id} 唯一、
 * {@code usage_stage} 基于 {@code (usage_log_id, stage_index)}、
 * {@code provider_cost_record} 基于 {@code usage_log_id}，outbox 则基于
 * {@code event_id} / {@code event_fingerprint}），并且整组语句处于同一个事务中：
 * {@code usage_log} 的插入返回 0 行就意味着这次证据已经写过了，整条链随即跳过，
 * 因此重放与崩溃后的补偿都不可能写第二套、也不可能只写一半。
 * V83 把这四个冲突目标写成断言，使它们不再是口头约定。
 *
 * <p><b>零用量。</b>一次终态生成有可能一张也没向客户计费：厂商明确拒绝（预留被释放）、
 * 厂商报出零张、或结算被拒。这些情况下仍然写入真实的
 * {@code usage_log}（{@code outcome} 为 FAILED/CANCELLED、{@code usage_units = 0}）
 * 与一条零用量的 stage，但**不写** {@code provider_cost_record}：
 * 图片调用失败不产出图片、厂商也不收费（V81:204-210 把这一点记成了
 * {@code retry_rate = 0} 的理由），因此零成本是事实而不是省略；
 * 反过来给它记一笔金额，或者按费率卡的单价凭空记一笔「失败也收费」的成本，
 * 才是伪造。该 stage 的 {@code provider_billable} 与 {@code customer_billable}
 * 同时为 false，这正是「这一次没有任何一方被计费」在 schema 上的说法。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class ImageEconomicsEvidenceWriter {

    private static final Logger log = LoggerFactory.getLogger(ImageEconomicsEvidenceWriter.class);

    /** 图片「张」自始至终所采用的计量维度。 */
    static final String DIMENSION = UsageEconomicsEvent.DIMENSION_IMAGE;

    /** V83 为图片生成所允许的 stage 类型。 */
    static final String STAGE_TYPE = "IMAGE_GENERATION";

    private static final int STAGE_INDEX = 0;
    private static final String CURRENCY = "CNY";
    private static final String SUCCEEDED = "SUCCEEDED";

    /**
     * 成功与失败的 {@code http_status}。
     *
     * <p>沿用 {@code ChatCompletionService} 的写法：成功是 200，其余一律 502。
     * 这里刻意不落厂商的状态码——它没有被持久化在生成记录上，补偿路径
     * （几分钟后、可能在另一个进程里）读不到它，硬写一个猜测值会让同一条记录的
     * 两个写入者给出不同的数字；而失败的真实原因在 {@code outcome} 与
     * {@code error_category} 上，两者都是持久化的事实。
     */
    private static final int SUCCESS_HTTP_STATUS = 200;
    private static final int FAILURE_HTTP_STATUS = 502;

    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactions;
    private final UsageEconomicsOutboxService economicsOutbox;
    private final ObjectMapper objectMapper;

    ImageEconomicsEvidenceWriter(
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
     * 为一个已进入终态的图片生成写入证据。
     *
     * @param task 该次生成的记录行；失败与补偿路径传入的是刚从库里读到的终态行，
     *        因此 {@code error_category} 是真实落库的那个值
     * @param terminalStatus 终态转换所写入的状态（SUCCEEDED/FAILED/CANCELLED）
     * @param billedImages 向客户计费的张数，也是向平台申报的 {@code usage_units}；
     *        为零时不写任何成本记录
     */
    Mono<Void> write(ImageTaskRepository.TaskRow task, String terminalStatus, int billedImages) {
        if (billedImages < 0) {
            return Mono.error(new IllegalStateException(
                    "image_evidence_negative_units:" + task.requestId()));
        }
        UUID usageLogId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        Mono<Void> work = resolveModelId(task.modelCode())
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "image_evidence_model_missing:" + task.modelCode())))
                .flatMap(modelId -> resolveRate(task)
                        .defaultIfEmpty(ImageRate.UNPRICED)
                        .flatMap(price -> insertUsageLog(
                                        usageLogId, task, modelId, terminalStatus, billedImages,
                                        occurredAt)
                                .filter(inserted -> inserted)
                                .flatMap(inserted -> insertStage(
                                                usageLogId, task, modelId, terminalStatus,
                                                billedImages, occurredAt, price)
                                        .then(insertProviderCost(
                                                usageLogId, task, billedImages, price))
                                        .then(economicsOutbox.enqueueUsage(usageLogId))
                                        .doOnSuccess(ignored -> log.info(
                                                "image_economics_evidence_written requestId={} "
                                                        + "dimension={} units={} providerCost={}",
                                                task.requestId(), DIMENSION, billedImages,
                                                billedImages > 0 && !price.unpriced()
                                                        ? price.totalAmount(billedImages)
                                                        : null))))
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
     * 本次证据定价所依据的每张成本。
     *
     * <p>先找请求档位自己的行，再退回该别名**已售**档位的行（
     * {@code gateway.image_alias_tier}）。两个都是持久化的事实，因此重放与补偿
     * 得到的是同一行；而按已售档位计价是平台实际承担的最坏情况，
     * 因为服务层只允许交付不大于已售档位的尺寸。
     *
     * <p>结果为空意味着该别名在任何一个相关档位上都没有已发布的图片费率卡行。
     * 这是真实的运营故障，但并不是隐瞒整个事实的理由：生成确实发生了。
     * 证据会带着 {@code price_missing} 写入——平台把它记为来源缺失并据此告警——
     * 而不是伪造一个零成本。
     */
    private Mono<ImageRate> resolveRate(ImageTaskRepository.TaskRow task) {
        String requestedTier = task.resolutionTier();
        return rateAt(task.modelCode(), requestedTier)
                .switchIfEmpty(Mono.defer(() -> soldTier(task.modelCode())
                        .filter(sold -> !sold.equals(requestedTier))
                        .flatMap(sold -> rateAt(task.modelCode(), sold))));
    }

    private Mono<ImageRate> rateAt(String modelCode, String resolutionTier) {
        if (resolutionTier == null) {
            return Mono.empty();
        }
        return databaseClient.sql("""
                        SELECT effective_unit_cost, provider_unit_cost, unit_price, retry_rate,
                               price_version, resolution_tier
                        FROM gateway.image_effective_cost
                        WHERE model_code = :model_code AND resolution_tier = :resolution_tier
                        """)
                .bind("model_code", modelCode)
                .bind("resolution_tier", resolutionTier)
                .map((row, metadata) -> new ImageRate(
                        row.get("effective_unit_cost", Long.class),
                        row.get("provider_unit_cost", Long.class),
                        row.get("unit_price", Long.class),
                        row.get("retry_rate", BigDecimal.class),
                        row.get("price_version", String.class),
                        row.get("resolution_tier", String.class)))
                .one();
    }

    private Mono<String> soldTier(String modelCode) {
        return databaseClient.sql("""
                        SELECT resolution_tier FROM gateway.image_alias_tier
                        WHERE model_code = :model_code
                        """)
                .bind("model_code", modelCode)
                .map((row, metadata) -> row.get("resolution_tier", String.class))
                .one();
    }

    private Mono<Boolean> insertUsageLog(
            UUID id,
            ImageTaskRepository.TaskRow task,
            UUID modelId,
            String terminalStatus,
            int billedImages,
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
                            false, false, :duration_ms, :http_status, :outcome,
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
                .bind("usage_units", billedImages)
                .bind("duration_ms", elapsedMs(task, occurredAt))
                .bind("http_status", httpStatus(terminalStatus))
                .bind("outcome", terminalStatus);
        spec = bindNullable(spec, "error_category", task.errorCategory(), String.class);
        return spec.fetch().rowsUpdated().map(rows -> rows > 0);
    }

    private Mono<Void> insertStage(
            UUID usageLogId,
            ImageTaskRepository.TaskRow task,
            UUID modelId,
            String terminalStatus,
            int billedImages,
            Instant occurredAt,
            ImageRate price) {
        // 只有真的向客户计了张数，才存在「有成本可报」这件事。零用量的终态
        // （厂商拒绝、零张、结算被拒）没有任何一方被计费，因此两个 billable
        // 标记同时为 false —— 这同时让 ck_usage_stage_provider_billable_cost
        // 强制所有成本列为 NULL，伪造成本在 schema 层就不可能发生。
        boolean billable = billedImages > 0;
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
                            :customer_billable, :provider_billable, 'NOT_APPLICABLE')
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
                .bind("usage_units", billedImages)
                .bind("duration_ms", elapsedMs(task, occurredAt))
                .bind("outcome", terminalStatus)
                .bind("customer_billable", billable)
                .bind("provider_billable", billable)
                // 未定价与零用量是两件事：前者是「有成本但来源缺失」，必须被看见，
                // 因此 price_estimated = true；后者根本没有成本可言，
                // 而 provider_billable = false 已要求这一列为 false。
                .bind("price_estimated", billable && price.unpriced());
        spec = bindNullable(spec, "error_category", task.errorCategory(), String.class);
        // 零用量时不给定价依据：一次没有数量、也没有成本的生成没有依据可记，
        // 而把「未定价」与「零张」写成同一个形状会让两者再也分不开。
        spec = bindNullable(spec, "raw_usage",
                billable ? pricingBasis(task, billedImages, price) : null, String.class);
        if (!billable || price.unpriced()) {
            spec = spec.bindNull("provider_cost_amount", Long.class)
                    .bindNull("provider_cost_currency", String.class)
                    .bindNull("provider_input_cost_amount", Long.class)
                    .bindNull("provider_output_cost_amount", Long.class);
        } else {
            // 全部按张成本都是供应商对已生成图片的收费；它被放在 output 桶中，因为
            // 图片没有 input/output 之分，而 ck_usage_stage_cost_breakdown 要求
            // 两者与总额对上。这与视频把每秒成本放进 output 桶的做法一致。
            spec = spec.bind("provider_cost_amount", price.totalAmount(billedImages))
                    .bind("provider_cost_currency", CURRENCY)
                    .bind("provider_input_cost_amount", 0L)
                    .bind("provider_output_cost_amount", price.totalAmount(billedImages));
        }
        return spec.fetch().rowsUpdated().then();
    }

    private Mono<Void> insertProviderCost(
            UUID usageLogId, ImageTaskRepository.TaskRow task, int billedImages, ImageRate price) {
        if (billedImages <= 0 || price.unpriced()) {
            // 零张就没有金额可记，而没有费率卡行就没有金额可算。
            // 杜撰一个金额就是伪造成本：stage 与事件改为携带零用量或 price_missing。
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
                .bind("amount", price.totalAmount(billedImages))
                .bind("currency", CURRENCY)
                .fetch().rowsUpdated().then();
    }

    /**
     * 本行定价所依据的基础，作为证据保留在 stage 上。
     *
     * <p>这是唯一能记录已结算张数之费率卡零售价值的地方：平台自行分摊套餐的
     * 实际收入，而事件契约不携带网关提供的零售金额。把依据保留在这里，意味着
     * 成本、费率卡价格以及它们来自哪个版本可以一起被审计。
     */
    private String pricingBasis(
            ImageTaskRepository.TaskRow task, int billedImages, ImageRate price) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("billedImages", billedImages);
        basis.put("requestedImages", task.requestedImages());
        basis.put("requestedResolutionTier", task.resolutionTier());
        basis.put("requestedSize", task.requestedSize());
        // 图层拆分按次计费（恰好一张），因此它必须出现在依据里：读到同一行的人
        // 才能解释为什么厂商返回三张而这里只计一张。
        basis.put("layerDecomposition", task.layerDecomposition());
        basis.put("priced", !price.unpriced());
        basis.put("priceVersion", price.priceVersion());
        basis.put("pricedTier", price.tier());
        basis.put("providerUnitCost", price.unitCost());
        basis.put("retryRate", price.retryRate());
        basis.put("unitPrice", price.unitPrice());
        basis.put("providerCostAmount", price.unpriced() ? null : price.totalAmount(billedImages));
        basis.put("retailAmount", price.retailAmount(billedImages));
        try {
            return objectMapper.writeValueAsString(basis);
        } catch (Exception error) {
            throw new IllegalStateException("image_economics_pricing_basis_failed", error);
        }
    }

    /**
     * 从提交到终态的挂钟毫秒数。
     *
     * <p>这不是生成耗时：一次同步生图的真实耗时由厂商决定，网关只知道自己等了多久，
     * 而 {@code duration_ms} 在其他所有地方的含义正是后者。终态时刻取记录上的
     * {@code terminal_at}（补偿路径与补写路径读到的正是它），因此一条几分钟后才被
     * 补上证据的记录不会把自己说成跑了那么久；只有获胜的那条路径手里还是终态转换
     * 之前的快照，那时用观测时刻。
     */
    private long elapsedMs(ImageTaskRepository.TaskRow task, Instant occurredAt) {
        if (task.submittedAt() == null) {
            return 0;
        }
        Instant terminal = task.terminalAt() == null ? occurredAt : task.terminalAt();
        return Math.max(0, Duration.between(task.submittedAt(), terminal).toMillis());
    }

    private int httpStatus(String terminalStatus) {
        return SUCCEEDED.equals(terminalStatus) ? SUCCESS_HTTP_STATUS : FAILURE_HTTP_STATUS;
    }

    private <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    /** 本次证据定价所依据的费率卡行。 */
    private record ImageRate(
            Long effectiveUnitCost,
            Long unitCost,
            Long unitPrice,
            BigDecimal retryRate,
            String priceVersion,
            String tier) {

        /** 被路由到的别名在相关档位上没有已发布的图片费率卡行。 */
        static final ImageRate UNPRICED = new ImageRate(null, null, null, null, null, null);

        boolean unpriced() {
            return effectiveUnitCost == null || unitCost == null;
        }

        long totalAmount(int billedImages) {
            return effectiveUnitCost * billedImages;
        }

        Long retailAmount(int billedImages) {
            return unitPrice == null ? null : unitPrice * billedImages;
        }
    }
}
