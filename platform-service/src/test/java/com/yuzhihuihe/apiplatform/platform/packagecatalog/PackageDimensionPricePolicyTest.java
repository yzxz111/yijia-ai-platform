package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * platform.package_dimension_price_policy 是发布毛利守卫的唯一事实来源：
 * 缩放系数、单位标签以及两个下限，都是在每次创建和每次发布时
 * 按用量维度读取的。
 *
 * <p>在这张表真正接上之前它是死代码 —— 守卫用的是 {@link PackageMarginPolicy}
 * 上的常量，而两者恰好一致，所以一直没人发现这件事。下面每个测试都会
 * 修改这张表并断言守卫跟着变化，因此一张被静默忽略的表会在这里
 * 失败。
 *
 * <p>三条预置数据行必须保持今天的行为：在预置的 token 行下能发布的版本
 * 必须仍然能发布，预置的 seconds 行必须仍然拒绝同样的那些版本。
 * 这些数据行携带的正是常量原先携带的值，这一点在
 * {@link #seededRowsCarryTheValuesTheGuardUsedToHardCode()} 中被直接断言。
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false"
})
class PackageDimensionPricePolicyTest {

    private static final long MIB = 1024L * 1024L;
    private static final String TOKEN_MODEL = "text-pro";
    private static final String VIDEO_MODEL = "video-basic";
    private static final String IMAGE_MODEL = "image-basic";
    private static final String AUDIO_MODEL = "audio-retalk";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PackageVersionService packageVersionService;

    @MockitoBean
    ProviderPriceClient providerPriceClient;

    @MockitoBean
    ProviderRetailPriceClient providerRetailPriceClient;

    /**
     * 守卫给 seconds 版本定价时所依据的价目表。这里用 mock，以便测试无需网关
     * 就能设定每秒成本。
     */
    @MockitoBean
    ProviderUnitCostClient providerUnitCostClient;

    @Test
    void seededRowsCarryTheValuesTheGuardEnforces() {
        // 这张表是守卫的唯一事实来源（V57），因此下面这些值就是发布实际适用的下限
        // 与缩放系数。seconds 与 image 的下限在 V58 中从 0.25/0.30 调整为
        // 0.30/0.35，为的是对每个维度都守住产品 30% 的承诺，而不只是对 token 成立；
        // 随后 V62 又把预警下限提高到产品 30-40% 目标区间的上沿，因此落在该区间内的
        // 版本会以 margin_warning = true 发布，而不是悄无声息地发布。
        // V65 又为 480P 入门档（V66）把视频维度的预警下限降到 0.35，
        // 因为该档的年付套餐是实打实的 35.7%，不得被标记发布。
        // 如果某一行再次变动，发布的适用规则就会随之改变，而这个测试会把
        // 这一点指出来。
        assertPolicyRow("TOKEN", 1_000_000L, "tokens", "0.30000", "0.40000");
        assertPolicyRow("VIDEO_SECOND", 1L, "seconds", "0.30000", "0.35000");
        assertPolicyRow("IMAGE", 1L, "images", "0.30000", "0.40000");
        // AUDIO_SECOND 由平台 V88 插入。它必须与 VIDEO_SECOND 区分开：
        // 后者的 0.35000 是 V65 只为视频产品线做的下调，音频没有对应实测数据，
        // 因此取区间上沿 0.40000。下限 0.30000 则是产品硬保证，不可低于
        // （V58 把 V54 的 0.25000 抬上来的正是这一条）。
        assertPolicyRow("AUDIO_SECOND", 1L, "seconds", "0.30000", "0.40000");
    }

