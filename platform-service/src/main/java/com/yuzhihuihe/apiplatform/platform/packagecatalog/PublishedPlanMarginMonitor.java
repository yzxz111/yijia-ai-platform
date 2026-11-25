package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.reporting.OperatingAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按照当前生效的成本基准重新校验每一个已发布的套餐。
 *
 * <p>发布守卫在该版本被发布的那一刻只检查一次，而已发布的版本是不可变的。
 * 此后没有任何东西重新检查过它们，因此一个版本可能在毛利率健康时获批，
 * 随后该毛利率被后续对成本基准的修改摧毁，
 * 无声且永久。这并非假设：把 {@code gateway.provider_model_price} 更正为
 * 厂商发票价格（V48）后，暴露出三个线上套餐是按被低估了
 * 4.5 倍的成本定价的，而低毛利告警无法报告此事，
 * 因为该告警由已结算的用量驱动 —— 一个套餐无论是否有人在你看的那个
 * 窗口里购买它，都在亏钱。
 *
 * <p>本任务填补了这个缺口：它为每一个已发布（PUBLISHED）版本重新计算
 * 当前成本基准所隐含的最坏情况 —— 与守卫相同的算术，
 * 整个额度先由允许范围内最贵的别名填满 ——
 * 并在该最坏情况低于该版本所属维度的下限时，
 * 发出已有的 {@code LOW_MARGIN_PACKAGE} 告警。
 *
 * <p>它只报告，绝不修改。下线或重新定价一个版本始终是人的决定，
 * 而一个悄悄改动了客户套餐的任务，
 * 会比它正在盯防的那个故障更糟。
 *
 * <p>它降级（fail soft）：无法访问网关时保留上一次评估的告警
 * 而不清除它们，因为「无法测量」
 * 绝不能被读作「已测量且正常」。
 */
@Component
@ConditionalOnProperty(
        name = "platform.package-margin.revalidation.enabled",
        havingValue = "true",
        matchIfMissing = true)
class PublishedPlanMarginMonitor {

    private static final Logger log = LoggerFactory.getLogger(PublishedPlanMarginMonitor.class);

    /**
     * 这几个维度被售出的每分钟速率是**每分钟单位数**，因此它们的成本
     * 基准必须从网关的单位成本接口取，而不是从每百万 token 的价格取。
     *
     * <p>漏掉一个维度会让该维度的已发布版本在这个巡检里**拿不到成本**，
     * 于是它既不会被重算毛利、也不会触发任何毛利告警——报告里它只是安静地
     * 缺席。而 §44 那句注释说的正是「缺席绝不能被读作已测量且正常」。
     */
    private static final Set<String> SECOND_DIMENSIONS =
            Set.of("VIDEO_SECOND", "IMAGE", "AUDIO_SECOND");

    private final PackageVersionRepository repository;
    private final ProviderPriceClient providerPriceClient;
    private final ProviderUnitCostClient providerUnitCostClient;
    private final DimensionPricePolicyRepository pricePolicyRepository;
    private final ModelUsageDimensionRepository dimensionRepository;
    private final OperatingAlertService alertService;

    PublishedPlanMarginMonitor(
            PackageVersionRepository repository,
            ProviderPriceClient providerPriceClient,
            ProviderUnitCostClient providerUnitCostClient,
            DimensionPricePolicyRepository pricePolicyRepository,
            ModelUsageDimensionRepository dimensionRepository,
            OperatingAlertService alertService) {
        this.repository = repository;
        this.providerPriceClient = providerPriceClient;
        this.providerUnitCostClient = providerUnitCostClient;
        this.pricePolicyRepository = pricePolicyRepository;
        this.dimensionRepository = dimensionRepository;
        this.alertService = alertService;
    }

