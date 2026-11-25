package com.yuzhihuihe.apiplatform.gateway.db;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V109 是**回填**，不是建表：它只修改部署时就已经存在的套餐实例与托管 Key，
 * 在一个干净的库上（Flyway 跑它时两张表都还是空的）它什么也做不了，
 * 因此"跑完迁移再看"的测试观察不到它。这里换一种方式（与
 * {@link TokenPackageLimitBackfillMigrationTest} 同一手法）：先按"故障当天线上
 * 的样子"插入夹具，再把迁移文件本身执行一遍，然后断言结果。
 *
 * <p>夹具覆盖的是那条规则的两侧，以及它与相邻规则的边界：
 *
 * <ul>
 *   <li><b>只卖了老文本别名的套餐</b>（V102/V103 之前卖出去的样子）：必须补上
 *       两个客户端文本通道，且原有条目（含每模型自额度）一字不动；
 *   <li><b>套餐已经补过、但托管 Key 没有</b>：这正是 2026-10-09 生产故障的形态
 *       （Bootstrap 回 5 个别名、{@code /v1/models} 回空数组），Key 必须被对齐，
 *       而套餐不该被重复追加；
 *   <li><b>视频 / 音频维度的实例</b>：不卖文本，一个通道都不该拿到；
 *   <li><b>只卖视觉别名的 TOKEN 套餐</b>：vision-pro 的能力含 TEXT，它就在客户端
 *       文本目录的判据之内，因此也该拿到两个入口；
 *   <li><b>自助创建的 Key 与已作废的 Key</b>：不在本次范围内，必须原样不动
 *       （前者是用户自己挑的子集，扩大它需要产品确认）；
 *   <li><b>本来就一致的托管 Key</b>：连 {@code updated_at} 都不该动，
 *       证明这条回填没有写放大；
 *   <li><b>跑两遍</b>：结果必须完全相同。
 * </ul>
 *
 * <p>每个 ACTIVE 实例各占一个用户：{@code uq_active_package_per_user_dimension}
 * （一个用户一个维度只能有一个生效实例）与 {@code uq_api_key_active_managed_package}
 * （一份实例只能有一把生效的托管 Key）都是生产上的不变量，夹具必须尊重它们，
 * 否则测的就不是那条迁移而是约束本身。
 */
@Testcontainers
class ClientTextChannelEntitlementMigrationTest {

    private static final String MIGRATION =
            "db/migration/V109__client_text_channels_and_managed_key_entitlement.sql";

    private static final UUID TENANT_ID = UUID.fromString("a1000000-0000-4000-8000-000000000001");

    /** 只卖了老文本别名的 TOKEN 套餐（V102 之前卖出去的样子）。 */
    private static final UUID LEGACY_TEXT_PACKAGE =
            UUID.fromString("a1000000-0000-4000-8000-000000000011");
    private static final UUID LEGACY_TEXT_USER =
            UUID.fromString("a1000000-0000-4000-8000-000000000051");
    /** 生产故障形态：套餐已经有这两个通道，托管 Key 没有。 */
    private static final UUID ALREADY_GRANTED_PACKAGE =
            UUID.fromString("a1000000-0000-4000-8000-000000000012");
    private static final UUID ALREADY_GRANTED_USER =
            UUID.fromString("a1000000-0000-4000-8000-000000000052");
    /** 套餐与托管 Key 本来就一致：回填不该写这一行。 */
    private static final UUID IN_SYNC_PACKAGE =
            UUID.fromString("a1000000-0000-4000-8000-000000000013");
    private static final UUID IN_SYNC_USER =
            UUID.fromString("a1000000-0000-4000-8000-000000000053");
    /** 按秒计费的视频套餐：不卖文本。 */
    private static final UUID VIDEO_PACKAGE = UUID.fromString("a1000000-0000-4000-8000-000000000014");
    private static final UUID VIDEO_USER = UUID.fromString("a1000000-0000-4000-8000-000000000054");
    /** 按秒计费的音频套餐：不卖文本。 */
    private static final UUID AUDIO_PACKAGE = UUID.fromString("a1000000-0000-4000-8000-000000000015");
    private static final UUID AUDIO_USER = UUID.fromString("a1000000-0000-4000-8000-000000000055");
    /** TOKEN 套餐，但只卖了视觉别名（能力里带 TEXT）。 */
    private static final UUID VISION_ONLY_PACKAGE =
            UUID.fromString("a1000000-0000-4000-8000-000000000016");
    private static final UUID VISION_ONLY_USER =
            UUID.fromString("a1000000-0000-4000-8000-000000000056");
    /** 已终结的实例：带通道也不该被回填（它的历史快照不该被改写）。 */
    private static final UUID REVOKED_PACKAGE = UUID.fromString("a1000000-0000-4000-8000-000000000017");
    private static final UUID REVOKED_USER = UUID.fromString("a1000000-0000-4000-8000-000000000057");

