package com.yuzhihuihe.apiplatform.gateway.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.limit.BlockedAdmissionService;
import com.yuzhihuihe.apiplatform.gateway.limit.DailyRequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.limit.PackageSecondRateLimitService;
import com.yuzhihuihe.apiplatform.gateway.limit.PackageTokenRateLimitService;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * {@code GET /internal/limit-usage} 的契约形状，基于真实计数器。
 *
 * <p>它是单个套餐实例的运营视图：每分钟和每天分别买到了多少、各自用了多少、被拒绝了多少次。
 * 维度在这里与其他任何地方同样重要 —— 视频套餐的 190 是秒数，而不是 credits —— 并且未知的
 * 套餐实例必须是带标签的 404，而不是没有标签的 "Not Found"。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.default-schema=gateway",
                "spring.flyway.schemas=gateway",
                "spring.data.redis.repositories.enabled=false",
                "security.api-key.pepper=test-api-key-pepper",
                "security.internal.hmac-secret=test-internal-secret"
        })
class LimitUsageControllerTest {

    private static final String PATH = "/internal/limit-usage";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final UUID TENANT_ID = UUID.fromString("9c000000-0000-4000-8000-000000000001");
    // 每个用户只能有一个活跃套餐是一条数据库不变式，因此每个预置套餐都有自己的所有者。
    private static final UUID USER_ID = UUID.fromString("9c000000-0000-4000-8000-000000000002");
    private static final UUID DAILY_USER_ID = UUID.fromString("9c000000-0000-4000-8000-000000000007");
    private static final UUID VIDEO_USER_ID = UUID.fromString("9c000000-0000-4000-8000-000000000008");
    private static final UUID VIDEO_TOKEN_RATE_USER_ID =
            UUID.fromString("9c000000-0000-4000-8000-000000000009");
    private static final UUID TOKEN_PACKAGE_ID =
            UUID.fromString("9c000000-0000-4000-8000-000000000003");
    private static final UUID VIDEO_PACKAGE_ID =
            UUID.fromString("9c000000-0000-4000-8000-000000000004");
    private static final UUID VIDEO_TOKEN_RATE_PACKAGE_ID =
            UUID.fromString("9c000000-0000-4000-8000-000000000005");
    private static final UUID DAILY_PACKAGE_ID =
            UUID.fromString("9c000000-0000-4000-8000-000000000006");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    PackageTokenRateLimitService packageTokenRateLimitService;

    @Autowired
    PackageSecondRateLimitService packageSecondRateLimitService;

    @Autowired
    DailyRequestLimitService dailyRequestLimitService;

