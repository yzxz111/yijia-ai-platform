package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import com.yuzhihuihe.apiplatform.platform.notification.NotificationEventService;
import com.yuzhihuihe.apiplatform.platform.notification.NotificationEventTestFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class LoginActivityServiceTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant NOW = Instant.parse("2026-08-09T02:15:00Z");
    private static final String PEPPER = "test-login-risk-pepper-with-at-least-32-bytes";

    @Test
    void storesOnlyHashesAndCoarseRegionThenRecordsMediumRiskEvent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NotificationEventService events = mock(NotificationEventService.class);
        when(jdbc.queryForObject(contains("device_hash"), eq(Boolean.class), any(), any()))
                .thenReturn(false);
        when(jdbc.queryForObject(contains("subnet_hmac"), eq(Boolean.class), any(), any()))
                .thenReturn(true);
        when(jdbc.queryForObject(contains("SELECT region"), eq(String.class), any()))
                .thenReturn("Zhejiang/Hangzhou");
        AtomicReference<Object[]> insertArguments = new AtomicReference<>();
        when(jdbc.update(contains("INSERT INTO platform.login_activity"), any(Object[].class)))
                .thenAnswer(invocation -> {
                    Object[] arguments = invocation.getArguments();
                    insertArguments.set(Arrays.copyOfRange(arguments, 1, arguments.length));
                    return 1;
                });
        LoginActivityService service = service(jdbc, events, ip -> "Zhejiang/Hangzhou");
        IdentityService.UserProfile user = user();

        service.recordSuccess(user, new LoginActivityService.LoginContext(
                "203.0.113.42", "raw-device-id", "Raw Browser/123"));

        assertThat(insertArguments.get()).allSatisfy(value -> {
            if (value instanceof String text) {
                assertThat(text).doesNotContain("203.0.113.42", "raw-device-id", "Raw Browser/123");
            }
        });
        assertThat(Arrays.stream(insertArguments.get()).filter(String.class::isInstance)
                .map(String.class::cast).toList())
                .filteredOn(value -> value.matches("[0-9a-f]{64}"))
                .hasSize(4);
        verify(events).recordAuthoritativeBusinessEvent(
                anyString(), eq("RISK_LOGIN"), eq(user.tenantId()), eq(user.id()),
                eq("LOGIN_ACTIVITY"), anyString(),
                eq(Map.of("loginAddr", "Zhejiang/Hangzhou", "riskLevel", "MEDIUM")),
                any(OffsetDateTime.class));
    }

    @Test
    void lowRiskLoginStoresActivityWithoutNotification() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NotificationEventService events = mock(NotificationEventService.class);
        when(jdbc.queryForObject(contains("device_hash"), eq(Boolean.class), any(), any()))
                .thenReturn(true);
        when(jdbc.queryForObject(contains("subnet_hmac"), eq(Boolean.class), any(), any()))
                .thenReturn(true);
        when(jdbc.queryForObject(contains("SELECT region"), eq(String.class), any()))
                .thenReturn("Zhejiang/Hangzhou");
        LoginActivityService service = service(jdbc, events, ip -> "Zhejiang/Hangzhou");

        service.recordSuccess(user(), new LoginActivityService.LoginContext(
                "203.0.113.42", "known-device", "Browser"));

        verify(jdbc).update(contains("INSERT INTO platform.login_activity"), any(Object[].class));
        verify(events, never()).recordAuthoritativeBusinessEvent(
                anyString(), anyString(), any(), any(), anyString(),
                anyString(), any(), any(OffsetDateTime.class));
    }

    @Test
    void riskEventKeyDeduplicatesSameDeviceNetworkAndShanghaiDay() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NotificationEventService events = mock(NotificationEventService.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any())).thenReturn(false);
        when(jdbc.queryForObject(contains("SELECT region"), eq(String.class), any())).thenReturn(null);
        LoginActivityService service = service(jdbc, events, ip -> IpLocationResolver.UNKNOWN);

        service.recordSuccess(user(), new LoginActivityService.LoginContext(
                "203.0.113.42", "device-a", "Browser"));
        service.recordSuccess(user(), new LoginActivityService.LoginContext(
                "203.0.113.99", "device-a", "Browser"));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(events, org.mockito.Mockito.times(2)).recordAuthoritativeBusinessEvent(
                keys.capture(), eq("RISK_LOGIN"), any(), any(), eq("LOGIN_ACTIVITY"),
                anyString(), any(), any(OffsetDateTime.class));
        assertThat(keys.getAllValues()).hasSize(2).allMatch(keys.getAllValues().getFirst()::equals);
    }

    @Test
    void blankPepperIsAllowedOnlyWhenActiveProfilesAreExclusivelyLocalOrTest() {
        for (String[] profiles : new String[][]{{"local"}, {"test"}, {"local", "test"}}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profiles);

            assertThatCode(() -> new LoginActivityService(
                    new LoginActivityRepository(mock(JdbcTemplate.class)),
                    mock(NotificationEventService.class),
                    ip -> IpLocationResolver.UNKNOWN, new LoginRiskEvaluator(), "", environment))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void blankPepperIsRejectedForEmptyOrMixedActiveProfiles() {
        for (String[] profiles : new String[][]{
                {}, {"production"}, {"local", "production"}, {"test", "staging"}}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profiles);

            assertThatThrownBy(() -> new LoginActivityService(
                    new LoginActivityRepository(mock(JdbcTemplate.class)),
                    mock(NotificationEventService.class),
                    ip -> IpLocationResolver.UNKNOWN, new LoginRiskEvaluator(), "", environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("identity_login_risk_pepper_is_required");
        }
    }

    @Test
    void sameDayAuthoritativeReplayKeepsFirstOccurrenceAndCommitsBothActivities() {
        POSTGRES.start();
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE SCHEMA platform");
            jdbc.execute("""
                    CREATE TABLE platform.login_activity(
                        id uuid PRIMARY KEY, tenant_id uuid NOT NULL, user_id uuid NOT NULL,
                        ip_hmac char(64) NOT NULL, subnet_hmac char(64) NOT NULL,
                        device_hash char(64) NOT NULL, user_agent_hash char(64) NOT NULL,
                        region varchar(128), outcome varchar(20) NOT NULL,
                        occurred_at timestamptz NOT NULL)
                    """);
            jdbc.execute("""
                    CREATE TABLE platform.notification_event(
                        id uuid PRIMARY KEY, event_key varchar(160) NOT NULL UNIQUE,
                        category varchar(32) NOT NULL, event_type varchar(64) NOT NULL,
                        tenant_id uuid, user_id uuid, aggregate_type varchar(64) NOT NULL,
                        aggregate_id varchar(160) NOT NULL, payload jsonb NOT NULL,
                        occurred_at timestamptz NOT NULL)
                    """);
            NotificationEventService events =
                    NotificationEventTestFactory.create(jdbc, new ObjectMapper());
            LoginRiskEvaluator evaluator = mock(LoginRiskEvaluator.class);
            when(evaluator.evaluate(anyBoolean(), anyBoolean(), any(), any()))
                    .thenReturn(LoginRiskEvaluator.RiskLevel.MEDIUM);
            Instant first = Instant.parse("2026-08-09T02:15:00Z");
            Instant second = Instant.parse("2026-08-09T09:45:00Z");
            LoginActivityService firstService = service(
                    jdbc, events, ip -> "Zhejiang/Hangzhou", Clock.fixed(first, ZoneId.of("UTC")), evaluator);
            LoginActivityService secondService = service(
                    jdbc, events, ip -> "Zhejiang/Hangzhou", Clock.fixed(second, ZoneId.of("UTC")), evaluator);
            LoginActivityService.LoginContext context = new LoginActivityService.LoginContext(
                    "203.0.113.42", "same-device", "Browser/1");
            TransactionTemplate transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));

            transaction.executeWithoutResult(status -> firstService.recordSuccess(user(), context));
            transaction.executeWithoutResult(status -> secondService.recordSuccess(user(), context));

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM platform.login_activity", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM platform.notification_event", Integer.class)).isOne();
            assertThat(jdbc.queryForObject(
                    "SELECT occurred_at FROM platform.notification_event", OffsetDateTime.class).toInstant())
                    .isEqualTo(first);
            assertThat(jdbc.queryForMap("""
                    SELECT payload->>'loginAddr' AS login_addr,
                           payload->>'riskLevel' AS risk_level
                    FROM platform.notification_event
                    """))
                    .containsEntry("login_addr", "Zhejiang/Hangzhou")
                    .containsEntry("risk_level", "MEDIUM");
        } finally {
            POSTGRES.stop();
        }
    }

    @Test
    void concurrentSameDayPayloadDifferencesCommitBothActivitiesAndKeepFirstEvent() throws Exception {
        POSTGRES.start();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE SCHEMA platform");
            jdbc.execute("""
                    CREATE TABLE platform.login_activity(
                        id uuid PRIMARY KEY, tenant_id uuid NOT NULL, user_id uuid NOT NULL,
                        ip_hmac char(64) NOT NULL, subnet_hmac char(64) NOT NULL,
                        device_hash char(64) NOT NULL, user_agent_hash char(64) NOT NULL,
                        region varchar(128), outcome varchar(20) NOT NULL,
                        occurred_at timestamptz NOT NULL)
                    """);
            jdbc.execute("""
                    CREATE TABLE platform.notification_event(
                        id uuid PRIMARY KEY, event_key varchar(160) NOT NULL UNIQUE,
                        category varchar(32) NOT NULL, event_type varchar(64) NOT NULL,
                        tenant_id uuid, user_id uuid, aggregate_type varchar(64) NOT NULL,
                        aggregate_id varchar(160) NOT NULL, payload jsonb NOT NULL,
                        occurred_at timestamptz NOT NULL)
                    """);
            NotificationEventService events =
                    NotificationEventTestFactory.create(jdbc, new ObjectMapper());
            LoginRiskEvaluator evaluator = mock(LoginRiskEvaluator.class);
            when(evaluator.evaluate(anyBoolean(), anyBoolean(), any(), anyString()))
                    .thenAnswer(invocation -> "Shanghai/Shanghai".equals(invocation.getArgument(3))
                            ? LoginRiskEvaluator.RiskLevel.HIGH
                            : LoginRiskEvaluator.RiskLevel.MEDIUM);
            LoginActivityService firstService = service(
                    jdbc, events, ip -> "Zhejiang/Hangzhou",
                    Clock.fixed(Instant.parse("2026-08-09T02:15:00Z"), ZoneId.of("UTC")), evaluator);
            LoginActivityService secondService = service(
                    jdbc, events, ip -> "Shanghai/Shanghai",
                    Clock.fixed(Instant.parse("2026-08-09T02:15:01Z"), ZoneId.of("UTC")), evaluator);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            TransactionTemplate transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));

            Future<?> first = executor.submit(() -> runConcurrentLogin(
                    transaction, firstService,
                    new LoginActivityService.LoginContext("203.0.113.42", "same-device", "Browser/1"),
                    ready, start));
            Future<?> second = executor.submit(() -> runConcurrentLogin(
                    transaction, secondService,
                    new LoginActivityService.LoginContext("203.0.113.99", "same-device", "Browser/2"),
                    ready, start));
            ready.await();
            start.countDown();
            first.get();
            second.get();

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM platform.login_activity", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM platform.notification_event", Integer.class)).isOne();
            assertThat(jdbc.queryForMap("""
                    SELECT payload->>'loginAddr' AS login_addr,
                           payload->>'riskLevel' AS risk_level
                    FROM platform.notification_event
                    """))
                    .satisfiesAnyOf(
                            payload -> assertThat(payload)
                                    .containsEntry("login_addr", "Zhejiang/Hangzhou")
                                    .containsEntry("risk_level", "MEDIUM"),
                            payload -> assertThat(payload)
                                    .containsEntry("login_addr", "Shanghai/Shanghai")
                                    .containsEntry("risk_level", "HIGH"));
        } finally {
            executor.shutdownNow();
            POSTGRES.stop();
        }
    }

    private void runConcurrentLogin(
            TransactionTemplate transaction,
            LoginActivityService service,
            LoginActivityService.LoginContext context,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        transaction.executeWithoutResult(status -> service.recordSuccess(user(), context));
    }

    private LoginActivityService service(
            JdbcTemplate jdbc, NotificationEventService events, IpLocationResolver locations) {
        return service(jdbc, events, locations, Clock.fixed(NOW, ZoneId.of("UTC")),
                new LoginRiskEvaluator());
    }

    private LoginActivityService service(
            JdbcTemplate jdbc,
            NotificationEventService events,
            IpLocationResolver locations,
            Clock clock,
            LoginRiskEvaluator evaluator) {
        return new LoginActivityService(
                new LoginActivityRepository(jdbc),
                events, locations, evaluator, clock, PEPPER, false);
    }

    private IdentityService.UserProfile user() {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000001");
        return new IdentityService.UserProfile(id, id, "user@example.com", "User", "USER");
    }
}
