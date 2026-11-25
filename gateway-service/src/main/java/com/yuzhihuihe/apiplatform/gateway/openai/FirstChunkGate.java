package com.yuzhihuihe.apiplatform.gateway.openai;

import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class FirstChunkGate {

    private FirstChunkGate() {
    }

    static Mono<Flux<String>> await(Flux<String> source) {
        return source.switchOnFirst((signal, replay) -> {
            if (signal.hasValue()) {
                return Mono.just(replay);
            }
            if (signal.isOnError()) {
                return Mono.error(signal.getThrowable());
            }
            return Mono.error(new ProviderAdapter.ProviderException(
                    502, "empty_provider_stream"));
        }, false).single();
    }
}
