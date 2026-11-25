package com.yuzhihuihe.apiplatform.platform.subscription;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 自动续费的调度任务。
 *
 * <p>骨架照抄既有的 {@code ExpiredOrderJob}：先在自己的事务里领取并记下租约，
 * 再逐个订阅开事务处理，因此「领取」与「处理」不共享长事务，一个订阅卡住不会拖住整批。
 *
 * <p><b>三个事务边界是刻意的</b>：
 * <ol>
 *   <li>领取：提交租约，让其他实例立刻看到；</li>
 *   <li>扣款（{@code REQUIRES_NEW}）：失败时**只回滚扣款**，不牵连记账。
 *       若与记账同事务，失败会把「记一次失败/清租约」一起回滚，订阅会永远卡在租约里；</li>
 *   <li>记账（外层事务）：读扣款落下的行状态，写账期与订阅状态。</li>
 * </ol>
 *
 * <p><b>租约在订阅行上</b>（见 {@code V71} 的 {@code ck_subscription_claim}），
 * 所以「同一订阅被两个实例同时扣款」在结构上不可能。
 *
 * <p>本任务**不**创建任何外部支付交易：续费只扣钱包余额。余额不足就走失败/宽限期，
 * 绝不隐式发起渠道扣款——渠道侧的签约代扣是 Phase 2 的范围（设计 §4/D1）。
 */
