package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.Instant;
import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 在准入阶段强制执行套餐作为 {@code tokens_per_minute} 售出的每分钟积分速率
 * （控制台将其显示为 积分/分钟）。
 *
 * <p>它不是请求数上限：它限制的是从套餐已售额度中消耗的积分。这些积分只有在请求结算之后才能得知，
 * 因此本服务是一个循环的两半——{@link #record} 写入一个已结束请求实际被收取的积分，{@link #admit}
 * 在当前分钟内被收取的积分达到已售速率后拒绝新请求。两半判定分钟的方式都与分钟请求限流器一致：
 * 取自 Redis 服务器时钟，截断到纪元分钟（{@code RedisRequestLimitStore} 对同一个窗口使用
 * {@code math.floor(TIME()/60)}）。由于 Asia/Shanghai 相对 UTC 是整小时偏移，它同时也是上海墙上时钟
 * 的分钟，所以这里不需要自己的时区——不像每日请求上限，其日边界确实取决于时区。
 *
 * <p>向客户承诺的、也是本实现所做到的：在一个时钟分钟内，对套餐实例收取的积分不超过
 * {@code tokens_per_minute}，其中一个积分就是从套餐已售额度中取出的一枚 token（与
 * {@code total_token_quota} 同一单位，也是用量账本记为 {@code package_settled_tokens} 的同一个数字）。
 * 请求溢出到钱包的积分由钱包支付，不计入套餐的收费。与分钟请求窗口一样，这是固定窗口：跨越分钟边界的
 * 突发在这条边界两侧最多可消耗宣称数量的两倍。关于该上限无法承诺什么，见
 * {@link DefaultPackageTokenRateLimitService}，因为一个请求的收费只有在它被服务之后才能得知。
 */
public interface PackageTokenRateLimitService {

    /**
     * 当套餐在当前分钟内仍可消耗积分时以空完成，当已售速率已被用尽时以
     * {@code 429 tokens_per_minute_exceeded} 报错。没有已售速率的套餐不触碰任何存储，直接以空完成。
     */
    Mono<Void> admit(RequestLimitService.PackageTokenLimit limit, UUID userId);

    /**
     * 记录一个已结束请求对其套餐被收取的积分。立即返回且从不传播失败：见
     * {@link RequestLimitService#recordPackageConsumption}。
     */
    void record(RequestLimitService.PackageTokenLimit limit, long chargedCredits);

    /**
     * 用于上报的该分钟计数器的当前值。计数器不存在或已过期时返回 0，且永不失败：这是对一个咨询性
     * 计数器的读取，而不是一个决策。
     */
    Mono<Long> used(RequestLimitService.PackageTokenLimit limit, UUID userId);

    PackageTokenRateLimitService UNLIMITED = new PackageTokenRateLimitService() {

        @Override
        public Mono<Void> admit(RequestLimitService.PackageTokenLimit limit, UUID userId) {
            return Mono.empty();
        }

        @Override
        public void record(RequestLimitService.PackageTokenLimit limit, long chargedCredits) {
        }

        @Override
        public Mono<Long> used(RequestLimitService.PackageTokenLimit limit, UUID userId) {
            return Mono.just(0L);
        }
    };

    static PackageTokenRateLimitService unlimited() {
        return UNLIMITED;
    }

    interface Store {

        default Mono<Boolean> isAvailable() {
            return Mono.just(true);
        }

        /**
         * 当该请求可以获准通过时返回 {@code true}。{@code minute} 是该请求所属分钟的开始时刻；
         * {@code userId} 标识降级存储必须统计其已结算积分的客户。主存储改为依据自己的时钟判定窗口，
         * 因此忽略 {@code minute}。
         */
        Mono<Boolean> admit(RequestLimitService.PackageTokenLimit limit, UUID userId, Instant minute);

        /**
         * 把一个已结束请求被收取的积分加到计数器上。降级存储直接从已结算的用量行本身推导出它的计数器，
         * 因此没有任何东西需要写入。
         */
        default Mono<Void> record(RequestLimitService.PackageTokenLimit limit, long chargedCredits) {
            return Mono.empty();
        }

        /** 计数器的当前值；当该存储不保存任何可读取的计数器时为 0。 */
        default Mono<Long> used(
                RequestLimitService.PackageTokenLimit limit, UUID userId, Instant minute) {
            return Mono.just(0L);
        }
    }
}
