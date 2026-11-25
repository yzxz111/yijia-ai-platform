package com.yuzhihuihe.apiplatform.gateway.multimodal;

import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class CompositeModelPolicyService {

    private static final Set<String> POLICY_KEYS = Set.of(
            "textModel", "imageModel", "fileModel", "mixedFinalModel", "extractorVersion");

    private final PolicyStore store;
    private final ObjectMapper objectMapper;

    @Autowired
    CompositeModelPolicyService(
            CompositeModelPolicyRepository repository, ObjectMapper objectMapper) {
        this((PolicyStore) repository, objectMapper);
    }

    CompositeModelPolicyService(PolicyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public Mono<ModelRouting> resolve(String publicModel) {
        if (publicModel == null || publicModel.isBlank()) {
            return Mono.error(routeUnavailable());
        }
        String model = publicModel.strip();
        return store.load(model)
                .flatMap(row -> resolve(model, row))
                .switchIfEmpty(Mono.just(ModelRouting.direct()));
    }

    private Mono<ModelRouting> resolve(String publicModel, PolicyRow row) {
        if (RoutingStrategy.DIRECT.name().equals(row.routingStrategy())) {
            return Mono.just(ModelRouting.direct());
        }
        if (!RoutingStrategy.COMPOSITE.name().equals(row.routingStrategy())) {
            return Mono.error(routeUnavailable());
        }
        CompositePolicy policy;
        try {
            policy = parse(publicModel, row.routingPolicy());
        } catch (Exception invalidPolicy) {
            return Mono.error(routeUnavailable());
        }
        Set<String> componentAliases = new LinkedHashSet<>();
        componentAliases.add(policy.textModel());
        componentAliases.add(policy.imageModel());
        componentAliases.add(policy.fileModel());
        componentAliases.add(policy.mixedFinalModel());
        Set<String> requiredAliases = Set.copyOf(componentAliases);
        return store.enabledAliases(requiredAliases)
                .flatMap(enabled -> enabled.containsAll(requiredAliases)
                        ? Mono.just(ModelRouting.composite(policy))
                        : Mono.error(routeUnavailable()));
    }

    private CompositePolicy parse(String publicModel, String routingPolicy) throws Exception {
        JsonNode root = objectMapper.readTree(routingPolicy);
        if (!root.isObject()) {
            throw new IllegalArgumentException("invalid_composite_model_policy");
        }
        Set<String> fields = new LinkedHashSet<>();
        root.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(POLICY_KEYS)) {
            throw new IllegalArgumentException("invalid_composite_model_policy");
        }
        for (String field : POLICY_KEYS) {
            if (!root.path(field).isTextual() || root.path(field).asText().isBlank()) {
                throw new IllegalArgumentException("invalid_composite_model_policy");
            }
        }
        return new CompositePolicy(
                publicModel,
                root.path("textModel").asText(),
                root.path("imageModel").asText(),
                root.path("fileModel").asText(),
                root.path("mixedFinalModel").asText(),
                root.path("extractorVersion").asText());
    }

    private static ResponseStatusException routeUnavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "multimodal_route_unavailable");
    }

    interface PolicyStore {
        Mono<PolicyRow> load(String publicModel);

        Mono<Set<String>> enabledAliases(Set<String> aliases);
    }

    record PolicyRow(String routingStrategy, String routingPolicy) {
    }

    public enum RoutingStrategy {
        DIRECT,
        COMPOSITE
    }

    public record ModelRouting(
            RoutingStrategy strategy,
            CompositePolicy compositePolicy) {

        public static ModelRouting direct() {
            return new ModelRouting(RoutingStrategy.DIRECT, null);
        }

        public static ModelRouting composite(CompositePolicy policy) {
            return new ModelRouting(RoutingStrategy.COMPOSITE, policy);
        }
    }

    public record CompositePolicy(
            String publicModel,
            String textModel,
            String imageModel,
            String fileModel,
            String mixedFinalModel,
            String extractorVersion) {

        public CompositePolicy {
            publicModel = required(publicModel);
            textModel = required(textModel);
            imageModel = required(imageModel);
            fileModel = required(fileModel);
            mixedFinalModel = required(mixedFinalModel);
            extractorVersion = required(extractorVersion);
        }

        private static String required(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("invalid_composite_model_policy");
            }
            return value.strip();
        }
    }

}
