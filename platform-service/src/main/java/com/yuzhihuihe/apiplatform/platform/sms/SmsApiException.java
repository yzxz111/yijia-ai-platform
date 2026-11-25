package com.yuzhihuihe.apiplatform.platform.sms;

import java.util.Map;

import org.springframework.http.HttpStatus;

public class SmsApiException extends RuntimeException {

    private static final Map<String, HttpStatus> EXPOSED_ERRORS = Map.ofEntries(
            Map.entry("sms_disabled", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("phone_unavailable", HttpStatus.CONFLICT),
            Map.entry("sms_login_unavailable", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("sms_rate_limited_60_seconds", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("sms_rate_limited_phone_hour", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("sms_rate_limited_phone_day", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("sms_rate_limited_ip_minute", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("sms_rate_limited_ip_hour", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("sms_rate_limit_unavailable", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("captcha_required", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("captcha_invalid", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("captcha_expired", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("captcha_attempts_exhausted", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("captcha_rate_limited", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("captcha_unavailable", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("sms_request_failed", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("sms_verification_required", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("invalid_sms_purpose", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("invalid_mainland_phone", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("invalid_sms_challenge", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("invalid_phone_challenge", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("invalid_registration_challenge", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("invalid_sms_code", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("invalid_sms_proof", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("invalid_sms_preference", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("challenge_not_found", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("challenge_consumed", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("challenge_already_verified", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("challenge_expired", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("challenge_attempts_exhausted", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("email_disabled", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("email_delivery_failed", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("email_rate_limited", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("invalid_email", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("invalid_email_challenge", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("email_already_bound", HttpStatus.CONFLICT),
            Map.entry("sms_phone_mismatch", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("sms_phone_not_bound", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("phone_already_bound", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("terms_acceptance_required", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("invalid_consent_version", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("legal_document_version_stale", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("sms_template_not_active", HttpStatus.UNPROCESSABLE_ENTITY));

    private final HttpStatus status;
    private final String code;

    public SmsApiException(HttpStatus status, String code) {
        super(code, null, false, false);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static SmsApiException from(RuntimeException error) {
        return from(error, "sms_request_failed");
    }

    public static SmsApiException from(RuntimeException error, String fallback) {
        String safeFallback = EXPOSED_ERRORS.containsKey(fallback) ? fallback : "sms_request_failed";
        String candidate = error == null ? null : error.getMessage();
        String code = EXPOSED_ERRORS.containsKey(candidate) ? candidate : safeFallback;
        return new SmsApiException(EXPOSED_ERRORS.get(code), code);
    }
}
