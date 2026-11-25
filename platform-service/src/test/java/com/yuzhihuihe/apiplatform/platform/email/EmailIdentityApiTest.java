package com.yuzhihuihe.apiplatform.platform.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.identity.IdentityService;
import com.yuzhihuihe.apiplatform.platform.identity.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "email.enabled=true",
        "email.cooldown=0s",
        "identity.login-risk.pepper=test-login-risk-pepper-with-at-least-32-bytes",
        "email.security.code-pepper=MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjI="
})
@AutoConfigureMockMvc
class EmailIdentityApiTest {

    private static final String DEVICE_ID = "0123456789abcdef0123456789abcdef";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenService jwtTokens;
    @Autowired RecordingEmailSender sender;
    @Autowired PasswordEncoder passwords;

    @BeforeEach
    void reset() {
        sender.codes.clear();
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
        // Account creation seeds the gateway-side safety cache; mirror gateway V18 so the
        // email-identity account-creating path can complete in this context.
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS gateway");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS gateway.safety_enforcement_cache (
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
        jdbc.update("TRUNCATE platform.email_verification_challenge, platform.refresh_token, "
                + "platform.account_balance, platform.user_role, platform.app_user, platform.app_role "
                + "RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO platform.app_role(code, name) VALUES ('USER', 'User'), ('ADMIN', 'Admin')");
    }

