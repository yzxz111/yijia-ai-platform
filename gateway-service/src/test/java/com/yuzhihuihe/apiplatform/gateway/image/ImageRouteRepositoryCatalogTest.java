package com.yuzhihuihe.apiplatform.gateway.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.routing.CredentialCipher;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 针对真实迁移的图片目录与路由解析，以及平台毛利守卫所读的那条成本路径。
 *
 * <p>本测试有意不自己造生图别名，而是使用 V81 播种的
 * {@code image-basic}：目录、执行路由与成本基准必须同时对这一行成立，
 * 否则一个已售出的别名要么不可见、要么不可路由、要么无法定价。
 */
@Testcontainers
class ImageRouteRepositoryCatalogTest {

    private static final UUID TENANT_ID =
            UUID.fromString("98000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("98000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID =
            UUID.fromString("98000000-0000-4000-8000-000000000003");
    private static final UUID IMAGE_CHANNEL_ID =
            UUID.fromString("84000000-0000-4000-8000-000000000072");

    private static final String ALIAS = "image-basic";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static ImageRouteRepository repository;
    static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateAndSeedImageEntitlement() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        // V81 registers the channel disabled, UNKNOWN and without a credential
        // (V71's precedent), and registers the Tianyi provider disabled too.
        // This is the operator's step: an enabled provider, a healthy channel with
        // a stored credential, which is what makes it routable.
        execute("""
                UPDATE gateway.provider SET enabled = true WHERE code = 'tianyi';
                UPDATE gateway.channel
                SET base_url = 'https://ai.ctaigw.cn',
                    encrypted_api_key = decode('01', 'hex'),
                    enabled = true,
                    last_health_status = 'HEALTHY'
                WHERE id = '84000000-0000-4000-8000-000000000072'
                """);

        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, package_kind,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, status, starts_at, activated_at, expires_at, usage_dimension)
                VALUES ('98000000-0000-4000-8000-000000000003', gen_random_uuid(),
                        gen_random_uuid(), '98000000-0000-4000-8000-000000000001',
                        '98000000-0000-4000-8000-000000000002', 'image-catalog-test',
                        gen_random_uuid(), 'IMAGE_TEST', 'Image Test', 'STANDARD',
                        8600, 'CNY',
                        '[{"modelCode":"image-basic","modelTokenQuota":null,"capabilities":["IMAGE_GEN"]}]'::jsonb,
                        20, 2, 20, 20, 2, 30, 'ACTIVE', now(), now(),
                        now() + interval '30 days', 'IMAGE');

