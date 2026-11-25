package com.yuzhihuihe.apiplatform.platform.safety;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console/platform/admin/content-safety")
public final class AdminContentSafetyController {
    private final ContentSafetyAdminService service;
    private final ContentSafetyPolicyService policies;
    private final ContentSafetyAdminOperationService operations;

    public AdminContentSafetyController(
            ContentSafetyAdminService service,
            ContentSafetyPolicyService policies,
            ContentSafetyAdminOperationService operations) {
        this.service = service;
        this.policies = policies;
        this.operations = operations;
    }

    @GetMapping("/policies/current")
    public ContentSafetyPolicyService.PolicySnapshotView currentPolicy() { return policies.publishedSnapshot(); }

    @PostMapping("/policies/drafts")
    public ContentSafetyPolicyService.PolicyVersionView draft(@RequestBody Map<String, Object> config,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actor = actor(jwt);
        return operations.execute(
                actor, idempotencyKey, "CONTENT_SAFETY_POLICY_DRAFT_CREATE", config,
                ContentSafetyPolicyService.PolicyVersionView.class,
                () -> policies.createDraft(config, actor),
                response -> new ContentSafetyAdminOperationService.AuditTarget(
                        null, "CONTENT_SAFETY_POLICY", String.valueOf(response.version())));
    }

    @PostMapping("/policies/{version}/publish")
    public ContentSafetyPolicyService.PolicyVersionView publish(@PathVariable long version,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actor = actor(jwt);
        return operations.execute(
                actor, idempotencyKey, "CONTENT_SAFETY_POLICY_PUBLISH", Map.of("version", version),
                ContentSafetyPolicyService.PolicyVersionView.class,
                () -> policies.publish(version, actor),
                response -> new ContentSafetyAdminOperationService.AuditTarget(
                        null, "CONTENT_SAFETY_POLICY", String.valueOf(response.version())));
    }

    @GetMapping("/cases")
    public ContentSafetyAdminService.PageResult<ContentSafetyAdminService.CaseSummary> cases(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listCases(status, query, page, size);
    }

    @PostMapping("/cases/{caseNo}/transition")
    public ContentSafetyCaseDto transition(@PathVariable String caseNo,
            @RequestBody TransitionRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actor = actor(jwt);
        return operations.execute(
                actor, idempotencyKey, "CONTENT_SAFETY_CASE_TRANSITION",
                Map.of("caseNo", caseNo, "request", request), ContentSafetyCaseDto.class,
                () -> service.transition(caseNo, request.status(), actor, request.note()),
                response -> new ContentSafetyAdminOperationService.AuditTarget(
                        response.tenantId(), "CONTENT_SAFETY_CASE", response.caseNo()));
    }

    @PostMapping("/cases/{caseNo}/resolve")
    public ContentSafetyCaseDto resolve(@PathVariable String caseNo,
            @RequestBody ResolveRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actor = actor(jwt);
        return operations.execute(
                actor, idempotencyKey, "CONTENT_SAFETY_CASE_RESOLVE",
                Map.of("caseNo", caseNo, "request", request), ContentSafetyCaseDto.class,
                () -> service.resolve(caseNo, request.action(), actor, request.note()),
                response -> new ContentSafetyAdminOperationService.AuditTarget(
                        response.tenantId(), "CONTENT_SAFETY_CASE", response.caseNo()));
    }

    @PostMapping("/enforcements")
    public ContentSafetyAdminService.EnforcementView enforce(
            @RequestBody EnforcementRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actor = actor(jwt);
        return operations.execute(
                actor, idempotencyKey, "CONTENT_SAFETY_ENFORCEMENT_APPLY", request,
                ContentSafetyAdminService.EnforcementView.class,
                () -> service.enforce(
                        request.tenantId(), request.userId(), request.state(), request.reason(),
                        request.caseNo(), actor),
                response -> new ContentSafetyAdminOperationService.AuditTarget(
                        request.tenantId(), "CONTENT_SAFETY_ENFORCEMENT", request.userId().toString()));
    }

    @PostMapping("/enforcements/clear")
    public ContentSafetyAdminService.EnforcementView clear(
            @RequestBody ClearRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actor = actor(jwt);
        return operations.execute(
                actor, idempotencyKey, "CONTENT_SAFETY_ENFORCEMENT_CLEAR", request,
                ContentSafetyAdminService.EnforcementView.class,
                () -> service.clear(request.tenantId(), request.userId(), actor, request.reason()),
                response -> new ContentSafetyAdminOperationService.AuditTarget(
                        request.tenantId(), "CONTENT_SAFETY_ENFORCEMENT", request.userId().toString()));
    }

    @GetMapping("/appeals")
    public ContentSafetyAdminService.PageResult<ContentSafetyAdminService.AppealView> appeals(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.appeals(null, null, query, page, size);
    }

    @PostMapping("/appeals/{businessNo}/process")
    public ContentSafetyAdminService.AppealView process(@PathVariable String businessNo,
            @RequestBody AppealProcessRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actor = actor(jwt);
        return operations.execute(
                actor, idempotencyKey, "CONTENT_SAFETY_APPEAL_PROCESS",
                Map.of("businessNo", businessNo, "request", request),
                ContentSafetyAdminService.AppealView.class,
                () -> service.processAppeal(businessNo, request.status(), actor, request.note()),
                response -> new ContentSafetyAdminOperationService.AuditTarget(
                        null, "CONTENT_SAFETY_APPEAL", response.businessNo()));
    }

    @PostMapping("/appeals/{businessNo}/withdraw")
    public ContentSafetyAdminService.AppealView withdraw(
            @PathVariable String businessNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actor = actor(jwt);
        return operations.execute(
                actor, idempotencyKey, "CONTENT_SAFETY_APPEAL_WITHDRAW",
                Map.of("businessNo", businessNo), ContentSafetyAdminService.AppealView.class,
                () -> service.withdrawAppealAsAdmin(businessNo, actor),
                response -> new ContentSafetyAdminOperationService.AuditTarget(
                        null, "CONTENT_SAFETY_APPEAL", response.businessNo()));
    }

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record TransitionRequest(String status, String note) { }
    public record ResolveRequest(String action, String note) { }
    public record EnforcementRequest(UUID tenantId, UUID userId, String state, String reason, String caseNo) { }
    public record ClearRequest(UUID tenantId, UUID userId, String reason) { }
    public record AppealProcessRequest(String status, String note) { }
}