    private static final UUID LEGACY_MANAGED_KEY =
            UUID.fromString("a1000000-0000-4000-8000-000000000021");
    private static final UUID STALE_MANAGED_KEY =
            UUID.fromString("a1000000-0000-4000-8000-000000000022");
    private static final UUID ALREADY_IN_SYNC_KEY =
            UUID.fromString("a1000000-0000-4000-8000-000000000023");
    private static final UUID VIDEO_MANAGED_KEY =
            UUID.fromString("a1000000-0000-4000-8000-000000000024");
    private static final UUID AUDIO_MANAGED_KEY =
            UUID.fromString("a1000000-0000-4000-8000-000000000025");
    private static final UUID SELF_SERVICE_KEY =
            UUID.fromString("a1000000-0000-4000-8000-000000000026");
    private static final UUID REVOKED_MANAGED_KEY =
            UUID.fromString("a1000000-0000-4000-8000-000000000027");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway").schemas("gateway")
                .locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void seedWhatWasAlreadySold() throws Exception {
        execute("TRUNCATE gateway.api_key, gateway.package_instance CASCADE");

        // ① 老形态：四条纯文本别名 + 多模态 + 视觉，其中 text-basic 带一份每模型自额度。
        insertPackage(LEGACY_TEXT_PACKAGE, LEGACY_TEXT_USER, "ADVANCED", "TOKEN", "ACTIVE",
                """
                [{"modelCode":"text-basic","modelTokenQuota":100,"capabilities":["TEXT"]},
                 {"modelCode":"text-standard","modelTokenQuota":null,"capabilities":["TEXT"]},
                 {"modelCode":"multimodal-basic","modelTokenQuota":null,
                  "capabilities":["TEXT","IMAGE","FILE"]},
                 {"modelCode":"vision-pro","modelTokenQuota":null,"capabilities":["TEXT","IMAGE"]}]
                """);
        insertKey(LEGACY_MANAGED_KEY, LEGACY_TEXT_USER, LEGACY_TEXT_PACKAGE, true, "ACTIVE",
                """
                ["multimodal-basic","text-basic","text-standard","vision-pro"]
                """);
        // 同一份实例上：自助创建的 Key 不在范围内。
        insertKey(SELF_SERVICE_KEY, LEGACY_TEXT_USER, LEGACY_TEXT_PACKAGE, false, "ACTIVE",
                """
                ["text-basic"]
                """);
        // 已作废的托管 Key 不该被复活成"与套餐一致"的样子。
        insertKey(REVOKED_MANAGED_KEY, LEGACY_TEXT_USER, LEGACY_TEXT_PACKAGE, true, "REVOKED",
                """
                ["text-basic"]
                """);

        // ② 生产故障形态：套餐有 5 个别名（含两个通道），Key 停在老名单上。
        insertPackage(ALREADY_GRANTED_PACKAGE, ALREADY_GRANTED_USER, "ADVANCED", "TOKEN", "ACTIVE",
                """
                [{"modelCode":"multimodal-basic","modelTokenQuota":null,
                  "capabilities":["TEXT","IMAGE","FILE"]},
                 {"modelCode":"text-max","modelTokenQuota":3600,"capabilities":["TEXT"]},
                 {"modelCode":"vision-pro","modelTokenQuota":null,"capabilities":["TEXT","IMAGE"]},
                 {"modelCode":"chat-standard","modelTokenQuota":null,"capabilities":["TEXT"]},
                 {"modelCode":"chat-deep","modelTokenQuota":null,"capabilities":["TEXT"]}]
                """);
        insertKey(STALE_MANAGED_KEY, ALREADY_GRANTED_USER, ALREADY_GRANTED_PACKAGE, true, "ACTIVE",
                """
                ["multimodal-basic","text-basic","text-pro"]
                """);

        // ③ 本来就一致的托管 Key：连 updated_at 都不该动。
        insertPackage(IN_SYNC_PACKAGE, IN_SYNC_USER, "ADVANCED", "TOKEN", "ACTIVE",
                """
                [{"modelCode":"chat-standard","modelTokenQuota":null,"capabilities":["TEXT"]},
                 {"modelCode":"chat-deep","modelTokenQuota":null,"capabilities":["TEXT"]}]
                """);
        insertKey(ALREADY_IN_SYNC_KEY, IN_SYNC_USER, IN_SYNC_PACKAGE, true, "ACTIVE",
                """
                ["chat-deep","chat-standard"]
                """);

        // ④ 视频与音频套餐：同一列里是别的东西，但都不是文本入口。
        insertPackage(VIDEO_PACKAGE, VIDEO_USER, "VIDEO_CREATOR", "VIDEO_SECOND", "ACTIVE",
                """
                [{"modelCode":"video-basic","modelTokenQuota":null,
                  "capabilities":["TEXT","IMAGE","VIDEO"]},
                 {"modelCode":"video-budget","modelTokenQuota":60,"capabilities":["VIDEO"]}]
                """);
        insertKey(VIDEO_MANAGED_KEY, VIDEO_USER, VIDEO_PACKAGE, true, "ACTIVE",
                """
                ["video-basic"]
                """);
        insertPackage(AUDIO_PACKAGE, AUDIO_USER, "AUDIO_BASIC", "AUDIO_SECOND", "ACTIVE",
                """
                [{"modelCode":"audio-asr-basic","modelTokenQuota":null,
                  "capabilities":["AUDIO_TRANSCRIBE"]},
                 {"modelCode":"audio-tts-basic","modelTokenQuota":null,
                  "capabilities":["AUDIO_SYNTHESIS"]}]
                """);
        insertKey(AUDIO_MANAGED_KEY, AUDIO_USER, AUDIO_PACKAGE, true, "ACTIVE",
                """
                ["audio-asr-basic","audio-tts-basic"]
                """);

        // ⑤ 只卖视觉别名的 TOKEN 套餐：vision-pro 的能力含 TEXT，在文本目录判据之内。
        insertPackage(VISION_ONLY_PACKAGE, VISION_ONLY_USER, "VISION", "TOKEN", "ACTIVE",
                """
                [{"modelCode":"vision-pro","modelTokenQuota":null,"capabilities":["TEXT","IMAGE"]}]
                """);

        // ⑥ 已终结的实例：历史快照不该被改写。
        insertPackage(REVOKED_PACKAGE, REVOKED_USER, "STARTER", "TOKEN", "REVOKED",
                """
                [{"modelCode":"text-basic","modelTokenQuota":null,"capabilities":["TEXT"]}]
                """);
    }

