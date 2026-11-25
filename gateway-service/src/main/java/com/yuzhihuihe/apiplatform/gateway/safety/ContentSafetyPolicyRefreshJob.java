package com.yuzhihuihe.apiplatform.gateway.safety;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
final class ContentSafetyPolicyRefreshJob {

    private final PolicySnapshotClient client;
    private final ContentSafetyMetrics metrics;

    ContentSafetyPolicyRefreshJob(PolicySnapshotClient client, ContentSafetyMetrics metrics) {
        this.client = client;
        this.metrics = metrics;
    }

    @Scheduled(
            initialDelayString = "${content-safety.policy-refresh-initial-delay:0s}",
            fixedDelayString = "${content-safety.policy-refresh-delay:15s}")
    Mono<Void> refresh() {
        return client.refresh()
                .doOnNext(accepted -> metrics.policySync(accepted ? "accepted" : "unchanged"))
                .doOnError(error -> metrics.policySync("failed"))
                .onErrorResume(error -> Mono.empty())
                .then();
    }
}