    @Scheduled(
            initialDelayString = "${platform.package-margin.revalidation.initial-delay-ms:600000}",
            fixedDelayString = "${platform.package-margin.revalidation.fixed-delay-ms:21600000}")
    void scheduledRevalidation() {
        try {
            revalidateOnce(Instant.now());
        } catch (RuntimeException error) {
            // 抛异常的定时方法会被 Spring 静默地重新调度，这会
            // 掩盖持续性的失败。记录日志，让下一次运行再试。
            log.warn("published_plan_margin_revalidation_failed error={}",
                    error.getClass().getSimpleName());
        }
    }

    /**
     * 重新测量每一个已发布的套餐，并按照测量结果对毛利率告警进行对账。
     *
     * <p>两个方向，而且两者都必要。最坏情况已低于其下限的版本，其告警会被
     * 开启（OPENED），与之前一样。告警处于开启但条件已不再成立的版本，其
     * 告警会被关闭（CLOSED），
     * 因为另一种做法 —— 把它留给运维 —— 正是
     * V61 已使其过时的三个告警一直保持红色、
     * 直到在那次迁移中被人手工解决的原因。
     * 告警只在恰好两种情况下被关闭：
     *
     * <ul>
     *   <li>该版本已发布且其重新计算的最坏情况已重新达到
     *       或高于其下限（恢复），或
     *   <li>该版本已完全不再是 PUBLISHED（被取代或已下线），因此
     *       该告警描述的是一个并不在售的东西。
     * </ul>
     *
     * <p>它在三处失败即关闭（fail-closed），每一处都是刻意的。一个授予了
     * 成本基准无法定价的别名的版本，会被计为跳过（COUNTED）且其告警保持
     * 开启：填充过程会跳过未定价的别名，因此测得的最坏情况只会低估真实的
     * 最坏情况，而「无法测量」绝不能被读作「已测量且正常」。
     * 一个仍然低于其下限的版本绝不被解决，
     * 即使它同时也未定价。一个仍是 PUBLISHED 但完全没有被评估的
     * 版本 —— 零价格赠品，或归档套餐下的已发布版本 —— 保留其告警，
     * 因为监控器没有证明关于它的任何事情。
     *
     * @return 被评估并发现低于其下限的已发布版本数量
     */
    int revalidateOnce(Instant observedAt) {
        List<PackageVersionRepository.PublishedPlanRow> published = repository.findPublishedPlans();

        Set<String> tokenModels = new LinkedHashSet<>();
        Set<String> secondModels = new LinkedHashSet<>();
        for (PackageVersionRepository.PublishedPlanRow plan : published) {
            for (PackageVersionRepository.ModelRow model : repository.findModels(plan.versionId())) {
                if (SECOND_DIMENSIONS.contains(String.valueOf(plan.usageDimension()))) {
                    secondModels.add(model.modelCode());
                } else {
                    tokenModels.add(model.modelCode());
                }
            }
        }

        // 每个维度一次调用：使用守卫自己的来源，因此这里测量到的就是
        // 发布时将会测量到的东西，而不是另一种意见。
        Map<String, BigDecimal> tokenCosts = tokenModels.isEmpty()
                ? Map.of()
                : providerPriceClient.nonPromotionalOutputPrices(tokenModels);
        Map<String, BigDecimal> secondCosts = secondModels.isEmpty()
                ? Map.of()
                : providerUnitCostClient.effectiveUnitCosts(secondModels);

        int evaluated = 0;
        int belowFloor = 0;
        int resolved = 0;
        int skippedUnknownCost = 0;
        Set<UUID> measured = new LinkedHashSet<>();
        Set<UUID> unprovable = new LinkedHashSet<>();
        // 只读取一次，并且只解决确实有告警处于开启状态的版本：要求
        // 存储关闭一个并不存在的告警，会成为每个套餐每次运行的一次
        // 写入尝试，而且会使「本次运行什么都没解决」与「本次运行
        // 试图解决所有东西」无法区分。
        Set<UUID> openAlerts =
                new LinkedHashSet<>(alertService.openPublishedPlanMarginVersions());

        for (PackageVersionRepository.PublishedPlanRow plan : published) {
            // 零价格版本按设计就是赠品，并非毛利率失败。它
            // 也不被测量，因此它的告警（如果有更早的成本基准开启过）保持
            // 不变，而不是凭一次从未运行的检查就将其解决。
            if (plan.priceAmount() <= 0) {
                continue;
            }
            evaluated++;
            measured.add(plan.versionId());

            String dimension = String.valueOf(plan.usageDimension());
            List<PackageVersionRepository.ModelRow> models =
                    repository.findModels(plan.versionId());

            // 方案 C：一个版本可以同时卖视频秒与音频秒，**每一份额度各自摊成本**。
            // 只用主维度那一份额度（`totalTokenQuota`）去摊全部模型，会让音频那一半
            // 按视频秒的额度计价——少算成本，而少算的方向正是"一个已经亏损的
            // 混合版本永远不会触发低毛利告警"。判据与发布守卫
            // （PackageVersionService.assessEveryDimension）以及网关侧的
            // LockedPackage.usesSecondBucket 逐字相同。
            Map<String, List<PackageVersionRepository.ModelRow>> byDimension =
                    groupByOwnDimension(models, dimension);

            BigDecimal worstDimensionMargin = null;
            BigDecimal worstDimensionFloor = null;
            String worstDimension = null;
            boolean unknownCost = false;
            boolean insideWarningBand = false;
            boolean missingSecondBucket = false;
            for (Map.Entry<String, List<PackageVersionRepository.ModelRow>> entry
                    : byDimension.entrySet()) {
                String modelDimension = entry.getKey();
                Map<String, BigDecimal> costs = SECOND_DIMENSIONS.contains(modelDimension)
                        ? secondCosts : tokenCosts;
                PackageMarginPolicy.DimensionPolicy policy =
                        dimensionPolicyFor(modelDimension, costs);
                Long quota = quotaForDimension(plan, modelDimension);
                if (quota == null) {
                    // 一个模型的维度需要第二份额度、而版本没有它：这是版本自身
                    // 不自洽（发布守卫与两侧 CHECK 都会拒绝它）。不能猜一个额度，
                    // 因此这一版按"无法测量"处理——保留告警，绝不解决它。
                    missingSecondBucket = true;
                    break;
                }
                if (entry.getValue().stream()
                        .anyMatch(model -> costs.get(model.modelCode()) == null)) {
                    unknownCost = true;
                }
                BigDecimal dimensionCost = worstCaseCost(
                        entry.getValue(), costs, quota,
                        BigDecimal.valueOf(policy.quotaUnitsPerCostUnit()));
                BigDecimal dimensionMargin = BigDecimal.valueOf(plan.priceAmount())
                        .subtract(dimensionCost)
                        .divide(BigDecimal.valueOf(plan.priceAmount()), 8, RoundingMode.HALF_UP);
                // 与守卫同一个算法：每个维度都以**整个售价**为分母（守卫的
                // assess 就是这么算的），因此两个维度的毛利率之和不等于任何东西，
                // 也不该被当成一个可加的量。
                if (dimensionMargin.compareTo(policy.minimumMargin()) < 0
                        && (worstDimensionMargin == null
                                || dimensionMargin.compareTo(worstDimensionMargin) < 0)) {
                    worstDimensionMargin = dimensionMargin;
                    worstDimensionFloor = policy.minimumMargin();
                    worstDimension = modelDimension;
                }
                if (dimensionMargin.compareTo(policy.minimumMargin()) >= 0
                        && dimensionMargin.compareTo(policy.warningMargin()) < 0) {
                    insideWarningBand = true;
                }
            }

            if (missingSecondBucket) {
                skippedUnknownCost++;
                unprovable.add(plan.versionId());
                log.warn("published_plan_margin_unmeasurable plan={} dimension={} "
                                + "reason=second_bucket_missing",
                        plan.code(), dimension);
                continue;
            }

            // 计数在**判定之前**：一个既有未定价别名、又确实跌破下限的版本
            // 两件事都要记下来。旧版本就是这么数的，而这一行汇总日志是运维
            // 判断"它跑了且什么都没发现"的唯一凭据——少一个计数会让
            // 一次被跳过的测量读起来像一次通过的测量。
            if (unknownCost) {
                skippedUnknownCost++;
                unprovable.add(plan.versionId());
            }

            if (worstDimensionMargin != null) {
                belowFloor++;
                alertService.evaluatePublishedPlanMargin(
                        plan.versionId(), plan.code(), worstDimensionMargin,
                        worstDimensionFloor, observedAt);
                log.warn("published_plan_below_margin_floor plan={} dimension={} price={} "
                                + "margin={} floor={}",
                        plan.code(), worstDimension, plan.priceAmount(),
                        worstDimensionMargin, worstDimensionFloor);
                continue;
            }

            // 已达到或高于下限。套餐中若存在未定价的别名，这便不能证明任何
            // 任何事情，因此告警保留，本次运行把它报告为跳过。
            if (unknownCost) {
                log.warn("published_plan_margin_unmeasurable plan={} dimension={} reason=unpriced_alias",
                        plan.code(), dimension);
                continue;
            }

            // 决策 1 的提前预警：在下限与预警下限之间发布套餐会设置
            // margin_warning，而这就是在该版本仍在售期间持续这么说的重新
            // 校验。它是预警，不是告警：该版本尚未击穿下限，因此不会
            // 开启任何 LOW_MARGIN_PACKAGE，
            // 也不会因为它而解决任何东西。
            if (insideWarningBand) {
                log.warn("published_plan_inside_margin_warning_band plan={} dimension={} price={}",
                        plan.code(), dimension, plan.priceAmount());
            }

            if (openAlerts.contains(plan.versionId())
                    && alertService.resolvePublishedPlanMargin(plan.versionId(),
                            OperatingAlertService.RESOLUTION_RECOVERED, observedAt) > 0) {
                resolved++;
                log.info("published_plan_margin_alert_resolved plan={} dimension={} "
                                + "reason=RECOVERED",
                        plan.code(), dimension);
            }
        }

        // 监控器未测量的版本所对应的告警。唯一可以关闭的是
        // 已不再是 PUBLISHED 的版本；仍然已发布但在上面被跳过的
        // 版本保留其告警。
        List<UUID> unmeasured = openAlerts.stream()
                .filter(versionId -> !measured.contains(versionId))
                .filter(versionId -> !unprovable.contains(versionId))
                .toList();
        Set<UUID> stillPublished = repository.findPublishedVersionIds(unmeasured);
        for (UUID versionId : unmeasured) {
            if (stillPublished.contains(versionId)) {
                log.info("published_plan_margin_alert_kept versionId={} reason=STILL_PUBLISHED",
                        versionId);
                continue;
            }
            if (alertService.resolvePublishedPlanMargin(versionId,
                    OperatingAlertService.RESOLUTION_NO_LONGER_PUBLISHED, observedAt) > 0) {
                resolved++;
                log.info("published_plan_margin_alert_resolved versionId={} "
                        + "reason=NO_LONGER_PUBLISHED", versionId);
            }
        }

        // 无论计数是多少都记录日志：「它运行了且什么都没发现」必须
        // 可观测，而不是靠沉默来推断。
        log.info("published_plan_margin_revalidation evaluated={} belowFloor={} resolved={} "
                        + "skippedUnknownCost={}",
                evaluated, belowFloor, resolved, skippedUnknownCost);
        return belowFloor;
    }

