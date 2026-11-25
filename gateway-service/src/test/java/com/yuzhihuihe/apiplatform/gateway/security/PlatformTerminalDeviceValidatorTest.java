package com.yuzhihuihe.apiplatform.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class PlatformTerminalDeviceValidatorTest {

    private static final String TOKEN = "terminal-access-token";
    private static final String DEVICE_ID = "0123456789abcdef0123456789abcdef";

    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> deviceId = new AtomicReference<>();
    private HttpServer server;
    private String validationUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/terminal-device/validate", this::validate);
        server.start();
        validationUri = "http://127.0.0.1:" + server.getAddress().getPort()
                + "/terminal-device/validate";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void forwardsTheBearerTokenAndDeviceIdToPlatform() {
        PlatformTerminalDeviceValidator validator = new PlatformTerminalDeviceValidator(
                WebClient.builder(), validationUri, Duration.ofSeconds(2));

        assertThat(validator.validate(TOKEN, DEVICE_ID).block()).isTrue();
        assertThat(authorization).hasValue("Bearer " + TOKEN);
        assertThat(deviceId).hasValue(DEVICE_ID);
    }

    @Test
    void returnsFalseWhenPlatformRejectsTheBinding() {
        PlatformTerminalDeviceValidator validator = new PlatformTerminalDeviceValidator(
                WebClient.builder(), validationUri, Duration.ofSeconds(2));

        assertThat(validator.validate(TOKEN, "fedcba9876543210fedcba9876543210").block())
                .isFalse();
    }

    private void validate(HttpExchange exchange) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        deviceId.set(exchange.getRequestHeaders().getFirst("X-Device-Id"));
        int status = ("Bearer " + TOKEN).equals(authorization.get())
                && DEVICE_ID.equals(deviceId.get()) ? 204 : 401;
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
