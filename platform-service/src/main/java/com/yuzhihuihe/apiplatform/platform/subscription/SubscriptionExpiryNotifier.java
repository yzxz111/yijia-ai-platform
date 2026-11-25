package com.yuzhihuihe.apiplatform.platform.subscription;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 到期提醒的分流（设计 §9.4）。
 *
 * <p>既有的 T-3 提醒对订阅用户是**错误的文案**：「您的套餐即将到期」而不是
 * 「将于 X 日自动续费 ¥Y」。两者对用户的含义完全相反——一个要他做点什么，
 * 一个告诉他不用做任何事。
 *
 * <p>分流方式刻意做成「调用点上换一条路径」而不是「改 {@code notifyPackageExpiry}
 * 的分支语义」：后者会影响所有非订阅用户，而这条链路上任何回归都会让**所有人**
 * 收不到到期提醒。既有服务因此保持一字未改。
 *
 * <p>订阅用户走 {@code SUBSCRIPTION_RENEWAL_DUE}，它是注册表里唯一带
 * {@code mandatorySms = true} 的类型——合规要求扣款前通知不可被用户关闭。
 */
@Service
public class SubscriptionExpiryNotifier {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final SubscriptionRepository subscriptions;
    private final SubscriptionPlanReader plans;
    private final SubscriptionNotifications notifications;
    private final CommercialNotificationService commercial;
    private final Clock clock;

    SubscriptionExpiryNotifier(
            SubscriptionRepository subscriptions,
            SubscriptionPlanReader plans,
            SubscriptionNotifications notifications,
            CommercialNotificationService commercial,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.notifications = notifications;
        this.commercial = commercial;
        this.clock = clock;
    }

    /**
     * 按用户是否持有订阅分流；返回是否走了订阅路径。
     *
     * <p>用「到期日最近的活跃订阅」而不是「按 packageCode 精确匹配」：订阅绑的是计划，
     * 而提醒信号带来的是套餐实例的 {@code packageCode}，两者在计划换版本后可能对不上，
     * 精确匹配会让续费提醒在换版本静默失效。
     */
    @Transactional
    public boolean notifyExpiry(
            UUID tenantId, UUID userId, UUID packageInstanceId, Instant expiresAt) {
        SubscriptionRepository.SubscriptionRow subscription =
                subscriptions.findActiveForUser(tenantId, userId).stream().findFirst().orElse(null);
        if (subscription == null) {
            return commercial.notifyPackageExpiry(tenantId, userId, packageInstanceId, expiresAt);
        }
        String planName = plans.readPlanName(subscription.packagePlanId());
        long amountFen = nextChargeAmount(subscription);
        notifications.notifyRenewalDue(subscription, planName, yuan(amountFen));
        return true;
    }

    /**
     * 下次将扣多少。
     *
     * <p>取**当前 PUBLISHED 版本的价格**而不是订阅行上的上次扣款金额：用户收到的
     * 「将扣 ¥X」必须是他实际会被扣的数。若价格已调整且尚待确认，这里展示新价，
     * 而实际扣款会被编排层暂缓——宁可提前告知价格变化，也不要报一个过期的数字。
     * 取不到时回退到上次扣款金额（首期刚开通时就是这个值）。
     */
    private long nextChargeAmount(SubscriptionRepository.SubscriptionRow subscription) {
        UUID publishedVersionId = plans.readPublishedVersionId(subscription.packagePlanId());
        if (publishedVersionId != null) {
            SubscriptionPlanReader.PlanPrice price = plans.readPrice(publishedVersionId);
            if (price != null && price.amount() > 0) {
                return price.amount();
            }
        }
        return subscription.lastChargedAmount() == null ? 0L : subscription.lastChargedAmount();
    }

    static String yuan(long amountFen) {
        return String.format("%.2f", amountFen / 100.0);
    }

    static String date(Instant instant) {
        return instant == null
                ? null
                : instant.atZone(SHANGHAI).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