    /**
     * 这一行会被原样读入守卫用于定价的策略：按维度给出缩放系数、
     * 单位标签以及两个下限。修改某一个维度的数据行，
     * 不得影响到另一个维度。
     */
    @Test
    @Transactional
    void policyRowValuesReachTheGuardVerbatim() {
        PackageMarginPolicy.DimensionPolicy token =
                packageVersionService.dimensionPolicy("TOKEN", Set.of(TOKEN_MODEL));
        assertThat(token.usageDimension()).isEqualTo("TOKEN");
        assertThat(token.quotaUnitsPerCostUnit()).isEqualTo(1_000_000L);
        assertThat(token.unitLabel()).isEqualTo("tokens");
        assertThat(token.minimumMargin()).isEqualByComparingTo("0.30000");
        assertThat(token.warningMargin()).isEqualByComparingTo("0.40000");

        jdbcTemplate.update("""
                UPDATE platform.package_dimension_price_policy
                SET quota_units_per_cost_unit = 1, unit_label = 'seconds',
                    minimum_margin = 0.40000, warning_margin = 0.45000
                WHERE usage_dimension = 'TOKEN'
                """);

        PackageMarginPolicy.DimensionPolicy edited =
                packageVersionService.dimensionPolicy("TOKEN", Set.of(TOKEN_MODEL));
        assertThat(edited.quotaUnitsPerCostUnit()).isEqualTo(1L);
        assertThat(edited.unitLabel()).isEqualTo("seconds");
        assertThat(edited.minimumMargin()).isEqualByComparingTo("0.40000");
        assertThat(edited.warningMargin()).isEqualByComparingTo("0.45000");

        PackageMarginPolicy.DimensionPolicy seconds =
                packageVersionService.dimensionPolicy("VIDEO_SECOND", Set.of(VIDEO_MODEL));
        assertThat(seconds.quotaUnitsPerCostUnit()).isEqualTo(1L);
        assertThat(seconds.unitLabel()).isEqualTo("seconds");
        assertThat(seconds.minimumMargin()).isEqualByComparingTo("0.30000");
        assertThat(seconds.warningMargin()).isEqualByComparingTo("0.35000");

        // AUDIO_SECOND 必须走与 VIDEO_SECOND/IMAGE 同一条**单位成本**取数路径，
        // 而不是 token 的每百万路径：一个按秒计费的维度若被拿去问
        // 「每百万 token 多少分」，成本基准会差六个数量级，而毛利率照样算得出来，
        // 因此不会以任何异常的形式自己暴露出来。若 dimensionPolicy 的
        // AUDIO_SECOND 分支缺失，这里会以 422 unsupported_usage_dimension 失败。
        when(providerUnitCostClient.effectiveUnitCosts(anySet()))
                .thenReturn(Map.of(AUDIO_MODEL, new BigDecimal("9")));

        PackageMarginPolicy.DimensionPolicy audio =
                packageVersionService.dimensionPolicy("AUDIO_SECOND", Set.of(AUDIO_MODEL));
        assertThat(audio.usageDimension()).isEqualTo("AUDIO_SECOND");
        assertThat(audio.quotaUnitsPerCostUnit()).isEqualTo(1L);
        assertThat(audio.unitLabel()).isEqualTo("seconds");
        assertThat(audio.minimumMargin()).isEqualByComparingTo("0.30000");
        assertThat(audio.warningMargin()).isEqualByComparingTo("0.40000");
    }

