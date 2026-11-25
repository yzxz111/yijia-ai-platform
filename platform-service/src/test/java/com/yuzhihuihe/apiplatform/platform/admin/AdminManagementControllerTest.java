package com.yuzhihuihe.apiplatform.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.yuzhihuihe.apiplatform.platform.security.AccessTokenVersionTestSupport.stubCurrentLegacyToken;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.PlatformServiceApplication;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.admin.AdminManagementRepository;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementRequests.AdminListQuery;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementRequests.UpdatePaymentConfigRequest;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.PageResult;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.PaymentConfigView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.PaymentView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.RefundView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.UserIdentityView;
import com.yuzhihuihe.apiplatform.platform.refund.RefundApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        classes = PlatformServiceApplication.class,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "reconciliation.job-enabled=false"
        })
@AutoConfigureMockMvc
class AdminManagementControllerTest {

    private static final UUID ACTOR_ID =
            UUID.fromString("75000000-0000-4000-8000-000000000001");

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    PlatformTransactionManager transactionManager;

    @MockitoBean
    AdminManagementService service;

    @MockitoBean
    RefundApplicationService refundService;

    @MockitoBean
    AdminAnalyticsService analyticsService;

    @Test
    void forwardsBillingAdminQueriesAndAuthenticatedConfigUpdate() {
        AdminManagementController controller =
                new AdminManagementController(service, refundService, analyticsService);
        UUID actorId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(actorId.toString()).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        PageResult<PaymentView> payments =
                new PageResult<>(List.of(), 0, 0, 20);
        PageResult<RefundView> refunds =
                new PageResult<>(List.of(), 0, 0, 20);
        UpdatePaymentConfigRequest request =
                new UpdatePaymentConfigRequest(true);
        PaymentConfigView config =
                new PaymentConfigView(
                        "MOCK", "LOCAL", true, true, "mock-fingerprint",
                        "VALID", Instant.now(), Instant.now());
        AdminListQuery query =
                new AdminListQuery(
                        0, 20, "order-1", "SUCCEEDED", "amount", "desc");
        when(service.listPayments(query)).thenReturn(payments);
        when(service.listRefunds(query)).thenReturn(refunds);
        when(service.updatePaymentConfig("MOCK", "LOCAL", request, actorId)).thenReturn(config);
        Instant snapshotTime = Instant.now();
        RefundApplicationService.CalculationSnapshot calculation =
                new RefundApplicationService.CalculationSnapshot(
                        10_001, snapshotTime.minusSeconds(86_400), snapshotTime,
                        1_000_000, 100_000, 900_000,
                        500, 100, 1000, 7, 9_000, 500, 8_500,
                        "PARTIAL", "BASE_FLOOR_FEE_HALF_UP", 1, snapshotTime,
                        UUID.randomUUID(), "ACTIVE", snapshotTime.plusSeconds(3600), snapshotTime);
        RefundApplicationService.RefundView refundDetail =
                new RefundApplicationService.RefundView(
                        "ref_admin", "ord_admin", "PENDING_APPROVAL", "PARTIAL", 8_500,
                        "changed plans", calculation, null, null, null, null,
                        Instant.now(), Instant.now());
        when(refundService.adminDetail("ref_admin")).thenReturn(refundDetail);

        assertThat(controller.payments(0, 20, "order-1", "SUCCEEDED", "amount", "desc"))
                .isSameAs(payments);
        assertThat(controller.refunds(0, 20, "order-1", "SUCCEEDED", "amount", "desc"))
                .isSameAs(refunds);
        assertThat(controller.refundDetail("ref_admin")).isSameAs(refundDetail);
        assertThat(controller.updatePaymentConfig("MOCK", "LOCAL", request, jwt)).isSameAs(config);

        verify(service).updatePaymentConfig("MOCK", "LOCAL", request, actorId);
        verify(service).listPayments(query);
        verify(service).listRefunds(query);
        verify(refundService).adminDetail("ref_admin");
    }

