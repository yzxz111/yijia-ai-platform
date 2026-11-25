package com.yuzhihuihe.apiplatform.platform.reconciliation;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.payment.BillEntryType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAnomalyService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAvailabilityService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentPostingService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import com.yuzhihuihe.apiplatform.platform.reporting.PaymentFeeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReconciliationService {

    private static final int DATABASE_BATCH_SIZE = 1_000;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> ITEM_STATUSES = Set.of("OPEN", "AUTO_RESOLVED", "RESOLVED");
    private final ReconciliationRepository repository;
    private final PaymentAvailabilityService availabilityService;
    private final PaymentPostingService postingService;
    private final PaymentAnomalyService anomalyService;
    private final CommercialTransactionMetrics metrics;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final PaymentFeeService paymentFeeService;
    private final int maxRecords;
    private final ZoneId zone;

    @Autowired
    ReconciliationService(
            ReconciliationRepository repository,
            PaymentAvailabilityService availabilityService,
            PaymentPostingService postingService,
            PaymentAnomalyService anomalyService,
            CommercialTransactionMetrics metrics,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            PaymentFeeService paymentFeeService,
            @Value("${reconciliation.max-records:1000000}") int maxRecords,
            @Value("${reconciliation.zone:Asia/Shanghai}") String zone) {
        if (maxRecords < 1 || maxRecords > PaymentAdapter.MAX_BILL_ENTRIES) {
            throw new IllegalArgumentException("reconciliation max records is invalid");
        }
        this.repository = repository;
        this.availabilityService = availabilityService;
        this.postingService = postingService;
        this.anomalyService = anomalyService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.paymentFeeService = paymentFeeService;
        this.maxRecords = maxRecords;
        this.zone = ZoneId.of(zone);
    }

    public RunDetail start(PaymentProvider provider, LocalDate billDate, UUID actorUserId) {
        if (provider == null || billDate == null || billDate.isAfter(LocalDate.now(zone))) {
            throw invalid("invalid_reconciliation_request");
        }
        Claim claim = requireTransactions().execute(status -> claim(provider, billDate, actorUserId));
        if (claim == null) {
            throw new IllegalStateException("reconciliation_claim_failed");
        }
        if (!claim.execute()) {
            return detail(claim.id(), 0, 20, "createdAt", "desc");
        }

        Instant started = Instant.now();
        List<UUID> createdAnomalyIds = new ArrayList<>();
        try {
            PaymentAdapter adapter = availabilityService.requireCheckout(
                    provider, primaryProduct(provider));
            PaymentAdapter.BillDownload download = adapter.downloadBill(billDate);
            validateDownload(download, billDate);
            SummaryAccumulator summary = reconcile(
                    claim, provider, adapter, download, createdAnomalyIds);
            complete(claim, provider, download, summary.toSummary(download.entries().size()));
            Duration duration = Duration.between(started, Instant.now());
            metrics.reconciliationRun(provider, "COMPLETED", duration);
            summary.differenceCounts().forEach((type, count) ->
                    metrics.reconciliationDifference(provider, type, count));
        } catch (RuntimeException exception) {
            fail(claim, createdAnomalyIds);
            metrics.reconciliationRun(
                    provider, "FAILED", Duration.between(started, Instant.now()));
        }
        return detail(claim.id(), 0, 20, "createdAt", "desc");
    }

    public RunDetail detail(UUID id, int page, int size, String sort, String direction) {
        if (id == null || page < 0 || size < 1 || size > 100
                || (long) page * size > Integer.MAX_VALUE) {
            throw invalid("invalid_reconciliation_query");
        }
        RunRow run = repository.findRun(id)
                .map(this::run)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "reconciliation_run_not_found"));
        ReconciliationRepository.ItemSort requestedSort = itemSort(sort);
        ReconciliationRepository.SortDirection requestedDirection =
                itemDirection(direction);
        long total = repository.countItems(id);
        List<ReconciliationItemView> items = repository.findItems(
                        id, requestedSort, requestedDirection, size,
                        Math.multiplyExact(page, size))
                .stream()
                .map(this::item)
                .toList();
        return new RunDetail(
                run.id(), run.provider(), run.billDate(), run.status(), run.billSha256(),
                summary(run.summary()), run.startedAt(), run.completedAt(), run.createdAt(),
                new PageResult<>(items, total, page, size));
    }

    List<PaymentProvider> enabledProviders() {
        return repository.enabledProviders().stream()
                .map(PaymentProvider::valueOf)
                .toList();
    }

    private Claim claim(PaymentProvider provider, LocalDate billDate, UUID actorUserId) {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        int inserted = repository.insertClaim(id, provider.name(), billDate, token);
        if (inserted == 0) {
            ReconciliationRepository.ExistingClaim existing =
                    repository.findClaim(provider.name(), billDate);
            if (Set.of("RUNNING", "FAILED").contains(existing.status())) {
                int reclaimed = repository.reclaim(existing.id(), token);
                if (reclaimed == 1) {
                    repository.deleteItems(existing.id());
                    auditStart(actorUserId, existing.id(), provider, billDate);
                    return new Claim(existing.id(), true, token);
                }
            }
            return new Claim(existing.id(), false, null);
        }
        auditStart(actorUserId, id, provider, billDate);
        return new Claim(id, true, token);
    }

    private void auditStart(
            UUID actorUserId,
            UUID runId,
            PaymentProvider provider,
            LocalDate billDate) {
        if (actorUserId != null) {
            int audit = repository.auditStart(
                    actorUserId, runId, provider.name(), billDate);
            if (audit != 1) {
                throw invalid("invalid_reconciliation_actor");
            }
        }
    }

    private void validateDownload(PaymentAdapter.BillDownload download, LocalDate billDate) {
        if (download == null || download.outcome() != ProviderOutcome.SUCCEEDED
                || !billDate.equals(download.billDate())
                || download.sha256() == null || !SHA256.matcher(download.sha256()).matches()
                || download.entries().size() > maxRecords) {
            throw new IllegalArgumentException("invalid verified bill download");
        }
        for (PaymentAdapter.BillEntry entry : download.entries()) {
            if (!entry.occurredAt().atZone(zone).toLocalDate().equals(billDate)) {
                throw new IllegalArgumentException("bill entry date mismatch");
            }
        }
    }

    private SummaryAccumulator reconcile(
            Claim claim,
            PaymentProvider provider,
            PaymentAdapter adapter,
            PaymentAdapter.BillDownload download,
            List<UUID> createdAnomalyIds) {
        int localPaymentCount = countLocalPayments(provider, download.billDate());
        int localRefundCount = countLocalRefunds(provider, download.billDate());
        requireLocalCandidateLimit((long) localPaymentCount + localRefundCount);

        SummaryAccumulator summary = new SummaryAccumulator();
        List<PaymentAdapter.BillEntry> entries = download.entries();
        int from = 0;
        while (from < entries.size()) {
            int to = providerWindowEnd(entries, from);
            List<PaymentAdapter.BillEntry> window = entries.subList(from, to);
            WindowResult result = reconcileProviderWindow(provider, adapter, window);
            createdAnomalyIds.addAll(
                    persistBatch(claim, provider, result.differences()));
            summary.addAll(result.differences());
            summary.addMatched(result.matchedCount());
            from = to;
        }
        scanMissingPlatformPayments(
                claim, provider, download.billDate(), entries, summary, createdAnomalyIds);
        scanMissingPlatformRefunds(
                claim, provider, download.billDate(), entries, summary, createdAnomalyIds);
        return summary;
    }

    private int providerWindowEnd(List<PaymentAdapter.BillEntry> entries, int from) {
        int to = from;
        int groups = 0;
        while (to < entries.size() && groups < DATABASE_BATCH_SIZE) {
            String key = businessKey(entries.get(to));
            do {
                to++;
            } while (to < entries.size() && key.equals(businessKey(entries.get(to))));
            groups++;
        }
        return to;
    }

    private WindowResult reconcileProviderWindow(
            PaymentProvider provider,
            PaymentAdapter adapter,
            List<PaymentAdapter.BillEntry> window) {
        Set<String> paymentNos = window.stream()
                .filter(entry -> entry.entryType() == BillEntryType.PAYMENT)
                .map(PaymentAdapter.BillEntry::transactionNo)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> refundNos = window.stream()
                .filter(entry -> entry.entryType() == BillEntryType.REFUND)
                .map(PaymentAdapter.BillEntry::refundNo)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<String, PaymentRow> payments = findPayments(provider, paymentNos);
        Map<String, RefundRow> refunds = findRefunds(provider, refundNos);
        List<Difference> differences = new ArrayList<>();
        long matchedCount = 0;
        int from = 0;
        while (from < window.size()) {
            String key = businessKey(window.get(from));
            int to = from + 1;
            while (to < window.size() && key.equals(businessKey(window.get(to)))) {
                to++;
            }
            List<PaymentAdapter.BillEntry> group = window.subList(from, to);
            PaymentAdapter.BillEntry entry = group.getFirst();
            Difference difference = group.size() > 1
                    ? duplicate(group, payments, refunds)
                    : entry.entryType() == BillEntryType.PAYMENT
                    ? reconcilePayment(provider, adapter, entry,
                    payments.get(entry.transactionNo()))
                    : reconcileRefund(entry, refunds.get(entry.refundNo()));
            if (difference != null) {
                differences.add(difference);
            } else {
                matchedCount++;
            }
            from = to;
        }
        return new WindowResult(differences, matchedCount);
    }

    private Difference reconcilePayment(
            PaymentProvider provider, PaymentAdapter adapter,
            PaymentAdapter.BillEntry entry, PaymentRow payment) {
        if (payment == null) {
            return Difference.open(entry, "UNLINKED_BILL_ROW", null, null, null, null,
                    Map.of("entryType", "PAYMENT", "reason", "TRANSACTION_NOT_FOUND"));
        }
        if (entry.amount() != payment.amount()) {
            return Difference.open(entry, "PAYMENT_AMOUNT_MISMATCH", payment.amount(),
                    payment.orderId(), payment.id(), null,
                    Map.of("expectedAmount", payment.amount(), "actualAmount", entry.amount()));
        }
        if (entry.outcome() == ProviderOutcome.SUCCEEDED
                && "CLOSED".equals(payment.orderStatus())) {
            return Difference.open(entry, "LATE_PAYMENT_AFTER_ORDER_CLOSE", payment.amount(),
                    payment.orderId(), payment.id(), null,
                    Map.of("reason", "ORDER_CLOSED"));
        }
        if (entry.outcome() == ProviderOutcome.SUCCEEDED
                && !"SUCCEEDED".equals(payment.status())) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("expectedStatus", "SUCCEEDED");
            detail.put("actualStatus", payment.status());
            String status = "OPEN";
            try {
                PaymentAdapter.ProviderPayment confirmed = adapter.queryPayment(
                        new PaymentAdapter.PaymentQuery(
                                payment.transactionNo(), payment.amount(), payment.currency()));
                if (confirmed.outcome() == ProviderOutcome.SUCCEEDED
                        && confirmed.transactionNo().equals(payment.transactionNo())
                        && confirmed.amount() == payment.amount()
                        && confirmed.currency().equals(payment.currency())) {
                    PaymentPostingService.PostingResult posting =
                            postingService.postSucceeded(provider, confirmed);
                    if (posting.appliedToOrder() || posting.replay()) {
                        status = "AUTO_RESOLVED";
                        detail.put("recoveryOutcome", "POSTED");
                    } else {
                        detail.put("recoveryOutcome", "DUPLICATE_REQUIRES_REVIEW");
                    }
                } else {
                    detail.put("recoveryOutcome", "QUERY_NOT_CONFIRMED");
                }
            } catch (RuntimeException exception) {
                detail.put("recoveryOutcome", "QUERY_UNCERTAIN");
            }
            return new Difference(
                    entry.providerRecordId(), entry.transactionNo(), null,
                    "PROVIDER_SUCCESS_PLATFORM_MISSING", status,
                    entry.amount(), payment.amount(), payment.orderId(), payment.id(), null,
                    Map.copyOf(detail));
        }
        if (entry.outcome() != ProviderOutcome.SUCCEEDED
                && "SUCCEEDED".equals(payment.status())) {
            return Difference.open(entry, "PAYMENT_STATUS_MISMATCH", payment.amount(),
                    payment.orderId(), payment.id(), null,
                    Map.of("expectedStatus", "SUCCEEDED",
                            "actualStatus", entry.outcome().name()));
        }
        return null;
    }

    private Difference reconcileRefund(PaymentAdapter.BillEntry entry, RefundRow refund) {
        if (refund == null) {
            return Difference.open(entry, "UNLINKED_BILL_ROW", null, null, null, null,
                    Map.of("entryType", "REFUND", "reason", "REFUND_NOT_FOUND"));
        }
        if (entry.amount() != refund.amount()) {
            return Difference.open(entry, "REFUND_AMOUNT_MISMATCH", refund.amount(),
                    refund.orderId(), refund.paymentId(), refund.id(),
                    Map.of("expectedAmount", refund.amount(), "actualAmount", entry.amount()));
        }
        ProviderOutcome localProviderOutcome = refundProviderOutcome(refund);
        if (entry.outcome() != localProviderOutcome
                || !refundBusinessStateAligned(refund.status(), localProviderOutcome)) {
            String expectedStatus = entry.outcome() == localProviderOutcome
                    ? expectedRefundBusinessStatus(localProviderOutcome)
                    : localProviderOutcome.name();
            String actualStatus = entry.outcome() == localProviderOutcome
                    ? refund.status() : entry.outcome().name();
            return Difference.open(entry, "REFUND_STATUS_MISMATCH", refund.amount(),
                    refund.orderId(), refund.paymentId(), refund.id(),
                    Map.of("expectedStatus", expectedStatus,
                            "actualStatus", actualStatus));
        }
        return null;
    }

    private ProviderOutcome refundProviderOutcome(RefundRow refund) {
        String status = refund.providerStatus() == null
                ? "" : refund.providerStatus().strip().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "SUCCESS", "SUCCEEDED", "REFUND_SUCCESS" -> ProviderOutcome.SUCCEEDED;
            case "PENDING", "PROCESSING", "REFUND_PROCESSING" -> ProviderOutcome.PENDING;
            case "FAILED", "ABNORMAL", "PAYERROR" -> ProviderOutcome.FAILED;
            case "CLOSED", "REFUND_CLOSED" -> ProviderOutcome.CLOSED;
            case "NOT_FOUND" -> ProviderOutcome.NOT_FOUND;
            case "UNCERTAIN", "UNKNOWN" -> ProviderOutcome.UNCERTAIN;
            default -> switch (refund.status()) {
                case "REVOKING", "COMPLETED" -> ProviderOutcome.SUCCEEDED;
                case "FAILED" -> ProviderOutcome.FAILED;
                case "SUBMITTING", "PROCESSING" -> ProviderOutcome.PENDING;
                default -> ProviderOutcome.UNCERTAIN;
            };
        };
    }

    private boolean refundBusinessStateAligned(
            String businessStatus, ProviderOutcome providerOutcome) {
        return switch (providerOutcome) {
            case SUCCEEDED -> Set.of("REVOKING", "COMPLETED").contains(businessStatus);
            case PENDING, UNCERTAIN -> Set.of("SUBMITTING", "PROCESSING")
                    .contains(businessStatus);
            case FAILED, CLOSED, NOT_FOUND -> "FAILED".equals(businessStatus);
        };
    }

    private String expectedRefundBusinessStatus(ProviderOutcome providerOutcome) {
        return switch (providerOutcome) {
            case SUCCEEDED -> "REVOKING_OR_COMPLETED";
            case PENDING, UNCERTAIN -> "PROCESSING";
            case FAILED, CLOSED, NOT_FOUND -> "FAILED";
        };
    }

    private Difference duplicate(
            List<PaymentAdapter.BillEntry> group,
            Map<String, PaymentRow> payments,
            Map<String, RefundRow> refunds) {
        PaymentAdapter.BillEntry entry = group.getFirst();
        PaymentRow payment = entry.entryType() == BillEntryType.PAYMENT
                ? payments.get(entry.transactionNo()) : null;
        RefundRow refund = entry.entryType() == BillEntryType.REFUND
                ? refunds.get(entry.refundNo()) : null;
        Long platformAmount = payment != null ? payment.amount()
                : refund == null ? null : refund.amount();
        return Difference.open(
                entry, "DUPLICATE_PROVIDER_RECEIPT", platformAmount,
                payment != null ? payment.orderId() : refund == null ? null : refund.orderId(),
                payment != null ? payment.id() : refund == null ? null : refund.paymentId(),
                refund == null ? null : refund.id(),
                Map.of("entryType", entry.entryType().name(), "duplicateCount", group.size()));
    }

    private int countLocalPayments(PaymentProvider provider, LocalDate billDate) {
        Instant from = billDate.atStartOfDay(zone).toInstant();
        Instant to = billDate.plusDays(1).atStartOfDay(zone).toInstant();
        return Math.toIntExact(
                repository.countSuccessfulPayments(provider.name(), from, to));
    }

    private int countLocalRefunds(PaymentProvider provider, LocalDate billDate) {
        Instant from = billDate.atStartOfDay(zone).toInstant();
        Instant to = billDate.plusDays(1).atStartOfDay(zone).toInstant();
        return Math.toIntExact(
                repository.countSuccessfulRefunds(provider.name(), from, to));
    }

    private void scanMissingPlatformPayments(
            Claim claim,
            PaymentProvider provider,
            LocalDate billDate,
            List<PaymentAdapter.BillEntry> entries,
            SummaryAccumulator summary,
            List<UUID> createdAnomalyIds) {
        Instant from = billDate.atStartOfDay(zone).toInstant();
        Instant to = billDate.plusDays(1).atStartOfDay(zone).toInstant();
        String after = "";
        while (true) {
            List<PlatformSuccess> candidates = repository.findSuccessfulPayments(
                            provider.name(), from, to, after, DATABASE_BATCH_SIZE)
                    .stream()
                    .map(row -> new PlatformSuccess(
                            row.id(), row.transactionNo(), row.reconciliationBusinessNo(),
                            row.orderId(), row.amount()))
                    .toList();
            if (candidates.isEmpty()) {
                return;
            }
            List<Difference> missing = candidates.stream()
                    .filter(value -> !containsBusiness(
                            entries, BillEntryType.PAYMENT, value.transactionNo()))
                    .map(value -> new Difference(
                        "platform-payment:" + value.transactionNo(),
                        value.transactionNo(), null,
                        "PLATFORM_SUCCESS_PROVIDER_MISSING", "OPEN", null, value.amount(),
                        value.orderId(), value.id(), null,
                        missingPlatformPaymentDetail(value)))
                    .toList();
            createdAnomalyIds.addAll(persistBatch(claim, provider, missing));
            summary.addAll(missing);
            after = candidates.getLast().transactionNo();
        }
    }

    private Map<String, String> missingPlatformPaymentDetail(PlatformSuccess payment) {
        if (payment.transactionNo().equals(payment.reconciliationBusinessNo())) {
            return Map.of("entryType", "PAYMENT", "reason", "BILL_ROW_MISSING");
        }
        return Map.of(
                "entryType", "PAYMENT",
                "reason", "BILL_ROW_MISSING",
                "businessNo", payment.reconciliationBusinessNo());
    }

    private void scanMissingPlatformRefunds(
            Claim claim,
            PaymentProvider provider,
            LocalDate billDate,
            List<PaymentAdapter.BillEntry> entries,
            SummaryAccumulator summary,
            List<UUID> createdAnomalyIds) {
        Instant from = billDate.atStartOfDay(zone).toInstant();
        Instant to = billDate.plusDays(1).atStartOfDay(zone).toInstant();
        String after = "";
        while (true) {
            List<PlatformRefundSuccess> candidates = repository.findSuccessfulRefunds(
                            provider.name(), from, to, after, DATABASE_BATCH_SIZE)
                    .stream()
                    .map(row -> new PlatformRefundSuccess(
                            row.id(), row.refundNo(), row.orderId(), row.paymentId(),
                            row.amount()))
                    .toList();
            if (candidates.isEmpty()) {
                return;
            }
            List<Difference> missing = candidates.stream()
                    .filter(value -> !containsBusiness(
                            entries, BillEntryType.REFUND, value.refundNo()))
                    .map(value -> new Difference(
                        "platform-refund:" + value.refundNo(), null, value.refundNo(),
                        "PLATFORM_REFUND_SUCCESS_PROVIDER_MISSING", "OPEN", null,
                        value.amount(), value.orderId(), value.paymentId(), value.id(),
                        Map.of("entryType", "REFUND", "reason", "BILL_ROW_MISSING")))
                    .toList();
            createdAnomalyIds.addAll(persistBatch(claim, provider, missing));
            summary.addAll(missing);
            after = candidates.getLast().refundNo();
        }
    }

    private boolean containsBusiness(
            List<PaymentAdapter.BillEntry> entries,
            BillEntryType type,
            String businessNo) {
        String target = type.name() + ":" + businessNo;
        int low = 0;
        int high = entries.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int comparison = businessKey(entries.get(middle)).compareTo(target);
            if (comparison < 0) {
                low = middle + 1;
            } else if (comparison > 0) {
                high = middle - 1;
            } else {
                return true;
            }
        }
        return false;
    }

    private void requireLocalCandidateLimit(long candidateCount) {
        if (candidateCount > maxRecords) {
            throw new IllegalArgumentException("local reconciliation records exceed limit");
        }
    }

    private void complete(
            Claim claim,
            PaymentProvider provider,
            PaymentAdapter.BillDownload download,
        RunSummary summary) {
        requireTransactions().executeWithoutResult(status -> {
            int updated = repository.completeRun(
                    claim.id(), claim.token(), download.sha256(), write(summary));
            if (updated != 1) {
                throw new IllegalStateException("reconciliation_run_state_changed");
            }
            paymentFeeService.recordActualBill(
                    provider, download.sha256(), download.entries());
        });
    }

    private List<UUID> persistBatch(
            Claim claim,
            PaymentProvider provider,
        List<Difference> differences) {
        List<UUID> insertedAnomalyIds = requireTransactions().execute(status -> {
            int renewed = repository.renewLease(claim.id(), claim.token());
            if (renewed != 1) {
                throw new IllegalStateException("reconciliation_run_state_changed");
            }
            if (!differences.isEmpty()) {
                persistItems(claim.id(), differences);
                return recordAnomalies(provider, differences);
            }
            return List.of();
        });
        if (insertedAnomalyIds == null) {
            throw new IllegalStateException("reconciliation_batch_result_missing");
        }
        return insertedAnomalyIds;
    }

    private void persistItems(UUID runId, List<Difference> differences) {
        repository.insertItems(
                runId,
                differences.stream()
                        .map(difference -> new ReconciliationRepository.ItemInsert(
                                difference.providerRecordId(), difference.transactionNo(),
                                difference.refundNo(), difference.type(), difference.status(),
                                difference.providerAmount(), difference.platformAmount(),
                                write(difference.detail())))
                        .toList(),
                DATABASE_BATCH_SIZE);
    }

    private List<UUID> recordAnomalies(
            PaymentProvider provider, List<Difference> differences) {
        List<UUID> insertedIds = new ArrayList<>();
        for (int from = 0; from < differences.size(); from += DATABASE_BATCH_SIZE) {
            insertedIds.addAll(anomalyService.recordAll(differences.subList(
                            from, Math.min(from + DATABASE_BATCH_SIZE, differences.size()))
                    .stream()
                    .filter(difference -> "OPEN".equals(difference.status()))
                    .map(difference -> new PaymentAnomalyService.AnomalyCommand(
                            difference.type(), provider, difference.orderId(),
                            difference.paymentId(), difference.refundId(),
                            anomalyDetail(difference)))
                    .toList()));
        }
        return List.copyOf(insertedIds);
    }

    private Map<String, ?> anomalyDetail(Difference difference) {
        if (!difference.detail().containsKey("businessNo")) {
            return difference.detail();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        difference.detail().forEach((key, value) -> {
            if (!"businessNo".equals(key)) {
                detail.put(key, value);
            }
        });
        return Map.copyOf(detail);
    }

    private void fail(Claim claim, List<UUID> createdAnomalyIds) {
        requireTransactions().executeWithoutResult(status -> {
            int failed = repository.failRun(
                    claim.id(), claim.token(),
                    write(new RunSummary(0, 0, 0, 0, 0, Map.of())));
            if (failed == 1) {
                repository.deleteItems(claim.id());
                deleteUnclaimedAnomalies(createdAnomalyIds);
            }
        });
    }

    private void deleteUnclaimedAnomalies(List<UUID> anomalyIds) {
        for (int from = 0; from < anomalyIds.size(); from += DATABASE_BATCH_SIZE) {
            List<UUID> batch = anomalyIds.subList(
                    from, Math.min(from + DATABASE_BATCH_SIZE, anomalyIds.size()));
            repository.deleteUnclaimedAnomalies(batch);
        }
    }

    private Map<String, PaymentRow> findPayments(
            PaymentProvider provider, Set<String> transactionNos) {
        Map<String, PaymentRow> payments = new HashMap<>();
        for (List<String> batch : batches(transactionNos)) {
            repository.findPayments(provider.name(), batch).stream()
                    .map(this::paymentRow)
                    .forEach(value -> payments.put(value.transactionNo(), value));
        }
        return payments;
    }

    private Map<String, RefundRow> findRefunds(
            PaymentProvider provider, Set<String> refundNos) {
        Map<String, RefundRow> refunds = new HashMap<>();
        for (List<String> batch : batches(refundNos)) {
            repository.findRefunds(provider.name(), batch).stream()
                    .map(this::refundRow)
                    .forEach(value -> refunds.put(value.refundNo(), value));
        }
        return refunds;
    }

    private List<List<String>> batches(Set<String> values) {
        List<String> ordered = new ArrayList<>(values);
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < ordered.size(); from += DATABASE_BATCH_SIZE) {
            batches.add(ordered.subList(
                    from, Math.min(from + DATABASE_BATCH_SIZE, ordered.size())));
        }
        return batches;
    }

    private String businessKey(PaymentAdapter.BillEntry entry) {
        return entry.entryType().name() + ":" + (entry.entryType() == BillEntryType.PAYMENT
                ? entry.transactionNo() : entry.refundNo());
    }

    private PaymentProduct primaryProduct(PaymentProvider provider) {
        return switch (provider) {
            case MOCK -> PaymentProduct.MOCK;
            case WECHAT_PAY -> PaymentProduct.WECHAT_NATIVE;
            case ALIPAY -> PaymentProduct.ALIPAY_PAGE;
        };
    }

    private ReconciliationRepository.ItemSort itemSort(String sort) {
        String requestedSort = sort == null || sort.isBlank() ? "createdAt" : sort.strip();
        return switch (requestedSort) {
            case "createdAt" -> ReconciliationRepository.ItemSort.CREATED_AT;
            case "differenceType" -> ReconciliationRepository.ItemSort.DIFFERENCE_TYPE;
            case "status" -> ReconciliationRepository.ItemSort.STATUS;
            case "providerAmount" -> ReconciliationRepository.ItemSort.PROVIDER_AMOUNT;
            case "platformAmount" -> ReconciliationRepository.ItemSort.PLATFORM_AMOUNT;
            default -> throw invalid("invalid_reconciliation_sort");
        };
    }

    private ReconciliationRepository.SortDirection itemDirection(String direction) {
        String requestedDirection = direction == null || direction.isBlank()
                ? "DESC" : direction.strip().toUpperCase(Locale.ROOT);
        return switch (requestedDirection) {
            case "ASC" -> ReconciliationRepository.SortDirection.ASC;
            case "DESC" -> ReconciliationRepository.SortDirection.DESC;
            default -> throw invalid("invalid_reconciliation_sort_direction");
        };
    }

    private RunRow run(ReconciliationRepository.RunRow row) {
        return new RunRow(
                row.id(), PaymentProvider.valueOf(row.provider()), row.billDate(),
                row.status(), row.billSha256(), row.summary(), row.startedAt(),
                row.completedAt(), row.createdAt());
    }

    private ReconciliationItemView item(ReconciliationRepository.ItemRow row) {
        return new ReconciliationItemView(
                row.id(), row.providerRecordId(), row.transactionNo(), row.refundNo(),
                row.differenceType(), row.status(), row.providerAmount(),
                row.platformAmount(), readTree(row.detail()), row.resolvedBy(),
                row.resolutionNote(), row.resolvedAt(), row.createdAt());
    }

    private PaymentRow paymentRow(ReconciliationRepository.PaymentRow row) {
        return new PaymentRow(
                row.id(), row.transactionNo(), row.orderId(), row.amount(),
                row.currency(), row.status(), row.orderStatus());
    }

    private RefundRow refundRow(ReconciliationRepository.RefundRow row) {
        return new RefundRow(
                row.id(), row.refundNo(), row.orderId(), row.paymentId(), row.amount(),
                row.status(), row.providerStatus());
    }

    private RunSummary summary(String value) {
        try {
            return objectMapper.readValue(value, RunSummary.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("reconciliation_summary_deserialization_failed", exception);
        }
    }

    private JsonNode readTree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("reconciliation_detail_deserialization_failed", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("reconciliation_serialization_failed", exception);
        }
    }

    private TransactionTemplate requireTransactions() {
        if (transactions == null) {
            throw new IllegalStateException("reconciliation_transaction_manager_required");
        }
        return transactions;
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public record RunSummary(
            long totalRecords,
            long matchedCount,
            long differenceCount,
            long autoResolvedCount,
            long openCount,
            Map<String, Long> differenceCounts) {
        public RunSummary {
            differenceCounts = Map.copyOf(differenceCounts == null ? Map.of() : differenceCounts);
        }
    }

    public record RunDetail(
            UUID id,
            PaymentProvider provider,
            LocalDate billDate,
            String status,
            String billSha256,
            RunSummary summary,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt,
            PageResult<ReconciliationItemView> items) {
    }

    public record ReconciliationItemView(
            UUID id,
            String providerRecordId,
            String transactionNo,
            String refundNo,
            String differenceType,
            String status,
            Long providerAmount,
            Long platformAmount,
            JsonNode detail,
            UUID resolvedBy,
            String resolutionNote,
            Instant resolvedAt,
            Instant createdAt) {
    }

    public record PageResult<T>(List<T> items, long total, int page, int size) {
    }

    private record Claim(UUID id, boolean execute, UUID token) {
    }

    private record RunRow(
            UUID id, PaymentProvider provider, LocalDate billDate, String status,
            String billSha256, String summary, Instant startedAt,
            Instant completedAt, Instant createdAt) {
    }

    private record PaymentRow(
            UUID id, String transactionNo, UUID orderId, long amount,
            String currency, String status, String orderStatus) {
    }

    private record RefundRow(
            UUID id, String refundNo, UUID orderId, UUID paymentId,
            long amount, String status, String providerStatus) {
    }

    private record PlatformSuccess(
            UUID id,
            String transactionNo,
            String reconciliationBusinessNo,
            UUID orderId,
            long amount) {
    }

    private record PlatformRefundSuccess(
            UUID id, String refundNo, UUID orderId, UUID paymentId, long amount) {
    }

    private static final class SummaryAccumulator {
        private final Map<String, Long> counts = new TreeMap<>();
        private long matchedCount;
        private long differenceCount;
        private long autoResolvedCount;
        private long openCount;

        void addAll(List<Difference> differences) {
            for (Difference difference : differences) {
                differenceCount++;
                counts.merge(difference.type(), 1L, Long::sum);
                if ("AUTO_RESOLVED".equals(difference.status())) {
                    autoResolvedCount++;
                } else if ("OPEN".equals(difference.status())) {
                    openCount++;
                }
            }
        }

        void addMatched(long count) {
            matchedCount += count;
        }

        Map<String, Long> differenceCounts() {
            return Map.copyOf(counts);
        }

        RunSummary toSummary(long totalRecords) {
            return new RunSummary(
                    totalRecords, matchedCount, differenceCount, autoResolvedCount,
                    openCount, differenceCounts());
        }
    }

    private record WindowResult(List<Difference> differences, long matchedCount) {
    }

    private record Difference(
            String providerRecordId,
            String transactionNo,
            String refundNo,
            String type,
            String status,
            Long providerAmount,
            Long platformAmount,
            UUID orderId,
            UUID paymentId,
            UUID refundId,
            Map<String, ?> detail) {
        static Difference open(
                PaymentAdapter.BillEntry entry,
                String type,
                Long platformAmount,
                UUID orderId,
                UUID paymentId,
                UUID refundId,
                Map<String, ?> detail) {
            return new Difference(
                    entry.providerRecordId(), entry.transactionNo(), entry.refundNo(),
                    type, "OPEN", entry.amount(), platformAmount,
                    orderId, paymentId, refundId, Map.copyOf(detail));
        }
    }
}
