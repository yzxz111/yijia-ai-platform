package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DownstreamRetryHeadersTest {
    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @ParameterizedTest
    @CsvSource({"rate_limit_exceeded", "tokens_per_minute_exceeded", "seconds_per_minute_exceeded",
            "images_per_minute_exceeded"})
    void minuteLimitsUseAConservativeWindowAndDisableCaching(String code) {
        HttpHeaders headers = OpenAiErrorMapper.retryHeaders(
                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, code),
                at("2026-11-24T03:00:00Z"));
        assertThat(headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(headers.getCacheControl()).isEqualTo("no-store");
    }

    @ParameterizedTest
    @CsvSource({"2026-11-24T15:59:59.500Z,1", "2026-11-24T15:59:58.001Z,2",
            "2026-11-24T16:00:00Z,86400", "2026-11-24T03:00:00Z,46800"})
    void dailyLimitWaitsUntilTheNextShanghaiDay(String now, String expected) {
        assertThat(OpenAiErrorMapper.retryHeaders(new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "daily_request_limit_exceeded"), at(now))
                .getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo(expected);
    }

    @Test
    void explicitLimiterDelayIsPreserved() {
        ResponseStatusException error = new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS) {
            @Override public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.RETRY_AFTER, "7");
                return headers;
            }
        };
        assertThat(OpenAiErrorMapper.retryHeaders(error).getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("7");
    }

    @Test
    void nonRateErrorsNeverSuggestAnAutomaticRetry() {
        assertThat(OpenAiErrorMapper.retryHeaders(new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "invalid_api_key"))).isEmpty();
        assertThat(OpenAiErrorMapper.retryHeaders(new IllegalStateException("private detail")))
                .isEmpty();
    }
}
