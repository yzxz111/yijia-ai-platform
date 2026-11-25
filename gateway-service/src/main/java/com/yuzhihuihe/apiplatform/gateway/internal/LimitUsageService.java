package com.yuzhihuihe.apiplatform.gateway.internal;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.limit.BlockedAdmissionService;
import com.yuzhihuihe.apiplatform.gateway.limit.DailyRequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.limit.PackageRateLimits;
import com.yuzhihuihe.apiplatform.gateway.limit.PackageSecondRateLimitService;
import com.yuzhihuihe.apiplatform.gateway.limit.PackageTokenRateLimitService;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * 一个套餐实例的限额是什么，以及它已经用掉了其中多少。
 *
 * <p><b>这些数字的含义。</b>{@code perMinute.limit} 是网关为该套餐实际强制执行的每分钟速率，
 * {@code perMinute.used} 则是同一个计数器的当前值：
 *
 * <ul>
 *   <li>每分钟数值是 token 速率的套餐，其对话消耗量受该数值约束，上报的就是它；
 *   <li>数值为「秒/分钟」的 {@code VIDEO_SECOND} 套餐，其生成量以秒为单位受该数值约束，
 *       上报的就是它 —— 由 {@code usageDimension} 指明单位，因此 {@code 190} 读作秒；
 *   <li>不为自身用量售卖任何每分钟速率的套餐，上报为 {@code limit: null}，
 *       而不是给出一个跨维度换算出来的数字。一个每分钟列承载的是对话 token 速率
 *       （平台的 V63 形态）的视频套餐就属于这一类：网关不为它约束任何秒数，
 *       而会报告它的积分速率，因为那才是它确实拥有的每分钟限额。
 * </ul>
 *
 * <p>计数器不存在或已过期时 {@code used} 为 0 —— 空闲的一分钟和不含该速率的套餐方案
 * 看起来都是这样。这些计数器只是参考性的：它们在这里只为运维视图而读取，绝不决定准入。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class LimitUsageService {

    private final LimitUsageRepository repository;
    private final PackageTokenRateLimitService packageTokenRateLimitService;
    private final PackageSecondRateLimitService packageSecondRateLimitService;
    private final DailyRequestLimitService dailyRequestLimitService;
    private final BlockedAdmissionService blockedAdmissionService;

    LimitUsageService(
            LimitUsageRepository repository,
            PackageTokenRateLimitService packageTokenRateLimitService,
            PackageSecondRateLimitService packageSecondRateLimitService,
            DailyRequestLimitService dailyRequestLimitService,
            BlockedAdmissionService blockedAdmissionService) {
        this.repository = repository;
        this.packageTokenRateLimitService = packageTokenRateLimitService;
        this.packageSecondRateLimitService = packageSecondRateLimitService;
        this.dailyRequestLimitService = dailyRequestLimitService;
        this.blockedAdmissionService = blockedAdmissionService;
    }

    Mono<LimitUsageView> usage(String rawPackageInstanceId) {
        UUID packageInstanceId = parse(rawPackageInstanceId);
        return repository.find(packageInstanceId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "package_instance_not_found")))
                .flatMap(this::assemble);
    }

    private Mono<LimitUsageView> assemble(LimitUsageRepository.PackageLimits limits) {
        PackageRateLimits rates = PackageRateLimits.of(
                limits.packageInstanceId(), limits.usageDimension(), limits.tokensPerMinute());
        RequestLimitService.PackageTokenLimit tokenLimit = rates.tokenLimit();
        RequestLimitService.PackageSecondLimit secondLimit = rates.secondLimit();
        RequestLimitService.PackageDailyLimit dailyLimit = RequestLimitService.PackageDailyLimit.of(
                limits.packageInstanceId(), limits.dailyRequestLimit());

        Mono<Long> perMinuteUsed = tokenLimit.enforced()
                ? packageTokenRateLimitService.used(tokenLimit, limits.userId())
                : secondLimit.enforced()
                        ? packageSecondRateLimitService.used(secondLimit, limits.userId())
                        : Mono.just(0L);
        Mono<Long> dailyUsed = dailyRequestLimitService.used(dailyLimit, limits.userId());
        Mono<Map<String, Long>> blocked =
                blockedAdmissionService.last24Hours(limits.packageInstanceId());

        return Mono.zip(perMinuteUsed, dailyUsed, blocked).map(values -> new LimitUsageView(
                limits.packageInstanceId(),
                rates.usageDimension(),
                new PerMinute(
                        tokenLimit.enforced()
                                ? tokenLimit.tokensPerMinute()
                                : secondLimit.enforced() ? secondLimit.secondsPerMinute() : null,
                        values.getT1()),
                new Daily(dailyLimit.dailyRequestLimit() == null
                        ? null : dailyLimit.dailyRequestLimit().longValue(), values.getT2()),
                values.getT3().values().stream().mapToLong(Long::longValue).sum(),
                new TreeMap<>(values.getT3())));
    }

    private UUID parse(String rawPackageInstanceId) {
        if (rawPackageInstanceId == null || rawPackageInstanceId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_package_instance_id");
        }
        try {
            return UUID.fromString(rawPackageInstanceId.trim());
        } catch (IllegalArgumentException malformed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_package_instance_id");
        }
    }

    record LimitUsageView(
            UUID packageInstanceId,
            String usageDimension,
            PerMinute perMinute,
            Daily daily,
            long blockedLast24h,
            Map<String, Long> blockedLast24HoursByReason) { }

    record PerMinute(Long limit, long used) { }

    record Daily(Long limit, long used) { }
}
