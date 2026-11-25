package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 一次已结算的视频生成会产生多少运营证据，以及以什么单位产生。
 *
 * <p>本类过去所钉住的缺口：对话路径会写入 {@code gateway.usage_log} +
 * {@code gateway.usage_stage} + {@code gateway.provider_cost_record}，并投递一条
 * 经济性事件（{@code UsageStageWriter.write}），而
 * {@code platform.reporting.OperatingFactIngestionService} 正是把它变成
 * {@code operating_fact}，也是 {@code OperatingAlertService.evaluatePackageMargin}
 * 在低毛利告警能够触发之前所需要的——而视频结算路径只调用了
 * {@code QuotaService.settle}、别的什么都没做，于是一个亏钱的视频套餐
 * 完全不产生任何事实。
 *
 * <p>这个缺口是通过命名单位、而不是把秒数写进 token 计数器来弥合的：
 * 网关 V49 为 {@code usage_log}/{@code usage_stage} 增加了 {@code usage_dimension} +
 * {@code usage_units}，并允许一种 {@code VIDEO_GENERATION} stage 类型，
 * {@link VideoEconomicsEvidenceWriter} 为赢得带守卫终态转换的那个调用方写入证据，
 * 而事件版本 {@code USAGE_ECONOMICS_V3} 把该维度带给一个按维度分别维持
 * token 不变式的平台。
 *
 * <p>下面三个测试就是验收证据，强度递增：schema 能表达它、一次结算能产生它，
 * 以及这些数字正是一份诚实的按秒事实所要求的（token 为零、
 * {@code VIDEO_SECOND}、每秒 69 分）。
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=gateway",
        "spring.flyway.schemas=gateway",
        "spring.data.redis.repositories.enabled=false",
        "security.api-key.pepper=test-api-key-pepper",
        "security.internal.hmac-secret=test-internal-secret",
        "gateway.video.allow-mock=true",
        "gateway.video.recovery.fixed-delay-ms=3600000",
        "gateway.video.recovery.initial-delay-ms=3600000"
})
class VideoGenerationEconomicsEvidenceTest {

