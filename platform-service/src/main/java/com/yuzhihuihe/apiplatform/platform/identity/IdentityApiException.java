package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.Duration;

import org.springframework.http.HttpStatus;

final class IdentityApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Duration retryAfter;

    private IdentityApiException(HttpStatus status, String code, Duration retryAfter) {
        super(code, null, false, false);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    static IdentityApiException invalidCredentials() {
        return new IdentityApiException(
                HttpStatus.UNAUTHORIZED, "invalid_credentials", null);
    }

    static IdentityApiException invalidCsrfToken() {
        return new IdentityApiException(
                HttpStatus.FORBIDDEN, "invalid_csrf_token", null);
    }

    static IdentityApiException accountSafetyRestricted() {
        return new IdentityApiException(
                HttpStatus.FORBIDDEN, "account_safety_restricted", null);
    }

    static IdentityApiException rateLimited(Duration retryAfter) {
        return new IdentityApiException(
                HttpStatus.TOO_MANY_REQUESTS, "login_rate_limited", retryAfter);
    }

    static IdentityApiException rateLimitUnavailable() {
        return new IdentityApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "login_rate_limit_unavailable", null);
    }

    static IdentityApiException invalidDeviceId() {
        return new IdentityApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_device_id", null);
    }

    static IdentityApiException loginDeviceLimitReached() {
        return new IdentityApiException(HttpStatus.CONFLICT, "login_device_limit_reached", null);
    }

    static IdentityApiException deviceRecoveryExpired() {
        return new IdentityApiException(HttpStatus.GONE, "login_device_recovery_expired", null);
    }

    static IdentityApiException invalidDeviceSelection() {
        return new IdentityApiException(HttpStatus.CONFLICT, "login_device_selection_changed", null);
    }

    static IdentityApiException invalidLoginDeviceLimit() {
        return new IdentityApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_login_device_limit", null);
    }

    static IdentityApiException tooManyLoginHandoffs(Duration retryAfter) {
        return new IdentityApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_login_handoffs", retryAfter);
    }

    static IdentityApiException loginHandoffUnavailable() {
        return new IdentityApiException(HttpStatus.SERVICE_UNAVAILABLE, "login_handoff_unavailable", null);
    }

    static IdentityApiException invalidHandoffDevice() {
        return new IdentityApiException(HttpStatus.FORBIDDEN, "login_handoff_invalid_device", null);
    }

    static IdentityApiException sessionReplaced() {
        return new IdentityApiException(HttpStatus.UNAUTHORIZED, "session_replaced", null);
    }

    static IdentityApiException handoffPending(Duration retryAfter) {
        return new IdentityApiException(HttpStatus.CONFLICT, "login_handoff_pending", retryAfter);
    }

    static IdentityApiException handoffDenied() {
        return new IdentityApiException(HttpStatus.FORBIDDEN, "login_handoff_denied", null);
    }

    static IdentityApiException handoffCancelled(String code) {
        return new IdentityApiException(HttpStatus.CONFLICT, code, null);
    }

    static IdentityApiException handoffExpired() {
        return new IdentityApiException(HttpStatus.GONE, "login_handoff_expired", null);
    }

    static IdentityApiException avatarFileRequired() {
        return new IdentityApiException(HttpStatus.UNPROCESSABLE_ENTITY, "avatar_file_required", null);
    }

    static IdentityApiException avatarFileTooLarge() {
        return new IdentityApiException(HttpStatus.UNPROCESSABLE_ENTITY, "avatar_file_too_large", null);
    }

    static IdentityApiException avatarFileTypeNotSupported() {
        return new IdentityApiException(HttpStatus.UNPROCESSABLE_ENTITY, "avatar_file_type_not_supported", null);
    }

    static IdentityApiException avatarFileFormatMismatch() {
        return new IdentityApiException(HttpStatus.UNPROCESSABLE_ENTITY, "avatar_file_format_mismatch", null);
    }

    static IdentityApiException avatarFileDimensionsExceeded() {
        return new IdentityApiException(HttpStatus.UNPROCESSABLE_ENTITY, "avatar_file_dimensions_exceeded", null);
    }

    static IdentityApiException avatarFileInvalid() {
        return new IdentityApiException(HttpStatus.UNPROCESSABLE_ENTITY, "avatar_file_invalid", null);
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    Duration retryAfter() {
        return retryAfter;
    }
}
