package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.identity.IdentityService;
import com.yuzhihuihe.apiplatform.platform.identity.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false"
})
@AutoConfigureMockMvc
class PackageCatalogTest {

    private static final long MIB = 1024L * 1024L;

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
    MockMvc mockMvc;

    @Autowired
    PackageVersionService packageVersionService;

    @Autowired
    com.yuzhihuihe.apiplatform.platform.grant.PackageSnapshotService packageSnapshotService;

    @Autowired
    JwtTokenService jwtTokenService;

    @MockitoBean
    ProviderPriceClient providerPriceClient;

    /**
     * 网关会据以计费的零售价牌。
     *
     * <p>有意把桩值设在成本基准之上：这些套件检验的是发布，
     * 而不是低于成本的拒绝，且低于成本的零售价会（正确地）拒绝它们发布的
     * 每一个版本。该拒绝行为有自己的测试。
     */
    @MockitoBean
    ProviderRetailPriceClient providerRetailPriceClient;

    /**
     * 秒数维度版本据以定价的费率卡。将其 mock 掉，以便单位防护测试
     * 无需网关即可创建视频版本；下面的 token 测试从不触达它。
     */
    @MockitoBean
    ProviderUnitCostClient providerUnitCostClient;

    @BeforeEach
    void priceSnapshot() {
        when(providerPriceClient.nonPromotionalOutputPrices(anySet())).thenReturn(Map.of(
                "text-basic", new BigDecimal("2.00"),
                "text-standard", new BigDecimal("2.00"),
                "vision-basic", new BigDecimal("2.00"),
                "file-basic", new BigDecimal("2.00"),
                "text-pro", new BigDecimal("10.00"),
                "vision-pro", new BigDecimal("10.00"),
                "text-max", new BigDecimal("10.00")));
        when(providerRetailPriceClient.currentRetailPrices(anySet())).thenReturn(Map.of(
                "text-basic", new ProviderRetailPriceClient.RetailPrice(30, 120),
                "text-standard", new ProviderRetailPriceClient.RetailPrice(58, 230),
                "vision-basic", new ProviderRetailPriceClient.RetailPrice(22, 85),
                "file-basic", new ProviderRetailPriceClient.RetailPrice(70, 280),
                "text-pro", new ProviderRetailPriceClient.RetailPrice(130, 390),
                "vision-pro", new ProviderRetailPriceClient.RetailPrice(30, 220),
                "text-max", new ProviderRetailPriceClient.RetailPrice(340, 1000)));
    }

