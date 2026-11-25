package com.yuzhihuihe.apiplatform.platform.reporting;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/console/platform/admin/operating-reports")
public class OperatingReportController {

    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final OperatingReportQueryService queryService;
    private final OperatingReportExportService exportService;
    private final OperatingReconciliationService reconciliationService;
    private final OperatingAlertService alertService;
    private final OperatingAlertQueryService alertQueryService;

    public OperatingReportController(
            OperatingReportQueryService queryService,
            OperatingReportExportService exportService,
            OperatingReconciliationService reconciliationService,
            OperatingAlertService alertService,
            OperatingAlertQueryService alertQueryService) {
        this.queryService = queryService;
        this.exportService = exportService;
        this.reconciliationService = reconciliationService;
        this.alertService = alertService;
        this.alertQueryService = alertQueryService;
    }

    @GetMapping("/summary")
    SummaryResponse summary(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "DAY") String grain,
            @RequestParam Map<String, String> parameters) {
        var result = queryService.query(filter(from, to, grain, parameters));
        return SummaryResponse.from(result);
    }

    @GetMapping("/trend")
    OperatingReportQueryService.ReportResult trend(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "DAY") String grain,
            @RequestParam Map<String, String> parameters) {
        return queryService.query(filter(from, to, grain, parameters));
    }

    @GetMapping("/breakdown")
    OperatingReportQueryService.ReportResult breakdown(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "DAY") String grain,
            @RequestParam Map<String, String> parameters) {
        return queryService.query(filter(from, to, grain, parameters));
    }

    @GetMapping("/reconciliation")
    OperatingReconciliationService.PageResult reconciliation(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        DateRange range = range(from, to);
        return reconciliationService.reconcile(
                range.from().atStartOfDay(REPORT_ZONE).toInstant(),
                range.to().plusDays(1).atStartOfDay(REPORT_ZONE).toInstant(),
                page,
                size);
    }

    @GetMapping("/alerts")
    PageResult<AlertView> alerts(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String normalized = enumCode(status, "invalid_alert_status", "OPEN", "ACKED", "RESOLVED");
        Page bounds = page(page, size, "invalid_alert_page");
        OperatingAlertQueryService.AlertPage result =
                alertQueryService.findByStatus(normalized, bounds.size(), bounds.offset());
        List<AlertView> items = result.items().stream().map(AlertView::from).toList();
        return new PageResult<>(items, result.total(), bounds.number(), bounds.size());
    }

    @PostMapping("/alerts/{id}/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void acknowledge(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        requireUpdated(alertService.acknowledge(id, actor(jwt)));
    }

    @PostMapping("/alerts/{id}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resolve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        requireUpdated(alertService.resolve(id, actor(jwt)));
    }

    private static void requireUpdated(OperatingAlertService.TransitionResult result) {
        if (result == OperatingAlertService.TransitionResult.NOT_FOUND) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "operating_alert_not_found");
        }
        if (result != OperatingAlertService.TransitionResult.UPDATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "operating_alert_invalid_state");
        }
    }

    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    void export(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "DAY") String grain,
            @RequestParam Map<String, String> parameters,
            HttpServletResponse response)
            throws IOException {
        var filter = filter(from, to, grain, parameters);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"operating-report-"
                        + filter.from()
                        + "-"
                        + filter.to()
                        + ".csv\"");
        exportService.export(
                new OperatingReportExportService.Filter(
                        filter.from(),
                        filter.to(),
                        filter.tenantId(),
                        filter.userId(),
                        filter.packagePlanVersionId(),
                        filter.logicalModel(),
                        filter.upstreamModel(),
                        filter.channel(),
                        filter.provider(),
                        filter.billingMode(),
                        filter.currency()),
                response.getOutputStream());
    }

    private static OperatingReportQueryService.Filter filter(
            String from, String to, String grain, Map<String, String> values) {
        DateRange range = range(from, to);
        var parsedGrain = parseGrain(grain);
        UUID tenantId = uuid(values.get("tenantId"), "invalid_tenant_id");
        UUID userId = uuid(values.get("userId"), "invalid_user_id");
        UUID packageId =
                uuid(values.get("packagePlanVersionId"), "invalid_package_plan_version_id");
        String logicalModel = code(values.get("logicalModel"), "invalid_logical_model");
        String upstreamModel = code(values.get("upstreamModel"), "invalid_upstream_model");
        String channel = code(values.get("channel"), "invalid_channel");
        String provider = code(values.get("provider"), "invalid_provider");
        String billingMode =
                nullableEnum(
                        values.get("billingMode"),
                        "invalid_billing_mode",
                        "PACKAGE_ONLY",
                        "WALLET_ONLY",
                        "HYBRID");
        String currency = values.get("currency");
        currency =
                currency == null || currency.isBlank()
                        ? "CNY"
                        : enumCode(currency, "invalid_currency", "CNY", "USD");
        return new OperatingReportQueryService.Filter(
                range.from(),
                range.to(),
                parsedGrain,
                tenantId,
                userId,
                packageId,
                logicalModel,
                upstreamModel,
                channel,
                provider,
                billingMode,
                currency);
    }

    private static DateRange range(String from, String to) {
        try {
            LocalDate start = LocalDate.parse(from);
            LocalDate end = LocalDate.parse(to);
            if (end.isBefore(start)) throw invalid("invalid_report_range");
            if (ChronoUnit.DAYS.between(start, end) + 1 > 366)
                throw invalid("report_range_exceeds_366_days");
            return new DateRange(start, end);
        } catch (java.time.format.DateTimeParseException exception) {
            throw invalid("invalid_report_date");
        }
    }

    private static OperatingReportQueryService.Grain parseGrain(String value) {
        try {
            return OperatingReportQueryService.Grain.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw invalid("invalid_report_grain");
        }
    }

    private static UUID uuid(String value, String reason) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(reason);
        }
    }

    private static String code(String value, String reason) {
        if (value == null || value.isBlank()) return null;
        if (!CODE.matcher(value).matches()) throw invalid(reason);
        return value;
    }

    private static String nullableEnum(String value, String reason, String... allowed) {
        if (value == null || value.isBlank()) return null;
        return enumCode(value, reason, allowed);
    }

    private static String enumCode(String value, String reason, String... allowed) {
        String normalized = value.toUpperCase(Locale.ROOT);
        for (String option : allowed) if (option.equals(normalized)) return normalized;
        throw invalid(reason);
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private static Page page(int number, int size, String reason) {
        if (number < 0 || size < 1 || size > 100) throw invalid(reason);
        long offset = (long) number * size;
        if (offset > Integer.MAX_VALUE) throw invalid(reason);
        return new Page(number, size, (int) offset);
    }

    private static UUID actor(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw invalid("invalid_admin_actor");
        }
    }

    record DateRange(LocalDate from, LocalDate to) {}

    private record Page(int number, int size, int offset) {}

    public record PageResult<T>(List<T> items, long total, int page, int size) {}

    /**
     * @param resolutionOrigin 当由定时监控关闭该告警时为 MACHINE， 由人工关闭时为 OPERATOR，尚未解决时为 null
     * @param resolutionReason 关闭原因 —— 监控关闭时为 RECOVERED 或 NO_LONGER_PUBLISHED， 运维人员关闭时为 null（未解决时同样为
     *     null）
     */
    public record AlertView(
            UUID id,
            String type,
            String status,
            String logicalModel,
            String provider,
            BigDecimal threshold,
            BigDecimal observed,
            String currency,
            Instant firstObservedAt,
            Instant lastObservedAt,
            String resolutionOrigin,
            String resolutionReason) {
        private static AlertView from(OperatingAlertQueryService.Alert value) {
            return new AlertView(
                    value.id(),
                    value.type(),
                    value.status(),
                    value.logicalModel(),
                    value.provider(),
                    value.threshold(),
                    value.observed(),
                    value.currency(),
                    value.firstObservedAt(),
                    value.lastObservedAt(),
                    value.resolutionOrigin(),
                    value.resolutionReason());
        }
    }

    public record SummaryResponse(
            String timezone,
            String currency,
            OperatingReportQueryService.Grain grain,
            BigDecimal confirmedRevenueAmount,
            BigDecimal cashInAmount,
            BigDecimal refundAmount,
            BigDecimal providerCostAmount,
            BigDecimal paymentProviderFeeAmount,
            BigDecimal refundProviderFeeAmount,
            BigDecimal allocatedUsageRevenueAmount,
            BigDecimal profitAmount,
            BigDecimal profitMargin,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long factCount,
            long estimatedCount,
            long missingSourceCount) {
        static SummaryResponse from(OperatingReportQueryService.ReportResult result) {
            var m = result.total();
            return new SummaryResponse(
                    result.timezone(),
                    result.currency(),
                    result.grain(),
                    m.confirmedRevenueAmount(),
                    m.cashInAmount(),
                    m.refundAmount(),
                    m.providerCostAmount(),
                    m.paymentProviderFeeAmount(),
                    m.refundProviderFeeAmount(),
                    m.allocatedUsageRevenueAmount(),
                    m.profitAmount(),
                    m.profitMargin(),
                    m.inputTokens(),
                    m.outputTokens(),
                    m.totalTokens(),
                    m.factCount(),
                    m.estimatedCount(),
                    m.missingSourceCount());
        }
    }
}
