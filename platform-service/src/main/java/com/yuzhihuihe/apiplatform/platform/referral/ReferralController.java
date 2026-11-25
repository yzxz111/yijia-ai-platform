package com.yuzhihuihe.apiplatform.platform.referral;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console/platform/referrals")
final class ReferralController {

    private final ReferralService referrals;

    ReferralController(ReferralService referrals) {
        this.referrals = referrals;
    }

    @GetMapping("/me")
    ReferralService.Overview me(@AuthenticationPrincipal Jwt jwt) {
        return referrals.overview(tenantId(jwt), userId(jwt));
    }

    @PostMapping("/codes")
    ReferralService.Overview ensureCode(@AuthenticationPrincipal Jwt jwt) {
        return referrals.ensureCode(tenantId(jwt), userId(jwt));
    }

    @PostMapping("/codes/rotate")
    ReferralService.Overview rotateCode(@AuthenticationPrincipal Jwt jwt) {
        return referrals.rotateCode(tenantId(jwt), userId(jwt));
    }

    @GetMapping("/me/invitees")
    ReferralService.InviteePage invitees(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return referrals.invitees(tenantId(jwt), userId(jwt), limit, cursor);
    }

    private UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException invalid) {
            throw ReferralApiException.unauthenticated();
        }
    }

    private UUID tenantId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getClaimAsString("tenant_id"));
        } catch (RuntimeException invalid) {
            throw ReferralApiException.unauthenticated();
        }
    }
}
