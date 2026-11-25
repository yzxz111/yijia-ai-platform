package com.yuzhihuihe.apiplatform.platform.refund;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RefundPreparationJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefundPreparationJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayRefundClient gatewayRefundClient;
    private final CommercialTransactionMetrics metrics;
    private final TransactionTemplate transactions;
    private final RefundCalculator calculator;
    private final Clock clock;
    private final int batchSize;
    private final Duration claimTimeout;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int maxAttempts;

    public RefundPreparationJob(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            GatewayRefundClient gatewayRefundClient,
            CommercialTransactionMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${refund.jobs.batch-size:50}") int batchSize,
            @Value("${refund.jobs.claim-timeout:30s}") Duration claimTimeout,
            @Value("${refund.jobs.initial-backoff:5s}") Duration initialBackoff,
            @Value("${refund.jobs.max-backoff:1h}") Duration maxBackoff,
            @Value("${refund.jobs.max-attempts:12}") int maxAttempts) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.gatewayRefundClient = gatewayRefundClient;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.calculator = new RefundCalculator(clock);
        this.batchSize = bounded(batchSize, 1, 50, "refund job batch size");
        this.claimTimeout = positive(claimTimeout, "refund job claim timeout");
        this.initialBackoff = positive(initialBackoff, "refund job initial backoff");
        this.maxBackoff = positive(maxBackoff, "refund job maximum backoff");
        this.maxAttempts = bounded(maxAttempts, 1, 100, "refund job maximum attempts");
        if (initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("refund job backoff range is invalid");
        }
    }

    @Scheduled(
            fixedDelayString = "${refund.jobs.preparation-delay-ms:5000}",
            initialDelayString = "${refund.jobs.preparation-delay-ms:5000}")
    public void scheduledRun() {
        runOnce();
    }

    public int runOnce() {
        int processed = 0;
        while (processed < batchSize) {
            ClaimBatch batch = transactions.execute(ignored -> claim());
            if (batch == null || batch.claims().isEmpty()) {
                break;
            }
            PreparationClaim claim = batch.claims().getFirst();
            if (claim.exhaustedRecovery()) {
                retry(batch.token(), claim, "MAXIMUM_ATTEMPTS");
            } else {
                prepare(batch.token(), claim);
            }
            processed++;
        }
        return processed;
    }

    private ClaimBatch claim() {
        UUID token = UUID.randomUUID();
        Instant now = clock.instant();
        List<PreparationClaim> claims = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT refund.id,
                           refund.provider_attempt_count = ? AS exhausted_recovery
                    FROM platform.refund AS refund
                    WHERE refund.status = 'PREPARING'
                      AND refund.next_attempt_at <= ?
                      AND (refund.provider_attempt_count < ? OR (
                            refund.provider_attempt_count = ?
                            AND refund.job_claim_token IS NOT NULL))
                      AND (refund.job_claimed_until IS NULL OR refund.job_claimed_until <= ?)
                    ORDER BY refund.next_attempt_at, refund.id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE platform.refund AS refund
                SET job_claim_token = ?, job_claimed_until = ?,
                    provider_attempt_count = CASE WHEN candidate.exhausted_recovery
                        THEN refund.provider_attempt_count
                        ELSE refund.provider_attempt_count + 1 END,
                    updated_at = now()
                FROM candidate, platform.customer_order AS customer_order,
                     platform.grant_request AS grant_request,
                     platform.refund_policy_version AS policy
                WHERE refund.id = candidate.id
                  AND customer_order.id = refund.order_id
                  AND grant_request.business_no = 'order:' || customer_order.order_no
                  AND policy.version = refund.policy_version
                RETURNING refund.id, refund.refund_no, refund.gateway_hold_business_no,
                          refund.provider_attempt_count, refund.created_at,
                          customer_order.id AS order_id,
                          customer_order.tenant_id, customer_order.user_id,
                          customer_order.amount AS paid_amount, customer_order.paid_at,
                          grant_request.id AS grant_request_id,
                           grant_request.status AS grant_status,
                           grant_request.completed_at AS grant_completed_at,
                           policy.version AS policy_version, policy.fee_bps,
                           policy.minimum_fee, policy.refund_window_days,
                           policy.maximum_usage_bps,
                           candidate.exhausted_recovery
                """, (result, rowNumber) -> new PreparationClaim(
                        result.getObject("id", UUID.class),
                        result.getString("refund_no"),
                        result.getString("gateway_hold_business_no"),
                        result.getInt("provider_attempt_count"),
                        result.getObject("created_at", OffsetDateTime.class).toInstant(),
                        result.getObject("order_id", UUID.class),
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("user_id", UUID.class),
                        result.getLong("paid_amount"),
                        result.getObject("paid_at", OffsetDateTime.class).toInstant(),
                        result.getObject("grant_request_id", UUID.class),
                        result.getString("grant_status"),
                        instant(result.getObject("grant_completed_at", OffsetDateTime.class)),
                        result.getInt("policy_version"),
                        result.getInt("fee_bps"),
                        result.getLong("minimum_fee"),
                        result.getInt("refund_window_days"),
                        result.getInt("maximum_usage_bps"),
                        result.getBoolean("exhausted_recovery")),
                maxAttempts, Timestamp.from(now), maxAttempts, maxAttempts,
                Timestamp.from(now), token, Timestamp.from(now.plus(claimTimeout)));
        return new ClaimBatch(token, List.copyOf(claims));
    }

    private void prepare(UUID token, PreparationClaim claim) {
        GatewayRefundClient.RefundSnapshot snapshot;
        try {
            snapshot = gatewayRefundClient.prepare(
                    claim.gatewayHoldBusinessNo(), claim.grantRequestId());
        } catch (RuntimeException exception) {
            retry(token, claim, "GATEWAY_PREPARE_UNCERTAIN");
            return;
        }
        if ("WAITING".equals(snapshot.status())) {
            metrics.refundHoldDuration(
                    "WAITING", Duration.between(claim.requestedAt(), clock.instant()));
            retry(token, claim, "GATEWAY_HOLD_WAITING");
            return;
        }

        RefundCalculator.RefundCalculation calculation;
        try {
            calculation = calculator.calculate(new RefundCalculator.RefundInput(
                    claim.paidAmount(), snapshot.totalTokens(), snapshot.usedTokens(),
                    claim.feeBps(), claim.minimumFee(), claim.maximumUsageBps(),
                    claim.refundWindowDays(), claim.policyVersion(), claim.paidAt(),
                    claim.requestedAt(), snapshot.packageExpiresAt(),
                    RefundCalculator.activated(
                            snapshot.originalStatus(), claim.grantStatus(),
                            claim.grantCompletedAt())));
        } catch (ResponseStatusException exception) {
            if (!eligibilityReason(exception.getReason())) {
                retry(token, claim, "CALCULATION_UNCERTAIN");
                return;
            }
            releaseIneligible(token, claim, exception.getReason());
            return;
        } catch (RuntimeException exception) {
            retry(token, claim, "CALCULATION_UNCERTAIN");
            return;
        }

        RefundApplicationService.CalculationSnapshot immutableSnapshot =
                new RefundApplicationService.CalculationSnapshot(
                        claim.paidAmount(), claim.paidAt(), claim.requestedAt(),
                        snapshot.totalTokens(), snapshot.usedTokens(), snapshot.remainingTokens(),
                        claim.feeBps(), claim.minimumFee(), claim.maximumUsageBps(),
                        claim.refundWindowDays(), calculation.baseRefund(), calculation.fee(),
                        calculation.refundAmount(), calculation.refundType().name(),
                        calculation.rounding(), claim.policyVersion(), calculation.calculatedAt(),
                        snapshot.packageInstanceId(), snapshot.originalStatus(),
                        snapshot.packageExpiresAt(), snapshot.snapshotAt());
        transactions.executeWithoutResult(ignored -> complete(
                token, claim, calculation, immutableSnapshot));
    }

    private void complete(
            UUID token,
            PreparationClaim claim,
            RefundCalculator.RefundCalculation calculation,
            RefundApplicationService.CalculationSnapshot snapshot) {
        int updated = jdbcTemplate.update("""
                UPDATE platform.refund
                SET status = 'PENDING_APPROVAL', refund_type = ?, amount = ?,
                    calculation_snapshot = ?::jsonb,
                    provider_status = 'PREPARED', provider_error_code = NULL,
                    job_claim_token = NULL, job_claimed_until = NULL,
                    updated_at = now(), lock_version = lock_version + 1
                WHERE id = ? AND status = 'PREPARING' AND job_claim_token = ?
                """, calculation.refundType().name(), calculation.refundAmount(),
                json(snapshot), claim.refundId(), token);
        if (updated > 0) {
            metrics.refundHoldDuration(
                    "READY", Duration.between(claim.requestedAt(), clock.instant()));
        }
    }

    private void releaseIneligible(UUID token, PreparationClaim claim, String reason) {
        try {
            gatewayRefundClient.release(
                    claim.gatewayHoldBusinessNo(), claim.grantRequestId());
        } catch (RuntimeException exception) {
            retry(token, claim, "GATEWAY_RELEASE_UNCERTAIN");
            return;
        }
        transactions.executeWithoutResult(ignored -> {
            int updated = jdbcTemplate.update("""
                    UPDATE platform.refund
                    SET status = 'REJECTED', provider_error_code = ?,
                        job_claim_token = NULL, job_claimed_until = NULL,
                        updated_at = now(), lock_version = lock_version + 1
                    WHERE id = ? AND status = 'PREPARING' AND job_claim_token = ?
                    """, reason, claim.refundId(), token);
            if (updated > 0) {
                metrics.refundHoldDuration(
                        "RELEASED", Duration.between(claim.requestedAt(), clock.instant()));
            }
        });
    }

    private void retry(UUID token, PreparationClaim claim, String reason) {
        transactions.executeWithoutResult(ignored -> {
            if (!ownsClaim(claim.refundId(), token)) {
                return;
            }
            if (claim.attempt() >= maxAttempts) {
                jdbcTemplate.update("""
                        UPDATE platform.refund
                        SET next_attempt_at = 'infinity'::timestamptz,
                            provider_error_code = ?, job_claim_token = NULL,
                            job_claimed_until = NULL, updated_at = now()
                        WHERE id = ? AND status = 'PREPARING' AND job_claim_token = ?
                        """, reason, claim.refundId(), token);
                recordExhausted(claim, reason);
                return;
            }
            jdbcTemplate.update("""
                    UPDATE platform.refund
                    SET next_attempt_at = ?, provider_error_code = ?,
                        job_claim_token = NULL, job_claimed_until = NULL,
                        updated_at = now()
                    WHERE id = ? AND status = 'PREPARING' AND job_claim_token = ?
                    """, Timestamp.from(clock.instant().plus(backoff(claim.attempt()))),
                    reason, claim.refundId(), token);
            LOGGER.atInfo()
                    .addKeyValue("refundNo", claim.refundNo())
                    .addKeyValue("state", "PREPARATION_RETRY_SCHEDULED")
                    .log("refund preparation retry scheduled");
        });
    }

    private boolean ownsClaim(UUID refundId, UUID token) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.refund
                    WHERE id = ? AND status = 'PREPARING' AND job_claim_token = ?
                    FOR UPDATE)
                """, Boolean.class, refundId, token));
    }

    private void recordExhausted(PreparationClaim claim, String reason) {
        String fingerprint = sha256(
                "REFUND_PREPARATION_EXHAUSTED\n" + claim.refundNo());
        jdbcTemplate.update("""
                INSERT INTO platform.payment_anomaly(
                    id, fingerprint, anomaly_type, order_id, refund_id, status, detail)
                VALUES (?, ?, 'REFUND_PREPARATION_EXHAUSTED', ?, ?, 'OPEN',
                        jsonb_build_object('reason', ?))
                ON CONFLICT (fingerprint) WHERE status IN ('OPEN', 'INVESTIGATING')
                DO NOTHING
                """, UUID.randomUUID(), fingerprint, claim.orderId(), claim.refundId(), reason);
    }

    private Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("refund_snapshot_serialization_failed", exception);
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

    /** @see RefundApplicationService#eligibilityReason(String) 关于该列表为何如此之短。 */
    private boolean eligibilityReason(String reason) {
        return "REFUND_WINDOW_EXPIRED".equals(reason)
                || "PACKAGE_EXPIRED".equals(reason)
                || "PACKAGE_ACTIVATED".equals(reason);
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

    private int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is out of range");
        }
        return value;
    }

    private Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private record PreparationClaim(
            UUID refundId,
            String refundNo,
            String gatewayHoldBusinessNo,
            int attempt,
            Instant requestedAt,
            UUID orderId,
            UUID tenantId,
            UUID userId,
            long paidAmount,
            Instant paidAt,
            UUID grantRequestId,
            String grantStatus,
            Instant grantCompletedAt,
            int policyVersion,
            int feeBps,
            long minimumFee,
            int refundWindowDays,
            int maximumUsageBps,
            boolean exhaustedRecovery) {
    }

    private record ClaimBatch(UUID token, List<PreparationClaim> claims) {
    }
}
