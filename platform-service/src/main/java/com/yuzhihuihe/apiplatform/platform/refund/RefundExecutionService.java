package com.yuzhihuihe.apiplatform.platform.refund;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAvailabilityService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefundExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefundExecutionService.class);
    private static final String REVOCATION_PREFIX = "refund-revoke:";

    private final RefundExecutionRepository repository;
    private final ObjectMapper objectMapper;
    private final PaymentAvailabilityService availabilityService;
    private final GatewayRefundClient gatewayRefundClient;
    private final CommercialTransactionMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final URI notifyUrl;
    private final Duration claimTimeout;

    RefundExecutionService(
            RefundExecutionRepository repository,
            ObjectMapper objectMapper,
            PaymentAvailabilityService availabilityService,
            GatewayRefundClient gatewayRefundClient,
            CommercialTransactionMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${refund.provider-notify-url:https://platform-service/console/platform/refunds/wechat-pay/callback}")
            URI notifyUrl,
            @Value("${refund.jobs.claim-timeout:30s}") Duration claimTimeout) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.availabilityService = availabilityService;
        this.gatewayRefundClient = gatewayRefundClient;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        if (notifyUrl == null || !"https".equalsIgnoreCase(notifyUrl.getScheme())) {
            throw new IllegalArgumentException("refund provider notify URL must use HTTPS");
        }
        if (claimTimeout == null || claimTimeout.isZero() || claimTimeout.isNegative()) {
            throw new IllegalArgumentException("refund claim timeout must be positive");
        }
        this.notifyUrl = notifyUrl;
        this.claimTimeout = claimTimeout;
    }

    public ExecutionResult submit(String refundNo) {
        ClaimedRefund claim = transactions.execute(
                ignored -> claim(refundNo, "SUBMITTING"));
        return claim == null
                ? currentResult(refundNo)
                : execute(claim, Operation.SUBMIT, false);
    }

    public ExecutionResult query(String refundNo) {
        ClaimedRefund claim = transactions.execute(
                ignored -> claim(refundNo, "PROCESSING"));
        return claim == null
                ? currentResult(refundNo)
                : execute(claim, Operation.QUERY, false);
    }

    ExecutionResult submitClaimed(String refundNo, UUID claimToken) {
        ClaimedRefund claim = transactions.execute(
                ignored -> loadClaimed(refundNo, claimToken, "SUBMITTING"));
        return claim == null
                ? currentResult(refundNo)
                : execute(claim, Operation.SUBMIT, true);
    }

    ExecutionResult queryClaimed(String refundNo, UUID claimToken) {
        ClaimedRefund claim = transactions.execute(
                ignored -> loadClaimed(refundNo, claimToken, "PROCESSING"));
        return claim == null
                ? currentResult(refundNo)
                : execute(claim, Operation.QUERY, true);
    }

    private ExecutionResult execute(
            ClaimedRefund claim, Operation operation, boolean preserveClaim) {
        if ("BALANCE".equals(claim.fundingSource())) {
            ExecutionResult routed = transactions.execute(
                    ignored -> routeBalanceToRevocation(claim, preserveClaim));
            return routed == null ? currentResult(claim.refundNo()) : routed;
        }
        PaymentAdapter adapter;
        try {
            adapter = availabilityService.requireOperational(
                    claim.provider(), claim.product());
        } catch (RuntimeException exception) {
            logUncertain(claim, operation, exception);
            PaymentAdapter.ProviderRefund unavailable =
                    uncertain(claim, "ADAPTER_UNAVAILABLE");
            metrics.refundResult(claim.provider(), unavailable.outcome());
            ExecutionResult deferred = transactions.execute(ignored ->
                    applyResult(claim, unavailable, preserveClaim, false));
            return deferred == null ? currentResult(claim.refundNo()) : deferred;
        }

        PaymentAdapter.ProviderRefund providerRefund;
        try {
            providerRefund = operation == Operation.SUBMIT
                    ? adapter.createRefund(new PaymentAdapter.RefundCommand(
                            claim.transactionNo(), claim.externalTradeNo(), claim.refundNo(),
                            claim.totalAmount(), claim.refundAmount(), claim.currency(),
                            claim.reason(), notifyUrl))
                    : adapter.queryRefund(new PaymentAdapter.RefundQuery(
                            claim.transactionNo(), claim.externalTradeNo(), claim.refundNo(),
                            claim.refundAmount(), claim.currency()));
            if (providerRefund == null) {
                providerRefund = uncertain(claim, "NULL_PROVIDER_RESULT");
            }
        } catch (RuntimeException exception) {
            logUncertain(claim, operation, exception);
            providerRefund = uncertain(claim, "ADAPTER_EXCEPTION");
        }
        metrics.refundResult(claim.provider(), providerRefund.outcome());

        if (providerMatches(claim, providerRefund)
                && definiteFailure(providerRefund.outcome())) {
            try {
                gatewayRefundClient.release(
                        claim.gatewayHoldBusinessNo(), claim.grantRequestId());
            } catch (RuntimeException exception) {
                LOGGER.atWarn()
                        .addKeyValue("refundNo", claim.refundNo())
                        .addKeyValue("operation", operation.name())
                        .addKeyValue("exception", exception.getClass().getSimpleName())
                        .log("refund hold release is uncertain");
                providerRefund = uncertain(claim, "HOLD_RELEASE_UNCERTAIN");
            }
        }

        PaymentAdapter.ProviderRefund finalProviderRefund = providerRefund;
        ExecutionResult applied = transactions.execute(ignored ->
                applyResult(claim, finalProviderRefund, preserveClaim, true));
        return applied == null ? currentResult(claim.refundNo()) : applied;
    }

    private ClaimedRefund claim(String refundNo, String expectedStatus) {
        validateRefundNo(refundNo);
        ClaimedRefund current = selectRefund(refundNo, null, expectedStatus, true);
        if (current == null) {
            return null;
        }
        Instant now = clock.instant();
        if (current.claimedUntil() != null && current.claimedUntil().isAfter(now)) {
            return null;
        }
        UUID token = UUID.randomUUID();
        Instant claimedUntil = now.plus(claimTimeout);
        int updated = repository.claim(
                current.refundId(), expectedStatus, token, claimedUntil, now);
        if (updated != 1) {
            return null;
        }
        return current.withClaim(token, claimedUntil);
    }

    private ClaimedRefund loadClaimed(
            String refundNo, UUID claimToken, String expectedStatus) {
        validateRefundNo(refundNo);
        if (claimToken == null) {
            throw new IllegalArgumentException("refund claim token is required");
        }
        return selectRefund(refundNo, claimToken, expectedStatus, false);
    }

    private ClaimedRefund selectRefund(
            String refundNo, UUID claimToken, String expectedStatus, boolean lock) {
        return repository.findClaim(refundNo, claimToken, expectedStatus, lock)
                .map(this::claimedRefund)
                .orElse(null);
    }

    private ExecutionResult routeBalanceToRevocation(
            ClaimedRefund claim, boolean preserveClaim) {
        String businessKey = REVOCATION_PREFIX + claim.refundNo();
        int updated = repository.routeBalanceToRevocation(
                claim.refundId(), claim.status(), claim.claimToken(),
                businessKey, preserveClaim);
        if (updated == 1) {
            insertRevocationEvent(claim, businessKey);
            return new ExecutionResult("REVOKING", false);
        }
        return currentResult(claim.refundNo());
    }

    private ExecutionResult applyResult(
            ClaimedRefund claim,
            PaymentAdapter.ProviderRefund providerRefund,
            boolean preserveClaim,
            boolean providerCallAttempted) {
        String currentStatus = repository.lockCurrentStatus(
                claim.refundId(), claim.claimToken()).orElse(null);
        if (currentStatus == null || !claim.status().equals(currentStatus)) {
            return currentStatus == null
                    ? currentResult(claim.refundNo())
                    : new ExecutionResult(currentStatus, false);
        }

        if (!providerMatches(claim, providerRefund)) {
            recordProviderMismatch(claim, providerRefund);
            updateProviderState(
                    claim, currentStatus, currentStatus, providerRefund,
                    preserveClaim, true, null);
            return new ExecutionResult(currentStatus, true);
        }

        if (!providerCallAttempted) {
            updateProviderState(
                    claim, currentStatus, currentStatus, providerRefund,
                    preserveClaim, true, null);
            return new ExecutionResult(currentStatus, true);
        }

        if (providerRefund.outcome() == ProviderOutcome.SUCCEEDED) {
            String businessKey = REVOCATION_PREFIX + claim.refundNo();
            int updated = updateProviderState(
                    claim, currentStatus, "REVOKING", providerRefund,
                    preserveClaim, false, businessKey);
            if (updated == 1) {
                insertRevocationEvent(claim, businessKey);
            }
            return new ExecutionResult("REVOKING", false);
        }
        if (definiteFailure(providerRefund.outcome())) {
            updateProviderState(
                    claim, currentStatus, "FAILED", providerRefund,
                    preserveClaim, false, null);
            return new ExecutionResult("FAILED", false);
        }

        updateProviderState(
                claim, currentStatus, "PROCESSING", providerRefund,
                preserveClaim, true, null);
        return new ExecutionResult("PROCESSING", true);
    }

    private int updateProviderState(
            ClaimedRefund claim,
            String expectedStatus,
            String nextStatus,
            PaymentAdapter.ProviderRefund providerRefund,
            boolean preserveClaim,
            boolean retry,
            String revocationBusinessNo) {
        return repository.updateProviderState(
                claim.refundId(), expectedStatus, claim.claimToken(), nextStatus,
                providerRefund.externalRefundNo(), providerRefund.providerStatus(),
                providerRefund.providerErrorCode(), revocationBusinessNo,
                retry, preserveClaim);
    }

    private void insertRevocationEvent(ClaimedRefund claim, String businessKey) {
        RefundRevocationPayload payload = new RefundRevocationPayload(
                claim.refundNo(), businessKey, claim.grantRequestId(), claim.userId());
        repository.insertRevocationEvent(
                UUID.randomUUID(), claim.refundId(), businessKey, json(payload));
    }

    private void recordProviderMismatch(
            ClaimedRefund claim, PaymentAdapter.ProviderRefund providerRefund) {
        String reason = !claim.refundNo().equals(providerRefund.refundNo())
                ? "REFUND_NUMBER_MISMATCH" : "REFUND_AMOUNT_MISMATCH";
        String fingerprint = sha256(String.join(
                "\n", "REFUND_PROVIDER_MISMATCH", claim.provider().name(),
                claim.refundNo(), reason));
        repository.insertProviderMismatch(
                UUID.randomUUID(), fingerprint, claim.provider().name(), claim.orderId(),
                claim.paymentTransactionId(), claim.refundId(), reason,
                providerRefund.providerStatus());
    }

    private ExecutionResult currentResult(String refundNo) {
        validateRefundNo(refundNo);
        return repository.findCurrentStatus(refundNo)
                .map(status -> new ExecutionResult(status, false))
                .orElse(new ExecutionResult("NOT_FOUND", false));
    }

    private ClaimedRefund claimedRefund(RefundExecutionRepository.ClaimRow row) {
        return new ClaimedRefund(
                row.refundId(), row.refundNo(), row.status(), row.refundAmount(),
                row.reason(), row.fundingSource(), row.gatewayHoldBusinessNo(),
                row.claimToken(), row.claimedUntil(), row.orderId(), row.tenantId(),
                row.userId(), row.totalAmount(), row.currency(), row.paymentTransactionId(),
                row.transactionNo(), row.externalTradeNo(),
                row.provider() == null ? null : PaymentProvider.valueOf(row.provider()),
                row.product() == null ? null : PaymentProduct.valueOf(row.product()),
                row.grantRequestId());
    }

    private PaymentAdapter.ProviderRefund uncertain(ClaimedRefund claim, String providerStatus) {
        return new PaymentAdapter.ProviderRefund(
                ProviderOutcome.UNCERTAIN, claim.refundNo(), null, claim.refundAmount(),
                providerStatus, null);
    }

    private boolean definiteFailure(ProviderOutcome outcome) {
        return outcome == ProviderOutcome.FAILED
                || outcome == ProviderOutcome.CLOSED
                || outcome == ProviderOutcome.NOT_FOUND;
    }

    private boolean providerMatches(
            ClaimedRefund claim, PaymentAdapter.ProviderRefund providerRefund) {
        return claim.refundNo().equals(providerRefund.refundNo())
                && claim.refundAmount() == providerRefund.amount();
    }

    private void logUncertain(
            ClaimedRefund claim, Operation operation, RuntimeException exception) {
        LOGGER.atWarn()
                .addKeyValue("refundNo", claim.refundNo())
                .addKeyValue("operation", operation.name())
                .addKeyValue("exception", exception.getClass().getSimpleName())
                .log("refund provider operation is uncertain");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("refund_outbox_serialization_failed", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("refund_anomaly_hash_failed", exception);
        }
    }

    private void validateRefundNo(String refundNo) {
        if (refundNo == null || refundNo.isBlank() || refundNo.length() > 64) {
            throw new IllegalArgumentException("invalid refund number");
        }
    }

    public record ExecutionResult(String refundStatus, boolean retryScheduled) {
    }

    public record RefundRevocationPayload(
            String refundNo,
            String revocationBusinessNo,
            UUID grantRequestId,
            UUID userId) {
    }

    private enum Operation {
        SUBMIT,
        QUERY
    }

    private record ClaimedRefund(
            UUID refundId,
            String refundNo,
            String status,
            long refundAmount,
            String reason,
            String fundingSource,
            String gatewayHoldBusinessNo,
            UUID claimToken,
            Instant claimedUntil,
            UUID orderId,
            UUID tenantId,
            UUID userId,
            long totalAmount,
            String currency,
            UUID paymentTransactionId,
            String transactionNo,
            String externalTradeNo,
            PaymentProvider provider,
            PaymentProduct product,
            UUID grantRequestId) {

        private ClaimedRefund withClaim(UUID token, Instant until) {
            return new ClaimedRefund(
                    refundId, refundNo, status, refundAmount, reason, fundingSource,
                    gatewayHoldBusinessNo, token, until, orderId, tenantId, userId,
                    totalAmount, currency, paymentTransactionId, transactionNo,
                    externalTradeNo, provider, product, grantRequestId);
        }
    }
}