    /**
     * 产品 30% 的承诺，是被断言出来的，而不是被假定的。
     *
     * <p>要求是任何套餐都不得以低于 30% 的毛利率售出，而自 V57 起，
     * 这张表里的下限才是发布实际执行的规则。某个维度若留在旧的 0.25，
     * 就会让视频套餐以产品不会接受的毛利率发布 —— 而这正是 V58 要修的问题；
     * 因此只要有人把某个下限降回承诺线以下，或者新增维度却不给它下限，
     * 这个测试就会失败。
     *
     * <p>这里刻意不设上限。40% 是运维努力追求的目标，而硬性上限只会
     * 毫无益处地禁止一个合法地拥有更高毛利率的套餐；
     * 真正起防亏损作用的是下限。
     */
    @Test
    void everyDimensionFloorMeetsTheProductsThirtyPercentGuarantee() {
        var floors = jdbcTemplate.queryForList("""
                SELECT usage_dimension, minimum_margin, warning_margin
                FROM platform.package_dimension_price_policy
                """);

        assertThat(floors)
                .as("every usage dimension the guard can price must carry a floor")
                .isNotEmpty()
                .allSatisfy(row -> {
                    String dimension = (String) row.get("usage_dimension");
                    java.math.BigDecimal minimum =
                            (java.math.BigDecimal) row.get("minimum_margin");
                    java.math.BigDecimal warning =
                            (java.math.BigDecimal) row.get("warning_margin");

                    assertThat(minimum)
                            .as("%s minimum_margin must be at least the product's 30%%", dimension)
                            .isNotNull()
                            .isGreaterThanOrEqualTo(new java.math.BigDecimal("0.30000"));
                    assertThat(warning)
                            .as("%s warning_margin must not sit below its minimum", dimension)
                            .isNotNull()
                            .isGreaterThanOrEqualTo(minimum);
                    // V62 把预警下限放在产品 30-40% 目标区间的上沿，因此每个维度都会
                    // 对一个距离下限只有一次成本变动之遥的版本发出标记。V65 只把
                    // VIDEO_SECOND 的下限降到 35%，因为 480P 入门档的年付套餐是
                    // 实打实的 35.7%，给它打标记只会让运维学会无视这个标记。
                    // 它只是一个预警信号，
                    // 而不是上限。
                    java.math.BigDecimal expectedWarning =
                            "VIDEO_SECOND".equals(dimension)
                                    ? new java.math.BigDecimal("0.35000")
                                    : new java.math.BigDecimal("0.40000");
                    assertThat(warning)
                            .as("%s warning_margin must be the dimension's early warning",
                                    dimension)
                            .isEqualByComparingTo(expectedWarning);
                });

        // 产品目前在售的四个维度，因此某个数据行被删掉也能被这个测试抓到。
        // AUDIO_SECOND 由平台 V88 写入，下限是 0.30000 / 0.40000：0.30000 是
        // V58 抬起来的、V62:58-65 与 V65:60-67 都断言过的产品硬保证（V54 的
        // 0.25000 只是历史值）；warning 取区间上沿，因为 V65:5-6 明说 0.35
        // 是只为视频产品线做的决定，音频没有对应的实测数据。
        // 上面 allSatisfy 里那条通用的 0.30 下限检查正是本可以抓住
        // 「音频被写成 0.25000」的地方。
        assertThat(floors)
                .extracting(row -> row.get("usage_dimension"))
                .containsExactlyInAnyOrder("TOKEN", "VIDEO_SECOND", "IMAGE", "AUDIO_SECOND");
    }

