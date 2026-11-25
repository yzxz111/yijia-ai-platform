package com.yuzhihuihe.apiplatform.platform.reporting;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OperatingReportExportService {

    static final int MAX_RANGE_DAYS = 366;
    static final int MAX_ROWS = 50_000;
    private static final byte[] UTF8_BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    private static final String HEADER = "business_date,source_service,source_event_id,fact_id,"
            + "event_type,currency,confirmed_revenue_cents,cash_in_cents,refund_cents,"
            + "provider_cost_cents,payment_fee_cents,refund_fee_cents,"
            + "allocated_usage_revenue_exact,estimated,missing_source,reconciliation_status";

    private final Store store;

    @Autowired
    OperatingReportExportService(OperatingReportExportRepository repository) {
        this.store = new RepositoryStore(repository);
    }

    OperatingReportExportService(Store store) {
        this.store = store;
    }

    public long export(Filter filter, OutputStream output) {
        validate(filter);
        Objects.requireNonNull(output, "output");
        if (store.count(filter, MAX_ROWS + 1L) > MAX_ROWS) {
            throw new IllegalArgumentException("export_row_limit_exceeded");
        }
        try {
            output.write(UTF8_BOM);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            writer.write(HEADER);
            writer.newLine();
            AtomicLong written = new AtomicLong();
            store.stream(filter, row -> {
                if (written.incrementAndGet() > MAX_ROWS) {
                    throw new IllegalArgumentException("export_row_limit_exceeded");
                }
                try {
                    writeRow(writer, row);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
            writer.flush();
            return written.get();
        } catch (IOException exception) {
            throw new UncheckedIOException("operating_report_export_failed", exception);
        }
    }

    private static void writeRow(BufferedWriter writer, ExportRow row) throws IOException {
        String[] cells = {
                row.businessDate().toString(), row.sourceService(), row.sourceEventId(), row.factId(),
                row.eventType(), row.currency(), Long.toString(row.confirmedRevenueCents()),
                Long.toString(row.cashInCents()), Long.toString(row.refundCents()),
                Long.toString(row.providerCostCents()), Long.toString(row.paymentFeeCents()),
                Long.toString(row.refundFeeCents()), row.allocatedUsageRevenueExact().toPlainString(),
                Boolean.toString(row.estimated()), Boolean.toString(row.missingSource()),
                row.reconciliationStatus()
        };
        for (int index = 0; index < cells.length; index++) {
            if (index > 0) writer.write(',');
            writer.write(csv(cells[index]));
        }
        writer.newLine();
    }

    static String csv(String value) {
        if (value == null) return "";
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static void validate(Filter filter) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(filter.from(), "from");
        Objects.requireNonNull(filter.to(), "to");
        if (filter.to().isBefore(filter.from())) throw new IllegalArgumentException("invalid_export_range");
        if (ChronoUnit.DAYS.between(filter.from(), filter.to()) + 1 > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("export_range_exceeds_366_days");
        }
    }

    public record Filter(LocalDate from, LocalDate to, UUID tenantId, UUID userId,
            UUID packagePlanVersionId, String logicalModel, String upstreamModel, String channel,
            String provider, String billingMode, String currency) { }

    public record ExportRow(LocalDate businessDate, String sourceService, String sourceEventId,
            String factId, String eventType, String currency, long confirmedRevenueCents,
            long cashInCents, long refundCents, long providerCostCents, long paymentFeeCents,
            long refundFeeCents, BigDecimal allocatedUsageRevenueExact, boolean estimated,
            boolean missingSource, String reconciliationStatus) { }

    interface Store {
        long count(Filter filter, long limit);
        void stream(Filter filter, Consumer<ExportRow> sink);
    }

    private static final class RepositoryStore implements Store {
        private final OperatingReportExportRepository repository;

        private RepositoryStore(OperatingReportExportRepository repository) {
            this.repository = repository;
        }

        @Override
        public long count(Filter filter, long limit) {
            return repository.count(repositoryFilter(filter), limit);
        }

        @Override
        public void stream(Filter filter, Consumer<ExportRow> sink) {
            repository.stream(repositoryFilter(filter), row -> sink.accept(new ExportRow(
                    row.businessDate(), row.sourceService(), row.sourceEventId(), row.factId(),
                    row.eventType(), row.currency(), row.confirmedRevenueCents(),
                    row.cashInCents(), row.refundCents(), row.providerCostCents(),
                    row.paymentFeeCents(), row.refundFeeCents(),
                    row.allocatedUsageRevenueExact(), row.estimated(), row.missingSource(),
                    row.reconciliationStatus())));
        }

        private OperatingReportExportRepository.Filter repositoryFilter(Filter filter) {
            return new OperatingReportExportRepository.Filter(
                    filter.from(), filter.to(), filter.tenantId(), filter.userId(),
                    filter.packagePlanVersionId(), filter.logicalModel(), filter.upstreamModel(),
                    filter.channel(), filter.provider(), filter.billingMode(), filter.currency());
        }
    }
}
