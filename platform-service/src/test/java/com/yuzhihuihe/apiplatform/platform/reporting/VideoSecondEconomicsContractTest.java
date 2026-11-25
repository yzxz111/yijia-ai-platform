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
 * 证明运营经济性契约对一个视频秒能表达什么、不能表达什么，
 * 因为这决定了视频套餐的毛利率是否可观测。
 *
 * <p>{@link OperatingAlertService#evaluatePackageMargin} 只能对携带
 * 已分摊收入金额的事实触发，而接入服务只能从
 * {@code package_settled_tokens} 除以事件的 token 总量来分摊收入
 * （见 {@link OperatingFactIngestionService#facts}）。一次视频
 * 生成消耗秒数、消耗零 token，因此下面三种形态是
 * 仅有的候选：
 *
 * <ol>
 *   <li>秒数被放在 {@code package_settled_tokens} 里，而 token 计数器
 *       保持诚实地为零 —— 被拒绝：契约要求
 *       {@code package_settled_tokens <= total_tokens} 且
 *       {@code total_tokens = input_tokens + output_tokens}。
 *   <li>一个诚实的零 token 事实，只携带提供方成本 —— 被接受，
 *       但它不分摊任何收入，因此毛利率检查永远不会被触达，
 *       而视频套餐上的亏损保持不可见。
 *   <li>把秒数写进 token 计数器 —— 唯一能触达
 *       告警的形态，它把「300 秒」记为 {@code output_tokens = 300} 和
 *       {@code total_tokens = 300}，随后流入
 *       {@code platform.operating_fact} 以及每日聚合的 token 指标。
 * </ol>
 *
 * <p>其经济性是种子数据中的 {@code VIDEO_PRO} / 1080P 形态：300 秒
 * 收费 199 元，而 1080P 提供方成本为 345 分/秒，即一个亏钱的套餐。
 * 本类就是「诚实的视频事实在今天无法表达」的证据；
 * 它不是一种变通做法。
 */
class VideoSecondEconomicsContractTest {

    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final UUID TENANT = UUID.fromString("92000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("92000000-0000-4000-8000-000000000002");
    private static final UUID EVENT = UUID.fromString("92000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE = UUID.fromString("92000000-0000-4000-8000-000000000004");
    private static final UUID PACKAGE_VERSION = UUID.fromString("92000000-0000-4000-8000-000000000010");
    private static final UUID PROVIDER = UUID.fromString("92000000-0000-4000-8000-000000000006");
    private static final UUID CHANNEL = UUID.fromString("92000000-0000-4000-8000-000000000007");
    private static final Instant NOW = Instant.parse("2026-09-16T08:00:00Z");

    /** 300 秒收费 199 元，即视频设计文档中定价为亏损的那种形态。 */
    private static final long PACKAGE_PRICE_AMOUNT = 19_900L;
    private static final long PACKAGE_SECOND_QUOTA = 300L;
    /** {@code gateway.model_usage_price}：video-max 1080P 为每秒 345 分。 */
    private static final long PROVIDER_COST_PER_SECOND = 345L;
    private static final long BILLED_SECONDS = 300L;
    private static final long PROVIDER_TOTAL_COST = BILLED_SECONDS * PROVIDER_COST_PER_SECOND;

    @Test
    void rejectsSecondsCarriedAsSettledUnitsWhileTheTokenCountersStayHonest() {
        MemoryStore store = new MemoryStore();
        RecordingAlerts alerts = new RecordingAlerts();

        var result = service(store, alerts).ingest(
                bytes(payload(BILLED_SECONDS, true, false)), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.INVALID);
        assertThat(store.audits).containsExactly("invalid_event_contract");
        assertThat(store.facts).isEmpty();
        assertThat(alerts.packageCalls).isZero();
    }

    @Test
    void anHonestZeroTokenVideoFactCarriesItsProviderCostButNeverReachesTheMarginAlert() {
        MemoryStore store = new MemoryStore();
        RecordingAlerts alerts = new RecordingAlerts();

        var result = service(store, alerts).ingest(bytes(payload(0, false, false)), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);
        assertThat(store.facts).hasSize(1);
        OperatingFactIngestionService.FactLine fact = store.facts.getFirst();
        // 成本被记录下来，且 token 计数器是诚实的。
        assertThat(fact.providerCostAmount()).isEqualByComparingTo(BigDecimal.valueOf(PROVIDER_TOTAL_COST));
        assertThat(fact.inputTokens()).isZero();
        assertThat(fact.outputTokens()).isZero();
        assertThat(fact.totalTokens()).isZero();
        // 但没有任何东西说明这个数字是每秒成本，也没有收入
        // 分摊给它，因此套餐毛利率永远不会被评估：一个亏损的
        // 视频套餐不会产生 LOW_MARGIN_PACKAGE 告警。
        assertThat(fact.metadata()).doesNotContainKey("usageDimension");
        assertThat(fact.allocatedUsageRevenueAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(alerts.packageCalls).isZero();
        assertThat(alerts.opened()).isEmpty();
    }

    @Test
    void theOnlyShapeThatReachesTheMarginAlertRecordsSecondsAsTokens() {
        MemoryStore store = new MemoryStore();
        RecordingAlerts alerts = new RecordingAlerts();

        var result = service(store, alerts).ingest(
                bytes(payload(BILLED_SECONDS, true, true)), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);
        assertThat(store.facts).hasSize(1);
        OperatingFactIngestionService.FactLine fact = store.facts.getFirst();
        // 这种错误标注，如实陈述为会被持久化的事实：一次 300 秒的
        // 生成被记为 300 个输出 token 和 300 个总 token。
        assertThat(fact.outputTokens()).isEqualTo(BILLED_SECONDS);
        assertThat(fact.totalTokens()).isEqualTo(BILLED_SECONDS);
        assertThat(fact.allocatedUsageRevenueAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(PACKAGE_PRICE_AMOUNT));
        // 告警路径被触达，并且对这个套餐会开启：该套餐价格
        // 无法覆盖 300 秒按 345 分计的用量。
        assertThat(alerts.packageCalls).isEqualTo(1);
        assertThat(alerts.opened()).hasSize(1);
        OperatingAlertService.Alert alert = alerts.opened().getFirst();
        assertThat(alert.type()).isEqualTo("LOW_MARGIN_PACKAGE");
        assertThat(alert.threshold()).isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat(alert.observed()).isLessThan(new BigDecimal("0.30"));
        assertThat(alert.evidenceFactIds()).hasSize(1);
    }

    private OperatingFactIngestionService service(MemoryStore store, RecordingAlerts alerts) {
        store.allocation = new OperatingFactIngestionService.PackageAllocation(
                BigDecimal.valueOf(PACKAGE_PRICE_AMOUNT), PACKAGE_SECOND_QUOTA, "PAID_ORDER",
                PACKAGE_VERSION);
        return new OperatingFactIngestionService(
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), store, alerts);
    }

    /**
     * 三种候选形态之一的一个视频秒事件。
     *
     * @param settledUnits 写入 {@code package_settled_tokens} 的值
     * @param customerBillable 该事件是否声称向客户计费
     * @param tokensCarrySeconds 秒数是被写入 token
     *        计数器（即错误标注），还是计数器保持为零（诚实）
     */
    private String payload(long settledUnits, boolean customerBillable, boolean tokensCarrySeconds) {
        long outputTokens = tokensCarrySeconds ? settledUnits : 0;
        long totalTokens = outputTokens;
        return """
                {"version":"USAGE_ECONOMICS_V2","event_id":"%s","request_id":"req-video-second",
                 "reservation_id":"92000000-0000-4000-8000-000000000005",
                 "tenant_id":"%s","user_id":"%s","package_instance_id":"%s",
                 "model_code":"video-max","upstream_model":"kling-v3-omni",
                 "provider_id":"%s","channel_id":"%s",
                 "input_tokens":0,"output_tokens":%d,"total_tokens":%d,
                 "billing_mode":"PACKAGE_ONLY","package_settled_tokens":%d,
                 "wallet_settled_amount":0,"usage_estimated":false,"price_missing":false,
                 "provider_input_cost_amount":0,"provider_output_cost_amount":%d,
                 "provider_total_cost_amount":%d,"provider_cost_currency":"CNY",
                 "stages":[
                   {"stage_index":0,"stage_type":"VIDEO_GENERATION","logical_model_code":"video-max",
                    "upstream_model":"kling-v3-omni","provider_id":"%s","channel_id":"%s",
                    "input_tokens":0,"output_tokens":%d,"total_tokens":%d,
                    "provider_input_cost_amount":0,"provider_output_cost_amount":%d,
                    "provider_total_cost_amount":%d,"provider_cost_currency":"CNY",
                    "usage_source":"PROVIDER_ACTUAL","usage_estimated":false,
                    "customer_billable":%s,"provider_billable":true,
                    "reconciliation_status":"NOT_APPLICABLE"}
                 ]}
                """.formatted(EVENT, TENANT, USER, PACKAGE, PROVIDER, CHANNEL,
                outputTokens, totalTokens, settledUnits, PROVIDER_TOTAL_COST, PROVIDER_TOTAL_COST,
                PROVIDER, CHANNEL, outputTokens, totalTokens,
                PROVIDER_TOTAL_COST, PROVIDER_TOTAL_COST, customerBillable);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class MemoryStore implements OperatingFactIngestionService.Store {
        OperatingFactIngestionService.PackageAllocation allocation;
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

    /**
     * 接入汇聚点接入了真实的 {@link OperatingAlertService}，因此
     * 开启的告警是生产决策，而不是测试替身的主张。
     */
    private static final class RecordingAlerts implements OperatingFactIngestionService.AlertSink {

        private final RecordingAlertStore alertStore = new RecordingAlertStore();
        private final OperatingAlertService alertService =
                new OperatingAlertService(alertStore, () -> NOW);
        int packageCalls;

        List<OperatingAlertService.Alert> opened() {
            return alertStore.opened;
        }

        @Override public void providerCost(String model, String provider, BigDecimal requestCost,
                BigDecimal sevenDayP95, boolean missing, List<UUID> factIds) {
        }

        @Override public void packageMargin(UUID packageId, BigDecimal revenue,
                BigDecimal providerCost, List<UUID> factIds) {
            packageCalls++;
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
