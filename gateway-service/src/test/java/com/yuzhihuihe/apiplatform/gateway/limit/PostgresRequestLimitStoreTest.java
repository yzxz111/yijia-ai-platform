package com.yuzhihuihe.apiplatform.gateway.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresRequestLimitStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static PostgresRequestLimitStore store;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway").schemas("gateway")
                .locations("classpath:db/migration").load().migrate();
        String url = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/" + POSTGRES.getDatabaseName();
        store = new PostgresRequestLimitStore(DatabaseClient.create(ConnectionFactories.get(url)));
    }

    @Test
    void conservativelyEnforcesConcurrencyAndReleasesLeaseIdempotently() {
        RequestLimitService.EffectiveLimit limit = limit(10, 1);
        RequestLimitService.LimitLease lease = store.acquire(limit).block();

        assertRateLimited(() -> store.acquire(limit).block());
        store.release(lease).then(store.release(lease)).block();

        assertThat(store.acquire(limit).block()).isNotNull();
    }

    @Test
    void conservativelyEnforcesRpm() {
        RequestLimitService.EffectiveLimit limit = limit(2, 10);
        store.acquire(limit).block();
        store.acquire(limit).block();

        assertRateLimited(() -> store.acquire(limit).block());
    }

    @Test
    void channelConcurrencyIsSharedAcrossUsers() {
        UUID channelId = UUID.randomUUID();
        RequestLimitService.EffectiveLimit firstUser = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "text-basic", channelId,
                10, 1, Duration.ofSeconds(30));
        RequestLimitService.EffectiveLimit secondUser = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "text-basic", channelId,
                10, 1, Duration.ofSeconds(30));

        store.acquire(firstUser).block();

        assertRateLimited(() -> store.acquire(secondUser).block());
    }

    @Test
    void rebindMovesChannelConcurrencyWithoutConsumingAnotherGlobalRequest() {
        RequestLimitService.EffectiveLimit initial = limit(1, 1);
        RequestLimitService.LimitLease lease = store.acquire(initial).block();
        UUID selectedChannel = UUID.randomUUID();

        RequestLimitService.LimitLease rebound = store.rebindChannel(lease, selectedChannel).block();

        assertThat(rebound.leaseId()).isEqualTo(lease.leaseId());
        assertThat(rebound.channelId()).isEqualTo(selectedChannel);
        RequestLimitService.EffectiveLimit oldChannelOtherScope = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "other-model-" + UUID.randomUUID(),
                lease.channelId(), 10, 1, Duration.ofSeconds(30));
        assertThat(store.acquire(oldChannelOtherScope).block()).isNotNull();
        RequestLimitService.EffectiveLimit selectedChannelOtherScope = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "selected-model-" + UUID.randomUUID(),
                selectedChannel, 10, 1, Duration.ofSeconds(30));
        assertRateLimited(() -> store.acquire(selectedChannelOtherScope).block());
    }

    @Test
    void routeActivationChecksLowerRpmWithoutInsertingAnotherRequestEvent() throws Exception {
        UUID apiKeyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String modelCode = "activation-model-" + UUID.randomUUID();
        UUID admissionChannel = UUID.randomUUID();
        RequestLimitService.EffectiveLimit publicLimit = new RequestLimitService.EffectiveLimit(
                apiKeyId, userId, modelCode, admissionChannel,
                10, 10, Duration.ofSeconds(30));
        RequestLimitService.LimitLease first = store.acquire(publicLimit).block();

        store.activateRoute(first, UUID.randomUUID(), 1, 10).block();

        assertThat(requestEvents(apiKeyId)).isEqualTo(1);
        RequestLimitService.LimitLease second = store.acquire(publicLimit).block();
        assertRateLimited(() -> store.activateRoute(
                second, UUID.randomUUID(), 1, 10).block());
        assertThat(requestEvents(apiKeyId)).isEqualTo(2);
    }

    @Test
    void rebindRejectsLeaseThatExpiresWhileWaitingForChannelLocks() throws Exception {
        RequestLimitService.EffectiveLimit initial = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "expiring-model-" + UUID.randomUUID(),
                UUID.randomUUID(), 10, 2, Duration.ofSeconds(2));
        RequestLimitService.LimitLease lease = store.acquire(initial).block();
        UUID targetChannel = UUID.randomUUID();

        CompletableFuture<RequestLimitService.LimitLease> attempt;
        try (Connection lockConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            lockConnection.setAutoCommit(false);
            try (PreparedStatement statement = lockConnection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended('channel:' || ?::text, 0))")) {
                statement.setObject(1, targetChannel);
                statement.execute();
            }
            attempt = CompletableFuture.supplyAsync(() -> store.rebindChannel(lease, targetChannel).block());
            assertThat(awaitRebindLockWait()).isTrue();
            awaitLeaseExpiry(lease.leaseId());
            lockConnection.commit();
        }

        assertThatThrownBy(() -> attempt.get(5, TimeUnit.SECONDS))
                .isInstanceOfSatisfying(ExecutionException.class, error -> {
                    assertThat(error.getCause()).isInstanceOf(ResponseStatusException.class);
                    ResponseStatusException response = (ResponseStatusException) error.getCause();
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(response.getReason()).isEqualTo("limit_lease_unavailable");
                });
    }

    @Test
    void expiredSourceLeaseWinsOverFullTargetAfterWaitingForChannelLocks() throws Exception {
        UUID sourceChannel = UUID.randomUUID();
        UUID targetChannel = UUID.randomUUID();
        RequestLimitService.EffectiveLimit source = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "expiring-full-source-" + UUID.randomUUID(),
                sourceChannel, 10, 1, Duration.ofSeconds(2));
        RequestLimitService.EffectiveLimit targetBlocker = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "full-target-" + UUID.randomUUID(),
                targetChannel, 10, 1, Duration.ofSeconds(30));
        RequestLimitService.LimitLease sourceLease = store.acquire(source).block();
        store.acquire(targetBlocker).block();

        CompletableFuture<RequestLimitService.LimitLease> attempt;
        try (Connection lockConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            lockConnection.setAutoCommit(false);
            try (PreparedStatement statement = lockConnection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended('channel:' || ?::text, 0))")) {
                statement.setObject(1, targetChannel);
                statement.execute();
            }
            attempt = CompletableFuture.supplyAsync(
                    () -> store.rebindChannel(sourceLease, targetChannel).block());
            assertThat(awaitRebindLockWait()).isTrue();
            awaitLeaseExpiry(sourceLease.leaseId());
            lockConnection.commit();
        }

        assertThatThrownBy(() -> attempt.get(5, TimeUnit.SECONDS))
                .isInstanceOfSatisfying(ExecutionException.class, error -> {
                    assertThat(error.getCause()).isInstanceOf(ResponseStatusException.class);
                    ResponseStatusException response = (ResponseStatusException) error.getCause();
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(response.getReason()).isEqualTo("limit_lease_unavailable");
                });
    }

    @Test
    void rebindIgnoresTargetLeaseThatExpiresWhileWaitingForChannelLocks() throws Exception {
        UUID sourceChannel = UUID.randomUUID();
        UUID targetChannel = UUID.randomUUID();
        RequestLimitService.EffectiveLimit source = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "waiting-source-" + UUID.randomUUID(),
                sourceChannel, 10, 1, Duration.ofSeconds(30));
        RequestLimitService.EffectiveLimit targetBlocker = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "waiting-target-" + UUID.randomUUID(),
                targetChannel, 10, 1, Duration.ofSeconds(2));
        RequestLimitService.LimitLease sourceLease = store.acquire(source).block();
        RequestLimitService.LimitLease blockerLease = store.acquire(targetBlocker).block();

        CompletableFuture<RequestLimitService.LimitLease> attempt;
        try (Connection lockConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            lockConnection.setAutoCommit(false);
            try (PreparedStatement statement = lockConnection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended('channel:' || ?::text, 0))")) {
                statement.setObject(1, targetChannel);
                statement.execute();
            }
            attempt = CompletableFuture.supplyAsync(
                    () -> store.rebindChannel(sourceLease, targetChannel).block());
            assertThat(awaitRebindLockWait()).isTrue();
            awaitLeaseExpiry(blockerLease.leaseId());
            lockConnection.commit();
        }

        RequestLimitService.LimitLease rebound = attempt.get(5, TimeUnit.SECONDS);
        assertThat(rebound.channelId()).isEqualTo(targetChannel);
    }

    @Test
    void sameChannelRebindRejectsAnExpiredLease() throws Exception {
        RequestLimitService.LimitLease lease = store.acquire(limit(10, 2)).block();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE gateway.request_limit_fallback_lease
                        SET expires_at = clock_timestamp() - interval '1 second'
                        WHERE lease_id = ?
                        """)) {
            statement.setObject(1, lease.leaseId());
            statement.executeUpdate();
        }

        assertThatThrownBy(() -> store.rebindChannel(lease, lease.channelId()).block())
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void sameChannelRebindRejectsLeaseThatExpiresWhileWaitingForItsRowLock() throws Exception {
        RequestLimitService.EffectiveLimit expiring = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "row-lock-expiry-" + UUID.randomUUID(),
                UUID.randomUUID(), 10, 2, Duration.ofSeconds(2));
        RequestLimitService.LimitLease lease = store.acquire(expiring).block();

        CompletableFuture<RequestLimitService.LimitLease> attempt;
        try (Connection lockConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            lockConnection.setAutoCommit(false);
            try (PreparedStatement statement = lockConnection.prepareStatement("""
                    SELECT lease_id
                    FROM gateway.request_limit_fallback_lease
                    WHERE lease_id = ?
                    FOR UPDATE
                    """)) {
                statement.setObject(1, lease.leaseId());
                statement.executeQuery();
            }
            attempt = CompletableFuture.supplyAsync(
                    () -> store.rebindChannel(lease, lease.channelId()).block());
            assertThat(awaitRebindLockWait()).isTrue();
            awaitLeaseExpiry(lease.leaseId());
            lockConnection.commit();
        }

        assertThatThrownBy(() -> attempt.get(5, TimeUnit.SECONDS))
                .isInstanceOfSatisfying(ExecutionException.class, error -> {
                    assertThat(error.getCause()).isInstanceOf(ResponseStatusException.class);
                    ResponseStatusException response = (ResponseStatusException) error.getCause();
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(response.getReason()).isEqualTo("limit_lease_unavailable");
                });
    }

    @Test
    void modelConcurrencyIsSharedAcrossUsers() {
        String modelCode = "shared-model-" + UUID.randomUUID();
        RequestLimitService.EffectiveLimit firstUser = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), modelCode, UUID.randomUUID(),
                10, 1, Duration.ofSeconds(30));
        RequestLimitService.EffectiveLimit secondUser = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), modelCode, UUID.randomUUID(),
                10, 1, Duration.ofSeconds(30));

        store.acquire(firstUser).block();

        assertRateLimited(() -> store.acquire(secondUser).block());
    }

    @Test
    void modelRpmIsSharedAcrossUsers() {
        String modelCode = "shared-model-" + UUID.randomUUID();
        RequestLimitService.EffectiveLimit firstUser = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), modelCode, UUID.randomUUID(),
                1, 10, Duration.ofSeconds(30));
        RequestLimitService.EffectiveLimit secondUser = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), modelCode, UUID.randomUUID(),
                1, 10, Duration.ofSeconds(30));

        store.acquire(firstUser).block();

        assertRateLimited(() -> store.acquire(secondUser).block());
    }

    private RequestLimitService.EffectiveLimit limit(int rpm, int concurrency) {
        return new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "test-model-" + UUID.randomUUID(), UUID.randomUUID(),
                rpm, concurrency, Duration.ofSeconds(30));
    }

    private boolean awaitRebindLockWait() throws Exception {
        Instant deadline = Instant.now().plusSeconds(2);
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    PreparedStatement statement = connection.prepareStatement("""
                            SELECT EXISTS (
                                SELECT 1 FROM pg_stat_activity
                                WHERE wait_event_type = 'Lock'
                                  AND query LIKE '%rebind_request_limit_fallback_channel%')
                            """);
                    ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getBoolean(1)) {
                    return true;
                }
            }
            Thread.sleep(25);
        }
        return false;
    }

    private void awaitLeaseExpiry(UUID leaseId) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    PreparedStatement statement = connection.prepareStatement("""
                            SELECT expires_at <= clock_timestamp()
                            FROM gateway.request_limit_fallback_lease
                            WHERE lease_id = ?
                            """)) {
                statement.setObject(1, leaseId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    if (result.getBoolean(1)) {
                        return;
                    }
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("lease did not expire before the test deadline");
    }

    private void assertRateLimited(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).satisfies(error -> {
            assertThat(error).isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        });
    }

    private int requestEvents(UUID apiKeyId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT count(*)
                        FROM gateway.request_limit_fallback_event
                        WHERE api_key_id = ?
                        """)) {
            statement.setObject(1, apiKeyId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
