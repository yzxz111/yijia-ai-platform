package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

class FirstChunkGateTest {

    @Test
    void replaysAllSynchronousChunksWhenTheClientRequestsUnboundedDemand() {
        Flux<String> asynchronousSource = Flux.just("first")
                .publishOn(Schedulers.boundedElastic())
                .concatWith(Flux.just("second"))
                .concatWithValues("third");

        List<String> chunks = FirstChunkGate.await(asynchronousSource)
                .flatMapMany(stream -> stream)
                .collectList()
                .block(Duration.ofSeconds(1));

        assertThat(chunks).containsExactly("first", "second", "third");
    }

    @Test
    void propagatesAnErrorBeforeOpeningTheClientStream() {
        IllegalStateException failure = new IllegalStateException("upstream failed");

        assertThatThrownBy(() -> FirstChunkGate.await(Flux.error(failure))
                        .block(Duration.ofSeconds(1)))
                .isSameAs(failure);
    }

    @Test
    void rejectsAnEmptyProviderStreamBeforeOpeningTheClientStream() {
        assertThatThrownBy(() -> FirstChunkGate.await(Flux.empty())
                        .block(Duration.ofSeconds(1)))
                .isInstanceOf(ProviderAdapter.ProviderException.class)
                .hasMessageContaining("empty_provider_stream");
    }
}
