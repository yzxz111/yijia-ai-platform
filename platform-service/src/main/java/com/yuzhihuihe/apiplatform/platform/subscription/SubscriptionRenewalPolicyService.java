package com.yuzhihuihe.apiplatform.platform.subscription;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 续费专用的资格判定。
 *
 * <p><b>本类刻意与 {@code PackagePurchasePolicyService} 平行，而不是给它加开关。</b>
 * 既有的购买策略对自动续费是硬阻断，且两条都无法通过调参绕过：
 *
 * <ul>
 *   <li>{@code hasUnconsumedPackage} 在「当期还有剩余额度」时抛
 *       {@code package_quota_remaining}，且**完全不看 {@code expires_at}**；
 *   <li>{@code MAX_PURCHASES_PER_PACKAGE = 3} 让第 4 个月必然抛
 *       {@code package_purchase_limit_reached}。
 * </ul>
 *
 * <p>续费必须绕过这两条——「按月买断一段时间」正是订阅的语义，用户当月的额度没用完
 * 不构成下一期不能开始续费的理由。但绕过必须是**有边界**的：本类把边界写成
 * 一张显式清单（见 {@link #evaluate}），而不是「续费什么都不检查」。
 *
 * <p>反向约束同样重要：一次性购买路径一字未改，仍然按原语义拒绝。两者通过**调用点**
 * 区分，不通过参数开关区分——开关会让「某条路径忘了传参」变成静默放行。
 */
@Service
public class SubscriptionRenewalPolicyService {

    /** 默认的累计续期上限，防跑飞；与 {@code PackagePurchasePolicyService} 的 3 次上限无关。 */
    static final int DEFAULT_MAX_RENEWALS = 36;

    private final SubscriptionRepository subscriptions;
    private final SubscriptionPlanReader plans;

    SubscriptionRenewalPolicyService(
            SubscriptionRepository subscriptions,
            SubscriptionPlanReader plans) {
        this.subscriptions = subscriptions;
        this.plans = plans;
    }

    /**
     * 判定某个账期此刻能否扣款。
     *
     * <p>返回 {@link Decision} 而不是抛异常，是因为「跳过」与「拒绝」在业务上完全不同：
     * 跳过要记 {@code last_skip_reason}、不计失败次数、不发通知（设计 §7.5），
     * 而「余额不足」这类失败要进退避重试并通知用户。把两者压成同一种信号，
     * 后台的失败率告警就会长期红着，运维随之开始忽略它。
     */
    @Transactional
    public Decision evaluate(
            SubscriptionRepository.SubscriptionRow subscription,
            UUID packagePlanVersionId,
            boolean priceIncreaseRequiresConfirmation,
            int maxRenewals,
            Instant now) {
        if (!subscription.autoRenew() || subscription.cancelAtPeriodEnd()) {
            return Decision.skip(SkipReason.CANCELED_BY_USER);
        }
        if (!"ACTIVE".equals(subscription.status()) && !"PAST_DUE".equals(subscription.status())) {
            return Decision.skip(SkipReason.SUBSCRIPTION_NOT_OPEN);
        }
        if (!subscriptions.hasActiveUser(subscription.tenantId(), subscription.userId())) {
            return Decision.skip(SkipReason.ACCOUNT_NOT_ACTIVE);
        }
        // 累计上限：TERMINATED 由编排层执行，因此这里返回的是「拒绝」而不是「跳过」。
        if (subscription.renewalCount() >= maxRenewals) {
            return Decision.limitReached(subscription.renewalCount(), maxRenewals);
        }
        // 这里**刻意不检查「本订阅是否已有未激活账期」**。
        //
        // 那个检查看似是队列深度守卫，实际上是错的：首期账期在开通时就是 CHARGED，
        // 而它的套餐实例要到激活时才由网关创建，因此在续费发生的整个窗口里
        // 「最近一个账期已扣款但没有实例」是**正常状态**，不是「排不上队」。
        // 拿它当守卫会让每一次续费都跳过——续费功能直接不工作。
        //
        // 真正的队列深度不变量由订阅级租约保证（V71 的 ck_subscription_claim）：
        // 一个订阅任何时刻只有一个租约，因此不可能有两个在途扣款。
        // 「扣款成功但实例长期排不上队」这个真实的坏状态由编排层的排队超时兜底处理
        // （SubscriptionRenewalJob.detectStuckQueue），那里的判定依据是扣款时间，
        // 而不是「有没有实例」。
        //
        // 同维度单活跃实例守卫（设计 §5.8）。**必须按维度判定**：V80 已把唯一性收窄为
        // `UNIQUE (user_id, usage_dimension)`，跨维度并存是既合法又预期的状态，因此
        // 「有任意活跃实例」不再等价于「该维度已有活跃实例」。
        // 不区分来源：同一维度下用户可能刚手工买过同类套餐，跳过是**正确**行为——他已经付过钱了。
        SubscriptionPlanReader.PlanPrice price = plans.readPrice(packagePlanVersionId);
        if (price == null) {
            return Decision.skip(SkipReason.PLAN_UNAVAILABLE);
        }
        if (subscriptions.hasActivePackageInstance(
                subscription.tenantId(), subscription.userId(), price.usageDimension())) {
            return Decision.skip(SkipReason.ACTIVE_PACKAGE_PRESENT);
        }
        if (price.amount() <= 0) {
            // 零价套餐不该走续费：它应当由免费领取通道处理。
            return Decision.skip(SkipReason.PLAN_UNAVAILABLE);
        }
        // 涨价必须先获得用户确认，不得静默按新价扣款（合规要求，设计 §7.3）。
        //
        // 比较基准取「上次实扣」与「用户已同意支付」两者中较大的一个：
        //   - 未确认过涨价时 acceptedPriceAmount 为 null，基准就是 lastChargedAmount，行为与从前一致；
        //   - 用户确认之后基准抬到已接受价，因此不会把同一个涨价再判一次。
        //     **这一步是必需的**：少了它，确认只是清了 pending 字段，调度下一轮仍拿旧的实扣金额
        //     比较、仍判定为涨价，于是「确认了却永远不扣款」——实测正是踩到这个死循环。
        //   - 若管理员在确认之后再涨价，基准仍低于新价，会正确地**再次**要求确认，
        //     因此「确认一次 = 永久放弃价格保护」这个坏性质不存在。
        Long baseline = subscription.lastChargedAmount();
        if (subscription.acceptedPriceAmount() != null
                && (baseline == null || subscription.acceptedPriceAmount() > baseline)) {
            baseline = subscription.acceptedPriceAmount();
        }
        if (priceIncreaseRequiresConfirmation
                && baseline != null
                && price.amount() > baseline) {
            return Decision.priceChangeRequiresConfirmation(baseline, price.amount());
        }
        return Decision.allow(price.amount(), price.currency());
    }

    public enum SkipReason {
        CANCELED_BY_USER,
        SUBSCRIPTION_NOT_OPEN,
        ACCOUNT_NOT_ACTIVE,
        ACTIVE_PACKAGE_PRESENT,
        PLAN_UNAVAILABLE
    }

    /** 判定结果。三者互斥：{@code allowed} 才能扣款，{@code skipReason} 与 {@code failure} 都表示不扣。 */
    public record Decision(
            boolean allowed,
            SkipReason skipReason,
            Long amount,
            String currency,
            String failureReason,
            Long previousAmount,
            Long newAmount) {

        static Decision allow(long amount, String currency) {
            return new Decision(true, null, amount, currency, null, null, null);
        }

        static Decision skip(SkipReason reason) {
            return new Decision(false, reason, null, null, null, null, null);
        }

        static Decision limitReached(int renewalCount, int maxRenewals) {
            return new Decision(false, null, null, null,
                    "subscription_renewal_limit_reached", (long) renewalCount, (long) maxRenewals);
        }

        static Decision priceChangeRequiresConfirmation(long previous, long updated) {
            return new Decision(false, null, null, null,
                    "subscription_price_changed", previous, updated);
        }
    }
}
