package com.yuzhihuihe.apiplatform.platform.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.identity.IdentityService;
import com.yuzhihuihe.apiplatform.platform.identity.JwtTokenService;
import com.yuzhihuihe.apiplatform.platform.outbox.OutboxPublisher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "outbox.publisher.initial-backoff=10m",
        "outbox.publisher.max-attempts=3",
        "outbox.publisher.claim-timeout=1m",
        "gateway.internal.connect-timeout=100ms",
        "gateway.internal.read-timeout=250ms",
        "outbox.publisher.initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class GrantOutboxTest {

    private static final UUID ADMIN_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("90000000-0000-4000-8000-000000000002");
    // ADVANCED 的版本 id **不写死**，每次从库里现查当前已发布的那一个
    // （见 publishedAdvancedVersionId()）。
    // 这里写死过两次，两次都以 404 published_package_version_not_found 失败：
    // V61__reprice_token_plans_to_thirty_percent_floor.sql:106 把 ADVANCED v1 的
    // ...103 改成 RETIRED，V63 又用 ...123 替换了 v2。每一次重新定价迁移都会换
    // 一批 UUID，而写死的常量坏掉时，错误信息指向发放接口、不指向那个过期常量。
    private static final AtomicReference<String> DELIVERED_BODY = new AtomicReference<>();
    private static final AtomicReference<String> DELIVERED_TIMESTAMP = new AtomicReference<>();
    private static final AtomicReference<String> DELIVERED_REQUEST_ID = new AtomicReference<>();
    private static final AtomicReference<String> DELIVERED_CONTENT_SHA256 = new AtomicReference<>();
    private static final AtomicReference<String> DELIVERED_SIGNATURE = new AtomicReference<>();
    private static final CopyOnWriteArrayList<String> DELIVERED_REQUEST_IDS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<String> STATUS_OBSERVED_BY_GATEWAY = new AtomicReference<>();
    private static final AtomicReference<String> GATEWAY_ACTIVATION_STATUS =
            new AtomicReference<>("ACTIVE");
    private static final AtomicInteger GATEWAY_STATUS = new AtomicInteger(200);
    private static final AtomicInteger GATEWAY_DELAY_MILLIS = new AtomicInteger();
    private static final AtomicInteger DELIVERY_COUNT = new AtomicInteger();
    private static final ExecutorService GATEWAY_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer GATEWAY = startGateway();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("gateway.internal.base-url",
                () -> "http://127.0.0.1:" + GATEWAY.getAddress().getPort());
        registry.add("security.internal.hmac-secret", () -> "test-internal-secret");
        TestJwtKeyRing.configure(registry);
    }

    @AfterAll
    static void stopGateway() {
        GATEWAY.stop(0);
        GATEWAY_EXECUTOR.shutdownNow();
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenService jwtTokenService;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    OutboxPublisher outboxPublisher;

    @BeforeEach
    void resetGrantData() {
        jdbcTemplate.update("TRUNCATE platform.admin_audit_log, platform.outbox_event, "
                + "platform.grant_request, platform.refresh_token, platform.user_role, "
                + "platform.invitation, platform.app_user RESTART IDENTITY CASCADE");
        insertUser(ADMIN_ID, "admin@example.com", "Admin");
        insertUser(USER_ID, "verified.user@example.com", "Verified User");
        DELIVERED_BODY.set(null);
        DELIVERED_TIMESTAMP.set(null);
        DELIVERED_REQUEST_ID.set(null);
        DELIVERED_CONTENT_SHA256.set(null);
        DELIVERED_SIGNATURE.set(null);
        DELIVERED_REQUEST_IDS.clear();
        STATUS_OBSERVED_BY_GATEWAY.set(null);
        GATEWAY_ACTIVATION_STATUS.set("ACTIVE");
        GATEWAY_STATUS.set(200);
        GATEWAY_DELAY_MILLIS.set(0);
        DELIVERY_COUNT.set(0);
    }

    @Test
    void adminGrantWritesRequestSnapshotOutboxAndAuditAtomically() throws Exception {
        String adminToken = jwtTokenService.createAccessToken(new IdentityService.UserProfile(
                ADMIN_ID, ADMIN_ID, "admin@example.com", "Admin", "ADMIN"));

        mockMvc.perform(post("/console/platform/admin/grants")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "90000000-0000-4000-8000-000000000002",
                                  "packageVersionId": "%s",
                                  "reason": "local acceptance",
                                  "businessNo": "grant-advanced-001"
                                }
                                """.formatted(publishedAdvancedVersionId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessNo").value("grant-advanced-001"))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        Map<String, Object> grant = jdbcTemplate.queryForMap("""
                SELECT status, source, created_by
                FROM platform.grant_request
                WHERE business_no = 'grant-advanced-001'
                """);
        assertThat(grant)
                .containsEntry("status", "QUEUED")
                .containsEntry("source", "ADMIN")
                .containsEntry("created_by", ADMIN_ID);

        Map<String, Object> outbox = jdbcTemplate.queryForMap("""
                SELECT event_type, business_key, payload::text AS payload
                FROM platform.outbox_event
                WHERE business_key = 'grant-advanced-001'
                """);
        assertThat(outbox)
                .containsEntry("event_type", "PACKAGE_GRANTED_V1")
                .containsEntry("business_key", "grant-advanced-001");
        String payload = (String) outbox.get("payload");
        // 快照里的配额与日限额**从版本行现查**，不写死。这两个数各被写死过一次
        // （配额写死 20000000、日限额写死 null），而重新定价迁移已经把 ADVANCED
        // 在售版本的它们分别改成 500000 与 10000——于是断言在定价变更之后必然失败，
        // 且失败信息只说"payload 里没有这个数"，看不出是常量过期。
        // 断言的意图是「快照确实抄下了版本行」，所以期望值应该来自版本行本身。
        Map<String, Object> version = jdbcTemplate.queryForMap("""
                SELECT total_token_quota, daily_request_limit
                FROM platform.package_plan_version WHERE id = ?
                """, publishedAdvancedVersionId());
        // JSON 里的 null 与数字要分别拼对：列是 NULL 时 payload 里就是 null。
        String expectedDailyLimit =
                version.get("daily_request_limit") == null
                        ? "null"
                        : version.get("daily_request_limit").toString();
        assertThat(payload)
                .contains("\"packageCode\": \"ADVANCED\"")
                .contains("\"totalTokenQuota\": " + version.get("total_token_quota"))
                .contains("\"modelCode\": \"text-pro\"")
                .contains("\"dailyRequestLimit\": " + expectedDailyLimit)
                .containsPattern("\\\"identityKey\\\": \\\"[0-9a-f]{64}\\\"")
                .doesNotContain("verified.user@example.com");

        Integer auditCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action = 'PACKAGE_GRANT_CREATED'
                  AND outcome = 'SUCCESS'
                """, Integer.class, ADMIN_ID);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void publisherSignsAndActivatesQueuedGrant() throws Exception {
        createAdvancedGrant("grant-delivery-001");

        assertThat(outboxState("grant-delivery-001"))
                .containsEntry("outbox_status", "PENDING")
                .containsEntry("grant_status", "QUEUED");

        Class<?> publisherType;
        try {
            publisherType = Class.forName(
                    "com.yuzhihuihe.apiplatform.platform.outbox.OutboxPublisher");
        } catch (ClassNotFoundException exception) {
            fail("OutboxPublisher is missing");
            return;
        }
        Object publisher = applicationContext.getBean(publisherType);
        publisherType.getMethod("publishPending").invoke(publisher);

        Map<String, Object> state = outboxState("grant-delivery-001");
        assertThat(state)
                .containsEntry("outbox_status", "DELIVERED")
                .containsEntry("grant_status", "ACTIVE");
        assertThat(DELIVERED_BODY.get())
                .contains("\"eventType\":\"PACKAGE_GRANTED_V1\"")
                .contains("\"businessKey\":\"grant-delivery-001\"")
                .contains("\"packageCode\":\"ADVANCED\"");
        assertThat(DELIVERED_REQUEST_ID.get()).isNotBlank().satisfies(value ->
                assertThat(UUID.fromString(value).toString()).isEqualTo(value));
        assertThat(DELIVERED_CONTENT_SHA256).hasValue(sha256(DELIVERED_BODY.get()));
        assertThat(DELIVERED_SIGNATURE.get()).isEqualTo(internalSignature(
                DELIVERED_TIMESTAMP.get(), DELIVERED_REQUEST_ID.get(), "POST",
                "/internal/events/package-granted", DELIVERED_CONTENT_SHA256.get()));
        assertThat(STATUS_OBSERVED_BY_GATEWAY).hasValue("DELIVERING");
    }

    @Test
    void failedDeliverySchedulesExponentialRetry() throws Exception {
        createAdvancedGrant("grant-retry-001");
        GATEWAY_STATUS.set(503);

        assertThatCode(outboxPublisher::publishPending).doesNotThrowAnyException();
        Map<String, Object> firstAttempt = outboxState("grant-retry-001");
        assertThat(firstAttempt)
                .containsEntry("outbox_status", "PENDING")
                .containsEntry("grant_status", "QUEUED")
                .containsEntry("attempt_count", 1);
        Instant firstRetry = ((Timestamp) firstAttempt.get("next_attempt_at")).toInstant();
        assertThat(firstRetry).isAfter(Instant.now().plusSeconds(9 * 60));

        jdbcTemplate.update("""
                UPDATE platform.outbox_event SET next_attempt_at = now()
                WHERE business_key = 'grant-retry-001'
                """);
        assertThatCode(outboxPublisher::publishPending).doesNotThrowAnyException();

        Map<String, Object> secondAttempt = outboxState("grant-retry-001");
        assertThat(secondAttempt)
                .containsEntry("outbox_status", "PENDING")
                .containsEntry("grant_status", "QUEUED")
                .containsEntry("attempt_count", 2);
        assertThat(((Timestamp) secondAttempt.get("next_attempt_at")).toInstant())
                .isAfter(Instant.now().plusSeconds(19 * 60))
                .isAfter(firstRetry);
        assertThat(DELIVERY_COUNT).hasValue(2);
        assertThat(DELIVERED_REQUEST_IDS).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void queuedResponseResetsFailureBudgetBeforeNextTransportFailure() throws Exception {
        createAdvancedGrant("grant-queued-budget-reset-001");
        GATEWAY_STATUS.set(503);

        for (int failure = 0; failure < 2; failure++) {
            outboxPublisher.publishPending();
            jdbcTemplate.update("""
                    UPDATE platform.outbox_event SET next_attempt_at = now()
                    WHERE business_key = 'grant-queued-budget-reset-001'
                    """);
        }

        GATEWAY_STATUS.set(200);
        GATEWAY_ACTIVATION_STATUS.set("QUEUED");
        outboxPublisher.publishPending();
        assertThat(outboxState("grant-queued-budget-reset-001"))
                .containsEntry("outbox_status", "PENDING")
                .containsEntry("grant_status", "QUEUED");

        jdbcTemplate.update("""
                UPDATE platform.outbox_event SET next_attempt_at = now()
                WHERE business_key = 'grant-queued-budget-reset-001'
                """);
        GATEWAY_STATUS.set(503);
        outboxPublisher.publishPending();

        assertThat(outboxState("grant-queued-budget-reset-001"))
                .containsEntry("outbox_status", "PENDING")
                .containsEntry("grant_status", "QUEUED")
                .containsEntry("attempt_count", 1);
        assertThat(DELIVERY_COUNT).hasValue(4);
    }

    @Test
    void finalDeliveryFailureFailsOutboxAndGrant() throws Exception {
        createAdvancedGrant("grant-failed-001");
        jdbcTemplate.update("""
                UPDATE platform.outbox_event SET attempt_count = 2
                WHERE business_key = 'grant-failed-001'
                """);
        GATEWAY_STATUS.set(503);

        assertThatCode(outboxPublisher::publishPending).doesNotThrowAnyException();

        Map<String, Object> state = outboxState("grant-failed-001");
        assertThat(state)
                .containsEntry("outbox_status", "FAILED")
                .containsEntry("grant_status", "FAILED")
                .containsEntry("attempt_count", 3);
        assertThat((String) state.get("last_error")).contains("503");
    }

    @Test
    void redirectResponseSchedulesRetryInsteadOfCompletingGrant() throws Exception {
        createAdvancedGrant("grant-redirect-001");
        GATEWAY_STATUS.set(302);

        assertThatCode(outboxPublisher::publishPending).doesNotThrowAnyException();

        assertThat(outboxState("grant-redirect-001"))
                .containsEntry("outbox_status", "PENDING")
                .containsEntry("grant_status", "QUEUED")
                .containsEntry("attempt_count", 1);
    }

    @Test
    void readTimeoutReleasesPublisherAndSchedulesRetry() throws Exception {
        createAdvancedGrant("grant-timeout-001");
        GATEWAY_DELAY_MILLIS.set(500);

        Instant startedAt = Instant.now();
        assertThatCode(outboxPublisher::publishPending).doesNotThrowAnyException();

        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofMillis(400));
        assertThat(outboxState("grant-timeout-001"))
                .containsEntry("outbox_status", "PENDING")
                .containsEntry("grant_status", "QUEUED")
                .containsEntry("attempt_count", 1);
    }

    @Test
    void expiredDeliveringClaimIsRecovered() throws Exception {
        createAdvancedGrant("grant-stuck-001");
        jdbcTemplate.update("""
                UPDATE platform.outbox_event
                SET status = 'DELIVERING', claim_token = ?,
                    next_attempt_at = now() - interval '1 second'
                WHERE business_key = 'grant-stuck-001'
                """, UUID.randomUUID());
        outboxPublisher.publishPending();

        Map<String, Object> state = outboxState("grant-stuck-001");
        assertThat(state)
                .containsEntry("outbox_status", "DELIVERED")
                .containsEntry("grant_status", "ACTIVE")
                .containsEntry("attempt_count", 0);
        assertThat(DELIVERY_COUNT).hasValue(1);
    }

    @Test
    void recoveredClaimRejectsStaleWorkerToken() throws Exception {
        createAdvancedGrant("grant-stale-token-001");
        UUID eventId = jdbcTemplate.queryForObject("""
                SELECT id FROM platform.outbox_event
                WHERE business_key = 'grant-stale-token-001'
                """, UUID.class);
        UUID staleToken = UUID.randomUUID();
        jdbcTemplate.update("""
                UPDATE platform.outbox_event
                SET status = 'DELIVERING', claim_token = ?,
                    next_attempt_at = now() - interval '1 second'
                WHERE id = ?
                """, staleToken, eventId);

        outboxPublisher.publishPending();

        int staleUpdate = jdbcTemplate.update("""
                UPDATE platform.outbox_event
                SET status = 'FAILED', claim_token = NULL
                WHERE id = ? AND status = 'DELIVERING' AND claim_token = ?
                """, eventId, staleToken);
        assertThat(staleUpdate).isZero();
        assertThat(outboxState("grant-stale-token-001"))
                .containsEntry("outbox_status", "DELIVERED")
                .containsEntry("grant_status", "ACTIVE")
                .containsEntry("attempt_count", 0);
    }

    @Test
    void migrationEnforcesOutboxClaimTokenInvariant() throws Exception {
        createAdvancedGrant("grant-claim-invariant-001");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE platform.outbox_event
                SET status = 'DELIVERING', claim_token = NULL
                WHERE business_key = 'grant-claim-invariant-001'
                """))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .rootCause()
                .hasMessageContaining("ck_outbox_claim_token");
    }

    @Test
    void publisherDeclaresConfigurableSchedule() throws Exception {
        Scheduled schedule = OutboxPublisher.class.getMethod("publishPending")
                .getAnnotation(Scheduled.class);

        assertThat(schedule).isNotNull();
        assertThat(schedule.fixedDelayString()).isEqualTo("${outbox.publisher.delay-ms:1000}");
        assertThat(schedule.initialDelayString())
                .isEqualTo("${outbox.publisher.initial-delay-ms:1000}");
    }

    private Map<String, Object> outboxState(String businessNo) {
        return jdbcTemplate.queryForMap("""
                SELECT o.status AS outbox_status, o.attempt_count, o.next_attempt_at,
                       o.last_error, g.status AS grant_status
                FROM platform.outbox_event o
                JOIN platform.grant_request g ON g.id = o.aggregate_id
                WHERE o.business_key = ?
                """, businessNo);
    }

    /**
     * 当前已发布的 ADVANCED 版本 id，**现查**。
     *
     * <p>发放接口只接受已发布版本（{@code PackageSnapshotService.loadPublished}），
     * 而在售版本 id 会被每一次重新定价迁移换掉。现查因此是这个测试唯一稳定的写法：
     * 它跟着迁移链走，不需要有人记得回来改一个常量。
     */
    private UUID publishedAdvancedVersionId() {
        return jdbcTemplate.queryForObject("""
                SELECT version.id
                FROM platform.package_plan_version version
                JOIN platform.package_plan plan ON plan.id = version.package_plan_id
                WHERE plan.code = 'ADVANCED' AND version.status = 'PUBLISHED'
                """, UUID.class);
    }

    private void createAdvancedGrant(String businessNo) throws Exception {
        String adminToken = jwtTokenService.createAccessToken(new IdentityService.UserProfile(
                ADMIN_ID, ADMIN_ID, "admin@example.com", "Admin", "ADMIN"));
        mockMvc.perform(post("/console/platform/admin/grants")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "%s",
                                  "packageVersionId": "%s",
                                  "reason": "outbox delivery",
                                  "businessNo": "%s"
                                }
                                """.formatted(USER_ID, publishedAdvancedVersionId(), businessNo)))
                .andExpect(status().isCreated());
    }

    private void insertUser(UUID id, String email, String displayName) {
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'test-password-hash', ?, 'ACTIVE')
                """, id, id, email, displayName);
    }

    private String internalSignature(
            String timestamp, String requestId, String method, String path, String contentSha256)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-internal-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (timestamp + "\n" + requestId + "\n" + method + "\n" + path + "\n" + contentSha256)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256(String body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpServer startGateway() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(GATEWAY_EXECUTOR);
            server.createContext("/internal/events/package-granted", exchange -> {
                DELIVERY_COUNT.incrementAndGet();
                try {
                    Thread.sleep(GATEWAY_DELAY_MILLIS.get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                STATUS_OBSERVED_BY_GATEWAY.set(currentOutboxStatus());
                DELIVERED_TIMESTAMP.set(exchange.getRequestHeaders().getFirst("X-Internal-Timestamp"));
                DELIVERED_REQUEST_ID.set(exchange.getRequestHeaders().getFirst("X-Internal-Request-Id"));
                DELIVERED_CONTENT_SHA256.set(
                        exchange.getRequestHeaders().getFirst("X-Internal-Content-Sha256"));
                DELIVERED_SIGNATURE.set(exchange.getRequestHeaders().getFirst("X-Internal-Signature"));
                DELIVERED_REQUEST_IDS.add(DELIVERED_REQUEST_ID.get());
                DELIVERED_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = ("{\"packageInstanceId\":\"" + UUID.randomUUID()
                        + "\",\"status\":\"" + GATEWAY_ACTIVATION_STATUS.get() + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(GATEWAY_STATUS.get(), response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String currentOutboxStatus() {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT status FROM platform.outbox_event
                        ORDER BY created_at, id LIMIT 1
                        """)) {
            return result.next() ? result.getString(1) : null;
        } catch (Exception exception) {
            throw new IllegalStateException("outbox_status_observation_failed", exception);
        }
    }
}
