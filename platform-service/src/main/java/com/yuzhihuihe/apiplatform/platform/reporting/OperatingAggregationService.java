package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OperatingAggregationService {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Store store;
    private final TransactionTemplate transaction;

    @Autowired
    OperatingAggregationService(
            OperatingAggregationRepository repository,
            PlatformTransactionManager transactionManager) {
        this.store = new RepositoryStore(repository);
        this.transaction = new TransactionTemplate(transactionManager);
    }

    OperatingAggregationService(Store store) {
        this.store = store;
        this.transaction = null;
    }

    public void markDirty(Instant recognizedAt, String currency) {
        store.markDirty(recognizedAt.atZone(ZONE).toLocalDate(), currency);
    }

    public boolean recomputeNextDirtyDate() {
        if (transaction == null) return recompute();
        Boolean recomputed = transaction.execute(status -> recompute());
        return Boolean.TRUE.equals(recomputed);
    }

    public int recomputeDirtyDates(int limit) {
        int count = 0;
        while (count < limit && recomputeNextDirtyDate()) count++;
        return count;
    }

    private boolean recompute() {
        LocalDate date = store.claimDirtyDate();
        if (date == null) return false;
        store.replace(date, aggregate(date, store.facts(date)));
        return true;
    }

    static List<DailyAggregate> aggregate(LocalDate date, List<FactRow> facts) {
        Map<Dimensions, MutableAggregate> grouped = new LinkedHashMap<>();
        for (FactRow fact : facts) {
            Dimensions dimensions = new Dimensions(fact.tenantId(), fact.userId(),
                    fact.packagePlanVersionId(), fact.logicalModel(), fact.upstreamModel(),
                    fact.channel(), fact.provider(), fact.billingMode(), fact.currency(),
                    dimension(fact));
            grouped.computeIfAbsent(dimensions, ignored -> new MutableAggregate()).add(fact);
        }
        List<DailyAggregate> result = new ArrayList<>(grouped.size());
        grouped.forEach((dimensions, value) -> result.add(value.freeze(date, dimensions)));
        return List.copyOf(result);
    }

    /** 未声明用量维度的行即为令牌行；列默认值也是这么规定的。 */
    private static String dimension(FactRow fact) {
        return fact.usageDimension() == null ? "TOKEN" : fact.usageDimension();
    }

    interface Store {
        void markDirty(LocalDate date, String currency);
        LocalDate claimDirtyDate();
        List<FactRow> facts(LocalDate date);
        void replace(LocalDate date, List<DailyAggregate> rows);
    }

    public record FactRow(
            UUID tenantId, UUID userId, UUID packagePlanVersionId,
            String logicalModel, String upstreamModel, String channel, String provider,
            String billingMode, String currency, int sign,
            BigDecimal confirmedRevenueAmount, BigDecimal cashInAmount, BigDecimal refundAmount,
            BigDecimal providerInputCostAmount, BigDecimal providerOutputCostAmount,
            BigDecimal paymentProviderFeeAmount, BigDecimal refundProviderFeeAmount,
            BigDecimal allocatedUsageRevenueAmount,
            long inputTokens, long outputTokens, String usageDimension, long usageUnits,
            boolean estimated, boolean missingSource) {

        /**
         * 既未声明用量维度、也未声明单位数量的明细事实即为令牌事实：其
         * 计入的单位就是其令牌数，这正是数据库检查与
         * 接入服务共同强制的不变量。
         */
        public FactRow(
                UUID tenantId, UUID userId, UUID packagePlanVersionId,
                String logicalModel, String upstreamModel, String channel, String provider,
                String billingMode, String currency, int sign,
                BigDecimal confirmedRevenueAmount, BigDecimal cashInAmount, BigDecimal refundAmount,
                BigDecimal providerInputCostAmount, BigDecimal providerOutputCostAmount,
                BigDecimal paymentProviderFeeAmount, BigDecimal refundProviderFeeAmount,
                BigDecimal allocatedUsageRevenueAmount,
                long inputTokens, long outputTokens, boolean estimated, boolean missingSource) {
            this(tenantId, userId, packagePlanVersionId, logicalModel, upstreamModel, channel,
                    provider, billingMode, currency, sign, confirmedRevenueAmount, cashInAmount,
                    refundAmount, providerInputCostAmount, providerOutputCostAmount,
                    paymentProviderFeeAmount, refundProviderFeeAmount, allocatedUsageRevenueAmount,
                    inputTokens, outputTokens, "TOKEN", inputTokens + outputTokens, estimated,
                    missingSource);
        }
    }

    public record DailyAggregate(
            LocalDate businessDate, UUID tenantId, UUID userId, UUID packagePlanVersionId,
            String logicalModel, String upstreamModel, String channel, String provider,
            String billingMode, String currency,
            BigDecimal confirmedRevenueAmount, BigDecimal cashInAmount, BigDecimal refundAmount,
            BigDecimal providerCostAmount, BigDecimal paymentProviderFeeAmount,
            BigDecimal refundProviderFeeAmount, BigDecimal allocatedUsageRevenueAmount,
            BigDecimal profitAmount, long inputTokens, long outputTokens, long totalTokens,
            long factCount, long estimatedCount, long missingSourceCount,
            String usageDimension, long usageUnits) { }

    private record Dimensions(
            UUID tenantId, UUID userId, UUID packagePlanVersionId,
            String logicalModel, String upstreamModel, String channel, String provider,
            String billingMode, String currency, String usageDimension) { }

    private static final class MutableAggregate {
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal cashIn = BigDecimal.ZERO;
        private BigDecimal refund = BigDecimal.ZERO;
        private BigDecimal inputCost = BigDecimal.ZERO;
        private BigDecimal outputCost = BigDecimal.ZERO;
        private BigDecimal paymentFee = BigDecimal.ZERO;
        private BigDecimal refundFee = BigDecimal.ZERO;
        private BigDecimal allocatedRevenue = BigDecimal.ZERO;
        private long inputTokens;
        private long outputTokens;
        private long usageUnits;
        private long factCount;
        private long estimatedCount;
        private long missingSourceCount;

        private void add(FactRow row) {
            BigDecimal sign = BigDecimal.valueOf(row.sign());
            revenue = revenue.add(row.confirmedRevenueAmount().multiply(sign));
            cashIn = cashIn.add(row.cashInAmount().multiply(sign));
            refund = refund.add(row.refundAmount());
            inputCost = inputCost.add(row.providerInputCostAmount().multiply(sign));
            outputCost = outputCost.add(row.providerOutputCostAmount().multiply(sign));
            paymentFee = paymentFee.add(row.paymentProviderFeeAmount().multiply(sign));
            refundFee = refundFee.add(row.refundProviderFeeAmount().multiply(sign));
            allocatedRevenue = allocatedRevenue.add(
                    row.allocatedUsageRevenueAmount().multiply(sign));
            inputTokens += row.sign() * row.inputTokens();
            outputTokens += row.sign() * row.outputTokens();
            // 按该分组自身的用量维度求和：VIDEO_SECOND 分组累加的是秒数，
            // 而其令牌计数器保持为零，因此令牌指标从不会看到这些秒数。
            usageUnits += row.sign() * row.usageUnits();
            factCount++;
            if (row.estimated()) estimatedCount++;
            if (row.missingSource()) missingSourceCount++;
        }

        private DailyAggregate freeze(LocalDate date, Dimensions dimensions) {
            BigDecimal providerCost = inputCost.add(outputCost);
            BigDecimal profit = revenue.subtract(refund).subtract(providerCost)
                    .subtract(paymentFee).subtract(refundFee);
            return new DailyAggregate(date, dimensions.tenantId(), dimensions.userId(),
                    dimensions.packagePlanVersionId(), dimensions.logicalModel(),
                    dimensions.upstreamModel(), dimensions.channel(), dimensions.provider(),
                    dimensions.billingMode(), dimensions.currency(), revenue, cashIn, refund,
                    providerCost, paymentFee, refundFee, allocatedRevenue, profit,
                    inputTokens, outputTokens, inputTokens + outputTokens, factCount,
                    estimatedCount, missingSourceCount, dimensions.usageDimension(), usageUnits);
        }
    }

    private static final class RepositoryStore implements Store {
        private final OperatingAggregationRepository repository;

        private RepositoryStore(OperatingAggregationRepository repository) {
            this.repository = repository;
        }

        @Override
        public void markDirty(LocalDate date, String currency) {
            repository.markDirty(date, currency);
        }

        @Override
        public LocalDate claimDirtyDate() {
            return repository.claimDirtyDate();
        }

        @Override
        public List<FactRow> facts(LocalDate date) {
            return repository.facts(date).stream().map(row -> new FactRow(
                    row.tenantId(), row.userId(), row.packagePlanVersionId(),
                    row.logicalModel(), row.upstreamModel(), row.channel(), row.provider(),
                    row.billingMode(), row.currency(), row.sign(), row.confirmedRevenueAmount(),
                    row.cashInAmount(), row.refundAmount(), row.providerInputCostAmount(),
                    row.providerOutputCostAmount(), row.paymentProviderFeeAmount(),
                    row.refundProviderFeeAmount(), row.allocatedUsageRevenueAmount(),
                    row.inputTokens(), row.outputTokens(), row.usageDimension(), row.usageUnits(),
                    row.estimated(), row.missingSource()))
                    .toList();
        }

        @Override
        public void replace(LocalDate date, List<DailyAggregate> rows) {
            repository.replace(date, rows.stream().map(row ->
                    new OperatingAggregationRepository.AggregateRow(
                            row.businessDate(), row.tenantId(), row.userId(),
                            row.packagePlanVersionId(), row.logicalModel(), row.upstreamModel(),
                            row.channel(), row.provider(), row.billingMode(), row.currency(),
                            row.usageDimension(), row.usageUnits(),
                            row.confirmedRevenueAmount(), row.cashInAmount(), row.refundAmount(),
                            row.providerCostAmount(), row.paymentProviderFeeAmount(),
                            row.refundProviderFeeAmount(), row.allocatedUsageRevenueAmount(),
                            row.profitAmount(), row.inputTokens(), row.outputTokens(),
                            row.totalTokens(), row.factCount(), row.estimatedCount(),
                            row.missingSourceCount())).toList());
        }
    }
}
