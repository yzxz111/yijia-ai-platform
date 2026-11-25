package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.routing.CredentialCipher;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class VideoRouteRepositoryCatalogTest {

    private static final UUID TENANT_ID =
            UUID.fromString("97000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("97000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID =
            UUID.fromString("97000000-0000-4000-8000-000000000003");
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static VideoRouteRepository repository;

    @BeforeAll
    static void migrateAndSeedVideoEntitlement() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        execute("""
                UPDATE gateway.channel
                SET base_url = 'https://workspace-test.cn-beijing.maas.aliyuncs.com',
                    encrypted_api_key = decode('01', 'hex'),
                    last_health_status = 'HEALTHY'
                WHERE id = '84000000-0000-4000-8000-000000000002';

                INSERT INTO gateway.channel_model(
                    id, channel_id, model_id, upstream_model, enabled, capabilities)
                SELECT gen_random_uuid(), '84000000-0000-4000-8000-000000000002',
                       model.id, 'wan2.6-t2v', true, '["VIDEO"]'::jsonb
                FROM gateway.model model
                WHERE model.code = 'video-minimax';

                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, package_kind,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, status, starts_at, activated_at, expires_at, usage_dimension)
                VALUES ('97000000-0000-4000-8000-000000000003', gen_random_uuid(),
                        gen_random_uuid(), '97000000-0000-4000-8000-000000000001',
                        '97000000-0000-4000-8000-000000000002', 'video-catalog-test',
                        gen_random_uuid(), 'VIDEO_TEST', 'Video Test', 'STANDARD',
                        12900, 'CNY',
                        '[{"modelCode":"video-minimax","modelTokenQuota":60,"capabilities":["VIDEO"]}]'::jsonb,
                        60, 2, 20, 60, 2, 30, 'ACTIVE', now(), now(),
                        now() + interval '30 days', 'VIDEO_SECOND');

                INSERT INTO gateway.package_instance_model_quota(
                    id, package_instance_id, model_code, usage_dimension, token_quota,
                    seconds_quota, reserved_tokens, used_tokens)
                VALUES (gen_random_uuid(), '97000000-0000-4000-8000-000000000003',
                        'video-minimax', 'VIDEO_SECOND', NULL, 60, 0, 0);
                """);
        String r2dbcUrl = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
                + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName();
        CredentialCipher credentialCipher = mock(CredentialCipher.class);
        when(credentialCipher.decrypt(any(byte[].class))).thenReturn("system-upstream-key");
        repository = new VideoRouteRepository(
                DatabaseClient.create(ConnectionFactories.get(r2dbcUrl)), credentialCipher);
    }

    @Test
    void listsPurchasedVideoAliasesEvenWhenUpstreamOrQuotaIsNotReady() throws Exception {
        // V110 起客户端目录只有合并后的 video-minimax；档位别名（video-basic 等）
        // 收起为 client_visible=false，但按名字调用与执行路由不受影响。
        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.alias()).isEqualTo("video-minimax");
            assertThat(row.displayName()).isEqualTo("MiniMax");
            assertThat(row.resolutionTier()).isEqualTo("720P");
        });        assertThat(candidates()).singleElement().satisfies(channel -> {
            // 执行投影必须携带配置的渠道 URL、已解密的
            // 系统凭证以及模型映射；桌面端只提交别名。
            assertThat(channel.baseUrl())
                    .isEqualTo("https://workspace-test.cn-beijing.maas.aliyuncs.com");
            assertThat(channel.apiKey()).isEqualTo("system-upstream-key");
            assertThat(channel.upstreamModel()).isEqualTo("wan2.6-t2v");
        });

        update("""
                UPDATE gateway.channel
                SET encrypted_api_key = NULL
                WHERE id = '84000000-0000-4000-8000-000000000002'
                """);
        assertThat(rows()).hasSize(1);
        assertThat(candidates()).isEmpty();

        update("""
                UPDATE gateway.channel
                SET encrypted_api_key = decode('01', 'hex')
                WHERE id = '84000000-0000-4000-8000-000000000002'
                """);

        update("""
                UPDATE gateway.channel
                SET enabled = false
                WHERE id = '84000000-0000-4000-8000-000000000002'
                """);
        assertThat(rows()).hasSize(1);
        assertThat(candidates()).isEmpty();

        update("""
                UPDATE gateway.channel
                SET enabled = true, last_health_status = 'UNHEALTHY'
                WHERE id = '84000000-0000-4000-8000-000000000002'
                """);
        assertThat(rows()).hasSize(1);
        assertThat(candidates()).isEmpty();

        update("""
                UPDATE gateway.channel
                SET last_health_status = 'HEALTHY',
                    base_url = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
                WHERE id = '84000000-0000-4000-8000-000000000002'
                """);
        assertThat(rows()).hasSize(1);
        assertThat(candidates()).isEmpty();

        update("""
                UPDATE gateway.channel
                SET base_url = 'https://workspace-test.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis'
                WHERE id = '84000000-0000-4000-8000-000000000002';
                UPDATE gateway.channel_model
                SET enabled = false
                WHERE channel_id = '84000000-0000-4000-8000-000000000002'
                  AND model_id = (SELECT id FROM gateway.model WHERE code = 'video-minimax')
                """);
        assertThat(rows()).hasSize(1);
        assertThat(candidates()).isEmpty();

        update("""
        UPDATE gateway.channel_model
                SET enabled = true
                WHERE channel_id = '84000000-0000-4000-8000-000000000002'
                  AND model_id = (SELECT id FROM gateway.model WHERE code = 'video-minimax')
                """);
        assertThat(candidates()).hasSize(1);

        update("""
                UPDATE gateway.channel
                SET base_url = 'https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis'
                WHERE id = '84000000-0000-4000-8000-000000000002'
                """);
        // 公共 DashScope 端点对 Wan 路由有效，包括
        // 运维存下完整提交 URL 的情形。Kling 仍然只能用工作空间端点。
        assertThat(candidates()).hasSize(1);

        update("""
                UPDATE gateway.provider
                SET enabled = false
                WHERE id = '81000000-0000-4000-8000-000000000002'
                """);
        assertThat(rows()).hasSize(1);
        assertThat(candidates()).isEmpty();

        update("""
                UPDATE gateway.provider
                SET enabled = true
                WHERE id = '81000000-0000-4000-8000-000000000002'
                """);
        assertThat(candidates()).hasSize(1);

        update("""
                UPDATE gateway.package_instance_model_quota
                SET reserved_tokens = 60
                WHERE package_instance_id = '97000000-0000-4000-8000-000000000003'
                """);
        // 已购买的别名在其按模型的秒数耗尽之后仍然可见；
        // POST /v1/videos 才拥有预留/准入决策权。
        assertThat(rows()).hasSize(1);

        update("""
                UPDATE gateway.package_instance_model_quota
                SET reserved_tokens = 0
                WHERE package_instance_id = '97000000-0000-4000-8000-000000000003';
                UPDATE gateway.package_instance
                SET reserved_tokens = 60
                WHERE id = '97000000-0000-4000-8000-000000000003'
                """);
        // 套餐级余额同样如此：目录可见并不等于可花费。
        assertThat(rows()).hasSize(1);

        update("""
                UPDATE gateway.package_instance
                SET reserved_tokens = 0
                WHERE id = '97000000-0000-4000-8000-000000000003';
                UPDATE gateway.channel
                SET last_health_status = 'UNHEALTHY'
                WHERE id = '84000000-0000-4000-8000-000000000002'
                """);
        assertThat(rows()).hasSize(1);
    }

    /**
     * 能力读取只关心「这条别名指向哪个上游模型」，因此它与执行路径的
     * 严格程度**不同**：渠道被停用或健康检查失败之后，客户端依然要能
     * 看到这个模型能生成什么参数，否则一次上游抖动会让客户端的
     * 分辨率/时长选择器整个变空。
     *
     * <p>这里**不**断言唯一的行：{@code video-basic} 在种子里同时挂在
     * 百炼与天翼云上，而多路由正是能力要取交集的场景。断言的是那条
     * 公共 DashScope 路由始终在册。
     */
    @Test
    void catalogRoutesSurviveADisabledOrUnhealthyChannel() throws Exception {
        assertThat(catalogRoutes("video-basic"))
                .extracting(VideoRouteRepository.VideoCatalogRoute::upstreamModel)
                .contains("wan2.6-t2v");
        assertThat(routeTo("video-basic", "BAILIAN"))
                .isNotNull()
                .satisfies(route -> assertThat(route.upstreamModel()).isEqualTo("wan2.6-t2v"));

        update("""
                UPDATE gateway.channel
                SET enabled = false, last_health_status = 'UNHEALTHY',
                    encrypted_api_key = NULL
                WHERE id = '84000000-0000-4000-8000-000000000002'
                """);
        assertThat(routeTo("video-basic", "BAILIAN")).isNotNull();

        update("""
                UPDATE gateway.channel
                SET enabled = true, last_health_status = 'HEALTHY',
                    encrypted_api_key = decode('01', 'hex')
                WHERE id = '84000000-0000-4000-8000-000000000002'
                """);
        assertThat(routeTo("video-basic", "BAILIAN")).isNotNull();
    }

    /** 别名没有登记任何路由时能力无从谈起：读取必须返回空，而不是抛错。 */
    @Test
    void catalogRoutesAreEmptyForAnAliasWithNoMapping() {
        assertThat(catalogRoutes("video-unrouted")).isEmpty();
        assertThat(catalogRoutes(null)).isEmpty();
        assertThat(catalogRoutes("  ")).isEmpty();
    }

    /** 能力读取只投影厂商类型与上游模型名：没有凭证、地址或渠道 id。 */
    @Test
    void catalogRoutesCarryNoCredentialsOrChannelAddresses() {
        assertThat(catalogRoutes("video-basic")).allSatisfy(route -> {
            assertThat(route.providerType()).isNotBlank();
            assertThat(route.upstreamModel()).isNotBlank();
        });
        assertThat(VideoRouteRepository.VideoCatalogRoute.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("providerType", "upstreamModel");
    }

    private static VideoRouteRepository.VideoCatalogRoute routeTo(String alias, String provider) {
        return catalogRoutes(alias).stream()
                .filter(route -> provider.equals(route.providerType()))
                .findFirst()
                .orElse(null);
    }

    private static List<VideoRouteRepository.VideoCatalogRoute> catalogRoutes(String alias) {
        return repository.catalogRoutes(alias).collectList().block();
    }

    private static List<VideoRouteRepository.VideoModelCatalogRow> rows() {
        return repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID)
                .collectList()
                .block();
    }

    private static List<VideoGenerationAdapter.Channel> candidates() {
        ApiKeyService.ApiKeyContext key = new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-minimax"));
        return repository.candidates(key, "video-minimax", "[\"VIDEO\"]")
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
