package com.yuzhihuihe.apiplatform.platform.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.security.SecretEncryptionService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsChallengeService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
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
        "sms.enabled=true",
        "sms.security.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "sms.security.phone-lookup-key=MTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTE=",
        "sms.security.code-pepper=MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjI=",
        "sms.security.request-ip-key=MzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzM=",
        "identity.login-risk.pepper=test-login-risk-pepper-with-at-least-32-bytes",
        "identity.password-login-rate-limit.hmac-key=test-password-login-hmac-key-with-32-bytes",
        "sms.aliyun.endpoint=dysmsapi.aliyuncs.com",
        "sms.aliyun.access-key-id=test-only",
        "sms.aliyun.access-key-secret=test-only",
        "sms.aliyun.sign-name=test-only",
        "sms.aliyun.region-id=cn-hangzhou",
        "sms.jobs.delivery-delay-ms=3600000"
})
@AutoConfigureMockMvc
class IdentityApiTest {

    private static final String INVITATION_CODE = "invite-test-code";

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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtTokenService jwtTokenService;

    @Autowired
    TerminalSessionRepository terminalSessionRepository;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    RSAKey jwtRsaKey;

    @Autowired
    SmsChallengeService smsChallenges;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    @Qualifier("smsSecretEncryptionService")
    SecretEncryptionService smsEncryption;

    int phoneSequence;

    @BeforeEach
    void resetIdentityData() {
        jdbcTemplate.update("TRUNCATE platform.refresh_token, platform.user_role, platform.invitation, "
                + "platform.app_user, platform.app_role RESTART IDENTITY CASCADE");
        jdbcTemplate.update("INSERT INTO platform.app_role(code, name) VALUES ('USER', 'User'), ('ADMIN', 'Admin')");
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
        phoneSequence = 0;
        // New accounts seed the gateway-side safety cache, so the account-creation paths need
        // that table to exist alongside the platform migrations (mirrors gateway V18).
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS gateway");
        jdbcTemplate.execute("""
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
        jdbcTemplate.update("""
                INSERT INTO platform.refund_policy_version(
                    version, fee_bps, minimum_fee, refund_window_days,
                    maximum_usage_bps, effective_at, published)
                VALUES (1, 500, 100, 7, 1000, now(), true)
                """);
    }

    @Test
    void registrationAndReferralEndpointsWorkWithoutReferralKeys() throws Exception {
        MvcResult created = register("user@example.com", "StrongPass123!")
                .andExpect(status().isCreated()).andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/console/platform/referrals/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.hasIssuedCode").doesNotExist());
        mockMvc.perform(get("/console/platform/referrals/me/invitees")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        MvcResult issued = mockMvc.perform(post("/console/platform/referrals/codes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasIssuedCode").doesNotExist())
                .andReturn();
        String code = com.jayway.jsonpath.JsonPath.read(
                issued.getResponse().getContentAsString(), "$.activeCode");
        assertThat(code).matches("[A-Za-z0-9]{32}");
        mockMvc.perform(get("/console/platform/referrals/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCode").value(code));
    }

    @Test
    void emailVerificationFailsClosedUntilDeliveryCredentialsAreConfigured() throws Exception {
        mockMvc.perform(post("/console/platform/auth/email/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"future@example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("email_disabled"));
    }

    @Test
    void registrationSucceedsWithoutInvitationAndLeavesHistoricalInvitationUntouched() throws Exception {
        insertInvitation(INVITATION_CODE);

        register("open@example.com", "StrongPass123!")
                .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM platform.invitation WHERE code_hash = ?",
                String.class, sha256(INVITATION_CODE))).isEqualTo("AVAILABLE");
    }

    @Test
    void registrationRejectsPasswordBeyondBcryptByteLimit() throws Exception {
        register("user@example.com", "Aa1!" + "x".repeat(69))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void registrationReturnsSessionWithoutConsumingInvitation() throws Exception {
        insertInvitation(INVITATION_CODE);

        MvcResult result = register("user@example.com", "StrongPass123!")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.user.email").value("user@example.com"))
                .andExpect(cookie().httpOnly("yzh_refresh", true))
                .andExpect(cookie().httpOnly("yzh_csrf", false))
                .andExpect(cookie().maxAge("yzh_refresh", -1))
                .andExpect(cookie().maxAge("yzh_csrf", -1))
                .andReturn();

        String rawRefreshToken = result.getResponse().getCookie("yzh_refresh").getValue();
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM platform.refresh_token", String.class);
        assertThat(storedHash).isEqualTo(sha256(rawRefreshToken)).isNotEqualTo(rawRefreshToken);
        String rawCsrfToken = result.getResponse().getCookie("yzh_csrf").getValue();
        String storedCsrfHash = jdbcTemplate.queryForObject(
                "SELECT csrf_token_hash FROM platform.refresh_token", String.class);
        assertThat(storedCsrfHash).isEqualTo(sha256(rawCsrfToken)).isNotEqualTo(rawCsrfToken);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT account.currency, account.available_amount
                FROM platform.account_balance AS account
                JOIN platform.app_user AS app_user ON app_user.id = account.user_id
                WHERE account.tenant_id = app_user.tenant_id
                  AND app_user.email = 'user@example.com'
                """))
                .containsEntry("currency", "CNY")
                .containsEntry("available_amount", 0L);
        assertThat(jdbcTemplate.queryForList("""
                SELECT recharge_option.amount
                FROM platform.recharge_option
                JOIN platform.app_user ON app_user.tenant_id = recharge_option.tenant_id
                WHERE app_user.email = 'user@example.com'
                ORDER BY recharge_option.amount
                """, Long.class)).containsExactly(
                        1000L, 3000L, 5000L, 10000L, 20000L, 30000L, 50000L);

