package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.identity.IdentityService;
import com.yuzhihuihe.apiplatform.platform.identity.IdentityRepositories;
import com.yuzhihuihe.apiplatform.platform.identity.JwtTokenService;
import com.yuzhihuihe.apiplatform.platform.security.SecretEncryptionService;
import com.yuzhihuihe.apiplatform.platform.wallet.WalletServiceTestFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
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
        "sms.captcha.enabled=false",
        "identity.login-risk.pepper=test-login-risk-pepper-with-at-least-32-bytes",
        "sms.aliyun.endpoint=dysmsapi.aliyuncs.com",
        "sms.aliyun.access-key-id=test-only",
        "sms.aliyun.access-key-secret=test-only",
        "sms.aliyun.sign-name=test-only",
        "sms.aliyun.region-id=cn-hangzhou",
        "sms.jobs.delivery-delay-ms=3600000"
})
@AutoConfigureMockMvc
class SmsIdentityApiTest {

    private static final String PASSWORD = "StrongPass123!";
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
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenService jwtTokens;
    @Autowired PasswordEncoder passwords;
    @Autowired SmsChallengeService smsChallenges;
    @Autowired
    @Qualifier("smsSecretEncryptionService")
    SecretEncryptionService smsEncryption;

    @BeforeEach
    void reset() {
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
        // 账号创建会写入网关侧安全缓存的种子数据；这里与 gateway V18 保持一致，以便
        // 创建账号的身份相关路径能在此上下文中完成。
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
        jdbc.update("TRUNCATE platform.admin_audit_log, platform.sms_delivery, platform.sms_proof, "
                + "platform.sms_verification_challenge, platform.refresh_token, platform.user_role, "
                + "platform.invitation, platform.app_user, platform.app_role RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO platform.app_role(code, name) VALUES ('USER', 'User'), ('ADMIN', 'Admin')");
    }

