package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OperatingReconciliationService {

    static final String LOCAL_SOURCE = OperatingFactService.SOURCE;
    private final Store store;
    private final GatewaySource gatewaySource;
    private final TransactionTemplate transaction;

    @Autowired
    OperatingReconciliationService(
            OperatingReconciliationRepository repository,
            GatewayEconomicsHealthClient gatewaySource,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.store = new RepositoryStore(repository, mapper);
        this.gatewaySource = gatewaySource;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    OperatingReconciliationService(Store store) {
        this(store, (from, to, sink) -> new GatewayEconomicsHealthClient.Status(0, 0, 0, 0, null));
    }

    OperatingReconciliationService(Store store, GatewaySource gatewaySource) {
        this.store = store;
        this.gatewaySource = gatewaySource;
        this.transaction = null;
    }

    public Result reconcile(Instant from, Instant to) {
        validate(from, to);
        Result local = compare(store.sources(from, to), store.facts(from, to), store.health(from, to));
        List<Item> items = new ArrayList<>(local.items());
        GatewayEconomicsHealthClient.Status gateway = gatewaySource.scan(from, to,
                events -> reconcileGatewayPage(events, items));
        if (gateway.backlog() > 0) {
            items.add(new Item(DifferenceType.GATEWAY_OUTBOX_BACKLOG, "gateway",
                    "usage-economics-outbox", "CNY", BigDecimal.valueOf(gateway.backlog()),
                    BigDecimal.ZERO, BigDecimal.valueOf(gateway.backlog()), Map.of(
                            "pending", gateway.pending(), "sending", gateway.sending(),
                            "failed", gateway.failed(), "dead", gateway.dead(),
                            "oldestPending", gateway.oldestPending() == null
                                    ? "" : gateway.oldestPending().toString())));
        }
        Result result = new Result(List.copyOf(items), local.allocationRoundingDifference());
        if (transaction == null) {
            store.save(from, to, result.items());
        } else {
            transaction.executeWithoutResult(status -> store.save(from, to, result.items()));
        }
        return result;
    }

    public PageResult reconcile(Instant from, Instant to, int page, int size) {
        Page bounds = page(page, size);
        Result result = reconcile(from, to);
        int fromIndex = Math.min(bounds.offset(), result.items().size());
        int toIndex = Math.min(fromIndex + bounds.size(), result.items().size());
        return new PageResult(List.copyOf(result.items().subList(fromIndex, toIndex)),
                result.items().size(), bounds.number(), bounds.size(),
                result.allocationRoundingDifference());
    }

    private void reconcileGatewayPage(List<GatewayEconomicsHealthClient.SourceEvent> events,
            List<Item> items) {
        if (events.isEmpty()) return;
        Map<String, GatewayReceipt> receipts = store.gatewayReceipts(
                events.stream().map(event -> event.eventId().toString()).toList());
        for (GatewayEconomicsHealthClient.SourceEvent event : events) {
            GatewayReceipt receipt = receipts.get(event.eventId().toString());
            if (receipt == null || receipt.factCount() == 0) {
                items.add(new Item(DifferenceType.MISSING, "gateway", event.eventId().toString(),
                        "CNY", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        Map.of("sourceType", "GATEWAY_ECONOMICS", "payloadHash", event.payloadHash())));
            } else if (!event.payloadHash().equals(receipt.payloadHash())) {
                Map<String, Object> evidence = new HashMap<>();
                evidence.put("sourceType", "GATEWAY_ECONOMICS");
                if (!blank(receipt.requestId())) evidence.put("requestId", receipt.requestId());
                items.add(new Item(DifferenceType.PAYLOAD_HASH_MISMATCH, "gateway",
                        event.eventId().toString(), "CNY", BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, Map.copyOf(evidence)));
            }
        }
    }

    static Result compare(List<SourceSnapshot> sources, List<FactSnapshot> facts, HealthSnapshot health) {
        Map<Key, FactSnapshot> bySource = new HashMap<>();
        for (FactSnapshot fact : facts) bySource.put(new Key(fact.sourceService(), fact.sourceEventId()), fact);
        List<Item> items = new ArrayList<>();
        for (SourceSnapshot source : sources) {
            FactSnapshot fact = bySource.get(new Key(source.sourceService(), source.sourceEventId()));
            if (fact == null || fact.factCount() < source.expectedFactCount()) {
                items.add(item(DifferenceType.MISSING, source, fact, source.amount()));
                continue;
            }
            if (fact.factCount() > source.expectedFactCount()) {
                items.add(item(DifferenceType.DUPLICATE, source, fact,
                        BigDecimal.valueOf(fact.factCount() - source.expectedFactCount())));
                continue;
            }
            BigDecimal difference = fact.amount().subtract(source.amount());
            if (difference.signum() != 0) {
                items.add(item(DifferenceType.AMOUNT_MISMATCH, source, fact, difference));
                continue;
            }
            if (fact.missingSource()) {
                items.add(item(DifferenceType.MISSING_SOURCE, source, fact, BigDecimal.ZERO));
            } else if (fact.estimated()) {
                items.add(item(DifferenceType.ESTIMATED_NOT_ACTUAL, source, fact, BigDecimal.ZERO));
            }
        }
        BigDecimal aggregateDifference = health.aggregateFinancialAmount()
                .subtract(health.factFinancialAmount());
        if (aggregateDifference.signum() != 0) {
            items.add(new Item(DifferenceType.AGGREGATE_MISMATCH, "platform-service",
                    "operating-daily-aggregate", "CNY", health.factFinancialAmount().abs(),
                    health.aggregateFinancialAmount().abs(), aggregateDifference,
                    Map.of("allocatedUsageRevenueExcluded", true)));
        }
        return new Result(List.copyOf(items), health.factAllocatedUsageRevenue()
                .subtract(health.aggregateAllocatedUsageRevenue()));
    }

    private static Item item(DifferenceType type, SourceSnapshot source, FactSnapshot fact,
            BigDecimal difference) {
        Map<String, Object> evidence = new HashMap<>(source.evidence());
        evidence.put("sourceType", source.sourceType());
        return new Item(type, source.sourceService(), source.sourceEventId(), source.currency(),
                source.amount(), fact == null ? BigDecimal.ZERO : fact.amount(), difference,
                Map.copyOf(evidence));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static void validate(Instant from, Instant to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) throw new IllegalArgumentException("invalid_reconciliation_range");
    }

    private static Page page(int number, int size) {
        if (number < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("invalid_reconciliation_page");
        }
        long offset = (long) number * size;
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid_reconciliation_page");
        }
        return new Page(number, size, (int) offset);
    }

    static String serializeEvidence(ObjectMapper mapper, Map<String, Object> evidence) {
        try {
            return mapper.writeValueAsString(evidence);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("operating_reconciliation_evidence_serialization_failed", exception);
        }
    }

    public enum DifferenceType {
        MISSING, DUPLICATE, AMOUNT_MISMATCH, ESTIMATED_NOT_ACTUAL, MISSING_SOURCE,
        GATEWAY_OUTBOX_BACKLOG, AGGREGATE_MISMATCH, PAYLOAD_HASH_MISMATCH
    }

    public record SourceSnapshot(String sourceType, String sourceService, String sourceEventId,
            String currency, BigDecimal amount, int expectedFactCount,
            Map<String, Object> evidence) {
        public SourceSnapshot {
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        }
    }
    public record FactSnapshot(String sourceService, String sourceEventId, String currency,
            BigDecimal amount, BigDecimal allocatedUsageRevenueAmount, int factCount,
            boolean estimated, boolean missingSource) { }
    public record HealthSnapshot(BigDecimal factFinancialAmount,
            BigDecimal aggregateFinancialAmount, BigDecimal factAllocatedUsageRevenue,
            BigDecimal aggregateAllocatedUsageRevenue) { }
    public record GatewayReceipt(String payloadHash, int factCount, String requestId) { }
    public record Item(DifferenceType type, String sourceService, String sourceEventId,
            String currency, BigDecimal sourceAmount, BigDecimal factAmount,
            BigDecimal differenceAmount, Map<String, Object> evidence) { }
    public record Result(List<Item> items, BigDecimal allocationRoundingDifference) { }
    public record PageResult(List<Item> items, long total, int page, int size,
            BigDecimal allocationRoundingDifference) { }
    private record Page(int number, int size, int offset) { }
    private record Key(String service, String eventId) { }

    interface Store {
        List<SourceSnapshot> sources(Instant from, Instant to);
        List<FactSnapshot> facts(Instant from, Instant to);
        HealthSnapshot health(Instant from, Instant to);
        Map<String, GatewayReceipt> gatewayReceipts(List<String> eventIds);
        void save(Instant from, Instant to, List<Item> items);
    }

    @FunctionalInterface
    interface GatewaySource {
        GatewayEconomicsHealthClient.Status scan(Instant from, Instant to,
                Consumer<List<GatewayEconomicsHealthClient.SourceEvent>> pageSink);
    }

    private static final class RepositoryStore implements Store {
        private final OperatingReconciliationRepository repository;
        private final ObjectMapper mapper;

        private RepositoryStore(
                OperatingReconciliationRepository repository, ObjectMapper mapper) {
            this.repository = repository;
            this.mapper = mapper;
        }

        @Override
        public List<SourceSnapshot> sources(Instant from, Instant to) {
            return repository.sources(from, to).stream().map(row -> new SourceSnapshot(
                    row.sourceType(), row.sourceService(), row.sourceEventId(), row.currency(),
                    row.amount(), row.expectedFactCount(), row.evidence())).toList();
        }

        @Override
        public List<FactSnapshot> facts(Instant from, Instant to) {
            return repository.facts(from, to).stream().map(row -> new FactSnapshot(
                    row.sourceService(), row.sourceEventId(), row.currency(), row.amount(),
                    row.allocatedUsageRevenueAmount(), row.factCount(), row.estimated(),
                    row.missingSource())).toList();
        }

        @Override
        public HealthSnapshot health(Instant from, Instant to) {
            OperatingReconciliationRepository.HealthRow row = repository.health(from, to);
            return new HealthSnapshot(
                    row.factFinancialAmount(), row.aggregateFinancialAmount(),
                    row.factAllocatedUsageRevenue(), row.aggregateAllocatedUsageRevenue());
        }

        @Override
        public Map<String, GatewayReceipt> gatewayReceipts(List<String> eventIds) {
            Map<String, GatewayReceipt> result = new HashMap<>();
            repository.gatewayReceipts(eventIds).forEach((eventId, row) -> result.put(
                    eventId, new GatewayReceipt(
                            row.payloadHash(), row.factCount(), row.requestId())));
            return result;
        }

        @Override
        public void save(Instant from, Instant to, List<Item> items) {
            repository.save(from, to, items.stream().map(item ->
                    new OperatingReconciliationRepository.ItemRow(
                            item.type().name(), item.sourceService(), item.sourceEventId(),
                            item.currency(), item.sourceAmount(), item.factAmount(),
                            item.differenceAmount(), serializeEvidence(mapper, item.evidence())))
                    .toList());
        }
    }
}
