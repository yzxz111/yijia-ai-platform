package com.yuzhihuihe.apiplatform.platform.refund;

import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.reporting.OperatingFactService;
import com.yuzhihuihe.apiplatform.platform.reporting.PaymentFeeService;
import com.yuzhihuihe.apiplatform.platform.wallet.BalancePostingService;
import com.yuzhihuihe.apiplatform.platform.subscription.SubscriptionRenewalTermination;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundOutboxCompletionService {

    private final RefundOutboxCompletionRepository repository;
    private final BalancePostingService balancePostingService;
    private final CommercialNotificationService notifications;
    private final OperatingFactService operatingFacts;
    private final PaymentFeeService paymentFees;
    /** 退款成功后的订阅收尾（设计 §10）。可为 null：单元测试的窄构造器不传它。 */
    private final SubscriptionRenewalTermination subscriptionTermination;

    @Autowired
    RefundOutboxCompletionService(
            RefundOutboxCompletionRepository repository,
            BalancePostingService balancePostingService,
            ObjectProvider<CommercialNotificationService> notifications,
            ObjectProvider<OperatingFactService> operatingFacts,
            ObjectProvider<PaymentFeeService> paymentFees,
            ObjectProvider<SubscriptionRenewalTermination> subscriptionTermination) {
        this(repository, balancePostingService, notifications.getIfAvailable(),
                operatingFacts.getIfAvailable(), paymentFees.getIfAvailable(),
                subscriptionTermination.getIfAvailable());
    }

    RefundOutboxCompletionService(
            RefundOutboxCompletionRepository repository,
            BalancePostingService balancePostingService,
            CommercialNotificationService notifications) {
        this(repository, balancePostingService, notifications, null, null, null);
    }

    RefundOutboxCompletionService(
            RefundOutboxCompletionRepository repository,
            BalancePostingService balancePostingService,
            CommercialNotificationService notifications,
            OperatingFactService operatingFacts) {
        this(repository, balancePostingService, notifications, operatingFacts, null, null);
    }

    RefundOutboxCompletionService(
            RefundOutboxCompletionRepository repository,
            BalancePostingService balancePostingService,
            CommercialNotificationService notifications,
            OperatingFactService operatingFacts,
            PaymentFeeService paymentFees) {
        this(repository, balancePostingService, notifications, operatingFacts, paymentFees, null);
    }

    RefundOutboxCompletionService(
            RefundOutboxCompletionRepository repository,
            BalancePostingService balancePostingService,
            CommercialNotificationService notifications,
            OperatingFactService operatingFacts,
            PaymentFeeService paymentFees,
            SubscriptionRenewalTermination subscriptionTermination) {
        this.repository = repository;
        this.balancePostingService = balancePostingService;
        this.notifications = notifications;
        this.operatingFacts = operatingFacts;
        this.paymentFees = paymentFees;
        this.subscriptionTermination = subscriptionTermination;
    }

    @Transactional
    public void complete(UUID refundId, RevocationResult response) {
        if (refundId == null || response == null) {
            throw new IllegalStateException("gateway_revocation_response_invalid");
        }
        UUID orderId = lockOrder(refundId);
        CompletionTarget target = lockTarget(refundId, orderId);
        if (!"REVOKED".equals(response.status())
                || response.packageInstanceId() == null
                || !response.packageInstanceId().equals(target.packageInstanceId())) {
            throw new IllegalStateException("gateway_revocation_response_mismatch");
        }
        String orderStatus = "FULL".equals(target.refundType())
                ? "REFUNDED" : "PARTIALLY_REFUNDED";
        if ("COMPLETED".equals(target.refundStatus())) {
            if (!"REVOKED".equals(target.grantStatus())
                    || !orderStatus.equals(target.orderStatus())) {
                throw new IllegalStateException("refund_completion_state_mismatch");
            }
            return;
        }
        if (!"REVOKING".equals(target.refundStatus())) {
            throw new IllegalStateException("refund_not_revoke_pending");
        }

        int grantUpdated = repository.markGrantRevoked(target.grantRequestId());
        if (grantUpdated != 1) {
            throw new IllegalStateException("refund_grant_revocation_failed");
        }
        UUID balanceRefundLedgerId = null;
        if ("BALANCE".equals(target.fundingSource())) {
            BalancePostingService.PostingResult credit = balancePostingService.post(
                    new BalancePostingService.PostingCommand(
                            target.tenantId(), target.userId(), "PACKAGE_REFUND",
                            target.amount(), 0, 0, "REFUND", target.refundId(),
                            target.refundNo(), "refund:" + target.refundNo()));
            balanceRefundLedgerId = credit.ledgerId();
        }
        int refundUpdated = repository.markRefundCompleted(
                target.refundId(), balanceRefundLedgerId);
        if (refundUpdated != 1) {
            throw new IllegalStateException("refund_completion_failed");
        }
        int orderUpdated = repository.markOrderRefunded(
                target.orderId(), orderStatus,
                "FULL".equals(target.refundType()) ? "PARTIALLY_REFUNDED" : "REFUNDED");
        if (orderUpdated != 1) {
            throw new IllegalStateException("refund_order_completion_failed");
        }
        if (operatingFacts != null) {
            operatingFacts.recordPackageRefund(new OperatingFactService.PackageRefund(
                    target.refundId(), target.orderId(), target.tenantId(), target.userId(),
                    target.amount(), target.currency(), target.fundingSource(), java.time.Instant.now()));
        }
        if (paymentFees != null && "EXTERNAL".equals(target.fundingSource())) {
            paymentFees.recordEstimatedRefund(
                    target.refundId(), target.fundingSource(), java.time.Instant.now());
        }
        repository.auditCompletion(
                target.tenantId(), target.refundNo(), target.amount(),
                target.refundType(), target.fundingSource(), orderStatus);
        // 退款已经落库、订单也已标记退款，此刻收尾订阅（设计 §10 第 447-449 行）。
        //
        // 位置很关键：必须在**钱确实退回去之后**才终止。反过来做的话，一旦后续任一步失败，
        // 用户会既没拿到退款、订阅也已经被停掉——比原缺陷更难解释。
        //
        // 终止只对订阅来源的发放生效（按账期行反查），一次性购买因此完全不受影响。
        // 不判返回值也不抛异常：不是订阅就什么都不该发生，而订阅侧自身的失败不应把
        // 一笔已经完成的退款回滚掉。
        if (subscriptionTermination != null) {
            subscriptionTermination.terminateIfSubscriptionGrant(target.grantRequestId());
        }
        if (notifications != null) {
            notifications.notifyRefundSucceeded(
                    target.refundId(), target.tenantId(), target.userId(), target.amount());
        }
    }

    private UUID lockOrder(UUID refundId) {
        // 钱包支付与退款都在 account_balance 之前先获取 customer_order。
        return repository.lockOrder(refundId)
                .orElseThrow(() -> new IllegalStateException(
                        "refund_completion_target_not_found"));
    }

    private CompletionTarget lockTarget(UUID refundId, UUID orderId) {
        List<CompletionTarget> targets = repository.lockTarget(refundId, orderId)
                .stream().map(row -> new CompletionTarget(
                        row.refundId(), row.refundStatus(), row.refundNo(), row.refundType(),
                        row.fundingSource(), row.balanceRefundLedgerId(), row.packageInstanceId(),
                        row.orderId(), row.orderStatus(), row.tenantId(), row.userId(),
                        row.amount(), row.currency(), row.grantRequestId(), row.grantStatus()))
                .toList();
        if (targets.size() != 1) {
            throw new IllegalStateException("refund_completion_target_not_found");
        }
        return targets.getFirst();
    }

    public record RevocationResult(
            UUID packageInstanceId,
            String status,
            UUID activatedPackageInstanceId,
            String activatedPackageStatus) {
    }

    private record CompletionTarget(
            UUID refundId,
            String refundStatus,
            String refundNo,
            String refundType,
            String fundingSource,
            UUID balanceRefundLedgerId,
            UUID packageInstanceId,
            UUID orderId,
            String orderStatus,
            UUID tenantId,
            UUID userId,
            long amount,
            String currency,
            UUID grantRequestId,
            String grantStatus) {
    }
}
