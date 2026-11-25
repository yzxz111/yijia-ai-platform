package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class LoginHandoffCleanupJob {

    private final LoginHandoffRepository repository;
    private final Clock clock;
    private final Duration completionGrace;
    private final Duration auditRetention;

    @org.springframework.beans.factory.annotation.Autowired
    LoginHandoffCleanupJob(
            LoginHandoffRepository repository,
            @Value("${identity.terminal-session.completion-grace:1m}") Duration completionGrace,
            @Value("${identity.terminal-session.audit-retention:30d}") Duration auditRetention) {
        this(repository, Clock.systemUTC(), completionGrace, auditRetention);
    }

    LoginHandoffCleanupJob(
            LoginHandoffRepository repository,
            Clock clock,
            Duration completionGrace,
            Duration auditRetention) {
        this.repository = repository;
        this.clock = clock;
        this.completionGrace = completionGrace;
        this.auditRetention = auditRetention;
    }

    @Scheduled(fixedDelayString = "${identity.terminal-session.cleanup-delay:3600000}")
    @Transactional
    void scheduledRun() {
        runOnce();
    }

    @Transactional
    CleanupResult runOnce() {
        Instant now = clock.instant();
        int requestsExpired = repository.expirePendingRequests(
                now, now.minus(completionGrace));
        int sessionsExpired = repository.expireActiveSessions(now);
        int auditsDeleted = repository.deleteCompletedRequestsBefore(
                now.minus(auditRetention));
        return new CleanupResult(requestsExpired, sessionsExpired, auditsDeleted);
    }

    record CleanupResult(int requestsExpired, int sessionsExpired, int auditsDeleted) { }
}