    /**
     * 这条承诺必须对已经在售的东西成立，而不只是对下一个新建的版本成立。
     * 这正是本可以抓住 VIDEO_PRO 的那个回归测试：它以 59% 的亏损被 PUBLISHED，
     * 而它存储的快照却报告了 63% 的毛利率。
     *
     * <p>它重新推导了一遍守卫自己的算法 —— 先用最贵的许可别名
     * 填满套餐的全部额度，并遵守每个模型各自的上限 —— 以网关会
     * 交给守卫的成本基准为准，断言每一个已发布版本都越过了
     * 它所属维度的下限。成本基准在这里按维度提供，这样测试就把
     * 它所检验的算术明说出来，而不必依赖测试容器里恰好启用了
     * 哪些提供方渠道。
     */
    @Test
    void everyPublishedVersionClearsItsDimensionFloor() {
        java.math.BigDecimal tokenFloor = new java.math.BigDecimal("0.30000");
        java.math.BigDecimal secondsFloor = new java.math.BigDecimal("0.30000");
        java.math.BigDecimal imageFloor = new java.math.BigDecimal("0.30000");

        // 每个别名允许的最贵成本：TOKEN 为每百万 token 多少分，VIDEO_SECOND
        // 为每秒多少分 —— 也就是守卫定价所依据的成本基准。
        var tokenCosts = java.util.Map.of(
                "text-basic", 28L, "text-standard", 202L, "text-pro", 627L, "text-max", 750L,
                "vision-basic", 60L, "vision-pro", 160L,
                "file-basic", 200L, "multimodal-basic", 200L);
        var secondCosts = java.util.Map.of(
                "video-max", 345L, "video-standard", 115L, "video-pro", 92L,
                "video-basic", 69L, "video-budget", 35L);
        // IMAGE 的成本基准是每张多少分：image-basic 取 62 分/张 —— V85 登记进
        // platform.model_usage_dimension 的 effective_unit_cost，也就是上游
        // ¥0.30–0.60 的输出图最坏情况（60 分）加上图生图的输入图（2 分）。
        // 没有它，V84 的图片计划版本就会被下面的填充跳过
        //（那正是这里删掉 filter 的原因）。
        var imageCosts = java.util.Map.of("image-basic", 62L);

        var costsByDimension = java.util.Map.of(
                "TOKEN", tokenCosts, "VIDEO_SECOND", secondCosts, "IMAGE", imageCosts);
        var floorsByDimension = java.util.Map.of(
                "TOKEN", tokenFloor, "VIDEO_SECOND", secondsFloor, "IMAGE", imageFloor);

        var published = jdbcTemplate.queryForList("""
                SELECT p.code AS plan_code, v.id AS version_id, v.price_amount,
                       v.total_token_quota, v.usage_dimension
                FROM platform.package_plan_version v
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                WHERE v.status = 'PUBLISHED' AND v.price_amount > 0
                """);
        assertThat(published).as("there must be published versions to check").isNotEmpty();

        for (var version : published) {
            String planCode = (String) version.get("plan_code");
            long price = ((Number) version.get("price_amount")).longValue();
            long quota = ((Number) version.get("total_token_quota")).longValue();
            String dimension = (String) version.get("usage_dimension");
            java.util.UUID versionId = (java.util.UUID) version.get("version_id");
            var costs = costsByDimension.get(dimension);
            assertThat(costs)
                    .as("%s (%s) is a dimension this test cannot price", planCode, dimension)
                    .isNotNull();
            long perCostUnit = "TOKEN".equals(dimension) ? 1_000_000L : 1L;

            var permissions = jdbcTemplate.queryForList("""
                    SELECT model_code, model_token_quota
                    FROM platform.package_plan_version_model
                    WHERE package_plan_version_id = ?
                    """, versionId);

            // 守卫的填充方式：先填最贵的，每个别名取 min(上限, 剩余额度)；上限为 NULL
            // 时可以取走剩余部分。成本相同时这里的先后顺序会被打乱，
            // 但对这里涉及的别名来说总额不变。
            //
            // 这里以前会把本测试没有成本基准的别名 filter 掉，于是填充把它们跳过、
            // 最坏成本被低估，而断言照样通过：image-basic 就是这么溜过去的。
            // 缺失的成本基准现在是失败，而不是跳过 —— 与被检验的守卫
            // （PackageMarginPolicy.requirePrice 对缺失成本抛异常）保持一致。
            var ordered = permissions.stream()
                    .map(permission -> java.util.Map.entry(permission,
                            costBasisOf(costs, planCode, dimension,
                                    (String) permission.get("model_code"))))
                    .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                    .toList();

            long remaining = quota;
            double worstCost = 0;
            for (var permission : ordered) {
                if (remaining <= 0) {
                    break;
                }
                Number cap = (Number) permission.getKey().get("model_token_quota");
                long take = cap == null ? remaining : Math.min(cap.longValue(), remaining);
                worstCost += (double) take * permission.getValue() / perCostUnit;
                remaining -= take;
            }

            double margin = (price - worstCost) / price;
            java.math.BigDecimal floor = floorsByDimension.get(dimension);
            assertThat(new java.math.BigDecimal(String.format(java.util.Locale.ROOT, "%.6f", margin)))
                    .as("%s (%s) worst-case margin against its %s floor; price=%d worstCost=%.0f",
                            planCode, dimension, floor.toPlainString(), price, worstCost)
                    .isGreaterThanOrEqualTo(floor);
        }
    }

    /**
     * 某个已发布版本授予的别名所依据的成本基准。缺失时直接失败：
     * 把它当作零成本或跳过都会让上面那条下限断言变得比它要检验的守卫更宽松。
     */
    private static long costBasisOf(
            java.util.Map<String, Long> costs, String planCode, String dimension, String modelCode) {
        Long cost = costs.get(modelCode);
        assertThat(cost)
                .as("%s (%s) grants %s, which this test has no cost basis for",
                        planCode, dimension, modelCode)
                .isNotNull();
        return cost;
    }

