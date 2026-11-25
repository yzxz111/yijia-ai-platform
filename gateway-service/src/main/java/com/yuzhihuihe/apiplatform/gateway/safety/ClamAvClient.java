package com.yuzhihuihe.apiplatform.gateway.safety;

import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class ClamAvClient {

    private static final int MAX_CHUNK_BYTES = 1_048_576;
    private static final long MAX_FILE_BYTES = 20_971_520L;

    private final String host;
    private final int port;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public ClamAvClient(String host, int port, Duration connectTimeout, Duration readTimeout) {
        this.host = host;
        this.port = port;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public Mono<ClamAvScanResult> scan(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_FILE_BYTES) {
            return Mono.just(new ClamAvScanResult(ClamAvScanResult.Status.ERROR));
        }
        return Mono.fromCallable(() -> scanBlocking(bytes))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(new ClamAvScanResult(ClamAvScanResult.Status.ERROR));
    }

    /** 通过 clamd 的 INSTREAM 协议从磁盘流式传输大包，不做堆缓冲。 */
    public Mono<ClamAvScanResult> scan(Path path) {
        if (path == null) {
            return Mono.just(new ClamAvScanResult(ClamAvScanResult.Status.ERROR));
        }
        return Mono.fromCallable(() -> {
                    try (InputStream input = Files.newInputStream(path)) {
                        return scanBlocking(input);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(new ClamAvScanResult(ClamAvScanResult.Status.ERROR));
    }

    static ClamAvScanResult parseResponse(String response) {
        if (response == null) {
            return new ClamAvScanResult(ClamAvScanResult.Status.ERROR);
        }
        String normalized = response.trim();
        if (normalized.equalsIgnoreCase("stream: OK")) {
            return new ClamAvScanResult(ClamAvScanResult.Status.CLEAN);
        }
        if (normalized.toUpperCase(java.util.Locale.ROOT).startsWith("STREAM:")
                && normalized.toUpperCase(java.util.Locale.ROOT).endsWith(" FOUND")) {
            return new ClamAvScanResult(ClamAvScanResult.Status.INFECTED);
        }
        return new ClamAvScanResult(ClamAvScanResult.Status.ERROR);
    }

    private ClamAvScanResult scanBlocking(byte[] bytes) throws IOException {
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            return scanBlocking(input);
        }
    }

    private ClamAvScanResult scanBlocking(InputStream content) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) connectTimeout.toMillis());
            socket.setSoTimeout((int) readTimeout.toMillis());
            try (DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                byte[] chunk = new byte[MAX_CHUNK_BYTES];
                int length;
                while ((length = content.read(chunk)) != -1) {
                    if (length == 0) {
                        continue;
                    }
                    output.writeInt(length);
                    output.write(chunk, 0, length);
                }
                output.writeInt(0);
                output.flush();
                return parseResponse(readLine(socket.getInputStream()));
            }
        }
    }

    private String readLine(InputStream input) throws IOException {
        StringBuilder value = new StringBuilder();
        int next;
        // z 前缀的 clamd 命令使用 NUL 终止符。也接受换行，以兼容更早的
        // 代理与测试替身，但绝不要等待对端关闭一个成功的响应。
        while ((next = input.read()) != -1
                && next != 0
                && next != '\n'
                && value.length() < 256) {
            value.append((char) next);
        }
        return value.toString();
    }
}