    private static final UUID USER_ID = UUID.fromString("51000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("51000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID = UUID.fromString("51000000-0000-4000-8000-000000000003");
    private static final UUID API_KEY_ID = UUID.fromString("51000000-0000-4000-8000-000000000004");
    private static final UUID PROVIDER_ID = UUID.fromString("51000000-0000-4000-8000-000000000005");
    private static final UUID CHANNEL_ID = UUID.fromString("51000000-0000-4000-8000-000000000006");
    private static final UUID VIDEO_MODEL_ID = UUID.fromString("51000000-0000-4000-8000-000000000007");

    /**
     * 与种子数据中的 {@code VIDEO_STARTER} 经济性一致：129 元 / 60 秒。
     * 网关的费率卡（{@code V40__usage_dimension_rate_card.sql}）把
     * {@code video-basic} 720P 定价为每秒 69 分供应商成本、100 分零售价，
     * 因此 8 个已结算秒数让平台付出 552 分，并分摊套餐价格的
     * 12900 * 8 / 60 = 1720 分。
     */
    private static final int PACKAGE_SECONDS = 60;
    private static final long PACKAGE_PRICE_AMOUNT = 12_900L;
    private static final long PROVIDER_UNIT_COST = 69L;
    private static final int SETTLED_SECONDS = 8;

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
        execute("""
                TRUNCATE gateway.video_generation_task, gateway.quota_ledger,
                         gateway.quota_reservation, gateway.package_instance_model_quota,
                         gateway.package_instance, gateway.api_key, gateway.channel_model,
                         gateway.channel, gateway.provider, gateway.model_alias, gateway.model,
                         gateway.usage_stage, gateway.provider_cost_record, gateway.usage_log,
                         gateway.usage_economics_outbox
                         CASCADE
                """);

        execute("""
                INSERT INTO gateway.model(id, code, display_name, capabilities,
                                          context_window, max_output_tokens, enabled)
                VALUES ('%s', 'video-basic', 'Video Basic', '["TEXT","IMAGE","VIDEO"]',
                        1, 1, true)
                """.formatted(VIDEO_MODEL_ID));
        execute("""
                INSERT INTO gateway.model_alias(id, model_id, alias)
                VALUES (gen_random_uuid(), '%s', 'video-basic')
                """.formatted(VIDEO_MODEL_ID));
        execute("""
                INSERT INTO gateway.video_alias_tier(model_code, resolution_tier)
                VALUES ('video-basic', '720P')
                """);
        execute("""
                INSERT INTO gateway.provider(id, code, name, provider_type, base_url, enabled)
                VALUES ('%s', 'mock-video', 'Mock Video', 'MOCK', 'mock://video', true)
                """.formatted(PROVIDER_ID));
        execute("""
                INSERT INTO gateway.channel(id, provider_id, code, name, environment, base_url,
                                            enabled, priority, weight, timeout_ms, last_health_status)
                VALUES ('%s', '%s', 'mock-video-primary', 'Mock Video Primary', 'PRODUCTION',
                        'mock://video/succeed', true, 100, 100, 60000, 'HEALTHY')
                """.formatted(CHANNEL_ID, PROVIDER_ID));
        execute("""
                INSERT INTO gateway.channel_model(id, channel_id, model_id, upstream_model,
                                                  enabled, capabilities)
                VALUES (gen_random_uuid(), '%s', '%s', 'mock-video-1', true, '["TEXT","IMAGE","VIDEO"]')
                """.formatted(CHANNEL_ID, VIDEO_MODEL_ID));
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, package_kind,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, status, starts_at, activated_at, expires_at, usage_dimension)
                VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', '%s', 'identity-key',
                        gen_random_uuid(), 'VIDEO_STARTER', 'Video Starter', 'STANDARD',
                        %d, 'CNY',
                        '[{"modelCode":"video-basic","modelTokenQuota":null,"capabilities":["TEXT","IMAGE","VIDEO"]}]'::jsonb,
                        %d, 2, 20, %d, 2, 30, 'ACTIVE',
                        now(), now(), now() + interval '30 days', 'VIDEO_SECOND')
                """.formatted(PACKAGE_ID, TENANT_ID, USER_ID, PACKAGE_PRICE_AMOUNT,
                        PACKAGE_SECONDS, PACKAGE_SECONDS));
        execute("""
                INSERT INTO gateway.package_instance_model_quota(
                    id, package_instance_id, model_code, usage_dimension, token_quota,
                    seconds_quota, reserved_tokens, used_tokens)
                VALUES (gen_random_uuid(), '%s', 'video-basic', 'VIDEO_SECOND', NULL,
                        %d, 0, 0)
                """.formatted(PACKAGE_ID, PACKAGE_SECONDS));
        execute("""
                INSERT INTO gateway.api_key(id, tenant_id, user_id, package_instance_id, name,
                                            key_prefix, key_hash, allowed_models, status)
                VALUES ('%s', '%s', '%s', '%s', 'video-key', 'vk_econ', 'hash-video-econ',
                        '["video-basic"]'::jsonb, 'ACTIVE')
                """.formatted(API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID));
    }

    @Autowired
    VideoGenerationService service;

    /**
     * 缺口已弥合：秒数通过配额账本计费，且同一次
     * 结算产生毛利告警所读取的运营证据。
     *
     * <p>本测试过去断言的是相反的一面——不存在任何用量行、stage、成本记录或
     * outbox 行——因为在把秒数记成 token 之前，这些一个都写不出来。
     */
    @Test
    void aSettledVideoGenerationWritesTheQuotaLedgerAndTheDimensionedEvidence() throws Exception {
        VideoGenerationService.TaskView done = submitAndPollToTerminal("req-no-economics");
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(done.billedSeconds()).isEqualTo(SETTLED_SECONDS);

        // 扣费本身是正确的，而且恰好一次。
        assertThat(queryLong("SELECT used_tokens FROM gateway.package_instance WHERE id = '"
                + PACKAGE_ID + "'")).isEqualTo(SETTLED_SECONDS);
        assertThat(queryLong("SELECT count(*) FROM gateway.quota_ledger "
                + "WHERE entry_type = 'SETTLE'")).isEqualTo(1);

        // 而且这笔支出现在对平台可见：秒数维度上一条用量行、
        // 一个 stage、一条供应商成本记录，以及 outbox 上一条事件。
        assertThat(queryLong("SELECT count(*) FROM gateway.usage_log")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM gateway.usage_stage")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM gateway.provider_cost_record")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM gateway.usage_economics_outbox")).isEqualTo(1);
        assertThat(queryString("SELECT usage_dimension FROM gateway.usage_log"))
                .isEqualTo("VIDEO_SECOND");
        assertThat(queryLong("SELECT usage_units FROM gateway.usage_log"))
                .isEqualTo(SETTLED_SECONDS);
        assertThat(queryString("SELECT stage_type FROM gateway.usage_stage"))
                .isEqualTo("VIDEO_GENERATION");
        // 再次轮询一个已完成的任务，绝不能写入第二套证据。
        service.status(apiKey(), "req-no-economics").block();
        assertThat(queryLong("SELECT count(*) FROM gateway.usage_log")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM gateway.usage_economics_outbox")).isEqualTo(1);
    }

    /**
     * 第二个维度是 schema 的属性，而不是一种约定：这些列
     * 存在，{@code ck_usage_stage_type} 允许该视频 stage，单位不变式是一条
     * CHECK，且一个 stage 不能声称与其用量行不同的维度。
     */
    @Test
    void theGatewayUsageEvidenceSchemaCarriesTheSecondDimensionAndTheVideoStageType() throws Exception {
        assertThat(queryLong("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'gateway'
                  AND table_name IN ('usage_log', 'usage_stage')
                  AND column_name IN ('usage_dimension', 'usage_units')
                """)).isEqualTo(4);

        String stageTypeCheck = queryString("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_usage_stage_type'
                """);
        assertThat(stageTypeCheck).contains("FINAL_RESPONSE").contains("FILE_ANALYSIS")
                .contains("VIDEO_GENERATION");

        // 危险的方向被强制住了：非 TOKEN 的行不得携带 token。
        String unitsCheck = queryString("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_usage_log_usage_units'
                """);
        assertThat(unitsCheck).contains("input_tokens = 0")
                .contains("output_tokens = 0").contains("total_tokens = 0");

        // 而且按秒的 stage 不能挂在一条 token 用量行下。
        String dimensionForeignKey = queryString("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'fk_usage_stage_usage_dimension'
                """);
        assertThat(dimensionForeignKey).contains("FOREIGN KEY (usage_log_id, usage_dimension)")
                .contains("REFERENCES gateway.usage_log(id, usage_dimension)");
    }

    /**
     * 预期的行为，现已实现并启用。
     *
     * <p>它断言一份诚实事实所要求的：一条 token 计数器
     * 保持为零的用量行、一个声明了自己维度的 stage、取自
     * {@code gateway.model_usage_price}/{@code video_effective_cost} 的供应商成本（此处为
     * 每秒 69 分），以及一条平台可据以分摊套餐
     * 价格的事件。它过去被禁用，是因为上述没有一项可以表达：
     * 事件携带的是 {@code input_tokens/output_tokens/total_tokens/package_settled_tokens}
     * 而没有维度，且摄取服务会拒绝一个超过 token 总数的
     * 已结算单位值，因此唯一能到达套餐毛利告警的形态，
     * 就是把秒数写进 token 计数器。
     *
     * <p><b>启用条件，五条现已全部满足</b>（参见
     * {@code docs/video-economics-evidence-gap.md}）：
     * <ol>
     *   <li>{@code UsageEconomicsEvent} 携带 {@code usage_dimension} 以及
     *       带维度限定的单位（{@code usage_units}、
     *       {@code package_settled_units}），且版本为
     *       {@code USAGE_ECONOMICS_V3}，因此更早的平台会拒绝该事件，
     *       而不是把秒数读成 token；
     *   <li>{@code gateway.usage_log} 与 {@code gateway.usage_stage} 有一个
     *       {@code usage_dimension} 列，且 {@code ck_usage_stage_type} 允许
     *       {@code VIDEO_GENERATION}（网关 V49）；
     *   <li>{@code platform.operating_fact} 与
     *       {@code platform.operating_daily_aggregate} 拥有
     *       {@code usage_dimension} + {@code usage_units}，并按维度分别
     *       维持不变式（平台 V60）；
     *   <li>{@code OperatingFactIngestionService} 校验维度，并
     *       依据已结算单位分摊收入；
     *   <li>视频结算的胜出者写入证据
     *       （{@link VideoEconomicsEvidenceWriter}），价格取自
     *       {@code gateway.video_effective_cost.provider_unit_cost} 按已计费
     *       秒数——位置就在 {@code VideoGenerationService.apply} 的
     *       {@code updated == 1L} 分支内。
     * </ol>
     * 下面的断言就是验收检查：token 为零、维度为
     * {@code VIDEO_SECOND}、8 秒的供应商成本为 552 分，以及一个带维度的
     * outbox 载荷。
     */
    @Test
    void aVideoSettlementMustEmitASecondDenominatedEconomicsEvent() throws Exception {
        submitAndPollToTerminal("req-economics");

        // 先看缺口：已结算的生成根本没有留下任何证据行。
        assertThat(queryLong("SELECT count(*) FROM gateway.usage_log "
                + "WHERE request_id = 'req-economics'")).isEqualTo(1);
        // 再看它无法被诚实写入的原因：没有维度可以用来命名单位。
        assertThat(queryString("SELECT usage_dimension FROM gateway.usage_log "
                + "WHERE request_id = 'req-economics'")).isEqualTo("VIDEO_SECOND");
        // 诚实的计数器：一次视频不消耗任何 token。
        assertThat(queryLong("SELECT total_tokens FROM gateway.usage_log "
                + "WHERE request_id = 'req-economics'")).isZero();
        assertThat(queryLong("SELECT provider_cost_amount FROM gateway.usage_stage stage "
                + "JOIN gateway.usage_log log ON log.id = stage.usage_log_id "
                + "WHERE log.request_id = 'req-economics'"))
                .isEqualTo(SETTLED_SECONDS * PROVIDER_UNIT_COST);
        assertThat(queryLong("SELECT amount FROM gateway.provider_cost_record record "
                + "JOIN gateway.usage_log log ON log.id = record.usage_log_id "
                + "WHERE log.request_id = 'req-economics'"))
                .isEqualTo(SETTLED_SECONDS * PROVIDER_UNIT_COST);
        assertThat(queryString("SELECT payload ->> 'usage_dimension' "
                + "FROM gateway.usage_economics_outbox"))
                .isEqualTo("VIDEO_SECOND");
    }

    private VideoGenerationService.TaskView submitAndPollToTerminal(String requestId) {
        service.submit(new VideoGenerationService.SubmitCommand(
                requestId, apiKey(), "video-basic", "a lighthouse at dusk", null,
                SETTLED_SECONDS, "720P", null, false)).block();
        // mock://video/succeed 先报告 RUNNING，第二次轮询才报告 SUCCEEDED。
        service.status(apiKey(), requestId).block();
        return service.status(apiKey(), requestId).block();
    }

    private ApiKeyService.ApiKeyContext apiKey() {
        return new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-basic"), true);
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

    private String queryString(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
