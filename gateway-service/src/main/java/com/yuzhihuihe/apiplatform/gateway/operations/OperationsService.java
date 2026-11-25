package com.yuzhihuihe.apiplatform.gateway.operations;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.yuzhihuihe.apiplatform.gateway.file.ExpiredFileCleanupJob;
import com.yuzhihuihe.apiplatform.gateway.quota.ReservationRecoveryJob;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelCircuitRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class OperationsService {

    static final String TENANT_ID_CONTEXT_KEY = "operationTenantId";
    static final String ACTOR_USER_ID_CONTEXT_KEY = "operationActorUserId";
    static final String IDEMPOTENCY_KEY_CONTEXT_KEY = "operationIdempotencyKey";
    static final String REQUEST_ID_CONTEXT_KEY = "operationRequestId";

    private static final String RECOVER_ACTION = "GATEWAY_RESERVATIONS_RECOVERED";
    private static final String CLEANUP_ACTION = "GATEWAY_FILES_CLEANED";

    private final OperationsRepository repository;
    private final ReservationRecoveryJob recoveryJob;
    private final ExpiredFileCleanupJob cleanupJob;
    private final ChannelCircuitRegistry circuits;

    OperationsService(
            OperationsRepository repository,
            ReservationRecoveryJob recoveryJob,
            ExpiredFileCleanupJob cleanupJob,
            ChannelCircuitRegistry circuits) {
        this.repository = repository;
        this.recoveryJob = recoveryJob;
        this.cleanupJob = cleanupJob;
        this.circuits = circuits;
    }

    public Mono<HealthSnapshot> health() {
        Mono<Long> expired = repository.countExpiredReservations();
        Mono<Long> cleanupCandidates = repository.countCleanupCandidates();
        return Mono.zip(expired, cleanupCandidates)
                .map(counts -> new HealthSnapshot(
                        "UP", "UP", Math.toIntExact(counts.getT1()),
                        Math.toIntExact(counts.getT2()), circuits.health()))
                .onErrorReturn(new HealthSnapshot("DOWN", "DOWN", 0, 0, circuits.health()));
    }

    public Mono<OperationResult> recoverReservations() {
        return execute(RECOVER_ACTION, "recover-reservations",
                () -> recoveryJob.recoverOnce().map(OperationCount::new));
    }

    public Mono<OperationResult> cleanupFiles() {
        return execute(CLEANUP_ACTION, "cleanup-files",
                () -> cleanupJob.cleanup().thenReturn(new OperationCount(null)));
    }

    private Mono<OperationResult> execute(
            String action, String operation, Supplier<Mono<OperationCount>> work) {
        return Mono.deferContextual(context -> {
            OperationContext operationContext = new OperationContext(
                    context.get(TENANT_ID_CONTEXT_KEY),
                    context.get(ACTOR_USER_ID_CONTEXT_KEY),
                    context.get(IDEMPOTENCY_KEY_CONTEXT_KEY),
                    context.get(REQUEST_ID_CONTEXT_KEY));
            UUID operationId = UUID.randomUUID();
            return repository.insert(
                            operationId,
                            action,
                            operationContext.tenantId(),
                            operationContext.actorUserId(),
                            operationContext.idempotencyKey(),
                            operationContext.requestId())
                    .flatMap(inserted -> work.get()
                            .flatMap(count -> repository.complete(operationId, count.affected())
                                    .thenReturn(new OperationResult(
                                            operation, "COMPLETED", count.affected())))
                            .onErrorResume(error -> repository
                                    .fail(operationId, error.getClass().getSimpleName())
                                    .then(Mono.error(error))))
                    .switchIfEmpty(load(action, operationContext, operation));
        });
    }

    private Mono<OperationResult> load(
            String action, OperationContext context, String operation) {
        return repository
                .find(action, context.tenantId(), context.actorUserId(), context.idempotencyKey())
                .map(row -> new OperationResult(operation, row.status(), row.affected()));
    }

    public record HealthSnapshot(
            String status, String database, int expiredReservations,
            int cleanupCandidates, List<ChannelCircuitRegistry.CircuitHealth> circuits) { }

    public record OperationResult(String operation, String status, Integer affected) { }

    private record OperationContext(
            UUID tenantId, UUID actorUserId, String idempotencyKey, String requestId) { }

    private record OperationCount(Integer affected) { }
}
