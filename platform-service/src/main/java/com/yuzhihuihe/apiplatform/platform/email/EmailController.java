package com.yuzhihuihe.apiplatform.platform.email;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.identity.IdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console/platform/profile/email")
public final class EmailController {
    private final EmailVerificationService verifications;
    private final IdentityService identities;

    public EmailController(EmailVerificationService verifications, IdentityService identities) {
        this.verifications = verifications;
        this.identities = identities;
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void bind(@AuthenticationPrincipal Jwt jwt, @RequestBody EmailBindRequest request) {
        UUID tenantId = tenantId(jwt);
        UUID userId = userId(jwt);
        String currentEmail = identities.boundEmail(tenantId, userId);
        if (currentEmail != null) {
            verifications.consume(
                    request == null ? null : request.currentChallengeId(),
                    currentEmail,
                    request == null ? null : request.currentVerificationCode());
        }
        String email = verifications.consume(request == null ? null : request.challengeId(),
                request == null ? null : request.email(), request == null ? null : request.verificationCode());
        identities.bindEmail(tenantId, userId, email);
    }

    private UUID userId(Jwt jwt) { return claim(jwt, true); }
    private UUID tenantId(Jwt jwt) { return claim(jwt, false); }

    private UUID claim(Jwt jwt, boolean subject) {
        try {
            return UUID.fromString(jwt == null ? null : subject
                    ? jwt.getSubject() : jwt.getClaimAsString("tenant_id"));
        } catch (RuntimeException error) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "authentication is required");
        }
    }

    public record EmailBindRequest(
            String email,
            UUID challengeId,
            String verificationCode,
            UUID currentChallengeId,
            String currentVerificationCode) { }
}
