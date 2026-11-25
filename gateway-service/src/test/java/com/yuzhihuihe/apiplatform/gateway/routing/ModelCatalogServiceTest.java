package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.multimodal.MultimodalProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ModelCatalogServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("91000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("91000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID = UUID.fromString("91000000-0000-4000-8000-000000000003");
    private static final UUID API_KEY_ID = UUID.fromString("91000000-0000-4000-8000-000000000004");
    private static final UUID BAILIAN_CHANNEL = UUID.fromString("84000000-0000-4000-8000-000000000002");
    private static final UUID TIANYI_CHAT_CHANNEL =
            UUID.fromString("84000000-0000-4000-8000-000000000074");

    /**
     * 一个 **AUDIO_SECOND** 套餐实例。
     *
     * <p>它的 {@code allowed_models} 刻意是"混合"的（音频 + 一个文本别名）：
     * 平台侧的发布校验不会产生这种数据，但这条目录查询必须**自己**对维度失效关闭，
     * 而不能依赖上游数据永远正确。因此这个夹具用来隔离"维度防线"。
     */
    private static final UUID AUDIO_PACKAGE_ID = UUID.fromString("91000000-0000-4000-8000-000000000009");

    /**
     * 客户端目录里**唯一**可见的文本别名（V102 建立、V103 收敛到这两个）。
     *
     * <p>夹具里它们都在套餐的 {@code allowed_models} 里——与 2026-10-09 生产故障
     * 时的套餐一致（那一侧是运维补过的），而当时**托管 Key** 那一侧没有。
     */
    private static final Set<String> CLIENT_TEXT_CHANNELS = Set.of("chat-standard", "chat-deep");

    /**
     * 故障当天那把 Key 的快照：全是 V103 之后已从客户端目录里收起的别名。
     *
     * <p>它非空、套餐也生效——这正是为什么当时的诊断原因码会是
     * {@code no_chat_models}（"套餐不含聊天模型"），而真实原因是两份名单不交叉。
     */
    private static final Set<String> STALE_KEY_SNAPSHOT =
            Set.of("multimodal-basic", "text-max", "vision-pro");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static ModelCatalogService catalog;
    static ChannelCircuitRegistry circuits;
    static DatabaseClient databaseClient;

    @BeforeAll
    static void migrateAndSeedPermissions() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway").schemas("gateway")
                .locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE gateway.channel SET last_health_status = 'UNHEALTHY';
                    UPDATE gateway.channel SET last_health_status = 'HEALTHY'
                    WHERE id = '84000000-0000-4000-8000-000000000002';
                    INSERT INTO gateway.package_instance(
                        id, grant_event_id, grant_request_id, tenant_id, user_id,
                        package_plan_version_id, package_code, package_name, price_amount,
                        allowed_models, total_token_quota, max_api_keys, requests_per_minute,
                        tokens_per_minute, max_concurrency, duration_days, status,
                        starts_at, expires_at, activated_at, identity_key)
                    VALUES (
                        '91000000-0000-4000-8000-000000000003',
                        '91000000-0000-4000-8000-000000000005',
                        '91000000-0000-4000-8000-000000000006',
                        '91000000-0000-4000-8000-000000000001',
                        '91000000-0000-4000-8000-000000000002',
                        '91000000-0000-4000-8000-000000000007',
                        'ADVANCED', 'Advanced', 19900,
                        -- 平台卖出去的老别名 + 运维补上的两个客户端入口（故障当天的
                        -- 生产形态）。四个 text-* 与 multimodal-basic 的能力里同样带
                        -- TEXT，但 V102/V103 之后它们不再出现在客户端目录里。
                        '[{"modelCode":"text-basic"},{"modelCode":"text-pro"},
                          {"modelCode":"multimodal-basic"},
                          {"modelCode":"chat-standard"},{"modelCode":"chat-deep"}]',
                        20000000, 10, 100, 500000, 15, 30, 'ACTIVE',
                        now(), now() + interval '30 days', now(),
                        '91000000-0000-4000-8000-000000000002');
                    INSERT INTO gateway.package_instance_model_quota(
                        id, package_instance_id, model_code, token_quota, used_tokens)
                    VALUES (
                        '91000000-0000-4000-8000-000000000008',
                        '91000000-0000-4000-8000-000000000003',
                        'text-pro', 100, 100),
                        (
                        '91000000-0000-4000-8000-00000000000d',
                        '91000000-0000-4000-8000-000000000003',
                        'chat-standard', 100, 100);
                    INSERT INTO gateway.channel_model(
                        id, channel_id, model_id, upstream_model, enabled, capabilities)
                    SELECT gen_random_uuid(), '84000000-0000-4000-8000-000000000002',
                           model.id, 'video-upstream', true, '["TEXT","IMAGE","VIDEO"]'::jsonb
                    FROM gateway.model model
                    WHERE model.code = 'video-basic';
                    """);
            statement.executeUpdate("""
                    INSERT INTO gateway.package_instance(
                        id, grant_event_id, grant_request_id, tenant_id, user_id,
                        package_plan_version_id, package_code, package_name, price_amount,
                        allowed_models, total_token_quota, max_api_keys, requests_per_minute,
                        tokens_per_minute, max_concurrency, duration_days, status,
                        starts_at, expires_at, activated_at, identity_key, usage_dimension)
                    VALUES (
                        '91000000-0000-4000-8000-000000000009',
                        '91000000-0000-4000-8000-00000000000a',
                        '91000000-0000-4000-8000-00000000000b',
                        '91000000-0000-4000-8000-000000000001',
                        '91000000-0000-4000-8000-000000000002',
                        '91000000-0000-4000-8000-00000000000c',
                        'AUDIO_BASIC', 'Audio Basic', 4990,
                        '[{"modelCode":"audio-asr-basic"},{"modelCode":"audio-asr-subtitle"},
                          {"modelCode":"text-basic"}]',
                        600, 10, 60, 60000, 4, 30, 'ACTIVE',
                        now(), now() + interval '30 days', now(),
                        '91000000-0000-4000-8000-00000000000a', 'AUDIO_SECOND');
                    """);
        }
        String r2dbcUrl = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
                + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName();
        databaseClient = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl));
        circuits = new ChannelCircuitRegistry(CircuitBreakerRegistry.ofDefaults());
        catalog = new ModelCatalogService(new ModelCatalogRepository(databaseClient), circuits);
    }

    private static ApiKeyService.ApiKeyContext packageKey(Set<String> allowedModels) {
        return new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, allowedModels);
    }

    @Test
    void returnsAliasesAllowedByKeyAndPackageWithoutHidingExhaustedModelQuota() {
        // Key 的快照里同时有"客户端入口"与"已从目录里收起的内部别名"，
        // 后者不该出现；chat-standard 的每模型自额度已经用尽（夹具里 100/100），
        // 但目录是**权益视图**，用量耗尽不该把它藏起来。
        ApiKeyService.ApiKeyContext context =
                packageKey(Set.of("text-basic", "text-max", "chat-standard", "chat-deep"));

        assertThat(catalog.availableModels(context).map(ModelCatalogService.ModelView::id)
                .collectList().block()).containsExactly("chat-deep", "chat-standard");

        circuits.circuitBreaker(TIANYI_CHAT_CHANNEL, "chat-standard")
                .transitionToForcedOpenState();
        assertThat(catalog.availableModels(context).map(ModelCatalogService.ModelView::id)
                .collectList().block()).containsExactly("chat-deep", "chat-standard");
        circuits.circuitBreaker(TIANYI_CHAT_CHANNEL, "chat-standard").reset();
    }

    /**
     * 2026-10-09 生产故障的形态：**套餐那一侧有**两个客户端文本通道，而**这把 Key
     * 的快照停在 V102/V103 之前**（只有 text-* / vision-* / multimodal-basic）。
     *
     * <p>两个方向都钉住：故障时目录为空、原因码指向"名单不一致"（而不是误导性的
     * {@code no_chat_models}）；名单对齐之后（Bootstrap 的 reconcileEntitlement /
     * 迁移 V109 做的事）两个入口立刻可用。
     */
    @Test
    void anOutOfSyncKeySeesAnEmptyCatalogueAndSaysWhy() {
        ApiKeyService.ApiKeyContext stale = packageKey(STALE_KEY_SNAPSHOT);

        assertThat(catalog.availableModels(stale).collectList().block()).isEmpty();
        assertThat(catalog.emptyReason(stale).block())
                .isEqualTo("key_allowed_models_out_of_sync");

        ApiKeyService.ApiKeyContext aligned = packageKey(Set.of(
                "multimodal-basic", "text-max", "vision-pro", "chat-standard", "chat-deep"));

        assertThat(catalog.availableModels(aligned).map(ModelCatalogService.ModelView::id)
                .collectList().block()).containsExactly("chat-deep", "chat-standard");
        // 注意：emptyReason 只由控制器在目录为空时调用，它自己不查目录，因此不能拿
        // "非空目录 + 非空 reason" 当矛盾——上面那条断言才是这份名单的契约。
    }

    @Test
    void walletOnlyKeyWithoutPackageListsItsAllowedDirectoryModels() {
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, null, Set.of("chat-standard"));

        assertThat(catalog.availableModels(context).map(ModelCatalogService.ModelView::id)
                .collectList().block()).containsExactly("chat-standard");
    }

    @Test
    void keepsPackageModelsVisibleWhenOverallQuotaIsExhausted() {
        ApiKeyService.ApiKeyContext context = packageKey(Set.of("chat-standard"));
        databaseClient.sql("""
                        UPDATE gateway.package_instance
                        SET used_tokens = total_token_quota
                        WHERE id = :package_id
                        """)
                .bind("package_id", PACKAGE_ID).fetch().rowsUpdated().block();
        try {
            assertThat(catalog.availableModels(context).map(ModelCatalogService.ModelView::id)
                    .collectList().block()).containsExactly("chat-standard");
        } finally {
            databaseClient.sql("""
                            UPDATE gateway.package_instance
                            SET used_tokens = 0, reserved_tokens = 0
                            WHERE id = :package_id
                            """)
                    .bind("package_id", PACKAGE_ID).fetch().rowsUpdated().block();
        }
    }

    @Test
    void neverListsVideoAliasesInTheGenericModelCatalog() {
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, null, Set.of("video-basic"));

        assertThat(catalog.availableModels(context).collectList().block()).isEmpty();
    }

    /**
     * P1-1 回归（模型级防线）：即使 Key 的快照里写着音频别名，它也不能出现在
     * 对话目录里。这里刻意用"无套餐"（钱包 Key）场景——那条分支不经过
     * {@code package.allowed_models}，因此唯一能挡住音频的就是模型自身的判据
     * （{@code capabilities} 白名单 / {@code audio_alias_tier}）。
     *
     * <p>正对照是 {@code chat-standard}（客户端目录里唯一的两个文本入口之一）：
     * 它出现证明这条夹具与 Key 绑定是活的，音频别名缺席才是被测行为。
     */
    @Test
    void neverListsAudioAliasesEvenWhenTheKeySnapshotAllowsThem() {
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, null,
                Set.of("audio-asr-basic", "audio-retalk", "chat-standard"));

        assertThat(catalog.availableModels(context).map(ModelCatalogService.ModelView::id)
                .collectList().block()).containsExactly("chat-standard");
    }

    /**
     * V102/V103 的产品规则：客户端目录里的文本入口**只有** chat-standard / chat-deep。
     *
     * <p>被收起的是四条纯文本别名（text-basic / text-standard / text-pro / text-max）
     * 与按文本入口售卖的 multimodal-basic：它们仍在套餐的 {@code allowed_models} 里、
     * 第三方兼容客户端仍可**按名字调用**，只是不再出现在这个列表里。这条断言是那条
     * 规则在网关侧的唯一事实来源；一旦有人把 {@code client_visible} 改回默认值，
     * 客户端的选择器就会重新长出四五个内部模型名。
     */
    @Test
    void onlyTheTwoClientTextChannelsAppearInTheGenericCatalogue() {
        ApiKeyService.ApiKeyContext context = packageKey(Set.of(
                "text-basic", "text-pro", "multimodal-basic", "chat-standard", "chat-deep"));

        assertThat(catalog.availableModels(context).map(ModelCatalogService.ModelView::id)
                .collectList().block()).containsExactly("chat-deep", "chat-standard");
    }

    /**
     * P1-1 回归（维度防线）：AUDIO_SECOND 套餐**整体**不进对话目录。
     *
     * <p>写成"正/负对照"而不是单边断言：同一把 Key、同一组 allowed 别名，
     * 只是套餐从 TOKEN 换成 AUDIO_SECOND。正对照证明夹具与 Key 绑定是活的
     * （否则"空"可以因为任何与维度无关的原因而成立），负对照才是被测行为。
     * 夹具的 AUDIO_SECOND 套餐 {@code allowed_models} 里故意同时含 `text-basic`，
     * 因此这条断言只可能被 {@code <> 'AUDIO_SECOND'} 满足。
     */
    @Test
    void neverListsAnythingFromAnAudioSecondPackageInTheGenericModelCatalog() {
        Set<String> allowed = Set.of("audio-asr-basic", "audio-asr-subtitle", "chat-standard");
        ApiKeyService.ApiKeyContext tokenPackage = packageKey(allowed);
        ApiKeyService.ApiKeyContext audioPackage = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, AUDIO_PACKAGE_ID, allowed);

        assertThat(catalog.availableModels(tokenPackage).map(ModelCatalogService.ModelView::id)
                .collectList().block()).containsExactly("chat-standard");
        assertThat(catalog.availableModels(audioPackage).collectList().block()).isEmpty();
    }

    /**
     * P1-3 回归：{@code capabilities} 必须按字母序返回。
     *
     * <p>修复前它来自 {@code Set.copyOf(...)}，迭代顺序未定义（OpenJDK 按进程
     * 启动时的随机盐），同一行数据在不同进程里会序列化成不同顺序的数组。
     *
     * <p>多能力集合的样本只剩 multimodal-basic（TEXT+IMAGE+FILE），而它按 V103
     * 已不在客户端目录里，因此这里临时把它放回目录、断言完再恢复：这条测试钉的是
     * **顺序的确定性**，与可见性无关。
     */
    @Test
    void returnsCapabilitiesInStableAlphabeticalOrder() {
        setClientVisible("multimodal-basic", true);
        try {
            ApiKeyService.ApiKeyContext context = packageKey(Set.of("multimodal-basic"));

            var view = catalog.availableModels(context).single().block();

            assertThat(view.capabilities()).containsExactly("FILE", "IMAGE", "TEXT");
            // 路由策略原样透传（COMPOSITE 由请求路径读取，目录不下发它自己的判断）。
            assertThat(view.routingStrategy()).isEqualTo("COMPOSITE");
        } finally {
            setClientVisible("multimodal-basic", false);
        }
    }

    /**
     * P1-5 回归：空目录必须能说清"为什么空"。
     *
     * <p>修复前四种情况在客户端看来是同一个响应（{@code 200 + data: []}）：
     * 套餐不含聊天模型 / 套餐过期 / Key 快照为空 / 根本没绑套餐。
     * 客户端只能显示"暂无可用模型"，给不出可操作提示。
     */
    @Test
    void explainsWhyTheCatalogueIsEmptyInsteadOfReturningABareEmptyArray() {
        // ① 按秒计费的音频套餐：生效、未过期，但没有任何聊天模型。
        assertThat(catalog.emptyReason(new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, AUDIO_PACKAGE_ID, Set.of("audio-asr-basic"))).block())
                .isEqualTo("no_chat_models");

        // ② 这条 Key 根本没有绑套餐（钱包 Key）。
        assertThat(catalog.emptyReason(new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, null, Set.of("text-basic"))).block())
                .isEqualTo("no_package_bound");

        // ③ Key 的快照里没有任何模型。
        assertThat(catalog.emptyReason(new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of())).block())
                .isEqualTo("key_has_no_allowed_models");

        // ④ 套餐实例不存在（已被删除/ID 不属于该租户）。
        assertThat(catalog.emptyReason(new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, UUID.randomUUID(), Set.of("text-basic"))).block())
                .isEqualTo("package_not_found");
    }

    @Test
    void reportsAnExpiredPackageRatherThanAnEmptyCatalogue() {
        ApiKeyService.ApiKeyContext context = packageKey(Set.of("chat-standard"));
        databaseClient.sql("""
                        UPDATE gateway.package_instance
                        SET expires_at = now() - interval '1 day'
                        WHERE id = :package_id
                        """)
                .bind("package_id", PACKAGE_ID).fetch().rowsUpdated().block();
        try {
            assertThat(catalog.availableModels(context).collectList().block()).isEmpty();
            assertThat(catalog.emptyReason(context).block()).isEqualTo("package_expired");
        } finally {
            databaseClient.sql("""
                            UPDATE gateway.package_instance
                            SET expires_at = now() + interval '30 days'
                            WHERE id = :package_id
                            """)
                    .bind("package_id", PACKAGE_ID).fetch().rowsUpdated().block();
        }
    }

    /**
     * 目录是**权益视图**：承载渠道被停用、路由能力被清空时，入口仍然列出来。
     *
     * <p>这一条在生产上有实际后果：渠道故障期间把模型藏起来，会让客户端把
     * "暂时打不通"显示成"你没有这个模型"，用户会去查订单而不是等恢复。
     */
    @Test
    void keepsAliasVisibleWhenExecutionRouteIsNotReady() {
        ApiKeyService.ApiKeyContext context = packageKey(Set.of("chat-standard"));
        try {
            databaseClient.sql("""
                            UPDATE gateway.channel_model SET capabilities = '[]'::jsonb
                            WHERE channel_id = :channel_id
                              AND model_id = (SELECT model_id FROM gateway.model_alias
                                              WHERE alias = 'chat-standard')
                            """)
                    .bind("channel_id", TIANYI_CHAT_CHANNEL).fetch().rowsUpdated().block();
            databaseClient.sql("""
                            UPDATE gateway.channel
                            SET enabled = false, last_health_status = 'UNHEALTHY'
                            WHERE id = :channel_id
                            """)
                    .bind("channel_id", TIANYI_CHAT_CHANNEL).fetch().rowsUpdated().block();
            assertThat(catalog.availableModels(context).map(ModelCatalogService.ModelView::id)
                    .collectList().block()).containsExactly("chat-standard");
        } finally {
            databaseClient.sql("""
                            UPDATE gateway.channel_model SET capabilities = '["TEXT"]'::jsonb
                            WHERE channel_id = :channel_id
                              AND model_id = (SELECT model_id FROM gateway.model_alias
                                              WHERE alias = 'chat-standard')
                            """)
                    .bind("channel_id", TIANYI_CHAT_CHANNEL).fetch().rowsUpdated().block();
            databaseClient.sql("""
                            UPDATE gateway.channel
                            SET enabled = true, last_health_status = 'HEALTHY'
                            WHERE id = :channel_id
                            """)
                    .bind("channel_id", TIANYI_CHAT_CHANNEL).fetch().rowsUpdated().block();
        }
    }

    /**
     * 目录不读任何**运行时**策略版本：{@code MultimodalProperties} 的抽取器版本与
     * 路由策略不一致时，客户端入口照样列出（能不能执行是请求路径的事）。
     *
     * <p>这条与 {@link #keepsAliasVisibleWhenExecutionRouteIsNotReady} 是同一性质的
     * 两个方向：权益视图不因为运行时状态而增减。V103 之后客户端目录里没有 COMPOSITE
     * 别名，因此样本换成两个文本通道。
     */
    @Test
    void keepsTheClientEntryVisibleWhateverTheRuntimePolicyVersionIs() {
        MultimodalProperties properties = new MultimodalProperties();
        properties.setExtractorVersion("multimodal-v2");
        ModelCatalogService incompatibleCatalog = new ModelCatalogService(
                new ModelCatalogRepository(databaseClient), circuits, properties);
        ApiKeyService.ApiKeyContext context = packageKey(CLIENT_TEXT_CHANNELS);

        assertThat(incompatibleCatalog.availableModels(context)
                .map(ModelCatalogService.ModelView::id).collectList().block())
                .containsExactly("chat-deep", "chat-standard");

        properties.setExtractorVersion("multimodal-v1");
        ModelCatalogService matchingCatalog = new ModelCatalogService(
                new ModelCatalogRepository(databaseClient), circuits, properties);
        assertThat(matchingCatalog.availableModels(context)
                .map(ModelCatalogService.ModelView::id).collectList().block())
                .containsExactly("chat-deep", "chat-standard");
    }

    private static void setClientVisible(String modelCode, boolean visible) {
        databaseClient.sql("""
                        UPDATE gateway.model SET client_visible = :visible WHERE code = :code
                        """)
                .bind("visible", visible)
                .bind("code", modelCode)
                .fetch().rowsUpdated().block();
    }
}
