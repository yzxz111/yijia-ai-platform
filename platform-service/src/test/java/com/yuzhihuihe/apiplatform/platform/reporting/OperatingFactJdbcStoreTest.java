package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OperatingFactJdbcStoreTest {

    private static final UUID TENANT = UUID.fromString("94000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("94000000-0000-4000-8000-000000000002");
    private static final UUID GRANT = UUID.fromString("94000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE = UUID.fromString("94000000-0000-4000-8000-000000000004");
    private static final UUID VERSION = UUID.fromString("94000000-0000-4000-8000-000000000005");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("DROP SCHEMA IF EXISTS gateway CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jdbc.execute("CREATE SCHEMA gateway");
        jdbc.execute("CREATE SCHEMA platform");
        jdbc.execute("""
                CREATE TABLE platform.grant_request (
                    id uuid PRIMARY KEY,
                    tenant_id uuid NOT NULL,
                    user_id uuid NOT NULL,
                    package_plan_version_id uuid NOT NULL,
                    source varchar(32) NOT NULL,
                    business_no varchar(128) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE platform.customer_order (
                    id uuid PRIMARY KEY,
                    tenant_id uuid NOT NULL,
                    user_id uuid NOT NULL,
                    order_no varchar(64) NOT NULL,
                    package_plan_version_id uuid NOT NULL,
                    amount bigint NOT NULL,
                    package_snapshot jsonb,
                    status varchar(32) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE gateway.package_instance (
                    id uuid PRIMARY KEY,
                    tenant_id uuid NOT NULL,
                    user_id uuid NOT NULL,
                    grant_request_id uuid NOT NULL,
                    package_plan_version_id uuid NOT NULL,
                    total_token_quota bigint NOT NULL,
                    usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN',
                    audio_seconds_quota bigint
                )
                """);
        jdbc.execute("""
                CREATE TABLE platform.operating_fact (
                    id uuid PRIMARY KEY,
                    source_service varchar(32) NOT NULL,
                    source_event_id varchar(128) NOT NULL,
                    line_no integer NOT NULL,
                    event_type varchar(64) NOT NULL,
                    occurred_at timestamptz NOT NULL,
                    recognized_at timestamptz NOT NULL,
                    tenant_id uuid NOT NULL,
                    user_id uuid NOT NULL,
                    package_plan_version_id uuid,
                    request_id varchar(128),
                    logical_model varchar(128),
                    upstream_model varchar(128),
                    channel varchar(128),
                    provider varchar(128),
                    input_tokens bigint NOT NULL,
                    output_tokens bigint NOT NULL,
                    total_tokens bigint NOT NULL,
                    usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN',
                    usage_units bigint NOT NULL DEFAULT 0,
                    billing_mode varchar(32) NOT NULL,
                    currency varchar(8) NOT NULL,
                    confirmed_revenue_amount numeric NOT NULL,
                    provider_input_cost_amount numeric NOT NULL,
                    provider_output_cost_amount numeric NOT NULL,
                    allocated_usage_revenue_amount numeric NOT NULL,
                    estimated boolean NOT NULL,
                    missing_source boolean NOT NULL,
                    metadata jsonb NOT NULL
                )
                """);
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, tenant_id, user_id, package_plan_version_id, source, business_no)
                VALUES (?, ?, ?, ?, 'ADMIN', 'grant:test')
                """, GRANT, TENANT, USER, VERSION);
        jdbc.update("""
                INSERT INTO gateway.package_instance(
                    id, tenant_id, user_id, grant_request_id,
                    package_plan_version_id, total_token_quota,
                    usage_dimension, audio_seconds_quota)
                VALUES (?, ?, ?, ?, ?, 1000000, 'VIDEO_SECOND', 600)
                """, PACKAGE, TENANT, USER, GRANT, VERSION);
    }

    @Test
    void readsAdminGrantPackageAllocationOnPostgres17() {
        OperatingFactIngestionRepository repository = repository();

        OperatingFactIngestionRepository.PackageAllocationRow allocation =
                repository.packageAllocation(PACKAGE, TENANT, USER, "TOKEN");

        assertThat(allocation).isNotNull();
        assertThat(allocation.priceAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(allocation.packageUnitQuota()).isEqualTo(1_000_000);
        assertThat(allocation.source()).isEqualTo("ADMIN_GRANT");
        assertThat(allocation.packagePlanVersionId()).isEqualTo(VERSION);
    }

    /**
     * 方案 C：混合实例（视频秒 + 音频秒）上，**哪一列当收入的分母由事件的维度决定**。
     *
     * <p>这条断言必须在真实 SQL 上做，而不是在替身上：要证明的正是那个
     * {@code CASE} 选对了列。用主列（1000000）去摊一次语音用量不会让任何东西
     * 失败，只会把收入按错误的比例摊到每一次语音调用上。
     */
    @Test
    void anAudioEventOnAMixedInstanceIsAllocatedAgainstTheAudioSecondBucket() {
        OperatingFactIngestionRepository repository = repository();

        assertThat(repository.packageAllocation(PACKAGE, TENANT, USER, "AUDIO_SECOND")
                .packageUnitQuota()).isEqualTo(600);
        // 视频维度仍然取主列，而 TOKEN/V1 事件（没有维度）同样取主列。
        assertThat(repository.packageAllocation(PACKAGE, TENANT, USER, "VIDEO_SECOND")
                .packageUnitQuota()).isEqualTo(1_000_000);
        assertThat(repository.packageAllocation(PACKAGE, TENANT, USER, null)
                .packageUnitQuota()).isEqualTo(1_000_000);
    }

    @Test
    void writesAndQueriesProviderCostUsingInstantsOnPostgres17() {
        OperatingFactIngestionRepository repository = repository();
        Instant recognizedAt = Instant.parse("2026-08-10T12:00:00Z");
        OperatingFactIngestionRepository.FactRow fact =
                new OperatingFactIngestionRepository.FactRow(
                        UUID.randomUUID(), UUID.randomUUID(), 0, recognizedAt,
                        TENANT, USER, VERSION, "req-jdbc-time", "text-basic",
                        "qwen-turbo", "bailian-primary", "bailian",
                        10, 5, 15, "PACKAGE_ONLY", BigDecimal.ZERO,
                        BigDecimal.valueOf(3), BigDecimal.valueOf(7),
                        BigDecimal.ZERO, false, false, Map.of("test", true));

        repository.insertAll(List.of(fact));

        assertThat(repository.providerCostP95(
                "text-basic", "bailian", recognizedAt.plusSeconds(1)))
                .isEqualByComparingTo("10");
    }

    private OperatingFactIngestionRepository repository() {
        return new OperatingFactIngestionRepository(jdbc, new ObjectMapper());
    }
}
