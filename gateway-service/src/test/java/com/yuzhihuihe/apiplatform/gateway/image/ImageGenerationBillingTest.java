package com.yuzhihuihe.apiplatform.gateway.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 针对真实数据库的端到端图片计费。
 *
 * <p>这些测试之所以存在，是因为图片的金钱行为恰恰是无法靠读代码检查的那部分：
 * 一次调用按最大张数预留、按实际返回的张数结算，客户端可能在超时后重放同一个
 * 幂等键，而厂商可能在完全没有产出图片的情况下拒绝这次调用。
 * 每个测试把其中一种结果钉到一个具体的配额效果上。
 *
 * <p>两条被单独钉住的计费规则：**一次请求最多买一张**（网关不向厂商转发
 * {@code n}，因此更大的订单会被拒绝，而不是少交付），以及**图层拆分按次计费**——
 * 一次调用一个单位，哪怕厂商为这一次调用返回多个图层。后者断言的是账本与
 * 数据库里的张数，不只是返回值里的字段。
 *
 * <p>本类同时钉住那次结算留下的**运营证据**。图片路径过去只调用
 * {@code QuotaService.settle} 就停住：扣费是对的，而 {@code usage_log}、
 * {@code usage_stage}、{@code provider_cost_record} 与
 * {@code usage_economics_outbox} 各为 0 行——一次真实付费调用之后仍然如此，
 * 于是这次生成在「调用与额度」里不可见，供应商成本也从未进入经济性上报。
 * 现在那条路径与对话、视频写同一组证据，单位是**张**而不是 token：
 * {@code usage_dimension = 'IMAGE'}、{@code usage_units = billed_images}、
 * 三个 token 计数器为零，供应商成本按费率卡的每张成本乘以计费张数计算。
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=gateway",
        "spring.flyway.schemas=gateway",
        "spring.data.redis.repositories.enabled=false",
        "security.api-key.pepper=test-api-key-pepper",
        "security.internal.hmac-secret=test-internal-secret",
        "gateway.image.allow-mock=true"
})
class ImageGenerationBillingTest {

