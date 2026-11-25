package com.yuzhihuihe.apiplatform.gateway.quota;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class PackageRefundRepository {

    private final DatabaseClient database;

    public PackageRefundRepository(DatabaseClient database) {
        this.database = database;
    }

    Mono<Long> insertHold(HoldInsertRow value) {
        DatabaseClient.GenericExecuteSpec insert = database.sql("""
                        INSERT INTO gateway.package_refund_hold(
                            id, business_no, package_instance_id, grant_request_id,
                            original_status, status, payload_hash, total_tokens,
                            used_tokens, remaining_tokens, package_expires_at, snapshot_at)
                        VALUES (
                            :id, :business_no, :package_id, :grant_id,
                            :original_status, :status, :payload_hash, :total_tokens,
                            :used_tokens, :remaining_tokens, :package_expires_at, :snapshot_at)
                        """)
                .bind("id", value.id())
                .bind("business_no", value.businessNo())
                .bind("package_id", value.packageInstanceId())
                .bind("grant_id", value.grantRequestId())
                .bind("original_status", value.originalStatus())
                .bind("status", value.status())
                .bind("payload_hash", value.payloadHash());
        insert = bindNullable(insert, "total_tokens", value.totalTokens(), Long.class);
        insert = bindNullable(insert, "used_tokens", value.usedTokens(), Long.class);
        insert = bindNullable(insert, "remaining_tokens", value.remainingTokens(), Long.class);
        insert = bindNullable(insert, "package_expires_at", value.packageExpiresAt(), Instant.class);
        insert = bindNullable(insert, "snapshot_at", value.snapshotAt(), Instant.class);
        return insert.fetch().rowsUpdated();
    }

    Mono<Long> markHoldReady(
            UUID id,
            long totalTokens,
            long usedTokens,
            long remainingTokens,
            Instant packageExpiresAt,
            Instant snapshotAt) {
        DatabaseClient.GenericExecuteSpec update = database.sql("""
                        UPDATE gateway.package_refund_hold
                        SET status = 'READY', total_tokens = :total_tokens,
                            used_tokens = :used_tokens, remaining_tokens = :remaining_tokens,
                            package_expires_at = :package_expires_at,
                            snapshot_at = :snapshot_at, updated_at = now()
                        WHERE id = :id AND status = 'WAITING'
                        """)
                .bind("total_tokens", totalTokens)
                .bind("used_tokens", usedTokens)
                .bind("remaining_tokens", remainingTokens)
                .bind("snapshot_at", snapshotAt)
                .bind("id", id);
        update = bindNullable(update, "package_expires_at", packageExpiresAt, Instant.class);
        return update.fetch().rowsUpdated();
    }

    Mono<Long> releaseHold(UUID id) {
        return database.sql("""
                        UPDATE gateway.package_refund_hold
                        SET status = 'RELEASED', released_at = now(), updated_at = now()
                        WHERE id = :id AND status IN ('WAITING', 'READY')
                        """)
                .bind("id", id)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> insertRevocationEvent(
            UUID id,
            UUID eventId,
            String businessNo,
            UUID packageId,
            String sourceBusinessNo,
            String payloadHash) {
        return database.sql("""
                        INSERT INTO gateway.package_lifecycle_event(
                            id, event_id, business_no, package_instance_id, event_type,
                            source_business_no, payload_hash)
                        VALUES (
                            :id, :event_id, :business_no, :package_id, 'REFUND_REVOKE',
                            :source_business_no, :payload_hash)
                        """)
                .bind("id", id)
                .bind("event_id", eventId)
                .bind("business_no", businessNo)
                .bind("package_id", packageId)
                .bind("source_business_no", sourceBusinessNo)
                .bind("payload_hash", payloadHash)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> revokePackage(UUID packageId) {
        return database.sql("""
                        UPDATE gateway.package_instance
                        SET status = 'REVOKED', ended_at = now(), updated_at = now(),
                            lock_version = lock_version + 1
                        WHERE id = :id AND status IN ('ACTIVE', 'QUEUED')
                        """)
                .bind("id", packageId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Void> revokeManagedApiKey(UUID packageId) {
        return database.sql("""
                        UPDATE gateway.api_key
                        SET status = 'REVOKED', revoked_at = now(), updated_at = now()
                        WHERE package_instance_id = :package_id
                          AND managed AND status = 'ACTIVE'
                        """)
                .bind("package_id", packageId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    Mono<Long> revokeHold(UUID holdId) {
        return database.sql("""
                        UPDATE gateway.package_refund_hold
                        SET status = 'REVOKED', revoked_at = now(), updated_at = now()
                        WHERE id = :id AND status = 'READY'
                        """)
                .bind("id", holdId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> persistRevocationResult(
            String businessNo, UUID activatedId, String activatedStatus) {
        DatabaseClient.GenericExecuteSpec update = database.sql("""
                        UPDATE gateway.package_lifecycle_event
                        SET activated_package_instance_id = :activated_id,
                            activated_package_status = :activated_status
                        WHERE business_no = :business_no AND event_type = 'REFUND_REVOKE'
                        """)
                .bind("business_no", businessNo);
        update = bindNullable(update, "activated_id", activatedId, UUID.class);
        update = bindNullable(update, "activated_status", activatedStatus, String.class);
        return update.fetch().rowsUpdated();
    }

    Flux<ExistingRevocation> findExistingRevocationsForUpdate(
            String businessNo, UUID eventId) {
        return database.sql("""
                        SELECT business_no, payload_hash, package_instance_id,
                               activated_package_instance_id, activated_package_status
                        FROM gateway.package_lifecycle_event
                        WHERE event_type = 'REFUND_REVOKE'
                          AND (business_no = :business_no OR event_id = :event_id)
                        FOR UPDATE
                        """)
                .bind("business_no", businessNo)
                .bind("event_id", eventId)
                .map((row, metadata) -> new ExistingRevocation(
                        row.get("business_no", String.class),
                        row.get("payload_hash", String.class),
                        row.get("package_instance_id", UUID.class),
                        row.get("activated_package_instance_id", UUID.class),
                        row.get("activated_package_status", String.class)))
                .all();
    }

    Mono<PackageRow> findPackageByGrant(UUID grantRequestId) {
        return packageByGrant(grantRequestId, false);
    }

    Mono<PackageRow> lockPackageByGrant(UUID grantRequestId) {
        return packageByGrant(grantRequestId, true);
    }

    private Mono<PackageRow> packageByGrant(UUID grantRequestId, boolean forUpdate) {
        String sql = """
                SELECT id, grant_request_id, user_id, status, total_token_quota,
                       reserved_tokens, used_tokens, expires_at, usage_dimension
                FROM gateway.package_instance
                WHERE grant_request_id = :grant_id
                """ + (forUpdate ? "FOR UPDATE" : "");
        return database.sql(sql)
                .bind("grant_id", grantRequestId)
                .map((row, metadata) -> new PackageRow(
                        row.get("id", UUID.class),
                        row.get("grant_request_id", UUID.class),
                        row.get("user_id", UUID.class),
                        row.get("status", String.class),
                        requiredLong(row.get("total_token_quota", Long.class)),
                        requiredLong(row.get("reserved_tokens", Long.class)),
                        requiredLong(row.get("used_tokens", Long.class)),
                        row.get("expires_at", Instant.class),
                        row.get("usage_dimension", String.class)))
                .one();
    }

    Mono<UUID> findRefundTargetUser(String businessNo) {
        return database.sql("""
                        SELECT package.user_id
                        FROM gateway.package_refund_hold AS hold
                        JOIN gateway.package_instance AS package
                          ON package.id = hold.package_instance_id
                        WHERE hold.business_no = :business_no
                        """)
                .bind("business_no", businessNo)
                .map((row, metadata) -> row.get("user_id", UUID.class))
                .one();
    }

    Mono<HoldRow> lockHold(String businessNo) {
        return database.sql("""
                        SELECT id, business_no, package_instance_id, grant_request_id,
                               original_status, status, payload_hash, total_tokens,
                               used_tokens, remaining_tokens, package_expires_at,
                               snapshot_at, created_at
                        FROM gateway.package_refund_hold
                        WHERE business_no = :business_no
                        FOR UPDATE
                        """)
                .bind("business_no", businessNo)
                .map((row, metadata) -> mapHold(row))
                .one();
    }

    Mono<HoldRow> lockReadyHold(UUID packageId) {
        return database.sql("""
                        SELECT id, business_no, package_instance_id, grant_request_id,
                               original_status, status, payload_hash, total_tokens,
                               used_tokens, remaining_tokens, package_expires_at,
                               snapshot_at, created_at
                        FROM gateway.package_refund_hold
                        WHERE package_instance_id = :package_id AND status = 'READY'
                        FOR UPDATE
                        """)
                .bind("package_id", packageId)
                .map((row, metadata) -> mapHold(row))
                .one();
    }

    Mono<Boolean> hasReservedRows(UUID packageId) {
        return database.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM gateway.quota_reservation
                            WHERE package_instance_id = :package_id AND status = 'RESERVED'
                        ) AS reserved
                        """)
                .bind("package_id", packageId)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("reserved", Boolean.class)))
                .one();
    }

    Mono<UUID> findOpenHoldForPackage(UUID packageId) {
        return database.sql("""
                        SELECT id FROM gateway.package_refund_hold
                        WHERE package_instance_id = :package_id
                          AND status IN ('WAITING', 'READY')
                        """)
                .bind("package_id", packageId)
                .map((row, metadata) -> row.get("id", UUID.class))
                .one();
    }

    Mono<Void> lockUser(UUID userId) {
        return database.sql("SELECT pg_advisory_xact_lock(hashtextextended(:user_id, 0))")
                .bind("user_id", userId.toString())
                .fetch()
                .rowsUpdated()
                .then();
    }

    Mono<Void> lockRefundBusiness(String businessNo) {
        return database.sql("""
                        SELECT pg_advisory_xact_lock(
                            hashtextextended('refund-hold:' || :business_no, 0))
                        """)
                .bind("business_no", businessNo)
                .fetch()
                .rowsUpdated()
                .then();
    }

    Mono<Void> lockRevocationIdempotency(String businessNo, UUID eventId) {
        return database.sql("""
                        SELECT pg_advisory_xact_lock(lock_key)
                        FROM (
                            SELECT DISTINCT lock_key
                            FROM (VALUES
                                (hashtextextended('refund-hold:' || :business_no, 0)),
                                (hashtextextended('refund-event:' || :event_id, 0))
                            ) AS idempotency_keys(lock_key)
                            ORDER BY lock_key
                        ) AS ordered_keys
                        """)
                .bind("business_no", businessNo)
                .bind("event_id", eventId.toString())
                .fetch()
                .rowsUpdated()
                .then();
    }

    private HoldRow mapHold(io.r2dbc.spi.Row row) {
        return new HoldRow(
                row.get("id", UUID.class),
                row.get("business_no", String.class),
                row.get("package_instance_id", UUID.class),
                row.get("grant_request_id", UUID.class),
                row.get("original_status", String.class),
                row.get("status", String.class),
                row.get("payload_hash", String.class),
                row.get("total_tokens", Long.class),
                row.get("used_tokens", Long.class),
                row.get("remaining_tokens", Long.class),
                row.get("package_expires_at", Instant.class),
                row.get("snapshot_at", Instant.class),
                row.get("created_at", Instant.class));
    }

    private <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private static long requiredLong(Long value) {
        return value == null ? 0 : value;
    }

    record HoldInsertRow(
            UUID id,
            String businessNo,
            UUID packageInstanceId,
            UUID grantRequestId,
            String originalStatus,
            String status,
            String payloadHash,
            Long totalTokens,
            Long usedTokens,
            Long remainingTokens,
            Instant packageExpiresAt,
            Instant snapshotAt) {}

    record PackageRow(
            UUID id,
            UUID grantRequestId,
            UUID userId,
            String status,
            long totalTokens,
            long reservedTokens,
            long usedTokens,
            Instant expiresAt,
            String usageDimension) {}

    record HoldRow(
            UUID id,
            String businessNo,
            UUID packageInstanceId,
            UUID grantRequestId,
            String originalStatus,
            String status,
            String payloadHash,
            Long totalTokens,
            Long usedTokens,
            Long remainingTokens,
            Instant packageExpiresAt,
            Instant snapshotAt,
            Instant createdAt) {}

    record ExistingRevocation(
            String businessNo,
            String payloadHash,
            UUID packageInstanceId,
            UUID activatedPackageInstanceId,
            String activatedPackageStatus) {}
}
