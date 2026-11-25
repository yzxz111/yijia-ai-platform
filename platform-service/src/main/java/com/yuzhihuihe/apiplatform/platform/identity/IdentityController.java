package com.yuzhihuihe.apiplatform.platform.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.email.EmailVerificationService;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationRequests.EmailChallengeRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationRequests.CompletePhoneRegistrationRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationRequests.CompleteEmailRegistrationRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationRequests.EmailIdentityRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationRequests.IdentityRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationRequests.LoginRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationRequests.PasswordResetRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationRequests.RegisterRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationRequests.SmsLoginRequest;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationResponses.AccountStateResponse;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationResponses.LoginHandoffPendingResponse;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationResponses.PhoneRegistrationPendingResponse;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationResponses.EmailRegistrationPendingResponse;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationResponses.SessionResponse;
import com.yuzhihuihe.apiplatform.platform.identity.api.AuthenticationResponses.UserResponse;
import com.yuzhihuihe.apiplatform.platform.identity.api.IdentityErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console/platform")
public class IdentityController {

    static final String REFRESH_COOKIE = "yzh_refresh";
    static final String CSRF_COOKIE = "yzh_csrf";
    static final String HANDOFF_COOKIE = "yzh_login_handoff";
    static final String HANDOFF_CSRF_COOKIE = "yzh_login_handoff_csrf";
    static final String HANDOFF_PATH = "/console/platform/auth/login-handoffs";

    private final IdentityService identityService;
    private final LoginHandoffService handoffs;
    private final EmailVerificationService emailVerifications;
    /**
     * 浏览器页会话：本地线的 X-Browser-Page / X-Browser-Sequence 心跳与 browser-close
     * 都经它处理。合并时它被对方版本覆盖掉了，导致前端「浏览器页」链路整体失效。
     */
    private final BrowserSessionService browserSessions;
    private final boolean secureCookies;

    public IdentityController(
            IdentityService identityService,
            LoginHandoffService handoffs,
            EmailVerificationService emailVerifications,
            BrowserSessionService browserSessions,
            @Value("${security.cookies.secure:false}") boolean secureCookies) {
        this.identityService = identityService;
        this.handoffs = handoffs;
        this.emailVerifications = emailVerifications;
        this.browserSessions = browserSessions;
        this.secureCookies = secureCookies;
    }

    @PostMapping("/auth/register")
    ResponseEntity<?> register(
            @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        return loginResponse(HttpStatus.CREATED, identityService.register(
                request.email(), request.password(), request.displayName(), request.phone(),
                request.challengeId(), request.verificationCode(),
                request.termsAccepted(), request.consentVersion(), request.invitationCode(),
                loginContext(servletRequest, deviceId, userAgent)));
    }

    @PostMapping("/auth/login")
    ResponseEntity<?> login(
            @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        String identifier = request == null ? null
                : request.identifier() == null ? request.email() : request.identifier();
        return loginResponse(HttpStatus.OK, identityService.login(
                identifier, request == null ? null : request.password(),
                loginContext(servletRequest, deviceId, userAgent)));
    }

    @PostMapping("/auth/identity")
    ResponseEntity<?> identity(
            @RequestBody IdentityRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        IdentityService.IdentityLoginResult result = identityService.authenticateWithPhoneChallenge(
                request == null ? null : request.identifier(),
                request == null ? null : request.challengeId(),
                request == null ? null : request.verificationCode(),
                request == null ? null : request.termsAccepted(),
                request == null ? null : request.consentVersion(),
                loginContext(servletRequest, deviceId, userAgent));
        if (result.registrationPending()) {
            return ResponseEntity.accepted().body(new PhoneRegistrationPendingResponse(
                    "phone_registration_pending", result.pendingChallengeId(),
                    result.pendingExpiresAt()));
        }
        return loginResponse(result.created() ? HttpStatus.CREATED : HttpStatus.OK, result.loginResult());
    }

