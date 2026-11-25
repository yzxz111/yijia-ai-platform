package com.yuzhihuihe.apiplatform.gateway.economics;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class UsageEconomicsOutboxHealthService {

    private final UsageEconomicsOutboxHealthRepository repository;

    public UsageEconomicsOutboxHealthService(UsageEconomicsOutboxHealthRepository repository) {
        this.repository = repository;
    }

    Mono<Status> status() {
        return repository
                .status()
                .map(
                        row ->
                                new Status(
                                        row.pending(),
                                        row.sending(),
                                        row.failed(),
                                        row.dead(),
                                        row.oldestPending()));
    }

    Flux<SourceEvent> events(
            Instant from, Instant to, Instant cursorCreatedAt, UUID cursorEventId, int fetchLimit) {
        return repository
                .events(from, to, cursorCreatedAt, cursorEventId, fetchLimit)
                .map(row -> new SourceEvent(row.eventId(), row.payloadHash(), row.createdAt()));
    }

    record Status(long pending, long sending, long failed, long dead, Instant oldestPending) {}

    record SourceEvent(UUID eventId, String payloadHash, Instant createdAt) {}
}