                INSERT INTO gateway.package_instance_model_quota(
                    id, package_instance_id, model_code, usage_dimension, token_quota,
                    seconds_quota, reserved_tokens, used_tokens)
                VALUES (gen_random_uuid(), '98000000-0000-4000-8000-000000000003',
                        'image-basic', 'IMAGE', NULL, 20, 0, 0);
                """);
        String r2dbcUrl = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
                + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName();
        DatabaseClient database = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl));
        CredentialCipher credentialCipher = mock(CredentialCipher.class);
        when(credentialCipher.decrypt(any(byte[].class))).thenReturn("system-upstream-key");
        repository = new ImageRouteRepository(database, credentialCipher);
        // 费率卡的断言走 JDBC：用另一条通道读「库里那一行到底是什么」，
        // 才不会把被测代码的读取当成事实。
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /**
     * 这个别名被定价所依据的那一行：最坏情况 62 分/张，零售 89 分/张，版本 image-v2。
     *
     * <p>{@code gateway.image_effective_cost} 正是 {@code ProviderPriceQueryRepository}
     * 为 {@code /internal/prices/effective-unit-cost} 所查的视图——平台把它送到发布
     * 毛利守卫那里给图片版本定价——所以这里问的就是「代码真正读的那一行」。
     * 62 = 输出图最坏情况 60 分 + 输入图 2 分；89 是同时越过绝对下限 25 与
     * 比率下限 30% 的最小价格（ceil(62 / 0.7) = 89），因此图生图那 2 分输入成本
     * 由客户承担。旧行必须是被关闭的，而不是被改写的：已经按 image-v1 结算过的
     * 扣费仍要能用那一行解释。
     */
    @Test
    void pricesTheAliasOnTheWorstCaseBasisThatIncludesTheInputImage() {
        // 视图不暴露 minimum_units（它只投影费率与两个下限），因此「一个单位」
        // 那条从它背后的表上取。
        Map<String, Object> current = jdbcTemplate.queryForMap("""
                SELECT cost.provider_unit_cost, cost.effective_unit_cost, cost.unit_price,
                       cost.retry_rate, cost.target_margin_ratio, cost.min_absolute_margin,
                       price.minimum_units, price.minimum_charge
                FROM gateway.image_effective_cost cost
                JOIN gateway.model_usage_price price
                  ON price.model_code = cost.model_code
                 AND price.resolution_tier = cost.resolution_tier
                 AND price.usage_dimension = 'IMAGE'
                 AND price.effective_to IS NULL
                WHERE cost.model_code = 'image-basic' AND cost.resolution_tier = '2K'
                """);

        assertThat(current)
                .containsEntry("provider_unit_cost", 62L)
                .containsEntry("effective_unit_cost", 62L)
                .containsEntry("unit_price", 89L)
                .containsEntry("minimum_units", 1L)
                .containsEntry("minimum_charge", 89L)
                .containsEntry("target_margin_ratio", new BigDecimal("0.30000"))
                .containsEntry("min_absolute_margin", 25L);
        assertThat((BigDecimal) current.get("retry_rate")).isEqualByComparingTo("0");

        assertThat(jdbcTemplate.queryForList("""
                SELECT price_version FROM gateway.model_usage_price
                WHERE model_code = 'image-basic' AND usage_dimension = 'IMAGE'
                  AND resolution_tier = '2K' AND effective_to IS NULL
                """, String.class)).containsExactly("image-v2");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM gateway.model_usage_price
                WHERE model_code = 'image-basic' AND usage_dimension = 'IMAGE'
                  AND resolution_tier = '2K' AND price_version = 'image-v1'
                  AND effective_to IS NOT NULL
                """, Integer.class))
                .as("image-v1 is retired by effective_to, not deleted or rewritten")
                .isEqualTo(1);

        // 视图只暴露当前行：把已经关闭的 image-v1 重新打开会让上面那条断言拿到
        // 两行（查询返回多行本身就是失败），而 uq_model_usage_price_current
        //（V40:137-139）也必须在数据库侧挡住它。
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE gateway.model_usage_price SET effective_to = NULL
                WHERE model_code = 'image-basic' AND usage_dimension = 'IMAGE'
                  AND resolution_tier = '2K' AND price_version = 'image-v1'
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listsPurchasedImageAliasesEvenWhenUpstreamOrQuotaIsNotReady() throws Exception {
        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.alias()).isEqualTo(ALIAS);
            assertThat(row.displayName()).isEqualTo("图片基础版");
            assertThat(row.resolutionTier()).isEqualTo("2K");
        });
        assertThat(candidates()).singleElement().satisfies(channel -> {
            // 执行投影必须携带配置的渠道 URL、已解密的系统凭证以及模型映射；
            // 客户端只提交别名。
            assertThat(channel.baseUrl()).isEqualTo("https://ai.ctaigw.cn");
            assertThat(channel.apiKey()).isEqualTo("system-upstream-key");
            assertThat(channel.upstreamModel()).isEqualTo("doubao-seedream-5.0-pro");
            assertThat(channel.providerType()).isEqualTo("TIANYIYUN");
        });

        update("""
                UPDATE gateway.channel SET encrypted_api_key = NULL
                WHERE id = '84000000-0000-4000-8000-000000000072'
                """);
        // 目录可见与可路由是两件事：运维正在配置凭证时，套餐仍然应当看到它的模型。
        assertThat(rows()).hasSize(1);
        assertThat(candidates()).isEmpty();

        update("""
                UPDATE gateway.channel SET encrypted_api_key = decode('01', 'hex'), enabled = false
                WHERE id = '84000000-0000-4000-8000-000000000072'
                """);
        assertThat(rows()).hasSize(1);
        assertThat(candidates()).isEmpty();

        update("""
                UPDATE gateway.channel
                SET enabled = true, last_health_status = 'UNHEALTHY'
                WHERE id = '84000000-0000-4000-8000-000000000072'
                """);
        assertThat(rows()).hasSize(1);
        assertThat(candidates()).isEmpty();

        update("""
                UPDATE gateway.channel
                SET last_health_status = 'HEALTHY'
                WHERE id = '84000000-0000-4000-8000-000000000072';
                UPDATE gateway.channel_model SET enabled = false
                WHERE channel_id = '84000000-0000-4000-8000-000000000072'
                  AND model_id = '82000000-0000-4000-8000-000000000110'
                """);
        assertThat(rows()).hasSize(1);
        assertThat(candidates()).isEmpty();

        update("""
                UPDATE gateway.channel_model SET enabled = true
                WHERE channel_id = '84000000-0000-4000-8000-000000000072'
                  AND model_id = '82000000-0000-4000-8000-000000000110';
                UPDATE gateway.provider SET enabled = false
                WHERE code = 'tianyi'
                """);
        assertThat(candidates()).isEmpty();

        update("""
                UPDATE gateway.provider SET enabled = true WHERE code = 'tianyi';
                UPDATE gateway.package_instance_model_quota
                SET reserved_tokens = 20
                WHERE package_instance_id = '98000000-0000-4000-8000-000000000003'
                """);
        // 已购买的别名在其按模型的张数耗尽之后仍然可见；
        // POST /v1/images/generations 才拥有预留/准入决策权。
        assertThat(rows()).hasSize(1);

        update("""
                UPDATE gateway.package_instance_model_quota
                SET reserved_tokens = 0
                WHERE package_instance_id = '98000000-0000-4000-8000-000000000003';
                UPDATE gateway.package_instance
                SET reserved_tokens = 20
                WHERE id = '98000000-0000-4000-8000-000000000003';
                UPDATE gateway.channel
                SET last_health_status = 'UNHEALTHY'
                WHERE id = '84000000-0000-4000-8000-000000000072'
                """);
        assertThat(rows()).hasSize(1);
    }

    @Test
    void aKeyThatIsNotAllowedThisAliasCannotRouteToIt() {
        ApiKeyService.ApiKeyContext other = new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), TENANT_ID, USER_ID, PACKAGE_ID, Set.of("text-basic"));

        assertThat(repository.candidates(other, ALIAS, "[\"IMAGE_GEN\"]").collectList().block())
                .isEmpty();
    }

    private static List<ImageRouteRepository.ImageModelCatalogRow> rows() {
        return repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID)
                .collectList()
                .block();
    }

    /**
     * 渠道的超时预算必须真的被带进渠道对象。
     *
     * <p>它以前被查询选出来、却在构造渠道对象时**被丢掉**，于是运维在渠道上配的
     * 超时静默失效：他配 60 秒，实际生效的是适配器里那个常量。这类
     * 「看起来生效、其实被忽略」的配置比配错更难发现——配错会有症状，
     * 被忽略只会让人以为自己的调整起了作用。
     */
    @Test
    void carriesTheConfiguredChannelTimeoutIntoTheRoute() throws Exception {
        update("UPDATE gateway.channel SET timeout_ms = 123456 "
                + "WHERE id = '84000000-0000-4000-8000-000000000072'");

        assertThat(candidates()).singleElement()
                .satisfies(channel -> assertThat(channel.timeoutMs()).isEqualTo(123456));
    }

    private static List<ImageGenerationAdapter.Channel> candidates() {
        ApiKeyService.ApiKeyContext key = new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), TENANT_ID, USER_ID, PACKAGE_ID, Set.of(ALIAS));
        return repository.candidates(key, ALIAS, "[\"IMAGE_GEN\"]")
                .collectList()
                .block();
    }

    private static void update(String sql) throws Exception {
        execute(sql);
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
