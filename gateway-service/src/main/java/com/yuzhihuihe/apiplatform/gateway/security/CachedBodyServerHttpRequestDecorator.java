package com.yuzhihuihe.apiplatform.gateway.security;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import reactor.core.publisher.Flux;

public final class CachedBodyServerHttpRequestDecorator extends ServerHttpRequestDecorator {

    private final byte[] body;
    private final DataBufferFactory bufferFactory;

    public CachedBodyServerHttpRequestDecorator(
            ServerHttpRequest delegate, byte[] body, DataBufferFactory bufferFactory) {
        super(delegate);
        this.body = body.clone();
        this.bufferFactory = bufferFactory;
    }

    @Override
    public Flux<DataBuffer> getBody() {
        return Flux.defer(() -> Flux.just(bufferFactory.wrap(body)));
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(super.getHeaders());
        headers.remove(HttpHeaders.TRANSFER_ENCODING);
        headers.setContentLength(body.length);
        return headers;
    }
}
