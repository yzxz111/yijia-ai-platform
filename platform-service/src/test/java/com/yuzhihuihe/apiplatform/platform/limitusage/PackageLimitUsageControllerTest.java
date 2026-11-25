package com.yuzhihuihe.apiplatform.platform.limitusage;

import static com.yuzhihuihe.apiplatform.platform.security.AccessTokenVersionTestSupport.stubCurrentLegacyToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 「调用方自有套餐」相关接口，其中归属仓储与网关客户端均被打桩：
 * 这一测试切片刻意不依赖数据库、也不依赖网关，因为这里真正关心的
 * 行为是这两者中先查哪一个、以何种顺序查，以及在二者中任何一方
 * 无法应答时，会告诉调用方什么。
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
class PackageLimitUsageControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("81000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("81000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000003");
    private static final UUID OTHER_TENANT_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000004");
    private static final UUID INSTANCE_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000005");

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        TestJwtKeyRing.configure(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean JdbcTemplate jdbcTemplate;
    @MockitoBean PlatformTransactionManager transactionManager;
    @MockitoBean PackageInstanceOwnershipRepository ownershipRepository;
    @MockitoBean GatewayLimitUsageClient limitUsageClient;

    @Test
    void returnsTheExactLimitUsageBodyOfTheCallersOwnPackage() throws Exception {
        when(ownershipRepository.isOwnedByCaller(INSTANCE_ID, TENANT_ID, USER_ID)).thenReturn(true);
        when(limitUsageClient.fetch(INSTANCE_ID)).thenReturn(limitUsage());

        MvcResult result = mockMvc.perform(limitUsageRequest(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageInstanceId").value(INSTANCE_ID.toString()))
                .andExpect(jsonPath("$.usageDimension").value("TOKEN"))
                .andExpect(jsonPath("$.perMinute.limit").value(100_000))
                .andExpect(jsonPath("$.perMinute.used").value(1_234))
                .andExpect(jsonPath("$.daily.limit").value(2_000))
                .andExpect(jsonPath("$.daily.used").value(17))
                .andExpect(jsonPath("$.blockedLast24h").value(3))
                .andExpect(jsonPath("$.blockedLast24HoursByReason.daily_request_limit_exceeded")
                        .value(1))
                .andExpect(jsonPath("$.blockedLast24HoursByReason.tokens_per_minute_exceeded")
                        .value(2))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "packageInstanceId", "usageDimension", "perMinute", "daily",
                "blockedLast24h", "blockedLast24HoursByReason");
        assertThat(json.path("perMinute").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("limit", "used");
        assertThat(json.path("daily").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("limit", "used");
        assertThat(json.path("blockedLast24HoursByReason").fieldNames()).toIterable()
                .containsExactlyInAnyOrder(
                        "daily_request_limit_exceeded", "tokens_per_minute_exceeded");
        assertThat(json.path("perMinute").path("limit").isIntegralNumber()).isTrue();
        assertThat(json.path("blockedLast24h").isIntegralNumber()).isTrue();

        verify(ownershipRepository).isOwnedByCaller(INSTANCE_ID, TENANT_ID, USER_ID);
        verify(limitUsageClient).fetch(INSTANCE_ID);
    }

    @Test
    void reportsAnUnstatedLimitAsNullRatherThanZero() throws Exception {
        when(ownershipRepository.isOwnedByCaller(INSTANCE_ID, TENANT_ID, USER_ID)).thenReturn(true);
        when(limitUsageClient.fetch(INSTANCE_ID)).thenReturn(
                new GatewayLimitUsageClient.LimitUsage(INSTANCE_ID, "VIDEO_SECOND",
                        new GatewayLimitUsageClient.LimitUsage.Window(null, 30L), null, 0L,
                        new GatewayLimitUsageClient.LimitUsage.BlockedReasons(0L, 0L)));

        mockMvc.perform(limitUsageRequest(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perMinute.limit").doesNotExist())
                .andExpect(jsonPath("$.perMinute.used").value(30))
                .andExpect(jsonPath("$.daily").doesNotExist())
                .andExpect(jsonPath("$.blockedLast24h").value(0))
                .andExpect(jsonPath("$.blockedLast24HoursByReason.daily_request_limit_exceeded")
                        .value(0));
    }

    @Test
    void anotherUsersOrTenantsPackageIsNotFoundAndNeverReachesTheGateway() throws Exception {
        MvcResult crossTenant = mockMvc.perform(get(
                        "/console/platform/packages/" + INSTANCE_ID + "/limit-usage")
                        .with(token(OTHER_TENANT_ID, OTHER_USER_ID)))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult crossUser = mockMvc.perform(get(
                        "/console/platform/packages/" + INSTANCE_ID + "/limit-usage")
                        .with(token(TENANT_ID, OTHER_USER_ID)))
                .andExpect(status().isNotFound())
                .andReturn();

        assertReason(crossTenant, "package_instance_not_found");
        assertReason(crossUser, "package_instance_not_found");
        verify(ownershipRepository).isOwnedByCaller(INSTANCE_ID, OTHER_TENANT_ID, OTHER_USER_ID);
        verify(ownershipRepository).isOwnedByCaller(INSTANCE_ID, TENANT_ID, OTHER_USER_ID);
        verifyNoInteractions(limitUsageClient);
    }

    @Test
    void anUnknownPackageInstanceIsNotFound() throws Exception {
        MvcResult result = mockMvc.perform(limitUsageRequest(userJwt()))
                .andExpect(status().isNotFound())
                .andReturn();

        assertReason(result, "package_instance_not_found");
        verify(limitUsageClient, never()).fetch(any());
    }

    @Test
    void anUnreadableGatewayIsServiceUnavailableWithoutInventedCounters() throws Exception {
        when(ownershipRepository.isOwnedByCaller(INSTANCE_ID, TENANT_ID, USER_ID)).thenReturn(true);
        when(limitUsageClient.fetch(INSTANCE_ID)).thenThrow(
                new GatewayInternalUnavailableException("gateway_internal_http_status:503"));

        MvcResult result = mockMvc.perform(limitUsageRequest(userJwt()))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertReason(result, "gateway_limit_usage_unavailable");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("packageInstanceId")
                .doesNotContain("usageDimension")
                .doesNotContain("perMinute")
                .doesNotContain("blockedLast24h");
    }

    @Test
    void aGatewayBodyWithoutAnEchoedInstanceIsAlsoServiceUnavailable() throws Exception {
        when(ownershipRepository.isOwnedByCaller(INSTANCE_ID, TENANT_ID, USER_ID)).thenReturn(true);
        when(limitUsageClient.fetch(INSTANCE_ID)).thenThrow(
                new GatewayInternalUnavailableException("gateway_limit_usage_payload_invalid"));

        MvcResult result = mockMvc.perform(limitUsageRequest(userJwt()))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertReason(result, "gateway_limit_usage_unavailable");
    }

    @Test
    void packageLimitUsageRequiresAnAuthenticatedUserToken() throws Exception {
        mockMvc.perform(get("/console/platform/packages/" + INSTANCE_ID + "/limit-usage"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(limitUsageRequest(authenticatedWithoutRoles()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(limitUsageClient);
    }

    private MockHttpServletRequestBuilder limitUsageRequest(JwtRequestPostProcessor token) {
        return get("/console/platform/packages/{packageInstanceId}/limit-usage", INSTANCE_ID)
                .with(token);
    }

    private GatewayLimitUsageClient.LimitUsage limitUsage() {
        return new GatewayLimitUsageClient.LimitUsage(INSTANCE_ID, "TOKEN",
                new GatewayLimitUsageClient.LimitUsage.Window(100_000L, 1_234L),
                new GatewayLimitUsageClient.LimitUsage.Window(2_000L, 17L),
                3L,
                new GatewayLimitUsageClient.LimitUsage.BlockedReasons(1L, 2L));
    }

    private JwtRequestPostProcessor userJwt() {
        return token(TENANT_ID, USER_ID);
    }

    private JwtRequestPostProcessor token(UUID tenantId, UUID userId) {
        stubCurrentLegacyToken(jdbcTemplate);
        return jwt()
                .jwt(value -> value.subject(userId.toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("auth_version", 0L))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private JwtRequestPostProcessor authenticatedWithoutRoles() {
        stubCurrentLegacyToken(jdbcTemplate);
        return jwt().jwt(value -> value.subject(USER_ID.toString())
                .claim("tenant_id", TENANT_ID.toString())
                .claim("auth_version", 0L));
    }

    /**
     * 故障必须在传输的内容上可读，而不仅仅体现在解析出的异常里：控制台
     * 依据响应体中的 code 分支处理，而「我们无法读取你的用量」不得以
     * 一个客户端会渲染成「用量为零」的匿名 503 形式到达。
     */
    private void assertReason(MvcResult result, String reason) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.path("code").asText()).isEqualTo(reason);
        assertThat(body.path("message").asText()).isNotBlank();
    }
}
