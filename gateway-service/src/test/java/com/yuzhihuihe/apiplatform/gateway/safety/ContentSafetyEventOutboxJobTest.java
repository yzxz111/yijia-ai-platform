package com.yuzhihuihe.apiplatform.gateway.safety;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

@Testcontainers
class ContentSafetyEventOutboxJobTest {

    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private DatabaseClient database;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway").schemas("gateway").locations("classpath:db/migration")
                .load().migrate();
    }

    @BeforeEach
    void reset() throws Exception {
        execute("TRUNCATE gateway.content_safety_event_outbox");
        var options = ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                .option(ConnectionFactoryOptions.PORT, POSTGRES.getFirstMappedPort())
                .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword()).build();
        database = DatabaseClient.create(ConnectionFactories.get(options));
    }

    @Test
    void successMarksClaimDelivered() throws Exception {
        seed("PENDING", 0, null, null, NOW.minusSeconds(1));

        job(event -> Mono.empty(), 5).runOnce().block();

        assertThat(state()).startsWith("DELIVERED:1:");
    }

    @Test
    void failureUsesExponentialBackoff() throws Exception {
        seed("PENDING", 0, null, null, NOW.minusSeconds(1));
        job(event -> Mono.error(new IllegalStateException("secret payload")), 5).runOnce().block();
        assertThat(state()).isEqualTo("FAILED:1:2026-08-09 00:01:00+00:delivery_failed");

        execute("UPDATE gateway.content_safety_event_outbox SET next_attempt_at='2026-08-08T23:59:59Z'");
        job(event -> Mono.error(new IllegalStateException("secret payload")), 5).runOnce().block();
        assertThat(state()).isEqualTo("FAILED:2:2026-08-09 00:02:00+00:delivery_failed");
    }

    @Test
    void stalledDeliveryTimesOutAndIsMarkedFailed() throws Exception {
        seed("PENDING", 0, null, null, NOW.minusSeconds(1));

        job(event -> Mono.never(), 5, Duration.ofMillis(25)).runOnce().block(Duration.ofSeconds(2));

        assertThat(state()).isEqualTo("FAILED:1:2026-08-09 00:01:00+00:delivery_failed");
    }

    @Test
    void expiredDeliveryLeaseCanBeRecovered() throws Exception {
        UUID oldToken = UUID.randomUUID();
        seed("DELIVERING", 1, oldToken, NOW.minusSeconds(1), NOW.minusSeconds(1));
        ContentSafetyEventOutboxJob job = job(event -> Mono.empty(), 5);

        ContentSafetyEventOutboxJob.Claim claim = job.claimOne().block();

        assertThat(claim).isNotNull();
        assertThat(claim.claimToken()).isNotEqualTo(oldToken);
        job.markDelivered(claim.id(), oldToken).block();
        assertThat(singleString("SELECT status FROM gateway.content_safety_event_outbox"))
                .isEqualTo("DELIVERING");
        job.markDelivered(claim.id(), claim.claimToken()).block();
        assertThat(singleString("SELECT status FROM gateway.content_safety_event_outbox"))
                .isEqualTo("DELIVERED");
    }

    @Test
    void maximumAttemptsAreNotRedelivered() throws Exception {
        seed("FAILED", 5, null, null, NOW.minusSeconds(1));
        AtomicInteger deliveries = new AtomicInteger();

        job(event -> {
            deliveries.incrementAndGet();
            return Mono.empty();
        }, 5).runOnce().block();

        assertThat(deliveries).hasValue(0);
        assertThat(state()).startsWith("FAILED:5:");
    }

    @Test
    void scheduledPublisherReevaluatesTimeOnEverySubscription() throws Exception {
        seed("PENDING", 0, null, null, NOW.plusSeconds(60));
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger deliveries = new AtomicInteger();
        ContentSafetyEventOutboxJob job = job(event -> {
            deliveries.incrementAndGet();
            return Mono.empty();
        }, clock, 5);
        Mono<Integer> scheduledPublisher = job.scheduledRun();

        assertThat(scheduledPublisher.block()).isZero();
        clock.set(NOW.plusSeconds(61));
        assertThat(scheduledPublisher.block()).isOne();
        assertThat(deliveries).hasValue(1);
    }

    private ContentSafetyEventOutboxJob job(
            ContentSafetyEventOutboxJob.DeliveryClient client, int maximumAttempts) {
        return job(client, Clock.fixed(NOW, ZoneOffset.UTC), maximumAttempts);
    }

    private ContentSafetyEventOutboxJob job(
            ContentSafetyEventOutboxJob.DeliveryClient client, Clock clock, int maximumAttempts) {
        return job(client, clock, maximumAttempts, Duration.ofSeconds(10));
    }

    private ContentSafetyEventOutboxJob job(
            ContentSafetyEventOutboxJob.DeliveryClient client, int maximumAttempts,
            Duration deliveryTimeout) {
        return job(client, Clock.fixed(NOW, ZoneOffset.UTC), maximumAttempts, deliveryTimeout);
    }

    private ContentSafetyEventOutboxJob job(
            ContentSafetyEventOutboxJob.DeliveryClient client, Clock clock, int maximumAttempts,
            Duration deliveryTimeout) {
        return new ContentSafetyEventOutboxJob(database, new ObjectMapper(), client, clock,
                10, Duration.ofMinutes(5),
                Duration.ofMinutes(1), Duration.ofHours(1), maximumAttempts, deliveryTimeout);
    }

    private static void seed(
            String status, int attempts, UUID token, Instant claimedUntil, Instant nextAttempt) throws Exception {
        String tokenSql = token == null ? "NULL" : "'" + token + "'";
        String claimSql = claimedUntil == null ? "NULL" : "'" + claimedUntil + "'";
        execute("""
                INSERT INTO gateway.content_safety_event_outbox(
                    id,event_type,target_service,event_fingerprint,payload,payload_hash,status,
                    attempt_count,claim_token,claimed_until,next_attempt_at)
                VALUES ('93000000-0000-4000-8000-000000000001','CONTENT_SAFETY_INPUT',
                    'platform-service',repeat('a',64),
                    '{"businessId":"gw-test","eventType":"CONTENT_SAFETY_INPUT","requestId":"req-1",\
                    "tenantId":"93000000-0000-4000-8000-000000000002",\
                    "userId":"93000000-0000-4000-8000-000000000003","direction":"INPUT",\
                    "source":"LOCAL","category":"SAFETY","severity":"HIGH","action":"BLOCK",\
                    "policyVersion":1,"evidenceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',
                    repeat('b',64),'%s',%d,%s,%s,'%s')
                """.formatted(status, attempts, tokenSql, claimSql, nextAttempt));
    }

    private static String state() throws Exception {
        return singleString("""
                SELECT status || ':' || attempt_count || ':' ||
                    to_char(next_attempt_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
                    || '+00:' || coalesce(last_error,'')
                FROM gateway.content_safety_event_outbox
                """);
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String singleString(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
