package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class OAuthTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T04:00:00Z");
    private static final String DEVICE_ID = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_DEVICE_ID = "fedcba9876543210fedcba9876543210";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = PkceVerifier.s256(VERIFIER);
    private static final String AUTHORIZATION_CODE = "code-abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String LOOPBACK_REDIRECT = "http://127.0.0.1:43210/auth/callback";

    private final OAuthClientProperties client = new OAuthClientProperties();
    private final OAuthAuthorizationCodeRepository codes =
            mock(OAuthAuthorizationCodeRepository.class);
    private final OAuthRefreshTokenRepository refreshTokens =
            mock(OAuthRefreshTokenRepository.class);
    private final OpaqueTokenService tokens = new OpaqueTokenService();
    private final DeviceIdentityService devices =
            new DeviceIdentityService("unit-test-device-pepper-value-32-bytes", true);
    private final LoginHandoffService handoffs = mock(LoginHandoffService.class);
    private final TerminalSessionService terminalSessions = mock(TerminalSessionService.class);
    private final TerminalSessionRepository terminalSessionsStore =
            mock(TerminalSessionRepository.class);
    private final JwtDecoder jwtDecoder = mock(JwtDecoder.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID terminalSessionId = UUID.randomUUID();

    private OAuthTokenService service;

    @BeforeEach
    void setUp() {
        service = new OAuthTokenService(client, codes, refreshTokens, tokens, devices, handoffs,
                terminalSessions, terminalSessionsStore, jwtDecoder, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void exchangesAnAuthorizationCodeForTokens() {
        UUID codeId = UUID.randomUUID();
        when(codes.lockByHash(tokens.hash(AUTHORIZATION_CODE)))
                .thenReturn(Optional.of(code(codeId, CHALLENGE, null, NOW.plusSeconds(300))));
        when(codes.consume(codeId, NOW)).thenReturn(1);
        when(handoffs.loadUser(userId, tenantId)).thenReturn(user());
        when(handoffs.begin(eq(user()), any())).thenReturn(completed());

        OAuthTokenService.TokenIssue issue = service.exchangeAuthorizationCode(
                "yuzhh-desktop", AUTHORIZATION_CODE, "yuzhhai://oauth/callback", VERIFIER,
                DEVICE_ID, null);

        assertThat(issue.accessToken()).isEqualTo("desktop-access-token");
        assertThat(issue.refreshToken()).isNotBlank();
        assertThat(issue.expiresIn()).isEqualTo(900);
        assertThat(issue.scope()).isEqualTo("openid profile");

        ArgumentCaptor<OAuthRefreshTokenRepository.RefreshToken> captor =
                ArgumentCaptor.forClass(OAuthRefreshTokenRepository.RefreshToken.class);
        verify(refreshTokens).insert(captor.capture());
        OAuthRefreshTokenRepository.RefreshToken stored = captor.getValue();
        assertThat(stored.tokenHash()).isEqualTo(tokens.hash(issue.refreshToken()));
        assertThat(stored.userId()).isEqualTo(userId);
        assertThat(stored.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(stored.terminalSessionId()).isEqualTo(terminalSessionId);
        assertThat(stored.revokedAt()).isNull();
        assertThat(stored.expiresAt()).isEqualTo(NOW.plusSeconds(60L * 24 * 3600));
    }

    @Test
    void exchangesAnAuthorizationCodeBoundToTheExactLoopbackRedirect() {
        UUID codeId = UUID.randomUUID();
        when(codes.lockByHash(tokens.hash(AUTHORIZATION_CODE)))
                .thenReturn(Optional.of(code(codeId, CHALLENGE, null, NOW.plusSeconds(300),
                        LOOPBACK_REDIRECT)));
        when(codes.consume(codeId, NOW)).thenReturn(1);
        when(handoffs.loadUser(userId, tenantId)).thenReturn(user());
        when(handoffs.begin(eq(user()), any())).thenReturn(completed());

        OAuthTokenService.TokenIssue issue = service.exchangeAuthorizationCode(
                "yuzhh-desktop", AUTHORIZATION_CODE, LOOPBACK_REDIRECT, VERIFIER,
                DEVICE_ID, null);

        assertThat(issue.accessToken()).isEqualTo("desktop-access-token");
        verify(codes).consume(codeId, NOW);
    }

    @Test
    void refusesARedeemedAuthorizationCode() {
        UUID codeId = UUID.randomUUID();
        when(codes.lockByHash(anyString())).thenReturn(
                Optional.of(code(codeId, CHALLENGE, NOW.minusSeconds(5), NOW.plusSeconds(300))));

        assertError(() -> service.exchangeAuthorizationCode(
                "yuzhh-desktop", AUTHORIZATION_CODE, "yuzhhai://oauth/callback", VERIFIER,
                DEVICE_ID, null), "invalid_grant");
        verify(codes, never()).consume(any(), any());
    }

    @Test
    void refusesAnExpiredAuthorizationCode() {
        UUID codeId = UUID.randomUUID();
        when(codes.lockByHash(anyString())).thenReturn(
                Optional.of(code(codeId, CHALLENGE, null, NOW.minusSeconds(1))));

        assertError(() -> service.exchangeAuthorizationCode(
                "yuzhh-desktop", AUTHORIZATION_CODE, "yuzhhai://oauth/callback", VERIFIER,
                DEVICE_ID, null), "invalid_grant");
        verify(codes, never()).consume(any(), any());
    }

    @Test
    void refusesAMismatchedRedirectUri() {
        stubPendingCode();

        assertError(() -> service.exchangeAuthorizationCode(
                "yuzhh-desktop", AUTHORIZATION_CODE, "https://evil.example/callback", VERIFIER,
                DEVICE_ID, null), "redirect_uri_mismatch");
        verify(codes, never()).consume(any(), any());
    }

    @Test
    void refusesAMismatchedDevice() {
        stubPendingCode();

        assertThatThrownBy(() -> service.exchangeAuthorizationCode(
                "yuzhh-desktop", AUTHORIZATION_CODE, "yuzhhai://oauth/callback", VERIFIER,
                OTHER_DEVICE_ID, null))
                .isInstanceOf(OAuthException.class)
                .satisfies(error -> {
                    OAuthException oauth = (OAuthException) error;
                    assertThat(oauth.error()).isEqualTo("device_mismatch");
                    assertThat(oauth.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(oauth.description())
                            .isEqualTo("The device identifier does not match the authorization code.");
                });
        verify(codes, never()).consume(any(), any());
    }

    /** 设备不匹配发生在消费授权码之前，因此客户端换回绑定设备号即可重试成功。 */
    @Test
    void keepsTheAuthorizationCodeRedeemableAfterADeviceMismatch() {
        UUID codeId = UUID.randomUUID();
        when(codes.lockByHash(anyString()))
                .thenReturn(Optional.of(code(codeId, CHALLENGE, null, NOW.plusSeconds(300))));
        when(codes.consume(codeId, NOW)).thenReturn(1);
        when(handoffs.loadUser(userId, tenantId)).thenReturn(user());
        when(handoffs.begin(eq(user()), any())).thenReturn(completed());

        assertError(() -> service.exchangeAuthorizationCode(
                "yuzhh-desktop", AUTHORIZATION_CODE, "yuzhhai://oauth/callback", VERIFIER,
                OTHER_DEVICE_ID, null), "device_mismatch");
        verify(codes, never()).consume(any(), any());

        OAuthTokenService.TokenIssue issue = service.exchangeAuthorizationCode(
                "yuzhh-desktop", AUTHORIZATION_CODE, "yuzhhai://oauth/callback", VERIFIER,
                DEVICE_ID, null);
        assertThat(issue.accessToken()).isEqualTo("desktop-access-token");
        verify(codes).consume(codeId, NOW);
    }

    @Test
    void mapsTheTerminalDeviceLimitToTheDocumentedOAuthHandoffError() {
        stubPendingCode();
        stubRedeemableCode();
        when(handoffs.begin(any(), any()))
                .thenThrow(IdentityApiException.loginDeviceLimitReached());

        assertError(() -> service.exchangeAuthorizationCode(
                "yuzhh-desktop", AUTHORIZATION_CODE, "yuzhhai://oauth/callback", VERIFIER,
                DEVICE_ID, null), "login_handoff_pending");
        verify(refreshTokens, never()).insert(any());
    }

    @Test
    void refusesAWrongCodeVerifier() {
        stubPendingCode();

        assertError(() -> service.exchangeAuthorizationCode(
                "yuzhh-desktop", AUTHORIZATION_CODE, "yuzhhai://oauth/callback", VERIFIER + "x",
                DEVICE_ID, null), "pkce_verification_failed");
        verify(codes, never()).consume(any(), any());
    }

    @Test
    void refusesAMalformedDeviceHeader() {
        assertError(() -> service.exchangeAuthorizationCode(
                "yuzhh-desktop", AUTHORIZATION_CODE, "yuzhhai://oauth/callback", VERIFIER,
                "NOT-A-DEVICE", null), "invalid_device_id");
    }

    @Test
    void refusesAnUnknownClient() {
        assertError(() -> service.exchangeAuthorizationCode(
                "someone-else", AUTHORIZATION_CODE, "yuzhhai://oauth/callback", VERIFIER,
                DEVICE_ID, null), "invalid_client");
    }

    @Test
    void refusesAnAlreadyConsumedCodeConcurrently() {
        stubPendingCode();
        UUID codeId = UUID.randomUUID();
        when(handoffs.loadUser(userId, tenantId)).thenReturn(user());
        when(handoffs.begin(eq(user()), any())).thenReturn(completed());
        when(codes.consume(any(), any())).thenReturn(0);

        assertError(() -> service.exchangeAuthorizationCode(
                "yuzhh-desktop", AUTHORIZATION_CODE, "yuzhhai://oauth/callback", VERIFIER,
                DEVICE_ID, null), "invalid_grant");
        verify(refreshTokens, never()).insert(any());
    }

    @Test
    void rotatesTheRefreshTokenOnRefresh() {
        UUID tokenId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        when(refreshTokens.lockByHash(anyString())).thenReturn(Optional.of(
                refreshToken(tokenId, familyId, null, NOW.plusSeconds(86400))));
        when(handoffs.loadUser(userId, tenantId)).thenReturn(user());
        when(terminalSessions.issueDesktopAccessToken(
                eq(user()), eq(terminalSessionId), anyString())).thenReturn("rotated-access-token");
        when(refreshTokens.markRotated(eq(tokenId), any(), eq(NOW))).thenReturn(1);

        OAuthTokenService.TokenIssue issue =
                service.refresh("yuzhh-desktop", "old-refresh-token", DEVICE_ID, null);

        assertThat(issue.accessToken()).isEqualTo("rotated-access-token");
        ArgumentCaptor<OAuthRefreshTokenRepository.RefreshToken> captor =
                ArgumentCaptor.forClass(OAuthRefreshTokenRepository.RefreshToken.class);
        verify(refreshTokens).insert(captor.capture());
        OAuthRefreshTokenRepository.RefreshToken rotated = captor.getValue();
        assertThat(rotated.tokenFamilyId()).isEqualTo(familyId);
        assertThat(rotated.terminalSessionId()).isEqualTo(terminalSessionId);
        assertThat(tokens.hash(issue.refreshToken())).isEqualTo(rotated.tokenHash());
    }

    @Test
    void revokesTheWholeFamilyWhenARotatedTokenIsReplayed() {
        UUID tokenId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        when(refreshTokens.lockByHash(anyString())).thenReturn(Optional.of(
                refreshToken(tokenId, familyId, NOW.minusSeconds(30), NOW.plusSeconds(86400))));

        assertError(() -> service.refresh("yuzhh-desktop", "replayed-refresh-token", DEVICE_ID, null),
                "refresh_token_revoked");
        verify(refreshTokens).revokeFamily(familyId, "REUSE_DETECTED", NOW);
        verify(refreshTokens, never()).insert(any());
    }

    @Test
    void refusesRefreshFromAnotherDevice() {
        UUID tokenId = UUID.randomUUID();
        when(refreshTokens.lockByHash(anyString())).thenReturn(Optional.of(
                refreshToken(tokenId, UUID.randomUUID(), null, NOW.plusSeconds(86400))));

        assertError(() -> service.refresh("yuzhh-desktop", "refresh-token", OTHER_DEVICE_ID, null),
                "invalid_grant");
    }

    @Test
    void refusesAnExpiredRefreshToken() {
        UUID tokenId = UUID.randomUUID();
        when(refreshTokens.lockByHash(anyString())).thenReturn(Optional.of(
                refreshToken(tokenId, UUID.randomUUID(), null, NOW.minusSeconds(1))));

        assertError(() -> service.refresh("yuzhh-desktop", "refresh-token", DEVICE_ID, null),
                "invalid_grant");
    }

    @Test
    void revokesTheFamilyAndTerminalOnExplicitRevocation() {
        UUID tokenId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        when(refreshTokens.lockByHash(anyString())).thenReturn(Optional.of(
                refreshToken(tokenId, familyId, null, NOW.plusSeconds(86400))));

        service.revoke("yuzhh-desktop", "refresh-token", "refresh_token");

        verify(refreshTokens).revokeFamily(familyId, "REVOKED_BY_CLIENT", NOW);
        verify(terminalSessionsStore).revokeTerminal(terminalSessionId, "LOGOUT");
    }

    @Test
    void revocationOfAnUnknownTokenStillSucceeds() {
        when(refreshTokens.lockByHash(anyString())).thenReturn(Optional.empty());

        assertThatCode(() -> service.revoke("yuzhh-desktop", "unknown-token", "refresh_token"))
                .doesNotThrowAnyException();
    }

    private void stubPendingCode() {
        UUID codeId = UUID.randomUUID();
        when(codes.lockByHash(anyString()))
                .thenReturn(Optional.of(code(codeId, CHALLENGE, null, NOW.plusSeconds(300))));
    }

    /** 让兑换流程走到终端绑定这一步，设备上限就是在那里被评估的。 */
    private void stubRedeemableCode() {
        when(codes.consume(any(), any())).thenReturn(1);
        when(handoffs.loadUser(userId, tenantId)).thenReturn(user());
    }

    private OAuthAuthorizationCodeRepository.AuthorizationCode code(
            UUID id, String challenge, Instant consumedAt, Instant expiresAt) {
        return code(id, challenge, consumedAt, expiresAt, "yuzhhai://oauth/callback");
    }

    private OAuthAuthorizationCodeRepository.AuthorizationCode code(
            UUID id, String challenge, Instant consumedAt, Instant expiresAt, String redirectUri) {
        return new OAuthAuthorizationCodeRepository.AuthorizationCode(
                id, tokens.hash(AUTHORIZATION_CODE), "yuzhh-desktop", userId, tenantId,
                redirectUri, challenge, "S256", DEVICE_ID, "openid profile",
                expiresAt, consumedAt, NOW.minusSeconds(10));
    }

    private OAuthRefreshTokenRepository.RefreshToken refreshToken(
            UUID id, UUID familyId, Instant revokedAt, Instant expiresAt) {
        return new OAuthRefreshTokenRepository.RefreshToken(
                id, tokens.hash("old-refresh-token"), familyId, "yuzhh-desktop", userId, tenantId,
                DEVICE_ID, terminalSessionId, "openid profile", expiresAt, revokedAt,
                revokedAt == null ? null : "ROTATED", null, NOW.minusSeconds(60), null);
    }

    private LoginHandoffService.Completed completed() {
        IdentityService.AccessSession session = new IdentityService.AccessSession(
                "desktop-access-token", 900, "web-refresh", NOW.plusSeconds(86400), "csrf", user());
        return new LoginHandoffService.Completed(session, terminalSessionId);
    }

    private IdentityService.UserProfile user() {
        return new IdentityService.UserProfile(userId, tenantId, "desk@example.com", "桌面用户", "USER");
    }

    private void assertError(Runnable action, String expectedError) {
        assertThatThrownBy(action::run)
                .isInstanceOf(OAuthException.class)
                .extracting(error -> ((OAuthException) error).error())
                .isEqualTo(expectedError);
    }
}
