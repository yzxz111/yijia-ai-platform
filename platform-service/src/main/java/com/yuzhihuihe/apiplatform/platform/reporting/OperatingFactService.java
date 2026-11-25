package com.yuzhihuihe.apiplatform.platform.reporting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 把权威的本地业务事件追加到不可变的运维台账。 */
@Service
public class OperatingFactService {

    static final String SOURCE = "platform";
    private final Clock clock;
    private final Store store;

    @Autowired
    OperatingFactService(OperatingFactRepository repository) {
        this(Clock.systemUTC(), new RepositoryStore(repository));
    }

    OperatingFactService(Clock clock, Store store) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Transactional
    public boolean recordPackagePurchase(PackagePurchase value) {
        requireCommon(value == null ? null : value.tenantId(), value == null ? null : value.userId(),
                value == null ? -1 : value.amount(), value == null ? null : value.currency(),
                value == null ? null : value.occurredAt());
        if (value.orderId() == null || value.packagePlanVersionId() == null
                || !settlement(value.settlementMethod())) throw invalid();
        String eventId = "customer-order:" + value.orderId() + ":paid:v1";
        return append(eventId, new Fact(eventId, "PACKAGE_PURCHASE_REVENUE", value.occurredAt(),
                clock.instant(), value.tenantId(), value.userId(), value.orderId(),
                value.packagePlanVersionId(), value.paymentTransactionId(), null, null,
                null, null, 0, 0, 0, "PACKAGE_ONLY", 1, false, false,
                value.amount(), 0, 0,
                value.currency(), Map.of("settlementMethod", value.settlementMethod())));
    }

    @Transactional
    public boolean recordWalletRecharge(WalletRecharge value) {
        requireCommon(value == null ? null : value.tenantId(), value == null ? null : value.userId(),
                value == null ? -1 : value.amount(), value == null ? null : value.currency(),
                value == null ? null : value.occurredAt());
        if (value.rechargeOrderId() == null || value.paymentTransactionId() == null) throw invalid();
        String eventId = "recharge-order:" + value.rechargeOrderId() + ":paid:v1";
        return append(eventId, new Fact(eventId, "WALLET_RECHARGE_CASH_IN", value.occurredAt(),
                clock.instant(), value.tenantId(), value.userId(), null, null,
                value.paymentTransactionId(), null, null, null, null, 0, 0, 0,
                "WALLET_ONLY", 1, false, false, 0, value.amount(), 0, value.currency(),
                Map.of("rechargeOrderId", value.rechargeOrderId().toString())));
    }

    @Transactional
    public boolean recordWalletUsage(WalletUsage value) {
        return recordWalletUsage(value, false);
    }

    @Transactional
    public boolean recordLegacyWalletUsage(WalletUsage value) {
        if (value == null || !"UNKNOWN".equals(value.billingMode())) throw invalid();
        return recordWalletUsage(value, true);
    }

    private boolean recordWalletUsage(WalletUsage value, boolean legacyUnknown) {
        requireCommon(value == null ? null : value.tenantId(), value == null ? null : value.userId(),
                value == null ? -1 : value.amount(), "CNY", value == null ? null : value.occurredAt());
        if (value.reservationId() == null || blank(value.requestId()) || blank(value.modelCode())
                || value.inputTokens() < 0 || value.outputTokens() < 0
                || !("HYBRID".equals(value.billingMode()) || "WALLET_ONLY".equals(value.billingMode())
                        || (legacyUnknown && "UNKNOWN".equals(value.billingMode())))) {
            throw invalid();
        }
        String eventId = "wallet-reservation:" + value.reservationId() + ":settled:v1";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reservationId", value.reservationId().toString());
        metadata.put("priceVersion", value.priceVersion());
        if (legacyUnknown) {
            metadata.put("legacyBillingMode", "UNKNOWN");
            metadata.put("missingSource", true);
        }
        return append(eventId, new Fact(eventId, "WALLET_USAGE_REVENUE", value.occurredAt(),
                clock.instant(), value.tenantId(), value.userId(), null, null, null, null,
                value.requestId(), value.modelCode(), null, value.inputTokens(), value.outputTokens(),
                Math.addExact(value.inputTokens(), value.outputTokens()),
                legacyUnknown ? "WALLET_ONLY" : value.billingMode(), 1,
                false, legacyUnknown, value.amount(), 0, 0, "CNY", metadata));
    }

    @Transactional
    public boolean recordPackageRefund(PackageRefund value) {
        requireCommon(value == null ? null : value.tenantId(), value == null ? null : value.userId(),
                value == null ? -1 : value.amount(), value == null ? null : value.currency(),
                value == null ? null : value.occurredAt());
        if (value.refundId() == null || value.orderId() == null
                || !settlement(value.fundingSource())) throw invalid();
        String eventId = "refund:" + value.refundId() + ":completed:v1";
        return append(eventId, new Fact(eventId, "PACKAGE_REFUND", value.occurredAt(), clock.instant(),
                value.tenantId(), value.userId(), value.orderId(), null, null, value.refundId(),
                null, null, null, 0, 0, 0, "PACKAGE_ONLY", -1, true, false, 0, 0, value.amount(),
                value.currency(), Map.of("fundingSource", value.fundingSource())));
    }

