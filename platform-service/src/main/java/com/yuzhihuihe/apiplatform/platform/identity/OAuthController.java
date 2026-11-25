package com.yuzhihuihe.apiplatform.platform.identity;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.identity.api.OAuthResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 桌面应用使用的 OAuth 2.0 端点。
 *
 * <ul>
 *   <li>{@code GET /auth/authorize} – 校验请求，并将系统浏览器重定向到
 *       携带事务标识的 console 登录页。</li>
 *   <li>{@code POST /auth/authorize/complete} – console 侧交接；为已登录用户
 *       创建授权码，并返回其注册的原生应用重定向。</li>
 *   <li>{@code POST /auth/token} – 授权码兑换与刷新令牌轮换。</li>
 *   <li>{@code POST /auth/revoke} – RFC 7009 撤销。</li>
 *   <li>{@code GET /auth/userinfo} – 兼容 OIDC 的用户信息。</li>
 * </ul>
 */
@RestController
@RequestMapping("/console/platform/auth")
class OAuthController {

    private final OAuthAuthorizationService authorizations;
    private final OAuthTokenService tokenService;
    private final LoginHandoffService handoffs;

    OAuthController(
            OAuthAuthorizationService authorizations,
            OAuthTokenService tokenService,
            LoginHandoffService handoffs) {
        this.authorizations = authorizations;
        this.tokenService = tokenService;
        this.handoffs = handoffs;
    }

    @GetMapping("/authorize")
    ResponseEntity<Void> authorize(@RequestParam MultiValueMap<String, String> query) {
        OAuthAuthorizationService.AuthorizationStart start = authorizations.begin(
                OAuthAuthorizationService.AuthorizationParams.of(firstValues(query)));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(start.loginRedirectUrl()))
                .build();
    }

    @PostMapping("/authorize/complete")
    OAuthResponses.AuthorizationCompletion complete(
            @RequestBody(required = false) CompletionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID transactionId = request == null ? null : request.transactionId();
        OAuthAuthorizationService.AuthorizationCompletion completion = authorizations.complete(
                transactionId, currentUser(jwt));
        return new OAuthResponses.AuthorizationCompletion(
                completion.redirectUrl(), completion.scopes(), completion.expiresAt());
    }

    @PostMapping("/authorize/cancel")
    OAuthResponses.AuthorizationCancellation cancel(
            @RequestBody(required = false) CompletionRequest request) {
        UUID transactionId = request == null ? null : request.transactionId();
        if (transactionId == null) {
            throw OAuthException.invalidRequest("transactionId is required.");
        }
        String redirect = authorizations.cancel(transactionId, "access_denied");
        return new OAuthResponses.AuthorizationCancellation(redirect);
    }

    @PostMapping("/token")
    OAuthResponses.TokenResponse token(
            @RequestParam MultiValueMap<String, String> form,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(name = "User-Agent", required = false) String userAgent,
            HttpServletRequest servletRequest) {
        String grantType = form.getFirst("grant_type");
        String clientId = form.getFirst("client_id");
        LoginActivityService.LoginContext context = new LoginActivityService.LoginContext(
                servletRequest == null ? null : servletRequest.getRemoteAddr(), deviceId, userAgent);
        OAuthTokenService.TokenIssue issue = switch (grantType == null ? "" : grantType) {
            case "authorization_code" -> tokenService.exchangeAuthorizationCode(
                    clientId,
                    form.getFirst("code"),
                    form.getFirst("redirect_uri"),
                    form.getFirst("code_verifier"),
                    deviceId,
                    context);
            case "refresh_token" -> tokenService.refresh(
                    clientId, form.getFirst("refresh_token"), deviceId, context);
            default -> throw OAuthException.unsupportedGrantType();
        };
        return new OAuthResponses.TokenResponse(
                issue.accessToken(), issue.refreshToken(), "Bearer", issue.expiresIn(), issue.scope());
    }

    @PostMapping("/revoke")
    ResponseEntity<Void> revoke(@RequestParam MultiValueMap<String, String> form) {
        tokenService.revoke(
                form.getFirst("client_id"),
                form.getFirst("token"),
                form.getFirst("token_type_hint"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/userinfo")
    OAuthResponses.UserInfoResponse userinfo(@AuthenticationPrincipal Jwt jwt) {
        IdentityService.UserProfile user = currentUser(jwt);
        return new OAuthResponses.UserInfoResponse(
                user.id(), user.tenantId(), user.email(), user.displayName(), user.role());
    }

    private IdentityService.UserProfile currentUser(Jwt jwt) {
        if (jwt == null) {
            throw OAuthException.invalidToken();
        }
        try {
            return handoffs.loadUser(
                    UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("tenant_id")));
        } catch (RuntimeException invalid) {
            throw OAuthException.invalidToken();
        }
    }

    private static Map<String, String> firstValues(MultiValueMap<String, String> values) {
        Map<String, String> flattened = new LinkedHashMap<>();
        values.forEach((key, list) -> {
            if (list != null && !list.isEmpty()) {
                flattened.put(key, list.getFirst());
            }
        });
        return flattened;
    }

    record CompletionRequest(UUID transactionId) { }
}
