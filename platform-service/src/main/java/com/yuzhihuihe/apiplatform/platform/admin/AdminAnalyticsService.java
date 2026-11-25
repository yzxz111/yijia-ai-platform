package com.yuzhihuihe.apiplatform.platform.admin;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.yuzhihuihe.apiplatform.platform.reporting.OperatingReportQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminAnalyticsService {

    private static final int SUPPORTED_MONTHS = 6;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AdminAnalyticsRepository repository;
    private final Clock clock;
    private final OperatingReportQueryService reports;

    public AdminAnalyticsService(
            AdminAnalyticsRepository repository,
            Clock clock,
            OperatingReportQueryService reports) {
        this.repository = repository;
        this.clock = clock;
        this.reports = reports;
    }

    public OperatingAnalytics analytics(int months) {
        if (months != SUPPORTED_MONTHS) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "months_must_be_6");
        }

        LocalDate currentMonth = clock.instant().atZone(ZONE).toLocalDate().withDayOfMonth(1);
        LocalDate firstMonth = currentMonth.minusMonths(months - 1L);
        OperatingReportQueryService.ReportResult report = reports.query(
                new OperatingReportQueryService.Filter(firstMonth,
                        currentMonth.plusMonths(1).minusDays(1),
                        OperatingReportQueryService.Grain.MONTH,
                        null, null, null, null, null, null, null, null, "CNY"));
        Map<LocalDate, OperatingReportQueryService.Metrics> metrics = new LinkedHashMap<>();
        report.rows().forEach(row -> metrics.put(row.periodStart(), row.metrics()));
        OperatingSummary summary = summary(metrics.get(currentMonth), currentMonth);
        List<MonthlyOperating> monthly = monthly(firstMonth, currentMonth, metrics);
        return new OperatingAnalytics(ZONE.getId(), months, summary, monthly);
    }

    private OperatingSummary summary(OperatingReportQueryService.Metrics current, LocalDate currentMonth) {
        long collected = current == null ? 0 : current.confirmedRevenueAmount().longValueExact();
        long refunded = current == null ? 0 : current.refundAmount().longValueExact();
        AdminAnalyticsRepository.SummaryCounts counts = repository.summary(
                currentMonth.atStartOfDay(ZONE).toInstant(),
                currentMonth.plusMonths(1).atStartOfDay(ZONE).toInstant());
        return new OperatingSummary(
                counts.registeredUsers(), counts.activeUsers(), collected, refunded,
                collected - refunded, counts.paidOrders(), counts.currentMonthOrders(),
                counts.currentMonthPaidOrders(), counts.currentMonthInvalidOrRefunded());
    }

    private List<MonthlyOperating> monthly(
            LocalDate firstMonth, LocalDate currentMonth,
            Map<LocalDate, OperatingReportQueryService.Metrics> metrics) {
        return repository.monthly(
                        firstMonth,
                        currentMonth,
                        firstMonth.atStartOfDay(ZONE).toInstant(),
                        currentMonth.plusMonths(1).atStartOfDay(ZONE).toInstant())
                .stream()
                .map(row -> {
                    OperatingReportQueryService.Metrics value = metrics.get(row.month());
                    long collected = value == null
                            ? 0 : value.confirmedRevenueAmount().longValueExact();
                    long refunded = value == null ? 0 : value.refundAmount().longValueExact();
                    return new MonthlyOperating(
                            row.month(), row.newUsers(), collected, refunded, collected - refunded);
                })
                .toList();
    }

    public record OperatingSummary(
            long registeredUsers,
            long activeUsers,
            long currentMonthCollectedAmount,
            long currentMonthRefundedAmount,
            long currentMonthNetAmount,
            long paidOrders,
            long currentMonthOrders,
            long currentMonthPaidOrders,
            long currentMonthInvalidOrRefunded) {

        public OperatingSummary(
                long registeredUsers,
                long activeUsers,
                long currentMonthCollectedAmount,
                long currentMonthRefundedAmount,
                long currentMonthNetAmount,
                long paidOrders) {
            this(registeredUsers, activeUsers, currentMonthCollectedAmount,
                    currentMonthRefundedAmount, currentMonthNetAmount, paidOrders, 0, 0, 0);
        }
    }

    public record MonthlyOperating(
            LocalDate month,
            long newUsers,
            long collectedAmount,
            long refundedAmount,
            long netAmount) { }

    public record OperatingAnalytics(
            String timezone,
            int months,
            OperatingSummary summary,
            List<MonthlyOperating> monthly) { }
}