    @Test
    void grantsTheClientTextChannelsAndAlignsEveryManagedKeyWithItsPackage() throws Exception {
        Timestamp inSync = updatedAt(ALREADY_IN_SYNC_KEY);

        runMigration();

        // ① 老形态：两个入口补在尾部（普通在前、深度在后，与发放路径一致），
        //    原有条目（含每模型自额度）一字不动。
        assertThat(instanceCodes(LEGACY_TEXT_PACKAGE)).isEqualTo(
                "text-basic,text-standard,multimodal-basic,vision-pro,chat-standard,chat-deep");
        assertThat(rawAllowedModels(LEGACY_TEXT_PACKAGE))
                .as("原有条目的每模型自额度必须原样保留")
                .contains("\"modelTokenQuota\": 100");

        // ② 生产故障形态：套餐已经有两个入口（不重复追加），Key 被对齐。
        assertThat(instanceCodes(ALREADY_GRANTED_PACKAGE)).isEqualTo(
                "multimodal-basic,text-max,vision-pro,chat-standard,chat-deep");
        assertThat(keyCodes(STALE_MANAGED_KEY)).isEqualTo(
                "chat-deep,chat-standard,multimodal-basic,text-max,vision-pro");

        // ③ 本来就一致的托管 Key 不该被写。
        assertThat(keyCodes(ALREADY_IN_SYNC_KEY)).isEqualTo("chat-deep,chat-standard");
        assertThat(updatedAt(ALREADY_IN_SYNC_KEY))
                .as("一致的托管 Key 连 updated_at 都不该动")
                .isEqualTo(inSync);

        // ④ 视频 / 音频维度：一个文本通道都不该拿到，但托管 Key 仍与套餐一致。
        assertThat(instanceCodes(VIDEO_PACKAGE)).isEqualTo("video-basic,video-budget");
        assertThat(instanceCodes(AUDIO_PACKAGE)).isEqualTo("audio-asr-basic,audio-tts-basic");
        assertThat(keyCodes(VIDEO_MANAGED_KEY)).isEqualTo("video-basic,video-budget");
        assertThat(keyCodes(AUDIO_MANAGED_KEY)).isEqualTo("audio-asr-basic,audio-tts-basic");

        // ⑤ 只卖视觉的 TOKEN 套餐也在文本目录判据之内，因此也拿到两个入口。
        assertThat(instanceCodes(VISION_ONLY_PACKAGE))
                .isEqualTo("vision-pro,chat-standard,chat-deep");

        // ⑥ 范围之外的三行原样不动。
        assertThat(keyCodes(SELF_SERVICE_KEY)).isEqualTo("text-basic");
        assertThat(keyCodes(REVOKED_MANAGED_KEY)).isEqualTo("text-basic");
        assertThat(instanceCodes(REVOKED_PACKAGE)).isEqualTo("text-basic");

        // 每条生效托管 Key 的名单都必须与它套餐的名单逐条一致——这就是客户端目录
        // 不再为空的充要条件。
        assertThat(disagreeingManagedKeys())
                .as("托管 Key 与套餐名单不一致的行")
                .isZero();
    }

