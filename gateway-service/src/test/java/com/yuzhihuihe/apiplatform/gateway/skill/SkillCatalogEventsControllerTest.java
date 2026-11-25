package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import reactor.core.publisher.Mono;

class SkillCatalogEventsControllerTest {

    @Test
    void emitsAConnectedCommentImmediately() {
        SkillCatalogEventsController controller = new SkillCatalogEventsController(
                new SkillCatalogEventService());

        ServerSentEvent<String> event = controller.events().next().block();

        assertThat(event).isNotNull();
        assertThat(event.comment()).isEqualTo("connected");
    }

    @Test
    void emitsACompactInvalidationAfterASuccessfulCatalogMutation() throws Exception {
        SkillCatalogEventService events = new SkillCatalogEventService();
        SkillCatalogEventsController controller = new SkillCatalogEventsController(events);
        CompletableFuture<ServerSentEvent<String>> nextChange = controller.events()
                .filter(event -> "catalog-changed".equals(event.event()))
                .next()
                .toFuture();

        events.publishAfter(Mono.just("updated")).block();

        ServerSentEvent<String> event = nextChange.get(1, TimeUnit.SECONDS);
        assertThat(event.id()).isNotBlank();
        assertThat(event.data()).isEqualTo(event.id());
    }
}
