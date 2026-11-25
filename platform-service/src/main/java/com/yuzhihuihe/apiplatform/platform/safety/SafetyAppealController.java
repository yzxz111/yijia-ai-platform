package com.yuzhihuihe.apiplatform.platform.safety;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/console/platform/safety/appeals")
public final class SafetyAppealController {
    private final ContentSafetyAdminService service;
    public SafetyAppealController(ContentSafetyAdminService service) { this.service = service; }

    @GetMapping
    public ContentSafetyAdminService.PageResult<ContentSafetyAdminService.AppealView> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.appeals(tenant(jwt), user(jwt), "", page, size);
    }

    @GetMapping("/cases")
    public ContentSafetyAdminService.PageResult<ContentSafetyAdminService.UserCaseView> cases(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.userCases(tenant(jwt), user(jwt), page, size);
    }

    @PostMapping
    public ContentSafetyAdminService.AppealView create(@RequestBody AppealRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (request == null) {
            throw new IllegalArgumentException("invalid_appeal_request");
        }
        return service.createAppeal(tenant(jwt), user(jwt), request.caseNo(), request.reason());
    }

    @PostMapping("/{businessNo}/withdraw")
    public ContentSafetyAdminService.AppealView withdraw(
            @PathVariable String businessNo,
            @AuthenticationPrincipal Jwt jwt) {
        return service.withdrawAppeal(tenant(jwt), user(jwt), businessNo);
    }

    private UUID tenant(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("tenant_id")); }
    private UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record AppealRequest(String caseNo, String reason) { }
}
