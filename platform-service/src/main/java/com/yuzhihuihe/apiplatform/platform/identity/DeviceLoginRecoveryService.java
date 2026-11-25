package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import com.yuzhihuihe.apiplatform.platform.safety.AccountEnforcementRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeviceLoginRecoveryService {
    private final DeviceLoginRecoveryRepository requests;
    private final LoginHandoffRepository users;
    private final TerminalSessionRepository sessions;
    private final TerminalSessionService terminals;
    private final DeviceIdentityService devices;
    private final OpaqueTokenService tokens;
    private final TerminalPresenceService presence;
    private final AccountEnforcementRepository enforcements;
    private final LoginActivityService activity;
    private final DesktopLoginNotificationService notifications;
    private final LoginClientSummaryService summaries;

    DeviceLoginRecoveryService(DeviceLoginRecoveryRepository requests, LoginHandoffRepository users,
            TerminalSessionRepository sessions, TerminalSessionService terminals, DeviceIdentityService devices,
            OpaqueTokenService tokens, TerminalPresenceService presence, AccountEnforcementRepository enforcements,
            LoginActivityService activity, DesktopLoginNotificationService notifications, LoginClientSummaryService summaries) {
        this.requests = requests; this.users = users; this.sessions = sessions; this.terminals = terminals;
        this.devices = devices; this.tokens = tokens; this.presence = presence; this.enforcements = enforcements;
        this.activity = activity; this.notifications = notifications; this.summaries = summaries;
    }

    /** Called only after password/code verification, while the user's capacity lock is held. */
    @Transactional
    Challenge begin(IdentityService.UserProfile user, String deviceHash, int limit) {
        UUID id = UUID.randomUUID();
        String token = tokens.generate();
        String csrf = tokens.generate();
        Instant expires = Instant.now().plusSeconds(300);
        requests.insert(new DeviceLoginRecoveryRepository.Request(id, user.tenantId(), user.id(),
                deviceHash, tokens.hash(token), tokens.hash(csrf), user.authVersion(), expires));
        List<DeviceView> rows = requests.devices(user.id(), user.tenantId()).stream()
                .map(row -> new DeviceView(row.id(), row.createdAt(), presence.isOnline(user.id(), row.id())))
                .toList();
        return new Challenge(id, expires, limit, rows, token, csrf);
    }

    @Transactional
    IdentityService.AccessSession complete(UUID id, String token, String csrf, String deviceId,
            List<UUID> selected, LoginActivityService.LoginContext context) {
        var initial = requests.find(id, false).orElseThrow(IdentityApiException::deviceRecoveryExpired);
        verify(initial, token, csrf, deviceId);
        // Use the same lock order as normal login: user, request, terminals, refresh credentials.
        if (!users.lockUser(initial.userId(), initial.tenantId())) throw IdentityApiException.invalidCredentials();
        var request = requests.find(id, true).orElseThrow(IdentityApiException::deviceRecoveryExpired);
        verify(request, token, csrf, deviceId);
        var row = users.findUser(request.userId(), request.tenantId()).orElseThrow(IdentityApiException::invalidCredentials);
        if (row.authVersion() != request.authVersion()) throw IdentityApiException.deviceRecoveryExpired();
        var user = new IdentityService.UserProfile(row.id(), row.tenantId(), row.email(), row.displayName(),
                row.role(), row.passwordSet(), row.status(), row.authVersion());
        if (enforcements.isRestricted(user.tenantId(), user.id())) throw IdentityApiException.accountSafetyRestricted();
        users.expireActiveTerminals(user.id(), Instant.now());
        var active = users.lockActiveTerminals(user.id());
        if (selected == null || selected.size() > 100 || selected.contains(null)
                || new HashSet<>(selected).size() != selected.size()) throw IdentityApiException.invalidDeviceSelection();
        var activeIds = active.stream().map(LoginHandoffRepository.ActiveTerminalRow::id).toList();
        if (!activeIds.containsAll(selected)) throw IdentityApiException.invalidDeviceSelection();
        Integer limit = users.maxLoginDevices(user.id(), user.tenantId());
        if (limit == null || active.size() - selected.size() >= limit) throw IdentityApiException.loginDeviceLimitReached();
        if (active.stream().anyMatch(item -> item.deviceHash().equals(request.deviceHash()))) {
            throw IdentityApiException.deviceRecoveryExpired();
        }
        for (UUID sessionId : selected) {
            sessions.revokeTerminalForReplacement(sessionId, user.tenantId(), user.id(), "DEVICE_RECOVERY");
            sessions.revokeRefreshesForTerminal(sessionId, "DEVICE_RECOVERY");
        }
        var grant = terminals.create(user, request.deviceHash());
        requests.consume(id);
        activity.recordSuccess(user, context);
        notifications.record(user, grant.terminalSessionId(), "DEVICE_RECOVERY", summaries.summarize(context),
                OffsetDateTime.now(ZoneOffset.UTC));
        return grant.session();
    }

    private void verify(DeviceLoginRecoveryRepository.Request row, String token, String csrf, String deviceId) {
        if (!row.expiresAt().isAfter(Instant.now())) throw IdentityApiException.deviceRecoveryExpired();
        if (!tokens.hash(token).equals(row.tokenHash()) || !devices.hashRequired(deviceId).equals(row.deviceHash())) {
            throw IdentityApiException.invalidCredentials();
        }
        if (csrf == null || !tokens.hash(csrf).equals(row.csrfHash())) throw IdentityApiException.invalidCsrfToken();
    }

    @Scheduled(fixedDelayString = "${identity.terminal-session.cleanup-delay:3600000}")
    void cleanup() { requests.deleteExpired(); }

    record DeviceView(UUID id, Instant createdAt, boolean recentlyOnline) { }
    record Challenge(UUID requestId, Instant expiresAt, int deviceLimit, List<DeviceView> devices,
            String token, String csrf) implements LoginHandoffService.LoginResult { }
}
