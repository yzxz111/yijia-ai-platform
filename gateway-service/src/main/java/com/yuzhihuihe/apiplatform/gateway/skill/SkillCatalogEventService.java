package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.UUID;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Lightweight, process-local fan-out for client-visible catalog mutations.
 *
 * <p>The gateway currently runs as one service instance. Keeping the signal in memory means an
 * administrator write performs no extra database query and connected desktops can refresh only
 * when something actually changed. The event carries no catalog data; clients continue to read the
 * existing authenticated catalog endpoints after receiving it.
 */
@Service
public class SkillCatalogEventService {

    private final Sinks.Many<String> changes = Sinks.many().multicast().directBestEffort();

    Flux<String> changes() {
        return changes.asFlux();
    }

    <T> Mono<T> publishAfter(Mono<T> operation) {
        return operation.doOnSuccess(ignored -> publishChange());
    }

    private void publishChange() {
        changes.tryEmitNext(UUID.randomUUID().toString());
    }
}
