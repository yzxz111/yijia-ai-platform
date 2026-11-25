package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * 生成套餐所售「每分钟单位」速率的主路径与降级路径这两半。
 *
 * <p>结构上是 {@link DefaultPackageTokenRateLimitService} 的双生子：先由 Redis 作答；Redis 不可用
 * 时由当前分钟已结算的单位作答；未知判定以 {@code 503 dependency_unavailable} 失败即拒绝；一次
 * 拒绝被上报为 {@code 429 seconds_per_minute_exceeded}（秒）或
 * {@code 429 images_per_minute_exceeded}（张）—— 与 token 方向的
 * {@code tokens_per_minute_exceeded} 是不同的错误码，因此客户端能区分「该套餐这一分钟的生成单位
 * 已用尽」与「该套餐这一分钟的聊天积分已用尽」，也能区分用尽的是秒还是张。
 *
 * <p>准入按套餐实例询问，因为生成路径没有已处理过的目录行：套餐的维度与售卖数值读取自
 * {@link PackageRateSource}（{@code gateway.package_instance}），并由 {@link PackageRateLimits}
 * 解释，因此 {@code VIDEO_SECOND} 套餐以秒为界、{@code IMAGE} 套餐以张为界，
 * 而每分钟数值是聊天 token 速率的套餐则完全不以生成单位为界。
 *
 * <p>关于后付费的单位上限能承诺什么、不能承诺什么，见 {@link PackageSecondRateLimitService}。
 */
public final class DefaultPackageSecondRateLimitService implements PackageSecondRateLimitService {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultPackageSecondRateLimitService.class);

    private final Store redisStore;
    private final Store databaseFallback;
    private final Duration redisTimeout;
    private final Clock clock;
    private final PackageRateSource rateSource;
    private BlockedAdmissionService blockedAdmissionService = BlockedAdmissionService.disabled();

    public DefaultPackageSecondRateLimitService(
            Store redisStore, Store databaseFallback, Duration redisTimeout,
            PackageRateSource rateSource) {
        this(redisStore, databaseFallback, redisTimeout, rateSource, Clock.systemUTC());
    }

    public DefaultPackageSecondRateLimitService(
            Store redisStore, Store databaseFallback, Duration redisTimeout,
            PackageRateSource rateSource, Clock clock) {
        if (redisStore == null || databaseFallback == null || redisTimeout == null
                || redisTimeout.isZero() || redisTimeout.isNegative() || rateSource == null
                || clock == null) {
            throw new IllegalArgumentException("invalid_package_second_rate_limit_service");
        }
        this.redisStore = redisStore;
        this.databaseFallback = databaseFallback;
        this.redisTimeout = redisTimeout;
        this.rateSource = rateSource;
        this.clock = clock;
    }

    @Autowired(required = false)
    void setBlockedAdmissionService(BlockedAdmissionService service) {
        this.blockedAdmissionService = service == null
                ? BlockedAdmissionService.disabled() : service;
    }

    @Override
    public Mono<Void> admit(UUID packageInstanceId, UUID userId) {
        return Mono.defer(() -> packageInstanceId == null
                ? Mono.empty()
                : rateSource.rateFor(packageInstanceId)
                        .flatMap(rates -> admit(rates.secondLimit(), userId)));
    }

    private Mono<Void> admit(RequestLimitService.PackageSecondLimit limit, UUID userId) {
        return Mono.defer(() -> {
            if (limit == null || !limit.enforced()) {
                return Mono.empty();
            }
            Instant minute = minute();
            return redisStore.isAvailable()
                    .timeout(redisTimeout)
                    .onErrorReturn(false)
                    .flatMap(available -> available
                            ? admitOn(redisStore, limit, userId, minute, redisTimeout)
                            : admitOn(databaseFallback, limit, userId, minute, null));
        });
    }

    @Override
    public void record(UUID packageInstanceId, long chargedSeconds) {
        if (packageInstanceId == null || chargedSeconds <= 0) {
            return;
        }
        try {
            redisStore.record(packageInstanceId, chargedSeconds)
                    .timeout(redisTimeout)
                    .subscribe(
                            ignored -> { },
                            failure -> logRecordFailure(packageInstanceId, failure));
        } catch (RuntimeException failure) {
            logRecordFailure(packageInstanceId, failure);
        }
    }

    @Override
    public Mono<Long> used(RequestLimitService.PackageSecondLimit limit, UUID userId) {
        return Mono.defer(() -> {
            if (limit == null || !limit.enforced()) {
                return Mono.just(0L);
            }
            Instant minute = minute();
            return redisStore.isAvailable()
                    .timeout(redisTimeout)
                    .onErrorReturn(false)
                    .flatMap(available -> available
                            ? read(redisStore, limit, userId, minute, redisTimeout)
                            : read(databaseFallback, limit, userId, minute, null))
                    // 对参考性计数器的一次报表读取：读不到的内容按「未计数」上报，
                    // 而不是让发起询问的端点失败。
                    .onErrorReturn(0L);
        });
    }

    /** 与积分速率和分钟请求限流器已经在使用的同一个统计窗口。 */
    private Instant minute() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MINUTES);
    }

    private Mono<Void> admitOn(
            Store store,
            RequestLimitService.PackageSecondLimit limit,
            UUID userId,
            Instant minute,
            Duration timeout) {
        Mono<Boolean> decision = store.admit(limit, userId, minute);
        if (timeout != null) {
            decision = decision.timeout(timeout);
        }
        return decision
                .switchIfEmpty(Mono.error(dependencyUnavailable()))
                .flatMap(admitted -> admitted
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(unitRateLimitExceeded(limit)))
                .onErrorMap(error -> !isRateLimited(error), error -> dependencyUnavailable());
    }

    private Mono<Long> read(
            Store store,
            RequestLimitService.PackageSecondLimit limit,
            UUID userId,
            Instant minute,
            Duration timeout) {
        Mono<Long> read = store.used(limit, userId, minute);
        if (timeout != null) {
            read = read.timeout(timeout);
        }
        return read.defaultIfEmpty(0L);
    }

    private void logRecordFailure(UUID packageInstanceId, Throwable failure) {
        LOG.warn(
                "sold seconds rate for package {} was not updated by a settled generation;"
                        + " admission stays on the last recorded value: {}",
                packageInstanceId,
                failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    /**
     * 由判定本身产生的一次拒绝。经过下面的错误映射后它仍然是 {@code 429}：额度超限并非未知判定，
     * 与积分速率中完全一样。
     *
     * <p>错误码按该上限的单位选择：{@code VIDEO_SECOND} 是秒，
     * {@code IMAGE} 是张。两者是不同的额度，因此客户端必须能区分
     * 「这一分钟的生图张数用完了」与「这一分钟的视频秒数用完了」——
     * 把张数报告成秒会让客户去找一个根本没有被消耗的额度。
     */
    private ResponseStatusException unitRateLimitExceeded(
            RequestLimitService.PackageSecondLimit limit) {
        String reason = PackageRateLimits.IMAGE_DIMENSION.equals(limit.usageDimension())
                ? BlockedAdmissionService.IMAGES_PER_MINUTE_EXCEEDED
                : BlockedAdmissionService.SECONDS_PER_MINUTE_EXCEEDED;
        blockedAdmissionService.record(limit.packageInstanceId(), reason);
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, reason);
    }

    private boolean isRateLimited(Throwable error) {
        return error instanceof ResponseStatusException response
                && response.getStatusCode().equals(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseStatusException dependencyUnavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable");
    }
}
