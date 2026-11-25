package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

class ChannelCircuitRegistryTest {

    @Test
    void isolatesCircuitStateByChannelIdAndModelCode() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        ChannelCircuitRegistry circuits = new ChannelCircuitRegistry(registry);
        UUID channelId = UUID.randomUUID();

        registry.circuitBreaker(circuits.key(channelId, "text-basic"))
                .transitionToForcedOpenState();

        assertThat(circuits.isAvailable(channelId, "text-basic")).isFalse();
        assertThat(circuits.isAvailable(channelId, "text-pro")).isTrue();
        assertThat(circuits.health()).anySatisfy(health -> {
            assertThat(health.key()).isEqualTo(channelId + ":text-basic");
            assertThat(health.state()).isEqualTo(CircuitBreaker.State.FORCED_OPEN.name());
        });
    }
}