    @Test
    void exposesConfirmedInitialPlans() throws Exception {
        // V61 与 V63 发布的版本，而不是最初的那些：V61 依据修正后的提供方成本基准为每个
        // token 套餐重新定价，并让随后被取代的版本退役，
        // 而 V63 以相同的价格与额度重新发布了它们中的每一个，
        // 以修正已售出的速率上限。
        //
        // V84 把图片商品卖成一个独立计划（IMAGE_BASIC v1），而不是给 ADVANCED /
        // ENTERPRISE 各加一个 IMAGE 版本：目录按计划只露出**版本号最大**的那个在售
        // 版本（PackageVersionRepository.findPublishedPlans 的 DISTINCT ON (p.id)），
        // 一个图片 v4 会把 ¥399 的文本套餐从门店里挤掉。因此四个文本档的版本号与
        // 数字一位不变，图片档是第五个计划 —— 断言里带上维度，因为「额度是 48」
        // 只有连同单位（张）一起才是完整的。
        assertPlan("FREE", "TOKEN", 2, 50_000L, 0L, 1, 2, 1);
        assertPlan("STARTER", "TOKEN", 3, 2_000_000L, 2_900L, 3, 20, 3);
        assertPlan("ADVANCED", "TOKEN", 3, 16_000_000L, 39_900L, 10, 100, 15);
        assertPlan("ENTERPRISE", "TOKEN", 4, 100_000_000L, 159_900L, 50, 300, 50);
        assertPlan("IMAGE_BASIC", "IMAGE", 1, 48L, 4_990L, 2, 20, 2);

        Map<String, Object> freeLimits = jdbcTemplate.queryForMap("""
                SELECT daily_request_limit, max_output_tokens, max_file_size_bytes, once_per_user
                FROM platform.package_plan_version v
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                WHERE p.code = 'FREE' AND v.status = 'PUBLISHED'
                """);
        assertThat(freeLimits)
                .containsEntry("daily_request_limit", 20)
                .containsEntry("max_output_tokens", 2_000)
                .containsEntry("max_file_size_bytes", 5L * MIB)
                .containsEntry("once_per_user", true);

        mockMvc.perform(get("/console/platform/plans"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void authenticatedUserListsPlansAndAdminPublishesNewVersion() throws Exception {
        String userToken = accessToken("USER");
        String adminToken = accessToken("ADMIN");
        UUID adminId = jdbcTemplate.queryForObject("""
                SELECT id FROM platform.app_user
                WHERE display_name = 'ADMIN' ORDER BY created_at DESC LIMIT 1
                """, UUID.class);

        mockMvc.perform(get("/console/platform/plans")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                // 十个套餐方案：四个文本档（展示顺序 0/10/20/30）、V66 的两个 480P
                // 入门档（36/37，V67 把它们排到视频线最前）、V54 的三个视频档
                // （40/50/60）以及 V84 的图片档（70，排在视频线之后）。此前这里写 7，
                // 漏掉了 V66 的两个入门档，于是一个本应断言完整目录的列表少算了
                // 两个真实在售的套餐方案。
                //
                // 该列表每个套餐方案只有一行：PackageVersionRepository.findPublishedPlans
                // 用 DISTINCT ON (p.id) ... ORDER BY p.id, v.version DESC 投影每个套餐方案
                // **版本号最大**的那个已发布版本，而不是每个已发布版本一行。V84 之后
                // 每个套餐方案仍然恰好一个在售版本（uq_published_package_plan_version），
                // 因此这里露出的就是各自唯一的在售版本：图片商品是 IMAGE_BASIC v1，
                // 而 ADVANCED / ENTERPRISE 仍是它们原来的 v3 / v4 —— 这两行是本迁移
                // 「没有把文本套餐挤出目录」的直接断言。
                //
                // 断言按 code 过滤而不是按下标：一旦某个套餐方案再发布一个版本，
                // 「第 N 行是哪个套餐方案」的写法就会把下面的断言静默挪到别的行上。
                .andExpect(jsonPath("$.length()").value(10))
                // 完整的顺序表：包装成 hamcrest matcher 而不是 List，因为
                // JsonPathExpectationsHelper 拿到 List 会按「期望值的具体类型」重新
                // 求值 JSON（Spring 6.2 的行为），而 Jayway 返回的是 JSONArray，
                // 两者类型不等价，结果是一个恒为 null 的比较。
                .andExpect(jsonPath("$[*].code").value(org.hamcrest.Matchers.contains(
                        "FREE", "STARTER", "ADVANCED", "ENTERPRISE", "VIDEO_LITE",
                        "VIDEO_LITE_ANNUAL", "VIDEO_STARTER", "VIDEO_CREATOR", "VIDEO_PRO",
                        "IMAGE_BASIC")))
                // 每个套餐方案在该列表里只出现一次，露出的是哪一版也写下来而不是假定。
                // 视频套餐的版本号在 V103 之后各 +2：V102 补 video-minimax、V103 补四个
                // 音频别名与音频秒额度，两次都是"退休旧版本 → 发布新版本"。
                .andExpect(jsonPath("$[*].version").value(org.hamcrest.Matchers.contains(
                        2, 3, 3, 4, 3, 3, 5, 5, 6, 1)))
                // 下面的断言按 code 过滤定位那一行：过滤命中恰好一行时 JsonPath 断言
                // 才通过，因此某个 code 若出现两行，这里会以「Got a list of values」
                // 失败，而不是悄悄断言到其中一行上。
                .andExpect(jsonPath(planRow("ADVANCED") + ".usageDimension").value("TOKEN"))
                .andExpect(jsonPath(planRow("ADVANCED") + ".totalTokenQuota").value(16_000_000))
                .andExpect(jsonPath(planRow("ADVANCED") + ".priceAmount").value(39_900))
                .andExpect(jsonPath(planRow("ADVANCED") + ".tokensPerMinute").value(500_000))
                .andExpect(jsonPath(planRow("ADVANCED") + ".dailyRequestLimit").value(10_000))
                .andExpect(jsonPath(planRow("ADVANCED") + ".estimatedCostAmount").value(25_800))
                .andExpect(jsonPath(planRow("ADVANCED") + ".estimatedMarginRatio").value(0.35338))
                .andExpect(jsonPath(planRow("ADVANCED") + ".marginWarning").value(true))
                .andExpect(jsonPath(planRow("ENTERPRISE") + ".usageDimension").value("TOKEN"))
                .andExpect(jsonPath(planRow("ENTERPRISE") + ".totalTokenQuota").value(100_000_000))
                .andExpect(jsonPath(planRow("ENTERPRISE") + ".priceAmount").value(159_900))
                .andExpect(jsonPath(planRow("ENTERPRISE") + ".tokensPerMinute").value(2_000_000))
                .andExpect(jsonPath(planRow("ENTERPRISE") + ".dailyRequestLimit").value(50_000))
                .andExpect(jsonPath(planRow("ENTERPRISE") + ".estimatedCostAmount").value(104_200))
                .andExpect(jsonPath(planRow("ENTERPRISE") + ".estimatedMarginRatio").value(0.34834))
                .andExpect(jsonPath(planRow("ENTERPRISE") + ".marginWarning").value(true))
                // V84 的图片计划：同一列在这里承载的是「张」与「张/分钟」，
                // 因此这三个数字必须连同维度一起断言。
                //
                // 成本与毛利率两列是**发布当时的快照**（60 分/张 的基准下算出的
                // 2,880 / 42.285%），而已发布版本的行不可变
                //（platform.reject_published_version_mutation）。V85 把成本基准
                // 改成含输入图的 62 分/张之后，按新基准重算得到 2,976 / 40.361%，
                // 但**列里存的仍然是发布时的那一对**——这正是本测试断言旧值的原因，
                // 也是运行期由 PublishedPlanMarginMonitor 按实时基准重算的原因。
                .andExpect(jsonPath(planRow("IMAGE_BASIC") + ".usageDimension").value("IMAGE"))
                .andExpect(jsonPath(planRow("IMAGE_BASIC") + ".totalTokenQuota").value(48))
                .andExpect(jsonPath(planRow("IMAGE_BASIC") + ".priceAmount").value(4_990))
                .andExpect(jsonPath(planRow("IMAGE_BASIC") + ".tokensPerMinute").value(480))
                .andExpect(jsonPath(planRow("IMAGE_BASIC") + ".dailyRequestLimit").value(200))
                .andExpect(jsonPath(planRow("IMAGE_BASIC") + ".estimatedCostAmount").value(2_880))
                .andExpect(jsonPath(planRow("IMAGE_BASIC") + ".estimatedMarginRatio").value(0.42285))
                // 仍高于 30% 下限，因此这一档不下架、也不重新发布。
                .andExpect(jsonPath(planRow("IMAGE_BASIC") + ".marginWarning").value(false))
                .andExpect(jsonPath(planRow("VIDEO_STARTER") + ".usageDimension").value("VIDEO_SECOND"))
                .andExpect(jsonPath(planRow("VIDEO_STARTER") + ".totalTokenQuota").value(60))
                .andExpect(jsonPath(planRow("VIDEO_STARTER") + ".priceAmount").value(12_900))
                // 控制台的速率标签取决于这是每分钟秒数上限，
                // 而不是 token 档位的速率：对于一个 60 秒的套餐，
                // 每分钟 60 秒视频就是 1 倍实时。
                .andExpect(jsonPath(planRow("VIDEO_STARTER") + ".tokensPerMinute").value(60))
                .andExpect(jsonPath(planRow("VIDEO_PRO") + ".tokensPerMinute").value(600))
                // 控制台为每个套餐渲染的毛利三元组，就在套餐列表本身上。
                .andExpect(jsonPath(planRow("VIDEO_STARTER") + ".estimatedCostAmount").value(6_900))
                .andExpect(jsonPath(planRow("VIDEO_STARTER") + ".estimatedMarginRatio").value(0.46512))
                .andExpect(jsonPath(planRow("VIDEO_STARTER") + ".marginWarning").value(false))
                .andExpect(jsonPath(planRow("VIDEO_CREATOR") + ".tokensPerMinute").value(190))
                .andExpect(jsonPath(planRow("VIDEO_PRO") + ".estimatedCostAmount").value(85_060))
                .andExpect(jsonPath(planRow("VIDEO_PRO") + ".marginWarning").value(true));

        // 详情接口对单个套餐是同一份契约，而且按模型的权限正是
        // 挂在这里，因此同一个三元组必须在那边也保持不变。
        mockMvc.perform(get("/console/platform/plans/VIDEO_PRO")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("VIDEO_PRO"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.usageDimension").value("VIDEO_SECOND"))
                .andExpect(jsonPath("$.totalTokenQuota").value(600))
                .andExpect(jsonPath("$.tokensPerMinute").value(600))
                .andExpect(jsonPath("$.dailyRequestLimit").value(1200))
                .andExpect(jsonPath("$.maxOutputTokens").value(8192))
                .andExpect(jsonPath("$.estimatedCostAmount").value(85_060))
                .andExpect(jsonPath("$.estimatedMarginRatio").value(0.39199))
                .andExpect(jsonPath("$.marginWarning").value(true))
                .andExpect(jsonPath("$.models.length()").value(5));

        // GET /console/platform/plans 是两种角色都可读的：SecurityConfiguration:206-208
        // 对 GET /console/platform/plans 与 /console/platform/plans/* 给的是
        // hasAnyRole("USER", "ADMIN")（HEAD 就已如此）。因此管理员拿到 200 与同一份
        // 目录，而不是 403 —— 这里此前写着两次 403，与已提交的规则相矛盾，
        // 只是更早的 $.length() 断言先一步打断了这个测试，那两行从未被执行到。
        mockMvc.perform(get("/console/platform/plans")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10));

        // 「管理员令牌不等于用户令牌」这层意思由只属于用户的控制台资源来断言：
        // /console/platform/packages 是 hasRole("USER")（SecurityConfiguration:184-205），
        // 管理员访问它仍然必须是 403。
        mockMvc.perform(get("/console/platform/packages")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/console/platform/admin/plans/STARTER/versions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "planCode": "STARTER",
                                  "priceAmount": 1900,
                                  "totalTokenQuota": 5000000,
                                  "durationDays": 30,
                                  "maxApiKeys": 3,
                                  "requestsPerMinute": 20,
                                  "tokensPerMinute": 100000,
                                  "maxConcurrency": 3,
                                  "maxFileSizeBytes": 20971520,
                                  "oncePerUser": false,
                                  "stockTotal": 25,
                                  "lowStockThreshold": 3,
                                  "maxPurchasesPerUser": 2,
                                  "models": [{
                                    "modelCode": "text-standard",
                                    "capabilities": ["TEXT"]
                                  }]
                                }
                                """))
                .andExpect(status().isCreated())
                // STARTER 的下一个空闲版本号：V61 发布了 v2，V63 发布了
                // v3，因此这个草稿是 v4。
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mockMvc.perform(post("/console/platform/admin/plans/STARTER/publish")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // 发布 v4 会让 v3 退役，也就是 V63 发布的那个版本 —— v2 与 v1 在此之前的
        // 迁移中就已经 RETIRED，因此这里断言的是本次调用所取代的
        // 那一个版本上的不变量。
        assertThat(jdbcTemplate.queryForObject("""
                SELECT v.status FROM platform.package_plan_version v
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                WHERE p.code = 'STARTER' AND v.version = 3
                """, String.class)).isEqualTo("RETIRED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT retired_at IS NOT NULL FROM platform.package_plan_version v
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                WHERE p.code = 'STARTER' AND v.version = 3
                """, Boolean.class)).isTrue();
        mockMvc.perform(get("/console/platform/admin/package-plans")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("query", "STARTER"))
                .andExpect(status().isOk())
                // 该查询是子串匹配，而 "STARTER" 也会匹配到 V54 的视频
                // 套餐（VIDEO_STARTER / "Video Starter"），后者的展示顺序把它排在
                // 文本档位之后。因此文本套餐是通过 code 来识别的。
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].code").value("STARTER"))
                // 四个版本：v1（被 V61 RETIRED）、v2（由 V61 发布并被 V63
                // RETIRED）、v3（由 V63 发布，现已被上面的草稿 RETIRED）以及
                // 本测试刚刚发布的 v4。
                .andExpect(jsonPath("$.items[0].versions.length()").value(4))
                .andExpect(jsonPath("$.items[0].versions[0].status").value("PUBLISHED"))
                // 管理目录的版本 DTO 携带与套餐列表相同的毛利三元组，
                // 因此运维能看到防护为他们即将发布或退役的那一行
                // 计算出的估算值。v4 是本测试刚刚发布的草稿，
                // 是依据上面的桩成本基准定价的，所以它的数字只按形状
                // 断言；v3 是预置行，所以它的数字是精确的。
                .andExpect(jsonPath("$.items[0].versions[0].estimatedCostAmount").isNumber())
                .andExpect(jsonPath("$.items[0].versions[0].estimatedMarginRatio").isNumber())
                .andExpect(jsonPath("$.items[0].versions[0].marginWarning").isBoolean())
                .andExpect(jsonPath("$.items[0].versions[0].tokensPerMinute").value(100_000))
                // 速率所计数的单位，因此控制台在管理目录上也能标注 TPM 还是 SPM，
                // 视频版本也就不会被读成 token 速率。
                .andExpect(jsonPath("$.items[0].versions[0].usageDimension").value("TOKEN"))
                .andExpect(jsonPath("$.items[0].versions[1].status").value("RETIRED"))
                .andExpect(jsonPath("$.items[0].versions[1].estimatedCostAmount").value(1_800))
                .andExpect(jsonPath("$.items[0].versions[1].estimatedMarginRatio").value(0.37931))
                .andExpect(jsonPath("$.items[0].versions[1].marginWarning").value(true))
                .andExpect(jsonPath("$.items[0].versions[2].status").value("RETIRED"))
                .andExpect(jsonPath("$.items[0].versions[3].status").value("RETIRED"));

        Map<String, Object> inventory = jdbcTemplate.queryForMap("""
                SELECT stock_total, stock_sold, stock_reserved, low_stock_threshold,
                       max_purchases_per_user
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                WHERE p.code = 'STARTER' AND v.version = 4
                """);
        assertThat(inventory)
                .containsEntry("stock_total", 25L)
                .containsEntry("stock_sold", 0L)
                .containsEntry("stock_reserved", 0L)
                .containsEntry("low_stock_threshold", 3L)
                .containsEntry("max_purchases_per_user", 2);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action = 'PACKAGE_VERSION_CREATED'
                  AND detail #>> '{after,status}' = 'DRAFT'
                  AND detail #>> '{after,version}' = '4'
                """, Integer.class, adminId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action = 'PACKAGE_VERSION_PUBLISHED'
                  AND detail #>> '{before,status}' = 'DRAFT'
                  AND detail #>> '{after,status}' = 'PUBLISHED'
                """, Integer.class, adminId)).isEqualTo(1);
    }

