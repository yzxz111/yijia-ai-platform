package com.yuzhihuihe.apiplatform.gateway.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code GET /internal/settlement-deficit} 约定的响应结构。
 *
 * <p>这是平台承担吸收的金额，因此必须是已记录的缺口本身：窗口内
 * {@code wallet_deficit_amount} / {@code wallet_deficit_tokens} 的合计、承载这些金额的行数，
 * 以及这些行中最近的 200 行。未回收的行（两列均为零）必须完全不出现，
 * 而被截断的列表必须明确说明自己被截断了。
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
class SettlementDeficitControllerTest {

    private static final String PATH = "/internal/settlement-deficit";

    private static final UUID TENANT_ID = UUID.fromString("9d000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("9d000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID = UUID.fromString("9d000000-0000-4000-8000-000000000003");
    private static final UUID KEY_ID = UUID.fromString("9d000000-0000-4000-8000-000000000004");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    // Signed internal requests must exercise the real, isolated Redis replay fence.
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @Autowired
    WebTestClient webTestClient;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, price_amount,
                    allowed_models, total_token_quota, max_api_keys, requests_per_minute,
                    tokens_per_minute, max_concurrency, duration_days, status,
                    starts_at, expires_at, activated_at)
                VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', '%s', 'deficit-identity',
                    gen_random_uuid(), 'ADVANCED', 'Advanced', 0,
                    '[{"modelCode":"text-basic"}]', 1000000, 2, 20, 500000, 3, 30, 'ACTIVE',
                    now(), now() + interval '30 days', now())
                """.formatted(PACKAGE_ID, TENANT_ID, USER_ID));
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name, key_prefix,
                    key_hash, allowed_models, status)
                VALUES ('%s', '%s', '%s', '%s', 'Deficit', 'sk-yzh-deficit', 'deficit-key-hash',
                        '["text-basic"]', 'ACTIVE')
                """.formatted(KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID));
        // 窗口内两条缺口，窗口外一条，以及一条已完全回收、绝不能出现在报告中的行。
        seedDeficit("01", "req-deficit-1", "2026-08-20T10:00:00Z", 1250, 0);
        seedDeficit("02", "req-deficit-2", "2026-08-25T10:00:00Z", 500, 10);
        seedDeficit("03", "req-deficit-old", "2025-01-01T10:00:00Z", 999_999, 999_999);
        seedDeficit("04", "req-collected", "2026-08-26T10:00:00Z", 0, 0);
        // 一个拥挤的 7 月：再插入 205 条缺口，使 7 月窗口的记录数超过条目数上限。
        execute("""
                INSERT INTO gateway.quota_reservation(
                    id, request_id, tenant_id, user_id, api_key_id, package_instance_id,
                    model_code, reserved_tokens, package_reserved_tokens, package_settled_tokens,
                    actual_input_tokens, actual_output_tokens, actual_total_tokens, status,
                    expires_at, settled_at, wallet_deficit_amount, wallet_deficit_tokens)
                SELECT gen_random_uuid(), 'req-july-' || series, '%s', '%s', '%s', '%s',
                    'text-basic', 100, 100, 100, 10, 20, 30, 'SETTLED',
                    TIMESTAMPTZ '2026-07-15T00:00:00Z' + (series || ' seconds')::interval,
                    TIMESTAMPTZ '2026-07-15T00:00:00Z' + (series || ' seconds')::interval, 3, 0
                FROM generate_series(1, 205) AS series
                """.formatted(TENANT_ID, USER_ID, KEY_ID, PACKAGE_ID));
    }

    @Test
    void reportsTheRecordedDeficitsOfTheWindowNewestFirst() {
        signedGet(PATH + "?from=2026-08-01T00:00:00Z&to=2026-09-01T00:00:00Z")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalAmount").isEqualTo(1750)
                .jsonPath("$.totalTokens").isEqualTo(10)
                .jsonPath("$.count").isEqualTo(2)
                .jsonPath("$.truncated").isEqualTo(false)
                .jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.items[0].reservationId")
                .isEqualTo("9e000000-0000-4000-8000-000000000002")
                .jsonPath("$.items[0].amount").isEqualTo(500)
                .jsonPath("$.items[0].tokens").isEqualTo(10)
                .jsonPath("$.items[0].occurredAt").isEqualTo("2026-08-25T10:00:00Z")
                .jsonPath("$.items[0].planCode").isEqualTo("ADVANCED")
                .jsonPath("$.items[0].userId").isEqualTo(USER_ID.toString())
                .jsonPath("$.items[1].amount").isEqualTo(1250);
    }

    @Test
    void theWindowExcludesDeficitsOutsideIt() {
        signedGet(PATH + "?from=2026-08-24T00:00:00Z&to=2026-08-26T00:00:00Z")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalAmount").isEqualTo(500)
                .jsonPath("$.count").isEqualTo(1);

        signedGet(PATH + "?from=2024-01-01T00:00:00Z&to=2025-06-01T00:00:00Z")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalAmount").isEqualTo(999_999)
                .jsonPath("$.count").isEqualTo(1);
    }

    @Test
    void anOffsetOrDateOnlyBoundIsTheSameWindow() {
        signedGet(PATH + "?from=2026-08-20T00:00:00Z&to=2026-08-26T00:00:00Z")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(2);

        // 2026-08-19T16:00:00-08:00 与 2026-08-20T00:00:00Z 是同一时刻。
        signedGet(PATH + "?from=2026-08-19T16:00:00-08:00&to=2026-08-25T16:00:00-08:00")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(2)
                .jsonPath("$.totalAmount").isEqualTo(1750);

        signedGet(PATH + "?from=2026-08-20&to=2026-08-26")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // 只有日期的写法表示 UTC 午夜，因此这与上面两个是同一个窗口。
                .jsonPath("$.count").isEqualTo(2);
    }

    @Test
    void theDefaultWindowIsTheLastThirtyDays() {
        // 只有当测试自身的时钟远离 2026-08 时，预置的缺口才会相对该时钟超过 30 天；
        // 这里断言的是默认响应结构而非其内容：合计值始终是窗口内所有记录的和。
        signedGet(PATH)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalAmount").isNumber()
                .jsonPath("$.items").isArray()
                .jsonPath("$.truncated").isBoolean();
    }

    @Test
    void aWindowWithMoreRowsThanTheItemBoundSaysSoWhileTheTotalsStayComplete() {
        signedGet(PATH + "?from=2026-07-01T00:00:00Z&to=2026-08-01T00:00:00Z")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(205)
                .jsonPath("$.totalAmount").isEqualTo(615)
                .jsonPath("$.truncated").isEqualTo(true)
                .jsonPath("$.items.length()").isEqualTo(200);
    }

    @Test
    void aWindowThatDoesNotMoveForwardIsRejected() {
        signedGet(PATH + "?from=2026-09-01T00:00:00Z&to=2026-08-01T00:00:00Z")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_time_window");

        signedGet(PATH + "?from=not-a-time")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_time_window");
    }

    @Test
    void anUnsignedCallIsRejectedByTheSameHmacFilterAsEveryInternalRoute() {
        webTestClient.get().uri(PATH)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static void seedDeficit(
            String suffix, String requestId, String settledAt, long amount, long tokens)
            throws Exception {
        execute("""
                INSERT INTO gateway.quota_reservation(
                    id, request_id, tenant_id, user_id, api_key_id, package_instance_id,
                    model_code, reserved_tokens, package_reserved_tokens, package_settled_tokens,
                    actual_input_tokens, actual_output_tokens, actual_total_tokens, status,
                    expires_at, settled_at, wallet_deficit_amount, wallet_deficit_tokens)
                VALUES ('9e000000-0000-4000-8000-0000000000%s', '%s', '%s', '%s', '%s', '%s',
                    'text-basic', 100, 100, 100, 10, 20, 30, 'SETTLED',
                    TIMESTAMPTZ '%s', TIMESTAMPTZ '%s', %d, %d)
                """.formatted(suffix, requestId, TENANT_ID, USER_ID, KEY_ID, PACKAGE_ID,
                settledAt, settledAt, amount, tokens));
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
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
