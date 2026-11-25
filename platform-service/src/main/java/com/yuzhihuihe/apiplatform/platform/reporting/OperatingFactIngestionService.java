package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatingFactIngestionService {

    private static final Logger log = LoggerFactory.getLogger(OperatingFactIngestionService.class);

    private static final String VERSION_V1 = "USAGE_ECONOMICS_V1";
    private static final String VERSION_V2 = "USAGE_ECONOMICS_V2";
    private static final String VERSION_V3 = "USAGE_ECONOMICS_V3";
    private static final String DIMENSION_TOKEN = "TOKEN";

    /**
     * 摄入层接受的计量维度白名单。
     *
     * <p><b>这个列表必须随网关新增的每一个计量维度一起扩。</b>不在其中的维度
     * 会让该事件被**拒绝**（{@link #validDimensionedQuantities} 返回 false），
     * 而拒绝的后果是那笔成本永远不出现在运营报表里——与「网关不写证据」
     * 是同一个后果的两个半边，且都是静默的：没有任何告警会指向一个
     * 被拒收的事件。
     *
     * <p>{@code AUDIO_SECOND} 由网关 V92 建立、V93 定价，平台侧由 V88 加入
     * 词表。它与 {@code VIDEO_SECOND} 同单位但不同维度，理由见
     * {@code UsageEconomicsEvent.DIMENSION_AUDIO_SECOND}。
     */
    private static final List<String> DIMENSIONS =
            List.of("TOKEN", "VIDEO_SECOND", "IMAGE", "AUDIO_SECOND");
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Store store;
    private final AlertSink alerts;

    @Autowired
    OperatingFactIngestionService(ObjectMapper objectMapper,
            OperatingFactIngestionRepository repository,
            OperatingAlertService alertService) {
        this(objectMapper, Clock.systemUTC(), new RepositoryStore(repository),
                new AlertSink() {
                    @Override public void providerCost(String model, String provider,
                            BigDecimal cost, BigDecimal p95, boolean missing, List<UUID> factIds) {
                        alertService.evaluateProviderCost(model, provider, cost, p95, missing, factIds);
                    }
                    @Override public void packageMargin(UUID packageId, BigDecimal revenue,
                            BigDecimal cost, List<UUID> factIds) {
                        alertService.evaluatePackageMargin(packageId, revenue, cost, factIds);
                    }
                });
    }

    OperatingFactIngestionService(ObjectMapper objectMapper, Clock clock, Store store) {
        this(objectMapper, clock, store, AlertSink.NOOP);
    }

    OperatingFactIngestionService(ObjectMapper objectMapper, Clock clock, Store store, AlertSink alerts) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = Objects.requireNonNull(store, "store");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
    }

    @Transactional
    public Result ingest(byte[] body, String payloadHash) {
        if (body == null || body.length == 0 || !hash(payloadHash)) {
            return invalid(null, payloadHash, "invalid_event_envelope");
        }
        UsageEvent event;
        try {
            event = objectMapper.readValue(body, UsageEvent.class);
        } catch (IOException malformed) {
            return invalid(null, payloadHash, "invalid_event_payload");
        }
        try {
            String validation = validate(event);
            if (validation != null) return invalid(event.eventId(), payloadHash, validation);
            if (!store.tenantUserExists(event.tenantId(), event.userId())) {
                return invalid(event.eventId(), payloadHash, "tenant_user_mismatch");
            }
            if (event.walletSettledAmount() > 0) {
                WalletSnapshot wallet = store.walletSnapshot(
                        event.tenantId(), event.userId(), event.requestId());
                if (wallet == null || wallet.settledAmount() != event.walletSettledAmount()
                        || wallet.inputTokens() != event.inputTokens()
                        || wallet.outputTokens() != event.outputTokens()) {
                    return invalid(event.eventId(), payloadHash, "wallet_settlement_mismatch");
                }
            }
            PackageAllocation allocation = settledUnits(event) == 0 ? null
                    : store.packageAllocation(event.packageInstanceId(), event.tenantId(),
                            event.userId(), eventDimension(event));
            if (settledUnits(event) > 0 && allocation == null) {
                return invalid(event.eventId(), payloadHash, "package_allocation_source_missing");
            }
            List<FactLine> facts = facts(event, payloadHash, allocation);
            Claim claim = store.claim(event.eventId(), payloadHash);
            if (claim == Claim.REPLAYED) return Result.REPLAYED;
            if (claim == Claim.CONFLICT) return Result.CONFLICT;
            Map<UUID, BigDecimal> p95ByFact = new LinkedHashMap<>();
            for (FactLine fact : facts) {
                p95ByFact.put(fact.id(), fact.missingSource() ? null
                        : store.providerCostP95(fact.logicalModel(), fact.provider(), fact.recognizedAt()));
            }
            store.insertAll(facts);
            List<UUID> factIds = facts.stream().map(FactLine::id).toList();
            for (FactLine fact : facts) {
                alerts.providerCost(fact.logicalModel(), fact.provider(), fact.providerCostAmount(),
                        p95ByFact.get(fact.id()), fact.missingSource(), List.of(fact.id()));
            }
            if (allocation != null && "PAID_ORDER".equals(allocation.source())
                    && allocation.packagePlanVersionId() != null
                    && settledUnits(event) > 0) {
                BigDecimal revenue = packageAllocationTotal(event, allocation);
                BigDecimal totalCost = facts.stream().map(FactLine::providerCostAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                // 本事件在套餐中所占份额所承载的套餐成本。比值
                // 的两侧处于同一维度（单位数除以单位数），这正是
                // 按秒套餐能够被评估的原因：改用 token 比值就会用
                // 已结算秒数除以 token 总量。
                BigDecimal packageCost = usageUnits(event) == 0 ? totalCost
                        : totalCost.multiply(BigDecimal.valueOf(settledUnits(event)))
                                .divide(BigDecimal.valueOf(usageUnits(event)), 6, RoundingMode.HALF_UP);
                alerts.packageMargin(allocation.packagePlanVersionId(), revenue, packageCost, factIds);
            }
            return Result.ACCEPTED;
        } catch (ArithmeticException invalidNumber) {
            return invalid(event.eventId(), payloadHash, "invalid_event_numeric_total");
        }
    }

    private String validate(UsageEvent event) {
        if (event == null
                || !List.of(VERSION_V1, VERSION_V2, VERSION_V3).contains(event.version())
                || event.eventId() == null
                || blank(event.requestId()) || event.tenantId() == null || event.userId() == null
                || event.reservationId() == null || blank(event.modelCode())
                || !List.of("PACKAGE_ONLY", "WALLET_ONLY", "HYBRID").contains(event.billingMode())
                || event.inputTokens() < 0 || event.outputTokens() < 0
                || event.totalTokens() != event.inputTokens() + event.outputTokens()
                || event.packageSettledTokens() < 0 || event.packageSettledTokens() > event.totalTokens()
                || event.walletSettledAmount() < 0) {
            return "invalid_event_contract";
        }
        // V3 会声明其单位。V1/V2 不会，且按构造就是以 token 计价的：
        // 维度与合格单位数量都是推导出来的，绝不靠猜测。
        boolean v3 = VERSION_V3.equals(event.version());
        if (v3 && !validDimensionedQuantities(event)) {
            return "invalid_event_contract";
        }
        if (settledUnits(event) > 0 && event.packageInstanceId() == null) {
            return "package_instance_required";
        }
        if (!costsMatch(event.providerInputCostAmount(), event.providerOutputCostAmount(),
                event.providerTotalCostAmount(), event.priceMissing())) return "provider_cost_mismatch";
        boolean v2 = VERSION_V2.equals(event.version());
        boolean dimensioned = v2 || v3;
        if (!event.stages().isEmpty()) {
            long input = 0, output = 0, total = 0, units = 0, inputCost = 0, outputCost = 0;
            HashSet<Integer> stageIndexes = new HashSet<>();
            for (Stage stage : event.stages()) {
                if (stage == null || stage.stageIndex() < 0 || blank(stage.logicalModelCode())
                        || !stageIndexes.add(stage.stageIndex())
                        || stage.inputTokens() < 0 || stage.outputTokens() < 0
                        || stage.totalTokens() != stage.inputTokens() + stage.outputTokens()
                        || (v3 && !validDimensionedStage(stage, event.usageDimension()))
                        || !costsMatch(stage.providerInputCostAmount(), stage.providerOutputCostAmount(),
                                stage.providerTotalCostAmount(), stage.priceMissing())
                        || dimensioned && !validV2Stage(stage)) {
                    return "invalid_stage_contract";
                }
                if (stage.customerBillable(dimensioned)) {
                    input = Math.addExact(input, stage.inputTokens());
                    output = Math.addExact(output, stage.outputTokens());
                    total = Math.addExact(total, stage.totalTokens());
                    units = Math.addExact(units, stageUnits(stage, v3));
                }
                if (stage.providerBillable(dimensioned)) {
                    inputCost = Math.addExact(inputCost, number(stage.providerInputCostAmount()));
                    outputCost = Math.addExact(outputCost, number(stage.providerOutputCostAmount()));
                }
            }
            if (input != event.inputTokens() || output != event.outputTokens()
                    || total != event.totalTokens()
                    || (v3 && units != event.usageUnits())
                    || (event.providerInputCostAmount() != null
                        && (inputCost != event.providerInputCostAmount()
                            || outputCost != event.providerOutputCostAmount()))) {
                return "stage_totals_mismatch";
            }
        }
        return null;
    }

    /**
     * 按维度的数量不变量，只在此声明一次，使数据库检查与
     * 接入检查不会发生漂移：TOKEN 事件的合格单位数就是其 token 总量，
     * 其已结算单位数就是其已结算 token 数；其它任何维度都只在
     * 单位字段中携带数量，且所有 token 计数器均为零。
     *
     * <p>选择拒绝而不是推导是刻意的。如果允许 VIDEO_SECOND 事件
     * 携带 token，该维度就不再具有权威性，秒数也会
     * 重新泄漏回本字段存在正是为了将其挡在外面的那些 token 合计中。
     */
    private static boolean validDimensionedQuantities(UsageEvent event) {
        if (!DIMENSIONS.contains(event.usageDimension())
                || event.usageUnits() < 0
                || event.packageSettledUnits() < 0
                || event.packageSettledUnits() > event.usageUnits()) {
            return false;
        }
        if (DIMENSION_TOKEN.equals(event.usageDimension())) {
            return event.usageUnits() == event.totalTokens()
                    && event.packageSettledUnits() == event.packageSettledTokens();
        }
        return event.inputTokens() == 0 && event.outputTokens() == 0 && event.totalTokens() == 0
                && event.packageSettledTokens() == 0;
    }

    private static boolean validDimensionedStage(Stage stage, String dimension) {
        if (stage.usageUnits() < 0 || !dimension.equals(stage.usageDimension())) {
            // 若某个阶段自身的维度与事件的维度不一致，就会让两个
            // 维度共用同一份载荷和同一笔分摊。
            return false;
        }
        return DIMENSION_TOKEN.equals(dimension)
                ? stage.usageUnits() == stage.totalTokens()
                : stage.inputTokens() == 0 && stage.outputTokens() == 0 && stage.totalTokens() == 0;
    }

    private static String eventDimension(UsageEvent event) {
        return VERSION_V3.equals(event.version()) ? event.usageDimension() : DIMENSION_TOKEN;
    }

    private static long usageUnits(UsageEvent event) {
        return VERSION_V3.equals(event.version()) ? event.usageUnits() : event.totalTokens();
    }

    private static long settledUnits(UsageEvent event) {
        return VERSION_V3.equals(event.version())
                ? event.packageSettledUnits() : event.packageSettledTokens();
    }

    private static long stageUnits(Stage stage, boolean v3) {
        return v3 ? stage.usageUnits() : stage.totalTokens();
    }

    private List<FactLine> facts(UsageEvent event, String payloadHash, PackageAllocation allocation) {
        boolean v2 = VERSION_V2.equals(event.version());
        boolean v3 = VERSION_V3.equals(event.version());
        boolean dimensioned = v2 || v3;
        long eventUnits = usageUnits(event);
        List<Stage> stages = (event.stages().isEmpty() ? List.of(Stage.from(event)) : event.stages())
                .stream()
                .filter(stage -> stage.customerBillable(dimensioned) || stage.providerBillable(dimensioned))
                .toList();
        BigDecimal packageAllocationTotal = packageAllocationTotal(event, allocation);
        BigDecimal walletAllocationTotal = BigDecimal.valueOf(event.walletSettledAmount())
                .setScale(6);
        List<FactLine> result = new ArrayList<>();
        BigDecimal allocatedPackage = BigDecimal.ZERO.setScale(6);
        BigDecimal allocatedWallet = BigDecimal.ZERO.setScale(6);
        int lastCustomerStage = -1;
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).customerBillable(dimensioned)) lastCustomerStage = i;
        }
        for (int i = 0; i < stages.size(); i++) {
            Stage stage = stages.get(i);
            boolean customerBillable = stage.customerBillable(dimensioned);
            boolean providerBillable = stage.providerBillable(dimensioned);
            boolean last = i == lastCustomerStage;
            // 分摊的两侧都处于事件自身的维度中，因此按秒事件
            // 就是除以秒。对 V1/V2 而言合格单位数就是 token 总量，
            // 所以这就是原本已经在执行的算法，只是在此统一表述了一次。
            BigDecimal packageLine = customerBillable
                    ? allocationLine(packageAllocationTotal, allocatedPackage,
                            stageUnits(stage, v3), eventUnits, last)
                    : BigDecimal.ZERO.setScale(6);
            BigDecimal walletLine = customerBillable
                    ? allocationLine(walletAllocationTotal, allocatedWallet,
                            stageUnits(stage, v3), eventUnits, last)
                    : BigDecimal.ZERO.setScale(6);
            allocatedPackage = allocatedPackage.add(packageLine);
            allocatedWallet = allocatedWallet.add(walletLine);
            BigDecimal lineAllocation = packageLine.add(walletLine);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("payloadHash", payloadHash);
            metadata.put("reservationId", event.reservationId().toString());
            metadata.put("packageInstanceId", string(event.packageInstanceId()));
            metadata.put("packageAllocationSource", allocation == null ? "NONE" : allocation.source());
            metadata.put("packageAllocatedUsageRevenueAmount", packageLine);
            metadata.put("walletAllocatedUsageRevenueAmount", walletLine);
            metadata.put("providerPriceId", string(stage.providerPriceId()));
            metadata.put("providerOriginalCostCurrency", stage.providerOriginalCostCurrency());
            metadata.put("providerOriginalCostAmount", stage.providerOriginalCostAmount());
            metadata.put("providerExchangeRate", stage.providerExchangeRate());
            metadata.put("walletSettledAmount", event.walletSettledAmount());
            metadata.put("packageSettledTokens", event.packageSettledTokens());
            if (v3) {
                // 只有带维度的事件才在证据中声明其单位。V1/V2 事实
                // 保留它一贯拥有的载荷：对这些版本而言维度按契约
                // 就是 TOKEN，事实行按列默认值记录它。
                metadata.put("usageDimension", event.usageDimension());
                metadata.put("usageUnits", event.usageUnits());
                metadata.put("packageSettledUnits", event.packageSettledUnits());
            }
            metadata.put("usageSource", stage.usageSource());
            metadata.put("providerRequestId", stage.providerRequestId());
            metadata.put("customerBillable", customerBillable);
            metadata.put("providerBillable", providerBillable);
            metadata.put("reconciliationStatus", stage.reconciliationStatus());
            metadata.put("cacheHitTokens", stage.cacheHitTokens());
            metadata.put("cacheMissTokens", stage.cacheMissTokens());
            metadata.put("cacheWriteTokens", stage.cacheWriteTokens());
            metadata.put("reasoningTokens", stage.reasoningTokens());
            metadata.put("textTokens", stage.textTokens());
            metadata.put("imageTokens", stage.imageTokens());
            metadata.put("audioTokens", stage.audioTokens());
            metadata.put("providerUncachedInputCostAmount", stage.providerUncachedInputCostAmount());
            metadata.put("providerCacheReadCostAmount", stage.providerCacheReadCostAmount());
            metadata.put("providerCacheWriteCostAmount", stage.providerCacheWriteCostAmount());
            metadata.put("providerNormalOutputCostAmount", stage.providerNormalOutputCostAmount());
            metadata.put("providerReasoningCostAmount", stage.providerReasoningCostAmount());
            result.add(new FactLine(UUID.randomUUID(), event.eventId(), stage.stageIndex(), clock.instant(), event.tenantId(),
                    event.userId(), allocation == null ? null : allocation.packagePlanVersionId(),
                    event.requestId(), stage.logicalModelCode(), stage.upstreamModel(),
                    string(stage.channelId()), string(stage.providerId()), stage.inputTokens(),
                    stage.outputTokens(), stage.totalTokens(), stageUnits(stage, v3),
                    eventDimension(event), event.billingMode(), BigDecimal.ZERO,
                    providerBillable ? amount(stage.providerInputCostAmount()) : BigDecimal.ZERO,
                    providerBillable ? amount(stage.providerOutputCostAmount()) : BigDecimal.ZERO,
                    lineAllocation, v2 || v3 ? stage.usageEstimated()
                            : event.usageEstimated() || stage.usageEstimated(),
                    providerBillable && (dimensioned ? stage.priceMissing()
                            : event.priceMissing() || stage.priceMissing()), metadata));
        }
        return result;
    }

    /**
     * 本事件已赚取的套餐收入：套餐价格 × 已结算单位数 ÷ **本次用量的那份额度**。
     *
     * <p>额度是携带维度的 —— 对 {@code VIDEO_SECOND} 套餐，
     * {@code gateway.package_instance.total_token_quota} 存放的是秒数（平台 V54）—— 因此只要
     * 分子是同一单位下的已结算数量，该除法就保持单位一致。
     * 这正是 {@link #settledUnits} 所返回的内容。
     *
     * <p>**方案 C** 之后"那份额度"不再总是主列：一个同时卖视频秒与音频秒的版本
     * 有两份额度，音频用量的分母必须是 {@code audio_seconds_quota}。取哪一列由
     * 仓储层按事件维度决定（同一条 {@code usesSecondBucket} 判据），这里只做
     * 除法的兜底：分母不可用时**记为 0 而不是乱除**，并且留下一条日志——
     * 一次无法归属的收入必须可观测，而不是被算成一个看起来正常的数字。
     */
    private BigDecimal packageAllocationTotal(UsageEvent event, PackageAllocation allocation) {
        if (allocation == null
                || !"PAID_ORDER".equals(allocation.source())
                || allocation.priceAmount().signum() == 0) {
            return BigDecimal.ZERO.setScale(6);
        }
        Long quota = allocation.packageUnitQuota();
        if (quota == null || quota <= 0) {
            log.warn("operating_fact_allocation_quota_unusable event={} dimension={} "
                            + "packageInstanceId={} units={}",
                    event.eventId(), eventDimension(event), event.packageInstanceId(),
                    settledUnits(event));
            return BigDecimal.ZERO.setScale(6);
        }
        return allocation.priceAmount().multiply(BigDecimal.valueOf(settledUnits(event)))
                .divide(BigDecimal.valueOf(quota), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal allocationLine(
            BigDecimal total, BigDecimal allocated, long lineUnits, long eventUnits, boolean last) {
        if (total.signum() == 0) return BigDecimal.ZERO.setScale(6);
        if (last) return total.subtract(allocated);
        return total.multiply(BigDecimal.valueOf(lineUnits))
                .divide(BigDecimal.valueOf(eventUnits), 6, RoundingMode.HALF_UP);
    }

    private Result invalid(UUID eventId, String payloadHash, String reason) {
        store.audit(eventId, payloadHash, reason);
        return Result.INVALID;
    }

    private static boolean costsMatch(Long input, Long output, Long total, boolean missing) {
        if (input == null && output == null && total == null) return missing;
        if (input == null || output == null || total == null) return false;
        return input >= 0 && output >= 0 && total >= 0 && total == input + output;
    }
    private static boolean validV2Stage(Stage stage) {
        if (stage.customerBillable() == null || stage.providerBillable() == null
                || blank(stage.usageSource()) || blank(stage.reconciliationStatus())) {
            return false;
        }
        if (!List.of("PROVIDER_ACTUAL", "LOCAL_ESTIMATED", "BILL_RECONCILED", "MANUAL_CORRECTED")
                .contains(stage.usageSource())
                || !List.of("PENDING", "MATCHED", "MISMATCHED", "ADJUSTED", "NOT_APPLICABLE")
                        .contains(stage.reconciliationStatus())
                || stage.usageEstimated() != "LOCAL_ESTIMATED".equals(stage.usageSource())
                || !validUsageDetails(stage)
                || !validDetailedCosts(stage)) {
            return false;
        }
        return Boolean.TRUE.equals(stage.providerBillable())
                || noProviderCost(stage);
    }
    private static boolean validUsageDetails(Stage stage) {
        if (invalidDetail(stage.cacheHitTokens(), stage.inputTokens())
                || invalidDetail(stage.cacheMissTokens(), stage.inputTokens())
                || invalidDetail(stage.cacheWriteTokens(), stage.inputTokens())
                || invalidDetail(stage.reasoningTokens(), stage.outputTokens())
                || invalidDetail(stage.textTokens(), stage.inputTokens())
                || invalidDetail(stage.imageTokens(), stage.inputTokens())
                || invalidDetail(stage.audioTokens(), stage.inputTokens())) {
            return false;
        }
        long cacheHit = number(stage.cacheHitTokens());
        long cacheWrite = number(stage.cacheWriteTokens());
        return cacheHit <= stage.inputTokens() - cacheWrite;
    }
    private static boolean validDetailedCosts(Stage stage) {
        Long[] details = {
                stage.providerUncachedInputCostAmount(), stage.providerCacheReadCostAmount(),
                stage.providerCacheWriteCostAmount(), stage.providerNormalOutputCostAmount(),
                stage.providerReasoningCostAmount()
        };
        boolean allNull = java.util.Arrays.stream(details).allMatch(value -> value == null);
        if (allNull) return true;
        if (java.util.Arrays.stream(details).anyMatch(value -> value == null || value < 0)
                || stage.providerInputCostAmount() == null
                || stage.providerOutputCostAmount() == null) {
            return false;
        }
        try {
            long input = Math.addExact(stage.providerUncachedInputCostAmount(),
                    Math.addExact(stage.providerCacheReadCostAmount(),
                            stage.providerCacheWriteCostAmount()));
            long output = Math.addExact(stage.providerNormalOutputCostAmount(),
                    stage.providerReasoningCostAmount());
            return input == stage.providerInputCostAmount()
                    && output == stage.providerOutputCostAmount();
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
    private static boolean noProviderCost(Stage stage) {
        return stage.providerPriceId() == null
                && stage.providerCostCurrency() == null
                && stage.providerExchangeRate() == null
                && stage.providerInputCostAmount() == null
                && stage.providerOutputCostAmount() == null
                && stage.providerTotalCostAmount() == null
                && stage.providerOriginalInputCostAmount() == null
                && stage.providerOriginalOutputCostAmount() == null
                && stage.providerOriginalCostAmount() == null
                && stage.providerOriginalCostCurrency() == null
                && stage.providerUncachedInputCostAmount() == null
                && stage.providerCacheReadCostAmount() == null
                && stage.providerCacheWriteCostAmount() == null
                && stage.providerNormalOutputCostAmount() == null
                && stage.providerReasoningCostAmount() == null;
    }
    private static boolean invalidDetail(Long value, long upperBound) {
        return value != null && (value < 0 || value > upperBound);
    }
    private static long number(Long value) { return value == null ? 0 : value; }
    private static BigDecimal amount(Long value) { return BigDecimal.valueOf(number(value)); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String string(Object value) { return value == null ? null : value.toString(); }

    public enum Result { ACCEPTED, REPLAYED, INVALID, CONFLICT }
    enum Claim { CLAIMED, REPLAYED, CONFLICT }

    interface Store {
        Claim claim(UUID eventId, String payloadHash);
        boolean tenantUserExists(UUID tenantId, UUID userId);
        WalletSnapshot walletSnapshot(UUID tenantId, UUID userId, String requestId);
        PackageAllocation packageAllocation(UUID packageInstanceId, UUID tenantId, UUID userId,
                String usageDimension);
        void insertAll(List<FactLine> facts);
        BigDecimal providerCostP95(String model, String provider, Instant before);
        void audit(UUID eventId, String payloadHash, String reason);
    }

    interface AlertSink {
        AlertSink NOOP = new AlertSink() {
            @Override public void providerCost(String model, String provider, BigDecimal requestCost,
                    BigDecimal sevenDayP95, boolean missing, List<UUID> factIds) { }
            @Override public void packageMargin(UUID packageId, BigDecimal revenue,
                    BigDecimal providerCost, List<UUID> factIds) { }
        };
        void providerCost(String model, String provider, BigDecimal requestCost,
                BigDecimal sevenDayP95, boolean missing, List<UUID> factIds);
        void packageMargin(UUID packageId, BigDecimal revenue,
                BigDecimal providerCost, List<UUID> factIds);
    }

    record WalletSnapshot(long settledAmount, long inputTokens, long outputTokens) {}
    /**
     * @param packageUnitQuota 本次用量要摊的那份额度，**单位由事件的维度决定**
     *        （方案 C 起可能取自主列，也可能取自音频那份额度）。
     *        为 null 表示无法确定分母，此时归属记为 0 并留下日志。
     */
    record PackageAllocation(BigDecimal priceAmount, Long packageUnitQuota, String source,
            UUID packagePlanVersionId) {
        PackageAllocation(BigDecimal priceAmount, long packageUnitQuota, String source) {
            this(priceAmount, Long.valueOf(packageUnitQuota), source, null);
        }
        PackageAllocation(BigDecimal priceAmount, long packageUnitQuota, String source,
                UUID packagePlanVersionId) {
            this(priceAmount, Long.valueOf(packageUnitQuota), source, packagePlanVersionId);
        }
    }
    record FactLine(UUID id, UUID eventId, int lineNo, Instant recognizedAt, UUID tenantId, UUID userId,
            UUID packagePlanVersionId,
            String requestId, String logicalModel, String upstreamModel, String channel, String provider,
            long inputTokens, long outputTokens, long totalTokens, long usageUnits,
            String usageDimension, String billingMode,
            BigDecimal confirmedRevenueAmount, BigDecimal providerInputCostAmount,
            BigDecimal providerOutputCostAmount, BigDecimal allocatedUsageRevenueAmount,
            boolean estimated, boolean missingSource, Map<String, Object> metadata) {
        String eventType() { return "PROVIDER_USAGE_COST"; }
        BigDecimal providerCostAmount() { return providerInputCostAmount.add(providerOutputCostAmount); }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record UsageEvent(String version, UUID eventId, String requestId, UUID reservationId,
            UUID tenantId, UUID userId, UUID packageInstanceId, String modelCode,
            String upstreamModel, UUID providerId, UUID channelId, long inputTokens,
            long outputTokens, long totalTokens, String billingMode, long packageSettledTokens,
            long walletSettledAmount, UUID providerPriceId, String providerCostCurrency,
            BigDecimal providerExchangeRate, Long providerInputCostAmount,
            Long providerOutputCostAmount, Long providerTotalCostAmount,
            BigDecimal providerOriginalInputCostAmount, BigDecimal providerOriginalOutputCostAmount,
            BigDecimal providerOriginalCostAmount, String providerOriginalCostCurrency,
            boolean usageEstimated, boolean priceMissing,
            // 仅 V3。V1/V2 载荷中不存在，那里的维度按契约就是 TOKEN，
            // 这些值由 token 计数器推导而非直接读取。
            String usageDimension, long usageUnits, long packageSettledUnits,
            List<Stage> stages) {
        UsageEvent { stages = stages == null ? List.of() : List.copyOf(stages); }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Stage(int stageIndex, String stageType, String logicalModelCode, String upstreamModel,
            UUID providerId, UUID channelId, long inputTokens, long outputTokens, long totalTokens,
            UUID providerPriceId, String providerCostCurrency, BigDecimal providerExchangeRate,
            Long providerInputCostAmount, Long providerOutputCostAmount, Long providerTotalCostAmount,
            BigDecimal providerOriginalInputCostAmount, BigDecimal providerOriginalOutputCostAmount,
            BigDecimal providerOriginalCostAmount, String providerOriginalCostCurrency,
            boolean usageEstimated, boolean priceMissing,
            Long cacheHitTokens, Long cacheMissTokens, Long cacheWriteTokens,
            Long reasoningTokens, Long textTokens, Long imageTokens, Long audioTokens,
            String usageSource, String providerRequestId,
            Boolean customerBillable, Boolean providerBillable, String reconciliationStatus,
            Long providerUncachedInputCostAmount, Long providerCacheReadCostAmount,
            Long providerCacheWriteCostAmount, Long providerNormalOutputCostAmount,
            Long providerReasoningCostAmount,
            // 仅 V3：该阶段的维度及其在该维度下的数量。
            String usageDimension, long usageUnits) {
        boolean customerBillable(boolean dimensioned) {
            return dimensioned ? Boolean.TRUE.equals(customerBillable) : true;
        }
        boolean providerBillable(boolean dimensioned) {
            return dimensioned ? Boolean.TRUE.equals(providerBillable) : true;
        }
        static Stage from(UsageEvent event) {
            return new Stage(0, "DIRECT", event.modelCode(), event.upstreamModel(), event.providerId(),
                    event.channelId(), event.inputTokens(), event.outputTokens(), event.totalTokens(),
                    event.providerPriceId(), event.providerCostCurrency(), event.providerExchangeRate(),
                    event.providerInputCostAmount(), event.providerOutputCostAmount(),
                    event.providerTotalCostAmount(), event.providerOriginalInputCostAmount(),
                    event.providerOriginalOutputCostAmount(), event.providerOriginalCostAmount(),
                    event.providerOriginalCostCurrency(), event.usageEstimated(), event.priceMissing(),
                    null, null, null, null, null, null, null,
                    event.usageEstimated() ? "LOCAL_ESTIMATED" : "PROVIDER_ACTUAL", null,
                    true, true, "NOT_APPLICABLE", null, null, null, null, null,
                    eventDimension(event), OperatingFactIngestionService.usageUnits(event));
        }
    }

    private static final class RepositoryStore implements Store {
        private final OperatingFactIngestionRepository repository;

        private RepositoryStore(OperatingFactIngestionRepository repository) {
            this.repository = repository;
        }

        @Override
        public Claim claim(UUID eventId, String payloadHash) {
            return Claim.valueOf(repository.claim(eventId, payloadHash).name());
        }

        @Override
        public boolean tenantUserExists(UUID tenantId, UUID userId) {
            return repository.tenantUserExists(tenantId, userId);
        }

        @Override
        public WalletSnapshot walletSnapshot(UUID tenantId, UUID userId, String requestId) {
            OperatingFactIngestionRepository.WalletRow row =
                    repository.walletSnapshot(tenantId, userId, requestId);
            return row == null ? null
                    : new WalletSnapshot(row.settledAmount(), row.inputTokens(), row.outputTokens());
        }

        @Override
        public PackageAllocation packageAllocation(
                UUID packageInstanceId, UUID tenantId, UUID userId, String usageDimension) {
            OperatingFactIngestionRepository.PackageAllocationRow row =
                    repository.packageAllocation(
                            packageInstanceId, tenantId, userId, usageDimension);
            return row == null ? null : new PackageAllocation(
                    row.priceAmount(), row.packageUnitQuota(), row.source(),
                    row.packagePlanVersionId());
        }

        @Override
        public void insertAll(List<FactLine> facts) {
            repository.insertAll(facts.stream().map(fact ->
                    new OperatingFactIngestionRepository.FactRow(
                            fact.id(), fact.eventId(), fact.lineNo(), fact.recognizedAt(),
                            fact.tenantId(), fact.userId(), fact.packagePlanVersionId(),
                            fact.requestId(), fact.logicalModel(), fact.upstreamModel(),
                            fact.channel(), fact.provider(), fact.inputTokens(), fact.outputTokens(),
                            fact.totalTokens(), fact.usageUnits(), fact.usageDimension(),
                            fact.billingMode(), fact.confirmedRevenueAmount(),
                            fact.providerInputCostAmount(), fact.providerOutputCostAmount(),
                            fact.allocatedUsageRevenueAmount(), fact.estimated(),
                            fact.missingSource(), fact.metadata())).toList());
        }

        @Override
        public BigDecimal providerCostP95(String model, String provider, Instant before) {
            return repository.providerCostP95(model, provider, before);
        }

        @Override
        public void audit(UUID eventId, String payloadHash, String reason) {
            repository.audit(eventId, payloadHash, reason);
        }
    }
}
