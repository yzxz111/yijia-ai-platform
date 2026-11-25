package com.yuzhihuihe.apiplatform.gateway.safety;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SafetyPersistenceServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("94000000-0000-4000-8000-000000000001");
    private static final UUID ACTIVE_USER_ID =
            UUID.fromString("94000000-0000-4000-8000-000000000002");
    private static final UUID EXPIRED_USER_ID =
            UUID.fromString("94000000-0000-4000-8000-000000000003");
    private static final UUID CLEARED_USER_ID =
            UUID.fromString("94000000-0000-4000-8000-000000000004");
    private static final UUID API_KEY_ID =
            UUID.fromString("94000000-0000-4000-8000-000000000005");
    private static final String FINGERPRINT = "a".repeat(64);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private DatabaseClient database;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void reset() throws Exception {
        execute(
                "TRUNCATE gateway.content_safety_check, "
                        + "gateway.content_safety_event_outbox, gateway.safety_enforcement_cache");
        var options =
                ConnectionFactoryOptions.builder()
                        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                        .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                        .option(ConnectionFactoryOptions.PORT, POSTGRES.getFirstMappedPort())
                        .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                        .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                        .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                        .build();
        database = DatabaseClient.create(ConnectionFactories.get(options));
    }

    @Test
    void retainsRestrictedAndClearedCacheStatesWithoutFailingOpen() throws Exception {
        insertEnforcement(ACTIVE_USER_ID, "FROZEN", null);
        insertEnforcement(EXPIRED_USER_ID, "BANNED", Instant.parse("2000-01-01T00:00:00Z"));
        insertEnforcement(CLEARED_USER_ID, "CLEARED", null);
        var service =
                new AccountEnforcementService(new AccountEnforcementRepository(database));

        AccountEnforcementService.Enforcement active =
                service.current(TENANT_ID, ACTIVE_USER_ID).block();

        assertThat(active)
                .isEqualTo(
                        new AccountEnforcementService.Enforcement(
                                "FROZEN", "policy.test", "CASE-1"));
        assertThat(service.current(TENANT_ID, EXPIRED_USER_ID).block())
                .isEqualTo(new AccountEnforcementService.Enforcement(
                        "BANNED", "policy.test", "CASE-1"));
        assertThat(service.current(TENANT_ID, CLEARED_USER_ID).block())
                .isEqualTo(new AccountEnforcementService.Enforcement(
                        "CLEARED", "policy.test", "CASE-1"));
        assertThat(service.current(null, ACTIVE_USER_ID).block()).isNull();
    }

    @Test
    void recordsSerializedSafetyCheckWithNonnegativeDuration() throws Exception {
        var service =
                new ContentSafetyCheckService(
                        new ContentSafetyCheckRepository(database), new ObjectMapper());

        service.record(
                        "request-1",
                        TENANT_ID,
                        ACTIVE_USER_ID,
                        API_KEY_ID,
                        "INPUT",
                        "ALLOW",
                        7,
                        Map.of("result", "ALLOW"),
                        null,
                        Map.of("references", 0),
                        -9,
                        FINGERPRINT)
                .block();

        assertThat(
                        singleString(
                                """
                                SELECT request_id || ':' || duration_ms || ':' ||
                                       (local_result ->> 'result') || ':' ||
                                       semantic_result::text || ':' ||
                                       (file_result ->> 'references')
                                FROM gateway.content_safety_check
                                """))
                .isEqualTo("request-1:0:ALLOW:{}:0");
    }

    @Test
    void enqueuesIdempotentEventWithStableHashes() throws Exception {
        var service =
                new ContentSafetyEventOutboxService(
                        new ContentSafetyEventOutboxRepository(database), new ObjectMapper());
        SafetyEventPayload event =
                new SafetyEventPayload(
                        "business-1",
                        "CONTENT_SAFETY_INPUT",
                        "request-1",
                        TENANT_ID,
                        ACTIVE_USER_ID,
                        "INPUT",
                        "LOCAL",
                        "SAFETY",
                        "HIGH",
                        "BLOCK",
                        7,
                        FINGERPRINT,
                        null,
                        "policy.test",
                        null,
                        null,
                        null);

        service.enqueue(event).block();
        service.enqueue(event).block();

        assertThat(singleLong("SELECT count(*) FROM gateway.content_safety_event_outbox"))
                .isOne();
        assertThat(
                        singleString(
                                """
                                SELECT target_service || ':' || status || ':' ||
                                       (payload ->> 'businessId') || ':' || event_fingerprint
                                FROM gateway.content_safety_event_outbox
                                """))
                .isEqualTo("platform-service:PENDING:business-1:" + sha256("business-1"));
        assertThat(singleString("SELECT payload_hash FROM gateway.content_safety_event_outbox"))
                .matches("[0-9a-f]{64}");
    }

    private static void insertEnforcement(UUID userId, String state, Instant expiresAt)
            throws Exception {
        String expiry = expiresAt == null ? "NULL" : "'" + expiresAt + "'";
        execute(
                """
                INSERT INTO gateway.safety_enforcement_cache(
                    id, tenant_id, user_id, state, reason_code, case_no,
                    event_version, effective_at, expires_at)
                VALUES ('%s', '%s', '%s', '%s', 'policy.test', 'CASE-1', 1, now(), %s)
                """
                        .formatted(UUID.randomUUID(), TENANT_ID, userId, state, expiry));
    }

    private static void execute(String sql) throws Exception {
        try (var connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String singleString(String sql) throws Exception {
        try (var connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static long singleLong(String sql) throws Exception {
        try (var connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
