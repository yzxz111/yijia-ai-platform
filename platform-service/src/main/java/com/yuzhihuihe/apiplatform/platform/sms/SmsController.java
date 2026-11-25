package com.yuzhihuihe.apiplatform.platform.sms;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console/platform")
public class SmsController {

    private static final Set<SmsPurpose> SENSITIVE_PURPOSES = Set.of(
            SmsPurpose.CHANGE_PASSWORD, SmsPurpose.PAYMENT_CONFIRM, SmsPurpose.API_KEY_RESET,
            SmsPurpose.PHONE_CHANGE_CONFIRM);

    private final ObjectProvider<SmsChallengeService> challenges;
    private final SmsRequestFingerprintService fingerprints;
    private final SmsChallengeIpRateLimiter challengeIpRateLimiter;
    private final SliderCaptchaService sliderCaptchas;

    public SmsController(
            ObjectProvider<SmsChallengeService> challenges,
            SmsRequestFingerprintService fingerprints,
            SmsChallengeIpRateLimiter challengeIpRateLimiter) {
        this(challenges, fingerprints, challengeIpRateLimiter, SliderCaptchaService.disabled());
    }

    @Autowired
    public SmsController(
            ObjectProvider<SmsChallengeService> challenges,
            SmsRequestFingerprintService fingerprints,
            SmsChallengeIpRateLimiter challengeIpRateLimiter,
            SliderCaptchaService sliderCaptchas) {
        this.challenges = challenges;
        this.fingerprints = fingerprints;
        this.challengeIpRateLimiter = challengeIpRateLimiter;
        this.sliderCaptchas = sliderCaptchas;
    }