    private static final UUID USER_ID = UUID.fromString("51000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("51000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID = UUID.fromString("51000000-0000-4000-8000-000000000003");
    private static final UUID API_KEY_ID = UUID.fromString("51000000-0000-4000-8000-000000000004");
    private static final UUID PROVIDER_ID = UUID.fromString("51000000-0000-4000-8000-000000000005");
    private static final UUID CHANNEL_ID = UUID.fromString("51000000-0000-4000-8000-000000000006");
    private static final UUID IMAGE_MODEL_ID = UUID.fromString("51000000-0000-4000-8000-000000000007");

    private static final String ALIAS = "image-test";

    /** 输入图 URL：图层拆分必须有输入图才成立（没有可拆的对象）。 */
    private static final String INPUT_IMAGE = "https://cdn.example.test/source.png";

    /**
     * 一条日志告警，测试会断言它在图层拆分路径上**没有**出现：
     * 那次调用里的「厂商报的比请求的多」是图层，而不是多买的生成。
     */
    private static final String MORE_THAN_REQUESTED_WARNING =
            "image_vendor_returned_more_than_requested";

    /** 套餐授予 20 张。上限是张数，与视频套餐的秒数完全同形。 */
    private static final int PACKAGE_IMAGES = 20;

    /**
     * 测试别名在费率卡上的每张供应商成本（分/张）。
     *
     * <p>**刻意不是** {@code image-basic} 的 62：写成另一个数，证据里出现的
     * {@code 47} 才证明它是从费率卡读出来的，而不是某处写死的常量。
     * 该行与种子行的形状完全一致（2K 档、{@code retry_rate = 0}、
     * 89 分零售），因此这里验证的是机制，而不是数字本身。
     */
    private static final long PROVIDER_UNIT_COST = 47L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void seedPackageAndChannel() throws Exception {
        channelBaseUrl = "mock://image/succeed";
        // ListAppender 自己不提供 clear()，它持有的 list 才是可清的。
        warnings.list.clear();
        execute("""
                TRUNCATE gateway.image_generation_task, gateway.quota_ledger,
                         gateway.quota_reservation, gateway.package_instance_model_quota,
                         gateway.package_instance, gateway.api_key, gateway.channel_model,
                         gateway.channel, gateway.provider, gateway.image_alias_tier,
                         gateway.model_alias, gateway.model,
                         gateway.usage_stage, gateway.provider_cost_record, gateway.usage_log,
                         gateway.usage_economics_outbox
                         CASCADE
                """);

        // 一个生图别名、一条声明 IMAGE_GEN 能力的厂商渠道，以及一个
        // 按张计量、授予其中 20 张的套餐。
        execute("""
                INSERT INTO gateway.model(id, code, display_name, capabilities,
                                          context_window, max_output_tokens, enabled)
                VALUES ('%s', '%s', 'Image Test', '["TEXT","IMAGE","IMAGE_GEN"]',
                        8192, 4096, true)
                """.formatted(IMAGE_MODEL_ID, ALIAS));
        execute("""
                INSERT INTO gateway.model_alias(id, model_id, alias)
                VALUES (gen_random_uuid(), '%s', '%s')
                """.formatted(IMAGE_MODEL_ID, ALIAS));
        execute("""
                INSERT INTO gateway.image_alias_tier(model_code, resolution_tier)
                VALUES ('%s', '2K')
                """.formatted(ALIAS));
        execute("""
                INSERT INTO gateway.provider(id, code, name, provider_type, base_url, enabled)
                VALUES ('%s', 'mock-image', 'Mock Image', 'MOCK', 'mock://image', true)
                """.formatted(PROVIDER_ID));
        execute("""
                INSERT INTO gateway.channel(id, provider_id, code, name, environment, base_url,
                                            enabled, priority, weight, timeout_ms, last_health_status)
                VALUES ('%s', '%s', 'mock-image-primary', 'Mock Image Primary', 'PRODUCTION',
                        '%s', true, 100, 100, 60000, 'HEALTHY')
                """.formatted(CHANNEL_ID, PROVIDER_ID, channelBaseUrl));
        execute("""
                INSERT INTO gateway.channel_model(id, channel_id, model_id, upstream_model,
                                                  enabled, capabilities)
                VALUES (gen_random_uuid(), '%s', '%s', 'mock-seedream-1', true,
                        '["TEXT","IMAGE","IMAGE_GEN"]')
                """.formatted(CHANNEL_ID, IMAGE_MODEL_ID));

        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, package_kind,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, status, starts_at, activated_at, expires_at, usage_dimension)
                VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', '%s', 'identity-key',
                        gen_random_uuid(), 'IMAGE_TEST', 'Image Test', 'STANDARD',
                        8600, 'CNY',
                        '[{"modelCode":"image-test","modelTokenQuota":null,"capabilities":["IMAGE_GEN"]}]'::jsonb,
                        %d, 2, 20, 1000, 2, 30, 'ACTIVE',
                        now(), now(), now() + interval '30 days', 'IMAGE')
                """.formatted(PACKAGE_ID, TENANT_ID, USER_ID, PACKAGE_IMAGES));
        execute("""
                INSERT INTO gateway.package_instance_model_quota(
                    id, package_instance_id, model_code, usage_dimension, token_quota,
                    seconds_quota, reserved_tokens, used_tokens)
                VALUES (gen_random_uuid(), '%s', '%s', 'IMAGE', NULL,
                        %d, 0, 0)
                """.formatted(PACKAGE_ID, ALIAS, PACKAGE_IMAGES));
        execute("""
                INSERT INTO gateway.api_key(id, tenant_id, user_id, package_instance_id, name,
                                            key_prefix, key_hash, allowed_models, status,
                                            usage_dimension)
                VALUES ('%s', '%s', '%s', '%s', 'image-key', 'vk_test', 'hash-image-key',
                        '["image-test"]'::jsonb, 'ACTIVE', 'IMAGE')
                """.formatted(API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID));

        // 该别名自己的图片费率卡行。形状照搬 gateway.image_effective_cost 的种子行
        // （V81/V82：2K 档、retry_rate 0、零售 89），只有每张成本不同——
        // 证据里的成本必须来自这一行，而不是任何写死的数字。
        // ON CONFLICT 是因为费率卡按 (别名, 维度, 档位, 版本) 唯一，而它不参与
        // 上面的 TRUNCATE：费率卡是历史，不该被测试清空。
        execute("""
                INSERT INTO gateway.model_usage_price(
                    id, model_code, usage_dimension, resolution_tier,
                    minimum_units, minimum_charge, unit_price,
                    provider_unit_cost, retry_rate, target_margin_ratio, min_absolute_margin,
                    price_version, effective_from, official_source)
                VALUES (gen_random_uuid(), '%s', 'IMAGE', '2K', 1, 89, 89, %d, 0, 0.30000, 25,
                        'image-test-v1', now(),
                        'test:per-image basis, deliberately not the seeded 62')
                ON CONFLICT (model_code, usage_dimension, resolution_tier, price_version)
                    DO NOTHING
                """.formatted(ALIAS, PROVIDER_UNIT_COST));
    }

    /**
     * 该渠道被配置为哪种 mock 结果。每次路由解析时都会重新读取，
     * 因此测试可以在调用服务之前改动它。
     */
    static String channelBaseUrl = "mock://image/succeed";

    /**
     * {@link ImageGenerationService} 的 WARN 日志，用来断言某条告警
     * **没有**出现。「没有多收钱」与「没有把这件事说成异常」是两件事：
     * 前者由账本断言，后者只能由日志断言。
     */
    private static final ListAppender<ILoggingEvent> warnings = new ListAppender<>();

    static {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ImageGenerationService.class);
        warnings.setContext(logger.getLoggerContext());
        warnings.start();
        logger.addAppender(warnings);
    }

    private static List<String> warningsContaining(String fragment) {
        List<String> matching = new ArrayList<>();
        for (ILoggingEvent event : warnings.list) {
            if (event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains(fragment)) {
                matching.add(event.getFormattedMessage());
            }
        }
        return matching;
    }

    @Autowired
    ImageGenerationService service;

    /** 恢复扫描：它读的是 {@code openTasks}，因此锚点会不会被它捡走必须在这条路径上验。 */
    @Autowired
    ImageTaskRecoveryJob recoveryJob;

    /** 补偿路径与证据写入器直接受测：崩溃恢复正是从这两者重新推导出那次结算。 */
    @Autowired
    ImageTaskRepository taskRepository;

    @Autowired
    ImageEconomicsEvidenceWriter economicsEvidence;

    /**
     * 一张的订单交付一张、收一个单位——普通路径的基线。
     *
     * <p>它与下面那条图层拆分的断言在账本上结果相同（都是一个单位），但理由不同：
     * 这里的一个单位是**厂商交付了一张**，那里的一次调用是**厂商被调用了一次**。
     * 两条都留下，才能在下一次改动时分辨出究竟是哪条规则被改坏了。
     */
    @Test
    void aSuccessfulGenerationBillsTheOneImageItReturnedExactlyOnce() throws Exception {
        ImageGenerationService.GenerationView view = submit("req-succeed", 1);

        assertThat(view.data()).hasSize(1);
        assertThat(view.billedImages()).isEqualTo(1);
        assertThat(view.errorCategory()).isNull();
        // 预留张数已转为已用张数，且没有任何残留占用。
        assertThat(usedImages()).isEqualTo(1);
        assertThat(reservedImages()).isZero();
        assertThat(ledgerSettleCount()).isEqualTo(1);
        assertThat(statusOf("req-succeed")).isEqualTo("SUCCEEDED");
        assertThat(billedImagesOf("req-succeed")).isEqualTo(1);
        assertThat(resultImagesOf("req-succeed")).isEqualTo(1);
    }

    /**
     * 图层拆分按**次**结算：一次调用恰好一个单位，哪怕厂商为这次调用返回了三张图层。
     *
     * <p>断言落在账本与数据库里的张数上，而不只是返回值里的字段：真正会多收客户钱的
     * 是那次结算，因此必须证明配额只被扣了一个单位、生成记录里记的也是一个单位。
     * 同时断言那条「厂商报的比请求的多」的告警**没有**出现——对图层调用而言它会把
     * 一次诚实收费说成异常，因为多出来的不是多买的生成，而是同一次调用的图层。
     */
    @Test
    void aLayerDecompositionCallSettlesExactlyOneUnitHoweverManyLayersTheVendorReturns()
            throws Exception {
        // mock://image/succeed-layers 无论请求几张都返回三张：这正是厂商对图层拆分的
        // 合法行为（它数的是图层），也是修复前会按三张收费的那种响应。
        setChannel("mock://image/succeed-layers");

        ImageGenerationService.GenerationView view = submitLayerDecomposition("req-layers-1k");

        assertThat(view.data()).hasSize(3);
        assertThat(view.billedImages()).isEqualTo(1);
        assertThat(view.errorCategory()).isNull();

        // 账本：只花掉一个单位，没有残留占用，恰好一次结算。
        assertThat(usedImages()).isEqualTo(1);
        assertThat(reservedImages()).isZero();
        assertThat(ledgerSettleCount()).isEqualTo(1);

        // 结算本身：收了一个单位，而这次用量报的也是一个单位——多出来的两张图层
        // 没有进入计费数字。
        assertThat(settledTokens("req-layers-1k")).isEqualTo(1);
        assertThat(reportedTokens("req-layers-1k")).isEqualTo(1);

        // 生成记录：请求一张、计费一个单位，但确实交付了三张图层。
        assertThat(requestedImagesOf("req-layers-1k")).isEqualTo(1);
        assertThat(billedImagesOf("req-layers-1k")).isEqualTo(1);
        assertThat(resultImagesOf("req-layers-1k")).isEqualTo(3);
        assertThat(statusOf("req-layers-1k")).isEqualTo("SUCCEEDED");

        // 图层调用里的「厂商报的比请求的多」不是告警：那是同一次调用的图层。
        assertThat(warningsContaining(MORE_THAN_REQUESTED_WARNING))
                .as("a layer call must not be reported as an over-delivery")
                .isEmpty();
    }

    /**
     * 同一条规则必须对**重放**成立，而不只是对首次结算成立。
     *
     * <p>这是「按次计费」最容易漏掉的一半：重放不会重新调用厂商，但
     * {@code compensateTerminalIfNeeded} 会从这条记录重新推导扣费，因此图层标记
     * 必须随记录存活（网关迁移 V82）。否则一次超时后的重试会把一个单位变成三个。
     */
    @Test
    void replayingALayerDecompositionCallNeitherBuysNorChargesASecondTime() throws Exception {
        setChannel("mock://image/succeed-layers");

        ImageGenerationService.GenerationView first = submitLayerDecomposition("req-layers-replay");
        ImageGenerationService.GenerationView second = submitLayerDecomposition("req-layers-replay");

        assertThat(second.data()).isEqualTo(first.data());
        assertThat(second.billedImages()).isEqualTo(1);
        assertThat(count("gateway.image_generation_task")).isEqualTo(1);
        assertThat(usedImages()).isEqualTo(1);
        assertThat(settledTokens("req-layers-replay")).isEqualTo(1);
        assertThat(ledgerSettleCount()).isEqualTo(1);
    }

    /**
     * 一张的订单交付一张：普通路径仍然按实际交付结算，而不是按图层规则被压成
     * 「无论如何都只收一个单位」。
     *
     * <p>{@code mock://image/succeed} 返回所请求的张数，因此这里的 1 是**交付出来的**
     * 一张，与图层路径的 1（一次调用）在账本上一致，但理由不同——后者由上面的测试
     * 与 {@code billed_images} 一起钉住。
     */
    @Test
    void anOrdinaryCallIsStillSettledByWhatTheVendorDelivered() throws Exception {
        // 预留按最大值发生，因此一次只交付一张的调用不会留下占用，
        // 客户只为拿到的那张付费。
        setChannel("mock://image/succeed");

        ImageGenerationService.GenerationView view = submit("req-one", 1);

        assertThat(view.data()).hasSize(1);
        assertThat(view.billedImages()).isEqualTo(1);
        assertThat(usedImages()).isEqualTo(1);
        assertThat(reservedImages()).isZero();
        assertThat(ledgerSettleCount()).isEqualTo(1);
        assertThat(settledTokens("req-one")).isEqualTo(1);
        assertThat(requestedImagesOf("req-one")).isEqualTo(1);
        assertThat(billedImagesOf("req-one")).isEqualTo(1);
    }

    /**
     * 图生图按新的 62/89 基准定价，并且旧的那一行被正确退役。
     *
     * <p>问的是**代码真正读的那一行**：{@code gateway.image_effective_cost}
     * 正是 {@code ProviderPriceQueryRepository} 为
     * {@code /internal/prices/effective-unit-cost} 所查的视图，也就是平台的发布毛利
     * 守卫给图片版本定价时拿到的那个数。它只暴露 {@code effective_to IS NULL} 的行，
     * 因此这里同时证明了「新基准生效」与「旧基准没有被读成当前行」。
     *
     * <p>62 = 最坏情况的输出图 60 分 + 输入图 2 分；89 是同时越过绝对下限 25 与
     * 比率下限 30%（ceil(62 / 0.7) = 89）的最小价格，因此那 2 分输入成本由客户
     * 而不是平台承担。
     */
    @Test
    void theImageAliasIsPricedOnTheWorstCaseBasisThatIncludesTheInputImage()
            throws Exception {
        assertThat(queryText("""
                SELECT provider_unit_cost || ':' || effective_unit_cost || ':' || unit_price
                       || ':' || price_version
                FROM gateway.image_effective_cost
                WHERE model_code = 'image-basic' AND resolution_tier = '2K'
                """))
                .isEqualTo("62:62:89:image-v2");

        // 只有 image-v2 是当前行：image-v1 被 effective_to 关闭，而不是被改写。
        assertThat(queryText("""
                SELECT string_agg(price_version, ',' ORDER BY price_version)
                FROM gateway.model_usage_price
                WHERE model_code = 'image-basic' AND usage_dimension = 'IMAGE'
                  AND resolution_tier = '2K' AND effective_to IS NULL
                """)).isEqualTo("image-v2");
        assertThat(queryLong("""
                SELECT count(*) FROM gateway.model_usage_price
                WHERE model_code = 'image-basic' AND usage_dimension = 'IMAGE'
                  AND resolution_tier = '2K' AND price_version = 'image-v1'
                  AND effective_to IS NOT NULL
                """))
                .as("image-v1 must stay on the card, closed, so old charges stay explainable")
                .isEqualTo(1);

        // 两条下限各自成立：价格 89 是同时满足它们的最小整数。
        assertThat(queryText("""
                SELECT (unit_price * minimum_units - provider_unit_cost * minimum_units)
                       || ':' || (unit_price >= ceil(provider_unit_cost::numeric
                                                     / (1 - target_margin_ratio)))
                FROM gateway.model_usage_price
                WHERE model_code = 'image-basic' AND usage_dimension = 'IMAGE'
                  AND resolution_tier = '2K' AND effective_to IS NULL
                """))
                .as("27 分 clears the 25 分 absolute floor, and 89 clears ceil(62 / 0.7), "
                        + "so the 2 分 input image is paid by the customer")
                .isEqualTo("27:true");
    }

    @Test
    void replayingAnIdempotencyKeyReturnsTheSameImagesWithoutBuyingASecondGeneration()
            throws Exception {
        ImageGenerationService.GenerationView first = submit("req-replay", 1);

        // 客户端在超时后重试：它必须拿回已经付过费的那次生成，
        // 而不是再买一次。
        ImageGenerationService.GenerationView second = submit("req-replay", 1);

        assertThat(second.requestId()).isEqualTo(first.requestId());
        assertThat(second.created()).isEqualTo(first.created());
        assertThat(second.data()).isEqualTo(first.data());
        assertThat(count("gateway.image_generation_task")).isEqualTo(1);
        assertThat(usedImages()).isEqualTo(1);
        assertThat(ledgerSettleCount()).isEqualTo(1);
    }

    @Test
    void aDefinitivelyRejectedGenerationReleasesTheReservationAndChargesNothing()
            throws Exception {
        // mock://image/fail 以 4xx 拒绝：厂商没有生成任何图片，
        // 因此把客户的张数扣住整整一个 TTL 就是在撒谎。
        setChannel("mock://image/fail");

        assertThatThrownBy(() -> submit("req-rejected", 1))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error ->
                        assertThat(error.status()).isEqualTo(400));

        assertThat(usedImages()).isZero();
        assertThat(reservedImages()).isZero();
        assertThat(statusOf("req-rejected")).isEqualTo("CANCELLED");
        assertThat(errorCategoryOf("req-rejected")).isEqualTo("upstream_rejected");
        assertThat(count("gateway.quota_ledger WHERE entry_type = 'SETTLE'")).isZero();

        // 同一个键在其上重放不会再买一次生成，也不会得到一次「成功」。
        assertThatThrownBy(() -> submit("req-rejected", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).isEqualTo("image_generation_previously_failed");
                });
        assertThat(count("gateway.image_generation_task")).isEqualTo(1);
    }

    @Test
    void anUnknownOutcomeKeepsTheReservationAndRefusesToBuyASecondGeneration() throws Exception {
        // mock://image/unavailable 报告 5xx：厂商可能已经生成了一张
        // 只是响应没有到达的图片，因此释放预留会让重试买下第二次生成。
        setChannel("mock://image/unavailable");

        assertThatThrownBy(() -> submit("req-unknown", 1))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error ->
                        assertThat(error.status()).isEqualTo(502));

        assertThat(usedImages()).isZero();
        assertThat(reservedImages()).isEqualTo(1);
        assertThat(statusOf("req-unknown")).isEqualTo("SUBMITTED");

        assertThatThrownBy(() -> submit("req-unknown", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).isEqualTo("image_generation_outcome_unknown");
                });
    }

    @Test
    void aProtocolFailureKeepsTheReservationBecauseNothingProvesItWasNotAccepted()
            throws Exception {
        // 2xx 却一张图都没有：没有证据表明厂商没有接单，因此按上游故障处理并保住预留。
        setChannel("mock://image/empty");

        assertThatThrownBy(() -> submit("req-empty", 1))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error ->
                        assertThat(error.status()).isEqualTo(502));

        assertThat(reservedImages()).isEqualTo(1);
        assertThat(usedImages()).isZero();
    }

    @Test
    void anotherAccountsRequestIdCannotBeReplayedIntoTheirGeneration() throws Exception {
        // 幂等键由客户端选定，因此与另一个账号的生成相撞的键必须 fail-closed：
        // 既不返回那批图片，也不在该 id 下再买一次生成。
        submit("req-taken", 1);
        ApiKeyService.ApiKeyContext stranger = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, UUID.randomUUID(), PACKAGE_ID, Set.of(ALIAS), true);

        assertThatThrownBy(() -> service.generate(new ImageGenerationService.SubmitCommand(
                        "req-taken", stranger, ALIAS, "another prompt", null, "2K", 1, "url",
                        true, false))
                .block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).isEqualTo("image_request_id_conflict");
                });

        assertThat(count("gateway.image_generation_task")).isEqualTo(1);
        assertThat(usedImages()).isEqualTo(1);
    }

    @Test
    void reservesImagesAgainstThePackageSoAnOversizedRequestCannotStart() throws Exception {
        // 该套餐授予 20 张。这里每次只买一张（走单图路径），二十次之后余额用尽，
        // 因此第二十一次必须作为配额问题失败，而不是被送到厂商那里。
        for (int index = 0; index < PACKAGE_IMAGES; index++) {
            submit("req-bulk-" + index, ImageGenerationService.MAX_IMAGES);
        }
        assertThat(usedImages()).isEqualTo(PACKAGE_IMAGES);
        assertThat(reservedImages()).isZero();

        assertThatThrownBy(() -> submit("req-bulk-overflow", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("quota_exhausted");
                });

        // 超出**一次请求**上限的张数根本到不了配额层：服务层自己的边界就拒绝了它。
        // 注意这里的比较对象是 MAX_FANOUT（一次请求买几张 = 4），而不是 MAX_IMAGES
        //（单次厂商调用买几张 = 1）——V114 起两者不再相等，用错那个常量会让这条
        // 断言在 n=2 时误报通过。
        assertThatThrownBy(() -> submit(
                        "req-over-api-limit", ImageGenerationService.MAX_FANOUT + 1))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("invalid_image_request");
                });

        assertThat(count("gateway.image_generation_task")).isEqualTo(PACKAGE_IMAGES);
    }

    @Test
    void anAliasWithNoRegisteredSizeTierIsRefusedBeforeAnythingIsBought() throws Exception {
        // fail-closed：没有登记档位的别名既没有价格也没有成本基础，
        // 因此放行它就等于卖出无人能定价的张数。
        execute("DELETE FROM gateway.image_alias_tier WHERE model_code = '" + ALIAS + "'");

        assertThatThrownBy(() -> submit("req-unregistered", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("image_model_size_unregistered");
                });

        assertThat(reservedImages()).isZero();
        assertThat(count("gateway.image_generation_task")).isZero();
    }

    @Test
    void aSizeLargerThanTheAliasSellsIsRefusedAndASmallerOneIsServed() throws Exception {
        // 该别名按 2K 定价。渲染 4K 就是让客户按 2K 的价格买 4K 的算力，
        // 因此 4K 被拒绝；1K 只会更便宜，因此它被接受。
        assertThatThrownBy(() -> submitWithSize("req-4k", "4K"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("image_size_not_sold_by_model");
                });
        assertThat(reservedImages()).isZero();

        ImageGenerationService.GenerationView view = submitWithSize("req-1k", "1024x1024");
        assertThat(view.data()).hasSize(1);
        assertThat(usedImages()).isEqualTo(1);
        assertThat(sizeOf("req-1k")).isEqualTo("1024x1024");
        assertThat(tierOf("req-1k")).isEqualTo("1K");

        // 1.5K 是 V115 补上的中间档：厂商文档里一直有它，此前网关词表里没有，
        // 因此客户端能读到、提交必被拒。现在它与 1K 一样是"比在售档位便宜"的
        // 合法档位，且**自己有一行费率卡**（62/89，与 2K 同价的最坏情况基准）。
        ImageGenerationService.GenerationView midTier = submitWithSize("req-15k", "1.5K");
        assertThat(midTier.data()).hasSize(1);
        assertThat(tierOf("req-15k")).isEqualTo("1.5K");
        assertThat(sizeOf("req-15k")).isEqualTo("1.5K");

        // 像素尺寸按最长边归约：1536 是这一档的单边上限，1537 落到下一档。
        submitWithSize("req-15k-pixels", "1536x864");
        assertThat(tierOf("req-15k-pixels")).isEqualTo("1.5K");
        submitWithSize("req-1537", "1537x1537");
        assertThat(tierOf("req-1537")).isEqualTo("2K");
    }

    @Test
    void aTokenDenominatedPackageCannotBeDrivenByImageGeneration() throws Exception {
        // IMAGE 之外的套餐没有张数余额可扣。把它按 token 计费会用张数
        // 消耗 token 余额，因此这里 fail-closed。
        execute("UPDATE gateway.package_instance SET usage_dimension = 'TOKEN', "
                + "total_token_quota = 5000000 WHERE id = '" + PACKAGE_ID + "'");

        assertThatThrownBy(() -> submit("req-token-package", 1))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(usedImages()).isZero();
        assertThat(reservedImages()).isZero();
    }

    // ---- 经济性证据 ------------------------------------------------------------

    /**
     * 一次成功结算的生成留下**完整**的四件套证据，单位是张而不是 token。
     *
     * <p>本测试钉住的正是那个缺陷：在此之前，这几张表在一次真实付费调用之后
     * 各为 0 行——扣费正确，而这次生成对运营完全不存在，
     * 供应商成本也从未进入经济性上报。
     */
    @Test
    void aSettledGenerationWritesTheDimensionedEconomicsEvidence() throws Exception {
        ImageGenerationService.GenerationView view = submit("req-evidence", 1);

        assertThat(view.billedImages()).isEqualTo(1);

        // 四张表各恰好一行。
        assertThat(count("gateway.usage_log")).isEqualTo(1);
        assertThat(count("gateway.usage_stage")).isEqualTo(1);
        assertThat(count("gateway.provider_cost_record")).isEqualTo(1);
        assertThat(count("gateway.usage_economics_outbox")).isEqualTo(1);

        // 用量行：维度是 IMAGE、数量是张、token 计数器一律为零。
        assertThat(usageText("req-evidence", "usage_dimension")).isEqualTo("IMAGE");
        assertThat(usageLong("req-evidence", "usage_units")).isEqualTo(1);
        assertThat(usageLong("req-evidence", "input_tokens")).isZero();
        assertThat(usageLong("req-evidence", "output_tokens")).isZero();
        assertThat(usageLong("req-evidence", "total_tokens")).isZero();
        // 归属与结果：模型、渠道、结果、状态码，以及那一次预留的链接。
        assertThat(usageText("req-evidence", "model_code")).isEqualTo(ALIAS);
        assertThat(usageText("req-evidence", "channel_id")).isEqualTo(CHANNEL_ID.toString());
        assertThat(usageText("req-evidence", "outcome")).isEqualTo("SUCCEEDED");
        assertThat(usageLong("req-evidence", "http_status")).isEqualTo(200);
        assertThat(usageText("req-evidence", "reservation_id"))
                .as("这一行用量必须挂在它结算的那一次预留上")
                .isEqualTo(queryText("SELECT reservation_id FROM gateway.image_generation_task "
                        + "WHERE request_id = 'req-evidence'"));
        assertThat(usageExpression("req-evidence", "usage.economics_event_pending::text"))
                .as("事件与用量行在同一事务里写入，因此没有待投递的悬空标记")
                .isEqualTo("false");

        // stage：图片阶段类型、同一维度、同一数量，成本落在 output 桶里。
        assertThat(stageText("req-evidence", "stage_type")).isEqualTo("IMAGE_GENERATION");
        assertThat(stageText("req-evidence", "usage_dimension")).isEqualTo("IMAGE");
        assertThat(stageLong("req-evidence", "usage_units")).isEqualTo(1);
        assertThat(stageLong("req-evidence", "total_tokens")).isZero();
        assertThat(stageText("req-evidence", "outcome")).isEqualTo("SUCCEEDED");
        assertThat(stageLong("req-evidence", "provider_cost_amount"))
                .isEqualTo(PROVIDER_UNIT_COST);
        assertThat(stageText("req-evidence", "provider_cost_currency")).isEqualTo("CNY");
        assertThat(stageExpression("req-evidence", "stage.provider_billable::text"))
                .isEqualTo("true");
        assertThat(stageExpression("req-evidence", "stage.customer_billable::text"))
                .isEqualTo("true");
        // 定价依据：读了哪一行费率卡，是审计成本与零售价的唯一入口。
        assertThat(stageExpression("req-evidence", "stage.raw_usage ->> 'priceVersion'"))
                .isEqualTo("image-test-v1");
        assertThat(stageExpression("req-evidence", "stage.raw_usage ->> 'pricedTier'"))
                .isEqualTo("2K");
        assertThat(stageExpression("req-evidence", "stage.raw_usage ->> 'billedImages'"))
                .isEqualTo("1");
        assertThat(stageExpression("req-evidence", "stage.raw_usage ->> 'providerCostAmount'"))
                .isEqualTo(String.valueOf(PROVIDER_UNIT_COST));

        // 供应商成本记录：金额 = 费率卡每张成本 × 计费张数。
        assertThat(providerCostAmount("req-evidence")).isEqualTo(PROVIDER_UNIT_COST);
        assertThat(queryText("""
                SELECT record.currency FROM gateway.provider_cost_record record
                JOIN gateway.usage_log usage ON usage.id = record.usage_log_id
                WHERE usage.request_id = 'req-evidence'
                """)).isEqualTo("CNY");
        assertThat(queryText("""
                SELECT record.model_code FROM gateway.provider_cost_record record
                JOIN gateway.usage_log usage ON usage.id = record.usage_log_id
                WHERE usage.request_id = 'req-evidence'
                """)).isEqualTo(ALIAS);

        // 经济性事件：带维度、带张数、带成本，平台据此记一条 operating_fact
        // 并按已结算张数分摊套餐收入。
        assertThat(outboxText("version")).isEqualTo("USAGE_ECONOMICS_V3");
        assertThat(outboxText("usage_dimension")).isEqualTo("IMAGE");
        assertThat(outboxText("usage_units")).isEqualTo("1");
        assertThat(outboxText("package_settled_units")).isEqualTo("1");
        assertThat(outboxText("provider_total_cost_amount"))
                .isEqualTo(String.valueOf(PROVIDER_UNIT_COST));
        assertThat(queryText("""
                SELECT (outbox.event_id = usage.id)::text
                FROM gateway.usage_economics_outbox outbox
                JOIN gateway.usage_log usage ON usage.request_id = 'req-evidence'
                """)).as("事件的身份就是那条用量行，因此重放不可能再投递一条")
                .isEqualTo("true");
    }

    /**
     * 成本来自费率卡，而不是任何写死的常量。
     *
     * <p>本类给该别名播种的每张成本是 47，而种子别名 {@code image-basic} 是 62：
     * 证据里出现的 47 只可能来自 {@code gateway.image_effective_cost}
     * （也就是发布毛利守卫读数的那同一个视图）。
     */
    @Test
    void theRecordedProviderCostComesFromTheRateCardForTheBilledTier() throws Exception {
        // 费率卡上真实的 62 仍然在那里，而这条别名读的是自己那一行。
        assertThat(queryText("""
                SELECT provider_unit_cost || ':' || effective_unit_cost || ':' || price_version
                FROM gateway.image_effective_cost
                WHERE model_code = 'image-basic' AND resolution_tier = '2K'
                """)).isEqualTo("62:62:image-v2");
        assertThat(queryText("""
                SELECT effective_unit_cost FROM gateway.image_effective_cost
                WHERE model_code = 'image-test' AND resolution_tier = '2K'
                """)).isEqualTo(String.valueOf(PROVIDER_UNIT_COST));

        submit("req-evidence-rate", 1);

        assertThat(providerCostAmount("req-evidence-rate")).isEqualTo(PROVIDER_UNIT_COST);
        assertThat(stageLong("req-evidence-rate", "provider_cost_amount"))
                .isEqualTo(PROVIDER_UNIT_COST);
    }

    /**
     * 重放同一次结算，以及崩溃恢复路径重新推导出的那次结算，
     * 都不得写出第二套证据。
     *
     * <p>三条路径都试：HTTP 层重放同一个幂等键、补偿路径对同一条终态记录
     * 重跑一次结算（{@code QuotaService.settle} 对已结算预留返回既有结算），
     * 以及直接对同一个 task 行再写一次证据。四张表都必须停在 1 行——
     * 幂等性靠的是 {@code usage_log.request_id} 唯一、
     * {@code provider_cost_record.usage_log_id} 唯一、
     * {@code usage_stage (usage_log_id, stage_index)} 唯一与 outbox 的
     * {@code event_id}/{@code event_fingerprint} 唯一（网关 V83 把这四条写成断言），
     * 而不是靠进程里的一次性标志。
     */
    @Test
    void replayingASettledGenerationDoesNotDuplicateAnyOfTheEvidence() throws Exception {
        submit("req-evidence-replay", 1);
        assertThat(count("gateway.usage_log")).isEqualTo(1);

        // 1) HTTP 层重放：拿回同一批图片，不再买、不再结算。
        submit("req-evidence-replay", 1);
        // 2) 崩溃恢复：终态行还在，补偿路径从这条记录重新推导扣费与证据。
        ImageTaskRepository.TaskRow terminal =
                taskRepository.findByRequestId("req-evidence-replay").block();
        assertThat(terminal).isNotNull();
        service.compensateTerminal(terminal).block();
        // 3) 最直接的一层：同一个 task 行、同一个张数，再写一次证据。
        economicsEvidence.write(terminal, "SUCCEEDED", 1).block();

        assertThat(count("gateway.usage_log")).isEqualTo(1);
        assertThat(count("gateway.usage_stage")).isEqualTo(1);
        assertThat(count("gateway.provider_cost_record")).isEqualTo(1);
        assertThat(count("gateway.usage_economics_outbox")).isEqualTo(1);
        // 钱也仍然只被收了一次。
        assertThat(ledgerSettleCount()).isEqualTo(1);
        assertThat(usedImages()).isEqualTo(1);
    }

    /**
     * 图层拆分那条路径的成本必须按**一次调用一张**记，而不是按返回的图层数。
     *
     * <p>账本一侧已经由上面的测试钉住（结算一个单位）；这里钉的是另一半：
     * 供应商成本也只有一份。厂商为这一次调用返回三张图层，而费率卡按调用计——
     * 记三份成本会凭空造出两倍的支出，让平台的利润数字看起来比实际更差。
     */
    @Test
    void aLayerDecompositionCallRecordsTheCostOfOneUnitNotOfItsLayers() throws Exception {
        setChannel("mock://image/succeed-layers");

        ImageGenerationService.GenerationView view = submitLayerDecomposition("req-evidence-layers");

        assertThat(view.data()).hasSize(3);
        assertThat(view.billedImages()).isEqualTo(1);
        assertThat(resultImagesOf("req-evidence-layers")).isEqualTo(3);

        assertThat(count("gateway.usage_log")).isEqualTo(1);
        assertThat(usageLong("req-evidence-layers", "usage_units")).isEqualTo(1);
        assertThat(stageLong("req-evidence-layers", "usage_units")).isEqualTo(1);
        assertThat(stageLong("req-evidence-layers", "provider_cost_amount"))
                .as("三张图层仍然只是一次付费调用，因此只有一份成本")
                .isEqualTo(PROVIDER_UNIT_COST);
        assertThat(providerCostAmount("req-evidence-layers")).isEqualTo(PROVIDER_UNIT_COST);
        assertThat(outboxText("usage_units")).isEqualTo("1");
        // 依据里必须留下「这是一次图层调用」，否则三张图层与一份成本再也对不上。
        assertThat(stageExpression("req-evidence-layers",
                "stage.raw_usage ->> 'layerDecomposition'")).isEqualTo("true");
        assertThat(stageExpression("req-evidence-layers",
                "stage.raw_usage ->> 'requestedImages'")).isEqualTo("1");
    }

    /**
     * 更小的尺寸按该别名**已售**档位的费率卡行计成本，而不是变成「没有价格」。
     *
     * <p>该别名按 2K 定价与出售，1K 请求是允许的（只会更便宜）。费率卡上没有
     * 1K 这一行，因此证据必须退回已售档位那一行——这是平台实际承担的最坏情况——
     * 而不是写一条 price_missing 的成本记录或干脆不写成本。
     */
    @Test
    void aSmallerRenderIsPricedOnTheTierTheAliasSells() throws Exception {
        ImageGenerationService.GenerationView view = submitWithSize("req-evidence-1k", "1024x1024");

        assertThat(view.billedImages()).isEqualTo(1);
        assertThat(tierOf("req-evidence-1k")).isEqualTo("1K");
        assertThat(count("gateway.provider_cost_record")).isEqualTo(1);
        assertThat(providerCostAmount("req-evidence-1k")).isEqualTo(PROVIDER_UNIT_COST);
        assertThat(stageExpression("req-evidence-1k", "stage.raw_usage ->> 'pricedTier'"))
                .isEqualTo("2K");
        assertThat(stageExpression("req-evidence-1k",
                "stage.raw_usage ->> 'requestedResolutionTier'")).isEqualTo("1K");
        assertThat(stageExpression("req-evidence-1k", "stage.raw_usage ->> 'priced'"))
                .isEqualTo("true");
    }

    /**
     * 一次被厂商明确拒绝的生成留下**真实**的终态：一条零用量的用量行，
     * 且没有任何被杜撰出来的成本。
     *
     * <p>图片调用失败不产出图片、厂商也不收费（V81 把这一点记成了
     * {@code retry_rate = 0} 的理由），因此零成本是事实而不是省略。反过来说，
     * 给它记一笔金额——或者把 {@code provider_billable} 写成 true 再留一个空的
     * 成本——都会把一次没人付钱的调用说成有支出。
     */
    @Test
    void aRejectedGenerationLeavesATruthfulZeroUsageRowAndInventsNoCost() throws Exception {
        setChannel("mock://image/fail");

        assertThatThrownBy(() -> submit("req-evidence-failed", 1))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error ->
                        assertThat(error.status()).isEqualTo(400));

        // 终态与配额结果没有改变：预留被释放，一分钱都没扣。
        assertThat(usedImages()).isZero();
        assertThat(reservedImages()).isZero();
        assertThat(statusOf("req-evidence-failed")).isEqualTo("CANCELLED");
        assertThat(count("gateway.quota_ledger WHERE entry_type = 'SETTLE'")).isZero();

        // 但这次调用不再对运营不可见：一条真实的、零用量的用量行。
        assertThat(count("gateway.usage_log")).isEqualTo(1);
        assertThat(usageText("req-evidence-failed", "usage_dimension")).isEqualTo("IMAGE");
        assertThat(usageLong("req-evidence-failed", "usage_units")).isZero();
        assertThat(usageLong("req-evidence-failed", "total_tokens")).isZero();
        assertThat(usageText("req-evidence-failed", "outcome")).isEqualTo("CANCELLED");
        assertThat(usageText("req-evidence-failed", "error_category"))
                .isEqualTo("upstream_rejected");
        assertThat(usageLong("req-evidence-failed", "http_status"))
                .as("与对话路径同一写法：成功 200，其余 502")
                .isEqualTo(502);

        // stage 存在且真实：零张、没有任何一方被计费、所有成本列为空。
        assertThat(stageText("req-evidence-failed", "stage_type")).isEqualTo("IMAGE_GENERATION");
        assertThat(stageLong("req-evidence-failed", "usage_units")).isZero();
        assertThat(stageText("req-evidence-failed", "outcome")).isEqualTo("CANCELLED");
        assertThat(stageExpression("req-evidence-failed", "stage.customer_billable::text"))
                .isEqualTo("false");
        assertThat(stageExpression("req-evidence-failed", "stage.provider_billable::text"))
                .isEqualTo("false");
        assertThat(stageExpression("req-evidence-failed",
                "(stage.provider_cost_amount IS NULL)::text")).isEqualTo("true");
        assertThat(stageExpression("req-evidence-failed",
                "(stage.provider_cost_currency IS NULL)::text")).isEqualTo("true");
        assertThat(stageExpression("req-evidence-failed", "stage.price_estimated::text"))
                .isEqualTo("false");

        // 没有成本记录，也没有任何金额：零张就是零成本。
        assertThat(count("gateway.provider_cost_record")).isZero();
        assertThat(outboxText("usage_dimension")).isEqualTo("IMAGE");
        assertThat(outboxText("usage_units")).isEqualTo("0");
        assertThat(outboxText("package_settled_units")).isEqualTo("0");
        assertThat(outboxText("provider_total_cost_amount")).isNull();
    }

    /**
     * 结果未知的生成**没有**终态，因此也没有任何证据：预留仍然被持有，
     * 那张图是否已经产出无从证明。写一条「失败」的用量行就等于声称
     * 厂商没有接单，而网关并不知道这件事。
     */
    @Test
    void anUnknownOutcomeWritesNoEvidenceBecauseNothingTerminated() throws Exception {
        setChannel("mock://image/unavailable");

        assertThatThrownBy(() -> submit("req-evidence-unknown", 1))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error ->
                        assertThat(error.status()).isEqualTo(502));

        assertThat(statusOf("req-evidence-unknown")).isEqualTo("SUBMITTED");
        assertThat(reservedImages()).isEqualTo(1);
        assertThat(count("gateway.usage_log")).isZero();
        assertThat(count("gateway.usage_stage")).isZero();
        assertThat(count("gateway.provider_cost_record")).isZero();
        assertThat(count("gateway.usage_economics_outbox")).isZero();
    }

    /**
     * 崩溃恰好落在「结算已提交、证据尚未落库」之间时，重放会把缺的证据补上，
     * 而且只补一次。
     *
     * <p>这条窗口是补偿路径覆盖不到的那一段：结算提交之后预留已是 SETTLED，
     * {@code compensateTerminalIfNeeded} 便不再看它，因此证据本来会永远缺失——
     * 生图成本又一次悄悄消失，而这次是崩溃造成的。重放是唯一还会重新看到这条
     * 记录的地方，所以补写发生在那里，靠的仍然是那四个唯一键。
     */
    @Test
    void aReplayHealsEvidenceThatACrashLeftMissing() throws Exception {
        submit("req-evidence-heal", 1);

        // 模拟那段窗口：钱已经扣了、生成记录已终态，而四张证据表一行都没有。
        execute("DELETE FROM gateway.usage_economics_outbox");
        execute("DELETE FROM gateway.provider_cost_record");
        execute("DELETE FROM gateway.usage_stage");
        execute("DELETE FROM gateway.usage_log");
        assertThat(count("gateway.usage_log")).isZero();
        assertThat(count("gateway.provider_cost_record")).isZero();
        assertThat(usedImages()).as("扣费本身已经提交").isEqualTo(1);
        assertThat(statusOf("req-evidence-heal")).isEqualTo("SUCCEEDED");

        submit("req-evidence-heal", 1);

        assertThat(count("gateway.usage_log")).isEqualTo(1);
        assertThat(count("gateway.usage_stage")).isEqualTo(1);
        assertThat(count("gateway.provider_cost_record")).isEqualTo(1);
        assertThat(count("gateway.usage_economics_outbox")).isEqualTo(1);
        assertThat(usageText("req-evidence-heal", "outcome")).isEqualTo("SUCCEEDED");
        assertThat(usageLong("req-evidence-heal", "usage_units")).isEqualTo(1);
        assertThat(providerCostAmount("req-evidence-heal")).isEqualTo(PROVIDER_UNIT_COST);
        // 补写不碰钱：既不重复扣费，也不重复结算。
        assertThat(ledgerSettleCount()).isEqualTo(1);
        assertThat(usedImages()).isEqualTo(1);

        // 再重放一次：补写本身也必须恰好一次。
        submit("req-evidence-heal", 1);
        assertThat(count("gateway.usage_log")).isEqualTo(1);
        assertThat(count("gateway.usage_stage")).isEqualTo(1);
        assertThat(count("gateway.provider_cost_record")).isEqualTo(1);
        assertThat(count("gateway.usage_economics_outbox")).isEqualTo(1);
    }

    /**
     * schema 也承认这个阶段：{@code ck_usage_stage_type} 必须列出
     * {@code IMAGE_GENERATION}，否则整条证据链在写入时就被数据库拒绝。
     * 这正是网关 V83 所做的唯一一处结构变更。
     */
    @Test
    void theUsageStageSchemaAdmitsTheImageGenerationStage() throws Exception {
        assertThat(queryText("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_usage_stage_type'
                  AND conrelid = 'gateway.usage_stage'::regclass
                """)).contains("IMAGE_GENERATION").contains("VIDEO_GENERATION")
                .contains("FINAL_RESPONSE").contains("FILE_ANALYSIS");
    }

    // ---- 扇出：一次请求买 N 张（网关迁移 V114）---------------------------------

    /**
     * 一次请求买四张，就真的交付四张、扣四张。
     *
     * <p>这是整个扇出改动的底线：N 张必须**真的**都到手。厂商侧实测静默忽略
     * {@code n}（{@code n=2}/{@code n=4} 都只回一张），因此这四张只可能来自
     * 网关自己发的四次调用——四行任务、四个预留。
     */
    @Test
    void aFanoutRequestDeliversAndBillsEveryImageItBought() throws Exception {
        ImageGenerationService.GenerationView view = submit("req-fanout-4", 4);

        assertThat(view.data()).hasSize(4);
        assertThat(view.billedImages()).isEqualTo(4);
        assertThat(view.partial()).isFalse();
        assertThat(view.errorCategory()).isNull();

        // 账本：四张全部转为已用，没有残留占用，恰好四次结算。
        assertThat(usedImages()).isEqualTo(4);
        assertThat(reservedImages()).isZero();
        assertThat(ledgerSettleCount()).isEqualTo(4);

        // 四行子任务，每张一行，每行只买一张，且都挂着同一个父请求。
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE fanout_parent_request_id = 'req-fanout-4'")).isEqualTo(4);
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE fanout_parent_request_id = 'req-fanout-4' AND requested_images = 1"))
                .isEqualTo(4);
        assertThat(queryLong("SELECT count(DISTINCT reservation_id) "
                + "FROM gateway.image_generation_task "
                + "WHERE fanout_parent_request_id = 'req-fanout-4'")).isEqualTo(4);
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE fanout_parent_request_id = 'req-fanout-4' AND status = 'SUCCEEDED'"))
                .isEqualTo(4);

        // 派生的子 request id 的形状：父 id + # + 张序。
        assertThat(statusOf("req-fanout-4#1")).isEqualTo("SUCCEEDED");
        assertThat(statusOf("req-fanout-4#4")).isEqualTo("SUCCEEDED");
        assertThat(billedImagesOf("req-fanout-4#1")).isEqualTo(1);
    }

    /**
     * 一次扇出请求必须在**客户端那个幂等键下**留下自己的锚点行。
     *
     * <p>这条用例补的是一个"测试看不见"的缺陷。改前扇出只写派生行
     * （{@code <父键>#1..#n}），客户端键下一行都没有，于是 {@code generate()}
     * 按该键查不到任何东西——重试**不是重放，而是重新买**。而客户端文档承诺的
     * 恰恰是「复用原 Idempotency-Key 重试，系统只补做缺的那几张」。
     *
     * <p><b>为什么原来的测试没抓到它。</b>原来那条
     * {@code replayingAFanoutRequestReturnsEveryImageAgainWithoutBuyingMore}
     * 只断言"额度、结算次数、子任务行数不变"。没有锚点时，重放会重新扇出一次，
     * 重新插入**同样数量**的子任务行，于是这三个数字看起来全都对——测试通过了，
     * 而客户已经被多扣了一次钱。这是一个"断言选错了观测点"的典型案例：
     * 要钉住"没有重买"，看的必须是**花费**（{@code usedImages} / 结算次数）
     * 和**锚点是否存在**，而不是行数。
     *
     * <p>这里同时钉住锚点行的形状，因为 {@code replay} 正是靠它把整族聚合出去：
     * 它必须挂在客户端键上、{@code requested_images} 等于请求张数（这是
     * "我是扇出"的唯一标记）、并且两个扇出链接列都为 null（与所有子任务行相反）。
     */
    @Test
    void aFanoutRequestKeepsAnAnchorRowUnderTheClientKey() throws Exception {
        ImageGenerationService.GenerationView view = submit("req-anchor-3", 3);
        assertThat(view.data()).hasSize(3);

        // 先看清库里到底有什么：这条断言在修复生效时给出 4（1 锚点 + 3 张），
        // 在锚点缺失时给出 3，比任何推理都直接。
        assertThat(count("gateway.image_generation_task")).isEqualTo(4);

        // 锚点：就挂在客户端给的那个键上，而不是任何派生 id 上。
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE request_id = 'req-anchor-3'")).isEqualTo(1);
        assertThat(requestedImagesOf("req-anchor-3")).isEqualTo(3);
        assertThat(taskColumn("req-anchor-3", "fanout_parent_request_id")).isNull();
        assertThat(taskColumn("req-anchor-3", "fanout_index")).isNull();

        // 它自己不携带图片、也永不结算：三张图各自落在三行子任务上。
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE fanout_parent_request_id = 'req-anchor-3'")).isEqualTo(3);
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE status = 'SUCCEEDED'")).isEqualTo(3);

        // 总行数 = 1 个锚点 + 3 张图。这条断言是"锚点真的写进去了"的正向证据。
        assertThat(count("gateway.image_generation_task")).isEqualTo(4);
    }

    /**
     * 锚点行**不得**被恢复扫描当成一张没跑完的图。
     *
     * <p>锚点永远停在 SUBMITTED（{@code ck_image_task_result} 要求 SUCCEEDED 的行
     * 必须有 {@code result_images > 0}，而锚点不携带图片），所以它天然长得像
     * "一张卡住的任务"。若恢复扫描把它捡走，它会去访问锚点上那一列
     * {@code reservation_id}——那指向**第一个子任务的预留**，也就是一张真实存在、
     * 可能还在飞的图。扫描会按"结果未知"把它释放掉：客户付过费、厂商也确实产出了
     * 的那一张被白送出去。这条用例把"扫描看不到锚点"钉死。
     */
    @Test
    void theRecoverySweepDoesNotSeeTheFanoutAnchor() throws Exception {
        submit("req-anchor-sweep", 2);

        // 让锚点与子任务都"过期"：把两个时间戳一起挪，满足 ck_image_task_expiry。
        execute("UPDATE gateway.image_generation_task "
                + "SET submitted_at = submitted_at - interval '1 hour', "
                + "    expires_at = expires_at - interval '1 hour' "
                + "WHERE request_id LIKE 'req-anchor-sweep%'");

        long settledBefore = ledgerSettleCount();
        long usedBefore = usedImages();

        // 提交之后，库里唯一还挂在 SUBMITTED 的行就是那条锚点——两张图在提交时
        // 就已经各自结算成 SUCCEEDED 了。所以"锚点被排除"这件事可以直接验：
        // 仍然开放的行里，一张 requested_images = 1 的都没有。
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE status = 'SUBMITTED'")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE status = 'SUBMITTED' AND requested_images = 1")).isZero();

        // 而扫描的候选集里当然就不该有任何属于这次请求的行——尤其不能有那条锚点。
        assertThat(service.openTasks(50).collectList().block())
                .as("锚点绝不能被恢复扫描当成一张没跑完的图")
                .noneSatisfy(task -> assertThat(task.requestId())
                        .startsWith("req-anchor-sweep"));

        // 把锚点与子任务都推到"过期"，让扫描真的有机会捡走它。
        execute("UPDATE gateway.image_generation_task "
                + "SET submitted_at = submitted_at - interval '1 hour', "
                + "    expires_at = expires_at - interval '1 hour' "
                + "WHERE request_id LIKE 'req-anchor-sweep%'");

        recoveryJob.recoverExpiredTasks();

        // 恢复之后：锚点原样留在 SUBMITTED（恢复只处理图片任务），
        // 它绑定那条预留（也就是第一张图的预留）仍然是已结算、没有被释放。
        assertThat(statusOf("req-anchor-sweep")).isEqualTo("SUBMITTED");
        assertThat(ledgerSettleCount()).isEqualTo(settledBefore);
        assertThat(usedImages()).isEqualTo(usedBefore);
        assertThat(reservedImages()).isZero();
    }

    /**
     * 扇出的重放交付**整族**，而不是父行那一行。
     *
     * <p>客户端只认得它给的那一个幂等键，而四张图分别落在四行子任务上。若重放
     * 只返回其中一张，客户重放一次 4 张的请求就只拿回 1 张——那正是这次改造要
     * 消灭的静默少交付，只是换了个地方发生。这里同时钉住"重放不重复扣费"。
     */
    @Test
    void replayingAFanoutRequestReturnsEveryImageAgainWithoutBuyingMore() throws Exception {
        ImageGenerationService.GenerationView first = submit("req-fanout-replay", 3);
        assertThat(first.data()).hasSize(3);
        assertThat(usedImages()).isEqualTo(3);

        ImageGenerationService.GenerationView replayed = submit("req-fanout-replay", 3);

        assertThat(replayed.data()).hasSize(3);
        assertThat(replayed.billedImages()).isEqualTo(3);
        assertThat(replayed.created()).isEqualTo(first.created());
        // 重放没有买任何新东西：额度、任务行、结算次数都不变。
        assertThat(usedImages()).isEqualTo(3);
        assertThat(reservedImages()).isZero();
        assertThat(ledgerSettleCount()).isEqualTo(3);
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE fanout_parent_request_id = 'req-fanout-replay'")).isEqualTo(3);
    }

    /**
     * 部分成功：四张里两张被厂商明确拒绝，交付两张、只收两张、退款两张。
     *
     * <p>这正是"允许部分成功"的完整形状，也是扇出相对"全有或全无"的差别所在。
     * {@code mock://image/succeed-partial} 让偶数序号的那两张以 4xx 明确拒绝——
     * 4xx 是确定性拒绝，因此那两张的钱必须退回去，而另外两张照常交付。
     *
     * <p>{@code partial} 标记必须为真：少交付是事实，静默地少交付才是缺陷。
     */
    @Test
    void aFanoutRequestDeliversTheImagesThatSucceededAndRefundsTheOnesThatFailed()
            throws Exception {
        setChannel("mock://image/succeed-partial");

        ImageGenerationService.GenerationView view = submit("req-fanout-partial", 4);

        // 交付的两张是 #1 与 #3；被拒绝的 #2 与 #4 一张都不交付。
        assertThat(view.data()).hasSize(2);
        assertThat(view.partial()).isTrue();
        assertThat(view.billedImages()).isEqualTo(2);

        // 钱只按成功的两张收：4 个预留里 2 个结算、2 个释放。
        assertThat(usedImages()).isEqualTo(2);
        assertThat(reservedImages()).isZero();
        assertThat(ledgerSettleCount()).isEqualTo(2);

        // 被拒绝的那两张各自是终态且带上了原因，而不是留在 SUBMITTED 里等
        // 8 分钟后的恢复扫描——那会让一次已经给出答案的请求被反复追问。
        assertThat(statusOf("req-fanout-partial#1")).isEqualTo("SUCCEEDED");
        assertThat(statusOf("req-fanout-partial#2")).isEqualTo("CANCELLED");
        assertThat(statusOf("req-fanout-partial#3")).isEqualTo("SUCCEEDED");
        assertThat(statusOf("req-fanout-partial#4")).isEqualTo("CANCELLED");
        assertThat(errorCategoryOf("req-fanout-partial#2")).isEqualTo("upstream_rejected");
        assertThat(billedImagesOf("req-fanout-partial#2")).isZero();
    }

    /**
     * 重放一次"部分成功"的请求：仍然交付那两张，仍然不重买被退款的那两张。
     *
     * <p>P2P 语义里最容易出错的一处：失败的那两张是终态，
     * {@link ImageGenerationService} 因此不会为它们再发一次厂商调用。若这里退化成
     * "重放即重试"，客户每刷新一次页面就会被多买两张。
     */
    @Test
    void replayingAPartialFanoutDoesNotBuyTheRefundedImagesAgain() throws Exception {
        setChannel("mock://image/succeed-partial");

        submit("req-fanout-partial-replay", 4);
        long settleCountAfterFirst = ledgerSettleCount();

        ImageGenerationService.GenerationView replayed = submit("req-fanout-partial-replay", 4);

        assertThat(replayed.data()).hasSize(2);
        assertThat(replayed.partial()).isTrue();
        assertThat(replayed.billedImages()).isEqualTo(2);
        assertThat(usedImages()).isEqualTo(2);
        assertThat(reservedImages()).isZero();
        // 没有新的结算发生：被退款的那两张没有被重新买一次。
        assertThat(ledgerSettleCount()).isEqualTo(settleCountAfterFirst);
        assertThat(statusOf("req-fanout-partial-replay#2")).isEqualTo("CANCELLED");
    }

    /**
     * 套餐付不起 N 张时，整批都不开始。
     *
     * <p>这是扇出最危险的中间态：四张里前三张占上了、第四张额度不足，
     * 那时前三次厂商调用可能已经发出、钱已经花了，而交付注定不完整。
     * 因此预留必须**全部成功之后**才发出任何一次厂商调用；任何一次失败都释放
     * 已占用的那些并整体拒绝。
     *
     * <p>构造方式是把套餐消耗到**只剩一张**（{@code PACKAGE_IMAGES - 1} 次单图），
     * 然后要四张：第一批预留会成功，而整体必然失败。若收尾逻辑写错（例如失败时
     * 忘记释放已占用的预留），这里就会留下残留占用；若前置校验写错（例如付不起
     * 也照样发调用），这里就会多出任务行。
     */
    @Test
    void aFanoutRequestThatThePackageCannotAffordBuysNothingAtAll() throws Exception {
        for (int index = 1; index < PACKAGE_IMAGES; index++) {
            submit("req-drain-" + index, 1);
        }
        assertThat(usedImages()).isEqualTo(PACKAGE_IMAGES - 1L);
        assertThat(reservedImages()).isZero();

        // 只剩一张，却要四张：必然付不起。
        assertThatThrownBy(() -> submit("req-fanout-unaffordable", 4))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("quota_exhausted");
                });

        // 关键：一张都没有买，而且为这次请求占下的预留全部被还了回去。
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE fanout_parent_request_id = 'req-fanout-unaffordable'")).isZero();
        assertThat(reservedImages()).isZero();
        assertThat(usedImages()).isEqualTo(PACKAGE_IMAGES - 1L);
    }

    /**
     * 一次请求超过 {@link ImageGenerationService#MAX_FANOUT} 张直接拒绝。
     *
     * <p>服务层的边界，与控制器那道 422 是两回事：绕过 HTTP 层的调用方
     * （内部调用、补偿、将来的第二个入口）也提不上去。上限是**一次请求**的
     * 张数，不是一次厂商调用的张数。
     */
    @Test
    void aFanoutRequestBeyondTheCeilingIsRefusedBeforeAnythingIsBought() throws Exception {
        assertThatThrownBy(() -> submit(
                        "req-fanout-over", ImageGenerationService.MAX_FANOUT + 1))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("invalid_image_request");
                });

        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task")).isZero();
        assertThat(reservedImages()).isZero();
    }

    /**
     * **n = 1 就是一次厂商调用，不是"扇出一次"。**
     *
     * <p>这是"一次请求买几张"与"一次买几张"这两件事最容易混淆的地方：单图请求
     * 走的必须是原来那条路径（{@code generateSingle}），不能顺手也过一遍扇出
     * 机制。两者在账面上都是一个预留、一次结算，差别只有在**厂商被叫了几次**
     * 上看得出来——而那个差别是要花钱的。
     *
     * <p>构造上让"偶数序号扇出子任务"必然失败（{@code succeed-partial}），
     * 并且**故意不设基线**：如果 n = 1 被错误地当成扇出，它唯一的那个子任务
     * 会被派生成 {@code req-one-call#1}（奇数）而侥幸成功，那这条断言就白写了；
     * 因此这里同时断言库里那一行的 request_id **就是**客户端给的原值，
     * 不带任何 {@code #序号} 后缀——那才是"没走扇出"的直接证据。
     */
    @Test
    void aSingleImageRequestCallsTheVendorExactlyOnceAndNeverFansOut() throws Exception {
        setChannel("mock://image/succeed-partial");

        ImageGenerationService.GenerationView view = submit("req-one-call", 1);

        assertThat(view.data()).hasSize(1);
        assertThat(view.billedImages()).isEqualTo(1);
        assertThat(view.partial()).isFalse();

        // 厂商恰好被叫了一次：一行任务、一个预留、一次结算。
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task")).isEqualTo(1);
        assertThat(ledgerSettleCount()).isEqualTo(1);
        assertThat(usedImages()).isEqualTo(1);
        assertThat(reservedImages()).isZero();

        // 那一行的 request_id 就是客户端给的原值：没有派生、没有扇出、没有父子链接。
        assertThat(statusOf("req-one-call")).isEqualTo("SUCCEEDED");
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE fanout_parent_request_id IS NOT NULL")).isZero();
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE request_id LIKE 'req-one-call#%'")).isZero();
    }

    /**
     * 与上一条对照：**n = 4 就是四次厂商调用**，不多不少。
     *
     * <p>两条测试合起来回答"是按张数区分，还是每次都出四张"：单图不扇出、
     * 四张就是四路。若哪天有人把扇出写成恒定四路，上一条会红；若写成恒定一路，
     * 这一条会红。
     */
    @Test
    void aFourImageRequestCallsTheVendorExactlyFourTimes() throws Exception {
        ImageGenerationService.GenerationView view = submit("req-four-calls", 4);

        assertThat(view.data()).hasSize(4);
        assertThat(view.billedImages()).isEqualTo(4);

        // 四路厂商调用 = 四行子任务 + **一行父锚点**、四个独立预留、四次结算。
        // 那行锚点是在 2026-10-15 补上的：它让同一个幂等键的重试成为重放而不是重新购买。
        // 断言从 4 改成 5，是因为这一行确实该存在——它此前缺失正是那个缺陷本身。
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task")).isEqualTo(5);
        assertThat(ledgerSettleCount()).isEqualTo(4);
        assertThat(usedImages()).isEqualTo(4);
        assertThat(reservedImages()).isZero();

        // 每一张都是派生的子请求，而不是把 n = 4 原样交给厂商；
        // 而客户端的那个键本身落在那行锚点上。
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE request_id LIKE 'req-four-calls#%'")).isEqualTo(4);
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE request_id = 'req-four-calls'")).isEqualTo(1);
    }

    /**
     * 单图路径完全没有被扇出改动碰到。
     *
     * <p>它是这次改造的回归底线：{@code n = 1}（含省略）必须与 V114 之前逐字节
     * 相同——一行任务、一个预留、不带任何扇出链接。
     */
    @Test
    void aSingleImageRequestKeepsTheOriginalShapeWithNoFanoutLink() throws Exception {
        ImageGenerationService.GenerationView view = submit("req-single-shape", 1);

        assertThat(view.data()).hasSize(1);
        assertThat(view.billedImages()).isEqualTo(1);
        assertThat(view.partial()).isFalse();
        assertThat(requestedImagesOf("req-single-shape")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE fanout_parent_request_id IS NOT NULL")).isZero();
        assertThat(queryLong("SELECT count(*) FROM gateway.image_generation_task "
                + "WHERE request_id = 'req-single-shape'")).isEqualTo(1);
        assertThat(ledgerSettleCount()).isEqualTo(1);
    }

    // ---- 辅助方法 ----------------------------------------------------------------

    private ImageGenerationService.GenerationView submit(String requestId, int imageCount) {
        return service.generate(new ImageGenerationService.SubmitCommand(
                requestId,
                apiKey(),
                ALIAS,
                "a lighthouse at dusk",
                null,
                "2K",
                imageCount,
                "url",
                true,
                false)).block();
    }

    private ImageGenerationService.GenerationView submitWithSize(String requestId, String size) {
        return service.generate(new ImageGenerationService.SubmitCommand(
                requestId,
                apiKey(),
                ALIAS,
                "a lighthouse at dusk",
                null,
                size,
                1,
                "url",
                true,
                false)).block();
    }

    /**
     * 一次图层拆分调用：有输入图、没有提示词、{@code layerDecomposition = true}。
     *
     * <p>三者缺一不可——图层拆分拆的是一张已有的图，因此没有输入图时该请求在
     * 服务层就被拒绝（{@code invalid_image_request}）。
     */
    private ImageGenerationService.GenerationView submitLayerDecomposition(String requestId) {
        return service.generate(new ImageGenerationService.SubmitCommand(
                requestId,
                apiKey(),
                ALIAS,
                null,
                INPUT_IMAGE,
                "2K",
                1,
                "url",
                true,
                true)).block();
    }

    private void setChannel(String baseUrl) throws Exception {
        channelBaseUrl = baseUrl;
        execute("UPDATE gateway.channel SET base_url = '" + baseUrl + "' WHERE id = '"
                + CHANNEL_ID + "'");
    }

    /**
     * 一次结果未知的生图必须被**收尾**，而不是把客户的额度永久占住。
     *
     * <p>这是线上真实缺陷的回归测试。在此之前：任务停在 {@code SUBMITTED}、
     * 预留停在 {@code RESERVED}，而通用预留清扫对非 TOKEN 维度是 fail-closed 的，
     * 所以它**永远不会**替我们释放——客户的额度就这样少了一张，
     * 而且没有任何东西会来纠正它（线上一个视频用户因此被冻住了全部剩余额度 17 天）。
     */
    @Test
    void recoveryReleasesTheQuotaOfAGenerationWhoseOutcomeStayedUnknown() throws Exception {
        setChannel("mock://image/unavailable");
        assertThatThrownBy(() -> submit("req-unknown-outcome", 1))
                .isInstanceOf(ProviderAdapter.ProviderException.class);

        assertThat(statusOf("req-unknown-outcome")).isEqualTo("SUBMITTED");
        assertThat(reservedImages()).isEqualTo(1);
        assertThat(usedImages()).isZero();

        // 把 TTL 挪到过去，正是恢复扫描的调用条件。
        // 两个时间戳必须一起挪：表上有 ck_image_task_expiry(expires_at > submitted_at)。
        execute("UPDATE gateway.image_generation_task "
                + "SET submitted_at = submitted_at - interval '1 hour', "
                + "    expires_at = expires_at - interval '1 hour' "
                + "WHERE request_id = 'req-unknown-outcome'");

        ImageTaskRepository.TaskRow task = service.openTasks(50)
                .filter(candidate -> "req-unknown-outcome".equals(candidate.requestId()))
                .blockFirst(Duration.ofSeconds(5));
        assertThat(task).isNotNull();

        service.recoverExpired(task).block(Duration.ofSeconds(10));

        assertThat(statusOf("req-unknown-outcome")).isEqualTo("FAILED");
        assertThat(errorCategoryOf("req-unknown-outcome"))
                .isEqualTo(ImageGenerationService.TASK_EXPIRED);
        assertThat(billedImagesOf("req-unknown-outcome")).isZero();
        // 额度回到了客户手里：预留清零，已用仍为零。
        assertThat(reservedImages()).isZero();
        assertThat(usedImages()).isZero();
    }

    /**
     * 恢复路径**只**碰仍然开放的任务。
     *
     * <p>不设这道守卫，一次正常的成功生成会在它的 TTL 到期后被恢复扫描
     * 再收尾一次——而那次收尾是「释放」：客户白拿一张已经交付的图。
     */
    @Test
    void recoveryNeverTouchesASettledTask() throws Exception {
        setChannel("mock://image/succeed");
        submit("req-already-done", 1);

        assertThat(statusOf("req-already-done")).isEqualTo("SUCCEEDED");
        assertThat(reservedImages()).isZero();
        long settled = settledTokens("req-already-done");

        execute("UPDATE gateway.image_generation_task "
                + "SET submitted_at = submitted_at - interval '1 hour', "
                + "    expires_at = expires_at - interval '1 hour' "
                + "WHERE request_id = 'req-already-done'");

        // 候选集只含 SUBMITTED，因此一次成功的生成永远不会被恢复扫描看到，
        // 也就不可能被「释放」掉。
        assertThat(service.openTasks(50)
                .filter(candidate -> "req-already-done".equals(candidate.requestId()))
                .blockFirst(Duration.ofSeconds(5))).isNull();

        assertThat(statusOf("req-already-done")).isEqualTo("SUCCEEDED");
        assertThat(settledTokens("req-already-done")).isEqualTo(settled);
    }

    private ApiKeyService.ApiKeyContext apiKey() {
        return new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of(ALIAS), true);
    }

    private long usedImages() throws Exception {
        return queryLong("SELECT used_tokens FROM gateway.package_instance WHERE id = '"
                + PACKAGE_ID + "'");
    }

    private long reservedImages() throws Exception {
        return queryLong("SELECT reserved_tokens FROM gateway.package_instance WHERE id = '"
                + PACKAGE_ID + "'");
    }

    private long ledgerSettleCount() throws Exception {
        return queryLong("SELECT count(*) FROM gateway.quota_ledger WHERE entry_type = 'SETTLE'");
    }

    /**
     * 结算这一笔实际向套餐收了几张。
     *
     * <p>读的是结算自己写下的那两个数：预留对象上的 {@code package_settled_tokens}
     * （结算收了多少）与 {@code actual_total_tokens}（这次用量报了多少）。
     * 套餐余额只说明净效果，而这里是扣费本身的证据 —— 「图层调用只收一个单位」
     * 必须落在这两个数上，而不只是落在返回值里。
     */
    private long settledTokens(String requestId) throws Exception {
        return queryLong("SELECT package_settled_tokens FROM gateway.quota_reservation "
                + "WHERE request_id = '" + requestId + "'");
    }

    private long reportedTokens(String requestId) throws Exception {
        return queryLong("SELECT actual_total_tokens FROM gateway.quota_reservation "
                + "WHERE request_id = '" + requestId + "'");
    }

    private String statusOf(String requestId) throws Exception {
        return taskColumn(requestId, "status");
    }

    private String errorCategoryOf(String requestId) throws Exception {
        return taskColumn(requestId, "error_category");
    }

    private String sizeOf(String requestId) throws Exception {
        return taskColumn(requestId, "requested_size");
    }

    private String tierOf(String requestId) throws Exception {
        return taskColumn(requestId, "resolution_tier");
    }

    private long requestedImagesOf(String requestId) throws Exception {
        return queryLong("SELECT requested_images FROM gateway.image_generation_task "
                + "WHERE request_id = '" + requestId + "'");
    }

    private long billedImagesOf(String requestId) throws Exception {
        return queryLong("SELECT billed_images FROM gateway.image_generation_task "
                + "WHERE request_id = '" + requestId + "'");
    }

    private long resultImagesOf(String requestId) throws Exception {
        return queryLong("SELECT result_images FROM gateway.image_generation_task "
                + "WHERE request_id = '" + requestId + "'");
    }

    private String taskColumn(String requestId, String column) throws Exception {
        return queryText("SELECT " + column + " FROM gateway.image_generation_task "
                + "WHERE request_id = '" + requestId + "'");
    }

    /** 该次请求写下的用量行的某一列。 */
    private long usageLong(String requestId, String column) throws Exception {
        return queryLong("SELECT " + column + " FROM gateway.usage_log WHERE request_id = '"
                + requestId + "'");
    }

    private String usageText(String requestId, String column) throws Exception {
        return queryText("SELECT " + column + " FROM gateway.usage_log WHERE request_id = '"
                + requestId + "'");
    }

    /** 用量行上的任意表达式（用来断言 JSON 依据与布尔列）。 */
    private String usageExpression(String requestId, String expression) throws Exception {
        return queryText("SELECT " + expression + " FROM gateway.usage_log usage "
                + "WHERE usage.request_id = '" + requestId + "'");
    }

    private long stageLong(String requestId, String column) throws Exception {
        return queryLong("SELECT stage." + column + " FROM gateway.usage_stage stage "
                + "JOIN gateway.usage_log usage ON usage.id = stage.usage_log_id "
                + "WHERE usage.request_id = '" + requestId + "'");
    }

    private String stageText(String requestId, String column) throws Exception {
        return queryText("SELECT stage." + column + " FROM gateway.usage_stage stage "
                + "JOIN gateway.usage_log usage ON usage.id = stage.usage_log_id "
                + "WHERE usage.request_id = '" + requestId + "'");
    }

    private String stageExpression(String requestId, String expression) throws Exception {
        return queryText("SELECT " + expression + " FROM gateway.usage_stage stage "
                + "JOIN gateway.usage_log usage ON usage.id = stage.usage_log_id "
                + "WHERE usage.request_id = '" + requestId + "'");
    }

    private long providerCostAmount(String requestId) throws Exception {
        return queryLong("SELECT record.amount FROM gateway.provider_cost_record record "
                + "JOIN gateway.usage_log usage ON usage.id = record.usage_log_id "
                + "WHERE usage.request_id = '" + requestId + "'");
    }

    /** 唯一的 outbox 事件载荷里的一个顶层字段。 */
    private String outboxText(String key) throws Exception {
        return queryText("SELECT payload ->> '" + key + "' FROM gateway.usage_economics_outbox");
    }

    private long queryLong(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String queryText(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private int count(String table) throws Exception {
        return (int) queryLong("SELECT count(*) FROM " + table);
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
