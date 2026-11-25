package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.yuzhihuihe.apiplatform.platform.payment.BillEntryType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 冻结估算的提供方费用，并以追加方式写入账单更正，而不改动历史记录。 */
@Service
public class PaymentFeeService {

    private static final String SOURCE = "payment-fee";
    private static final int BILL_BATCH_SIZE = 500;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private final Clock clock;
    private final Store store;

    @Autowired
    PaymentFeeService(PaymentFeeRepository repository) {
        this(Clock.systemUTC(), new RepositoryStore(repository));
    }

    PaymentFeeService(Clock clock, Store store) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Transactional
    public boolean recordEstimatedPayment(
            UUID paymentTransactionId, String settlementMethod, Instant occurredAt) {
        if (paymentTransactionId == null || occurredAt == null) {
            throw new IllegalArgumentException("invalid_payment_fee_estimate");
        }
        if ("BALANCE".equals(settlementMethod)) return false;
        EstimateCommand command = store.paymentEstimate(
                paymentTransactionId, settlementMethod, occurredAt);
        if (command == null) throw new IllegalStateException("payment_fee_source_not_found");
        return recordEstimated(command);
    }

    @Transactional
    public boolean recordEstimatedRefund(
            UUID refundId, String settlementMethod, Instant occurredAt) {
        if (refundId == null || occurredAt == null) {
            throw new IllegalArgumentException("invalid_payment_fee_estimate");
        }
        if ("BALANCE".equals(settlementMethod)) return false;
        EstimateCommand command = store.refundEstimate(refundId, settlementMethod, occurredAt);
        if (command == null) throw new IllegalStateException("payment_fee_source_not_found");
        return recordEstimated(command);
    }

    @Transactional
    public boolean recordEstimated(EstimateCommand command) {
        validate(command);
        if ("BALANCE".equals(command.settlementMethod())) return false;
        FeeRate rate = store.findRate(command.provider(), command.product(), command.occurredAt());
        BigDecimal selectedRate = rate == null ? BigDecimal.ZERO
                : command.entryType() == BillEntryType.PAYMENT
                ? rate.paymentRate() : rate.refundRate();
        long fee = BigDecimal.valueOf(command.amount()).multiply(selectedRate)
                .setScale(0, RoundingMode.CEILING).longValueExact();
        String eventId = "fee-estimate:" + command.entryType().name().toLowerCase()
                + ":" + command.businessNo() + ":v1";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("businessNo", command.businessNo());
        metadata.put("product", command.product().name());
        metadata.put("rate", selectedRate.toPlainString());
        Fact fact = new Fact(eventId, 0, estimatedType(command.entryType()), command.entryType(),
                command.businessNo(), command.occurredAt(), clock.instant(), command.tenantId(),
                command.userId(), command.orderId(), command.paymentTransactionId(),
                command.refundId(), command.provider(), command.orderId() == null
                ? "WALLET_ONLY" : "PACKAGE_ONLY", 1, false,
                command.entryType() == BillEntryType.PAYMENT ? fee : 0,
                command.entryType() == BillEntryType.REFUND ? fee : 0,
                command.currency(), rate == null ? null : rate.id(), true, rate == null, metadata);
        return append(eventId, List.of(fact));
    }

    @Transactional
    public int recordActualBill(
            PaymentProvider provider, String billHash, List<PaymentAdapter.BillEntry> entries) {
        Objects.requireNonNull(provider, "provider");
        if (billHash == null || !SHA256.matcher(billHash).matches()) {
            throw new IllegalArgumentException("invalid_bill_hash");
        }
        Objects.requireNonNull(entries, "entries");
        int recorded = 0;
        for (int start = 0; start < entries.size(); start += BILL_BATCH_SIZE) {
            List<PaymentAdapter.BillEntry> batch = entries.subList(
                            start, Math.min(start + BILL_BATCH_SIZE, entries.size())).stream()
                    .filter(entry -> entry.outcome() == ProviderOutcome.SUCCEEDED)
                    .filter(PaymentAdapter.BillEntry::feeKnown)
                    .toList();
            if (batch.isEmpty()) continue;
            Map<String, Fact> estimates = store.findEstimates(batch);
            List<PaymentAdapter.BillEntry> missingBusiness = batch.stream()
                    .filter(entry -> entry.feeAmount() > 0)
                    .filter(entry -> !estimates.containsKey(
                            estimateKey(entry.entryType(), businessNo(entry))))
                    .toList();
            Map<String, BusinessSnapshot> businesses = store.findBusinesses(
                    missingBusiness, provider);
            List<PendingEvent> events = new ArrayList<>();
            for (PaymentAdapter.BillEntry entry : batch) {
                String businessNo = businessNo(entry);
                String eventId = "fee-bill:" + provider.name().toLowerCase() + ":"
                        + billHash + ":" + entry.providerRecordId() + ":v1";
                Fact estimate = estimates.get(estimateKey(entry.entryType(), businessNo));
                if (entry.feeAmount() == 0 && estimate == null) continue;
                BusinessSnapshot business = estimate == null
                        ? businesses.get(estimateKey(entry.entryType(), businessNo)) : null;
                List<Fact> facts = actualFacts(
                        eventId, provider, billHash, entry, businessNo, estimate, business);
                events.add(pendingEvent(eventId, facts));
            }
            recorded += store.appendBatch(events);
        }
        return recorded;
    }

