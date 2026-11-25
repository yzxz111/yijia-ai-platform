package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class OperatingReportExportServiceTest {

    @Test
    void streamsUtf8BomSafeCsvWithIntegerCentsAndExactAllocation() {
        var row = new OperatingReportExportService.ExportRow(
                LocalDate.of(2026, 8, 1), "platform-service", "=source-id", "fact-id",
                "PACKAGE_PURCHASE_REVENUE", "CNY", 1234, 56, 7, 8, 9, 10,
                new BigDecimal("12.345678"), true, false, "AMOUNT_MISMATCH");
        RecordingStore store = new RecordingStore(List.of(row));
        OperatingReportExportService service = new OperatingReportExportService(store);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var filter = filter(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        long count = service.export(filter, output);

        byte[] bytes = output.toByteArray();
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(csv).contains("source_service,source_event_id,fact_id")
                .contains("platform-service,'=source-id,fact-id")
                .contains(",1234,56,7,8,9,10,12.345678,true,false,AMOUNT_MISMATCH")
                .doesNotContain("phone", "prompt", "attachment", "api_key");
        assertThat(count).isEqualTo(1);
        assertThat(store.countFilter).isEqualTo(filter);
        assertThat(store.streamFilter).isEqualTo(filter);
    }

    @Test
    void rejectsRangesAndRowsBeyondHardLimitsBeforeWriting() {
        RecordingStore store = new RecordingStore(List.of());
        OperatingReportExportService service = new OperatingReportExportService(store);

        assertThatThrownBy(() -> service.export(
                filter(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2)),
                new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("export_range_exceeds_366_days");

        store.count = 50_001;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> service.export(
                filter(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1)), output))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("export_row_limit_exceeded");
        assertThat(output.size()).isZero();
    }

    private static OperatingReportExportService.Filter filter(LocalDate from, LocalDate to) {
        return new OperatingReportExportService.Filter(from, to, null, null, null,
                null, null, null, null, null, "CNY");
    }

    private static final class RecordingStore implements OperatingReportExportService.Store {
        private final List<OperatingReportExportService.ExportRow> rows;
        private long count;
        private OperatingReportExportService.Filter countFilter;
        private OperatingReportExportService.Filter streamFilter;

        private RecordingStore(List<OperatingReportExportService.ExportRow> rows) {
            this.rows = rows;
            this.count = rows.size();
        }

        @Override public long count(OperatingReportExportService.Filter filter, long limit) {
            countFilter = filter;
            return count;
        }

        @Override public void stream(OperatingReportExportService.Filter filter,
                java.util.function.Consumer<OperatingReportExportService.ExportRow> sink) {
            streamFilter = filter;
            rows.forEach(sink);
        }
    }
}
