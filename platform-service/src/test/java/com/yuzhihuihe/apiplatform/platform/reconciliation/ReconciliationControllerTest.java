package com.yuzhihuihe.apiplatform.platform.reconciliation;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.PlatformServiceApplication;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAnomalyService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

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
class ReconciliationControllerTest {

    private static final UUID ACTOR_ID =
            UUID.fromString("53000000-0000-4000-8000-000000000001");
    private static final UUID ANOMALY_ID =
            UUID.fromString("53000000-0000-4000-8000-000000000002");
    private static final UUID RUN_ID =
            UUID.fromString("53000000-0000-4000-8000-000000000003");

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    PlatformTransactionManager transactionManager;

    @MockitoBean
    PaymentAnomalyService anomalyService;

    @MockitoBean
    ReconciliationService reconciliationService;

    @Test
    void allAdministratorRoutesRejectNonAdmins() throws Exception {
        stubCurrentLegacyToken(jdbcTemplate);
        for (var request : List.of(
                get("/console/platform/admin/payment-anomalies"),
                patch("/console/platform/admin/payment-anomalies/" + ANOMALY_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/console/platform/admin/reconciliation-runs")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                get("/console/platform/admin/reconciliation-runs/" + RUN_ID))) {
            mockMvc.perform(request.with(jwt().jwt(token -> token
                                    .subject(ACTOR_ID.toString())
                                    .claim("tenant_id", ACTOR_ID.toString())
                                    .claim("auth_version", 0L))
                            .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void listsAndUpdatesAnomaliesWithOnlyBoundedAdministratorFields() throws Exception {
        Instant now = Instant.parse("2030-03-04T05:06:07Z");
        PaymentAnomalyService.AnomalyView anomaly = new PaymentAnomalyService.AnomalyView(
                ANOMALY_ID, "a".repeat(64), "PAYMENT_AMOUNT_MISMATCH",
                PaymentProvider.WECHAT_PAY, null, null, null, "INVESTIGATING",
                ACTOR_ID, objectMapper.readTree("{\"expectedAmount\":2990,\"actualAmount\":3990}"),
                null, null, now, now, 1);
        when(anomalyService.list(any())).thenReturn(
                new PaymentAnomalyService.PageResult<>(List.of(anomaly), 1, 0, 20));
        when(anomalyService.update(any(), any(), any())).thenReturn(anomaly);

        mockMvc.perform(get("/console/platform/admin/payment-anomalies")
                        .param("anomalyType", "PAYMENT_AMOUNT_MISMATCH")
                        .param("status", "OPEN")
                        .param("provider", "WECHAT_PAY")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].detail.expectedAmount").value(2990));
        mockMvc.perform(patch("/console/platform/admin/payment-anomalies/" + ANOMALY_ID)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"INVESTIGATING","assignee":"%s",
                                 "resolutionNote":null}
                                """.formatted(ACTOR_ID))
                        .with(adminJwt()))
                .andExpect(status().isOk());
        verify(anomalyService).update(eq(ANOMALY_ID), argThat(command ->
                        command.expectedLockVersion() == 0
                                && "INVESTIGATING".equals(command.status())
                                && ACTOR_ID.equals(command.assignee())
                                && command.resolutionNote() == null),
                eq(ACTOR_ID));

        mockMvc.perform(patch("/console/platform/admin/payment-anomalies/" + ANOMALY_ID)
                        .header(HttpHeaders.IF_MATCH, "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IGNORED","assignee":"%s",
                                 "resolutionNote":"x"}
                                """.formatted(ACTOR_ID))
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(patch("/console/platform/admin/payment-anomalies/" + ANOMALY_ID)
                        .header(HttpHeaders.IF_MATCH, "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED","assignee":"%s",
                                 "resolutionNote":"%s"}
                                """.formatted(ACTOR_ID, "x".repeat(1001)))
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rejectsMalformedAnomalyFiltersAndNonTextualResolutionNotes() throws Exception {
        mockMvc.perform(get("/console/platform/admin/payment-anomalies")
                        .param("anomalyType", "PAYMENT;DROP TABLE")
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(patch("/console/platform/admin/payment-anomalies/" + ANOMALY_ID)
                        .header(HttpHeaders.IF_MATCH, "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED","assignee":null,
                                 "resolutionNote":123}
                                """)
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(patch("/console/platform/admin/payment-anomalies/" + ANOMALY_ID)
                        .header(HttpHeaders.IF_MATCH, "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED","assignee":null,
                                 "resolutionNote":"done","expectedLockVersion":0}
                                """)
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(patch("/console/platform/admin/payment-anomalies/" + ANOMALY_ID)
                        .header(HttpHeaders.IF_MATCH, "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED","assignee":null}
                                """)
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(patch("/console/platform/admin/payment-anomalies/" + ANOMALY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED","assignee":null,
                                 "resolutionNote":"done"}
                                """)
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(patch("/console/platform/admin/payment-anomalies/" + ANOMALY_ID)
                        .header(HttpHeaders.IF_MATCH, "-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED","assignee":null,
                                 "resolutionNote":"done"}
                                """)
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());

        verify(anomalyService, never()).list(any());
        verify(anomalyService, never()).update(any(), any(), any());
    }

    @Test
    void startsAndReadsReconciliationWithoutReturningRawBillMaterial() throws Exception {
        Instant now = Instant.parse("2030-03-04T05:06:07Z");
        ReconciliationService.RunSummary summary =
                new ReconciliationService.RunSummary(
                        1, 0, 1, 0, 1, Map.of("UNLINKED_BILL_ROW", 1L));
        ReconciliationService.RunDetail detail = new ReconciliationService.RunDetail(
                RUN_ID, PaymentProvider.ALIPAY, LocalDate.of(2030, 3, 3), "COMPLETED",
                "a".repeat(64), summary, now, now, now,
                new ReconciliationService.PageResult<>(List.of(), 0, 0, 20));
        when(reconciliationService.start(
                PaymentProvider.ALIPAY, LocalDate.of(2030, 3, 3), ACTOR_ID))
                .thenReturn(detail);
        when(reconciliationService.detail(RUN_ID, 0, 20, "createdAt", "desc"))
                .thenReturn(detail);

        mockMvc.perform(post("/console/platform/admin/reconciliation-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ALIPAY\",\"billDate\":\"2030-03-03\"}")
                        .with(adminJwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.rawBill").doesNotExist());
        mockMvc.perform(get("/console/platform/admin/reconciliation-runs/" + RUN_ID)
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.matchedCount").value(0))
                .andExpect(jsonPath("$.summary.differenceCount").value(1))
                .andExpect(jsonPath("$.rawBill").doesNotExist());

        mockMvc.perform(post("/console/platform/admin/reconciliation-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ALIPAY\"}")
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/console/platform/admin/reconciliation-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"billDate\":\"2030-03-03\"}")
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity());
        verify(reconciliationService, never()).start(any(), any(),
                org.mockito.ArgumentMatchers.isNull());
    }

    private org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        stubCurrentLegacyToken(jdbcTemplate);
        return jwt().jwt(token -> token
                        .subject(ACTOR_ID.toString())
                        .claim("tenant_id", ACTOR_ID.toString())
                        .claim("auth_version", 0L))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
