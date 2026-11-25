package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class TerminalSessionCloseCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");

    @Test
    void revokesOnlyCloseRequestsOlderThanTheConfiguredGracePeriod() {
        TerminalSessionRepository repository = mock(TerminalSessionRepository.class);
        Instant cutoff = NOW.minus(Duration.ofMinutes(2));
        when(repository.revokeBrowserClosedTerminals(cutoff, NOW)).thenReturn(3);
        TerminalSessionCloseCleanupJob job = new TerminalSessionCloseCleanupJob(
                repository, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(2));

        assertThat(job.runOnce()).isEqualTo(3);
        verify(repository).revokeBrowserClosedTerminals(cutoff, NOW);
    }
}
