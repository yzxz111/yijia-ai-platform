package com.yuzhihuihe.apiplatform.gateway.openai;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

final class ChatPublicAdmissionLifecycle {

    private static final UUID PUBLIC_ADMISSION_CHANNEL_ID = new UUID(0L, 0L);

    private final RequestLimitService requestLimitService;
    private final QuotaService quotaService;
    private final ChatCompletionRequestValidator requestValidator;

    ChatPublicAdmissionLifecycle(
            RequestLimitService requestLimitService,
            QuotaService quotaService,
            ChatCompletionRequestValidator requestValidator) {
        this.requestLimitService = requestLimitService;
        this.quotaService = quotaService;
        this.requestValidator = requestValidator;
    }

    void validateForAdmission(
            ApiKeyService.ApiKeyContext apiKey, String publicModelAlias) {
        requestValidator.validatePublicAlias(apiKey, publicModelAlias);
    }

    Mono<AdmissionState> acquire(
            ApiKeyService.ApiKeyContext apiKey,
            String publicModelAlias,
            RequestLimitService.Thresholds rpm,
            RequestLimitService.Thresholds concurrency) {
        return acquire(apiKey, publicModelAlias, rpm, concurrency,
                RequestLimitService.PackageDailyLimit.unlimited());
    }

    Mono<AdmissionState> acquire(
            ApiKeyService.ApiKeyContext apiKey,
            String publicModelAlias,
            RequestLimitService.Thresholds rpm,
            RequestLimitService.Thresholds concurrency,
            RequestLimitService.PackageDailyLimit dailyLimit) {
        return acquire(apiKey, publicModelAlias, rpm, concurrency, dailyLimit,
                RequestLimitService.PackageTokenLimit.unlimited());
    }

    Mono<AdmissionState> acquire(
            ApiKeyService.ApiKeyContext apiKey,
            String publicModelAlias,
            RequestLimitService.Thresholds rpm,
            RequestLimitService.Thresholds concurrency,
            RequestLimitService.PackageDailyLimit dailyLimit,
            RequestLimitService.PackageTokenLimit tokenLimit) {
        return requestLimitService.acquirePublic(new RequestLimitService.LimitCommand(
                        apiKey.apiKeyId(), apiKey.userId(), publicModelAlias,
                        PUBLIC_ADMISSION_CHANNEL_ID, rpm, concurrency, Duration.ofMinutes(2),
                        dailyLimit, tokenLimit))
                .map(AdmissionState::new);
    }

    <V, T> Mono<T> withClaimedAdmission(
            AdmissionState admission,
            Supplier<V> validation,
            Function<V, Mono<T>> execution) {
        return Mono.defer(() -> {
            if (admission == null || !admission.claim()) {
                return Mono.error(new IllegalArgumentException("public_admission_required"));
            }
            V validated;
            try {
                validated = validation.get();
            } catch (RuntimeException error) {
                return release(admission).then(Mono.error(error));
            }
            return Mono.usingWhen(
                    Mono.just(admission), ignored -> execution.apply(validated),
                    ignored -> Mono.empty(),
                    (ignored, error) -> Mono.empty(),
                    this::cancel);
        });
    }

    void validateIdentity(
            ApiKeyService.ApiKeyContext apiKey,
            String publicModelAlias,
            String admittedModelAlias,
            AdmissionState admission) {
        requestValidator.validatePublicAlias(apiKey, publicModelAlias);
        RequestLimitService.LimitLease lease = admission.lease();
        if (!admittedModelAlias.equals(publicModelAlias)
                || !lease.apiKeyId().equals(apiKey.apiKeyId())
                || !lease.userId().equals(apiKey.userId())
                || !lease.modelCode().equals(publicModelAlias)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_multimodal_request");
        }
    }

    boolean samePublicModel(
            UUID routedModelId,
            String routedModelAlias,
            int routedMaxOutputTokens,
            Integer routedPackageMaxOutputTokens,
            UUID admittedModelId,
            String admittedModelAlias,
            int admittedMaxOutputTokens,
            Integer admittedPackageMaxOutputTokens) {
        // 模型上限与已售套餐方案的上限两者都要比，因为二者共同构成该请求获准时所依据的
        // 输出上限：依据一个上限准入、却依据另一个上限预留，会让发给供应商的请求体与预留量
        // 互相矛盾。
        return routedModelId.equals(admittedModelId)
                && routedModelAlias.equals(admittedModelAlias)
                && routedMaxOutputTokens == admittedMaxOutputTokens
                && java.util.Objects.equals(
                        routedPackageMaxOutputTokens, admittedPackageMaxOutputTokens);
    }

    boolean samePublicModel(
            UUID routedModelId,
            String routedModelAlias,
            int routedMaxOutputTokens,
            UUID admittedModelId,
            String admittedModelAlias,
            int admittedMaxOutputTokens) {
        return samePublicModel(routedModelId, routedModelAlias, routedMaxOutputTokens, null,
                admittedModelId, admittedModelAlias, admittedMaxOutputTokens, null);
    }

    void associateReservation(AdmissionState admission, UUID reservationId) {
        if (admission != null) {
            admission.reservationId(reservationId);
        }
    }

    Mono<Void> releaseLease(
            RequestLimitService.LimitLease lease, AdmissionState admission) {
        return admission == null ? requestLimitService.release(lease) : release(admission);
    }

    Mono<Void> release(AdmissionState admission) {
        if (admission == null) {
            return Mono.empty();
        }
        return Mono.defer(() -> {
            if (!admission.beginRelease()) {
                return Mono.empty();
            }
            return Mono.defer(() -> requestLimitService.release(admission.lease()))
                    .doOnSuccess(ignored -> admission.releaseSucceeded())
                    .doOnError(ignored -> admission.releaseAborted())
                    .doOnCancel(admission::releaseAborted);
        });
    }

    private Mono<Void> cancel(AdmissionState admission) {
        UUID reservationId = admission.reservationId();
        Mono<Void> reservationCleanup = reservationId == null
                ? Mono.empty()
                : quotaService.release(
                                reservationId, QuotaService.ReleaseReason.CLIENT_CANCELLED)
                        .onErrorResume(ignored -> Mono.empty());
        return reservationCleanup.then(release(admission));
    }

    static final class AdmissionState {
        private final RequestLimitService.LimitLease lease;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicReference<ReleaseState> releaseState =
                new AtomicReference<>(ReleaseState.AVAILABLE);
        private final AtomicReference<UUID> reservationId = new AtomicReference<>();

        AdmissionState(RequestLimitService.LimitLease lease) {
            if (lease == null) {
                throw new IllegalArgumentException("invalid_public_admission");
            }
            this.lease = lease;
        }

        RequestLimitService.LimitLease lease() {
            return lease;
        }

        boolean claimed() {
            return claimed.get();
        }

        private boolean claim() {
            return claimed.compareAndSet(false, true);
        }

        private boolean beginRelease() {
            return releaseState.compareAndSet(ReleaseState.AVAILABLE, ReleaseState.RELEASING);
        }

        private void releaseSucceeded() {
            releaseState.compareAndSet(ReleaseState.RELEASING, ReleaseState.RELEASED);
        }

        private void releaseAborted() {
            releaseState.compareAndSet(ReleaseState.RELEASING, ReleaseState.AVAILABLE);
        }

        private void reservationId(UUID value) {
            reservationId.set(value);
        }

        private UUID reservationId() {
            return reservationId.get();
        }

        private enum ReleaseState {
            AVAILABLE,
            RELEASING,
            RELEASED
        }
    }
}
