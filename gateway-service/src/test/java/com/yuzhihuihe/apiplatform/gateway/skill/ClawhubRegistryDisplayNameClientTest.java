package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class ClawhubRegistryDisplayNameClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void resolvesOwnerAndSlugFromStrictDetailResponse() throws Exception {
        JsonNode response = MAPPER.readTree("""
                {"skill":{"slug":"shared","displayName":"Wanted Name"},
                 "owner":{"handle":"wanted-owner"}}
                """);

        assertThat(ClawhubRegistryDisplayNameClient.selectDetailDisplayName(
                response, "WANTED-OWNER", "shared")).isEqualTo("Wanted Name");
        assertThat(ClawhubRegistryDisplayNameClient.selectDetailDisplayName(
                response, "other-owner", "shared")).isNull();
        assertThat(ClawhubRegistryDisplayNameClient.selectDetailDisplayName(
                response, "wanted-owner", "other-slug")).isNull();
    }

    @Test
    void usesSlugOnlyWhenThereIsOneExactGlobalRegistryMatch() throws Exception {
        JsonNode response = MAPPER.readTree("""
                {"results":[
                  {"owner":{"handle":"one-owner"},"skill":{"slug":"unique","displayName":"Unique Name"}}
                ],"total":1}
                """);

        assertThat(ClawhubRegistryDisplayNameClient.selectDisplayName(response, "unique"))
                .isEqualTo("Unique Name");
    }

    @Test
    void refusesSlugOnlyNameWhenMultiplePublishersShareSlug() throws Exception {
        JsonNode response = MAPPER.readTree("""
                {"results":[
                  {"ownerHandle":"one-owner","slug":"shared","displayName":"One"},
                  {"ownerHandle":"two-owner","slug":"shared","displayName":"Two"}
                ],"total":2}
                """);

        assertThat(ClawhubRegistryDisplayNameClient.selectDisplayName(response, "shared"))
                .isNull();
    }

    @Test
    void refusesSlugOnlyNameWhenExactSearchResultsAreTruncated() throws Exception {
        JsonNode response = MAPPER.readTree("""
                {"results":[
                  {"ownerHandle":"one-owner","slug":"maybe-unique","displayName":"Maybe"}
                ],"total":2}
                """);

        assertThat(ClawhubRegistryDisplayNameClient.selectDisplayName(response, "maybe-unique"))
                .isNull();
    }

    @Test
    void refusesSlugOnlyNameWhenRegistryDoesNotReportSearchCompleteness() throws Exception {
        JsonNode response = MAPPER.readTree("""
                {"results":[
                  {"ownerHandle":"one-owner","slug":"unverified","displayName":"Unverified"}
                ]}
                """);

        assertThat(ClawhubRegistryDisplayNameClient.selectDisplayName(response, "unverified"))
                .isNull();
    }

    @Test
    void refusesDuplicateSlugMatchesInsteadOfChoosingArbitraryTitle() throws Exception {
        JsonNode response = MAPPER.readTree("""
                {"results":[
                  {"ownerHandle":"same-owner","slug":"duplicate","displayName":"One"},
                  {"ownerHandle":"same-owner","slug":"duplicate","displayName":"Two"}
                ],"total":2}
                """);

        assertThat(ClawhubRegistryDisplayNameClient.selectDisplayName(response, "duplicate"))
                .isNull();
    }

    @Test
    void sendsUntrustedSlugAsAnEncodedQueryParameterToFixedRegistryHost() {
        String slug = "a +/?#& value";
        AtomicReference<URI> requestedUri = new AtomicReference<>();
        String responseBody = """
                {"results":[{"slug":"a +/?#& value","displayName":"Encoded title"}],"total":1}
                """;
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requestedUri.set(request.url());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(responseBody)
                    .build());
        });

        String name = new ClawhubRegistryDisplayNameClient(builder)
                .displayName(null, slug)
                .block();

        assertThat(name).isEqualTo("Encoded title");
        assertThat(requestedUri.get().getHost()).isEqualTo("clawhub.ai");
        assertThat(requestedUri.get().getRawQuery()).contains("q=a%20%2B%2F%3F%23%26%20value");
    }

    @Test
    void looksUpOwnerQualifiedSkillAndVerifiesIdentityBeforeUsingTitle() {
        String slug = "self-improving";
        String owner = "ivangdavila";
        AtomicReference<URI> requestedUri = new AtomicReference<>();
        String responseBody = """
                {"skill":{"slug":"self-improving","displayName":"Self-Improving + Proactive Agent"},
                 "owner":{"handle":"ivangdavila"}}
                """;
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requestedUri.set(request.url());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(responseBody)
                    .build());
        });

        String name = new ClawhubRegistryDisplayNameClient(builder).displayName(owner, slug).block();

        assertThat(name).isEqualTo("Self-Improving + Proactive Agent");
        assertThat(requestedUri.get().getHost()).isEqualTo("clawhub.ai");
        assertThat(requestedUri.get().getPath()).isEqualTo("/api/v1/skills/self-improving");
        assertThat(requestedUri.get().getQuery()).isEqualTo("ownerHandle=ivangdavila");
    }
}
