package com.yuzhihuihe.apiplatform.gateway.file;

import java.util.UUID;

import reactor.core.publisher.Mono;

public interface ProviderFileBindingRepository {

    Mono<Binding> findReady(UUID fileObjectId, UUID channelId);

    Mono<Binding> find(UUID fileObjectId, UUID channelId);

    Mono<Binding> claim(UUID fileObjectId, UUID channelId);

    Mono<Void> markReady(UUID bindingId, UUID claimToken, String upstreamFileId);

    Mono<Void> markFailed(UUID bindingId, UUID claimToken);

    record Binding(
            UUID id, UUID fileObjectId, UUID channelId,
            String upstreamFileId, String status, UUID claimToken) { }
}
