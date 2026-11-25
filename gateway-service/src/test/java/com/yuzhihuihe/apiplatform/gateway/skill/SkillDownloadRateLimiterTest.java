package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * Unit coverage for the fixed-window download throttle (design R-19/R-34): within the window the
 * call passes, above it the 429 carries the window as {@code Retry-After}, and a Redis outage fails
 * closed with 503 instead of letting every request through.
 */
class SkillDownloadRateLimiterTest {

    private static final String IDENTITY = "11111111-1111-4111-8111-111111111111";
    private static final String DEVICE = "0123456789abcdef0123456789abcdef";

    @Test
    void defaultAllowsSixtySessionsAndRejectsTheNextOne() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(60L), Flux.just(61L))
                .when(redis).execute(any(), anyList(), anyList());
        SkillProperties properties = new SkillProperties();
        SkillDownloadRateLimiter limiter = new SkillDownloadRateLimiter(
                redis, properties.getDownloadLimit(), properties.getDownloadWindow(),
                properties.getDownloadRateLimitTimeout());

        assertThat(limiter.acquire(IDENTITY, DEVICE).block()).isNull();
        assertThatThrownBy(() -> limiter.acquire(IDENTITY, DEVICE).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void allowsCallsInsideTheWindow() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(3L)).when(redis).execute(any(), anyList(), anyList());
        SkillDownloadRateLimiter limiter = new SkillDownloadRateLimiter(
                redis, 10, Duration.ofMinutes(1), Duration.ofSeconds(1));

        assertThat(limiter.acquire(IDENTITY, DEVICE).block()).isNull();
    }

    @Test
    void rateLimitErrorCarriesTheConfiguredWindowAsRetryAfter() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(11L)).when(redis).execute(any(), anyList(), anyList());
        SkillDownloadRateLimiter limiter = new SkillDownloadRateLimiter(
                redis, 10, Duration.ofSeconds(45), Duration.ofSeconds(1));

        assertThatThrownBy(() -> limiter.acquire(IDENTITY, DEVICE).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(response.getReason()).isEqualTo("rate_limited");
                    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("45");
                });
    }

    @Test
    void failsClosedWhenRedisIsUnavailable() {
        SkillDownloadRateLimiter withoutRedis = new SkillDownloadRateLimiter(
                null, 10, Duration.ofMinutes(1), Duration.ofSeconds(1));
        assertThatThrownBy(() -> withoutRedis.acquire(IDENTITY, DEVICE).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.empty()).when(redis).execute(any(), anyList(), anyList());
        SkillDownloadRateLimiter silent = new SkillDownloadRateLimiter(
                redis, 10, Duration.ofMinutes(1), Duration.ofSeconds(1));
        assertThatThrownBy(() -> silent.acquire(IDENTITY, DEVICE).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .isEqualTo("skill_storage_unavailable"));
    }
}

