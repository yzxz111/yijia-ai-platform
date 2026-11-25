package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yuzhihuihe.apiplatform.gateway.provider.BailianAdapter;
import com.yuzhihuihe.apiplatform.gateway.provider.DeepSeekAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateChannelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.ModelRouteConfiguration;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.SaveModelConfigurationCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelModelTestView;
import io.r2dbc.spi.ConnectionFactories;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 「测试模型」必须真的发一次请求，并且把「为什么不能用」说清楚。
 *
 * <p>这些用例用本机 HTTP 桩（而不是模拟适配器）跑完整链路：从库里解密密钥、拼出
 * {@code /chat/completions}、读上游应答、再写回渠道健康态。上一版连接测试只探
 * DNS/TCP/TLS，因此「密钥错的渠道也是 HEALTHY」这件事只有在这种端到端用例里才暴露得出来。
 *
 * <p>目录模型（别名 {@value #MODEL_CODE}）只在 {@link #migrate()} 里建一次：它是「平台
 * 上已有的模型」，而每个用例各建一条自己的渠道并挂上映射——这既是运营的真实顺序，
 * 也避免了同一个别名被反复创建所触发的唯一约束冲突。
 */
@Testcontainers
class ChannelModelTestServiceTest {

    private static final UUID BAILIAN_PROVIDER =
            UUID.fromString("81000000-0000-4000-8000-000000000002");
    private static final UUID TEST_ACTOR =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final String MODEL_CODE = "text-probe";
    private static final String UPSTREAM_MODEL = "qwen-turbo";
    private static final String AUDIO_MODEL_CODE = "audio-probe";
    private static final String AUDIO_UPSTREAM_MODEL = "qwen-audio-3.0-asr-flash";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static GatewayAdminService admin;
    static ChannelModelTestService modelTests;
    static DatabaseClient databaseClient;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        String url = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/" + POSTGRES.getDatabaseName();
        var connectionFactory = ConnectionFactories.get(url);
        databaseClient = DatabaseClient.create(connectionFactory);
        String key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        CredentialCipher cipher = new CredentialCipher(key);
        TransactionalOperator transactions =
                TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
        ObjectMapper mapper = new ObjectMapper();
        modelTests = new ChannelModelTestService(
                new GatewayAdminRepository(databaseClient),
                cipher,
                List.of(new BailianAdapter(WebClient.builder(), mapper),
                        new DeepSeekAdapter(WebClient.builder(), mapper)));
        admin = new GatewayAdminService(
                new GatewayAdminRepository(databaseClient),
                transactions,
                cipher,
                null,
                null,
                WebClient.builder(),
                modelTests,
                "localhost,127.0.0.1,::1");
        // 目录里的模型 + 挂在第一个渠道上的映射：后面每个用例只新建自己的渠道。
        createCatalogModel("bootstrap-catalog", "http://127.0.0.1:1");
    }

    @Test
    void reportsSuccessWithUpstreamTokensAndMarksTheChannelHealthy() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (StubUpstream upstream = StubUpstream.answering(
                200, """
                {"id":"chatcmpl-1","object":"chat.completion",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"pong"},
                             "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":7,"completion_tokens":1,"total_tokens":8}}
                """, authorization, body)) {
            ChannelModelTestView result = test(probeChannel("probe-ok", upstream.baseUrl()));

            assertThat(result.ok()).isTrue();
            assertThat(result.modelInvoked()).isTrue();
            assertThat(result.status()).isEqualTo("HEALTHY");
            assertThat(result.healthPersisted()).isTrue();
            assertThat(result.upstreamModel()).isEqualTo(UPSTREAM_MODEL);
            assertThat(result.latencyMs()).isNotNull();
            // 上游报的 token 数原样回给控制台：它是「这次真的调通了」的证据。
            assertThat(result.inputTokens()).isEqualTo(7L);
            assertThat(result.outputTokens()).isEqualTo(1L);
            // 用的是渠道里保存的密钥，且打的是 OpenAI 兼容路径与指定的上游模型名。
            assertThat(authorization.get()).isEqualTo("Bearer provider-secret");
            assertThat(body.get()).contains("\"model\":\"" + UPSTREAM_MODEL + "\"");
            assertThat(body.get()).contains("\"max_tokens\":1");
            assertThat(healthStatus(result.channelId())).isEqualTo("HEALTHY");
        }
    }

    @Test
    void rejectsAWrongKeyAndStopsRoutingToThatChannel() throws Exception {
        try (StubUpstream upstream = StubUpstream.answering(
                401, "{\"error\":{\"message\":\"invalid api key\"}}", new AtomicReference<>(),
                new AtomicReference<>())) {
            ChannelModelTestView result = test(probeChannel("probe-bad-key", upstream.baseUrl()));

            assertThat(result.ok()).isFalse();
            assertThat(result.reason()).isEqualTo("credential_rejected");
            assertThat(result.httpStatus()).isEqualTo(401);
            // 「上游原文」现在是上游自己那句话，而不是网关的状态码字符串。
            assertThat(result.upstreamMessage()).isEqualTo("invalid api key");
            // 这正是旧连接测试的盲区：地址通、TLS 通，但密钥是错的。
            assertThat(result.reachable()).isTrue();
            assertThat(result.status()).isEqualTo("UNHEALTHY");
            assertThat(healthStatus(result.channelId())).isEqualTo("UNHEALTHY");
        }
    }

    @Test
    void reportsAMissingUpstreamModelAsUnhealthy() throws Exception {
        try (StubUpstream upstream = StubUpstream.answering(
                404, "{\"error\":{\"code\":\"model_not_found\",\"message\":\"model not found\"}}",
                new AtomicReference<>(), new AtomicReference<>())) {
            ChannelModelTestView result = test(probeChannel("probe-bad-model", upstream.baseUrl()));

            assertThat(result.ok()).isFalse();
            // 上游原文提到了模型：这才是「模型名不对」，控制台的处置也就指向上游真实模型。
            assertThat(result.reason()).isEqualTo("model_not_found_on_provider");
            assertThat(result.upstreamMessage()).isEqualTo("model not found");
            assertThat(result.status()).isEqualTo("UNHEALTHY");
        }
    }

    @Test
    void doesNotBlameTheModelNameForAnUnexplained404() throws Exception {
        // 地址打错（少一段版本段）时上游回的也是 404，而且往往根本没有 JSON 错误体。
        try (StubUpstream upstream = StubUpstream.answering(
                404, "<html><body>404 Not Found</body></html>", new AtomicReference<>(),
                new AtomicReference<>())) {
            ChannelModelTestView result =
                    test(probeChannel("probe-wrong-path", upstream.baseUrl()));

            assertThat(result.ok()).isFalse();
            assertThat(result.reason()).isEqualTo("upstream_endpoint_not_found");
            assertThat(result.httpStatus()).isEqualTo(404);
            // 原始响应体刻意不保留：只剩状态码字符串，界面据此说「地址更像是错的」。
            assertThat(result.upstreamMessage()).isEqualTo("provider_http_404");
            assertThat(result.status()).isEqualTo("UNHEALTHY");
            assertThat(healthStatus(result.channelId())).isEqualTo("UNHEALTHY");
        }
    }

    @Test
    void reportsAnUpstream404ThatNeverMentionsTheModelAsAnEndpointProblem() throws Exception {
        // 上游确实回了 JSON，但那句话与模型无关：处置是改地址，不是改模型名。
        try (StubUpstream upstream = StubUpstream.answering(
                404, "{\"error\":{\"code\":\"NotFound\",\"message\":\"url path not found\"}}",
                new AtomicReference<>(), new AtomicReference<>())) {
            ChannelModelTestView result =
                    test(probeChannel("probe-path-json", upstream.baseUrl()));

            assertThat(result.reason()).isEqualTo("upstream_endpoint_not_found");
            assertThat(result.upstreamMessage()).isEqualTo("url path not found");
            assertThat(result.status()).isEqualTo("UNHEALTHY");
        }
    }

    @Test
    void refusesToChatProbeAnAliasThatIsNotAChatModel() throws Exception {
        // 天翼的音频别名就是这样：模型确实存在，但它不在 /chat/completions 上。
        // 旧行为会拿它去打对话端点、拿到 404、显示「上游没有这个模型」，并把渠道
        // 标成 UNHEALTHY——而音频路由要求渠道 HEALTHY，于是整条音频链路被堵死。
        AtomicReference<String> authorization = new AtomicReference<>();
        try (StubUpstream upstream = StubUpstream.answering(
                404, "{\"error\":{\"message\":\"model not found\"}}", authorization,
                new AtomicReference<>())) {
            UUID channelId = createChannel("probe-audio", upstream.baseUrl());
            attachAudioRoute(channelId);

            ChannelModelTestView result = admin
                    .testChannelModel(channelId, AUDIO_UPSTREAM_MODEL, false, TEST_ACTOR).block();

            assertThat(result.ok()).isFalse();
            assertThat(result.modelInvoked()).isFalse();
            assertThat(result.reason()).isEqualTo("alias_not_chat_capable");
            assertThat(result.upstreamModel()).isEqualTo(AUDIO_UPSTREAM_MODEL);
            // 关键：一次「打错端点」的测试不许改写健康态，否则渠道会被它永久钉在异常上。
            assertThat(result.healthPersisted()).isFalse();
            assertThat(healthStatus(channelId)).isEqualTo("UNKNOWN");
            // 而且它连请求都没发出去——上面那个桩本可以回一个「model not found」。
            assertThat(authorization.get()).isNull();
        }
    }

    @Test
    void keepsRoutingUsableButDegradedForATemporaryUpstreamFailure() throws Exception {
        try (StubUpstream upstream = StubUpstream.answering(
                429, "{\"error\":{\"message\":\"rate limited\"}}", new AtomicReference<>(),
                new AtomicReference<>())) {
            ChannelModelTestView result = test(probeChannel("probe-busy", upstream.baseUrl()));

            assertThat(result.ok()).isFalse();
            assertThat(result.reason()).isEqualTo("upstream_overloaded");
            // 上游忙不是「配置错了」：降到 DEGRADED 而不是把渠道从分流里摘掉。
            assertThat(result.status()).isEqualTo("DEGRADED");
            assertThat(healthStatus(result.channelId())).isEqualTo("DEGRADED");
        }
    }

    @Test
    void treatsAnErrorObjectInsideA200ResponseAsAFailure() throws Exception {
        try (StubUpstream upstream = StubUpstream.answering(
                200, "{\"error\":{\"message\":\"insufficient quota\"}}", new AtomicReference<>(),
                new AtomicReference<>())) {
            ChannelModelTestView result =
                    test(probeChannel("probe-body-error", upstream.baseUrl()));

            // 只看 HTTP 状态码会把这种应答记成通过——上游恰恰常用它报余额与开通问题。
            assertThat(result.ok()).isFalse();
            assertThat(result.reason()).isEqualTo("upstream_error_in_body");
            assertThat(result.upstreamMessage()).contains("insufficient quota");
            assertThat(result.status()).isEqualTo("UNHEALTHY");
        }
    }

    @Test
    void refusesToCallAnUnreachableAddressAndSaysSo() {
        ChannelModelTestView result = test(probeChannel("probe-unreachable", "https://127.0.0.1:1"));

        assertThat(result.ok()).isFalse();
        assertThat(result.reachable()).isFalse();
        assertThat(result.reason()).isEqualTo("upstream_unreachable");
        assertThat(result.status()).isEqualTo("UNHEALTHY");
    }

    @Test
    void reportsAMissingCredentialWithoutCallingAnything() throws Exception {
        try (StubUpstream upstream = StubUpstream.answering(
                200, "{\"choices\":[]}", new AtomicReference<>(), new AtomicReference<>())) {
            UUID channelId = createChannel("probe-no-key", upstream.baseUrl());
            attachRoute(channelId, UPSTREAM_MODEL);
            databaseClient.sql("UPDATE gateway.channel SET encrypted_api_key = NULL WHERE id = :id")
                    .bind("id", channelId).then().block();

            ChannelModelTestView result = test(channelId);

            assertThat(result.credentialConfigured()).isFalse();
            assertThat(result.modelInvoked()).isFalse();
            assertThat(result.reason()).isEqualTo("channel_credential_required");
            assertThat(result.status()).isEqualTo("UNHEALTHY");
        }
    }

    @Test
    void explainsWhenTheRequestedUpstreamModelIsNotMappedOnThisChannel() throws Exception {
        try (StubUpstream upstream = StubUpstream.answering(
                200, "{\"choices\":[]}", new AtomicReference<>(), new AtomicReference<>())) {
            UUID channelId = createChannel("probe-unknown-model", upstream.baseUrl());
            attachRoute(channelId, UPSTREAM_MODEL);

            ChannelModelTestView result = admin
                    .testChannelModel(channelId, "never-mapped", false, TEST_ACTOR).block();

            assertThat(result.modelInvoked()).isFalse();
            assertThat(result.reason()).isEqualTo("model_mapping_missing");
            // 仍然把该渠道上真实的映射列表带回来，运营可以照着改。
            assertThat(result.models()).extracting("upstreamModel").contains(UPSTREAM_MODEL);
        }
    }

    @Test
    void fallsBackToTheFirstUsableMappingWhenNoModelIsRequested() throws Exception {
        try (StubUpstream upstream = StubUpstream.answering(
                200, "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}",
                new AtomicReference<>(), new AtomicReference<>())) {
            UUID channelId = createChannel("probe-auto", upstream.baseUrl());
            attachRoute(channelId, UPSTREAM_MODEL);

            ChannelModelTestView result =
                    admin.testChannelModel(channelId, null, false, TEST_ACTOR).block();

            assertThat(result.ok()).isTrue();
            assertThat(result.upstreamModel()).isEqualTo(UPSTREAM_MODEL);
            assertThat(result.modelCode()).isEqualTo(MODEL_CODE);
        }
    }

    @Test
    void connectivityOnlyStaysHonestAboutNotHavingTestedTheModel() throws Exception {
        try (StubUpstream upstream = StubUpstream.answering(
                200, "{\"choices\":[]}", new AtomicReference<>(), new AtomicReference<>())) {
            UUID channelId = probeChannel("probe-connectivity", upstream.baseUrl());

            ChannelModelTestView result =
                    modelTests.test(channelId, UPSTREAM_MODEL, true).block();

            assertThat(result.reachable()).isTrue();
            assertThat(result.modelInvoked()).isFalse();
            // 「地址通」不是「模型可用」：ok 必须为假，否则界面会把一次可达性探测
            // 报成测试通过——那正是这次改造要消灭的假阳性。
            assertThat(result.ok()).isFalse();
            assertThat(result.modelOk()).isFalse();
            // 可达性探测不写健康态：它证明不了模型可用，不该把一个渠道标成 HEALTHY。
            assertThat(result.healthPersisted()).isFalse();
            assertThat(healthStatus(channelId)).isEqualTo("UNKNOWN");
        }
    }

    @Test
    void writesAnAuditRowForEveryRealCall() throws Exception {
        try (StubUpstream upstream = StubUpstream.answering(
                200, "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}",
                new AtomicReference<>(), new AtomicReference<>())) {
            ChannelModelTestView result = test(probeChannel("probe-audit", upstream.baseUrl()));

            Long audits = databaseClient.sql("""
                            SELECT count(*) AS rows FROM gateway.admin_operation_audit
                            WHERE actor_user_id = :actor AND action = 'GATEWAY_CHANNEL_MODEL_TESTED'
                              AND resource_id = :resource
                            """)
                    .bind("actor", TEST_ACTOR)
                    .bind("resource", result.channelId().toString())
                    .map((row, metadata) -> row.get("rows", Long.class))
                    .one().block();
            assertThat(audits).isEqualTo(1L);
        }
    }

    private static String healthStatus(UUID channelId) {
        return databaseClient.sql("SELECT last_health_status FROM gateway.channel WHERE id = :id")
                .bind("id", channelId)
                .map((row, metadata) -> row.get("last_health_status", String.class))
                .one().block();
    }

    private ChannelModelTestView test(UUID channelId) {
        return admin.testChannelModel(channelId, UPSTREAM_MODEL, false, TEST_ACTOR).block();
    }

    /** 建一条新渠道并挂上目录模型的映射：用例真正要测的那条「渠道 ← 别名」。 */
    private UUID probeChannel(String code, String baseUrl) {
        UUID channelId = createChannel(code, baseUrl);
        attachRoute(channelId, UPSTREAM_MODEL);
        return channelId;
    }

    /**
     * 建渠道时必须用 {@code LOCAL}：本机桩地址会被 SSRF 守卫按私网地址拒绝，而
     * {@code LOCAL} 是它允许探测本机地址的唯一口径。
     */
    private UUID createChannel(String code, String baseUrl) {
        return admin.createChannel(
                        new CreateChannelCommand(
                                BAILIAN_PROVIDER, code, "Channel " + code, "LOCAL", baseUrl,
                                "provider-secret", true, 10, 100, 30000, 600, 10),
                        TEST_ACTOR)
                .block()
                .id();
    }

    /**
     * 把目录模型挂到这条渠道上。
     *
     * <p>{@code id} 必须给已存在的模型 id：命令里 {@code id == null} 的含义是「新建模型」，
     * 而别名 {@value #MODEL_CODE} 已经存在，那样会撞上 {@code model_code_key} 唯一约束，
     * 被包装成 409 {@code model_configuration_conflict}。控制台的「编辑模型 → 保存配置」
     * 走的正是这条带 id 的更新路径。
     */
    private void attachRoute(UUID channelId, String upstreamModel) {
        admin.saveModelConfiguration(
                        new SaveModelConfigurationCommand(
                                modelId(MODEL_CODE), MODEL_CODE, "Text Probe", Set.of(MODEL_CODE),
                                Set.of("TEXT"), 8192, 2048, false, true, 60, 5,
                                new ModelRouteConfiguration(
                                        channelId, MODEL_CODE, BAILIAN_PROVIDER, "LOCAL",
                                        baseUrlOf(channelId), "provider-secret", upstreamModel,
                                        true, 100, 100, 30000, 600, 10)),
                        TEST_ACTOR)
                .block();
    }

    /**
     * 建一个**音频**别名并挂到该渠道上。
     *
     * <p>它存在的意义正是「不是每条别名都该被对话探测打」：音频模型确实存在，
     * 只是不在 {@code /chat/completions} 上。能力校验在这里没有注入（
     * {@code ProviderCapabilityCatalog} 为 null），因为本用例测的是探测的选路，
     * 不是保存逻辑的校验。
     */
    private void attachAudioRoute(UUID channelId) {
        admin.saveModelConfiguration(
                        new SaveModelConfigurationCommand(
                                null, AUDIO_MODEL_CODE, "Audio Probe", Set.of(AUDIO_MODEL_CODE),
                                Set.of("AUDIO_TRANSCRIBE"), 1, 1, false, true, 60, 4,
                                new ModelRouteConfiguration(
                                        channelId, AUDIO_MODEL_CODE, BAILIAN_PROVIDER, "LOCAL",
                                        baseUrlOf(channelId), "provider-secret",
                                        AUDIO_UPSTREAM_MODEL, true, 100, 100, 30000, 600, 10)),
                        TEST_ACTOR)
                .block();
    }

    private UUID modelId(String modelCode) {
        return databaseClient.sql("SELECT id FROM gateway.model WHERE code = :code")
                .bind("code", modelCode)
                .map((row, metadata) -> row.get("id", UUID.class))
                .one().block();
    }

    private String baseUrlOf(UUID channelId) {
        return databaseClient.sql("SELECT base_url FROM gateway.channel WHERE id = :id")
                .bind("id", channelId)
                .map((row, metadata) -> row.get("base_url", String.class))
                .one().block();
    }

    /**
     * 目录模型只建一次（{@code id == null} 表示新建模型）；它自带的渠道地址不可达，
     * 不会被任何用例使用——每个用例都会新建自己的渠道并挂上映射。
     */
    private static void createCatalogModel(String channelCode, String baseUrl) {
        admin.saveModelConfiguration(
                        new SaveModelConfigurationCommand(
                                null, MODEL_CODE, "Text Probe", Set.of(MODEL_CODE),
                                Set.of("TEXT"), 8192, 2048, false, true, 60, 5,
                                new ModelRouteConfiguration(
                                        null, MODEL_CODE, BAILIAN_PROVIDER, "LOCAL", baseUrl,
                                        "provider-secret", UPSTREAM_MODEL, true, 100, 100,
                                        30000, 600, 10)),
                        TEST_ACTOR)
                .block();
    }

    /** 本机 HTTP 桩：把最后一次请求的 Authorization 与请求体留下来给断言。 */
    private static final class StubUpstream implements AutoCloseable {

        private final HttpServer server;

        private StubUpstream(HttpServer server) {
            this.server = server;
        }

        static StubUpstream answering(
                int status,
                String payload,
                AtomicReference<String> authorization,
                AtomicReference<String> body)
                throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                body.set(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return new StubUpstream(server);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
