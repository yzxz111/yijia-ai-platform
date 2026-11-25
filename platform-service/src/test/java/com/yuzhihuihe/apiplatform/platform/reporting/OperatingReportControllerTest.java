package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.yuzhihuihe.apiplatform.platform.security.AccessTokenVersionTestSupport.stubCurrentLegacyToken;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.PlatformServiceApplication;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(classes = PlatformServiceApplication.class, properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "reconciliation.job-enabled=false"
})
@AutoConfigureMockMvc
class OperatingReportControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID ALERT_ID = UUID.fromString("71000000-0000-4000-8000-000000000002");
    private static final UUID OPERATOR_ALERT_ID =
            UUID.fromString("71000000-0000-4000-8000-000000000003");

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        TestJwtKeyRing.configure(registry);
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean JdbcTemplate jdbcTemplate;
    @MockitoBean PlatformTransactionManager transactionManager;
    @MockitoBean OperatingReportQueryService queryService;
    @MockitoBean OperatingReportExportService exportService;
    @MockitoBean OperatingReconciliationService reconciliationService;
    @MockitoBean OperatingAlertService alertService;
    @MockitoBean OperatingAlertQueryService alertQueryService;
    @MockitoBean GatewayEconomicsHealthClient gatewayEconomicsHealthClient;
    @MockitoBean ProviderPriceSnapshotClient providerPriceSnapshotClient;
    @MockitoBean OperatingAggregationService aggregationService;
    @MockitoBean OperatingFactIngestionService ingestionService;
    @MockitoBean OperatingFactService factService;
    @MockitoBean PaymentFeeService paymentFeeService;
    @MockitoBean OperatingAggregationJob aggregationJob;
    @MockitoBean OperatingReconciliationJob reconciliationJob;
    @MockitoBean ProviderPriceMonitorJob providerPriceMonitorJob;

    @Test
    void reportRoutesRequireAdministratorRole() throws Exception {
        mockMvc.perform(get("/console/platform/admin/operating-reports/summary")
                        .param("from", "2026-07-01").param("to", "2026-07-30")
                        .with(userJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void validatesRangeGrainUuidAndCodeFilters() throws Exception {
        for (var request : List.of(
                get("/console/platform/admin/operating-reports/summary")
                        .param("from", "2025-01-01").param("to", "2026-01-02"),
                get("/console/platform/admin/operating-reports/trend")
                        .param("from", "2026-07-01").param("to", "2026-07-30").param("grain", "YEAR"),
                get("/console/platform/admin/operating-reports/breakdown")
                        .param("from", "2026-07-01").param("to", "2026-07-30").param("userId", "bad"),
                get("/console/platform/admin/operating-reports/summary")
                        .param("from", "2026-07-01").param("to", "2026-07-30").param("provider", "bad;drop"))) {
            mockMvc.perform(request.with(adminJwt())).andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    void returnsSummaryAndStreamsCsv() throws Exception {
        var metrics = new OperatingReportQueryService.Metrics(
                new BigDecimal("129900"), BigDecimal.ZERO, new BigDecimal("1000"),
                new BigDecimal("30000"), new BigDecimal("800"), new BigDecimal("20"),
                new BigDecimal("70000.125000"), new BigDecimal("98080"),
                new BigDecimal("0.761055"), 10, 20, 30, 4, 1, 0);
        when(queryService.query(any())).thenReturn(new OperatingReportQueryService.ReportResult(
                "Asia/Shanghai", "CNY", OperatingReportQueryService.Grain.DAY, List.of(), metrics));
        when(exportService.export(any(), any())).thenAnswer(invocation -> {
            invocation.<java.io.OutputStream>getArgument(1).write("date,amount\n".getBytes());
            return 1L;
        });

        mockMvc.perform(get("/console/platform/admin/operating-reports/summary")
                        .param("from", "2026-07-01").param("to", "2026-07-30")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedRevenueAmount").value(129900))
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"));

        mockMvc.perform(get("/console/platform/admin/operating-reports/export")
                        .param("from", "2026-07-01").param("to", "2026-07-30")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("operating-report-2026-07-01-2026-07-30.csv")));
    }

    @Test
    void defaultsReportsToCnyAndRejectsUnsupportedCurrencies() throws Exception {
        var metrics = new OperatingReportQueryService.Metrics(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, 0, 0, 0, 0, 0, 0);
        when(queryService.query(any())).thenAnswer(invocation -> {
            var filter = invocation.getArgument(0, OperatingReportQueryService.Filter.class);
            return new OperatingReportQueryService.ReportResult("Asia/Shanghai", filter.currency(),
                    filter.grain(), List.of(), metrics);
        });

        mockMvc.perform(get("/console/platform/admin/operating-reports/summary")
                        .param("from", "2026-07-01").param("to", "2026-07-30")
                        .with(adminJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("CNY"));
        mockMvc.perform(get("/console/platform/admin/operating-reports/summary")
                        .param("from", "2026-07-01").param("to", "2026-07-30")
                        .param("currency", "EUR").with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void alertTransitionsUseAuthenticatedAdministratorForAudit() throws Exception {
        when(alertService.acknowledge(ALERT_ID, ADMIN_ID))
                .thenReturn(OperatingAlertService.TransitionResult.UPDATED);
        when(alertService.resolve(ALERT_ID, ADMIN_ID))
                .thenReturn(OperatingAlertService.TransitionResult.UPDATED);
        mockMvc.perform(post("/console/platform/admin/operating-reports/alerts/" + ALERT_ID + "/ack")
                        .with(adminJwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/console/platform/admin/operating-reports/alerts/" + ALERT_ID + "/resolve")
                        .with(adminJwt()))
                .andExpect(status().isNoContent());

        verify(alertService).acknowledge(ALERT_ID, ADMIN_ID);
        verify(alertService).resolve(ALERT_ID, ADMIN_ID);
    }

    @Test
    void alertTransitionsDistinguishMissingAlertsFromInvalidStates() throws Exception {
        when(alertService.acknowledge(ALERT_ID, ADMIN_ID))
                .thenReturn(OperatingAlertService.TransitionResult.NOT_FOUND);
        mockMvc.perform(post("/console/platform/admin/operating-reports/alerts/" + ALERT_ID + "/ack")
                        .with(adminJwt()))
                .andExpect(status().isNotFound());

        when(alertService.resolve(ALERT_ID, ADMIN_ID))
                .thenReturn(OperatingAlertService.TransitionResult.INVALID_STATE);
        mockMvc.perform(post("/console/platform/admin/operating-reports/alerts/" + ALERT_ID + "/resolve")
                        .with(adminJwt()))
                .andExpect(status().isConflict());
    }

    @Test
    void returnsFilteredAlertPagesWithTotalAndValidatesBounds() throws Exception {
        var alert = new OperatingAlertQueryService.Alert(
                ALERT_ID, "LOW_MARGIN_PACKAGE", "OPEN", "qwen-plus", "bailian",
                new BigDecimal("0.30"), new BigDecimal("0.20"), "CNY",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-02T00:00:00Z"),
                null, null);
        when(alertQueryService.findByStatus("OPEN", 2, 2))
                .thenReturn(new OperatingAlertQueryService.AlertPage(List.of(alert), 5));

        var controller = new OperatingReportController(queryService, exportService,
                reconciliationService, alertService, alertQueryService);

        OperatingReportController.PageResult<OperatingReportController.AlertView> result =
                controller.alerts("open", 1, 2);

        assertThat(result.items()).extracting(OperatingReportController.AlertView::id)
                .containsExactly(ALERT_ID);
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThatThrownBy(() -> controller.alerts("OPEN", -1, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_alert_page");
        assertThatThrownBy(() -> controller.alerts("OPEN", 0, 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_alert_page");
        assertThatThrownBy(() -> controller.alerts("OPEN", 0, 101))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_alert_page");
    }

    /**
     * 解决字段的线上形态，控制台正是依据它来编写代码：
     * 由监控解决的告警携带 MACHINE 以及它存储的原因，
     * 由人解决的告警携带 OPERATOR 且不带原因，未解决的告警
     * 两者都不携带。
     */
    @Test
    void reportsWhoResolvedAnAlertAndWhy() throws Exception {
        when(alertQueryService.findByStatus("RESOLVED", 20, 0))
                .thenReturn(new OperatingAlertQueryService.AlertPage(List.of(
                        new OperatingAlertQueryService.Alert(
                                ALERT_ID, "LOW_MARGIN_PACKAGE", "RESOLVED", null, null,
                                new BigDecimal("0.30"), new BigDecimal("0.45"), "CNY",
                                Instant.parse("2026-08-01T00:00:00Z"),
                                Instant.parse("2026-08-02T00:00:00Z"),
                                "MACHINE", "RECOVERED"),
                        new OperatingAlertQueryService.Alert(
                                OPERATOR_ALERT_ID, "PROVIDER_PRICE_CHANGED", "RESOLVED",
                                "qwen-plus", "bailian", new BigDecimal("0.05"),
                                new BigDecimal("0.20"), "CNY",
                                Instant.parse("2026-08-01T00:00:00Z"),
                                Instant.parse("2026-08-02T00:00:00Z"),
                                "OPERATOR", null)), 2));

        mockMvc.perform(get("/console/platform/admin/operating-reports/alerts")
                        .param("status", "RESOLVED")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].resolutionOrigin").value("MACHINE"))
                .andExpect(jsonPath("$.items[0].resolutionReason").value("RECOVERED"))
                .andExpect(jsonPath("$.items[1].resolutionOrigin").value("OPERATOR"))
                .andExpect(jsonPath("$.items[1].resolutionReason").doesNotExist())
                .andExpect(jsonPath("$.items[1].status").value("RESOLVED"));
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return token("ROLE_ADMIN");
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor userJwt() {
        return token("ROLE_USER");
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor token(String role) {
        stubCurrentLegacyToken(jdbcTemplate);
        return jwt().jwt(value -> value.subject(ADMIN_ID.toString())
                        .claim("tenant_id", ADMIN_ID.toString()).claim("auth_version", 0L))
                .authorities(new SimpleGrantedAuthority(role));
    }
}
