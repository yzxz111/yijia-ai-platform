package com.yuzhihuihe.apiplatform.gateway.economics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class UsageEconomicsOutboxServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private UsageEconomicsOutboxService service;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway").schemas("gateway").locations("classpath:db/migration")
                .load().migrate();
    }

    @BeforeEach
    void reset() throws Exception {
        execute("TRUNCATE gateway.usage_economics_outbox");
        var options = ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                .option(ConnectionFactoryOptions.PORT, POSTGRES.getFirstMappedPort())
                .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword()).build();
        service = new UsageEconomicsOutboxService(
                new UsageEconomicsOutboxRepository(
                        DatabaseClient.create(ConnectionFactories.get(options))),
                new ObjectMapper());
    }

    @Test
    void sameRequestAndPayloadCreatesOnlyOneOutboxRow() throws Exception {
        UsageEconomicsEvent event = event("req-1", 10, "safe-model");

        service.enqueue(event).block();
        service.enqueue(event).block();

        assertThat(singleLong("SELECT count(*) FROM gateway.usage_economics_outbox")).isEqualTo(1);
    }

    @Test
    void canonicalPayloadContainsVersionAndEconomicSnapshotsButNoSensitiveContent() throws Exception {
        service.enqueue(event("req-safe", 10, "safe-model")).block();

        String payload = singleString("SELECT payload::text FROM gateway.usage_economics_outbox");
        assertThat(payload).contains("USAGE_ECONOMICS_V3", "billing_mode",
                "package_settled_tokens", "wallet_settled_amount", "provider_original_cost_amount",
                "usage_estimated", "price_missing", "stages",
                // V3：单位是被命名的，而合规的单位数由 token 维度行的
                // token 总量推导得出，而不是留作隐式。
                "usage_dimension", "usage_units", "package_settled_units");
        assertThat(payload).doesNotContain("prompt", "api_key", "credential", "response", "attachment");
    }

    @Test
    void sameEventIdWithDifferentPayloadFailsWithoutOverwrite() throws Exception {
        UsageEconomicsEvent original = event("req-1", 10, "safe-model");
        UsageEconomicsEvent conflict = event("req-1", 11, "safe-model");
        service.enqueue(original).block();

        assertThatThrownBy(() -> service.enqueue(conflict).block())
                .hasMessageContaining("usage_economics_outbox_conflict");
        assertThat(singleString("SELECT payload->>'total_tokens' FROM gateway.usage_economics_outbox"))
                .isEqualTo("10");
    }

    /**
     * 按张计费的图片事件：数量只能待在单位字段里。
     *
     * <p>预留记录把已结算的**张数**存在 {@code package_settled_tokens}（那是该列
     * 按维度的语义），但 V3 事件的同名字段在平台侧被校验为「非 TOKEN 维度必须为 0」。
     * 修复前这里会得到 1，整条事件被判 {@code invalid_event_contract}（400），
     * 生图的收入与成本因此在运营报表里是空的。
     */
    @Test
    void imageDimensionEventCarriesUnitsWithoutTokenDenominatedSettledCount() {
        UsageEconomicsEvent event = service.toEvent(snapshot("IMAGE", 0, 0, 1, 1), List.of());

        assertThat(event.usageDimension()).isEqualTo("IMAGE");
        assertThat(event.usageUnits()).isEqualTo(1);
        assertThat(event.packageSettledUnits()).isEqualTo(1);
        assertThat(event.packageSettledTokens()).isZero();
        assertThat(event.inputTokens()).isZero();
        assertThat(event.outputTokens()).isZero();
        assertThat(event.totalTokens()).isZero();
    }

    /** 按秒计费的视频事件同样是维度化事件，必须满足同一条不变量。 */
    @Test
    void videoSecondDimensionEventCarriesSecondsWithoutTokenDenominatedSettledCount() {
        UsageEconomicsEvent event = service.toEvent(snapshot("VIDEO_SECOND", 0, 0, 8, 8), List.of());

        assertThat(event.usageDimension()).isEqualTo("VIDEO_SECOND");
        assertThat(event.usageUnits()).isEqualTo(8);
        assertThat(event.packageSettledUnits()).isEqualTo(8);
        assertThat(event.packageSettledTokens()).isZero();
    }

    /** TOKEN 维度不受影响：单位数就是 token 数，两个字段仍然相等。 */
    @Test
    void tokenDimensionEventKeepsSettledTokensEqualToSettledUnits() {
        UsageEconomicsEvent event = service.toEvent(snapshot("TOKEN", 4, 6, 10, 7), List.of());

        assertThat(event.usageUnits()).isEqualTo(10);
        assertThat(event.packageSettledUnits()).isEqualTo(7);
        assertThat(event.packageSettledTokens()).isEqualTo(7);
        assertThat(event.totalTokens()).isEqualTo(10);
    }

    private static UsageEconomicsOutboxRepository.UsageSnapshotRow snapshot(
            String dimension, long inputTokens, long outputTokens, long usageUnits,
            long packageSettledTokens) {
        return new UsageEconomicsOutboxRepository.UsageSnapshotRow(
                UUID.fromString("91000000-0000-4000-8000-000000000009"),
                "req-dimension",
                UUID.fromString("91000000-0000-4000-8000-000000000002"),
                UUID.fromString("91000000-0000-4000-8000-000000000003"),
                UUID.fromString("91000000-0000-4000-8000-000000000004"),
                UUID.fromString("91000000-0000-4000-8000-000000000005"),
                "image-basic", "doubao-seedream-5.0-pro",
                UUID.fromString("91000000-0000-4000-8000-000000000006"),
                UUID.fromString("91000000-0000-4000-8000-000000000007"),
                inputTokens, outputTokens, inputTokens + outputTokens,
                dimension, usageUnits, false, "PACKAGE_ONLY",
                packageSettledTokens, 0L,
                null, "CNY", null, 0L, 62L, 62L,
                null, null, null, null);
    }

    private static UsageEconomicsEvent event(String requestId, long totalTokens, String modelCode) {
        UUID eventId = UUID.fromString("91000000-0000-4000-8000-000000000001");
        return new UsageEconomicsEvent(
                UsageEconomicsEvent.VERSION, eventId, requestId,
                UUID.fromString("91000000-0000-4000-8000-000000000002"),
                UUID.fromString("91000000-0000-4000-8000-000000000003"),
                UUID.fromString("91000000-0000-4000-8000-000000000004"),
                UUID.fromString("91000000-0000-4000-8000-000000000005"),
                modelCode, "upstream", UUID.fromString("91000000-0000-4000-8000-000000000006"),
                UUID.fromString("91000000-0000-4000-8000-000000000007"), 4, 6, totalTokens,
                "HYBRID", 7, 30, UUID.fromString("91000000-0000-4000-8000-000000000008"),
                "CNY", new BigDecimal("7.20000000"), 2L, 3L, 5L,
                new BigDecimal("0.20"), new BigDecimal("0.30"), new BigDecimal("0.50"), "USD",
                false, false, List.of(new UsageEconomicsEvent.Stage(
                        0, "FINAL_RESPONSE", modelCode, "upstream",
                        UUID.fromString("91000000-0000-4000-8000-000000000006"),
                        UUID.fromString("91000000-0000-4000-8000-000000000007"), 4, 6, totalTokens,
                        UUID.fromString("91000000-0000-4000-8000-000000000008"), "CNY",
                        new BigDecimal("7.20000000"), 2L, 3L, 5L,
                        new BigDecimal("0.20"), new BigDecimal("0.30"), new BigDecimal("0.50"),
                        "USD", false, false)));
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

    private static String singleString(String sql) throws Exception {
        return String.valueOf(singleValue(sql));
    }

    private static Object singleValue(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getObject(1);
        }
    }
}
