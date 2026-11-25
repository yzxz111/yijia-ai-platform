package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Best-effort lookup of publisher-authored names from the fixed ClawHub registry. */
@Service
class ClawhubRegistryDisplayNameClient {

    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(2);
    // The registry detail response may include the source document; keep a bounded margin for it.
    private static final int RESPONSE_LIMIT = 4 * 1024 * 1024;
    private static final int SEARCH_LIMIT = 100;

    private final WebClient catalog;

    ClawhubRegistryDisplayNameClient(WebClient.Builder webClientBuilder) {
        this.catalog = webClientBuilder.clone()
                .baseUrl("https://clawhub.ai")
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(RESPONSE_LIMIT))
                .build();
    }

    Mono<String> displayName(String owner, String slug) {
        if (slug == null || slug.isBlank()) return Mono.empty();
        if (owner != null && !owner.isBlank()) {
            return catalog.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/skills/{slug}")
                            .queryParam("ownerHandle", "{owner}")
                            .build(slug, owner))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(LOOKUP_TIMEOUT)
                    .flatMap(response -> Mono.justOrEmpty(
                            selectDetailDisplayName(response, owner, slug)))
                    .onErrorResume(error -> Mono.empty());
        }
        return catalog.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/search")
                        .queryParam("q", "{slug}")
                        .queryParam("mode", "exact")
                        .queryParam("limit", SEARCH_LIMIT)
                        .build(slug))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(LOOKUP_TIMEOUT)
                .flatMap(response -> Mono.justOrEmpty(selectDisplayName(response, slug)))
                .onErrorResume(error -> Mono.empty());
    }

    /**
     * Resolves a name only when the registry response identifies one exact package. If the ZIP has
     * no owner identity, an exact slug search must return a globally unique, complete result set.
     */
    static String selectDisplayName(JsonNode response, String slug) {
        if (response == null || slug == null || !response.path("results").isArray()) return null;

        JsonNode results = response.path("results");
        JsonNode total = response.path("total");
        if (results.size() >= SEARCH_LIMIT
                || !total.isIntegralNumber()
                || total.longValue() != results.size()) return null;

        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode result : results) {
            String resultSlug = firstText(result.path("slug"), result.path("skill").path("slug"));
            if (!slug.equalsIgnoreCase(resultSlug)) continue;
            matches.add(result);
        }
        if (matches.size() != 1) return null;

        JsonNode match = matches.getFirst();
        String name = firstText(match.path("displayName"), match.path("skill").path("displayName"));
        return ClawhubSkillAdapter.isValidDisplayName(name) ? name.strip() : null;
    }

    static String selectDetailDisplayName(JsonNode response, String owner, String slug) {
        if (response == null || owner == null || slug == null) return null;
        JsonNode skill = response.path("skill");
        String resultSlug = firstText(skill.path("slug"), response.path("slug"));
        String resultOwner = firstText(
                response.path("owner").path("handle"), response.path("ownerHandle"));
        if (!slug.equalsIgnoreCase(resultSlug) || !owner.equalsIgnoreCase(resultOwner)) return null;
        String name = firstText(skill.path("displayName"), response.path("displayName"));
        return ClawhubSkillAdapter.isValidDisplayName(name) ? name.strip() : null;
    }

    private static String firstText(JsonNode... values) {
        for (JsonNode value : values) {
            if (value != null && value.isTextual() && !value.textValue().isBlank()) {
                return value.textValue().strip();
            }
        }
        return null;
    }
}
