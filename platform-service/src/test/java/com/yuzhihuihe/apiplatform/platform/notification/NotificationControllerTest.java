package com.yuzhihuihe.apiplatform.platform.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.yuzhihuihe.apiplatform.platform.security.AccessTokenVersionTestSupport.stubCurrentLegacyToken;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import com.yuzhihuihe.apiplatform.platform.PlatformServiceApplication;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;

@SpringBootTest(classes = PlatformServiceApplication.class, properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "reconciliation.job-enabled=false"
})
@AutoConfigureMockMvc
class NotificationControllerTest {

    private static final UUID TENANT = UUID.fromString("71000000-0000-4000-8000-000000000010");
    private static final UUID USER = UUID.fromString("71000000-0000-4000-8000-000000000011");
    private static final UUID NOTIFICATION = UUID.fromString("71000000-0000-4000-8000-000000000012");

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        TestJwtKeyRing.configure(registry);
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean NotificationQueryService queries;
    @MockitoBean NotificationPreferenceService preferences;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean PlatformTransactionManager transactionManager;
    @MockitoBean com.yuzhihuihe.apiplatform.platform.reporting.OperatingReportQueryService reportQueryService;
    @MockitoBean com.yuzhihuihe.apiplatform.platform.reporting.OperatingReportExportService reportExportService;
    @MockitoBean com.yuzhihuihe.apiplatform.platform.reporting.OperatingReconciliationService reconciliationService;
    @MockitoBean com.yuzhihuihe.apiplatform.platform.reporting.OperatingAlertService alertService;
    @MockitoBean com.yuzhihuihe.apiplatform.platform.reporting.GatewayEconomicsHealthClient gatewayEconomicsHealthClient;
    @MockitoBean com.yuzhihuihe.apiplatform.platform.reporting.ProviderPriceSnapshotClient providerPriceSnapshotClient;
    @MockitoBean com.yuzhihuihe.apiplatform.platform.reporting.OperatingAggregationService aggregationService;
    @MockitoBean com.yuzhihuihe.apiplatform.platform.reporting.OperatingFactIngestionService ingestionService;
    @MockitoBean com.yuzhihuihe.apiplatform.platform.reporting.OperatingFactService factService;
    @MockitoBean com.yuzhihuihe.apiplatform.platform.reporting.PaymentFeeService paymentFeeService;
    @MockitoBean com.yuzhihuihe.apiplatform.platform.reporting.OperatingAggregationJob aggregationJob;
    @MockitoBean com.yuzhihuihe.apiplatform.platform.reporting.OperatingReconciliationJob reconciliationJob;
    @MockitoBean com.yuzhihuihe.apiplatform.platform.reporting.ProviderPriceMonitorJob providerPriceMonitorJob;

    @Test
    void exposesNotificationQueryAndMutationRoutesWithAuthenticatedScope() throws Exception {
        var item = new NotificationQueryService.NotificationView(NOTIFICATION, "BILLING",
                "ORDER_PAYMENT_FAILED", "Payment failed", "Try again", "ORDER", "O-1",
                null, null, Instant.parse("2026-08-09T03:00:00Z"));
        when(queries.list(TENANT, USER, "BILLING", false, 0, 20))
                .thenReturn(new NotificationQueryService.NotificationPage(List.of(item), 1, 0, 20));
        when(queries.unreadCount(TENANT, USER)).thenReturn(3L);

        mockMvc.perform(get("/console/platform/notifications")
                        .param("category", "BILLING").param("read", "false").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Payment failed"))
                .andExpect(jsonPath("$.items[0].phone").doesNotExist())
                .andExpect(jsonPath("$.items[0].smsDeliveryId").doesNotExist());
        mockMvc.perform(get("/console/platform/notifications/unread-count").with(userJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3));
        mockMvc.perform(post("/console/platform/notifications/" + NOTIFICATION + "/read").with(userJwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/console/platform/notifications/read-all").with(userJwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/console/platform/notifications/" + NOTIFICATION + "/archive").with(userJwt()))
                .andExpect(status().isNoContent());

        verify(queries).markRead(TENANT, USER, NOTIFICATION);
        verify(queries).markAllRead(TENANT, USER);
        verify(queries).archive(TENANT, USER, NOTIFICATION);
    }

    @Test
    void exposesPreferenceRoutesAndSafeResponseShape() throws Exception {
        var preference = new NotificationPreferenceService.PreferenceView(
                "OPERATIONS", "SMS", false, LocalTime.of(22, 0), LocalTime.of(8, 0),
                "Asia/Shanghai");
        when(preferences.list(TENANT, USER)).thenReturn(List.of(preference));
        when(preferences.update(any(), any(), any())).thenReturn(List.of(preference));

        mockMvc.perform(get("/console/platform/notification-preferences").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("OPERATIONS"))
                .andExpect(jsonPath("$[0].phone").doesNotExist());
        mockMvc.perform(put("/console/platform/notification-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(userJwt())
                        .content("""
                                {"preferences":[{"category":"OPERATIONS","channel":"SMS",
                                "enabled":true,"quietStart":"23:30","quietEnd":"07:15",
                                "timezone":"Asia/Shanghai"}]}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/console/platform/notification-preferences/unsubscribe-sms").with(userJwt()))
                .andExpect(status().isNoContent());

        verify(preferences).unsubscribeSms(TENANT, USER);
    }

    @Test
    void validatesBooleanPaginationAndMissingPreferenceBody() throws Exception {
        mockMvc.perform(get("/console/platform/notifications").param("read", "yes").with(userJwt()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/console/platform/notifications").param("page", "-1").with(userJwt()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/console/platform/notifications")
                        .param("page", String.valueOf(Integer.MAX_VALUE)).param("size", "100")
                        .with(userJwt()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/console/platform/notification-preferences")
                        .contentType(MediaType.APPLICATION_JSON).content("{}").with(userJwt()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void securityChainRejectsAnonymousAndAcceptsActiveJwtWithScope() throws Exception {
        when(queries.list(TENANT, USER, null, null, 0, 20))
                .thenReturn(new NotificationQueryService.NotificationPage(List.of(), 0, 0, 20));

        mockMvc.perform(get("/console/platform/notifications"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/console/platform/notifications").with(userJwt()))
                .andExpect(status().isOk());

        verify(queries).list(TENANT, USER, null, null, 0, 20);
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor userJwt() {
        stubCurrentLegacyToken(jdbc);
        return jwt().jwt(value -> value.subject(USER.toString())
                        .claim("tenant_id", TENANT.toString()).claim("auth_version", 0L))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
