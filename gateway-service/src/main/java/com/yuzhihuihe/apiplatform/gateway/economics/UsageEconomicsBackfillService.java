package com.yuzhihuihe.apiplatform.gateway.economics;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class UsageEconomicsBackfillService {

    private final UsageEconomicsBackfillRepository repository;
    private final UsageEconomicsOutboxService snapshots;

    public UsageEconomicsBackfillService(
            UsageEconomicsBackfillRepository repository, UsageEconomicsOutboxService snapshots) {
        this.repository = repository;
        this.snapshots = snapshots;
    }

    Flux<Snapshot> snapshots(Instant cursorCreatedAt, UUID cursorUsageId, int fetchLimit) {
        return repository
                .page(cursorCreatedAt, cursorUsageId, fetchLimit)
                .concatMap(
                        row ->
                                snapshots
                                        .snapshotUsage(row.id())
                                        .map(event -> new Snapshot(event, row.createdAt())));
    }

    record Snapshot(UsageEconomicsEvent event, Instant createdAt) {}
}
