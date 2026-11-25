package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.safety.ContentSafetyAdminService;
import com.yuzhihuihe.apiplatform.platform.safety.ContentSafetyCaseService;
import com.yuzhihuihe.apiplatform.platform.safety.SafetyServiceTestFactory;

class AccountEnforcementNotificationIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");
    private static final DataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        DATA_SOURCE = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(DATA_SOURCE)
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        JdbcTemplate fixtureJdbc = new JdbcTemplate(DATA_SOURCE);
        fixtureJdbc.execute("CREATE SCHEMA IF NOT EXISTS gateway");
        fixtureJdbc.execute("""
                CREATE TABLE gateway.safety_enforcement_cache (
                    id uuid PRIMARY KEY,
                    tenant_id uuid NOT NULL,
                    user_id uuid NOT NULL,
                    state varchar(16) NOT NULL,
                    reason_code varchar(128),
                    case_no varchar(64),
                    event_version bigint NOT NULL,
                    effective_at timestamptz NOT NULL,
                    expires_at timestamptz,
                    updated_at timestamptz NOT NULL DEFAULT now(),
                    CONSTRAINT ck_safety_enforcement_cache_state
                        CHECK (state IN ('FROZEN', 'BANNED', 'CLEARED')),
                    CONSTRAINT ck_safety_enforcement_cache_version CHECK (event_version > 0),
                    CONSTRAINT uq_safety_enforcement_cache_owner UNIQUE (tenant_id, user_id)
                )
                """);
    }

    private final JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);
    private final TransactionTemplate transactions = new TransactionTemplate(
            new DataSourceTransactionManager(DATA_SOURCE));
    private final ContentSafetyAdminService service = SafetyServiceTestFactory.admin(
            jdbc, mock(ContentSafetyCaseService.class),
            new NotificationEventService(
                    new NotificationEventRepository(jdbc), new ObjectMapper()));

    @BeforeEach
    void reset() {
        jdbc.execute("""
                ALTER TABLE gateway.safety_enforcement_cache
                DROP CONSTRAINT IF EXISTS ck_test_gateway_sync_failure
                """);
        jdbc.execute("""
                TRUNCATE TABLE platform.notification_event,
                    gateway.safety_enforcement_cache, platform.account_enforcement,
                    platform.content_safety_case, platform.app_user CASCADE
                """);
    }

    @Test
    void recordsOnlyRealFrozenBannedAndClearedTransitionsWithStableVersions() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId);
        String caseNo = "CS-00000000000000000001";
        insertCase(tenantId, userId, caseNo);

        assertThat(enforce(tenantId, userId, "FROZEN", "policy.abuse", caseNo).version())
                .isEqualTo(1);
        assertThat(enforce(tenantId, userId, "FROZEN", "changed.reason", caseNo).version())
                .isEqualTo(1);
        assertThat(enforce(tenantId, userId, "BANNED", "policy.repeat", caseNo).version())
                .isEqualTo(2);
        assertThat(enforce(tenantId, userId, "BANNED", "changed.reason", caseNo).version())
                .isEqualTo(2);
        assertThat(clear(tenantId, userId, "appeal.approved").version()).isEqualTo(3);
        assertThat(clear(tenantId, userId, "duplicate.clear").version()).isEqualTo(3);

        assertThat(events()).extracting(row -> row.get("event_type"))
                .containsExactly("ACCOUNT_FROZEN", "ACCOUNT_BANNED", "ACCOUNT_UNFROZEN");
        assertThat(events()).extracting(row -> row.get("event_key"))
                .containsExactly(
                        eventKey(tenantId, userId, 1, "FROZEN"),
                        eventKey(tenantId, userId, 2, "BANNED"),
                        eventKey(tenantId, userId, 3, "CLEARED"));
        assertThat(jdbc.queryForObject("""
                SELECT payload::text FROM platform.notification_event
                WHERE event_type = 'ACCOUNT_FROZEN'
                """, String.class))
                .contains("\"reasonCode\": \"policy.abuse\"")
                .contains("\"caseNo\": \"" + caseNo + "\"")
                .doesNotContain("changed.reason");
        assertThat(jdbc.queryForObject("""
                SELECT payload - 'reasonCode' - 'caseNo' = '{}'::jsonb
                FROM platform.notification_event
                WHERE event_type = 'ACCOUNT_FROZEN'
                """, Boolean.class)).isTrue();
    }

    @Test
    void firstBannedTransitionRecordsBannedAndClearWithoutActiveEnforcementIsSilent() {
        UUID tenantId = UUID.randomUUID();
        UUID bannedUser = UUID.randomUUID();
        UUID untouchedUser = UUID.randomUUID();
        insertUser(tenantId, bannedUser);
        insertUser(tenantId, untouchedUser);
        String caseNo = "CS-00000000000000000002";
        insertCase(tenantId, bannedUser, caseNo);

        String unsafeReason = "prompt response attachment phone 13800138000 IP 127.0.0.1";
        assertThat(enforce(tenantId, bannedUser, "BANNED", unsafeReason, caseNo).version())
                .isEqualTo(1);
        assertThat(clear(tenantId, untouchedUser, "no.active.enforcement")).isNull();

        assertThat(events()).extracting(row -> row.get("event_type"))
                .containsExactly("ACCOUNT_BANNED");
        assertThat(jdbc.queryForObject("""
                SELECT payload::text FROM platform.notification_event
                WHERE event_type = 'ACCOUNT_BANNED'
                """, String.class))
                .contains("\"reasonCode\": \"unspecified\"")
                .doesNotContain(unsafeReason, "13800138000", "127.0.0.1");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM gateway.safety_enforcement_cache WHERE user_id = ?
                """, Integer.class, untouchedUser)).isZero();
    }

    @Test
    void gatewaySyncFailureRollsBackEnforcementAndAuthoritativeEvent() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId);
        String caseNo = "CS-00000000000000000003";
        insertCase(tenantId, userId, caseNo);
        jdbc.execute("""
                ALTER TABLE gateway.safety_enforcement_cache
                ADD CONSTRAINT ck_test_gateway_sync_failure CHECK (false) NOT VALID
                """);

        assertThatThrownBy(() -> enforce(
                tenantId, userId, "FROZEN", "policy.rollback", caseNo))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.account_enforcement WHERE user_id = ?
                """, Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event WHERE user_id = ?
                """, Integer.class, userId)).isZero();
    }

    @Test
    void rejectsMaliciousUnresolvedAndCrossTenantCaseReferencesWithoutEmittingThem() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        insertUser(tenantId, userId);
        insertUser(otherTenantId, otherUserId);
        String otherCaseNo = "CS-00000000000000000004";
        insertCase(otherTenantId, otherUserId, otherCaseNo);

        for (String invalidCaseNo : List.of(
                "prompt response attachment phone 13800138000 IP 127.0.0.1",
                "CS-ffffffffffffffffffff",
                otherCaseNo)) {
            assertThatThrownBy(() -> enforce(
                    tenantId, userId, "FROZEN", "policy.invalid-case", invalidCaseNo))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("content_safety_case_not_found");
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.account_enforcement", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.notification_event", Integer.class)).isZero();
    }

    @Test
    void concurrentFirstDifferentStatesAreSerializedIntoOrderedTransitions() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId);
        String frozenCaseNo = "CS-00000000000000000005";
        String bannedCaseNo = "CS-00000000000000000006";
        insertCase(tenantId, userId, frozenCaseNo);
        insertCase(tenantId, userId, bannedCaseNo);

        List<ContentSafetyAdminService.EnforcementView> results = concurrentEnforce(
                tenantId, userId,
                new EnforcementCommand("FROZEN", frozenCaseNo),
                new EnforcementCommand("BANNED", bannedCaseNo));

        assertThat(results).extracting(ContentSafetyAdminService.EnforcementView::version)
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(events()).extracting(row -> row.get("event_type"))
                .containsExactlyInAnyOrder("ACCOUNT_FROZEN", "ACCOUNT_BANNED");
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT version) FROM platform.account_enforcement
                WHERE tenant_id = ? AND user_id = ?
                """, Integer.class, tenantId, userId)).isOne();
        assertThat(service.enforcement(tenantId, userId).version()).isEqualTo(2);
    }

    @Test
    void concurrentFirstSameStateCreatesOneVersionAndOneEvent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId);
        String caseNo = "CS-00000000000000000007";
        insertCase(tenantId, userId, caseNo);

        List<ContentSafetyAdminService.EnforcementView> results = concurrentEnforce(
                tenantId, userId,
                new EnforcementCommand("FROZEN", caseNo),
                new EnforcementCommand("FROZEN", caseNo));

        assertThat(results).extracting(ContentSafetyAdminService.EnforcementView::version)
                .containsOnly(1L);
        assertThat(events()).extracting(row -> row.get("event_type"))
                .containsExactly("ACCOUNT_FROZEN");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.account_enforcement", Integer.class)).isOne();
    }

    private ContentSafetyAdminService.EnforcementView enforce(
            UUID tenantId, UUID userId, String state, String reasonCode, String caseNo) {
        return transactions.execute(status -> service.enforce(
                tenantId, userId, state, reasonCode, caseNo, userId));
    }

    private ContentSafetyAdminService.EnforcementView clear(
            UUID tenantId, UUID userId, String reasonCode) {
        return transactions.execute(status -> service.clear(tenantId, userId, userId, reasonCode));
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList("""
                SELECT event_key, event_type FROM platform.notification_event
                ORDER BY occurred_at, event_key
                """);
    }

    private void insertUser(UUID tenantId, UUID userId) {
        jdbc.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name)
                VALUES (?, ?, ?, 'test-hash', 'Enforcement test user')
                """, userId, tenantId, userId + "@example.invalid");
    }

    private void insertCase(UUID tenantId, UUID userId, String caseNo) {
        jdbc.update("""
                INSERT INTO platform.content_safety_policy_version(
                    version, status, config, config_sha256)
                VALUES (999, 'DRAFT', '{}'::jsonb, ?)
                ON CONFLICT (version) DO NOTHING
                """, "0".repeat(64));
        String fingerprint = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO platform.content_safety_case(
                    id, case_no, tenant_id, user_id, request_id, direction, source,
                    category, severity, action, status, policy_version,
                    evidence_sha256, case_fingerprint)
                VALUES (?, ?, ?, ?, ?, 'INPUT', 'SYSTEM', 'POLICY', 'HIGH',
                        'BLOCK', 'OPEN', 999, ?, ?)
                """, UUID.randomUUID(), caseNo, tenantId, userId, UUID.randomUUID().toString(),
                "0".repeat(64), fingerprint);
    }

    private List<ContentSafetyAdminService.EnforcementView> concurrentEnforce(
            UUID tenantId,
            UUID userId,
            EnforcementCommand first,
            EnforcementCommand second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ContentSafetyAdminService.EnforcementView> firstResult = executor.submit(() -> {
                await(start);
                return enforce(tenantId, userId, first.state(), "policy.concurrent", first.caseNo());
            });
            Future<ContentSafetyAdminService.EnforcementView> secondResult = executor.submit(() -> {
                await(start);
                return enforce(tenantId, userId, second.state(), "policy.concurrent", second.caseNo());
            });
            return List.of(firstResult.get(), secondResult.get());
        }
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception exception) {
            throw new IllegalStateException("enforcement_test_barrier_failed", exception);
        }
    }

    private String eventKey(UUID tenantId, UUID userId, long version, String state) {
        return "account-enforcement:%s:%s:%d:%s".formatted(
                tenantId, userId, version, state);
    }

    private record EnforcementCommand(String state, String caseNo) {
    }
}
