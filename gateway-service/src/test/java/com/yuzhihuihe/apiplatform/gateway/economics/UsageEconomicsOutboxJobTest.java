package com.yuzhihuihe.apiplatform.gateway.economics;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

@Testcontainers
class UsageEconomicsOutboxJobTest {

    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

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
        execute("TRUNCATE gateway.usage_economics_outbox");
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
    void twoHundredMarksClaimSent() throws Exception {
        seed("PENDING", 0, null, null, NOW.minusSeconds(1));
        job(payload -> Mono.just(204), 5).runOnce().block();
        assertThat(state()).startsWith("SENT:1:");
    }

    @Test
    void scheduledPublisherReadsTheClockAgainForEachSubscription() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        UsageEconomicsOutboxJob job = new UsageEconomicsOutboxJob(
                database, payload -> Mono.just(204), clock,
                10, Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofHours(1), 5);
        Mono<Integer> scheduled = job.scheduledRun();

        scheduled.block();
        clock.advance(Duration.ofMinutes(1));
        seed("PENDING", 0, null, null, NOW.plusSeconds(30));
        scheduled.block();

        assertThat(state()).startsWith("SENT:1:");
    }

    @Test
    void fiveHundredAndTimeoutFailWithExponentialBackoffAndBoundedSafeError() throws Exception {
        seed("PENDING", 0, null, null, NOW.minusSeconds(1));
        job(payload -> Mono.just(503), 5).runOnce().block();
        assertThat(state()).isEqualTo("FAILED:1:2026-08-08 00:01:00+00:http_5xx");

        execute("UPDATE gateway.usage_economics_outbox SET next_attempt_at='2026-08-07T23:59:59Z'");
        job(payload -> Mono.error(new java.util.concurrent.TimeoutException("payload secret")), 5)
                .runOnce().block();
        assertThat(state()).isEqualTo("FAILED:2:2026-08-08 00:02:00+00:delivery_timeout");
    }

    @Test
    void fourHundredContractFailureIsDead() throws Exception {
        seed("PENDING", 0, null, null, NOW.minusSeconds(1));
        job(payload -> Mono.just(422), 5).runOnce().block();
        assertThat(state()).startsWith("DEAD:1:");
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429})
    void retryableClientPressureStatusesUseBackoff(int status) throws Exception {
        seed("PENDING", 0, null, null, NOW.minusSeconds(1));

        job(payload -> Mono.just(status), 5).runOnce().block();

        assertThat(state()).isEqualTo(
                "FAILED:1:2026-08-08 00:01:00+00:http_retryable_4xx");
    }

    @Test
    void expiredSendingLeaseCanBeTakenOverAndOldTokenCannotCompleteIt() throws Exception {
        UUID oldToken = UUID.randomUUID();
        seed("SENDING", 1, oldToken, NOW.minusSeconds(1), NOW.minusSeconds(1));
        UsageEconomicsOutboxJob job = job(payload -> Mono.just(200), 5);
        UsageEconomicsOutboxJob.Claim claim = job.claimOne().block();
        assertThat(claim).isNotNull();
        assertThat(claim.claimToken()).isNotEqualTo(oldToken);

        job.markSent(claim.eventId(), oldToken).block();
        assertThat(singleString("SELECT status FROM gateway.usage_economics_outbox"))
                .isEqualTo("SENDING");
        job.markSent(claim.eventId(), claim.claimToken()).block();
        assertThat(singleString("SELECT status FROM gateway.usage_economics_outbox"))
                .isEqualTo("SENT");
    }

    @Test
    void maximumAttemptIsDeadInsteadOfRetried() throws Exception {
        seed("FAILED", 4, null, null, NOW.minusSeconds(1));
        job(payload -> Mono.just(500), 5).runOnce().block();
        assertThat(state()).startsWith("DEAD:5:");
    }

    @Test
    void expiredSendingAtMaximumAttemptsBecomesDeadWithoutRedelivery() throws Exception {
        seed("SENDING", 5, UUID.randomUUID(), NOW.minusSeconds(1), NOW.minusSeconds(1));
        AtomicInteger deliveries = new AtomicInteger();

        job(payload -> {
            deliveries.incrementAndGet();
            return Mono.just(200);
        }, 5).runOnce().block();

        assertThat(deliveries).hasValue(0);
        assertThat(state()).startsWith("DEAD:5:");
    }

    private UsageEconomicsOutboxJob job(UsageEconomicsOutboxJob.DeliveryClient client, int maxAttempts) {
        return new UsageEconomicsOutboxJob(database, client, Clock.fixed(NOW, ZoneOffset.UTC),
                10, Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofHours(1), maxAttempts);
    }

    private static void seed(
            String status, int attempts, UUID token, Instant claimedUntil, Instant nextAttempt) throws Exception {
        String tokenSql = token == null ? "NULL" : "'" + token + "'";
        String claimSql = claimedUntil == null ? "NULL" : "'" + claimedUntil + "'";
        execute("""
                INSERT INTO gateway.usage_economics_outbox(
                    event_id,event_fingerprint,payload,payload_hash,status,attempt_count,
                    claim_token,claimed_until,next_attempt_at)
                VALUES ('92000000-0000-4000-8000-000000000001', repeat('a',64),
                    '{"event_id":"92000000-0000-4000-8000-000000000001"}', repeat('b',64),
                    '%s',%d,%s,%s,'%s')
                """.formatted(status, attempts, tokenSql, claimSql, nextAttempt));
    }

    private static String state() throws Exception {
        return singleString("""
                SELECT status || ':' || attempt_count || ':' ||
                    to_char(next_attempt_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
                    || '+00:' || coalesce(last_error,'')
                FROM gateway.usage_economics_outbox
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
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
