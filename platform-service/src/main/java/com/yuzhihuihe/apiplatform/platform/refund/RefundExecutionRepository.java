package com.yuzhihuihe.apiplatform.platform.refund;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RefundExecutionRepository {

    private final JdbcTemplate jdbc;

    RefundExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    int claim(
            UUID refundId,
            String expectedStatus,
            UUID claimToken,
            Instant claimedUntil,
            Instant now) {
        return jdbc.update("""
                UPDATE platform.refund
                SET job_claim_token = ?, job_claimed_until = ?,
                    provider_attempt_count = provider_attempt_count
                        + CASE WHEN funding_source = 'EXTERNAL' THEN 1 ELSE 0 END,
                    submitted_at = CASE WHEN ? = 'SUBMITTING'
                        THEN COALESCE(submitted_at, ?) ELSE submitted_at END,
                    updated_at = now()
                WHERE id = ? AND status = ?
                  AND (job_claimed_until IS NULL OR job_claimed_until <= ?)
                """, claimToken, Timestamp.from(claimedUntil), expectedStatus,
                Timestamp.from(now), refundId, expectedStatus, Timestamp.from(now));
    }

    Optional<ClaimRow> findClaim(
            String refundNo, UUID claimToken, String expectedStatus, boolean lock) {
        String claimPredicate = claimToken == null ? "" : " AND refund.job_claim_token = ?";
        String lockClause = lock ? " FOR UPDATE OF refund" : "";
        List<Object> arguments = new ArrayList<>();
        arguments.add(refundNo);
        arguments.add(expectedStatus);
        if (claimToken != null) {
            arguments.add(claimToken);
        }
        return jdbc.query("""
                        SELECT refund.id AS refund_id, refund.refund_no, refund.status,
                               refund.amount AS refund_amount, refund.reason,
                               refund.funding_source,
                               refund.gateway_hold_business_no,
                               refund.job_claim_token, refund.job_claimed_until,
                               customer_order.id AS order_id, customer_order.tenant_id,
                               customer_order.user_id, customer_order.amount AS total_amount,
                               customer_order.currency,
                               payment.id AS payment_transaction_id, payment.transaction_no,
                               payment.external_trade_no, payment.provider, payment.payment_product,
                               grant_request.id AS grant_request_id
                        FROM platform.refund AS refund
                        JOIN platform.customer_order AS customer_order
                          ON customer_order.id = refund.order_id
                        LEFT JOIN platform.payment_transaction AS payment
                          ON payment.id = refund.payment_transaction_id
                         AND payment.status = 'SUCCEEDED' AND payment.applied_to_order
                        JOIN platform.grant_request AS grant_request
                          ON grant_request.business_no = 'order:' || customer_order.order_no
                        WHERE refund.refund_no = ? AND refund.status = ?
                          AND ((refund.funding_source = 'EXTERNAL' AND payment.id IS NOT NULL)
                               OR (refund.funding_source = 'BALANCE'
                                   AND refund.payment_transaction_id IS NULL))
                        """ + claimPredicate + lockClause,
                (result, rowNumber) -> new ClaimRow(
                        result.getObject("refund_id", UUID.class),
                        result.getString("refund_no"), result.getString("status"),
                        result.getLong("refund_amount"), result.getString("reason"),
                        result.getString("funding_source"),
                        result.getString("gateway_hold_business_no"),
                        result.getObject("job_claim_token", UUID.class),
                        instant(result.getObject("job_claimed_until", OffsetDateTime.class)),
                        result.getObject("order_id", UUID.class),
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("user_id", UUID.class),
                        result.getLong("total_amount"), result.getString("currency"),
                        result.getObject("payment_transaction_id", UUID.class),
                        result.getString("transaction_no"),
                        result.getString("external_trade_no"),
                        result.getString("provider"), result.getString("payment_product"),
                        result.getObject("grant_request_id", UUID.class)),
                arguments.toArray()).stream().findFirst();
    }

    int routeBalanceToRevocation(
            UUID refundId,
            String expectedStatus,
            UUID claimToken,
            String businessKey,
            boolean preserveClaim) {
        return jdbc.update("""
                UPDATE platform.refund
                SET status = 'REVOKING', revocation_business_no = ?,
                    job_claim_token = CASE WHEN ? THEN job_claim_token ELSE NULL END,
                    job_claimed_until = CASE WHEN ? THEN job_claimed_until ELSE NULL END,
                    updated_at = now(), lock_version = lock_version + 1
                WHERE id = ? AND status = ? AND job_claim_token = ?
                  AND funding_source = 'BALANCE'
                  AND payment_transaction_id IS NULL
                """, businessKey, preserveClaim, preserveClaim,
                refundId, expectedStatus, claimToken);
    }

    Optional<String> lockCurrentStatus(UUID refundId, UUID claimToken) {
        return jdbc.query("""
                        SELECT status FROM platform.refund
                        WHERE id = ? AND job_claim_token = ?
                        FOR UPDATE
                        """, (result, rowNumber) -> result.getString("status"),
                refundId, claimToken).stream().findFirst();
    }

    int updateProviderState(
            UUID refundId,
            String expectedStatus,
            UUID claimToken,
            String nextStatus,
            String externalRefundNo,
            String providerStatus,
            String providerErrorCode,
            String revocationBusinessNo,
            boolean retry,
            boolean preserveClaim) {
        return jdbc.update("""
                UPDATE platform.refund
                SET status = ?, external_refund_no = COALESCE(?, external_refund_no),
                    provider_status = ?, provider_error_code = ?,
                    revocation_business_no = COALESCE(?, revocation_business_no),
                    next_attempt_at = CASE WHEN ? THEN now() ELSE next_attempt_at END,
                    job_claim_token = CASE WHEN ? THEN job_claim_token ELSE NULL END,
                    job_claimed_until = CASE WHEN ? THEN job_claimed_until ELSE NULL END,
                    updated_at = now(), lock_version = lock_version + 1
                WHERE id = ? AND status = ? AND job_claim_token = ?
                """, nextStatus, externalRefundNo, providerStatus, providerErrorCode,
                revocationBusinessNo, retry, preserveClaim, preserveClaim,
                refundId, expectedStatus, claimToken);
    }

    void insertRevocationEvent(
            UUID eventId,
            UUID refundId,
            String businessKey,
            String payloadJson) {
        jdbc.update("""
                INSERT INTO platform.outbox_event(
                    id, aggregate_type, aggregate_id, event_type, business_key, payload)
                VALUES (?, 'REFUND', ?, 'PACKAGE_REFUNDED_V1', ?, ?::jsonb)
                ON CONFLICT (business_key) DO NOTHING
                """, eventId, refundId, businessKey, payloadJson);
    }

    void insertProviderMismatch(
            UUID anomalyId,
            String fingerprint,
            String provider,
            UUID orderId,
            UUID paymentTransactionId,
            UUID refundId,
            String reason,
            String providerStatus) {
        jdbc.update("""
                INSERT INTO platform.payment_anomaly(
                    id, fingerprint, anomaly_type, provider, order_id,
                    payment_transaction_id, refund_id, status, detail)
                VALUES (?, ?, 'REFUND_PROVIDER_MISMATCH', ?, ?, ?, ?, 'OPEN',
                        jsonb_build_object('reason', ?, 'providerStatus', ?))
                ON CONFLICT (fingerprint) WHERE status IN ('OPEN', 'INVESTIGATING')
                DO NOTHING
                """, anomalyId, fingerprint, provider, orderId,
                paymentTransactionId, refundId, reason, providerStatus);
    }

    Optional<String> findCurrentStatus(String refundNo) {
        return jdbc.query(
                        "SELECT status FROM platform.refund WHERE refund_no = ?",
                        (result, rowNumber) -> result.getString("status"), refundNo)
                .stream().findFirst();
    }

    private Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    record ClaimRow(
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
            String provider,
            String product,
            UUID grantRequestId) {
    }
}