    @PostMapping("/auth/identity/complete-registration")
    ResponseEntity<?> completePhoneRegistration(
            @RequestBody CompletePhoneRegistrationRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        LoginHandoffService.LoginResult result = identityService.completePhoneRegistration(
                request == null ? null : request.challengeId(),
                request == null ? null : request.newPassword(),
                request == null ? null : request.termsAccepted(),
                request == null ? null : request.consentVersion(),
                request == null ? null : request.invitationCode(),
                loginContext(servletRequest, deviceId, userAgent));
        return loginResponse(HttpStatus.CREATED, result);
    }

    @PostMapping("/auth/email/challenges")
    ResponseEntity<EmailVerificationService.EmailChallenge> emailChallenge(
            @RequestBody EmailChallengeRequest request,
            HttpServletRequest servletRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(emailVerifications.issue(
                request == null ? null : request.email(), servletRequest.getRemoteAddr()));
    }

    @PostMapping("/auth/email-identity")
    ResponseEntity<?> emailIdentity(
            @RequestBody EmailIdentityRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        EmailVerificationService.VerifiedRegistration verified = emailVerifications.verifyForIdentity(
                request == null ? null : request.challengeId(),
                request == null ? null : request.email(),
                request == null ? null : request.verificationCode());
        IdentityService.IdentityLoginResult result = identityService.authenticateWithVerifiedEmail(
                verified, request == null ? null : request.termsAccepted(),
                request == null ? null : request.consentVersion(),
                loginContext(servletRequest, deviceId, userAgent));
        if (result.registrationPending()) {
            return ResponseEntity.accepted().body(new EmailRegistrationPendingResponse(
                    "email_registration_pending", result.pendingChallengeId(),
                    result.pendingExpiresAt()));
        }
        return loginResponse(HttpStatus.OK, result.loginResult());
    }

    @PostMapping("/auth/email-identity/complete-registration")
    ResponseEntity<?> completeEmailRegistration(
            @RequestBody CompleteEmailRegistrationRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        LoginHandoffService.LoginResult result = identityService.completeEmailRegistration(
                request == null ? null : request.challengeId(),
                request == null ? null : request.newPassword(),
                request == null ? null : request.termsAccepted(),
                request == null ? null : request.consentVersion(),
                request == null ? null : request.invitationCode(),
                loginContext(servletRequest, deviceId, userAgent));
        return loginResponse(HttpStatus.CREATED, result);
    }

    @PostMapping("/auth/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@RequestBody PasswordResetRequest request) {
        String identifier = request == null || request.identifier() == null
                ? "" : request.identifier().trim();
        String newPassword = request == null ? null : request.newPassword();
        identityService.validateResetPassword(newPassword);
        if (identifier.contains("@")) {
            String email = emailVerifications.consume(
                    request.challengeId(), identifier, request.verificationCode());
            identityService.resetPasswordWithEmail(email, newPassword);
            return;
        }
        identityService.resetPasswordWithPhone(
                request == null ? null : request.challengeId(),
                request == null ? null : request.verificationCode(),
                newPassword);
    }

    @PostMapping("/auth/sms-login")
    ResponseEntity<?> smsLogin(
            @RequestBody SmsLoginRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        return loginResponse(HttpStatus.OK, identityService.loginWithSms(
                request == null ? null : request.challengeId(),
                request == null ? null : request.code(),
                loginContext(servletRequest, deviceId, userAgent)));
    }

