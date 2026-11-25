package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 带维度的经济性契约，{@code USAGE_ECONOMICS_V3}。
 *
 * <p>{@code VideoSecondEconomicsContractTest} 证明了为什么视频秒数在 V1/V2 中
 * 无法被诚实地表达：token 计数器受算术约束，而
 * 唯一能到达套餐毛利告警的、被接受的形状是把秒数写进
 * {@code output_tokens}。V3 通过给单位命名消除了这一两难：
 *
 * <ul>
 *   <li>{@code usage_dimension} 说明事件的量以哪个单位计
 *       （{@code TOKEN}、{@code VIDEO_SECOND}、{@code IMAGE} 或
 *       {@code AUDIO_SECOND}）。{@code AUDIO_SECOND} 由平台 V88 加入词表，
 *       其重要性在于**加入白名单的失败方向是静默的**：不在其中的维度会让事件
 *       被 {@code INVALID} 拒收，而拒收不报错给任何人，只是让那笔成本在运营
 *       报表里不存在——与「网关不写证据」是同一个后果的另一个半边；
 *   <li>{@code usage_units} 与 {@code package_settled_units} 以该
 *       单位携带量，因此秒数不再需要借道 token 列来传输；
 *   <li>token 不变量按维度保持不变：{@code TOKEN} 事件仍然有
 *       {@code usage_units = total_tokens}，而非 {@code TOKEN} 事件的三项
 *       token 计数器全为零；
 *   <li>收入分摊除以已结算的 UNITS 以及同一
 *       单位下的套餐额度，平台侧的 {@code gateway.package_instance.total_token_quota}
 *       已经携带该值（V54 在其中为秒数套餐保存 SECONDS）。
 * </ul>
 *
 * <p>下面的经济性与 {@code VideoSecondEconomicsContractTest}
 * 使用的是同一个亏损的视频套餐 —— 199 元人民币换 300 秒，对应
 * 每秒 345 分的提供方成本 —— 因此这两个类可以并排阅读：同一个
 * 原本只能记为 {@code output_tokens = 300} 的事实，现在记为
 * 300 秒，而它本应始终开启的告警确实开启了。
 */
