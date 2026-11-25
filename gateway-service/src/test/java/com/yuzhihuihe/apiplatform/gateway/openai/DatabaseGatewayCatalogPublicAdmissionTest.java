package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelCircuitRegistry;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import com.yuzhihuihe.apiplatform.gateway.routing.CredentialCipher;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

@Testcontainers
class DatabaseGatewayCatalogPublicAdmissionTest {

    private static final UUID TENANT_ID = UUID.fromString("92000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("92000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID = UUID.fromString("92000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE_KEY_ID = UUID.fromString("92000000-0000-4000-8000-000000000004");
    private static final UUID WALLET_KEY_ID = UUID.fromString("92000000-0000-4000-8000-000000000005");
    private static final UUID VIDEO_PACKAGE_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000010");
    private static final UUID VIDEO_PACKAGE_KEY_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000011");
    private static final UUID VIDEO_PRO_PACKAGE_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000012");
    private static final UUID VIDEO_TOKEN_RATE_PACKAGE_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000013");
    private static final UUID VIDEO_TOKEN_RATE_KEY_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000014");
    private static final UUID VIDEO_PRO_KEY_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000015");
    // 每个用户只能有一个生效中的套餐，所以每个被种入的视频套餐都有自己的归属用户和自己的 key。
    private static final UUID VIDEO_USER_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000040");
    private static final UUID VIDEO_PRO_USER_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000041");
    private static final UUID VIDEO_TOKEN_RATE_USER_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000042");
    private static final java.util.List<UUID> VIDEO_PACKAGE_IDS =
            java.util.List.of(VIDEO_PACKAGE_ID, VIDEO_PRO_PACKAGE_ID);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static DatabaseClient database;
    static DatabaseGatewayCatalog catalog;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
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
                        starts_at, expires_at, activated_at, identity_key, daily_request_limit)
                    VALUES (
                        '92000000-0000-4000-8000-000000000003',
                        '92000000-0000-4000-8000-000000000006',
                        '92000000-0000-4000-8000-000000000007',
                        '92000000-0000-4000-8000-000000000001',
                        '92000000-0000-4000-8000-000000000002',
                        '92000000-0000-4000-8000-000000000008',
                        'FREE', 'Free', 0,
                        '[{"modelCode":"text-basic"}]',
                        10000, 2, 7, 10000, 1, 30, 'ACTIVE',
                        now(), now() + interval '30 days', now(),
                        '92000000-0000-4000-8000-000000000002', 20);
                    INSERT INTO gateway.api_key(
                        id, tenant_id, user_id, package_instance_id, name,
                        key_prefix, key_hash, allowed_models, status)
                    VALUES
                        ('92000000-0000-4000-8000-000000000004',
                         '92000000-0000-4000-8000-000000000001',
                         '92000000-0000-4000-8000-000000000002',
                         '92000000-0000-4000-8000-000000000003',
                         'package-key', 'sk-yzh-package', 'package-key-hash',
                         '["text-basic"]', 'ACTIVE'),
                        ('92000000-0000-4000-8000-000000000005',
                         '92000000-0000-4000-8000-000000000001',
                         '92000000-0000-4000-8000-000000000002',
                         NULL,
                         'wallet-key', 'sk-yzh-wallet', 'wallet-key-hash',
                         '["text-basic", "video-basic"]', 'ACTIVE');
                    INSERT INTO gateway.package_instance_model_quota(
                        id, package_instance_id, model_code, token_quota)
                    VALUES (
                        '92000000-0000-4000-8000-000000000009',
                        '92000000-0000-4000-8000-000000000003',
                        'text-basic', 10000);
                    -- Video packages: the per-minute column carries the plan's own dimension's
                    -- rate. VIDEO_CREATOR and VIDEO_PRO sell seconds per minute (190 / 600) while
                    -- VIDEO_PRO_TOKEN sells a chat token rate on a seconds-denominated plan.
                    INSERT INTO gateway.package_instance(
                        id, grant_event_id, grant_request_id, tenant_id, user_id,
                        package_plan_version_id, package_code, package_name, price_amount,
                        allowed_models, total_token_quota, max_api_keys, requests_per_minute,
                        tokens_per_minute, max_concurrency, duration_days, status,
                        starts_at, expires_at, activated_at, identity_key, daily_request_limit,
                        usage_dimension)
                    VALUES
                        ('92000000-0000-4000-8000-000000000010',
                         '92000000-0000-4000-8000-000000000020',
                         '92000000-0000-4000-8000-000000000021',
                         '92000000-0000-4000-8000-000000000001',
                         '92000000-0000-4000-8000-000000000040',
                         '92000000-0000-4000-8000-000000000022',
                         'VIDEO_CREATOR', 'Video Creator', 39900,
                         '[{"modelCode":"text-basic"}]',
                         190, 2, 7, 190, 1, 30, 'ACTIVE',
                         now(), now() + interval '30 days', now(),
                         '92000000-0000-4000-8000-000000000010', 500, 'VIDEO_SECOND'),
                        ('92000000-0000-4000-8000-000000000012',
                         '92000000-0000-4000-8000-000000000023',
                         '92000000-0000-4000-8000-000000000024',
                         '92000000-0000-4000-8000-000000000001',
                         '92000000-0000-4000-8000-000000000041',
                         '92000000-0000-4000-8000-000000000025',
                         'VIDEO_PRO', 'Video Pro', 139900,
                         '[{"modelCode":"text-basic"}]',
                         600, 2, 7, 600, 1, 30, 'ACTIVE',
                         now(), now() + interval '30 days', now(),
                         '92000000-0000-4000-8000-000000000012', 1200, 'VIDEO_SECOND'),
                        ('92000000-0000-4000-8000-000000000013',
                         '92000000-0000-4000-8000-000000000026',
                         '92000000-0000-4000-8000-000000000027',
                         '92000000-0000-4000-8000-000000000001',
                         '92000000-0000-4000-8000-000000000042',
                         '92000000-0000-4000-8000-000000000028',
                         'VIDEO_PRO', 'Video Pro', 139900,
                         '[{"modelCode":"text-basic"}]',
                         600, 2, 7, 500000, 1, 30, 'ACTIVE',
                         now(), now() + interval '30 days', now(),
                         '92000000-0000-4000-8000-000000000013', 1200, 'VIDEO_SECOND');
                    INSERT INTO gateway.api_key(
                        id, tenant_id, user_id, package_instance_id, name,
                        key_prefix, key_hash, allowed_models, status)
                    VALUES
                        ('92000000-0000-4000-8000-000000000011',
                         '92000000-0000-4000-8000-000000000001',
                         '92000000-0000-4000-8000-000000000040',
                         '92000000-0000-4000-8000-000000000010',
                         'video-key', 'sk-yzh-video', 'video-key-hash',
                         '["text-basic"]', 'ACTIVE'),
                        ('92000000-0000-4000-8000-000000000014',
                         '92000000-0000-4000-8000-000000000001',
                         '92000000-0000-4000-8000-000000000042',
                         '92000000-0000-4000-8000-000000000013',
                         'video-pro-key', 'sk-yzh-video-pro', 'video-pro-key-hash',
                         '["text-basic"]', 'ACTIVE'),
                        ('92000000-0000-4000-8000-000000000015',
                         '92000000-0000-4000-8000-000000000001',
                         '92000000-0000-4000-8000-000000000041',
                         '92000000-0000-4000-8000-000000000012',
                         'video-pro-seconds-key', 'sk-yzh-video-pro-seconds',
                         'video-pro-seconds-key-hash',
                         '["text-basic"]', 'ACTIVE');
                    INSERT INTO gateway.channel_model(
                        id, channel_id, model_id, upstream_model, enabled, capabilities)
                    SELECT gen_random_uuid(), '84000000-0000-4000-8000-000000000002',
                           model.id, 'video-upstream', true, '["TEXT","IMAGE","VIDEO"]'::jsonb
                    FROM gateway.model model
                    WHERE model.code = 'video-basic';
                    -- These packages deliberately carry a token-shaped alias as a regression
                    -- fixture: the chat catalog must reject the VIDEO_SECOND package before it
                    -- can interpret the package's seconds as token usage.
                    """);
        }
        String r2dbcUrl = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
                + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName();
        database = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl));
        catalog = new DatabaseGatewayCatalog(
                database,
                new ChannelCircuitRegistry(CircuitBreakerRegistry.ofDefaults()),
                new CredentialCipher(""),
                new ObjectMapper());
    }

    @Test
    void packageBoundKeyUsesActivePackageLimitsForPublicAdmission() {
        var context = new ApiKeyService.ApiKeyContext(
                PACKAGE_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("text-basic"));

        var prepared = catalog.preparePublic(context, "text-basic")
                .block(Duration.ofSeconds(3));

        assertThat(prepared).isNotNull();
        assertThat(prepared.rpm().packageLimit()).isEqualTo(7);
        assertThat(prepared.concurrency().packageLimit()).isEqualTo(1);
    }

    @Test
    void publicAdmissionCarriesTheSoldDailyRequestCapOfThePackageInstance() {
        var context = new ApiKeyService.ApiKeyContext(
                PACKAGE_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("text-basic"));

        var prepared = catalog.preparePublic(context, "text-basic")
                .block(Duration.ofSeconds(3));

        assertThat(prepared).isNotNull();
        assertThat(prepared.dailyLimit()).isEqualTo(
                RequestLimitService.PackageDailyLimit.of(PACKAGE_ID, 20));
    }

    @Test
    void directPreparationCarriesTheSoldDailyRequestCapOfThePackageInstance() {
        var prepared = directPreparation(packageContext());

        assertThat(prepared).isNotNull();
        assertThat(prepared.dailyLimit()).isEqualTo(
                RequestLimitService.PackageDailyLimit.of(PACKAGE_ID, 20));
    }

    @Test
    void walletOnlyKeyHasNoDailyRequestCapToEnforce() {
        var context = new ApiKeyService.ApiKeyContext(
                WALLET_KEY_ID, TENANT_ID, USER_ID, null, Set.of("text-basic"));

        var publicPrepared = catalog.preparePublic(context, "text-basic")
                .block(Duration.ofSeconds(3));

        assertThat(publicPrepared).isNotNull();
        assertThat(publicPrepared.dailyLimit().enforced()).isFalse();
        assertThat(directPreparation(context).dailyLimit().enforced()).isFalse();
    }

    @Test
    void rejectsPackageBoundKeyWhenPackageIsNotUsable() {
        var context = new ApiKeyService.ApiKeyContext(
                PACKAGE_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("text-basic"));
        database.sql("UPDATE gateway.package_instance SET status = 'EXHAUSTED' WHERE id = :id")
                .bind("id", PACKAGE_ID).fetch().rowsUpdated().block();
        try {
            assertThat(catalog.preparePublic(context, "text-basic")
                    .block(Duration.ofSeconds(3))).isNull();
        } finally {
            database.sql("UPDATE gateway.package_instance SET status = 'ACTIVE' WHERE id = :id")
                    .bind("id", PACKAGE_ID).fetch().rowsUpdated().block();
        }
    }

    @TestFactory
    Stream<DynamicTest> rejectsPackageBoundKeyForEveryInvalidPackageConstraint() {
        return invalidPackageMutations()
                .map(mutation -> DynamicTest.dynamicTest(mutation.name(), () -> {
                    execute(mutation.sql());
                    try {
                        assertPackageAdmissionRejected();
                    } finally {
                        restoreUsablePackage();
                    }
                }));
    }

    @Test
    void directPreparationUsesActivePackageLimits() {
        var prepared = directPreparation(packageContext());

        assertThat(prepared).isNotNull();
        assertThat(prepared.rpm().packageLimit()).isEqualTo(7);
        assertThat(prepared.concurrency().packageLimit()).isEqualTo(1);
    }

    @TestFactory
    Stream<DynamicTest> directPreparationRejectsEveryInvalidPackageConstraint() {
        return Stream.concat(
                        Stream.of(new PackageMutation("inactive package",
                                "UPDATE gateway.package_instance SET status = 'EXHAUSTED' WHERE id = :id")),
                        invalidPackageMutations())
                .map(mutation -> DynamicTest.dynamicTest(mutation.name(), () -> {
                    execute(mutation.sql());
                    try {
                        assertThat(directPreparation(packageContext())).isNull();
                    } finally {
                        restoreUsablePackage();
                    }
                }));
    }

    @Test
    void directPreparationSupportsWalletOnlyKeyWithoutPackage() {
        var context = new ApiKeyService.ApiKeyContext(
                WALLET_KEY_ID, TENANT_ID, USER_ID, null, Set.of("text-basic"));

        assertThat(directPreparation(context)).isNotNull();
    }

    @Test
    void walletOnlyKeyWithoutPackageStillSupportsPublicAdmission() {
        var context = new ApiKeyService.ApiKeyContext(
                WALLET_KEY_ID, TENANT_ID, USER_ID, null, Set.of("text-basic"));

        assertThat(catalog.preparePublic(context, "text-basic")
                .block(Duration.ofSeconds(3))).isNotNull();
    }

    @Test
    void chatCatalogRejectsVideoAliasEvenWhenAChatCapableRouteExists() {
        var context = new ApiKeyService.ApiKeyContext(
                WALLET_KEY_ID, TENANT_ID, USER_ID, null, Set.of("video-basic"));

        assertThat(catalog.prepare(
                        context, "video-basic", "video-basic",
                        Set.of(ChannelRouter.Capability.TEXT))
                .block(Duration.ofSeconds(3))).isNull();
        assertThat(catalog.preparePublic(context, "video-basic")
                .block(Duration.ofSeconds(3))).isNull();
    }

    @Test
    void tokenPackageStillCarriesItsSoldCreditsPerMinute() {
        var prepared = catalog.preparePublic(packageContext(), "text-basic")
                .block(Duration.ofSeconds(3));

        assertThat(prepared).isNotNull();
        assertThat(prepared.tokenLimit())
                .as("a token package's rate is unchanged by the dimension handling")
                .isEqualTo(RequestLimitService.PackageTokenLimit.of(PACKAGE_ID, 10_000L));
    }

    /**
     * 这里防住的缺陷：{@code VIDEO_SECOND} 实例上的 {@code tokens_per_minute} 表示的是
     * 每分钟可生成的秒数，因此该套餐上的聊天请求绝不能被一个含义为秒的数字限流。
     */
    @Test
    void aVideoSecondPackageCannotEnterChatAdmissionForATokenShapedAlias() {
        for (UUID videoPackageId : VIDEO_PACKAGE_IDS) {
            var context = new ApiKeyService.ApiKeyContext(
                    videoKey(videoPackageId), TENANT_ID, videoUser(videoPackageId), videoPackageId,
                    Set.of("text-basic"));

            var prepared = catalog.preparePublic(context, "text-basic").block(Duration.ofSeconds(3));

            assertThat(prepared)
                    .as("package %s sells seconds and is not a chat package", videoPackageId)
                    .isNull();
        }
    }

    @Test
    void aVideoSecondPackageCannotEnterChatEvenWhenItCarriesATokenRate() {
        var context = new ApiKeyService.ApiKeyContext(
                VIDEO_TOKEN_RATE_KEY_ID, TENANT_ID, VIDEO_TOKEN_RATE_USER_ID,
                VIDEO_TOKEN_RATE_PACKAGE_ID, Set.of("text-basic"));

        var prepared = catalog.preparePublic(context, "text-basic").block(Duration.ofSeconds(3));

        assertThat(prepared).isNull();
    }

    private void assertPackageAdmissionRejected() {
        assertThat(catalog.preparePublic(packageContext(), "text-basic")
                .block(Duration.ofSeconds(3))).isNull();
    }

    private ChatCompletionService.PreparedRequest directPreparation(
            ApiKeyService.ApiKeyContext context) {
        return catalog.prepare(
                        context, "text-basic", "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT))
                .block(Duration.ofSeconds(3));
    }

    private ApiKeyService.ApiKeyContext packageContext() {
        return new ApiKeyService.ApiKeyContext(
                PACKAGE_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("text-basic"));
    }

    private static UUID videoKey(UUID videoPackageId) {
        if (VIDEO_PACKAGE_ID.equals(videoPackageId)) {
            return VIDEO_PACKAGE_KEY_ID;
        }
        if (VIDEO_PRO_PACKAGE_ID.equals(videoPackageId)) {
            return VIDEO_PRO_KEY_ID;
        }
        return VIDEO_TOKEN_RATE_KEY_ID;
    }

    private static UUID videoUser(UUID videoPackageId) {
        if (VIDEO_PACKAGE_ID.equals(videoPackageId)) {
            return VIDEO_USER_ID;
        }
        if (VIDEO_PRO_PACKAGE_ID.equals(videoPackageId)) {
            return VIDEO_PRO_USER_ID;
        }
        return VIDEO_TOKEN_RATE_USER_ID;
    }

    private Stream<PackageMutation> invalidPackageMutations() {
        return Stream.of(
                new PackageMutation("wrong owner",
                        "UPDATE gateway.package_instance SET user_id = '92000000-0000-4000-8000-000000000099' WHERE id = :id"),
                new PackageMutation("expired package",
                        "UPDATE gateway.package_instance SET expires_at = now() - interval '1 second' WHERE id = :id"),
                new PackageMutation("exhausted overall quota",
                        "UPDATE gateway.package_instance SET used_tokens = total_token_quota WHERE id = :id"),
                new PackageMutation("package-disallowed model",
                        "UPDATE gateway.package_instance SET allowed_models = '[]'::jsonb WHERE id = :id"),
                new PackageMutation("exhausted model subquota",
                        "UPDATE gateway.package_instance_model_quota SET used_tokens = token_quota WHERE package_instance_id = :id AND model_code = 'text-basic'"));
    }

    private void restoreUsablePackage() {
        execute("""
                UPDATE gateway.package_instance
                SET user_id = '92000000-0000-4000-8000-000000000002',
                    status = 'ACTIVE',
                    expires_at = now() + interval '30 days',
                    used_tokens = 0,
                    allowed_models = '[{"modelCode":"text-basic"}]'::jsonb
                WHERE id = :id
                """);
        execute("""
                UPDATE gateway.package_instance_model_quota
                SET used_tokens = 0
                WHERE package_instance_id = :id AND model_code = 'text-basic'
                """);
    }

    private void execute(String sql) {
        Mono<Long> updated = database.sql(sql).bind("id", PACKAGE_ID).fetch().rowsUpdated();
        assertThat(updated.block(Duration.ofSeconds(3))).isEqualTo(1L);
    }

    private record PackageMutation(String name, String sql) { }
}
