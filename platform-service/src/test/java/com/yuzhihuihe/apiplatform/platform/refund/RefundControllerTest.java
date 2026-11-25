package com.yuzhihuihe.apiplatform.platform.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class RefundControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("43000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("43000000-0000-4000-8000-000000000002");
    private static final UUID ADMIN_ID =
            UUID.fromString("43000000-0000-4000-8000-000000000003");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private RefundApplicationService refundService;
    private RefundPolicyService policyService;
    private RefundController userController;
    private AdminRefundController adminController;
    private AdminRefundPolicyController policyController;

    @BeforeEach
    void setUp() {
        refundService = mock(RefundApplicationService.class);
        policyService = mock(RefundPolicyService.class);
        userController = new RefundController(refundService);
        adminController = new AdminRefundController(refundService);
        policyController = new AdminRefundPolicyController(policyService);
    }

    @Test
    void userRoutesForwardOnlyAuthenticatedTenantUserAndBoundedReason() throws Exception {
        Jwt jwt = jwt(USER_ID, TENANT_ID);
        RefundApplicationService.RefundView view = refundView("ref_user", "PENDING_APPROVAL");
        when(refundService.apply(
                TENANT_ID, USER_ID, "ord_user",
                new RefundApplicationService.ApplyRefundCommand("changed plans")))
                .thenReturn(view);
        when(refundService.cancel(TENANT_ID, USER_ID, "ref_user")).thenReturn(view);
        when(refundService.detail(TENANT_ID, USER_ID, "ref_user")).thenReturn(view);

        var created = userController.apply(
                jwt, "ord_user", objectMapper.readTree("{\"reason\":\"changed plans\"}"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isSameAs(view);
        assertThat(userController.cancel(jwt, "ref_user")).isSameAs(view);
        assertThat(userController.detail(jwt, "ref_user")).isSameAs(view);
        verify(refundService).apply(
                TENANT_ID, USER_ID, "ord_user",
                new RefundApplicationService.ApplyRefundCommand("changed plans"));
    }

    @Test
    void applicationRejectsBrowserSuppliedFinancialOrGatewayFacts() throws Exception {
        Jwt jwt = jwt(USER_ID, TENANT_ID);
        List<String> forbidden = List.of(
                "amount", "paidAmount", "totalTokens", "usedTokens", "feeBps",
                "minimumFee", "provider", "refundType");

        for (String field : forbidden) {
            assertThatThrownBy(() -> userController.apply(
                    jwt,
                    "ord_user",
                    objectMapper.readTree("{\"reason\":\"x\",\"" + field + "\":1}")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("invalid_refund_application");
        }
        assertThatThrownBy(() -> userController.apply(
                jwt, "ord_user", objectMapper.readTree("{\"reason\":\"\"}")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_refund_application");
    }

    @Test
    void packageRefundRoutePropagatesRechargeRejectionWithoutCreatingARefund() throws Exception {
        Jwt jwt = jwt(USER_ID, TENANT_ID);
        when(refundService.apply(
                TENANT_ID, USER_ID, "rch_not_a_package",
                new RefundApplicationService.ApplyRefundCommand("not a package")))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "paid_order_not_found"));

        assertThatThrownBy(() -> userController.apply(
                jwt,
                "rch_not_a_package",
                objectMapper.readTree("{\"reason\":\"not a package\"}")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("paid_order_not_found");
        verify(refundService).apply(
                TENANT_ID, USER_ID, "rch_not_a_package",
                new RefundApplicationService.ApplyRefundCommand("not a package"));
    }

    @Test
    void adminDecisionRoutesUseAuthenticatedActorAndRejectUnknownFields() throws Exception {
        Jwt admin = jwt(ADMIN_ID, TENANT_ID);
        RefundApplicationService.RefundView approved = refundView("ref_admin", "SUBMITTING");
        RefundApplicationService.RefundView rejected = refundView("ref_reject", "REJECTED");
        when(refundService.approve(ADMIN_ID, "ref_admin")).thenReturn(approved);
        when(refundService.reject(
                ADMIN_ID, "ref_reject",
                new RefundApplicationService.RejectRefundCommand("risk review")))
                .thenReturn(rejected);

        assertThat(adminController.approve(admin, "ref_admin")).isSameAs(approved);
        assertThat(adminController.reject(
                admin,
                "ref_reject",
                objectMapper.readTree("{\"reviewNote\":\"risk review\"}")))
                .isSameAs(rejected);
        verify(refundService).approve(ADMIN_ID, "ref_admin");
        verify(refundService).reject(
                ADMIN_ID, "ref_reject",
                new RefundApplicationService.RejectRefundCommand("risk review"));

        assertThatThrownBy(() -> adminController.reject(
                admin,
                "ref_reject",
                objectMapper.readTree("{\"reviewNote\":\"x\",\"amount\":1}")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_refund_review_note");
    }

    @Test
    void policyRoutesAcceptOnlyPublicationFieldsAndAuthenticatedActor() throws Exception {
        Jwt admin = jwt(ADMIN_ID, TENANT_ID);
        Instant effectiveAt = Instant.parse("2030-02-03T04:05:06Z");
        RefundPolicyService.PublishPolicyCommand command =
                new RefundPolicyService.PublishPolicyCommand(2, 500, 100, 7, 1000, effectiveAt);
        RefundPolicyService.RefundPolicy policy = new RefundPolicyService.RefundPolicy(
                2, 500, 100, 7, 1000, effectiveAt, true, ADMIN_ID, Instant.now());
        RefundPolicyService.PageResult<RefundPolicyService.RefundPolicy> page =
                new RefundPolicyService.PageResult<>(List.of(policy), 1, 0, 20);
        when(policyService.listPublished(0, 20)).thenReturn(page);
        when(policyService.publish(ADMIN_ID, command)).thenReturn(policy);

        assertThat(policyController.list(0, 20)).isEqualTo(page);
        var created = policyController.publish(admin, objectMapper.readTree("""
                {
                  "version":2,
                  "feeBps":500,
                  "minimumFee":100,
                  "refundWindowDays":7,
                  "maximumUsageBps":1000,
                  "effectiveAt":"2030-02-03T04:05:06Z"
                }
                """));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isSameAs(policy);
        verify(policyService).publish(ADMIN_ID, command);

        assertThatThrownBy(() -> policyController.publish(admin, objectMapper.readTree("""
                {
                  "version":3,
                  "feeBps":500,
                  "minimumFee":100,
                  "refundWindowDays":7,
                  "maximumUsageBps":1000,
                  "effectiveAt":"2030-02-03T04:05:06Z",
                  "published":false
                }
                """)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_refund_policy");

        assertThatThrownBy(() -> policyController.publish(admin, objectMapper.readTree("""
                {
                  "version":4294967297,
                  "feeBps":500,
                  "minimumFee":100,
                  "refundWindowDays":7,
                  "maximumUsageBps":1000,
                  "effectiveAt":"2030-02-03T04:05:06Z"
                }
                """)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_refund_policy");
    }

    private Jwt jwt(UUID userId, UUID tenantId) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("tenant_id", tenantId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
    }

    private RefundApplicationService.RefundView refundView(String refundNo, String status) {
        Instant now = Instant.now();
        return new RefundApplicationService.RefundView(
                refundNo, "ord_user", status, "FULL", 1000, "reason", null,
                null, null, null, null, now, now);
    }
}