    @PostMapping("/auth/refresh")
    ResponseEntity<SessionResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfHeader,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId) {
        verifyCsrf(csrfCookie, csrfHeader);
        return sessionResponse(
                HttpStatus.OK, identityService.refresh(refreshToken, csrfHeader, deviceId));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfHeader) {
        verifyCsrf(csrfCookie, csrfHeader);
        identityService.logout(refreshToken, csrfHeader);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        clearCookie(REFRESH_COOKIE, "/console/platform/auth", true).toString(),
                        clearCookie(CSRF_COOKIE, "/", false).toString())
                .build();
    }

    @GetMapping("/auth/me")
    UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userResponse(authenticatedTerminal(jwt).user());
    }

    @GetMapping("/auth/account-state")
    AccountStateResponse accountState(@AuthenticationPrincipal Jwt jwt) {
        LoginHandoffService.AuthenticatedTerminal terminal = authenticatedTerminal(jwt);
        return accountStateResponse(identityService.accountState(
                terminal.user().tenantId(), terminal.user().id()));
    }

    @PostMapping("/auth/terminal-presence")
    AccountStateResponse heartbeat(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(name = "X-Browser-Page", required = false) UUID pageId,
            @RequestHeader(name = "X-Browser-Sequence", required = false) Long sequence) {
        var terminal = authenticatedTerminal(jwt);
        // 没有浏览器页标识时是普通心跳；带页标识时额外登记页级会话（本地线的多标签页会话维持）。
        if (pageId == null && sequence == null) {
            return accountStateResponse(identityService.heartbeat(terminal, deviceId));
        }
        browserSessions.heartbeat(terminal, deviceId, pageId, sequence == null ? -1 : sequence);
        return accountStateResponse(
                identityService.accountState(terminal.user().tenantId(), terminal.user().id()));
    }

    @PostMapping("/auth/terminal-close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void requestBrowserClose(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId) {
        identityService.requestBrowserClose(authenticatedTerminal(jwt), deviceId);
    }

    @GetMapping("/auth/terminal-device/validate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void validateTerminalDevice(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId) {
        identityService.validateTerminalDevice(authenticatedTerminal(jwt), deviceId);
    }

    @GetMapping("/auth/login-handoffs/pending")
    ResponseEntity<LoginHandoffService.PendingApprovalView> pending(@AuthenticationPrincipal Jwt jwt) {
        return handoffs.pendingFor(authenticatedTerminal(jwt))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/auth/login-handoffs/{id}/approve")
    ResponseEntity<Void> approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        handoffs.approve(id, authenticatedTerminal(jwt));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/login-handoffs/{id}/deny")
    ResponseEntity<Void> deny(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        handoffs.deny(id, authenticatedTerminal(jwt));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/login-handoffs/{id}")
    LoginHandoffService.HandoffStatusView handoffStatus(
            @PathVariable UUID id,
            @CookieValue(name = HANDOFF_COOKIE, required = false) String pendingToken,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId) {
        return handoffs.inspect(id, pendingToken, deviceId);
    }

    @PostMapping("/auth/login-handoffs/{id}/complete")
    ResponseEntity<SessionResponse> complete(
            @PathVariable UUID id,
            @CookieValue(name = HANDOFF_COOKIE, required = false) String pendingToken,
            @CookieValue(name = HANDOFF_CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfHeader,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(name = "User-Agent", required = false) String userAgent,
            HttpServletRequest request) {
        verifyCsrf(csrfCookie, csrfHeader);
        IdentityService.AccessSession session = identityService.completeHandoff(
                id, pendingToken, deviceId, loginContext(request, deviceId, userAgent));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(session).toString(), csrfCookie(session.csrfToken()).toString(),
                        clearCookie(HANDOFF_COOKIE, HANDOFF_PATH, true).toString(),
                        clearCookie(HANDOFF_CSRF_COOKIE, "/", false).toString())
                .body(sessionResponseBody(session));
    }

    @PostMapping("/auth/login-handoffs/{id}/cancel")
    ResponseEntity<Void> cancel(
            @PathVariable UUID id,
            @CookieValue(name = HANDOFF_COOKIE, required = false) String pendingToken,
            @CookieValue(name = HANDOFF_CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfHeader,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId) {
        verifyCsrf(csrfCookie, csrfHeader);
        handoffs.cancel(id, pendingToken, deviceId);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        clearCookie(HANDOFF_COOKIE, HANDOFF_PATH, true).toString(),
                        clearCookie(HANDOFF_CSRF_COOKIE, "/", false).toString())
                .build();
    }

    @GetMapping("/auth/validate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void validateAccessToken(@AuthenticationPrincipal Jwt jwt) {
        LoginHandoffService.AuthenticatedTerminal terminal = authenticatedTerminal(jwt);
        if (!"ACTIVE".equals(identityService.accountState(
                terminal.user().tenantId(), terminal.user().id()).status())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, "account is restricted");
        }
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<IdentityErrorResponse> identityDatabaseUnavailable(DataAccessException ignored) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new IdentityErrorResponse("identity_session_unavailable"));
    }

    private ResponseEntity<?> loginResponse(
            HttpStatus completedStatus, LoginHandoffService.LoginResult result) {
        // 设备登录恢复：达到设备上限且请求方声明支持恢复（X-Device-Recovery: true）时，
        // 返回 202 + challenge，并把一次性凭据放进 HttpOnly cookie。合并时这个分支被对方的
        // 版本覆盖掉了，于是前端 DeviceLoginRecoveryPanel 成了死代码、用户只看到
        // login_device_limit_reached。
        if (result instanceof DeviceLoginRecoveryService.Challenge challenge) {
            return ResponseEntity.accepted()
                    .header(HttpHeaders.SET_COOKIE, DeviceLoginRecoveryController.credentialCookie(
                            challenge.token(), challenge.expiresAt(), secureCookies).toString())
                    .body(new DeviceLoginRecoveryController.RequiredResponse(
                            "login_device_recovery_required", challenge.requestId(),
                            challenge.expiresAt(), challenge.deviceLimit(), challenge.devices(),
                            challenge.csrf()));
        }
        if (result instanceof LoginHandoffService.Completed completed) {
            return ResponseEntity.status(completedStatus)
                    .header(HttpHeaders.SET_COOKIE,
                            refreshCookie(completed.session()).toString(),
                            csrfCookie(completed.session().csrfToken()).toString(),
                            clearCookie(HANDOFF_COOKIE, HANDOFF_PATH, true).toString(),
                            clearCookie(HANDOFF_CSRF_COOKIE, "/", false).toString())
                    .body(sessionResponseBody(completed.session()));
        }
        LoginHandoffService.Pending pending = (LoginHandoffService.Pending) result;
        String csrf = identityService.newCsrfToken();
        Instant cookieExpiry = pending.expiresAt().plusSeconds(60);
        return ResponseEntity.accepted()
                .header(HttpHeaders.SET_COOKIE,
                        clearCookie(REFRESH_COOKIE, "/console/platform/auth", true).toString(),
                        clearCookie(CSRF_COOKIE, "/", false).toString(),
                        handoffCookie(pending.pendingToken(), cookieExpiry).toString(),
                        handoffCsrfCookie(csrf, cookieExpiry).toString())
                .body(new LoginHandoffPendingResponse(
                        "login_handoff_required", pending.requestId(), "PENDING",
                        pending.expiresAt(), pending.pollAfterSeconds()));
    }

    private ResponseEntity<SessionResponse> sessionResponse(
            HttpStatusCode status, IdentityService.AccessSession session) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(session).toString(), csrfCookie(session.csrfToken()).toString())
                .body(sessionResponseBody(session));
    }

    static SessionResponse sessionResponseBody(IdentityService.AccessSession session) {
        return new SessionResponse(
                session.accessToken(), session.expiresIn(), userResponse(session.user()));
    }

    private static UserResponse userResponse(IdentityService.UserProfile user) {
        return new UserResponse(
                user.id(), user.tenantId(), user.email(), user.displayName(), user.role(),
                user.passwordSet(), user.status());
    }

    private static AccountStateResponse accountStateResponse(IdentityService.AccountState state) {
        return new AccountStateResponse(
                state.status(), state.disabledReason(), state.disabledAt());
    }

    private LoginHandoffService.AuthenticatedTerminal authenticatedTerminal(Jwt jwt) {
        if (jwt == null) throw IdentityApiException.invalidCredentials();
        try {
            Object version = jwt.getClaim("auth_version");
            String role = jwt.getClaimAsString("role");
            String clientType = jwt.getClaimAsString("client_type");
            String sessionId = jwt.getClaimAsString("session_id");
            String tenantId = jwt.getClaimAsString("tenant_id");
            String accountStatus = accountStatus(jwt);
            if (!(version instanceof Number) || role == null || tenantId == null
                    || sessionId == null || clientType == null
                    || jwt.getSubject() == null) {
                throw IdentityApiException.invalidCredentials();
            }
            IdentityService.UserProfile user = new IdentityService.UserProfile(
                    UUID.fromString(jwt.getSubject()), UUID.fromString(tenantId),
                    jwt.getClaimAsString("email"), jwt.getClaimAsString("name"), role,
                    !Boolean.FALSE.equals(jwt.getClaim("password_set")),
                    accountStatus,
                    ((Number) version).longValue());
            return new LoginHandoffService.AuthenticatedTerminal(
                    user, UUID.fromString(sessionId), clientType);
        } catch (IllegalArgumentException error) {
            throw IdentityApiException.invalidCredentials();
        }
    }

    private static String accountStatus(Jwt jwt) {
        String status = jwt.getClaimAsString("account_status");
        return status == null || status.isBlank() ? "ACTIVE" : status;
    }

    private LoginActivityService.LoginContext loginContext(
            HttpServletRequest request, String deviceId, String userAgent) {
        // X-Device-Recovery 决定「达到设备上限时是否允许走恢复流程」；它必须透传到
        // LoginContext，否则 LoginActivityService.deviceRecoverySupported 恒为 false，
        // 前端那套设备恢复界面永远拿不到 202 + challenge。
        return new LoginActivityService.LoginContext(
                request == null ? null : request.getRemoteAddr(), deviceId, userAgent,
                request != null && "true".equals(request.getHeader("X-Device-Recovery")));
    }

    private ResponseCookie refreshCookie(IdentityService.AccessSession session) {
        return ResponseCookie.from(REFRESH_COOKIE, session.refreshToken())
                .httpOnly(true).secure(secureCookies).sameSite("Strict")
                .path("/console/platform/auth")
                .build();
    }

    private ResponseCookie csrfCookie(String csrfToken) {
        return ResponseCookie.from(CSRF_COOKIE, csrfToken)
                .httpOnly(false).secure(secureCookies).sameSite("Strict")
                .path("/").build();
    }

    private ResponseCookie handoffCookie(String token, Instant expiresAt) {
        return ResponseCookie.from(HANDOFF_COOKIE, token)
                .httpOnly(true).secure(secureCookies).sameSite("Strict")
                .path(HANDOFF_PATH).maxAge(Duration.between(Instant.now(), expiresAt)).build();
    }

    private ResponseCookie handoffCsrfCookie(String token, Instant expiresAt) {
        return ResponseCookie.from(HANDOFF_CSRF_COOKIE, token)
                .httpOnly(false).secure(secureCookies).sameSite("Strict")
                .path("/").maxAge(Duration.between(Instant.now(), expiresAt)).build();
    }

    private ResponseCookie clearCookie(String name, String path, boolean httpOnly) {
        return ResponseCookie.from(name, "")
                .httpOnly(httpOnly).secure(secureCookies).sameSite("Strict")
                .path(path).maxAge(Duration.ZERO).build();
    }

    private void verifyCsrf(String cookie, String header) {
        if (cookie == null || header == null || !MessageDigest.isEqual(
                cookie.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8))) {
            throw IdentityApiException.invalidCsrfToken();
        }
    }

}
