package com.yuzhihuihe.apiplatform.platform.identity;

import org.springframework.http.HttpStatus;

/**
 * RFC 6749 第 5.2 节定义的 OAuth 2.0 错误。描述始终是一句固定、
 * 安全的文本：绝不回显堆栈信息、SQL、令牌或用户输入。
 */
final class OAuthException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final String description;

    private OAuthException(HttpStatus status, String error, String description) {
        super(error, null, false, false);
        this.status = status;
        this.error = error;
        this.description = description;
    }

    HttpStatus status() {
        return status;
    }

    String error() {
        return error;
    }

    String description() {
        return description;
    }

    static OAuthException invalidRequest(String description) {
        return new OAuthException(HttpStatus.BAD_REQUEST, "invalid_request", description);
    }

    static OAuthException invalidGrant() {
        return new OAuthException(HttpStatus.BAD_REQUEST, "invalid_grant",
                "The authorization code is invalid or expired.");
    }

    static OAuthException invalidRefreshToken() {
        return new OAuthException(HttpStatus.BAD_REQUEST, "invalid_grant",
                "The refresh token is invalid or expired.");
    }

    static OAuthException pkceVerificationFailed() {
        return new OAuthException(HttpStatus.BAD_REQUEST, "pkce_verification_failed",
                "The PKCE code verifier does not match the code challenge.");
    }

    static OAuthException redirectUriMismatch() {
        return new OAuthException(HttpStatus.BAD_REQUEST, "redirect_uri_mismatch",
                "The redirect URI is not registered for this client.");
    }

    static OAuthException invalidScope() {
        return new OAuthException(HttpStatus.BAD_REQUEST, "invalid_scope",
                "The requested scope is not allowed.");
    }

    static OAuthException unsupportedGrantType() {
        return new OAuthException(HttpStatus.BAD_REQUEST, "unsupported_grant_type",
                "The grant type is not supported.");
    }

    static OAuthException invalidClient() {
        return new OAuthException(HttpStatus.UNAUTHORIZED, "invalid_client",
                "The client is unknown or disabled.");
    }

    static OAuthException invalidToken() {
        return new OAuthException(HttpStatus.UNAUTHORIZED, "invalid_token",
                "The access token is missing or invalid.");
    }

    static OAuthException refreshTokenRevoked() {
        return new OAuthException(HttpStatus.UNAUTHORIZED, "refresh_token_revoked",
                "The refresh token has been revoked.");
    }

    /**
     * 授权码绑定的 {@code device_id} 与本次请求的 {@code X-Device-Id} 不一致。
     *
     * <p>刻意使用独立错误码而不是笼统的 {@code invalid_grant}：客户端据此可以判断
     * "授权码本身没问题、只是设备号不一致"，从而用授权时使用的设备号重试，而不是重开登录。
     * 授权码在设备校验之前不会被消费，所以这个错误是可恢复的。
     */
    static OAuthException deviceMismatch() {
        return new OAuthException(HttpStatus.BAD_REQUEST, "device_mismatch",
                "The device identifier does not match the authorization code.");
    }

    static OAuthException loginHandoffPending() {
        return new OAuthException(HttpStatus.CONFLICT, "login_handoff_pending",
                "Another device must approve this login first.");
    }

    static OAuthException loginHandoffDenied() {
        return new OAuthException(HttpStatus.FORBIDDEN, "login_handoff_denied",
                "The login request was denied on the other device.");
    }

    static OAuthException loginHandoffExpired() {
        return new OAuthException(HttpStatus.GONE, "login_handoff_expired",
                "The login request expired.");
    }

    static OAuthException invalidDeviceId() {
        return new OAuthException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_device_id",
                "The device identifier must be a 32 character lowercase hex string.");
    }

    static OAuthException authorizationUnavailable() {
        return new OAuthException(HttpStatus.SERVICE_UNAVAILABLE, "authorization_unavailable",
                "The authorization service is temporarily unavailable.");
    }

    static OAuthException transactionNotFound() {
        return new OAuthException(HttpStatus.GONE, "invalid_request",
                "The login transaction is unknown or already finished.");
    }

    static OAuthException accessDenied() {
        return new OAuthException(HttpStatus.FORBIDDEN, "access_denied",
                "The resource owner denied the request.");
    }

    static OAuthException passwordSetupRequired() {
        return new OAuthException(HttpStatus.CONFLICT, "password_setup_required",
                "Set an account password before completing desktop authorization.");
    }
}
