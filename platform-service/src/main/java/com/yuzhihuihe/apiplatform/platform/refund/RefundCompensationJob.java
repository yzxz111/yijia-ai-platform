package com.yuzhihuihe.apiplatform.platform.refund;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RefundCompensationJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefundCompensationJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final RefundExecutionService executionService;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration claimTimeout;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int maxAttempts;

    public RefundCompensationJob(
            JdbcTemplate jdbcTemplate,
            RefundExecutionService executionService,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${refund.jobs.batch-size:50}") int batchSize,
            @Value("${refund.jobs.claim-timeout:30s}") Duration claimTimeout,
            @Value("${refund.jobs.initial-backoff:5s}") Duration initialBackoff,
            @Value("${refund.jobs.max-backoff:1h}") Duration maxBackoff,
            @Value("${refund.jobs.max-attempts:12}") int maxAttempts) {
        this.jdbcTemplate = jdbcTemplate;
        this.executionService = executionService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
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
            fixedDelayString = "${refund.jobs.compensation-delay-ms:10000}",
            initialDelayString = "${refund.jobs.compensation-delay-ms:10000}")
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
            CompensationClaim claim = batch.claims().getFirst();
            if (claim.exhaustedRecovery()) {
                transactions.executeWithoutResult(ignored -> finish(
                        batch.token(), claim,
                        new RefundExecutionService.ExecutionResult(claim.status(), true)));
            } else {
                RefundExecutionService.ExecutionResult result;
                try {
                    result = "SUBMITTING".equals(claim.status())
                            ? executionService.submitClaimed(claim.refundNo(), batch.token())
                            : executionService.queryClaimed(claim.refundNo(), batch.token());
                } catch (RuntimeException exception) {
                    result = new RefundExecutionService.ExecutionResult(claim.status(), true);
                }
                RefundExecutionService.ExecutionResult finalResult = result;
                transactions.executeWithoutResult(
                        ignored -> finish(batch.token(), claim, finalResult));
            }
            processed++;
        }
        return processed;
    }

    private ClaimBatch claim() {
        UUID token = UUID.randomUUID();
        Instant now = clock.instant();
        List<CompensationClaim> claims = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT id, provider_attempt_count = ? AS exhausted_recovery
                    FROM platform.refund
                    WHERE status IN ('SUBMITTING', 'PROCESSING')
                      AND (funding_source = 'EXTERNAL' OR status = 'SUBMITTING')
                      AND next_attempt_at <= ?
                      AND (provider_attempt_count < ? OR (
                            provider_attempt_count = ?
                            AND job_claim_token IS NOT NULL))
                      AND (job_claimed_until IS NULL OR job_claimed_until <= ?)
                    ORDER BY next_attempt_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE platform.refund AS refund
                SET job_claim_token = ?, job_claimed_until = ?,
                    provider_attempt_count = CASE WHEN candidate.exhausted_recovery
                        THEN refund.provider_attempt_count
                        WHEN refund.funding_source = 'BALANCE'
                        THEN refund.provider_attempt_count
                        ELSE refund.provider_attempt_count + 1 END,
                    submitted_at = CASE WHEN refund.status = 'SUBMITTING'
                        THEN COALESCE(refund.submitted_at, ?) ELSE refund.submitted_at END,
                    updated_at = now()
                FROM candidate
                WHERE refund.id = candidate.id
                RETURNING refund.id, refund.refund_no, refund.order_id,
                          refund.provider, refund.status, refund.provider_attempt_count,
                          candidate.exhausted_recovery
                """, (result, rowNumber) -> new CompensationClaim(
                        result.getObject("id", UUID.class),
                        result.getString("refund_no"),
                        result.getObject("order_id", UUID.class),
                        result.getString("provider") == null
                                ? null : PaymentProvider.valueOf(result.getString("provider")),
                        result.getString("status"),
                        result.getInt("provider_attempt_count"),
                        result.getBoolean("exhausted_recovery")),
                maxAttempts, Timestamp.from(now), maxAttempts, maxAttempts,
                Timestamp.from(now), token,
                Timestamp.from(now.plus(claimTimeout)), Timestamp.from(now));
        return new ClaimBatch(token, List.copyOf(claims));
    }

    private void finish(
            UUID token,
            CompensationClaim claim,
            RefundExecutionService.ExecutionResult result) {
        if (!ownsClaim(claim.refundId(), token)) {
            return;
        }
        if (!result.retryScheduled()) {
            clearClaim(claim.refundId(), token);
            return;
        }
        if (claim.attempt() >= maxAttempts) {
            jdbcTemplate.update("""
                    UPDATE platform.refund
                    SET next_attempt_at = 'infinity'::timestamptz,
                        job_claim_token = NULL, job_claimed_until = NULL,
                        updated_at = now()
                    WHERE id = ? AND job_claim_token = ?
                    """, claim.refundId(), token);
            recordExhausted(claim);
            return;
        }
        jdbcTemplate.update("""
                UPDATE platform.refund
                SET next_attempt_at = ?, job_claim_token = NULL,
                    job_claimed_until = NULL, updated_at = now()
                WHERE id = ? AND job_claim_token = ?
                """, Timestamp.from(clock.instant().plus(backoff(claim.attempt()))),
                claim.refundId(), token);
        LOGGER.atInfo()
                .addKeyValue("refundNo", claim.refundNo())
                .addKeyValue("state", "COMPENSATION_RETRY_SCHEDULED")
                .log("refund compensation retry scheduled");
    }

    private boolean ownsClaim(UUID refundId, UUID token) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.refund
                    WHERE id = ? AND job_claim_token = ?
                    FOR UPDATE)
                """, Boolean.class, refundId, token));
    }

    private void clearClaim(UUID refundId, UUID token) {
        jdbcTemplate.update("""
                UPDATE platform.refund
                SET job_claim_token = NULL, job_claimed_until = NULL, updated_at = now()
                WHERE id = ? AND job_claim_token = ?
                """, refundId, token);
    }

    private void recordExhausted(CompensationClaim claim) {
        String anomalyType = "PROCESSING".equals(claim.status())
                ? "REFUND_QUERY_EXHAUSTED" : "REFUND_SUBMISSION_EXHAUSTED";
        String fingerprint = sha256(anomalyType + "\n" + claim.refundNo());
        jdbcTemplate.update("""
                INSERT INTO platform.payment_anomaly(
                    id, fingerprint, anomaly_type, provider, order_id,
                    refund_id, status, detail)
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN',
                        jsonb_build_object('reason', 'MAXIMUM_ATTEMPTS'))
                ON CONFLICT (fingerprint) WHERE status IN ('OPEN', 'INVESTIGATING')
                DO NOTHING
                """, UUID.randomUUID(), fingerprint, anomalyType,
                claim.provider() == null ? null : claim.provider().name(),
                claim.orderId(), claim.refundId());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("refund_anomaly_hash_failed", exception);
        }
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

    private record CompensationClaim(
            UUID refundId,
            String refundNo,
            UUID orderId,
            PaymentProvider provider,
            String status,
            int attempt,
            boolean exhaustedRecovery) {
    }

    private record ClaimBatch(UUID token, List<CompensationClaim> claims) {
    }
}
