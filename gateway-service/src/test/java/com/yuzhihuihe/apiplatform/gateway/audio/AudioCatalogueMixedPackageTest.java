package com.yuzhihuihe.apiplatform.gateway.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.routing.CredentialCipher;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 音频目录对**混合套餐**（视频秒 + 音频秒）的可见性，跑的是
 * {@link AudioRouteRepository#AVAILABLE_AUDIO_MODELS_SQL} 本身。
 *
 * <p>为什么这条测试必须在 {@code gateway.audio} 包里：那份 SQL 与
 * {@link AudioRouteRepository} 都是包级可见的，而其它音频测试（
 * {@code AudioModelCatalogServiceTest} / {@code AudioModelsControllerTest}）
 * 把仓库整个 mock 掉，因此「谓语放宽了没有」只有在真库上跑这条语句才可能被验证。
 *
 * <p>它钉住两个方向，缺一个都会让这条放宽变成"顺手把整个视频家族放进来"：
 *
 * <ul>
 *   <li><b>带音频秒额度的视频实例必须看得到音频别名</b> —— 否则「买了视频套餐的
 *       人也能用配音」在客户端目录里不成立：{@code GET /v1/audio-models} 返回空数组，
 *       客户端没有理由去调音频端点，而那份额度一直躺着；
 *   <li><b>纯视频实例仍然看不到</b> —— 这是这条目录一直以来的性质。
 * </ul>
 *
 * <p>期望值不是写死的字面量，而是先问一句"库里到底有哪些可售音频别名"，再用它比对：
 * 一个只把 {@code audio-tts-basic} 放进去的谓语会漏掉另外三个，而那正是本次放宽
 * 要避免的形态。
 */
@Testcontainers
class AudioCatalogueMixedPackageTest {

    private static final String GATEWAY_USER = "gateway_app";
    private static final String GATEWAY_PASSWORD = "gateway-test-password";
    private static final String PLATFORM_USER = "platform_app";
    private static final String PLATFORM_PASSWORD = "platform-test-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void createIsolatedSchemas() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + GATEWAY_USER + " LOGIN PASSWORD '" + GATEWAY_PASSWORD + "'");
            statement.execute("CREATE ROLE " + PLATFORM_USER + " LOGIN PASSWORD '" + PLATFORM_PASSWORD + "'");
            statement.execute("CREATE SCHEMA platform AUTHORIZATION " + POSTGRES.getUsername());
            statement.execute("REVOKE ALL ON SCHEMA platform FROM PUBLIC");
            statement.execute("CREATE SCHEMA gateway AUTHORIZATION " + GATEWAY_USER);
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("gateway")
                .defaultSchema("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        // 生产上这些表由 gateway_app **拥有**（迁移以它自己的身份跑），因此它对
        // package_instance / model_alias 有全部权限；而这里迁移是以超级用户跑的，
        // 表主是 postgres，gateway_app 默认只剩 USAGE。补上读写授权，夹具才与生产
        // 的权限模型一致——否则测出来的会是"权限不足"，而不是目录谓语对不对。
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA gateway TO "
                    + GATEWAY_USER);
            statement.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA gateway TO " + GATEWAY_USER);
        }
    }

    @Test
    void aVideoPackageThatAlsoSellsAudioSecondsSeesTheAudioAliases() {
        // 播种用管理员连接：gateway_app 对 package_instance **没有 INSERT 权限**
        // （那是运行期不该有的能力），而这份夹具要在库里放两份实例。
        // 被验证的仓库仍然拿着 gateway_app 的连接——因此"目录能不能看见"这个问题
        // 是在真实的权限边界内回答的。
        DatabaseClient seeding = adminDatabase();
        DatabaseClient gateway = gatewayDatabase();
        UUID mixedId = UUID.randomUUID();
        UUID videoOnlyId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        // 每份实例一个用户：uq_active_package_per_user_dimension 保证"每个用户每个维度
        // 至多一个 ACTIVE 实例"，两份实例共用 user_id 会被这条不变式拒绝——而那条不变式
        // 与本次要验证的目录可见性无关，夹具因此各给一个。
        UUID mixedUserId = UUID.randomUUID();
        UUID videoOnlyUserId = UUID.randomUUID();

        // 混合形态：主维度是视频秒，第二份额度是音频秒（平台 V103 发的那五个套餐）。
        insertPackageInstance(seeding, mixedId, tenantId, mixedUserId, "VIDEO_LIKE_MIXED",
                "VIDEO_SECOND", 200L,
                """
                [{"modelCode": "video-basic", "capabilities": ["TEXT", "VIDEO"],
                  "modelTokenQuota": null},
                 {"modelCode": "audio-asr-basic", "capabilities": ["AUDIO_TRANSCRIBE"],
                  "modelTokenQuota": null},
                 {"modelCode": "audio-asr-subtitle", "capabilities": ["AUDIO_TRANSCRIBE"],
                  "modelTokenQuota": null},
                 {"modelCode": "audio-tts-basic", "capabilities": ["AUDIO_SYNTHESIS"],
                  "modelTokenQuota": null},
                 {"modelCode": "audio-retalk", "capabilities": ["LIP_SYNC"],
                  "modelTokenQuota": null}]
                """);

        // 对照：只有视频别名、没有音频额度的实例（纯视频套餐的形态）。
        insertPackageInstance(seeding, videoOnlyId, tenantId, videoOnlyUserId, "VIDEO_LIKE_ONLY",
                "VIDEO_SECOND", null,
                """
                [{"modelCode": "video-basic", "capabilities": ["TEXT", "VIDEO"],
                  "modelTokenQuota": null}]
                """);

        // 可售别名的清单同样用管理员连接读：gateway_app 对 model_alias 没有 SELECT
        // 权限（那是目录 SQL 自己的事，它按表在迁移里被授过权）。这份清单只是本测试
        // 的期望值来源，不是被验证的路径。
        List<String> sellable = seeding.sql("""
                        SELECT DISTINCT alias.alias
                        FROM gateway.model_alias alias
                        JOIN gateway.model model ON model.id = alias.model_id AND model.enabled
                        JOIN gateway.audio_alias_tier tier ON tier.model_code = alias.alias
                        WHERE model.capabilities @> '["AUDIO_TRANSCRIBE"]'::jsonb
                           OR model.capabilities @> '["AUDIO_SYNTHESIS"]'::jsonb
                           OR model.capabilities @> '["LIP_SYNC"]'::jsonb
                        ORDER BY 1
                        """)
                .map((row, metadata) -> row.get("alias", String.class))
                .all().collectList().block(Duration.ofSeconds(5));
        assertThat(sellable)
                .as("the gateway must sell at least the four aliases platform V103 grants")
                .contains("audio-asr-basic", "audio-asr-subtitle",
                        "audio-tts-basic", "audio-retalk");

        AudioRouteRepository repository =
                new AudioRouteRepository(gateway, new CredentialCipher(""));
        List<String> mixed = repository.availableModels(mixedId, tenantId, mixedUserId)
                .map(AudioRouteRepository.AudioModelCatalogRow::alias)
                .collectList().block(Duration.ofSeconds(5));
        List<String> videoOnly = repository.availableModels(videoOnlyId, tenantId, videoOnlyUserId)
                .map(AudioRouteRepository.AudioModelCatalogRow::alias)
                .collectList().block(Duration.ofSeconds(5));

        assertThat(mixed)
                .as(diagnose(seeding, mixedId, tenantId, mixedUserId))
                .containsExactlyElementsOf(sellable);
        assertThat(videoOnly).isEmpty();
    }

    /**
     * 失败时把目录谓语拆成逐条计数，而不是只报"期望 4 个、实际 0 个"。
     *
     * <p>这条 SQL 有六道谓词（实例 id / 租户 / 用户 / 状态 / 有效期 / 音频资格），
     * 任何一条不成立都表现为同一个空数组，而从断言消息里看不出是哪一条。
     * 同样的推理见 V103 的迁移注释：断言必须让下一个人知道**该改哪里**。
     */
    private String diagnose(DatabaseClient database, UUID instanceId, UUID tenantId, UUID userId) {
        long aliasRows = scalar(database, """
                SELECT count(*) AS value FROM gateway.model_alias alias
                JOIN gateway.model model ON model.id = alias.model_id AND model.enabled
                JOIN gateway.audio_alias_tier tier ON tier.model_code = alias.alias
                WHERE model.capabilities @> '["AUDIO_TRANSCRIBE"]'::jsonb
                   OR model.capabilities @> '["AUDIO_SYNTHESIS"]'::jsonb
                   OR model.capabilities @> '["LIP_SYNC"]'::jsonb
                """);
        long instanceRows = scalar(database, """
                SELECT count(*) AS value FROM gateway.package_instance package
                WHERE package.id = :id AND package.tenant_id = :tenant AND package.user_id = :user
                  AND package.status = 'ACTIVE' AND package.expires_at > now()
                  AND coalesce(package.audio_seconds_quota, 0) > 0
                """, "id", instanceId, "tenant", tenantId, "user", userId);
        long allowedRows = scalar(database, """
                SELECT count(*) AS value FROM gateway.package_instance package
                JOIN gateway.model_alias routing_alias ON EXISTS (
                     SELECT 1 FROM jsonb_array_elements(package.allowed_models) allowed
                     WHERE allowed ->> 'modelCode' = routing_alias.alias)
                WHERE package.id = :id
                """, "id", instanceId);
        long catalogueRows = scalar(database, """
                SELECT count(*) AS value
                FROM gateway.package_instance package
                JOIN gateway.model_alias routing_alias
                  ON EXISTS (
                       SELECT 1
                       FROM jsonb_array_elements(package.allowed_models) allowed
                       WHERE allowed ->> 'modelCode' = routing_alias.alias)
                JOIN gateway.model routing_model
                  ON routing_model.id = routing_alias.model_id
                 AND routing_model.enabled
                 AND (routing_model.capabilities @> '["AUDIO_TRANSCRIBE"]'::jsonb
                      OR routing_model.capabilities @> '["AUDIO_SYNTHESIS"]'::jsonb
                      OR routing_model.capabilities @> '["LIP_SYNC"]'::jsonb)
                JOIN gateway.audio_alias_tier tier ON tier.model_code = routing_alias.alias
                WHERE package.id = :id AND package.tenant_id = :tenant AND package.user_id = :user
                  AND package.status = 'ACTIVE' AND package.expires_at > now()
                  AND (package.usage_dimension = 'AUDIO_SECOND'
                       OR coalesce(package.audio_seconds_quota, 0) > 0)
                """, "id", instanceId, "tenant", tenantId, "user", userId);
        return "sellable=" + aliasRows + " instance-predicates=" + instanceRows
                + " allowed-model-join=" + allowedRows + " full-catalogue=" + catalogueRows;
    }

    /** 一条只返回单个计数的诊断查询；绑定按 name/value 成对给出。 */
    private long scalar(DatabaseClient database, String sql, Object... nameValuePairs) {
        var spec = database.sql(sql);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            spec = spec.bind((String) nameValuePairs[i], nameValuePairs[i + 1]);
        }
        Long value = spec.map((row, metadata) -> row.get("value", Long.class))
                .one().block(Duration.ofSeconds(5));
        return value == null ? -1 : value;
    }

    /**
     * 插一份 {@code package_instance}。列清单与 V101 的探针行一致，因此这里的失败
     * 只可能来自自己写错的列，而不是某个漏掉的 NOT NULL。
     */
    private void insertPackageInstance(
            DatabaseClient database,
            UUID id,
            UUID tenantId,
            UUID userId,
            String packageCode,
            String usageDimension,
            Long audioSecondsQuota,
            String allowedModels) {
        var statement = database.sql("""
                        INSERT INTO gateway.package_instance(
                            id, grant_event_id, grant_request_id, tenant_id, user_id,
                            package_plan_version_id, package_code, package_name,
                            package_kind, price_amount, allowed_models, total_token_quota,
                            max_api_keys, requests_per_minute, tokens_per_minute,
                            max_concurrency, duration_days, max_file_size_bytes,
                            identity_key, once_per_user, usage_dimension, status,
                            starts_at, expires_at, activated_at, audio_seconds_quota)
                        VALUES (:id, gen_random_uuid(), gen_random_uuid(), :tenant_id,
                            :user_id, gen_random_uuid(), :package_code, :package_code,
                            'STANDARD', 100, CAST(:allowed_models AS jsonb), 600,
                            1, 1, 1, 1, 30, 20971520,
                            'audio-catalogue-test', false, :usage_dimension, 'ACTIVE',
                            now(), now() + interval '30 days', now(),
                            CAST(:audio_seconds_quota AS bigint))
                        """)
                .bind("id", id)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("package_code", packageCode)
                .bind("allowed_models", allowedModels)
                .bind("usage_dimension", usageDimension);
        // 纯视频对照实例的额度是 NULL：R2DBC 的 bind 不接受 null，必须显式 bindNull
        // （SQL 里那个 CAST 让 null 也走同一条语句，两种形态共用一份列清单）。
        statement = audioSecondsQuota == null
                ? statement.bindNull("audio_seconds_quota", Long.class)
                : statement.bind("audio_seconds_quota", audioSecondsQuota);
        statement.fetch().rowsUpdated().block(Duration.ofSeconds(3));
    }

    private static DatabaseClient gatewayDatabase() {
        return client(GATEWAY_USER, GATEWAY_PASSWORD);
    }

    /** 播种用的管理员连接：只有它有 package_instance 的 INSERT 权限。 */
    private static DatabaseClient adminDatabase() {
        return client(POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static DatabaseClient client(String user, String password) {
        String url = "r2dbc:postgresql://" + user + ":" + password
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/" + POSTGRES.getDatabaseName();
        return DatabaseClient.create(ConnectionFactories.get(url));
    }
}
