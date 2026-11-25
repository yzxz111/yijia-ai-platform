package com.yuzhihuihe.apiplatform.gateway.limit;

import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 当调用方只有实例 id 时，套餐实例已售每分钟速率的来源——即视频路径，它不读取任何目录行。
 *
 * <p>速率由 {@link PackageRateLimits} 按套餐自身的维度读取，因此以秒计价的套餐的数字绝不会
 * 被误认为 token 速率，反之亦然。未知实例解析为没有已售速率：套餐是否存在是配额预留的问题，
 * 而本来源不会为它读不到的套餐凭空造出一个上限。
 */
@FunctionalInterface
public interface PackageRateSource {

    Mono<PackageRateLimits> rateFor(UUID packageInstanceId);

    static PackageRateSource unlimited() {
        return packageInstanceId -> Mono.just(PackageRateLimits.unlimited());
    }
}
