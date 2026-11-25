package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

class PlatformPackageModelClientTest {

    private static final String TOKEN = "admin-access-token";

    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private int responseStatus;
    private String responseBody;
    private PlatformPackageModelClient client;

    @BeforeEach
    void startServer() throws IOException {
        responseStatus = 200;
        responseBody = """
                {"modelCode":"text-basic","referenceCount":2,"references":[]}
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new PlatformPackageModelClient(
                WebClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(2));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void forwardsAdminTokenAndReturnsPackageReferences() {
        PlatformPackageModelClient.ModelReferenceView result =
                client.references("text-basic", TOKEN).block();

        assertThat(authorization).hasValue("Bearer " + TOKEN);
        assertThat(result.modelCode()).isEqualTo("text-basic");
        assertThat(result.referenceCount()).isEqualTo(2);
    }

    @Test
    void failsClosedWhenPlatformCannotValidateReferences() {
        responseStatus = 500;
        responseBody = "upstream-sensitive-detail";

        assertThatThrownBy(() -> client.references("text-basic", TOKEN).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("package_reference_check_unavailable");
                    assertThat(error.toString()).doesNotContain(responseBody);
                });
    }

    @Test
    void rejectsInvalidClientConfiguration() {
        assertThatThrownBy(() -> new PlatformPackageModelClient(
                WebClient.builder(), " ", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("package_catalog_base_url_required");
        assertThatThrownBy(() -> new PlatformPackageModelClient(
                WebClient.builder(), "http://platform-service:8080", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("package_catalog_timeout_invalid");
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException error) {
            throw new RuntimeException(error);
        }
    }
}
