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
 * 已售出每分钟积分速率的主路径与降级路径两半。
 *
 * <p><b>它能承诺什么。</b>积分只有在请求被服务完之后才知道，所以这是一个后付费的速率上限，
 * 而不是准入时的预留：当当前分钟内已计入的积分达到已售出的速率时，请求会被拒绝。两个后果，
 * 都如实上报而不是隐藏：
 *
 * <ol>
 *   <li>在桶满时已经处于在途状态的请求，会按它们最终消耗的积分被准入。因此超出的部分被那一刻
 *       在途请求的积分所界定，而在途数量又被该套餐自身售出的并发所界定。
 *   <li>突发中的第一个请求总是会被准入，即使该请求本身就大于整个每分钟额度，此后该上限在
 *       本分钟剩余时间内保持生效。要堵住这一点就意味着在准入时按请求的预留上限来拒绝，而那
 *       是 {@code input estimate + min(8192, model max output)}：一个没有设置 {@code
 *       max_tokens} 的请求是十 token 的提示词配 8192 积分的上限，所以一个售出速率低于每分钟
 *       8192 积分的套餐，会拒绝掉每一个它本可以轻松服务的请求。那等于凭空发明了一个产品从未
 *       售卖过的上限，而这正是本设计要避免的失败，所以这个上限被有意不用作拒绝的依据。
 * </ol>
 *
 * <p><b>失败策略。</b>已售出的额度不是可以猜的东西，所以该决策与每日请求上限完全一样地
 * 失败即拒绝（fail-closed）：先由 Redis 作答，Redis 不可用时由当前分钟已结算的用量行作答，
 * 任何让决策停留在未知状态的情况都返回 {@code 503 dependency_unavailable}，而不是准入一个
 * 该套餐可能并不拥有的请求。
 */
public final class DefaultPackageTokenRateLimitService implements PackageTokenRateLimitService {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultPackageTokenRateLimitService.class);

    private final Store redisStore;
    private final Store databaseFallback;
    private final Duration redisTimeout;
    private final Clock clock;
    private BlockedAdmissionService blockedAdmissionService = BlockedAdmissionService.disabled();

    public DefaultPackageTokenRateLimitService(
            Store redisStore, Store databaseFallback, Duration redisTimeout) {
        this(redisStore, databaseFallback, redisTimeout, Clock.systemUTC());
    }

    public DefaultPackageTokenRateLimitService(
            Store redisStore, Store databaseFallback, Duration redisTimeout, Clock clock) {
        if (redisStore == null || databaseFallback == null || redisTimeout == null
                || redisTimeout.isZero() || redisTimeout.isNegative() || clock == null) {
            throw new IllegalArgumentException("invalid_package_token_rate_limit_service");
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
    public Mono<Void> admit(RequestLimitService.PackageTokenLimit limit, UUID userId) {
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
    public void record(RequestLimitService.PackageTokenLimit limit, long chargedCredits) {
        if (limit == null || !limit.enforced() || chargedCredits <= 0) {
            return;
        }
        try {
            redisStore.record(limit, chargedCredits)
                    .timeout(redisTimeout)
                    .subscribe(
                            ignored -> { },
                            failure -> logRecordFailure(limit, failure));
        } catch (RuntimeException failure) {
            logRecordFailure(limit, failure);
        }
    }

    @Override
    public Mono<Long> used(RequestLimitService.PackageTokenLimit limit, UUID userId) {
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
                    // 对一个参考性计数器的报表读取：读不到的就报成没有计入任何量，
                    // 而不是让发起询问的接口失败。
                    .onErrorReturn(0L);
        });
    }

    /**
     * 窗口是纪元分钟，也就是主存储的 Lua 依据 Redis 时钟算出来的那个值，因此也是分钟请求限流器
     * 已经在其中计数的同一分钟。降级查询把它与一个 {@code timestamptz} 列相比较，所以它同样
     * 不需要时区。
     */
    private Instant minute() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MINUTES);
    }

    private Mono<Void> admitOn(
            Store store,
            RequestLimitService.PackageTokenLimit limit,
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
                        : Mono.<Void>error(tokenRateLimitExceeded(limit)))
                .onErrorMap(error -> !isRateLimited(error), error -> dependencyUnavailable());
    }

    private Mono<Long> read(
            Store store,
            RequestLimitService.PackageTokenLimit limit,
            UUID userId,
            Instant minute,
            Duration timeout) {
        Mono<Long> read = store.used(limit, userId, minute);
        if (timeout != null) {
            read = read.timeout(timeout);
        }
        return read.defaultIfEmpty(0L);
    }

    private void logRecordFailure(
            RequestLimitService.PackageTokenLimit limit, Throwable failure) {
        LOG.warn(
                "sold credit rate for package {} was not updated by a settled request; admission"
                        + " stays on the last recorded value: {}",
                limit.packageInstanceId(),
                failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    private boolean isRateLimited(Throwable error) {
        return error instanceof ResponseStatusException response
                && response.getStatusCode().equals(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseStatusException tokenRateLimitExceeded(
            RequestLimitService.PackageTokenLimit limit) {
        blockedAdmissionService.record(
                limit.packageInstanceId(), BlockedAdmissionService.TOKENS_PER_MINUTE_EXCEEDED);
        return new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, BlockedAdmissionService.TOKENS_PER_MINUTE_EXCEEDED);
    }

    private ResponseStatusException dependencyUnavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable");
    }
}