class VideoSecondEconomicsV3IngestionTest {

    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final UUID TENANT = UUID.fromString("93000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("93000000-0000-4000-8000-000000000002");
    private static final UUID EVENT = UUID.fromString("93000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE = UUID.fromString("93000000-0000-4000-8000-000000000004");
    private static final UUID PACKAGE_VERSION = UUID.fromString("93000000-0000-4000-8000-000000000010");
    private static final UUID PROVIDER = UUID.fromString("93000000-0000-4000-8000-000000000006");
    private static final UUID CHANNEL = UUID.fromString("93000000-0000-4000-8000-000000000007");
    private static final Instant NOW = Instant.parse("2026-09-16T08:00:00Z");

    /** 199 元人民币换 300 秒，即视频设计文档定价为亏损的那种形状。 */
    private static final long PACKAGE_PRICE_AMOUNT = 19_900L;
    private static final long PACKAGE_SECOND_QUOTA = 300L;
    /** {@code gateway.model_usage_price}：video-max 1080P 为每秒 345 分。 */
    private static final long PROVIDER_COST_PER_SECOND = 345L;
    private static final long BILLED_SECONDS = 300L;
    private static final long PROVIDER_TOTAL_COST = BILLED_SECONDS * PROVIDER_COST_PER_SECOND;

    /**
     * 音频那一组。成本取换口型的费率（{@code gateway V93}：8 分/秒原价、
     * retry 0.05，effective 9 分/秒；零售 14 分/秒）。
     * 与视频那组刻意不同，让「按秒」这件事是被验证的而不是被复制的。
     */
    private static final long AUDIO_BILLED_SECONDS = 100L;
    private static final long AUDIO_PROVIDER_COST_PER_SECOND = 14L;
    private static final long AUDIO_PROVIDER_TOTAL_COST =
            AUDIO_BILLED_SECONDS * AUDIO_PROVIDER_COST_PER_SECOND;

    /**
     * 一个混合套餐（方案 C）：视频秒 300、音频秒 600，同一个 199 元的价格。
     */
    private static final long MIXED_AUDIO_SECOND_QUOTA = 600L;

    /**
     * 收入必须除以**本次用量那一个维度**的额度（方案 C）。
     *
     * <p>混合套餐有两份额度：视频秒与音频秒。用主列（视频 300 秒）去当一次语音
     * 用量的分母不会让任何东西失败，只会把收入按错误的比例摊到每一次语音调用上
     * ——这里差了 6 倍（19900 对 3316.67），而报表上的毛利率随之从 51% 变成
     * −82%。
     *
     * <p>摄入层本身不读数据库：它把**事件的维度**交给存储层，由存储层按同一条
     * `usesSecondBucket` 判据取对应那一列。因此这条测试钉的是那两个方向——
     * 传下去的维度必须是事件自己的，而分母必须用传回来的那份额度。
     */
    @Test
    void allocatesRevenueAgainstTheQuotaOfTheEventsOwnDimension() {
        MemoryStore store = new MemoryStore();
        RecordingAlerts alerts = new RecordingAlerts();
        OperatingFactIngestionService service = service(store, alerts);
        // 覆盖 helper 的默认值：这次是语音用量，因此返回的是**音频**那份额度。
        store.allocation = new OperatingFactIngestionService.PackageAllocation(
                BigDecimal.valueOf(PACKAGE_PRICE_AMOUNT), MIXED_AUDIO_SECOND_QUOTA, "PAID_ORDER",
                PACKAGE_VERSION);

        var result = service.ingest(bytes(audioEvent()), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);
        assertThat(store.lastAllocationDimension).isEqualTo("AUDIO_SECOND");
        OperatingFactIngestionService.FactLine fact = store.facts.getFirst();
        // 19900 × 100 / 600 = 3316.666667。用视频的 300 秒会得到 6633.333333，
        // 用视频的 100 秒（本文件的另一条测试）会得到 19900——三个数字都不同，
        // 因此这条断言不可能靠巧合通过。
        assertThat(fact.allocatedUsageRevenueAmount()).isEqualByComparingTo("3316.666667");
    }

    /**
     * 分母取不到时**不猜**：归属记为 0，而不是拿一个错误（或为零）的分母去除。
     */
    @Test
    void aMissingUnitQuotaAllocatesNoRevenueInsteadOfDividingByZeroOrByTheWrongColumn() {
        MemoryStore store = new MemoryStore();
        RecordingAlerts alerts = new RecordingAlerts();
        OperatingFactIngestionService service = service(store, alerts);
        store.allocation = new OperatingFactIngestionService.PackageAllocation(
                BigDecimal.valueOf(PACKAGE_PRICE_AMOUNT), null, "PAID_ORDER", PACKAGE_VERSION);

        var result = service.ingest(bytes(audioEvent()), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);
        assertThat(store.facts.getFirst().allocatedUsageRevenueAmount())
                .isEqualByComparingTo("0");
    }

    @Test
    void acceptsAVideoSecondEventAndNeverWritesASecondIntoATokenCounter() {
        MemoryStore store = new MemoryStore();
        RecordingAlerts alerts = new RecordingAlerts();

        var result = service(store, alerts).ingest(bytes(videoEvent()), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);
        assertThat(store.audits).isEmpty();
        assertThat(store.facts).hasSize(1);
        OperatingFactIngestionService.FactLine fact = store.facts.getFirst();
        // 量是一个带维度限定的数字，而 token 计数器是诚实的。
        assertThat(fact.usageDimension()).isEqualTo("VIDEO_SECOND");
        assertThat(fact.usageUnits()).isEqualTo(BILLED_SECONDS);
        assertThat(fact.inputTokens()).isZero();
        assertThat(fact.outputTokens()).isZero();
        assertThat(fact.totalTokens()).isZero();
        assertThat(fact.metadata()).containsEntry("usageDimension", "VIDEO_SECOND");
        assertThat(fact.metadata()).containsEntry("usageUnits", BILLED_SECONDS);
        // 成本是真实的，收入按已结算的秒数分摊，而不是
        // 按 token 计数：19900 * 300 / 300 = 整个套餐价格。
        assertThat(fact.providerCostAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(PROVIDER_TOTAL_COST));
        assertThat(fact.allocatedUsageRevenueAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(PACKAGE_PRICE_AMOUNT));
        // 而这正是让亏损的视频套餐变得可见的原因：这个套餐收取 199
        // 元人民币换 300 秒，而成本为 1035 元人民币，因此毛利告警开启。
        assertThat(alerts.packageCalls).isEqualTo(1);
        assertThat(alerts.packageCosts.getFirst())
                .isEqualByComparingTo(BigDecimal.valueOf(PROVIDER_TOTAL_COST));
        assertThat(alerts.opened()).hasSize(1);
        OperatingAlertService.Alert alert = alerts.opened().getFirst();
        assertThat(alert.type()).isEqualTo("LOW_MARGIN_PACKAGE");
        assertThat(alert.observed()).isLessThan(new BigDecimal("0.30"));
    }

    @Test
    void stillRejectsASecondDenominatedEventThatCountsItsUnitsAsTokens() {
        MemoryStore store = new MemoryStore();
        RecordingAlerts alerts = new RecordingAlerts();

        var result = service(store, alerts).ingest(
                bytes(videoEvent().replace("\"output_tokens\":0", "\"output_tokens\":300")
                        .replace("\"total_tokens\":0", "\"total_tokens\":300")), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.INVALID);
        assertThat(store.audits).containsExactly("invalid_event_contract");
        assertThat(store.facts).isEmpty();
        assertThat(alerts.packageCalls).isZero();
    }

    @Test
    void rejectsADimensionedEventWhoseSettledUnitsExceedItsUsageUnits() {
        MemoryStore store = new MemoryStore();

        var result = service(store, new RecordingAlerts()).ingest(
                bytes(videoEvent().replace("\"package_settled_units\":300",
                        "\"package_settled_units\":301")), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.INVALID);
        assertThat(store.audits).containsExactly("invalid_event_contract");
    }

    @Test
    void rejectsAnUnknownDimensionAndAnUnqualifiedUnitCount() {
        MemoryStore store = new MemoryStore();
        OperatingFactIngestionService service = service(store, new RecordingAlerts());

        assertThat(service.ingest(bytes(videoEvent().replace(
                "\"usage_dimension\":\"VIDEO_SECOND\"", "\"usage_dimension\":\"MINUTE\"")), HASH_A))
                .isEqualTo(OperatingFactIngestionService.Result.INVALID);
        assertThat(service.ingest(bytes(videoEvent().replace(
                "\"usage_dimension\":\"VIDEO_SECOND\"", "\"usage_dimension\":\"\"")), HASH_A))
                .isEqualTo(OperatingFactIngestionService.Result.INVALID);
        assertThat(store.audits).containsOnly("invalid_event_contract");
        assertThat(store.facts).isEmpty();
    }

    @Test
    void rejectsADimensionedEventWhoseCustomerBillableStagesDoNotSumToTheEventUnits() {
        MemoryStore store = new MemoryStore();

        // 该事件声称 301 秒，而它唯一对客户计费的阶段写明 300。
        String payload = videoEvent().replaceFirst("\"usage_units\":300,", "\"usage_units\":301,");
        assertThat(payload).isNotEqualTo(videoEvent());

        var result = service(store, new RecordingAlerts()).ingest(bytes(payload), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.INVALID);
        assertThat(store.audits).containsExactly("stage_totals_mismatch");
        assertThat(store.facts).isEmpty();
    }

    @Test
    void keepsTheTokenDimensionInvariantForATokenEvent() {
        MemoryStore store = new MemoryStore();

        var result = service(store, new RecordingAlerts()).ingest(bytes(tokenEvent()), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);
        OperatingFactIngestionService.FactLine fact = store.facts.getFirst();
        assertThat(fact.usageDimension()).isEqualTo("TOKEN");
        assertThat(fact.usageUnits()).isEqualTo(40);
        assertThat(fact.totalTokens()).isEqualTo(40);
        // 按 token 维度已经使用的同一单位分摊：
        // 19900 * 40 / 300 = 2653.333333。
        assertThat(fact.allocatedUsageRevenueAmount()).isEqualByComparingTo("2653.333333");
    }

    @Test
    void rejectsATokenEventWhoseUnitsAndTokensDisagree() {
        MemoryStore store = new MemoryStore();

        var result = service(store, new RecordingAlerts()).ingest(
                bytes(tokenEvent().replace("\"usage_units\":40", "\"usage_units\":41")), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.INVALID);
        assertThat(store.audits).containsExactly("invalid_event_contract");
    }

    /**
     * 一个在非 token 维度下仍携带 {@code package_settled_tokens} 的 V3 事件
     * 会让旧的结算字段可被读取，仿佛它就是已结算的量。
     * 一旦维度声明为秒，token 字段就必须保持为零。
     */
    @Test
    void rejectsANonTokenEventThatAlsoSettlesTokens() {
        MemoryStore store = new MemoryStore();

        var result = service(store, new RecordingAlerts()).ingest(
                bytes(videoEvent().replace("\"package_settled_tokens\":0",
                        "\"package_settled_tokens\":300")), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.INVALID);
        assertThat(store.audits).containsExactly("invalid_event_contract");
    }

    /**
     * 音频秒必须以它自己的维度被接受。
     *
     * <p>这条测试防住的是一个**静默**的缺陷，而不是一个错误：平台的摄入层有一份
     * 维度白名单（{@code OperatingFactIngestionService.DIMENSIONS}），
     * 不在其中的维度会让事件被 {@code INVALID} 拒收。被拒收的事件不会报错给任何人
     * ——它只是在运营报表里不存在——因此「音频成本对平台完全不可见」这个后果
     * 可以从两个方向到达：网关不写证据，或平台拒收证据。
     *
     * <p>数字与视频那条测试刻意不同，好让「按秒」这件事是被验证而不是被复制：
     * 换口型的费率是 14 分/秒（V93），一个 199 元含 300 秒的套餐跑 100 秒时
     * 成本 1400 分、分摊收入 6633.33 分，毛利 78.9%——**健康**，因此它不该开告警。
     * 视频那条用的是 345 分/秒的亏损形状，两条并排读能看出同一个维度机制
     * 对贵和便宜的按秒商品都成立。
     */
    @Test
    void acceptsAnAudioSecondEventUnderItsOwnDimension() {
        MemoryStore store = new MemoryStore();
        RecordingAlerts alerts = new RecordingAlerts();

        var result = service(store, alerts).ingest(bytes(audioEvent()), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);
        assertThat(store.audits).isEmpty();
        assertThat(store.facts).hasSize(1);
        OperatingFactIngestionService.FactLine fact = store.facts.getFirst();

        // 维度是音频自己的，**不是** VIDEO_SECOND：两者同单位却相差两个数量级，
        // 合并维度会让报表把一段视频的秒数与一次配音的秒数相加求和。
        assertThat(fact.usageDimension()).isEqualTo("AUDIO_SECOND");
        assertThat(fact.usageUnits()).isEqualTo(AUDIO_BILLED_SECONDS);
        assertThat(fact.inputTokens()).isZero();
        assertThat(fact.outputTokens()).isZero();
        assertThat(fact.totalTokens()).isZero();
        assertThat(fact.metadata()).containsEntry("usageDimension", "AUDIO_SECOND");
        assertThat(fact.metadata()).containsEntry("usageUnits", AUDIO_BILLED_SECONDS);

        // 成本与收入都按已结算的**秒**分摊：19900 * 100 / 300 = 6633.333333。
        assertThat(fact.providerCostAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(AUDIO_PROVIDER_TOTAL_COST));
        assertThat(fact.allocatedUsageRevenueAmount()).isEqualByComparingTo("6633.333333");

        // 这个套餐是健康的（14 分/秒的成本对 66 分/秒的零售），因此不该开告警。
        // 断言发票次数为 1 而不是 0：告警服务确实被调用了，只是没有开单——
        // 否则「没有告警」与「这段代码根本没跑」无法区分。
        assertThat(alerts.packageCalls).isEqualTo(1);
        assertThat(alerts.opened()).isEmpty();
    }

    /**
     * 音频的 token 不变量与视频相同：按秒的事件不得携带任何 token 计数。
     *
     * <p>这条不是重复：它与视频那条走的是同一个判据，但针对的是**新增的维度**，
     * 而新增维度正是那种「顺手把它加进白名单、却忘了它也受同一条算术约束」
     * 的改动容易漏掉的地方。
     */
    @Test
    void rejectsAnAudioEventThatCountsItsUnitsAsTokens() {
        MemoryStore store = new MemoryStore();

        var result = service(store, new RecordingAlerts()).ingest(
                bytes(audioEvent().replace("\"output_tokens\":0", "\"output_tokens\":100")
                        .replace("\"total_tokens\":0", "\"total_tokens\":100")), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.INVALID);
        assertThat(store.audits).containsExactly("invalid_event_contract");
        assertThat(store.facts).isEmpty();
    }

    private OperatingFactIngestionService service(MemoryStore store, RecordingAlerts alerts) {
        store.allocation = new OperatingFactIngestionService.PackageAllocation(
                BigDecimal.valueOf(PACKAGE_PRICE_AMOUNT), PACKAGE_SECOND_QUOTA, "PAID_ORDER",
                PACKAGE_VERSION);
        return new OperatingFactIngestionService(
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), store, alerts);
    }

    private String videoEvent() {
        return """
                {"version":"USAGE_ECONOMICS_V3","event_id":"%s","request_id":"req-v3-video",
                 "reservation_id":"93000000-0000-4000-8000-000000000005",
                 "tenant_id":"%s","user_id":"%s","package_instance_id":"%s",
                 "model_code":"video-max","upstream_model":"kling-v3-omni",
                 "provider_id":"%s","channel_id":"%s",
                 "input_tokens":0,"output_tokens":0,"total_tokens":0,
                 "usage_dimension":"VIDEO_SECOND","usage_units":%d,
                 "billing_mode":"PACKAGE_ONLY","package_settled_tokens":0,
                 "package_settled_units":%d,
                 "wallet_settled_amount":0,"usage_estimated":false,"price_missing":false,
                 "provider_input_cost_amount":0,"provider_output_cost_amount":%d,
                 "provider_total_cost_amount":%d,"provider_cost_currency":"CNY",
                 "stages":[
                   {"stage_index":0,"stage_type":"VIDEO_GENERATION","logical_model_code":"video-max",
                    "upstream_model":"kling-v3-omni","provider_id":"%s","channel_id":"%s",
                    "input_tokens":0,"output_tokens":0,"total_tokens":0,
                    "usage_dimension":"VIDEO_SECOND","usage_units":%d,
                    "provider_input_cost_amount":0,"provider_output_cost_amount":%d,
                    "provider_total_cost_amount":%d,"provider_cost_currency":"CNY",
                    "usage_source":"PROVIDER_ACTUAL","usage_estimated":false,
                    "customer_billable":true,"provider_billable":true,
                    "reconciliation_status":"NOT_APPLICABLE"}
                 ]}
                """.formatted(EVENT, TENANT, USER, PACKAGE, PROVIDER, CHANNEL,
                BILLED_SECONDS, BILLED_SECONDS,
                PROVIDER_TOTAL_COST, PROVIDER_TOTAL_COST,
                PROVIDER, CHANNEL, BILLED_SECONDS,
                PROVIDER_TOTAL_COST, PROVIDER_TOTAL_COST);
    }

    /**
     * 一个音频秒事件：网关侧由 {@code AudioEconomicsEvidenceWriter} 写出，
     * 形状与视频事件完全相同，只有维度、stage 类型与数字不同。
     *
     * <p>stage 类型是 {@code AUDIO_GENERATION}（网关 V92 加入），
     * 而 {@code logical_model_code} 是音频别名而非视频别名。
     */
    private String audioEvent() {
        return """
                {"version":"USAGE_ECONOMICS_V3","event_id":"%s","request_id":"req-v3-audio",
                 "reservation_id":"93000000-0000-4000-8000-000000000005",
                 "tenant_id":"%s","user_id":"%s","package_instance_id":"%s",
                 "model_code":"audio-retalk","upstream_model":"videoretalk",
                 "provider_id":"%s","channel_id":"%s",
                 "input_tokens":0,"output_tokens":0,"total_tokens":0,
                 "usage_dimension":"AUDIO_SECOND","usage_units":%d,
                 "billing_mode":"PACKAGE_ONLY","package_settled_tokens":0,
                 "package_settled_units":%d,
                 "wallet_settled_amount":0,"usage_estimated":false,"price_missing":false,
                 "provider_input_cost_amount":0,"provider_output_cost_amount":%d,
                 "provider_total_cost_amount":%d,"provider_cost_currency":"CNY",
                 "stages":[
                   {"stage_index":0,"stage_type":"AUDIO_GENERATION","logical_model_code":"audio-retalk",
                    "upstream_model":"videoretalk","provider_id":"%s","channel_id":"%s",
                    "input_tokens":0,"output_tokens":0,"total_tokens":0,
                    "usage_dimension":"AUDIO_SECOND","usage_units":%d,
                    "provider_input_cost_amount":0,"provider_output_cost_amount":%d,
                    "provider_total_cost_amount":%d,"provider_cost_currency":"CNY",
                    "usage_source":"PROVIDER_ACTUAL","usage_estimated":false,
                    "customer_billable":true,"provider_billable":true,
                    "reconciliation_status":"NOT_APPLICABLE"}
                 ]}
                """.formatted(EVENT, TENANT, USER, PACKAGE, PROVIDER, CHANNEL,
                AUDIO_BILLED_SECONDS, AUDIO_BILLED_SECONDS,
                AUDIO_PROVIDER_TOTAL_COST, AUDIO_PROVIDER_TOTAL_COST,
                PROVIDER, CHANNEL, AUDIO_BILLED_SECONDS,
                AUDIO_PROVIDER_TOTAL_COST, AUDIO_PROVIDER_TOTAL_COST);
    }

    /** 一个带维度的 token 事件：聊天链路迁移到 V3 后会发出的形状。 */
    private String tokenEvent() {
        return """
                {"version":"USAGE_ECONOMICS_V3","event_id":"%s","request_id":"req-v3-token",
                 "reservation_id":"93000000-0000-4000-8000-000000000005",
                 "tenant_id":"%s","user_id":"%s","package_instance_id":"%s",
                 "model_code":"text-basic","upstream_model":"deepseek-chat",
                 "provider_id":"%s","channel_id":"%s",
                 "input_tokens":30,"output_tokens":10,"total_tokens":40,
                 "usage_dimension":"TOKEN","usage_units":40,
                 "billing_mode":"PACKAGE_ONLY","package_settled_tokens":40,
                 "package_settled_units":40,"wallet_settled_amount":0,
                 "usage_estimated":false,"price_missing":false,
                 "provider_input_cost_amount":2,"provider_output_cost_amount":3,
                 "provider_total_cost_amount":5,"provider_cost_currency":"CNY",
                 "stages":[
                   {"stage_index":0,"stage_type":"FINAL_RESPONSE","logical_model_code":"text-basic",
                    "upstream_model":"deepseek-chat","provider_id":"%s","channel_id":"%s",
                    "input_tokens":30,"output_tokens":10,"total_tokens":40,
                    "usage_dimension":"TOKEN","usage_units":40,
                    "provider_input_cost_amount":2,"provider_output_cost_amount":3,
                    "provider_total_cost_amount":5,"provider_cost_currency":"CNY",
                    "usage_source":"PROVIDER_ACTUAL","usage_estimated":false,
                    "customer_billable":true,"provider_billable":true,
                    "reconciliation_status":"PENDING"}
                 ]}
                """.formatted(EVENT, TENANT, USER, PACKAGE, PROVIDER, CHANNEL,
                PROVIDER, CHANNEL);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class MemoryStore implements OperatingFactIngestionService.Store {
        OperatingFactIngestionService.PackageAllocation allocation;
        /** 摄入层询问的维度：方案 C 起它决定收入摊到哪一份额度上。 */
        volatile String lastAllocationDimension;
        final List<OperatingFactIngestionService.FactLine> facts = new ArrayList<>();
        final List<String> audits = new ArrayList<>();

        @Override public OperatingFactIngestionService.Claim claim(UUID eventId, String payloadHash) {
            return OperatingFactIngestionService.Claim.CLAIMED;
        }
        @Override public boolean tenantUserExists(UUID tenantId, UUID userId) {
            return TENANT.equals(tenantId) && USER.equals(userId);
        }
        @Override public OperatingFactIngestionService.WalletSnapshot walletSnapshot(
                UUID tenantId, UUID userId, String requestId) {
            return null;
        }
        @Override public OperatingFactIngestionService.PackageAllocation packageAllocation(
                UUID packageInstanceId, UUID tenantId, UUID userId, String usageDimension) {
            this.lastAllocationDimension = usageDimension;
            return allocation;
        }
        @Override public void insertAll(List<OperatingFactIngestionService.FactLine> lines) {
            facts.addAll(lines);
        }
        @Override public BigDecimal providerCostP95(String model, String provider, Instant before) {
            return null;
        }
        @Override public void audit(UUID eventId, String payloadHash, String reason) {
            audits.add(reason);
        }
    }

    private static final class RecordingAlerts implements OperatingFactIngestionService.AlertSink {

        private final RecordingAlertStore alertStore = new RecordingAlertStore();
        private final OperatingAlertService alertService =
                new OperatingAlertService(alertStore, () -> NOW);
        int packageCalls;
        final List<BigDecimal> packageCosts = new ArrayList<>();

        List<OperatingAlertService.Alert> opened() {
            return alertStore.opened;
        }

        @Override public void providerCost(String model, String provider, BigDecimal requestCost,
                BigDecimal sevenDayP95, boolean missing, List<UUID> factIds) {
        }

        @Override public void packageMargin(UUID packageId, BigDecimal revenue,
                BigDecimal providerCost, List<UUID> factIds) {
            packageCalls++;
            packageCosts.add(providerCost);
            alertService.evaluatePackageMargin(packageId, revenue, providerCost, factIds);
        }
    }

    private static final class RecordingAlertStore implements OperatingAlertService.Store {
        final List<OperatingAlertService.Alert> opened = new ArrayList<>();

        @Override public boolean open(OperatingAlertService.Alert alert) {
            opened.add(alert);
            return true;
        }
        @Override public OperatingAlertService.TransitionResult transition(
                UUID id, String status, UUID actor, Instant at, String resolutionReason) {
            return OperatingAlertService.TransitionResult.NOT_FOUND;
        }
        @Override public void audit(UUID id, UUID actor, String action, Instant at) {
        }
        @Override public List<UUID> openLowMarginPackageVersions() {
            return List.of();
        }
        @Override public java.util.Optional<UUID> openLowMarginPackageAlert(UUID packageVersionId) {
            return java.util.Optional.empty();
        }
    }
}