    private boolean append(String eventId, Fact fact) {
        String payloadHash = sha256(fact.canonical());
        Claim claim = store.claim(eventId, payloadHash);
        if (claim == Claim.REPLAYED) return false;
        if (claim == Claim.CONFLICT) throw new IllegalStateException("operating_fact_event_conflict");
        store.insert(fact.withPayloadHash(payloadHash));
        return true;
    }

    private void requireCommon(UUID tenantId, UUID userId, long amount, String currency, Instant occurredAt) {
        if (tenantId == null || userId == null || amount < 0 || blank(currency)
                || currency.length() != 3 || occurredAt == null) throw invalid();
    }

    private static boolean settlement(String value) {
        return "EXTERNAL".equals(value) || "BALANCE".equals(value);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid_operating_fact");
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("operating_fact_hash_failed", exception);
        }
    }

    public record PackagePurchase(UUID orderId, UUID tenantId, UUID userId,
            UUID packagePlanVersionId, UUID paymentTransactionId, long amount, String currency,
            String settlementMethod, Instant occurredAt) { }
    public record WalletRecharge(UUID rechargeOrderId, UUID tenantId, UUID userId,
            UUID paymentTransactionId, long amount, String currency, Instant occurredAt) { }
    public record WalletUsage(UUID reservationId, UUID tenantId, UUID userId, String requestId,
            String modelCode, String priceVersion, long amount, long inputTokens, long outputTokens,
            String billingMode, Instant occurredAt) { }
    public record PackageRefund(UUID refundId, UUID orderId, UUID tenantId, UUID userId,
            long amount, String currency, String fundingSource, Instant occurredAt) { }

    enum Claim { CLAIMED, REPLAYED, CONFLICT }
    interface Store {
        Claim claim(String sourceEventId, String payloadHash);
        void insert(Fact fact);
    }

    public record Fact(String sourceEventId, String eventType, Instant occurredAt,
            Instant recognizedAt, UUID tenantId, UUID userId, UUID orderId,
            UUID packagePlanVersionId, UUID paymentTransactionId, UUID refundId,
            String requestId, String logicalModel, String provider, long inputTokens,
            long outputTokens, long totalTokens, String billingMode, int sign, boolean reversal,
            boolean missingSource, long confirmedRevenueAmount, long cashInAmount,
            long refundAmount, String currency,
            Map<String, Object> metadata) {
        String canonical() {
            return String.join("\n", sourceEventId, eventType, occurredAt.toString(),
                    string(tenantId), string(userId), string(orderId), string(packagePlanVersionId),
                    string(paymentTransactionId), string(refundId), string(requestId),
                    string(logicalModel), Long.toString(inputTokens), Long.toString(outputTokens),
                    billingMode, Integer.toString(sign), Boolean.toString(missingSource),
                    Long.toString(confirmedRevenueAmount),
                    Long.toString(cashInAmount), Long.toString(refundAmount), currency,
                    new java.util.TreeMap<>(metadata).toString());
        }
        Fact withPayloadHash(String hash) {
            Map<String, Object> copy = new LinkedHashMap<>(metadata);
            copy.put("payloadHash", hash);
            return new Fact(sourceEventId, eventType, occurredAt, recognizedAt, tenantId, userId,
                    orderId, packagePlanVersionId, paymentTransactionId, refundId, requestId,
                    logicalModel, provider, inputTokens, outputTokens, totalTokens, billingMode,
                    sign, reversal, missingSource, confirmedRevenueAmount, cashInAmount,
                    refundAmount, currency, copy);
        }
        private static String string(Object value) { return value == null ? "" : value.toString(); }
    }

    private static final class RepositoryStore implements Store {
        private final OperatingFactRepository repository;

        private RepositoryStore(OperatingFactRepository repository) {
            this.repository = repository;
        }

        @Override
        public Claim claim(String sourceEventId, String payloadHash) {
            return Claim.valueOf(repository.claim(sourceEventId, payloadHash).name());
        }

        @Override
        public void insert(Fact fact) {
            repository.insert(new OperatingFactRepository.FactRow(
                    fact.sourceEventId(), fact.eventType(), fact.occurredAt(), fact.recognizedAt(),
                    fact.tenantId(), fact.userId(), fact.orderId(), fact.packagePlanVersionId(),
                    fact.paymentTransactionId(), fact.refundId(), fact.requestId(),
                    fact.logicalModel(), fact.provider(), fact.inputTokens(), fact.outputTokens(),
                    fact.totalTokens(), fact.billingMode(), fact.sign(), fact.reversal(),
                    fact.missingSource(), fact.confirmedRevenueAmount(), fact.cashInAmount(),
                    fact.refundAmount(), fact.currency(), fact.metadata()));
        }
    }
}