    @Test
    void exposesDefaultSixMonthOperatingAnalyticsContract() throws Exception {
        AdminAnalyticsService.OperatingAnalytics analytics =
                new AdminAnalyticsService.OperatingAnalytics(
                        "Asia/Shanghai", 6,
                        new AdminAnalyticsService.OperatingSummary(4, 3, 10_000, 2_000, 8_000, 5),
                        List.of(new AdminAnalyticsService.MonthlyOperating(
                                LocalDate.of(2026, 8, 1), 1, 10_000, 2_000, 8_000)));
        when(analyticsService.analytics(6)).thenReturn(analytics);

        mockMvc.perform(get("/console/platform/admin/analytics").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.months").value(6))
                .andExpect(jsonPath("$.summary.currentMonthNetAmount").value(8_000))
                .andExpect(jsonPath("$.monthly[0].month").value("2026-08-01"));

        verify(analyticsService).analytics(6);
    }

    @Test
    void rejectsUnsupportedMonthCountWithUnprocessableEntity() throws Exception {
        when(analyticsService.analytics(5)).thenThrow(
                new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "months_must_be_6"));

        mockMvc.perform(get("/console/platform/admin/analytics")
                        .param("months", "5")
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());

        verify(analyticsService).analytics(5);
    }

    @Test
    void rejectsNonAdminAnalyticsRequests() throws Exception {
        stubCurrentAccessTokenVersion();

        mockMvc.perform(get("/console/platform/admin/analytics")
                        .with(jwt().jwt(token -> token
                                        .subject(ACTOR_ID.toString())
                                        .claim("tenant_id", ACTOR_ID.toString())
                                        .claim("auth_version", 0L))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        verify(analyticsService, never()).analytics(any(Integer.class));
    }

    @Test
    void directUserUpdateCannotBypassGlobalAdministratorAuthorization() throws Exception {
        UUID targetId = UUID.fromString("75000000-0000-4000-8000-000000000003");
        // 控制器现在传的是**带租户的 actor**（否则响应体回读会绕过租户边界），
        // 因此这里要把 resolveAdminActor 也桩掉 —— service 是 mock，默认返回 null，
        // 不桩的话控制器会把 null 当 actor 传下去，桩就匹配不上（表现为"没拦住"）。
        when(service.resolveAdminActor(eq(ACTOR_ID), eq(ACTOR_ID.toString())))
                .thenReturn(new AdminManagementRepository.AdminActor(
                        ACTOR_ID, ACTOR_ID, false));
        // 必须写显式类型参数：`argThat` 返回 null，不加会让重载解析选到 UUID 那个版本。
        org.mockito.ArgumentMatcher<AdminManagementRepository.AdminActor> actorWithId =
                actor -> actor != null && ACTOR_ID.equals(actor.userId());
        when(service.updateUser(
                eq(targetId), any(),
                org.mockito.ArgumentMatchers.<AdminManagementRepository.AdminActor>argThat(
                        actorWithId)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "global_admin_required"));

        mockMvc.perform(patch("/console/platform/admin/users/{id}", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"role\":\"ADMIN\"}")
                        .with(adminJwt()))
                .andExpect(status().isForbidden());

        verify(service).updateUser(
                eq(targetId), any(),
                org.mockito.ArgumentMatchers.<AdminManagementRepository.AdminActor>argThat(
                        actorWithId));
    }

    @Test
    void exposesOnlyPublicUserIdentityForBoundedIdentityLookup() throws Exception {
        UUID userId = UUID.fromString("75000000-0000-4000-8000-000000000002");
        when(service.resolveUserIdentities(List.of(userId))).thenReturn(List.of(
                new UserIdentityView(userId, "U00000042", "测试用户")));

        String response = mockMvc.perform(get("/console/platform/admin/users/identities")
                        .param("ids", userId.toString())
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(userId.toString()))
                .andExpect(jsonPath("$[0].userNo").value("U00000042"))
                .andExpect(jsonPath("$[0].displayName").value("测试用户"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("email", "phone", "tenantId");
        verify(service).resolveUserIdentities(List.of(userId));
    }

    private org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        stubCurrentAccessTokenVersion();
        return jwt().jwt(token -> token
                        .subject(ACTOR_ID.toString())
                        .claim("tenant_id", ACTOR_ID.toString())
                        .claim("auth_version", 0L))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private void stubCurrentAccessTokenVersion() {
        stubCurrentLegacyToken(jdbcTemplate);
    }
}
