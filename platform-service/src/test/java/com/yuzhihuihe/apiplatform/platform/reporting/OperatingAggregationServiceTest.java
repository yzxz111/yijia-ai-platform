package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class OperatingAggregationServiceTest {

    @Test
    void canBeCreatedForWebSliceTestsWithoutARealDataSource() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThatCode(() -> new OperatingAggregationService(
                new OperatingAggregationRepository(jdbc),
                mock(PlatformTransactionManager.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void marksShanghaiBusinessDateDirtyAndReplacesTheWholeDay() {
        MemoryStore store = new MemoryStore();
        OperatingAggregationService service = new OperatingAggregationService(store);
        LocalDate augustFirst = LocalDate.of(2026, 8, 1);
        LocalDate augustSecond = LocalDate.of(2026, 8, 2);

        service.markDirty(Instant.parse("2026-08-01T15:59:59Z"), "CNY");
        service.markDirty(Instant.parse("2026-08-01T16:00:00Z"), "CNY");
        assertThat(store.dirtyDates).containsExactly(augustFirst, augustSecond);

        store.facts.put(augustFirst, List.of(fact(1000, 200, 100, 50, 20, 10, 1)));
        assertThat(service.recomputeNextDirtyDate()).isTrue();
        assertThat(store.aggregates.get(augustFirst)).singleElement().satisfies(row -> {
            assertThat(row.confirmedRevenueAmount()).isEqualByComparingTo("1000");
            assertThat(row.profitAmount()).isEqualByComparingTo("620");
            assertThat(row.totalTokens()).isEqualTo(30);
        });

        store.facts.put(augustFirst, List.of(fact(700, 50, 25, 10, 5, 4, 1)));
        store.markDirty(augustFirst, "CNY");
        assertThat(service.recomputeDirtyDates(2)).isEqualTo(2);
        assertThat(store.aggregates.get(augustFirst)).singleElement().satisfies(row -> {
            assertThat(row.confirmedRevenueAmount()).isEqualByComparingTo("700");
            assertThat(row.profitAmount()).isEqualByComparingTo("606");
            assertThat(row.factCount()).isEqualTo(1);
        });
    }

    @Test
    void keepsRefundPositiveForNegativeSignRefundFact() {
        OperatingAggregationService.FactRow refund = new OperatingAggregationService.FactRow(
                null, null, null,
                null, null, null, null, "PACKAGE_ONLY", "CNY", -1,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(180),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, 0, false, false);

        var aggregate = OperatingAggregationService.aggregate(
                LocalDate.of(2026, 8, 1), List.of(refund)).getFirst();

        assertThat(aggregate.refundAmount()).isEqualByComparingTo("180");
        assertThat(aggregate.profitAmount()).isEqualByComparingTo("-180");
    }

    @Test
    void dirtyClaimUsesOneTransactionLockForEveryCurrencyRowOfTheDate() {
        assertThat(OperatingAggregationRepository.CLAIM_DIRTY_DATE_SQL)
                .contains("pg_try_advisory_xact_lock")
                .contains("JOIN locked_date USING (business_date)")
                .contains("FOR UPDATE OF aggregate")
                .doesNotContain("LIMIT 1\n            FOR UPDATE");
    }

    private OperatingAggregationService.FactRow fact(
            long revenue, long refund, long inputCost, long outputCost,
            long paymentFee, long refundFee, int sign) {
        return new OperatingAggregationService.FactRow(
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("20000000-0000-4000-8000-000000000001"),
                UUID.fromString("30000000-0000-4000-8000-000000000001"),
                "smart", "qwen-plus", "bailian-primary", "BAILIAN", "HYBRID", "CNY",
                sign, BigDecimal.valueOf(revenue), BigDecimal.ZERO,
                BigDecimal.valueOf(refund), BigDecimal.valueOf(inputCost),
                BigDecimal.valueOf(outputCost), BigDecimal.valueOf(paymentFee),
                BigDecimal.valueOf(refundFee), BigDecimal.valueOf(300),
                10, 20, false, false);
    }

    private static final class MemoryStore implements OperatingAggregationService.Store {
        private final List<LocalDate> dirtyDates = new ArrayList<>();
        private final Map<LocalDate, List<OperatingAggregationService.FactRow>> facts = new HashMap<>();
        private final Map<LocalDate, List<OperatingAggregationService.DailyAggregate>> aggregates =
                new HashMap<>();

        @Override
        public void markDirty(LocalDate date, String currency) {
            if (!dirtyDates.contains(date)) dirtyDates.add(date);
        }

        @Override
        public LocalDate claimDirtyDate() {
            return dirtyDates.isEmpty() ? null : dirtyDates.removeFirst();
        }

        @Override
        public List<OperatingAggregationService.FactRow> facts(LocalDate date) {
            return facts.getOrDefault(date, List.of());
        }

        @Override
        public void replace(LocalDate date, List<OperatingAggregationService.DailyAggregate> rows) {
            aggregates.put(date, List.copyOf(rows));
        }
    }
}
