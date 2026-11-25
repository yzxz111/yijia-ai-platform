package com.yuzhihuihe.apiplatform.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.GatewayServiceApplication;
import com.yuzhihuihe.apiplatform.gateway.routing.CredentialCipher;
import com.yuzhihuihe.apiplatform.gateway.routing.ModelCatalogService;
import com.yuzhihuihe.apiplatform.gateway.security.ConsoleTokenValidator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Testcontainers
@SpringBootTest(
        classes = GatewayServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.default-schema=gateway",
                "spring.flyway.schemas=gateway",
                "spring.data.redis.repositories.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "security.api-key.pepper=test-managed-api-key-pepper",
                "security.internal.hmac-secret=test-internal-secret",
                "gateway.secrets.encryption-key="
                        + "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        })
class ManagedApiKeyServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("61000000-0000-4000-8000-000000000011");
    private static final UUID USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000011");
    private static final UUID PACKAGE_ID = UUID.fromString("81000000-0000-4000-8000-000000000011");

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
        try {
            execute("""
                    CREATE SCHEMA IF NOT EXISTS platform;
                    CREATE TABLE IF NOT EXISTS platform.app_user(
                        id uuid PRIMARY KEY,
                        tenant_id uuid NOT NULL,
                        status varchar(20) NOT NULL
                    );
                    CREATE OR REPLACE VIEW platform.gateway_user_status AS
                    SELECT tenant_id, id, status FROM platform.app_user;
                    GRANT USAGE ON SCHEMA platform TO PUBLIC;
                    GRANT SELECT ON platform.gateway_user_status TO PUBLIC;
                    """);
        } catch (Exception error) {
            throw new IllegalStateException("test_platform_user_schema_setup_failed", error);
        }
    }

    @BeforeEach
    void resetData() throws Exception {
        execute("TRUNCATE platform.app_user");
        execute("""
                INSERT INTO platform.app_user(id, tenant_id, status)
                VALUES ('71000000-0000-4000-8000-000000000011',
                        '61000000-0000-4000-8000-000000000011', 'ACTIVE')
                """);
        execute("TRUNCATE gateway.admin_operation_audit, gateway.package_refund_hold, "
                + "gateway.api_key, gateway.package_instance_model_quota, "
                + "gateway.package_instance CASCADE");
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    identity_key, package_plan_version_id, package_code, package_name,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, max_file_size_bytes, once_per_user,
                    status, starts_at, expires_at, activated_at)
                VALUES (
                    '81000000-0000-4000-8000-000000000011',
                    '41000000-0000-4000-8000-000000000011',
                    '51000000-0000-4000-8000-000000000011',
                    '61000000-0000-4000-8000-000000000011',
                    '71000000-0000-4000-8000-000000000011',
                    'managed-key-identity',
                    '91000000-0000-4000-8000-000000000011',
                    'ADVANCED', 'Advanced', 29900, 'CNY',
                    ('[{"modelCode":"text-basic","modelTokenQuota":null,"capabilities":["TEXT"]},'
                        || '{"modelCode":"multimodal-basic","modelTokenQuota":null,'
                        || '"capabilities":["TEXT","IMAGE","FILE"]},'
                        -- 客户端可见的文本通道也要在实例名单里，否则 /v1/models 会是空的。
                        -- 此前这份夹具只有 V102/V103 已经收起的 text-basic / multimodal-basic，
                        -- 于是目录查询（要求 client_visible）与 Key 名单交集为空 ——
                        -- 这不是生产形态：真实实例的名单里两类别名都在，
                        -- PackageActivationService.effectiveModels 也正是这么补的
                        -- （与网关迁移 V109 是同一条规则的两次表达）。
                        || '{"modelCode":"chat-standard","modelTokenQuota":null,"capabilities":["TEXT"]},'
                        || '{"modelCode":"chat-deep","modelTokenQuota":null,"capabilities":["TEXT"]}]')::jsonb,
                    5000000, 10, 60, 50000, 8, 30, 20971520, false,
                    'ACTIVE', now(), now() + interval '30 days', now())
                """);
    }

    @Autowired
    ManagedApiKeyService managedApiKeyService;

    @Autowired
    ApiKeyService apiKeyService;

    @Autowired
    ManagedApiKeyRepository managedApiKeyRepository;

    @Autowired
    ModelCatalogService modelCatalogService;

    @Autowired
    CredentialCipher credentialCipher;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @MockitoBean
    ConsoleTokenValidator consoleTokenValidator;

    @Test
    void concurrentBootstrapReturnsOneManagedKeyAndSamePlaintext() {
        var results = Mono.zip(
                        managedApiKeyService.bootstrap(TENANT_ID, USER_ID, null)
                                .subscribeOn(Schedulers.parallel()),
                        managedApiKeyService.bootstrap(TENANT_ID, USER_ID, null)
                                .subscribeOn(Schedulers.parallel()))
                .block(Duration.ofSeconds(15));

        assertThat(results).isNotNull();
        assertThat(results.getT1().apiKey()).isEqualTo(results.getT2().apiKey());
        assertThat(results.getT1().apiKey()).startsWith("sk-yzh-").hasSizeGreaterThanOrEqualTo(50);
        assertThat(singleInt("SELECT count(*) FROM gateway.api_key "
                + "WHERE package_instance_id = '" + PACKAGE_ID + "' AND managed AND status = 'ACTIVE'"))
                .isEqualTo(1);
    }

    @Test
    void storedCiphertextDecryptsToASecretWhoseHmacAuthenticates() {
        ManagedApiKeyService.ClientBootstrap result = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(credentialCipher.decrypt(singleBytes(
                "SELECT secret_ciphertext FROM gateway.api_key WHERE managed")))
                .isEqualTo(result.apiKey());
        assertThat(singleString("SELECT key_hash FROM gateway.api_key WHERE managed"))
                .isEqualTo(apiKeyService.digestSecret(result.apiKey()));
        assertThat(apiKeyService.authenticate(result.apiKey()).block(Duration.ofSeconds(5)))
                .extracting(ApiKeyService.ApiKeyContext::packageInstanceId)
                .isEqualTo(PACKAGE_ID);
    }

    @Test
    void returnsActivePackageMetadata() throws Exception {
        // 客户端文本通道由 V109 / PackageActivationService 补进套餐；这里按同一条
        // 规则手动补上，模拟一个已经补齐两个入口的文本实例（夹具里只有被收起的
        // text-basic / multimodal-basic）。
        execute("""
                UPDATE gateway.package_instance
                SET allowed_models = allowed_models || '[{"modelCode":"chat-standard",
                        "modelTokenQuota":null,"capabilities":["TEXT"]},
                        {"modelCode":"chat-deep","modelTokenQuota":null,
                         "capabilities":["TEXT"]}]'::jsonb
                WHERE id = '81000000-0000-4000-8000-000000000011'
                """);

        ManagedApiKeyService.ClientBootstrap result = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.packageInstanceId()).isEqualTo(PACKAGE_ID);
        assertThat(result.packageCode()).isEqualTo("ADVANCED");
        assertThat(result.packageExpiresAt()).isNotNull();
        // 响应只回目录可见的别名：text-basic / multimodal-basic 已被 V102/V103 收起
        // （client_visible = false），不能出现在客户端的模型下拉里。
        assertThat(result.allowedModels()).isEqualTo(Set.of("chat-deep", "chat-standard"));
        // P1-3 回归：这个集合会以 JSON 数组下发（Bootstrap 的 allowedModels），
        // 因此它的**迭代顺序**也是契约：必须按字母序确定，不能随进程的随机盐变化。
        assertThat(result.allowedModels()).containsExactly("chat-deep", "chat-standard");
    }

    /**
     * 客户端「套餐模型」下拉只应看到目录可见的两个文本通道；套餐里被收起的别名
     * （text-basic / multimodal-basic）虽然仍可被按名字调用，但必须从响应里隐藏。
     */
    @Test
    void hidesClientInvisibleAliasesFromBootstrapResponse() throws Exception {
        execute("""
                UPDATE gateway.package_instance
                SET allowed_models = allowed_models || '[{"modelCode":"chat-standard",
                        "modelTokenQuota":null,"capabilities":["TEXT"]},
                        {"modelCode":"chat-deep","modelTokenQuota":null,
                         "capabilities":["TEXT"]},
                        {"modelCode":"vision-pro","modelTokenQuota":null,
                         "capabilities":["TEXT","IMAGE"]}]'::jsonb
                WHERE id = '81000000-0000-4000-8000-000000000011'
                """);

        ManagedApiKeyService.ClientBootstrap result = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.allowedModels()).containsExactly("chat-deep", "chat-standard");
        // 托管 Key 的授权名单必须仍是套餐的**完整**名单（含被收起的别名），
        // 这样第三方兼容客户端按名字调用照旧。
        assertThat(singleString("SELECT allowed_models::text FROM gateway.api_key WHERE managed"))
                .isEqualTo("[\"chat-deep\", \"chat-standard\", \"multimodal-basic\", "
                        + "\"text-basic\", \"vision-pro\"]");
    }

    /**
     * V110：视频入口（VIDEO_SECOND 维度）的 bootstrap 只回合并后的 video-minimax——
     * 档位视频别名已收起（client_visible=false），音频别名（LIP_SYNC）不进视频下拉，
     * 尽管它们都还在套餐的完整名单里。
     */
    @Test
    void videoDimensionBootstrapListsOnlyTheConsolidatedVideoModel() throws Exception {
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    identity_key, package_plan_version_id, package_code, package_name,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, max_file_size_bytes, once_per_user, usage_dimension,
                    status, starts_at, expires_at, activated_at)
                VALUES (
                    '81000000-0000-4000-8000-000000000013',
                    '41000000-0000-4000-8000-000000000013',
                    '51000000-0000-4000-8000-000000000013',
                    '61000000-0000-4000-8000-000000000011',
                    '71000000-0000-4000-8000-000000000011',
                    'managed-key-identity-video-minimax',
                    '91000000-0000-4000-8000-000000000013',
                    'VIDEO_MINIMAX_TEST', 'Video Minimax Test', 12900, 'CNY',
                    ('[{"modelCode":"video-minimax","modelTokenQuota":null,'
                        || '"capabilities":["TEXT","IMAGE","VIDEO"]},'
                        || '{"modelCode":"video-budget","modelTokenQuota":null,'
                        || '"capabilities":["TEXT","IMAGE","VIDEO"]},'
                        || '{"modelCode":"audio-retalk","modelTokenQuota":null,'
                        || '"capabilities":["TEXT","LIP_SYNC"]}]')::jsonb,
                    60, 2, 60, 60, 2, 30, 20971520, false, 'VIDEO_SECOND',
                    'ACTIVE', now(), now() + interval '30 days', now())
                """);

        ManagedApiKeyService.ClientBootstrap result = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, "VIDEO_SECOND").block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.allowedModels()).containsExactly("video-minimax");
        // 托管 Key 的完整名单不受影响：480P 档位与音频别名仍可被按名字调用。
        assertThat(singleString("SELECT allowed_models::text FROM gateway.api_key "
                + "WHERE managed AND package_instance_id = '81000000-0000-4000-8000-000000000013'"))
                .isEqualTo("[\"audio-retalk\", \"video-budget\", \"video-minimax\"]");
    }

    /**
     * IMAGE 维度（生图套餐）的 bootstrap 必须回 {@code image-basic}——它是客户端图片
     * 入口的**唯一**开关（2026-10-09 生产故障）。
     *
     * <p>故障形态：V103 那条「不在白名单里就不可见」的 UPDATE，白名单里只有
     * chat-* / video-* / audio-*，**漏了 image-basic**，把它一并置成
     * {@code client_visible = false}；而 {@code findClientVisibleModels()} 正是按这一
     * 列过滤的。于是持有 {@code IMAGE_BASIC} 套餐的账号在图片页只能看到
     * 「当前套餐暂无可用的图片模型」——网关侧的权益、费率、档位、路由却全都是好的。
     *
     * <p>反向守卫也在这里：{@code image-basic} 带 {@code IMAGE_GEN}，因此它**不得**
     * 出现在文本维度（TOKEN）的下拉里——{@code relevantTo} 的默认分支必须排除它。
     */
    @Test
    void imageDimensionBootstrapListsTheImageModel() throws Exception {
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    identity_key, package_plan_version_id, package_code, package_name,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, max_file_size_bytes, once_per_user, usage_dimension,
                    status, starts_at, expires_at, activated_at)
                VALUES (
                    '81000000-0000-4000-8000-000000000014',
                    '41000000-0000-4000-8000-000000000014',
                    '51000000-0000-4000-8000-000000000014',
                    '61000000-0000-4000-8000-000000000011',
                    '71000000-0000-4000-8000-000000000011',
                    'managed-key-identity-image-basic',
                    '91000000-0000-4000-8000-000000000014',
                    'IMAGE_BASIC_TEST', 'Image Basic Test', 4990, 'CNY',
                    ('[{"modelCode":"image-basic","modelTokenQuota":null,'
                        || '"capabilities":["IMAGE","TEXT","IMAGE_GEN"]},'
                        || '{"modelCode":"chat-standard","modelTokenQuota":null,'
                        || '"capabilities":["TEXT"]}]')::jsonb,
                    48, 2, 20, 20, 2, 30, 20971520, false, 'IMAGE',
                    'ACTIVE', now(), now() + interval '30 days', now())
                """);

        ManagedApiKeyService.ClientBootstrap result = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, "IMAGE").block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.usageDimension()).isEqualTo("IMAGE");
        // 图片下拉必须有它；同一份套餐里写的 chat-standard 属于文本维度，不进这个下拉。
        assertThat(result.allowedModels()).containsExactly("image-basic");
        // 托管 Key 的**完整**名单不受展示收敛影响：套餐里写了什么就授权什么。
        assertThat(singleString("SELECT allowed_models::text FROM gateway.api_key "
                + "WHERE managed AND package_instance_id = '81000000-0000-4000-8000-000000000014'"))
                .isEqualTo("[\"chat-standard\", \"image-basic\"]");
    }

    /**
     * 复用托管 Key 时，Key 的授权名单必须跟着**套餐当前**的名单走（2026-10-09 生产故障）。
     *
     * <p>故障形态：套餐的 {@code allowed_models} 被更新过（运维把客户端文本入口换成
     * chat-standard / chat-deep），而桌面端那把托管 Key 的快照停在发放那一刻。网关
     * 要求**两边都授权**，于是客户端拿到 5 个 allowedModels、{@code /v1/models} 却是
     * 空数组——"模型在客户端全部不可用"。
     *
     * <p>这里钉住三件事：① Key 被**复用**（同一把 secret，不是重建）；② 它的名单被对齐
     * 成套餐的名单（字符串数组、字母序，与发放时写的形状一致）；③ 重复 Bootstrap 不会
     * 改变这份名单（幂等）。
     */
    @Test
    void reusingAManagedKeyAlignsItsAllowedModelsWithThePackage() throws Exception {
        ManagedApiKeyService.ClientBootstrap first = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));
        assertThat(first).isNotNull();
        // 首次 Bootstrap 之后，Key 的名单就已经等于套餐的**完整**名单：
        // 实例自身带的两个旧别名 + `effectiveModels` 补上的两个客户端文本通道。
        assertThat(singleString("SELECT allowed_models::text FROM gateway.api_key WHERE managed"))
                .isEqualTo("[\"chat-deep\", \"chat-standard\", \"multimodal-basic\", \"text-basic\"]");

        // 套餐侧更新：补上两个客户端文本通道（生产上是运维/控制台做的）。
        // 这里它们已经在名单里了，所以下面的 UPDATE 是**幂等**的 ——
        // 用例随后断言的"复用同一把 Key、名单不变"正依赖这一点。
        execute("""
                UPDATE gateway.package_instance
                SET allowed_models = allowed_models || '[{"modelCode":"chat-standard",
                        "modelTokenQuota":null,"capabilities":["TEXT"]},
                        {"modelCode":"chat-deep","modelTokenQuota":null,
                         "capabilities":["TEXT"]}]'::jsonb
                WHERE id = '81000000-0000-4000-8000-000000000011'
                """);

        ManagedApiKeyService.ClientBootstrap second = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));

        assertThat(second).isNotNull();
        assertThat(second.apiKey())
                .as("复用的是同一把 Key，不是重建一把")
                .isEqualTo(first.apiKey());
        // 响应只回目录可见的两个通道；被收起的别名仍留在 Key 的完整名单里。
        assertThat(second.allowedModels())
                .containsExactly("chat-deep", "chat-standard");
        assertThat(singleString("SELECT allowed_models::text FROM gateway.api_key WHERE managed"))
                .as("Key 的名单必须被对齐成套餐的名单（字母序、字符串数组）")
                .isEqualTo("[\"chat-deep\", \"chat-standard\", \"multimodal-basic\", \"text-basic\"]");
        assertThat(singleInt("SELECT count(*) FROM gateway.api_key WHERE managed"))
                .as("对齐不新建 Key")
                .isEqualTo(1);

        // 幂等：名单已经一致时再 Bootstrap 不会改变它（那条 UPDATE 匹配 0 行）。
        // 这里不能用 updated_at 当证据：Bootstrap 每次都会 markRevealed，它本来就会
        // 写这一列。回填路径的"不写"由 ClientTextChannelEntitlementMigrationTest 钉住。
        managedApiKeyService.bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));
        assertThat(singleString("SELECT allowed_models::text FROM gateway.api_key WHERE managed"))
                .isEqualTo("[\"chat-deep\", \"chat-standard\", \"multimodal-basic\", \"text-basic\"]");
    }

    @Test
    void repeatedBootstrapSynchronizesManagedPermissionsForAuthenticationAndCatalog()
            throws Exception {
        ManagedApiKeyService.ClientBootstrap first = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));
        assertThat(first).isNotNull();
        ApiKeyService.ApiKeyContext oldContext = apiKeyService.authenticate(first.apiKey())
                .block(Duration.ofSeconds(5));
        assertThat(oldContext).isNotNull();
        UUID keyId = oldContext.apiKeyId();
        // Key 的名单是套餐的**完整**名单：客户端可见的两个通道 + 被收起的旧别名。
        assertThat(oldContext.allowedModels())
                .containsExactlyInAnyOrder(
                        "text-basic", "multimodal-basic", "chat-standard", "chat-deep");
        // **但客户端目录只回可见的那两个。** 这正是这条用例存在的理由：
        // 修复 2026-10-09 那次「allowedModels 非空、目录却是 data: []」的故障 ——
        // 目录查询要求 client_visible，而 V102/V103 把 text-basic / multimodal-basic
        // 收起了，所以实例名单里必须有这两个可见通道，交集才不会为空。
        assertThat(modelCatalogService.availableModels(oldContext)
                .map(ModelCatalogService.ModelView::id).collectList().block())
                .containsExactly("chat-deep", "chat-standard");

        execute("""
                UPDATE gateway.package_instance
                SET allowed_models = ('[{"modelCode":"multimodal-basic","capabilities":'
                    || '["TEXT","IMAGE","FILE"]},{"modelCode":"text-pro",'
                    || '"capabilities":["TEXT"]},{"modelCode":"chat-standard",'
                    || '"capabilities":["TEXT"]}]')::jsonb
                WHERE id = '81000000-0000-4000-8000-000000000011'
                """);

        ManagedApiKeyService.ClientBootstrap refreshed = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));
        assertThat(refreshed).isNotNull();
        assertThat(refreshed.apiKey()).isEqualTo(first.apiKey());
        // Bootstrap 的响应字段是**客户端目录可见**的那部分（与 line 251 的用例同一语义），
        // 所以这里只剩 chat-standard —— 另外两个是被 V102/V103 收起的别名。
        assertThat(refreshed.allowedModels())
                .containsExactly("chat-standard");
        // Key 自己的完整名单则包含三者：同步确实把新名单写进去了。
        assertThat(singleString("SELECT allowed_models::text FROM gateway.api_key WHERE managed"))
                .isEqualTo("[\"chat-standard\", \"multimodal-basic\", \"text-pro\"]");
        ApiKeyService.ApiKeyContext newContext = apiKeyService.authenticate(first.apiKey())
                .block(Duration.ofSeconds(5));
        assertThat(newContext).isNotNull();
        assertThat(newContext.apiKeyId()).isEqualTo(keyId);
        assertThat(newContext.allowedModels())
                .containsExactlyInAnyOrder("multimodal-basic", "text-pro", "chat-standard");
        // 目录**重新同步**了：新加的可见通道（chat-standard）出现，被收起的别名
        // （multimodal-basic / text-pro）**不出现**。这是"同步"这一半的证明 ——
        // 修复前这里恒为空集，因为实例名单里根本没有可见别名。
        assertThat(modelCatalogService.availableModels(newContext)
                .map(ModelCatalogService.ModelView::id).collectList().block())
                .containsExactly("chat-standard");
        assertThat(singleInt("SELECT count(*) FROM gateway.api_key WHERE managed AND status = 'ACTIVE'"))
                .isEqualTo(1);
    }

    @Test
    void synchronizingManagedKeyDoesNotExpandAnOrdinaryKeysSelectedModels() throws Exception {
        ApiKeyService.CreatedApiKey ordinary = apiKeyService.create(
                TENANT_ID, USER_ID,
                new ApiKeyService.CreateApiKeyRequest(
                        "Selected text model", List.of("text-basic"), "TOKEN"))
                .block(Duration.ofSeconds(5));
        assertThat(ordinary).isNotNull();
        ManagedApiKeyService.ClientBootstrap managed = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));
        assertThat(managed).isNotNull();

        execute("""
                UPDATE gateway.package_instance
                SET allowed_models = allowed_models ||
                    '[{"modelCode":"text-pro","capabilities":["TEXT"]}]'::jsonb
                WHERE id = '81000000-0000-4000-8000-000000000011'
                """);
        managedApiKeyService.bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));

        assertThat(apiKeyService.authenticate(managed.apiKey())
                .block(Duration.ofSeconds(5)).allowedModels())
                .contains("text-pro");
        ApiKeyService.ApiKeyContext ordinaryContext = apiKeyService.authenticate(ordinary.secret())
                .block(Duration.ofSeconds(5));
        assertThat(ordinaryContext).isNotNull();
        assertThat(ordinaryContext.apiKeyId()).isEqualTo(ordinary.id());
        assertThat(ordinaryContext.allowedModels()).containsExactly("text-basic");
        // 普通 Key 的名单是它**自己选的** text-basic —— 那是个被收起的别名，
        // 所以它的目录为空。这与"托管 Key 有没有同步"无关：目录查询只认
        // client_visible，而 text-basic 被 V102/V103 收起了。
        assertThat(modelCatalogService.availableModels(ordinaryContext)
                .map(ModelCatalogService.ModelView::id).collectList().block())
                .isEmpty();
    }

    @Test
    void snapshotUpdateRequiresTheManagedKeyAndExactOwnerAndPackage() {
        ManagedApiKeyService.ClientBootstrap managed = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));
        assertThat(managed).isNotNull();
        UUID keyId = apiKeyService.authenticate(managed.apiKey())
                .block(Duration.ofSeconds(5)).apiKeyId();
        ApiKeyService.CreatedApiKey ordinary = apiKeyService.create(
                TENANT_ID, USER_ID,
                new ApiKeyService.CreateApiKeyRequest(
                        "Ordinary subset", List.of("text-basic"), "TOKEN"))
                .block(Duration.ofSeconds(5));
        assertThat(ordinary).isNotNull();
        String widerSnapshot = "[\"text-basic\",\"multimodal-basic\",\"text-pro\"]";

        assertThat(managedApiKeyRepository.synchronizeAndMarkRevealed(
                keyId, UUID.randomUUID(), USER_ID, PACKAGE_ID, widerSnapshot).block())
                .isZero();
        assertThat(managedApiKeyRepository.synchronizeAndMarkRevealed(
                keyId, TENANT_ID, UUID.randomUUID(), PACKAGE_ID, widerSnapshot).block())
                .isZero();
        assertThat(managedApiKeyRepository.synchronizeAndMarkRevealed(
                keyId, TENANT_ID, USER_ID, UUID.randomUUID(), widerSnapshot).block())
                .isZero();
        assertThat(managedApiKeyRepository.synchronizeAndMarkRevealed(
                ordinary.id(), TENANT_ID, USER_ID, PACKAGE_ID, widerSnapshot).block())
                .isZero();
        // 四次"错主/错租户/错用户/非托管 Key"的同步尝试都必须匹配 0 行，
        // 因此托管 Key 的名单保持在 Bootstrap 之后的样子（含两个客户端文本通道），
        // `widerSnapshot` 里的 text-pro **没有被写进去**。
        assertThat(apiKeyService.authenticate(managed.apiKey())
                .block(Duration.ofSeconds(5)).allowedModels())
                .containsExactlyInAnyOrder(
                        "text-basic", "multimodal-basic", "chat-standard", "chat-deep")
                .doesNotContain("text-pro");
        assertThat(apiKeyService.authenticate(ordinary.secret())
                .block(Duration.ofSeconds(5)).allowedModels())
                .containsExactly("text-basic");
    }

    @Test
    void bootstrapFailsClosedWhenManagedSnapshotCannotBeUpdated() throws Exception {
        ManagedApiKeyService.ClientBootstrap first = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));
        assertThat(first).isNotNull();
        execute("""
                UPDATE gateway.package_instance
                SET allowed_models = '[{"modelCode":"text-pro","capabilities":["TEXT"]}]'::jsonb
                WHERE id = '81000000-0000-4000-8000-000000000011'
                """);
        execute("""
                CREATE FUNCTION gateway.skip_managed_snapshot_sync() RETURNS trigger
                LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$
                """);
        try {
            execute("""
                    CREATE TRIGGER skip_managed_snapshot_sync
                    BEFORE UPDATE OF allowed_models ON gateway.api_key
                    FOR EACH ROW WHEN (OLD.managed)
                    EXECUTE FUNCTION gateway.skip_managed_snapshot_sync()
                    """);
            assertThatThrownBy(() -> managedApiKeyService.bootstrap(TENANT_ID, USER_ID, null)
                    .block(Duration.ofSeconds(5)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("client_bootstrap_unavailable");
            assertThat(singleInt("SELECT count(*) FROM gateway.admin_operation_audit "
                    + "WHERE action = 'MANAGED_API_KEY_REVEAL'"))
                    .isEqualTo(1);
        } finally {
            execute("DROP TRIGGER IF EXISTS skip_managed_snapshot_sync ON gateway.api_key");
            execute("DROP FUNCTION gateway.skip_managed_snapshot_sync()");
        }
        assertThat(apiKeyService.authenticate(first.apiKey())
                .block(Duration.ofSeconds(5)).allowedModels())
                .containsExactlyInAnyOrder(
                        "text-basic", "multimodal-basic", "chat-standard", "chat-deep");
    }

    @Test
    void servesEachDimensionIndependentlyWhenBothAreActive() throws Exception {
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    identity_key, package_plan_version_id, package_code, package_name,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, max_file_size_bytes, once_per_user, usage_dimension,
                    status, starts_at, expires_at, activated_at)
                VALUES (
                    '81000000-0000-4000-8000-000000000012',
                    '41000000-0000-4000-8000-000000000012',
                    '51000000-0000-4000-8000-000000000012',
                    '61000000-0000-4000-8000-000000000011',
                    '71000000-0000-4000-8000-000000000011',
                    'managed-key-identity-video',
                    '91000000-0000-4000-8000-000000000012',
                    'VIDEO_STARTER', 'Video Starter', 12900, 'CNY',
                    ('[{"modelCode":"video-basic","modelTokenQuota":null,'
                        || '"capabilities":["VIDEO"]}]')::jsonb,
                    60, 2, 60, 60, 2, 30, 20971520, false, 'VIDEO_SECOND',
                    'ACTIVE', now(), now() + interval '30 days', now())
                """);

        // 两个维度同时生效正是这次改动的全部目的：唯一性按维度收窄，而不是被放弃。
        assertThat(singleInt("SELECT count(*) FROM gateway.package_instance WHERE status = 'ACTIVE'"))
                .isEqualTo(2);

        ManagedApiKeyService.ClientBootstrap text = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, "TOKEN").block(Duration.ofSeconds(5));
        ManagedApiKeyService.ClientBootstrap video = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, "VIDEO_SECOND").block(Duration.ofSeconds(5));

        assertThat(text).isNotNull();
        assertThat(text.packageCode()).isEqualTo("ADVANCED");
        assertThat(text.usageDimension()).isEqualTo("TOKEN");
        assertThat(video).isNotNull();
        assertThat(video.packageCode()).isEqualTo("VIDEO_STARTER");
        assertThat(video.usageDimension()).isEqualTo("VIDEO_SECOND");
        assertThat(video.packageInstanceId())
                .isEqualTo(UUID.fromString("81000000-0000-4000-8000-000000000012"));
        // 托管 key 是每个实例一把（uq_api_key_active_managed_package），因此两把共存，
        // 且各自带着自己维度的标记。
        assertThat(singleInt("SELECT count(*) FROM gateway.api_key "
                + "WHERE managed AND status = 'ACTIVE'")).isEqualTo(2);
        assertThat(singleString("SELECT usage_dimension FROM gateway.api_key "
                + "WHERE package_instance_id = '81000000-0000-4000-8000-000000000012'"))
                .isEqualTo("VIDEO_SECOND");

        UUID textKeyId = apiKeyService.authenticate(text.apiKey())
                .block(Duration.ofSeconds(5)).apiKeyId();
        ApiKeyService.ApiKeyContext videoContext = apiKeyService.authenticate(video.apiKey())
                .block(Duration.ofSeconds(5));
        assertThat(videoContext).isNotNull();
        execute("""
                UPDATE gateway.package_instance
                SET allowed_models = '[{"modelCode":"text-pro","capabilities":["TEXT"]}]'::jsonb
                WHERE id = '81000000-0000-4000-8000-000000000011'
                """);
        ManagedApiKeyService.ClientBootstrap refreshedText = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, "TOKEN").block(Duration.ofSeconds(5));
        assertThat(refreshedText).isNotNull();
        assertThat(refreshedText.apiKey()).isEqualTo(text.apiKey());
        assertThat(apiKeyService.authenticate(refreshedText.apiKey())
                .block(Duration.ofSeconds(5)))
                .satisfies(context -> {
                    assertThat(context.apiKeyId()).isEqualTo(textKeyId);
                    assertThat(context.allowedModels()).containsExactly("text-pro");
                });
        ApiKeyService.ApiKeyContext unchangedVideo = apiKeyService.authenticate(video.apiKey())
                .block(Duration.ofSeconds(5));
        assertThat(unchangedVideo).isNotNull();
        assertThat(unchangedVideo.apiKeyId()).isEqualTo(videoContext.apiKeyId());
        assertThat(unchangedVideo.allowedModels()).containsExactly("video-basic");
        assertThat(singleInt("SELECT count(*) FROM gateway.api_key "
                + "WHERE managed AND status = 'ACTIVE' AND package_instance_id = "
                + "'81000000-0000-4000-8000-000000000012'"))
                .isEqualTo(1);

        // 维度歧义时不许替客户端猜：猜错就是拿秒数额度去跑文本调用。
        assertThatThrownBy(() -> managedApiKeyService.bootstrap(TENANT_ID, USER_ID, null)
                .block(Duration.ofSeconds(5)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("package_dimension_required");
    }

    @Test
    void rejectsAnUnknownRequestedDimension() {
        assertThatThrownBy(() -> managedApiKeyService.bootstrap(TENANT_ID, USER_ID, "VIDEO")
                .block(Duration.ofSeconds(5)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_usage_dimension");
    }


    @Test
    void rejectsMissingActivePackageWithoutCreatingAKey() throws Exception {
        execute("UPDATE gateway.package_instance SET status = 'EXPIRED', ended_at = now()");

        assertThatThrownBy(() -> managedApiKeyService.bootstrap(TENANT_ID, USER_ID, null)
                .block(Duration.ofSeconds(5)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("active_package_required");
        assertThat(singleInt("SELECT count(*) FROM gateway.api_key")).isZero();
    }

    @Test
    void rejectsOpenRefundHoldWithoutRevealingAKey() throws Exception {
        execute("""
                INSERT INTO gateway.package_refund_hold(
                    id, business_no, package_instance_id, grant_request_id,
                    original_status, status, payload_hash)
                VALUES (
                    '31000000-0000-4000-8000-000000000011', 'refund-managed-key',
                    '81000000-0000-4000-8000-000000000011',
                    '51000000-0000-4000-8000-000000000011',
                    'ACTIVE', 'WAITING', repeat('a', 64))
                """);

        assertThatThrownBy(() -> managedApiKeyService.bootstrap(TENANT_ID, USER_ID, null)
                .block(Duration.ofSeconds(5)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("package_refund_in_progress");
        assertThat(singleInt("SELECT count(*) FROM gateway.api_key")).isZero();
    }

    @Test
    void auditContainsIdsAndPrefixButNeverPlaintextOrCiphertext() {
        ManagedApiKeyService.ClientBootstrap result = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        String audit = singleString("SELECT string_agg(action || ':' || detail::text, '|') "
                + "FROM gateway.admin_operation_audit");
        assertThat(audit)
                .contains("MANAGED_API_KEY_CREATE", "MANAGED_API_KEY_REVEAL", PACKAGE_ID.toString())
                .contains("sk-yzh-")
                .doesNotContain(result.apiKey(), "secret", "ciphertext");
    }

    @Test
    void administratorRotationRevokesOldKeyAndRevealsReplacementOnlyOnce() {
        ManagedApiKeyService.ClientBootstrap bootstrap = managedApiKeyService
                .bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));
        assertThat(bootstrap).isNotNull();
        UUID oldKeyId = UUID.fromString(singleString(
                "SELECT id::text FROM gateway.api_key WHERE managed AND status = 'ACTIVE'"));
        UUID actorUserId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();

        ManagedApiKeyService.ManagedKeyRotation first = managedApiKeyService.rotate(
                TENANT_ID, actorUserId, oldKeyId, operationId).block(Duration.ofSeconds(5));
        ManagedApiKeyService.ManagedKeyRotation retried = managedApiKeyService.rotate(
                TENANT_ID, actorUserId, oldKeyId, operationId).block(Duration.ofSeconds(5));

        assertThat(first).isNotNull();
        assertThat(first.secretAvailable()).isTrue();
        assertThat(first.apiKey()).startsWith("sk-yzh-").isNotEqualTo(bootstrap.apiKey());
        assertThat(retried).isNotNull();
        assertThat(retried.newKeyId()).isEqualTo(first.newKeyId());
        assertThat(retried.newKeyPrefix()).isEqualTo(first.newKeyPrefix());
        assertThat(retried.secretAvailable()).isFalse();
        assertThat(retried.apiKey()).isNull();
        assertThat(singleInt("SELECT count(*) FROM gateway.api_key WHERE managed AND status = 'ACTIVE'"))
                .isEqualTo(1);
        assertThat(singleInt("SELECT count(*) FROM gateway.api_key WHERE managed AND status = 'REVOKED'"))
                .isEqualTo(1);
        assertThat(singleString("SELECT status FROM gateway.managed_api_key_rotation"))
                .isEqualTo("COMPLETED");
        assertThat(singleInt("SELECT count(*) FROM gateway.admin_operation_audit "
                + "WHERE action = 'MANAGED_API_KEY_ROTATE'"))
                .isEqualTo(1);
        assertThat(apiKeyService.authenticate(bootstrap.apiKey()).block(Duration.ofSeconds(5)))
                .isNull();
        assertThat(apiKeyService.authenticate(first.apiKey()).block(Duration.ofSeconds(5)))
                .extracting(ApiKeyService.ApiKeyContext::packageInstanceId)
                .isEqualTo(PACKAGE_ID);
        assertThat(singleString("SELECT detail::text FROM gateway.admin_operation_audit "
                + "WHERE action = 'MANAGED_API_KEY_ROTATE'"))
                .contains(oldKeyId.toString(), first.newKeyId().toString(), actorUserId.toString())
                .doesNotContain(bootstrap.apiKey(), first.apiKey(), "ciphertext", "secret");
    }

    @Test
    void rotationRejectsReusingOperationIdForAnotherRequest() {
        managedApiKeyService.bootstrap(TENANT_ID, USER_ID, null).block(Duration.ofSeconds(5));
        UUID keyId = UUID.fromString(singleString(
                "SELECT id::text FROM gateway.api_key WHERE managed AND status = 'ACTIVE'"));
        UUID operationId = UUID.randomUUID();
        managedApiKeyService.rotate(
                TENANT_ID, UUID.randomUUID(), keyId, operationId).block(Duration.ofSeconds(5));

        assertThatThrownBy(() -> managedApiKeyService.rotate(
                        TENANT_ID, UUID.randomUUID(), keyId, operationId)
                .block(Duration.ofSeconds(5)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("managed_api_key_rotation_operation_reused");
        assertThat(singleInt("SELECT count(*) FROM gateway.api_key WHERE managed"))
                .isEqualTo(2);
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int singleInt(String sql) {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String singleString(String sql) {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] singleBytes(String sql) {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getBytes(1);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
