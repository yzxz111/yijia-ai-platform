package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.Duration;
import java.util.UUID;

import reactor.core.publisher.Mono;

public interface RequestLimitService {

    Mono<LimitLease> acquire(LimitCommand command);

    Mono<LimitLease> acquirePublic(LimitCommand command);

    Mono<LimitLease> rebindChannel(LimitLease lease, UUID channelId);

    Mono<LimitLease> activateRoute(
            LimitLease lease, UUID channelId, int rpmLimit, int concurrencyLimit);

    Mono<Void> release(LimitLease lease);

    /**
     * 把一个已完成请求实际计入其套餐的积分交给已售出每分钟积分速率的准入可见计数器。
     *
     * <p>该上限限制的是消耗而不是请求，所以这里是唯一能知道真实数字的地方：它从已经服务完并
     * 已付费的请求的结算尾部调用。因此实现必须立即返回，绝不能延迟、失败或重试那次结算
     * —— 资金路径不依赖这个计数器，下一次准入也不得依赖它。
     */
    default void recordPackageConsumption(PackageTokenLimit limit, long chargedCredits) {
    }

    interface LimitStore {
        default Mono<Boolean> isAvailable() {
            return Mono.just(true);
        }

        Mono<LimitLease> acquire(EffectiveLimit command);

        default Mono<LimitLease> rebindChannel(LimitLease lease, UUID channelId) {
            if (lease != null && channelId != null && channelId.equals(lease.channelId())) {
                return Mono.just(lease);
            }
            return Mono.error(new UnsupportedOperationException("channel_rebind_not_supported"));
        }

        default Mono<LimitLease> activateRoute(
                LimitLease lease, UUID channelId, int rpmLimit, int concurrencyLimit) {
            return Mono.error(new UnsupportedOperationException("route_activation_not_supported"));
        }

        Mono<Void> release(LimitLease lease);
    }

    enum Store {
        REDIS, DATABASE
    }

    record Thresholds(int packageLimit, int apiKeyLimit, int userLimit, int channelLimit, int modelLimit) {
        public Thresholds {
            if (packageLimit <= 0 || apiKeyLimit <= 0 || userLimit <= 0
                    || channelLimit <= 0 || modelLimit <= 0) {
                throw new IllegalArgumentException("limit_threshold_must_be_positive");
            }
        }

        public static Thresholds all(int limit) {
            return new Thresholds(limit, limit, limit, limit, limit);
        }

        public int minimum() {
            return Math.min(packageLimit,
                    Math.min(apiKeyLimit, Math.min(userLimit, Math.min(channelLimit, modelLimit))));
        }
    }

    /**
     * 客户为一个套餐实例购买的每日请求配额
     * （{@code gateway.package_instance.daily_request_limit}，控制台把它标注为每日请求上限）。
     * {@code dailyRequestLimit == null} 表示该套餐不售卖每日上限，凡是把这个字段留空的套餐都是这种情况。
     */
    record PackageDailyLimit(UUID packageInstanceId, Integer dailyRequestLimit) {

        private static final PackageDailyLimit UNLIMITED = new PackageDailyLimit(null, null);

        public PackageDailyLimit {
            if (dailyRequestLimit != null
                    && (dailyRequestLimit <= 0 || packageInstanceId == null)) {
                throw new IllegalArgumentException("invalid_package_daily_limit");
            }
        }

        public static PackageDailyLimit unlimited() {
            return UNLIMITED;
        }

        public static PackageDailyLimit of(UUID packageInstanceId, Integer dailyRequestLimit) {
            return dailyRequestLimit == null ? UNLIMITED : new PackageDailyLimit(
                    packageInstanceId, dailyRequestLimit);
        }

        public static PackageDailyLimit of(UUID packageInstanceId, int dailyRequestLimit) {
            return new PackageDailyLimit(packageInstanceId, dailyRequestLimit);
        }

        public boolean enforced() {
            return dailyRequestLimit != null;
        }
    }

    /**
     * 客户为一个套餐实例购买的每分钟积分速率
     * （{@code gateway.package_instance.tokens_per_minute}，控制台把它标注为 积分/分钟）。
     * {@code tokensPerMinute == null} 表示该套餐不售卖这一速率，凡是不带套餐计费的请求都是这种情况。
     */
    record PackageTokenLimit(UUID packageInstanceId, Long tokensPerMinute) {

        private static final PackageTokenLimit UNLIMITED = new PackageTokenLimit(null, null);

        public PackageTokenLimit {
            if (tokensPerMinute != null && tokensPerMinute > 0 && packageInstanceId == null) {
                throw new IllegalArgumentException("invalid_package_token_limit");
            }
        }

        public static PackageTokenLimit unlimited() {
            return UNLIMITED;
        }

        /**
         * 读取已售出的字段。缺失、零或负数都表示该套餐不售卖每分钟积分速率，此时网关的行为
         * 与该上限存在之前完全一致：没有计数器、没有准入检查、也没有拒绝。
         */
        public static PackageTokenLimit of(UUID packageInstanceId, Long tokensPerMinute) {
            return packageInstanceId == null || tokensPerMinute == null || tokensPerMinute <= 0
                    ? UNLIMITED
                    : new PackageTokenLimit(packageInstanceId, tokensPerMinute);
        }

        public boolean enforced() {
            return tokensPerMinute != null;
        }
    }

