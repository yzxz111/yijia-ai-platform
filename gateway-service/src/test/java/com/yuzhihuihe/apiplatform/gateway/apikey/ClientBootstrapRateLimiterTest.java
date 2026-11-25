package com.yuzhihuihe.apiplatform.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ClientBootstrapRateLimiterTest {

    private static final UUID USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000021");
    private static final UUID SESSION_ID = UUID.fromString("21000000-0000-4000-8000-000000000021");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static ReactiveStringRedisTemplate redis;
    static ClientBootstrapRateLimiter rateLimiter;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
        rateLimiter = new ClientBootstrapRateLimiter(
                redis, 10, Duration.ofMinutes(1), Duration.ofSeconds(1));
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void clearRedis() {
        redis.execute(connection -> connection.serverCommands().flushAll()).then().block();
    }

    @Test
    void atomicallyLimitsTheEleventhAttemptForOneUserSession() {
        for (int attempt = 0; attempt < 10; attempt++) {
            rateLimiter.acquire(USER_ID, SESSION_ID).block(Duration.ofSeconds(2));
        }

        assertThatThrownBy(() -> rateLimiter.acquire(USER_ID, SESSION_ID)
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
                })
                .hasMessageContaining("client_bootstrap_rate_limited");
    }

    @Test
    void usesAnIndependentWindowForAnotherTerminalSession() {
        for (int attempt = 0; attempt < 10; attempt++) {
            rateLimiter.acquire(USER_ID, SESSION_ID).block(Duration.ofSeconds(2));
        }

        rateLimiter.acquire(USER_ID, UUID.randomUUID()).block(Duration.ofSeconds(2));

        assertThat(redis.keys("gateway:client-bootstrap:*").collectList().block())
                .hasSize(2);
    }
}
