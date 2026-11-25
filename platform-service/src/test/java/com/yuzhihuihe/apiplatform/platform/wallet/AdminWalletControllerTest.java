package com.yuzhihuihe.apiplatform.platform.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
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
class AdminWalletControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("85000000-0000-4000-8000-000000000001");
    private static final UUID ADMIN_ID =
            UUID.fromString("85000000-0000-4000-8000-000000000002");
    private static final UUID USER_ID =
            UUID.fromString("85000000-0000-4000-8000-000000000003");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("85000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_TENANT_ID =
            UUID.fromString("85000000-0000-4000-8000-000000000005");
    private static final UUID OTHER_ADMIN_ID =
            UUID.fromString("85000000-0000-4000-8000-000000000006");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("85000000-0000-4000-8000-000000000007");
    private static final UUID OTHER_ACCOUNT_ID =
            UUID.fromString("85000000-0000-4000-8000-000000000008");
    private static final UUID OPTION_ID =
            UUID.fromString("85000000-0000-4000-8000-000000000009");
    private static final UUID REMOVED_OPTION_ID =
            UUID.fromString("85000000-0000-4000-8000-000000000010");
    private static final UUID OTHER_OPTION_ID =
            UUID.fromString("85000000-0000-4000-8000-000000000011");
    private static final Instant OLD_TIME = Instant.parse("2033-01-01T00:00:00Z");
    private static final Instant NEW_TIME = Instant.parse("2033-01-02T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtTokenService jwtTokenService;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void resetData() {
        jdbc.update("TRUNCATE platform.app_user RESTART IDENTITY CASCADE");
        insertUser(TENANT_ID, ADMIN_ID, "wallet.admin@example.test", "ADMIN");
        insertUser(TENANT_ID, USER_ID, "wallet.customer@example.test", "USER");
        insertUser(OTHER_TENANT_ID, OTHER_ADMIN_ID, "other.admin@example.test", "ADMIN");
        insertUser(OTHER_TENANT_ID, OTHER_USER_ID, "other.customer@example.test", "USER");
        insertAccount(ACCOUNT_ID, TENANT_ID, USER_ID, 12_345, NEW_TIME);
        insertAccount(OTHER_ACCOUNT_ID, OTHER_TENANT_ID, OTHER_USER_ID, 99_999, NEW_TIME);
        insertLedger("wle_admin_recharge", TENANT_ID, USER_ID, ACCOUNT_ID,
                "RECHARGE", 2_000, 12_345, "rch_admin_owned", NEW_TIME);
        insertLedger("wle_admin_purchase", TENANT_ID, USER_ID, ACCOUNT_ID,
                "PACKAGE_PURCHASE", -500, 11_845, "ord_admin_owned", OLD_TIME);
        insertLedger("wle_other_tenant", OTHER_TENANT_ID, OTHER_USER_ID, OTHER_ACCOUNT_ID,
                "RECHARGE", 10_000, 99_999, "rch_other_tenant", NEW_TIME);
        insertRecharge("rch_admin_owned", TENANT_ID, USER_ID, 2_000, "PENDING", NEW_TIME);
        insertRecharge("rch_admin_paid", TENANT_ID, USER_ID, 3_000, "PAID", OLD_TIME);
        insertRecharge("rch_other_tenant", OTHER_TENANT_ID,
                OTHER_USER_ID, 10_000, "PENDING", NEW_TIME);
        insertOption(OPTION_ID, TENANT_ID, 1_000, true, 10);
        insertOption(REMOVED_OPTION_ID, TENANT_ID, 3_000, true, 20);
        insertOption(OTHER_OPTION_ID, OTHER_TENANT_ID, 9_000, true, 10);
    }

    @Test
    void globalAdminCanReadCrossTenantAccountsRechargesAndLedgerButNotChangeTenantOptions() throws Exception {
        jdbc.update("UPDATE platform.app_user SET global_admin = true WHERE id = ?", ADMIN_ID);
        mockMvc.perform(get("/console/platform/admin/wallet/accounts")
                        .queryParam("query", "other.customer@example.test")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(OTHER_USER_ID.toString()));
        mockMvc.perform(get("/console/platform/admin/wallet/recharges")
                        .queryParam("query", "rch_other_tenant")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get("/console/platform/admin/wallet/ledger")
                        .queryParam("query", "wle_other_tenant")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get("/console/platform/admin/wallet/overview")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").value(2))
                .andExpect(jsonPath("$.totalBalance").value(112344));
        mockMvc.perform(get("/console/platform/admin/wallet/recharge-options")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void overviewIncludesMoreThanOneHundredRowsAndIgnoresClientScopeEscalation() throws Exception {
        Instant now = Instant.now();
        for (int index = 0; index < 105; index++) {
            insertLedger("wle_today_" + index, TENANT_ID, USER_ID, ACCOUNT_ID,
                    "RECHARGE", 100, 12345, "rch_today_" + index, now);
        }
        mockMvc.perform(get("/console/platform/admin/wallet/overview")
                        .queryParam("globalAdmin", "true").queryParam("tenantId", OTHER_TENANT_ID.toString())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").value(1))
                .andExpect(jsonPath("$.totalBalance").value(12345))
                .andExpect(jsonPath("$.todayRecharge").value(10500))
                .andExpect(jsonPath("$.trend.length()").value(7));
    }

    @Test
    void accountsAreTenantScopedFilteredPagedAndPublic() throws Exception {
        MvcResult response = mockMvc.perform(get("/console/platform/admin/wallet/accounts")
                        .queryParam("query", "wallet.customer@example.test")
                        .queryParam("page", "0")
                        .queryParam("size", "1")
                        .queryParam("sort", "availableAmount")
                        .queryParam("direction", "desc")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items[0].id").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.items[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.items[0].userNo").value(org.hamcrest.Matchers.matchesPattern("U[0-9A-Z]{8}")))
                .andExpect(jsonPath("$.items[0].email").value("wallet.customer@example.test"))
                .andExpect(jsonPath("$.items[0].displayName").value("Admin Wallet User"))
                .andExpect(jsonPath("$.items[0].availableAmount").value(12_345))
                .andExpect(jsonPath("$.items[0].currency").value("CNY"))
                .andReturn();

        assertThat(response.getResponse().getContentAsString()).doesNotContain(
                OTHER_ACCOUNT_ID.toString(), "tenantId", "password", "idempotencyKey");
    }

    @Test
    void accountsSupportUpdatedAtDateRange() throws Exception {
        mockMvc.perform(get("/console/platform/admin/wallet/accounts")
                        .queryParam("from", "2033-01-02T00:00:00Z")
                        .queryParam("to", "2033-01-02T23:59:59.999Z")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(ACCOUNT_ID.toString()));
    }

    @Test
    void ledgerIsTenantScopedFilteredPagedAndContainsOnlyPublicEntries() throws Exception {
        MvcResult response = mockMvc.perform(get("/console/platform/admin/wallet/ledger")
                        .queryParam("entryType", "RECHARGE")
                        .queryParam("query", "wallet.customer@example.test")
                        .queryParam("page", "0")
                        .queryParam("size", "10")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].entryNo").value("wle_admin_recharge"))
                .andExpect(jsonPath("$.items[0].entryType").value("RECHARGE"))
                .andExpect(jsonPath("$.items[0].amountDelta").value(2_000))
                .andExpect(jsonPath("$.items[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.items[0].userNo").value(org.hamcrest.Matchers.matchesPattern("U[0-9A-Z]{8}")))
                .andExpect(jsonPath("$.items[0].displayName").value("Admin Wallet User"))
                .andReturn();

        assertThat(response.getResponse().getContentAsString()).doesNotContain(
                "wle_other_tenant", "tenantId", "accountId", "businessId",
                "idempotencyKey", "balanceBefore", "paidAmount", "bonusAmount");
    }

    @Test
    void rechargesAreTenantScopedFilteredPagedAndNewestFirst() throws Exception {
        MvcResult response = mockMvc.perform(get("/console/platform/admin/wallet/recharges")
                        .queryParam("status", "PENDING")
                        .queryParam("query", "wallet.customer@example.test")
                        .queryParam("page", "0")
                        .queryParam("size", "10")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].rechargeNo").value("rch_admin_owned"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].paidAmount").value(2_000))
                .andExpect(jsonPath("$.items[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.items[0].userNo").value(org.hamcrest.Matchers.matchesPattern("U[0-9A-Z]{8}")))
                .andExpect(jsonPath("$.items[0].displayName").value("Admin Wallet User"))
                .andReturn();

        assertThat(response.getResponse().getContentAsString()).doesNotContain(
                "rch_other_tenant", "tenantId", "idempotencyKey", "providerAttempt");
    }

    @Test
    void rechargeOptionsListIsTenantScopedAndPublic() throws Exception {
        MvcResult response = mockMvc.perform(get(
                        "/console/platform/admin/wallet/recharge-options")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(OPTION_ID.toString()))
                .andExpect(jsonPath("$[0].amount").value(1_000))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].displayOrder").value(10))
                .andExpect(jsonPath("$[1].id").value(REMOVED_OPTION_ID.toString()))
                .andReturn();

        assertThat(response.getResponse().getContentAsString()).doesNotContain(
                OTHER_OPTION_ID.toString(), "tenantId", "createdBy", "updatedBy", "bonus");
    }

    @Test
    void replacingOptionsPreservesIdsDisablesOmissionsAndAuditsSafeDetails() throws Exception {
        String body = """
                {"options":[
                  {"amount":2500,"enabled":true,"displayOrder":5},
                  {"amount":1000,"enabled":false,"displayOrder":30}
                ]}
                """;

        mockMvc.perform(put("/console/platform/admin/wallet/recharge-options")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(2_500))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].displayOrder").value(5))
                .andExpect(jsonPath("$[1].id").value(REMOVED_OPTION_ID.toString()))
                .andExpect(jsonPath("$[1].enabled").value(false))
                .andExpect(jsonPath("$[2].id").value(OPTION_ID.toString()))
                .andExpect(jsonPath("$[2].amount").value(1_000))
                .andExpect(jsonPath("$[2].enabled").value(false))
                .andExpect(jsonPath("$[2].displayOrder").value(30));

        assertThat(optionId(1_000)).isEqualTo(OPTION_ID);
        assertThat(optionEnabled(3_000)).isFalse();
        assertThat(optionEnabled(2_500)).isTrue();
        JsonNode detail = objectMapper.readTree(jdbc.queryForObject("""
                SELECT detail::text
                FROM platform.admin_audit_log
                WHERE tenant_id = ? AND actor_user_id = ?
                  AND action = 'WALLET_RECHARGE_OPTIONS_REPLACED'
                """, String.class, TENANT_ID, ADMIN_ID));
        assertThat(detail.path("optionCount").asInt()).isEqualTo(2);
        assertThat(detail.path("enabledCount").asInt()).isEqualTo(1);
        assertThat(detail.toString()).doesNotContain(
                "claim", "token", "password", "secret", "idempotency");
    }

    @Test
    void replacementRejectsDuplicatesOutOfRangeTooManyAndMalformedOrBonusPayloads()
            throws Exception {
        String twentyOne = java.util.stream.LongStream.rangeClosed(1, 21)
                .map(value -> value * 100)
                .mapToObj(amount -> "{\"amount\":" + amount
                        + ",\"enabled\":true,\"displayOrder\":" + amount + "}")
                .collect(java.util.stream.Collectors.joining(",", "{\"options\":[", "]}"));
        String[] invalidBodies = {
                "{\"options\":[{\"amount\":1000,\"enabled\":true,\"displayOrder\":1},"
                        + "{\"amount\":1000,\"enabled\":false,\"displayOrder\":2}]}",
                "{\"options\":[{\"amount\":99,\"enabled\":true,\"displayOrder\":1}]}",
                "{\"options\":[{\"amount\":5000001,\"enabled\":true,\"displayOrder\":1}]}",
                twentyOne,
                "{}",
                "{\"options\":{}}",
                "{\"options\":[{\"amount\":1000.5,\"enabled\":true,\"displayOrder\":1}]}",
                "{\"options\":[{\"amount\":1000,\"enabled\":true}]}",
                "{\"options\":[{\"amount\":1000,\"enabled\":true,\"displayOrder\":1,"
                        + "\"bonusAmount\":500}]}",
                "{\"options\":[],\"bonusSettings\":{\"rate\":10}}"
        };

        for (String invalidBody : invalidBodies) {
            MvcResult result = mockMvc.perform(put(
                            "/console/platform/admin/wallet/recharge-options")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isUnprocessableEntity())
                    .andReturn();
            assertReason(result, "invalid_recharge_options");
        }

        assertThat(optionCount(TENANT_ID)).isEqualTo(2);
        assertThat(auditCount()).isZero();
    }

    @Test
    void adminQueriesRejectUnsafePaginationFiltersAndSorts() throws Exception {
        assertInvalidQuery("/accounts?page=abc", "invalid_pagination");
        assertInvalidQuery("/accounts?size=101", "invalid_pagination");
        assertInvalidQuery("/accounts?sort=tenantId", "invalid_admin_wallet_sort");
        assertInvalidQuery("/ledger?entryType=WITHDRAWAL", "invalid_ledger_entry_type");
        assertInvalidQuery("/ledger?direction=SIDEWAYS", "invalid_sort_direction");
        assertInvalidQuery("/recharges?status=REFUNDED", "invalid_recharge_status");
        assertInvalidQuery("/recharges?sort=idempotencyKey", "invalid_admin_wallet_sort");
    }

    @Test
    void nonAdminsAreForbiddenFromAllAdminWalletEndpoints() throws Exception {
        String authorization = "Bearer " + userToken();

        mockMvc.perform(get("/console/platform/admin/wallet/overview")
                        .header("Authorization", authorization))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/console/platform/admin/wallet/accounts")
                        .header("Authorization", authorization))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/console/platform/admin/wallet/ledger")
                        .header("Authorization", authorization))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/console/platform/admin/wallet/recharges")
                        .header("Authorization", authorization))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/console/platform/admin/wallet/recharge-options")
                        .header("Authorization", authorization))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/console/platform/admin/wallet/recharge-options")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"options\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void balanceMutationEndpointRemainsAbsentAndCannotChangeAnAccount() throws Exception {
        long before = balance();

        MvcResult result = mockMvc.perform(post(
                        "/console/platform/admin/wallet/accounts/" + ACCOUNT_ID)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableAmount\":999999}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isIn(404, 405);
        assertThat(balance()).isEqualTo(before);
    }

    private void assertInvalidQuery(String suffix, String reason) throws Exception {
        MvcResult result = mockMvc.perform(get("/console/platform/admin/wallet" + suffix)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertReason(result, reason);
    }

    private void assertReason(MvcResult result, String reason) {
        assertThat(result.getResolvedException())
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getReason()).isEqualTo(reason));
    }

    private void insertUser(
            UUID tenantId, UUID userId, String email, String role) {
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'hash', 'Admin Wallet User', 'ACTIVE')
                """, userId, tenantId, email);
        jdbc.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                SELECT ?, ?, id FROM platform.app_role WHERE code = ?
                """, tenantId, userId, role);
    }

    private void insertAccount(
            UUID id, UUID tenantId, UUID userId, long amount, Instant updatedAt) {
        jdbc.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount,
                    version, created_at, updated_at)
                VALUES (?, ?, ?, 'CNY', ?, 0, ?, ?)
                """, id, tenantId, userId, amount,
                Timestamp.from(OLD_TIME), Timestamp.from(updatedAt));
    }

    private void insertLedger(
            String entryNo,
            UUID tenantId,
            UUID userId,
            UUID accountId,
            String entryType,
            long delta,
            long balanceAfter,
            String businessNo,
            Instant createdAt) {
        boolean recharge = "RECHARGE".equals(entryType);
        long balanceBefore = balanceAfter - delta;
        jdbc.update("""
                INSERT INTO platform.balance_ledger(
                    id, entry_no, tenant_id, user_id, account_id, entry_type,
                    amount_delta, paid_amount, bonus_amount, balance_before, balance_after,
                    business_type, business_id, business_no, idempotency_key,
                    description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), entryNo, tenantId, userId, accountId, entryType,
                delta, recharge ? delta : 0, balanceBefore, balanceAfter,
                recharge ? "RECHARGE_ORDER" : "ORDER", UUID.randomUUID(), businessNo,
                "idem_" + entryNo, recharge ? "Recharge credit" : "Package purchase",
                Timestamp.from(createdAt));
    }

    private void insertRecharge(
            String rechargeNo,
            UUID tenantId,
            UUID userId,
            long amount,
            String status,
            Instant createdAt) {
        Timestamp lifecycleTime = "PAID".equals(status) ? Timestamp.from(createdAt) : null;
        jdbc.update("""
                INSERT INTO platform.recharge_order(
                    id, recharge_no, tenant_id, user_id, paid_amount, bonus_amount,
                    credited_amount, currency, status, idempotency_key,
                    expires_at, paid_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, 'CNY', ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), rechargeNo, tenantId, userId, amount, amount,
                status, "idem_" + rechargeNo, Timestamp.from(createdAt.plusSeconds(900)),
                lifecycleTime, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private void insertOption(
            UUID id, UUID tenantId, long amount, boolean enabled, int displayOrder) {
        jdbc.update("""
                INSERT INTO platform.recharge_option(
                    id, tenant_id, amount, enabled, display_order)
                VALUES (?, ?, ?, ?, ?)
                """, id, tenantId, amount, enabled, displayOrder);
    }

    private UUID optionId(long amount) {
        return jdbc.queryForObject("""
                SELECT id FROM platform.recharge_option
                WHERE tenant_id = ? AND amount = ?
                """, UUID.class, TENANT_ID, amount);
    }

    private boolean optionEnabled(long amount) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT enabled FROM platform.recharge_option
                WHERE tenant_id = ? AND amount = ?
                """, Boolean.class, TENANT_ID, amount));
    }

    private int optionCount(UUID tenantId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM platform.recharge_option WHERE tenant_id = ?
                """, Integer.class, tenantId);
    }

    private int auditCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE tenant_id = ? AND action = 'WALLET_RECHARGE_OPTIONS_REPLACED'
                """, Integer.class, TENANT_ID);
    }

    private long balance() {
        return jdbc.queryForObject("""
                SELECT available_amount FROM platform.account_balance
                WHERE tenant_id = ? AND user_id = ? AND id = ?
                """, Long.class, TENANT_ID, USER_ID, ACCOUNT_ID);
    }

    private String adminToken() {
        return token(TENANT_ID, ADMIN_ID, "wallet.admin@example.test", "ADMIN");
    }

    private String userToken() {
        return token(TENANT_ID, USER_ID, "wallet.customer@example.test", "USER");
    }

    private String token(UUID tenantId, UUID userId, String email, String role) {
        return jwtTokenService.createAccessToken(new IdentityService.UserProfile(
                userId, tenantId, email, "Admin Wallet User", role));
    }
}
