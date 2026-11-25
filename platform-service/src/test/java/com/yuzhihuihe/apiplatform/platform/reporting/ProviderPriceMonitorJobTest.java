package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ProviderPriceMonitorJobTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");

    @Test
    void healthWriteCastsNullableSuccessTimestampForPostgres() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        ProviderPriceMonitorJob.JdbcStore store =
                new ProviderPriceMonitorJob.JdbcStore(jdbc, transactions);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        store.health(true, null, NOW);

        verify(jdbc).update(sql.capture(), any(), any(), any(), any(), any());
        assertThat(sql.getValue()).contains("CAST(? AS timestamptz)");
    }

    @Test
    void storesNewPricesIdempotentlyAndAlertsAtFivePercentChange() {
        MemoryStore store = new MemoryStore();
        MemoryAlerts alerts = new MemoryAlerts();
        ProviderPriceMonitorJob job = new ProviderPriceMonitorJob(
                () -> List.of(price("p1", "100", "200")), store, alerts, () -> NOW);
        job.synchronize();
        job.synchronize();
        ProviderPriceMonitorJob changed = new ProviderPriceMonitorJob(
                () -> List.of(price("p2", "105", "200")), store, alerts, () -> NOW.plusSeconds(60));
        changed.synchronize();

        assertThat(store.prices).hasSize(2);
        assertThat(alerts.changes).hasSize(1);
        assertThat(store.healthy).isTrue();
    }

    @Test
    void alertsWhenOnlyAUsageCategoryPriceChanges() {
        ProviderPriceSnapshotClient.PriceSnapshot before = price("p-cache-1", "100", "200",
                "20", "30", "40");
        ProviderPriceSnapshotClient.PriceSnapshot after = price("p-cache-2", "100", "200",
                "22", "30", "40");

        assertThat(ProviderPriceMonitorJob.maximumChange(before, after))
                .isEqualByComparingTo("0.10000000");
    }

    @Test
    void synchronizationFailureKeepsLastSuccessfulSnapshot() {
        MemoryStore store = new MemoryStore();
        store.seed(price("p1", "100", "200"));
        ProviderPriceMonitorJob job = new ProviderPriceMonitorJob(
                () -> { throw new IllegalStateException("down"); }, store, new MemoryAlerts(), () -> NOW);

        job.synchronize();

        assertThat(store.prices).hasSize(1);
        assertThat(store.healthy).isFalse();
    }

    @Test
    void alertFailureOnSecondPriceRollsBackTheWholeSnapshotBatch() {
        MemoryStore store = new MemoryStore();
        store.seed(price("base", "100", "200"));
        MemoryAlerts alerts = new MemoryAlerts();
        alerts.failOnCall = 2;
        ProviderPriceMonitorJob job = new ProviderPriceMonitorJob(
                () -> List.of(price("p2", "105", "200"), price("p3", "110", "220")),
                store, alerts, () -> NOW.plusSeconds(60));

        job.synchronize();

        assertThat(store.prices).extracting(ProviderPriceSnapshotClient.PriceSnapshot::id)
                .containsExactly(UUID.nameUUIDFromBytes("base".getBytes()));
        assertThat(store.healthy).isFalse();
    }

    private ProviderPriceSnapshotClient.PriceSnapshot price(String id, String input, String output) {
        return price(id, input, output, null, null, null);
    }

    private ProviderPriceSnapshotClient.PriceSnapshot price(
            String id, String input, String output,
            String cacheRead, String cacheWrite, String reasoning) {
        return new ProviderPriceSnapshotClient.PriceSnapshot(UUID.nameUUIDFromBytes(id.getBytes()),
                "bailian", "qwen-plus", "qwen-plus", new BigDecimal(input),
                new BigDecimal(output), decimal(cacheRead), decimal(cacheWrite), decimal(reasoning),
                "CNY", BigDecimal.ONE, "official", NOW, null);
    }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    // ---- 镜像 JDBC 存储对已迁移 schema 的验证 ---------------------------------
    //
    // 这里锁定的缺陷存在于镜像与真实 PostgreSQL 约束
    // （ex_provider_price_no_overlap、ck_provider_price_period）的交互中，而 MemoryStore 无法
    // 复现它，因此这些用例针对已迁移的数据库运行真正的 JdbcStore。

    /** 线上实际发生的取代：(bailian, text-max) 于 2026-07-30 开启、于 2026-09-16 关闭。 */
    private static final String MIRRORED_OPEN_ID = "86000000-0000-4000-8000-000000000008";
    private static final String MIRRORED_SUPERSEDING_ID = "86100000-0000-4000-8000-000000000004";
    private static final String REPUBLISHED_ID = "86100000-0000-4000-8000-000000000005";
    private static final String HISTORICAL_ID = "86100000-0000-4000-8000-000000000006";
    private static final String BOUNDED_ID = "86100000-0000-4000-8000-000000000007";
    private static final String BACKDATED_ID = "86100000-0000-4000-8000-000000000008";
    private static final String OTHER_MODEL_ID = "86100000-0000-4000-8000-000000000009";

    private static final Instant OPEN_FROM = Instant.parse("2026-07-30T00:00:00Z");
    private static final Instant HISTORICAL_FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant HISTORICAL_TO = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant BACKDATED_FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant SUPERSEDING_FROM = Instant.parse("2026-09-16T05:35:09.047860Z");
    private static final Instant BOUNDED_FROM = Instant.parse("2026-10-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static JdbcTemplate mirrorJdbc;
    private static ProviderPriceMonitorJob.JdbcStore mirrorStore;
    private static int mirroredBatches;

    @BeforeAll
    static void migrateMirrorSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        mirrorJdbc = new JdbcTemplate(dataSource);
        mirrorStore = new ProviderPriceMonitorJob.JdbcStore(
                mirrorJdbc, new DataSourceTransactionManager(dataSource));
    }

    @Test
    void closesTheMirrorRowTheSourceSupersededInsteadOfAbortingTheBatch() {
        clearMirror();
        MemoryAlerts alerts = new MemoryAlerts();

        // 镜像按首次运行时的状态复制源那条开放结束时间的行。
        synchronizeMirror(alerts, mirrorPrice(MIRRORED_OPEN_ID, "text-max", "250", "750", OPEN_FROM, null));
        // 源随后在 2026-09-16 关闭那一行，并为同一个
        // (bailian, text-max) 键发布其继任者，这正是线上网关表今天所处的状态。
        synchronizeMirror(alerts,
                mirrorPrice(MIRRORED_SUPERSEDING_ID, "text-max", "1200", "3600", SUPERSEDING_FROM, null));

        // 没有这次关闭，该批次会死在 ex_provider_price_no_overlap 上：继任者
        // 从未被镜像，镜像保留了一段过期的开放区间，而 PROVIDER_PRICE_CHANGED 一直
        // 沉默。被取代的行现在必须正好在传入区间开始处结束。
        assertThat(mirrorIds()).containsExactlyInAnyOrder(MIRRORED_OPEN_ID, MIRRORED_SUPERSEDING_ID);
        assertThat(mirroredEffectiveTo(MIRRORED_OPEN_ID)).isEqualTo(SUPERSEDING_FROM);
        assertThat(mirroredEffectiveTo(MIRRORED_SUPERSEDING_ID)).isNull();
        assertThat(alerts.changes).containsExactly(MIRRORED_SUPERSEDING_ID);
        assertThat(mirrorReportedHealthy()).isTrue();
        assertThat(mirrorLastError()).isEmpty();
    }

    @Test
    void alertsOnAGenuineMoveAndNotOnAnUnchangedRepublishedRange() {
        clearMirror();
        MemoryAlerts alerts = new MemoryAlerts();

        // 首次见到某个键时没有可比较的镜像行，因此不会产生任何告警。
        synchronizeMirror(alerts, mirrorPrice(MIRRORED_OPEN_ID, "text-max", "250", "750", OPEN_FROM, null));
        assertThat(alerts.ratios).isEmpty();
        // 同一源行再次出现：original_price_id 已被镜像，因此它会被跳过而不是
        // 被重新插入，也不会产生任何告警。
        synchronizeMirror(alerts, mirrorPrice(MIRRORED_OPEN_ID, "text-max", "250", "750", OPEN_FROM, null));
        assertThat(alerts.ratios).isEmpty();
        assertThat(mirrorIds()).containsExactly(MIRRORED_OPEN_ID);

        // 一条把价格乘以 4.8 的取代行远超 5% 阈值。
        synchronizeMirror(alerts,
                mirrorPrice(MIRRORED_SUPERSEDING_ID, "text-max", "1200", "3600", SUPERSEDING_FROM, null));
        assertThat(alerts.changes).containsExactly(MIRRORED_SUPERSEDING_ID);
        assertThat(alerts.ratios).containsExactly(new BigDecimal("3.80000000"));

        // 价格完全相同的重新发布区间会被镜像（并关闭它所取代的行），
        // 但不得告警：变动为零。
        synchronizeMirror(alerts,
                mirrorPrice(REPUBLISHED_ID, "text-max", "1200", "3600", BOUNDED_FROM, null));
        assertThat(alerts.changes).containsExactly(MIRRORED_SUPERSEDING_ID);
        assertThat(mirrorIds()).containsExactlyInAnyOrder(
                MIRRORED_OPEN_ID, MIRRORED_SUPERSEDING_ID, REPUBLISHED_ID);
        assertThat(mirroredEffectiveTo(MIRRORED_SUPERSEDING_ID)).isEqualTo(BOUNDED_FROM);
        assertThat(mirrorReportedHealthy()).isTrue();
    }

    @Test
    void appendsRangesThatDoNotOverlapAndKeepsABoundedIncomingEnd() {
        clearMirror();
        MemoryAlerts alerts = new MemoryAlerts();
        synchronizeMirror(alerts, mirrorPrice(MIRRORED_OPEN_ID, "text-max", "250", "750", OPEN_FROM, null));

        // 一段在开放镜像行开始之前结束的有界区间与它不重叠，因此
        // 该开放行必须保持开放，而历史区间被原样追加。
        synchronizeMirror(alerts, mirrorPrice(
                HISTORICAL_ID, "text-max", "250", "750", HISTORICAL_FROM, HISTORICAL_TO));
        assertThat(mirroredEffectiveTo(MIRRORED_OPEN_ID)).isNull();
        assertThat(mirroredEffectiveTo(HISTORICAL_ID)).isEqualTo(HISTORICAL_TO);

        // 一段在它之后开始的有界区间确实与之重叠，因此该开放行在
        // 传入的 effective_from 处关闭，而传入的 effective_to 按发布时的值保留。
        synchronizeMirror(alerts, mirrorPrice(
                BOUNDED_ID, "text-max", "250", "750", SUPERSEDING_FROM, BOUNDED_FROM));
        assertThat(mirroredEffectiveTo(MIRRORED_OPEN_ID)).isEqualTo(SUPERSEDING_FROM);
        assertThat(mirroredEffectiveTo(BOUNDED_ID)).isEqualTo(BOUNDED_FROM);
        assertThat(mirrorReportedHealthy()).isTrue();
    }

    @Test
    void keepsMirroringTheRestOfTheBatchWhenARangeCannotBeOrderedAfterTheOpenRow() {
        clearMirror();
        MemoryAlerts alerts = new MemoryAlerts();
        synchronizeMirror(alerts,
                mirrorPrice(MIRRORED_OPEN_ID, "text-max", "250", "750", SUPERSEDING_FROM, null));

        // 一条跨越开放镜像行起点的回溯更正没有合法的镜像
        // 表示：唯一候选的结束值（传入的 effective_from）会早于
        // 开放行自身的起点，而 ck_provider_price_period 会拒绝它。它被略过并
        // 给出警告，而批次其余部分仍然落库，而不是像以前那样
        // 中止批次中的每一个快照。
        synchronizeMirror(alerts,
                mirrorPrice(BACKDATED_ID, "text-max", "200", "600", BACKDATED_FROM, BOUNDED_FROM),
                mirrorPrice(OTHER_MODEL_ID, "text-plus", "300", "400", SUPERSEDING_FROM, null));

        assertThat(mirrorIds()).containsExactlyInAnyOrder(MIRRORED_OPEN_ID, OTHER_MODEL_ID);
        assertThat(mirroredEffectiveTo(MIRRORED_OPEN_ID)).isNull();
        assertThat(mirroredEffectiveTo(OTHER_MODEL_ID)).isNull();
        assertThat(alerts.changes).isEmpty();
        assertThat(mirrorReportedHealthy()).isTrue();
    }

    /** 运行真实 JDBC 镜像存储的一次调度遍历，时间在前一次之后一小时。 */
    private static void synchronizeMirror(MemoryAlerts alerts,
            ProviderPriceSnapshotClient.PriceSnapshot... batch) {
        Instant checkedAt = OPEN_FROM.plusSeconds(3600L * mirroredBatches++);
        new ProviderPriceMonitorJob(() -> List.of(batch), mirrorStore, alerts, () -> checkedAt)
                .synchronize();
    }

    private static ProviderPriceSnapshotClient.PriceSnapshot mirrorPrice(String id, String model,
            String input, String output, Instant effectiveFrom, Instant effectiveTo) {
        return new ProviderPriceSnapshotClient.PriceSnapshot(UUID.fromString(id), "bailian", model, model,
                new BigDecimal(input), new BigDecimal(output), null, null, null, "CNY", BigDecimal.ONE,
                "official", effectiveFrom, effectiveTo);
    }

    private static void clearMirror() {
        mirrorJdbc.execute("DELETE FROM platform.provider_price_snapshot");
        mirrorJdbc.execute("DELETE FROM platform.provider_price_sync_health");
    }

    private static List<String> mirrorIds() {
        return mirrorJdbc.queryForList("""
                SELECT original_price_id FROM platform.provider_price_snapshot ORDER BY original_price_id
                """, String.class);
    }

    private static Instant mirroredEffectiveTo(String originalPriceId) {
        Timestamp value = mirrorJdbc.queryForObject("""
                SELECT effective_to FROM platform.provider_price_snapshot WHERE original_price_id = ?
                """, Timestamp.class, originalPriceId);
        return value == null ? null : value.toInstant();
    }

    private static boolean mirrorReportedHealthy() {
        return Boolean.TRUE.equals(mirrorJdbc.queryForObject(
                "SELECT healthy FROM platform.provider_price_sync_health WHERE singleton", Boolean.class));
    }

    private static String mirrorLastError() {
        return mirrorJdbc.queryForObject("""
                SELECT COALESCE(last_error, '') FROM platform.provider_price_sync_health WHERE singleton
                """, String.class);
    }

    static final class MemoryStore implements ProviderPriceMonitorJob.Store {
        final List<ProviderPriceSnapshotClient.PriceSnapshot> prices = new ArrayList<>();
        boolean healthy;
        private ProviderPriceSnapshotClient.PriceSnapshot latest(String provider, String model) {
            return prices.stream().filter(p -> p.provider().equals(provider) && p.upstreamModel().equals(model))
                    .reduce((a, b) -> b).orElse(null);
        }
        void seed(ProviderPriceSnapshotClient.PriceSnapshot value) { prices.add(value); }
        private boolean save(ProviderPriceSnapshotClient.PriceSnapshot value) {
            if (prices.stream().anyMatch(p -> p.id().equals(value.id()))) return false;
            prices.add(value); return true;
        }
        @Override public void applyBatch(List<ProviderPriceSnapshotClient.PriceSnapshot> values,
                Instant capturedAt, ProviderPriceMonitorJob.AlertSink alerts) {
            List<ProviderPriceSnapshotClient.PriceSnapshot> beforeBatch = new ArrayList<>(prices);
            try {
                for (ProviderPriceSnapshotClient.PriceSnapshot value : values) {
                    ProviderPriceSnapshotClient.PriceSnapshot previous =
                            latest(value.provider(), value.upstreamModel());
                    if (!save(value) || previous == null) continue;
                    BigDecimal change = ProviderPriceMonitorJob.maximumChange(previous, value);
                    if (change.compareTo(new BigDecimal("0.05")) >= 0) {
                        alerts.providerPriceChanged(previous, value, change, capturedAt);
                    }
                }
            } catch (RuntimeException failure) {
                prices.clear(); prices.addAll(beforeBatch); throw failure;
            }
        }
        @Override public void health(boolean healthy, String detail, Instant checkedAt) { this.healthy = healthy; }
    }
    static final class MemoryAlerts implements ProviderPriceMonitorJob.AlertSink {
        final List<String> changes = new ArrayList<>();
        final List<BigDecimal> ratios = new ArrayList<>();
        int failOnCall;
        @Override public void providerPriceChanged(ProviderPriceSnapshotClient.PriceSnapshot before,
                ProviderPriceSnapshotClient.PriceSnapshot after, BigDecimal ratio, Instant observedAt) {
            changes.add(after.id().toString());
            ratios.add(ratio);
            if (changes.size() == failOnCall) throw new IllegalStateException("alert failed");
        }
    }
    @Test
    @SuppressWarnings("unchecked")
    void snapshotClientSignsGatewayRequestWithInternalHmac() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("[]");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> { captured.set(invocation.getArgument(0)); return response; });
        InternalRequestSigner signer = new InternalRequestSigner("secret");
        ProviderPriceSnapshotClient client = new ProviderPriceSnapshotClient(http,
                new ObjectMapper().findAndRegisterModules(), URI.create("http://gateway:8081"), signer, true);

        assertThat(client.fetch()).isEmpty();
        HttpRequest request = captured.get();
        long timestamp = Long.parseLong(request.headers().firstValue("X-Internal-Timestamp").orElseThrow());
        UUID requestId = UUID.fromString(request.headers().firstValue("X-Internal-Request-Id").orElseThrow());
        assertThat(signer.verify(timestamp, requestId, "GET", ProviderPriceSnapshotClient.PATH,
                new byte[0], request.headers().firstValue("X-Internal-Content-Sha256").orElseThrow(),
                request.headers().firstValue("X-Internal-Signature").orElseThrow())).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotClientPreservesUsageCategoryPrices() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                [{"id":"00000000-0000-4000-8000-000000000001",
                  "providerCode":"bailian","modelCode":"qwen-plus",
                  "inputPricePerMillion":100,"outputPricePerMillion":200,
                  "cacheReadPricePerMillion":20,"cacheWritePricePerMillion":30,
                  "reasoningPricePerMillion":400,"currency":"CNY",
                  "internalExchangeRate":1,"officialSource":"official",
                  "effectiveFrom":"2026-08-08T08:00:00Z","effectiveTo":null}]
                """);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        ProviderPriceSnapshotClient client = new ProviderPriceSnapshotClient(http,
                new ObjectMapper().findAndRegisterModules(), URI.create("http://gateway:8081"),
                new InternalRequestSigner("secret"), true);

        ProviderPriceSnapshotClient.PriceSnapshot price = client.fetch().getFirst();

        assertThat(price.cacheReadPrice()).isEqualByComparingTo("20");
        assertThat(price.cacheWritePrice()).isEqualByComparingTo("30");
        assertThat(price.reasoningPrice()).isEqualByComparingTo("400");
    }
}