    @Test
    @Transactional
    void seededTokenRowPublishesAndWarnsExactlyAsTheConstantsDid() {
        // 起决定作用的是这一行的下限，而这一行携带的正是产品的数字：
        // 30% 的下限仍然是 Java 兜底常量携带的那个值，
        // 预警下限则是 V62 设定的 40%。10,000 分按每百万 680 分可以买到
        // 10,000,000 token：成本 6,800 分，毛利率 32% —— 高于下限、
        // 低于预警下限，因此它会带预警发布。在旧的 35% 预警下限下
        // 它同样会被预警，所以对这个版本而言行为没有变化，
        // 这次改动真正挪动的是下面那个边界。
        PackageVersionService.CreatedVersion created = packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "680.00"), UUID.randomUUID());

        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.margin().marginRatio()).isEqualByComparingTo("0.32000");
        assertThat(created.margin().estimatedCostAmount()).isEqualTo(6_800L);
        assertThat(created.margin().warning()).isTrue();
        assertThat(created.margin().publishable()).isTrue();
    }

    /**
     * V62 挪动过的那个边界，在它的两侧分别断言：恰好 40% 不预警，
     * 比它低一个万分之一则会预警，因为当 {@code margin < warning_margin}
     * 时守卫会预警。39.99% 的版本正是这个决定所针对的情形 ——
     * 它会发布，同时运维会被告知。
     */
    @Test
    @Transactional
    void theRaisedWarningFloorIsStrictAndSitsAtFortyPercent() {
        // 10,000 分买 10,000,000 token、单价 600 分/M，成本恰好是 6,000 分：40.00000%。
        PackageVersionService.CreatedVersion atWarning = packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "600.00"), UUID.randomUUID());
        assertThat(atWarning.margin().marginRatio()).isEqualByComparingTo("0.40000");
        assertThat(atWarning.margin().warning()).isFalse();
        assertThat(atWarning.margin().publishable()).isTrue();

        // 600.01 分/M 对应 6,000.1 分成本，守卫向上取整到 6,001 ——
        // 0.39990，仍然高于 30% 的下限，但现在落进了预警区间。
        PackageVersionService.CreatedVersion justInside = packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "600.01"), UUID.randomUUID());
        assertThat(justInside.margin().marginRatio()).isEqualByComparingTo("0.39990");
        assertThat(justInside.margin().warning()).isTrue();
        assertThat(justInside.margin().publishable()).isTrue();
    }

    /**
     * 下限是包含端点的，而且它是从数据行里读出来的：恰好 30% 的版本可以发布
     * （margin >= floor），而比它低万分之一的版本不行。这两个比较与原先的常量
     * 所用的比较一致，因此运维把下限敲错也不会悄悄挪动边界。
     */
    @Test
    @Transactional
    void theSeededTokenFloorIsInclusiveExactlyAsTheConstantWas() {
        PackageVersionService.CreatedVersion atFloor = packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "700.00"), UUID.randomUUID());

        assertThat(atFloor.margin().marginRatio()).isEqualByComparingTo("0.30000");
        assertThat(atFloor.margin().publishable()).isTrue();
        assertThat(atFloor.margin().warning()).isTrue();

        // 每百万 700.01 分对应 7,000.1 分成本，守卫向上取整到 7,001 ——
        // 0.29990，低于下限。
        assertThatThrownBy(() -> packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "700.01"), UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("token_margin_below_minimum");
    }

    /**
     * 预警下限是单独的一列，它驱动被存储下来、由 LOW_MARGIN 告警读取的
     * {@code margin_warning} 标记。自 V62 起它被预置为 0.40，因此这里的第一个
     * 用例完全不需要改动数据：32% 的版本高于 30% 的下限、低于 40% 的预警下限，
     * 因此按出厂的数据行它就会预警。其余用例改动这一列，用来证明起决定作用的
     * 是这一列而不是某个常量 —— 其中包括：这个比较是严格比较，
     * 恰好等于预警下限的版本不会预警，
     * 而比它低万分之一的版本会预警。
     */
    @Test
    @Transactional
    void theWarningFloorFromTheTableDecidesTheWarningFlag() {
        PackageVersionService.CreatedVersion atSeededFloors = packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "680.00"), UUID.randomUUID());
        assertThat(atSeededFloors.margin().marginRatio()).isEqualByComparingTo("0.32000");
        assertThat(atSeededFloors.margin().warning()).isTrue();
        assertThat(atSeededFloors.margin().publishable()).isTrue();

        // 下限必须跟着一起改：ck_package_dimension_policy_values（以及 Java 侧的守卫）
        // 都会拒绝低于自身下限的预警下限。
        jdbcTemplate.update("""
                UPDATE platform.package_dimension_price_policy
                SET minimum_margin = 0.10000, warning_margin = 0.20000
                WHERE usage_dimension = 'TOKEN'
                """);

        assertThat(packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "680.00"), UUID.randomUUID())
                .margin().warning()).isFalse();

        jdbcTemplate.update("""
                UPDATE platform.package_dimension_price_policy
                SET warning_margin = 0.32000
                WHERE usage_dimension = 'TOKEN'
                """);
        assertThat(packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "680.00"), UUID.randomUUID())
                .margin().warning())
                .as("a version at exactly the warning floor is not below it")
                .isFalse();

        jdbcTemplate.update("""
                UPDATE platform.package_dimension_price_policy
                SET warning_margin = 0.32001
                WHERE usage_dimension = 'TOKEN'
                """);
        assertThat(packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "680.00"), UUID.randomUUID())
                .margin().warning()).isTrue();
    }

    /**
     * 当这张表被忽略时就会失败的那个测试：运维把 token 的下限提高到 40%，
     * 而原先能按 32% 发布的版本必须被拒绝。硬编码的 30% 下限会放行它，
     * 因此只有真正读取了数据行，这个测试才会通过。
     */
    @Test
    @Transactional
    void aStricterTokenFloorInTheTableRefusesANewVersion() {
        raiseTokenFloor("0.40000", "0.45000");

        assertThatThrownBy(() -> packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "680.00"), UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("token_margin_below_minimum");
    }

    /**
     * 发布会重新读取数据行，因此在草稿写好之后才提高的下限，会阻止该草稿
     * 被发布。这是资金链路上的另一半：创建版本与发布版本是两个独立操作，
     * 而守卫在两者上都会运行。
     */
    @Test
    @Transactional
    void aStricterTokenFloorInTheTableRefusesToPublishAnExistingDraft() {
        PackageVersionService.CreatedVersion draft = packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "680.00"), UUID.randomUUID());
        assertThat(draft.margin().publishable()).isTrue();

        raiseTokenFloor("0.40000", "0.45000");

        assertThatThrownBy(() -> packageVersionService.publish(
                "STARTER", draft.version(), UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("token_margin_below_minimum");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM platform.package_plan_version WHERE id = ?
                """, String.class, draft.id())).isEqualTo("DRAFT");
    }

    /**
     * 缩放这一列是数据行的另一半。把 token 的除数设为 1，会让同样的额度
     * 贵上一百万倍，因此预置数据行原本接受的版本现在必须被拒绝。
     * 硬编码的 1,000,000 会放它过去。
     */
    @Test
    @Transactional
    void aTokenScalingFactorOfOneRefusesAVersionTheMillionDivisorAccepted() {
        jdbcTemplate.update("""
                UPDATE platform.package_dimension_price_policy
                SET quota_units_per_cost_unit = 1
                WHERE usage_dimension = 'TOKEN'
                """);

        assertThatThrownBy(() -> packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "680.00"), UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("token_margin_below_minimum");
    }

    /**
     * 决定视频版本的是 seconds 那一行，既不是 token 行也不是某个常量。
     * 100 秒按 80 分/秒计算是 8,000 分成本，对 10,000 分的价格而言是 20% 的毛利率，
     * 低于预置的 25% seconds 下限。把这一行改低 —— 只改下限 —— 就能让同一个版本
     * 不带预警地发布，而硬编码的 25% 下限做不到这一点。
     */
    @Test
    @Transactional
    void theSecondsRowDecidesWhetherAVideoVersionPublishes() {
        assertThatThrownBy(() -> packageVersionService.createVersion(
                videoVersion("VIDEO_STARTER", 10_000L, 100L, "80.00"), UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("token_margin_below_minimum");

        jdbcTemplate.update("""
                UPDATE platform.package_dimension_price_policy
                SET minimum_margin = 0.15000, warning_margin = 0.18000
                WHERE usage_dimension = 'VIDEO_SECOND'
                """);

        PackageVersionService.CreatedVersion created = packageVersionService.createVersion(
                videoVersion("VIDEO_STARTER", 10_000L, 100L, "80.00"), UUID.randomUUID());

        assertThat(created.margin().estimatedCostAmount()).isEqualTo(8_000L);
        assertThat(created.margin().marginRatio()).isEqualByComparingTo("0.20000");
        assertThat(created.margin().warning()).isFalse();
        assertThat(created.margin().publishable()).isTrue();
    }

    /**
     * IMAGE 是拥有自己数据行的独立维度。把 seconds 行改低不得影响到它，
     * 否则一个廉价的视频下限就会悄悄削弱所有图片套餐。
     */
    @Test
    @Transactional
    void theImageDimensionReadsItsOwnRowRatherThanTheSecondsRow() {
        // image-basic 现在由 V83 迁移登记为 IMAGE 别名，所以这里必须
        // 用 upsert 而不是裸 INSERT：测试要断言的是「IMAGE 读自己那一行」，
        // 而不是「这一行存在」——后者已经是迁移的责任了。
        jdbcTemplate.update("""
                INSERT INTO platform.model_usage_dimension(model_code, usage_dimension)
                VALUES (?, 'IMAGE')
                ON CONFLICT (model_code) DO UPDATE
                SET usage_dimension = EXCLUDED.usage_dimension
                """, IMAGE_MODEL);
        jdbcTemplate.update("""
                UPDATE platform.package_dimension_price_policy
                SET minimum_margin = 0.15000, warning_margin = 0.18000
                WHERE usage_dimension = 'VIDEO_SECOND'
                """);

        assertThatThrownBy(() -> packageVersionService.createVersion(
                imageVersion(10_000L, 100L, "80.00"), UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("token_margin_below_minimum");
    }

    /**
     * 没有数据行的维度会回退到这张表存在之前守卫所携带的值，
     * 而这个回退值就是全部有文档记载的契约：token 为 30% / 35%，
     * seconds 为 25% / 30%。
     */
    @Test
    @Transactional
    void aDimensionWithoutARowFallsBackToTheDocumentedConstants() {
        jdbcTemplate.update("""
                DELETE FROM platform.package_dimension_price_policy
                WHERE usage_dimension IN ('TOKEN', 'VIDEO_SECOND')
                """);

        PackageVersionService.CreatedVersion created = packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "680.00"), UUID.randomUUID());
        assertThat(created.margin().marginRatio()).isEqualByComparingTo("0.32000");
        assertThat(created.margin().warning()).isTrue();
        assertThat(created.margin().publishable()).isTrue();

        // 29%：刚好差一点没达到回退值 30% 的下限，因此必须被拒绝。
        assertThatThrownBy(() -> packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "710.00"), UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("token_margin_below_minimum");

        // 20% 的视频版本会被 seconds 回退值的 25% 下限拒绝。
        assertThatThrownBy(() -> packageVersionService.createVersion(
                videoVersion("VIDEO_CREATOR", 10_000L, 100L, "80.00"), UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("token_margin_below_minimum");
    }

    /**
     * 存在但不可用的数据行必须失败即关闭（fail-closed）。静默回退会给运维
     * 一个比他们自以为配置的更弱的守卫：在这里，守卫会按 32% 发布，
     * 而数据行声称的下限是 45%。
     *
     * <p>这张表自己的 CHECK 约束会拒绝这样的行，因此在这个事务里把它删掉，
     * 以便同样检验 Java 侧的守卫 —— 一个被放宽过的约束，或者一行由超级用户
     * 写入的数据，都不得悄悄削弱下限。
     */
    @Test
    @Transactional
    void anInvalidPolicyRowFailsClosedInsteadOfFallingBack() {
        dropValueConstraint();
        jdbcTemplate.update("""
                UPDATE platform.package_dimension_price_policy
                SET minimum_margin = 0.45000, warning_margin = 0.40000
                WHERE usage_dimension = 'TOKEN'
                """);

        assertThatThrownBy(() -> packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "680.00"), UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("invalid_package_dimension_price_policy")
                .hasMessageContaining("TOKEN")
                .hasMessageContaining("warning_margin_below_minimum");
    }

    @Test
    @Transactional
    void aPolicyRowWithoutAPositiveScalingFactorFailsClosed() {
        dropValueConstraint();
        jdbcTemplate.update("""
                UPDATE platform.package_dimension_price_policy
                SET quota_units_per_cost_unit = 0
                WHERE usage_dimension = 'TOKEN'
                """);

        assertThatThrownBy(() -> packageVersionService.createVersion(
                tokenVersion(10_000L, 10_000_000L, "680.00"), UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("invalid_package_dimension_price_policy")
                .hasMessageContaining("quota_units_per_cost_unit");
    }

    private void raiseTokenFloor(String minimumMargin, String warningMargin) {
        jdbcTemplate.update("""
                UPDATE platform.package_dimension_price_policy
                SET minimum_margin = ?, warning_margin = ?
                WHERE usage_dimension = 'TOKEN'
                """, new BigDecimal(minimumMargin), new BigDecimal(warningMargin));
    }

    private void dropValueConstraint() {
        jdbcTemplate.execute("""
                ALTER TABLE platform.package_dimension_price_policy
                DROP CONSTRAINT ck_package_dimension_policy_values
                """);
    }

    private void assertPolicyRow(
            String dimension, long quotaUnitsPerCostUnit, String unitLabel,
            String minimumMargin, String warningMargin) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT quota_units_per_cost_unit, unit_label, minimum_margin, warning_margin
                FROM platform.package_dimension_price_policy
                WHERE usage_dimension = ?
                """, dimension);

        assertThat(row).containsEntry("quota_units_per_cost_unit", quotaUnitsPerCostUnit)
                .containsEntry("unit_label", unitLabel);
        assertThat((BigDecimal) row.get("minimum_margin")).isEqualByComparingTo(minimumMargin);
        assertThat((BigDecimal) row.get("warning_margin")).isEqualByComparingTo(warningMargin);
    }

    private PackageVersionService.CreateVersionRequest tokenVersion(
            long priceAmount, long totalQuota, String costPerMillion) {
        when(providerPriceClient.nonPromotionalOutputPrices(anySet()))
                .thenReturn(Map.of(TOKEN_MODEL, new BigDecimal(costPerMillion)));
        // 零售价高于成本，这样一旦被拒，原因就可以归到被测的下限上，
        // 而不是另一项独立的「售价低于成本基准」检查。
        when(providerRetailPriceClient.currentRetailPrices(anySet()))
                .thenReturn(Map.of(TOKEN_MODEL,
                        new ProviderRetailPriceClient.RetailPrice(1_000, 10_000)));

        return new PackageVersionService.CreateVersionRequest(
                "STARTER", priceAmount, totalQuota, 30, 3, 20, 100_000L, 3,
                null, null, 20L * MIB, false,
                Set.of(new PackageVersionService.ModelPermission(
                        TOKEN_MODEL, null, Set.of("TEXT"))));
    }

    /**
     * 一个按每秒成本定价的 seconds 维度版本。
     *
     * <p>售卖速率是秒/分钟的速率上限，而不是 token 速率
     * （{@link PackageRateCeilingPolicy} 会拒绝 seconds 列里出现 token 量级的值 —— 见
     * {@code PackageRateCeilingPolicyTest}）。这些测试关心的是成本与毛利率，因此任何合法
     * 的上限都可以；这里用 600，好让这个夹具是一个真实视频套餐卖得出去的速率。
     */
    private PackageVersionService.CreateVersionRequest videoVersion(
            String planCode, long priceAmount, long totalQuotaSeconds, String costPerSecond) {
        when(providerUnitCostClient.effectiveUnitCosts(anySet()))
                .thenReturn(Map.of(VIDEO_MODEL, new BigDecimal(costPerSecond)));

        return new PackageVersionService.CreateVersionRequest(
                planCode, priceAmount, totalQuotaSeconds, 30, 3, 20, 600L, 3,
                null, null, 20L * MIB, false,
                Set.of(new PackageVersionService.ModelPermission(
                        VIDEO_MODEL, null, Set.of("VIDEO"))));
    }

    /** 与 {@link #videoVersion} 相同：IMAGE 维度的速率同样是以秒为单位的上限。 */
    private PackageVersionService.CreateVersionRequest imageVersion(
            long priceAmount, long totalQuotaSeconds, String costPerSecond) {
        when(providerUnitCostClient.effectiveUnitCosts(anySet()))
                .thenReturn(Map.of(IMAGE_MODEL, new BigDecimal(costPerSecond)));

        return new PackageVersionService.CreateVersionRequest(
                "STARTER", priceAmount, totalQuotaSeconds, 30, 3, 20, 600L, 3,
                null, null, 20L * MIB, false,
                Set.of(new PackageVersionService.ModelPermission(
                        IMAGE_MODEL, null, Set.of("IMAGE"))));
    }
}
