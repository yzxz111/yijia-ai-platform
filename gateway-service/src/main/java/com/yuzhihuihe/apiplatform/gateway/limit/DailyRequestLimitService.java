package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.LocalDate;
import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 在准入时强制执行的、按套餐计算的每日请求额度，也就是控制台以
 * {@code daily_request_limit} 出售的那一项。
 *
 * <p>它运行在授予分钟租约之处，因此一旦套餐用完当天所购买的额度，请求就绝不会到达一次
 * 付费的上游调用。计数器以套餐实例和上海日历日作为键，这正是平台其余部分使用的日期约定
 * （{@code WalletUsageDigestJob}、{@code UsageAnalyticsService}）。
 *
 * <p>失败策略，刻意与分钟限流器保持一致：Redis 不可用时降级到计数式的数据库查询，
 * 而任何使判定结果处于未知状态的情况都以 {@code 503 dependency_unavailable}
 * 失败即拒绝（fail-closed），而不是放行一个套餐可能并不拥有的请求。
 */
public interface DailyRequestLimitService {

    /**
     * 把一个被准入的请求计入套餐的当天用量并空完成，或者在额度用尽时以
     * {@code 429 daily_request_limit_exceeded} 报错。未售出每日上限的套餐会空完成，
     * 且不接触任何存储。
     */
    Mono<Void> admit(RequestLimitService.PackageDailyLimit limit, UUID userId);

    /**
     * 计数器今天当前的值，用于报表。当计数器不存在或已过期时返回 0，且永不失败：
     * 这是对参考性计数器的读取，而不是一项判定。
     */
    Mono<Long> used(RequestLimitService.PackageDailyLimit limit, UUID userId);

    static DailyRequestLimitService unlimited() {
        return new DailyRequestLimitService() {

            @Override
            public Mono<Void> admit(RequestLimitService.PackageDailyLimit limit, UUID userId) {
                return Mono.empty();
            }

            @Override
            public Mono<Long> used(RequestLimitService.PackageDailyLimit limit, UUID userId) {
                return Mono.just(0L);
            }
        };
    }

    interface Store {

        default Mono<Boolean> isAvailable() {
            return Mono.just(true);
        }

        /**
         * 当该请求可以被准入时返回 {@code true}。{@code day} 是请求所属的上海日历日；
         * {@code userId} 标识的是其已结算请求必须由降级后的存储来计数的客户。
         */
        Mono<Boolean> admit(RequestLimitService.PackageDailyLimit limit, UUID userId, LocalDate day);

        /** 计数器今天当前的值；当该存储没有可读取的计数器时为 0。 */
        default Mono<Long> used(
                RequestLimitService.PackageDailyLimit limit, UUID userId, LocalDate day) {
            return Mono.just(0L);
        }
    }
}
