package com.yuzhihuihe.apiplatform.platform.refund;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RefundApplicationService {

    private final RefundApplicationRepository repository;
    private final ObjectMapper objectMapper;
    private final RefundPolicyService policyService;
    private final RefundCalculator calculator;
    private final GatewayRefundClient gatewayRefundClient;
    private final RefundExecutionService executionService;
    private final CommercialTransactionMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    RefundApplicationService(
            RefundApplicationRepository repository,
            ObjectMapper objectMapper,
            RefundPolicyService policyService,
            GatewayRefundClient gatewayRefundClient,
            RefundExecutionService executionService,
            CommercialTransactionMetrics metrics,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.policyService = policyService;
        this.gatewayRefundClient = gatewayRefundClient;
        this.executionService = executionService;
        this.metrics = metrics;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.calculator = new RefundCalculator(clock);
    }

    public RefundView apply(
            UUID tenantId,
            UUID userId,
            String orderNo,
            ApplyRefundCommand command) {
        try {
            validateApply(tenantId, userId, orderNo, command);
            PreparingRefund preparing = transactionTemplate.execute(status ->
                    beginApplication(tenantId, userId, orderNo, command));
            if (preparing == null) {
                throw new IllegalStateException("refund_preparation_insert_failed");
            }
            RefundView result = transactionTemplate.execute(
                    status -> completePreparation(preparing));
            if (result == null) {
                throw new IllegalStateException("refund_preparation_completion_failed");
            }
            metrics.refundRequest(result.status());
            return result;
        } catch (RuntimeException exception) {
            metrics.refundRequest("FAILED");
            throw exception;
        }
    }

    private RefundView completePreparation(PreparingRefund preparing) {
        String currentStatus = repository.lockStatus(preparing.refundId());
        if (!"PREPARING".equals(currentStatus)) {
            return detailById(preparing.refundId());
        }
        RefundApplicationRepository.OrderRow order = preparing.order();
        RefundPolicyService.RefundPolicy policy = preparing.policy();

        GatewayRefundClient.RefundSnapshot gatewaySnapshot =
                gatewayRefundClient.prepare(preparing.businessNo(), order.grantRequestId());
        if ("WAITING".equals(gatewaySnapshot.status())) {
            return detailById(preparing.refundId());
        }
        try {
            RefundCalculator.RefundCalculation calculation = calculator.calculate(
                    new RefundCalculator.RefundInput(
                            order.paidAmount(),
                            gatewaySnapshot.totalTokens(),
                            gatewaySnapshot.usedTokens(),
                            policy.feeBps(),
                            policy.minimumFee(),
                            policy.maximumUsageBps(),
                            policy.refundWindowDays(),
                            policy.version(),
                            order.paidAt(),
                            preparing.requestedAt(),
                            gatewaySnapshot.packageExpiresAt(),
                            // 激活状态的两份独立记录：快照里网关自己的
                            // 套餐实例状态，以及平台的权益发放行，
                            // 发件箱发布器仅在网关确认 ACTIVE 后
                            // 才把它置为完成。
                            RefundCalculator.activated(
                                    gatewaySnapshot.originalStatus(),
                                    order.grantStatus(),
                                    order.grantCompletedAt())));
            CalculationSnapshot immutableSnapshot = new CalculationSnapshot(
                    order.paidAmount(),
                    order.paidAt(),
                    preparing.requestedAt(),
                    gatewaySnapshot.totalTokens(),
                    gatewaySnapshot.usedTokens(),
                    gatewaySnapshot.remainingTokens(),
                    policy.feeBps(),
                    policy.minimumFee(),
                    policy.maximumUsageBps(),
                    policy.refundWindowDays(),
                    calculation.baseRefund(),
                    calculation.fee(),
                    calculation.refundAmount(),
                    calculation.refundType().name(),
                    calculation.rounding(),
                    policy.version(),
                    calculation.calculatedAt(),
                    gatewaySnapshot.packageInstanceId(),
                    gatewaySnapshot.originalStatus(),
                    gatewaySnapshot.packageExpiresAt(),
                    gatewaySnapshot.snapshotAt());
            int updated = repository.markPrepared(
                    preparing.refundId(), calculation.refundType().name(), policy.version(),
                    calculation.refundAmount(), json(immutableSnapshot));
            if (updated != 1) {
                throw new IllegalStateException("refund_preparation_state_changed");
            }
        } catch (ResponseStatusException exception) {
            String reason = exception.getReason();
            if (!eligibilityReason(reason)) {
                throw exception;
            }
            gatewayRefundClient.release(preparing.businessNo(), order.grantRequestId());
            int updated = repository.rejectPreparation(preparing.refundId(), reason);
            if (updated != 1) {
                throw new IllegalStateException("refund_rejection_state_changed");
            }
        }
        return detailById(preparing.refundId());
    }

    private PreparingRefund beginApplication(
            UUID tenantId,
            UUID userId,
            String orderNo,
            ApplyRefundCommand command) {
        RefundApplicationRepository.OrderRow order = lockPaidOrder(tenantId, userId, orderNo);
        if (repository.hasOpenRefund(order.id())) {
            throw conflict("open_refund_exists");
        }
        Instant requestedAt = clock.instant();
        RefundPolicyService.RefundPolicy policy = policyService.effectiveAt(requestedAt);
        String refundNo = "ref_" + UUID.randomUUID().toString().replace("-", "");
        String businessNo = "refund-hold:" + refundNo;
        UUID refundId = UUID.randomUUID();
        repository.insertRefund(
                refundId, refundNo, order, command.reason().trim(), userId,
                businessNo, policy.version(), requestedAt);
        repository.auditApplied(order.tenantId(), userId, refundNo, order.orderNo());
        return new PreparingRefund(
                refundId, businessNo, order, policy, requestedAt);
    }

    @Transactional
    public RefundView cancel(UUID tenantId, UUID userId, String refundNo) {
        validateIdentity(tenantId, userId, refundNo);
        RefundRow refund = lockOwnedRefund(tenantId, userId, refundNo);
        if ("CANCELED".equals(refund.status())) {
            return view(refund);
        }
        if (!("PREPARING".equals(refund.status())
                || "PENDING_APPROVAL".equals(refund.status()))) {
            throw conflict("refund_cannot_be_canceled");
        }
        gatewayRefundClient.release(refund.gatewayHoldBusinessNo(), refund.grantRequestId());
        repository.cancel(refund.id());
        return detailById(refund.id());
    }

    public RefundView detail(UUID tenantId, UUID userId, String refundNo) {
        validateIdentity(tenantId, userId, refundNo);
        return view(findOwnedRefund(tenantId, userId, refundNo, false));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public RefundView adminDetail(String refundNo) {
        if (invalidRefundNo(refundNo)) {
            throw unprocessable("invalid_refund_admin_request");
        }
        return view(findRefund(refundNo, false));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public RefundView approve(UUID actorId, String refundNo) {
        validateAdmin(actorId, refundNo);
        Instant startedAt = clock.instant();
        RefundView approved;
        try {
            RefundRow pending = transactionTemplate.execute(status -> approvalCandidate(refundNo));
            if (pending == null) {
                throw new IllegalStateException("refund_approval_candidate_missing");
            }
            GatewayRefundClient.RefundSnapshot current = gatewayRefundClient.prepare(
                    pending.gatewayHoldBusinessNo(), pending.grantRequestId());
            approved = transactionTemplate.execute(
                    status -> commitApproval(actorId, refundNo, current));
            if (approved == null) {
                throw new IllegalStateException("refund_approval_commit_failed");
            }
        } catch (RuntimeException exception) {
            metrics.refundApprovalDuration(
                    "REJECTED", java.time.Duration.between(startedAt, clock.instant()));
            throw exception;
        }
        metrics.refundDecision("APPROVED");
        metrics.refundApprovalDuration(
                "APPROVED", java.time.Duration.between(startedAt, clock.instant()));
        executionService.submit(refundNo);
        return approved;
    }

    private RefundRow approvalCandidate(String refundNo) {
        RefundRow refund = lockRefund(refundNo);
        if (!"PENDING_APPROVAL".equals(refund.status()) || refund.calculation() == null) {
            throw conflict("refund_cannot_be_approved");
        }
        return refund;
    }

    private RefundView commitApproval(
            UUID actorId,
            String refundNo,
            GatewayRefundClient.RefundSnapshot current) {
        RefundRow refund = lockRefund(refundNo);
        if (!"PENDING_APPROVAL".equals(refund.status()) || refund.calculation() == null) {
            throw conflict("refund_cannot_be_approved");
        }
        if (!sameSnapshot(refund.calculation(), current)) {
            throw conflict("refund_snapshot_changed");
        }
        Instant reviewedAt = clock.instant();
        int updated = repository.approve(refund.id(), actorId, reviewedAt);
        if (updated != 1) {
            throw new IllegalStateException("refund_approval_state_changed");
        }
        repository.auditApproved(
                refund.tenantId(), actorId, refund.refundNo(),
                refund.amount(), refund.refundType());
        return detailById(refund.id());
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RefundView reject(UUID actorId, String refundNo, RejectRefundCommand command) {
        validateAdmin(actorId, refundNo);
        validateReviewNote(command);
        RefundRow refund = lockRefund(refundNo);
        if ("REJECTED".equals(refund.status())) {
            return view(refund);
        }
        if (!"PENDING_APPROVAL".equals(refund.status())) {
            throw conflict("refund_cannot_be_rejected");
        }
        gatewayRefundClient.release(refund.gatewayHoldBusinessNo(), refund.grantRequestId());
        Instant reviewedAt = clock.instant();
        repository.reject(refund.id(), actorId, reviewedAt, command.reviewNote().trim());
        repository.auditRejected(
                refund.tenantId(), actorId, refund.refundNo(), refund.amount());
        RefundView rejected = detailById(refund.id());
        metrics.refundDecision("REJECTED");
        return rejected;
    }

    private RefundApplicationRepository.OrderRow lockPaidOrder(
            UUID tenantId, UUID userId, String orderNo) {
        return repository.lockPaidOrder(tenantId, userId, orderNo)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "paid_order_not_found"));
    }

    private RefundRow lockOwnedRefund(UUID tenantId, UUID userId, String refundNo) {
        return findOwnedRefund(tenantId, userId, refundNo, true);
    }

    private RefundRow findOwnedRefund(
            UUID tenantId, UUID userId, String refundNo, boolean lock) {
        return repository.findOwned(tenantId, userId, refundNo, lock)
                .map(this::refundRow)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "refund_not_found"));
    }

    private RefundRow lockRefund(String refundNo) {
        return findRefund(refundNo, true);
    }

    private RefundRow findRefund(String refundNo, boolean lock) {
        return repository.find(refundNo, lock)
                .map(this::refundRow)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "refund_not_found"));
    }

    private RefundView detailById(UUID refundId) {
        return repository.findById(refundId)
                .map(this::refundRow)
                .map(this::view)
                .orElseThrow(() -> new IllegalStateException("refund_not_found_after_update"));
    }

    private RefundRow refundRow(RefundApplicationRepository.RefundRow row) {
        String snapshot = row.calculationSnapshotJson();
        return new RefundRow(
                row.id(), row.refundNo(), row.orderNo(), row.tenantId(), row.userId(),
                row.status(), row.refundType(), row.amount(), row.reason(),
                snapshot == null ? null : readSnapshot(snapshot),
                row.reviewedBy(), row.reviewedAt(), row.reviewNote(), row.failureReason(),
                row.gatewayHoldBusinessNo(), row.grantRequestId(),
                row.createdAt(), row.updatedAt());
    }

    private RefundView view(RefundRow row) {
        return new RefundView(
                row.refundNo(), row.orderNo(), row.status(), row.refundType(), row.amount(),
                row.reason(), row.calculation(), row.reviewedBy(), row.reviewedAt(),
                row.reviewNote(), row.failureReason(), row.createdAt(), row.updatedAt());
    }

    private boolean sameSnapshot(
            CalculationSnapshot expected, GatewayRefundClient.RefundSnapshot actual) {
        return actual != null
                && "READY".equals(actual.status())
                && Objects.equals(expected.packageInstanceId(), actual.packageInstanceId())
                && Objects.equals(expected.originalPackageStatus(), actual.originalStatus())
                && Objects.equals(expected.totalTokens(), actual.totalTokens())
                && Objects.equals(expected.usedTokens(), actual.usedTokens())
                && Objects.equals(expected.remainingTokens(), actual.remainingTokens())
                && Objects.equals(expected.packageExpiresAt(), actual.packageExpiresAt())
                && Objects.equals(expected.gatewaySnapshotAt(), actual.snapshotAt());
    }

    private String json(CalculationSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("refund_snapshot_serialization_failed", exception);
        }
    }

    private CalculationSnapshot readSnapshot(String value) {
        try {
            return objectMapper.readValue(value, CalculationSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("refund_snapshot_deserialization_failed", exception);
        }
    }

    private void validateApply(
            UUID tenantId, UUID userId, String orderNo, ApplyRefundCommand command) {
        if (tenantId == null
                || userId == null
                || orderNo == null
                || orderNo.isBlank()
                || orderNo.length() > 64
                || command == null
                || command.reason() == null
                || command.reason().isBlank()
                || command.reason().length() > 500) {
            throw unprocessable("invalid_refund_application");
        }
    }

    private void validateIdentity(UUID tenantId, UUID userId, String refundNo) {
        if (tenantId == null || userId == null || invalidRefundNo(refundNo)) {
            throw unprocessable("invalid_refund_request");
        }
    }

    private void validateAdmin(UUID actorId, String refundNo) {
        if (actorId == null || invalidRefundNo(refundNo)) {
            throw unprocessable("invalid_refund_admin_request");
        }
    }

    private void validateReviewNote(RejectRefundCommand command) {
        if (command == null
                || command.reviewNote() == null
                || command.reviewNote().isBlank()
                || command.reviewNote().length() > 500) {
            throw unprocessable("invalid_refund_review_note");
        }
    }

    private boolean invalidRefundNo(String refundNo) {
        return refundNo == null || refundNo.isBlank() || refundNo.length() > 64;
    }

    /**
     * 计算器或快照拒绝时可以作为持久化的 REJECTED 退款携带的拒绝原因，
     * 而不是一个必须重试的不确定状态。这个列表过去携带的部分退款原因
     * （USAGE_LIMIT_EXCEEDED、REFUND_AMOUNT_NOT_POSITIVE）已无法再产生：
     * 退款仅在套餐被激活之前可用，
     * 而该退款始终是全额。
     */
    private boolean eligibilityReason(String reason) {
        return "REFUND_WINDOW_EXPIRED".equals(reason)
                || "PACKAGE_EXPIRED".equals(reason)
                || "PACKAGE_ACTIVATED".equals(reason);
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public record ApplyRefundCommand(String reason) {
    }

    public record RejectRefundCommand(String reviewNote) {
    }

    public record CalculationSnapshot(
            long paidAmount,
            Instant paidAt,
            Instant requestedAt,
            long totalTokens,
            long usedTokens,
            long remainingTokens,
            int feeBps,
            long minimumFee,
            int maximumUsageBps,
            int refundWindowDays,
            long baseRefund,
            long fee,
            long refundAmount,
            String refundType,
            String rounding,
            int policyVersion,
            Instant calculatedAt,
            UUID packageInstanceId,
            String originalPackageStatus,
            Instant packageExpiresAt,
            Instant gatewaySnapshotAt) {
    }

    public record RefundView(
            String refundNo,
            String orderNo,
            String status,
            String refundType,
            long amount,
            String reason,
            CalculationSnapshot calculation,
            UUID reviewedBy,
            Instant reviewedAt,
            String reviewNote,
            String failureReason,
            Instant createdAt,
            Instant updatedAt) {
    }

    private record PreparingRefund(
            UUID refundId,
            String businessNo,
            RefundApplicationRepository.OrderRow order,
            RefundPolicyService.RefundPolicy policy,
            Instant requestedAt) {
    }

    private record RefundRow(
            UUID id,
            String refundNo,
            String orderNo,
            UUID tenantId,
            UUID userId,
            String status,
            String refundType,
            long amount,
            String reason,
            CalculationSnapshot calculation,
            UUID reviewedBy,
            Instant reviewedAt,
            String reviewNote,
            String failureReason,
            String gatewayHoldBusinessNo,
            UUID grantRequestId,
            Instant createdAt,
            Instant updatedAt) {
    }
}