    private List<Fact> actualFacts(
            String eventId, PaymentProvider provider, String billHash,
            PaymentAdapter.BillEntry entry, String businessNo, Fact estimate,
            BusinessSnapshot business) {
        List<Fact> result = new ArrayList<>();
        int line = 0;
        if (estimate != null) {
            Map<String, Object> reversalMetadata = new LinkedHashMap<>();
            reversalMetadata.put("businessNo", businessNo);
            reversalMetadata.put("billHash", billHash);
            reversalMetadata.put("reversesSourceEventId", estimate.sourceEventId());
            result.add(new Fact(eventId, line++, reversedType(entry.entryType()), entry.entryType(),
                    businessNo, entry.occurredAt(), clock.instant(), estimate.tenantId(),
                    estimate.userId(), estimate.orderId(), estimate.paymentTransactionId(),
                    estimate.refundId(), provider, estimate.billingMode(), -1, true,
                    estimate.paymentProviderFeeAmount(), estimate.refundProviderFeeAmount(),
                    estimate.currency(), estimate.rateVersionId(), false, false, reversalMetadata));
        }
        Map<String, Object> actualMetadata = new LinkedHashMap<>();
        actualMetadata.put("businessNo", businessNo);
        actualMetadata.put("billHash", billHash);
        actualMetadata.put("providerRecordId", entry.providerRecordId());
        UUID tenantId = estimate != null ? estimate.tenantId()
                : business == null ? null : business.tenantId();
        UUID userId = estimate != null ? estimate.userId()
                : business == null ? null : business.userId();
        UUID orderId = estimate != null ? estimate.orderId()
                : business == null ? null : business.orderId();
        UUID paymentId = estimate != null ? estimate.paymentTransactionId()
                : business == null ? null : business.paymentTransactionId();
        UUID refundId = estimate != null ? estimate.refundId()
                : business == null ? null : business.refundId();
        result.add(new Fact(eventId, line, actualType(entry.entryType()), entry.entryType(),
                businessNo, entry.occurredAt(), clock.instant(), tenantId, userId, orderId,
                paymentId, refundId, provider, estimate != null ? estimate.billingMode()
                : business == null ? "PACKAGE_ONLY" : business.billingMode(), 1, false,
                entry.entryType() == BillEntryType.PAYMENT ? entry.feeAmount() : 0,
                entry.entryType() == BillEntryType.REFUND ? entry.feeAmount() : 0,
                entry.feeCurrency(), null, false, estimate == null && business == null,
                actualMetadata));
        return List.copyOf(result);
    }

    private boolean append(String eventId, List<Fact> facts) {
        PendingEvent event = pendingEvent(eventId, facts);
        Claim claim = store.claim(eventId, event.payloadHash());
        if (claim == Claim.REPLAYED) return false;
        if (claim == Claim.CONFLICT) throw new IllegalStateException("payment_fee_event_conflict");
        store.insert(event.facts());
        return true;
    }

    private PendingEvent pendingEvent(String eventId, List<Fact> facts) {
        String payloadHash = sha256(facts.stream().map(Fact::canonical)
                .reduce((left, right) -> left + "\n--\n" + right).orElseThrow());
        return new PendingEvent(eventId, payloadHash, facts.stream()
                .map(value -> value.withPayloadHash(payloadHash)).toList());
    }

    private void validate(EstimateCommand value) {
        if (value == null || value.businessNo() == null || value.businessNo().isBlank()
                || value.entryType() == null || value.provider() == null || value.product() == null
                || value.product().provider() != value.provider() || value.tenantId() == null
                || value.userId() == null || value.amount() < 0 || value.occurredAt() == null
                || !"CNY".equals(value.currency())
                || !("EXTERNAL".equals(value.settlementMethod())
                || "BALANCE".equals(value.settlementMethod()))) {
            throw new IllegalArgumentException("invalid_payment_fee_estimate");
        }
    }

