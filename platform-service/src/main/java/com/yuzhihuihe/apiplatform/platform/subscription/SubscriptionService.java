package com.yuzhihuihe.apiplatform.platform.subscription;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.grant.PackageSnapshotService;
import com.yuzhihuihe.apiplatform.platform.order.OrderApplicationService;
import com.yuzhihuihe.apiplatform.platform.wallet.BalanceOrderPaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 连续包月的开通、解约与恢复。
 *
 * <p><b>开通首期仍然是一次购买，因此原样走既有的两个服务</b>：
 * {@code OrderApplicationService.createWithOutcome}（下单）与
 * {@code BalanceOrderPaymentService.pay}（余额扣款 + 记账 + 发放）。
 * 本类不复制它们的任何逻辑——扣款、幂等、发放、库存、经营事实全部由既有链路负责，
 * 这正是「不改动现有扣费」的落地方式。
 *
 * <p>与续费的关键差异：开通**受限购约束**（走 {@code PackagePurchasePolicyService}），
 * 而续费走平行的 {@link SubscriptionRenewalPolicyService}。这个不对称是刻意的——
 * 它阻止用户用「N 个订阅」绕开限购。
 */
@Service
public class SubscriptionService {

    /**
     * 首期账期的序号。
     *
     * <p>{@code ck_subscription_cycle_index} 要求 {@code period_index > 0}，所以首期是 **1**
     * 而不是 0，续期从 2 开始。{@code business_key} 用的是同一个序号，
     * 因此首期与续期的幂等键落在同一条规则上、不会相撞。
     */
    private static final int FIRST_PERIOD_INDEX = 1;

    private final SubscriptionRepository subscriptions;
    private final SubscriptionCycleRepository cycles;
    private final SubscriptionPlanReader plans;
    private final PackageSnapshotService snapshots;
    private final OrderApplicationService orders;
    private final BalanceOrderPaymentService balancePayments;
    private final Clock clock;

    SubscriptionService(
            SubscriptionRepository subscriptions,
            SubscriptionCycleRepository cycles,
            SubscriptionPlanReader plans,
            PackageSnapshotService snapshots,
            OrderApplicationService orders,
            BalanceOrderPaymentService balancePayments,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.cycles = cycles;
        this.plans = plans;
        this.snapshots = snapshots;
        this.orders = orders;
        this.balancePayments = balancePayments;
        this.clock = clock;
    }

    /**
     * 开通连续包月，并在同一事务里完成首期支付。
     *
     * <p><b>为什么必须同一事务、同一请求内完成支付</b>：{@code customer_order} 的 TTL 只有
     * 15 分钟（{@code OrderApplicationService.ORDER_TTL}），跨请求延迟支付会留下过期订单，
     * 而关单任务会释放它的库存预留。一次性购买路径的结账对话框就是这个节奏。
     */
    @Transactional
    public SubscriptionView open(
            UUID tenantId, UUID userId, UUID packageVersionId, String idempotencyKey) {
        requireIdentity(tenantId, userId);
        if (packageVersionId == null) {
            throw invalid("invalid_subscription_request");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128) {
            throw invalid("invalid_idempotency_key");
        }

        PackageSnapshotService.PackageSnapshot snapshot = snapshots.loadPublished(packageVersionId);
        UUID packagePlanId = plans.readPlanId(packageVersionId);
        if (packagePlanId == null) {
            throw invalid("package_plan_not_resolved");
        }

        // 同一「用户 × 计划」串行化，避免并发开通造出两份订阅
        // （第二份会被 uq_subscription_active_plan 拒绝，但先锁住能给出更准确的结果）。
        subscriptions.lockUserPlan(userId, packagePlanId);

        // 已经有活跃订阅就直接返回它：重复点击「开通」不该产生第二笔扣款。
        var existing = subscriptions.findActiveByPlan(tenantId, userId, packagePlanId);
        if (existing.isPresent()) {
            return view(existing.get(), snapshot.packageName());
        }

        OrderApplicationService.CreationOutcome created = orders.createWithOutcome(
                tenantId, userId, "subscription-open:" + idempotencyKey,
                new OrderApplicationService.CreateOrderCommand(packageVersionId));
        BalanceOrderPaymentService.BalancePaymentResult payment = balancePayments.pay(
                tenantId, userId, created.order().orderNo(),
                "subscription-open:" + idempotencyKey);

        Instant periodStart = clock.instant();
        // 首期结束时间由**套餐时长**推进，而不是取支付返回值里的到期时间：
        // 用户若已持有活跃实例，首期实例会以 QUEUED 落库且 expires_at 为 NULL
        // （设计 §5.5 / §5.7.1 H4），根本取不到那个时间。
        Instant periodEnd = periodStart.plus(Duration.ofDays(snapshot.durationDays()));
        Instant nextChargeAt = periodEnd.minus(Duration.ofDays(1));

        SubscriptionRepository.SubscriptionRow subscription = subscriptions.insertIfAbsent(
                        new SubscriptionRepository.NewSubscription(
                                UUID.randomUUID(), tenantId, userId, packagePlanId,
                                periodStart, periodEnd, nextChargeAt, snapshot.priceAmount()))
                .orElseThrow(() -> new IllegalStateException("subscription_insert_conflict"));

        // 首期账期行。business_key 与续期共用同一套派生规则（"renewal:<id>:<index>"），
        // 因此两者不会相撞；首期是 index=1，续期从 2 开始。
        UUID cycleId = cycles.insertIfAbsent(new SubscriptionCycleRepository.NewCycle(
                UUID.randomUUID(), subscription.id(), FIRST_PERIOD_INDEX,
                periodStart, periodEnd, businessKey(subscription.id(), FIRST_PERIOD_INDEX),
                "PENDING", snapshot.priceAmount(), snapshot.currency(), packageVersionId));
        if (cycleId == null) {
            // 同一 subscription × period 已有账期行（例如并发开通的第二个请求），
            // 读取既有行而不是插入第二行。
            cycleId = cycles.findLatest(subscription.id())
                    .orElseThrow(() -> new IllegalStateException("subscription_cycle_missing"))
                    .cycleId();
        }
        cycles.markCharged(
                cycleId,
                plans.readOrderId(created.order().orderNo()),
                created.order().orderNo(), packageVersionId,
                snapshot.priceAmount(), payment.grantRequestId());

        return view(subscription, snapshot.packageName());
    }

