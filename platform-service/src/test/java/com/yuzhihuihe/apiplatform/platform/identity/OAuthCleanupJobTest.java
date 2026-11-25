package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OAuthCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-09-12T04:00:00Z");

    @Test
    void keepsJdbcBehindTheRepositoryBoundary() {
        assertThat(Arrays.stream(OAuthCleanupJob.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(JdbcTemplate.class))).isFalse();
    }

    @Test
    void deletesRetiredRecordsWithStableTimeBoundaries() {
        OAuthAuthorizationCodeRepository codes = mock(OAuthAuthorizationCodeRepository.class);
        OAuthRefreshTokenRepository refreshTokens = mock(OAuthRefreshTokenRepository.class);
        OAuthLoginTransactionRepository transactions =
                mock(OAuthLoginTransactionRepository.class);
        when(codes.deleteExpired(NOW, NOW.minus(Duration.ofDays(1)))).thenReturn(3);
        when(refreshTokens.deleteRetired(NOW, NOW.minus(Duration.ofDays(30)))).thenReturn(2);
        when(transactions.deleteRetired(NOW, NOW.minus(Duration.ofDays(1)))).thenReturn(1);

        OAuthCleanupJob job = new OAuthCleanupJob(
                codes, refreshTokens, transactions,
                Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(30),
                Clock.fixed(NOW, ZoneOffset.UTC));

        OAuthCleanupJob.CleanupResult result = job.runOnce();

        assertThat(result).isEqualTo(new OAuthCleanupJob.CleanupResult(3, 2, 1));
        verify(codes).deleteExpired(NOW, NOW.minus(Duration.ofDays(1)));
        verify(refreshTokens).deleteRetired(NOW, NOW.minus(Duration.ofDays(30)));
        verify(transactions).deleteRetired(NOW, NOW.minus(Duration.ofDays(1)));
    }
}
