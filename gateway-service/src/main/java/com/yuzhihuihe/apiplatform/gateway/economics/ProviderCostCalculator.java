package com.yuzhihuihe.apiplatform.gateway.economics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.time.Instant;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ProviderCostCalculator {

    private final DatabaseClient databaseClient;

    public ProviderCostCalculator(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<CostSnapshot> calculate(
            UUID providerId, UUID modelId, long inputTokens, long outputTokens, Instant occurredAt) {
        return calculate(providerId, modelId,
                new UsageBreakdown(inputTokens, outputTokens, null, null, null, null), occurredAt);
    }

    public Mono<CostSnapshot> calculate(
            UUID providerId, UUID modelId, UsageBreakdown usage, Instant occurredAt) {
        long cacheReadTokens = usage.cacheHitTokens() == null ? 0 : usage.cacheHitTokens();
        long cacheWriteTokens = usage.cacheWriteTokens() == null ? 0 : usage.cacheWriteTokens();
        long reasoningTokens = usage.reasoningTokens() == null ? 0 : usage.reasoningTokens();
        long uncachedInputTokens = Math.subtractExact(
                Math.subtractExact(usage.inputTokens(), cacheReadTokens), cacheWriteTokens);
        long normalOutputTokens = Math.subtractExact(usage.outputTokens(), reasoningTokens);
        return databaseClient.sql("""
                        SELECT price.id AS provider_price_id,
                               price.currency,
                               price.internal_exchange_rate AS exchange_rate,
                               CAST((CAST(:uncached_input_tokens AS numeric)
                                     * price.input_price_per_million
                                     + CAST(:cache_read_tokens AS numeric)
                                     * COALESCE(price.cache_read_price_per_million,
                                                price.input_price_per_million)
                                     + CAST(:cache_write_tokens AS numeric)
                                     * COALESCE(price.cache_write_price_per_million,
                                                price.input_price_per_million)) / 1000000
                                    AS numeric(20,8)) AS original_input_amount,
                               CAST((CAST(:normal_output_tokens AS numeric)
                                     * price.output_price_per_million
                                     + CAST(:reasoning_tokens AS numeric)
                                     * COALESCE(price.reasoning_price_per_million,
                                                price.output_price_per_million)) / 1000000
                                    AS numeric(20,8)) AS original_output_amount,
                               CAST((CAST(:uncached_input_tokens AS numeric)
                                     * price.input_price_per_million
                                     + CAST(:cache_read_tokens AS numeric)
                                     * COALESCE(price.cache_read_price_per_million,
                                                price.input_price_per_million)
                                     + CAST(:cache_write_tokens AS numeric)
                                     * COALESCE(price.cache_write_price_per_million,
                                                price.input_price_per_million)
                                     + CAST(:normal_output_tokens AS numeric)
                                     * price.output_price_per_million
                                     + CAST(:reasoning_tokens AS numeric)
                                     * COALESCE(price.reasoning_price_per_million,
                                                price.output_price_per_million)) / 1000000
                                    AS numeric(20,8)) AS original_total_amount,
                               CAST(CAST(:uncached_input_tokens AS numeric)
                                    * price.input_price_per_million
                                    * price.internal_exchange_rate / 1000000 AS numeric(30,12))
                                    AS uncached_input_exact_amount,
                               CAST(CAST(:cache_read_tokens AS numeric)
                                    * COALESCE(price.cache_read_price_per_million,
                                               price.input_price_per_million)
                                    * price.internal_exchange_rate / 1000000 AS numeric(30,12))
                                    AS cache_read_exact_amount,
                               CAST(CAST(:cache_write_tokens AS numeric)
                                    * COALESCE(price.cache_write_price_per_million,
                                               price.input_price_per_million)
                                    * price.internal_exchange_rate / 1000000 AS numeric(30,12))
                                    AS cache_write_exact_amount,
                               CAST(ceil((CAST(:uncached_input_tokens AS numeric)
                                    * price.input_price_per_million
                                    + CAST(:cache_read_tokens AS numeric)
                                    * COALESCE(price.cache_read_price_per_million,
                                               price.input_price_per_million)
                                    + CAST(:cache_write_tokens AS numeric)
                                    * COALESCE(price.cache_write_price_per_million,
                                               price.input_price_per_million))
                                    * price.internal_exchange_rate / 1000000) AS bigint)
                                    AS input_amount,
                               CAST(CAST(:normal_output_tokens AS numeric)
                                    * price.output_price_per_million
                                    * price.internal_exchange_rate / 1000000 AS numeric(30,12))
                                    AS normal_output_exact_amount,
                               CAST(CAST(:reasoning_tokens AS numeric)
                                    * COALESCE(price.reasoning_price_per_million,
                                               price.output_price_per_million)
                                    * price.internal_exchange_rate / 1000000 AS numeric(30,12))
                                    AS reasoning_exact_amount,
                               CAST(ceil((CAST(:normal_output_tokens AS numeric)
                                    * price.output_price_per_million
                                    + CAST(:reasoning_tokens AS numeric)
                                    * COALESCE(price.reasoning_price_per_million,
                                               price.output_price_per_million))
                                    * price.internal_exchange_rate / 1000000) AS bigint)
                                    AS output_amount
                        FROM gateway.provider_model_price price
                        WHERE price.provider_id = :provider_id
                          AND price.model_id = :model_id
                          AND price.effective_from <= :occurred_at
                          AND (price.effective_to IS NULL OR price.effective_to > :occurred_at)
                        ORDER BY price.effective_from DESC, price.id DESC
                        LIMIT 1
                        """)
                .bind("uncached_input_tokens", uncachedInputTokens)
                .bind("cache_read_tokens", cacheReadTokens)
                .bind("cache_write_tokens", cacheWriteTokens)
                .bind("normal_output_tokens", normalOutputTokens)
                .bind("reasoning_tokens", reasoningTokens)
                .bind("provider_id", providerId)
                .bind("model_id", modelId)
                .bind("occurred_at", occurredAt)
                .map((row, metadata) -> {
                    Long inputAmount = row.get("input_amount", Long.class);
                    Long outputAmount = row.get("output_amount", Long.class);
                    long[] inputBreakdown = allocateRoundedAmount(inputAmount,
                            row.get("uncached_input_exact_amount", BigDecimal.class),
                            row.get("cache_read_exact_amount", BigDecimal.class),
                            row.get("cache_write_exact_amount", BigDecimal.class));
                    long[] outputBreakdown = allocateRoundedAmount(outputAmount,
                            row.get("normal_output_exact_amount", BigDecimal.class),
                            row.get("reasoning_exact_amount", BigDecimal.class));
                    return new CostSnapshot(
                            row.get("provider_price_id", UUID.class),
                            row.get("currency", String.class),
                            row.get("exchange_rate", BigDecimal.class),
                            row.get("original_input_amount", BigDecimal.class),
                            row.get("original_output_amount", BigDecimal.class),
                            row.get("original_total_amount", BigDecimal.class),
                            inputAmount,
                            outputAmount,
                            Math.addExact(inputAmount, outputAmount),
                            inputBreakdown[0],
                            inputBreakdown[1],
                            inputBreakdown[2],
                            outputBreakdown[0],
                            outputBreakdown[1],
                            false,
                            false);
                })
                .one()
                .defaultIfEmpty(CostSnapshot.missingSnapshot());
    }

    private static long[] allocateRoundedAmount(long roundedTotal, BigDecimal... exactAmounts) {
        long[] allocation = new long[exactAmounts.length];
        Integer[] indexes = new Integer[exactAmounts.length];
        long allocated = 0;
        for (int index = 0; index < exactAmounts.length; index++) {
            BigDecimal exact = exactAmounts[index];
            long floor = exact.setScale(0, RoundingMode.FLOOR).longValueExact();
            allocation[index] = floor;
            allocated = Math.addExact(allocated, floor);
            indexes[index] = index;
        }
        Arrays.sort(indexes, Comparator
                .<Integer, BigDecimal>comparing(index -> exactAmounts[index]
                        .remainder(BigDecimal.ONE))
                .reversed()
                .thenComparingInt(Integer::intValue));
        long remainder = Math.subtractExact(roundedTotal, allocated);
        if (remainder > indexes.length) {
            throw new IllegalStateException("invalid_provider_cost_allocation");
        }
        for (int index = 0; index < remainder; index++) {
            allocation[indexes[index]] = Math.addExact(allocation[indexes[index]], 1);
        }
        return allocation;
    }

    public record UsageBreakdown(
            long inputTokens,
            long outputTokens,
            Long cacheHitTokens,
            Long cacheMissTokens,
            Long cacheWriteTokens,
            Long reasoningTokens) {

        public UsageBreakdown {
            if (inputTokens < 0 || outputTokens < 0
                    || invalid(cacheHitTokens) || invalid(cacheMissTokens)
                    || invalid(cacheWriteTokens) || invalid(reasoningTokens)
                    || number(cacheHitTokens) > inputTokens - number(cacheWriteTokens)
                    || number(reasoningTokens) > outputTokens) {
                throw new IllegalArgumentException("invalid_provider_usage_breakdown");
            }
        }

        private static boolean invalid(Long value) {
            return value != null && value < 0;
        }

        private static long number(Long value) {
            return value == null ? 0 : value;
        }
    }

    public record CostSnapshot(
            UUID providerPriceId,
            String currency,
            BigDecimal exchangeRate,
            BigDecimal originalInputAmount,
            BigDecimal originalOutputAmount,
            BigDecimal originalTotalAmount,
            Long inputAmount,
            Long outputAmount,
            Long totalAmount,
            Long uncachedInputAmount,
            Long cacheReadAmount,
            Long cacheWriteAmount,
            Long normalOutputAmount,
            Long reasoningAmount,
            boolean estimated,
            boolean missing) {

        private static CostSnapshot missingSnapshot() {
            return new CostSnapshot(
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, true, true);
        }
    }
}
