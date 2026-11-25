package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

public final class DefaultDailyRequestLimitService implements DailyRequestLimitService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final Store redisStore;
    private final Store databaseFallback;
    private final Duration redisTimeout;
    private final Clock clock;
    private BlockedAdmissionService blockedAdmissionService = BlockedAdmissionService.disabled();

    public DefaultDailyRequestLimitService(
            Store redisStore, Store databaseFallback, Duration redisTimeout) {
        this(redisStore, databaseFallback, redisTimeout, Clock.systemUTC());
    }

    public DefaultDailyRequestLimitService(
            Store redisStore, Store databaseFallback, Duration redisTimeout, Clock clock) {
        if (redisTimeout == null || redisTimeout.isZero() || redisTimeout.isNegative()
                || clock == null) {
            throw new IllegalArgumentException("invalid_daily_request_limit_service");
        }
        this.redisStore = redisStore;
        this.databaseFallback = databaseFallback;
        this.redisTimeout = redisTimeout;
        this.clock = clock;
    }

    @Autowired(required = false)
    void setBlockedAdmissionService(BlockedAdmissionService service) {
        this.blockedAdmissionService = service == null
                ? BlockedAdmissionService.disabled() : service;
    }

    @Override
    public Mono<Void> admit(RequestLimitService.PackageDailyLimit limit, UUID userId) {
        return Mono.defer(() -> {
            if (limit == null || !limit.enforced()) {
                return Mono.empty();
            }
            LocalDate day = LocalDate.now(clock.withZone(SHANGHAI));
            return redisStore.isAvailable()
                    .timeout(redisTimeout)
                    .onErrorReturn(false)
                    .flatMap(available -> available
                            ? admitOn(redisStore, limit, userId, day, redisTimeout)
                            : admitOn(databaseFallback, limit, userId, day, null));
        });
    }

    @Override
    public Mono<Long> used(RequestLimitService.PackageDailyLimit limit, UUID userId) {
        return Mono.defer(() -> {
            if (limit == null || !limit.enforced()) {
                return Mono.just(0L);
            }
            LocalDate day = LocalDate.now(clock.withZone(SHANGHAI));
            return redisStore.isAvailable()
                    .timeout(redisTimeout)
                    .onErrorReturn(false)
                    .flatMap(available -> available
                            ? read(redisStore, limit, userId, day, redisTimeout)
                            : read(databaseFallback, limit, userId, day, null))
                    // 对一个建议性计数器的报表式读取：读不到的内容会被报告为「未计数」，
                    // 而不是让发问的端点失败。
                    .onErrorReturn(0L);
        });
    }

    private Mono<Void> admitOn(
            Store store,
            RequestLimitService.PackageDailyLimit limit,
            UUID userId,
            LocalDate day,
            Duration timeout) {
        Mono<Boolean> decision = store.admit(limit, userId, day);
        if (timeout != null) {
            decision = decision.timeout(timeout);
        }
        return decision
                .switchIfEmpty(Mono.error(dependencyUnavailable()))
                .flatMap(admitted -> admitted
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(dailyLimitExceeded(limit)))
                .onErrorMap(error -> !isRateLimited(error), error -> dependencyUnavailable());
    }

    private Mono<Long> read(
            Store store,
            RequestLimitService.PackageDailyLimit limit,
            UUID userId,
            LocalDate day,
            Duration timeout) {
        Mono<Long> read = store.used(limit, userId, day);
        if (timeout != null) {
            read = read.timeout(timeout);
        }
        return read.defaultIfEmpty(0L);
    }

    private boolean isRateLimited(Throwable error) {
        return error instanceof ResponseStatusException response
                && response.getStatusCode().equals(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseStatusException dailyLimitExceeded(
            RequestLimitService.PackageDailyLimit limit) {
        blockedAdmissionService.record(
                limit.packageInstanceId(), BlockedAdmissionService.DAILY_REQUEST_LIMIT_EXCEEDED);
        return new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, BlockedAdmissionService.DAILY_REQUEST_LIMIT_EXCEEDED);
    }

    private ResponseStatusException dependencyUnavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable");
    }
}