    @Autowired
    BlockedAdmissionService blockedAdmissionService;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        seed(TOKEN_PACKAGE_ID, "STARTER", "TOKEN", 100_000, 2000, USER_ID);
        seed(VIDEO_PACKAGE_ID, "VIDEO_CREATOR", "VIDEO_SECOND", 190, 500, VIDEO_USER_ID);
        seed(VIDEO_TOKEN_RATE_PACKAGE_ID, "VIDEO_PRO", "VIDEO_SECOND", 500_000, 1200,
                VIDEO_TOKEN_RATE_USER_ID);
        seed(DAILY_PACKAGE_ID, "ADVANCED", "TOKEN", 500_000, 2000, DAILY_USER_ID);
    }

    @Test
    void reportsATokenPlansSoldRateAndItsConsumedCredits() {
        packageTokenRateLimitService.record(
                RequestLimitService.PackageTokenLimit.of(TOKEN_PACKAGE_ID, 100_000L), 1234);
        awaitUsed(TOKEN_PACKAGE_ID, 1234);

        signedGet(PATH + "?packageInstanceId=" + TOKEN_PACKAGE_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.packageInstanceId").isEqualTo(TOKEN_PACKAGE_ID.toString())
                .jsonPath("$.usageDimension").isEqualTo("TOKEN")
                .jsonPath("$.perMinute.limit").isEqualTo(100_000)
                .jsonPath("$.perMinute.used").isEqualTo(1234)
                .jsonPath("$.daily.limit").isEqualTo(2000)
                .jsonPath("$.daily.used").isEqualTo(0)
                .jsonPath("$.blockedLast24h").isEqualTo(0)
                .jsonPath("$.blockedLast24HoursByReason").isEmpty();
    }

    @Test
    void reportsAVideoPlansSecondsRateInSecondsAndNeverAsCredits() {
        packageSecondRateLimitService.record(VIDEO_PACKAGE_ID, 60);
        awaitUsed(VIDEO_PACKAGE_ID, 60);

        signedGet(PATH + "?packageInstanceId=" + VIDEO_PACKAGE_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.usageDimension").isEqualTo("VIDEO_SECOND")
                .jsonPath("$.perMinute.limit")
                .isEqualTo(190)
                .jsonPath("$.perMinute.used").isEqualTo(60)
                .jsonPath("$.daily.limit").isEqualTo(500);
    }

    @Test
    void reportsTheCreditRateOfAVideoPlanWhosePerMinuteNumberIsAChatRate() {
        // 这个套餐唯一的每分钟上限是它的聊天 token 速率：它的每分钟列
        // 携带的是 token 速率，因此没有售卖秒数速率，也没有强制执行任何秒数速率。
        signedGet(PATH + "?packageInstanceId=" + VIDEO_TOKEN_RATE_PACKAGE_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.usageDimension").isEqualTo("VIDEO_SECOND")
                .jsonPath("$.perMinute.limit").isEqualTo(500_000)
                .jsonPath("$.perMinute.used").isEqualTo(0);
    }

    @Test
    void reportsTheDaysAdmittedRequestsAndTheRefusalsByReason() {
        RequestLimitService.PackageDailyLimit daily =
                RequestLimitService.PackageDailyLimit.of(DAILY_PACKAGE_ID, 2000);
        for (int index = 0; index < 37; index++) {
            dailyRequestLimitService.admit(daily, DAILY_USER_ID).block(Duration.ofSeconds(5));
        }
        blockedAdmissionService.record(
                DAILY_PACKAGE_ID, BlockedAdmissionService.DAILY_REQUEST_LIMIT_EXCEEDED);
        blockedAdmissionService.record(
                DAILY_PACKAGE_ID, BlockedAdmissionService.TOKENS_PER_MINUTE_EXCEEDED);
        awaitBlocked(DAILY_PACKAGE_ID, 2);

        signedGet(PATH + "?packageInstanceId=" + DAILY_PACKAGE_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.daily.limit").isEqualTo(2000)
                .jsonPath("$.daily.used").isEqualTo(37)
                .jsonPath("$.blockedLast24h").isEqualTo(2)
                .jsonPath("$.blockedLast24HoursByReason.daily_request_limit_exceeded").isEqualTo(1)
                .jsonPath("$.blockedLast24HoursByReason.tokens_per_minute_exceeded").isEqualTo(1);
    }

    @Test
    void anUnknownPackageInstanceIsALabelled404() {
        signedGet(PATH + "?packageInstanceId=" + UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("package_instance_not_found");
    }

    @Test
    void aMalformedOrMissingPackageInstanceIdIsALabelled400() {
        signedGet(PATH + "?packageInstanceId=not-a-uuid")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_package_instance_id");

        signedGet(PATH)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_package_instance_id");
    }

    @Test
    void anUnsignedCallIsRejectedByTheSameHmacFilterAsEveryInternalRoute() {
        webTestClient.get().uri(PATH + "?packageInstanceId=" + TOKEN_PACKAGE_ID)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private void awaitUsed(UUID packageInstanceId, long expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode usage = usageOf(packageInstanceId);
            if (usage != null && usage.path("perMinute").path("used").asLong() == expected) {
                return;
            }
            sleep();
        }
    }

    private void awaitBlocked(UUID packageInstanceId, long expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode usage = usageOf(packageInstanceId);
            if (usage != null && usage.path("blockedLast24h").asLong() == expected) {
                return;
            }
            sleep();
        }
    }

    /** 该端点自己的应答，因此每次等待都是在等待调用方将会看到的内容。 */
    private JsonNode usageOf(UUID packageInstanceId) {
        byte[] body = signedGet(PATH + "?packageInstanceId=" + packageInstanceId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBodyContent();
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (IOException malformed) {
            throw new IllegalStateException("limit_usage_response_unreadable", malformed);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void seed(
            UUID packageId, String code, String dimension, long tokensPerMinute, int dailyLimit,
            UUID userId)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO gateway.package_instance(
                        id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                        package_plan_version_id, package_code, package_name, price_amount,
                        allowed_models, total_token_quota, max_api_keys, requests_per_minute,
                        tokens_per_minute, max_concurrency, duration_days, status,
                        starts_at, expires_at, activated_at, daily_request_limit, usage_dimension)
                    VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', '%s', 'identity-%s',
                        gen_random_uuid(), '%s', '%s', 0,
                        '[{"modelCode":"text-basic"}]', 1000000, 2, 20,
                        %d, 3, 30, 'ACTIVE', now(), now() + interval '30 days', now(), %d, '%s')
                    """.formatted(packageId, TENANT_ID, userId, packageId, code, code,
                    tokensPerMinute, dailyLimit, dimension));
        }
    }

    private WebTestClient.RequestHeadersSpec<?> signedGet(String uri) {
        String path = uri.indexOf('?') < 0 ? uri : uri.substring(0, uri.indexOf('?'));
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        UUID requestId = UUID.randomUUID();
        try {
            String contentSha256 = sha256(new byte[0]);
            return webTestClient.get().uri(uri)
                    .header("X-Internal-Timestamp", timestamp)
                    .header("X-Internal-Request-Id", requestId.toString())
                    .header("X-Internal-Content-Sha256", contentSha256)
                    .header("X-Internal-Signature",
                            signature(timestamp, requestId, "GET", path, contentSha256));
        } catch (Exception failure) {
            throw new IllegalStateException("internal_request_signing_failed", failure);
        }
    }

    private String signature(
            String timestamp, UUID requestId, String method, String path, String contentSha256)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "test-internal-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (timestamp + "\n" + requestId + "\n" + method + "\n" + path + "\n" + contentSha256)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }
}
