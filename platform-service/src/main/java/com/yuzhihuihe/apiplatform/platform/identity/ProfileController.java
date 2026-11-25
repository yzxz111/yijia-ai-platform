package com.yuzhihuihe.apiplatform.platform.identity;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.identity.api.ProfileContracts.BindPhoneRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.ProfileContracts.ChangePasswordRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.ProfileContracts.DisplayNameRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.ProfileContracts.DisplayNameResponse;
import com.yuzhihuihe.apiplatform.platform.identity.api.ProfileContracts.LoginDeviceLimitRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.ProfileContracts.LoginDeviceLimitResponse;
import com.yuzhihuihe.apiplatform.platform.identity.api.ProfileContracts.PhoneChallengeRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.ProfileContracts.SmsPreferenceRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.yuzhihuihe.apiplatform.platform.sms.SmsChallengeService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsPurpose;
import com.yuzhihuihe.apiplatform.platform.sms.SmsRequestFingerprintService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/console/platform/profile")
public class ProfileController {

    private final IdentityService identityService;
    private final SmsRequestFingerprintService fingerprints;
    private final boolean secureCookies;

    public ProfileController(
            IdentityService identityService,
            SmsRequestFingerprintService fingerprints) {
        this(identityService, fingerprints, false);
    }

    @Autowired
    public ProfileController(
            IdentityService identityService,
            SmsRequestFingerprintService fingerprints,
            @Value("${security.cookies.secure:false}") boolean secureCookies) {
        this.identityService = identityService;
        this.fingerprints = fingerprints;
        this.secureCookies = secureCookies;
    }

    @GetMapping
    IdentityService.ProfileView profile(@AuthenticationPrincipal Jwt jwt) {
        return identityService.profile(tenantId(jwt), userId(jwt));
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateAvatar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw IdentityApiException.avatarFileRequired();
        }
        try {
            identityService.updateAvatar(
                    tenantId(jwt), userId(jwt), file.getBytes(), file.getContentType());
        } catch (java.io.IOException error) {
            throw IdentityApiException.avatarFileInvalid();
        }
    }

    @GetMapping("/avatar")
    ResponseEntity<byte[]> avatar(@AuthenticationPrincipal Jwt jwt) {
        IdentityService.AvatarContent avatar = identityService.avatar(tenantId(jwt), userId(jwt));
        if (avatar == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatar.contentType()))
                .cacheControl(CacheControl.noCache())
                .body(avatar.bytes());
    }

    @PostMapping("/password")
    ResponseEntity<?> changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody ChangePasswordRequest request,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(name = "User-Agent", required = false) String userAgent,
            HttpServletRequest servletRequest) {
        IdentityService.AccessSession session = identityService.changePassword(
                tenantId(jwt), userId(jwt),
                request == null ? null : request.currentPassword(),
                request == null ? null : request.newPassword(),
                request == null ? null : request.smsProof(),
                request == null ? null : request.invitationCode(),
                new LoginActivityService.LoginContext(
                        servletRequest == null ? null : servletRequest.getRemoteAddr(),
                        deviceId,
                        userAgent));
        if (session == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session).toString(),
                        csrfCookie(session.csrfToken()).toString())
                .body(IdentityController.sessionResponseBody(session));
    }

    private ResponseCookie refreshCookie(IdentityService.AccessSession session) {
        return ResponseCookie.from("yzh_refresh", session.refreshToken())
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite("Strict")
                .path("/console/platform/auth")
                .build();
    }

    private ResponseCookie csrfCookie(String csrfToken) {
        return ResponseCookie.from("yzh_csrf", csrfToken)
                .httpOnly(false)
                .secure(secureCookies)
                .sameSite("Strict")
                .path("/")
                .build();
    }

    @PostMapping("/phone/challenges")
    @ResponseStatus(HttpStatus.CREATED)
    SmsChallengeService.SmsChallenge phoneChallenge(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody PhoneChallengeRequest request,
            HttpServletRequest servletRequest) {
        return identityService.issuePhoneChallenge(
                tenantId(jwt), userId(jwt),
                request == null ? null : request.purpose(),
                request == null ? null : request.phone(),
                fingerprints.fingerprint(servletRequest));
    }

    @PutMapping("/phone")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void bindPhone(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody BindPhoneRequest request) {
        identityService.bindPhone(
                tenantId(jwt), userId(jwt),
                request == null ? null : request.challengeId(),
                request == null ? null : request.purpose(),
                request == null ? null : request.currentPhoneProof());
    }

    @PatchMapping("/sms-preference")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void smsPreference(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody SmsPreferenceRequest request) {
        if (request == null || request.enabled() == null) {
            throw new com.yuzhihuihe.apiplatform.platform.sms.SmsApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sms_preference");
        }
        identityService.updateSmsPreference(tenantId(jwt), userId(jwt), request.enabled());
    }

    @PatchMapping("/display-name")
    DisplayNameResponse displayName(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody DisplayNameRequest request) {
        return new DisplayNameResponse(identityService.updateDisplayName(
                tenantId(jwt), userId(jwt), request == null ? null : request.displayName()));
    }

    @PatchMapping("/device-limit")
    LoginDeviceLimitResponse loginDeviceLimit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody LoginDeviceLimitRequest request) {
        return new LoginDeviceLimitResponse(identityService.updateLoginDeviceLimit(
                tenantId(jwt), userId(jwt), request == null ? null : request.maxLoginDevices()));
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

}