    private static String businessNo(PaymentAdapter.BillEntry entry) {
        return entry.entryType() == BillEntryType.PAYMENT
                ? entry.transactionNo() : entry.refundNo();
    }
    private static String estimateKey(BillEntryType type, String businessNo) {
        return type.name() + ":" + businessNo;
    }
    private static String estimatedType(BillEntryType type) {
        return type == BillEntryType.PAYMENT
                ? "PAYMENT_PROVIDER_FEE_ESTIMATED" : "REFUND_PROVIDER_FEE_ESTIMATED";
    }
    private static String reversedType(BillEntryType type) {
        return type == BillEntryType.PAYMENT
                ? "PAYMENT_PROVIDER_FEE_ESTIMATE_REVERSAL"
                : "REFUND_PROVIDER_FEE_ESTIMATE_REVERSAL";
    }
    private static String actualType(BillEntryType type) {
        return type == BillEntryType.PAYMENT
                ? "PAYMENT_PROVIDER_FEE_ACTUAL" : "REFUND_PROVIDER_FEE_ACTUAL";
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("payment_fee_hash_failed", exception);
        }
    }

    public record EstimateCommand(String businessNo, BillEntryType entryType,
            PaymentProvider provider, PaymentProduct product, UUID tenantId, UUID userId,
            UUID orderId, UUID paymentTransactionId, UUID refundId, long amount, String currency,
            String settlementMethod, Instant occurredAt) { }
    public record FeeRate(UUID id, BigDecimal paymentRate, BigDecimal refundRate) { }
    record BusinessSnapshot(UUID tenantId, UUID userId, UUID orderId,
            UUID paymentTransactionId, UUID refundId, String billingMode) { }
    record PendingEvent(String eventId, String payloadHash, List<Fact> facts) { }
    enum Claim { CLAIMED, REPLAYED, CONFLICT }
    interface Store {
        EstimateCommand paymentEstimate(
                UUID paymentTransactionId, String settlementMethod, Instant occurredAt);
        EstimateCommand refundEstimate(
                UUID refundId, String settlementMethod, Instant occurredAt);
        FeeRate findRate(PaymentProvider provider, PaymentProduct product, Instant occurredAt);
        Claim claim(String eventId, String payloadHash);
        Map<String, Fact> findEstimates(List<PaymentAdapter.BillEntry> entries);
        BusinessSnapshot findBusiness(
                BillEntryType type, String businessNo, PaymentProvider provider);
        default Map<String, BusinessSnapshot> findBusinesses(
                List<PaymentAdapter.BillEntry> entries, PaymentProvider provider) {
            Map<String, BusinessSnapshot> result = new LinkedHashMap<>();
            for (PaymentAdapter.BillEntry entry : entries) {
                String businessNo = businessNo(entry);
                BusinessSnapshot value = findBusiness(entry.entryType(), businessNo, provider);
                if (value != null) {
                    result.put(estimateKey(entry.entryType(), businessNo), value);
                }
            }
            return Map.copyOf(result);
        }
        void insert(List<Fact> values);
        default int appendBatch(List<PendingEvent> events) {
            int inserted = 0;
            for (PendingEvent event : events) {
                Claim claim = claim(event.eventId(), event.payloadHash());
                if (claim == Claim.CONFLICT) {
                    throw new IllegalStateException("payment_fee_event_conflict");
                }
                if (claim == Claim.CLAIMED) {
                    insert(event.facts());
                    inserted++;
                }
            }
            return inserted;
        }
    }

    public record Fact(String sourceEventId, int lineNo, String eventType,
            BillEntryType entryType, String businessNo, Instant occurredAt, Instant recognizedAt,
            UUID tenantId, UUID userId, UUID orderId, UUID paymentTransactionId, UUID refundId,
            PaymentProvider provider, String billingMode, int sign, boolean reversal,
            long paymentProviderFeeAmount, long refundProviderFeeAmount, String currency,
            UUID rateVersionId, boolean estimated, boolean missingSource,
            Map<String, Object> metadata) {
        String canonical() {
            return String.join("\n", sourceEventId, Integer.toString(lineNo), eventType,
                    entryType.name(), businessNo, occurredAt.toString(), string(tenantId),
                    string(userId), string(orderId), string(paymentTransactionId), string(refundId),
                    provider.name(), billingMode, Integer.toString(sign),
                    Long.toString(paymentProviderFeeAmount), Long.toString(refundProviderFeeAmount),
                    currency, string(rateVersionId), Boolean.toString(estimated),
                    Boolean.toString(missingSource), new java.util.TreeMap<>(metadata).toString());
        }
        Fact withPayloadHash(String value) {
            Map<String, Object> copy = new LinkedHashMap<>(metadata);
            copy.put("payloadHash", value);
            return new Fact(sourceEventId, lineNo, eventType, entryType, businessNo, occurredAt,
                    recognizedAt, tenantId, userId, orderId, paymentTransactionId, refundId,
                    provider, billingMode, sign, reversal, paymentProviderFeeAmount,
                    refundProviderFeeAmount, currency, rateVersionId, estimated, missingSource, copy);
        }
        private static String string(Object value) { return value == null ? "" : value.toString(); }
    }

    private static final class RepositoryStore implements Store {
        private final PaymentFeeRepository repository;

        private RepositoryStore(PaymentFeeRepository repository) {
            this.repository = repository;
        }
        @Override
        public EstimateCommand paymentEstimate(
                UUID paymentTransactionId, String settlementMethod, Instant occurredAt) {
            return estimate(repository.paymentEstimate(
                    paymentTransactionId, settlementMethod, occurredAt));
        }
        @Override
        public EstimateCommand refundEstimate(
                UUID refundId, String settlementMethod, Instant occurredAt) {
            return estimate(repository.refundEstimate(refundId, settlementMethod, occurredAt));
        }
        @Override
        public FeeRate findRate(
                PaymentProvider provider, PaymentProduct product, Instant occurredAt) {
            PaymentFeeRepository.RateRow row =
                    repository.findRate(provider, product, occurredAt);
            return row == null ? null
                    : new FeeRate(row.id(), row.paymentRate(), row.refundRate());
        }
        @Override
        public Claim claim(String eventId, String payloadHash) {
            return Claim.valueOf(repository.claim(eventId, payloadHash).name());
        }
        @Override
        public Map<String, Fact> findEstimates(List<PaymentAdapter.BillEntry> entries) {
            Map<String, Fact> result = new LinkedHashMap<>();
            repository.findEstimates(entries).forEach((key, row) -> result.put(key, fact(row)));
            return Map.copyOf(result);
        }
        @Override
        public BusinessSnapshot findBusiness(
                BillEntryType type, String businessNo, PaymentProvider provider) {
            return business(repository.findBusiness(type, businessNo, provider));
        }

        @Override
        public Map<String, BusinessSnapshot> findBusinesses(
                List<PaymentAdapter.BillEntry> entries, PaymentProvider provider) {
            Map<String, BusinessSnapshot> result = new LinkedHashMap<>();
            repository.findBusinesses(entries, provider)
                    .forEach((key, row) -> result.put(key, business(row)));
            return Map.copyOf(result);
        }

        @Override
        public int appendBatch(List<PendingEvent> events) {
            return repository.appendBatch(events.stream().map(event ->
                    new PaymentFeeRepository.PendingRow(
                            event.eventId(), event.payloadHash(),
                            event.facts().stream().map(this::factRow).toList()))
                    .toList());
        }

        @Override
        public void insert(List<Fact> values) {
            repository.insert(values.stream().map(this::factRow).toList());
        }

        private EstimateCommand estimate(PaymentFeeRepository.EstimateRow row) {
            return row == null ? null : new EstimateCommand(
                    row.businessNo(), row.entryType(), row.provider(), row.product(),
                    row.tenantId(), row.userId(), row.orderId(), row.paymentTransactionId(),
                    row.refundId(), row.amount(), row.currency(), row.settlementMethod(),
                    row.occurredAt());
        }

        private Fact fact(PaymentFeeRepository.FactRow row) {
            return new Fact(
                    row.sourceEventId(), row.lineNo(), row.eventType(), row.entryType(),
                    row.businessNo(), row.occurredAt(), row.recognizedAt(), row.tenantId(),
                    row.userId(), row.orderId(), row.paymentTransactionId(), row.refundId(),
                    row.provider(), row.billingMode(), row.sign(), row.reversal(),
                    row.paymentProviderFeeAmount(), row.refundProviderFeeAmount(),
                    row.currency(), row.rateVersionId(), row.estimated(),
                    row.missingSource(), row.metadata());
        }

        private PaymentFeeRepository.FactRow factRow(Fact value) {
            return new PaymentFeeRepository.FactRow(
                    value.sourceEventId(), value.lineNo(), value.eventType(), value.entryType(),
                    value.businessNo(), value.occurredAt(), value.recognizedAt(),
                    value.tenantId(), value.userId(), value.orderId(),
                    value.paymentTransactionId(), value.refundId(), value.provider(),
                    value.billingMode(), value.sign(), value.reversal(),
                    value.paymentProviderFeeAmount(), value.refundProviderFeeAmount(),
                    value.currency(), value.rateVersionId(), value.estimated(),
                    value.missingSource(), value.metadata());
        }

        private BusinessSnapshot business(PaymentFeeRepository.BusinessRow row) {
            return row == null ? null : new BusinessSnapshot(
                    row.tenantId(), row.userId(), row.orderId(),
                    row.paymentTransactionId(), row.refundId(), row.billingMode());
        }
    }
}