    @Test
    void verifiedNewEmailWaitsForPasswordAndThenReusesTheAccount() throws Exception {
        UUID firstChallenge = issue("new-user@example.com");
        MvcResult pending = mockMvc.perform(post("/console/platform/auth/email-identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("new-user@example.com", firstChallenge)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("email_registration_pending"))
                .andExpect(jsonPath("$.challengeId").value(firstChallenge.toString()))
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();
        assertThat(pending.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE email = 'new-user@example.com'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.user_consent", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.refresh_token", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.oauth_authorization_codes", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT verified_at IS NOT NULL AND consumed_at IS NULL "
                        + "FROM platform.email_verification_challenge WHERE id = ?",
                Boolean.class, firstChallenge)).isTrue();

        MvcResult created = mockMvc.perform(post(
                        "/console/platform/auth/email-identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionJson(firstChallenge, "StrongPass123!", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("new-user@example.com"))
                .andExpect(jsonPath("$.user.passwordSet").value(true))
                .andReturn();
        UUID userId = UUID.fromString(json(created).at("/user/id").asText());
        Map<String, Object> account = jdbc.queryForMap("""
                SELECT password_hash, display_name FROM platform.app_user WHERE id = ?
                """, userId);
        assertThat(account.get("display_name")).isEqualTo("邮箱用户");
        assertThat(passwords.matches("StrongPass123!", (String) account.get("password_hash")))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.user_consent WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(2);

        mockMvc.perform(post("/console/platform/auth/email-identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionJson(firstChallenge, "StrongPass123!", true)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_registration_challenge"));

        UUID secondChallenge = issue("NEW-USER@example.com");
        mockMvc.perform(post("/console/platform/auth/email-identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("NEW-USER@example.com", secondChallenge)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId.toString()));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE email = 'new-user@example.com'",
                Integer.class)).isOne();

        mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"new-user@example.com\","
                                + "\"password\":\"StrongPass123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId.toString()));
    }

    @Test
    void invalidPasswordAndMissingConsentLeaveVerifiedEmailAvailableForRetry() throws Exception {
        UUID challenge = issue("retry@example.com");
        mockMvc.perform(post("/console/platform/auth/email-identity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("retry@example.com", challenge, false)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("terms_acceptance_required"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE email = 'retry@example.com'",
                Integer.class)).isZero();

        mockMvc.perform(post("/console/platform/auth/email-identity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("retry@example.com", challenge)))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/console/platform/auth/email-identity/complete-registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionJson(challenge, "weak", true)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/console/platform/auth/email-identity/complete-registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionJson(challenge, "StrongPass123!", false)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("terms_acceptance_required"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE email = 'retry@example.com'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT consumed_at IS NULL FROM platform.email_verification_challenge "
                        + "WHERE id = ?", Boolean.class, challenge)).isTrue();

        mockMvc.perform(post("/console/platform/auth/email-identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionJson(challenge, "StrongPass123!", true)))
                .andExpect(status().isCreated());
    }

    @Test
    void existingPasswordlessEmailStillLogsInAndCanSetItsFirstPassword() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'legacy@example.com', NULL, 'Legacy User', 'ACTIVE')
                """, userId, userId);
        jdbc.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                SELECT ?, ?, id FROM platform.app_role WHERE code = 'USER'
                """, userId, userId);

        UUID challenge = issue("legacy@example.com");
        MvcResult loggedIn = mockMvc.perform(post("/console/platform/auth/email-identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("legacy@example.com", challenge, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId.toString()))
                .andExpect(jsonPath("$.user.passwordSet").value(false))
                .andReturn();

        mockMvc.perform(post("/console/platform/profile/password")
                        .header("Authorization", "Bearer " + json(loggedIn).at("/accessToken").asText())
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"StrongPass123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.passwordSet").value(true));
        mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"legacy@example.com\","
                                + "\"password\":\"StrongPass123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId.toString()));
    }

    @Test
    void legacyPasswordlessAccountCanUseAnInvitationOnlyDuringFirstPasswordSetup()
            throws Exception {
        UUID inviter = insertReferralUser("legacy-inviter@example.com", "Inviter");
        MvcResult issued = mockMvc.perform(post("/console/platform/referrals/codes")
                        .header("Authorization", referralBearer(
                                inviter, "legacy-inviter@example.com", "Inviter")))
                .andExpect(status().isOk())
                .andReturn();
        String invitationCode = json(issued).path("activeCode").asText();

        UUID invitee = UUID.randomUUID();
        String email = "legacy-invitee@example.com";
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, NULL, 'Legacy Invitee', 'ACTIVE')
                """, invitee, invitee, email);
        jdbc.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                SELECT ?, ?, id FROM platform.app_role WHERE code = 'USER'
                """, invitee, invitee);
        UUID challenge = issue(email);
        MvcResult loggedIn = mockMvc.perform(post("/console/platform/auth/email-identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson(email, challenge, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.passwordSet").value(false))
                .andReturn();
        String firstToken = json(loggedIn).at("/accessToken").asText();

        mockMvc.perform(post("/console/platform/profile/password")
                        .header("Authorization", "Bearer " + firstToken)
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"StrongPass123!","invitationCode":"invalid"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_invitation_code"));
        assertThat(jdbc.queryForObject(
                "SELECT password_hash FROM platform.app_user WHERE id = ?", String.class,
                invitee)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.referral_attribution WHERE invitee_user_id = ?",
                Integer.class, invitee)).isZero();

        MvcResult setup = mockMvc.perform(post("/console/platform/profile/password")
                        .header("Authorization", "Bearer " + firstToken)
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"StrongPass123!","invitationCode":"%s"}
                                """.formatted(invitationCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.passwordSet").value(true))
                .andReturn();
        String secondToken = json(setup).at("/accessToken").asText();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.referral_attribution
                WHERE invitee_user_id = ? AND inviter_user_id = ?
                """, Integer.class, invitee, inviter)).isOne();

