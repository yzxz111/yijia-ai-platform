package com.yuzhihuihe.apiplatform.gateway.routing;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ChannelCircuitRegistry {

    private final CircuitBreakerRegistry registry;

    public ChannelCircuitRegistry(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    public String key(UUID channelId, String modelCode) {
        return channelId + ":" + modelCode;
    }

    public CircuitBreaker circuitBreaker(UUID channelId, String modelCode) {
        return registry.circuitBreaker(key(channelId, modelCode));
    }

    public boolean isAvailable(UUID channelId, String modelCode) {
        CircuitBreaker.State state = circuitBreaker(channelId, modelCode).getState();
        return state != CircuitBreaker.State.OPEN && state != CircuitBreaker.State.FORCED_OPEN;
    }

    public List<CircuitHealth> health() {
        return registry.getAllCircuitBreakers().stream()
                .map(circuit -> new CircuitHealth(
                        circuit.getName(), circuit.getState().name(),
                        circuit.getMetrics().getFailureRate(),
                        circuit.getMetrics().getNumberOfBufferedCalls()))
                .sorted(Comparator.comparing(CircuitHealth::key))
                .toList();
    }

    public CircuitHealth reset(String key) {
        CircuitBreaker circuit = registry.getAllCircuitBreakers().stream()
                .filter(candidate -> candidate.getName().equals(key))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "circuit_not_found"));
        circuit.reset();
        return new CircuitHealth(
                circuit.getName(), circuit.getState().name(),
                circuit.getMetrics().getFailureRate(),
                circuit.getMetrics().getNumberOfBufferedCalls());
    }

    public record CircuitHealth(String key, String state, float failureRate, int bufferedCalls) {
        public UUID channelId() {
            int separator = key.indexOf(':');
            if (separator < 1) {
                return null;
            }
            try {
                return UUID.fromString(key.substring(0, separator));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        public String modelCode() {
            int separator = key.indexOf(':');
            return separator < 0 || separator == key.length() - 1
                    ? null
                    : key.substring(separator + 1);
        }
    }
}
