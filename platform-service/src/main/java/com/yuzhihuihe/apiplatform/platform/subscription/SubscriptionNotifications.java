package com.yuzhihuihe.apiplatform.platform.subscription;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.notification.NotificationEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 自动续费的通知（设计 §9.1）。
 *
 * <p><b>扣款成功与周期开始是两条通知，不能合并。</b>扣款成功时新实例是 {@code QUEUED}、
 * {@code expires_at} 为 NULL（设计 §5.5 / §5.7.1 H4），因此那个时刻**算不出**有效期；
 * 有效期只有实例真正激活后才知道。
 *
 * <p>{@code SUBSCRIPTION_RENEWAL_DUE}（扣款前预告）在注册表里带
 * {@code mandatorySms = true}，因此即使用户关掉了短信偏好、即使处于免打扰时段，
 * 它仍然会发出——这是合规要求，也是本功能唯一一条不可关闭的通知。
 * 其余订阅通知都尊重用户偏好。
 *
 * <p>幂等由 {@code uq_notification_event_key} 在数据库层保证：这里只负责生成稳定的
 * {@code event_key}（沿用仓库既有约定 {@code <domain>:<id>[:<state>]}），
 * 不靠应用层判断「是不是已经发过」。
 */
@Service
public class SubscriptionNotifications {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionNotifications.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final NotificationEventService events;
    private final SubscriptionCycleRepository cycles;
    private final Clock clock;

    SubscriptionNotifications(
            NotificationEventService events,
            SubscriptionCycleRepository cycles,
            Clock clock) {
        this.events = events;
        this.cycles = cycles;
        this.clock = clock;
    }