    /**
     * 控制台的档位登记词表接受 {@code 1.5K}（V106）。
     *
     * <p>这是"1.5K 能不能上架"的最后一道门。网关 V115 让 1.5K 能提交、能计成本，
     * 但别名要在商城卖出去，运维必须先在控制台把它登记成一个按张计价的 IMAGE
     * 别名——词表里少一档时这里会 422，而错误信息只会说"档位非法"，看不出
     * 网关那边其实已经支持了。
     *
     * <p>反面对照同样重要：放开 1.5K 只针对 IMAGE。视频别名声称一个图片尺寸档位
     * 必须仍然被拒，否则按分辨率加权的视频成本基准会拿到一个无法解释的档位。
     */
    @Test
    @Transactional
    void registersTheOnePointFiveKImageTierButNotForVideoAliases() throws Exception {
        String adminToken = accessToken("ADMIN");

        mockMvc.perform(put("/console/platform/admin/model-dimensions/image-1-5k-probe")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usageDimension":"IMAGE","resolutionTier":"1.5K",
                                 "effectiveUnitCost":62,"retailUnitPrice":89}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelCode").value("image-1-5k-probe"))
                .andExpect(jsonPath("$.usageDimension").value("IMAGE"))
                .andExpect(jsonPath("$.resolutionTier").value("1.5K"));

        mockMvc.perform(put("/console/platform/admin/model-dimensions/video-1-5k-probe")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usageDimension":"VIDEO_SECOND","resolutionTier":"1.5K"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_model_resolution_tier"));
    }

    /**
     * V63 犯下的错误 —— 把 token 速率写进秒数列 —— 在一个版本可能经过的
     * 两道门处都被拒绝，并在响应体中指明单位。
     *
     * <p>create 是运维使用的那道门；publish 是已存在的行所经过的那道门，
     * 它会重新检查存储的速率，而不是相信 create 已经看过它。断言针对
     * 传输信封（{@code {code,message}}），因为那正是管理控制台渲染的内容，
     * 同时也针对数据行，因为被拒绝的版本不得留下任何痕迹。
     */
    @Test
    @Transactional
    void refusesATokenRateInASecondsColumnOnCreateAndOnPublish() throws Exception {
        String adminToken = accessToken("ADMIN");
        when(providerUnitCostClient.effectiveUnitCosts(anySet()))
                .thenReturn(Map.of("video-standard", new BigDecimal("115")));
        Map<String, Long> ceilingsBefore = publishedVideoCeilings();

        // V63 写入的三个上限，再加上边界值。100,000 就是那个错误的形状。
        for (long tokenRate : new long[] {100_000L, 300_000L, 500_000L, 86_401L}) {
            mockMvc.perform(videoVersionRequest(adminToken, tokenRate))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code")
                            .value("video_seconds_per_minute_out_of_range"))
                    // 这条消息必须教会这个约定，而不仅仅是拒绝。
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.containsString("秒")))
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.containsString("86400")));
        }
        assertThat(draftVersions("VIDEO_STARTER")).isZero();

        // 真正的秒数上限会被接受，包括文档中记载的上边界。
        for (long ceiling : new long[] {60L, 190L, 600L, 86_400L}) {
            mockMvc.perform(videoVersionRequest(adminToken, ceiling))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        }
        assertThat(draftVersions("VIDEO_STARTER")).isEqualTo(4);

        // 非正速率绝不触达单位防护：请求形状校验器会先用通用错误码把它们
        // 拒绝掉。防护自身的 `> 0` 分支在
        // PackageRateCeilingPolicyTest 中断言，那是在其他情况下唯一可能
        // 构造出已存储的非正速率的地方。
        for (long invalid : new long[] {0L, -1L}) {
            mockMvc.perform(videoVersionRequest(adminToken, invalid))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("invalid_package_version"));
        }

        // 发布：一个携带 token 速率的草稿，正如在这道防护存在之前写入的任何行
        // 那样。即使 create 不会产出它，它也必须被拒绝，而且这次
        // 拒绝必须让已发布目录与 V64 发布时完全一致。
        UUID legacyDraft = insertVideoDraftWithRate(100_000L);
        mockMvc.perform(post("/console/platform/admin/plans/VIDEO_STARTER/publish")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":9}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("video_seconds_per_minute_out_of_range"));
        assertThat(versionStatus(legacyDraft)).isEqualTo("DRAFT");
        assertThat(publishedVideoCeilings()).isEqualTo(ceilingsBefore);

        // ……而同一个草稿换成合法上限后就能发布，因此拒绝的原因是单位，
        // 而不是草稿本身。V64 发布的那一行保留自己的值；它被退役，而不是被改写。
        jdbcTemplate.update("""
                UPDATE platform.package_plan_version SET tokens_per_minute = 600 WHERE id = ?
                """, legacyDraft);
        mockMvc.perform(post("/console/platform/admin/plans/VIDEO_STARTER/publish")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        assertThat(publishedVideoCeilings())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "VIDEO_CREATOR", 190L, "VIDEO_PRO", 600L, "VIDEO_STARTER", 600L,
                        // V66 的两个 480P 入门档：它们此前不在这个 map 的期望里，
                        // 而 map 本身是按 code LIKE 'VIDEO%' 查出来的完整已发布视频
                        // 目录，因此漏掉它们会让「完整目录」这句话变成三个套餐方案的
                        // 子集断言。基线（V84 之前）的运行证明这与 V84 无关：
                        // 那时它就已经是 5 项。
                        "VIDEO_LITE", 60L, "VIDEO_LITE_ANNUAL", 60L));
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, tokens_per_minute, status
                FROM platform.package_plan_version
                WHERE id = '00000000-0000-4000-8000-000000000321'
                """)).containsEntry("version", 3)
                .containsEntry("tokens_per_minute", 60L)
                .containsEntry("status", "RETIRED");
    }

    /**
     * 同一道防护的 token 分支：授予 TEXT 的版本不能售卖一个
     * 无法服务一次对话的速率，而 10,000 是 V64 为已发布行断言的下限。
     */
    @Test
    @Transactional
    void refusesATextVersionWhoseTokenRateCannotServeAConversation() throws Exception {
        String adminToken = accessToken("ADMIN");

        mockMvc.perform(tokenVersionRequest(adminToken, 9_999L))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("text_tokens_per_minute_below_minimum"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("10000")));
        assertThat(draftVersions("STARTER")).isZero();

        mockMvc.perform(tokenVersionRequest(adminToken, 10_000L))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        assertThat(draftVersions("STARTER")).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            videoVersionRequest(String adminToken, long tokensPerMinute) {
        return post("/console/platform/admin/plans/VIDEO_STARTER/versions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "planCode": "VIDEO_STARTER",
                          "priceAmount": 12900,
                          "totalTokenQuota": 60,
                          "durationDays": 30,
                          "maxApiKeys": 2,
                          "requestsPerMinute": 20,
                          "tokensPerMinute": %d,
                          "maxConcurrency": 2,
                          "maxFileSizeBytes": 20971520,
                          "oncePerUser": false,
                          "usageDimension": "VIDEO_SECOND",
                          "models": [{
                            "modelCode": "video-standard",
                            "capabilities": ["VIDEO"]
                          }]
                        }
                        """.formatted(tokensPerMinute));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            tokenVersionRequest(String adminToken, long tokensPerMinute) {
        return post("/console/platform/admin/plans/STARTER/versions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "planCode": "STARTER",
                          "priceAmount": 1900,
                          "totalTokenQuota": 5000000,
                          "durationDays": 30,
                          "maxApiKeys": 3,
                          "requestsPerMinute": 20,
                          "tokensPerMinute": %d,
                          "maxConcurrency": 3,
                          "maxFileSizeBytes": 20971520,
                          "oncePerUser": false,
                          "models": [{
                            "modelCode": "text-standard",
                            "capabilities": ["TEXT"]
                          }]
                        }
                        """.formatted(tokensPerMinute));
    }

    /**
     * 一条携带调用方任意指定速率的 DRAFT 行，直接写入表中：
     * 这是用一个 create 现在会拒绝的速率走到发布的唯一途径，也正是
     * 防护生效之前的行所处的状态。
     */
    private UUID insertVideoDraftWithRate(long tokensPerMinute) {
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, price_amount, currency, total_token_quota,
                    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
                    max_concurrency, target_margin_ratio, status, max_file_size_bytes,
                    once_per_user, low_stock_threshold, usage_dimension)
                SELECT ?, p.id, 9, 12900, 'CNY', 60, 30, 2, 20, ?, 2, 0.35000, 'DRAFT',
                       20971520, false, 0, 'VIDEO_SECOND'
                FROM platform.package_plan p WHERE p.code = 'VIDEO_STARTER'
                """, versionId, tokensPerMinute);
        jdbcTemplate.update("""
                INSERT INTO platform.package_plan_version_model(
                    id, package_plan_version_id, model_code, model_token_quota, capabilities)
                VALUES (gen_random_uuid(), ?, 'video-standard', NULL, '["VIDEO"]'::jsonb)
                """, versionId);
        return versionId;
    }

    /**
     * 每个视频套餐当前作为其每分钟上限所售卖的值。这个 map 就是整个
     * 已发布视频目录，因此一个悄悄改写或退役了在线版本的防护会
     * 表现为某个条目发生移动，而不是表现为断言通过。
     */
    private Map<String, Long> publishedVideoCeilings() {
        Map<String, Long> ceilings = new java.util.TreeMap<>();
        jdbcTemplate.query("""
                SELECT p.code, v.tokens_per_minute
                FROM platform.package_plan_version v
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                WHERE p.code LIKE 'VIDEO%' AND v.status = 'PUBLISHED'
                """, (result, rowNumber) -> {
            ceilings.put(result.getString("code"), result.getLong("tokens_per_minute"));
            return null;
        });
        return ceilings;
    }

    private long draftVersions(String planCode) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.package_plan_version v
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                WHERE p.code = ? AND v.status = 'DRAFT'
                """, Long.class, planCode);
    }

    private String versionStatus(UUID versionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM platform.package_plan_version WHERE id = ?",
                String.class, versionId);
    }

    @Test
    @Transactional
    void adminCreatesArbitraryPackagePlanWithoutChoosingSeedCode() throws Exception {
        String adminToken = accessToken("ADMIN");

        mockMvc.perform(post("/console/platform/admin/package-plans")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "图像生成体验包",
                                  "description": "用于短期图像模型体验",
                                  "kind": "STANDARD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("PKG_")))
                .andExpect(jsonPath("$.name").value("图像生成体验包"))
                .andExpect(jsonPath("$.kind").value("STANDARD"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/console/platform/admin/package-plans")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("query", "图像生成"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("图像生成体验包"))
                .andExpect(jsonPath("$.items[0].versions.length()").value(0));

        mockMvc.perform(post("/console/platform/admin/package-plans")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"另一个免费套餐","kind":"FREE_CLAIM"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("active_free_package_exists"));
    }

    @Test
    @Transactional
    void adminEditsArchivesAndRestoresPackagePlan() throws Exception {
        String adminToken = accessToken("ADMIN");
        String userToken = accessToken("USER");
        UUID adminId = jdbcTemplate.queryForObject("""
                SELECT id FROM platform.app_user
                WHERE display_name = 'ADMIN' ORDER BY created_at DESC LIMIT 1
                """, UUID.class);
        UUID versionId = jdbcTemplate.queryForObject("""
                SELECT v.id
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                WHERE p.code = 'ADVANCED' AND v.status = 'PUBLISHED'
                ORDER BY v.version DESC LIMIT 1
                """, UUID.class);

        mockMvc.perform(patch("/console/platform/admin/plans/ADVANCED")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"专业套餐","description":"适合稳定业务调用"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ADVANCED"))
                .andExpect(jsonPath("$.name").value("专业套餐"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(patch("/console/platform/admin/plans/ADVANCED")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"专业套餐","description":"  "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("专业套餐"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT description IS NULL
                FROM platform.package_plan
                WHERE code = 'ADVANCED'
                """, Boolean.class)).isTrue();

        mockMvc.perform(post("/console/platform/admin/plans/ADVANCED/archive")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mockMvc.perform(get("/console/platform/plans/ADVANCED")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> packageSnapshotService.loadPublished(versionId))
                .hasMessageContaining("published_package_version_not_found");

        mockMvc.perform(get("/console/platform/admin/plans")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("query", "ADVANCED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("专业套餐"))
                .andExpect(jsonPath("$.items[0].planStatus").value("ARCHIVED"));

        mockMvc.perform(post("/console/platform/admin/plans/ADVANCED/restore")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/console/platform/plans/ADVANCED")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("专业套餐"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ?
                  AND action IN ('PACKAGE_PLAN_UPDATED', 'PACKAGE_PLAN_ARCHIVED', 'PACKAGE_PLAN_RESTORED')
                  AND resource_type = 'PACKAGE_PLAN'
                """, Integer.class, adminId)).isEqualTo(4);
    }

    @Test
    @Transactional
    void rejectsInvalidPackagePlanName() throws Exception {
        String adminToken = accessToken("ADMIN");

        mockMvc.perform(patch("/console/platform/admin/plans/ADVANCED")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"description\":null}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("package_plan_name_required"));
    }

    @Test
    void seedsStableModelPermissionsAndSubQuotas() {
        assertThat(modelsFor("FREE", 1))
                .containsExactlyInAnyOrder(
                        "text-basic", "vision-basic", "file-basic", "multimodal-basic");
        assertThat(modelsFor("STARTER", 1))
                .containsExactlyInAnyOrder(
                        "text-basic", "vision-basic", "file-basic", "multimodal-basic", "text-standard");
        assertThat(modelsFor("ADVANCED", 1))
                .containsExactlyInAnyOrder(
                        "text-basic", "vision-basic", "file-basic", "multimodal-basic",
                        "text-standard", "text-pro", "vision-pro");
        assertThat(modelsFor("ENTERPRISE", 2))
                .containsExactlyInAnyOrder(
                        "text-basic", "vision-basic", "file-basic", "multimodal-basic",
                        "text-standard", "text-pro", "vision-pro", "text-max");

        assertThat(modelQuota("FREE", 1, "multimodal-basic")).isNull();
        assertThat(modelQuota("ADVANCED", 1, "text-pro")).isEqualTo(10_000_000L);
        assertThat(modelQuota("ADVANCED", 1, "vision-pro")).isEqualTo(2_000_000L);
        assertThat(modelQuotas("ENTERPRISE", 2)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "text-basic", 60_000_000L,
                "vision-basic", 5_000_000L,
                "file-basic", 5_000_000L,
                "multimodal-basic", 3_000_000L,
                "text-standard", 10_000_000L,
                "text-pro", 10_000_000L,
                "vision-pro", 5_000_000L,
                "text-max", 2_000_000L));
        assertThat(modelQuotas("ENTERPRISE", 2).values()).doesNotContainNull();
        assertThat(modelQuotas("ENTERPRISE", 2).values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(100_000_000L);
    }

    @Test
    @Transactional
    void reportsPackageVersionsReferencingAModelToAdministrators() throws Exception {
        PackageVersionService.ModelReferenceView references =
                packageVersionService.modelReferences("text-basic");

        assertThat(references.referenceCount()).isGreaterThan(0);
        assertThat(references.references()).isNotEmpty();
        assertThat(references.references())
                .allSatisfy(reference -> assertThat(reference.version()).isPositive());

        mockMvc.perform(get("/console/platform/admin/package-model-references/text-basic")
                        .header("Authorization", "Bearer " + accessToken("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelCode").value("text-basic"))
                .andExpect(jsonPath("$.referenceCount").value(
                        org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.references").isArray());
    }

    @Test
    void seedsInitialNonPromotionalCostEstimates() {
        assertCostEstimate("FREE", 20L, null);
        assertCostEstimate("STARTER", 1_008L, new BigDecimal("0.46947"));
        assertCostEstimate("ADVANCED", 11_613L, new BigDecimal("0.41643"));
        assertCostEstimate("ENTERPRISE", 152_000L, new BigDecimal("0.39176"));
        // V84 的图片计划：发布时按 60 分/张 的基准算出的 48 × 60 = 2,880 分最坏成本，
        // (4,990 − 2,880) / 4,990 = 42.285%。存下来的三元组必须仍然是这一对：
        // 已发布版本不可变，V85 改动成本基准不会回头改写它（运行期监控器按
        // 新基准重算，得到 40.361%，仍高于 30% 下限）。
        assertCostEstimate("IMAGE_BASIC", 2_880L, new BigDecimal("0.42285"));
    }

    @Test
    void rejectsPackageVersionBelowThirtyPercentTokenMargin() {
        when(providerPriceClient.nonPromotionalOutputPrices(anySet()))
                .thenReturn(Map.of("text-max", new BigDecimal("1000.00")));

        PackageVersionService.CreateVersionRequest request = new PackageVersionService.CreateVersionRequest(
                "ENTERPRISE", 100_000L, 100_000_000L, 30, 50, 300, 2_000_000L, 50,
                null, null, 20L * MIB, false,
                Set.of(new PackageVersionService.ModelPermission("text-max", 100_000_000L, Set.of("TEXT"))));

        assertThatThrownBy(() -> packageVersionService.createVersion(request, UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("token_margin_below_minimum");
    }

    @Test
    void acceptsVideoCapabilityBecauseSecondsArePricedFromTheRateCard() {
        when(providerPriceClient.nonPromotionalOutputPrices(anySet()))
                .thenReturn(Map.of("text-max", new BigDecimal("1000.00")));

        PackageVersionService.CreateVersionRequest request = new PackageVersionService.CreateVersionRequest(
                "ENTERPRISE", 100_000L, 100_000_000L, 30, 50, 300, 2_000_000L, 50,
                null, null, 20L * MIB, false,
                Set.of(new PackageVersionService.ModelPermission(
                        "text-max", 100_000_000L, Set.of("TEXT", "VIDEO"))));

        // VIDEO 现在是允许的能力。能走到毛利防护就证明
        // 能力检查已经通过，因为 validateVersionKind 在它之前运行。
        assertThatThrownBy(() -> packageVersionService.createVersion(request, UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("token_margin_below_minimum");
    }

    @Test
    void rejectsPermissionWithCapabilityOutsideTheAllowedSet() {
        when(providerPriceClient.nonPromotionalOutputPrices(anySet()))
                .thenReturn(Map.of("text-max", new BigDecimal("1000.00")));

        PackageVersionService.CreateVersionRequest request = new PackageVersionService.CreateVersionRequest(
                "ENTERPRISE", 100_000L, 100_000_000L, 30, 50, 300, 2_000_000L, 50,
                null, null, 20L * MIB, false,
                Set.of(new PackageVersionService.ModelPermission(
                        "text-max", 100_000_000L, Set.of("TEXT", "AUDIO"))));

        assertThatThrownBy(() -> packageVersionService.createVersion(request, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_model_permission");
    }

    @Test
    void rejectsZeroPricedVersionOutsideFreePlan() {
        PackageVersionService.CreateVersionRequest request = new PackageVersionService.CreateVersionRequest(
                "STARTER", 0L, 5_000_000L, 30, 3, 20, 100_000L, 3,
                null, null, 20L * MIB, false,
                Set.of(new PackageVersionService.ModelPermission("text-standard", null, Set.of("TEXT"))));

        assertThatThrownBy(() -> packageVersionService.createVersion(request, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_package_version");
    }

    /**
     * 若网关会按低于自身成本基准的价格为某个版本的别名计费，该版本
     * 不得发布。
     *
     * <p>当 {@code text-pro} 与 {@code text-standard} 携带的零售价低于
     * 毛利防护据以批准该套餐的成本基准时，缺失的正是这项检查：
     * 防护按成本定价，网关按零售价计费，而没有任何东西比较
     * 这两者。这里成本基准保持 10.00，而零售价牌被降到它之下，因此
     * 这次拒绝可归因于零售价比较，而不是毛利下限。
     */
    @Test
    void rejectsAVersionWhoseRetailPriceIsBelowTheCostBasis() {
        when(providerPriceClient.nonPromotionalOutputPrices(anySet()))
                .thenReturn(Map.of("text-max", new BigDecimal("1000.00")));
        when(providerRetailPriceClient.currentRetailPrices(anySet()))
                .thenReturn(Map.of(
                        "text-max", new ProviderRetailPriceClient.RetailPrice(340, 900)));

        PackageVersionService.CreateVersionRequest request = new PackageVersionService.CreateVersionRequest(
                "ENTERPRISE", 5_000_000L, 5_000_000L, 30, 50, 300, 2_000_000L, 50,
                null, null, 20L * MIB, false,
                Set.of(new PackageVersionService.ModelPermission("text-max", null, Set.of("TEXT"))));

        assertThatThrownBy(() -> packageVersionService.createVersion(request, UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("provider_retail_below_cost:text-max")
                .hasMessageContaining("retail=900")
                .hasMessageContaining("cost=1000");
    }

    /**
     * 没有任何当前零售行的别名根本无法计费，因此授予它的版本
     * 不得发布，而不是发布了却在请求时失败。
     */
    @Test
    void rejectsAVersionWhoseAliasHasNoChargeableRetailPrice() {
        when(providerPriceClient.nonPromotionalOutputPrices(anySet()))
                .thenReturn(Map.of("text-max", new BigDecimal("1000.00")));
        when(providerRetailPriceClient.currentRetailPrices(anySet())).thenReturn(Map.of());

        PackageVersionService.CreateVersionRequest request = new PackageVersionService.CreateVersionRequest(
                "ENTERPRISE", 5_000_000L, 5_000_000L, 30, 50, 300, 2_000_000L, 50,
                null, null, 20L * MIB, false,
                Set.of(new PackageVersionService.ModelPermission("text-max", null, Set.of("TEXT"))));

        assertThatThrownBy(() -> packageVersionService.createVersion(request, UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("provider_retail_price_missing:text-max");
    }

    @Test
    void rejectsPackageVersionBelowThirtyFivePercentWarningThreshold() {
        PackageMarginPolicy.MarginAssessment assessment = new PackageMarginPolicy().assess(
                10_000L,
                10_000_000L,
                Set.of(new PackageVersionService.ModelPermission("text-pro", null, Set.of("TEXT"))),
                PackageMarginPolicy.tokenPolicy(Map.of("text-pro", new BigDecimal("680.00"))));

        assertThat(assessment.marginRatio()).isEqualByComparingTo("0.32000");
        assertThat(assessment.warning()).isTrue();
        assertThat(assessment.publishable()).isTrue();
    }

    /**
     * 零成本基准不得可发布。
     *
     * <p>零以前会产生成本 0、毛利率 100% 以及
     * {@code publishable = true}，因此任何价格都能通过。零在生产环境中
     * 是可达的：提供方价格约束允许 {@code output_price_per_million >= 0}，
     * 费率卡允许 {@code provider_unit_cost >= 0}，而占位的或
     * 尚未议定的提供方行正好位于 0 —— 这正是全新栈中唯一
     * 启用的提供方所处的状态。因此「免费」的成本基准必须像
     * 缺失一样对待：拒绝售卖，而不是报告一个没人赚到的毛利率。
     */
    @Test
    void rejectsAZeroCostBasisInEveryDimension() {
        Set<PackageVersionService.ModelPermission> permissions = Set.of(
                new PackageVersionService.ModelPermission("text-pro", null, Set.of("TEXT")));
        PackageMarginPolicy policy = new PackageMarginPolicy();

        assertThatThrownBy(() -> policy.assess(
                10_000L, 10_000_000L, permissions,
                PackageMarginPolicy.tokenPolicy(Map.of("text-pro", BigDecimal.ZERO))))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("provider_price_missing:text-pro:TOKEN");

        // 一个本来会轻松越过毛利率下限的零价格：100 分买到
        // 100,000,000 token，在任何真实成本下都是亏损，而在零成本下「不花什么钱」。
        assertThatThrownBy(() -> policy.assess(
                100L, 100_000_000L, permissions,
                PackageMarginPolicy.tokenPolicy(Map.of("text-pro", BigDecimal.ZERO))))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class);

        Set<PackageVersionService.ModelPermission> video = Set.of(
                new PackageVersionService.ModelPermission("video-basic", null, Set.of("VIDEO")));
        assertThatThrownBy(() -> policy.assess(
                100L, 10_000L, video,
                PackageMarginPolicy.secondPolicy(Map.of("video-basic", BigDecimal.ZERO))))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("provider_price_missing:video-basic:VIDEO_SECOND");

        // 负值因同样的原因被拒绝，而且此前就已经被拒绝。
        assertThatThrownBy(() -> policy.assess(
                10_000L, 10_000_000L, permissions,
                PackageMarginPolicy.tokenPolicy(Map.of("text-pro", new BigDecimal("-1")))))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class);

        // 缺失仍然被拒绝：这是该防护最初的契约。
        assertThatThrownBy(() -> policy.assess(
                10_000L, 10_000_000L, permissions, PackageMarginPolicy.tokenPolicy(Map.of())))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class);
    }

    @Test
    void rejectsModelSubQuotaAbovePackageTotal() {
        PackageVersionService.CreateVersionRequest request = new PackageVersionService.CreateVersionRequest(
                "STARTER", 1_900L, 5_000_000L, 30, 3, 20, 100_000L, 3,
                null, null, 20L * MIB, false,
                Set.of(new PackageVersionService.ModelPermission("text-standard", 5_000_001L, Set.of("TEXT"))));

        assertThatThrownBy(() -> packageVersionService.createVersion(request, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_model_permission");
    }

    /**
     * 方案 C 第 4 步：一个"视频秒 + 音频秒"的版本可以被创建，第二份额度真的落库，
     * 而且毛利守卫**按各自那一份额度**给两个桶计价。
     *
     * <p>两个模型的自额度（视频 100、音频 600）都取到了正确的上限，这本身证明了
     * "自额度按它自己的列比较"：600 秒的语音子额度大于 100 秒的视频总额度，
     * 若拿视频那一列去比，这个版本会被 {@code invalid_model_permission} 拒绝。
     *
     * <p>成本断言（1600 = 100×10 + 600×1）钉住的是守卫的另一半：音频那一半必须用
     * **音频的 600 秒**摊成本，而不是版本的 100。用错额度会让成本变成 1100，
     * 即少算 500 秒的语音成本——而少算成本的方向正是"把一个亏损套餐发出去"。
     */
    @Test
    @Transactional
    void createsAMixedVideoAndAudioVersionAndCostsEachBucketWithItsOwnQuota() {
        stubBothGenerationCosts();

        PackageVersionService.CreatedVersion created = packageVersionService.createVersion(
                mixedVideoAndAudioRequest(600L, mixedModels(600L)), UUID.randomUUID());

        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT audio_seconds_quota FROM platform.package_plan_version WHERE id = ?
                """, Long.class, created.id())).isEqualTo(600L);
        assertThat(created.margin().estimatedCostAmount()).isEqualTo(1_600L);
    }

    /**
     * 第二份额度与这组模型不一致的四个方向，全部在创建时拒绝。
     *
     * <p>各自的真实事故：有音频别名却没有额度（每次语音请求都以
     * {@code quota_exhausted} 结束，而客户已经付过款）、没有音频别名却有额度
     * （一个无人能消费、却会显示在控制台上的承诺）、纯音频版本填了两份额度
     * （"这次请求扣哪一列"出现两个都说得通的答案）、音频子额度超过音频总额度
     * （上限取错了列）。
     */
    @Test
    void rejectsAudioSecondsQuotasThatDoNotMatchTheVersionsModels() {
        stubBothGenerationCosts();

        Set<PackageVersionService.ModelPermission> audioOnly = Set.of(
                new PackageVersionService.ModelPermission(
                        "audio-asr-basic", 600L, Set.of("AUDIO_TRANSCRIBE")));
        Set<PackageVersionService.ModelPermission> textOnly = Set.of(
                new PackageVersionService.ModelPermission(
                        "text-standard", null, Set.of("TEXT")));

        assertThatThrownBy(() -> packageVersionService.createVersion(
                mixedVideoAndAudioRequest(null, mixedModels(600L)), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("audio_alias_requires_audio_seconds_quota");

        assertThatThrownBy(() -> packageVersionService.createVersion(
                new PackageVersionService.CreateVersionRequest(
                        "STARTER", 1_900L, 5_000_000L, 600L, 30, 3, 20, 100_000L, 3,
                        null, null, 20L * MIB, false, null, 0, null, "TOKEN", textOnly),
                UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("audio_seconds_quota_without_an_audio_alias");

        assertThatThrownBy(() -> packageVersionService.createVersion(
                new PackageVersionService.CreateVersionRequest(
                        "STARTER", 3_000L, 600L, 600L, 30, 3, 60, 100_000L, 3,
                        null, null, 20L * MIB, false, null, 0, null, "AUDIO_SECOND", audioOnly),
                UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("audio_seconds_quota_on_a_pure_audio_version");

        // 音频子额度 601 > 音频总额度 600：上限必须取自音频那一列。
        assertThatThrownBy(() -> packageVersionService.createVersion(
                mixedVideoAndAudioRequest(600L, mixedModels(601L)), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_model_permission");
    }

    /**
     * 混合版本里**音频那一半**的毛利不达标必须拒绝整个版本。
     *
     * <p>方案 C 第 3 步的核心主张是"按每个维度各算一次、任一不过即拒"。只算主维度
     * 的实现会放行这个版本：视频那一半（100 秒 × 1 分）在 3000 分的售价下毛利
     * 96.7%，而音频那一半（600 秒 × 100 分 = 60000 分成本）是严重亏损。
     * 一个亏损的音频别名搭着视频套餐发出去，正是这一步要防的事。
     */
    @Test
    void rejectsAMixedVersionWhoseAudioHalfIsBelowTheMarginFloor() {
        when(providerUnitCostClient.effectiveUnitCosts(anySet())).thenReturn(Map.of(
                "video-basic", new BigDecimal("1"),
                "audio-asr-basic", new BigDecimal("100")));

        assertThatThrownBy(() -> packageVersionService.createVersion(
                mixedVideoAndAudioRequest(600L, mixedModels(600L)), UUID.randomUUID()))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("token_margin_below_minimum");

        assertThat(draftVersions("STARTER")).isZero();
    }

    /**
     * "音频秒"只与"视频秒"并存；token 与任何按秒维度仍然不能混在一个版本里。
     *
     * <p>这是方案 C 放宽发布校验时唯一不能一起放走的东西：token 与秒的额度列
     * 在结构上不是同一列，混装会让一类用量悄悄花掉另一类的额度。
     */
    @Test
    void stillRejectsAPackageThatMixesTokensWithPerSecondDimensions() {
        stubBothGenerationCosts();

        Set<PackageVersionService.ModelPermission> mixed = Set.of(
                new PackageVersionService.ModelPermission("text-standard", null, Set.of("TEXT")),
                new PackageVersionService.ModelPermission("video-basic", 100L, Set.of("VIDEO")));

        assertThatThrownBy(() -> packageVersionService.createVersion(
                mixedVideoAndAudioRequest(null, mixed), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("mixed_usage_dimensions_in_package");
    }

    private void stubBothGenerationCosts() {
        when(providerUnitCostClient.effectiveUnitCosts(anySet())).thenReturn(Map.of(
                "video-basic", new BigDecimal("10"),
                "audio-asr-basic", new BigDecimal("1")));
    }

    private PackageVersionService.CreateVersionRequest mixedVideoAndAudioRequest(
            Long audioSecondsQuota, Set<PackageVersionService.ModelPermission> models) {
        // tokens_per_minute 对按秒维度意味着"每分钟秒数上限"：它必须是秒量级
        // （<= 86400），否则 PackageRateCeilingPolicy 会以
        // video_seconds_per_minute_out_of_range 拒绝——这正是 V63 犯过的错。
        return new PackageVersionService.CreateVersionRequest(
                "STARTER", 3_000L, 100L, audioSecondsQuota, 30, 3, 60, 600L, 3,
                null, null, 20L * MIB, false, null, 0, null, "VIDEO_SECOND", models);
    }

    private Set<PackageVersionService.ModelPermission> mixedModels(long audioModelQuota) {
        return Set.of(
                new PackageVersionService.ModelPermission("video-basic", 100L, Set.of("VIDEO")),
                new PackageVersionService.ModelPermission(
                        "audio-asr-basic", audioModelQuota, Set.of("AUDIO_TRANSCRIBE")));
    }

    @Test
    void rejectsUnknownModelCapability() {
        PackageVersionService.CreateVersionRequest request = new PackageVersionService.CreateVersionRequest(
                "STARTER", 1_900L, 5_000_000L, 30, 3, 20, 100_000L, 3,
                null, null, 20L * MIB, false,
                Set.of(new PackageVersionService.ModelPermission("text-standard", null, Set.of("AUDIO"))));

        assertThatThrownBy(() -> packageVersionService.createVersion(request, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_model_permission");
    }

    @Test
    void publishedVersionCannotBeChangedInPlace() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE platform.package_plan_version v
                SET price_amount = 2000
                FROM platform.package_plan p
                WHERE p.id = v.package_plan_id AND p.code = 'STARTER' AND v.version = 1
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("published_version_immutable");
    }

    /**
     * 按套餐方案代码定位 /console/platform/plans 里的一行。该列表每个套餐方案只有
     * 一行（见 findPublishedPlans 的 DISTINCT ON (p.id)），因此 code 是稳定的定位键；
     * 一旦某个套餐方案再发布一个版本，写死下标的断言就会静默挪到别的行上。
     */
    private static String planRow(String code) {
        return "$[?(@.code=='" + code + "')]";
    }

    private void assertPlan(
            String code, String usageDimension, int version, long tokenQuota, long priceAmount,
            int maxApiKeys, int rpm, int maxConcurrency) {
        Map<String, Object> plan = jdbcTemplate.queryForMap("""
                SELECT v.total_token_quota, v.price_amount, v.max_api_keys,
                       v.requests_per_minute, v.max_concurrency, v.duration_days,
                       v.max_file_size_bytes, v.status, v.usage_dimension
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                WHERE p.code = ? AND v.version = ?
                """, code, version);
        assertThat(plan)
                .containsEntry("total_token_quota", tokenQuota)
                .containsEntry("price_amount", priceAmount)
                .containsEntry("max_api_keys", maxApiKeys)
                .containsEntry("requests_per_minute", rpm)
                .containsEntry("max_concurrency", maxConcurrency)
                .containsEntry("duration_days", 30)
                .containsEntry("max_file_size_bytes", code.equals("FREE") ? 5L * MIB : 20L * MIB)
                // 额度所在的那个单位：同一列在 TOKEN 版本里是 token，在 IMAGE 版本里
                // 是张数，因此「额度是 48」这句话只有连同维度一起才是完整的。
                .containsEntry("usage_dimension", usageDimension)
                .containsEntry("status", "PUBLISHED");
    }

    private java.util.List<String> modelsFor(String planCode, int version) {
        return jdbcTemplate.queryForList("""
                SELECT m.model_code
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                JOIN platform.package_plan_version_model m ON m.package_plan_version_id = v.id
                WHERE p.code = ? AND v.version = ?
                ORDER BY m.model_code
                """, String.class, planCode, version);
    }

    private Long modelQuota(String planCode, int version, String modelCode) {
        return jdbcTemplate.queryForObject("""
                SELECT m.model_token_quota
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                JOIN platform.package_plan_version_model m ON m.package_plan_version_id = v.id
                WHERE p.code = ? AND v.version = ? AND m.model_code = ?
                """, Long.class, planCode, version, modelCode);
    }

    private Map<String, Long> modelQuotas(String planCode, int version) {
        return jdbcTemplate.query("""
                SELECT m.model_code, m.model_token_quota
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                JOIN platform.package_plan_version_model m ON m.package_plan_version_id = v.id
                WHERE p.code = ? AND v.version = ?
                """, result -> {
                    Map<String, Long> quotas = new java.util.LinkedHashMap<>();
                    while (result.next()) {
                        quotas.put(result.getString("model_code"), result.getLong("model_token_quota"));
                    }
                    return quotas;
                }, planCode, version);
    }

    private void assertCostEstimate(String planCode, long costAmount, BigDecimal marginRatio) {
        Map<String, Object> estimate = jdbcTemplate.queryForMap("""
                SELECT v.estimated_cost_amount, v.estimated_margin_ratio
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                WHERE p.code = ? AND v.version = 1
                """, planCode);
        assertThat(estimate.get("estimated_cost_amount")).isEqualTo(costAmount);
        if (marginRatio == null) {
            assertThat(estimate.get("estimated_margin_ratio")).isNull();
        } else {
            assertThat((BigDecimal) estimate.get("estimated_margin_ratio"))
                    .isEqualByComparingTo(marginRatio);
        }
    }

    private String accessToken(String role) {
        UUID userId = UUID.randomUUID();
        String email = role.toLowerCase() + "." + userId + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'test-password-hash', ?, 'ACTIVE')
                """, userId, userId, email, role);
        Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform.app_role WHERE code = ?", Long.class, role);
        jdbcTemplate.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                VALUES (?, ?, ?)
                """, userId, userId, roleId);
        return jwtTokenService.createAccessToken(new IdentityService.UserProfile(
                userId, userId, email, role, role));
    }
}
