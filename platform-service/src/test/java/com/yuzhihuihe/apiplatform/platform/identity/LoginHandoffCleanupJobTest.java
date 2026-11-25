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
import org.springframework.jdbc.core.JdbcTemplate;

class LoginHandoffCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-08-31T02:00:00Z");

    @Test
    void keepsTerminalSessionSqlBehindTheRepositoryBoundary() {
        assertThat(hasFieldOfType(LoginHandoffRepository.class)).isTrue();
        assertThat(hasFieldOfType(JdbcTemplate.class)).isFalse();
    }

    @Test
    void coordinatesCleanupThroughTheRepositoryWithStableTimeBoundaries() {
        LoginHandoffRepository repository = mock(LoginHandoffRepository.class);
        when(repository.expirePendingRequests(NOW, NOW.minus(Duration.ofMinutes(1))))
                .thenReturn(2);
        when(repository.expireActiveSessions(NOW)).thenReturn(3);
        when(repository.deleteCompletedRequestsBefore(NOW.minus(Duration.ofDays(30))))
                .thenReturn(4);
        LoginHandoffCleanupJob job = new LoginHandoffCleanupJob(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(1),
                Duration.ofDays(30));

        LoginHandoffCleanupJob.CleanupResult result = job.runOnce();

        assertThat(result).isEqualTo(new LoginHandoffCleanupJob.CleanupResult(2, 3, 4));
        verify(repository).expirePendingRequests(NOW, NOW.minus(Duration.ofMinutes(1)));
        verify(repository).expireActiveSessions(NOW);
        verify(repository).deleteCompletedRequestsBefore(NOW.minus(Duration.ofDays(30)));
    }

    private static boolean hasFieldOfType(Class<?> expectedType) {
        return java.util.Arrays.stream(LoginHandoffCleanupJob.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(expectedType));
    }
}
