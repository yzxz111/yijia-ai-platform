package com.yuzhihuihe.apiplatform.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

class ClientBootstrapRateLimiterUnitTest {

    @Test
    void rateLimitErrorCarriesTheConfiguredWindowAsRetryAfter() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(2L)).when(redis).execute(any(), anyList(), anyList());
        ClientBootstrapRateLimiter limiter = new ClientBootstrapRateLimiter(
                redis, 1, Duration.ofSeconds(75), Duration.ofSeconds(1));

        assertThatThrownBy(() -> limiter.acquire(UUID.randomUUID(), UUID.randomUUID()).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("75");
                });
    }
}