    /**
     * 该维度的策略行：有记录行时用它，否则用与守卫相同的后备策略，
     * 因此下限、预警下限**与换算因子**始终来自同一个来源。
     *
     * <p>换算因子必须取自这里，而不是按"是不是秒维度"写死的 1 或 1_000_000：
     * 那是守卫读的同一个列（{@code package_dimension_price_policy.quota_units_per_cost_unit}），
     * 写死会让两者在运维改过那一列之后报出两个不同的毛利率——而本任务存在的
     * 全部理由就是"测量守卫将会测量到的东西，而不是另一种意见"。
     */
    private PackageMarginPolicy.DimensionPolicy dimensionPolicyFor(
            String dimension, Map<String, BigDecimal> costs) {
        return pricePolicyRepository.find(dimension)
                .map(row -> PackageMarginPolicy.fromPolicyRow(
                        dimension, costs, row.quotaUnitsPerCostUnit(),
                        row.minimumMargin(), row.warningMargin(), row.unitLabel()))
                .orElseGet(() -> PackageMarginPolicy.fallbackPolicy(dimension, costs));
    }

    /**
     * 按**每个模型自己的**维度分组。
     *
     * <p>模型没有登记维度时退回版本的主维度——这正是 V89/V90/V91 之前每个模型的
     * 形态，因此旧行的行为完全不变。
     */
    private Map<String, List<PackageVersionRepository.ModelRow>> groupByOwnDimension(
            List<PackageVersionRepository.ModelRow> models, String versionDimension) {
        Map<String, String> dimensions = dimensionRepository.dimensionByModelCode();
        Map<String, List<PackageVersionRepository.ModelRow>> byDimension = new LinkedHashMap<>();
        for (PackageVersionRepository.ModelRow model : models) {
            String dimension = dimensions.getOrDefault(model.modelCode(), versionDimension);
            byDimension.computeIfAbsent(dimension, ignored -> new ArrayList<>()).add(model);
        }
        return byDimension;
    }

