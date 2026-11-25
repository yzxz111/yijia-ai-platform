package com.yuzhihuihe.apiplatform.gateway.quota;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.quota.PackageRefundRepository.ExistingRevocation;
import com.yuzhihuihe.apiplatform.gateway.quota.PackageRefundRepository.HoldRow;
import com.yuzhihuihe.apiplatform.gateway.quota.PackageRefundRepository.PackageRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class PackageRefundService {

    private static final HexFormat HEX = HexFormat.of();

    private final PackageRefundRepository repository;
    private final TransactionalOperator transactions;
    private final ObjectMapper objectMapper;
    private final PackageActivationService packageActivationService;
    private final PackageRefundMetrics metrics;

    public PackageRefundService(
            PackageRefundRepository repository,
            TransactionalOperator transactions,
            ObjectMapper objectMapper,
            PackageActivationService packageActivationService,
            PackageRefundMetrics metrics) {
        this.repository = repository;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.packageActivationService = packageActivationService;
        this.metrics = metrics;
    }

    public Mono<RefundSnapshot> prepare(PrepareRefundCommand command) {
        return Mono.defer(() -> {
                    validate(command);
                    String payloadHash = commandHash(command.businessNo(), command.grantRequestId());
                    return refundTarget(command.businessNo(), command.grantRequestId())
                            .switchIfEmpty(Mono.error(unprocessable("package_not_refundable")))
                            .flatMap(target -> transactions.transactional(advisoryLock(target.userId())
                                    .then(refundBusinessLock(command.businessNo()))
                                    .then(prepareLocked(command, payloadHash))));
                })
                .doOnNext(snapshot -> metrics.prepare(snapshot.status()))
                .doOnError(error -> metrics.prepare("REJECTED"));
    }

    public Mono<ReleaseResult> release(ReleaseRefundCommand command) {
        return Mono.defer(() -> {
                    validate(command);
                    String payloadHash = commandHash(command.businessNo(), command.grantRequestId());
                    return refundTarget(command.businessNo(), command.grantRequestId())
                            .switchIfEmpty(Mono.error(unprocessable("refund_hold_not_found")))
                            .flatMap(target -> transactions.transactional(advisoryLock(target.userId())
                                    .then(refundBusinessLock(command.businessNo()))
                                    .then(lockHold(command.businessNo()))
                                    .switchIfEmpty(Mono.error(unprocessable("refund_hold_not_found")))
                                    .flatMap(hold -> releaseLocked(command, payloadHash, hold))));
                })
                .doOnNext(execution -> metrics.release(execution.outcome()))
                .map(ReleaseExecution::result)
                .doOnError(error -> metrics.release("REJECTED"));
    }

    public Mono<RevocationResult> revoke(PackageRefundedEvent event) {
        return Mono.defer(() -> {
                    validate(event);
                    String payloadHash = sha256(toJson(event.payload()));
                    return refundTarget(
                            event.payload().revocationBusinessNo(),
                            event.payload().grantRequestId())
                            .switchIfEmpty(Mono.error(conflict("refund_hold_not_ready")))
                            .flatMap(target -> transactions.transactional(advisoryLock(target.userId())
                                    .then(revocationIdempotencyLock(event))
                                    .then(existingRevocation(event, payloadHash))
                                    .switchIfEmpty(Mono.defer(() -> revokeNew(
                                            event, payloadHash, target.userId())))));
                })
                .doOnNext(execution -> metrics.revoke(execution.outcome()))
                .map(RevocationExecution::result)
                .doOnError(error -> metrics.revoke("REJECTED"));
    }

    private Mono<RefundSnapshot> prepareLocked(
            PrepareRefundCommand command, String payloadHash) {
        return lockHold(command.businessNo())
                .flatMap(hold -> {
                    requireMatchingHold(command.grantRequestId(), payloadHash, hold);
                    return lockPackage(command.grantRequestId())
                            .switchIfEmpty(Mono.error(conflict("refund_hold_payload_mismatch")))
                            .flatMap(locked -> replayOrPromote(
                                    command, payloadHash, locked, hold));
                })
                .switchIfEmpty(Mono.defer(() -> lockPackage(command.grantRequestId())
                        .switchIfEmpty(Mono.error(unprocessable("package_not_refundable")))
                        .flatMap(locked -> createHold(command, payloadHash, locked))));
    }

    private Mono<RefundSnapshot> replayOrPromote(
            PrepareRefundCommand command, String payloadHash, PackageRow locked, HoldRow hold) {
        requireMatchingHold(command.grantRequestId(), payloadHash, hold);
        if ("READY".equals(hold.status())) {
            return Mono.just(snapshot(hold));
        }
        if (!"WAITING".equals(hold.status())) {
            return Mono.error(conflict("refund_hold_not_open"));
        }
        if (!refundable(locked)) {
            return Mono.error(unprocessable("package_not_refundable"));
        }
        return hasReservedRows(hold.packageInstanceId())
                .flatMap(reserved -> reserved
                        ? Mono.just(snapshot(hold))
                        : readySnapshot(hold, locked));
    }

    private Mono<RefundSnapshot> createHold(
            PrepareRefundCommand command, String payloadHash, PackageRow locked) {
        if (!refundable(locked)) {
            return Mono.error(unprocessable("package_not_refundable"));
        }
        return openHoldForPackage(locked.id())
                .flatMap(open -> Mono.<RefundSnapshot>error(conflict("package_refund_in_progress")))
                .switchIfEmpty(Mono.defer(() -> hasReservedRows(locked.id())
                        .flatMap(reserved -> insertHold(command, payloadHash, locked, reserved))));
    }

    private Mono<RefundSnapshot> insertHold(
            PrepareRefundCommand command,
            String payloadHash,
            PackageRow locked,
            boolean reserved) {
        String status = reserved ? "WAITING" : "READY";
        Instant snapshotAt = reserved ? null : Instant.now().truncatedTo(ChronoUnit.MICROS);
        long used = "QUEUED".equals(locked.status()) ? 0 : locked.usedTokens();
        PackageRefundRepository.HoldInsertRow row = new PackageRefundRepository.HoldInsertRow(
                UUID.randomUUID(), command.businessNo(), locked.id(), command.grantRequestId(),
                locked.status(), status, payloadHash,
                reserved ? null : locked.totalTokens(),
                reserved ? null : used,
                reserved ? null : locked.totalTokens() - used,
                locked.expiresAt(), snapshotAt);
        return repository.insertHold(row)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("refund_hold_insert_failed")))
                .thenReturn(new RefundSnapshot(
                        command.businessNo(), locked.id(), status, locked.status(),
                        reserved ? null : locked.totalTokens(), reserved ? null : used,
                        reserved ? null : locked.totalTokens() - used,
                        locked.expiresAt(), snapshotAt));
    }

    private Mono<RefundSnapshot> readySnapshot(HoldRow hold, PackageRow locked) {
        if (!hold.packageInstanceId().equals(locked.id())) {
            return Mono.error(conflict("refund_hold_payload_mismatch"));
        }
        long used = "QUEUED".equals(hold.originalStatus()) ? 0 : locked.usedTokens();
        Instant snapshotAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return repository.markHoldReady(
                        hold.id(), locked.totalTokens(), used,
                        locked.totalTokens() - used, locked.expiresAt(), snapshotAt)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("refund_snapshot_update_failed")))
                .doOnNext(ignored -> metrics.holdWait(Duration.between(hold.createdAt(), snapshotAt)))
                .thenReturn(new RefundSnapshot(
                        hold.businessNo(), locked.id(), "READY", hold.originalStatus(),
                        locked.totalTokens(), used, locked.totalTokens() - used,
                        locked.expiresAt(), snapshotAt));
    }

    private Mono<ReleaseExecution> releaseLocked(
            ReleaseRefundCommand command, String payloadHash, HoldRow hold) {
        requireMatchingHold(command.grantRequestId(), payloadHash, hold);
        if ("RELEASED".equals(hold.status())) {
            return Mono.just(new ReleaseExecution(
                    new ReleaseResult(command.businessNo(), "RELEASED"), "REPLAY"));
        }
        if (!"WAITING".equals(hold.status()) && !"READY".equals(hold.status())) {
            return Mono.error(conflict("refund_hold_not_releasable"));
        }
        if (!"ACTIVE".equals(hold.originalStatus()) && !"QUEUED".equals(hold.originalStatus())) {
            return Mono.error(conflict("refund_hold_not_releasable"));
        }
        return repository.releaseHold(hold.id())
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("refund_hold_release_failed")))
                .thenReturn(new ReleaseExecution(
                        new ReleaseResult(command.businessNo(), "RELEASED"), "RELEASED"));
    }

    private Mono<RevocationExecution> revokeNew(
            PackageRefundedEvent event, String payloadHash, UUID userId) {
        PackageRefundedPayload payload = event.payload();
        return lockPackage(payload.grantRequestId())
                .switchIfEmpty(Mono.error(conflict("refund_hold_not_ready")))
                .flatMap(locked -> lockReadyHold(locked.id())
                        .switchIfEmpty(Mono.error(conflict("refund_hold_not_ready")))
                        .flatMap(hold -> validateRevocation(payload, userId, locked, hold)
                                .then(insertRevocationEvent(event, payloadHash, locked.id()))
                                .then(revokePackage(locked.id()))
                                .then(revokeManagedApiKey(locked.id()))
                                .then(revokeHold(hold.id()))
                                .then(packageActivationService
                                        .activateNextLocked(userId, locked.usageDimension())
                                        .map(result -> new RevocationResult(
                                                locked.id(), "REVOKED",
                                                result.packageInstanceId(), result.status()))
                                        .defaultIfEmpty(new RevocationResult(
                                                locked.id(), "REVOKED", null, null)))
                                .flatMap(result -> persistRevocationResult(event.businessKey(), result)
                                        .thenReturn(new RevocationExecution(result, "REVOKED")))));
    }

    private Mono<Void> validateRevocation(
            PackageRefundedPayload payload, UUID userId, PackageRow locked, HoldRow hold) {
        boolean valid = payload.userId().equals(userId)
                && payload.grantRequestId().equals(locked.grantRequestId())
                && payload.grantRequestId().equals(hold.grantRequestId())
                && locked.id().equals(hold.packageInstanceId())
                && "READY".equals(hold.status())
                && locked.reservedTokens() == 0
                && ("ACTIVE".equals(locked.status()) || "QUEUED".equals(locked.status()));
        return valid ? Mono.empty() : Mono.error(conflict("refund_hold_not_ready"));
    }

    private Mono<Void> insertRevocationEvent(
            PackageRefundedEvent event, String payloadHash, UUID packageId) {
        return repository.insertRevocationEvent(
                        UUID.randomUUID(), event.eventId(), event.businessKey(), packageId,
                        event.payload().refundNo(), payloadHash)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("refund_revocation_event_insert_failed")))
                .then();
    }

    private Mono<Void> revokePackage(UUID packageId) {
        return repository.revokePackage(packageId)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("package_refund_revoke_failed")))
                .then();
    }

    private Mono<Void> revokeManagedApiKey(UUID packageId) {
        return repository.revokeManagedApiKey(packageId);
    }

    private Mono<Void> revokeHold(UUID holdId) {
        return repository.revokeHold(holdId)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("refund_hold_revoke_failed")))
                .then();
    }

    private Mono<Void> persistRevocationResult(String businessNo, RevocationResult result) {
        return repository.persistRevocationResult(
                        businessNo, result.activatedPackageInstanceId(), result.activatedPackageStatus())
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("refund_revocation_result_update_failed")))
                .then();
    }

    private Mono<RevocationExecution> existingRevocation(
            PackageRefundedEvent event, String payloadHash) {
        return repository.findExistingRevocationsForUpdate(event.businessKey(), event.eventId())
                .collectList()
                .flatMap(existingRows -> {
                    if (existingRows.isEmpty()) {
                        return Mono.empty();
                    }
                    if (existingRows.size() != 1) {
                        return Mono.error(conflict("event_payload_mismatch"));
                    }
                    ExistingRevocation existing = existingRows.getFirst();
                    if (!existing.businessNo().equals(event.businessKey())
                            || !constantEquals(existing.payloadHash(), payloadHash)) {
                        return Mono.error(conflict("event_payload_mismatch"));
                    }
                    return Mono.just(new RevocationExecution(
                            new RevocationResult(
                                    existing.packageInstanceId(), "REVOKED",
                                    existing.activatedPackageInstanceId(),
                                    existing.activatedPackageStatus()),
                            "REPLAY"));
                });
    }

    private Mono<PackageRow> packageByGrant(UUID grantRequestId) {
        return repository.findPackageByGrant(grantRequestId);
    }

    private Mono<PackageRow> lockPackage(UUID grantRequestId) {
        return repository.lockPackageByGrant(grantRequestId);
    }

    private Mono<RefundTarget> refundTarget(String businessNo) {
        return repository.findRefundTargetUser(businessNo).map(RefundTarget::new);
    }

    private Mono<RefundTarget> refundTarget(String businessNo, UUID grantRequestId) {
        return refundTarget(businessNo)
                .switchIfEmpty(packageByGrant(grantRequestId)
                        .map(packageRow -> new RefundTarget(packageRow.userId())));
    }

    private Mono<HoldRow> lockHold(String businessNo) {
        return repository.lockHold(businessNo);
    }

    private Mono<HoldRow> lockReadyHold(UUID packageId) {
        return repository.lockReadyHold(packageId);
    }

    private Mono<Boolean> hasReservedRows(UUID packageId) {
        return repository.hasReservedRows(packageId);
    }

    private Mono<UUID> openHoldForPackage(UUID packageId) {
        return repository.findOpenHoldForPackage(packageId);
    }

    private Mono<Void> advisoryLock(UUID userId) {
        return repository.lockUser(userId);
    }

    private Mono<Void> refundBusinessLock(String businessNo) {
        return repository.lockRefundBusiness(businessNo);
    }

    private Mono<Void> revocationIdempotencyLock(PackageRefundedEvent event) {
        return repository.lockRevocationIdempotency(event.businessKey(), event.eventId());
    }

    private RefundSnapshot snapshot(HoldRow hold) {
        return new RefundSnapshot(
                hold.businessNo(), hold.packageInstanceId(), hold.status(), hold.originalStatus(),
                hold.totalTokens(), hold.usedTokens(), hold.remainingTokens(),
                hold.packageExpiresAt(), hold.snapshotAt());
    }

    private boolean refundable(PackageRow packageRow) {
        if ("QUEUED".equals(packageRow.status())) {
            return true;
        }
        return "ACTIVE".equals(packageRow.status())
                && packageRow.expiresAt() != null
                && packageRow.expiresAt().isAfter(Instant.now());
    }

    private void requireMatchingHold(UUID grantRequestId, String payloadHash, HoldRow hold) {
        if (!grantRequestId.equals(hold.grantRequestId())
                || !constantEquals(payloadHash, hold.payloadHash())) {
            throw conflict("refund_hold_payload_mismatch");
        }
    }

    private String commandHash(String businessNo, UUID grantRequestId) {
        return sha256(businessNo + "\n" + grantRequestId);
    }

    private String sha256(String value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("refund_payload_hash_failed", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("refund_payload_serialization_failed", exception);
        }
    }

    private boolean constantEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private void validate(PrepareRefundCommand command) {
        if (command == null || blank(command.businessNo()) || command.businessNo().length() > 128
                || command.grantRequestId() == null) {
            throw unprocessable("invalid_refund_prepare_request");
        }
    }

    private void validate(ReleaseRefundCommand command) {
        if (command == null || blank(command.businessNo()) || command.businessNo().length() > 128
                || command.grantRequestId() == null) {
            throw unprocessable("invalid_refund_release_request");
        }
    }

    private void validate(PackageRefundedEvent event) {
        PackageRefundedPayload payload = event == null ? null : event.payload();
        if (event == null || event.eventId() == null || !"PACKAGE_REFUNDED_V1".equals(event.eventType())
                || blank(event.businessKey()) || event.businessKey().length() > 128
                || payload == null || blank(payload.refundNo()) || payload.refundNo().length() > 128
                || blank(payload.revocationBusinessNo()) || payload.revocationBusinessNo().length() > 128
                || !event.businessKey().equals(payload.revocationBusinessNo())
                || payload.grantRequestId() == null || payload.userId() == null) {
            throw unprocessable("invalid_package_refunded_event");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public record PrepareRefundCommand(
            @NotBlank @Size(max = 128) String businessNo,
            @NotNull UUID grantRequestId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RefundSnapshot(
            String businessNo,
            UUID packageInstanceId,
            String status,
            String originalStatus,
            Long totalTokens,
            Long usedTokens,
            Long remainingTokens,
            Instant packageExpiresAt,
            Instant snapshotAt) {
    }

    public record ReleaseRefundCommand(
            @NotBlank @Size(max = 128) String businessNo,
            @NotNull UUID grantRequestId) {
    }

    public record ReleaseResult(String businessNo, String status) {
    }

    private record ReleaseExecution(ReleaseResult result, String outcome) {
    }

    public record PackageRefundedEvent(
            @NotNull UUID eventId,
            @NotBlank @Size(max = 32) String eventType,
            @NotBlank @Size(max = 128) String businessKey,
            @NotNull @Valid PackageRefundedPayload payload) {
    }

    public record PackageRefundedPayload(
            @NotBlank @Size(max = 128) String refundNo,
            @NotBlank @Size(max = 128) String revocationBusinessNo,
            @NotNull UUID grantRequestId,
            @NotNull UUID userId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevocationResult(
            UUID packageInstanceId,
            String status,
            UUID activatedPackageInstanceId,
            String activatedPackageStatus) {
    }

    private record RevocationExecution(RevocationResult result, String outcome) {
    }

    private record RefundTarget(UUID userId) {
    }

}
