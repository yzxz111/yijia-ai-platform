package com.yuzhihuihe.apiplatform.platform.subscription;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 管理端的订阅治理：查询、人工终止、人工重试。
 *
 * <p><b>刻意不提供「人工改价扣款」</b>：任何金额都来自
 * {@code package_plan_version.price_amount} 的快照，人工能改的只有「是否继续」。
 * 一个能人工定价的扣款入口既绕过了套餐目录的毛利守卫，也让金额与审计脱钩。
 */
@Service
public class AdminSubscriptionService {

    private static final Set<String> STATUSES = Set.of(
            "ACTIVE", "PAST_DUE", "CANCELED", "EXPIRED", "TERMINATED");

    private final AdminSubscriptionRepository repository;
    private final SubscriptionCycleRepository cycles;
    private final ObjectMapper objectMapper;

    AdminSubscriptionService(
            AdminSubscriptionRepository repository,
            SubscriptionCycleRepository cycles,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.cycles = cycles;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AdminSubscriptionRepository.PageResult<AdminSubscriptionRepository.AdminSubscriptionView> list(
            String status, String planCode, UUID userId, int page, int size) {
        if (page < 0) {
            throw invalid("invalid_page");
        }
        if (size < 1 || size > 100) {
            throw invalid("invalid_page_size");
        }
        if (page > Integer.MAX_VALUE / size) {
            throw invalid("invalid_page");
        }
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            throw invalid("invalid_subscription_status");
        }
        return repository.list(status, planCode, userId, page, size);
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID subscriptionId) {
        AdminSubscriptionRepository.AdminSubscriptionView view = repository.findView(subscriptionId)
                .orElseThrow(AdminSubscriptionService::notFound);
        List<SubscriptionService.CycleView> cycleViews = cycles.listBySubscription(subscriptionId).stream()
                .map(row -> new SubscriptionService.CycleView(
                        row.cycleId(), row.periodIndex(), row.periodStart(), row.periodEnd(),
                        row.status(), row.amount(), row.currency(), row.orderNo(),
                        row.chargedAt(), row.failureReason()))
                .toList();
        return new Detail(view, cycleViews);
    }

    /**
     * 人工终止。**原因必填**并写入审计。
     *
     * <p>不校验当前状态：管理端的场景恰恰包括「这个订阅状态不对，我要停掉它」，
     * 要求它处于某个特定状态才能停会挡住真正的运维需求。
     */
    @Transactional
    public AdminSubscriptionRepository.AdminSubscriptionView terminate(
            UUID subscriptionId, UUID actorUserId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw invalid("termination_reason_required");
        }
        AdminSubscriptionRepository.AdminSubscriptionView view = repository.findView(subscriptionId)
                .orElseThrow(AdminSubscriptionService::notFound);
        repository.terminateByAdmin(subscriptionId, "admin_terminated");
        repository.insertAudit(
                view.tenantId(), actorUserId, "SUBSCRIPTION_TERMINATED",
                subscriptionId.toString(),
                json(new TerminationDetail(reason.strip(), view.status())));
        return repository.findView(subscriptionId).orElseThrow(AdminSubscriptionService::notFound);
    }

    /**
     * 人工重试失败的一期。
     *
     * <p>幂等：账期不是 {@code FAILED} 时返回「未重试」而不报错——运维连点两次、
     * 或调度器刚好自己重试成功了，都不该看到一个错误弹窗。
     */
    @Transactional
    public RetryResult retry(UUID cycleId, UUID actorUserId, String reason) {
        SubscriptionCycleRepository.CycleRow cycle = cycles.findByCycleId(cycleId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "subscription_cycle_not_found"));
        AdminSubscriptionRepository.AdminSubscriptionView view =
                repository.findView(cycle.subscriptionId())
                        .orElseThrow(AdminSubscriptionService::notFound);

        int requeued = repository.requeueFailedCycle(cycleId);
        if (requeued == 0) {
            return new RetryResult(false, cycle.subscriptionId(), cycle.periodIndex(),
                    "cycle_not_failed");
        }
        // 账期可领取还不够：调度器的候选条件要求订阅处于 ACTIVE 或 PAST_DUE。
        int reopened = repository.reopenForRetry(cycle.subscriptionId());
        repository.insertAudit(
                view.tenantId(), actorUserId, "SUBSCRIPTION_RETRY_REQUESTED",
                cycleId.toString(),
                json(new RetryDetail(cycle.periodIndex(), reason, reopened)));
        return new RetryResult(true, cycle.subscriptionId(), cycle.periodIndex(),
                reopened == 0 ? "subscription_state_unchanged" : "requeued");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            // 审计详情序列化失败不该让整个管理操作失败：动作本身已经生效。
            return "{}";
        }
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "subscription_not_found");
    }

    public record Detail(
            AdminSubscriptionRepository.AdminSubscriptionView subscription,
            List<SubscriptionService.CycleView> cycles) {
    }

    public record RetryResult(boolean retried, UUID subscriptionId, int periodIndex, String outcome) {
    }

    /**
     * 审计详情。键名刻意与既有仓库约定一致（不含 phone/token/secret 等敏感键），
     * 也刻意不含金额——金额来自目录快照，写进审计只会产生一个可能与目录不一致的副本。
     */
    record TerminationDetail(String reason, String previousStatus) {
    }

    record RetryDetail(int periodIndex, String reason, int reopenedSubscriptions) {
    }
}
