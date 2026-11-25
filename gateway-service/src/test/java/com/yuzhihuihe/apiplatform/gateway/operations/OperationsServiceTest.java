package com.yuzhihuihe.apiplatform.gateway.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.file.ExpiredFileCleanupJob;
import com.yuzhihuihe.apiplatform.gateway.quota.ReservationRecoveryJob;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelCircuitRegistry;
import io.r2dbc.spi.ConnectionFactories;
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
class OperationsServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR_ID =
            UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_TENANT_ID =
            UUID.fromString("61000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_ACTOR_ID =
            UUID.fromString("71000000-0000-4000-8000-000000000002");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private DatabaseClient database;
    private ReservationRecoveryJob recoveryJob;
    private ExpiredFileCleanupJob cleanupJob;
    private OperationsService service;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void reset() throws Exception {
        execute("TRUNCATE gateway.admin_operation_audit, gateway.file_object CASCADE");
        database = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl()));
        recoveryJob = mock(ReservationRecoveryJob.class);
        cleanupJob = mock(ExpiredFileCleanupJob.class);
        ChannelCircuitRegistry circuits = mock(ChannelCircuitRegistry.class);
        when(circuits.health()).thenReturn(List.of());
        when(recoveryJob.recoverOnce()).thenReturn(Mono.just(4));
        when(cleanupJob.cleanup()).thenReturn(Mono.empty());
        service = new OperationsService(
                new OperationsRepository(database), recoveryJob, cleanupJob, circuits);
    }

    @Test
    void manualOperationsAreIdempotentAndPersistTheAuthenticatedActor() throws Exception {
        OperationsService.OperationResult first = withActor(
                service.recoverReservations(), "idem-recover-1", "req-recover-1");
        OperationsService.OperationResult replay = withActor(
                service.recoverReservations(), "idem-recover-1", "req-recover-1");
        withActor(service.cleanupFiles(), "idem-cleanup-1", "req-cleanup-1");
        withActor(service.cleanupFiles(), "idem-cleanup-1", "req-cleanup-1");

        assertThat(first).isEqualTo(replay);
        assertThat(first.affected()).isEqualTo(4);
        verify(recoveryJob, times(1)).recoverOnce();
        verify(cleanupJob, times(1)).cleanup();
        assertThat(singleLong("SELECT count(*) FROM gateway.admin_operation_audit"))
                .isEqualTo(2);
        assertThat(values("""
                SELECT action || ':' || actor_user_id || ':' || tenant_id || ':' || request_id
                FROM gateway.admin_operation_audit ORDER BY action
                """)).containsExactly(
                        "GATEWAY_FILES_CLEANED:" + ACTOR_ID + ":" + TENANT_ID + ":req-cleanup-1",
                        "GATEWAY_RESERVATIONS_RECOVERED:" + ACTOR_ID + ":" + TENANT_ID
                                + ":req-recover-1");
    }

    @Test
    void scopesIdempotencyKeysByTenantActorAndAction() throws Exception {
        withActor(service.recoverReservations(), "shared-key", "req-recover-a");
        withActor(service.cleanupFiles(), "shared-key", "req-cleanup-a");
        withActor(service.recoverReservations(), "shared-key", "req-recover-b",
                OTHER_TENANT_ID, OTHER_ACTOR_ID);
        withActor(service.recoverReservations(), "shared-key", "req-recover-a");

        verify(recoveryJob, times(2)).recoverOnce();
        verify(cleanupJob, times(1)).cleanup();
        assertThat(singleLong("SELECT count(*) FROM gateway.admin_operation_audit"))
                .isEqualTo(3);
    }

    @Test
    void persistsAReplayableSanitizedFailureAudit() throws Exception {
        when(recoveryJob.recoverOnce()).thenReturn(Mono.error(
                new IllegalStateException("provider-secret-must-not-be-persisted")));

        assertThatThrownBy(() -> withActor(
                service.recoverReservations(), "failed-key", "req-failed"))
                .isInstanceOf(IllegalStateException.class);
        OperationsService.OperationResult replay = withActor(
                service.recoverReservations(), "failed-key", "req-failed");

        assertThat(replay.status()).isEqualTo("FAILED");
        verify(recoveryJob, times(1)).recoverOnce();
        assertThat(values("""
                SELECT status || ':' || failure_summary
                FROM gateway.admin_operation_audit
                WHERE request_id = 'req-failed'
                """)).containsExactly("FAILED:IllegalStateException");
        assertThat(values("""
                SELECT coalesce(failure_summary, '')
                FROM gateway.admin_operation_audit
                WHERE request_id = 'req-failed'
                """)).noneMatch(value -> value.contains("provider-secret"));
    }

    @Test
    void healthCountsExpiredFailedFilesAsCleanupCandidates() throws Exception {
        execute("""
                INSERT INTO gateway.file_object(
                    id, external_file_id, tenant_id, owner_user_id, object_key,
                    original_filename, sha256, mime_type, size_bytes, status,
                    expires_at, created_at, updated_at)
                VALUES (gen_random_uuid(), 'file_failed_expired', gen_random_uuid(),
                    gen_random_uuid(), 'files/failed-expired', 'failed.pdf', repeat('0', 64),
                    'application/pdf', 10, 'FAILED', now() - interval '1 minute', now(), now())
                """);

        OperationsService.HealthSnapshot health = service.health().block(Duration.ofSeconds(3));

        assertThat(health).isNotNull();
        assertThat(health.cleanupCandidates()).isEqualTo(1);
    }

    private OperationsService.OperationResult withActor(
            Mono<OperationsService.OperationResult> operation,
            String idempotencyKey,
            String requestId) {
        return withActor(operation, idempotencyKey, requestId, TENANT_ID, ACTOR_ID);
    }

    private OperationsService.OperationResult withActor(
            Mono<OperationsService.OperationResult> operation,
            String idempotencyKey,
            String requestId,
            UUID tenantId,
            UUID actorId) {
        return operation.contextWrite(context -> context
                        .put("operationTenantId", tenantId)
                        .put("operationActorUserId", actorId)
                        .put("operationIdempotencyKey", idempotencyKey)
                        .put("operationRequestId", requestId))
                .block(Duration.ofSeconds(3));
    }

    private static String r2dbcUrl() {
        return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/" + POSTGRES.getDatabaseName();
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long singleLong(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static List<String> values(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return values;
        }
    }
}