    /**
     * 该维度那份额度：主维度取 {@code totalTokenQuota}，而 {@code AUDIO_SECOND}
     * 在**混合**版本上取第二份额度。
     *
     * <p>判据与发布守卫的 {@code assessEveryDimension} 以及网关侧的
     * {@code LockedPackage.usesSecondBucket} 逐字相同：一个纯音频版本
     * （主维度就是 AUDIO_SECOND）的音频秒住在主列里，没有第二份额度。
     *
     * @return 该维度的额度；需要第二份额度而版本没有它时为 null
     *         （不自洽的版本，调用方按"无法测量"处理）
     */
    private static Long quotaForDimension(
            PackageVersionRepository.PublishedPlanRow plan, String dimension) {
        boolean secondBucket = "AUDIO_SECOND".equals(dimension)
                && !"AUDIO_SECOND".equals(plan.usageDimension());
        return secondBucket ? plan.audioSecondsQuota() : plan.totalTokenQuota();
    }

    /**
     * 守卫自己的填充方式：允许范围内最贵的别名优先，
     * 每个消耗 min(cap, remaining)，
     * 因此 cap 为 NULL 表示「该别名可以取走剩余额度」。
     *
     * <p>映射中没有价格的别名会被跳过，而不是被当作免费 —— 守卫对缺失的
     * 成本失败即关闭（fail-closed），而这项检查不得比它所校验的守卫更
     * 宽松。跳过只会低估最坏情况，因此触发本告警的版本确实低于下限；
     * 未触发的版本会由调用方的日志报告为未知，而不是被静默放过。
     */
    static BigDecimal worstCaseCost(
            List<PackageVersionRepository.ModelRow> models,
            Map<String, BigDecimal> unitCosts,
            long quota,
            BigDecimal quotaUnitsPerCostUnit) {
        List<PackageVersionRepository.ModelRow> priced = new ArrayList<>();
        for (PackageVersionRepository.ModelRow model : models) {
            if (unitCosts.get(model.modelCode()) != null) {
                priced.add(model);
            }
        }
        priced.sort((left, right) -> unitCosts.get(right.modelCode())
                .compareTo(unitCosts.get(left.modelCode())));

        long remaining = quota;
        BigDecimal cost = BigDecimal.ZERO;
        for (PackageVersionRepository.ModelRow model : priced) {
            if (remaining <= 0) {
                break;
            }
            Long cap = model.modelTokenQuota();
            long take = cap == null ? remaining : Math.min(cap, remaining);
            cost = cost.add(unitCosts.get(model.modelCode())
                    .multiply(BigDecimal.valueOf(take)));
            remaining -= take;
        }
        return cost.divide(quotaUnitsPerCostUnit, 4, RoundingMode.HALF_UP);
    }
}
