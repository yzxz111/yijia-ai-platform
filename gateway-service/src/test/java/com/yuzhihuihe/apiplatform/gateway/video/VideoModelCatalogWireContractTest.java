package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.routing.CredentialCipher;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 客户端**真正收到**的那个字节流里有没有参数。
 *
 * <p>这条测试刻意不用 mock：真实 SQL（Testcontainers + Flyway 迁移）、
 * 真实适配器（{@link BailianVideoGenerationAdapter} 的
 * {@code submissionProfile}）、真实目录服务、真实控制器与真实 JSON 序列化。
 * 单元测试能证明"服务算出了参数"，但只有端到端才能证明
 * "客户端看得见参数"——中间任何一环漏掉字段（响应记录少一个组件、
 * 序列化被裁掉、路径被某个过滤器拦掉）都会在这里暴露。
 *
 * <p>它同时钉住两条**不得泄露**的性质：响应里不出现渠道地址、
 * 凭据、渠道/供应商 id 或上游模型名。参数是厂商契约的边界，
 * 可以下发；路由配置不行。
 */
@Testcontainers
class VideoModelCatalogWireContractTest {

    private static final UUID TENANT_ID =
            UUID.fromString("98000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("98000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID =
            UUID.fromString("98000000-0000-4000-8000-000000000003");
    private static final UUID API_KEY_ID =
            UUID.fromString("98000000-0000-4000-8000-000000000004");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static WebTestClient client;
    static ApiKeyService.ApiKeyContext context;

    @BeforeAll
    static void migrateSeedAndMountTheRealController() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // V110 合并模型：video-minimax 是客户端唯一的视频入口，480P 由内部
        // video-budget（wan2.5）履约、720P 由 video-minimax（wan2.6）履约。
        // 两个档位的内部别名走两个不同的上游模型，参数口径必须不同：
        //   video-budget 480P → wan2.5-t2v-preview（时长只能取 5/10，无 4:3 文生画幅）
        //   video-minimax 720P → wan2.6-t2v（时长 2–15 连续，有 4:3）
        execute("""
                INSERT INTO gateway.channel(
                    id, provider_id, code, name, environment, base_url, encrypted_api_key,
                    enabled, priority, weight, timeout_ms, last_health_status)
                SELECT '98000000-0000-4000-8000-000000000010', provider.id,
                       'wire-contract-video', 'Wire Contract Video', 'PRODUCTION',
                       'https://workspace-wire.cn-beijing.maas.aliyuncs.com',
                       decode('01', 'hex'), true, 100, 100, 60000, 'HEALTHY'
                FROM gateway.provider provider
                WHERE provider.code = 'bailian';

                INSERT INTO gateway.channel_model(
                    id, channel_id, model_id, upstream_model, enabled, capabilities)
                SELECT gen_random_uuid(), '98000000-0000-4000-8000-000000000010',
                       model.id, route.upstream_model, true, '["VIDEO"]'::jsonb
                FROM (VALUES
                    ('video-budget',  'wan2.5-t2v-preview'),
                    ('video-minimax', 'wan2.6-t2v')
                ) AS route(model_code, upstream_model)
                JOIN gateway.model model ON model.code = route.model_code;

                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, package_kind,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, status, starts_at, activated_at, expires_at, usage_dimension)
                VALUES ('98000000-0000-4000-8000-000000000003', gen_random_uuid(),
                        gen_random_uuid(), '98000000-0000-4000-8000-000000000001',
                        '98000000-0000-4000-8000-000000000002', 'video-wire-contract',
                        gen_random_uuid(), 'VIDEO_WIRE', 'Video Wire', 'STANDARD',
                        12900, 'CNY',
                        '[{"modelCode":"video-minimax","modelTokenQuota":60,"capabilities":["VIDEO"]},
                          {"modelCode":"video-budget","modelTokenQuota":60,"capabilities":["VIDEO"]}]'::jsonb,
                        60, 2, 20, 60, 2, 30, 'ACTIVE', now(), now(),
                        now() + interval '30 days', 'VIDEO_SECOND');
                """);

        // 目录只读路由的厂商类型与上游模型名，从不解密凭据——
        // 这正是它能与执行路径解耦的原因之一。
        CredentialCipher cipher = new CredentialCipher("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=");
        VideoRouteRepository repository = new VideoRouteRepository(
                DatabaseClient.create(ConnectionFactories.get(r2dbcUrl())), cipher);
        VideoAdapterRegistry registry = new VideoAdapterRegistry(List.of(
                new BailianVideoGenerationAdapter(WebClient.builder(), new ObjectMapper())));
        VideoModelCatalogService catalog = new VideoModelCatalogService(repository, registry);

        context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID,
                Set.of("video-minimax", "video-budget"));
        client = WebTestClient.bindToController(new VideoModelsController(catalog))
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, context);
                    return chain.filter(exchange);
                })
                .build();
    }

    /** 分辨率、时长、画幅是否真的出现在客户端拿到的 JSON 里。 */
    @Test
    void theClientReceivesTheGeneratableParametersForEveryAlias() throws Exception {
        JsonNode data = fetch().path("data");
        assertThat(data.isArray()).isTrue();
        // V110：客户端目录里只有一个合并模型。
        assertThat(data).hasSize(1);

        JsonNode minimax = alias(data, "video-minimax");
        assertThat(minimax.path("displayName").asText()).isEqualTo("MiniMax");
        JsonNode text = mode(minimax, "TEXT_TO_VIDEO");
        // 480P（内部 video-budget）与 720P（内部 video-minimax）都是可提交档位。
        assertThat(text.path("resolutions"))
                .extracting(JsonNode::asText)
                .containsExactly("480P", "720P");
        // 跨档位保守交集：Wan 2.5 只收 5 或 10 秒、Wan 2.6 收 2–15 秒，
        // 合并后是 5–10 的连续区间（一条连续路由会让枚举语义失效）。
        assertThat(text.path("durationSeconds").path("min").asInt()).isEqualTo(5);
        assertThat(text.path("durationSeconds").path("max").asInt()).isEqualTo(10);
        assertThat(text.path("durationSeconds").path("options")).isEmpty();
        // 480P 文生视频只收三种画幅：4:3 必须不在其中（跨档位交集）。
        assertThat(text.path("aspectRatios"))
                .extracting(JsonNode::asText)
                .containsExactly("16:9", "1:1", "9:16");

        // 顶层便利字段与图像模态也要在。
        assertThat(minimax.path("parameters").path("soldResolutionTier").asText())
                .isEqualTo("720P");
        assertThat(minimax.path("parameters").path("platformMaxSeconds").asInt()).isEqualTo(60);
        assertThat(minimax.path("parameters").path("imageInputSupported").asBoolean()).isTrue();
        assertThat(mode(minimax, "IMAGE_TO_VIDEO").path("resolutions"))
                .extracting(JsonNode::asText)
                .containsExactly("480P", "720P");
        // 上游档位是集合（升序，故 1080P 在 720P 之前），
        // 可提交档位才是与套餐持有的档位映射的交集。
        assertThat(minimax.path("parameters").path("upstreamResolutionTiers"))
                .extracting(JsonNode::asText)
                .containsExactly("1080P", "720P");
    }

    /**
     * 整个响应体逐字段相等。
     *
     * <p>快照而不是零散断言，是因为这份响应就是客户端的契约：
     * 删字段、改字段名、多下发内部字段都必须是一次**有意的**改动
     * （改快照），而不是一次无人察觉的漂移。下面这段 JSON 是
     * {@code docs/视频模型能力接口文档.md} 里示例的真实来源。
     */
    @Test
    void theWholeBodyMatchesTheDocumentedContract() {
        assertThat(rawBody()).isEqualTo("""
                {"object":"list","data":[\
                {"id":"video-minimax","displayName":"MiniMax","resolutionTier":"720P",\
                "usageDimension":"VIDEO_SECOND","parameters":{\
                "modes":[\
                {"mode":"TEXT_TO_VIDEO","resolutions":["480P","720P"],\
                "durationSeconds":{"min":5,"max":10,"options":[]},\
                "aspectRatios":["16:9","1:1","9:16"]},\
                {"mode":"IMAGE_TO_VIDEO","resolutions":["480P","720P"],\
                "durationSeconds":{"min":5,"max":10,"options":[]},\
                "aspectRatios":["16:9","1:1","3:4","4:3","9:16"]}],\
                "soldResolutionTier":"720P",\
                "upstreamResolutionTiers":["1080P","720P"],\
                "durationSeconds":{"min":5,"max":10,"options":[]},\
                "platformMaxSeconds":60,\
                "aspectRatios":["16:9","1:1","3:4","4:3","9:16"],\
                "imageInputSupported":true,"withAudioSupported":false,"operator":[]}}]}""");
    }

    /**
     * 渠道坏掉不会让客户端的选择器变空。
     *
     * <p>这是"能力与渠道健康解耦"这条设计的**端到端**证据：把渠道停用、
     * 健康置为 UNHEALTHY、凭据清空、映射停用之后，`parameters` 必须逐字节
     * 不变（只有提交时才会拿到 503）。少了这条，目录很容易在后续重构里
     * 悄悄跟着健康状态走——那正是"上游抖动一次，客户端的时长选项全消失"
     * 的成因。
     */
    @Test
    void parametersSurviveADisabledUnhealthyChannelWithoutCredentials() throws Exception {
        String healthy = rawBody();
        try {
            execute("""
                    UPDATE gateway.channel
                    SET enabled = false, last_health_status = 'UNHEALTHY',
                        encrypted_api_key = NULL,
                        base_url = 'https://nothing-configured.example.com'
                    WHERE id = '98000000-0000-4000-8000-000000000010';

                    UPDATE gateway.channel_model
                    SET enabled = false
                    WHERE channel_id = '98000000-0000-4000-8000-000000000010';
                    """);

            assertThat(rawBody()).isEqualTo(healthy);
        } finally {
            execute("""
                    UPDATE gateway.channel
                    SET enabled = true, last_health_status = 'HEALTHY',
                        encrypted_api_key = decode('01', 'hex'),
                        base_url = 'https://workspace-wire.cn-beijing.maas.aliyuncs.com'
                    WHERE id = '98000000-0000-4000-8000-000000000010';

                    UPDATE gateway.channel_model
                    SET enabled = true
                    WHERE channel_id = '98000000-0000-4000-8000-000000000010';
                    """);
        }
        // 复位之后仍然一致：这条用例不留副作用给其它用例。
        assertThat(rawBody()).isEqualTo(healthy);
    }

    /**
     * 参数可以下发，路由配置不行。
     *
     * <p>直接在**原始响应文本**上断言，而不是在解析后的字段上：
     * 任何一层的调试字段、异常回显或序列化意外都会在这里现形。
     */
    @Test
    void theWireBodyLeaksNoRoutingConfiguration() {
        String body = rawBody();

        assertThat(body)
                .doesNotContain("wan2.6-t2v")
                .doesNotContain("wan2.5-t2v-preview")
                .doesNotContain("kling/")
                .doesNotContain("workspace-wire.cn-beijing.maas.aliyuncs.com")
                .doesNotContain("baseUrl")
                .doesNotContain("apiKey")
                .doesNotContain("encrypted")
                .doesNotContain("providerId")
                .doesNotContain("channelId")
                .doesNotContain("upstreamModel")
                .doesNotContain("98000000-0000-4000-8000-000000000010");

        // 而参数必须以固定的字段名出现，客户端才可能解析。
        assertThat(body)
                .contains("\"parameters\"")
                .contains("\"soldResolutionTier\"")
                .contains("\"durationSeconds\"")
                .contains("\"resolutions\"")
                .contains("\"aspectRatios\"")
                .contains("\"modes\"")
                .contains("\"platformMaxSeconds\"");
    }

    private static JsonNode fetch() throws Exception {
        byte[] body = client.get().uri("/v1/video-models").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().exists("x-request-id")
                .expectBody()
                .returnResult()
                .getResponseBody();
        return new ObjectMapper().readTree(body);
    }

    private static String rawBody() {
        return new String(client.get().uri("/v1/video-models").exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody());
    }

    private static JsonNode alias(JsonNode data, String id) {
        for (JsonNode entry : data) {
            if (id.equals(entry.path("id").asText())) {
                return entry;
            }
        }
        throw new AssertionError("alias missing from the client catalogue: " + id);
    }

    private static JsonNode mode(JsonNode alias, String mode) {
        for (JsonNode entry : alias.path("parameters").path("modes")) {
            if (mode.equals(entry.path("mode").asText())) {
                return entry;
            }
        }
        throw new AssertionError("mode missing for " + alias.path("id").asText() + ": " + mode);
    }

    private static String r2dbcUrl() {
        return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
                + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName();
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
