package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
class PostgresUploadSessionRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static DatabaseClient database;
    private PostgresUploadSessionRepository repository;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        String url = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/" + POSTGRES.getDatabaseName();
        database = DatabaseClient.create(ConnectionFactories.get(url));
    }

    @BeforeEach
    void setUp() {
        repository = new PostgresUploadSessionRepository(database);
        database.sql("DELETE FROM gateway.file_object").fetch().rowsUpdated().block(Duration.ofSeconds(3));
        database.sql("DELETE FROM gateway.api_key").fetch().rowsUpdated().block(Duration.ofSeconds(3));
        database.sql("DELETE FROM gateway.package_instance").fetch().rowsUpdated().block(Duration.ofSeconds(3));
    }

    @Test
    void createOrFindIsScopedToApiKeyAndClientIdempotencyKey() {
        Owner firstOwner = owner();
        Owner secondOwner = owner();
        UploadSessionRepository.UploadSession first = draft(firstOwner, "client-key");
        UploadSessionRepository.UploadSession duplicate = draft(firstOwner, "client-key");
        UploadSessionRepository.UploadSession otherApiKey = draft(secondOwner, "client-key");

        UploadSessionRepository.UploadSession created = repository.createOrFind(first).block(Duration.ofSeconds(3));
        UploadSessionRepository.UploadSession found = repository.createOrFind(duplicate).block(Duration.ofSeconds(3));
        UploadSessionRepository.UploadSession independentlyCreated =
                repository.createOrFind(otherApiKey).block(Duration.ofSeconds(3));

        assertThat(found.id()).isEqualTo(created.id());
        assertThat(found.uploadSessionId()).isEqualTo(created.uploadSessionId());
        assertThat(independentlyCreated.id()).isNotEqualTo(created.id());
        assertThat(count("SELECT count(*) FROM gateway.file_object")).isEqualTo(2);
    }

    @Test
    void findOwnedRequiresTheWholeOwnerTuple() {
        Owner owner = owner();
        UploadSessionRepository.UploadSession created = repository.createOrFind(draft(owner, "owned"))
                .block(Duration.ofSeconds(3));

        assertThat(repository.findOwned(created.uploadSessionId(), owner.apiKeyId(), owner.tenantId(), owner.userId())
                .block(Duration.ofSeconds(3))).isEqualTo(created);
        assertThat(repository.findOwned(created.uploadSessionId(), UUID.randomUUID(), owner.tenantId(), owner.userId())
                .block(Duration.ofSeconds(3))).isNull();
        assertThat(repository.findOwned(created.uploadSessionId(), owner.apiKeyId(), UUID.randomUUID(), owner.userId())
                .block(Duration.ofSeconds(3))).isNull();
        assertThat(repository.findOwned(created.uploadSessionId(), owner.apiKeyId(), owner.tenantId(), UUID.randomUUID())
                .block(Duration.ofSeconds(3))).isNull();
    }

    @Test
    void markUploadedOnlyChangesAnUnexpiredPendingDirectUpload() {
        Instant now = Instant.parse("2026-08-10T04:00:00Z");
        Owner owner = owner();
        UploadSessionRepository.UploadSession pending = repository.createOrFind(draft(owner, "pending", now.plusSeconds(60)))
                .block(Duration.ofSeconds(3));
        UploadSessionRepository.UploadSession expired = repository.createOrFind(draft(owner, "expired", now.minusSeconds(1)))
                .block(Duration.ofSeconds(3));

        UploadSessionRepository.UploadSession uploaded = repository.markUploaded(
                        pending.id(), owner.apiKeyId(), owner.tenantId(), owner.userId(), "etag-1", now)
                .block(Duration.ofSeconds(3));

        assertThat(uploaded.status()).isEqualTo("UPLOADED");
        assertThat(uploaded.etag()).isEqualTo("etag-1");
        assertThat(uploaded.nextScanAt()).isEqualTo(now);
        assertThat(repository.markUploaded(
                expired.id(), owner.apiKeyId(), owner.tenantId(), owner.userId(), "etag-2", now)
                .block(Duration.ofSeconds(3))).isNull();
        assertThat(status(expired.id())).isEqualTo("PENDING_UPLOAD");
        assertThat(repository.markUploaded(
                pending.id(), owner.apiKeyId(), owner.tenantId(), owner.userId(), "etag-3", now)
                .block(Duration.ofSeconds(3))).isNull();
    }

    @Test
    void markUploadedRejectsAValidRowWhenTheOwnerTupleDoesNotMatch() {
        Instant now = Instant.parse("2026-08-10T04:00:00Z");
        Owner owner = owner();
        Owner attacker = owner();
        UploadSessionRepository.UploadSession pending = repository.createOrFind(draft(owner, "owner-fence"))
                .block(Duration.ofSeconds(3));

        UploadSessionRepository.UploadSession result = repository.markUploaded(
                pending.id(), attacker.apiKeyId(), attacker.tenantId(), attacker.userId(), "etag-stolen", now)
                .block(Duration.ofSeconds(3));

        assertThat(result).isNull();
        assertThat(status(pending.id())).isEqualTo("PENDING_UPLOAD");
    }

    @Test
    void rejectPendingIsConditionalAndRecordsAStableError() {
        Instant now = Instant.parse("2026-08-10T04:00:00Z");
        UploadSessionRepository.UploadSession pending = repository.createOrFind(draft(owner(), "reject"))
                .block(Duration.ofSeconds(3));

        UploadSessionRepository.UploadSession rejected = repository.rejectPending(
                pending.id(), "upload_head_mismatch", now).block(Duration.ofSeconds(3));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.lastErrorCode()).isEqualTo("upload_head_mismatch");
        assertThat(repository.rejectPending(pending.id(), "second", now).block(Duration.ofSeconds(3))).isNull();
    }

    @Test
    void concurrentScanClaimersReceiveDisjointRows() {
        Instant now = Instant.parse("2026-08-10T04:00:00Z");
        UploadSessionRepository.UploadSession first = uploaded(owner(), "claim-a", now);
        UploadSessionRepository.UploadSession second = uploaded(owner(), "claim-b", now);

        var claims = Mono.zip(
                        repository.claimForScan(1, now, Duration.ofMinutes(5)).collectList(),
                        repository.claimForScan(1, now, Duration.ofMinutes(5)).collectList())
                .block(Duration.ofSeconds(5));

        assertThat(claims.getT1()).hasSize(1);
        assertThat(claims.getT2()).hasSize(1);
        assertThat(claims.getT1().getFirst().session().id())
                .isNotEqualTo(claims.getT2().getFirst().session().id());
        assertThat(List.of(claims.getT1().getFirst().session().id(), claims.getT2().getFirst().session().id()))
                .containsExactlyInAnyOrder(first.id(), second.id());
    }

    @Test
    void staleScanningClaimIsReclaimedWithANewToken() {
        Instant now = Instant.parse("2026-08-10T04:00:00Z");
        UUID staleToken = UUID.randomUUID();
        UploadSessionRepository.UploadSession uploaded = uploaded(owner(), "stale", now);
        database.sql("""
                        UPDATE gateway.file_object
                        SET status = 'SCANNING', scan_claim_token = :token,
                            scan_claimed_at = :claimed_at
                        WHERE id = :id
                        """)
                .bind("id", uploaded.id())
                .bind("token", staleToken)
                .bind("claimed_at", now.minus(Duration.ofMinutes(6)))
                .fetch().rowsUpdated().block(Duration.ofSeconds(3));

        UploadSessionRepository.ScanClaim reclaimed = repository.claimForScan(1, now, Duration.ofMinutes(5))
                .single().block(Duration.ofSeconds(3));

        assertThat(reclaimed.session().id()).isEqualTo(uploaded.id());
        assertThat(reclaimed.claimToken()).isNotEqualTo(staleToken);
    }

    @Test
    void terminalScanUpdatesAreFencedAndRetrySchedulesAnotherAttempt() {
        Instant now = Instant.parse("2026-08-10T04:00:00Z");
        UploadSessionRepository.ScanClaim readyClaim = claim(uploaded(owner(), "ready", now), now);
        UploadSessionRepository.ScanClaim rejectedClaim = claim(uploaded(owner(), "rejected", now), now);
        UploadSessionRepository.ScanClaim retryClaim = claim(uploaded(owner(), "retry", now), now);
        UUID staleToken = UUID.randomUUID();
        Instant nextAttempt = now.plusSeconds(30);

        assertThat(repository.markReady(readyClaim.session().id(), staleToken, "clean/stale", now).block()).isFalse();
        assertThat(repository.markRejected(rejectedClaim.session().id(), staleToken, "malware", now).block()).isFalse();
        assertThat(repository.retryOrFail(retryClaim.session().id(), staleToken, "scanner_down",
                nextAttempt, 3, now).block()).isFalse();
        assertThat(repository.markReady(readyClaim.session().id(), readyClaim.claimToken(), "clean/current", now).block())
                .isTrue();
        assertThat(repository.markRejected(rejectedClaim.session().id(), rejectedClaim.claimToken(), "malware", now).block())
                .isTrue();
        assertThat(repository.retryOrFail(retryClaim.session().id(), retryClaim.claimToken(), "scanner_down",
                nextAttempt, 3, now).block()).isTrue();

        assertThat(status(readyClaim.session().id())).isEqualTo("READY");
        assertThat(value(readyClaim.session().id(), "object_key")).isEqualTo("clean/current");
        assertThat(status(rejectedClaim.session().id())).isEqualTo("REJECTED");
        assertThat(status(retryClaim.session().id())).isEqualTo("UPLOADED");
        assertThat(count("SELECT scan_attempts FROM gateway.file_object WHERE id = '"
                + retryClaim.session().id() + "'" )).isEqualTo(1);
        assertThat(instantValue(retryClaim.session().id(), "next_scan_at")).isEqualTo(nextAttempt);
    }

    @Test
    void retryFailsAtTheMaximumAttemptCount() {
        Instant now = Instant.parse("2026-08-10T04:00:00Z");
        UploadSessionRepository.ScanClaim claim = claim(uploaded(owner(), "last-attempt", now), now);
        database.sql("UPDATE gateway.file_object SET scan_attempts = 2 WHERE id = :id")
                .bind("id", claim.session().id()).fetch().rowsUpdated().block(Duration.ofSeconds(3));

        assertThat(repository.retryOrFail(claim.session().id(), claim.claimToken(), "scanner_down",
                now.plusSeconds(30), 3, now).block()).isTrue();
        assertThat(status(claim.session().id())).isEqualTo("FAILED");
        assertThat(count("SELECT scan_attempts FROM gateway.file_object WHERE id = '"
                + claim.session().id() + "'" )).isEqualTo(3);
    }

    @Test
    void quarantineCleanupIsClaimedAndClearedWithFencing() {
        Instant now = Instant.parse("2026-08-10T04:00:00Z");
        UploadSessionRepository.UploadSession uploaded = uploaded(owner(), "cleanup", now);
        UploadSessionRepository.ScanClaim promotion = claim(uploaded, now);
        assertThat(repository.markReady(
                uploaded.id(), promotion.claimToken(), "clean/cleanup", now).block()).isTrue();

        UploadSessionRepository.QuarantineCleanupClaim claim = repository
                .claimQuarantineCleanup(1, now, Duration.ofMinutes(5))
                .single().block(Duration.ofSeconds(3));

        assertThat(claim.id()).isEqualTo(uploaded.id());
        assertThat(claim.quarantineObjectKey()).isEqualTo(uploaded.quarantineObjectKey());
        assertThat(repository.markQuarantineDeleted(claim.id(), UUID.randomUUID(), now).block()).isFalse();
        assertThat(repository.markQuarantineDeleted(claim.id(), claim.cleanupClaimToken(), now).block()).isTrue();
        assertThat(value(claim.id(), "quarantine_object_key")).isNull();
    }

    @Test
    void quarantineCleanupClaimsReadyRejectedAndFailedObjects() {
        Instant now = Instant.parse("2026-08-10T04:00:00Z");
        UploadSessionRepository.UploadSession ready = repository
                .createOrFind(draft(owner(), "cleanup-ready")).block(Duration.ofSeconds(3));
        UploadSessionRepository.UploadSession rejected = repository
                .createOrFind(draft(owner(), "cleanup-rejected")).block(Duration.ofSeconds(3));
        UploadSessionRepository.UploadSession failed = repository
                .createOrFind(draft(owner(), "cleanup-failed")).block(Duration.ofSeconds(3));
        setStatus(ready.id(), "READY");
        setStatus(rejected.id(), "REJECTED");
        setStatus(failed.id(), "FAILED");

        List<UUID> claimed = repository.claimQuarantineCleanup(10, now, Duration.ofMinutes(5))
                .map(UploadSessionRepository.QuarantineCleanupClaim::id)
                .collectList().block(Duration.ofSeconds(3));

        assertThat(claimed).containsExactlyInAnyOrder(ready.id(), rejected.id(), failed.id());
    }

    private UploadSessionRepository.ScanClaim claim(UploadSessionRepository.UploadSession session, Instant now) {
        return repository.claimForScan(1, now, Duration.ofMinutes(5))
                .filter(candidate -> candidate.session().id().equals(session.id()))
                .single().block(Duration.ofSeconds(3));
    }

    private UploadSessionRepository.UploadSession uploaded(Owner owner, String key, Instant now) {
        UploadSessionRepository.UploadSession pending = repository.createOrFind(draft(owner, key))
                .block(Duration.ofSeconds(3));
        return repository.markUploaded(
                        pending.id(), owner.apiKeyId(), owner.tenantId(), owner.userId(), "etag-" + key, now)
                .block(Duration.ofSeconds(3));
    }

    private UploadSessionRepository.UploadSession draft(Owner owner, String key) {
        return draft(owner, key, Instant.parse("2026-08-10T05:00:00Z"));
    }

    private UploadSessionRepository.UploadSession draft(Owner owner, String key, Instant uploadExpiresAt) {
        UUID id = UUID.randomUUID();
        String objectKey = "quarantine/" + owner.tenantId() + "/" + id;
        Instant createdAt = Instant.parse("2026-08-10T03:00:00Z");
        return new UploadSessionRepository.UploadSession(
                id, "file_" + id, owner.apiKeyId(), owner.tenantId(), owner.userId(),
                "upl_" + id, key, objectKey, objectKey, "upload.pdf", "a".repeat(64),
                "application/pdf", 128, "PENDING_UPLOAD", uploadExpiresAt,
                createdAt.plus(Duration.ofDays(1)), null, null, null, 0, null, null,
                createdAt, createdAt);
    }

    private Owner owner() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        database.sql("""
                        INSERT INTO gateway.package_instance(
                            id, grant_event_id, grant_request_id, tenant_id, user_id,
                            package_plan_version_id, package_code, package_name, price_amount,
                            total_token_quota, max_api_keys, requests_per_minute,
                            tokens_per_minute, max_concurrency, duration_days, identity_key)
                        VALUES (:id, :grant_event_id, :grant_request_id, :tenant_id, :user_id,
                            :plan_id, 'test', 'Test', 0, 1000, 10, 10, 1000, 10, 1, :identity_key)
                        """)
                .bind("id", packageId)
                .bind("grant_event_id", UUID.randomUUID())
                .bind("grant_request_id", UUID.randomUUID())
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("plan_id", UUID.randomUUID())
                .bind("identity_key", userId.toString())
                .fetch().rowsUpdated().block(Duration.ofSeconds(3));
        database.sql("""
                        INSERT INTO gateway.api_key(
                            id, tenant_id, user_id, package_instance_id, name, key_prefix, key_hash)
                        VALUES (:id, :tenant_id, :user_id, :package_id, 'test', :prefix, :hash)
                        """)
                .bind("id", apiKeyId)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("package_id", packageId)
                .bind("prefix", "sk-test-" + apiKeyId.toString().substring(0, 8))
                .bind("hash", apiKeyId.toString())
                .fetch().rowsUpdated().block(Duration.ofSeconds(3));
        return new Owner(apiKeyId, tenantId, userId);
    }

    private long count(String sql) {
        return database.sql(sql).map((row, metadata) -> row.get(0, Long.class)).one()
                .block(Duration.ofSeconds(3));
    }

    private String status(UUID id) {
        return value(id, "status");
    }

    private void setStatus(UUID id, String status) {
        database.sql("UPDATE gateway.file_object SET status = :status WHERE id = :id")
                .bind("status", status)
                .bind("id", id)
                .fetch().rowsUpdated().block(Duration.ofSeconds(3));
    }

    private String value(UUID id, String column) {
        return database.sql("SELECT " + column + "::text FROM gateway.file_object WHERE id = :id")
                .bind("id", id)
                .map((row, metadata) -> Optional.ofNullable(row.get(0, String.class)))
                .one().block(Duration.ofSeconds(3)).orElse(null);
    }

    private Instant instantValue(UUID id, String column) {
        return database.sql("SELECT " + column + " FROM gateway.file_object WHERE id = :id")
                .bind("id", id)
                .map((row, metadata) -> row.get(0, Instant.class))
                .one().block(Duration.ofSeconds(3));
    }

    private record Owner(UUID apiKeyId, UUID tenantId, UUID userId) { }
}
