package com.yuzhihuihe.apiplatform.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static com.yuzhihuihe.apiplatform.platform.security.AccessTokenVersionTestSupport.stubCurrentLegacyToken;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.yuzhihuihe.apiplatform.platform.PlatformServiceApplication;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.MediaType;

@SpringBootTest(classes = PlatformServiceApplication.class, properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "reconciliation.job-enabled=false"
})
@AutoConfigureMockMvc
class SupportTicketControllerTest {
    private static final UUID TENANT = UUID.fromString("79000000-0000-4000-8000-000000000010");
    private static final UUID USER = UUID.fromString("79000000-0000-4000-8000-000000000011");

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) { TestJwtKeyRing.configure(registry); }

    @Autowired MockMvc mockMvc;
    @MockitoBean SupportTicketService service;
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
    void exposesFixedUserAndAdminControllers() {
        assertThat(SupportTicketController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/console/platform/support-tickets");
        assertThat(AdminSupportTicketController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/console/platform/admin/support-tickets");
        assertThat(methodNames(SupportTicketController.class))
                .contains("list", "detail", "create", "comment", "close");
        assertThat(methodNames(AdminSupportTicketController.class))
                .contains("list", "detail", "claim", "process", "reply", "complete", "close");
    }

    @Test
    void listEndpointsExposePageResponsesInsteadOfRawArrays() throws Exception {
        assertThat(SupportTicketController.class
                .getDeclaredMethod("list", Jwt.class, int.class, int.class)
                .getReturnType().getSimpleName()).isEqualTo("PageResult");
        assertThat(AdminSupportTicketController.class
                .getDeclaredMethod("list", Jwt.class, String.class, String.class,
                        Instant.class, Instant.class, int.class, int.class)
                .getReturnType().getSimpleName()).isEqualTo("PageResult");
    }

    @Test
    void appliesUserAndAdminSecurityToFixedRoutes() throws Exception {
        when(service.listUser(TENANT, USER, 0, 20))
                .thenReturn(new SupportTicketService.PageResult<>(List.of(), 0, 0, 20));
        when(service.listAdmin(null, 0, 20))
                .thenReturn(new SupportTicketService.PageResult<>(List.of(), 0, 0, 20));

        mockMvc.perform(get("/console/platform/support-tickets")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/console/platform/support-tickets").with(token("USER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/console/platform/admin/support-tickets").with(token("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/console/platform/admin/support-tickets").with(token("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void securesAllUserAndAdminMutationPaths() throws Exception {
        UUID ticket = UUID.fromString("79000000-0000-4000-8000-000000000020");
        SupportTicketService.TicketView view = ticket(ticket);
        when(service.addUserComment(TENANT, USER, ticket, "Comment")).thenReturn(view);
        when(service.closeUser(TENANT, USER, ticket)).thenReturn(view);
        mockMvc.perform(post("/console/platform/support-tickets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Title\",\"body\":\"Body\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/console/platform/support-tickets").with(token("USER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Title\",\"body\":\"Body\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/console/platform/support-tickets/" + ticket + "/comments").with(token("USER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Comment\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/console/platform/support-tickets/" + ticket + "/close").with(token("USER")))
                .andExpect(status().isOk());

        List<Request> adminActions = List.of(
                new Request("claim", null), new Request("process", null),
                new Request("reply", "{\"body\":\"Reply\"}"),
                new Request("complete", "{\"resolution\":\"Resolved\",\"providerBusinessNo\":\"SUP-1\"}"),
                new Request("close", null));
        for (Request action : adminActions) {
            var userRequest = post("/console/platform/admin/support-tickets/" + ticket + "/" + action.path())
                    .with(token("USER"));
            var adminRequest = post("/console/platform/admin/support-tickets/" + ticket + "/" + action.path())
                    .with(token("ADMIN"));
            if (action.body() != null) {
                userRequest.contentType(MediaType.APPLICATION_JSON).content(action.body());
                adminRequest.contentType(MediaType.APPLICATION_JSON).content(action.body());
            }
            mockMvc.perform(userRequest).andExpect(status().isForbidden());
            mockMvc.perform(adminRequest).andExpect(status().isOk());
        }
    }

    @Test
    void acceptsBoundedMultipartAttachmentsForTicketCreationAndReplies() throws Exception {
        UUID ticket = UUID.fromString("79000000-0000-4000-8000-000000000020");
        when(service.addUserComment(eq(TENANT), eq(USER), eq(ticket), eq("Additional evidence"), anyList()))
                .thenReturn(ticket(ticket));
        MockMultipartFile title = new MockMultipartFile(
                "title", "", MediaType.TEXT_PLAIN_VALUE, "Attachment issue".getBytes());
        MockMultipartFile body = new MockMultipartFile(
                "body", "", MediaType.TEXT_PLAIN_VALUE, "Please inspect the attachment".getBytes());
        MockMultipartFile comment = new MockMultipartFile(
                "body", "", MediaType.TEXT_PLAIN_VALUE, "Additional evidence".getBytes());
        MockMultipartFile attachment = new MockMultipartFile(
                "attachments", "evidence.txt", MediaType.TEXT_PLAIN_VALUE, "evidence".getBytes());

        mockMvc.perform(multipart("/console/platform/support-tickets")
                        .file(title).file(body).file(attachment).with(token("USER")))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/console/platform/support-tickets/" + ticket + "/comments")
                        .file(comment).file(attachment).with(token("USER")))
                .andExpect(status().isOk());
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor token(String role) {
        stubCurrentLegacyToken(jdbc);
        return jwt().jwt(value -> value.subject(USER.toString())
                        .claim("tenant_id", TENANT.toString()).claim("auth_version", 0L))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static Set<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).collect(Collectors.toSet());
    }

    private static SupportTicketService.TicketView ticket(UUID id) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-19T04:00:00Z");
        return new SupportTicketService.TicketView(
                id, "TK-TEST", TENANT, USER, null, "Title", "Body",
                SupportTicketService.Status.OPEN, null, null, null, now, now, List.of());
    }

    private record Request(String path, String body) { }
}