        register("second@example.com", "StrongPass123!")
                .andExpect(status().isCreated());
    }

    @Test
    void registrationRollsBackUserWalletAndOptionsWhenSessionCreationFails() throws Exception {
        jdbcTemplate.execute("""
                CREATE FUNCTION platform.fail_registration_session()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'forced registration session failure';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_fail_registration_session
                BEFORE INSERT ON platform.refresh_token
                FOR EACH ROW EXECUTE FUNCTION platform.fail_registration_session()
                """);
        try {
            register("rollback@example.com", "StrongPass123!")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("identity_session_unavailable"));
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM platform.app_user
                    WHERE email = 'rollback@example.com'
                    """, Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM platform.account_balance", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM platform.recharge_option", Integer.class)).isZero();
        } finally {
            jdbcTemplate.execute(
                    "DROP TRIGGER IF EXISTS trg_fail_registration_session ON platform.refresh_token");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS platform.fail_registration_session()");
        }
    }

    @Test
    void duplicateEmailRaceMapsTheEmailConstraintAndRollsBackSmsConsumption() throws Exception {
        jdbcTemplate.execute("""
                CREATE FUNCTION platform.inject_duplicate_email_race()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.email = 'race@example.com' AND pg_trigger_depth() = 1 THEN
                        INSERT INTO platform.app_user(
                            id, tenant_id, email, password_hash, display_name, status)
                        VALUES ('10000000-0000-0000-0000-000000000001',
                                '10000000-0000-0000-0000-000000000001',
                                NEW.email, 'race', 'Race Winner', 'ACTIVE');
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_inject_duplicate_email_race
                BEFORE INSERT ON platform.app_user
                FOR EACH ROW EXECUTE FUNCTION platform.inject_duplicate_email_race()
                """);
        try {
            MvcResult result = register("race@example.com", "StrongPass123!")
                    .andExpect(status().isConflict())
                    .andReturn();
            assertThat(result.getResolvedException())
                    .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                            assertThat(error.getReason()).isEqualTo("email is already registered"));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM platform.app_user WHERE email = 'race@example.com'",
                    Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM platform.sms_verification_challenge
                    WHERE consumed_at IS NOT NULL
                    """, Integer.class)).isZero();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_inject_duplicate_email_race ON platform.app_user");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS platform.inject_duplicate_email_race()");
        }
    }

    @Test
    void adminEndpointRejectsSignedUserBearerToken() throws Exception {
        MvcResult registered = register("user@example.com", "StrongPass123!")
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = com.jayway.jsonpath.JsonPath.read(
                registered.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/console/platform/admin/users")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginMeRefreshAndLogoutRotateTheSession() throws Exception {
        register("user@example.com", "StrongPass123!")
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", "0123456789abcdef0123456789abcdef")
                        .header("User-Agent", "Test Browser/1.0")
                        .header("X-Forwarded-For", "203.0.113.42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","password":"StrongPass123!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andReturn();

        assertThat(jdbcTemplate.queryForMap("""
                SELECT ip_hmac, subnet_hmac, device_hash, user_agent_hash, outcome
                FROM platform.login_activity
                """))
                .allSatisfy((key, value) -> {
                    if (!key.equals("outcome")) assertThat(value.toString()).matches("[0-9a-f]{64}");
                })
                .containsEntry("outcome", "SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.notification_event WHERE event_type='RISK_LOGIN'",
                Integer.class)).isOne();

        String accessToken = com.jayway.jsonpath.JsonPath.read(
                login.getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/console/platform/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));

        MockCookie refreshCookie = new MockCookie(
                "yzh_refresh", login.getResponse().getCookie("yzh_refresh").getValue());
        MockCookie csrfCookie = new MockCookie(
                "yzh_csrf", login.getResponse().getCookie("yzh_csrf").getValue());
        mockMvc.perform(post("/console/platform/auth/refresh").cookie(refreshCookie, csrfCookie))
                .andExpect(status().isForbidden());

        MvcResult refreshed = mockMvc.perform(post("/console/platform/auth/refresh")
                        .cookie(refreshCookie, csrfCookie)
                        .header("X-Device-Id", "0123456789abcdef0123456789abcdef")
                        .header("X-CSRF-Token", csrfCookie.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.login_activity", Integer.class)).isOne();
        assertThat(refreshed.getResponse().getCookie("yzh_refresh").getValue())
                .isNotEqualTo(refreshCookie.getValue());

        MockCookie rotatedRefresh = new MockCookie(
                "yzh_refresh", refreshed.getResponse().getCookie("yzh_refresh").getValue());
        MockCookie rotatedCsrf = new MockCookie(
                "yzh_csrf", refreshed.getResponse().getCookie("yzh_csrf").getValue());
        mockMvc.perform(post("/console/platform/auth/logout")
                        .cookie(rotatedRefresh, rotatedCsrf)
                        .header("X-CSRF-Token", rotatedCsrf.getValue()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("yzh_refresh", 0));

        Integer activeTokens = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.refresh_token WHERE revoked_at IS NULL", Integer.class);
        Integer revokedCurrentToken = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.refresh_token
                WHERE token_hash = ? AND revoked_at IS NOT NULL
                """, Integer.class, sha256(rotatedRefresh.getValue()));
        assertThat(activeTokens).isZero();
        assertThat(revokedCurrentToken).isEqualTo(1);
    }

    @Test
    void refreshAndLogoutRejectCsrfValuesThatAreNotBoundToTheRefreshSession() throws Exception {
        MvcResult first = register("first@example.com", "StrongPass123!")
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = register("second@example.com", "StrongPass123!")
                .andExpect(status().isCreated())
                .andReturn();
        MockCookie firstRefresh = new MockCookie(
                "yzh_refresh", first.getResponse().getCookie("yzh_refresh").getValue());
        String firstCsrf = first.getResponse().getCookie("yzh_csrf").getValue();
        String secondCsrf = second.getResponse().getCookie("yzh_csrf").getValue();
        String attackerChosenCsrf = "attacker-chosen-csrf";

        mockMvc.perform(post("/console/platform/auth/refresh")
                        .cookie(firstRefresh, new MockCookie("yzh_csrf", attackerChosenCsrf))
                        .header("X-CSRF-Token", attackerChosenCsrf)
                        .header("X-Device-Id", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("invalid_csrf_token"));
        mockMvc.perform(post("/console/platform/auth/refresh")
                        .cookie(firstRefresh, new MockCookie("yzh_csrf", secondCsrf))
                        .header("X-CSRF-Token", secondCsrf)
                        .header("X-Device-Id", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("invalid_csrf_token"));
        mockMvc.perform(post("/console/platform/auth/logout")
                        .cookie(firstRefresh, new MockCookie("yzh_csrf", attackerChosenCsrf))
                        .header("X-CSRF-Token", attackerChosenCsrf))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("invalid_csrf_token"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.refresh_token
                WHERE token_hash = ? AND revoked_at IS NULL
                """, Integer.class, sha256(firstRefresh.getValue()))).isOne();
        mockMvc.perform(post("/console/platform/auth/refresh")
                        .cookie(firstRefresh, new MockCookie("yzh_csrf", firstCsrf))
                        .header("X-CSRF-Token", firstCsrf)
                        .header("X-Device-Id", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRemainsIdempotentForAnUnknownRefreshToken() throws Exception {
        String csrf = "unknown-session-csrf";

        mockMvc.perform(post("/console/platform/auth/logout")
                        .cookie(new MockCookie("yzh_refresh", "unknown-refresh-token"),
                                new MockCookie("yzh_csrf", csrf))
                        .header("X-CSRF-Token", csrf))
                .andExpect(status().isNoContent());
    }

    @Test
    void terminalDeviceValidationMatchesTheActiveSessionsStoredDeviceHash() throws Exception {
        MvcResult registered = register("device-validate@example.com", "StrongPass123!")
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = com.jayway.jsonpath.JsonPath.read(
                registered.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/console/platform/auth/terminal-device/validate")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Device-Id", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/console/platform/auth/terminal-device/validate")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Device-Id", "fedcba9876543210fedcba9876543210"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"));

        mockMvc.perform(get("/console/platform/auth/terminal-device/validate")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Device-Id", "malformed-device"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    @Test
    void failedPasswordLoginDoesNotRecordSuccessfulActivity() throws Exception {
        register("failed-login@example.com", "StrongPass123!").andExpect(status().isCreated());

        mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", "failed-device")
                        .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {"email":"failed-login@example.com","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.login_activity", Integer.class)).isZero();
    }

    @Test
    void passwordLoginReturnsTheSameCodeForEveryCredentialMismatch() throws Exception {
        register("mismatch@example.com", "StrongPass123!").andExpect(status().isCreated());

        for (String payload : java.util.List.of(
                "{}",
                "{\"email\":\"unknown@example.com\",\"password\":\"StrongPass123!\"}",
                "{\"email\":\"mismatch@example.com\"}",
                "{\"email\":\"mismatch@example.com\",\"password\":\"wrong\"}")) {
            mockMvc.perform(post("/console/platform/auth/login")
                            .with(request -> {
                                request.setRemoteAddr("203.0.113.82");
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("invalid_credentials"));
        }
    }

    @Test
    void fifthPasswordFailureIsUnauthorizedAndSixthAttemptIsRateLimited() throws Exception {
        register("limited@example.com", "StrongPass123!").andExpect(status().isCreated());

        for (int failure = 1; failure <= 5; failure++) {
            mockMvc.perform(post("/console/platform/auth/login")
                            .with(request -> {
                                request.setRemoteAddr("203.0.113.83");
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"limited@example.com","password":"wrong"}
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("invalid_credentials"));
        }

        mockMvc.perform(post("/console/platform/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.83");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"limited@example.com","password":"StrongPass123!"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("login_rate_limited"));
    }

    @Test
    void equivalentPhoneFormatsShareTheSameAccountLimit() throws Exception {
        register("phone-limited@example.com", "StrongPass123!").andExpect(status().isCreated());
        for (String identifier : java.util.List.of(
                "15000000001", "+8615000000001", "150 0000 0001",
                "+86 150 0000 0001", "15000000001")) {
            mockMvc.perform(post("/console/platform/auth/login")
                            .with(request -> {
                                request.setRemoteAddr("203.0.113.84");
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"identifier":"%s","password":"wrong"}
                                    """.formatted(identifier)))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/console/platform/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.84");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"+8615000000001","password":"StrongPass123!"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("login_rate_limited"));
    }

    @Test
    void invitationAdministrationEndpointsAreRemoved() throws Exception {
        IdentityService.UserProfile admin = insertAdmin();
        String adminToken = jwtTokenService.createAccessToken(admin);

        mockMvc.perform(post("/console/platform/admin/invitations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInHours\":24}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/console/platform/admin/invitations")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishesTheCurrentPublicSigningKey() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").isString())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void profileReturnsTheAuthenticatedUserAndSessionHistory() throws Exception {
        register("user@example.com", "StrongPass123!")
                .andExpect(status().isCreated());
        String accessToken = loginAccessToken("user@example.com", "StrongPass123!");
        String userNo = jdbcTemplate.queryForObject(
                "SELECT user_no FROM platform.app_user WHERE email = 'user@example.com'", String.class);
        UUID latestSessionId = jdbcTemplate.queryForObject("""
                SELECT id FROM platform.terminal_session
                WHERE user_id = (SELECT id FROM platform.app_user WHERE email = 'user@example.com')
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """, UUID.class);

        mockMvc.perform(get("/console/platform/auth/validate")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/console/platform/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.userNo").value(userNo))
                .andExpect(jsonPath("$.displayName").value("Test User"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.maxLoginDevices").value(5))
                .andExpect(jsonPath("$.activeDeviceCount").value(1))
                .andExpect(jsonPath("$.sessions.length()").value(2))
                .andExpect(jsonPath("$.sessions[0].id").value(latestSessionId.toString()))
                .andExpect(jsonPath("$.sessions[0].tokenHash").doesNotExist());
    }

    @Test
    void profileCountsActiveDevicesOutsideTheRecentSessionHistoryWindow() throws Exception {
        MvcResult registered = register("device-count@example.com", "StrongPass123!")
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = com.jayway.jsonpath.JsonPath.read(
                registered.getResponse().getContentAsString(), "$.accessToken");
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform.app_user WHERE email = 'device-count@example.com'", UUID.class);
        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM platform.app_user WHERE id = ?", UUID.class, userId);

        for (int index = 0; index < 55; index++) {
            UUID sessionId = UUID.randomUUID();
            String deviceHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
            jdbcTemplate.update("""
                    INSERT INTO platform.terminal_session(
                        id, tenant_id, user_id, client_type, device_hash, status,
                        created_at, expires_at, revoked_at, revocation_reason)
                    VALUES (?, ?, ?, 'DESKTOP_WEB', ?, 'REVOKED', now(), now() + interval '30 days', now(), 'TEST_HISTORY')
                    """, sessionId, tenantId, userId, deviceHash);
        }

        mockMvc.perform(get("/console/platform/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeDeviceCount").value(1))
                .andExpect(jsonPath("$.sessions[?(@.status == 'ACTIVE')]").isEmpty())
                .andExpect(jsonPath("$.sessions.length()").value(50));
    }

    @Test
    void updatesTheAuthenticatedUsersLoginDeviceLimitWithinTheSupportedRange() throws Exception {
        register("device-setting@example.com", "StrongPass123!")
                .andExpect(status().isCreated());
        String accessToken = loginAccessToken("device-setting@example.com", "StrongPass123!");
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform.app_user WHERE email = 'device-setting@example.com'", UUID.class);

        mockMvc.perform(patch("/console/platform/profile/device-limit")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxLoginDevices\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxLoginDevices").value(7));

        mockMvc.perform(get("/console/platform/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxLoginDevices").value(7));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT max_login_devices FROM platform.app_user WHERE id = ?",
                Integer.class, userId)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action = 'LOGIN_DEVICE_LIMIT_CHANGED'
                  AND detail ->> 'maxLoginDevices' = '7'
                """, Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void rejectsAnInvalidLoginDeviceLimitWithoutChangingTheDefault() throws Exception {
        register("invalid-device-setting@example.com", "StrongPass123!")
                .andExpect(status().isCreated());
        String accessToken = loginAccessToken("invalid-device-setting@example.com", "StrongPass123!");
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform.app_user WHERE email = 'invalid-device-setting@example.com'", UUID.class);

        for (String requestBody : java.util.List.of(
                "{}", "{\"maxLoginDevices\":0}", "{\"maxLoginDevices\":11}")) {
            mockMvc.perform(patch("/console/platform/profile/device-limit")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("invalid_login_device_limit"));
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT max_login_devices FROM platform.app_user WHERE id = ?",
                Integer.class, userId)).isEqualTo(5);
    }

    @Test
    void appliesTheConfiguredLoginDeviceLimitToFutureDistinctDevices() throws Exception {
        MvcResult registered = register("configured-cap@example.com", "StrongPass123!")
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = com.jayway.jsonpath.JsonPath.read(
                registered.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(patch("/console/platform/profile/device-limit")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxLoginDevices\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxLoginDevices").value(1));

        mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", "10000000000000000000000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"configured-cap@example.com","password":"StrongPass123!"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("login_device_limit_reached"));
    }

    @Test
    void avatarUploadReturnsFormatMismatchCodeAndAcceptsMatchingContentType() throws Exception {
        MvcResult registered = register("avatar@example.com", "StrongPass123!")
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = com.jayway.jsonpath.JsonPath.read(
                registered.getResponse().getContentAsString(), "$.accessToken");
        byte[] jpeg = imageBytes("jpeg");

        mockMvc.perform(multipart("/console/platform/profile/avatar")
                        .file(new MockMultipartFile("file", "avatar.png", "image/png", jpeg))
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("avatar_file_format_mismatch"));

        mockMvc.perform(multipart("/console/platform/profile/avatar")
                        .file(new MockMultipartFile("file", "avatar.jpg", "image/jpeg", jpeg))
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/console/platform/profile/avatar")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"));
    }

    @Test
    void phoneRegistrationPasswordSetupReturnsAUsableSession() throws Exception {
        String phone = "15000000001";
        SmsChallengeService.SmsChallenge challenge = smsChallenges.issue(
                SmsPurpose.REGISTER, phone, null, null, "a".repeat(64));
        String code = deliveredCode(challenge.id());

        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", "a".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","challengeId":"%s","verificationCode":"%s",
                                 "termsAccepted":true}
                                """.formatted(phone, challenge.id(), code)))
                .andExpect(status().isAccepted());

        MvcResult setup = mockMvc.perform(post("/console/platform/auth/identity/complete-registration")
                        .header("X-Device-Id", "a".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","newPassword":"Ab1!cdef","termsAccepted":true}
                                """.formatted(challenge.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.passwordSet").value(true))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(cookie().exists("yzh_refresh"))
                .andExpect(cookie().exists("yzh_csrf"))
                .andReturn();
        String accessToken = com.jayway.jsonpath.JsonPath.read(
                setup.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/console/platform/auth/validate")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void passwordChangeVerifiesCurrentPasswordAndRevokesAllRefreshSessions() throws Exception {
        register("user@example.com", "StrongPass123!")
                .andExpect(status().isCreated());
        String accessToken = loginAccessToken("user@example.com", "StrongPass123!");
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform.app_user WHERE email = 'user@example.com'", UUID.class);
        SmsChallengeService.SmsChallenge passwordChallenge = smsChallenges.issue(
                SmsPurpose.CHANGE_PASSWORD, "15000000001", userId, userId, "b".repeat(64));
        String smsProof = smsChallenges.verifyOwned(
                passwordChallenge.id(), deliveredCode(passwordChallenge.id()), userId, userId).proofToken();

        mockMvc.perform(post("/console/platform/profile/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong","newPassword":"ChangedPass456!"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/console/platform/profile/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"StrongPass123!","newPassword":"ChangedPass456!",
                                 "smsProof":"%s"}
                                """.formatted(smsProof)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/console/platform/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/console/platform/auth/validate")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.refresh_token WHERE revoked_at IS NULL", Integer.class))
                .isZero();
        mockMvc.perform(post("/console/platform/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","password":"StrongPass123!"}
                                """))
                .andExpect(status().isUnauthorized());
        MvcResult changedLogin = mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", "0123456789abcdef0123456789abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","password":"ChangedPass456!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String changedAccessToken = com.jayway.jsonpath.JsonPath.read(
                changedLogin.getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/console/platform/auth/validate")
                        .header("Authorization", "Bearer " + changedAccessToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void allowsFiveDevicesRejectsTheSixthAndRotatesSameDeviceSession() throws Exception {
        String email = "device-limit@example.com";
        String password = "StrongPass123!";
        String firstDevice = "0123456789abcdef0123456789abcdef";
        MvcResult registered = register(email, password)
                .andExpect(status().isCreated())
                .andReturn();
        String firstAccessToken = com.jayway.jsonpath.JsonPath.read(
                registered.getResponse().getContentAsString(), "$.accessToken");

        String reloginDevice = null;
        String reloginOldToken = null;
        for (int index = 1; index <= 4; index++) {
            String deviceId = "%032x".formatted(index);
            MvcResult login = mockMvc.perform(post("/console/platform/auth/login")
                            .header("X-Device-Id", deviceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"%s","password":"%s"}
                                    """.formatted(email, password)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.status").value("ACTIVE"))
                    .andReturn();
            if (index == 1) {
                reloginDevice = deviceId;
                reloginOldToken = com.jayway.jsonpath.JsonPath.read(
                        login.getResponse().getContentAsString(), "$.accessToken");
            }
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.terminal_session
                WHERE status = 'ACTIVE' AND expires_at > now()
                """, Integer.class)).isEqualTo(5);

        mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", "%032x".formatted(5))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("login_device_limit_reached"));

        mockMvc.perform(get("/console/platform/auth/validate")
                        .header("Authorization", "Bearer " + firstAccessToken))
                .andExpect(status().isNoContent());

        MvcResult relogin = mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", reloginDevice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(get("/console/platform/auth/validate")
                        .header("Authorization", "Bearer " + reloginOldToken))
                .andExpect(status().isUnauthorized());
        String rotatedToken = com.jayway.jsonpath.JsonPath.read(
                relogin.getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/console/platform/auth/validate")
                        .header("Authorization", "Bearer " + rotatedToken))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.terminal_session
                WHERE status = 'ACTIVE' AND expires_at > now()
                """, Integer.class)).isEqualTo(5);
    }

    @Test
    void disabledAccountGetsOnlyARestrictedSessionAfterValidCredentials() throws Exception {
        String email = "restricted@example.com";
        String password = "StrongPass123!";
        MvcResult registered = register(email, password)
                .andExpect(status().isCreated())
                .andReturn();
        String onlineToken = com.jayway.jsonpath.JsonPath.read(
                registered.getResponse().getContentAsString(), "$.accessToken");
        jdbcTemplate.update("""
                UPDATE platform.app_user
                SET status = 'DISABLED', disabled_reason = '账号存在异常登录', disabled_at = now()
                WHERE email = ?
                """, email);

        mockMvc.perform(post("/console/platform/auth/terminal-presence")
                        .header("Authorization", "Bearer " + onlineToken)
                        .header("X-Device-Id", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.disabledReason").value("账号存在异常登录"))
                .andExpect(jsonPath("$.disabledAt").isString());
        mockMvc.perform(get("/console/platform/orders")
                        .header("Authorization", "Bearer " + onlineToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account_restricted"));

        mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", "%032x".formatted(9))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"wrong"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"));

        MvcResult login = mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", "%032x".formatted(9))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.status").value("DISABLED"))
                .andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(
                login.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/console/platform/auth/account-state")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.disabledReason").value("账号存在异常登录"));

        mockMvc.perform(get("/console/platform/auth/validate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/console/platform/orders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account_restricted"));
        mockMvc.perform(get("/console/platform/support-tickets")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/console/platform/safety/appeals")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void browserCloseIsDelayedAndHeartbeatCancelsThePendingClose() throws Exception {
        MvcResult registered = register("browser-close@example.com", "StrongPass123!")
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = com.jayway.jsonpath.JsonPath.read(
                registered.getResponse().getContentAsString(), "$.accessToken");
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform.app_user WHERE email = 'browser-close@example.com'",
                UUID.class);

        mockMvc.perform(post("/console/platform/auth/terminal-close")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Device-Id", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT browser_close_requested_at IS NOT NULL
                FROM platform.terminal_session
                WHERE user_id = ? AND status = 'ACTIVE'
                """, Boolean.class, userId)).isTrue();

        mockMvc.perform(post("/console/platform/auth/terminal-presence")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Device-Id", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT browser_close_requested_at IS NULL
                FROM platform.terminal_session
                WHERE user_id = ? AND status = 'ACTIVE'
                """, Boolean.class, userId)).isTrue();

        mockMvc.perform(post("/console/platform/auth/terminal-close")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Device-Id", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isNoContent());
        assertThat(terminalSessionRepository.revokeBrowserClosedTerminals(
                Instant.now().plusSeconds(1), Instant.now())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.terminal_session
                WHERE user_id = ? AND status = 'ACTIVE'
                """, Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.refresh_token
                WHERE user_id = ? AND revoked_at IS NULL
                """, Integer.class, userId)).isZero();
        mockMvc.perform(get("/console/platform/auth/validate")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void safetyRestrictedAccountCannotLoginRefreshOrAccessOrdinaryBusinessApis() throws Exception {
        String email = "safety-restricted@example.com";
        String password = "StrongPass123!";
        MvcResult registered = register(email, password)
                .andExpect(status().isCreated())
                .andReturn();
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform.app_user WHERE email = ?", UUID.class, email);
        jdbcTemplate.update("""
                INSERT INTO platform.account_enforcement(
                    id, tenant_id, user_id, state, reason_code, starts_at, version)
                VALUES (?, ?, ?, 'FROZEN', 'policy.test', now(), 1)
                """, UUID.randomUUID(), userId, userId);

        mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", "fedcba9876543210fedcba9876543210")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account_safety_restricted"));

        String refresh = registered.getResponse().getCookie("yzh_refresh").getValue();
        String csrf = registered.getResponse().getCookie("yzh_csrf").getValue();
        mockMvc.perform(post("/console/platform/auth/refresh")
                        .cookie(new MockCookie("yzh_refresh", refresh),
                                new MockCookie("yzh_csrf", csrf))
                        .header("X-CSRF-Token", csrf)
                        .header("X-Device-Id", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account_safety_restricted"));

        String accessToken = com.jayway.jsonpath.JsonPath.read(
                registered.getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/console/platform/orders")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account_safety_restricted"));
        mockMvc.perform(get("/console/platform/support-tickets")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/console/platform/safety/appeals")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/console/platform/support-tickets-unrelated")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account_safety_restricted"));
    }

    @Test
    void registrationRequiresExplicitTermsAcceptance() throws Exception {
        // Omitted and explicit false both have to fail before any account exists. Each case
        // gets its own already-verified challenge because a rejected request still consumes it.
        for (String consent : new String[] {null, "false"}) {
            String phone = "1500000%04d".formatted(++phoneSequence);
            SmsChallengeService.SmsChallenge challenge = smsChallenges.issue(
                    SmsPurpose.REGISTER, phone, null, null, "a".repeat(64));
            String code = deliveredCode(challenge.id());
            String consentField = consent == null
                    ? ""
                    : ",\n  \"termsAccepted\": " + consent;
            mockMvc.perform(post("/console/platform/auth/register")
                            .header("X-Device-Id", "0123456789abcdef0123456789abcdef")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "consent-required@example.com",
                                      "password": "StrongPass123!",
                                      "displayName": "Test User",
                                      "phone": "%s",
                                      "challengeId": "%s",
                                      "verificationCode": "%s"%s
                                    }
                                    """.formatted(phone, challenge.id(), code, consentField)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("terms_acceptance_required"));

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM platform.app_user WHERE email = 'consent-required@example.com'",
                    Integer.class)).isZero();
        }
    }

    @Test
    void verifiedRegistrationRecordsBothAcceptedDocumentsWithTheirVersion() throws Exception {
        MvcResult registered = register("consent@example.com", "StrongPass123!")
                .andExpect(status().isCreated())
                .andReturn();
        UUID userId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                registered.getResponse().getContentAsString(), "$.user.id"));

        assertThat(jdbcTemplate.queryForList("""
                SELECT document, version, source FROM platform.user_consent
                WHERE user_id = ? ORDER BY document
                """, userId))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row)
                        .containsEntry("version", ConsentProperties.DEFAULT_VERSION)
                        .containsEntry("source", "REGISTRATION"))
                .extracting(row -> row.get("document"))
                .containsExactly("PRIVACY", "TERMS");
    }

    @Test
    void firstPhoneIdentityLoginCreatesTheAccountOnlyAfterConsentAndPasswordSetup() throws Exception {
        String phone = "1500000%04d".formatted(++phoneSequence);

        SmsChallengeService.SmsChallenge rejected = smsChallenges.issue(
                SmsPurpose.REGISTER, phone, null, null, "c".repeat(64));
        String code = deliveredCode(rejected.id());

        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", "c".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","challengeId":"%s","verificationCode":"%s"}
                                """.formatted(phone, rejected.id(), code)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("terms_acceptance_required"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE display_name LIKE ?",
                Integer.class, "%" + phone.substring(phone.length() - 4))).isZero();

        // Same challenge, still unconsumed after the rejection, and issued directly so the
        // 60-second per-phone SMS rate limit does not mask the consent behaviour under test.
        SmsChallengeService.SmsChallenge accepted = new SmsChallengeService.SmsChallenge(
                rejected.id(), rejected.purpose(), rejected.maskedPhone(), rejected.expiresAt());
        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", "c".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","challengeId":"%s","verificationCode":"%s",
                                 "termsAccepted":true,"consentVersion":"2026-09-15"}
                                """.formatted(phone, accepted.id(), code)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("phone_registration_pending"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE display_name LIKE ?",
                Integer.class, "%" + phone.substring(phone.length() - 4))).isZero();

        MvcResult identity = mockMvc.perform(post("/console/platform/auth/identity/complete-registration")
                        .header("X-Device-Id", "c".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","newPassword":"StrongPass123!",
                                 "termsAccepted":true,"consentVersion":"2026-09-15"}
                                """.formatted(accepted.id())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID userId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                identity.getResponse().getContentAsString(), "$.user.id"));

        assertThat(jdbcTemplate.queryForList("""
                SELECT document, version, source FROM platform.user_consent
                WHERE user_id = ? ORDER BY document
                """, userId))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row)
                        .containsEntry("version", "2026-09-15")
                        .containsEntry("source", "PHONE_IDENTITY"))
                .extracting(row -> row.get("document"))
                .containsExactly("PRIVACY", "TERMS");
    }

    @Test
    void returningPhoneUserWithoutConsentStillSignsIn() throws Exception {
        String phone = "1500000%04d".formatted(++phoneSequence);

        // The first verified challenge remains pending until a password is set.
        SmsChallengeService.SmsChallenge creation = smsChallenges.issue(
                SmsPurpose.REGISTER, phone, null, null, "e".repeat(64));
        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", "e".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","challengeId":"%s","verificationCode":"%s",
                                 "termsAccepted":true,"consentVersion":"2026-09-15"}
                                """.formatted(
                                phone, creation.id(), deliveredCode(creation.id()))))
                .andExpect(status().isAccepted());
        MvcResult created = mockMvc.perform(post("/console/platform/auth/identity/complete-registration")
                        .header("X-Device-Id", "e".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","newPassword":"StrongPass123!",
                                 "termsAccepted":true,"consentVersion":"2026-09-15"}
                                """.formatted(creation.id())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID userId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.user.id"));

        // ...and the returning login for that same account must not be gated on consent again.
        SmsChallengeService.SmsChallenge returnLogin = smsChallenges.issue(
                SmsPurpose.REGISTER, phone, null, null, "f".repeat(64));
        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", "f".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","challengeId":"%s","verificationCode":"%s",
                                 "termsAccepted":false}
                                """.formatted(
                                phone, returnLogin.id(), deliveredCode(returnLogin.id()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId.toString()));
    }

    @Test
    void ordersReturnOnlyTheAuthenticatedUsersPaymentAndRefundState() throws Exception {
        MvcResult registered = register("user@example.com", "StrongPass123!")
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = com.jayway.jsonpath.JsonPath.read(
                registered.getResponse().getContentAsString(), "$.accessToken");
        UUID userId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                registered.getResponse().getContentAsString(), "$.user.id"));
        UUID versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform.package_plan_version ORDER BY created_at LIMIT 1", UUID.class);
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, paid_at)
                VALUES (?, 'order-user-001', ?, ?, ?, 9900, 'CNY', 'PAID', 'MOCK', now())
                """, orderId, userId, userId, versionId);
        jdbcTemplate.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, external_trade_no,
                    amount, status, paid_at)
                VALUES (?, 'payment-user-001', ?, 'MOCK', 'trade-user-001',
                    9900, 'SUCCEEDED', now())
                """, paymentId, orderId);
        jdbcTemplate.update("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id,
                    amount, status, reason, refund_type, policy_version,
                    calculation_snapshot, requested_by, provider,
                    gateway_hold_business_no, revocation_business_no)
                VALUES (?, 'refund-user-001', ?, ?, 100, 'PENDING_APPROVAL', 'test refund',
                        'PARTIAL', 1, '{"fixture":"phase2"}'::jsonb, ?, 'MOCK',
                        'refund-hold:refund-user-001', 'refund-revoke:refund-user-001')
                """, UUID.randomUUID(), orderId, paymentId, userId);
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider)
                VALUES (?, 'order-user-002', ?, ?, ?, 1900, 'CNY', 'PENDING', 'MOCK')
                """, UUID.randomUUID(), userId, userId, versionId);

        mockMvc.perform(get("/console/platform/orders?status=PAID&page=0&size=20")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].orderNo").value("order-user-001"))
                .andExpect(jsonPath("$.items[0].paymentStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].refundStatus").value("PENDING_APPROVAL"));
    }

    @Test
    void profileControllerRejectsMissingOrMalformedIdentityClaimsWithUnauthorized() {
        ProfileController controller = new ProfileController(null, null);
        UUID valid = UUID.randomUUID();
        for (Jwt jwt : java.util.List.of(
                jwt(null, valid.toString()), jwt("not-a-uuid", valid.toString()),
                jwt(valid.toString(), null), jwt(valid.toString(), "not-a-uuid"))) {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> controller.profile(jwt)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        }
    }

    @Test
    void securityFilterRejectsValidSignedTokenWithoutSubjectAsUnauthorized() throws Exception {
        mockMvc.perform(get("/console/platform/profile")
                        .header("Authorization", "Bearer " + signedToken(null, UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void securityFilterRejectsValidSignedTokenWithoutTenantAsUnauthorized() throws Exception {
        mockMvc.perform(get("/console/platform/profile")
                        .header("Authorization", "Bearer " + signedToken(UUID.randomUUID().toString(), null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void legacyAccessTokenWithoutAccountStatusStillValidatesForActiveUser() throws Exception {
        IdentityService.UserProfile user = insertAdmin();
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.terminal_session(
                    id, tenant_id, user_id, client_type, device_hash, status, expires_at)
                VALUES (?, ?, ?, 'DESKTOP_WEB', ?, 'ACTIVE', now() + interval '30 days')
                """, sessionId, user.tenantId(), user.id(), "0".repeat(64));

        mockMvc.perform(get("/console/platform/auth/validate")
                        .header("Authorization", "Bearer "
                                + signedToken(
                                        user.id().toString(), user.tenantId().toString(), sessionId)))
                .andExpect(status().isNoContent());
    }

    private org.springframework.test.web.servlet.ResultActions register(
            String email, String password) throws Exception {
        String phone = "1500000%04d".formatted(++phoneSequence);
        SmsChallengeService.SmsChallenge challenge = smsChallenges.issue(
                SmsPurpose.REGISTER, phone, null, null, "a".repeat(64));
        byte[] encryptedCode = jdbcTemplate.queryForObject("""
                SELECT sensitive_variables_ciphertext FROM platform.sms_delivery
                WHERE deduplication_key = ?
                """, byte[].class, "verification:" + challenge.id());
        String code = objectMapper.readTree(smsEncryption.decrypt(encryptedCode)).get("code").asText();
        return mockMvc.perform(post("/console/platform/auth/register")
                .header("X-Device-Id", "0123456789abcdef0123456789abcdef")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "email": "%s",
                          "password": "%s",
                          "displayName": "Test User",
                          "phone": "%s",
                          "challengeId": "%s",
                          "verificationCode": "%s",
                          "termsAccepted": true
                        }
                        """.formatted(email, password, phone, challenge.id(), code)));
    }

    private String deliveredCode(UUID challengeId) throws Exception {
        byte[] encryptedCode = jdbcTemplate.queryForObject("""
                SELECT sensitive_variables_ciphertext FROM platform.sms_delivery
                WHERE deduplication_key = ?
                """, byte[].class, "verification:" + challengeId);
        return objectMapper.readTree(smsEncryption.decrypt(encryptedCode)).get("code").asText();
    }

    private byte[] imageBytes(String format) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, output);
            return output.toByteArray();
        }
    }

    private void insertInvitation(String code) throws Exception {
        String hash = sha256(code);
        jdbcTemplate.update("""
                INSERT INTO platform.invitation(id, code_prefix, code_hash, status, expires_at)
                VALUES (?, ?, ?, 'AVAILABLE', ?)
                """, UUID.randomUUID(), code.substring(0, Math.min(8, code.length())), hash,
                Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    private String loginAccessToken(String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", "0123456789abcdef0123456789abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                login.getResponse().getContentAsString(), "$.accessToken");
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private Jwt jwt(String subject, String tenantId) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (subject != null) builder.subject(subject);
        if (tenantId != null) builder.claim("tenant_id", tenantId);
        return builder.build();
    }

    private String signedToken(String subject, String tenantId) {
        return signedToken(subject, tenantId, null);
    }

    private String signedToken(String subject, String tenantId, UUID sessionId) {
        Instant issuedAt = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("yuzhihuihe-platform")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(60))
                .id(UUID.randomUUID().toString())
                .claim("role", "USER")
                .claim("auth_version", 0L);
        if (subject != null) claims.subject(subject);
        if (tenantId != null) claims.claim("tenant_id", tenantId);
        if (sessionId != null) {
            claims.claim("session_id", sessionId.toString());
            claims.claim("client_type", "DESKTOP_WEB");
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(jwtRsaKey.getKeyID())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private IdentityService.UserProfile insertAdmin() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'admin@example.com', 'not-used', 'Admin', 'ACTIVE')
                """, userId, userId);
        Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform.app_role WHERE code = 'ADMIN'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id) VALUES (?, ?, ?)
                """, userId, userId, roleId);
        return new IdentityService.UserProfile(userId, userId, "admin@example.com", "Admin", "ADMIN");
    }
}
