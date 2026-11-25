package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OperatingReportQueryServiceTest {

    private static final UUID TENANT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID PACKAGE = UUID.fromString("30000000-0000-4000-8000-000000000001");

    @Test
    void groupsDailyRowsAcrossShanghaiWeeksAndMonthsWithoutChangingTotals() {
        OperatingReportQueryService service = new OperatingReportQueryService(filter -> List.of(
                row(LocalDate.of(2026, 7, 31), "smart", "BAILIAN", 1000, 100, 20, 10),
                row(LocalDate.of(2026, 8, 1), "smart", "BAILIAN", 2000, 300, 30, 20),
                row(LocalDate.of(2026, 8, 3), "smart", "BAILIAN", 3000, 500, 40, 30)));

        var daily = service.query(filter(OperatingReportQueryService.Grain.DAY, null, null));
        var weekly = service.query(filter(OperatingReportQueryService.Grain.WEEK, null, null));
        var monthly = service.query(filter(OperatingReportQueryService.Grain.MONTH, null, null));

        assertThat(daily.rows()).hasSize(3);
        assertThat(weekly.rows()).extracting(OperatingReportQueryService.ReportRow::periodStart)
                .containsExactly(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 3));
        assertThat(monthly.rows()).extracting(OperatingReportQueryService.ReportRow::periodStart)
                .containsExactly(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));
        assertThat(daily.total()).isEqualTo(weekly.total()).isEqualTo(monthly.total());
    }

    @Test
    void appliesAllDimensionsAndUsesAllocatedRevenueForModelContributionProfit() {
        OperatingReportQueryService.DailyRow matching = new OperatingReportQueryService.DailyRow(
                LocalDate.of(2026, 8, 1), TENANT, USER, PACKAGE, "smart", "qwen-plus",
                "bailian-primary", "BAILIAN", "HYBRID", "CNY",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(200),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(600),
                10, 20, 30, 1, 1, 1);
        OperatingReportQueryService service = new OperatingReportQueryService(filter ->
                filter.matches(matching) ? List.of(matching) : List.of());
        var filter = new OperatingReportQueryService.Filter(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                OperatingReportQueryService.Grain.MONTH, TENANT, USER, PACKAGE,
                "smart", "qwen-plus", "bailian-primary", "BAILIAN", "HYBRID", "CNY");

        var result = service.query(filter);

        assertThat(result.total().confirmedRevenueAmount()).isZero();
        assertThat(result.total().allocatedUsageRevenueAmount()).isEqualByComparingTo("600");
        assertThat(result.total().profitAmount()).isEqualByComparingTo("400");
        assertThat(result.total().profitMargin()).isEqualByComparingTo("0.666667");
        assertThat(result.total().estimatedCount()).isEqualTo(1);
        assertThat(result.total().missingSourceCount()).isEqualTo(1);
    }

    @Test
    void returnsNullMarginForZeroRevenueAndRejectsRangesAbove366Days() {
        OperatingReportQueryService service = new OperatingReportQueryService(filter -> List.of(
                new OperatingReportQueryService.DailyRow(LocalDate.of(2026, 8, 1),
                        TENANT, USER, PACKAGE, null, null, null, null, "HYBRID", "CNY",
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.valueOf(50), BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, 0, 0, 0, 1, 0, 0)));

        assertThat(service.query(filter(OperatingReportQueryService.Grain.DAY, null, null))
                .total().profitMargin()).isNull();
        assertThatThrownBy(() -> service.query(new OperatingReportQueryService.Filter(
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2),
                OperatingReportQueryService.Grain.DAY, null, null, null,
                null, null, null, null, null, "CNY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("report_range_exceeds_366_days");
    }

    @Test
    void jdbcQueryAggregatesBySafeRequestedGrainAndBoundsReturnedPeriods() {
        assertThat(OperatingReportQueryRepository.aggregateSql(
                OperatingReportQueryRepository.Grain.DAY, " AND provider = ?"))
                .contains("SELECT business_date AS business_date")
                .contains("sum(confirmed_revenue_amount)")
                .contains("GROUP BY 1")
                .contains("LIMIT 366")
                .contains("AND provider = ?");
        assertThat(OperatingReportQueryRepository.aggregateSql(
                OperatingReportQueryRepository.Grain.WEEK, ""))
                .contains("date_trunc('week', business_date::timestamp)::date");
        assertThat(OperatingReportQueryRepository.aggregateSql(
                OperatingReportQueryRepository.Grain.MONTH, ""))
                .contains("date_trunc('month', business_date::timestamp)::date");
    }

    @Test
    void neverMixesCurrenciesAndReturnsTheRequestedCurrency() {
        var cny = row(LocalDate.of(2026, 8, 1), "smart", "BAILIAN", 1000, 100, 0, 0);
        var usd = new OperatingReportQueryService.DailyRow(
                cny.businessDate(), cny.tenantId(), cny.userId(), cny.packagePlanVersionId(),
                cny.logicalModel(), cny.upstreamModel(), cny.channel(), cny.provider(),
                cny.billingMode(), "USD", BigDecimal.valueOf(9000), cny.cashInAmount(),
                cny.refundAmount(), cny.providerCostAmount(), cny.paymentProviderFeeAmount(),
                cny.refundProviderFeeAmount(), cny.allocatedUsageRevenueAmount(),
                cny.inputTokens(), cny.outputTokens(), cny.totalTokens(), cny.factCount(),
                cny.estimatedCount(), cny.missingSourceCount());
        OperatingReportQueryService service = new OperatingReportQueryService(filter -> List.of(cny, usd));

        var result = service.query(filter(OperatingReportQueryService.Grain.DAY, null, null));

        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.total().confirmedRevenueAmount()).isEqualByComparingTo("1000");
        assertThat(OperatingReportQueryRepository.aggregateSql(
                OperatingReportQueryRepository.Grain.DAY, " AND currency = ?"))
                .contains("min(currency) currency")
                .contains("AND currency = ?");
    }

    private OperatingReportQueryService.Filter filter(
            OperatingReportQueryService.Grain grain, String model, String provider) {
        return filter(grain, model, provider, model == null ? null : "HYBRID");
    }

    private OperatingReportQueryService.Filter filter(
            OperatingReportQueryService.Grain grain, String model, String provider,
            String billingMode) {
        return new OperatingReportQueryService.Filter(
                LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 31), grain,
                model == null ? null : TENANT, model == null ? null : USER,
                model == null ? null : PACKAGE, model, model == null ? null : "qwen-plus",
                model == null ? null : "bailian-primary", provider,
                billingMode, "CNY");
    }

    private OperatingReportQueryService.DailyRow row(
            LocalDate date, String model, String provider, long revenue, long cost,
            long paymentFee, long refundFee) {
        return new OperatingReportQueryService.DailyRow(date, TENANT, USER, PACKAGE,
                model, model == null ? null : "qwen-plus",
                model == null ? null : "bailian-primary", provider, "HYBRID", "CNY",
                BigDecimal.valueOf(revenue), BigDecimal.valueOf(400), BigDecimal.valueOf(100),
                BigDecimal.valueOf(cost), BigDecimal.valueOf(paymentFee),
                BigDecimal.valueOf(refundFee), BigDecimal.valueOf(600), 10, 20, 30,
                1, 1, 1);
    }
}