@Component
public class SubscriptionRenewalJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionRenewalJob.class);

    private final SubscriptionRepository subscriptions;
    private final SubscriptionCycleRepository cycles;
    private final SubscriptionRenewalPolicyService policy;
    private final SubscriptionPlanReader plans;
    private final SubscriptionChargeService charges;
    private final SubscriptionNotifications notifications;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;
    private final Duration claimTimeout;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int maxAttempts;
    private final Duration gracePeriod;
    private final Duration chargeLeadTime;
    private final int maxRenewals;
    private final Duration stuckQueuedAlertAfter;
    private final boolean priceIncreaseRequiresConfirmation;
    /**
     * 涨价报价的确认时限。超过它仍未确认就终止订阅。
     *
     * <p>这条兜底不是可选项：没有它，订阅会无限期停在「ACTIVE 但永不扣款」——用户以为在续费、
     * 实际早就停了，而且不会有任何一方收到告警（暂缓刻意不发失败通知）。
     */
    private final Duration priceConfirmationTimeout;
    private final TransactionTemplate transactions;
    private final TransactionTemplate chargeTransactions;
    /** 失败记账必须用独立事务：扣款事务失败会把外层标记 rollback-only（见 charge 的注释）。 */
    private final TransactionTemplate failureTransactions;
    /** 通知用独立事务：任何 SQL 错误都会把共享事务标记 rollback-only，静默回滚已落地的记账。 */
    private final TransactionTemplate notificationTransactions;

    public SubscriptionRenewalJob(
            SubscriptionRepository subscriptions,
            SubscriptionCycleRepository cycles,
            SubscriptionRenewalPolicyService policy,
            SubscriptionPlanReader plans,
            SubscriptionChargeService charges,
            SubscriptionNotifications notifications,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${subscription.jobs.enabled:false}") boolean enabled,
            @Value("${subscription.jobs.batch-size:50}") int batchSize,
            @Value("${subscription.jobs.claim-timeout:5m}") Duration claimTimeout,
            @Value("${subscription.jobs.initial-backoff:1h}") Duration initialBackoff,
            @Value("${subscription.jobs.max-backoff:24h}") Duration maxBackoff,
            @Value("${subscription.jobs.max-attempts:3}") int maxAttempts,
            @Value("${subscription.jobs.grace-period:7d}") Duration gracePeriod,
            @Value("${subscription.jobs.charge-lead-time:24h}") Duration chargeLeadTime,
            @Value("${subscription.jobs.max-renewals:36}") int maxRenewals,
            @Value("${subscription.jobs.stuck-queued-alert-after:45d}") Duration stuckQueuedAlertAfter,
            @Value("${subscription.jobs.price-increase-requires-confirmation:true}")
            boolean priceIncreaseRequiresConfirmation,
            @Value("${subscription.jobs.price-confirmation-timeout:30d}")
            Duration priceConfirmationTimeout) {
        this.subscriptions = subscriptions;
        this.cycles = cycles;
        this.policy = policy;
        this.plans = plans;
        this.charges = charges;
        this.notifications = notifications;
        this.transactions = new TransactionTemplate(transactionManager);
        this.chargeTransactions = new TransactionTemplate(transactionManager);
        // 扣款必须独立提交/回滚：失败时记账仍要落地。
        this.chargeTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.failureTransactions = new TransactionTemplate(transactionManager);
        this.notificationTransactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = bounded(batchSize, 1, 500, "subscription job batch size");
        this.claimTimeout = positive(claimTimeout, "subscription job claim timeout");
        this.initialBackoff = positive(initialBackoff, "subscription job initial backoff");
        this.maxBackoff = positive(maxBackoff, "subscription job maximum backoff");
        this.maxAttempts = bounded(maxAttempts, 1, 100, "subscription job maximum attempts");
        this.gracePeriod = positive(gracePeriod, "subscription job grace period");
        this.chargeLeadTime = positive(chargeLeadTime, "subscription job charge lead time");
        this.maxRenewals = bounded(maxRenewals, 1, 1200, "subscription job maximum renewals");
        this.stuckQueuedAlertAfter = positive(stuckQueuedAlertAfter, "subscription job stuck queued window");
        this.priceIncreaseRequiresConfirmation = priceIncreaseRequiresConfirmation;
        this.priceConfirmationTimeout = atLeast(
                priceConfirmationTimeout,
                MIN_PRICE_CONFIRMATION_TIMEOUT,
                "subscription job price confirmation timeout");
        if (initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("subscription job backoff range is invalid");
        }
    }

    @Scheduled(
            fixedDelayString = "${subscription.jobs.renewal-delay-ms:60000}",
            initialDelayString = "${subscription.jobs.renewal-delay-ms:60000}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        runOnce();
    }

    /** 跑一轮。返回处理的订阅数，便于测试与运维手动触发。 */
    public int runOnce() {
        Instant now = clock.instant();
        // 兜底扫描必须**先于**领取：超时未确认的订阅仍是 ACTIVE + auto_renew + 水位在过去，
        // 会被 claimDue 正常领走，然后每一轮都再暂缓一次——永远等不到终止。
        expireUnconfirmedPriceChanges(now);
        List<SubscriptionRepository.ClaimedSubscription> claimed = transactions.execute(
                status -> subscriptions.claimDue(now, claimTimeout, batchSize));
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        for (SubscriptionRepository.ClaimedSubscription claim : claimed) {
            try {
                transactions.executeWithoutResult(status -> handle(claim));
            } catch (RuntimeException exception) {
                // 单个订阅的异常不能拖垮整批。租约会在超时后自动可回收
                // （claimDue 的过期租约分支），因此这里只需记录并继续。
                LOGGER.atWarn()
                        .addKeyValue("subscriptionId", claim.subscriptionId())
                        .addKeyValue("outcome", "UNEXPECTED")
                        .setCause(exception)
                        .log("subscription renewal attempt failed unexpectedly");
            }
        }
        return claimed.size();
    }

    /**
     * 终止「涨价报价长期未确认」的订阅（设计 §7.3 的兜底）。
     *
     * <p>为什么必须有：暂缓路径刻意不发失败通知、也不消耗尝试预算，因此一个没人确认的报价会让
     * 订阅无限期停在「ACTIVE 但永不扣款」。用户以为自己还在续费，实际服务到期即止；而运维侧
     * 失败率、告警都不会红——**没有任何一方会发现问题**。这类「静默停摆」比一次明确的失败更糟。
     *
     * <p>终止而不是继续等待：超过一个周期仍未确认，说明用户既不接受新价也不主动解约，
     * 继续挂着只会让「这个订阅到底还算不算数」越来越难回答。
     *
     * <p>用独立事务：本方法在领取之前运行，不应把失败牵连到随后的扣款批次。
     */
    private void expireUnconfirmedPriceChanges(Instant now) {
        Instant deadline = now.minus(priceConfirmationTimeout);
        List<UUID> stale = transactions.execute(
                status -> subscriptions.findUnconfirmedPriceBeyond(deadline, batchSize));
        if (stale == null || stale.isEmpty()) {
            return;
        }
        for (UUID subscriptionId : stale) {
            try {
                transactions.executeWithoutResult(status ->
                        subscriptions.markTerminated(subscriptionId, "price_not_confirmed"));
                // 这里刻意**不**向用户发订阅失败通知。用户在报价产生时已经收到
                // SUBSCRIPTION_PRICE_CHANGED，并且那条文案就写着「请到订阅管理确认后继续」；
                // 再用「续费失败」去惊动一次，只会让人以为扣款出了问题——而实际上系统一次
                // 都没有尝试按新价扣款。终止事实通过 last_skip_reason/reason 代码对后台可见。
                LOGGER.atWarn()
                        .addKeyValue("subscriptionId", subscriptionId)
                        .addKeyValue("outcome", "PRICE_NOT_CONFIRMED")
                        .addKeyValue("timeout", priceConfirmationTimeout.toString())
                        .log("subscription terminated because the price change was never confirmed");
            } catch (RuntimeException exception) {
                LOGGER.atWarn()
                        .addKeyValue("subscriptionId", subscriptionId)
                        .setCause(exception)
                        .log("could not expire an unconfirmed price change");
            }
        }
    }

    /** 一个订阅的一次处理结果，用于通知与日志。 */
    public enum CycleOutcome {
        RENEWED,
        SKIPPED,
        FAILED,
        DEFERRED,
        LIMIT_REACHED,
        EXPIRED,
        STUCK_QUEUED
    }

    private void handle(SubscriptionRepository.ClaimedSubscription claim) {
        SubscriptionRepository.SubscriptionRow subscription = subscriptions
                .findById(claim.tenantId(), claim.userId(), claim.subscriptionId())
                .orElse(null);
        if (subscription == null || subscription.claimToken() == null) {
            // 租约在读取前已被释放（例如并发的后台终止），本轮无事可做。
            return;
        }
        Instant now = clock.instant();
        CycleOutcome outcome = process(subscription, now);

        // 通知是尽力而为的副作用：它失败了不该让已经落地的扣款与状态记账作废。
        //
        // 这里曾经有一处**注释与实现不符**的真实缺陷：注释声称「通知走自己的事务」，但
        // notificationTransactions 用的是默认传播行为（REQUIRED），而
        // SubscriptionNotifications.report 当时也只是 @Transactional——于是通知加入的是
        // 本方法的事务，report 内部的 try-catch 吞掉异常后，外层仍被标记 rollback-only，
        // 提交时被静默回滚。实测症状：日志成对出现
        // `409 notification_event_idempotency_mismatch` 与 `UnexpectedRollbackException`，
        // 这一次续费尝试什么都没做成（outcome=UNEXPECTED）。
        //
        // 修法有两处，缺一不可：
        //  1. `report` 标成 REQUIRES_NEW（见 SubscriptionNotifications.report 的注释），
        //     让通知跑在独立事务里；
        //  2. 失败通知的事件键必须逐轮唯一。只靠第 1 点不够——实测发现真正的触发原因是
        //     `event_key` 只用 consecutiveFailures，而人工重试不重置该计数，同一个账期
        //     「失败 → 重试 → 再失败」会撞同一个键，`sameIdentity` 比对（含 occurredAt）
        //     于是抛 409。详见 SubscriptionNotifications 里失败分支的注释。
        //
        // 这里的 catch 还要额外吃掉 UnexpectedRollbackException：它不是方法体抛的，而是
        // `report` 自己那层 REQUIRES_NEW **提交时**抛的，方法内的 try-catch 包不住它。
        // 通知是尽力而为的副作用，无论哪种失败都不该影响已经落地的扣款与记账。
        try {
            notificationTransactions.executeWithoutResult(
                    status -> notifications.report(subscription, outcome, now));
        } catch (UnexpectedRollbackException exception) {
            LOGGER.atWarn()
                    .addKeyValue("subscriptionId", subscription.id())
                    .addKeyValue("outcome", outcome)
                    .setCause(exception)
                    .log("subscription notification transaction rolled back; notification skipped");
        } catch (RuntimeException exception) {
            LOGGER.atWarn()
                    .addKeyValue("subscriptionId", subscription.id())
                    .addKeyValue("outcome", outcome)
                    .setCause(exception)
                    .log("subscription notification transaction failed");
        }
    }

    private CycleOutcome process(SubscriptionRepository.SubscriptionRow subscription, Instant now) {
        CycleOutcome stuck = detectStuckQueue(subscription, now);
        if (stuck != null) {
            return stuck;
        }

        // 版本按「当前 PUBLISHED」解析，而不是记住开通时那一个：这个仓库的常态是
        // 「发布新版本 + 旧版本置 RETIRED」（设计 §7.3）。
        UUID versionId = plans.readPublishedVersionId(subscription.packagePlanId());
        SubscriptionRenewalPolicyService.Decision decision = policy.evaluate(
                subscription, versionId, priceIncreaseRequiresConfirmation, maxRenewals, now);
        if (!decision.allowed()) {
            return handleDenied(subscription, decision, now);
        }
        return charge(subscription, versionId, decision, now);
    }

    private CycleOutcome handleDenied(
            SubscriptionRepository.SubscriptionRow subscription,
            SubscriptionRenewalPolicyService.Decision decision,
            Instant now) {
        if (decision.skipReason() != null) {
            return skip(subscription, skipReasonCode(decision.skipReason()));
        }
        if ("subscription_renewal_limit_reached".equals(decision.failureReason())) {
            // 累计上限到顶是「拒绝」而不是「跳过」：它要触发终止与运营告警。
            subscriptions.markTerminated(subscription.id(), "renewal_limit_reached");
            return CycleOutcome.LIMIT_REACHED;
        }
        // 价格上调：暂缓而不是失败。不消耗尝试预算（设计 §7.3 的合规要求）。
        //
        // 必须把**报价落库**并带上租约，而不是只清租约记原因：用户确认时要能校验
        // 「你确认的那个价」与「此刻的已发布价」是否还是同一个。此前只调 markChargeDeferred，
        // 于是 pending 从未落库、确认入口也无从校验，订阅会永久停在「ACTIVE 但永不扣款」。
        // recordPendingPriceChange 同时完成「记报价」与「清租约」，语义上取代了原来的调用。
        subscriptions.recordPendingPriceChange(
                subscription.id(), subscription.claimToken(),
                decision.newAmount() == null ? 0L : decision.newAmount(), now);
        return CycleOutcome.DEFERRED;
    }

    /**
     * 对一期扣款并记账。
     *
     * <p><b>顺序至关重要：先扣款，再写账期行。</b>最初写成「先插账期行、再扣款」，
     * 结果是死锁——账期行对 {@code package_plan_version} 有外键，插入时会锁住那个版本行，
     * 而扣款事务里的 {@code PackageInventoryService.consume} 要对同一个版本行
     * {@code FOR UPDATE}。两个事务互相等待，续费永久卡住。
     * 实测证据：{@code pg_blocking_pids} 显示内层扣款事务被外层的
     * {@code INSERT INTO platform.subscription_cycle} 阻塞。
     *
     * <p>改成「先扣款（独立事务提交）、再在记账事务里写账期行」之后，锁的取得顺序
     * 变成单向的，而且失败重试时账期行会被同一次回滚一起撤销，语义更干净。
     */
    private CycleOutcome charge(
            SubscriptionRepository.SubscriptionRow subscription,
            UUID versionId,
            SubscriptionRenewalPolicyService.Decision decision,
            Instant now) {
        int periodIndex = cycles.findLatest(subscription.id())
                .map(latest -> latest.periodIndex() + 1)
                .orElse(1);
        int durationDays = durationDaysOf(subscription, versionId, now);
        String businessKey = SubscriptionService.businessKey(subscription.id(), periodIndex);

        SubscriptionChargeService.ChargeOutcome outcome;
        try {
            outcome = chargeTransactions.execute(status -> charges.charge(
                    subscription.tenantId(), subscription.userId(), versionId, businessKey));
        } catch (RuntimeException failure) {
            // 扣款事务已经把外层标记 rollback-only；若在外层接着记账，提交时会被
            // Spring 以 UnexpectedRollbackException 静默回滚（租约留在库里、失败原因消失）。
            // 因此记账另开一个事务，并且不让异常穿出本方法。
            failureTransactions.executeWithoutResult(status ->
                    recordFailure(subscription, periodIndex, durationDays, failure, now));
            return CycleOutcome.FAILED;
        }
        if (outcome == null) {
            IllegalStateException missing = new IllegalStateException("charge_returned_null");
            failureTransactions.executeWithoutResult(status ->
                    recordFailure(subscription, periodIndex, durationDays, missing, now));
            return CycleOutcome.FAILED;
        }

        UUID cycleId = cycles.insertIfAbsent(new SubscriptionCycleRepository.NewCycle(
                UUID.randomUUID(), subscription.id(), periodIndex,
                subscription.currentPeriodEnd(),
                subscription.currentPeriodEnd().plus(Duration.ofDays(durationDays)),
                businessKey, "CHARGED", decision.amount(), decision.currency(), versionId));
        if (cycleId != null) {
            cycles.markCharged(cycleId, outcome.orderId(), outcome.orderNo(), versionId,
                    decision.amount(), outcome.grantRequestId());
        }
        Instant periodStart = subscription.currentPeriodEnd();
        Instant periodEnd = periodStart.plus(Duration.ofDays(durationDays));
        // next_charge_at 按 durationDays 前移，**不等待实例激活**（设计 §5.7.1/H3）：
        // 续期实例以 QUEUED 落库，拿它当触发点会让账期永远停住。
        subscriptions.advancePeriod(subscription.id(), periodStart, periodEnd,
                periodEnd.minus(chargeLeadTime), subscription.cancelAtPeriodEnd(), decision.amount());
        subscriptions.completeClaim(subscription.id(), subscription.claimToken());
        return CycleOutcome.RENEWED;
    }

    /** 扣款失败后的记账。**必须在独立事务里调用**（理由见 charge 的注释）。 */
    private void recordFailure(
            SubscriptionRepository.SubscriptionRow subscription,
            int periodIndex,
            int durationDays,
            RuntimeException failure,
            Instant now) {
        String reason = failureReason(failure);
        // 权威口径是「这份订阅累计失败了几期」，不是「最新账期行的 attempt_count」：
        // 后者对每个新账期行都从 0 加到 1、恒为 1，上限永远达不到，订阅会永远停在
        // PAST_DUE 而失败重试无限期进行。本次失败尚未计入，因此判断用 attempts + 1。
        int attempts = cycles.countFailedCycles(subscription.id());
        Instant graceUntil = subscription.graceUntil() == null
                ? now.plus(gracePeriod)
                : subscription.graceUntil();
        boolean exhausted = attempts + 1 >= maxAttempts || !graceUntil.isAfter(now);

        // 失败也留一行账期记录：后台需要能回答「这一期为什么没扣成功」。
        // 它由本次记账事务写入，因此与订阅状态同生共死。
        UUID cycleId = cycles.insertIfAbsent(new SubscriptionCycleRepository.NewCycle(
                UUID.randomUUID(), subscription.id(), periodIndex,
                subscription.currentPeriodEnd(),
                subscription.currentPeriodEnd().plus(Duration.ofDays(durationDays)),
                SubscriptionService.businessKey(subscription.id(), periodIndex),
                "FAILED", 0L, "CNY", null));
        if (cycleId != null) {
            if (exhausted) {
                cycles.markExhausted(cycleId, reason);
            } else {
                cycles.markFailed(cycleId, reason, failure.getMessage(), now.plus(backoff(attempts)));
            }
        }

        if (exhausted) {
            subscriptions.markExpired(subscription.id());
            subscriptions.releaseClaim(subscription.id(), subscription.claimToken());
            return;
        }
        // 订阅的 next_charge_at 推后**一个账期**而不是退避时长：宽限期内每个账期留
        // 一次尝试机会。把它留在过去会让调度器在退避到期后一路重试到次数上限，
        // 用户还没来得及充值订阅就已经作废。
        //
        // 起点必须是**现在**而不是 current_period_end：后者在「当期已用完、正在续费」
        // 这个时刻本来就是过去时间，从它推进仍然落在过去，于是退避形同虚设。
        subscriptions.markChargeFailed(subscription.id(), subscription.claimToken(),
                now.plus(Duration.ofDays(durationDays)), graceUntil);
    }

    private CycleOutcome skip(
            SubscriptionRepository.SubscriptionRow subscription, String reason) {
        // 跳过的水位前移一个账期：用户手工买过同类套餐时，下一个账期再判断一次。
        subscriptions.skipClaim(subscription.id(), subscription.claimToken(), reason,
                subscription.currentPeriodEnd());
        return CycleOutcome.SKIPPED;
    }

    /**
     * §5.7.1 实测新增的兜底：扣款成功但实例长期排不上队。
     *
     * <p>这是本功能唯一会「静默吞钱」的路径——钱扣了、服务没生效、用户也不会收到
     * 失败通知。超过阈值就停止扣款并留下明确原因，让运维能看见。
     */
    private CycleOutcome detectStuckQueue(
            SubscriptionRepository.SubscriptionRow subscription, Instant now) {
        Instant threshold = now.minus(stuckQueuedAlertAfter);
        boolean stuck = cycles.findUnactivated(subscription.id()).stream()
                .anyMatch(cycle -> cycle.notYetActive()
                        && cycle.chargedAt() != null
                        && cycle.chargedAt().isBefore(threshold));
        if (!stuck) {
            return null;
        }
        subscriptions.markTerminated(subscription.id(), "renewal_instance_stuck_queued");
        return CycleOutcome.STUCK_QUEUED;
    }

    /** 版本对应的套餐时长（天）。取不到时回退到当前账期长度，保证不会算出 0 天账期。 */
    private int durationDaysOf(
            SubscriptionRepository.SubscriptionRow subscription, UUID versionId, Instant now) {
        SubscriptionPlanReader.PlanPrice price = versionId == null ? null : plans.readPrice(versionId);
        if (price != null && price.durationDays() > 0) {
            return price.durationDays();
        }
        if (versionId == null) {
            // 计划不可用：策略层已跳过，这里只是给账期行一个合法长度。
            return 1;
        }
        return (int) Math.max(1, Duration.between(
                subscription.currentPeriodStart(), subscription.currentPeriodEnd()).toDays());
    }

    static String skipReasonCode(SubscriptionRenewalPolicyService.SkipReason reason) {
        return switch (reason) {
            case CANCELED_BY_USER -> "canceled_by_user";
            case SUBSCRIPTION_NOT_OPEN -> "subscription_not_open";
            case ACCOUNT_NOT_ACTIVE -> "account_not_active";
            case ACTIVE_PACKAGE_PRESENT -> "active_package_present";
            case PLAN_UNAVAILABLE -> "plan_unavailable";
        };
    }

    /** 把底层异常映射成稳定短码（设计 §7.2）。 */
    static String failureReason(RuntimeException failure) {
        if (failure instanceof ResponseStatusException status) {
            String reason = status.getReason();
            if ("wallet_insufficient_balance".equals(reason)) {
                return "insufficient_balance";
            }
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
        }
        return "renewal_failed";
    }

    private Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(30, Math.max(0, attempt - 1));
        Duration candidate;
        try {
            candidate = initialBackoff.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            candidate = maxBackoff;
        }
        return candidate.compareTo(maxBackoff) > 0 ? maxBackoff : candidate;
    }

    private static int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is out of range");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /**
     * 「涨价报价待确认」的**最短**时限。
     *
     * <p>为什么需要一个下界：这个值决定「报价发出后多久仍未被确认就终止订阅」。设得过短（例如
     * {@code 1s}）会让通知刚发出、用户还没看到，订阅就已经被终止——而且是被**静默**终止：
     * 用户收到的只是一条「价格将调整」的提示，不会想到自己必须在一秒内点确认。
     *
     * <p>取 24 小时的依据是这个流程本身的时序：扣款发生在周期结束前 {@code CHARGE_LEAD_TIME}
     * （默认 24h），也就是说用户**在扣款之后**仍然需要一段可回应的时间。低于一天，等于把
     * 「用户没来得及看通知」变成「订阅被终止」。
     *
     * <p>只设下界、不设上界：调长只会让宽限更宽松，风险方向是安全的。
     */
    static final Duration MIN_PRICE_CONFIRMATION_TIMEOUT = Duration.ofHours(24);

    private static Duration atLeast(Duration value, Duration minimum, String name) {
        return requireAtLeast(value, minimum, name);
    }

    /** 同 {@link #atLeast}，包内可见以便直接对这条下界写测试。 */
    static Duration requireAtLeast(Duration value, Duration minimum, String name) {
        if (value == null || value.compareTo(minimum) < 0) {
            throw new IllegalArgumentException(
                    name + " must be at least " + minimum + " (got " + value + ")");
        }
        return value;
    }
}
