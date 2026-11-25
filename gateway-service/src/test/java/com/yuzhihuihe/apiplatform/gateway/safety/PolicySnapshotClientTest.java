package com.yuzhihuihe.apiplatform.gateway.safety;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

class PolicySnapshotClientTest {

    @Test
    void acceptsOnlySignedMonotonicSnapshots() {
        PolicySnapshotClient client = new PolicySnapshotClient("test-secret");
        PolicySnapshot first = PolicySnapshot.signed(
                1L, List.of(new PolicySnapshot.Rule("prompt_injection", "PROMPT_INJECTION", "HIGH", "BLOCK")),
                Instant.parse("2026-08-06T00:00:00Z"), "test-secret");
        PolicySnapshot older = PolicySnapshot.signed(
                1L, first.rules(), first.publishedAt(), "test-secret");
        PolicySnapshot tampered = new PolicySnapshot(
                2L, first.configSha256(), first.rules(), first.publishedAt(), "bad-signature");

        assertThat(client.accept(first)).isTrue();
        assertThat(client.accept(older)).isFalse();
        assertThat(client.accept(tampered)).isFalse();
        assertThat(client.current()).contains(first);
    }

    @Test
    void unchangedRefreshRenewsFreshnessWithoutAcceptingASecondVersion() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T00:00:00Z"));
        PolicySnapshotClient client = new PolicySnapshotClient("test-secret", clock);
        PolicySnapshot snapshot = PolicySnapshot.signed(
                1L, List.of(), clock.instant(), "test-secret");

        assertThat(client.accept(snapshot)).isTrue();
        clock.advance(Duration.ofSeconds(9));
        assertThat(client.accept(snapshot)).isFalse();
        assertThat(client.current(Duration.ofSeconds(10))).contains(snapshot);
        clock.advance(Duration.ofSeconds(10));
        assertThat(client.current(Duration.ofSeconds(10))).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