    @PostMapping("/auth/sms/challenges")
    ResponseEntity<ChallengeResponse> registerChallenge(
            @RequestBody ChallengeRequest request,
            HttpServletRequest servletRequest) {
        if (request == null || request.purpose() != SmsPurpose.REGISTER) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sms_purpose");
        }
        SmsChallengeService service = required();
        String requestIpHash = fingerprints.fingerprint(servletRequest);
        SliderCaptchaService.ProofReservation captchaReservation =
                sliderCaptchas.reserveProof(request.captchaProof(), requestIpHash);
        SmsChallengeIpRateLimiter.Reservation reservation = null;
        SmsChallengeService.SmsChallenge issued;
        try {
            reservation = challengeIpRateLimiter.reserve(
                    SmsChallengeIpRateLimiter.Scope.REGISTER, requestIpHash);
            issued = service.issue(SmsPurpose.REGISTER, request.phone(), null, null, requestIpHash);
        } catch (RuntimeException error) {
            throw releaseAndMap(captchaReservation, reservation, error);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ChallengeResponse.from(issued));
    }

    @PostMapping("/auth/sms-login/challenges")
    ResponseEntity<ChallengeResponse> loginChallenge(
            @RequestBody LoginChallengeRequest request,
            HttpServletRequest servletRequest) {
        SmsChallengeService service = required();
        String requestIpHash = fingerprints.fingerprint(servletRequest);
        SliderCaptchaService.ProofReservation captchaReservation = sliderCaptchas.reserveProof(
                request == null ? null : request.captchaProof(), requestIpHash);
        SmsChallengeIpRateLimiter.Reservation reservation = null;
        SmsChallengeService.SmsChallenge issued;
        try {
            reservation = challengeIpRateLimiter.reserve(
                    SmsChallengeIpRateLimiter.Scope.LOGIN, requestIpHash);
            issued = service.issueLogin(request == null ? null : request.phone(), requestIpHash);
        } catch (RuntimeException error) {
            throw releaseAndMap(captchaReservation, reservation, error);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ChallengeResponse.from(issued));
    }

    @PostMapping("/auth/password-reset/sms/challenges")
    ResponseEntity<ChallengeResponse> passwordResetChallenge(
            @RequestBody LoginChallengeRequest request,
            HttpServletRequest servletRequest) {
        SmsChallengeService service = required();
        String requestIpHash = fingerprints.fingerprint(servletRequest);
        SliderCaptchaService.ProofReservation captchaReservation = sliderCaptchas.reserveProof(
                request == null ? null : request.captchaProof(), requestIpHash);
        SmsChallengeIpRateLimiter.Reservation reservation = null;
        SmsChallengeService.SmsChallenge issued;
        try {
            reservation = challengeIpRateLimiter.reserve(
                    SmsChallengeIpRateLimiter.Scope.LOGIN, requestIpHash);
            issued = service.issuePasswordReset(
                    request == null ? null : request.phone(), requestIpHash);
        } catch (RuntimeException error) {
            throw releaseAndMap(captchaReservation, reservation, error);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ChallengeResponse.from(issued));
    }

    @PostMapping("/sms/challenges")
    ResponseEntity<ChallengeResponse> sensitiveChallenge(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody ChallengeRequest request,
            HttpServletRequest servletRequest) {
        if (request == null || !SENSITIVE_PURPOSES.contains(request.purpose()) || request.phone() != null) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sms_purpose");
        }
        UUID tenantId = tenantId(jwt);
        UUID userId = userId(jwt);
        try {
            SmsChallengeService.SmsChallenge issued = required().issueForBoundPhone(
                    request.purpose(), tenantId, userId,
                    fingerprints.fingerprint(servletRequest));
            return ResponseEntity.status(HttpStatus.CREATED).body(ChallengeResponse.from(issued));
        } catch (SmsApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw SmsApiException.from(error);
        }
    }

    @PostMapping("/sms/challenges/{id}/verify")
    ProofResponse verify(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody VerifyRequest request) {
        UUID tenantId = tenantId(jwt);
        UUID userId = userId(jwt);
        try {
            SmsChallengeService.VerifiedChallenge verified = required().verifyOwned(
                    id, request == null ? null : request.code(), tenantId, userId);
            return new ProofResponse(
                    verified.proofToken(), verified.purpose(), verified.proofExpiresAt());
        } catch (SmsApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw SmsApiException.from(error);
        }
    }

    private SmsChallengeService required() {
        SmsChallengeService service = challenges.getIfAvailable();
        if (service == null) {
            throw new SmsApiException(HttpStatus.SERVICE_UNAVAILABLE, "sms_disabled");
        }
        return service;
    }

    private SmsApiException releaseAndMap(
            SliderCaptchaService.ProofReservation captchaReservation,
            SmsChallengeIpRateLimiter.Reservation reservation,
            RuntimeException error) {
        challengeIpRateLimiter.release(reservation);
        sliderCaptchas.release(captchaReservation);
        return error instanceof SmsApiException apiError ? apiError : SmsApiException.from(error);
    }

    private UUID userId(Jwt jwt) {
        return identityClaim(jwt, true);
    }

    private UUID tenantId(Jwt jwt) {
        return identityClaim(jwt, false);
    }

    private UUID identityClaim(Jwt jwt, boolean subject) {
        try {
            String value = jwt == null ? null
                    : subject ? jwt.getSubject() : jwt.getClaimAsString("tenant_id");
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing identity claim");
            }
            return UUID.fromString(value);
        } catch (RuntimeException invalidClaim) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "authentication is required");
        }
    }

    record ChallengeRequest(SmsPurpose purpose, String phone, String captchaProof) { }

    record LoginChallengeRequest(String phone, String captchaProof) { }

    record VerifyRequest(String code) { }

    record ChallengeResponse(UUID id, SmsPurpose purpose, String maskedPhone, Instant expiresAt) {
        static ChallengeResponse from(SmsChallengeService.SmsChallenge challenge) {
            return new ChallengeResponse(
                    challenge.id(), challenge.purpose(), challenge.maskedPhone(), challenge.expiresAt());
        }
    }

    record ProofResponse(String smsProof, SmsPurpose purpose, Instant expiresAt) { }
}
