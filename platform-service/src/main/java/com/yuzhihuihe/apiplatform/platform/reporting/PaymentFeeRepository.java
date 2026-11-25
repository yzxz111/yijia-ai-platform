package com.yuzhihuihe.apiplatform.platform.reporting;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.payment.BillEntryType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PaymentFeeRepository {

    private static final String SOURCE = "payment-fee";
    private static final int BILL_BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    PaymentFeeRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    EstimateRow paymentEstimate(
            UUID paymentTransactionId, String settlementMethod, Instant occurredAt) {
        return jdbc.query("""
                SELECT payment.transaction_no, payment.provider, payment.payment_product,
                       COALESCE(customer.tenant_id, recharge.tenant_id) tenant_id,
                       COALESCE(customer.user_id, recharge.user_id) user_id,
                       payment.order_id, payment.id payment_transaction_id,
                       payment.amount,
                       COALESCE(customer.currency, recharge.currency, 'CNY') currency
                FROM platform.payment_transaction payment
                LEFT JOIN platform.customer_order customer ON customer.id=payment.order_id
                LEFT JOIN platform.recharge_order recharge ON recharge.id=payment.recharge_order_id
                WHERE payment.id=?
                """, (result, row) -> new EstimateRow(
                result.getString("transaction_no"), BillEntryType.PAYMENT,
                PaymentProvider.valueOf(result.getString("provider")),
                PaymentProduct.valueOf(result.getString("payment_product")),
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getObject("order_id", UUID.class),
                result.getObject("payment_transaction_id", UUID.class), null,
                result.getLong("amount"), result.getString("currency"),
                settlementMethod, occurredAt), paymentTransactionId)
                .stream().findFirst().orElse(null);
    }

    EstimateRow refundEstimate(UUID refundId, String settlementMethod, Instant occurredAt) {
        return jdbc.query("""
                SELECT refund.refund_no, payment.provider, payment.payment_product,
                       customer.tenant_id, customer.user_id, refund.order_id,
                       refund.payment_transaction_id, refund.id refund_id,
                       refund.amount, customer.currency
                FROM platform.refund refund
                JOIN platform.customer_order customer ON customer.id=refund.order_id
                JOIN platform.payment_transaction payment ON payment.id=refund.payment_transaction_id
                WHERE refund.id=?
                """, (result, row) -> new EstimateRow(
                result.getString("refund_no"), BillEntryType.REFUND,
                PaymentProvider.valueOf(result.getString("provider")),
                PaymentProduct.valueOf(result.getString("payment_product")),
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getObject("order_id", UUID.class),
                result.getObject("payment_transaction_id", UUID.class),
                result.getObject("refund_id", UUID.class), result.getLong("amount"),
                result.getString("currency"), settlementMethod, occurredAt), refundId)
                .stream().findFirst().orElse(null);
    }

    RateRow findRate(PaymentProvider provider, PaymentProduct product, Instant occurredAt) {
        return jdbc.query("""
                SELECT id, payment_fee_rate, refund_fee_rate
                FROM platform.payment_fee_rate_version
                WHERE provider=? AND product=? AND effective_from <= ?
                  AND (effective_to IS NULL OR effective_to > ?)
                ORDER BY effective_from DESC LIMIT 1
                """, (result, row) -> new RateRow(
                result.getObject("id", UUID.class),
                result.getBigDecimal("payment_fee_rate"),
                result.getBigDecimal("refund_fee_rate")), provider.name(), product.name(),
                Timestamp.from(occurredAt), Timestamp.from(occurredAt))
                .stream().findFirst().orElse(null);
    }

    Claim claim(String eventId, String payloadHash) {
        int inserted = jdbc.update("""
                INSERT INTO platform.operating_fact_event_receipt(
                    source_service, source_event_id, payload_hash)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, SOURCE, eventId, payloadHash);
        if (inserted == 1) {
            return Claim.CLAIMED;
        }
        String existing = jdbc.queryForObject("""
                SELECT payload_hash FROM platform.operating_fact_event_receipt
                WHERE source_service=? AND source_event_id=?
                """, String.class, SOURCE, eventId);
        return payloadHash.equals(existing) ? Claim.REPLAYED : Claim.CONFLICT;
    }

    Map<String, FactRow> findEstimates(List<PaymentAdapter.BillEntry> entries) {
        if (entries.isEmpty()) {
            return Map.of();
        }
        List<String> businessNos = entries.stream().map(PaymentFeeRepository::businessNo)
                .distinct().toList();
        String placeholders = String.join(",",
                java.util.Collections.nCopies(businessNos.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(SOURCE);
        arguments.addAll(businessNos);
        String sql = """
                SELECT source_event_id, line_no, event_type, occurred_at, recognized_at,
                       tenant_id, user_id, order_id, payment_transaction_id, refund_id,
                       provider, billing_mode, sign, reversal,
                       payment_provider_fee_amount, refund_provider_fee_amount, currency,
                       payment_fee_rate_version_id, estimated, missing_source, metadata::text,
                       metadata->>'businessNo' business_no
                FROM platform.operating_fact
                WHERE source_service=? AND estimated
                  AND event_type IN ('PAYMENT_PROVIDER_FEE_ESTIMATED',
                                     'REFUND_PROVIDER_FEE_ESTIMATED')
                  AND metadata->>'businessNo' IN (%s)
                ORDER BY created_at DESC
                """.formatted(placeholders);
        List<FactRow> facts = jdbc.query(sql, (result, row) -> {
            BillEntryType type = result.getString("event_type").startsWith("PAYMENT_")
                    ? BillEntryType.PAYMENT : BillEntryType.REFUND;
            return fact(result, type, result.getString("business_no"));
        }, arguments.toArray());
        Map<String, FactRow> result = new LinkedHashMap<>();
        facts.forEach(fact -> result.putIfAbsent(
                estimateKey(fact.entryType(), fact.businessNo()), fact));
        return Map.copyOf(result);
    }

    BusinessRow findBusiness(
            BillEntryType type, String businessNo, PaymentProvider provider) {
        if (type == BillEntryType.PAYMENT) {
            return jdbc.query("""
                    SELECT COALESCE(customer.tenant_id, recharge.tenant_id) tenant_id,
                           COALESCE(customer.user_id, recharge.user_id) user_id,
                           payment.order_id, payment.id payment_transaction_id,
                           NULL::uuid refund_id,
                           CASE WHEN payment.order_id IS NULL
                                THEN 'WALLET_ONLY' ELSE 'PACKAGE_ONLY' END billing_mode
                    FROM platform.payment_transaction payment
                    LEFT JOIN platform.customer_order customer ON customer.id=payment.order_id
                    LEFT JOIN platform.recharge_order recharge ON recharge.id=payment.recharge_order_id
                    WHERE payment.transaction_no=? AND payment.provider=?
                    """, (result, row) -> business(result), businessNo, provider.name())
                    .stream().findFirst().orElse(null);
        }
        return jdbc.query("""
                SELECT customer.tenant_id, customer.user_id, refund.order_id,
                       refund.payment_transaction_id, refund.id refund_id,
                       'PACKAGE_ONLY' billing_mode
                FROM platform.refund refund
                JOIN platform.customer_order customer ON customer.id=refund.order_id
                WHERE refund.refund_no=? AND refund.provider=?
                """, (result, row) -> business(result), businessNo, provider.name())
                .stream().findFirst().orElse(null);
    }

    Map<String, BusinessRow> findBusinesses(
            List<PaymentAdapter.BillEntry> entries, PaymentProvider provider) {
        Map<String, BusinessRow> result = new LinkedHashMap<>();
        List<String> payments = entries.stream()
                .filter(entry -> entry.entryType() == BillEntryType.PAYMENT)
                .map(PaymentFeeRepository::businessNo).distinct().toList();
        if (!payments.isEmpty()) {
            String placeholders = String.join(",",
                    java.util.Collections.nCopies(payments.size(), "?"));
            List<Object> arguments = new ArrayList<>(payments);
            arguments.add(provider.name());
            jdbc.query("""
                    SELECT payment.transaction_no business_no,
                           COALESCE(customer.tenant_id, recharge.tenant_id) tenant_id,
                           COALESCE(customer.user_id, recharge.user_id) user_id,
                           payment.order_id, payment.id payment_transaction_id,
                           NULL::uuid refund_id,
                           CASE WHEN payment.order_id IS NULL
                                THEN 'WALLET_ONLY' ELSE 'PACKAGE_ONLY' END billing_mode
                    FROM platform.payment_transaction payment
                    LEFT JOIN platform.customer_order customer ON customer.id=payment.order_id
                    LEFT JOIN platform.recharge_order recharge ON recharge.id=payment.recharge_order_id
                    WHERE payment.transaction_no IN (%s) AND payment.provider=?
                    """.formatted(placeholders), (row, rowNumber) -> Map.entry(
                            estimateKey(BillEntryType.PAYMENT, row.getString("business_no")),
                            business(row)), arguments.toArray())
                    .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        }
        List<String> refunds = entries.stream()
                .filter(entry -> entry.entryType() == BillEntryType.REFUND)
                .map(PaymentFeeRepository::businessNo).distinct().toList();
        if (!refunds.isEmpty()) {
            String placeholders = String.join(",",
                    java.util.Collections.nCopies(refunds.size(), "?"));
            List<Object> arguments = new ArrayList<>(refunds);
            arguments.add(provider.name());
            jdbc.query("""
                    SELECT refund.refund_no business_no, customer.tenant_id,
                           customer.user_id, refund.order_id,
                           refund.payment_transaction_id, refund.id refund_id,
                           'PACKAGE_ONLY' billing_mode
                    FROM platform.refund refund
                    JOIN platform.customer_order customer ON customer.id=refund.order_id
                    WHERE refund.refund_no IN (%s) AND refund.provider=?
                    """.formatted(placeholders), (row, rowNumber) -> Map.entry(
                            estimateKey(BillEntryType.REFUND, row.getString("business_no")),
                            business(row)), arguments.toArray())
                    .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        }
        return Map.copyOf(result);
    }

    int appendBatch(List<PendingRow> events) {
        if (events.isEmpty()) {
            return 0;
        }
        List<Object[]> receiptArguments = events.stream()
                .map(event -> new Object[]{SOURCE, event.eventId(), event.payloadHash()})
                .toList();
        int[] inserted = jdbc.batchUpdate("""
                INSERT INTO platform.operating_fact_event_receipt(
                    source_service, source_event_id, payload_hash)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, receiptArguments);
        String placeholders = String.join(",",
                java.util.Collections.nCopies(events.size(), "?"));
        List<Object> lookupArguments = new ArrayList<>();
        lookupArguments.add(SOURCE);
        events.forEach(event -> lookupArguments.add(event.eventId()));
        Map<String, String> existing = jdbc.query("""
                SELECT source_event_id, payload_hash
                FROM platform.operating_fact_event_receipt
                WHERE source_service=? AND source_event_id IN (%s)
                """.formatted(placeholders), result -> {
            Map<String, String> hashes = new LinkedHashMap<>();
            while (result.next()) {
                hashes.put(result.getString("source_event_id"),
                        result.getString("payload_hash"));
            }
            return hashes;
        }, lookupArguments.toArray());
        List<FactRow> claimedFacts = new ArrayList<>();
        int claimed = 0;
        for (int index = 0; index < events.size(); index++) {
            PendingRow event = events.get(index);
            if (!event.payloadHash().equals(existing.get(event.eventId()))) {
                throw new IllegalStateException("payment_fee_event_conflict");
            }
            if (inserted[index] != 0) {
                claimed++;
                claimedFacts.addAll(event.facts());
            }
        }
        insert(claimedFacts);
        return claimed;
    }

    void insert(List<FactRow> values) {
        for (int start = 0; start < values.size(); start += BILL_BATCH_SIZE) {
            List<Object[]> arguments = values.subList(
                            start, Math.min(start + BILL_BATCH_SIZE, values.size())).stream()
                    .map(this::factArguments)
                    .toList();
            jdbc.batchUpdate("""
                    INSERT INTO platform.operating_fact(
                      source_service, source_event_id, line_no, event_type,
                      occurred_at, recognized_at, tenant_id, user_id, order_id,
                      payment_transaction_id, refund_id, provider, billing_mode, sign, reversal,
                      payment_provider_fee_amount, refund_provider_fee_amount, currency,
                      payment_fee_rate_version_id, estimated, missing_source, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, arguments);
        }
    }

    private Object[] factArguments(FactRow value) {
        return new Object[]{SOURCE, value.sourceEventId(), value.lineNo(), value.eventType(),
                Timestamp.from(value.occurredAt()), Timestamp.from(value.recognizedAt()),
                value.tenantId(), value.userId(), value.orderId(),
                value.paymentTransactionId(), value.refundId(), value.provider().name(),
                value.billingMode(), value.sign(), value.reversal(),
                value.paymentProviderFeeAmount(), value.refundProviderFeeAmount(),
                value.currency(), value.rateVersionId(), value.estimated(),
                value.missingSource(), json(value.metadata())};
    }

    private FactRow fact(ResultSet result, BillEntryType type, String businessNo)
            throws SQLException {
        return new FactRow(
                result.getString("source_event_id"), result.getInt("line_no"),
                result.getString("event_type"), type, businessNo,
                instant(result, "occurred_at"), instant(result, "recognized_at"),
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getObject("order_id", UUID.class),
                result.getObject("payment_transaction_id", UUID.class),
                result.getObject("refund_id", UUID.class),
                PaymentProvider.valueOf(result.getString("provider")),
                result.getString("billing_mode"), result.getInt("sign"),
                result.getBoolean("reversal"),
                result.getLong("payment_provider_fee_amount"),
                result.getLong("refund_provider_fee_amount"), result.getString("currency"),
                result.getObject("payment_fee_rate_version_id", UUID.class),
                result.getBoolean("estimated"), result.getBoolean("missing_source"), Map.of());
    }

    private BusinessRow business(ResultSet result) throws SQLException {
        return new BusinessRow(
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getObject("order_id", UUID.class),
                result.getObject("payment_transaction_id", UUID.class),
                result.getObject("refund_id", UUID.class), result.getString("billing_mode"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "payment_fee_metadata_serialization_failed", exception);
        }
    }

    private static String businessNo(PaymentAdapter.BillEntry entry) {
        return entry.entryType() == BillEntryType.PAYMENT
                ? entry.transactionNo() : entry.refundNo();
    }

    private static String estimateKey(BillEntryType type, String businessNo) {
        return type.name() + ":" + businessNo;
    }

    enum Claim {
        CLAIMED,
        REPLAYED,
        CONFLICT
    }

    record EstimateRow(
            String businessNo, BillEntryType entryType, PaymentProvider provider,
            PaymentProduct product, UUID tenantId, UUID userId, UUID orderId,
            UUID paymentTransactionId, UUID refundId, long amount, String currency,
            String settlementMethod, Instant occurredAt) {
    }

    record RateRow(UUID id, java.math.BigDecimal paymentRate, java.math.BigDecimal refundRate) {
    }

    record BusinessRow(
            UUID tenantId, UUID userId, UUID orderId,
            UUID paymentTransactionId, UUID refundId, String billingMode) {
    }

    record PendingRow(String eventId, String payloadHash, List<FactRow> facts) {
    }

    record FactRow(
            String sourceEventId, int lineNo, String eventType,
            BillEntryType entryType, String businessNo, Instant occurredAt, Instant recognizedAt,
            UUID tenantId, UUID userId, UUID orderId, UUID paymentTransactionId, UUID refundId,
            PaymentProvider provider, String billingMode, int sign, boolean reversal,
            long paymentProviderFeeAmount, long refundProviderFeeAmount, String currency,
            UUID rateVersionId, boolean estimated, boolean missingSource,
            Map<String, Object> metadata) {
    }
}
