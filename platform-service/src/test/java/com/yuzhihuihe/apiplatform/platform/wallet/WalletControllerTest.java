package com.yuzhihuihe.apiplatform.platform.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.ActiveProfiles;
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
        "outbox.publisher.initial-delay-ms=3600000",
        "payment.mock.enabled=true",
        "payment.mock.callback-secret=wallet-controller-test-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class WalletControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("83000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("83000000-0000-4000-8000-000000000002");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("83000000-0000-4000-8000-000000000003");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("83000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_ACCOUNT_ID =
            UUID.fromString("83000000-0000-4000-8000-000000000005");
    private static final UUID NO_WALLET_USER_ID =
            UUID.fromString("83000000-0000-4000-8000-000000000006");
    private static final UUID OTHER_TENANT_ID =
            UUID.fromString("83000000-0000-4000-8000-000000000007");
    private static final UUID OTHER_TENANT_USER_ID =
            UUID.fromString("83000000-0000-4000-8000-000000000008");
    private static final UUID OTHER_TENANT_ACCOUNT_ID =
            UUID.fromString("83000000-0000-4000-8000-000000000009");
    private static final UUID OPTION_ID =
            UUID.fromString("83000000-0000-4000-8000-000000000010");
    private static final UUID SECOND_OPTION_ID =
            UUID.fromString("83000000-0000-4000-8000-000000000011");
    private static final UUID DISABLED_OPTION_ID =
            UUID.fromString("83000000-0000-4000-8000-000000000012");
    private static final UUID OTHER_TENANT_OPTION_ID =
            UUID.fromString("83000000-0000-4000-8000-000000000013");

    private static final Instant SUMMARY_UPDATED_AT =
            Instant.parse("2031-04-02T03:04:05Z");
    private static final Instant FIRST_CREATED_AT =
            Instant.parse("2031-04-01T00:00:00Z");
    private static final Instant SECOND_CREATED_AT =
            Instant.parse("2031-04-01T00:01:00Z");
    private static final Instant NEWEST_CREATED_AT =
            Instant.parse("2031-04-01T00:02:00Z");

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
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtTokenService jwtTokenService;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("TRUNCATE platform.app_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                UPDATE platform.payment_config SET enabled = true
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """);
        insertUser(TENANT_ID, USER_ID, "wallet.owner@example.test");
        insertUser(TENANT_ID, OTHER_USER_ID, "wallet.other@example.test");
        insertUser(TENANT_ID, NO_WALLET_USER_ID, "wallet.missing@example.test");
        insertUser(OTHER_TENANT_ID, OTHER_TENANT_USER_ID, "wallet.tenant@example.test");

        insertAccount(ACCOUNT_ID, TENANT_ID, USER_ID, 12_345, SUMMARY_UPDATED_AT);
        insertAccount(OTHER_ACCOUNT_ID, TENANT_ID, OTHER_USER_ID, 777, SUMMARY_UPDATED_AT);
        insertAccount(OTHER_TENANT_ACCOUNT_ID, OTHER_TENANT_ID,
                OTHER_TENANT_USER_ID, 888, SUMMARY_UPDATED_AT);

        insertOption(OPTION_ID, TENANT_ID, 1_000, true, 10);
        insertOption(SECOND_OPTION_ID, TENANT_ID, 3_000, true, 20);
        insertOption(DISABLED_OPTION_ID, TENANT_ID, 5_000, false, 30);
        insertOption(OTHER_TENANT_OPTION_ID, OTHER_TENANT_ID, 7_000, true, 10);

        insertLedger("wle_owner_1", "00000000-0000-4000-8000-000000000101",
                TENANT_ID, USER_ID, ACCOUNT_ID, 1_000, 0, 1_000, FIRST_CREATED_AT);
        insertLedger("wle_owner_2", "00000000-0000-4000-8000-000000000102",
                TENANT_ID, USER_ID, ACCOUNT_ID, -200, 1_000, 800, SECOND_CREATED_AT);
        insertLedger("wle_owner_3", "00000000-0000-4000-8000-000000000103",
                TENANT_ID, USER_ID, ACCOUNT_ID, 2_000, 800, 2_800, NEWEST_CREATED_AT);
        insertLedger("wle_owner_4", "00000000-0000-4000-8000-000000000104",
                TENANT_ID, USER_ID, ACCOUNT_ID, -500, 2_800, 2_300, NEWEST_CREATED_AT);
        insertLedger("wle_other_user", "00000000-0000-4000-8000-000000000105",
                TENANT_ID, OTHER_USER_ID, OTHER_ACCOUNT_ID, 777, 0, 777, NEWEST_CREATED_AT);
        insertLedger("wle_other_tenant", "00000000-0000-4000-8000-000000000106",
                OTHER_TENANT_ID, OTHER_TENANT_USER_ID, OTHER_TENANT_ACCOUNT_ID,
                888, 0, 888, NEWEST_CREATED_AT);
    }

    @Test
    void summaryReturnsOnlyTheAuthenticatedWalletWithTheLatestLedgerTime() throws Exception {
        MvcResult response = mockMvc.perform(get("/console/platform/wallet")
                        .header("Authorization", "Bearer " + token(
                                TENANT_ID, USER_ID, "wallet.owner@example.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableAmount").value(12_345))
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.updatedAt").value(NEWEST_CREATED_AT.toString()))
                .andReturn();

        JsonNode json = objectMapper.readTree(response.getResponse().getContentAsByteArray());
        assertThat(json.path("availableAmount").isIntegralNumber()).isTrue();
        assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("availableAmount", "currency", "updatedAt");
    }

    @Test
    void summaryFallsBackToTheAccountTimeWhenNoLedgerExists() throws Exception {
        UUID userId = UUID.fromString("83000000-0000-4000-8000-000000000014");
        UUID accountId = UUID.fromString("83000000-0000-4000-8000-000000000015");
        insertUser(TENANT_ID, userId, "wallet.no-ledger@example.test");
        insertAccount(accountId, TENANT_ID, userId, 321, SUMMARY_UPDATED_AT);

        mockMvc.perform(get("/console/platform/wallet")
                        .header("Authorization", "Bearer " + token(
                                TENANT_ID, userId, "wallet.no-ledger@example.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableAmount").value(321))
                .andExpect(jsonPath("$.updatedAt").value(SUMMARY_UPDATED_AT.toString()));
    }

    @Test
    void missingScopedWalletIsNotFoundAndItsLedgerIsEmpty() throws Exception {
        String token = token(TENANT_ID, NO_WALLET_USER_ID, "wallet.missing@example.test");

        MvcResult summary = mockMvc.perform(get("/console/platform/wallet")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn();
        assertReason(summary, "wallet_not_found");

        mockMvc.perform(get("/console/platform/wallet/ledger")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void ledgerDefaultsToNewestFirstWithDeterministicTieBreakingAndPublicFields()
            throws Exception {
        MvcResult response = mockMvc.perform(get("/console/platform/wallet/ledger")
                        .header("Authorization", "Bearer " + token(
                                TENANT_ID, USER_ID, "wallet.owner@example.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[0].entryNo").value("wle_owner_4"))
                .andExpect(jsonPath("$.items[1].entryNo").value("wle_owner_3"))
                .andExpect(jsonPath("$.items[2].entryNo").value("wle_owner_2"))
                .andExpect(jsonPath("$.items[3].entryNo").value("wle_owner_1"))
                .andExpect(jsonPath("$.items[0].amountDelta").value(-500))
                .andReturn();

        String json = response.getResponse().getContentAsString();
        assertThat(json).doesNotContain(
                "tenantId", "userId", "accountId", "ledgerId", "businessId",
                "businessType", "idempotencyKey", "balanceBefore", "paidAmount", "bonusAmount");
        JsonNode first = objectMapper.readTree(json).path("items").get(0);
        assertThat(first.path("amountDelta").isIntegralNumber()).isTrue();
        assertThat(first.path("balanceAfter").isIntegralNumber()).isTrue();
        assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "entryNo", "entryType", "amountDelta", "balanceAfter",
                "businessNo", "description", "createdAt");
    }

    @Test
    void ledgerSupportsServerPaginationAndBothWhitelistedSorts() throws Exception {
        String token = token(TENANT_ID, USER_ID, "wallet.owner@example.test");

        mockMvc.perform(get("/console/platform/wallet/ledger")
                        .queryParam("page", "1")
                        .queryParam("size", "2")
                        .queryParam("sort", "amountDelta")
                        .queryParam("direction", "ASC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items[0].entryNo").value("wle_owner_1"))
                .andExpect(jsonPath("$.items[1].entryNo").value("wle_owner_3"));

        mockMvc.perform(get("/console/platform/wallet/ledger")
                        .queryParam("size", "4")
                        .queryParam("sort", "createdAt")
                        .queryParam("direction", "ASC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].entryNo").value("wle_owner_1"))
                .andExpect(jsonPath("$.items[1].entryNo").value("wle_owner_2"))
                .andExpect(jsonPath("$.items[2].entryNo").value("wle_owner_3"))
                .andExpect(jsonPath("$.items[3].entryNo").value("wle_owner_4"));
    }

    @Test
    void ledgerRejectsInvalidPaginationSortAndDirectionWithStable422Errors()
            throws Exception {
        String token = token(TENANT_ID, USER_ID, "wallet.owner@example.test");
        assertInvalidLedgerQuery(token, "?page=-1", "invalid_pagination");
        assertInvalidLedgerQuery(token, "?size=0", "invalid_pagination");
        assertInvalidLedgerQuery(token, "?size=101", "invalid_pagination");
        assertInvalidLedgerQuery(token, "?page=2147483647&size=100", "invalid_pagination");
        assertInvalidLedgerQuery(token, "?sort=businessNo", "invalid_sort");
        assertInvalidLedgerQuery(token, "?direction=SIDEWAYS", "invalid_sort_direction");
    }

    @Test
    void ledgerMapsMalformedAndOverflowingPaginationTextToStable422Errors()
            throws Exception {
        String token = token(TENANT_ID, USER_ID, "wallet.owner@example.test");

        assertInvalidLedgerQuery(token, "?page=abc", "invalid_pagination");
        assertInvalidLedgerQuery(token, "?size=2147483648", "invalid_pagination");
    }

    @Test
    void ledgerNeverReturnsEntriesOwnedByAnotherUserOrTenant() throws Exception {
        mockMvc.perform(get("/console/platform/wallet/ledger")
                        .header("Authorization", "Bearer " + token(
                                TENANT_ID, OTHER_USER_ID, "wallet.other@example.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].entryNo").value("wle_other_user"));

        mockMvc.perform(get("/console/platform/wallet/ledger")
                        .header("Authorization", "Bearer " + token(
                                OTHER_TENANT_ID, OTHER_TENANT_USER_ID,
                                "wallet.tenant@example.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].entryNo").value("wle_other_tenant"));
    }

    @Test
    void rechargeOptionsExposeOnlyEnabledTenantOptionsAndTheCustomRange() throws Exception {
        MvcResult response = mockMvc.perform(get("/console/platform/wallet/recharge-options")
                        .header("Authorization", "Bearer " + ownerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.options.length()").value(2))
                .andExpect(jsonPath("$.options[0].id").value(OPTION_ID.toString()))
                .andExpect(jsonPath("$.options[0].amount").value(1_000))
                .andExpect(jsonPath("$.options[0].displayOrder").value(10))
                .andExpect(jsonPath("$.options[1].id").value(SECOND_OPTION_ID.toString()))
                .andExpect(jsonPath("$.custom.minimumAmount").value(100))
                .andExpect(jsonPath("$.custom.maximumAmount").value(5_000_000))
                .andReturn();

        String json = response.getResponse().getContentAsString();
        assertThat(json).doesNotContain(
                DISABLED_OPTION_ID.toString(), OTHER_TENANT_OPTION_ID.toString(),
                "tenantId", "createdBy", "updatedBy", "bonus", "idempotencyKey");
        assertThat(objectMapper.readTree(json).path("options").get(0).path("amount").isIntegralNumber())
                .isTrue();
    }

    @Test
    void createRechargeReturns201Then200ForAnIdenticalReplayInMinorUnits() throws Exception {
        String body = "{\"amount\":1000,\"optionId\":\"" + OPTION_ID + "\"}";

        MvcResult created = mockMvc.perform(post("/console/platform/wallet/recharges")
                        .header("Authorization", "Bearer " + ownerToken())
                        .header("Idempotency-Key", "wallet-http-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rechargeNo").value(org.hamcrest.Matchers.startsWith("rch_")))
                .andExpect(jsonPath("$.optionId").value(OPTION_ID.toString()))
                .andExpect(jsonPath("$.paidAmount").value(1_000))
                .andExpect(jsonPath("$.bonusAmount").value(0))
                .andExpect(jsonPath("$.creditedAmount").value(1_000))
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String rechargeNo = objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("rechargeNo").asText();

        MvcResult replay = mockMvc.perform(post("/console/platform/wallet/recharges")
                        .header("Authorization", "Bearer " + ownerToken())
                        .header("Idempotency-Key", "wallet-http-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rechargeNo").value(rechargeNo))
                .andReturn();

        JsonNode replayJson = objectMapper.readTree(replay.getResponse().getContentAsByteArray());
        assertThat(replayJson.path("paidAmount").isIntegralNumber()).isTrue();
        assertThat(replayJson.path("creditedAmount").isIntegralNumber()).isTrue();
        assertThat(rechargeOrderCount()).isEqualTo(1);
    }

    @Test
    void rechargeListIsOwnedPagedNewestFirstAndRejectsUnsafeQueries() throws Exception {
        insertRecharge("rch_owner_old", TENANT_ID, USER_ID, 100, FIRST_CREATED_AT);
        insertRecharge("rch_owner_new", TENANT_ID, USER_ID, 200, SECOND_CREATED_AT);
        insertRecharge("rch_other_user", TENANT_ID, OTHER_USER_ID, 300, NEWEST_CREATED_AT);
        insertRecharge("rch_other_tenant", OTHER_TENANT_ID,
                OTHER_TENANT_USER_ID, 400, NEWEST_CREATED_AT);

        mockMvc.perform(get("/console/platform/wallet/recharges")
                        .queryParam("page", "0")
                        .queryParam("size", "1")
                        .header("Authorization", "Bearer " + ownerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items[0].rechargeNo").value("rch_owner_new"));

        assertInvalidRechargeQuery("?page=abc", "invalid_pagination");
        assertInvalidRechargeQuery("?size=101", "invalid_pagination");
        assertInvalidRechargeQuery("?sort=tenantId", "invalid_recharge_sort");
        assertInvalidRechargeQuery("?direction=SIDEWAYS", "invalid_sort_direction");
    }

    @Test
    void rechargeDetailIsOwnerScopedAndExposesNoInternalFields() throws Exception {
        insertRecharge("rch_owner_detail", TENANT_ID, USER_ID, 1_234, FIRST_CREATED_AT);

        MvcResult response = mockMvc.perform(get(
                        "/console/platform/wallet/recharges/rch_owner_detail")
                        .header("Authorization", "Bearer " + ownerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rechargeNo").value("rch_owner_detail"))
                .andExpect(jsonPath("$.paidAmount").value(1_234))
                .andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain(
                "tenantId", "userId", "idempotencyKey", "provider", "paymentTransaction");

        MvcResult crossUser = mockMvc.perform(get(
                        "/console/platform/wallet/recharges/rch_owner_detail")
                        .header("Authorization", "Bearer " + token(
                                TENANT_ID, OTHER_USER_ID, "wallet.other@example.test")))
                .andExpect(status().isNotFound())
                .andReturn();
        assertReason(crossUser, "recharge_order_not_found");
    }

    @Test
    void closeRechargeIsOwnerScopedAndReturnsTheUpdatedOrder() throws Exception {
        insertRecharge("rch_owner_close", TENANT_ID, USER_ID, 1_234, FIRST_CREATED_AT);

        mockMvc.perform(post("/console/platform/wallet/recharges/rch_owner_close/close")
                        .header("Authorization", "Bearer " + ownerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rechargeNo").value("rch_owner_close"))
                .andExpect(jsonPath("$.status").value("CLOSED"));

        MvcResult crossUser = mockMvc.perform(post(
                        "/console/platform/wallet/recharges/rch_owner_close/close")
                        .header("Authorization", "Bearer " + token(
                                TENANT_ID, OTHER_USER_ID, "wallet.other@example.test")))
                .andExpect(status().isNotFound())
                .andReturn();
        assertReason(crossUser, "recharge_order_not_found");
    }

    @Test
    void createsExternalPaymentAttemptForOwnedRecharge() throws Exception {
        insertRecharge("rch_http_attempt", TENANT_ID, USER_ID, 1_234, NEWEST_CREATED_AT);

        mockMvc.perform(post(
                        "/console/platform/wallet/recharges/rch_http_attempt/payment-attempts")
                        .header("Authorization", "Bearer " + ownerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"MOCK\",\"product\":\"MOCK\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionNo")
                        .value(org.hamcrest.Matchers.startsWith("pay_")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.action.type").value("NONE"))
                .andExpect(jsonPath("$.expiresAt").isString());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM platform.payment_transaction AS payment
                JOIN platform.recharge_order AS recharge
                  ON recharge.id = payment.recharge_order_id
                WHERE recharge.recharge_no = 'rch_http_attempt'
                  AND payment.order_id IS NULL
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void refundAndWithdrawalMutationRoutesRemainAbsentAndDoNotChangeWalletState()
            throws Exception {
        insertRecharge("rch_no_refunds", TENANT_ID, USER_ID, 500, FIRST_CREATED_AT);
        long balanceBefore = balance();
        int ordersBefore = rechargeOrderCount();

        MvcResult refund = mockMvc.perform(post(
                        "/console/platform/wallet/recharges/rch_no_refunds/refund")
                        .header("Authorization", "Bearer " + ownerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        MvcResult withdrawal = mockMvc.perform(post("/console/platform/wallet/withdrawals")
                        .header("Authorization", "Bearer " + ownerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100}"))
                .andReturn();

        assertThat(refund.getResponse().getStatus()).isIn(404, 405);
        assertThat(withdrawal.getResponse().getStatus()).isIn(404, 405);
        assertThat(balance()).isEqualTo(balanceBefore);
        assertThat(rechargeOrderCount()).isEqualTo(ordersBefore);
    }

    private void assertInvalidLedgerQuery(String token, String query, String reason)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/console/platform/wallet/ledger" + query)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertReason(result, reason);
    }

    private void assertInvalidRechargeQuery(String query, String reason) throws Exception {
        MvcResult result = mockMvc.perform(get("/console/platform/wallet/recharges" + query)
                        .header("Authorization", "Bearer " + ownerToken()))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertReason(result, reason);
    }

    private void assertReason(MvcResult result, String reason) {
        assertThat(result.getResolvedException())
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getReason()).isEqualTo(reason));
    }

    private void insertUser(UUID tenantId, UUID userId, String email) {
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'hash', 'Wallet Query User', 'ACTIVE')
                """, userId, tenantId, email);
    }

    private void insertAccount(
            UUID accountId, UUID tenantId, UUID userId, long amount, Instant updatedAt) {
        jdbcTemplate.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, version, updated_at)
                VALUES (?, ?, ?, 'CNY', ?, 0, ?)
                """, accountId, tenantId, userId, amount, Timestamp.from(updatedAt));
    }

    private void insertOption(
            UUID id, UUID tenantId, long amount, boolean enabled, int displayOrder) {
        jdbcTemplate.update("""
                INSERT INTO platform.recharge_option(
                    id, tenant_id, amount, enabled, display_order)
                VALUES (?, ?, ?, ?, ?)
                """, id, tenantId, amount, enabled, displayOrder);
    }

    private void insertRecharge(
            String rechargeNo, UUID tenantId, UUID userId, long amount, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO platform.recharge_order(
                    id, recharge_no, tenant_id, user_id, paid_amount, bonus_amount,
                    credited_amount, currency, status, idempotency_key,
                    expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, 'CNY', 'PENDING', ?, ?, ?, ?)
                """, UUID.randomUUID(), rechargeNo, tenantId, userId, amount, amount,
                "idem_" + rechargeNo, Timestamp.from(createdAt.plusSeconds(900)),
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private int rechargeOrderCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.recharge_order", Integer.class);
    }

    private long balance() {
        return jdbcTemplate.queryForObject("""
                SELECT available_amount FROM platform.account_balance
                WHERE tenant_id = ? AND user_id = ? AND id = ?
                """, Long.class, TENANT_ID, USER_ID, ACCOUNT_ID);
    }

    private void insertLedger(
            String entryNo, String ledgerId, UUID tenantId, UUID userId, UUID accountId,
            long delta, long balanceBefore, long balanceAfter, Instant createdAt) {
        boolean credit = delta > 0;
        jdbcTemplate.update("""
                INSERT INTO platform.balance_ledger(
                    id, entry_no, tenant_id, user_id, account_id, entry_type,
                    amount_delta, paid_amount, bonus_amount, balance_before, balance_after,
                    business_type, business_id, business_no, idempotency_key,
                    description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.fromString(ledgerId), entryNo, tenantId, userId, accountId,
                credit ? "RECHARGE" : "PACKAGE_PURCHASE", delta, credit ? delta : 0,
                balanceBefore, balanceAfter, credit ? "RECHARGE_ORDER" : "ORDER",
                UUID.randomUUID(), "business_" + entryNo, "idem_" + entryNo,
                credit ? "Recharge credit" : "Package purchase", Timestamp.from(createdAt));
    }

    private String token(UUID tenantId, UUID userId, String email) {
        return jwtTokenService.createAccessToken(new IdentityService.UserProfile(
                userId, tenantId, email, "Wallet Query User", "USER"));
    }

    private String ownerToken() {
        return token(TENANT_ID, USER_ID, "wallet.owner@example.test");
    }
}