    /**
     * 生成套餐售卖的每分钟**单位**速率：秒
     * （把 {@code gateway.package_instance.tokens_per_minute} 按套餐自身的
     * {@code VIDEO_SECOND} 或 {@code AUDIO_SECOND} 维度来读，控制台把它标注为 SPM），
     * 或张（按 {@code IMAGE} 维度来读，控制台把它标注为 IPM）。
     *
     * <p>它是 {@link PackageTokenLimit} 的生成侧孪生体，之所以存在是因为一列不能承载两种单位：
     * {@code package_instance.tokens_per_minute} 是带维度的，与 {@code total_token_quota} 完全
     * 一样，所以在 {@code VIDEO_SECOND} 实例上这个数字就是每分钟多少秒、在 {@code AUDIO_SECOND}
     * 上同样是多少秒、在 {@code IMAGE} 实例上就是每分钟多少张，三者都绝不能计入 token
     * 计数器（反向也一样）。
     * {@link PackageRateLimits} 是唯一决定一个已售出的数字属于哪一边的地方。
     * {@code secondsPerMinute == null} 表示该套餐不售卖该单位的速率。
     *
     * @param usageDimension 该上限所用的单位：{@code VIDEO_SECOND} 与
     *        {@code AUDIO_SECOND} 是秒，{@code IMAGE} 是张。
     *        它只影响措辞与错误码，**不影响计数**——计数是套餐级的：
     *        {@code tokens_per_minute} 一个套餐一列，而两条计数器（Redis 与降级路径）
     *        都按套餐实例汇总该套餐**所有**按单位计费维度的用量。因此一个混合套餐
     *        （视频秒 + 音频秒）的秒/分钟上限是**两者之和**的上限；把它理解成
     *        "每个维度各自一个"会凭空把售出的上限翻倍。
     */
    record PackageSecondLimit(
            UUID packageInstanceId, String usageDimension, Long secondsPerMinute) {

        private static final PackageSecondLimit UNLIMITED = new PackageSecondLimit(
                null, PackageRateLimits.VIDEO_SECOND_DIMENSION, null);

        public PackageSecondLimit {
            if (secondsPerMinute != null && secondsPerMinute > 0 && packageInstanceId == null) {
                throw new IllegalArgumentException("invalid_package_second_limit");
            }
            usageDimension = usageDimension == null || usageDimension.isBlank()
                    ? PackageRateLimits.VIDEO_SECOND_DIMENSION
                    : usageDimension;
        }

        public static PackageSecondLimit unlimited() {
            return UNLIMITED;
        }

        /** 每秒速率的形式。缺失、零或负数都表示该套餐不售卖每分钟秒数速率。 */
        public static PackageSecondLimit of(UUID packageInstanceId, Long secondsPerMinute) {
            return of(packageInstanceId, PackageRateLimits.VIDEO_SECOND_DIMENSION, secondsPerMinute);
        }

        /** 缺失、零或负数都表示该套餐不售卖该维度的每分钟单位速率。 */
        public static PackageSecondLimit of(
                UUID packageInstanceId, String usageDimension, Long secondsPerMinute) {
            return packageInstanceId == null || secondsPerMinute == null || secondsPerMinute <= 0
                    ? UNLIMITED
                    : new PackageSecondLimit(packageInstanceId, usageDimension, secondsPerMinute);
        }

        public boolean enforced() {
            return secondsPerMinute != null;
        }
    }

    record LimitCommand(
            UUID apiKeyId,
            UUID userId,
            String modelCode,
            UUID channelId,
            Thresholds rpm,
            Thresholds concurrency,
            Duration leaseTtl,
            PackageDailyLimit packageDailyLimit,
            PackageTokenLimit packageTokenLimit) {

        public LimitCommand {
            if (apiKeyId == null || userId == null || modelCode == null || modelCode.isBlank()
                    || modelCode.length() > 128 || channelId == null || rpm == null || concurrency == null
                    || leaseTtl == null || leaseTtl.isZero() || leaseTtl.isNegative()
                    || packageDailyLimit == null || packageTokenLimit == null) {
                throw new IllegalArgumentException("invalid_limit_command");
            }
        }

        public LimitCommand(
                UUID apiKeyId,
                UUID userId,
                String modelCode,
                UUID channelId,
                Thresholds rpm,
                Thresholds concurrency,
                Duration leaseTtl,
                PackageDailyLimit packageDailyLimit) {
            this(apiKeyId, userId, modelCode, channelId, rpm, concurrency, leaseTtl,
                    packageDailyLimit, PackageTokenLimit.unlimited());
        }

        public LimitCommand(
                UUID apiKeyId,
                UUID userId,
                String modelCode,
                UUID channelId,
                Thresholds rpm,
                Thresholds concurrency,
                Duration leaseTtl) {
            this(apiKeyId, userId, modelCode, channelId, rpm, concurrency, leaseTtl,
                    PackageDailyLimit.unlimited(), PackageTokenLimit.unlimited());
        }

        public EffectiveLimit effective() {
            return new EffectiveLimit(apiKeyId, userId, modelCode, channelId,
                    rpm.minimum(), concurrency.minimum(), leaseTtl);
        }
    }

    record EffectiveLimit(
            UUID apiKeyId,
            UUID userId,
            String modelCode,
            UUID channelId,
            int rpmLimit,
            int concurrencyLimit,
            Duration leaseTtl) {
    }

    record LimitLease(
            UUID leaseId,
            UUID apiKeyId,
            UUID userId,
            String modelCode,
            UUID channelId,
            int rpmLimit,
            int concurrencyLimit,
            Store store) {
    }
}
