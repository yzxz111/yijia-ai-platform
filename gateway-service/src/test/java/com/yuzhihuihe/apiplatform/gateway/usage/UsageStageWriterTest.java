package com.yuzhihuihe.apiplatform.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.economics.ProviderCostCalculator;
import com.yuzhihuihe.apiplatform.gateway.economics.UsageEconomicsOutboxService;
import com.yuzhihuihe.apiplatform.gateway.economics.UsageEconomicsOutboxRepository;
import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class UsageStageWriterTest {

    private static final UUID TENANT_ID = UUID.fromString("62000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("72000000-0000-4000-8000-000000000001");
    private static final UUID PACKAGE_ID = UUID.fromString("82000000-0000-4000-9000-000000000001");
    private static final UUID API_KEY_ID = UUID.fromString("83000000-0000-4000-9000-000000000001");
    private static final UUID PUBLIC_MODEL_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000008");
    private static final UUID ROUTING_MODEL_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000003");
    private static final UUID SECOND_ROUTING_MODEL_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final UUID PROVIDER_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000002");
    private static final UUID SECOND_PROVIDER_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000001");
    private static final UUID CHANNEL_ID =
            UUID.fromString("84000000-0000-4000-8000-000000000002");
    private static final UUID SECOND_CHANNEL_ID =
            UUID.fromString("84000000-0000-4000-8000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private UsageStageWriter writer;
    private DatabaseClient database;
    private TransactionalOperator transactions;

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
        execute("TRUNCATE gateway.provider_cost_record, gateway.usage_stage, gateway.usage_log, gateway.quota_ledger, "
                + "gateway.quota_reservation, gateway.api_key, "
                + "gateway.package_instance_model_quota, gateway.package_instance CASCADE");
        execute("DELETE FROM gateway.provider_model_price WHERE provider_id = '" + PROVIDER_ID
                + "' AND model_id IN ('" + ROUTING_MODEL_ID + "', '" + PUBLIC_MODEL_ID + "')");
        seedPackageAndKey();
        seedReservation("86000000-0000-4000-9000-000000000001", "req-stage-success");

        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                .option(ConnectionFactoryOptions.PORT, POSTGRES.getFirstMappedPort())
                .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                .build();
        ConnectionFactory connectionFactory = ConnectionFactories.get(options);
        database = DatabaseClient.create(connectionFactory);
        transactions = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
        writer = new UsageStageWriter(database, transactions);
    }

    @Test
    void writesOnePublicParentAndOneComponentStageWithEffectiveRoundedCost() throws Exception {
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million,
                    output_price_per_million, currency, internal_exchange_rate,
                    effective_from, effective_to)
                VALUES
                    ('86000000-0000-4000-9000-000000000011', '%s', '%s',
                     9000000, 9000000, 'CNY', 1, now() - interval '2 days', now() - interval '1 day'),
                    ('86000000-0000-4000-9000-000000000012', '%s', '%s',
                     100000, 200000, 'USD', 7.2, now() - interval '1 hour', now() + interval '1 hour'),
                    ('86000000-0000-4000-9000-000000000013', '%s', '%s',
                     8000000, 8000000, 'CNY', 1, now() + interval '1 day', NULL),
                    ('86000000-0000-4000-9000-000000000014', '%s', '%s',
                     7000000, 7000000, 'CNY', 1, now() - interval '1 hour', NULL)
                """.formatted(
                PROVIDER_ID, ROUTING_MODEL_ID,
                PROVIDER_ID, ROUTING_MODEL_ID,
                PROVIDER_ID, ROUTING_MODEL_ID,
                PROVIDER_ID, PUBLIC_MODEL_ID));

        writer.write(compositeEntry(
                "req-stage-success", "86000000-0000-4000-9000-000000000001",
                new ProviderAdapter.TokenUsage(3, 2, 5), QuotaService.UsageQuality.ACTUAL,
                "SUCCEEDED", "provider_error", "sanitized")).block();

        assertThat(singleLong("SELECT count(*) FROM gateway.usage_log")).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM gateway.usage_stage")).isEqualTo(1);
        assertThat(singleString("SELECT model_code FROM gateway.usage_log"))
                .isEqualTo("multimodal-basic");
        assertThat(singleString("SELECT logical_model_code FROM gateway.usage_stage"))
                .isEqualTo("vision-basic");
        assertThat(singleLong("SELECT total_tokens FROM gateway.usage_log"))
                .isEqualTo(singleLong("SELECT total_tokens FROM gateway.usage_stage"));
        assertThat(singleLong("SELECT provider_cost_amount FROM gateway.usage_stage"))
                .isEqualTo(6);
        assertThat(singleString("SELECT provider_cost_currency FROM gateway.usage_stage"))
                .isEqualTo("CNY");
        assertThat(singleBoolean("SELECT price_estimated FROM gateway.usage_stage")).isFalse();
        assertThat(singleString("SELECT provider_price_id::text FROM gateway.usage_stage"))
                .isEqualTo("86000000-0000-4000-9000-000000000012");
        assertThat(singleLong("SELECT provider_input_cost_amount FROM gateway.usage_stage"))
                .isEqualTo(3);
        assertThat(singleLong("SELECT provider_output_cost_amount FROM gateway.usage_stage"))
                .isEqualTo(3);
        assertThat(singleDecimal("SELECT provider_original_input_cost_amount FROM gateway.usage_stage"))
                .isEqualByComparingTo("0.30000000");
        assertThat(singleDecimal("SELECT provider_original_output_cost_amount FROM gateway.usage_stage"))
                .isEqualByComparingTo("0.40000000");
        assertThat(singleDecimal("SELECT provider_original_cost_amount FROM gateway.usage_stage"))
                .isEqualByComparingTo("0.70000000");
        assertThat(singleString("SELECT provider_original_cost_currency FROM gateway.usage_stage"))
                .isEqualTo("USD");
        assertThat(singleDecimal("SELECT provider_exchange_rate FROM gateway.usage_stage"))
                .isEqualByComparingTo("7.20000000");
        assertThat(singleLong("SELECT used_tokens FROM gateway.package_instance_model_quota "
                + "WHERE model_code = 'vision-basic'")).isZero();
    }

    @Test
    void marksMissingPriceAsEstimatedWithoutInventingAmountOrCurrency() throws Exception {
        writer.write(compositeEntry(
                "req-stage-success", "86000000-0000-4000-9000-000000000001",
                new ProviderAdapter.TokenUsage(4, 6, 10), QuotaService.UsageQuality.ESTIMATED,
                "FAILED", "provider_error", "sanitized")).block();

        assertThat(singleNullableLong(
                "SELECT provider_cost_amount FROM gateway.usage_stage")).isNull();
        assertThat(singleNullableString(
                "SELECT provider_cost_currency FROM gateway.usage_stage")).isNull();
        assertThat(singleBoolean("SELECT price_estimated FROM gateway.usage_stage")).isTrue();
        assertThat(singleBoolean("SELECT usage_estimated FROM gateway.usage_stage")).isTrue();
        assertThat(singleString("SELECT outcome FROM gateway.usage_stage")).isEqualTo("FAILED");
    }

    @Test
    void writesFailoverStagesInOrderWithEachStagesProviderModelAndUsagePrice() throws Exception {
        // 两条 fixture 行都从现在起生效，而不是一分钟之前：ProviderCostCalculator 解析请求
        // 时刻或之前的最新一行，而 V48 播种了一条当前的 DeepSeek text-basic 行，因此日期被
        // 倒填的 fixture 会输给它，该 stage 就会按播种的基准而不是 fixture 的基准计价。这条
        // fixture 必须是对应的最新一行，正如运维人员的价格变更也必须是最新的一样。
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million,
                    output_price_per_million, currency, internal_exchange_rate,
                    effective_from, effective_to)
                VALUES
                    ('86000000-0000-4000-9000-000000000021', '%s', '%s',
                     1000000, 1000000, 'CNY', 1, now(), NULL),
                    ('86000000-0000-4000-9000-000000000022', '%s', '%s',
                     2000000, 2000000, 'USD', 1, now(), NULL)
                """.formatted(
                PROVIDER_ID, ROUTING_MODEL_ID,
                SECOND_PROVIDER_ID, SECOND_ROUTING_MODEL_ID));
        var stages = java.util.List.of(
                new ChatCompletionService.UsageStage(
                        "FINAL_RESPONSE", ROUTING_MODEL_ID, "vision-basic", "qwen-vl-plus",
                        PROVIDER_ID, CHANNEL_ID, new ProviderAdapter.TokenUsage(90, 9, 99),
                        QuotaService.UsageQuality.ACTUAL, 20, "FAILED", "provider_error", false, true),
                new ChatCompletionService.UsageStage(
                        "FINAL_RESPONSE", SECOND_ROUTING_MODEL_ID, "text-basic", "deepseek-chat",
                        SECOND_PROVIDER_ID, SECOND_CHANNEL_ID, new ProviderAdapter.TokenUsage(12, 1, 13),
                        QuotaService.UsageQuality.ESTIMATED, 30, "SUCCEEDED", null, true, true));
        ChatCompletionService.UsageEntry entry = new ChatCompletionService.UsageEntry(
                "req-stage-success", UUID.fromString("86000000-0000-4000-9000-000000000001"),
                TENANT_ID, USER_ID, API_KEY_ID, PACKAGE_ID,
                PUBLIC_MODEL_ID, "multimodal-basic", SECOND_ROUTING_MODEL_ID, "text-basic", true,
                "deepseek-chat", SECOND_PROVIDER_ID, SECOND_CHANNEL_ID,
                new ProviderAdapter.TokenUsage(102, 10, 112), QuotaService.UsageQuality.ESTIMATED,
                true, 15L, 50, 4, 200, "SUCCEEDED", null, null, stages);

        writer.write(entry).block();

        assertThat(singleLong("SELECT total_tokens FROM gateway.usage_log")).isEqualTo(112);
        assertThat(strings("""
                SELECT concat_ws(':', stage_index, logical_model_code, provider_id,
                                 total_tokens, outcome, provider_cost_amount)
                FROM gateway.usage_stage
                ORDER BY stage_index
                """)).containsExactly(
                "0:vision-basic:" + PROVIDER_ID + ":99:FAILED:99",
                "1:text-basic:" + SECOND_PROVIDER_ID + ":13:SUCCEEDED:26");
        assertThat(strings("""
                SELECT customer_billable || ':' || provider_billable
                FROM gateway.usage_stage ORDER BY stage_index
                """)).containsExactly("false:true", "true:true");
    }

    @Test
    void writesEveryExplicitAttemptForNonCompositeFailover() throws Exception {
        var stages = List.of(
                new ChatCompletionService.UsageStage(
                        "FINAL_RESPONSE", ROUTING_MODEL_ID, "vision-basic", "qwen-vl-plus",
                        PROVIDER_ID, CHANNEL_ID, new ProviderAdapter.TokenUsage(9, 1, 10),
                        QuotaService.UsageQuality.ACTUAL, 20, "FAILED", "provider_error", false, true),
                new ChatCompletionService.UsageStage(
                        "FINAL_RESPONSE", ROUTING_MODEL_ID, "vision-basic", "qwen-vl-plus",
                        PROVIDER_ID, CHANNEL_ID, new ProviderAdapter.TokenUsage(3, 2, 5),
                        QuotaService.UsageQuality.ACTUAL, 30, "SUCCEEDED", null, true, true));
        ChatCompletionService.UsageEntry entry = new ChatCompletionService.UsageEntry(
                "req-stage-success", UUID.fromString("86000000-0000-4000-9000-000000000001"),
                TENANT_ID, USER_ID, API_KEY_ID, PACKAGE_ID,
                ROUTING_MODEL_ID, "vision-basic", ROUTING_MODEL_ID, "vision-basic", false,
                "qwen-vl-plus", PROVIDER_ID, CHANNEL_ID,
                new ProviderAdapter.TokenUsage(3, 2, 5), QuotaService.UsageQuality.ACTUAL,
                false, null, 50, 4, 200, "SUCCEEDED", null, null, stages);

        writer.write(entry).block();

        assertThat(singleLong("SELECT count(*) FROM gateway.usage_stage")).isEqualTo(2);
        assertThat(strings("""
                SELECT outcome || ':' || customer_billable || ':' || provider_billable
                FROM gateway.usage_stage ORDER BY stage_index
                """)).containsExactly("FAILED:false:true", "SUCCEEDED:true:true");
    }

    @Test
    void persistsDetailedOfficialUsageAndDoesNotPriceProviderNonbillableStage() throws Exception {
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million,
                    output_price_per_million, currency, internal_exchange_rate,
                    effective_from, effective_to)
                VALUES ('86000000-0000-4000-9000-000000000023', '%s', '%s',
                        1000000, 1000000, 'CNY', 1, now() - interval '1 minute', NULL)
                """.formatted(PROVIDER_ID, ROUTING_MODEL_ID));
        var rawUsage = new ObjectMapper().readTree("""
                {"prompt_tokens":100,"completion_tokens":40,"total_tokens":140,
                 "prompt_cache_hit_tokens":20,"provider_only":"audit"}
                """);
        var officialUsage = new ProviderAdapter.TokenUsage(
                100, 40, 140, 20L, 75L, 5L, 12L, 55L, 30L, 15L,
                ProviderAdapter.UsageSource.PROVIDER_ACTUAL,
                "provider-request-123", rawUsage);
        var nonbillableUsage = new ProviderAdapter.TokenUsage(
                10, 2, 12, null, null, null, null, null, null, null,
                ProviderAdapter.UsageSource.LOCAL_ESTIMATED, null, null);
        var stages = List.of(
                new ChatCompletionService.UsageStage(
                        "FINAL_RESPONSE", ROUTING_MODEL_ID, "vision-basic", "qwen-vl-plus",
                        PROVIDER_ID, CHANNEL_ID, officialUsage, QuotaService.UsageQuality.ACTUAL,
                        20, "FAILED", "provider_error", false, true),
                new ChatCompletionService.UsageStage(
                        "FINAL_RESPONSE", ROUTING_MODEL_ID, "vision-basic", "qwen-vl-plus",
                        PROVIDER_ID, CHANNEL_ID, nonbillableUsage, QuotaService.UsageQuality.ESTIMATED,
                        30, "SUCCEEDED", null, true, false));
        ChatCompletionService.UsageEntry entry = new ChatCompletionService.UsageEntry(
                "req-stage-success", UUID.fromString("86000000-0000-4000-9000-000000000001"),
                TENANT_ID, USER_ID, API_KEY_ID, PACKAGE_ID,
                PUBLIC_MODEL_ID, "multimodal-basic", ROUTING_MODEL_ID, "vision-basic", true,
                "qwen-vl-plus", PROVIDER_ID, CHANNEL_ID, nonbillableUsage,
                QuotaService.UsageQuality.ESTIMATED, false, null, 50, 4, 200,
                "SUCCEEDED", null, null, stages);

        writer.write(entry).block();

        assertThat(singleString("SELECT provider_request_id FROM gateway.usage_stage WHERE stage_index=0"))
                .isEqualTo("provider-request-123");
        assertThat(singleString("SELECT usage_source FROM gateway.usage_stage WHERE stage_index=0"))
                .isEqualTo("PROVIDER_ACTUAL");
        assertThat(strings("""
                SELECT concat_ws(':', cache_hit_tokens, cache_miss_tokens, cache_write_tokens,
                    reasoning_tokens, text_tokens, image_tokens, audio_tokens)
                FROM gateway.usage_stage WHERE stage_index=0
                """)).containsExactly("20:75:5:12:55:30:15");
        assertThat(singleString("SELECT raw_usage->>'provider_only' FROM gateway.usage_stage WHERE stage_index=0"))
                .isEqualTo("audit");
        assertThat(singleString("SELECT reconciliation_status FROM gateway.usage_stage WHERE stage_index=0"))
                .isEqualTo("PENDING");
        assertThat(singleLong("SELECT provider_cost_amount FROM gateway.usage_stage WHERE stage_index=0"))
                .isEqualTo(140);
        assertThat(singleLong("SELECT provider_uncached_input_cost_amount FROM gateway.usage_stage WHERE stage_index=0"))
                .isEqualTo(75);
        assertThat(singleLong("SELECT provider_cache_read_cost_amount FROM gateway.usage_stage WHERE stage_index=0"))
                .isEqualTo(20);
        assertThat(singleLong("SELECT provider_cache_write_cost_amount FROM gateway.usage_stage WHERE stage_index=0"))
                .isEqualTo(5);
        assertThat(singleLong("SELECT provider_normal_output_cost_amount FROM gateway.usage_stage WHERE stage_index=0"))
                .isEqualTo(28);
        assertThat(singleLong("SELECT provider_reasoning_cost_amount FROM gateway.usage_stage WHERE stage_index=0"))
                .isEqualTo(12);
        assertThat(singleNullableLong(
                "SELECT provider_cost_amount FROM gateway.usage_stage WHERE stage_index=1")).isNull();
        assertThat(singleBoolean(
                "SELECT price_estimated FROM gateway.usage_stage WHERE stage_index=1")).isFalse();
    }

    @Test
    void omitsRawUsageThatExceedsThePersistenceLimit() throws Exception {
        var oversizedRawUsage = new ObjectMapper().createObjectNode()
                .put("payload", "x".repeat(20_000));
        var usage = new ProviderAdapter.TokenUsage(
                1, 1, 2, null, null, null, null, null, null, null,
                ProviderAdapter.UsageSource.PROVIDER_ACTUAL,
                "provider-request-oversized", oversizedRawUsage);

        writer.write(compositeEntry(
                "req-stage-success", "86000000-0000-4000-9000-000000000001",
                usage, QuotaService.UsageQuality.ACTUAL,
                "SUCCEEDED", null, null)).block();

        assertThat(singleNullableString("SELECT raw_usage::text FROM gateway.usage_stage")).isNull();
        assertThat(singleString("SELECT provider_request_id FROM gateway.usage_stage"))
                .isEqualTo("provider-request-oversized");
    }

    @Test
    void recordsFailedAndCancelledStagesWithoutSensitivePayloadColumns() throws Exception {
        seedReservation("86000000-0000-4000-9000-000000000002", "req-stage-cancelled");

        writer.write(compositeEntry(
                "req-stage-success", "86000000-0000-4000-9000-000000000001",
                new ProviderAdapter.TokenUsage(1, 0, 1), QuotaService.UsageQuality.ESTIMATED,
                "FAILED", "provider_error", "sensitive-marker")).block();
        writer.write(compositeEntry(
                "req-stage-cancelled", "86000000-0000-4000-9000-000000000002",
                new ProviderAdapter.TokenUsage(2, 0, 2), QuotaService.UsageQuality.ESTIMATED,
                "CANCELLED", "cancelled", "sensitive-marker")).block();

        assertThat(strings("SELECT outcome FROM gateway.usage_stage ORDER BY outcome"))
                .containsExactly("CANCELLED", "FAILED");
        assertThat(strings("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'gateway' AND table_name = 'usage_stage'
                """)).doesNotContain(
                "request_body", "extracted_text", "image_bytes", "credential",
                "file_name", "temporary_url", "error_summary");
        assertThat(strings("SELECT to_jsonb(stage)::text FROM gateway.usage_stage stage"))
                .allSatisfy(row -> assertThat(row).doesNotContain("sensitive-marker"));
    }

    @Test
    void rollsBackTheParentWhenTheCompositeStageCannotBeInserted() throws Exception {
        ChatCompletionService.UsageEntry invalid = new ChatCompletionService.UsageEntry(
                "req-stage-success", UUID.fromString("86000000-0000-4000-9000-000000000001"),
                TENANT_ID, USER_ID, API_KEY_ID, PACKAGE_ID,
                PUBLIC_MODEL_ID, "multimodal-basic", ROUTING_MODEL_ID, "vision-basic", true,
                null, PROVIDER_ID, CHANNEL_ID,
                new ProviderAdapter.TokenUsage(1, 1, 2), QuotaService.UsageQuality.ACTUAL,
                false, null, 25, 4, 502, "FAILED", "provider_error", "sanitized");

        assertThatThrownBy(() -> writer.write(invalid).block()).isInstanceOf(RuntimeException.class);
        assertThat(singleLong("SELECT count(*) FROM gateway.usage_log")).isZero();
        assertThat(singleLong("SELECT count(*) FROM gateway.usage_stage")).isZero();
    }

    @Test
    void directUsageWritesParentAndFrozenProviderCost() throws Exception {
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million,
                    output_price_per_million, currency, internal_exchange_rate,
                    effective_from, effective_to)
                VALUES ('86000000-0000-4000-9000-000000000031', '%s', '%s',
                        100000, 200000, 'USD', 7.2, now() - interval '1 minute', NULL)
                """.formatted(PROVIDER_ID, ROUTING_MODEL_ID));
        ChatCompletionService.UsageEntry direct = new ChatCompletionService.UsageEntry(
                "req-stage-success", UUID.fromString("86000000-0000-4000-9000-000000000001"),
                TENANT_ID, USER_ID, API_KEY_ID, PACKAGE_ID,
                ROUTING_MODEL_ID, "vision-basic", "qwen-vl-plus", PROVIDER_ID, CHANNEL_ID,
                new ProviderAdapter.TokenUsage(2, 3, 5), QuotaService.UsageQuality.ACTUAL,
                false, null, 25, 4, 200, "SUCCEEDED", null, null);

        writer.write(direct).block();

        assertThat(singleLong("SELECT count(*) FROM gateway.usage_log")).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM gateway.usage_stage")).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM gateway.provider_cost_record")).isEqualTo(1);
        assertThat(singleString("SELECT stage_type FROM gateway.usage_stage"))
                .isEqualTo("FINAL_RESPONSE");
        assertThat(singleBoolean("SELECT customer_billable FROM gateway.usage_stage")).isTrue();
        assertThat(singleBoolean("SELECT provider_billable FROM gateway.usage_stage")).isTrue();
        assertThat(singleString("SELECT usage_source FROM gateway.usage_stage"))
                .isEqualTo("LOCAL_ESTIMATED");
        assertThat(singleString("SELECT provider_price_id::text FROM gateway.provider_cost_record"))
                .isEqualTo("86000000-0000-4000-9000-000000000031");
        assertThat(singleLong("SELECT provider_input_cost_amount FROM gateway.provider_cost_record"))
                .isEqualTo(2);
        assertThat(singleLong("SELECT provider_output_cost_amount FROM gateway.provider_cost_record"))
                .isEqualTo(5);
        assertThat(singleLong("SELECT amount FROM gateway.provider_cost_record")).isEqualTo(7);
        assertThat(singleDecimal("SELECT provider_original_cost_amount FROM gateway.provider_cost_record"))
                .isEqualByComparingTo("0.80000000");
        assertThat(singleString("SELECT provider_original_cost_currency FROM gateway.provider_cost_record"))
                .isEqualTo("USD");
    }

    @Test
    void directUsageKeepsParentWithoutZeroCostWhenPriceIsMissingAndReplayIsIdempotent()
            throws Exception {
        ChatCompletionService.UsageEntry direct = new ChatCompletionService.UsageEntry(
                "req-stage-success", UUID.fromString("86000000-0000-4000-9000-000000000001"),
                TENANT_ID, USER_ID, API_KEY_ID, PACKAGE_ID,
                ROUTING_MODEL_ID, "vision-basic", "qwen-vl-plus", PROVIDER_ID, CHANNEL_ID,
                new ProviderAdapter.TokenUsage(2, 3, 5), QuotaService.UsageQuality.ACTUAL,
                false, null, 25, 4, 200, "SUCCEEDED", null, null);

        writer.write(direct).block();
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million,
                    output_price_per_million, currency, internal_exchange_rate,
                    effective_from, effective_to)
                VALUES ('86000000-0000-4000-9000-000000000041', '%s', '%s',
                        100000, 200000, 'USD', 7.2, now() - interval '1 minute', NULL)
                """.formatted(PROVIDER_ID, ROUTING_MODEL_ID));
        writer.write(direct).block();

        assertThat(singleLong("SELECT count(*) FROM gateway.usage_log")).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM gateway.provider_cost_record")).isZero();
    }

    @Test
    void compositeReplayDoesNotBackfillStageCostAfterPriceAppears() throws Exception {
        writer.write(compositeEntry(
                "req-stage-success", "86000000-0000-4000-9000-000000000001",
                new ProviderAdapter.TokenUsage(4, 6, 10), QuotaService.UsageQuality.ACTUAL,
                "SUCCEEDED", null, null)).block();
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million,
                    output_price_per_million, currency, internal_exchange_rate,
                    effective_from, effective_to)
                VALUES ('86000000-0000-4000-9000-000000000042', '%s', '%s',
                        100000, 200000, 'USD', 7.2, now() - interval '1 minute', NULL)
                """.formatted(PROVIDER_ID, ROUTING_MODEL_ID));
        writer.write(compositeEntry(
                "req-stage-success", "86000000-0000-4000-9000-000000000001",
                new ProviderAdapter.TokenUsage(4, 6, 10), QuotaService.UsageQuality.ACTUAL,
                "SUCCEEDED", null, null)).block();

        assertThat(singleLong("SELECT count(*) FROM gateway.usage_log")).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM gateway.usage_stage")).isEqualTo(1);
        assertThat(singleNullableLong("SELECT provider_cost_amount FROM gateway.usage_stage")).isNull();
        assertThat(singleNullableString("SELECT provider_price_id::text FROM gateway.usage_stage")).isNull();
        assertThat(singleBoolean("SELECT price_estimated FROM gateway.usage_stage")).isTrue();
    }

    @Test
    void writesWalletOnlyUsageWithoutPackageBinding() throws Exception {
        ChatCompletionService.UsageEntry walletOnly = new ChatCompletionService.UsageEntry(
                "req-stage-success", UUID.fromString("86000000-0000-4000-9000-000000000001"),
                TENANT_ID, USER_ID, API_KEY_ID, null,
                ROUTING_MODEL_ID, "vision-basic", "qwen-vl-plus", PROVIDER_ID, CHANNEL_ID,
                new ProviderAdapter.TokenUsage(2, 3, 5), QuotaService.UsageQuality.ACTUAL,
                true, 12L, 25, 4, 200, "SUCCEEDED", null, null);

        writer.write(walletOnly).block();

        assertThat(singleLong("SELECT count(*) FROM gateway.usage_log")).isEqualTo(1);
        assertThat(singleNullableString(
                "SELECT package_instance_id::text FROM gateway.usage_log")).isNull();
    }

    private ChatCompletionService.UsageEntry compositeEntry(
            String requestId, String reservationId, ProviderAdapter.TokenUsage usage,
            QuotaService.UsageQuality quality, String outcome,
            String errorCategory, String errorSummary) {
        return new ChatCompletionService.UsageEntry(
                requestId, UUID.fromString(reservationId), TENANT_ID, USER_ID, API_KEY_ID, PACKAGE_ID,
                PUBLIC_MODEL_ID, "multimodal-basic", ROUTING_MODEL_ID, "vision-basic", true,
                "qwen-vl-plus", PROVIDER_ID, CHANNEL_ID, usage, quality,
                false, null, 25, 4, "SUCCEEDED".equals(outcome) ? 200 : 502,
                outcome, errorCategory, errorSummary);
    }

    private static void seedPackageAndKey() throws Exception {
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    identity_key, package_plan_version_id, package_code, package_name,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute,
                    max_concurrency, duration_days, daily_request_limit,
                    max_file_size_bytes, once_per_user, status, starts_at, expires_at, activated_at)
                VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', '%s',
                    'identity-stage', gen_random_uuid(), 'STARTER', 'Starter', 1900, 'CNY',
                    '[{"modelCode":"multimodal-basic","capabilities":["TEXT","IMAGE","FILE"]}]',
                    100000, 3, 20, 100000, 3, 30, 200, 20971520, false, 'ACTIVE',
                    now(), now() + interval '30 days', now())
                """.formatted(PACKAGE_ID, TENANT_ID, USER_ID));
        execute("""
                INSERT INTO gateway.package_instance_model_quota(
                    id, package_instance_id, model_code, token_quota, reserved_tokens, used_tokens)
                VALUES
                    (gen_random_uuid(), '%s', 'multimodal-basic', 50000, 0, 0),
                    (gen_random_uuid(), '%s', 'vision-basic', 50000, 0, 0)
                """.formatted(PACKAGE_ID, PACKAGE_ID));
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status)
                VALUES ('%s', '%s', '%s', '%s', 'Test', 'safe-prefix',
                    'usage-stage-test-hash', '["multimodal-basic"]', 'ACTIVE')
                """.formatted(API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID));
    }

    private static void seedReservation(String id, String requestId) throws Exception {
        execute("""
                INSERT INTO gateway.quota_reservation(
                    id, request_id, tenant_id, user_id, api_key_id, package_instance_id,
                    model_code, reserved_tokens, package_reserved_tokens, status, expires_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s',
                    'multimodal-basic', 100, 100, 'RESERVED', now() + interval '5 minutes')
                """.formatted(id, requestId, TENANT_ID, USER_ID, API_KEY_ID, PACKAGE_ID));
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long singleLong(String sql) throws Exception {
        return ((Number) singleValue(sql)).longValue();
    }

    private static Long singleNullableLong(String sql) throws Exception {
        Object value = singleValue(sql);
        return value == null ? null : ((Number) value).longValue();
    }

    private static boolean singleBoolean(String sql) throws Exception {
        return (Boolean) singleValue(sql);
    }

    @Test
    void settlementFailureDefersEconomicsUntilSettledRecoverySnapshotIsAvailable() throws Exception {
        UsageEconomicsOutboxService outbox = new UsageEconomicsOutboxService(
                new UsageEconomicsOutboxRepository(database),
                new ObjectMapper());
        UsageStageWriter economicsWriter = new UsageStageWriter(
                database, transactions, new ProviderCostCalculator(database), outbox);

        economicsWriter.write(compositeEntry(
                        "req-stage-success", "86000000-0000-4000-9000-000000000001",
                        new ProviderAdapter.TokenUsage(4, 6, 10), QuotaService.UsageQuality.ACTUAL,
                        "SUCCEEDED", null, null))
                .contextWrite(context -> context.put("suppressUsageEconomics", true))
                .block();

        assertThat(singleLong("SELECT count(*) FROM gateway.usage_log")).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM gateway.usage_economics_outbox")).isZero();
        assertThat(singleBoolean("SELECT economics_event_pending FROM gateway.usage_log")).isTrue();

        execute("""
                UPDATE gateway.quota_reservation
                SET status='SETTLED', package_settled_tokens=7, wallet_settled_amount=30
                WHERE request_id='req-stage-success'
                """);
        outbox.enqueueRecoveredSettledUsage(10).block();

        assertThat(singleLong("SELECT count(*) FROM gateway.usage_economics_outbox")).isEqualTo(1);
        assertThat(singleBoolean("SELECT economics_event_pending FROM gateway.usage_log")).isFalse();
        assertThat(singleLong("""
                SELECT (payload->>'package_settled_tokens')::bigint
                FROM gateway.usage_economics_outbox
                """)).isEqualTo(7);
        assertThat(singleLong("""
                SELECT (payload->>'wallet_settled_amount')::bigint
                FROM gateway.usage_economics_outbox
                """)).isEqualTo(30);
        assertThat(singleString("""
                SELECT payload->>'version' FROM gateway.usage_economics_outbox
                """)).isEqualTo("USAGE_ECONOMICS_V3");
        // 恢复的事件也会指明它的单位：token 行会写明 TOKEN，并携带 token 总数作为其
        // 合格单位。
        assertThat(singleString("""
                SELECT payload->>'usage_dimension' FROM gateway.usage_economics_outbox
                """)).isEqualTo("TOKEN");
        assertThat(singleLong("""
                SELECT (payload->>'usage_units')::bigint FROM gateway.usage_economics_outbox
                """)).isEqualTo(10);
        assertThat(singleString("""
                SELECT payload->'stages'->0->>'usage_source'
                FROM gateway.usage_economics_outbox
                """)).isEqualTo("LOCAL_ESTIMATED");
        assertThat(singleString("""
                SELECT payload->'stages'->0->>'customer_billable'
                FROM gateway.usage_economics_outbox
                """)).isEqualTo("true");
        assertThat(singleString("""
                SELECT payload->'stages'->0->>'reconciliation_status'
                FROM gateway.usage_economics_outbox
                """)).isEqualTo("PENDING");
    }

    private static BigDecimal singleDecimal(String sql) throws Exception {
        return (BigDecimal) singleValue(sql);
    }

    private static String singleString(String sql) throws Exception {
        return String.valueOf(singleValue(sql));
    }

    private static String singleNullableString(String sql) throws Exception {
        Object value = singleValue(sql);
        return value == null ? null : String.valueOf(value);
    }

    private static Object singleValue(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getObject(1);
        }
    }

    private static java.util.List<String> strings(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return values;
        }
    }
}
