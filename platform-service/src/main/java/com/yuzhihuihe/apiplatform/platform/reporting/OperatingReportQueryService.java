package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OperatingReportQueryService {

    private static final int MAX_RANGE_DAYS = 366;
    private final Store store;

    @Autowired
    OperatingReportQueryService(OperatingReportQueryRepository repository) {
        this.store = new RepositoryStore(repository);
    }

    OperatingReportQueryService(Store store) {
        this.store = store;
    }

    public ReportResult query(Filter filter) {
        validate(filter);
        Map<LocalDate, MutableTotal> grouped = new LinkedHashMap<>();
        MutableTotal total = new MutableTotal();
        for (DailyRow row : store.load(filter)) {
            if (!filter.matches(row)) continue;
            LocalDate period = periodStart(row.businessDate(), filter.grain());
            grouped.computeIfAbsent(period, ignored -> new MutableTotal()).add(row);
            total.add(row);
        }
        boolean contribution = filter.isUsageContributionScope();
        List<ReportRow> rows = new ArrayList<>(grouped.size());
        grouped.forEach((period, value) -> rows.add(value.row(period, contribution)));
        return new ReportResult("Asia/Shanghai", filter.currency(), filter.grain(), List.copyOf(rows),
                total.total(contribution));
    }

    private static void validate(Filter filter) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(filter.from(), "from");
        Objects.requireNonNull(filter.to(), "to");
        Objects.requireNonNull(filter.grain(), "grain");
        if (!List.of("CNY", "USD").contains(filter.currency())) {
            throw new IllegalArgumentException("invalid_report_currency");
        }
        if (filter.to().isBefore(filter.from())) throw new IllegalArgumentException("invalid_report_range");
        if (ChronoUnit.DAYS.between(filter.from(), filter.to()) + 1 > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("report_range_exceeds_366_days");
        }
    }

    private static LocalDate periodStart(LocalDate date, Grain grain) {
        return switch (grain) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    interface Store {
        List<DailyRow> load(Filter filter);
    }

    public enum Grain { DAY, WEEK, MONTH }

    public record Filter(
            LocalDate from, LocalDate to, Grain grain,
            UUID tenantId, UUID userId, UUID packagePlanVersionId,
            String logicalModel, String upstreamModel, String channel, String provider,
            String billingMode, String currency) {

        public boolean matches(DailyRow row) {
            return !row.businessDate().isBefore(from) && !row.businessDate().isAfter(to)
                    && equalIfSet(tenantId, row.tenantId())
                    && equalIfSet(userId, row.userId())
                    && equalIfSet(packagePlanVersionId, row.packagePlanVersionId())
                    && equalIfSet(logicalModel, row.logicalModel())
                    && equalIfSet(upstreamModel, row.upstreamModel())
                    && equalIfSet(channel, row.channel())
                    && equalIfSet(provider, row.provider())
                    && equalIfSet(billingMode, row.billingMode())
                    && equalIfSet(currency, row.currency());
        }

        boolean isUsageContributionScope() {
            return logicalModel != null || upstreamModel != null || channel != null || provider != null;
        }

        private static boolean equalIfSet(Object expected, Object actual) {
            return expected == null || expected.equals(actual);
        }
    }

    public record DailyRow(
            LocalDate businessDate, UUID tenantId, UUID userId, UUID packagePlanVersionId,
            String logicalModel, String upstreamModel, String channel, String provider,
            String billingMode, String currency,
            BigDecimal confirmedRevenueAmount, BigDecimal cashInAmount, BigDecimal refundAmount,
            BigDecimal providerCostAmount, BigDecimal paymentProviderFeeAmount,
            BigDecimal refundProviderFeeAmount, BigDecimal allocatedUsageRevenueAmount,
            long inputTokens, long outputTokens, long totalTokens, long factCount,
            long estimatedCount, long missingSourceCount) { }

    public record Metrics(
            BigDecimal confirmedRevenueAmount, BigDecimal cashInAmount, BigDecimal refundAmount,
            BigDecimal providerCostAmount, BigDecimal paymentProviderFeeAmount,
            BigDecimal refundProviderFeeAmount, BigDecimal allocatedUsageRevenueAmount,
            BigDecimal profitAmount, BigDecimal profitMargin,
            long inputTokens, long outputTokens, long totalTokens, long factCount,
            long estimatedCount, long missingSourceCount) { }

    public record ReportRow(LocalDate periodStart, Metrics metrics) { }

    public record ReportResult(
            String timezone, String currency, Grain grain, List<ReportRow> rows, Metrics total) { }

    private static final class MutableTotal {
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal cashIn = BigDecimal.ZERO;
        private BigDecimal refund = BigDecimal.ZERO;
        private BigDecimal providerCost = BigDecimal.ZERO;
        private BigDecimal paymentFee = BigDecimal.ZERO;
        private BigDecimal refundFee = BigDecimal.ZERO;
        private BigDecimal allocatedRevenue = BigDecimal.ZERO;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private long factCount;
        private long estimatedCount;
        private long missingSourceCount;

        private void add(DailyRow row) {
            revenue = revenue.add(row.confirmedRevenueAmount());
            cashIn = cashIn.add(row.cashInAmount());
            refund = refund.add(row.refundAmount());
            providerCost = providerCost.add(row.providerCostAmount());
            paymentFee = paymentFee.add(row.paymentProviderFeeAmount());
            refundFee = refundFee.add(row.refundProviderFeeAmount());
            allocatedRevenue = allocatedRevenue.add(row.allocatedUsageRevenueAmount());
            inputTokens += row.inputTokens();
            outputTokens += row.outputTokens();
            totalTokens += row.totalTokens();
            factCount += row.factCount();
            estimatedCount += row.estimatedCount();
            missingSourceCount += row.missingSourceCount();
        }

        private ReportRow row(LocalDate period, boolean contribution) {
            return new ReportRow(period, total(contribution));
        }

        private Metrics total(boolean contribution) {
            BigDecimal netRevenue = (contribution ? allocatedRevenue : revenue).subtract(refund);
            BigDecimal profit = netRevenue.subtract(providerCost).subtract(paymentFee).subtract(refundFee);
            BigDecimal margin = netRevenue.signum() == 0 ? null
                    : profit.divide(netRevenue, 6, RoundingMode.HALF_UP);
            return new Metrics(revenue, cashIn, refund, providerCost, paymentFee, refundFee,
                    allocatedRevenue, profit, margin, inputTokens, outputTokens, totalTokens,
                    factCount, estimatedCount, missingSourceCount);
        }
    }

    private static final class RepositoryStore implements Store {
        private final OperatingReportQueryRepository repository;

        private RepositoryStore(OperatingReportQueryRepository repository) {
            this.repository = repository;
        }

        @Override
        public List<DailyRow> load(Filter filter) {
            OperatingReportQueryRepository.Filter query =
                    new OperatingReportQueryRepository.Filter(
                            filter.from(), filter.to(),
                            OperatingReportQueryRepository.Grain.valueOf(filter.grain().name()),
                            filter.tenantId(), filter.userId(), filter.packagePlanVersionId(),
                            filter.logicalModel(), filter.upstreamModel(), filter.channel(),
                            filter.provider(), filter.billingMode(), filter.currency());
            return repository.load(query).stream().map(row -> new DailyRow(
                    row.businessDate(), row.tenantId(), row.userId(), row.packagePlanVersionId(),
                    row.logicalModel(), row.upstreamModel(), row.channel(), row.provider(),
                    row.billingMode(), row.currency(), row.confirmedRevenueAmount(),
                    row.cashInAmount(), row.refundAmount(), row.providerCostAmount(),
                    row.paymentProviderFeeAmount(), row.refundProviderFeeAmount(),
                    row.allocatedUsageRevenueAmount(), row.inputTokens(), row.outputTokens(),
                    row.totalTokens(), row.factCount(), row.estimatedCount(),
                    row.missingSourceCount())).toList();
        }
    }
}
