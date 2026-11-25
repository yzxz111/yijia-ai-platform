package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OperatingBackfillJobTest {

    private static final Instant CREATED = Instant.parse("2026-08-08T08:00:00Z");

    @Test
    void cursorUsesPostgresUnsignedUuidOrdering() {
        var low = new OperatingBackfillJob.Cursor(CREATED,
                UUID.fromString("00000000-0000-0000-0000-000000000000"));
        var high = new OperatingBackfillJob.Cursor(CREATED,
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));

        assertThat(low.compareTo(high)).isNegative();
        assertThat(high.compareTo(low)).isPositive();
    }

    @Test
    void walletBackfillSqlUsesPersistedBillingMode() {
        assertThat(OperatingBackfillJob.WALLET_SQL)
                .contains("'billingMode', usage.billing_mode")
                .doesNotContain("'billingMode', 'WALLET_ONLY'");
    }

    @Test
    void walletBackfillPreservesHybridBillingMode() {
        OperatingFactServiceTest.MemoryStore store = new OperatingFactServiceTest.MemoryStore();
        OperatingBackfillJob.FactSink sink = new OperatingBackfillJob.FactSink(
                new ObjectMapper(), new OperatingFactService(
                        java.time.Clock.systemUTC(), store), null);

        sink.accept(OperatingBackfillJob.SourceType.WALLET_USAGE,
                walletItem("HYBRID", "00000000-0000-0000-0000-000000000011"));

        assertThat(store.facts).singleElement().satisfies(fact -> {
            assertThat(fact.billingMode()).isEqualTo("HYBRID");
            assertThat(fact.missingSource()).isFalse();
        });
    }

    @Test
    void walletBackfillMarksLegacyUnknownAsMissingSource() {
        OperatingFactServiceTest.MemoryStore store = new OperatingFactServiceTest.MemoryStore();
        OperatingBackfillJob.FactSink sink = new OperatingBackfillJob.FactSink(
                new ObjectMapper(), new OperatingFactService(
                        java.time.Clock.systemUTC(), store), null);

        sink.accept(OperatingBackfillJob.SourceType.WALLET_USAGE,
                walletItem("UNKNOWN", "00000000-0000-0000-0000-000000000012"));

        assertThat(store.facts).singleElement().satisfies(fact -> {
            assertThat(fact.missingSource()).isTrue();
            assertThat(fact.metadata()).containsEntry("legacyBillingMode", "UNKNOWN")
                    .containsEntry("missingSource", true);
        });
    }

    @Test
    void advancesIndependentCheckpointsInBatchesNoLargerThanFiveHundred() {
        MemoryCheckpoints checkpoints = new MemoryCheckpoints();
        MemorySink sink = new MemorySink();
        RecordingSource orders = new RecordingSource(501, false);
        Map<OperatingBackfillJob.SourceType, OperatingBackfillJob.Source> sources =
                new EnumMap<>(OperatingBackfillJob.SourceType.class);
        sources.put(OperatingBackfillJob.SourceType.PACKAGE_ORDER, orders);
        OperatingBackfillJob job = new OperatingBackfillJob(500, checkpoints, sources, sink);

        OperatingBackfillJob.BatchResult first = job.runBatch(
                OperatingBackfillJob.SourceType.PACKAGE_ORDER);
        OperatingBackfillJob.BatchResult second = job.runBatch(
                OperatingBackfillJob.SourceType.PACKAGE_ORDER);

        assertThat(first.processed()).isEqualTo(500);
        assertThat(first.completed()).isFalse();
        assertThat(second.processed()).isEqualTo(1);
        assertThat(second.completed()).isTrue();
        assertThat(orders.requestedLimits).containsOnly(500);
        assertThat(checkpoints.load(OperatingBackfillJob.SourceType.PACKAGE_ORDER).cursor().id())
                .isEqualTo(orders.items.getLast().cursor().id());
        assertThat(sink.keys).hasSize(501);
    }

    @Test
    void failedBatchDoesNotAdvanceAndResumeRemainsIdempotent() {
        MemoryCheckpoints checkpoints = new MemoryCheckpoints();
        MemorySink sink = new MemorySink();
        RecordingSource source = new RecordingSource(2, false);
        sink.failOnceOn = source.items.getLast().key();
        Map<OperatingBackfillJob.SourceType, OperatingBackfillJob.Source> sources =
                Map.of(OperatingBackfillJob.SourceType.WALLET_USAGE, source);
        OperatingBackfillJob job = new OperatingBackfillJob(500, checkpoints, sources, sink);

        assertThatThrownBy(() -> job.runBatch(OperatingBackfillJob.SourceType.WALLET_USAGE))
                .hasMessage("fixture_failure");
        assertThat(checkpoints.load(OperatingBackfillJob.SourceType.WALLET_USAGE).cursor()).isNull();
        assertThat(checkpoints.load(OperatingBackfillJob.SourceType.WALLET_USAGE).status())
                .isEqualTo(OperatingBackfillJob.Status.FAILED);

        job.resume(OperatingBackfillJob.SourceType.WALLET_USAGE);
        OperatingBackfillJob.BatchResult retried = job.runBatch(
                OperatingBackfillJob.SourceType.WALLET_USAGE);
        assertThat(retried.completed()).isTrue();
        assertThat(sink.keys).containsExactlyInAnyOrderElementsOf(
                source.items.stream().map(OperatingBackfillJob.Item::key).toList());

        job.pause(OperatingBackfillJob.SourceType.WALLET_USAGE);
        assertThat(job.runBatch(OperatingBackfillJob.SourceType.WALLET_USAGE).paused()).isTrue();
    }

    @Test
    void concurrentReplicaCannotClaimAnAlreadyRunningSource() {
        MemoryCheckpoints checkpoints = new MemoryCheckpoints();
        MemorySink sink = new MemorySink();
        final OperatingBackfillJob[] competitor = new OperatingBackfillJob[1];
        final OperatingBackfillJob.BatchResult[] competingResult = new OperatingBackfillJob.BatchResult[1];
        RecordingSource source = new RecordingSource(1, false);
        OperatingBackfillJob.Source slowSource = (after, limit) -> {
            competingResult[0] = competitor[0].runBatch(
                    OperatingBackfillJob.SourceType.PACKAGE_ORDER);
            return source.fetch(after, limit);
        };
        Map<OperatingBackfillJob.SourceType, OperatingBackfillJob.Source> sources =
                Map.of(OperatingBackfillJob.SourceType.PACKAGE_ORDER, slowSource);
        OperatingBackfillJob first = new OperatingBackfillJob(500, checkpoints, sources, sink);
        competitor[0] = new OperatingBackfillJob(500, checkpoints, sources, sink);

        OperatingBackfillJob.BatchResult result = first.runBatch(
                OperatingBackfillJob.SourceType.PACKAGE_ORDER);

        assertThat(result.processed()).isEqualTo(1);
        assertThat(competingResult[0].processed()).isZero();
        assertThat(sink.keys).containsExactly("item-0");
    }

    @Test
    void pauseInvalidatesSlowWorkerClaimWithoutWatermarkRegression() {
        MemoryCheckpoints checkpoints = new MemoryCheckpoints();
        RecordingSource source = new RecordingSource(1, false);
        final OperatingBackfillJob[] jobRef = new OperatingBackfillJob[1];
        OperatingBackfillJob.Sink pausingSink = (type, item) -> jobRef[0].pause(type);
        OperatingBackfillJob job = new OperatingBackfillJob(500, checkpoints,
                Map.of(OperatingBackfillJob.SourceType.PACKAGE_ORDER, source), pausingSink);
        jobRef[0] = job;

        assertThatThrownBy(() -> job.runBatch(OperatingBackfillJob.SourceType.PACKAGE_ORDER))
                .hasMessage("backfill_checkpoint_claim_lost");

        OperatingBackfillJob.Checkpoint checkpoint = checkpoints.load(
                OperatingBackfillJob.SourceType.PACKAGE_ORDER);
        assertThat(checkpoint.status()).isEqualTo(OperatingBackfillJob.Status.PAUSED);
        assertThat(checkpoint.cursor()).isNull();
    }

    private static final class RecordingSource implements OperatingBackfillJob.Source {
        private final List<OperatingBackfillJob.Item> items = new ArrayList<>();
        private final List<Integer> requestedLimits = new ArrayList<>();
        private RecordingSource(int size, boolean ignored) {
            for (int i = 0; i < size; i++) {
                UUID id = new UUID(0, i + 1L);
                items.add(new OperatingBackfillJob.Item("item-" + i,
                        new OperatingBackfillJob.Cursor(CREATED.plusSeconds(i), id), new byte[0]));
            }
        }
        @Override public List<OperatingBackfillJob.Item> fetch(
                OperatingBackfillJob.Cursor after, int limit) {
            requestedLimits.add(limit);
            int start = after == null ? 0 : items.indexOf(items.stream()
                    .filter(item -> item.cursor().equals(after)).findFirst().orElseThrow()) + 1;
            return items.subList(start, Math.min(items.size(), start + limit));
        }
    }

    private OperatingBackfillJob.Item walletItem(String billingMode, String id) {
        String payload = """
                {"id":"%s","tenantId":"00000000-0000-0000-0000-000000000001",
                 "userId":"00000000-0000-0000-0000-000000000002","requestId":"req-wallet",
                 "modelCode":"text-basic","priceVersion":"pv-1","amount":25,
                 "inputTokens":7,"outputTokens":3,"billingMode":"%s",
                 "occurredAt":"2026-08-08T08:00:00Z"}
                """.formatted(id, billingMode);
        UUID uuid = UUID.fromString(id);
        return new OperatingBackfillJob.Item(id,
                new OperatingBackfillJob.Cursor(CREATED, uuid),
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class MemorySink implements OperatingBackfillJob.Sink {
        private final java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        private String failOnceOn;
        @Override public void accept(OperatingBackfillJob.SourceType type, OperatingBackfillJob.Item item) {
            if (item.key().equals(failOnceOn)) {
                failOnceOn = null;
                throw new IllegalStateException("fixture_failure");
            }
            keys.add(item.key());
        }
    }

    private static final class MemoryCheckpoints implements OperatingBackfillJob.Checkpoints {
        private final Map<OperatingBackfillJob.SourceType, OperatingBackfillJob.Checkpoint> values =
                new HashMap<>();
        private final Map<OperatingBackfillJob.SourceType, UUID> claims = new HashMap<>();
        @Override public OperatingBackfillJob.Checkpoint load(OperatingBackfillJob.SourceType type) {
            return values.getOrDefault(type,
                    new OperatingBackfillJob.Checkpoint(null, OperatingBackfillJob.Status.PENDING, null));
        }
        @Override public OperatingBackfillJob.Claim claim(OperatingBackfillJob.SourceType type) {
            OperatingBackfillJob.Checkpoint old = load(type);
            if (claims.containsKey(type) || old.status() == OperatingBackfillJob.Status.PAUSED
                    || old.status() == OperatingBackfillJob.Status.COMPLETED) return null;
            UUID token = UUID.randomUUID();
            claims.put(type, token);
            values.put(type, new OperatingBackfillJob.Checkpoint(old.cursor(),
                    OperatingBackfillJob.Status.RUNNING, null));
            return new OperatingBackfillJob.Claim(token, old);
        }
        @Override public boolean advanced(OperatingBackfillJob.SourceType type, UUID token,
                OperatingBackfillJob.Cursor expected, OperatingBackfillJob.Cursor cursor,
                boolean completed) {
            if (!java.util.Objects.equals(claims.get(type), token)
                    || !java.util.Objects.equals(load(type).cursor(), expected)
                    || load(type).status() != OperatingBackfillJob.Status.RUNNING) return false;
            claims.remove(type);
            values.put(type, new OperatingBackfillJob.Checkpoint(cursor,
                    completed ? OperatingBackfillJob.Status.COMPLETED
                            : OperatingBackfillJob.Status.RUNNING, null));
            return true;
        }
        @Override public boolean failed(OperatingBackfillJob.SourceType type, UUID token,
                OperatingBackfillJob.Cursor expected, String error) {
            OperatingBackfillJob.Checkpoint old = load(type);
            if (!java.util.Objects.equals(claims.get(type), token)
                    || !java.util.Objects.equals(old.cursor(), expected)
                    || old.status() != OperatingBackfillJob.Status.RUNNING) return false;
            claims.remove(type);
            values.put(type, new OperatingBackfillJob.Checkpoint(old.cursor(),
                    OperatingBackfillJob.Status.FAILED, error));
            return true;
        }
        @Override public void status(OperatingBackfillJob.SourceType type,
                OperatingBackfillJob.Status status) {
            OperatingBackfillJob.Checkpoint old = load(type);
            claims.remove(type);
            values.put(type, new OperatingBackfillJob.Checkpoint(old.cursor(), status, null));
        }
    }
}
