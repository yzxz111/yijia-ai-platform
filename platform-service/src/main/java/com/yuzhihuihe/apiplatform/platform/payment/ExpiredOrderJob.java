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
public class ExpiredOrderJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExpiredOrderJob.class);

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

    public ExpiredOrderJob(
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
            fixedDelayString = "${payment.jobs.expired-order-delay-ms:5000}",
            initialDelayString = "${payment.jobs.expired-order-delay-ms:5000}")
    public void scheduledRun() {
        runOnce();
    }

    public int runOnce() {
        ClaimBatch batch = transactions.execute(ignored -> claim());
        if (batch == null) {
            return 0;
        }
        for (OrderClaim claim : batch.claims()) {
            PaymentLifecycleService.CloseOrderResult result;
            try {
                result = lifecycleService.resolveAndCloseSystem(claim.orderNo());
            } catch (RuntimeException exception) {
                result = new PaymentLifecycleService.CloseOrderResult(
                        claim.orderNo(), "PENDING", null, true);
            }
            PaymentLifecycleService.CloseOrderResult finalResult = result;
            transactions.executeWithoutResult(
                    ignored -> finish(batch.token(), claim, finalResult));
        }
        return batch.claims().size();
    }

    private ClaimBatch claim() {
        UUID token = UUID.randomUUID();
        Instant now = clock.instant();
        List<OrderClaim> claims = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT id
                    FROM platform.customer_order
                    WHERE status = 'PENDING'
                      AND expires_at <= ?
                      AND next_close_at <= ?
                      AND close_attempt_count < ?
                      AND (job_claimed_until IS NULL OR job_claimed_until <= ?)
                    ORDER BY next_close_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE platform.customer_order AS customer_order
                SET job_claim_token = ?, job_claimed_until = ?,
                    close_attempt_count = close_attempt_count + 1,
                    updated_at = now()
                FROM candidate
                WHERE customer_order.id = candidate.id
                RETURNING customer_order.id, customer_order.order_no,
                          customer_order.close_attempt_count
                """, (result, rowNumber) -> new OrderClaim(
                        result.getObject("id", UUID.class),
                        result.getString("order_no"),
                        result.getInt("close_attempt_count")),
                Timestamp.from(now), Timestamp.from(now), maxAttempts,
                Timestamp.from(now), batchSize, token,
                Timestamp.from(now.plus(claimTimeout)));
        return new ClaimBatch(token, List.copyOf(claims));
    }

    private void finish(
            UUID token,
            OrderClaim claim,
            PaymentLifecycleService.CloseOrderResult result) {
        boolean owned = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.customer_order
                    WHERE id = ? AND job_claim_token = ?
                    FOR UPDATE)
                """, Boolean.class, claim.orderId(), token));
        if (!owned) {
            return;
        }
        if (!result.retryScheduled()) {
            clearClaim(claim.orderId(), token);
            return;
        }
        if (claim.attempt() >= maxAttempts) {
            jdbcTemplate.update("""
                    UPDATE platform.customer_order
                    SET next_close_at = 'infinity'::timestamptz,
                        job_claim_token = NULL, job_claimed_until = NULL,
                        updated_at = now()
                    WHERE id = ? AND job_claim_token = ?
                    """, claim.orderId(), token);
            anomalyService.recordOrderCloseExhausted(
                    claim.orderId(), claim.orderNo());
            return;
        }
        jdbcTemplate.update("""
                UPDATE platform.customer_order
                SET next_close_at = ?, job_claim_token = NULL,
                    job_claimed_until = NULL, updated_at = now()
                WHERE id = ? AND job_claim_token = ?
                """, Timestamp.from(clock.instant().plus(backoff(claim.attempt()))),
                claim.orderId(), token);
        LOGGER.atInfo()
                .addKeyValue("orderNo", claim.orderNo())
                .addKeyValue("state", "RETRY_SCHEDULED")
                .log("expired order retry scheduled");
    }

    private void clearClaim(UUID orderId, UUID token) {
        jdbcTemplate.update("""
                UPDATE platform.customer_order
                SET job_claim_token = NULL, job_claimed_until = NULL,
                    updated_at = now()
                WHERE id = ? AND job_claim_token = ?
                """, orderId, token);
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

    private record OrderClaim(UUID orderId, String orderNo, int attempt) {
    }

    private record ClaimBatch(UUID token, List<OrderClaim> claims) {
    }
}