        mockMvc.perform(post("/console/platform/profile/password")
                        .header("Authorization", "Bearer " + secondToken)
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"StrongPass123!",
                                 "newPassword":"ChangedPass456!",
                                 "invitationCode":"%s"}
                                """.formatted(invitationCode)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invitation_code_not_allowed"));
        String savedHash = jdbc.queryForObject(
                "SELECT password_hash FROM platform.app_user WHERE id = ?", String.class,
                invitee);
        assertThat(passwords.matches("StrongPass123!", savedHash)).isTrue();
        assertThat(passwords.matches("ChangedPass456!", savedHash)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.referral_attribution WHERE invitee_user_id = ?",
                Integer.class, invitee)).isOne();

        // Even if a legacy account is repaired back to passwordless, its existing
        // attribution must not be overwritten by a second first-password request.
        jdbc.update("""
                UPDATE platform.app_user
                SET password_hash = NULL, auth_version = auth_version + 1
                WHERE id = ?
                """, invitee);
        UUID repeatChallenge = issue(email);
        MvcResult repeatedLogin = mockMvc.perform(post("/console/platform/auth/email-identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson(email, repeatChallenge, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.passwordSet").value(false))
                .andReturn();
        mockMvc.perform(post("/console/platform/profile/password")
                        .header("Authorization", "Bearer "
                                + json(repeatedLogin).at("/accessToken").asText())
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"ChangedPass456!","invitationCode":"%s"}
                                """.formatted(invitationCode)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invitation_code_not_allowed"));
        assertThat(jdbc.queryForObject(
                "SELECT password_hash FROM platform.app_user WHERE id = ?", String.class,
                invitee)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.referral_attribution WHERE invitee_user_id = ?",
                Integer.class, invitee)).isOne();
    }

    @Test
    void concurrentCompletionClaimsTheVerifiedEmailOnlyOnce() throws Exception {
        UUID challenge = issue("concurrent@example.com");
        mockMvc.perform(post("/console/platform/auth/email-identity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("concurrent@example.com", challenge)))
                .andExpect(status().isAccepted());

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> complete = () -> {
                start.await();
                return mockMvc.perform(post("/console/platform/auth/email-identity/complete-registration")
                                .header("X-Device-Id", DEVICE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(completionJson(challenge, "StrongPass123!", true)))
                        .andReturn().getResponse().getStatus();
            };
            Future<Integer> first = executor.submit(complete);
            Future<Integer> second = executor.submit(complete);
            start.countDown();
            assertThat(java.util.List.of(
                    first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 422);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE email = 'concurrent@example.com'",
                Integer.class)).isOne();
    }

    @Test
    void expiredOrClaimedPendingEmailCannotCreateAnotherAccount() throws Exception {
        UUID expired = issue("expired@example.com");
        mockMvc.perform(post("/console/platform/auth/email-identity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("expired@example.com", expired)))
                .andExpect(status().isAccepted());
        jdbc.update("""
                UPDATE platform.email_verification_challenge
                SET created_at = now() - interval '12 minutes',
                    verified_at = now() - interval '11 minutes',
                    expires_at = now() - interval '1 second'
                WHERE id = ?
                """, expired);
        mockMvc.perform(post("/console/platform/auth/email-identity/complete-registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionJson(expired, "StrongPass123!", true)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_registration_challenge"));

        UUID claimed = issue("claimed@example.com");
        mockMvc.perform(post("/console/platform/auth/email-identity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("claimed@example.com", claimed)))
                .andExpect(status().isAccepted());
        UUID otherUser = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'claimed@example.com', ?, 'Other User', 'ACTIVE')
                """, otherUser, otherUser, passwords.encode("AnotherPass123!"));
        mockMvc.perform(post("/console/platform/auth/email-identity/complete-registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionJson(claimed, "StrongPass123!", true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("email_unavailable"));
        assertThat(jdbc.queryForObject(
                "SELECT consumed_at IS NULL FROM platform.email_verification_challenge "
                        + "WHERE id = ?", Boolean.class, claimed)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE email = 'claimed@example.com'",
                Integer.class)).isOne();
    }

    @Test
    void authenticatedPhoneAccountCanBindAVerifiedEmail() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, NULL, NULL, 'Phone User', 'ACTIVE')
                """, userId, userId);
        jdbc.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                SELECT ?, ?, id FROM platform.app_role WHERE code = 'USER'
                """, userId, userId);
        String token = jwtTokens.createAccessToken(new IdentityService.UserProfile(
                userId, userId, null, "Phone User", "USER"));

        UUID challengeId = issue("bound@example.com");
        mockMvc.perform(put("/console/platform/profile/email")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("bound@example.com", challengeId)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "SELECT email FROM platform.app_user WHERE id = ?", String.class, userId))
                .isEqualTo("bound@example.com");
    }

    @Test
    void storesRequestIpFingerprintIndependentlyOfTheEmail() throws Exception {
        mockMvc.perform(post("/console/platform/auth/email/challenges")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"first@example.com\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/console/platform/auth/email/challenges")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"second@example.com\"}"))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT request_ip_hash)
                FROM platform.email_verification_challenge
                """, Integer.class)).isOne();
    }

    @Test
    void verifiedEmailCanResetAnExistingAccountPassword() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'recover@example.com', ?, 'Recover User', 'ACTIVE')
                """, userId, userId, passwords.encode("OriginalPass123!"));
        UUID challengeId = issue("recover@example.com");

        mockMvc.perform(post("/console/platform/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"recover@example.com","challengeId":"%s",
                                 "verificationCode":"%s","newPassword":"RecoveredPass456!"}
                                """.formatted(challengeId, sender.codes.get("recover@example.com"))))
                .andExpect(status().isNoContent());

        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM platform.app_user WHERE id = ?", String.class, userId);
        assertThat(passwords.matches("RecoveredPass456!", hash)).isTrue();
    }

    @Test
    void referralCodesTrackOnlyCompletedEmailRegistrationsAcrossRotation()
            throws Exception {
        UUID inviter = insertReferralUser("inviter@example.com", "Inviter");
        String authorization = referralBearer(inviter, "inviter@example.com", "Inviter");
        mockMvc.perform(get("/console/platform/referrals/me")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.hasIssuedCode").doesNotExist());

        MvcResult firstIssue = mockMvc.perform(post("/console/platform/referrals/codes")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.hasIssuedCode").doesNotExist())
                .andReturn();
        String firstCode = json(firstIssue).path("activeCode").asText();
        assertThat(firstCode).matches("[A-Za-z0-9]{32}");
        mockMvc.perform(post("/console/platform/referrals/codes")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCode").value(firstCode));
        assertThat(jdbc.queryForObject("""
                SELECT code FROM platform.referral_code WHERE inviter_user_id = ?
                """, String.class, inviter)).isEqualTo(firstCode);

        UUID firstChallenge = issue("first-invitee@example.com");
        mockMvc.perform(post("/console/platform/auth/email-identity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("first-invitee@example.com", firstChallenge)))
                .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.app_user
                WHERE email = 'first-invitee@example.com'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.referral_attribution", Integer.class)).isZero();

        mockMvc.perform(post("/console/platform/auth/email-identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionWithCode(firstChallenge, "Unknown0123456789Abcdef012345678", true)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_invitation_code"));
        assertThat(jdbc.queryForObject("""
                SELECT consumed_at IS NULL FROM platform.email_verification_challenge WHERE id = ?
                """, Boolean.class, firstChallenge)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.app_user WHERE email = 'first-invitee@example.com'
                """, Integer.class)).isZero();

        mockMvc.perform(post("/console/platform/auth/email-identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionWithCode(firstChallenge, firstCode, true)))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.app_user
                WHERE email = 'first-invitee@example.com'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT a.registered_at = u.created_at
                FROM platform.referral_attribution a
                JOIN platform.app_user u ON u.id = a.invitee_user_id
                WHERE u.email = 'first-invitee@example.com'
                """, Boolean.class)).isTrue();

        MvcResult rotated = mockMvc.perform(post("/console/platform/referrals/codes/rotate")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andReturn();
        String secondCode = json(rotated).path("activeCode").asText();
        assertThat(secondCode).matches("[A-Za-z0-9]{32}").isNotEqualTo(firstCode);

        UUID secondChallenge = issue("second-invitee@example.com");
        mockMvc.perform(post("/console/platform/auth/email-identity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("second-invitee@example.com", secondChallenge)))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/console/platform/auth/email-identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionWithCode(secondChallenge, firstCode, true)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invitation_code_revoked"));
        assertThat(jdbc.queryForObject("""
                SELECT consumed_at IS NULL FROM platform.email_verification_challenge WHERE id = ?
                """, Boolean.class, secondChallenge)).isTrue();
        mockMvc.perform(post("/console/platform/auth/email-identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionWithCode(secondChallenge, secondCode, true)))
                .andExpect(status().isCreated());

        MvcResult firstPage = mockMvc.perform(get("/console/platform/referrals/me/invitees")
                        .header("Authorization", authorization)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("邮箱用户"))
                .andExpect(jsonPath("$.items[0].registeredAt").isString())
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        String cursor = json(firstPage).path("nextCursor").asText();
        mockMvc.perform(get("/console/platform/referrals/me/invitees")
                        .header("Authorization", authorization)
                        .param("limit", "1")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));

        UUID other = insertReferralUser("other@example.com", "Other");
        String otherBearer = referralBearer(other, "other@example.com", "Other");
        mockMvc.perform(get("/console/platform/referrals/me")
                        .header("Authorization", otherBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(0));
        mockMvc.perform(get("/console/platform/referrals/me/invitees")
                        .header("Authorization", otherBearer)
                        .param("cursor", cursor))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_referral_cursor"));

        mockMvc.perform(delete("/console/platform/referrals/codes/current")
                        .header("Authorization", authorization))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/console/platform/referrals/me")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCode").value(secondCode))
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.hasIssuedCode").doesNotExist());
    }

    @Test
    void concurrentCodeIssueReturnsOneActiveCode() throws Exception {
        UUID inviter = insertReferralUser("concurrent-inviter@example.com", "Inviter");
        String authorization = referralBearer(inviter, "concurrent-inviter@example.com", "Inviter");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> issueCode = () -> {
                start.await();
                MvcResult issued = mockMvc.perform(post("/console/platform/referrals/codes")
                                .header("Authorization", authorization))
                        .andExpect(status().isOk()).andReturn();
                return json(issued).path("activeCode").asText();
            };
            Future<String> first = executor.submit(issueCode);
            Future<String> second = executor.submit(issueCode);
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS))
                    .isEqualTo(second.get(30, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.referral_code
                WHERE inviter_user_id = ? AND revoked_at IS NULL
                """, Integer.class, inviter)).isOne();
    }

    @Test
    void administratorCanReadReferralOverview() throws Exception {
        UUID admin = insertReferralUser("admin-inviter@example.com", "Admin");
        jdbc.update("DELETE FROM platform.user_role WHERE user_id = ?", admin);
        jdbc.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                SELECT ?, ?, id FROM platform.app_role WHERE code = 'ADMIN'
                """, admin, admin);
        String token = jwtTokens.createAccessToken(new IdentityService.UserProfile(
                admin, admin, "admin-inviter@example.com", "Admin", "ADMIN"));
        mockMvc.perform(get("/console/platform/referrals/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.hasIssuedCode").doesNotExist());
    }

    private UUID insertReferralUser(String email, String displayName) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """, id, id, email, passwords.encode("StrongPass123!"), displayName);
        jdbc.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                SELECT ?, ?, id FROM platform.app_role WHERE code = 'USER'
                """, id, id);
        return id;
    }

    private String referralBearer(UUID userId, String email, String displayName) {
        return "Bearer " + jwtTokens.createAccessToken(new IdentityService.UserProfile(
                userId, userId, email, displayName, "USER"));
    }

    private String completionWithCode(UUID challengeId, String code, boolean termsAccepted) {
        return """
                {"challengeId":"%s","newPassword":"StrongPass123!",
                 "termsAccepted":%s,"invitationCode":"%s"}
                """.formatted(challengeId, termsAccepted, code);
    }

    private UUID issue(String email) throws Exception {
        MvcResult issued = mockMvc.perform(post("/console/platform/auth/email/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.maskedEmail").isString())
                .andReturn();
        return UUID.fromString(json(issued).at("/id").asText());
    }

    private String identityJson(String email, UUID challengeId) {
        return identityJson(email, challengeId, true);
    }

    private String identityJson(String email, UUID challengeId, boolean termsAccepted) {
        return """
                {"email":"%s","challengeId":"%s","verificationCode":"%s","termsAccepted":%s}
                """.formatted(
                email, challengeId, sender.codes.get(email.toLowerCase()), termsAccepted);
    }

    private String completionJson(UUID challengeId, String password, boolean termsAccepted) {
        return """
                {"challengeId":"%s","newPassword":"%s","termsAccepted":%s}
                """.formatted(challengeId, password, termsAccepted);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    @TestConfiguration
    static class SenderConfiguration {
        @Bean
        @Primary
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }

    static final class RecordingEmailSender implements EmailSender {
        private final Map<String, String> codes = new ConcurrentHashMap<>();

        @Override
        public void sendVerificationCode(String email, String code) {
            codes.put(email, code);
        }
    }
}
