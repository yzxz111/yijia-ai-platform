package com.yuzhihuihe.apiplatform.platform.settlement;

import static com.yuzhihuihe.apiplatform.platform.security.AccessTokenVersionTestSupport.stubCurrentLegacyToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.PlatformServiceApplication;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.internalapi.GatewayInternalUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 面向管理员的结算垫付缺口，其中网关客户端被桩替代。该端点是
 * {@code /console/platform/admin/**} 下的纯读取：这个切片证明角色边界、线上格式
 * （包括合法的空答复），以及不可读取的网关会被上报为 503，而不是
 * 一个让人安心的零。
 */
@SpringBootTest(classes = PlatformServiceApplication.class, properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "reconciliation.job-enabled=false"
})
@AutoConfigureMockMvc
class SettlementDeficitControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("82000000-0000-4000-8000-000000000002");
    private static final UUID RESERVATION_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000003");
    private static final UUID OWNER_ID = UUID.fromString("82000000-0000-4000-8000-000000000004");
    private static final Instant FROM = Instant.parse("2026-01-02T03:04:05Z");
    private static final Instant TO = Instant.parse("2026-01-03T03:04:05Z");

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        TestJwtKeyRing.configure(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean JdbcTemplate jdbcTemplate;
    @MockitoBean PlatformTransactionManager transactionManager;
    @MockitoBean GatewaySettlementDeficitClient deficitClient;

    @Test
    void returnsTheExactDeficitBodyForAnAdministrator() throws Exception {
        when(deficitClient.fetch(null, null)).thenReturn(new GatewaySettlementDeficitClient
                .SettlementDeficit(12_345L, 67_890L, 1L, false, List.of(item())));

        MvcResult result = mockMvc.perform(get("/console/platform/admin/operations/settlement-deficit")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(12_345))
                .andExpect(jsonPath("$.totalTokens").value(67_890))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.items[0].reservationId").value(RESERVATION_ID.toString()))
                .andExpect(jsonPath("$.items[0].amount").value(100))
                .andExpect(jsonPath("$.items[0].tokens").value(200))
                .andExpect(jsonPath("$.items[0].occurredAt").value(FROM.toString()))
                .andExpect(jsonPath("$.items[0].planCode").value("STARTER"))
                .andExpect(jsonPath("$.items[0].userId").value(OWNER_ID.toString()))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("totalAmount", "totalTokens", "count", "truncated", "items");
        assertThat(json.path("items").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder(
                "reservationId", "amount", "tokens", "occurredAt", "planCode", "userId");
        assertThat(json.path("totalAmount").isIntegralNumber()).isTrue();

        verify(deficitClient).fetch(null, null);
    }

    @Test
    void passesATruncatedReportThroughSoTheConsoleCanScopeTheList() throws Exception {
        // 网关返回所有内容的总计，但只返回一部分明细，并说明了这一点。
        // console 的「明细范围」会读取该标志；丢掉它会让页面无法
        // 区分完整列表与有界列表。
        when(deficitClient.fetch(null, null)).thenReturn(new GatewaySettlementDeficitClient
                .SettlementDeficit(862_900L, 9_387_508L, 610L, true, List.of(item())));

        mockMvc.perform(get("/console/platform/admin/operations/settlement-deficit")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(610))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void returnsZeroesAndAnEmptyListWhenNothingWasAbsorbed() throws Exception {
        when(deficitClient.fetch(FROM, TO)).thenReturn(
                new GatewaySettlementDeficitClient.SettlementDeficit(0L, 0L, 0L, false, List.of()));

        mockMvc.perform(get("/console/platform/admin/operations/settlement-deficit")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(0))
                .andExpect(jsonPath("$.totalTokens").value(0))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void passesAValidatedWindowThroughUnchanged() throws Exception {
        when(deficitClient.fetch(FROM, TO)).thenReturn(
                new GatewaySettlementDeficitClient.SettlementDeficit(0L, 0L, 0L, false, List.of()));

        mockMvc.perform(get("/console/platform/admin/operations/settlement-deficit")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .with(adminJwt()))
                .andExpect(status().isOk());

        verify(deficitClient).fetch(FROM, TO);
    }

    @Test
    void acceptsAWindowAtTheThreeHundredAndSixtySixDayBoundary() throws Exception {
        Instant to = FROM.plus(Duration.ofDays(366));
        when(deficitClient.fetch(FROM, to)).thenReturn(
                new GatewaySettlementDeficitClient.SettlementDeficit(0L, 0L, 0L, false, List.of()));

        mockMvc.perform(get("/console/platform/admin/operations/settlement-deficit")
                        .param("from", FROM.toString())
                        .param("to", to.toString())
                        .with(adminJwt()))
                .andExpect(status().isOk());

        verify(deficitClient).fetch(FROM, to);
    }

    @Test
    void rejectsAHalfGivenUnreadableOrReversedWindowWithoutCallingTheGateway() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                        get("/console/platform/admin/operations/settlement-deficit")
                                .param("from", FROM.toString()),
                        get("/console/platform/admin/operations/settlement-deficit")
                                .param("to", TO.toString()),
                        get("/console/platform/admin/operations/settlement-deficit")
                                .param("from", "not-an-instant").param("to", TO.toString()),
                        get("/console/platform/admin/operations/settlement-deficit")
                                .param("from", FROM.toString()).param("to", FROM.toString()),
                        get("/console/platform/admin/operations/settlement-deficit")
                                .param("from", TO.toString()).param("to", FROM.toString()),
                        get("/console/platform/admin/operations/settlement-deficit")
                                .param("from", FROM.toString())
                                .param("to", FROM.plus(Duration.ofDays(367)).toString()),
                        get("/console/platform/admin/operations/settlement-deficit")
                                .param("from", " ").param("to", TO.toString()));

        for (var request : requests) {
            MvcResult result = mockMvc.perform(request.with(adminJwt()))
                    .andExpect(status().isUnprocessableEntity())
                    .andReturn();
            assertCode(result, "invalid_settlement_deficit_range",
                    "settlement_deficit_range_exceeds_366_days");
        }
        verifyNoInteractions(deficitClient);
    }

    @Test
    void settlementDeficitRequiresAnAdministrator() throws Exception {
        mockMvc.perform(get("/console/platform/admin/operations/settlement-deficit")
                        .with(userJwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/console/platform/admin/operations/settlement-deficit"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(deficitClient);
    }

    @Test
    void anUnreadableGatewayIsServiceUnavailableWithoutInventedTotals() throws Exception {
        when(deficitClient.fetch(null, null)).thenThrow(
                new GatewayInternalUnavailableException("gateway_internal_request_failed"));

        MvcResult result = mockMvc.perform(get("/console/platform/admin/operations/settlement-deficit")
                        .with(adminJwt()))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertCode(result, "gateway_settlement_deficit_unavailable");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("totalAmount")
                .doesNotContain("totalTokens")
                .doesNotContain("items");
    }

    /**
     * 该失败必须在线上的响应中可读，而不仅仅存在于已解析的异常里：
     * console 会依据响应体中的 code 分支处理，而一个以匿名 503 形式
     * 到达的不可读缺口，正是本端点绝不能产生的唯一东西。
     */
    private void assertCode(MvcResult result, String... expected) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.path("code").asText()).isIn((Object[]) expected);
        assertThat(body.path("message").asText()).isNotBlank();
    }

    private GatewaySettlementDeficitClient.SettlementDeficit.Item item() {
        return new GatewaySettlementDeficitClient.SettlementDeficit.Item(
                RESERVATION_ID, 100L, 200L, FROM, "STARTER", OWNER_ID);
    }

    private JwtRequestPostProcessor adminJwt() {
        return token(ADMIN_ID, "ROLE_ADMIN");
    }

    private JwtRequestPostProcessor userJwt() {
        return token(USER_ID, "ROLE_USER");
    }

    private JwtRequestPostProcessor token(UUID userId, String role) {
        stubCurrentLegacyToken(jdbcTemplate);
        return jwt()
                .jwt(value -> value.subject(userId.toString())
                        .claim("tenant_id", userId.toString())
                        .claim("auth_version", 0L))
                .authorities(new SimpleGrantedAuthority(role));
    }
}
