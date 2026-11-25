package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yuzhihuihe.apiplatform.platform.reporting.OperatingAlertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 此前缺失的重新校验，以及它现在额外执行的对账。
 *
 * <p>发布时对版本检查一次，而已发布的版本是不可变的，因此之后
 * 对成本基准的改动可能悄无声息地摧毁一个在线套餐的毛利，
 * 且没有任何东西会上报它：现有的低毛利告警由已结算的用量驱动，
 * 而一个套餐无论是否有人在恰好查看的那个窗口内购买过它，都在亏钱。
 * 把提供方成本表更正为供应商发票后，恰好暴露出
 * 三个处于该状态的在线套餐。
 *
 * <p>这些测试钉住两个方面：一个已发布版本若其最坏情形如今跌破其
 * 下限，就必须发出该告警；而仍然高于下限的则不得发出。它们还钉住
 * 另一个方向，那是本作业第一个版本所缺失的——它开启的告警一旦
 * 条件不再成立就必须再次关闭，而当监控无法证明任何事情时
 * （某个别名未定价、某个版本仍处于已发布状态但
 * 未被测量）或该版本仍低于其下限时，则不得关闭。
 */
class PublishedPlanMarginMonitorTest {

    private static final UUID TOKEN_VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID VIDEO_VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final Instant NOW = Instant.parse("2026-09-16T05:00:00Z");

