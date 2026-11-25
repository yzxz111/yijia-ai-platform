package com.yuzhihuihe.apiplatform.platform.subscription;

import java.time.Clock;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.order.OrderApplicationService;
import com.yuzhihuihe.apiplatform.platform.order.PackageOrderSettlementService;
import com.yuzhihuihe.apiplatform.platform.wallet.BalancePostingService;
import org.springframework.stereotype.Service;

/**
 * 一次「版本 → 下单 → 余额扣款 → 结算发放」的动作。
 *
 * <p>开通首期与自动续费走在这里，因此两者用的是**同一段**既有链路：
 * {@link OrderApplicationService}（下单）、{@link BalancePostingService}（余额扣款 + 记账）、
 * {@link PackageOrderSettlementService}（标记已支付 + 库存 + 经营事实 + 发放请求）。
 * 本类只负责按正确顺序把它们串起来，不复制其中任何一步的逻辑。
 *
 * <h2>为什么不用 {@code BalanceOrderPaymentService.pay}</h2>
 *
 * <p>那是最省事的复用，但它在续费场景下有一个致命行为：它是 {@code @Transactional}，
 * 而续费的扣款必须跑在自己的 {@code REQUIRES_NEW} 事务里。当钱包余额不足时，
 * <b>异常穿过 {@code REQUIRES_NEW} 边界时事务已被标记 rollback-only，
 * Spring 抛出的是 {@code UnexpectedRollbackException} 而不是原始业务异常</b>——
 * 调用方据此无法区分「余额不足」与「事务基础设施故障」。
 *
 * <p>实测症状：失败原因写不进数据库（外层记账事务在提交时被静默回滚），
 * 而订阅租约（在外层已提交）留在库里，订阅永久卡在持有租约的状态。
 * 定位证据是探针捕获到的
 * {@code UnexpectedRollbackException: Transaction silently rolled back because it has been
 * marked as rollback-only}。
 *
 * <p>把 {@code pay} 拆成它内部**本来就已经并列调用**的这两个服务之后，余额不足是
 * {@link BalancePostingService} 直接抛出的 {@code wallet_insufficient_balance}，
 * 调用方可以按稳定错误码归类并记进账期行。
 *
 * <p><b>幂等</b>：{@code idempotencyKey} 由调用方按确定性规则派生（账期的
 * {@code business_key}），同时用作下单的 {@code Idempotency-Key} 与账本幂等键，
 * 因此重复调用不可能产生第二笔订单或第二笔扣款。
 */
@Service
class SubscriptionChargeService {

    private final OrderApplicationService orders;
    private final BalancePostingService balancePosting;
    private final PackageOrderSettlementService settlements;
    private final SubscriptionPlanReader plans;
    private final Clock clock;

    SubscriptionChargeService(
            OrderApplicationService orders,
            BalancePostingService balancePosting,
            PackageOrderSettlementService settlements,
            SubscriptionPlanReader plans,
            Clock clock) {
        this.orders = orders;
        this.balancePosting = balancePosting;
        this.settlements = settlements;
        this.plans = plans;
        this.clock = clock;
    }

    /**
     * 对指定版本下单并扣款。**调用方负责事务边界。**
     */
    ChargeOutcome charge(
            UUID tenantId, UUID userId, UUID packageVersionId, String idempotencyKey) {
        OrderApplicationService.CreationOutcome created = orders.createWithOutcome(
                tenantId, userId, idempotencyKey,
                new OrderApplicationService.CreateOrderCommand(packageVersionId));
        String orderNo = created.order().orderNo();
        UUID orderId = plans.readOrderId(orderNo);

        // 与 BalanceOrderPaymentService.pay 完全相同的两步（顺序也一致）：
        // 先扣余额并记账，再按「余额结算」标记已支付并生成发放请求。
        BalancePostingService.PostingResult debit = balancePosting.post(
                new BalancePostingService.PostingCommand(
                        tenantId, userId, "PACKAGE_PURCHASE",
                        -created.order().amount(), 0, 0, "ORDER", orderId, orderNo,
                        idempotencyKey));
        PackageOrderSettlementService.SettlementResult settlement = settlements.settle(
                orderId,
                PackageOrderSettlementService.SettlementCommand.balance(
                        clock.instant(), debit.ledgerId()));

        return new ChargeOutcome(
                orderId, orderNo, settlement.grant().id(), debit.balanceAfter());
    }

    record ChargeOutcome(UUID orderId, String orderNo, UUID grantRequestId, long balanceAfter) {
    }
}
