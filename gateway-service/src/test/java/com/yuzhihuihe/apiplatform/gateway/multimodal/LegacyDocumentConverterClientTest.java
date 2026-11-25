package com.yuzhihuihe.apiplatform.gateway.multimodal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.yuzhihuihe.apiplatform.gateway.file.FileContentValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

class LegacyDocumentConverterClientTest {

    private static final String TOKEN = "gateway-converter-test-token";
    private static final long MAX_BYTES = 1024 * 1024;

    @Test
    void sendsAuthenticatedDocAndXlsRequestsAndReturnsTypedValidatedOutput() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> sourceFormat = new AtomicReference<>();
        AtomicReference<byte[]> received = new AtomicReference<>();
        byte[] docx = docx();
        byte[] xlsx = xlsx();
        HttpServer server = start(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            sourceFormat.set(exchange.getRequestHeaders().getFirst("X-Source-Format"));
            received.set(exchange.getRequestBody().readAllBytes());
            if ("doc".equals(sourceFormat.get())) {
                respond(exchange, 200,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        docx);
            } else {
                respond(exchange, 200,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        xlsx);
            }
        });
        try {
            LegacyDocumentConverterClient client = client(server, Duration.ofSeconds(1));
            byte[] doc = ole("WordDocument");
            LegacyDocumentConverterClient.ConvertedDocument convertedDoc = client.convert(
                    "legacy.doc", "application/msword", doc).block(Duration.ofSeconds(2));

            assertThat(authorization.get()).isEqualTo("Bearer " + TOKEN);
            assertThat(sourceFormat.get()).isEqualTo("doc");
            assertThat(received.get()).isEqualTo(doc);
            assertThat(convertedDoc.format())
                    .isEqualTo(LegacyDocumentConverterClient.ConvertedFormat.DOCX);
            assertThat(convertedDoc.content()).isEqualTo(docx);

            byte[] xls = ole("Workbook");
            LegacyDocumentConverterClient.ConvertedDocument convertedXls = client.convert(
                    "legacy.xls", "application/vnd.ms-excel", xls).block(Duration.ofSeconds(2));
            assertThat(sourceFormat.get()).isEqualTo("xls");
            assertThat(received.get()).isEqualTo(xls);
            assertThat(convertedXls.format())
                    .isEqualTo(LegacyDocumentConverterClient.ConvertedFormat.XLSX);
            assertThat(convertedXls.content()).isEqualTo(xlsx);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidSourceBeforeCallingConverter() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = start(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "application/octet-stream", new byte[] {1});
        });
        try {
            LegacyDocumentConverterClient client = client(server, Duration.ofSeconds(1));

            assertStableError(
                    () -> client.convert(
                            "not-doc.pdf", "application/msword", ole("WordDocument"))
                            .block(Duration.ofSeconds(2)),
                    HttpStatus.BAD_REQUEST,
                    "invalid_file_content");
            assertThat(calls).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsCorruptOrMismatchedSuccessfulOutput() throws Exception {
        HttpServer server = start(exchange -> respond(
                exchange,
                200,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "not-a-docx".getBytes(StandardCharsets.UTF_8)));
        try {
            LegacyDocumentConverterClient client = client(server, Duration.ofSeconds(1));

            assertStableError(
                    () -> client.convert(
                            "legacy.doc", "application/msword", ole("WordDocument"))
                            .block(Duration.ofSeconds(2)),
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "document_conversion_failed");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsConverterStatusAndClientTimeoutToStableGatewayErrors() throws Exception {
        assertMapping(400, HttpStatus.UNPROCESSABLE_ENTITY, "document_conversion_failed");
        assertMapping(413, HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large");
        assertMapping(422, HttpStatus.UNPROCESSABLE_ENTITY, "document_conversion_failed");
        assertMapping(503, HttpStatus.SERVICE_UNAVAILABLE, "document_converter_unavailable");
        assertMapping(504, HttpStatus.GATEWAY_TIMEOUT, "multimodal_preprocessing_timeout");

        HttpServer timeoutServer = start(exchange -> {
            try {
                Thread.sleep(200);
                respond(exchange, 200,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        docx());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            LegacyDocumentConverterClient client = client(timeoutServer, Duration.ofMillis(25));
            assertStableError(
                    () -> client.convert(
                            "legacy.doc", "application/msword", ole("WordDocument"))
                            .block(Duration.ofSeconds(2)),
                    HttpStatus.GATEWAY_TIMEOUT,
                    "multimodal_preprocessing_timeout");
        } finally {
            timeoutServer.stop(0);
        }
    }

    @Test
    void stableFailuresNeverIncludeTokenOrDocumentBytes() throws Exception {
        byte[] privateContent = ole("WordDocument");
        HttpServer server = start(exchange -> respond(
                exchange, 503, "application/json",
                "{\"error\":{\"code\":\"secret-upstream-detail\"}}"
                        .getBytes(StandardCharsets.UTF_8)));
        try {
            LegacyDocumentConverterClient client = client(server, Duration.ofSeconds(1));

            assertThatThrownBy(() -> client.convert(
                            "customer-secret.doc", "application/msword", privateContent)
                    .block(Duration.ofSeconds(2)))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> {
                        String rendered = error.toString();
                        assertThat(rendered).doesNotContain(TOKEN);
                        assertThat(rendered).doesNotContain("customer-secret");
                        assertThat(rendered).doesNotContain("secret-upstream-detail");
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnsafeConverterEndpointsAndKnownDefaultToken() {
        FileContentValidator validator = new FileContentValidator();

        assertThatThrownBy(() -> new LegacyDocumentConverterClient(
                        WebClient.builder(), URI.create("http://converter.example.test"),
                        TOKEN, MAX_BYTES, Duration.ofSeconds(1), validator,
                        false, java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_document_converter_configuration");
        assertThatThrownBy(() -> new LegacyDocumentConverterClient(
                        WebClient.builder(), URI.create("https://converter.example.test/path?secret=x"),
                        TOKEN, MAX_BYTES, Duration.ofSeconds(1), validator,
                        false, java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_document_converter_configuration");
        assertThatThrownBy(() -> new LegacyDocumentConverterClient(
                        WebClient.builder(), URI.create("https://converter.example.test"),
                        "local-document-converter-token-change-me", MAX_BYTES,
                        Duration.ofSeconds(1), validator, false, java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_document_converter_configuration");
    }

    private static void assertMapping(int converterStatus, HttpStatus expected, String code)
            throws Exception {
        HttpServer server = start(exchange -> respond(
                exchange, converterStatus, "application/json",
                "{\"error\":{\"code\":\"private-detail\"}}"
                        .getBytes(StandardCharsets.UTF_8)));
        try {
            assertStableError(
                    () -> client(server, Duration.ofSeconds(1)).convert(
                            "legacy.doc", "application/msword", ole("WordDocument"))
                            .block(Duration.ofSeconds(2)),
                    expected,
                    code);
        } finally {
            server.stop(0);
        }
    }

    private static LegacyDocumentConverterClient client(HttpServer server, Duration timeout) {
        return new LegacyDocumentConverterClient(
                WebClient.builder(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                TOKEN,
                MAX_BYTES,
                timeout,
                new FileContentValidator(),
                true,
                java.util.Set.of("127.0.0.1"));
    }

    private static HttpServer start(HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/convert", handler);
        server.start();
        return server;
    }

    private static void respond(
            HttpExchange exchange, int status, String contentType, byte[] content) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    private static byte[] ole(String streamName) {
        try (POIFSFileSystem filesystem = new POIFSFileSystem();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            filesystem.getRoot().createDocument(
                    streamName, new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
            filesystem.writeFilesystem(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static byte[] docx() {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("converted");
            document.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static byte[] xlsx() {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("converted");
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void assertStableError(
            Runnable action, HttpStatus status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(status);
                    assertThat(response.getReason()).isEqualTo(code);
                });
    }
}
