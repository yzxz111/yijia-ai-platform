package com.yuzhihuihe.apiplatform.gateway.economics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ProviderCostCalculatorTest {

    private static final UUID MODEL_ID = UUID.fromString("82000000-0000-4000-8000-000000000003");
    private static final UUID PROVIDER_ID = UUID.fromString("81000000-0000-4000-8000-000000000002");
    private static final UUID EXPIRED_PRICE_ID = UUID.fromString("87000000-0000-4000-8000-000000000001");
    private static final UUID ACTIVE_PRICE_ID = UUID.fromString("87000000-0000-4000-8000-000000000002");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private ProviderCostCalculator calculator;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void reset() throws Exception {
        execute("DELETE FROM gateway.provider_model_price WHERE provider_id = '" + PROVIDER_ID
                + "' AND model_id = '" + MODEL_ID + "'");
        var options = ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                .option(ConnectionFactoryOptions.PORT, POSTGRES.getFirstMappedPort())
                .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                .build();
        calculator = new ProviderCostCalculator(DatabaseClient.create(ConnectionFactories.get(options)));
    }

    @Test
    void selectsPriceAtEffectiveFromAndExcludesPriceAtEffectiveTo() throws Exception {
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million,
                    output_price_per_million, currency, internal_exchange_rate,
                    effective_from, effective_to)
                VALUES
                    ('%s', '%s', '%s', 9000000, 9000000, 'CNY', 1,
                     '2026-01-01T00:00:00Z', '2026-01-01T01:00:00Z'),
                    ('%s', '%s', '%s', 100000, 200000, 'USD', 7.2,
                     '2026-01-01T01:00:00Z', NULL)
                """.formatted(
                EXPIRED_PRICE_ID, PROVIDER_ID, MODEL_ID,
                ACTIVE_PRICE_ID, PROVIDER_ID, MODEL_ID));

        var cost = calculator.calculate(
                PROVIDER_ID, MODEL_ID, 3, 2, Instant.parse("2026-01-01T01:00:00Z")).block();

        assertThat(cost).isNotNull();
        assertThat(cost.providerPriceId()).isEqualTo(ACTIVE_PRICE_ID);
        assertThat(cost.currency()).isEqualTo("USD");
        assertThat(cost.exchangeRate()).isEqualByComparingTo("7.20000000");
        assertThat(cost.originalInputAmount()).isEqualByComparingTo("0.30000000");
        assertThat(cost.originalOutputAmount()).isEqualByComparingTo("0.40000000");
        assertThat(cost.originalTotalAmount()).isEqualByComparingTo("0.70000000");
        assertThat(cost.inputAmount()).isEqualTo(3);
        assertThat(cost.outputAmount()).isEqualTo(3);
        assertThat(cost.totalAmount()).isEqualTo(6);
        assertThat(cost.estimated()).isFalse();
        assertThat(cost.missing()).isFalse();
    }

    @Test
    void roundsInputAndOutputUpSeparatelyForCnyPrice() throws Exception {
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million,
                    output_price_per_million, currency, internal_exchange_rate,
                    effective_from, effective_to)
                VALUES ('%s', '%s', '%s', 1, 1, 'CNY', 1,
                        '2026-01-01T00:00:00Z', NULL)
                """.formatted(ACTIVE_PRICE_ID, PROVIDER_ID, MODEL_ID));

        var cost = calculator.calculate(
                PROVIDER_ID, MODEL_ID, 1, 1, Instant.parse("2026-01-01T00:00:00Z")).block();

        assertThat(cost.inputAmount()).isEqualTo(1);
        assertThat(cost.outputAmount()).isEqualTo(1);
        assertThat(cost.totalAmount()).isEqualTo(2);
        assertThat(cost.originalTotalAmount()).isEqualByComparingTo(new BigDecimal("0.00000200"));
    }

    @Test
    void reportsMissingPriceWithoutInventingCost() {
        var cost = calculator.calculate(
                PROVIDER_ID, MODEL_ID, 10, 5, Instant.parse("2025-01-01T00:00:00Z")).block();

        assertThat(cost).isNotNull();
        assertThat(cost.providerPriceId()).isNull();
        assertThat(cost.currency()).isNull();
        assertThat(cost.totalAmount()).isNull();
        assertThat(cost.originalTotalAmount()).isNull();
        assertThat(cost.estimated()).isTrue();
        assertThat(cost.missing()).isTrue();
    }

    @Test
    void pricesCacheReadAndWriteByReplacingTheirGenericInputPortion() throws Exception {
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million,
                    output_price_per_million, cache_read_price_per_million,
                    cache_write_price_per_million, currency, internal_exchange_rate,
                    effective_from, effective_to)
                VALUES ('%s', '%s', '%s', 1000000, 2000000, 100000, 1500000,
                        'CNY', 1, '2026-01-01T00:00:00Z', NULL)
                """.formatted(ACTIVE_PRICE_ID, PROVIDER_ID, MODEL_ID));

        var usage = new ProviderCostCalculator.UsageBreakdown(
                100, 20, 60L, null, 10L, null);
        var cost = calculator.calculate(
                PROVIDER_ID, MODEL_ID, usage, Instant.parse("2026-01-01T00:00:00Z")).block();

        assertThat(cost.uncachedInputAmount()).isEqualTo(30);
        assertThat(cost.cacheReadAmount()).isEqualTo(6);
        assertThat(cost.cacheWriteAmount()).isEqualTo(15);
        assertThat(cost.normalOutputAmount()).isEqualTo(40);
        assertThat(cost.reasoningAmount()).isZero();
        assertThat(cost.totalAmount()).isEqualTo(91);
    }

    @Test
    void explicitReasoningPriceReplacesReasoningShareOfOutput() throws Exception {
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million,
                    output_price_per_million, reasoning_price_per_million,
                    currency, internal_exchange_rate, effective_from, effective_to)
                VALUES ('%s', '%s', '%s', 1000000, 2000000, 4000000,
                        'CNY', 1, '2026-01-01T00:00:00Z', NULL)
                """.formatted(ACTIVE_PRICE_ID, PROVIDER_ID, MODEL_ID));

        var usage = new ProviderCostCalculator.UsageBreakdown(
                10, 20, null, null, null, 5L);
        var cost = calculator.calculate(
                PROVIDER_ID, MODEL_ID, usage, Instant.parse("2026-01-01T00:00:00Z")).block();

        assertThat(cost.normalOutputAmount()).isEqualTo(30);
        assertThat(cost.reasoningAmount()).isEqualTo(20);
        assertThat(cost.outputAmount()).isEqualTo(50);
        assertThat(cost.totalAmount()).isEqualTo(60);
    }

    @Test
    void missingCategoryPricesFallBackToGenericPricesWithoutDoubleCharging() throws Exception {
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million,
                    output_price_per_million, currency, internal_exchange_rate,
                    effective_from, effective_to)
                VALUES ('%s', '%s', '%s', 1000000, 2000000, 'CNY', 1,
                        '2026-01-01T00:00:00Z', NULL)
                """.formatted(ACTIVE_PRICE_ID, PROVIDER_ID, MODEL_ID));

        var usage = new ProviderCostCalculator.UsageBreakdown(
                100, 20, 60L, 30L, 10L, 5L);
        var cost = calculator.calculate(
                PROVIDER_ID, MODEL_ID, usage, Instant.parse("2026-01-01T00:00:00Z")).block();

        assertThat(cost.inputAmount()).isEqualTo(100);
        assertThat(cost.outputAmount()).isEqualTo(40);
        assertThat(cost.totalAmount()).isEqualTo(140);
    }

    @Test
    void categoryBreakdownDoesNotIncreaseAuthoritativeInputRounding() throws Exception {
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million,
                    output_price_per_million, cache_read_price_per_million,
                    cache_write_price_per_million, currency, internal_exchange_rate,
                    effective_from, effective_to)
                VALUES ('%s', '%s', '%s', 1, 1, 1, 1,
                        'CNY', 1, '2026-01-01T00:00:00Z', NULL)
                """.formatted(ACTIVE_PRICE_ID, PROVIDER_ID, MODEL_ID));

        var usage = new ProviderCostCalculator.UsageBreakdown(
                3, 0, 1L, null, 1L, null);
        var cost = calculator.calculate(
                PROVIDER_ID, MODEL_ID, usage, Instant.parse("2026-01-01T00:00:00Z")).block();

        assertThat(cost.inputAmount()).isEqualTo(1);
        assertThat(cost.uncachedInputAmount()
                + cost.cacheReadAmount()
                + cost.cacheWriteAmount()).isEqualTo(cost.inputAmount());
        assertThat(cost.totalAmount()).isEqualTo(1);
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
