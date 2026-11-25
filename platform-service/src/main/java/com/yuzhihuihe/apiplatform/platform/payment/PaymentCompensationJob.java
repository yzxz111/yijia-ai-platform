package com.yuzhihuihe.apiplatform.platform.payment;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PaymentCompensationJob {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PaymentCompensationJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final PaymentLifecycleService lifecycleService;
    private final PaymentAnomalyService anomalyService;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration claimTimeout;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int maxAttempts;

    public PaymentCompensationJob(
            JdbcTemplate jdbcTemplate,
            PaymentLifecycleService lifecycleService,
            PaymentAnomalyService anomalyService,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${payment.jobs.batch-size:50}") int batchSize,
            @Value("${payment.jobs.claim-timeout:30s}") Duration claimTimeout,
            @Value("${payment.jobs.initial-backoff:5s}") Duration initialBackoff,
            @Value("${payment.jobs.max-backoff:1h}") Duration maxBackoff,
            @Value("${payment.jobs.max-attempts:12}") int maxAttempts) {
        this.jdbcTemplate = jdbcTemplate;
        this.lifecycleService = lifecycleService;
        this.anomalyService = anomalyService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = bounded(batchSize, 1, 50, "payment job batch size");
        this.claimTimeout = positive(claimTimeout, "payment job claim timeout");
        this.initialBackoff = positive(initialBackoff, "payment job initial backoff");
        this.maxBackoff = positive(maxBackoff, "payment job maximum backoff");
        this.maxAttempts = bounded(maxAttempts, 1, 100, "payment job maximum attempts");
        if (initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("payment job backoff range is invalid");
        }
    }

    @Scheduled(
            fixedDelayString = "${payment.jobs.compensation-delay-ms:5000}",
            initialDelayString = "${payment.jobs.compensation-delay-ms:5000}")
    public void scheduledRun() {
        runOnce();
    }

    public int runOnce() {
        ClaimBatch batch = transactions.execute(ignored -> claim());
        if (batch == null) {
            return 0;
        }
        for (PaymentClaim claim : batch.claims()) {
            PaymentLifecycleService.PaymentQueryResult result;
            try {
                result = lifecycleService.queryForCompensation(claim.transactionNo());
            } catch (RuntimeException exception) {
                result = new PaymentLifecycleService.PaymentQueryResult(
                        claim.transactionNo(), "PENDING", true);
            }
            PaymentLifecycleService.PaymentQueryResult finalResult = result;
            transactions.executeWithoutResult(
                    ignored -> finish(batch.token(), claim, finalResult));
        }
        return batch.claims().size();
    }

    private ClaimBatch claim() {
        UUID token = UUID.randomUUID();
        Instant now = clock.instant();
        List<PaymentClaim> claims = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT id
                    FROM platform.payment_transaction
                    WHERE status = 'PENDING'
                      AND next_query_at <= ?
                      AND query_attempt_count < ?
                      AND (job_claimed_until IS NULL OR job_claimed_until <= ?)
                    ORDER BY next_query_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE platform.payment_transaction AS payment_transaction
                SET job_claim_token = ?, job_claimed_until = ?,
                    query_attempt_count = query_attempt_count + 1,
                    updated_at = now()
                FROM candidate
                WHERE payment_transaction.id = candidate.id
                RETURNING payment_transaction.id,
                          payment_transaction.transaction_no,
                          payment_transaction.order_id,
                          payment_transaction.recharge_order_id,
                          payment_transaction.provider,
                          payment_transaction.query_attempt_count
                """, (result, rowNumber) -> new PaymentClaim(
                        result.getObject("id", UUID.class),
                        result.getString("transaction_no"),
                        result.getObject("order_id", UUID.class),
                        result.getObject("recharge_order_id", UUID.class),
                        PaymentProvider.valueOf(result.getString("provider")),
                        result.getInt("query_attempt_count")),
                Timestamp.from(now), maxAttempts, Timestamp.from(now), batchSize,
                token, Timestamp.from(now.plus(claimTimeout)));
        return new ClaimBatch(token, List.copyOf(claims));
    }

    private void finish(
            UUID token,
            PaymentClaim claim,
            PaymentLifecycleService.PaymentQueryResult result) {
        boolean owned = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.payment_transaction
                    WHERE id = ? AND job_claim_token = ?
                    FOR UPDATE)
                """, Boolean.class, claim.transactionId(), token));
        if (!owned) {
            return;
        }
        if (!result.retryScheduled()) {
            clearClaim(claim.transactionId(), token);
            return;
        }
        if (claim.attempt() >= maxAttempts) {
            jdbcTemplate.update("""
                    UPDATE platform.payment_transaction
                    SET next_query_at = 'infinity'::timestamptz,
                        job_claim_token = NULL, job_claimed_until = NULL,
                        updated_at = now()
                    WHERE id = ? AND job_claim_token = ?
                    """, claim.transactionId(), token);
            anomalyService.recordPaymentQueryExhausted(
                    claim.provider(), claim.orderId(), claim.rechargeOrderId(),
                    claim.transactionId(), claim.transactionNo());
            return;
        }
        jdbcTemplate.update("""
                UPDATE platform.payment_transaction
                SET next_query_at = ?, job_claim_token = NULL,
                    job_claimed_until = NULL, updated_at = now()
                WHERE id = ? AND job_claim_token = ?
                """, Timestamp.from(clock.instant().plus(backoff(claim.attempt()))),
                claim.transactionId(), token);
        LOGGER.atInfo()
                .addKeyValue("transactionNo", claim.transactionNo())
                .addKeyValue("state", "RETRY_SCHEDULED")
                .log("payment compensation retry scheduled");
    }

    private void clearClaim(UUID transactionId, UUID token) {
        jdbcTemplate.update("""
                UPDATE platform.payment_transaction
                SET job_claim_token = NULL, job_claimed_until = NULL,
                    updated_at = now()
                WHERE id = ? AND job_claim_token = ?
                """, transactionId, token);
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

    private record PaymentClaim(
            UUID transactionId,
            String transactionNo,
            UUID orderId,
            UUID rechargeOrderId,
            PaymentProvider provider,
            int attempt) {
    }

    private record ClaimBatch(UUID token, List<PaymentClaim> claims) {
    }
}
