package com.yuzhihuihe.apiplatform.gateway.safety;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClamAvClientTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsOnlySafeClamResponsesToCleanOrInfected() {
        assertThat(ClamAvClient.parseResponse("stream: OK").status())
                .isEqualTo(ClamAvScanResult.Status.CLEAN);
        assertThat(ClamAvClient.parseResponse("stream: signature FOUND").status())
                .isEqualTo(ClamAvScanResult.Status.INFECTED);
        assertThat(ClamAvClient.parseResponse("unexpected").status())
                .isEqualTo(ClamAvScanResult.Status.ERROR);
    }

    @Test
    void streamsAFileThroughTheInstreamProtocol() throws Exception {
        byte[] content = "skill package".repeat(10_000).getBytes(StandardCharsets.UTF_8);
        Path packageFile = tempDir.resolve("skill.zip");
        Files.write(packageFile, content);
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Long> received = CompletableFuture.supplyAsync(() -> {
                try (var socket = server.accept();
                        var input = new DataInputStream(socket.getInputStream())) {
                    byte[] command = input.readNBytes(10);
                    assertThat(new String(command, StandardCharsets.US_ASCII))
                            .isEqualTo("zINSTREAM\0");
                    long total = 0;
                    int chunk;
                    while ((chunk = input.readInt()) != 0) {
                        total += input.readNBytes(chunk).length;
                    }
                    socket.getOutputStream().write("stream: OK\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return total;
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            ClamAvClient client = new ClamAvClient(
                    "127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2), Duration.ofSeconds(5));
            assertThat(client.scan(packageFile).block(Duration.ofSeconds(10)).status())
                    .isEqualTo(ClamAvScanResult.Status.CLEAN);
            assertThat(received.get(10, TimeUnit.SECONDS)).isEqualTo(content.length);
        }
    }

    @Test
    void acceptsNullTerminatedCleanAndInfectedResponses() throws Exception {
        assertThat(scanResponse("stream: OK\0").status())
                .isEqualTo(ClamAvScanResult.Status.CLEAN);
        assertThat(scanResponse("stream: Eicar-Test-Signature FOUND\0").status())
                .isEqualTo(ClamAvScanResult.Status.INFECTED);
    }

    private ClamAvScanResult scanResponse(String response) throws Exception {
        Path packageFile = tempDir.resolve(UUID.randomUUID() + ".zip");
        Files.writeString(packageFile, "package", StandardCharsets.UTF_8);
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> replied = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept();
                        var input = new DataInputStream(socket.getInputStream())) {
                    assertThat(new String(input.readNBytes(10), StandardCharsets.US_ASCII))
                            .isEqualTo("zINSTREAM\0");
                    int chunk;
                    while ((chunk = input.readInt()) != 0) {
                        input.readNBytes(chunk);
                    }
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            ClamAvClient client = new ClamAvClient(
                    "127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2), Duration.ofSeconds(2));
            ClamAvScanResult result = client.scan(packageFile).block(Duration.ofSeconds(5));
            replied.get(5, TimeUnit.SECONDS);
            return result;
        }
    }
}
