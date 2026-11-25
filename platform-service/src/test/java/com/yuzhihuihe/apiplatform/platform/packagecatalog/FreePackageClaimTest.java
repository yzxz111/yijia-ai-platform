package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.grant.GrantService;
import com.yuzhihuihe.apiplatform.platform.identity.IdentityService;
import com.yuzhihuihe.apiplatform.platform.identity.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "outbox.publisher.initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class FreePackageClaimTest {

    private static final UUID USER_ID = UUID.fromString("92000000-0000-4000-8000-000000000001");
    /**
     * 领取路径实际会解析到的版本：FREE 的 PUBLISHED 版本。
     *
     * <p>V61 重新发布了 FREE（额度减半至 50,000）并退役了 v1，因此该 id 从
     * ...101 变为 ...111。这里的每个测试都会改动「该免费版本」然后断言
     * 领取被拒绝或被受理，因此它必须指向服务实际查询的版本 ——
     * 改动已退役的行会让已发布的那一行保持原样，并悄悄颠倒
     * 拒绝类测试的结果。
     */
    private static final UUID FREE_VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000111");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtTokenService jwtTokenService;

    @Autowired
    FreePackageClaimService claimService;

    @BeforeEach
    void resetData() {
        restoreFreeVersion();
        jdbcTemplate.update("TRUNCATE platform.admin_audit_log, platform.outbox_event, "
                + "platform.grant_request, platform.refresh_token, platform.user_role, "
                + "platform.invitation, platform.app_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'free.claim@example.com', 'test-password-hash', 'Free Claim', 'ACTIVE')
                """, USER_ID, USER_ID);
        Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform.app_role WHERE code = 'USER'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                VALUES (?, ?, ?)
                """, USER_ID, USER_ID, roleId);
    }

    @Test
    void firstClaimSucceedsWithoutOrderAndRetryReturnsOriginalGrant() throws Exception {
        String token = accessToken();

        JsonNode first = objectMapper.readTree(mockMvc.perform(post("/console/platform/plans/free/claim")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessNo").value("free:" + USER_ID))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsByteArray());
        JsonNode replay = objectMapper.readTree(mockMvc.perform(post("/console/platform/plans/free/claim")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());

        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(replay.get("eventId").asText()).isEqualTo(first.get("eventId").asText());
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
        assertThat(count("platform.customer_order")).isZero();
        assertThat(count("platform.payment_transaction")).isZero();
    }

    @Test
    void firstClaimSucceedsForPhoneOnlyUserWithoutEmail() throws Exception {
        jdbcTemplate.update("UPDATE platform.app_user SET email = NULL WHERE id = ?", USER_ID);

        mockMvc.perform(post("/console/platform/plans/free/claim")
                        .header("Authorization", "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessNo").value("free:" + USER_ID))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
    }

    @Test
    void claimStatusIsFalseBeforeClaimAndTrueAfterClaim() throws Exception {
        String token = accessToken();

        mockMvc.perform(get("/console/platform/plans/free/claim-status"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/console/platform/plans/free/claim-status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(false));

        claimService.claim(USER_ID);

        mockMvc.perform(get("/console/platform/plans/free/claim-status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(true));
    }

    @Test
    void claimStatusRemainsTrueForHistoricalNonActiveGrant() throws Exception {
        claimService.claim(USER_ID);
        jdbcTemplate.update("""
                UPDATE platform.grant_request
                SET status = 'REVOKED'
                WHERE business_no = ?
                """, "free:" + USER_ID);

        mockMvc.perform(get("/console/platform/plans/free/claim-status")
                        .header("Authorization", "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(true));
    }

    @Test
    void concurrentClaimsCreateOneGrant() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> claimConcurrently(ready, start));
            var second = executor.submit(() -> claimConcurrently(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            GrantService.GrantResult firstResult = first.get(20, TimeUnit.SECONDS);
            GrantService.GrantResult secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(secondResult).isEqualTo(firstResult);
        }
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
    }

    @Test
    void rejectsNonFreePlan() {
        jdbcTemplate.update("UPDATE platform.package_plan SET kind = 'STANDARD' "
                + "WHERE id = (SELECT package_plan_id FROM platform.package_plan_version WHERE id = ?)",
                FREE_VERSION_ID);

        assertUnavailable();
    }

    @Test
    void rejectsArchivedFreePlan() {
        jdbcTemplate.update("UPDATE platform.package_plan SET status = 'ARCHIVED' WHERE code = 'FREE'");

        assertUnavailable();
    }

    @Test
    void rejectsNonzeroPrice() {
        mutateFreeVersion("price_amount = 1");

        assertUnavailable();
    }

    @Test
    void rejectsUnpublishedVersion() {
        mutateFreeVersion("status = 'DRAFT'");

        assertUnavailable();
    }

    @Test
    void rejectsVersionThatIsNotOncePerUser() {
        mutateFreeVersion("once_per_user = false");

        assertUnavailable();
    }

    private GrantService.GrantResult claimConcurrently(CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return claimService.claim(USER_ID);
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> claimService.claim(USER_ID))
                .hasMessageContaining("free_package_unavailable");
        assertThat(count("platform.grant_request")).isZero();
    }

    private String accessToken() {
        return jwtTokenService.createAccessToken(new IdentityService.UserProfile(
                USER_ID, USER_ID, "free.claim@example.com", "Free Claim", "USER"));
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void restoreFreeVersion() {
        jdbcTemplate.execute("ALTER TABLE platform.package_plan_version "
                + "DISABLE TRIGGER trg_package_version_immutable");
        try {
            jdbcTemplate.update("""
                    UPDATE platform.package_plan
                    SET code = 'FREE', kind = 'FREE_CLAIM', status = 'ACTIVE'
                    WHERE id = (SELECT package_plan_id FROM platform.package_plan_version WHERE id = ?)
                    """, FREE_VERSION_ID);
            jdbcTemplate.update("""
                    UPDATE platform.package_plan_version
                    SET price_amount = 0, status = 'PUBLISHED', once_per_user = true
                    WHERE id = ?
                    """, FREE_VERSION_ID);
        } finally {
            jdbcTemplate.execute("ALTER TABLE platform.package_plan_version "
                    + "ENABLE TRIGGER trg_package_version_immutable");
        }
    }

    private void mutateFreeVersion(String assignment) {
        jdbcTemplate.execute("ALTER TABLE platform.package_plan_version "
                + "DISABLE TRIGGER trg_package_version_immutable");
        try {
            jdbcTemplate.update("UPDATE platform.package_plan_version SET " + assignment + " WHERE id = ?",
                    FREE_VERSION_ID);
        } finally {
            jdbcTemplate.execute("ALTER TABLE platform.package_plan_version "
                    + "ENABLE TRIGGER trg_package_version_immutable");
        }
    }
}
