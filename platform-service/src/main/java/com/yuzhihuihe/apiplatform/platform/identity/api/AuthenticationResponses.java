package com.yuzhihuihe.apiplatform.platform.identity.api;

import java.time.Instant;
import java.util.UUID;

public final class AuthenticationResponses {

    private AuthenticationResponses() {}

    public record LoginHandoffPendingResponse(
            String code, UUID requestId, String status, Instant expiresAt, int pollAfterSeconds) {}

    public record PhoneRegistrationPendingResponse(
            String code, UUID challengeId, Instant expiresAt) {}

    public record EmailRegistrationPendingResponse(
            String code, UUID challengeId, Instant expiresAt) {}

    public record SessionResponse(String accessToken, long expiresIn, UserResponse user) {}

    public record UserResponse(
            UUID id,
            UUID tenantId,
            String email,
            String displayName,
            String role,
            boolean passwordSet,
            String status) {}

    public record AccountStateResponse(String status, String disabledReason, Instant disabledAt) {}
}