    @Test
    void publicRegistrationChallengeIsTheOnlyAnonymousSmsRouteAndMasksRecipient() throws Exception {
        mockMvc.perform(post("/console/platform/auth/sms/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purpose":"REGISTER","phone":"15012341234"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.purpose").value("REGISTER"))
                .andExpect(jsonPath("$.maskedPhone").value("150****1234"))
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.phone").doesNotExist());

        mockMvc.perform(post("/console/platform/auth/sms/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purpose":"CHANGE_PASSWORD","phone":"15012341234"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_sms_purpose"));
        mockMvc.perform(post("/console/platform/sms/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"CHANGE_PASSWORD\"}"))
                .andExpect(status().isUnauthorized());

        String storedFingerprint = jdbc.queryForObject(
                "SELECT request_ip_hash FROM platform.sms_verification_challenge", String.class);
        assertThat(storedFingerprint).matches("[0-9a-f]{64}").doesNotContain("127.0.0.1");
    }

    /**
     * 指纹以 {@code getRemoteAddr()} 为键，**应用层刻意不读 {@code X-Forwarded-For}**。
     *
     * <p>采信转发头是容器的事：{@code server.forward-headers-strategy: native}
     * 安装 Tomcat 的 RemoteIpValve，只在对端匹配 {@code internal-proxies} 时才改写
     * {@code getRemoteAddr()}（application.yaml:1-20 记录了为什么选 native 而不是
     * framework：后者会无条件采信任何能建立连接的对端）。本服务处于 nginx 之后，
     * 而 nginx 位于私有网络，因此该阀生效、限流与指纹都以真实客户端地址为键。
     *
     * <p>MockMvc 不经过 servlet 容器，阀不参与。本用例显式模拟受信代理完成地址
     * 解析后的 {@code getRemoteAddr()}；下一个用例保留回环地址并只伪造转发头，
     * 验证应用自己不解析该头，避免任意对端借此轮换限流与指纹桶。
     * 「转发地址真正生效」那一段由部署契约测试固定
     * （deploy/tests/deployment-contract.test.mjs 断言 nginx 写该头、
     * 且本服务配置为 native）。
     */
    @Test
    void requestFingerprintKeysOnTheContainerResolvedRemoteAddress() throws Exception {
        mockMvc.perform(post("/console/platform/auth/sms/challenges")
                        .header("X-Forwarded-For", "203.0.113.41")
                        // MockMvc bypasses Tomcat's RemoteIpValve; emulate its trusted-proxy result.
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.41");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"REGISTER\",\"phone\":\"15012341234\"}"))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject(
                "SELECT request_ip_hash FROM platform.sms_verification_challenge", String.class))
                .isEqualTo(hmac(
                        "sms-request-ip:v1:203.0.113.41".getBytes(StandardCharsets.US_ASCII),
                        "3".repeat(32).getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void forgedForwardedAddressDoesNotReplaceTheUnresolvedRemoteAddress() throws Exception {
        mockMvc.perform(post("/console/platform/auth/sms/challenges")
                        .header("X-Forwarded-For", "203.0.113.41")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"REGISTER\",\"phone\":\"15012341234\"}"))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject(
                "SELECT request_ip_hash FROM platform.sms_verification_challenge", String.class))
                .isEqualTo(hmac(
                        "sms-request-ip:v1:127.0.0.1".getBytes(StandardCharsets.US_ASCII),
                        "3".repeat(32).getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void registrationRequiresSmsAndAtomicallyStoresEncryptedVerifiedPhone() throws Exception {
        mockMvc.perform(post("/console/platform/auth/register")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("sms-user@example.com", "15012341234", null, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("sms_verification_required"))
                .andExpect(jsonPath("$.reason").value("sms_verification_required"));

        Challenge challenge = issue("/console/platform/auth/sms/challenges", null,
                "REGISTER", "15012341234");
        MvcResult result = mockMvc.perform(post("/console/platform/auth/register")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(
                                "sms-user@example.com", "15012341234",
                                challenge.id(), challenge.code())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("sms-user@example.com"))
                .andReturn();

        UUID userId = UUID.fromString(json(result).at("/user/id").asText());
        byte[] ciphertext = jdbc.queryForObject(
                "SELECT phone_ciphertext FROM platform.app_user WHERE id = ?", byte[].class, userId);
        assertThat(new String(smsEncryption.decrypt(ciphertext), StandardCharsets.UTF_8))
                .isEqualTo("+8615012341234");
        assertThat(jdbc.queryForObject("""
                SELECT phone_verified_at IS NOT NULL AND phone_lookup_hash IS NOT NULL
                FROM platform.app_user WHERE id = ?
                """, Boolean.class, userId)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT consumed_at IS NOT NULL FROM platform.sms_verification_challenge WHERE id = ?
                """, Boolean.class, challenge.id())).isTrue();

        mockMvc.perform(post("/console/platform/auth/register")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(
                                "replay@example.com", "15012341234", challenge.id(), challenge.code())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void directRegistrationPersistsWrongSmsAttemptsAndLocksTheChallenge() throws Exception {
        Challenge challenge = issue("/console/platform/auth/sms/challenges", null,
                "REGISTER", "15012341111");
        String wrongCode = wrongCode(challenge);

        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(post("/console/platform/auth/register")
                            .header("X-Device-Id", DEVICE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerJson("wrong-code@example.com", "15012341111",
                                    challenge.id(), wrongCode)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value(attempt == 5
                            ? "challenge_attempts_exhausted" : "invalid_sms_code"));
            assertThat(jdbc.queryForObject("""
                    SELECT attempt_count FROM platform.sms_verification_challenge WHERE id = ?
                    """, Integer.class, challenge.id())).isEqualTo(attempt);
        }

        mockMvc.perform(post("/console/platform/auth/register")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("wrong-code@example.com", "15012341111",
                                challenge.id(), challenge.code())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("challenge_attempts_exhausted"));
        assertChallengeUnused(challenge.id());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE email = 'wrong-code@example.com'",
                Integer.class)).isZero();
    }

    @Test
    void phoneIdentityPersistsWrongSmsAttemptsAndLocksTheChallenge() throws Exception {
        String phone = "15012342222";
        Challenge challenge = issue("/console/platform/auth/sms/challenges", null,
                "REGISTER", phone);
        String wrongCode = wrongCode(challenge);

        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(post("/console/platform/auth/identity")
                            .header("X-Device-Id", DEVICE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"identifier":"%s","challengeId":"%s",
                                     "verificationCode":"%s","termsAccepted":true}
                                    """.formatted(phone, challenge.id(), wrongCode)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value(attempt == 5
                            ? "challenge_attempts_exhausted" : "invalid_sms_code"));
            assertThat(jdbc.queryForObject("""
                    SELECT attempt_count FROM platform.sms_verification_challenge WHERE id = ?
                    """, Integer.class, challenge.id())).isEqualTo(attempt);
        }

        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","challengeId":"%s",
                                 "verificationCode":"%s","termsAccepted":true}
                                """.formatted(phone, challenge.id(), challenge.code())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("challenge_attempts_exhausted"));
        assertChallengeUnused(challenge.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.app_user",
                Integer.class)).isZero();
    }

    @Test
    void rejectedRegistrationConsentRollsBackSuccessfulSmsVerification() throws Exception {
        Challenge direct = issue("/console/platform/auth/sms/challenges", null,
                "REGISTER", "15012343333");
        mockMvc.perform(post("/console/platform/auth/register")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"consent-retry@example.com","password":"%s",
                                 "displayName":"SMS User","phone":"15012343333",
                                 "challengeId":"%s","verificationCode":"%s","termsAccepted":false}
                                """.formatted(PASSWORD, direct.id(), direct.code())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("terms_acceptance_required"));
        assertChallengeUnused(direct.id());
        mockMvc.perform(post("/console/platform/auth/register")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("consent-retry@example.com", "15012343333",
                                direct.id(), direct.code())))
                .andExpect(status().isCreated());

        String phone = "15012344444";
        Challenge identity = issue("/console/platform/auth/sms/challenges", null,
                "REGISTER", phone);
        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","challengeId":"%s",
                                 "verificationCode":"%s","termsAccepted":false}
                                """.formatted(phone, identity.id(), identity.code())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("terms_acceptance_required"));
        assertChallengeUnused(identity.id());
        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","challengeId":"%s",
                                 "verificationCode":"%s","termsAccepted":true}
                                """.formatted(phone, identity.id(), identity.code())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("phone_registration_pending"));
    }

    @Test
    void unifiedPhoneIdentityWaitsForPasswordBeforeCreatingAccount() throws Exception {
        Challenge first = issue("/console/platform/auth/sms/challenges", null,
                "REGISTER", "15012349999");
        MvcResult pending = mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"15012349999","challengeId":"%s","verificationCode":"%s",
                                 "termsAccepted":true}
                                """.formatted(first.id(), first.code())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("phone_registration_pending"))
                .andExpect(jsonPath("$.challengeId").value(first.id().toString()))
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();
        assertThat(pending.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE phone_last4 = '9999'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.refresh_token", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT consumed_at IS NULL FROM platform.sms_verification_challenge WHERE id = ?",
                Boolean.class, first.id())).isTrue();

        MvcResult created = mockMvc.perform(post("/console/platform/auth/identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","newPassword":"%s","termsAccepted":true}
                                """.formatted(first.id(), PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").doesNotExist())
                .andExpect(jsonPath("$.user.displayName").value("150用户"))
                .andExpect(jsonPath("$.user.passwordSet").value(true))
                .andReturn();

        UUID userId = UUID.fromString(json(created).at("/user/id").asText());
        Map<String, Object> account = jdbc.queryForMap("""
                SELECT email, password_hash, phone_verified_at IS NOT NULL AS phone_verified
                FROM platform.app_user WHERE id = ?
                """, userId);
        assertThat(account.get("email")).isNull();
        assertThat(account.get("password_hash")).isNotNull();
        assertThat(account.get("phone_verified")).isEqualTo(true);

        Challenge second = issue("/console/platform/auth/sms/challenges", null,
                "REGISTER", "15012349999");
        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"15012349999","challengeId":"%s","verificationCode":"%s"}
                                """.formatted(second.id(), second.code())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId.toString()))
                .andExpect(jsonPath("$.user.passwordSet").value(true));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE phone_last4 = '9999'",
                Integer.class)).isOne();

        mockMvc.perform(post("/console/platform/auth/identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","newPassword":"%s","termsAccepted":true}
                                """.formatted(first.id(), PASSWORD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_registration_challenge"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE phone_last4 = '9999'",
                Integer.class)).isOne();
    }

    @Test
    void newPhoneRegistrationSupportsPasswordSetupAndLaterPasswordAndSmsLogin() throws Exception {
        String phone = "15012346666";
        Challenge challenge = issue("/console/platform/auth/sms/challenges", null, "REGISTER", phone);
        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","challengeId":"%s","verificationCode":"%s",
                                 "termsAccepted":true}
                                """.formatted(phone, challenge.id(), challenge.code())))
                .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE phone_last4 = '6666'",
                Integer.class)).isZero();

        mockMvc.perform(post("/console/platform/auth/identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","newPassword":"Ab1!cde","termsAccepted":true}
                                """.formatted(challenge.id())))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE phone_last4 = '6666'",
                Integer.class)).isZero();

        MvcResult created = mockMvc.perform(post("/console/platform/auth/identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","newPassword":"Ab1!cdef","termsAccepted":true}
                                """.formatted(challenge.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.user.passwordSet").value(true))
                .andReturn();
        UUID userId = UUID.fromString(json(created).at("/user/id").asText());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE id = ? AND phone_verified_at IS NOT NULL",
                Integer.class, userId)).isOne();
        mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + phone + "\",\"password\":\"Ab1!cdef\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId.toString()))
                .andExpect(jsonPath("$.user.passwordSet").value(true));

        MvcResult smsChallenge = issueLoginChallenge(phone);
        UUID smsChallengeId = UUID.fromString(json(smsChallenge).at("/id").asText());
        mockMvc.perform(post("/console/platform/auth/sms-login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","code":"%s"}
                                """.formatted(smsChallengeId, deliveredCode(smsChallengeId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId.toString()))
                .andExpect(jsonPath("$.user.passwordSet").value(true));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE id = ?", Integer.class, userId))
                .isOne();
    }

    @Test
    void phoneAndDirectRegistrationAttributeOnlyAfterAccountCreation() throws Exception {
        UserFixture inviter = insertUser("phone-inviter@example.com", null);
        String authorization = bearer(inviter);
        MvcResult issued = mockMvc.perform(post("/console/platform/referrals/codes")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andReturn();
        String code = json(issued).path("activeCode").asText();
        assertThat(code).matches("[A-Za-z0-9]{32}");

        String phone = "15012345551";
        Challenge pending = issue("/console/platform/auth/sms/challenges", null, "REGISTER", phone);
        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","challengeId":"%s","verificationCode":"%s",
                                 "termsAccepted":true}
                                """.formatted(phone, pending.id(), pending.code())))
                .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.referral_attribution", Integer.class)).isZero();
        mockMvc.perform(post("/console/platform/auth/identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","newPassword":"%s",
                                 "termsAccepted":true,"invitationCode":"bad"}
                                """.formatted(pending.id(), PASSWORD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_invitation_code"));
        assertThat(jdbc.queryForObject("""
                SELECT consumed_at IS NULL FROM platform.sms_verification_challenge WHERE id = ?
                """, Boolean.class, pending.id())).isTrue();
        mockMvc.perform(post("/console/platform/auth/identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","newPassword":"%s",
                                 "termsAccepted":true,"invitationCode":"%s"}
                                """.formatted(pending.id(), PASSWORD, code)))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.referral_attribution
                WHERE inviter_user_id = ?
                """, Integer.class, inviter.userId())).isOne();

        MvcResult rotated = mockMvc.perform(post("/console/platform/referrals/codes/rotate")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andReturn();
        String newCode = json(rotated).path("activeCode").asText();
        assertThat(newCode).matches("[A-Za-z0-9]{32}").isNotEqualTo(code);
        mockMvc.perform(delete("/console/platform/referrals/codes/current")
                        .header("Authorization", authorization))
                .andExpect(status().isNotFound());
        String directPhone = "15012345552";
        Challenge direct = issue("/console/platform/auth/sms/challenges", null,
                "REGISTER", directPhone);
        mockMvc.perform(post("/console/platform/auth/register")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"direct-invitee@example.com","password":"%s",
                                 "displayName":"Direct Invitee","phone":"%s","challengeId":"%s",
                                 "verificationCode":"%s","termsAccepted":true,
                                 "invitationCode":"%s"}
                                """.formatted(PASSWORD, directPhone, direct.id(), direct.code(), code)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invitation_code_revoked"));
        assertThat(jdbc.queryForObject("""
                SELECT consumed_at IS NULL FROM platform.sms_verification_challenge WHERE id = ?
                """, Boolean.class, direct.id())).isTrue();

        mockMvc.perform(post("/console/platform/auth/register")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"direct-invitee@example.com","password":"%s",
                                 "displayName":"Direct Invitee","phone":"%s","challengeId":"%s",
                                 "verificationCode":"%s","termsAccepted":true,
                                 "invitationCode":"%s"}
                                """.formatted(PASSWORD, directPhone, direct.id(), direct.code(), newCode)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/console/platform/referrals/me")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCode").value(newCode))
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.hasIssuedCode").doesNotExist());
    }

    @Test
    void pendingPhoneRegistrationExpiresWithoutCreatingAnAccount() throws Exception {
        String phone = "15012345555";
        Challenge challenge = issue("/console/platform/auth/sms/challenges", null, "REGISTER", phone);
        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","challengeId":"%s","verificationCode":"%s",
                                 "termsAccepted":true}
                                """.formatted(phone, challenge.id(), challenge.code())))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/console/platform/auth/identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","newPassword":"%s","termsAccepted":false}
                                """.formatted(challenge.id(), PASSWORD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("terms_acceptance_required"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE phone_last4 = '5555'",
                Integer.class)).isZero();

        jdbc.update("""
                UPDATE platform.sms_verification_challenge
                SET created_at = now() - interval '15 minutes',
                    verified_at = now() - interval '12 minutes',
                    expires_at = now() - interval '2 minutes'
                WHERE id = ?
                """, challenge.id());
        mockMvc.perform(post("/console/platform/auth/identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","newPassword":"%s","termsAccepted":true}
                                """.formatted(challenge.id(), PASSWORD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_registration_challenge"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE phone_last4 = '5555'",
                Integer.class)).isZero();
    }

    @Test
    void phoneClaimedDuringPendingRegistrationDoesNotConsumeVerifiedChallenge() throws Exception {
        String phone = "15012344444";
        Challenge challenge = issue("/console/platform/auth/sms/challenges", null, "REGISTER", phone);
        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","challengeId":"%s","verificationCode":"%s",
                                 "termsAccepted":true}
                                """.formatted(phone, challenge.id(), challenge.code())))
                .andExpect(status().isAccepted());

        insertUser("claimed-during-setup@example.com", phone);
        mockMvc.perform(post("/console/platform/auth/identity/complete-registration")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","newPassword":"%s","termsAccepted":true}
                                """.formatted(challenge.id(), PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("phone_unavailable"));
        assertThat(jdbc.queryForObject(
                "SELECT consumed_at IS NULL FROM platform.sms_verification_challenge WHERE id = ?",
                Boolean.class, challenge.id())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.app_user WHERE phone_last4 = '4444'",
                Integer.class)).isOne();
    }

    @Test
    void passwordLoginAcceptsRegisteredPhoneAndRejectsPendingRegistration() throws Exception {
        insertUser("phone-password@example.com", "15012348888");
        mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"15012348888","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("phone-password@example.com"));

        Challenge challenge = issue("/console/platform/auth/sms/challenges", null,
                "REGISTER", "15012347777");
        mockMvc.perform(post("/console/platform/auth/identity")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"15012347777","challengeId":"%s","verificationCode":"%s",
                                 "termsAccepted":true}
                                """.formatted(challenge.id(), challenge.code())))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"identifier\":\"15012347777\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationRejectsPhoneMismatchAndRollsBackChallengeOnUniquenessConflict() throws Exception {
        Challenge mismatch = issue("/console/platform/auth/sms/challenges", null,
                "REGISTER", "15112341234");
        mockMvc.perform(post("/console/platform/auth/register")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(
                                "mismatch@example.com", "15212341234", mismatch.id(), mismatch.code())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_sms_challenge"));
        assertChallengeUnused(mismatch.id());

        UserFixture owner = insertUser("owner@example.com", "15312341234");
        Challenge duplicate = issue("/console/platform/auth/sms/challenges", null,
                "REGISTER", "15312341234");
        mockMvc.perform(post("/console/platform/auth/register")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(
                                "duplicate@example.com", "15312341234", duplicate.id(), duplicate.code())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("phone_unavailable"));
        assertChallengeUnused(duplicate.id());
        assertThat(owner.userId()).isNotNull();
    }

    @Test
    void profileExposesOnlyMaskedPhoneAndPreference() throws Exception {
        UserFixture user = insertUser("profile@example.com", "15098761234");

        mockMvc.perform(get("/console/platform/profile")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedPhone").value("150****1234"))
                .andExpect(jsonPath("$.phoneVerified").value(true))
                .andExpect(jsonPath("$.phoneState").value("VERIFIED"))
                .andExpect(jsonPath("$.smsNotificationsEnabled").value(true))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.phoneCiphertext").doesNotExist());
    }

    @Test
    void userCanUpdateTheDisplayNameShownByTheProfile() throws Exception {
        UserFixture user = insertUser("rename@example.com", "15098760001");

        mockMvc.perform(patch("/console/platform/profile/display-name")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"宇智用户\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("宇智用户"));

        mockMvc.perform(get("/console/platform/profile")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("宇智用户"));

        mockMvc.perform(patch("/console/platform/profile/display-name")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action = 'DISPLAY_NAME_CHANGED'
                """, Integer.class, user.userId())).isEqualTo(1);
    }

    @Test
    void profileFallsBackToStoredLastFourWhenPhoneCryptoIsUnavailableOrCiphertextIsCorrupt()
            throws Exception {
        UserFixture user = insertUser("fallback-profile@example.com", "15098761234");
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        IdentityService disabledIdentity = new IdentityService(
                IdentityRepositories.forJdbc(jdbc), passwords, jwtTokens,
                empty.getBeanProvider(SmsChallengeService.class),
                empty.getBeanProvider(SmsProofService.class),
                empty.getBeanProvider(PhoneProtectionService.class),
                WalletServiceTestFactory.account(jdbc));

        IdentityService.ProfileView disabledProfile = disabledIdentity.profile(user.tenantId(), user.userId());
        assertThat(disabledProfile.maskedPhone()).isEqualTo("***-****-1234");
        assertThat(disabledProfile.phoneVerified()).isTrue();
        assertThat(disabledProfile.phoneState()).isEqualTo("VERIFIED");

        jdbc.update("UPDATE platform.app_user SET phone_ciphertext = ? WHERE id = ?",
                new byte[] {1, 2, 3}, user.userId());
        mockMvc.perform(get("/console/platform/profile")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedPhone").value("***-****-1234"))
                .andExpect(jsonPath("$.phoneVerified").value(true))
                .andExpect(jsonPath("$.phoneState").value("VERIFIED"));
    }

    @Test
    void userCanBindThenChangePhoneAndUpdateBusinessPreference() throws Exception {
        UserFixture user = insertUser("bind@example.com", null);
        Challenge bind = issue("/console/platform/profile/phone/challenges", user,
                "PHONE_BIND", "15412341234");
        verify(user, bind);

        mockMvc.perform(put("/console/platform/profile/phone")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + bind.id() + "\",\"purpose\":\"PHONE_BIND\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/console/platform/profile/phone")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + bind.id() + "\",\"purpose\":\"PHONE_BIND\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_phone_challenge"));

        Challenge change = issue("/console/platform/profile/phone/challenges", user,
                "PHONE_CHANGE", "15512341234");
        verify(user, change);
        mockMvc.perform(put("/console/platform/profile/phone")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + change.id()
                                + "\",\"purpose\":\"PHONE_CHANGE\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("current_phone_verification_required"));

        Challenge currentPhone = issue("/console/platform/sms/challenges", user,
                "PHONE_CHANGE_CONFIRM", null);
        String currentPhoneProof = verify(user, currentPhone).at("/smsProof").asText();
        mockMvc.perform(put("/console/platform/profile/phone")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + change.id()
                                + "\",\"purpose\":\"PHONE_CHANGE\",\"currentPhoneProof\":\""
                                + currentPhoneProof + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/console/platform/profile/sms-preference")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/console/platform/profile")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedPhone").value("155****1234"))
                .andExpect(jsonPath("$.smsNotificationsEnabled").value(false));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action IN ('PHONE_BOUND', 'PHONE_CHANGED', 'SMS_PREFERENCE_CHANGED')
                """, Integer.class, user.userId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT detail::text FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action = 'PHONE_CHANGED'
                """, String.class, user.userId())).contains("155****1234").doesNotContain("15512341234");
    }

    @Test
    void phoneBindingEnforcesGlobalUniquenessWithoutRevealingOwner() throws Exception {
        insertUser("existing-phone@example.com", "15912341234");
        UserFixture binder = insertUser("binder@example.com", null);
        Challenge duplicate = issue("/console/platform/profile/phone/challenges", binder,
                "PHONE_BIND", "15912341234");
        verify(binder, duplicate);

        mockMvc.perform(put("/console/platform/profile/phone")
                        .header("Authorization", bearer(binder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + duplicate.id()
                                + "\",\"purpose\":\"PHONE_BIND\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("phone_unavailable"))
                .andExpect(jsonPath("$.owner").doesNotExist());
        assertThat(jdbc.queryForObject("""
                SELECT consumed_at IS NULL FROM platform.sms_verification_challenge WHERE id = ?
                """, Boolean.class, duplicate.id())).isTrue();
    }

    @Test
    void authenticatedProofChallengeUsesBoundPhoneAndCannotBeBurnedByAnotherUser() throws Exception {
        UserFixture owner = insertUser("proof-owner@example.com", "15612341234");
        UserFixture attacker = insertUser("proof-attacker@example.com", "15712341234");
        Challenge challenge = issue("/console/platform/sms/challenges", owner,
                "CHANGE_PASSWORD", null);

        mockMvc.perform(post("/console/platform/sms/challenges/" + challenge.id() + "/verify")
                        .header("Authorization", bearer(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_sms_challenge"));
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM platform.sms_verification_challenge WHERE id = ?
                """, Integer.class, challenge.id())).isZero();

        mockMvc.perform(post("/console/platform/sms/challenges/" + challenge.id() + "/verify")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + challenge.code() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.smsProof").isString())
                .andExpect(jsonPath("$.purpose").value("CHANGE_PASSWORD"))
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.maskedPhone").doesNotExist());
    }

    @Test
    void passwordProofIsRequiredOnlyForBoundUsersAndRollsBackOnWrongPassword() throws Exception {
        UserFixture bound = insertUser("bound@example.com", "15812341234");
        Challenge challenge = issue("/console/platform/sms/challenges", bound,
                "CHANGE_PASSWORD", null);
        String proof = verify(bound, challenge).at("/smsProof").asText();

        mockMvc.perform(post("/console/platform/profile/password")
                        .header("Authorization", bearer(bound))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson(PASSWORD, "ChangedPass456!", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("sms_verification_required"));
        mockMvc.perform(post("/console/platform/profile/password")
                        .header("Authorization", bearer(bound))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("wrong", "ChangedPass456!", proof)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("""
                SELECT consumed_at IS NULL FROM platform.sms_proof WHERE token_hash = ?
                """, Boolean.class, sha256(proof))).isTrue();
        mockMvc.perform(post("/console/platform/profile/password")
                        .header("Authorization", bearer(bound))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson(PASSWORD, "ChangedPass456!", proof)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/console/platform/profile/password")
                        .header("Authorization", "Bearer " + jwtTokens.createAccessToken(
                                new IdentityService.UserProfile(bound.userId(), bound.tenantId(),
                                        bound.email(), "SMS User", "USER", 1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("ChangedPass456!", "ThirdPass789!", proof)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_sms_proof"));

        UserFixture legacy = insertUser("legacy@example.com", null);
        mockMvc.perform(post("/console/platform/profile/password")
                        .header("Authorization", bearer(legacy))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson(PASSWORD, "LegacyPass456!", null)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"legacy@example.com\",\"password\":\"LegacyPass456!\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void passwordAndProofRollBackWhenTheFinalAuditWriteFails() throws Exception {
        UserFixture bound = insertUser("late-failure@example.com", "15812341234");
        Challenge challenge = issue("/console/platform/sms/challenges", bound,
                "CHANGE_PASSWORD", null);
        String proof = verify(bound, challenge).at("/smsProof").asText();
        String originalHash = jdbc.queryForObject(
                "SELECT password_hash FROM platform.app_user WHERE id = ?", String.class, bound.userId());

        jdbc.execute("""
                CREATE FUNCTION platform.fail_password_changed_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.action = 'PASSWORD_CHANGED' THEN
                        RAISE EXCEPTION 'forced password audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_fail_password_changed_audit
                BEFORE INSERT ON platform.admin_audit_log
                FOR EACH ROW EXECUTE FUNCTION platform.fail_password_changed_audit()
                """);
        try {
            assertThatThrownBy(() -> mockMvc.perform(post("/console/platform/profile/password")
                            .header("Authorization", bearer(bound))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(passwordJson(PASSWORD, "ChangedPass456!", proof))))
                    .hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(jdbc.queryForObject(
                    "SELECT password_hash FROM platform.app_user WHERE id = ?", String.class, bound.userId()))
                    .isEqualTo(originalHash);
            assertThat(jdbc.queryForObject(
                    "SELECT consumed_at IS NULL FROM platform.sms_proof WHERE token_hash = ?",
                    Boolean.class, sha256(proof))).isTrue();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_password_changed_audit ON platform.admin_audit_log");
            jdbc.execute("DROP FUNCTION IF EXISTS platform.fail_password_changed_audit()");
        }

        mockMvc.perform(post("/console/platform/profile/password")
                        .header("Authorization", bearer(bound))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson(PASSWORD, "ChangedPass456!", proof)))
                .andExpect(status().isNoContent());
    }

    @Test
    void phonePasswordResetUsesPasswordTemplateAndConsumesTheChallenge() throws Exception {
        UserFixture user = insertUser("password-reset@example.com", "15012345672");
        MvcResult issued = mockMvc.perform(post("/console/platform/auth/password-reset/sms/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"15012345672\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purpose").value("PASSWORD_RESET"))
                .andReturn();
        UUID challengeId = UUID.fromString(json(issued).at("/id").asText());
        String code = deliveredCode(challengeId);

        mockMvc.perform(post("/console/platform/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"15012345672","challengeId":"%s",
                                 "verificationCode":"%s","newPassword":"ResetPass456!"}
                                """.formatted(challengeId, code)))
                .andExpect(status().isNoContent());

        Map<String, Object> account = jdbc.queryForMap("""
                SELECT password_hash, auth_version FROM platform.app_user WHERE id = ?
                """, user.userId());
        assertThat(passwords.matches("ResetPass456!", (String) account.get("password_hash"))).isTrue();
        assertThat(((Number) account.get("auth_version")).longValue()).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT consumed_at IS NOT NULL FROM platform.sms_verification_challenge WHERE id = ?
                """, Boolean.class, challengeId)).isTrue();
    }

    @Test
    void smsLoginIssuesNormalSessionConsumesCodeAndPreservesPasswordLogin() throws Exception {
        UserFixture user = insertUser("sms-login@example.com", "15012345671");
        MvcResult issued = issueLoginChallenge("15012345671");
        UUID challengeId = UUID.fromString(json(issued).at("/id").asText());
        String code = deliveredCode(challengeId);

        MvcResult loggedIn = mockMvc.perform(post("/console/platform/auth/sms-login")
                        .header("X-Device-Id", DEVICE_ID)
                        .header("User-Agent", "SMS Test Browser/1.0")
                        .header("X-Forwarded-For", "198.51.100.22")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","code":"%s"}
                                """.formatted(challengeId, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.user.id").value(user.userId().toString()))
                .andExpect(jsonPath("$.user.email").value(user.email()))
                .andReturn();

        assertThat(loggedIn.getResponse().getHeaders("Set-Cookie"))
                .anyMatch(cookie -> cookie.startsWith("yzh_refresh="))
                .anyMatch(cookie -> cookie.startsWith("yzh_csrf="));
        assertThat(jdbc.queryForObject("""
                SELECT last_login_at IS NOT NULL FROM platform.app_user WHERE id = ?
                """, Boolean.class, user.userId())).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT consumed_at IS NOT NULL FROM platform.sms_verification_challenge WHERE id = ?
                """, Boolean.class, challengeId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.login_activity WHERE user_id = ?",
                Integer.class, user.userId())).isOne();

        mockMvc.perform(post("/console/platform/auth/sms-login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","code":"%s"}
                                """.formatted(challengeId, code)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/console/platform/auth/login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"sms-login@example.com","password":"StrongPass123!"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void logoutAfterSmsLoginAllowsAnImmediateNewLoginCode() throws Exception {
        String phone = "15012345672";
        insertUser("sms-login-resend@example.com", phone);
        MvcResult issued = issueLoginChallenge(phone);
        UUID challengeId = UUID.fromString(json(issued).at("/id").asText());

        MvcResult loggedIn = mockMvc.perform(post("/console/platform/auth/sms-login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","code":"%s"}
                                """.formatted(challengeId, deliveredCode(challengeId))))
                .andExpect(status().isOk())
                .andReturn();
        MockCookie refreshCookie = new MockCookie(
                "yzh_refresh", loggedIn.getResponse().getCookie("yzh_refresh").getValue());
        MockCookie csrfCookie = new MockCookie(
                "yzh_csrf", loggedIn.getResponse().getCookie("yzh_csrf").getValue());

        mockMvc.perform(post("/console/platform/auth/logout")
                        .cookie(refreshCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfCookie.getValue()))
                .andExpect(status().isNoContent());
        issueLoginChallenge(phone);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.sms_verification_challenge
                WHERE purpose = 'LOGIN'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void smsLoginRollsBackChallengeAndLastLoginWhenRefreshSessionCreationFails() throws Exception {
        UserFixture user = insertUser("sms-login-rollback@example.com", "15412345671");
        MvcResult issued = issueLoginChallenge("15412345671");
        UUID challengeId = UUID.fromString(json(issued).at("/id").asText());
        String code = deliveredCode(challengeId);

        jdbc.execute("""
                CREATE FUNCTION platform.fail_sms_login_refresh()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'forced refresh token failure';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_fail_sms_login_refresh
                BEFORE INSERT ON platform.refresh_token
                FOR EACH ROW EXECUTE FUNCTION platform.fail_sms_login_refresh()
                """);
        try {
            mockMvc.perform(post("/console/platform/auth/sms-login")
                            .header("X-Device-Id", DEVICE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"challengeId":"%s","code":"%s"}
                                    """.formatted(challengeId, code)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("identity_session_unavailable"));
            assertThat(jdbc.queryForObject("""
                    SELECT verified_at IS NULL AND consumed_at IS NULL
                    FROM platform.sms_verification_challenge WHERE id = ?
                    """, Boolean.class, challengeId)).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT last_login_at IS NULL FROM platform.app_user WHERE id = ?
                    """, Boolean.class, user.userId())).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM platform.refresh_token WHERE user_id = ?
                    """, Integer.class, user.userId())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_sms_login_refresh ON platform.refresh_token");
            jdbc.execute("DROP FUNCTION IF EXISTS platform.fail_sms_login_refresh()");
        }

        mockMvc.perform(post("/console/platform/auth/sms-login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","code":"%s"}
                                """.formatted(challengeId, code)))
                .andExpect(status().isOk());
    }

    @Test
    void smsLoginChallengeAllowsDisabledAccountButRejectsUnknownOrUnboundAccounts() throws Exception {
        insertUser("verified@example.com", "15112345671");
        insertUser("unbound@example.com", null);
        UserFixture disabled = insertUser("disabled@example.com", "15312345671");
        jdbc.update("UPDATE platform.app_user SET status = 'DISABLED' WHERE id = ?", disabled.userId());

        MvcResult disabledChallenge = issueLoginChallenge("15312345671");
        UUID disabledChallengeId = UUID.fromString(json(disabledChallenge).at("/id").asText());
        mockMvc.perform(post("/console/platform/auth/sms-login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","code":"%s"}
                                """.formatted(disabledChallengeId, deliveredCode(disabledChallengeId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.status").value("DISABLED"));

        for (String phone : java.util.List.of(
                "15412345671", "15212345671",
                "15412345672", "15412345673", "15412345674")) {
            mockMvc.perform(post("/console/platform/auth/sms-login/challenges")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + phone + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("sms_login_unavailable"))
                    .andExpect(jsonPath("$.reason").value("sms_login_unavailable"));
        }
        issueLoginChallenge("15112345671");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.sms_verification_challenge
                WHERE purpose = 'LOGIN'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.sms_delivery WHERE template_key = 'LOGIN_CODE'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void smsLoginChallengeAllowsFiveQueuedCodesPerIpMinuteThenRejectsTheSixth() throws Exception {
        java.util.List<String> phones = java.util.List.of(
                "15012345671", "15012345672", "15012345673",
                "15012345674", "15012345675", "15012345676");
        for (int index = 0; index < phones.size(); index++) {
            insertUser("rate-limit-" + index + "@example.com", phones.get(index));
        }

        for (int index = 0; index < 5; index++) {
            mockMvc.perform(post("/console/platform/auth/sms-login/challenges")
                            .header("X-Forwarded-For", "203.0.113.80")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + phones.get(index) + "\"}"))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/console/platform/auth/sms-login/challenges")
                        .header("X-Forwarded-For", "203.0.113.80")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phones.get(5) + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("sms_rate_limited_ip_minute"))
                .andExpect(jsonPath("$.reason").value("sms_rate_limited_ip_minute"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.sms_verification_challenge
                WHERE purpose = 'LOGIN'
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.sms_delivery WHERE template_key = 'LOGIN_CODE'
                """, Integer.class)).isEqualTo(5);
    }

    @Test
    void registrationAndLoginHaveIndependentFiveSendIpMinuteAllowances() throws Exception {
        String clientIp = "203.0.113.81";
        for (int attempt = 0; attempt < 6; attempt++) {
            mockMvc.perform(post("/console/platform/auth/sms/challenges")
                            .header("X-Forwarded-For", clientIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"purpose\":\"REGISTER\",\"phone\":\"invalid-"
                                    + attempt + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("invalid_mainland_phone"));
        }

        java.util.List<String> registrationPhones = java.util.List.of(
                "15912345671", "15912345672", "15912345673",
                "15912345674", "15912345675", "15912345676");
        for (int index = 0; index < 5; index++) {
            mockMvc.perform(post("/console/platform/auth/sms/challenges")
                            .header("X-Forwarded-For", clientIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"purpose\":\"REGISTER\",\"phone\":\""
                                    + registrationPhones.get(index) + "\"}"))
                    .andExpect(status().isCreated());
        }

        insertUser("independent-login-limit@example.com", "15112345672");
        mockMvc.perform(post("/console/platform/auth/sms-login/challenges")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"15112345672\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/console/platform/auth/sms/challenges")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"REGISTER\",\"phone\":\""
                                + registrationPhones.get(5) + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("sms_rate_limited_ip_minute"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.sms_verification_challenge
                WHERE purpose = 'REGISTER'
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.sms_verification_challenge
                WHERE purpose = 'LOGIN'
                """, Integer.class)).isOne();
    }

    @Test
    void samePhoneHasIndependentHourlyAllowancesForRegistrationAndLogin() throws Exception {
        String phone = "15112345673";
        insertUser("purpose-rate-limit@example.com", phone);

        for (int attempt = 0; attempt < 5; attempt++) {
            Challenge challenge = issue(
                    "/console/platform/auth/sms/challenges", null, "REGISTER", phone);
            jdbc.update("""
                    UPDATE platform.sms_verification_challenge
                    SET created_at = created_at - interval '2 minutes',
                        expires_at = expires_at - interval '2 minutes'
                    WHERE id = ?
                    """, challenge.id());
        }

        issueLoginChallenge(phone);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.sms_verification_challenge
                WHERE phone_last4 = '5673' AND purpose = 'REGISTER'
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.sms_verification_challenge
                WHERE phone_last4 = '5673' AND purpose = 'LOGIN'
                """, Integer.class)).isOne();
    }

    @Test
    void smsLoginUsesGenericUnauthorizedForWrongCodeThenAllowsTheCorrectCode() throws Exception {
        insertUser("retry@example.com", "15512345671");
        MvcResult issued = issueLoginChallenge("15512345671");
        UUID challengeId = UUID.fromString(json(issued).at("/id").asText());

        mockMvc.perform(post("/console/platform/auth/sms-login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","code":"000000"}
                                """.formatted(challengeId)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM platform.sms_verification_challenge WHERE id = ?
                """, Integer.class, challengeId)).isOne();
        mockMvc.perform(post("/console/platform/auth/sms-login")
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","code":"%s"}
                                """.formatted(challengeId, deliveredCode(challengeId))))
                .andExpect(status().isOk());
    }

    @Test
    void smsLoginLocksTheAccountUntilConcurrentPhoneOrStatusChangesAreVisible() throws Exception {
        assertConcurrentAccountMutationLoginOutcome(
                "concurrent-disable@example.com", "15612345671",
                "UPDATE platform.app_user SET status = 'DISABLED' WHERE id = ?",
                200, "DISABLED");
        assertConcurrentAccountMutationLoginOutcome(
                "concurrent-phone@example.com", "15712345671",
                "UPDATE platform.app_user SET phone_lookup_hash = repeat('f', 64) WHERE id = ?",
                401, null);
    }

    @Test
    void smsControllerRejectsMissingOrMalformedIdentityClaimsWithUnauthorized() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("smsChallenges", smsChallenges);
        SmsController controller = new SmsController(
                beans.getBeanProvider(SmsChallengeService.class),
                new SmsRequestFingerprintService(
                        Base64.getEncoder().encodeToString("3".repeat(32)
                                .getBytes(StandardCharsets.US_ASCII)), true),
                noOpChallengeIpRateLimiter());
        UUID valid = UUID.randomUUID();
        for (Jwt jwt : java.util.List.of(
                jwt(null, valid.toString()), jwt("not-a-uuid", valid.toString()),
                jwt(valid.toString(), null), jwt(valid.toString(), "not-a-uuid"))) {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> controller.verify(
                    jwt, UUID.randomUUID(), new SmsController.VerifyRequest("000000"))))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        }
    }

    @Test
    void absentSmsBeansFailClosedWithoutWeakeningRegistrationRequirement() throws Exception {
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        SmsController disabledController = new SmsController(
                empty.getBeanProvider(SmsChallengeService.class),
                new SmsRequestFingerprintService("", false),
                noOpChallengeIpRateLimiter());
        MockMvc disabledMvc = MockMvcBuilders.standaloneSetup(disabledController)
                .setControllerAdvice(new SmsApiExceptionHandler())
                .build();

        disabledMvc.perform(post("/console/platform/auth/sms/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"REGISTER\",\"phone\":\"15012341234\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("sms_disabled"));

        IdentityService disabledIdentity = new IdentityService(
                IdentityRepositories.forJdbc(jdbc), passwords, jwtTokens,
                empty.getBeanProvider(SmsChallengeService.class),
                empty.getBeanProvider(SmsProofService.class),
                empty.getBeanProvider(PhoneProtectionService.class),
                WalletServiceTestFactory.account(jdbc));
        assertThatThrownBy(() -> disabledIdentity.register(
                "disabled@example.com", PASSWORD, "Disabled",
                "15012341234", UUID.randomUUID(), "123456"))
                .isInstanceOfSatisfying(SmsApiException.class,
                        error -> assertThat(error.code()).isEqualTo("sms_disabled"));
        assertThatThrownBy(() -> disabledIdentity.register(
                "disabled@example.com", PASSWORD, "Disabled",
                null, null, null))
                .isInstanceOfSatisfying(SmsApiException.class,
                        error -> assertThat(error.code()).isEqualTo("sms_verification_required"));
    }

    private Challenge issue(String path, UserFixture user, String purpose, String phone) throws Exception {
        String body = phone == null
                ? "{\"purpose\":\"" + purpose + "\"}"
                : "{\"purpose\":\"" + purpose + "\",\"phone\":\"" + phone + "\"}";
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (user != null) request.header("Authorization", bearer(user));
        MvcResult issued = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(json(issued).at("/id").asText());
        return new Challenge(id, deliveredCode(id));
    }

    private MvcResult issueLoginChallenge(String phone) throws Exception {
        return mockMvc.perform(post("/console/platform/auth/sms-login/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.purpose").value("LOGIN"))
                .andExpect(jsonPath("$.maskedPhone").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andReturn();
    }

    private void assertConcurrentAccountMutationLoginOutcome(
            String email, String phone, String mutationSql,
            int expectedStatus, String expectedAccountStatus) throws Exception {
        UserFixture user = insertUser(email, phone);
        MvcResult issued = issueLoginChallenge(phone);
        UUID challengeId = UUID.fromString(json(issued).at("/id").asText());
        String code = deliveredCode(challengeId);
        CompletableFuture<MvcResult> login = null;
        boolean committed = false;

        try (Connection mutation = dataSource.getConnection();
                PreparedStatement update = mutation.prepareStatement(mutationSql)) {
            mutation.setAutoCommit(false);
            update.setObject(1, user.userId());
            assertThat(update.executeUpdate()).isOne();
            int blockerPid;
            try (var statement = mutation.createStatement();
                    var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                assertThat(result.next()).isTrue();
                blockerPid = result.getInt(1);
            }

            login = CompletableFuture.supplyAsync(() -> {
                try {
                    return mockMvc.perform(post("/console/platform/auth/sms-login")
                                    .header("X-Device-Id", DEVICE_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"challengeId":"%s","code":"%s"}
                                            """.formatted(challengeId, code)))
                            .andReturn();
                } catch (Exception error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            });

            assertThat(waitUntilBlockedBy(blockerPid, login))
                    .as("SMS login must wait for the concurrent app_user mutation")
                    .isTrue();
            mutation.commit();
            committed = true;

            MvcResult result = login.get(10, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
            if (expectedAccountStatus != null) {
                assertThat(json(result).at("/user/status").asText()).isEqualTo(expectedAccountStatus);
            }
        } finally {
            if (!committed && login != null) {
                login.cancel(true);
            }
        }
    }

    private boolean waitUntilBlockedBy(int blockerPid, CompletableFuture<?> operation) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline) && !operation.isDone()) {
            Boolean blocked = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity activity
                        WHERE ? = ANY(pg_blocking_pids(activity.pid))
                    )
                    """, Boolean.class, blockerPid);
            if (Boolean.TRUE.equals(blocked)) return true;
            Thread.sleep(25);
        }
        return false;
    }

    private JsonNode verify(UserFixture user, Challenge challenge) throws Exception {
        MvcResult verified = mockMvc.perform(post(
                        "/console/platform/sms/challenges/" + challenge.id() + "/verify")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + challenge.code() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return json(verified);
    }

    private String deliveredCode(UUID challengeId) throws Exception {
        byte[] ciphertext = jdbc.queryForObject("""
                SELECT sensitive_variables_ciphertext FROM platform.sms_delivery
                WHERE deduplication_key = ?
                """, byte[].class, "verification:" + challengeId);
        return objectMapper.readTree(smsEncryption.decrypt(ciphertext)).get("code").asText();
    }

    private SmsChallengeIpRateLimiter noOpChallengeIpRateLimiter() {
        return new SmsChallengeIpRateLimiter() {
            @Override
            public Reservation reserve(Scope scope, String requestIpHash) {
                return new Reservation("test:sms-login-rate-limit");
            }

            @Override
            public void release(Reservation reservation) {
            }
        };
    }

    private UserFixture insertUser(String email, String phone) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, ?, 'SMS User', 'ACTIVE')
                """, userId, userId, email, passwords.encode(PASSWORD));
        jdbc.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                SELECT ?, ?, id FROM platform.app_role WHERE code = 'USER'
                """, userId, userId);
        if (phone != null) storeVerifiedPhone(userId, phone);
        return new UserFixture(userId, userId, email);
    }

    private void storeVerifiedPhone(UUID userId, String phone) {
        byte[] normalized = ("+86" + phone).getBytes(StandardCharsets.UTF_8);
        String lookup = hmac(normalized, Base64.getDecoder().decode(
                "MTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTE="));
        jdbc.update("""
                UPDATE platform.app_user
                SET phone_ciphertext = ?, phone_lookup_hash = ?, phone_last4 = ?,
                    phone_verified_at = now()
                WHERE id = ?
                """, smsEncryption.encrypt(normalized), lookup, phone.substring(7), userId);
    }

    private void assertChallengeUnused(UUID id) {
        assertThat(jdbc.queryForObject("""
                SELECT verified_at IS NULL AND consumed_at IS NULL
                FROM platform.sms_verification_challenge WHERE id = ?
                """, Boolean.class, id)).isTrue();
    }

    private String registerJson(String email, String phone, UUID challengeId, String code) {
        return """
                {"email":"%s","password":"%s","displayName":"SMS User",
                 "phone":"%s","challengeId":%s,"verificationCode":%s,"termsAccepted":true}
                """.formatted(email, PASSWORD, phone,
                challengeId == null ? "null" : "\"" + challengeId + "\"",
                code == null ? "null" : "\"" + code + "\"");
    }

    private String wrongCode(Challenge challenge) {
        return "000000".equals(challenge.code()) ? "111111" : "000000";
    }

    private String passwordJson(String current, String replacement, String proof) {
        return """
                {"currentPassword":"%s","newPassword":"%s","smsProof":%s}
                """.formatted(current, replacement, proof == null ? "null" : "\"" + proof + "\"");
    }

    private String bearer(UserFixture user) {
        return "Bearer " + jwtTokens.createAccessToken(new IdentityService.UserProfile(
                user.userId(), user.tenantId(), user.email(), "SMS User", "USER"));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String hmac(byte[] value, byte[] key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
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

    private record Challenge(UUID id, String code) { }

    private record UserFixture(UUID userId, UUID tenantId, String email) { }
}
