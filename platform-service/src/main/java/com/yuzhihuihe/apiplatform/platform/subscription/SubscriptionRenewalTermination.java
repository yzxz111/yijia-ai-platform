package com.yuzhihuihe.apiplatform.platform.subscription;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 退款成功后的订阅收尾（设计 §10）。
 *
 * <p><b>为什么需要它。</b>设计 §10 第 447-449 行要求「退款成功后必须停止扣下一期」，但
 * {@code platform/refund/**} 里此前没有任何订阅引用。实测后果：退款被**批准**之后订阅仍是
 * {@code ACTIVE} / {@code auto_renew=true}，于是**用户既拿到退款、又会被继续扣下一期**。
 *
 * <p><b>为什么放在订阅包、以窄接口的形式被退款调用</b>，而不是让退款直接操作订阅表：
 * 退款是资金动作、订阅是权益动作。把两者的接触面收成一个只有单个方法、语义明确的类，
 * 「退款会改动订阅」这件事就只有一处可读、可测、可审计；退款包也不必了解订阅的状态机。
 *
 * <p><b>只对订阅来源的订单生效。</b>归属通过账期行的 {@code grant_request_id} 反查
 * （{@link SubscriptionCycleRepository#findSubscriptionIdByGrant}）：查不到就说明这笔发放不是
 * 订阅产生的，直接返回 false。**一次性购买因此完全不受影响**——这是本类最重要的一条边界。
 */
@Service
public class SubscriptionRenewalTermination {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionRenewalTermination.class);

    private final SubscriptionRepository subscriptions;
    private final SubscriptionCycleRepository cycles;

    SubscriptionRenewalTermination(
            SubscriptionRepository subscriptions, SubscriptionCycleRepository cycles) {
        this.subscriptions = subscriptions;
        this.cycles = cycles;
    }

    /**
     * 若该发放属于某份订阅，则终止它，使其不再扣款。
     *
     * <p>幂等：重复调用第二次影响 0 行，返回 false，不抛异常——退款完成事件本身可能被重投。
     *
     * @param grantRequestId 被撤销的那次发放；为 null 时无操作
     * @return 是否真的把一份活跃订阅置为 {@code TERMINATED}
     */
    public boolean terminateIfSubscriptionGrant(UUID grantRequestId) {
        Optional<UUID> subscriptionId = cycles.findSubscriptionIdByGrant(grantRequestId);
        if (subscriptionId.isEmpty()) {
            // 不是订阅产生的发放（一次性购买），或账期行尚未写入。两种情况下都**不动任何东西**。
            return false;
        }
        boolean terminated = subscriptions.terminateForRefund(subscriptionId.get());
        if (terminated) {
            LOGGER.atInfo()
                    .addKeyValue("subscriptionId", subscriptionId.get())
                    .addKeyValue("grantRequestId", grantRequestId)
                    .addKeyValue("reason", "refund_terminated")
                    .log("subscription terminated because its order was refunded");
        }
        return terminated;
    }
}
