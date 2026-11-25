package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

/** Pushes a tiny invalidation signal when the cloud Skill catalog changes. */
@RestController
@RequestMapping("/v1/skill-catalog")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillCatalogEventsController {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(20);
    private static final String CHANGE_EVENT = "catalog-changed";

    private final SkillCatalogEventService events;

    public SkillCatalogEventsController(SkillCatalogEventService events) {
        this.events = events;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> events() {
        ServerSentEvent<String> connected = ServerSentEvent.<String>builder()
                .comment("connected")
                .build();
        Flux<ServerSentEvent<String>> changes = events.changes()
                .map(id -> ServerSentEvent.<String>builder()
                        .id(id)
                        .event(CHANGE_EVENT)
                        .data(id)
                        .build());
        Flux<ServerSentEvent<String>> heartbeats = Flux.interval(HEARTBEAT_INTERVAL)
                .map(ignored -> ServerSentEvent.<String>builder()
                        .comment("keepalive")
                        .build());
        // Flush one frame immediately so proxies and clients can distinguish a live SSE endpoint
        // from a successful response that closed before producing any stream activity.
        return Flux.concat(Flux.just(connected), Flux.merge(changes, heartbeats));
    }
}
