package com.yuzhihuihe.apiplatform.platform.referral;

import org.springframework.http.HttpStatus;

public final class ReferralApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private ReferralApiException(HttpStatus status, String code) {
        super(code, null, false, false);
        this.status = status;
        this.code = code;
    }

    public static ReferralApiException invalidCode() {
        return new ReferralApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_invitation_code");
    }

    public static ReferralApiException revokedCode() {
        return new ReferralApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invitation_code_revoked");
    }

    public static ReferralApiException invitationNotAllowed() {
        return new ReferralApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invitation_code_not_allowed");
    }

    static ReferralApiException invalidCursor() {
        return new ReferralApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_referral_cursor");
    }

    static ReferralApiException invalidLimit() {
        return new ReferralApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_referral_limit");
    }

    static ReferralApiException unauthenticated() {
        return new ReferralApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials");
    }

    static ReferralApiException ownerUnavailable() {
        return new ReferralApiException(HttpStatus.FORBIDDEN, "referral_owner_unavailable");
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
