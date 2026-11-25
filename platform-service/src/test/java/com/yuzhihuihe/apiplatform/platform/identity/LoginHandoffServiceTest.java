package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.safety.AccountEnforcementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginHandoffServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-22T10:00:00Z");
    private static final String RAW_TOKEN = "pending-token";
    private static final String TOKEN_HASH = "token-hash";
    private static final String RAW_DEVICE = "0123456789abcdef0123456789abcdef";
    private static final String DEVICE_HASH = "device-hash";

    private final LoginHandoffRepository repository = mock(LoginHandoffRepository.class);
    private final DeviceIdentityService devices = mock(DeviceIdentityService.class);
    private final OpaqueTokenService tokens = mock(OpaqueTokenService.class);
    private final TerminalSessionService terminalSessions = mock(TerminalSessionService.class);
    private final TerminalPresenceService presence = mock(TerminalPresenceService.class);
    private final LoginHandoffRateLimiter rateLimiter = mock(LoginHandoffRateLimiter.class);
    private final LoginClientSummaryService summaries = mock(LoginClientSummaryService.class);
    private final DesktopLoginNotificationService notifications =
            mock(DesktopLoginNotificationService.class);
    private final AccountEnforcementRepository accountEnforcements =
            mock(AccountEnforcementRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private LoginHandoffService service;

    @BeforeEach
    void setUp() {
        service = new LoginHandoffService(
                repository,
                devices,
                tokens,
                terminalSessions,
                presence,
                rateLimiter,
                summaries,
                notifications,
                accountEnforcements,
                mock(DeviceLoginRecoveryService.class),
                Duration.ofMinutes(10),
                Duration.ofMinutes(1),
                clock);
        when(tokens.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(devices.hashRequired(RAW_DEVICE)).thenReturn(DEVICE_HASH);
    }

    @Test
    void approvePreparesReplacementBeforePersistingTheDecision() {
        UUID requestId = UUID.randomUUID();
        UUID replacementId = UUID.randomUUID();
        IdentityService.UserProfile user = user();
        LoginHandoffService.AuthenticatedTerminal terminal = terminal(user);
        when(repository.findRequest(requestId, true))
                .thenReturn(Optional.of(row(user, terminal.sessionId(), "PENDING",
                        NOW.plusSeconds(300), null)));
        when(terminalSessions.prepareReplacement(
                user, terminal.sessionId(), DEVICE_HASH, "SESSION_REPLACED"))
                .thenReturn(replacementId);

        service.approve(requestId, terminal);

        var ordered = inOrder(terminalSessions, repository);
        ordered.verify(terminalSessions).prepareReplacement(
                user, terminal.sessionId(), DEVICE_HASH, "SESSION_REPLACED");
        ordered.verify(repository).approve(requestId, replacementId, NOW, terminal.sessionId());
    }

    @Test
    void denyPersistsTheDecisionWithoutPreparingAReplacement() {
        UUID requestId = UUID.randomUUID();
        IdentityService.UserProfile user = user();
        LoginHandoffService.AuthenticatedTerminal terminal = terminal(user);
        when(repository.findRequest(requestId, true))
                .thenReturn(Optional.of(row(user, terminal.sessionId(), "PENDING",
                        NOW.plusSeconds(300), null)));

        service.deny(requestId, terminal);

        verify(repository).deny(requestId, NOW, terminal.sessionId());
        verify(terminalSessions, never()).prepareReplacement(
                user, terminal.sessionId(), DEVICE_HASH, "SESSION_REPLACED");
    }

    @Test
    void completeWithinGraceUsesTheTimeoutReplacementPath() {
        UUID requestId = UUID.randomUUID();
        UUID oldSessionId = UUID.randomUUID();
        UUID replacementId = UUID.randomUUID();
        IdentityService.UserProfile user = user();
        LoginActivityService.LoginContext context = LoginActivityService.LoginContext.empty();
        IdentityService.AccessSession accessSession = accessSession(user);
        when(repository.findRequest(requestId, true))
                .thenReturn(Optional.of(row(user, oldSessionId, "PENDING",
                        NOW.minusSeconds(30), null)));
        when(repository.findUser(user.id(), user.tenantId())).thenReturn(Optional.of(userRow(user)));
        when(terminalSessions.prepareReplacement(
                user, oldSessionId, DEVICE_HASH, "SESSION_REPLACED"))
                .thenReturn(replacementId);
        when(terminalSessions.issueForExisting(user, replacementId, DEVICE_HASH, false))
                .thenReturn(new TerminalSessionService.TerminalGrant(replacementId, accessSession));
        LoginClientSummaryService.ClientSummary summary =
                new LoginClientSummaryService.ClientSummary("Shanghai", "Desktop");
        when(summaries.summarize(context)).thenReturn(summary);

        IdentityService.AccessSession result =
                service.complete(requestId, RAW_TOKEN, RAW_DEVICE, context);

        assertThat(result).isSameAs(accessSession);
        var ordered = inOrder(terminalSessions, repository, notifications);
        ordered.verify(terminalSessions).prepareReplacement(
                user, oldSessionId, DEVICE_HASH, "SESSION_REPLACED");
        ordered.verify(repository).approveAfterTimeout(requestId, replacementId, NOW);
        ordered.verify(terminalSessions).issueForExisting(user, replacementId, DEVICE_HASH, false);
        ordered.verify(repository).complete(requestId, NOW);
        ordered.verify(notifications).record(
                user, replacementId, "TIMEOUT", summary, NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    void completeAfterGraceMarksTheRequestExpired() {
        UUID requestId = UUID.randomUUID();
        IdentityService.UserProfile user = user();
        when(repository.findRequest(requestId, true))
                .thenReturn(Optional.of(row(user, UUID.randomUUID(), "PENDING",
                        NOW.minusSeconds(61), null)));
        when(repository.findUser(user.id(), user.tenantId())).thenReturn(Optional.of(userRow(user)));

        assertThatThrownBy(() -> service.complete(
                requestId, RAW_TOKEN, RAW_DEVICE, LoginActivityService.LoginContext.empty()))
                .isInstanceOfSatisfying(IdentityApiException.class,
                        error -> assertThat(error.code()).isEqualTo("login_handoff_expired"));

        verify(repository).expireRequest(requestId, NOW);
        verify(terminalSessions, never()).issueForExisting(
                user, null, DEVICE_HASH, false);
    }

    @Test
    void completedRequestUsesTheIdempotentTerminalIssuancePath() {
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        IdentityService.UserProfile user = user();
        LoginActivityService.LoginContext context = LoginActivityService.LoginContext.empty();
        IdentityService.AccessSession accessSession = accessSession(user);
        when(repository.findRequest(requestId, true))
                .thenReturn(Optional.of(row(user, UUID.randomUUID(), "COMPLETED",
                        NOW.minusSeconds(30), sessionId)));
        when(repository.findUser(user.id(), user.tenantId())).thenReturn(Optional.of(userRow(user)));
        when(terminalSessions.issueForExisting(user, sessionId, DEVICE_HASH, true))
                .thenReturn(new TerminalSessionService.TerminalGrant(sessionId, accessSession));
        when(summaries.summarize(context))
                .thenReturn(new LoginClientSummaryService.ClientSummary("UNKNOWN", "Desktop"));

        assertThat(service.complete(requestId, RAW_TOKEN, RAW_DEVICE, context))
                .isSameAs(accessSession);

        verify(terminalSessions).issueForExisting(user, sessionId, DEVICE_HASH, true);
        verify(repository).complete(requestId, NOW);
    }

    @Test
    void invalidPendingCredentialStopsBeforeLoadingTheUserOrWritingState() {
        UUID requestId = UUID.randomUUID();
        IdentityService.UserProfile user = user();
        when(repository.findRequest(requestId, true))
                .thenReturn(Optional.of(row(user, UUID.randomUUID(), "APPROVED",
                        NOW.plusSeconds(300), UUID.randomUUID())));
        when(tokens.hash("wrong-token")).thenReturn("wrong-hash");

        assertThatThrownBy(() -> service.complete(
                requestId, "wrong-token", RAW_DEVICE, LoginActivityService.LoginContext.empty()))
                .isInstanceOfSatisfying(IdentityApiException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("login_handoff_invalid_device"));

        verify(repository, never()).findUser(user.id(), user.tenantId());
        verify(repository, never()).complete(requestId, NOW);
    }

    @Test
    void cancelPersistsOnlyPendingRequests() {
        UUID requestId = UUID.randomUUID();
        IdentityService.UserProfile user = user();
        when(repository.findRequest(requestId, true))
                .thenReturn(Optional.of(row(user, UUID.randomUUID(), "PENDING",
                        NOW.plusSeconds(300), null)));

        service.cancel(requestId, RAW_TOKEN, RAW_DEVICE);

        verify(repository).cancel(requestId, NOW);
    }

    private IdentityService.UserProfile user() {
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID tenantId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        return new IdentityService.UserProfile(
                userId, tenantId, "user@example.com", "User", "USER", true, "ACTIVE", 3L);
    }

    private LoginHandoffService.AuthenticatedTerminal terminal(IdentityService.UserProfile user) {
        return new LoginHandoffService.AuthenticatedTerminal(
                user, UUID.fromString("33333333-3333-3333-3333-333333333333"),
                LoginHandoffService.CLIENT_TYPE);
    }

    private LoginHandoffRepository.HandoffRow row(
            IdentityService.UserProfile user,
            UUID oldSessionId,
            String status,
            Instant expiresAt,
            UUID newSessionId) {
        return new LoginHandoffRepository.HandoffRow(
                UUID.randomUUID(), user.tenantId(), user.id(), oldSessionId, newSessionId,
                DEVICE_HASH, TOKEN_HASH, status, NOW.minusSeconds(60), expiresAt);
    }

    private LoginHandoffRepository.UserRow userRow(IdentityService.UserProfile user) {
        return new LoginHandoffRepository.UserRow(
                user.id(), user.tenantId(), user.email(), user.displayName(), user.role(),
                user.passwordSet(), user.status(), user.authVersion());
    }

    private IdentityService.AccessSession accessSession(IdentityService.UserProfile user) {
        return new IdentityService.AccessSession(
                "access-token", 900, "refresh-token", NOW.plusSeconds(3600), "csrf", user);
    }
}
