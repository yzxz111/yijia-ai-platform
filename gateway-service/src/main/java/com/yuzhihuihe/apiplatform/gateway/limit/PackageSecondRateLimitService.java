package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.Instant;
import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 在准入阶段强制执行视频套餐所售卖的每分钟秒数速率。
 *
 * <p>它是 {@link PackageTokenRateLimitService} 的秒数孪生体，存在的理由与
 * {@link PackageRateLimits} 存在的理由一样：{@code VIDEO_SECOND} 实例上的
 * {@code package_instance.tokens_per_minute} 是每分钟可生成的秒数（控制台把它标为 {@code SPM}），
 * 视频生成必须以这个秒数为界——而不是用一个 token 计数器来界定，也不是把这个秒数
 * 当作 token 来计费。
 *
 * <p>与积分速率不同，这里的准入是按套餐实例询问的，而不是按一个已经读到的速率：
 * 视频路径上没有承载已售数字的目录行，因此本服务自行解析套餐自身的维度与速率
 * （{@link PackageRateSource}），并且仅当套餐方案确实售卖了秒数方向时才强制执行该方向。
 *
 * <p>这个循环的形态与积分速率相同，也同样有着如实的局限：秒数只有在一次生成结算之后才能得知，
 * 所以这是一个后付费上限。当当前分钟内已经计费的秒数达到已售速率时，请求会被拒绝；
 * 因此当一次生成在桶被打满时已经在途，它会按最终实际用掉的秒数被准入（受任务 TTL 与
 * 套餐自身并发度的约束），而一分钟内的第一个请求总是被准入。{@code QuotaService}
 * 中的每请求秒数配额仍然是单次生成永远不能超过的界限。
 *
 * <p>失败策略与积分速率一致：当决策无法做出时，它以 {@code 503 dependency_unavailable}
 * 失败即拒绝（fail-closed），而不是准入一次该套餐可能并不拥有的生成。
 */
public interface PackageSecondRateLimitService {

    /**
     * 当套餐在当前分钟内仍可生成秒数时以空完成；当已售速率已被用尽时，以
     * {@code 429 seconds_per_minute_exceeded} 报错。不售卖秒数速率的套餐——包括完全没有
     * 套餐实例的套餐——不触碰任何存储即以空完成。
     */
    Mono<Void> admit(UUID packageInstanceId, UUID userId);

    /**
     * 记录一次已结束的生成实际对其套餐被收取的秒数。立即返回且从不传播失败：它运行在一个已经提交的
     * 结算的尾部，资金路径绝不能依赖这个计数器。
     */
    void record(UUID packageInstanceId, long chargedSeconds);

    /**
     * 用于上报的该分钟计数器的当前值。计数器不存在或已过期时返回 0，且永不失败：这是对一个咨询性
     * 计数器的读取，而不是一个决策。
     */
    Mono<Long> used(RequestLimitService.PackageSecondLimit limit, UUID userId);

    PackageSecondRateLimitService UNLIMITED = new PackageSecondRateLimitService() {

        @Override
        public Mono<Void> admit(UUID packageInstanceId, UUID userId) {
            return Mono.empty();
        }

        @Override
        public void record(UUID packageInstanceId, long chargedSeconds) {
        }

        @Override
        public Mono<Long> used(RequestLimitService.PackageSecondLimit limit, UUID userId) {
            return Mono.just(0L);
        }
    };

    static PackageSecondRateLimitService unlimited() {
        return UNLIMITED;
    }

    interface Store {

        default Mono<Boolean> isAvailable() {
            return Mono.just(true);
        }

        /**
         * 当这次生成可以获准通过时返回 {@code true}。{@code minute} 是它所属分钟的开始时刻；
         * {@code userId} 标识降级存储必须统计其已结算秒数的客户。主存储改为依据自己的时钟判定窗口，
         * 因此忽略 {@code minute}。
         */
        Mono<Boolean> admit(RequestLimitService.PackageSecondLimit limit, UUID userId, Instant minute);

        /**
         * 把一次已结束的生成被收取的秒数加到计数器上。降级存储直接从已结算的用量行本身推导出它的
         * 计数器，因此没有任何东西需要写入。
         */
        default Mono<Void> record(UUID packageInstanceId, long chargedSeconds) {
            return Mono.empty();
        }

        /** 计数器的当前值；当该存储不保存任何可读取的计数器时为 0。 */
        default Mono<Long> used(
                RequestLimitService.PackageSecondLimit limit, UUID userId, Instant minute) {
            return Mono.just(0L);
        }
    }
}