    private PackageVersionRepository repository;
    private ProviderPriceClient providerPriceClient;
    private ProviderUnitCostClient providerUnitCostClient;
    private DimensionPricePolicyRepository pricePolicyRepository;
    private ModelUsageDimensionRepository dimensionRepository;
    private OperatingAlertService alertService;
    private PublishedPlanMarginMonitor monitor;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        repository = mock(PackageVersionRepository.class);
        providerPriceClient = mock(ProviderPriceClient.class);
        providerUnitCostClient = mock(ProviderUnitCostClient.class);
        pricePolicyRepository = mock(DimensionPricePolicyRepository.class);
        dimensionRepository = mock(ModelUsageDimensionRepository.class);
        alertService = mock(OperatingAlertService.class);
        monitor = new PublishedPlanMarginMonitor(
                repository, providerPriceClient, providerUnitCostClient,
                pricePolicyRepository, dimensionRepository, alertService);
        // 默认没有登记维度的别名 → 每个模型都跟随版本的主维度，这正是 V89/V90/V91
        // 之前的形态，因此本节里那些单维度用例的行为完全不变。
        when(dimensionRepository.dimensionByModelCode()).thenReturn(Map.of());
        when(pricePolicyRepository.find(anyString()))
                .thenReturn(java.util.Optional.of(new DimensionPricePolicyRepository.Row(
                        "TOKEN", 1_000_000L, "tokens",
                        new BigDecimal("0.30000"), new BigDecimal("0.40000"))));
        // 在运行之前挂载：汇总行就是断言目标。
        logs = new ListAppender<>();
        logs.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(PublishedPlanMarginMonitor.class)).addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        if (logs != null) {
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                    .getLogger(PublishedPlanMarginMonitor.class)).detachAppender(logs);
        }
    }

    /**
     * 真正要紧的那次回归：STARTER 承诺 19 元人民币 5,000,000 个 token，而
     * 背后的别名每百万输出 token 成本为 900 分。在它发布时
     * 生效的成本基准下，这看起来是 46.8% 的毛利；而在依据发票推导出的基准下，
     * 同一版本是 137% 的亏损。此前没有任何东西在监视这一转变。
     */
    @Test
    void aPublishedPlanThatTheCorrectedCostBasisMakesUnderwaterRaisesTheAlert() {
        publishedPlan("STARTER", 1_900L, 5_000_000L);
        when(repository.findModels(TOKEN_VERSION_ID)).thenReturn(List.of(
                new PackageVersionRepository.ModelRow("text-standard", null, "TEXT")));
        // deepseek-v4-flash 输出经更正的供应商数字：每百万 9.00 元人民币。
        when(providerPriceClient.nonPromotionalOutputPrices(any()))
                .thenReturn(Map.of("text-standard", new BigDecimal("900")));

        int belowFloor = monitor.revalidateOnce(NOW);

        assertThat(belowFloor).isEqualTo(1);
        // 5,000,000 个 token x 900 分/百万 = 4,500 分，对应 1,900 分的价格 -> -1.3684。
        verify(alertService).evaluatePublishedPlanMargin(
                eq(TOKEN_VERSION_ID), eq("STARTER"),
                eq(new BigDecimal("-1.36842105")), eq(new BigDecimal("0.30000")), eq(NOW));
        // 低于下限与「已恢复」正相反：告警必须保持开启。
        verify(alertService, never()).resolvePublishedPlanMargin(any(), any(), any());
    }

    @Test
    void aPublishedPlanThatStillClearsItsFloorRaisesNothing() {
        publishedPlan("ADVANCED", 19_900L, 20_000_000L);
        when(repository.findModels(TOKEN_VERSION_ID)).thenReturn(List.of(
                new PackageVersionRepository.ModelRow("text-pro", 10_000_000L, "TEXT"),
                new PackageVersionRepository.ModelRow("file-basic", null, "TEXT")));
        // 最贵的优先：10M 的 text-pro 按 2700 分/百万 = 27,000，随后 10M 的 file-basic 按
        // 200 分/百万 = 2,000 -> 19,900 分中的 29,000 分，这确实亏本；改为配一个能通过的
        // 基准，使本用例测试通过分支。
        when(providerPriceClient.nonPromotionalOutputPrices(any())).thenReturn(Map.of(
                "text-pro", new BigDecimal("600"),
                "file-basic", new BigDecimal("50")));

        int belowFloor = monitor.revalidateOnce(NOW);

        // 10M x 600 分/百万 = 6,000，随后 10M x 50 分/百万 = 500 -> 6,500 分；毛利 67.3%。
        assertThat(belowFloor).isZero();
        verify(alertService, never()).evaluatePublishedPlanMargin(
                any(), anyString(), any(), any(), any());
    }

    /** 免费赠送是产品决策，而不是毛利事故。 */
    @Test
    void aZeroPricePlanIsNotReportedAsBelowFloor() {
        publishedPlan("FREE", 0L, 100_000L);
        when(repository.findModels(TOKEN_VERSION_ID)).thenReturn(List.of(
                new PackageVersionRepository.ModelRow("file-basic", null, "TEXT")));
        when(providerPriceClient.nonPromotionalOutputPrices(any()))
                .thenReturn(Map.of("file-basic", new BigDecimal("200")));

        assertThat(monitor.revalidateOnce(NOW)).isZero();
        verify(alertService, never()).evaluatePublishedPlanMargin(
                any(), anyString(), any(), any(), any());
        // 未被测量，因此也未被判定为已恢复：这次运行对免费赠送证明不了任何事情。
        verify(alertService, never()).resolvePublishedPlanMargin(any(), any(), any());
        assertThat(summary()).contains("evaluated=0 belowFloor=0 resolved=0 skippedUnknownCost=0");
    }

    /**
     * 基准无法定价的成本绝不能被读作免费。守卫在成本缺失时
     * 失败即关闭（fail-closed），因此本项检查会跳过该别名，且只可能低估最坏情形——
     * 它绝不能从一个缺失的数字制造出一个通过的毛利。
     */
    @Test
    void anUnpricedAliasIsSkippedRatherThanTreatedAsFree() {
        publishedPlan("ENTERPRISE", 24_900L, 100_000_000L);
        when(repository.findModels(TOKEN_VERSION_ID)).thenReturn(List.of(
                new PackageVersionRepository.ModelRow("text-pro", 10_000_000L, "TEXT"),
                new PackageVersionRepository.ModelRow("unpriced-alias", null, "TEXT")));
        // 只有 text-pro 有定价。若把未定价的别名当作免费，整个额度将是
        // 100M x 2700 分/百万 = 270,000 分，但填充被限制在 10M，因此
        // 上报的最坏情形是 27,000 分 -> 8% 的毛利，仍低于下限。
        when(providerPriceClient.nonPromotionalOutputPrices(any()))
                .thenReturn(Map.of("text-pro", new BigDecimal("2700")));

        int belowFloor = monitor.revalidateOnce(NOW);

        assertThat(belowFloor).isEqualTo(1);
        verify(alertService, times(1)).evaluatePublishedPlanMargin(
                eq(TOKEN_VERSION_ID), eq("ENTERPRISE"),
                eq(new BigDecimal("-0.08433735")), eq(new BigDecimal("0.30000")), eq(NOW));
        verify(alertService, never()).resolvePublishedPlanMargin(any(), any(), any());
    }

    /**
     * ……而当某个别名未定价、同时部分填充确实越过了下限时，
     * 所测得的毛利证明不了任何事情：告警保持开启，且本次运行
     * 把该版本计为已跳过，而不是上报一个它无法证明的恢复。
     */
    @Test
    void anUnpricedAliasLeavesTheAlertOpenAndIsCountedAsSkipped() {
        publishedPlan("ADVANCED", 10_000L, 1_000_000L);
        when(repository.findModels(TOKEN_VERSION_ID)).thenReturn(List.of(
                new PackageVersionRepository.ModelRow("text-standard", null, "TEXT"),
                new PackageVersionRepository.ModelRow("unpriced-alias", null, "TEXT")));
        // 1,000,000 个 token x 900 分/百万 = 900 分，对应 10,000 分 -> 已定价部分为 91%。
        when(providerPriceClient.nonPromotionalOutputPrices(any()))
                .thenReturn(Map.of("text-standard", new BigDecimal("900")));
        when(alertService.openPublishedPlanMarginVersions()).thenReturn(List.of(TOKEN_VERSION_ID));

        assertThat(monitor.revalidateOnce(NOW)).isZero();

        verify(alertService, never()).evaluatePublishedPlanMargin(
                any(), anyString(), any(), any(), any());
        verify(alertService, never()).resolvePublishedPlanMargin(any(), any(), any());
        assertThat(summary()).contains("evaluated=1 belowFloor=0 resolved=0 skippedUnknownCost=1");
    }

    /**
     * 本作业此前无法表达的「恢复」：该版本处于已发布状态，成本
     * 基准已转向有利于它，而它所开启的告警由测量到它的那次运行
     * 关闭。此处毛利为 37.9%——高于 30% 的下限，位于 40% 的预警
     * 区间内，这正是决策 1 的预警情形：上报，但不告警。
     */
    @Test
    void aRecoveredPlanHasItsOpenAlertResolved() {
        publishedPlan("STARTER", 2_900L, 2_000_000L);
        when(repository.findModels(TOKEN_VERSION_ID)).thenReturn(List.of(
                new PackageVersionRepository.ModelRow("text-standard", null, "TEXT")));
        when(providerPriceClient.nonPromotionalOutputPrices(any()))
                .thenReturn(Map.of("text-standard", new BigDecimal("900")));
        when(alertService.openPublishedPlanMarginVersions()).thenReturn(List.of(TOKEN_VERSION_ID));
        when(alertService.resolvePublishedPlanMargin(TOKEN_VERSION_ID, OperatingAlertService.RESOLUTION_RECOVERED, NOW)).thenReturn(1);

        assertThat(monitor.revalidateOnce(NOW)).isZero();

        verify(alertService).resolvePublishedPlanMargin(TOKEN_VERSION_ID, OperatingAlertService.RESOLUTION_RECOVERED, NOW);
        verify(alertService, never()).evaluatePublishedPlanMargin(
                any(), anyString(), any(), any(), any());
        assertThat(summary()).contains("evaluated=1 belowFloor=0 resolved=1 skippedUnknownCost=0");
        // 37.9% 的毛利被作为预警上报，尽管它并非告警，
        // 这正是提高后的预警下限的用途。
        assertThat(allLogs()).contains("published_plan_inside_margin_warning_band");
    }

    /**
     * 一个不再是 PUBLISHED 的版本——已被取代或已停用——完全不会被测量，
     * 而它的告警描述的是并不在售的东西。这是运行可以关闭告警的
     * 第二种、也是仅有的另一种方式。
     */
    @Test
    void anAlertForAVersionThatIsNoLongerPublishedIsResolvedWithoutMeasuringIt() {
        when(repository.findPublishedPlans()).thenReturn(List.of());
        when(alertService.openPublishedPlanMarginVersions())
                .thenReturn(List.of(VIDEO_VERSION_ID));
        when(repository.findPublishedVersionIds(any())).thenReturn(Set.of());
        when(alertService.resolvePublishedPlanMargin(VIDEO_VERSION_ID, OperatingAlertService.RESOLUTION_NO_LONGER_PUBLISHED, NOW)).thenReturn(1);

        assertThat(monitor.revalidateOnce(NOW)).isZero();

        verify(alertService).resolvePublishedPlanMargin(VIDEO_VERSION_ID, OperatingAlertService.RESOLUTION_NO_LONGER_PUBLISHED, NOW);
        verify(alertService, never()).evaluatePublishedPlanMargin(
                any(), anyString(), any(), any(), any());
        assertThat(summary()).contains("evaluated=0 belowFloor=0 resolved=1 skippedUnknownCost=0");
    }

    /**
     * 一个仍处于 PUBLISHED 状态但未被测量的版本——此处是因为其套餐
     * 已归档，即 {@code findPublishedPlans} 过滤掉的情形——保留其告警。归档
     * 是可逆的（目录可以恢复套餐），因此对它关闭告警会
     * 在套餐恢复的那一刻丢失该预警。
     */
    @Test
    void anAlertForAPublishedVersionThatWasNotMeasuredIsKept() {
        when(repository.findPublishedPlans()).thenReturn(List.of());
        when(alertService.openPublishedPlanMarginVersions())
                .thenReturn(List.of(VIDEO_VERSION_ID));
        when(repository.findPublishedVersionIds(any())).thenReturn(Set.of(VIDEO_VERSION_ID));

        assertThat(monitor.revalidateOnce(NOW)).isZero();

        verify(alertService, never()).resolvePublishedPlanMargin(any(), any(), any());
        assertThat(summary()).contains("evaluated=0 belowFloor=0 resolved=0 skippedUnknownCost=0");
    }

    /**
     * 什么都没有发现的运行必须说明它运行过且什么都没发现。在此之前，
     * 一次干净运行留下的唯一证据就是没有告警，而这与一个
     * 从未启动的作业无法区分。
     */
    @Test
    void aCleanRunLogsItsCountsAndResolvesNothing() {
        publishedPlan("ENTERPRISE", 159_900L, 100_000_000L);
        when(repository.findModels(TOKEN_VERSION_ID)).thenReturn(List.of(
                new PackageVersionRepository.ModelRow("text-max", 2_000_000L, "TEXT"),
                new PackageVersionRepository.ModelRow("text-basic", null, "TEXT")));
        // 2M x 3600 分/M = 7,200 随后 98M x 800 分/M = 78,400 -> 85,600 分；毛利 46.5%，
        // 高于预警下限，因此本次运行除汇总行外完全静默。
        when(providerPriceClient.nonPromotionalOutputPrices(any())).thenReturn(Map.of(
                "text-max", new BigDecimal("3600"),
                "text-basic", new BigDecimal("800")));
        when(alertService.openPublishedPlanMarginVersions()).thenReturn(List.of());

        assertThat(monitor.revalidateOnce(NOW)).isZero();

        verify(alertService, never()).evaluatePublishedPlanMargin(
                any(), anyString(), any(), any(), any());
        verify(alertService, never()).resolvePublishedPlanMargin(any(), any(), any());
        assertThat(summary()).contains("evaluated=1 belowFloor=0 resolved=0 skippedUnknownCost=0");
    }

    /** 填充把 NULL 上限理解为「该别名可以取走额度中剩下的部分」。 */
    @Test
    void aNullCapLetsTheDearestAliasTakeTheWholeQuota() {
        BigDecimal cost = PublishedPlanMarginMonitor.worstCaseCost(
                List.of(new PackageVersionRepository.ModelRow("text-standard", null, "TEXT")),
                Map.of("text-standard", new BigDecimal("900")),
                5_000_000L, BigDecimal.valueOf(1_000_000L));

        assertThat(cost).isEqualByComparingTo("4500.0000");
    }

    /**
     * 每个模型的上限约束住最贵的别名，次贵的别名取走剩下的部分。
     */
    @Test
    void aPerModelCapBoundsTheDearestAlias() {
        BigDecimal cost = PublishedPlanMarginMonitor.worstCaseCost(
                List.of(
                        new PackageVersionRepository.ModelRow("text-pro", 1_000_000L, "TEXT"),
                        new PackageVersionRepository.ModelRow("text-basic", null, "TEXT")),
                Map.of("text-pro", new BigDecimal("2700"), "text-basic", new BigDecimal("800")),
                5_000_000L, BigDecimal.valueOf(1_000_000L));

        // 1M x 2700 = 2,700 随后 4M x 800 = 3,200 -> 5,900 分。
        assertThat(cost).isEqualByComparingTo("5900.0000");
    }

    /**
     * 视频维度，它按秒定价，并读取另一个成本来源。
     *
     * <p>这正是承载维度的证据契约所服务的那一半重新校验：VIDEO_PRO v1
     * 以 129,900 分售卖 600 秒，而 `video-max` 每秒成本为 345 分，
     * 因此用该套餐所宣传的别名填满额度要花
     * 207,000 分——即 59% 的亏损，而纯 token 检查看不到它，因为该套餐
     * 根本没有 token。它已通过重新定价与按别名上限（V56）修复，
     * 而本测试正是阻止同样形态再次悄悄出现的东西。
     */
    @Test
    void aVideoPackageWhoseSecondsCostMoreThanItChargesRaisesTheSameAlert() {
        publishedPlan(VIDEO_VERSION_ID, "VIDEO_PRO", 129_900L, 600L, "VIDEO_SECOND");
        when(repository.findModels(VIDEO_VERSION_ID)).thenReturn(List.of(
                new PackageVersionRepository.ModelRow("video-max", 600L, "VIDEO")));
        when(providerUnitCostClient.effectiveUnitCosts(any()))
                .thenReturn(Map.of("video-max", new BigDecimal("345")));
        when(pricePolicyRepository.find("VIDEO_SECOND"))
                .thenReturn(java.util.Optional.of(new DimensionPricePolicyRepository.Row(
                        "VIDEO_SECOND", 1L, "seconds",
                        new BigDecimal("0.30000"), new BigDecimal("0.40000"))));

        int belowFloor = monitor.revalidateOnce(NOW);

        assertThat(belowFloor).isEqualTo(1);
        // 600 秒 x 345 分/秒 = 207,000 分，对应 129,900 分的价格。若在此处套用 token 套餐的
        // 除数（1,000,000），本会报告 0.0002% 的亏损，而不是 -59%。
        verify(alertService).evaluatePublishedPlanMargin(
                eq(VIDEO_VERSION_ID), eq("VIDEO_PRO"),
                eq(new BigDecimal("-0.59353349")), eq(new BigDecimal("0.30000")), eq(NOW));
        verify(alertService, never()).resolvePublishedPlanMargin(any(), any(), any());
    }

    /**
     * 另一个方向，使用 V56 实际发布的上限：同一别名在 600 秒中被限制为 150 秒即可越过下限，
     * 因此监控必须保持静默。若没有这一条，该告警就可能通过「让它在每个视频套餐上都触发」
     * 被「修好」。
     *
     * <p>它还钉住决策 1 在视频维度上的边界：39.2% 高于 30% 的下限并低于 40% 的预警下限，
     * 因此本次运行只预警而不告警。
     */
    @Test
    void aVideoPackageWithPerAliasCeilingsAndTheCorrectedPriceRaisesNothing() {
        publishedPlan(VIDEO_VERSION_ID, "VIDEO_PRO", 139_900L, 600L, "VIDEO_SECOND");
        when(repository.findModels(VIDEO_VERSION_ID)).thenReturn(List.of(
                new PackageVersionRepository.ModelRow("video-max", 150L, "VIDEO"),
                new PackageVersionRepository.ModelRow("video-standard", 120L, "VIDEO"),
                new PackageVersionRepository.ModelRow("video-basic", 100L, "VIDEO"),
                new PackageVersionRepository.ModelRow("video-pro", 80L, "VIDEO"),
                new PackageVersionRepository.ModelRow("video-budget", 150L, "VIDEO")));
        when(providerUnitCostClient.effectiveUnitCosts(any())).thenReturn(Map.of(
                "video-max", new BigDecimal("345"),
                "video-standard", new BigDecimal("115"),
                "video-basic", new BigDecimal("69"),
                "video-pro", new BigDecimal("92"),
                "video-budget", new BigDecimal("35")));
        when(pricePolicyRepository.find("VIDEO_SECOND"))
                .thenReturn(java.util.Optional.of(new DimensionPricePolicyRepository.Row(
                        "VIDEO_SECOND", 1L, "seconds",
                        new BigDecimal("0.30000"), new BigDecimal("0.40000"))));

        assertThat(monitor.revalidateOnce(NOW)).isZero();
        verify(alertService, never()).evaluatePublishedPlanMargin(
                any(), anyString(), any(), any(), any());
        assertThat(allLogs()).contains("published_plan_inside_margin_warning_band");
    }

    /**
     * 方案 C：混合版本（视频秒 + 音频秒）必须**按各自那一份额度**重算成本。
     *
     * <p>视频那一半健康、音频那一半严重亏损，而这一次巡检必须报出来。用主维度
     * 那一份额度（100 视频秒）去摊全部模型，只会算出 100 × max(1, 10) = 1000 分，
     * 即 66.7% 的毛利率——高于 30% 的下限，于是**这个亏损的混合版本永远不会
     * 触发低毛利告警**。按各自额度算，音频那一半是 600 × 10 = 6000 分，
     * 对应 3000 分的售价即 −100%。
     *
     * <p>这正是本任务那句自我要求（"测量守卫将会测量到的东西"）在方案 C 之后的
     * 含义：守卫按每个维度各判一次，因此巡检也必须各判一次，
     * 否则一个维度的亏损会被另一个维度的额度掩掉。
     */
    @Test
    void aMixedVersionWhoseAudioHalfIsUnderwaterRaisesTheAlertItsMainDimensionHides() {
        publishedPlan(VIDEO_VERSION_ID, "VIDEO_AUDIO", 3_000L, 100L, "VIDEO_SECOND", 600L);
        when(repository.findModels(VIDEO_VERSION_ID)).thenReturn(List.of(
                new PackageVersionRepository.ModelRow("video-basic", 100L, "VIDEO"),
                new PackageVersionRepository.ModelRow("audio-asr-basic", 600L, "AUDIO_TRANSCRIBE")));
        when(dimensionRepository.dimensionByModelCode()).thenReturn(Map.of(
                "video-basic", "VIDEO_SECOND",
                "audio-asr-basic", "AUDIO_SECOND"));
        when(providerUnitCostClient.effectiveUnitCosts(any())).thenReturn(Map.of(
                "video-basic", new BigDecimal("1"),
                "audio-asr-basic", new BigDecimal("10")));
        when(pricePolicyRepository.find("VIDEO_SECOND"))
                .thenReturn(java.util.Optional.of(new DimensionPricePolicyRepository.Row(
                        "VIDEO_SECOND", 1L, "seconds",
                        new BigDecimal("0.30000"), new BigDecimal("0.40000"))));
        when(pricePolicyRepository.find("AUDIO_SECOND"))
                .thenReturn(java.util.Optional.of(new DimensionPricePolicyRepository.Row(
                        "AUDIO_SECOND", 1L, "seconds",
                        new BigDecimal("0.30000"), new BigDecimal("0.40000"))));

        int belowFloor = monitor.revalidateOnce(NOW);

        assertThat(belowFloor).isEqualTo(1);
        // 音频那一半：600 秒 × 10 分/秒 = 6000 分，对应 3000 分的售价。
        verify(alertService).evaluatePublishedPlanMargin(
                eq(VIDEO_VERSION_ID), eq("VIDEO_AUDIO"),
                eq(new BigDecimal("-1.00000000")), eq(new BigDecimal("0.30000")), eq(NOW));
        verify(alertService, never()).resolvePublishedPlanMargin(any(), any(), any());
    }

    private ListAppender<ILoggingEvent> summaryLogs() {
        return logs;
    }

    private String allLogs() {
        return summaryLogs().list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private String summary() {
        return summaryLogs().list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("published_plan_margin_revalidation"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the run logged no summary line"));
    }

    private void publishedPlan(String code, long priceAmount, long totalTokenQuota) {
        publishedPlan(TOKEN_VERSION_ID, code, priceAmount, totalTokenQuota, "TOKEN");
    }

    private void publishedPlan(
            UUID versionId, String code, long priceAmount, long totalTokenQuota,
            String usageDimension) {
        publishedPlan(versionId, code, priceAmount, totalTokenQuota, usageDimension, null);
    }

    private void publishedPlan(
            UUID versionId, String code, long priceAmount, long totalTokenQuota,
            String usageDimension, Long audioSecondsQuota) {
        when(repository.findPublishedPlans()).thenReturn(List.of(
                new PackageVersionRepository.PublishedPlanRow(
                        code,                       // code
                        code,                       // name
                        null,                       // description
                        "STANDARD",                 // kind
                        0,                          // displayOrder
                        versionId,                  // versionId
                        1,                          // version
                        priceAmount,                // priceAmount
                        "CNY",                      // currency
                        totalTokenQuota,            // totalTokenQuota
                        30,                         // durationDays
                        3,                          // maxApiKeys
                        60,                         // requestsPerMinute
                        100_000L,                   // tokensPerMinute
                        2,                          // maxConcurrency
                        null,                       // dailyRequestLimit
                        null,                       // maxOutputTokens
                        20L * 1024 * 1024,          // maxFileSizeBytes
                        false,                      // oncePerUser
                        null,                       // estimatedCostAmount
                        null,                       // estimatedMarginRatio
                        false,                      // marginWarning
                        null,                       // stockTotal
                        0L,                         // stockSold
                        0L,                         // stockReserved
                        0L,                         // lowStockThreshold
                        null,                       // maxPurchasesPerUser
                        null,                       // remainingStock
                        false,                      // soldOut
                        false,                      // lowStock
                        usageDimension,             // usageDimension
                        audioSecondsQuota)));       // audioSecondsQuota（方案 C）
    }
}
