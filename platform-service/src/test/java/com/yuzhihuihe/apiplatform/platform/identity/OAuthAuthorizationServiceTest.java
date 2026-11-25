package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OAuthAuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T04:00:00Z");
    private static final String DEVICE_ID = "0123456789abcdef0123456789abcdef";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String STATE = "state-9f2c";
    private static final String LOOPBACK_REDIRECT = "http://127.0.0.1:43210/auth/callback";

    private final OAuthClientProperties client = new OAuthClientProperties();
    private final OAuthLoginTransactionRepository transactions =
            mock(OAuthLoginTransactionRepository.class);
    private final OAuthAuthorizationCodeRepository codes =
            mock(OAuthAuthorizationCodeRepository.class);
    private final OpaqueTokenService tokens = new OpaqueTokenService();

    private OAuthAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new OAuthAuthorizationService(
                client, transactions, codes, tokens, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void startsATransactionAndRedirectsToTheDedicatedDesktopLoginPage() {
        OAuthAuthorizationService.AuthorizationStart start =
                service.begin(params(Map.of()));

        ArgumentCaptor<OAuthLoginTransactionRepository.LoginTransaction> captor =
                ArgumentCaptor.forClass(OAuthLoginTransactionRepository.LoginTransaction.class);
        verify(transactions).insert(captor.capture());
        OAuthLoginTransactionRepository.LoginTransaction row = captor.getValue();

        assertThat(row.status()).isEqualTo(OAuthLoginTransactionRepository.STATUS_PENDING);
        assertThat(row.clientId()).isEqualTo("yuzhh-desktop");
        assertThat(row.redirectUri()).isEqualTo("yuzhhai://oauth/callback");
        assertThat(row.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(row.scope()).isEqualTo("openid profile");
        assertThat(row.state()).isEqualTo(STATE);
        assertThat(row.codeChallenge()).isEqualTo(CHALLENGE);
        assertThat(row.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(start.loginRedirectUrl())
                .isEqualTo("/desktop-login?oauth_transaction=" + start.transactionId());
    }

    @Test
    void rejectsAnUnknownClient() {
        assertError(params(Map.of("client_id", "someone-else")), "invalid_client");
    }

    @Test
    void rejectsAnUnsupportedResponseType() {
        assertError(params(Map.of("response_type", "token")), "invalid_request");
    }

    @Test
    void rejectsARedirectUriThatIsNotRegistered() {
        assertError(params(Map.of("redirect_uri", "https://evil.example/callback")),
                "redirect_uri_mismatch");
    }

    @Test
    void acceptsOnlyTheExactNativeAppLoopbackRedirectShape() {
        assertThat(client.matchesRedirectUri(LOOPBACK_REDIRECT)).isTrue();
        assertThat(client.matchesRedirectUri("http://localhost:43210/auth/callback")).isFalse();
        assertThat(client.matchesRedirectUri("http://127.0.0.1/auth/callback")).isFalse();
        assertThat(client.matchesRedirectUri("https://127.0.0.1:43210/auth/callback")).isFalse();
        assertThat(client.matchesRedirectUri("http://127.0.0.1:43210/other")).isFalse();
        assertThat(client.matchesRedirectUri(LOOPBACK_REDIRECT + "?code=unexpected")).isFalse();
        assertThat(client.matchesRedirectUri("http://user@127.0.0.1:43210/auth/callback")).isFalse();
    }

    @Test
    void storesTheExactDynamicLoopbackRedirectInTheLoginTransaction() {
        service.begin(params(Map.of("redirect_uri", LOOPBACK_REDIRECT)));

        ArgumentCaptor<OAuthLoginTransactionRepository.LoginTransaction> captor =
                ArgumentCaptor.forClass(OAuthLoginTransactionRepository.LoginTransaction.class);
        verify(transactions).insert(captor.capture());
        assertThat(captor.getValue().redirectUri()).isEqualTo(LOOPBACK_REDIRECT);
    }

    @Test
    void rejectsAnUnregisteredScope() {
        assertError(params(Map.of("scope", "openid profile admin")), "invalid_scope");
    }

    @Test
    void rejectsAMissingState() {
        assertError(params(Map.of("state", " ")), "invalid_request");
    }

    @Test
    void rejectsAPlainCodeChallengeMethod() {
        assertError(params(Map.of("code_challenge_method", "plain")), "invalid_request");
    }

    @Test
    void rejectsAMalformedDeviceId() {
        assertError(params(Map.of("device_id", "NOT-A-DEVICE")), "invalid_device_id");
    }

    @Test
    void rejectsAMissingDeviceId() {
        assertError(params(Map.of("device_id", "")), "invalid_device_id");
    }

    @Test
    void createsAnAuthorizationCodeForTheSignedInUser() {
        UUID transactionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(transactions.lockById(transactionId)).thenReturn(Optional.of(pending(transactionId)));

        OAuthAuthorizationService.AuthorizationCompletion completion = service.complete(
                transactionId, user(userId, tenantId));

        ArgumentCaptor<OAuthAuthorizationCodeRepository.AuthorizationCode> captor =
                ArgumentCaptor.forClass(OAuthAuthorizationCodeRepository.AuthorizationCode.class);
        verify(codes).insert(captor.capture());
        OAuthAuthorizationCodeRepository.AuthorizationCode code = captor.getValue();

        assertThat(code.clientId()).isEqualTo("yuzhh-desktop");
        assertThat(code.userId()).isEqualTo(userId);
        assertThat(code.tenantId()).isEqualTo(tenantId);
        assertThat(code.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(code.codeChallenge()).isEqualTo(CHALLENGE);
        assertThat(code.codeChallengeMethod()).isEqualTo("S256");
        assertThat(code.consumedAt()).isNull();
        assertThat(code.expiresAt()).isEqualTo(NOW.plusSeconds(300));

        verify(transactions).markAuthenticated(transactionId, userId, tenantId);
        verify(transactions).complete(transactionId, NOW);

        assertThat(completion.redirectUrl())
                .startsWith("yuzhhai://oauth/callback?code=")
                .contains("&state=" + STATE);
        assertThat(completion.scopes()).containsExactly("openid", "profile");
    }

    @Test
    void completesBackToTheExactDynamicLoopbackRedirect() {
        UUID transactionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(transactions.lockById(transactionId))
                .thenReturn(Optional.of(pending(transactionId, LOOPBACK_REDIRECT)));

        OAuthAuthorizationService.AuthorizationCompletion completion = service.complete(
                transactionId, user(userId, tenantId));

        ArgumentCaptor<OAuthAuthorizationCodeRepository.AuthorizationCode> captor =
                ArgumentCaptor.forClass(OAuthAuthorizationCodeRepository.AuthorizationCode.class);
        verify(codes).insert(captor.capture());
        assertThat(captor.getValue().redirectUri()).isEqualTo(LOOPBACK_REDIRECT);
        assertThat(completion.redirectUrl())
                .startsWith(LOOPBACK_REDIRECT + "?code=")
                .contains("&state=" + STATE);
    }

    @Test
    void waitsForFirstPasswordSetupWithoutConsumingTheDesktopTransaction() {
        UUID transactionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(transactions.lockById(transactionId)).thenReturn(Optional.of(pending(transactionId)));

        assertThatThrownBy(() -> service.complete(transactionId,
                new IdentityService.UserProfile(
                        userId, tenantId, null, "新用户", "USER", false)))
                .isInstanceOf(OAuthException.class)
                .extracting(error -> ((OAuthException) error).error())
                .isEqualTo("password_setup_required");

        verify(transactions, never()).markAuthenticated(any(), any(), any());
        verify(transactions, never()).complete(any(), any());
        verify(codes, never()).insert(any());
    }

    @Test
    void refusesAnUnknownOrFinishedTransaction() {
        UUID transactionId = UUID.randomUUID();
        when(transactions.lockById(transactionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(transactionId, user(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(OAuthException.class)
                .extracting(error -> ((OAuthException) error).error())
                .isEqualTo("invalid_request");
    }

    @Test
    void refusesAnExpiredTransaction() {
        UUID transactionId = UUID.randomUUID();
        OAuthLoginTransactionRepository.LoginTransaction expired =
                new OAuthLoginTransactionRepository.LoginTransaction(
                        transactionId, transactionId.toString(), "yuzhh-desktop",
                        "yuzhhai://oauth/callback", DEVICE_ID, "openid profile", STATE, CHALLENGE,
                        "S256", null, null, null,
                        OAuthLoginTransactionRepository.STATUS_PENDING,
                        NOW.minusSeconds(1), NOW.minusSeconds(601), null);
        when(transactions.lockById(transactionId)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.complete(transactionId, user(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(OAuthException.class)
                .extracting(error -> ((OAuthException) error).error())
                .isEqualTo("invalid_request");
    }

    @Test
    void cancelsWithAnErrorRedirect() {
        UUID transactionId = UUID.randomUUID();
        when(transactions.lockById(transactionId)).thenReturn(Optional.of(pending(transactionId)));

        String redirect = service.cancel(transactionId, "access_denied");

        verify(transactions).cancel(transactionId, NOW);
        assertThat(redirect).isEqualTo(
                "yuzhhai://oauth/callback?error=access_denied&state=" + STATE);
    }

    @Test
    void doesNotTouchTheCodeRepositoryWhenValidationFails() {
        assertError(params(Map.of("client_id", "someone-else")), "invalid_client");
        verify(codes, org.mockito.Mockito.never()).insert(any());
    }

    private OAuthAuthorizationService.AuthorizationParams params(Map<String, String> overrides) {
        Map<String, String> values = new java.util.HashMap<>(Map.of(
                "client_id", "yuzhh-desktop",
                "response_type", "code",
                "redirect_uri", "yuzhhai://oauth/callback",
                "scope", "openid profile",
                "state", STATE,
                "code_challenge", CHALLENGE,
                "code_challenge_method", "S256",
                "device_id", DEVICE_ID));
        values.putAll(overrides);
        return OAuthAuthorizationService.AuthorizationParams.of(values);
    }

    private OAuthLoginTransactionRepository.LoginTransaction pending(UUID id) {
        return pending(id, "yuzhhai://oauth/callback");
    }

    private OAuthLoginTransactionRepository.LoginTransaction pending(UUID id, String redirectUri) {
        return new OAuthLoginTransactionRepository.LoginTransaction(
                id, id.toString(), "yuzhh-desktop", redirectUri, DEVICE_ID,
                "openid profile", STATE, CHALLENGE, "S256", null, null, null,
                OAuthLoginTransactionRepository.STATUS_PENDING,
                NOW.plusSeconds(600), NOW, null);
    }

    private IdentityService.UserProfile user(UUID id, UUID tenantId) {
        return new IdentityService.UserProfile(id, tenantId, "desk@example.com", "桌面用户", "USER");
    }

    private void assertError(OAuthAuthorizationService.AuthorizationParams params, String error) {
        assertThatThrownBy(() -> service.begin(params))
                .isInstanceOf(OAuthException.class)
                .extracting(caught -> ((OAuthException) caught).error())
                .isEqualTo(error);
    }
}