    /**
     * 按本轮结果发通知。
     *
     * <p>刻意不在这里发 {@code SUBSCRIPTION_RENEWAL_DUE}：扣款预告属于「到期前 N 天」的
     * 时间驱动通知，由既有的到期提醒链路分流（设计 §9.4），与调度结果无关。
     *
     * <p><b>{@code REQUIRES_NEW} 不是可选项。</b>下面的 try-catch 会吞掉异常，因此如果本方法
     * 加入调用方的事务（默认的 {@code REQUIRED}），任何一条 INSERT 失败都会把**调用方**的事务
     * 标记 rollback-only；异常虽然被吞了，调用方提交时仍会被 Spring 静默回滚——扣款状态与失败
     * 原因一起消失，而租约留在库里。
     *
     * <p>实测证据（2026-09-24，`SCENARIO H` 后台重试失败账期）：日志出现成对的
     * {@code 409 CONFLICT "notification_event_idempotency_mismatch"} 与
     * {@code UnexpectedRollbackException: Transaction silently rolled back because it has been
     * marked as rollback-only}，{@code outcome=UNEXPECTED}——这一次续费尝试什么都没做成。
     * {@code SubscriptionRenewalJob} 原先的注释声称「通知走自己的事务」，但它传入的
     * {@code TransactionTemplate} 用的是默认传播行为，所以那句话当时并不成立。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void report(
            SubscriptionRepository.SubscriptionRow subscription,
            SubscriptionRenewalJob.CycleOutcome outcome,
            Instant now) {
        try {
            switch (outcome) {
                case RENEWED -> record(
                        "renewal:" + subscription.id() + ":" + subscription.renewalCount() + ":charged",
                        "SUBSCRIPTION_RENEWED", subscription.tenantId(), subscription.userId(),
                        Map.of("payMoney", yuan(amountOrZero(subscription))));
                // 键里同时含 consecutiveFailures 与 nextChargeAt：**只用 consecutiveFailures
                // 会碰撞**。后台重试（AdminSubscriptionRepository.reopenForRetry）不重置该计数，
                // 而 recordFailure 只在记账时 +1，于是「同一账期先失败 → 人工重试 → 再失败」会
                // 生成同一个键；而 NotificationEventService 的 sameIdentity 比对包含 occurredAt，
                // 同键不同时刻必然抛 409。实测证据：`SCENARIO H` 日志里的
                // `notification_event_idempotency_mismatch`，栈顶是 replayAuthoritative。
                // nextChargeAt 在每次失败后都会前移，因此它足以把不同轮次区分开。
                case FAILED, EXPIRED -> record(
                        "renewal:" + subscription.id() + ":failed:"
                                + subscription.consecutiveFailures() + ":" + subscription.nextChargeAt(),
                        "SUBSCRIPTION_RENEWAL_FAILED", subscription.tenantId(), subscription.userId(),
                        Map.of(
                                "failReason", failureText(outcome, subscription),
                                "retryDate", date(subscription.nextChargeAt())));
                case DEFERRED -> record(
                        "subscription:" + subscription.id() + ":price-changed",
                        "SUBSCRIPTION_PRICE_CHANGED", subscription.tenantId(), subscription.userId(),
                        Map.of("oldMoney", yuan(amountOrZero(subscription))));
                case STUCK_QUEUED -> LOGGER.atError()
                        .addKeyValue("subscriptionId", subscription.id())
                        .addKeyValue("outcome", "STUCK_QUEUED")
                        .log("subscription renewal charged but the entitlement never activated");
                case LIMIT_REACHED -> LOGGER.atWarn()
                        .addKeyValue("subscriptionId", subscription.id())
                        .addKeyValue("outcome", "LIMIT_REACHED")
                        .log("subscription reached its renewal limit");
                // 跳过不发通知：它在正常运营中频繁发生（用户提前手工续费就是这种情况），
                // 每跳过一次就打扰一次用户是不可接受的。
                case SKIPPED -> { }
            }
        } catch (RuntimeException exception) {
            // 通知失败不能连累已经落地的扣款状态：调度结果已经写入数据库，
            // 这里只记录，让通知在下一轮或由运维补发。
            LOGGER.atWarn()
                    .addKeyValue("subscriptionId", subscription.id())
                    .addKeyValue("outcome", outcome)
                    .setCause(exception)
                    .log("subscription notification failed");
        }
    }

    /**
     * 扣款前预告（设计 §9.4 的 T-3 分流），由到期提醒链路在订阅用户上调用。
     *
     * <p>{@code event_key} 含到期日而不是「3 天」：这样同一个账期内重复触发只会有一条，
     * 而下一个账期会自然产生新的键。
     */
    @Transactional
    public boolean notifyRenewalDue(
            SubscriptionRepository.SubscriptionRow subscription, String planName, String renewMoney) {
        return events.recordAuthoritativeBusinessEvent(
                "renewal:" + subscription.id() + ":" + date(subscription.currentPeriodEnd()) + ":due",
                "SUBSCRIPTION_RENEWAL_DUE",
                subscription.tenantId(), subscription.userId(),
                "SUBSCRIPTION", subscription.id().toString(),
                Map.of(
                        "renewMoney", renewMoney,
                        "renewDate", date(subscription.currentPeriodEnd()),
                        "planName", planName == null ? "订阅套餐" : planName),
                java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC))
                .created();
    }

    /**
     * 「周期真正开始」的通知（设计 §9.1）。
     *
     * <p>与 {@code SUBSCRIPTION_RENEWED} 分开是因为**两者相隔很久**：扣款成功时新实例是
     * {@code QUEUED}、{@code expires_at} 为 NULL，有效期的日期根本算不出来；只有实例
     * 真正激活时才知道。触发点在 {@code OutboxPublisher} 把发放请求翻成 {@code ACTIVE}
     * 的地方——那里正是既有的 {@code notifyGrantActive} 被调用的同一位置。
     *
     * <p><b>有效期一律回查实例，不接受调用方传入的时刻。</b>2026-09-28 实测缺陷：
     * 早先的实现把调用方的 {@code clock.instant()} 当有效期，于是通知里写的是
     * 「有效期至 2026-09-28」而实例真实到期日是 2026-10-28——**少报整整 30 天**，
     * 用户会以为套餐当天到期。实例的 {@code expires_at} 由网关侧激活事务写入，
     * 平台落库 {@code grant_request=ACTIVE} 时它已经存在，因此这里读得到权威值。
     *
     * <p>找不到对应账期说明这笔发放不是订阅产生的（一次性购买），静默返回。
     * 账期找得到但实例还没物化（{@code expires_at} 为 NULL）时同样**不发**——
     * 宁可晚一轮，也不能发一个算不出来的日期；outbox 会周期性重投。
     *
     * @return 是否发出了订阅周期开始的通知
     */
    @Transactional
    public boolean notifyPeriodStarted(UUID grantRequestId) {
        if (grantRequestId == null) {
            return false;
        }
        SubscriptionCycleRepository.CycleOwner owner = cycles.findOwnerByGrant(grantRequestId);
        if (owner == null || owner.expiresAt() == null) {
            return false;
        }
        return record(
                "subscription-period:" + owner.cycleId() + ":started",
                "SUBSCRIPTION_PERIOD_STARTED",
                owner.tenantId(), owner.userId(),
                Map.of("expireDate", date(owner.expiresAt())));
    }

    private boolean record(
            String eventKey,
            String eventType,
            UUID tenantId,
            UUID userId,
            Map<String, ?> payload) {
        return events.recordAuthoritativeBusinessEvent(
                eventKey, eventType, tenantId, userId,
                "SUBSCRIPTION", eventKey, payload,
                java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC))
                .created();
    }

    private static long amountOrZero(SubscriptionRepository.SubscriptionRow subscription) {
        return subscription.lastChargedAmount() == null ? 0L : subscription.lastChargedAmount();
    }

    /**
     * 用户可读的失败原因。
     *
     * <p>刻意用固定文案而不是把 {@code last_error} 原文透出：那里面可能有内部异常消息。
     * 稳定的机器码走 {@code failure_reason} 字段给后台，用户看到的是这句话。
     */
    private static String failureText(
            SubscriptionRenewalJob.CycleOutcome outcome,
            SubscriptionRepository.SubscriptionRow subscription) {
        if (outcome == SubscriptionRenewalJob.CycleOutcome.EXPIRED) {
            return "宽限期内未能完成续费";
        }
        String skipReason = subscription.lastSkipReason();
        if ("insufficient_balance".equals(skipReason)) {
            return "余额不足";
        }
        return "余额不足";
    }

    static String date(Instant instant) {
        return instant == null
                ? null
                : instant.atZone(SHANGHAI).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /** 分 → 元，两位小数。与既有通知的金额口径一致（{@code payMoney} 等字符串键）。 */
    static String yuan(long amountFen) {
        return String.format("%.2f", amountFen / 100.0);
    }
}
