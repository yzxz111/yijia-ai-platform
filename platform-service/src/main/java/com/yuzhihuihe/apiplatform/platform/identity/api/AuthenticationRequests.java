package com.yuzhihuihe.apiplatform.platform.identity.api;

import java.util.UUID;

public final class AuthenticationRequests {

    private AuthenticationRequests() {}

    public record RegisterRequest(
            String email,
            String password,
            String displayName,
            String phone,
            UUID challengeId,
            String verificationCode,
            Boolean termsAccepted,
            String consentVersion,
            String invitationCode) {}

    public record LoginRequest(String identifier, String email, String password) {}

    public record IdentityRequest(
            String identifier,
            UUID challengeId,
            String verificationCode,
            Boolean termsAccepted,
            String consentVersion) {}

    public record CompletePhoneRegistrationRequest(
            UUID challengeId,
            String newPassword,
            Boolean termsAccepted,
            String consentVersion,
            String invitationCode) {}

    public record EmailChallengeRequest(String email) {}

    public record EmailIdentityRequest(
            String email,
            UUID challengeId,
            String verificationCode,
            Boolean termsAccepted,
            String consentVersion) {}

    public record CompleteEmailRegistrationRequest(
            UUID challengeId,
            String newPassword,
            Boolean termsAccepted,
            String consentVersion,
            String invitationCode) {}

    public record PasswordResetRequest(
            String identifier, UUID challengeId, String verificationCode, String newPassword) {}

    public record SmsLoginRequest(UUID challengeId, String code) {}
}
