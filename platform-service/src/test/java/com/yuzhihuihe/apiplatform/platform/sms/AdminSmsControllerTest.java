package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "spring.task.scheduling.enabled=false",
        "reconciliation.job-enabled=false",
        "sms.enabled=true",
        "sms.provider=mock",
        "sms.security.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "sms.security.phone-lookup-key=MTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTE=",
        "sms.security.code-pepper=MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjI=",
        "sms.security.request-ip-key=MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjI="
})
@AutoConfigureMockMvc
class AdminSmsControllerTest {

    private static final UUID ADMIN_ID =
            UUID.fromString("78000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("78000000-0000-4000-8000-000000000002");
    private static final UUID TENANT_ID =
            UUID.fromString("78000000-0000-4000-8000-000000000003");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdminSmsService adminSmsService;

    @BeforeEach
    void reset() {
        jdbc.update("TRUNCATE platform.admin_audit_log, platform.sms_delivery, "
                + "platform.refresh_token, platform.user_role, platform.app_user "
                + "RESTART IDENTITY CASCADE");
        insertUser(ADMIN_ID, "admin@example.invalid");
        insertUser(USER_ID, "user@example.invalid");
    }

    @Test
    void returnsRegistryAndIndexedExactDeliveryFiltersWithoutSensitivePayloads() throws Exception {
        UUID first = UUID.fromString("78000000-0000-4000-8000-000000000011");
        UUID second = UUID.fromString("78000000-0000-4000-8000-000000000012");
        UUID third = UUID.fromString("78000000-0000-4000-8000-000000000013");
        insertDelivery(first, "ORDER_PAYMENT_FAILED", "FAILED", "8963",
                "order:matching:first", Instant.parse("2026-08-04T00:00:00Z"));
        insertDelivery(second, "ORDER_PAYMENT_FAILED", "FAILED", "4321",
                "order:matching:second", Instant.parse("2026-08-04T00:01:00Z"));
        insertDelivery(third, "PACKAGE_EXPIRED", "DEAD", "0000",
                "package:not-matching", Instant.parse("2026-08-04T00:02:00Z"));

        MvcResult templatesResult = mockMvc.perform(get("/console/platform/admin/sms/templates")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                // 28 = 24 个既有模板 + V72 为连续包月新增的 4 个
                // （SUBSCRIPTION_RENEWAL_DUE / RENEWED / RENEWAL_FAILED / PRICE_CHANGED）。
                // 本条在加入 V72 之前期望 24 并通过；新增模板后必须同步更新，
                // 精确条数会让任何后续模板增删都被显式看到。
                .andExpect(jsonPath("$.length()").value(28))
                .andExpect(jsonPath("$[?(@.templateKey == 'REGISTER_CODE')].integrationStatus")
                        .value("ACTIVE"))
                .andExpect(jsonPath("$[?(@.templateKey == 'LOGIN_CODE')].integrationStatus")
                        .value("ACTIVE"))
                // 订阅的 4 个模板此刻必须是 RESERVED：阿里云审核通过前不得发出短信，
                // 而扣款前通知是合规要求（见 docs/testing/subscription-renewal-acceptance.md §3）。
                .andExpect(jsonPath("$[?(@.templateKey == 'SUBSCRIPTION_RENEWAL_DUE')].integrationStatus")
                        .value("RESERVED"))
                .andExpect(jsonPath("$[?(@.templateKey == 'SUBSCRIPTION_RENEWED')].integrationStatus")
                        .value("RESERVED"))
                .andExpect(jsonPath("$[?(@.templateKey == 'SUBSCRIPTION_RENEWAL_FAILED')].integrationStatus")
                        .value("RESERVED"))
                .andExpect(jsonPath("$[?(@.templateKey == 'SUBSCRIPTION_PRICE_CHANGED')].integrationStatus")
                        .value("RESERVED"))
                .andReturn();

        MvcResult deliveryResult = mockMvc.perform(get("/console/platform/admin/sms/deliveries")
                        .param("page", "0")
                        .param("size", "1")
                        .param("query", second.toString())
                        .param("status", "FAILED")
                        .param("templateKey", "ORDER_PAYMENT_FAILED")
                        .param("sort", "createdAt")
                        .param("direction", "asc")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(second.toString()))
                .andExpect(jsonPath("$.items[0].templateKey").value("ORDER_PAYMENT_FAILED"))
                .andExpect(jsonPath("$.items[0].maskedPhone").value("*******4321"))
                .andExpect(jsonPath("$.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.items[0].sanitizedError")
                        .value("TRANSIENT:PROVIDER_NETWORK_ERROR"))
                .andExpect(jsonPath("$.items[0].phoneCiphertext").doesNotExist())
                .andExpect(jsonPath("$.items[0].phone").doesNotExist())
                .andExpect(jsonPath("$.items[0].variables").doesNotExist())
                .andExpect(jsonPath("$.items[0].sensitiveVariablesCiphertext").doesNotExist())
                .andReturn();

        mockMvc.perform(get("/console/platform/admin/sms/deliveries")
                        .param("query", "8963")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()));
        mockMvc.perform(get("/console/platform/admin/sms/deliveries")
                        .param("query", "package_expired")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(third.toString()));
        mockMvc.perform(get("/console/platform/admin/sms/deliveries")
                        .param("query", "ORDER_PAYMENT")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/console/platform/admin/sms/deliveries")
                        .param("query", "order:matching")
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());

        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'platform' AND tablename = 'sms_delivery'
                """, String.class)).contains(
                        "ix_sms_delivery_phone_last4_created",
                        "ix_sms_delivery_template_key_created");

        String json = templatesResult.getResponse().getContentAsString()
                + deliveryResult.getResponse().getContentAsString();
        assertThat(json)
                .doesNotContain("must-not-render", "654321", "smsProof", "accessKeySecret",
                        "phoneCiphertext", "sensitiveVariablesCiphertext");
    }

    @Test
    void preservesMutableAdminQueryLists() {
        var templates = adminSmsService.listTemplates();
        templates.clear();
        assertThat(templates).isEmpty();

        var deliveries = adminSmsService.listDeliveries(
                new AdminSmsService.DeliveryQuery(0, 20, null, null, null, null, null));
        deliveries.items().clear();
        assertThat(deliveries.items()).isEmpty();
    }

    @Test
    void adminQueuesTemplateTestWithoutReturningPlainPhone() throws Exception {
        mockMvc.perform(post("/console/platform/admin/sms/templates/{key}/test-deliveries",
                        "SUPPORT_TICKET_COMPLETED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"15089118963",
                                 "variables":{"ticketNo":"TK-SMS-TEST-20260809"},
                                 "businessNo":"sms-template-test-http-20260809"}
                                """)
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateKey").value("SUPPORT_TICKET_COMPLETED"))
                .andExpect(jsonPath("$.maskedPhone").value("*******8963"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.variables").doesNotExist());
    }

    @Test
    void normalUserCannotQueueTemplateTest() throws Exception {
        mockMvc.perform(post("/console/platform/admin/sms/templates/{key}/test-deliveries",
                        "SUPPORT_TICKET_COMPLETED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"15089118963",
                                 "variables":{"ticketNo":"TK-SMS-TEST-20260809"},
                                 "businessNo":"sms-template-test-forbidden-20260809"}
                                """)
                        .with(userJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidTemplateTestRequestsReturnUnprocessableEntity() throws Exception {
        mockMvc.perform(post("/console/platform/admin/sms/templates/{key}/test-deliveries",
                        "SUPPORT_TICKET_COMPLETED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"123",
                                 "variables":{"ticketNo":"TK-SMS-TEST-20260809"},
                                 "businessNo":"sms-template-test-invalid-phone-20260809"}
                                """)
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/console/platform/admin/sms/templates/{key}/test-deliveries",
                        "GENERIC_VERIFICATION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"15089118963",
                                 "variables":{"code":"123456"},
                                 "businessNo":"sms-template-test-reserved-http-20260809"}
                                """)
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void retriesFailedAndDeadDeliveriesOncePerBusinessNumberAndAuditsOnce() throws Exception {
        UUID failed = UUID.fromString("78000000-0000-4000-8000-000000000021");
        UUID dead = UUID.fromString("78000000-0000-4000-8000-000000000022");
        insertDelivery(failed, "ORDER_PAYMENT_FAILED", "FAILED", "8963",
                "retry:failed", Instant.parse("2026-08-04T00:00:00Z"));
        insertDelivery(dead, "PACKAGE_EXPIRED", "DEAD", "4321",
                "retry:dead", Instant.parse("2026-08-04T00:01:00Z"));
        Map<String, Object> protectedBefore = protectedDelivery(failed);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/console/platform/admin/sms/deliveries/{id}/retry", failed)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"businessNo\":\"sms-retry-visible-1\"}")
                            .with(adminJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(failed.toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.attemptCount").value(0))
                    .andExpect(jsonPath("$.sanitizedError").doesNotExist());
        }

        mockMvc.perform(post("/console/platform/admin/sms/deliveries/{id}/retry", dead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessNo\":\"sms-retry-visible-2\"}")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(protectedDelivery(failed)).isEqualTo(protectedBefore);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'SMS_DELIVERY_RETRIED'
                  AND resource_id = ? AND request_id = ?
                """, Integer.class, failed.toString(), "sms-retry-visible-1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'SMS_DELIVERY_RETRIED'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void rejectsIneligibleStatesAndBusinessNumberReuseForAnotherDelivery() throws Exception {
        UUID replayTarget = UUID.fromString("78000000-0000-4000-8000-000000000031");
        insertDelivery(replayTarget, "ORDER_PAYMENT_FAILED", "FAILED", "1111",
                "retry:reserved", Instant.parse("2026-08-04T00:00:00Z"));
        retry(replayTarget, "sms-retry-reserved").andExpect(status().isOk());

        UUID conflictTarget = UUID.fromString("78000000-0000-4000-8000-000000000032");
        insertDelivery(conflictTarget, "ORDER_PAYMENT_FAILED", "FAILED", "2222",
                "retry:conflict", Instant.parse("2026-08-04T00:01:00Z"));
        retry(conflictTarget, "sms-retry-reserved").andExpect(status().isConflict());

        for (String state : new String[] {"PENDING", "SENDING", "SENT"}) {
            UUID id = UUID.randomUUID();
            insertDelivery(id, "ORDER_PAYMENT_FAILED", state, "3333",
                    "retry:" + state.toLowerCase(), Instant.parse("2026-08-04T00:02:00Z"));
            retry(id, "sms-retry-" + state.toLowerCase()).andExpect(status().isConflict());
        }
    }

    @Test
    void forbidsNormalUsersFromEveryAdministratorSmsEndpoint() throws Exception {
        UUID failed = UUID.fromString("78000000-0000-4000-8000-000000000041");
        insertDelivery(failed, "ORDER_PAYMENT_FAILED", "FAILED", "8963",
                "forbidden:delivery", Instant.parse("2026-08-04T00:00:00Z"));

        mockMvc.perform(get("/console/platform/admin/sms/templates").with(userJwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/console/platform/admin/sms/deliveries").with(userJwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/console/platform/admin/sms/deliveries/{id}/retry", failed)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessNo\":\"sms-retry-forbidden\"}")
                        .with(userJwt()))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT status FROM platform.sms_delivery WHERE id = ?",
                String.class, failed)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.admin_audit_log",
                Integer.class)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions retry(UUID id, String businessNo)
            throws Exception {
        return mockMvc.perform(post("/console/platform/admin/sms/deliveries/{id}/retry", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("businessNo", businessNo)))
                .with(adminJwt()));
    }

    private org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return token(ADMIN_ID, "ROLE_ADMIN");
    }

    private org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor userJwt() {
        return token(USER_ID, "ROLE_USER");
    }

    private org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor token(UUID id, String role) {
        return jwt().jwt(token -> token
                        .subject(id.toString())
                        .claim("tenant_id", TENANT_ID.toString())
                        .claim("auth_version", 0L))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private void insertUser(UUID id, String email) {
        jdbc.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name)
                VALUES (?, ?, ?, 'test-hash', 'SMS admin test user')
                """, id, TENANT_ID, email);
    }

    private void insertDelivery(
            UUID id,
            String templateKey,
            String state,
            String last4,
            String deduplicationKey,
            Instant createdAt) {
        UUID claimToken = "SENDING".equals(state) ? UUID.randomUUID() : null;
        Timestamp claimedUntil = "SENDING".equals(state)
                ? Timestamp.from(createdAt.plusSeconds(60)) : null;
        Timestamp sentAt = "SENT".equals(state) ? Timestamp.from(createdAt.plusSeconds(1)) : null;
        jdbc.update("""
                INSERT INTO platform.sms_delivery(
                    id, tenant_id, user_id, template_key, template_code,
                    phone_ciphertext, phone_last4, variables, sensitive_variables_ciphertext,
                    deduplication_key, status, attempt_count, claim_token, claimed_until,
                    next_attempt_at, provider_request_id, provider_biz_id,
                    sanitized_error, sent_at, created_at, updated_at)
                SELECT ?, ?, NULL, template.template_key, template.template_code,
                       decode('001122', 'hex'), ?, '{"payMoney":"must-not-render"}'::jsonb,
                       decode('deadbeef', 'hex'), ?, ?, 8, ?, ?, ?, 'provider-request-safe',
                       'provider-biz-safe', ?, ?, ?, ?
                FROM platform.sms_template template
                WHERE template.template_key = ?
                """, id, TENANT_ID, last4, deduplicationKey, state, claimToken, claimedUntil,
                Timestamp.from(createdAt),
                "SENT".equals(state) || "PENDING".equals(state)
                        ? null : "TRANSIENT:PROVIDER_NETWORK_ERROR",
                sentAt, Timestamp.from(createdAt), Timestamp.from(createdAt), templateKey);
    }

    private Map<String, Object> protectedDelivery(UUID id) {
        return jdbc.queryForMap("""
                SELECT deduplication_key, encode(phone_ciphertext, 'hex') AS phone_ciphertext,
                       variables::text AS variables,
                       encode(sensitive_variables_ciphertext, 'hex') AS sensitive_variables_ciphertext
                FROM platform.sms_delivery WHERE id = ?
                """, id);
    }
}