    @Transactional(readOnly = true)
    public List<SubscriptionView> list(UUID tenantId, UUID userId) {
        requireIdentity(tenantId, userId);
        return subscriptions.listForUser(tenantId, userId).stream()
                .map(row -> view(row, plans.readPlanName(row.packagePlanId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SubscriptionDetail detail(UUID tenantId, UUID userId, UUID subscriptionId) {
        requireIdentity(tenantId, userId);
        SubscriptionRepository.SubscriptionRow row = requireSubscription(tenantId, userId, subscriptionId);
        return new SubscriptionDetail(
                view(row, plans.readPlanName(row.packagePlanId())),
                cycles.listBySubscription(subscriptionId).stream().map(SubscriptionService::cycleView).toList());
    }

    /**
     * 解约：当期用满为止，不再扣下一期（设计 §13 决策 5 的默认口径）。
     *
     * <p>不退款、不立即终止——已生效的当期是用户已付过钱的服务。
     */
    @Transactional
    public SubscriptionView cancel(UUID tenantId, UUID userId, UUID subscriptionId) {
        requireIdentity(tenantId, userId);
        SubscriptionRepository.SubscriptionRow row = requireSubscriptionForUpdate(tenantId, userId, subscriptionId);
        subscriptions.lockUserPlan(userId, row.packagePlanId());
        return setAutoRenewInternal(tenantId, userId, subscriptionId, false);
    }

    /** 撤销解约。仅当当期尚未结束时有意义（已过期的订阅无法恢复）。 */
    @Transactional
    public SubscriptionView resume(UUID tenantId, UUID userId, UUID subscriptionId) {
        requireIdentity(tenantId, userId);
        SubscriptionRepository.SubscriptionRow row = requireSubscriptionForUpdate(tenantId, userId, subscriptionId);
        subscriptions.lockUserPlan(userId, row.packagePlanId());
        if (!row.currentPeriodEnd().isAfter(clock.instant())) {
            throw conflict("subscription_period_ended");
        }
        return setAutoRenewInternal(tenantId, userId, subscriptionId, true);
    }

    /**
     * 用户确认接受调整后的续费价（设计 §7.3）。
     *
     * <p>这是暂缓中的订阅**唯一**的恢复入口。没有它，{@code markChargeDeferred} 留下的
     * 「水位不动」就永远不会被推进：每一轮调度都重新判定为涨价、重新暂缓，订阅无限期停在
     * 「ACTIVE 但永不扣款」，用户看到的是「订阅悄悄不再续费」。
     *
     * <p><b>确认的是「当前已发布价」，而不是通知里那个数。</b>因此这里重新解析一次
     * {@code PLAN} 的 PUBLISHED 版本：若管理员在用户点确认之前又改了价，用户确认的价与
     * 即将扣的价就不是同一个了，此时**拒绝本次确认**并要求重新确认，而不是按新价静默扣款。
     * 这是「绝不能扣一个用户没见过的价」的最后一道闸门。
     */
    @Transactional
    public SubscriptionView confirmPrice(UUID tenantId, UUID userId, UUID subscriptionId) {
        requireIdentity(tenantId, userId);
        SubscriptionRepository.SubscriptionRow row =
                requireSubscriptionForUpdate(tenantId, userId, subscriptionId);
        if (row.pendingPriceAmount() == null) {
            throw conflict("subscription_no_pending_price_change");
        }
        subscriptions.lockUserPlan(userId, row.packagePlanId());

        UUID versionId = plans.readPublishedVersionId(row.packagePlanId());
        SubscriptionPlanReader.PlanPrice current = versionId == null ? null : plans.readPrice(versionId);
        if (current == null) {
            // 计划下架或没有已发布版本：续费本来就走不了，确认也就没有意义。
            throw conflict("subscription_plan_unavailable");
        }
        long amount = current.amount();
        if (amount != row.pendingPriceAmount()) {
            // 报价已变。刷新成新报价并要求用户重新确认，绝不按新价扣款。
            subscriptions.refreshPendingPriceChange(subscriptionId, amount, clock.instant());
            throw conflict("subscription_price_changed_again");
        }

        Instant now = clock.instant();
        if (!subscriptions.confirmPendingPrice(subscriptionId, amount, now)) {
            // 乐观校验失败：期间被别的路径改了（例如又一次调价或已终止）。
            throw conflict("subscription_no_pending_price_change");
        }
        SubscriptionRepository.SubscriptionRow updated =
                requireSubscriptionForUpdate(tenantId, userId, subscriptionId);
        return view(updated, plans.readPlanName(updated.packagePlanId()));
    }

    private SubscriptionView setAutoRenewInternal(
            UUID tenantId, UUID userId, UUID subscriptionId, boolean autoRenew) {
        try {
            subscriptions.setAutoRenew(tenantId, userId, subscriptionId, autoRenew);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "subscription_not_open_for_update", exception);
        }
        SubscriptionRepository.SubscriptionRow updated =
                requireSubscriptionForUpdate(tenantId, userId, subscriptionId);
        return view(updated, plans.readPlanName(updated.packagePlanId()));
    }

    /** 归属校验三条件；查不到一律 404，不区分「不存在」与「不是你的」。 */
    SubscriptionRepository.SubscriptionRow requireSubscription(
            UUID tenantId, UUID userId, UUID subscriptionId) {
        return subscriptions.findById(tenantId, userId, subscriptionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "subscription_not_found"));
    }

    /** 同上，但加行锁。调用方必须处于可写事务中（只读事务里 PostgreSQL 会拒绝 FOR UPDATE）。 */
    private SubscriptionRepository.SubscriptionRow requireSubscriptionForUpdate(
            UUID tenantId, UUID userId, UUID subscriptionId) {
        return subscriptions.findByIdForUpdate(tenantId, userId, subscriptionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "subscription_not_found"));
    }

    static String businessKey(UUID subscriptionId, int periodIndex) {
        return "renewal:" + subscriptionId + ":" + periodIndex;
    }

    private static SubscriptionView view(
            SubscriptionRepository.SubscriptionRow row, String planName) {
        return new SubscriptionView(
                row.id(), row.packagePlanId(), planName, row.status(), row.autoRenew(),
                row.cancelAtPeriodEnd(), row.currentPeriodStart(), row.currentPeriodEnd(),
                row.nextChargeAt(), row.lastChargedAmount(), row.renewalCount(),
                row.consecutiveFailures(), row.graceUntil(), row.lastSkipReason(),
                row.pendingPriceAmount(), row.priceChangeDetectedAt(), row.priceConfirmedAt());
    }

    private static CycleView cycleView(SubscriptionCycleRepository.CycleRow row) {
        return new CycleView(
                row.cycleId(), row.periodIndex(), row.periodStart(), row.periodEnd(),
                row.status(), row.amount(), row.currency(), row.orderNo(),
                row.chargedAt(), row.failureReason());
    }

    private static void requireIdentity(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication_required");
        }
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    /**
     * 前台视图。
     *
     * <p>{@code nextChargeAmount} 刻意不在这里给：它取自**当前** PUBLISHED 版本的价格，
     * 而版本随时可能被替换。调用方要展示「下次扣多少」时应读实时价格，否则会把一个
     * 过期的数字展示给用户。首期已扣金额由 {@code lastChargedAmount} 承载。
     */
    public record SubscriptionView(
            UUID id,
            UUID packagePlanId,
            String planName,
            String status,
            boolean autoRenew,
            boolean cancelAtPeriodEnd,
            Instant currentPeriodStart,
            Instant currentPeriodEnd,
            Instant nextChargeAt,
            Long lastChargedAmount,
            int renewalCount,
            int consecutiveFailures,
            Instant graceUntil,
            String lastSkipReason,
            /**
             * 等待用户确认的续费报价（分）。非空即表示该订阅因涨价处于暂缓状态。
             *
             * <p>客户端据此显示「续费价已调整为 X，确认后继续」与确认入口；它是**唯一**能让
             * 一个暂缓中的订阅恢复扣款的办法（设计 §7.3）。
             */
            Long pendingPriceAmount,
            Instant priceChangeDetectedAt,
            Instant priceConfirmedAt) {
    }

    public record CycleView(
            UUID id,
            int periodIndex,
            Instant periodStart,
            Instant periodEnd,
            String status,
            long amount,
            String currency,
            String orderNo,
            Instant chargedAt,
            String failureReason) {
    }

    public record SubscriptionDetail(SubscriptionView subscription, List<CycleView> cycles) {
    }
}
