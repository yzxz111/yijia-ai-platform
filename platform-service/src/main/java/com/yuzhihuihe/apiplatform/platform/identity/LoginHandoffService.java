package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.safety.AccountEnforcementRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginHandoffService {

    static final String CLIENT_TYPE = "DESKTOP_WEB";
    private static final int POLL_AFTER_SECONDS = 5;

    private final LoginHandoffRepository repository;
    private final DeviceIdentityService devices;
    private final OpaqueTokenService tokens;
    private final TerminalSessionService terminalSessions;
    private final TerminalPresenceService presence;
    private final LoginHandoffRateLimiter rateLimiter;
    private final LoginClientSummaryService summaries;
    private final DesktopLoginNotificationService notifications;
    private final AccountEnforcementRepository accountEnforcements;
    private final Duration handoffTimeout;
    private final Duration completionGrace;
    private final Clock clock;
    private final DeviceLoginRecoveryService deviceRecovery;

    @org.springframework.beans.factory.annotation.Autowired
    LoginHandoffService(
            LoginHandoffRepository repository,
            DeviceIdentityService devices,
            OpaqueTokenService tokens,
            TerminalSessionService terminalSessions,
            TerminalPresenceService presence,
            LoginHandoffRateLimiter rateLimiter,
            LoginClientSummaryService summaries,
            DesktopLoginNotificationService notifications,
            AccountEnforcementRepository accountEnforcements,
            DeviceLoginRecoveryService deviceRecovery,
            @Value("${identity.terminal-session.handoff-timeout:10m}") Duration handoffTimeout,
            @Value("${identity.terminal-session.completion-grace:1m}") Duration completionGrace) {
        this(repository, devices, tokens, terminalSessions, presence, rateLimiter, summaries,
                notifications, accountEnforcements, deviceRecovery, handoffTimeout, completionGrace,
                Clock.systemUTC());
    }

    LoginHandoffService(
            LoginHandoffRepository repository,
            DeviceIdentityService devices,
            OpaqueTokenService tokens,
            TerminalSessionService terminalSessions,
            TerminalPresenceService presence,
            LoginHandoffRateLimiter rateLimiter,
            LoginClientSummaryService summaries,
            DesktopLoginNotificationService notifications,
            AccountEnforcementRepository accountEnforcements,
            DeviceLoginRecoveryService deviceRecovery,
            Duration handoffTimeout,
            Duration completionGrace,
            Clock clock) {
        this.repository = repository;
        this.devices = devices;
        this.tokens = tokens;
        this.terminalSessions = terminalSessions;
        this.presence = presence;
        this.rateLimiter = rateLimiter;
        this.summaries = summaries;
        this.notifications = notifications;
        this.accountEnforcements = accountEnforcements;
        this.handoffTimeout = handoffTimeout;
        this.completionGrace = completionGrace;
        this.clock = clock;
        this.deviceRecovery = deviceRecovery;
    }

    @Transactional
    public LoginResult begin(
            IdentityService.UserProfile user, LoginActivityService.LoginContext context) {
        String deviceHash = devices.hashRequired(context == null ? null : context.deviceId());
        lockUser(user);
        requireSafetyAllowed(user);
        Instant now = clock.instant();
        repository.expireActiveTerminals(user.id(), now);
        var activeRows = repository.lockActiveTerminals(user.id());
        if (activeRows.isEmpty()) {
            TerminalSessionService.TerminalGrant created = terminalSessions.create(user, deviceHash);
            return new Completed(created.session(), created.terminalSessionId());
        }

        LoginHandoffRepository.ActiveTerminalRow active = activeRows.stream()
                .filter(row -> deviceHash.equals(row.deviceHash()))
                .findFirst().orElse(null);
        if (active != null) {
            TerminalSessionService.TerminalGrant replaced = terminalSessions.replace(
                    user, active.id(), deviceHash, "EXPLICIT_RELOGIN");
            return new Completed(replaced.session(), replaced.terminalSessionId());
        }
        Integer deviceLimit = repository.maxLoginDevices(user.id(), user.tenantId());
        if (deviceLimit == null) throw IdentityApiException.loginDeviceLimitReached();
        if (activeRows.size() >= deviceLimit) {
            if (context == null || !context.deviceRecoverySupported()) throw IdentityApiException.loginDeviceLimitReached();
            return deviceRecovery.begin(user, deviceHash, deviceLimit);
        }
        TerminalSessionService.TerminalGrant created = terminalSessions.create(user, deviceHash);
        notifications.record(user, created.terminalSessionId(), "NEW_DEVICE",
                summaries.summarize(context), OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        return new Completed(created.session(), created.terminalSessionId());
    }

    public Optional<PendingApprovalView> pendingFor(AuthenticatedTerminal terminal) {
        requireDesktop(terminal);
        return repository.findPendingApproval(terminal.user().id(), terminal.sessionId())
                .map(row -> new PendingApprovalView(
                        row.id(), row.requestedAt(), row.expiresAt(),
                        row.region(), row.userAgentSummary()));
    }

    @Transactional
    public void approve(UUID requestId, AuthenticatedTerminal terminal) {
        HandoffRow row = lockedRequest(requestId);
        requireOldTerminal(row, terminal);
        requirePending(row);
        UUID prepared = terminalSessions.prepareReplacement(
                terminal.user(), row.oldSessionId(), row.newDeviceHash(), "SESSION_REPLACED");
        repository.approve(requestId, prepared, clock.instant(), terminal.sessionId());
    }

    @Transactional
    public void deny(UUID requestId, AuthenticatedTerminal terminal) {
        HandoffRow row = lockedRequest(requestId);
        requireOldTerminal(row, terminal);
        requirePending(row);
        repository.deny(requestId, clock.instant(), terminal.sessionId());
    }

    public HandoffStatusView inspect(UUID requestId, String pendingToken, String rawDeviceId) {
        HandoffRow row = request(requestId);
        verifyPendingCredential(row, pendingToken, rawDeviceId);
        String status = row.status();
        if ("PENDING".equals(status)
                && clock.instant().isAfter(row.expiresAt().plus(completionGrace))) {
            status = "EXPIRED";
        }
        return new HandoffStatusView(requestId, status, row.expiresAt());
    }

    @Transactional
    public IdentityService.AccessSession complete(
            UUID requestId,
            String pendingToken,
            String rawDeviceId,
            LoginActivityService.LoginContext context) {
        HandoffRow row = lockedRequest(requestId);
        verifyPendingCredential(row, pendingToken, rawDeviceId);
        Instant now = clock.instant();
        IdentityService.UserProfile user = loadUser(row.userId(), row.tenantId());
        UUID terminalSessionId = row.newSessionId();
        String mode = "APPROVED";

        if ("PENDING".equals(row.status())) {
            if (now.isBefore(row.expiresAt())) {
                throw IdentityApiException.handoffPending(
                        Duration.ofSeconds(Math.max(1, row.expiresAt().getEpochSecond() - now.getEpochSecond())));
            }
            if (now.isAfter(row.expiresAt().plus(completionGrace))) {
                repository.expireRequest(requestId, now);
                throw IdentityApiException.handoffExpired();
            }
            terminalSessionId = terminalSessions.prepareReplacement(
                    user, row.oldSessionId(), row.newDeviceHash(), "SESSION_REPLACED");
            repository.approveAfterTimeout(requestId, terminalSessionId, now);
            mode = "TIMEOUT";
        } else if ("DENIED".equals(row.status())) {
            throw IdentityApiException.handoffDenied();
        } else if ("CANCELLED".equals(row.status())) {
            throw IdentityApiException.handoffCancelled("login_handoff_cancelled");
        } else if ("SUPERSEDED".equals(row.status())) {
            throw IdentityApiException.handoffCancelled("login_handoff_superseded");
        } else if ("EXPIRED".equals(row.status())) {
            throw IdentityApiException.handoffExpired();
        } else if (!"APPROVED".equals(row.status()) && !"COMPLETED".equals(row.status())) {
            throw IdentityApiException.handoffCancelled("login_handoff_unavailable");
        }

        if (terminalSessionId == null) throw IdentityApiException.sessionReplaced();
        boolean retry = "COMPLETED".equals(row.status());
        TerminalSessionService.TerminalGrant grant = terminalSessions.issueForExisting(
                user, terminalSessionId, row.newDeviceHash(), retry);
        repository.complete(requestId, now);
        notifications.record(user, terminalSessionId, mode, summaries.summarize(context),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        return grant.session();
    }

    @Transactional
    public void cancel(UUID requestId, String pendingToken, String rawDeviceId) {
        HandoffRow row = lockedRequest(requestId);
        verifyPendingCredential(row, pendingToken, rawDeviceId);
        if ("CANCELLED".equals(row.status())) return;
        if (!"PENDING".equals(row.status())) {
            throw IdentityApiException.handoffCancelled("login_handoff_" + row.status().toLowerCase());
        }
        repository.cancel(requestId, clock.instant());
    }

    private void lockUser(IdentityService.UserProfile user) {
        if (!repository.lockUser(user.id(), user.tenantId())) {
            throw IdentityApiException.invalidCredentials();
        }
    }

    private void requireSafetyAllowed(IdentityService.UserProfile user) {
        if (accountEnforcements.isRestricted(user.tenantId(), user.id())) {
            throw IdentityApiException.accountSafetyRestricted();
        }
    }

    private HandoffRow request(UUID requestId) {
        return queryRequest(requestId, false);
    }

    private HandoffRow lockedRequest(UUID requestId) {
        return queryRequest(requestId, true);
    }

    private HandoffRow queryRequest(UUID requestId, boolean lock) {
        LoginHandoffRepository.HandoffRow row = repository.findRequest(requestId, lock)
                .orElseThrow(IdentityApiException::invalidCredentials);
        return new HandoffRow(
                row.id(), row.tenantId(), row.userId(), row.oldSessionId(), row.newSessionId(),
                row.newDeviceHash(), row.requestTokenHash(), row.status(), row.requestedAt(),
                row.expiresAt());
    }

    IdentityService.UserProfile loadUser(UUID userId, UUID tenantId) {
        LoginHandoffRepository.UserRow row = repository.findUser(userId, tenantId)
                .orElseThrow(IdentityApiException::invalidCredentials);
        return new IdentityService.UserProfile(
                row.id(), row.tenantId(), row.email(), row.displayName(), row.role(),
                row.passwordSet(), row.status(), row.authVersion());
    }

    private void verifyPendingCredential(HandoffRow row, String pendingToken, String rawDeviceId) {
        if (!tokens.hash(pendingToken).equals(row.requestTokenHash())
                || !devices.hashRequired(rawDeviceId).equals(row.newDeviceHash())) {
            throw IdentityApiException.invalidHandoffDevice();
        }
    }

    private void requireOldTerminal(HandoffRow row, AuthenticatedTerminal terminal) {
        requireDesktop(terminal);
        if (!row.userId().equals(terminal.user().id())
                || !row.tenantId().equals(terminal.user().tenantId())
                || !row.oldSessionId().equals(terminal.sessionId())) {
            throw IdentityApiException.invalidCredentials();
        }
    }

    private void requireDesktop(AuthenticatedTerminal terminal) {
        if (terminal == null || terminal.user() == null || terminal.sessionId() == null
                || !CLIENT_TYPE.equals(terminal.clientType())) {
            throw IdentityApiException.invalidCredentials();
        }
    }

    private void requirePending(HandoffRow row) {
        if (!"PENDING".equals(row.status())) {
            throw IdentityApiException.handoffCancelled("login_handoff_" + row.status().toLowerCase());
        }
        if (!clock.instant().isBefore(row.expiresAt())) throw IdentityApiException.handoffExpired();
    }

    public sealed interface LoginResult permits Completed, Pending, DeviceLoginRecoveryService.Challenge { }

    public record Completed(IdentityService.AccessSession session, UUID terminalSessionId)
            implements LoginResult { }

    public record Pending(
            UUID requestId,
            Instant expiresAt,
            int pollAfterSeconds,
            String pendingToken) implements LoginResult { }

    public record AuthenticatedTerminal(
            IdentityService.UserProfile user, UUID sessionId, String clientType) { }

    public record PendingApprovalView(
            UUID requestId,
            Instant requestedAt,
            Instant expiresAt,
            String region,
            String clientSummary) { }

    public record HandoffStatusView(UUID requestId, String status, Instant expiresAt) { }

    private record HandoffRow(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID oldSessionId,
            UUID newSessionId,
            String newDeviceHash,
            String requestTokenHash,
            String status,
            Instant requestedAt,
            Instant expiresAt) { }
}
