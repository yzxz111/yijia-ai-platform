package com.yuzhihuihe.apiplatform.platform.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 订阅治理的后台接口。
 *
 * <p>这里走真实的 HTTP 层（MockMvc）而不是直接调服务：本类要验证的第一件事就是
 * **谁能访问**，而授权是 Spring Security 的过滤链行为，直接调服务会把它整段跳过。
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "outbox.publisher.initial-delay-ms=3600000",
        "subscription.jobs.enabled=false"
})
@AutoConfigureMockMvc
class AdminSubscriptionControllerTest {

    private static final long PLAN_PRICE = 2_900L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    private UUID tenantId;
    private UUID userId;
    private UUID adminId;
    private UUID planId;
    private UUID versionId;
    private UUID subscriptionId;

    @BeforeEach
    void reset() {
        jdbc.update("TRUNCATE platform.subscription_cycle, platform.subscription, "
                + "platform.notification_event, platform.notification_channel_delivery, "
                + "platform.in_app_notification, platform.notification_preference, "
                + "platform.admin_audit_log, platform.outbox_event, platform.grant_request, "
                + "platform.balance_ledger, platform.customer_order, platform.package_plan "
                + "RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM platform.account_balance");
        jdbc.update("DELETE FROM platform.app_user");
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        planId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        prepareGatewayPackageInstances();
        insertUser(adminId, tenantId, "admin@yuzhi.test");
        insertUser(userId, tenantId, "subscriber@yuzhi.test");
        jdbc.update("""
                INSERT INTO platform.package_plan(id, code, name, status, kind)
                VALUES (?, 'ADMIN_SUB_TEST', '后台治理测试套餐', 'ACTIVE', 'STANDARD')
                """, planId);
        jdbc.update("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, status, price_amount, currency,
                    total_token_quota, duration_days, max_api_keys,
                    requests_per_minute, tokens_per_minute, max_concurrency,
                    target_margin_ratio, published_at)
                VALUES (?, ?, 1, 'PUBLISHED', ?, 'CNY', 2000000, 30, 2, 60, 10000, 2, 0.30, now())
                """, versionId, planId, PLAN_PRICE);
        subscriptionId = insertSubscription("ACTIVE", 0);
    }

    // ------------------------------------------------------------------
    // 授权
    // ------------------------------------------------------------------

    @Test
    void anAdministratorCanListSubscriptions() throws Exception {
        mockMvc.perform(get("/console/platform/admin/subscriptions").with(token(adminId, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].planCode").value("ADMIN_SUB_TEST"))
                .andExpect(jsonPath("$.items[0].userEmail").value("subscriber@yuzhi.test"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
    }

    @Test
    void aRegularUserIsRefused() throws Exception {
        // 后台治理接口只能由管理员访问。整段授权由既有的
        // /console/platform/admin/** 规则提供，本测试锁住它没有被放宽。
        mockMvc.perform(get("/console/platform/admin/subscriptions").with(token(userId, "ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAnonymousCallerIsRefused() throws Exception {
        mockMvc.perform(get("/console/platform/admin/subscriptions"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Test
    void listFiltersByStatusAndPlanCode() throws Exception {
        insertSubscription("EXPIRED", 3);

        mockMvc.perform(get("/console/platform/admin/subscriptions")
                        .param("status", "EXPIRED")
                        .with(token(adminId, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].consecutiveFailures").value(3));

        mockMvc.perform(get("/console/platform/admin/subscriptions")
                        .param("planCode", "NO_SUCH_PLAN")
                        .with(token(adminId, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void listRejectsAnUnknownStatusRatherThanReturningEverything() throws Exception {
        // 未知状态必须报错而不是被静默忽略：静默忽略会让运维以为筛选生效了，
        // 看到的其实是全量数据。
        mockMvc.perform(get("/console/platform/admin/subscriptions")
                        .param("status", "RENEWING")
                        .with(token(adminId, "ROLE_ADMIN")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_subscription_status"));
    }

    @Test
    void detailIncludesTheChargedCycles() throws Exception {
        mockMvc.perform(get("/console/platform/admin/subscriptions/{id}", subscriptionId)
                        .with(token(adminId, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.id").value(subscriptionId.toString()))
                .andExpect(jsonPath("$.cycles").isArray());
    }

    @Test
    void anUnknownSubscriptionIsNotFound() throws Exception {
        mockMvc.perform(get("/console/platform/admin/subscriptions/{id}", UUID.randomUUID())
                        .with(token(adminId, "ROLE_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("subscription_not_found"));
    }

    // ------------------------------------------------------------------
    // 人工终止
    // ------------------------------------------------------------------

    @Test
    void terminateRequiresAReasonAndAuditsTheActor() throws Exception {
        mockMvc.perform(post("/console/platform/admin/subscriptions/{id}/terminate", subscriptionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(token(adminId, "ROLE_ADMIN")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("termination_reason_required"));
        assertThat(auditCount("SUBSCRIPTION_TERMINATED")).isZero();
        assertThat(subscriptionStatus()).isEqualTo("ACTIVE");

        mockMvc.perform(post("/console/platform/admin/subscriptions/{id}/terminate", subscriptionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"用户投诉重复扣款\"}")
                        .with(token(adminId, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.autoRenew").value(false));

        // 终止后停止扣款，且审计里能查到操作者与原因。
        assertThat(subscriptionStatus()).isEqualTo("TERMINATED");
        assertThat(autoRenew()).isFalse();
        assertThat(claimToken()).isNull();
        assertThat(auditCount("SUBSCRIPTION_TERMINATED")).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT actor_user_id, resource_type, outcome, detail->>'reason' AS reason
                FROM platform.admin_audit_log WHERE action = 'SUBSCRIPTION_TERMINATED'
                """)).containsEntry("actor_user_id", adminId)
                .containsEntry("resource_type", "SUBSCRIPTION")
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("reason", "用户投诉重复扣款");
    }

    // ------------------------------------------------------------------
    // 人工重试
    // ------------------------------------------------------------------

    @Test
    void retryRequeuesAFailedCycleAndReopensTheSubscription() throws Exception {
        // 造一个「已用尽尝试」的失败账期与 EXPIRED 订阅。
        UUID cycleId = insertFailedCycle(3);
        jdbc.update("""
                UPDATE platform.subscription SET status = 'EXPIRED', auto_renew = false WHERE id = ?
                """, subscriptionId);

        mockMvc.perform(post("/console/platform/admin/subscriptions/cycles/{id}/retry", cycleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"用户已充值\"}")
                        .with(token(adminId, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retried").value(true))
                .andExpect(jsonPath("$.periodIndex").value(2))
                .andExpect(jsonPath("$.outcome").value("requeued"));

        // 账期重新可领取，且订阅回到调度器的候选状态——
        // 只把账期改回 PENDING 是不够的：候选条件要求订阅处于 ACTIVE/PAST_DUE。
        assertThat(jdbc.queryForMap("""
                SELECT status, failure_reason FROM platform.subscription_cycle WHERE id = ?
                """, cycleId)).containsEntry("status", "PENDING")
                .containsEntry("failure_reason", null);
        assertThat(subscriptionStatus()).isEqualTo("PAST_DUE");
        assertThat(auditCount("SUBSCRIPTION_RETRY_REQUESTED")).isOne();
    }

    @Test
    void retryIsIdempotentSoADoubleClickIsNotAnError() throws Exception {
        UUID cycleId = insertFailedCycle(1);

        mockMvc.perform(post("/console/platform/admin/subscriptions/cycles/{id}/retry", cycleId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(token(adminId, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retried").value(true));

        // 第二次：账期已不是 FAILED，返回「未重试」而不是报错。
        mockMvc.perform(post("/console/platform/admin/subscriptions/cycles/{id}/retry", cycleId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(token(adminId, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retried").value(false))
                .andExpect(jsonPath("$.outcome").value("cycle_not_failed"));
        assertThat(auditCount("SUBSCRIPTION_RETRY_REQUESTED")).isOne();
    }

    @Test
    void retryOfAnUnknownCycleIsNotFound() throws Exception {
        mockMvc.perform(post("/console/platform/admin/subscriptions/cycles/{id}/retry", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(token(adminId, "ROLE_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("subscription_cycle_not_found"));
    }

    // ------------------------------------------------------------------
    // fixtures / helpers
    // ------------------------------------------------------------------

    private org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor token(
                    UUID subject, String role) {
        return jwt().jwt(value -> value
                        .subject(subject.toString())
                        .claim("tenant_id", tenantId.toString())
                        // auth_version 是既有的 AccessTokenVersionFilter 要求的 claim，
                        // 缺了它请求会在鉴权之后被拒。
                        .claim("auth_version", 0L))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private UUID insertSubscription(String status, int consecutiveFailures) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.subscription(
                    id, tenant_id, user_id, package_plan_id, status, auto_renew,
                    cancel_at_period_end, consecutive_failures,
                    current_period_start, current_period_end, next_charge_at)
                VALUES (?, ?, ?, ?, ?, true, false, ?, now(), now() + interval '30 days',
                    now() + interval '29 days')
                """, id, tenantId, userId, planId, status, consecutiveFailures);
        return id;
    }

    private UUID insertFailedCycle(int attemptCount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.subscription_cycle(
                    id, subscription_id, period_index, period_start, period_end,
                    business_key, status, amount, currency, attempt_count,
                    next_attempt_at, failure_reason)
                VALUES (?, ?, 2, now(), now() + interval '30 days', ?, 'FAILED', ?, 'CNY', ?,
                    now() + interval '1 hour', 'insufficient_balance')
                """, id, subscriptionId, "renewal:" + subscriptionId + ":2", PLAN_PRICE, attemptCount);
        return id;
    }

    private void insertUser(UUID id, UUID tenant, String email) {
        jdbc.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'x', 'Subscriber', 'ACTIVE')
                """, id, tenant, email);
    }

    private void prepareGatewayPackageInstances() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS gateway");
        jdbc.execute("DROP TABLE IF EXISTS gateway.package_instance");
        jdbc.execute("""
                CREATE TABLE gateway.package_instance (
                    id uuid PRIMARY KEY,
                    grant_request_id uuid UNIQUE,
                    tenant_id uuid NOT NULL,
                    user_id uuid NOT NULL,
                    package_code varchar(64) NOT NULL,
                    package_name varchar(100) NOT NULL,
                    package_kind varchar(20) NOT NULL DEFAULT 'STANDARD',
                    total_token_quota bigint NOT NULL,
                    reserved_tokens bigint NOT NULL DEFAULT 0,
                    used_tokens bigint NOT NULL DEFAULT 0,
                    audio_seconds_quota bigint,
                    audio_seconds_reserved bigint NOT NULL DEFAULT 0,
                    audio_seconds_used bigint NOT NULL DEFAULT 0,
                    usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN',
                    status varchar(20) NOT NULL,
                    ends_at timestamptz,
                    starts_at timestamptz,
                    expires_at timestamptz,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
    }

    private String subscriptionStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM platform.subscription WHERE id = ?", String.class, subscriptionId);
    }

    private boolean autoRenew() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT auto_renew FROM platform.subscription WHERE id = ?",
                Boolean.class, subscriptionId));
    }

    private UUID claimToken() {
        return jdbc.queryForObject(
                "SELECT claim_token FROM platform.subscription WHERE id = ?",
                UUID.class, subscriptionId);
    }

    private int auditCount(String action) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM platform.admin_audit_log WHERE action = ?",
                Integer.class, action);
        return count == null ? 0 : count;
    }

    private static Instant daysFromNow(int days) {
        return Instant.now().plus(Duration.ofDays(days));
    }
}
