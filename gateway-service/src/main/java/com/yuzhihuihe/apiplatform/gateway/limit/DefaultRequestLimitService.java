package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yuzhihuihe.apiplatform.gateway.sms.RateLimitSmsReporter;

import reactor.core.publisher.Mono;

public final class DefaultRequestLimitService implements RequestLimitService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRequestLimitService.class);

    private final LimitStore redisStore;
    private final LimitStore databaseFallback;
    private final int fallbackMaxConcurrency;
    private final Duration redisTimeout;
    private final DailyRequestLimitService dailyRequestLimitService;
    private final PackageTokenRateLimitService packageTokenRateLimitService;
    private RateLimitSmsReporter rateLimitSmsReporter;
    private BlockedAdmissionService blockedAdmissionService = BlockedAdmissionService.disabled();

    public DefaultRequestLimitService(
            LimitStore redisStore, LimitStore databaseFallback, int fallbackMaxConcurrency) {
        this(redisStore, databaseFallback, fallbackMaxConcurrency, Duration.ofSeconds(1));
    }

    public DefaultRequestLimitService(
            LimitStore redisStore, LimitStore databaseFallback, int fallbackMaxConcurrency,
            Duration redisTimeout) {
        this(redisStore, databaseFallback, fallbackMaxConcurrency, redisTimeout, null);
    }

    public DefaultRequestLimitService(
            LimitStore redisStore, LimitStore databaseFallback, int fallbackMaxConcurrency,
            Duration redisTimeout, RateLimitSmsReporter rateLimitSmsReporter) {
        this(redisStore, databaseFallback, fallbackMaxConcurrency, redisTimeout,
                rateLimitSmsReporter, DailyRequestLimitService.unlimited());
    }

    public DefaultRequestLimitService(
            LimitStore redisStore, LimitStore databaseFallback, int fallbackMaxConcurrency,
            Duration redisTimeout, RateLimitSmsReporter rateLimitSmsReporter,
            DailyRequestLimitService dailyRequestLimitService) {
        this(redisStore, databaseFallback, fallbackMaxConcurrency, redisTimeout,
                rateLimitSmsReporter, dailyRequestLimitService,
                PackageTokenRateLimitService.unlimited());
    }

    public DefaultRequestLimitService(
            LimitStore redisStore, LimitStore databaseFallback, int fallbackMaxConcurrency,
            Duration redisTimeout, RateLimitSmsReporter rateLimitSmsReporter,
            DailyRequestLimitService dailyRequestLimitService,
            PackageTokenRateLimitService packageTokenRateLimitService) {
        if (redisTimeout == null || redisTimeout.isZero() || redisTimeout.isNegative()) {
            throw new IllegalArgumentException("redis_timeout_required");
        }
        this.redisStore = redisStore;
        this.databaseFallback = databaseFallback;
        this.fallbackMaxConcurrency = fallbackMaxConcurrency;
        this.redisTimeout = redisTimeout;
        this.rateLimitSmsReporter = rateLimitSmsReporter;
        this.dailyRequestLimitService = dailyRequestLimitService == null
                ? DailyRequestLimitService.unlimited() : dailyRequestLimitService;
        this.packageTokenRateLimitService = packageTokenRateLimitService == null
                ? PackageTokenRateLimitService.unlimited() : packageTokenRateLimitService;
    }

    @Autowired(required = false)
    void setRateLimitSmsReporter(RateLimitSmsReporter reporter) {
        this.rateLimitSmsReporter = reporter;
    }

    @Autowired(required = false)
    void setBlockedAdmissionService(BlockedAdmissionService service) {
        this.blockedAdmissionService = service == null
                ? BlockedAdmissionService.disabled() : service;
    }

    @Override
    public Mono<LimitLease> acquire(LimitCommand command) {
        return Mono.defer(() -> {
            EffectiveLimit effective = command.effective();
            return redisStore.acquire(effective).timeout(redisTimeout)
                    .onErrorResume(error -> {
                        if (error instanceof ResponseStatusException) {
                            reportRateLimit(command, error);
                            recordBlockedAdmission(command, error);
                            return Mono.error(error);
                        }
                    return effective.concurrencyLimit() <= fallbackMaxConcurrency
                            ? databaseFallback.acquire(effective)
                                    .doOnError(fallbackError -> {
                                        reportRateLimit(command, fallbackError);
                                        recordBlockedAdmission(command, fallbackError);
                                    })
                                    .onErrorMap(fallbackError -> !isRateLimited(fallbackError),
                                            fallbackError -> dependencyUnavailable())
                            : Mono.error(dependencyUnavailable());
                })
                    .flatMap(lease -> admitPackageCaps(command, lease));
        });
    }

    @Override
    public Mono<LimitLease> acquirePublic(LimitCommand command) {
        return Mono.defer(() -> {
            EffectiveLimit effective = command.effective();
            return redisStore.isAvailable()
                    .timeout(redisTimeout)
                    .onErrorReturn(false)
                    .flatMap(available -> available
                            ? acquirePublicFromRedis(command, effective)
                            : acquireFallback(command, effective))
                    .flatMap(lease -> admitPackageCaps(command, lease));
        });
    }

    /**
     * 套餐所售出的各项上限在分钟级租约授予之后才计数，因此被分钟级限流器拒绝的请求
     * 绝不会消耗客户的日额度或每分钟额度，而被日额度或额度上限拒绝的请求则立即归还
     * 它的分钟级租约。日额度先判定，所以一个日额度已耗尽的套餐报告的是这项更粗粒度的
     * 额度；只有当当日仍有余量时才会去查询额度速率。
     */
    private Mono<LimitLease> admitPackageCaps(LimitCommand command, LimitLease lease) {
        Mono<Void> daily = admitDaily(command);
        Mono<Void> credits = admitCredits(command);
        Mono<Void> caps = daily.then(credits);
        return caps.thenReturn(lease)
                .onErrorResume(error -> release(lease)
                        .onErrorResume(ignored -> Mono.empty())
                        .then(Mono.error(error)));
    }

    private Mono<Void> admitDaily(LimitCommand command) {
        return command.packageDailyLimit().enforced()
                ? dailyRequestLimitService.admit(command.packageDailyLimit(), command.userId())
                : Mono.empty();
    }

    private Mono<Void> admitCredits(LimitCommand command) {
        return command.packageTokenLimit().enforced()
                ? packageTokenRateLimitService.admit(command.packageTokenLimit(), command.userId())
                : Mono.empty();
    }

    @Override
    public void recordPackageConsumption(
            RequestLimitService.PackageTokenLimit limit, long chargedCredits) {
        if (limit == null || !limit.enforced() || chargedCredits <= 0) {
            return;
        }
        try {
            packageTokenRateLimitService.record(limit, chargedCredits);
        } catch (RuntimeException failure) {
            LOG.warn("recording settled credits for package {} failed; the settlement it belongs to"
                    + " is unaffected: {}", limit.packageInstanceId(),
                    failure.getClass().getSimpleName());
        }
    }

    private Mono<LimitLease> acquirePublicFromRedis(
            LimitCommand command, EffectiveLimit effective) {
        return redisStore.acquire(effective)
                .timeout(redisTimeout)
                .doOnError(error -> {
                    reportRateLimit(command, error);
                    recordBlockedAdmission(command, error);
                })
                .onErrorMap(error -> !isRateLimited(error), error -> dependencyUnavailable());
    }

    private Mono<LimitLease> acquireFallback(
            LimitCommand command, EffectiveLimit effective) {
        if (effective.concurrencyLimit() > fallbackMaxConcurrency) {
            return Mono.error(dependencyUnavailable());
        }
        return databaseFallback.acquire(effective)
                .doOnError(error -> {
                    reportRateLimit(command, error);
                    recordBlockedAdmission(command, error);
                })
                .onErrorMap(error -> !isRateLimited(error), error -> dependencyUnavailable());
    }

    @Override
    public Mono<LimitLease> rebindChannel(LimitLease lease, java.util.UUID channelId) {
        return Mono.defer(() -> {
            if (lease == null || channelId == null) {
                return Mono.error(new IllegalArgumentException("invalid_limit_channel_rebind"));
            }
            LimitStore store = lease.store() == Store.DATABASE ? databaseFallback : redisStore;
            Mono<LimitLease> operation = store.rebindChannel(lease, channelId);
            return bounded(lease.store(), operation)
                    .doOnError(error -> reportRateLimit(lease, error))
                    .onErrorMap(error -> !isRateLimited(error), error -> dependencyUnavailable());
        });
    }

    @Override
    public Mono<LimitLease> activateRoute(
            LimitLease lease,
            java.util.UUID channelId,
            int rpmLimit,
            int concurrencyLimit) {
        return Mono.defer(() -> {
            if (lease == null || channelId == null || rpmLimit <= 0 || concurrencyLimit <= 0) {
                return Mono.error(new IllegalArgumentException("invalid_limit_route_activation"));
            }
            int effectiveRpm = Math.min(lease.rpmLimit(), rpmLimit);
            int effectiveConcurrency = Math.min(lease.concurrencyLimit(), concurrencyLimit);
            LimitStore store = lease.store() == Store.DATABASE ? databaseFallback : redisStore;
            Mono<LimitLease> operation = store.activateRoute(
                    lease, channelId, effectiveRpm, effectiveConcurrency);
            return bounded(lease.store(), operation)
                    .doOnError(error -> reportRateLimit(lease, error))
                    .map(activated -> new LimitLease(
                            activated.leaseId(), activated.apiKeyId(), activated.userId(),
                            activated.modelCode(), activated.channelId(), lease.rpmLimit(),
                            lease.concurrencyLimit(), activated.store()))
                    .onErrorMap(error -> !isRateLimited(error), error -> dependencyUnavailable());
        });
    }

    @Override
    public Mono<Void> release(LimitLease lease) {
        return Mono.defer(() -> {
            if (lease == null) {
                return Mono.empty();
            }
            LimitStore store = lease.store() == Store.DATABASE ? databaseFallback : redisStore;
            return bounded(lease.store(), store.release(lease));
        });
    }

    private <T> Mono<T> bounded(Store store, Mono<T> operation) {
        return store == Store.REDIS ? operation.timeout(redisTimeout) : operation;
    }

    private ResponseStatusException dependencyUnavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable");
    }

    private boolean isRateLimited(Throwable error) {
        return error instanceof ResponseStatusException response
                && response.getStatusCode().equals(HttpStatus.TOO_MANY_REQUESTS);
    }

    private void reportRateLimit(LimitCommand command, Throwable error) {
        if (!isRateLimited(error) || rateLimitSmsReporter == null) {
            return;
        }
        try {
            rateLimitSmsReporter.report(command).subscribe(
                    ignored -> { }, this::logReporterFailure);
        } catch (RuntimeException reportingFailure) {
            logReporterFailure(reportingFailure);
        }
    }

    private void reportRateLimit(LimitLease lease, Throwable error) {
        if (!isRateLimited(error) || rateLimitSmsReporter == null) {
            return;
        }
        try {
            rateLimitSmsReporter.reportLease(lease).subscribe(
                    ignored -> { }, this::logReporterFailure);
        } catch (RuntimeException reportingFailure) {
            logReporterFailure(reportingFailure);
        }
    }

    private void logReporterFailure(Throwable error) {
        String failureType = error == null ? "unknown" : error.getClass().getSimpleName();
        LOG.warn("rate-limit SMS reporting failed; admission result is unchanged: {}", failureType);
    }

    /**
     * 把一次被拒绝的准入计入它所针对的套餐，这样套餐的运营视图就能说明它命中各项上限的
     * 次数。不属于任何套餐实例的拒绝——没有套餐的 key 或渠道突发流量——不算任何套餐的
     * 计数，因此不予记录。
     *
     * <p>各服务自身产生的套餐上限（{@code tokens_per_minute_exceeded}、
     * {@code daily_request_limit_exceeded}、{@code seconds_per_minute_exceeded}）在它们被
     * 抛出的地方计数；这里覆盖的是分钟级限流器与降级存储所抛出的存储层上限
     * （{@code rate_limit_exceeded}）。
     */
    private void recordBlockedAdmission(LimitCommand command, Throwable error) {
        if (command == null || !isRateLimited(error)) {
            return;
        }
        java.util.UUID packageInstanceId = command.packageTokenLimit() == null
                ? null : command.packageTokenLimit().packageInstanceId();
        if (packageInstanceId == null && command.packageDailyLimit() != null) {
            packageInstanceId = command.packageDailyLimit().packageInstanceId();
        }
        if (packageInstanceId == null) {
            return;
        }
        blockedAdmissionService.record(packageInstanceId, BlockedAdmissionService.reasonOf(error));
    }
}