    @Test
    void isSafeToRunTwice() throws Exception {
        runMigration();
        Map<UUID, String> packages = instanceCodes();
        Map<UUID, String> keys = keyCodes();

        runMigration();

        assertThat(instanceCodes()).isEqualTo(packages);
        assertThat(keyCodes()).isEqualTo(keys);
    }

    /**
     * 执行迁移文件本身。
     *
     * <p>必须在一个事务里跑（与 Flyway 的做法一致）：迁移用
     * {@code CREATE TEMP TABLE ... ON COMMIT DROP} 暂存中间结果，自动提交会让它们在
     * 下一条语句之前就消失。
     */
    private static void runMigration() throws Exception {
        try (InputStream migration = ClientTextChannelEntitlementMigrationTest.class
                        .getClassLoader().getResourceAsStream(MIGRATION);
                Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            assertThat(migration).as("migration %s exists", MIGRATION).isNotNull();
            connection.setAutoCommit(false);
            statement.execute(new String(migration.readAllBytes(), UTF_8));
            connection.commit();
        }
    }

    private static void insertPackage(
            UUID id, UUID userId, String code, String dimension, String status, String allowedModels)
            throws Exception {
        // total_token_quota 的单位随维度（TOKEN = 词元、VIDEO_SECOND = 秒），
        // 但它不是这条迁移的判据，因此夹具只要求它是正数。
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, price_amount, currency,
                    allowed_models, total_token_quota, max_api_keys, requests_per_minute,
                    tokens_per_minute, max_concurrency, duration_days, status,
                    starts_at, expires_at, activated_at, usage_dimension)
                VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', '%s', 'identity-%s',
                    gen_random_uuid(), '%s', '%s', 19900, 'CNY',
                    '%s'::jsonb, 20000000, 10, 100, 500000, 15, 30, '%s',
                    now(), now() + interval '30 days', now(), '%s')
                """.formatted(id, TENANT_ID, userId, id, code, code,
                allowedModels.replace("'", "''").replace("\n", " "), status, dimension));
    }

    private static void insertKey(
            UUID id, UUID userId, UUID packageId, boolean managed, String status,
            String allowedModels)
            throws Exception {
        // ck_api_key_managed_secret 要求托管 Key 带着至少 29 字节的密文与正的密钥版本。
        String secret = managed ? "decode(repeat('ab', 32), 'hex'), 1" : "NULL, NULL";
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status, expires_at, managed,
                    secret_ciphertext, secret_key_version, usage_dimension)
                VALUES ('%s', '%s', '%s', '%s', 'Internal client',
                    'sk-yzh-%s', md5('%s') || repeat('a', 32), '%s'::jsonb, '%s',
                    now() + interval '30 days', %s, %s, 'TOKEN')
                """.formatted(id, TENANT_ID, userId, packageId,
                id.toString().substring(0, 8), id,
                allowedModels.replace("'", "''").replace("\n", " "), status, managed, secret));
    }

    private static String instanceCodes(UUID id) throws Exception {
        return single("""
                SELECT string_agg(element.value ->> 'modelCode', ',' ORDER BY element.ordinality)
                FROM gateway.package_instance package,
                     jsonb_array_elements(package.allowed_models)
                         WITH ORDINALITY AS element(value, ordinality)
                WHERE package.id = '%s'
                """.formatted(id));
    }

    private static String rawAllowedModels(UUID id) throws Exception {
        return single("SELECT allowed_models::text FROM gateway.package_instance WHERE id = '"
                + id + "'");
    }

    private static String keyCodes(UUID id) throws Exception {
        return single("""
                SELECT string_agg(element.value, ',' ORDER BY element.ordinality)
                FROM gateway.api_key key,
                     jsonb_array_elements_text(key.allowed_models)
                         WITH ORDINALITY AS element(value, ordinality)
                WHERE key.id = '%s'
                """.formatted(id));
    }

    private static Map<UUID, String> instanceCodes() throws Exception {
        Map<UUID, String> codes = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT package.id,
                               string_agg(element.value ->> 'modelCode', ',' ORDER BY element.ordinality)
                        FROM gateway.package_instance package,
                             jsonb_array_elements(package.allowed_models)
                                 WITH ORDINALITY AS element(value, ordinality)
                        GROUP BY package.id
                        """)) {
            while (rows.next()) {
                codes.put(rows.getObject(1, UUID.class), rows.getString(2));
            }
        }
        return codes;
    }

    private static Map<UUID, String> keyCodes() throws Exception {
        Map<UUID, String> codes = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT key.id,
                               string_agg(element.value, ',' ORDER BY element.ordinality)
                        FROM gateway.api_key key,
                             jsonb_array_elements_text(key.allowed_models)
                                 WITH ORDINALITY AS element(value, ordinality)
                        GROUP BY key.id
                        """)) {
            while (rows.next()) {
                codes.put(rows.getObject(1, UUID.class), rows.getString(2));
            }
        }
        return codes;
    }

    private static int disagreeingManagedKeys() throws Exception {
        return singleInt("""
                SELECT count(*)
                FROM gateway.api_key key
                JOIN gateway.package_instance package ON package.id = key.package_instance_id
                WHERE key.managed AND key.status = 'ACTIVE'
                  AND key.allowed_models IS DISTINCT FROM (
                        SELECT COALESCE(jsonb_agg(DISTINCT code ORDER BY code), '[]'::jsonb)
                        FROM (SELECT allowed ->> 'modelCode' AS code
                              FROM jsonb_array_elements(package.allowed_models) allowed) model
                        WHERE code IS NOT NULL AND code <> '')
                """);
    }

    private static Timestamp updatedAt(UUID keyId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT updated_at FROM gateway.api_key WHERE id = '" + keyId + "'")) {
            assertThat(row.next()).isTrue();
            return row.getTimestamp("updated_at");
        }
    }

    private static String single(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private static int singleInt(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
