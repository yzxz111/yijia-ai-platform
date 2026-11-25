package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class TerminalSessionCloseCleanupJob {

    private final TerminalSessionRepository repository;
    private final Clock clock;
    private final Duration closeGrace;

    @org.springframework.beans.factory.annotation.Autowired
    TerminalSessionCloseCleanupJob(
            TerminalSessionRepository repository,
            @Value("${identity.terminal-session.browser-close-grace:2m}") Duration closeGrace) {
        this(repository, Clock.systemUTC(), closeGrace);
    }

    TerminalSessionCloseCleanupJob(
            TerminalSessionRepository repository, Clock clock, Duration closeGrace) {
        this.repository = repository;
        this.clock = clock;
        this.closeGrace = closeGrace;
    }

    @Scheduled(fixedDelayString =
            "${identity.terminal-session.browser-close-cleanup-delay:10000}")
    @Transactional
    void scheduledRun() {
        runOnce();
    }

    @Transactional
    int runOnce() {
        Instant now = clock.instant();
        return repository.revokeBrowserClosedTerminals(now.minus(closeGrace), now);
    }
}
