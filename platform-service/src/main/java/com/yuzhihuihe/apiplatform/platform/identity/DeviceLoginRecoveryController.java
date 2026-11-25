package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationResponses.SessionResponse;

@RestController
@RequestMapping(DeviceLoginRecoveryController.PATH)
class DeviceLoginRecoveryController {
    static final String PATH = "/console/platform/auth/device-recovery";
    static final String COOKIE = "yzh_device_recovery";
    private final DeviceLoginRecoveryService recovery;
    private final boolean secureCookies;

    DeviceLoginRecoveryController(DeviceLoginRecoveryService recovery,
            @Value("${security.cookies.secure:false}") boolean secureCookies) {
        this.recovery = recovery; this.secureCookies = secureCookies;
    }

    @PostMapping("/{id}/complete")
    ResponseEntity<SessionResponse> complete(@PathVariable UUID id,
            @CookieValue(name = COOKIE, required = false) String token,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(name = "User-Agent", required = false) String userAgent,
            @RequestBody Selection selection, HttpServletRequest request) {
        var session = recovery.complete(id, token, csrf, deviceId,
                selection == null ? null : selection.sessionIds(),
                new LoginActivityService.LoginContext(request.getRemoteAddr(), deviceId, userAgent));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                ResponseCookie.from(IdentityController.REFRESH_COOKIE, session.refreshToken())
                        .httpOnly(true).secure(secureCookies).sameSite("Strict").path("/console/platform/auth").build().toString(),
                ResponseCookie.from(IdentityController.CSRF_COOKIE, session.csrfToken())
                        .secure(secureCookies).sameSite("Strict").path("/").build().toString(),
                credentialCookie("", Instant.EPOCH, secureCookies).toString())
                .body(IdentityController.sessionResponseBody(session));
    }

    static ResponseCookie credentialCookie(String token, Instant expires, boolean secure) {
        return ResponseCookie.from(COOKIE, token).httpOnly(true).secure(secure).sameSite("Strict")
                .path(PATH).maxAge(expires.equals(Instant.EPOCH) ? Duration.ZERO : Duration.between(Instant.now(), expires)).build();
    }

    record Selection(List<UUID> sessionIds) { }
    record RequiredResponse(String code, UUID requestId, Instant expiresAt, int deviceLimit,
            List<DeviceLoginRecoveryService.DeviceView> devices, String csrfToken) { }
}
