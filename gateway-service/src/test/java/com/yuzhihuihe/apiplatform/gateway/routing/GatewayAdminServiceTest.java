package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.image.ImageAdapterRegistry;
import com.yuzhihuihe.apiplatform.gateway.audio.AudioAdapterRegistry;
import com.yuzhihuihe.apiplatform.gateway.provider.BailianAdapter;
import com.yuzhihuihe.apiplatform.gateway.provider.DeepSeekAdapter;
import com.yuzhihuihe.apiplatform.gateway.provider.TianYiYunChatAdapter;
import com.yuzhihuihe.apiplatform.gateway.video.VideoAdapterRegistry;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.AdminListQuery;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateChannelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateModelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateProviderCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.ModelRouteConfiguration;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.OnboardRouteCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.OnboardRoutesCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.SaveModelConfigurationCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.UpdateChannelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.UpdateModelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelTestView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ModelView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.PageResult;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ProviderDeletionView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ProviderView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.RouteOnboardingResult;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.RoutesOnboardingView;
import com.yuzhihuihe.apiplatform.gateway.routing.PlatformPackageModelClient.ModelReferenceView;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.SoftAssertions;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Mono;

@Testcontainers
class GatewayAdminServiceTest {

    private static final UUID BAILIAN_PROVIDER =
            UUID.fromString("81000000-0000-4000-8000-000000000002");
    private static final UUID TEST_ACTOR = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static GatewayAdminService admin;
    static GatewayAdminService guardedAdmin;
    static DatabaseClient databaseClient;
    static PlatformPackageModelClient packageModels;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway").schemas("gateway")
                .locations("classpath:db/migration").load().migrate();
        String url = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/" + POSTGRES.getDatabaseName();
        var connectionFactory = ConnectionFactories.get(url);
        String key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        databaseClient = DatabaseClient.create(connectionFactory);
        packageModels = mock(PlatformPackageModelClient.class);
        TransactionalOperator transactions =
                TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
        admin = new GatewayAdminService(
                new GatewayAdminRepository(databaseClient),
                transactions,
                new CredentialCipher(key),
                packageModels);
        // 带能力目录的实例：用于验证「保存路由时的能力守卫」确实生效。
        ObjectMapper mapper = new ObjectMapper();
        ImageAdapterRegistry images = mock(ImageAdapterRegistry.class);
        VideoAdapterRegistry videos = mock(VideoAdapterRegistry.class);
        for (String type : List.of("TIANYIYUN", "BAILIAN", "MOCK")) {
            when(images.supportsProviderType(type)).thenReturn(true);
            when(videos.supportsProviderType(type)).thenReturn(true);
        }
        // 音频适配器注册表不再是 null：接入提示现在按**能力**分派到各自的维度
        // （视频 → VIDEO_SECOND，音频 → AUDIO_SECOND），因此一个不认识音频的目录
        // 会让音频别名在接入时就被拒，而本类要断言的正是"接进去了但还不能卖"
        // 那条提示。只认 BAILIAN，其余类型仍然拒绝音频——负向行为照样可以测。
        AudioAdapterRegistry audio = mock(AudioAdapterRegistry.class);
        when(audio.supportsProviderType("BAILIAN")).thenReturn(true);
        when(audio.capabilitiesOf("BAILIAN")).thenReturn(
                Set.of("AUDIO_TRANSCRIBE", "AUDIO_SYNTHESIS", "LIP_SYNC"));
        guardedAdmin = new GatewayAdminService(
                new GatewayAdminRepository(databaseClient),
                transactions,
                new CredentialCipher(key),
                packageModels,
                new ProviderCapabilityCatalog(
                        List.of(new BailianAdapter(WebClient.builder(), mapper),
                                new DeepSeekAdapter(WebClient.builder(), mapper),
                                new TianYiYunChatAdapter(WebClient.builder(), mapper)),
                        images,
                        videos,
                        audio),
                WebClient.builder());
    }

    @Test
    void createsStableAliasTransactionallyAndEncryptsChannelCredentialAtRest() throws Exception {
        ModelView model = createModel(new CreateModelCommand(
                "text-test", "Text Test", Set.of("TEXT"), 8192, 2048,
                false, true, 20, 2)).block();
        String secret = "plain-provider-secret";
        ChannelView channel = createChannel(
                new CreateChannelCommand(
                        UUID.fromString("81000000-0000-4000-8000-000000000002"),
                        "bailian-test", "Bailian Test", "SANDBOX", "https://example.test/v1",
                        secret, true, 10, 100, 30000, 20, 2)).block();

        assertThat(model.code()).isEqualTo("text-test");
        assertThat(model.routingStrategy()).isEqualTo("DIRECT");
        assertThat(channel.credentialConfigured()).isTrue();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        SELECT channel.encrypted_api_key,
                               EXISTS (SELECT 1 FROM gateway.model_alias WHERE model_id = ? AND alias = 'text-test')
                        FROM gateway.channel channel WHERE channel.id = ?
                        """)) {
            statement.setObject(1, model.id());
            statement.setObject(2, channel.id());
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                byte[] ciphertext = result.getBytes(1);
                assertThat(ciphertext).isNotEmpty();
                assertThat(new String(ciphertext, StandardCharsets.UTF_8)).doesNotContain(secret);
                assertThat(result.getBoolean(2)).isTrue();
            }
        }
    }

    @Test
    void savesModelChannelAndRouteTogetherWhilePreservingAnOmittedCredential() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = "unified-model-" + suffix;
        String secret = "unified-provider-secret-" + suffix;
        ModelView created = admin.saveModelConfiguration(
                new SaveModelConfigurationCommand(
                        null, code, "Unified Model", Set.of(code, code + "-alias"),
                        Set.of("TEXT"), 16384, 4096, false, true, 40, 4,
                        new ModelRouteConfiguration(
                                null, code, BAILIAN_PROVIDER, "SANDBOX",
                                "https://example.test/v1", secret, "upstream-v1",
                                true, 80, 70, 45000, 120, 12)),
                TEST_ACTOR).block();

        assertThat(created).isNotNull();
        assertThat(created.aliases()).containsExactlyInAnyOrder(code, code + "-alias");
        assertThat(created.routes()).hasSize(1);
        assertThat(created.routes().getFirst().providerCode()).isEqualTo("bailian");
        assertThat(created.routes().getFirst().upstreamModel()).isEqualTo("upstream-v1");
        UUID channelId = created.routes().getFirst().channelId();
        byte[] originalCiphertext = encryptedCredential(channelId);
        assertThat(originalCiphertext).isNotEmpty();
        assertThat(new String(originalCiphertext, StandardCharsets.UTF_8)).doesNotContain(secret);

        ModelView updated = admin.saveModelConfiguration(
                new SaveModelConfigurationCommand(
                        created.id(), null, "Unified Model Updated", Set.of(code + "-alias-2"),
                        Set.of("TEXT"), 32768, 8192, true, true, 60, 6,
                        new ModelRouteConfiguration(
                                channelId, code, BAILIAN_PROVIDER, "PRODUCTION",
                                "https://example.test/v2", null, "upstream-v2",
                                true, 90, 60, 60000, 180, 18)),
                TEST_ACTOR).block();

        assertThat(updated.displayName()).isEqualTo("Unified Model Updated");
        assertThat(updated.aliases()).containsExactlyInAnyOrder(code, code + "-alias-2");
        assertThat(updated.routes().getFirst().upstreamModel()).isEqualTo("upstream-v2");
        assertThat(updated.routes().getFirst().baseUrl()).isEqualTo("https://example.test/v2");
        assertThat(encryptedCredential(channelId)).isEqualTo(originalCiphertext);
    }

    @Test
    void rejectsSavingARouteWhoseProviderTypeCannotServeTheModelCapabilities() throws Exception {
        // 用 DEEPSEEK 而不是 TIANYIYUN：TianYiYunChatAdapter 落地后，天翼确实
        // 能服务读图模型，因此它不再是「不能服务」的例子。被守的不变量没变——
        // 一个没有 IMAGE 能力的供应商类型必须在这一步被拒，
        // 而不是写进去再让每次请求 503。
        UUID deepseek = providerIdByCode("deepseek");
        String code = "vision-on-deepseek-" + UUID.randomUUID().toString().substring(0, 8);

        assertThatThrownBy(() -> guardedAdmin.saveModelConfiguration(
                new SaveModelConfigurationCommand(
                        null, code, "Vision On DeepSeek", Set.of(code),
                        Set.of("TEXT", "IMAGE"), 16384, 4096, false, true, 40, 4,
                        new ModelRouteConfiguration(
                                null, code, deepseek, "PRODUCTION",
                                "https://api.deepseek.com/v1", "secret", "deepseek-chat",
                                true, 200, 100, 60000, 60, 10)),
                TEST_ACTOR).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason())
                            .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);
                });

        // 被拒之后不能留下任何半成品：模型、别名、渠道映射都不该写进去。
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "SELECT count(*) FROM gateway.model WHERE code = ?")) {
            statement.setString(1, code);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    @Test
    void allowsTheSameModelOnAProviderTypeThatCanReadImages() throws Exception {
        String code = "vision-on-bailian-" + UUID.randomUUID().toString().substring(0, 8);

        ModelView created = guardedAdmin.saveModelConfiguration(
                new SaveModelConfigurationCommand(
                        null, code, "Vision On Bailian", Set.of(code),
                        Set.of("TEXT", "IMAGE"), 16384, 4096, false, true, 40, 4,
                        new ModelRouteConfiguration(
                                null, code, BAILIAN_PROVIDER, "PRODUCTION",
                                "https://ai.ctaigw.cn/v1", "secret", "Qwen2.5-VL-72B-Instruct",
                                true, 200, 100, 60000, 60, 10)),
                TEST_ACTOR).block();

        assertThat(created).isNotNull();
        assertThat(created.routes().getFirst().upstreamModel())
                .isEqualTo("Qwen2.5-VL-72B-Instruct");
    }

    @Test
    void bulkOnboardingWritesMappingsAndRejectsCapabilityMismatch() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ChannelView bailian = guardedAdmin.createChannel(new CreateChannelCommand(
                BAILIAN_PROVIDER, "onboard-bailian-" + suffix, "Onboard Bailian", "SANDBOX",
                "https://ai.ctaigw.cn/v1", "secret-" + suffix, true, 210, 100, 60000, 60, 10),
                TEST_ACTOR)
                .block();
        ChannelView tianyi = guardedAdmin.createChannel(new CreateChannelCommand(
                providerIdByCode("tianyi"), "onboard-tianyi-" + suffix, "Onboard Tianyi",
                "PRODUCTION", "https://ai.ctaigw.cn/v1", "secret-" + suffix, true, 5, 100,
                60000, 60, 10),
                TEST_ACTOR)
                .block();

        // BAILIAN 下：读图与生图都能接。
        RoutesOnboardingView ok = guardedAdmin.onboardRoutes(
                bailian.id(),
                new OnboardRoutesCommand(List.of(
                        new OnboardRouteCommand("vision-basic", "Qwen2.5-VL-72B-Instruct",
                                true, null, null),
                        new OnboardRouteCommand("image-basic", "doubao-seedream-5.0-pro",
                                true, null, null))))
                .block();

        assertThat(ok).isNotNull();
        assertThat(ok.routes())
                .extracting(RouteOnboardingResult::status)
                .containsOnly("CREATED");
        assertThat(ok.routes())
                .extracting(RouteOnboardingResult::upstreamModel)
                .containsExactlyInAnyOrder("Qwen2.5-VL-72B-Instruct", "doubao-seedream-5.0-pro");

        // DEEPSEEK 下：读图模型必须被拒，而不是写进去再让请求 503。
        // 这里不用 TIANYIYUN：TianYiYunChatAdapter 落地后天翼真的能读图了，
        // 因此它不再是「能力不匹配」的例子（正面用例见下）。
        ChannelView deepseek = guardedAdmin.createChannel(new CreateChannelCommand(
                providerIdByCode("deepseek"), "onboard-deepseek-" + suffix,
                "Onboard DeepSeek", "PRODUCTION", "https://api.deepseek.com/v1",
                "secret-" + suffix, true, 5, 100, 60000, 60, 10),
                TEST_ACTOR)
                .block();

        RoutesOnboardingView rejected = guardedAdmin.onboardRoutes(
                deepseek.id(),
                new OnboardRoutesCommand(List.of(
                        new OnboardRouteCommand("vision-basic", "Qwen2.5-VL-72B-Instruct",
                                true, null, null))))
                .block();

        assertThat(rejected).isNotNull();
        assertThat(rejected.routes()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("REJECTED");
            assertThat(result.reason())
                    .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);
        });

        // TIANYIYUN 下：同样的读图模型现在**必须被接受**。这是补上
        // TianYiYunChatAdapter 的意义所在，也是这次改动唯一可能悄悄退化的地方：
        // 若适配器 bean 没有被注册进能力目录，这里会退回 REJECTED。
        RoutesOnboardingView accepted = guardedAdmin.onboardRoutes(
                tianyi.id(),
                new OnboardRoutesCommand(List.of(
                        new OnboardRouteCommand("vision-basic", "Qwen2.5-VL-72B-Instruct",
                                true, null, null))))
                .block();

        assertThat(accepted).isNotNull();
        assertThat(accepted.routes()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("CREATED");
            assertThat(result.reason()).isNull();
        });
    }

    @Test
    void bulkOnboardingNotesAVideoAliasThatStillHasNoPerSecondPrice() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        // 自建一个视频别名：它一定没有按秒费率行，因此这条提示必然该出现。
        // （用迁移里已有的 video-* 别名测不出来——那些别名本来就带价格。）
        String videoAlias = "video-note-" + suffix;
        guardedAdmin.createModel(new CreateModelCommand(
                videoAlias, "Video Note", Set.of("TEXT", "VIDEO"), 8192, 2048,
                false, true, 20, 2), TEST_ACTOR).block();
        ChannelView channel = guardedAdmin.createChannel(new CreateChannelCommand(
                providerIdByCode("tianyi"), "onboard-video-" + suffix, "Onboard Video",
                "PRODUCTION", "https://ai.ctaigw.cn/v1", "secret-" + suffix, true, 210, 100,
                60000, 60, 10),
                TEST_ACTOR)
                .block();

        RoutesOnboardingView view = guardedAdmin.onboardRoutes(
                channel.id(),
                new OnboardRoutesCommand(List.of(
                        new OnboardRouteCommand(videoAlias, "minimax-h3-max", true, null, null))))
                .block();

        assertThat(view).isNotNull();
        assertThat(view.routes()).singleElement()
                .extracting(RouteOnboardingResult::status).isEqualTo("CREATED");
        // 「接进去了但还不能卖」必须被说出来：视频别名缺按秒计价。
        assertThat(view.notes()).anySatisfy(note ->
                assertThat(note).contains("按秒计费").contains(videoAlias));
    }

    /**
     * 同一条提示也必须覆盖**音频**别名（方案 C）。
     *
     * <p>提示原本只认 {@code VIDEO} 能力，而音频别名现在也能挂进套餐——一个
     * 缺 {@code AUDIO_SECOND} 费率行的音频别名会与视频别名一样"接进去了但还不能卖"，
     * 只是它的暴露时刻更晚：平台发布守卫会在另一个工具里以
     * {@code provider_price_missing} 拒绝那个版本。
     *
     * <p>提示还必须指向**真实存在**的修法：音频没有视频那样的
     * {@code PUT /video-aliases/{code}} 端点，费率行只由迁移播种，
     * 因此它不能指一个不存在的端点。
     */
    @Test
    void bulkOnboardingNotesAnAudioAliasThatStillHasNoPerSecondPrice() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String audioAlias = "audio-note-" + suffix;
        guardedAdmin.createModel(new CreateModelCommand(
                audioAlias, "Audio Note", Set.of("AUDIO_TRANSCRIBE"), 8192, 2048,
                false, true, 20, 2), TEST_ACTOR).block();
        ChannelView channel = guardedAdmin.createChannel(new CreateChannelCommand(
                providerIdByCode("bailian"), "onboard-audio-" + suffix, "Onboard Audio",
                "PRODUCTION", "https://dashscope.aliyuncs.com/api/v1", "secret-" + suffix,
                true, 210, 100, 60000, 60, 10),
                TEST_ACTOR)
                .block();

        RoutesOnboardingView view = guardedAdmin.onboardRoutes(
                channel.id(),
                new OnboardRoutesCommand(List.of(
                        new OnboardRouteCommand(audioAlias, "qwen-audio-3.0-asr-flash",
                                true, null, null))))
                .block();

        assertThat(view).isNotNull();
        assertThat(view.routes()).singleElement()
                .extracting(RouteOnboardingResult::status).isEqualTo("CREATED");
        assertThat(view.notes()).anySatisfy(note -> assertThat(note)
                .contains("音频别名")
                .contains("AUDIO_SECOND")
                .contains(audioAlias)
                .doesNotContain("PUT /video-aliases"));
    }

    @Test
    void deletingAProviderRequiresCascadeAndReportsWhatItRemoved() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ProviderView provider = guardedAdmin.createProvider(new CreateProviderCommand(
                "del-probe-" + suffix, "Delete Probe", "TIANYIYUN",
                "https://ai.ctaigw.cn/v1", true), TEST_ACTOR).block();
        ChannelView channel = guardedAdmin.createChannel(new CreateChannelCommand(
                provider.id(), "del-probe-ch-" + suffix, "Delete Probe Channel", "PRODUCTION",
                "https://ai.ctaigw.cn/v1", "secret-" + suffix, true, 10, 100, 60000, 60, 10),
                TEST_ACTOR).block();
        guardedAdmin.onboardRoutes(channel.id(), new OnboardRoutesCommand(List.of(
                new OnboardRouteCommand("image-basic", "doubao-seedream-5.0-pro",
                        true, null, null))))
                .block();

        // ① 默认不级联：名下还有渠道就直接拒，避免顺手删掉可能还在售的模型路由。
        assertThatThrownBy(() -> guardedAdmin.deleteProvider(provider.id(), false, TEST_ACTOR).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).isEqualTo("provider_has_channels");
                });
        assertThat(guardedAdmin.listProviders()
                .filter(view -> view.id().equals(provider.id())).collectList().block())
                .as("被拒之后服务商必须还在")
                .hasSize(1);

        // ② 显式级联：渠道与别名映射一起删掉，并如实回报删了什么。
        ProviderDeletionView deleted =
                guardedAdmin.deleteProvider(provider.id(), true, TEST_ACTOR).block();
        assertThat(deleted).isNotNull();
        assertThat(deleted.deletedChannels()).isEqualTo(1);
        assertThat(deleted.deletedRoutes()).isEqualTo(1);
        assertThat(guardedAdmin.listProviders()
                .filter(view -> view.id().equals(provider.id())).collectList().block())
                .isEmpty();
    }

    private static UUID providerIdByCode(String providerCode) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "SELECT id FROM gateway.provider WHERE code = ?")) {
            statement.setString(1, providerCode);
            try (var result = statement.executeQuery()) {
                assertThat(result.next())
                        .as("provider %s must exist in the migrated schema", providerCode)
                        .isTrue();
                return result.getObject(1, UUID.class);
            }
        }
    }

    @Test
    void listsProvidersForFirstChannelCreationWithoutCredentialMaterial() {
        ProviderView provider = admin.listProviders()
                .filter(view -> view.id().equals(BAILIAN_PROVIDER))
                .single().block();

        assertThat(provider.code()).isEqualTo("bailian");
        assertThat(provider.name()).isEqualTo("阿里云百炼");
        assertThat(provider.providerType()).isEqualTo("BAILIAN");
        assertThat(provider.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(provider.enabled()).isTrue();
    }

    @Test
    void enablingCredentialedRealChannelAlsoEnablesItsProvider() {
        UUID providerId = UUID.randomUUID();
        String suffix = providerId.toString().substring(0, 8);
        databaseClient.sql("""
                        INSERT INTO gateway.provider(
                            id, code, name, provider_type, base_url, enabled)
                        VALUES (:id, :code, 'Runtime Reconcile Provider', 'BAILIAN',
                                'https://example.test/v1', false)
                """)
                .bind("id", providerId)
                .bind("code", "runtime-reconcile-" + suffix)
                .fetch().rowsUpdated().block();
        ChannelView channel = createChannel(
                new CreateChannelCommand(
                        providerId, "runtime-reconcile-" + suffix,
                        "Runtime Reconcile Channel", "SANDBOX", "https://example.test/v1",
                        "provider-secret", false, 10, 100, 30000, 20, 2)).block();

        updateChannel(new UpdateChannelCommand(
                channel.id(), channel.name(), channel.baseUrl(), null,
                true, channel.priority(), channel.weight(), channel.timeoutMs(),
                channel.requestsPerMinute(), channel.maxConcurrency())).block();

        assertThat(databaseClient.sql("SELECT enabled FROM gateway.provider WHERE id = :id")
                .bind("id", providerId)
                .map((row, metadata) -> row.get("enabled", Boolean.class))
                .one().block()).isTrue();
    }

    @Test
    void enablingCredentialedRealChannelDisablesLocalMockFallback() {
        UUID mockProviderId = UUID.fromString("81000000-0000-4000-8000-000000000099");
        UUID mockChannelId = UUID.fromString("84000000-0000-4000-8000-000000000099");
        databaseClient.sql("""
                        INSERT INTO gateway.provider(
                            id, code, name, provider_type, base_url, enabled)
                        VALUES (:id, 'local-mock', 'Local Mock', 'MOCK',
                                'http://wiremock:8080/v1', true)
                        """)
                .bind("id", mockProviderId).fetch().rowsUpdated().block();
        databaseClient.sql("""
                        INSERT INTO gateway.channel(
                            id, provider_id, code, name, environment, base_url,
                            enabled, priority, weight, timeout_ms,
                            requests_per_minute, max_concurrency)
                        VALUES (:id, :provider_id, 'local-mock-test', 'Local Mock Test',
                                'LOCAL', 'http://wiremock:8080/v1', true, 200, 100,
                                30000, 1000, 100)
                        """)
                .bind("id", mockChannelId).bind("provider_id", mockProviderId)
                .fetch().rowsUpdated().block();

        createChannel(channelCommand(
                "real-disables-mock", BAILIAN_PROVIDER, "SANDBOX",
                "https://example.test/v1", 20, 2)).block();

        assertThat(databaseClient.sql("""
                        SELECT NOT provider.enabled AND NOT channel.enabled AS disabled
                        FROM gateway.provider provider
                        JOIN gateway.channel channel ON channel.provider_id = provider.id
                        WHERE provider.id = :provider_id AND channel.id = :channel_id
                        """)
                .bind("provider_id", mockProviderId).bind("channel_id", mockChannelId)
                .map((row, metadata) -> row.get("disabled", Boolean.class))
                .one().block()).isTrue();
    }

    @Test
    void exposesCompositeRoutingStrategyAsReadOnlyCatalogMetadata() {
        ModelView composite = admin.listModels(
                        new AdminListQuery(
                                0, 10, "multimodal-basic", "ENABLED", "code", "asc"))
                .map(PageResult::items)
                .map(List::getFirst)
                .block();

        assertThat(composite.routingStrategy()).isEqualTo("COMPOSITE");
        assertThat(composite.capabilities())
                .containsExactlyInAnyOrder("TEXT", "IMAGE", "FILE");
        assertThat(composite.routes()).anySatisfy(route -> {
            assertThat(route.componentModelCode()).isEqualTo("text-basic");
            assertThat(route.providerName()).isEqualTo("DeepSeek");
            assertThat(route.upstreamModel()).isEqualTo("deepseek-chat");
        });
        assertThat(composite.routes()).anySatisfy(route -> {
            assertThat(route.componentModelCode()).isEqualTo("vision-basic");
            assertThat(route.providerName()).isEqualTo("阿里云百炼");
            assertThat(route.upstreamModel()).isEqualTo("qwen-vl-plus");
        });
        assertThat(composite.routes()).anySatisfy(route -> {
            assertThat(route.componentModelCode()).isEqualTo("file-basic");
            assertThat(route.providerName()).isEqualTo("阿里云百炼");
            assertThat(route.upstreamModel()).isEqualTo("qwen-long");
        });
    }

    @Test
    void pagesFiltersAndSortsModelsByDisplayedFields() {
        createModel(new CreateModelCommand(
                "phase7-model-alpha", "Phase7 Alpha", Set.of("phase7-hidden-alias"), Set.of("TEXT"),
                4096, 1024, false, true, 10, 1)).block();
        createModel(new CreateModelCommand(
                "phase7-model-zulu", "Phase7 Zulu", Set.of(), Set.of("TEXT"),
                8192, 2048, false, true, 20, 2)).block();
        createModel(new CreateModelCommand(
                "phase7-model-disabled", "Phase7 Disabled", Set.of(), Set.of("TEXT"),
                16384, 4096, false, false, 30, 3)).block();

        PageResult<ModelView> first = admin.listModels(
                new AdminListQuery(
                        0, 1, "phase7-model", "enabled", "displayName", "asc")).block();
        PageResult<ModelView> second = admin.listModels(
                new AdminListQuery(
                        1, 1, "phase7-model", "ENABLED", "displayName", "asc")).block();
        PageResult<ModelView> descending = admin.listModels(
                new AdminListQuery(
                        0, 2, "phase7-model", "ENABLED", "displayName", "desc")).block();
        PageResult<ModelView> alias = admin.listModels(
                new AdminListQuery(
                        0, 20, "PHASE7-HIDDEN-ALIAS", null, "code", "asc")).block();
        PageResult<ModelView> disabled = admin.listModels(
                new AdminListQuery(
                        0, 20, "phase7-model", "DISABLED", "code", "asc")).block();

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(1);
        assertThat(first.items()).extracting(ModelView::displayName)
                .containsExactly("Phase7 Alpha");
        assertThat(second.items()).extracting(ModelView::displayName)
                .containsExactly("Phase7 Zulu");
        assertThat(descending.items()).extracting(ModelView::displayName)
                .containsExactly("Phase7 Zulu", "Phase7 Alpha");
        assertThat(alias.items()).extracting(ModelView::code)
                .containsExactly("phase7-model-alpha");
        assertThat(disabled.items()).extracting(ModelView::code)
                .containsExactly("phase7-model-disabled");
    }

    /**
     * 限流列为 NULL 的模型与渠道仍然能列出来。
     *
     * <p>{@code requests_per_minute} 与 {@code max_concurrency} 在模型表与渠道表上都是
     * 可空的，而且 NULL 有语义：它表示**这一层不设限**，有效值由套餐、key、用户、
     * 渠道、模型各层取最小值决定（见 {@code DatabaseGatewayCatalog} 与
     * {@code RequestLimitService.Thresholds}）。执行路径因此一直允许它们为空，
     * 但管理端视图用的是原始类型 {@code int}。
     *
     * <p>2026-10-09 的生产故障就是从这里来的：V102 插入 {@code chat-standard} /
     * {@code chat-deep} 时没有写这两列，而列表按 {@code code} 升序分页、这两行落在
     * 第一页里，于是拆箱抛 NPE，**整页** 500——后台看起来"一个模型都没有"。
     * 这条用例把"数据缺列不再打挂列表"钉住。
     *
     * <p>刻意用 SQL 直接插入：服务层的创建命令要求这两个值大于 0，所以只有迁移
     * 或运维脚本能造出这种行，而那正是故障当天的成因。
     */
    @Test
    void listsModelsAndChannelsWhoseLimitColumnsAreNull() {
        databaseClient
                .sql(
                        """
                        INSERT INTO gateway.model(id, code, display_name, capabilities,
                                                  context_window, max_output_tokens, enabled)
                        VALUES (gen_random_uuid(), 'phase7-null-limits', 'Null Limits',
                                '["TEXT"]'::jsonb, 8192, 1024, true)
                        """)
                .fetch()
                .rowsUpdated()
                .block();
        databaseClient
                .sql(
                        """
                        INSERT INTO gateway.channel(id, provider_id, code, name, environment,
                                                    base_url, enabled)
                        VALUES (gen_random_uuid(), :provider_id, 'phase7-null-limits',
                                'Null Limits', 'SANDBOX', 'https://example.test/v1', true)
                        """)
                .bind("provider_id", BAILIAN_PROVIDER)
                .fetch()
                .rowsUpdated()
                .block();

        ModelView model = modelSearch("phase7-null-limits").items().getFirst();
        ChannelView channel = channelSearch("phase7-null-limits").items().getFirst();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(model.code()).isEqualTo("phase7-null-limits");
        softly.assertThat(model.requestsPerMinute()).isEqualTo(60);
        softly.assertThat(model.maxConcurrency()).isEqualTo(10);
        softly.assertThat(channel.code()).isEqualTo("phase7-null-limits");
        softly.assertThat(channel.requestsPerMinute()).isEqualTo(60);
        softly.assertThat(channel.maxConcurrency()).isEqualTo(10);
        softly.assertAll();
    }

    @Test
    void filtersNormalAndAbnormalModelsAndSupportsExplicitStatusChanges() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = "lifecycle-model-" + suffix;
        ModelView created = admin.saveModelConfiguration(
                new SaveModelConfigurationCommand(
                        null, code, "Lifecycle Model", Set.of(code), Set.of("TEXT"),
                        8192, 2048, false, true, 20, 2,
                        new ModelRouteConfiguration(
                                null, code, BAILIAN_PROVIDER, "SANDBOX",
                                "https://example.test/v1", "provider-secret", "upstream-model",
                                true, 10, 100, 30000, 20, 2)),
                TEST_ACTOR).block();

        assertThat(admin.listModels(new AdminListQuery(
                0, 20, code, "ABNORMAL", "code", "asc")).block().items())
                .extracting(ModelView::code).containsExactly(code);
        assertThat(admin.listModels(new AdminListQuery(
                0, 20, code, "NORMAL", "code", "asc")).block().items()).isEmpty();

        databaseClient.sql("""
                        UPDATE gateway.channel SET last_health_status = 'HEALTHY'
                        WHERE id = :id
                        """)
                .bind("id", created.routes().getFirst().channelId())
                .fetch().rowsUpdated().block();
        assertThat(admin.listModels(new AdminListQuery(
                0, 20, code, "NORMAL", "code", "asc")).block().items())
                .extracting(ModelView::code).containsExactly(code);

        databaseClient.sql("""
                        UPDATE gateway.channel_model SET capabilities = '[]'::jsonb
                        WHERE channel_id = :channelId AND model_id = :modelId
                        """)
                .bind("channelId", created.routes().getFirst().channelId())
                .bind("modelId", created.id())
                .fetch().rowsUpdated().block();
        assertThat(admin.listModels(new AdminListQuery(
                0, 20, code, "NORMAL", "code", "asc")).block().items()).isEmpty();
        assertThat(admin.listModels(new AdminListQuery(
                0, 20, code, "ABNORMAL", "code", "asc")).block().items())
                .extracting(ModelView::code).containsExactly(code);

        ModelView disabled = admin.setModelEnabled(created.id(), false, TEST_ACTOR).block();
        assertThat(disabled.enabled()).isFalse();
        assertThat(admin.listModels(new AdminListQuery(
                0, 20, code, "DISABLED", "code", "asc")).block().items())
                .extracting(ModelView::code).containsExactly(code);
    }

    @Test
    void blocksPackageReferencedModelsAndDeletesUnreferencedModels() {
        String code = "deletable-model-" + UUID.randomUUID().toString().substring(0, 8);
        ModelView model = createModel(new CreateModelCommand(
                code, "Deletable Model", Set.of("TEXT"),
                8192, 2048, false, false, 20, 2)).block();
        when(packageModels.references(code, "admin-token"))
                .thenReturn(
                        Mono.just(new ModelReferenceView(code, 1, List.of())),
                        Mono.just(new ModelReferenceView(code, 0, List.of())));

        assertThatThrownBy(() -> admin.deleteModel(model.id(), TEST_ACTOR, "admin-token").block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).isEqualTo("model_referenced_by_package");
                });
        assertThat(admin.listModels(new AdminListQuery(
                0, 20, code, null, "code", "asc")).block().items()).hasSize(1);

        admin.deleteModel(model.id(), TEST_ACTOR, "admin-token").block();
        assertThat(admin.listModels(new AdminListQuery(
                0, 20, code, null, "code", "asc")).block().items()).isEmpty();
    }

    @Test
    void pagesFiltersAndSortsChannelsWithoutSelectingCredentials() {
        createChannel(new CreateChannelCommand(
                BAILIAN_PROVIDER, "phase7-channel-alpha", "Phase7 Alpha", "SANDBOX",
                "https://phase7-alpha.example.test/v1", "alpha-secret", true,
                10, 100, 30000, 10, 1)).block();
        createChannel(new CreateChannelCommand(
                BAILIAN_PROVIDER, "phase7-channel-zulu", "Phase7 Zulu", "SANDBOX",
                "https://phase7-zulu.example.test/v1", "zulu-secret", true,
                20, 90, 40000, 20, 2)).block();
        createChannel(new CreateChannelCommand(
                BAILIAN_PROVIDER, "phase7-channel-disabled", "Phase7 Disabled", "LOCAL",
                "http://phase7-disabled.example.test/v1", "disabled-secret", false,
                30, 80, 50000, 30, 3)).block();

        PageResult<ChannelView> first = admin.listChannels(
                new AdminListQuery(
                        0, 1, "phase7-channel", "ENABLED", "name", "asc")).block();
        PageResult<ChannelView> second = admin.listChannels(
                new AdminListQuery(
                        1, 1, "phase7-channel", "enabled", "name", "asc")).block();
        PageResult<ChannelView> descending = admin.listChannels(
                new AdminListQuery(
                        0, 2, "phase7-channel", "ENABLED", "name", "desc")).block();
        PageResult<ChannelView> baseUrl = admin.listChannels(
                new AdminListQuery(
                        0, 20, "phase7-alpha.example", null, "code", "asc")).block();
        PageResult<ChannelView> disabled = admin.listChannels(
                new AdminListQuery(
                        0, 20, "phase7-channel", "DISABLED", "code", "asc")).block();

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items()).extracting(ChannelView::name)
                .containsExactly("Phase7 Alpha");
        assertThat(second.items()).extracting(ChannelView::name)
                .containsExactly("Phase7 Zulu");
        assertThat(descending.items()).extracting(ChannelView::name)
                .containsExactly("Phase7 Zulu", "Phase7 Alpha");
        assertThat(baseUrl.items()).singleElement().satisfies(channel -> {
            assertThat(channel.code()).isEqualTo("phase7-channel-alpha");
            assertThat(channel.credentialConfigured()).isTrue();
        });
        assertThat(disabled.items()).extracting(ChannelView::code)
                .containsExactly("phase7-channel-disabled");
    }

    @Test
    void usesDocumentedDefaultOrderingForModelsAndChannels() {
        createModel(new CreateModelCommand(
                "phase7-default-model-zulu", "Phase7 Default Model Zulu", Set.of("TEXT"),
                4096, 1024, false, true, 10, 1)).block();
        createModel(new CreateModelCommand(
                "phase7-default-model-alpha", "Phase7 Default Model Alpha", Set.of("TEXT"),
                4096, 1024, false, true, 10, 1)).block();
        createChannel(new CreateChannelCommand(
                BAILIAN_PROVIDER, "phase7-default-channel-beta", "Phase7 Default Channel Beta",
                "SANDBOX", "https://phase7-default-beta.example.test/v1", "beta-secret", true,
                20, 100, 30000, 10, 1)).block();
        createChannel(new CreateChannelCommand(
                BAILIAN_PROVIDER, "phase7-default-channel-gamma", "Phase7 Default Channel Gamma",
                "SANDBOX", "https://phase7-default-gamma.example.test/v1", "gamma-secret", true,
                10, 100, 30000, 10, 1)).block();
        createChannel(new CreateChannelCommand(
                BAILIAN_PROVIDER, "phase7-default-channel-alpha", "Phase7 Default Channel Alpha",
                "SANDBOX", "https://phase7-default-alpha.example.test/v1", "alpha-secret", true,
                20, 100, 30000, 10, 1)).block();

        PageResult<ModelView> models = admin.listModels(
                new AdminListQuery(
                        0, 10, "phase7-default-model-", null, null, null)).block();
        PageResult<ChannelView> channels = admin.listChannels(
                new AdminListQuery(
                        0, 10, "phase7-default-channel-", null, null, null)).block();

        assertThat(models.items()).extracting(ModelView::code)
                .containsExactly("phase7-default-model-alpha", "phase7-default-model-zulu");
        assertThat(channels.items()).extracting(ChannelView::code)
                .containsExactly(
                        "phase7-default-channel-alpha",
                        "phase7-default-channel-beta",
                        "phase7-default-channel-gamma");
    }

    @Test
    void usesIdTieBreakersInRequestedDirectionForEqualModelAndChannelSortKeys() {
        ModelView firstModel = createModel(
                new CreateModelCommand(
                        "phase7-model-tie-alpha", "Phase7 Equal Model Name", Set.of("TEXT"),
                        4096, 1024, false, true, 10, 1)).block();
        ModelView secondModel = createModel(
                new CreateModelCommand(
                        "phase7-model-tie-beta", "Phase7 Equal Model Name", Set.of("TEXT"),
                        4096, 1024, false, true, 10, 1)).block();
        ChannelView firstChannel = createChannel(
                new CreateChannelCommand(
                        BAILIAN_PROVIDER, "phase7-tie-channel-alpha", "Phase7 Channel Tie Alpha",
                        "SANDBOX", "https://phase7-tie-alpha.example.test/v1", "alpha-secret", true,
                        77, 100, 30000, 10, 1)).block();
        ChannelView secondChannel = createChannel(
                new CreateChannelCommand(
                        BAILIAN_PROVIDER, "phase7-tie-channel-beta", "Phase7 Channel Tie Beta",
                        "SANDBOX", "https://phase7-tie-beta.example.test/v1", "beta-secret", true,
                        77, 100, 30000, 10, 1)).block();

        List<UUID> expectedModelIds = List.of(firstModel.id(), secondModel.id()).stream()
                .sorted(Comparator.comparing(UUID::toString)).toList();
        List<UUID> expectedChannelIds = List.of(firstChannel.id(), secondChannel.id()).stream()
                .sorted(Comparator.comparing(UUID::toString)).toList();

        List<UUID> modelAscending = admin.listModels(new AdminListQuery(
                        0, 10, "phase7-model-tie-", null, "displayName", "asc"))
                .block().items().stream().map(ModelView::id).toList();
        List<UUID> modelDescending = admin.listModels(new AdminListQuery(
                        0, 10, "phase7-model-tie-", null, "displayName", "desc"))
                .block().items().stream().map(ModelView::id).toList();
        List<UUID> channelAscending = admin.listChannels(new AdminListQuery(
                        0, 10, "phase7-tie-channel-", null, "priority", "asc"))
                .block().items().stream().map(ChannelView::id).toList();
        List<UUID> channelDescending = admin.listChannels(new AdminListQuery(
                        0, 10, "phase7-tie-channel-", null, "priority", "desc"))
                .block().items().stream().map(ChannelView::id).toList();

        assertThat(modelAscending).containsExactlyElementsOf(expectedModelIds);
        assertThat(modelDescending).containsExactlyElementsOf(expectedModelIds.reversed());
        assertThat(channelAscending).containsExactlyElementsOf(expectedChannelIds);
        assertThat(channelDescending).containsExactlyElementsOf(expectedChannelIds.reversed());
    }

    @Test
    void treatsSqlLikeMetacharactersLiterallyInModelSearch() {
        createModel(new CreateModelCommand(
                "phase7-literal-model-percent", "phase7%model-literal", Set.of("TEXT"),
                4096, 1024, false, true, 10, 1)).block();
        createModel(new CreateModelCommand(
                "phase7-decoy-model-percent", "phase7-anything-model-literal", Set.of("TEXT"),
                4096, 1024, false, true, 10, 1)).block();
        createModel(new CreateModelCommand(
                "phase7-literal-model-underscore", "phase7_model-literal", Set.of("TEXT"),
                4096, 1024, false, true, 10, 1)).block();
        createModel(new CreateModelCommand(
                "phase7-decoy-model-underscore", "phase7Xmodel-literal", Set.of("TEXT"),
                4096, 1024, false, true, 10, 1)).block();
        createModel(new CreateModelCommand(
                "phase7-literal-model-backslash", "phase7\\model-literal", Set.of("TEXT"),
                4096, 1024, false, true, 10, 1)).block();
        createModel(new CreateModelCommand(
                "phase7-decoy-model-backslash", "phase7model-literal", Set.of("TEXT"),
                4096, 1024, false, true, 10, 1)).block();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(modelSearch("phase7%model-literal").items())
                    .extracting(ModelView::code)
                    .containsExactly("phase7-literal-model-percent");
            softly.assertThat(modelSearch("phase7_model-literal").items())
                    .extracting(ModelView::code)
                    .containsExactly("phase7-literal-model-underscore");
            softly.assertThat(modelSearch("phase7\\model-literal").items())
                    .extracting(ModelView::code)
                    .containsExactly("phase7-literal-model-backslash");
        });
    }

    @Test
    void treatsSqlLikeMetacharactersLiterallyInChannelSearch() {
        createChannel(searchChannel(
                "phase7-literal-channel-percent", "phase7%channel-literal")).block();
        createChannel(searchChannel(
                "phase7-decoy-channel-percent", "phase7-anything-channel-literal")).block();
        createChannel(searchChannel(
                "phase7-literal-channel-underscore", "phase7_channel-literal")).block();
        createChannel(searchChannel(
                "phase7-decoy-channel-underscore", "phase7Xchannel-literal")).block();
        createChannel(searchChannel(
                "phase7-literal-channel-backslash", "phase7\\channel-literal")).block();
        createChannel(searchChannel(
                "phase7-decoy-channel-backslash", "phase7channel-literal")).block();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(channelSearch("phase7%channel-literal").items())
                    .extracting(ChannelView::code)
                    .containsExactly("phase7-literal-channel-percent");
            softly.assertThat(channelSearch("phase7_channel-literal").items())
                    .extracting(ChannelView::code)
                    .containsExactly("phase7-literal-channel-underscore");
            softly.assertThat(channelSearch("phase7\\channel-literal").items())
                    .extracting(ChannelView::code)
                    .containsExactly("phase7-literal-channel-backslash");
        });
    }

    @Test
    void rejectsInvalidAdminListPaginationStatusSortAndDirection() {
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> admin.listModels(
                new AdminListQuery(-1, 20, null, null, "code", "asc")).block());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> admin.listModels(
                new AdminListQuery(0, 101, null, null, "code", "asc")).block());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> admin.listModels(
                new AdminListQuery(0, 20, null, "ACTIVE", "code", "asc")).block());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> admin.listModels(
                new AdminListQuery(0, 20, null, null, "updatedAt", "asc")).block());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> admin.listModels(
                new AdminListQuery(0, 20, null, null, "code", "sideways")).block());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> admin.listChannels(
                new AdminListQuery(0, 0, null, null, "code", "asc")).block());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> admin.listChannels(
                new AdminListQuery(0, 20, null, "ACTIVE", "code", "asc")).block());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> admin.listChannels(
                new AdminListQuery(0, 20, null, null, "encryptedApiKey", "asc")).block());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> admin.listChannels(
                new AdminListQuery(0, 20, null, null, "code", "sideways")).block());
    }

    @Test
    void rejectsMissingProviderInvalidEnvironmentAndNonPositiveLimits() {
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> createChannel(channelCommand(
                "missing-provider", null, "SANDBOX", "https://example.test/v1", 20, 2)).block());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> createChannel(channelCommand(
                "invalid-environment", BAILIAN_PROVIDER, "INVALID", "https://example.test/v1", 20, 2)).block());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> createChannel(channelCommand(
                "invalid-rpm", BAILIAN_PROVIDER, "SANDBOX", "https://example.test/v1", 0, 2)).block());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> createChannel(channelCommand(
                "invalid-concurrency", BAILIAN_PROVIDER, "SANDBOX", "https://example.test/v1", 20, 0)).block());
    }

    @Test
    void requiresHttpsForProductionChannelsOnCreateAndUpdate() {
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> createChannel(channelCommand(
                "production-http", BAILIAN_PROVIDER, "PRODUCTION", "http://example.test/v1", 20, 2)).block());

        ChannelView channel = createChannel(channelCommand(
                "production-update", BAILIAN_PROVIDER, "PRODUCTION", "https://example.test/v1", 20, 2)).block();
        UpdateChannelCommand update = new UpdateChannelCommand(
                channel.id(), "Production Update", "http://example.test/v1", null,
                true, 10, 100, 30000, 20, 2);

        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> updateChannel(update).block());
    }

    @Test
    void rejectsCredentialBearingChannelUrlsOnCreateAndUpdate() {
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> createChannel(channelCommand(
                "credential-url", BAILIAN_PROVIDER, "SANDBOX",
                "https://user:password@example.test/v1", 20, 2)).block());

        ChannelView channel = createChannel(channelCommand(
                "credential-update", BAILIAN_PROVIDER, "SANDBOX",
                "https://example.test/v1", 20, 2)).block();
        UpdateChannelCommand update = new UpdateChannelCommand(
                channel.id(), "Credential Update", "https://token@example.test/v1", null,
                true, 10, 100, 30000, 20, 2);

        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> updateChannel(update).block());
    }

    @Test
    void reportsUnknownProvidersAndDuplicateCodesAsClientErrors() {
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> createChannel(channelCommand(
                "unknown-provider", UUID.randomUUID(), "SANDBOX", "https://example.test/v1", 20, 2)).block());

        CreateChannelCommand command = channelCommand(
                "duplicate-channel", BAILIAN_PROVIDER, "SANDBOX", "https://example.test/v1", 20, 2);
        createChannel(command).block();
        assertStatus(HttpStatus.CONFLICT, () -> createChannel(command).block());
    }

    @Test
    void channelTestProbesLocalEndpointAndPersistsHealthyState() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread accept = Thread.startVirtualThread(() -> {
                try (Socket ignored = server.accept()) {
                    // 对于 Task 8 探活来说，一条成功的传输层连接就足够了。
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            ChannelView channel = createChannel(channelCommand(
                    "reachable-local", BAILIAN_PROVIDER, "LOCAL",
                    "http://127.0.0.1:" + server.getLocalPort(), 20, 2)).block();

            ChannelTestView result = admin.testChannel(channel.id()).block();

            assertThat(result.status()).isEqualTo("HEALTHY");
            assertThat(databaseClient.sql("""
                            SELECT last_health_status = 'HEALTHY'
                                   AND last_health_checked_at IS NOT NULL AS healthy
                            FROM gateway.channel WHERE id = :id
                            """)
                    .bind("id", channel.id())
                    .map((row, metadata) -> row.get("healthy", Boolean.class))
                    .one().block()).isTrue();
            accept.join(1000);
        }
    }

    @Test
    void channelTestRejectsPrivateSandboxEndpointAndPersistsUnhealthyState() {
        ChannelView channel = createChannel(channelCommand(
                "private-sandbox", BAILIAN_PROVIDER, "SANDBOX",
                "https://127.0.0.1:443", 20, 2)).block();

        ChannelTestView result = admin.testChannel(channel.id()).block();

        assertThat(result.status()).isEqualTo("UNHEALTHY");
        assertThat(admin.listAllChannels().filter(view -> view.id().equals(channel.id()))
                .single().block().lastHealthStatus()).isEqualTo("UNHEALTHY");
    }

    @Test
    void staleChannelProbeCannotOverwriteHealthAfterEndpointUpdate() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch accepted = new CountDownLatch(1);
            Thread.startVirtualThread(() -> {
                try (Socket ignored = server.accept()) {
                    accepted.countDown();
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                    // 服务器生命周期由该测试自己掌管。
                }
            });
            ChannelView channel = createChannel(channelCommand(
                    "stale-probe", BAILIAN_PROVIDER, "LOCAL",
                    "https://127.0.0.1:" + server.getLocalPort(), 20, 2, 300)).block();
            CompletableFuture<ChannelTestView> probe =
                    admin.testChannel(channel.id()).toFuture();
            assertThat(accepted.await(1, TimeUnit.SECONDS)).isTrue();

            updateChannel(new UpdateChannelCommand(
                    channel.id(), "Updated Endpoint", "http://127.0.0.1:1", null,
                    true, 10, 100, 30000, 20, 2)).block();

            assertThatThrownBy(probe::join)
                    .rootCause().isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(admin.listAllChannels().filter(view -> view.id().equals(channel.id()))
                    .single().block().lastHealthStatus()).isEqualTo("UNKNOWN");
        }
    }

    @Test
    void rejectsNonGlobalSpecialUseAddresses() throws Exception {
        assertThat(ChannelConnectivityProbe.isGlobalUnicast(InetAddress.getByName("0.0.0.1"))).isFalse();
        assertThat(ChannelConnectivityProbe.isGlobalUnicast(InetAddress.getByName("198.18.0.1"))).isFalse();
        assertThat(ChannelConnectivityProbe.isGlobalUnicast(InetAddress.getByName("240.0.0.1"))).isFalse();
        assertThat(ChannelConnectivityProbe.isGlobalUnicast(InetAddress.getByName("8.8.8.8"))).isTrue();
    }

    @Test
    void reportsDuplicateModelCodeAsConflict() {
        CreateModelCommand create = new CreateModelCommand(
                "duplicate-model", "Duplicate Model", Set.of("TEXT"),
                8192, 2048, false, true, 20, 2);
        createModel(create).block();
        assertStatus(HttpStatus.CONFLICT, () -> createModel(create).block());
    }

    @Test
    void rejectsOversizedModelCodeAndMissingUpdateId() {
        CreateModelCommand oversized = new CreateModelCommand(
                "m".repeat(129), "Oversized Model", Set.of("TEXT"),
                8192, 2048, false, true, 20, 2);
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> createModel(oversized).block());

        UpdateModelCommand missingId = new UpdateModelCommand(
                null, "Missing ID", Set.of("TEXT"),
                8192, 2048, false, true, 20, 2);
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> updateModel(missingId).block());
    }

    @Test
    void replacesEditableAliasesWhilePreservingCanonicalAlias() {
        ModelView created = createModel(
                new CreateModelCommand(
                        "alias-model", "Alias Model", Set.of("alias-v1", "openai-compatible"),
                        Set.of("TEXT"), 8192, 2048, false, true, 20, 2)).block();

        ModelView updated = updateModel(
                new UpdateModelCommand(
                        created.id(), "Alias Model", Set.of("alias-v2"), Set.of("TEXT"),
                        8192, 2048, false, true, 20, 2)).block();

        assertThat(created.aliases()).containsExactlyInAnyOrder(
                "alias-model", "alias-v1", "openai-compatible");
        assertThat(updated.aliases()).containsExactlyInAnyOrder("alias-model", "alias-v2");
        assertThat(databaseClient.sql("""
                        SELECT count(*) FROM gateway.model_alias
                        WHERE model_id = :id AND alias = 'alias-v1'
                        """).bind("id", created.id())
                .map((row, metadata) -> row.get(0, Long.class)).one().block()).isZero();
    }

    @Test
    void omittedAliasesPreserveExistingAliasSet() {
        ModelView created = createModel(
                new CreateModelCommand(
                        "legacy-alias-model", "Legacy Alias Model", Set.of("legacy-compatible"),
                        Set.of("TEXT"), 8192, 2048, false, true, 20, 2)).block();

        ModelView updated = updateModel(
                new UpdateModelCommand(
                        created.id(), "Renamed Display", Set.of("TEXT"),
                        8192, 2048, false, true, 20, 2)).block();

        assertThat(updated.aliases()).containsExactlyInAnyOrder(
                "legacy-alias-model", "legacy-compatible");
    }

    @Test
    void writesActorAwareSafeAuditsForModelAndChannelMutations() throws Exception {
        UUID actorId = UUID.randomUUID();
        ModelView model = admin.createModel(
                new CreateModelCommand(
                        "audited-model", "Audited Model", Set.of("audit-alias"), Set.of("TEXT"),
                        8192, 2048, false, true, 20, 2), actorId).block();
        admin.updateModel(new UpdateModelCommand(
                model.id(), "Audited Model Updated", Set.of("audit-alias-v2"), Set.of("TEXT"),
                16384, 4096, true, false, 30, 3), actorId).block();
        ChannelView channel = admin.createChannel(
                new CreateChannelCommand(
                        BAILIAN_PROVIDER, "audited-channel", "Audited Channel", "SANDBOX",
                        "https://example.test/v1", "create-secret", true,
                        10, 100, 30000, 20, 2), actorId).block();
        admin.updateChannel(new UpdateChannelCommand(
                channel.id(), "Audited Channel Updated", "https://example.test/v2",
                "replacement-secret", false, 20, 80, 45000, 30, 3), actorId).block();
        admin.updateChannel(new UpdateChannelCommand(
                channel.id(), "Audited Channel Final", "https://example.test/v3",
                null, true, 30, 60, 60000, 40, 4), actorId).block();

        List<JsonNode> audits = auditRows(actorId);

        assertThat(audits).extracting(row -> row.path("action").asText())
                .containsExactly(
                        "GATEWAY_MODEL_CREATED", "GATEWAY_MODEL_UPDATED",
                        "GATEWAY_CHANNEL_CREATED", "GATEWAY_CHANNEL_UPDATED",
                        "GATEWAY_CHANNEL_UPDATED");
        assertThat(audits).allSatisfy(row -> {
            assertThat(row.path("actor_user_id").asText()).isEqualTo(actorId.toString());
            assertThat(row.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(row.path("resource_id").asText()).isNotBlank();
        });
        assertThat(audits.get(0).path("resource_type").asText()).isEqualTo("GATEWAY_MODEL");
        assertThat(audits.get(0).at("/detail/before").isNull()).isTrue();
        assertThat(audits.get(0).at("/detail/after/code").asText()).isEqualTo("audited-model");
        assertThat(audits.get(1).at("/detail/before/displayName").asText())
                .isEqualTo("Audited Model");
        assertThat(audits.get(1).at("/detail/after/displayName").asText())
                .isEqualTo("Audited Model Updated");
        assertThat(audits.get(2).path("resource_type").asText()).isEqualTo("GATEWAY_CHANNEL");
        assertThat(audits.get(2).at("/detail/credentialChanged").asBoolean()).isTrue();
        assertThat(audits.get(3).at("/detail/credentialChanged").asBoolean()).isTrue();
        assertThat(audits.get(4).at("/detail/credentialChanged").asBoolean()).isFalse();
        assertThat(audits.toString())
                .doesNotContain("create-secret", "replacement-secret", "apiKey",
                        "encryptedApiKey", "encrypted_api_key", "credentialConfigured");
    }

    @Test
    void rejectsCredentialBearingChannelBaseUrlComponents() {
        for (String baseUrl : List.of(
                "https://user:secret@example.test/v1",
                "https://example.test/v1?api_key=secret",
                "https://example.test/v1#secret")) {
            assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> createChannel(
                    channelCommand("unsafe-" + Math.abs(baseUrl.hashCode()), BAILIAN_PROVIDER,
                            "SANDBOX", baseUrl, 20, 2)).block());
        }
    }

    private List<JsonNode> auditRows(UUID actorId) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        List<JsonNode> rows = new ArrayList<>();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        SELECT to_jsonb(audit)::text
                        FROM gateway.admin_operation_audit audit
                        WHERE actor_user_id = ?
                        ORDER BY id
                        """)) {
            statement.setObject(1, actorId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(objectMapper.readTree(result.getString(1)));
                }
            }
        }
        return rows;
    }

    private byte[] encryptedCredential(UUID channelId) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "SELECT encrypted_api_key FROM gateway.channel WHERE id = ?")) {
            statement.setObject(1, channelId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBytes(1);
            }
        }
    }

    private CreateChannelCommand channelCommand(
            String code, UUID providerId, String environment, String baseUrl, int rpm, int concurrency) {
        return channelCommand(code, providerId, environment, baseUrl, rpm, concurrency, 30000);
    }

    private Mono<ModelView> createModel(
            CreateModelCommand command) {
        return admin.createModel(command, TEST_ACTOR);
    }

    private Mono<ModelView> updateModel(
            UpdateModelCommand command) {
        return admin.updateModel(command, TEST_ACTOR);
    }

    private Mono<ChannelView> createChannel(
            CreateChannelCommand command) {
        return admin.createChannel(command, TEST_ACTOR);
    }

    private Mono<ChannelView> updateChannel(
            UpdateChannelCommand command) {
        return admin.updateChannel(command, TEST_ACTOR);
    }

    private CreateChannelCommand channelCommand(
            String code, UUID providerId, String environment, String baseUrl,
            int rpm, int concurrency, int timeoutMs) {
        return new CreateChannelCommand(
                providerId, code, "Channel " + code, environment, baseUrl,
                "provider-secret", true, 10, 100, timeoutMs, rpm, concurrency);
    }

    private PageResult<ModelView> modelSearch(String query) {
        return admin.listModels(new AdminListQuery(
                0, 100, query, null, "code", "asc")).block();
    }

    private PageResult<ChannelView> channelSearch(String query) {
        return admin.listChannels(new AdminListQuery(
                0, 100, query, null, "code", "asc")).block();
    }

    private CreateChannelCommand searchChannel(String code, String name) {
        return new CreateChannelCommand(
                BAILIAN_PROVIDER, code, name, "SANDBOX", "https://example.test/v1",
                "provider-secret", true, 10, 100, 30000, 20, 2);
    }

    private void assertStatus(HttpStatus status, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(status));
    }
}
