package com.yuzhihuihe.apiplatform.gateway.file;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SNIHostName;

import com.yuzhihuihe.apiplatform.gateway.security.RemoteUrlPolicy;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.client.HttpClient;

@Component
public class PinnedReactorNettyTransport implements RemoteImageFetcher.PinnedTransport {

    private final Exchange exchange;

    public PinnedReactorNettyTransport() {
        this(PinnedReactorNettyTransport::exchangePinned);
    }

    PinnedReactorNettyTransport(Exchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public Mono<RemoteImageFetcher.TransportResponse> get(
            RemoteUrlPolicy.ValidatedRemote target, long maxBytes, Duration timeout) {
        String host = target.uri().getHost();
        DialRequest request = new DialRequest(
                new InetSocketAddress(target.address(), target.port()),
                target.uri(), host, host, false, maxBytes, timeout);
        return exchange.exchange(request).timeout(timeout, Mono.error(
                new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "remote_image_timeout")));
    }

    private static Mono<RemoteImageFetcher.TransportResponse> exchangePinned(DialRequest request) {
        int timeoutMillis = (int) Math.min(Integer.MAX_VALUE,
                Math.max(1, request.timeout().toMillis()));
        HttpClient client = HttpClient.create()
                .followRedirect(request.followRedirects())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis)
                .responseTimeout(request.timeout())
                .remoteAddress(() -> request.remoteAddress())
                .headers(headers -> headers.set(HttpHeaderNames.HOST, request.hostHeader()))
                .secure(ssl -> ssl.sslContext(Http11SslContextSpec.forClient())
                        .serverNames(new SNIHostName(request.tlsServerName())));
        return client.get()
                .uri(request.uri().toASCIIString())
                .response((response, inbound) -> {
                    int status = response.status().code();
                    String location = response.responseHeaders().get(HttpHeaderNames.LOCATION);
                    String mediaType = response.responseHeaders().get(HttpHeaderNames.CONTENT_TYPE);
                    URI redirect = status >= 300 && status < 400 && location != null
                            ? URI.create(location) : null;
                    Flux<byte[]> body = inbound.map(buffer -> {
                        byte[] bytes = new byte[buffer.readableBytes()];
                        buffer.readBytes(bytes);
                        return bytes;
                    });
                    return collectResponse(status, redirect, mediaType, body, request.maxBytes());
                })
                .single()
                .timeout(request.timeout(), Mono.error(new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT, "remote_image_timeout")));
    }

    static Mono<RemoteImageFetcher.TransportResponse> collectResponse(
            int status,
            URI redirect,
            String mediaType,
            Flux<byte[]> body,
            long maxBytes) {
        AtomicLong received = new AtomicLong();
        return body.handle((bytes, sink) -> {
                    long total;
                    try {
                        total = Math.addExact(received.get(), bytes.length);
                    } catch (ArithmeticException overflow) {
                        sink.error(tooLarge());
                        return;
                    }
                    if (total > maxBytes) {
                        sink.error(tooLarge());
                        return;
                    }
                    received.set(total);
                    sink.next(bytes);
                })
                .cast(byte[].class)
                .collectList()
                .map(chunks -> new RemoteImageFetcher.TransportResponse(
                        status,
                        redirect == null ? mediaType : null,
                        redirect,
                        redirect == null ? Flux.fromIterable(chunks) : Flux.empty()));
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "remote_image_too_large");
    }

    @FunctionalInterface
    interface Exchange {
        Mono<RemoteImageFetcher.TransportResponse> exchange(DialRequest request);
    }

    record DialRequest(
            InetSocketAddress remoteAddress,
            URI uri,
            String hostHeader,
            String tlsServerName,
            boolean followRedirects,
            long maxBytes,
            Duration timeout) { }
}
