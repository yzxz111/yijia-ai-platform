package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ProviderPriceMonitorJob {
    private static final Logger LOG = LoggerFactory.getLogger(ProviderPriceMonitorJob.class);
    private static final BigDecimal CHANGE_THRESHOLD = new BigDecimal("0.05");
    private final PriceSource source;
    private final Store store;
    private final AlertSink alerts;
    private final Supplier<Instant> now;

    @Autowired
    public ProviderPriceMonitorJob(ProviderPriceSnapshotClient client, JdbcTemplate jdbc,
            OperatingAlertService alertService, PlatformTransactionManager transactionManager) {
        this(client::fetch, new JdbcStore(jdbc, transactionManager),
                alertService::providerPriceChanged, Instant::now);
    }

    ProviderPriceMonitorJob(PriceSource source, Store store, AlertSink alerts, Supplier<Instant> now) {
        this.source = source; this.store = store; this.alerts = alerts; this.now = now;
    }

    @Scheduled(fixedDelayString = "${reporting.provider-price-monitor-delay-ms:3600000}")
    public void synchronize() {
        Instant checkedAt = now.get();
        try {
            List<ProviderPriceSnapshotClient.PriceSnapshot> batch = List.copyOf(source.fetch());
            validate(batch);
            store.applyBatch(batch, checkedAt, alerts);
            store.health(true, null, checkedAt);
        } catch (RuntimeException failure) {
            store.health(false, failure.getMessage(), checkedAt);
            LOG.warn("provider price synchronization failed; retaining last snapshot", failure);
        }
    }

    private void validate(List<ProviderPriceSnapshotClient.PriceSnapshot> batch) {
        Set<java.util.UUID> ids = new HashSet<>();
        for (ProviderPriceSnapshotClient.PriceSnapshot value : batch) {
            if (value == null || value.id() == null || !ids.add(value.id())
                    || blank(value.provider()) || blank(value.logicalModel())
                    || blank(value.upstreamModel()) || value.inputPrice() == null
                    || value.outputPrice() == null || value.inputPrice().signum() < 0
                    || value.outputPrice().signum() < 0
                    || negative(value.cacheReadPrice()) || negative(value.cacheWritePrice())
                    || negative(value.reasoningPrice()) || value.exchangeRate() == null
                    || value.exchangeRate().signum() <= 0 || value.currency() == null
                    || value.currency().length() != 3 || blank(value.priceSource())
                    || value.effectiveFrom() == null
                    || (value.effectiveTo() != null
                        && !value.effectiveTo().isAfter(value.effectiveFrom()))) {
                throw new IllegalArgumentException("invalid_provider_price_snapshot_batch");
            }
        }
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private boolean negative(BigDecimal value) { return value != null && value.signum() < 0; }

    @FunctionalInterface interface PriceSource { List<ProviderPriceSnapshotClient.PriceSnapshot> fetch(); }
    interface Store {
        void applyBatch(List<ProviderPriceSnapshotClient.PriceSnapshot> values,
                Instant capturedAt, AlertSink alerts);
        void health(boolean healthy, String detail, Instant checkedAt);
    }
    @FunctionalInterface interface AlertSink {
        void providerPriceChanged(ProviderPriceSnapshotClient.PriceSnapshot before,
                ProviderPriceSnapshotClient.PriceSnapshot after, BigDecimal ratio, Instant observedAt);
    }

    static final class JdbcStore implements Store {
        private final JdbcTemplate jdbc;
        private final TransactionTemplate batchTransaction;
        private final TransactionTemplate healthTransaction;
        JdbcStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
            this.jdbc = jdbc;
            this.batchTransaction = new TransactionTemplate(transactionManager);
            this.healthTransaction = new TransactionTemplate(transactionManager);
            this.healthTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
        @Override public void applyBatch(List<ProviderPriceSnapshotClient.PriceSnapshot> values,
                Instant capturedAt, AlertSink alerts) {
            batchTransaction.executeWithoutResult(status -> {
                if (values.isEmpty()) return;
                Set<UUID> existingIds = existingIds(values);
                Map<PriceKey, ProviderPriceSnapshotClient.PriceSnapshot> latest = latest(values);
                List<ProviderPriceSnapshotClient.PriceSnapshot> inserts = new ArrayList<>();
                List<PriceChange> changes = new ArrayList<>();
                for (ProviderPriceSnapshotClient.PriceSnapshot value : values) {
                    if (existingIds.contains(value.id())) continue;
                    PriceKey key = new PriceKey(value.provider(), value.upstreamModel());
                    ProviderPriceSnapshotClient.PriceSnapshot previous = latest.put(key, value);
                    inserts.add(value);
                    BigDecimal change = maximumChange(previous, value);
                    changes.add(previous != null && change.compareTo(CHANGE_THRESHOLD) >= 0
                            ? new PriceChange(previous, value, change) : null);
                }
                if (inserts.isEmpty()) return;
                // 数据源通过关闭旧行并插入更晚的一行来取代某个价格，但镜像端
                // 从来看不到那次关闭：它在被取代的行仍是开放式的时候就把该行复制了过来，
                // 而 original_price_id 的幂等性使这份副本不会被改写。
                // 若不处理，传入区间会与镜像中的开放区间重叠，
                // ex_provider_price_no_overlap 会中止整批 —— 批次中的每一个快照，于是
                // PROVIDER_PRICE_CHANGED 就此静默。把镜像行关闭在传入的
                // effective_from 处，即可复现数据源自身的视图，而这必须在同一个
                // 事务中、在插入之前完成。
                closeSupersededOpenRanges(inserts);
                discardUnrepresentable(inserts, changes);
                int[] results = insertBatch(inserts, capturedAt);
                for (int index = 0; index < results.length; index++) {
                    PriceChange change = changes.get(index);
                    if ((results[index] > 0 || results[index] == java.sql.Statement.SUCCESS_NO_INFO)
                            && change != null) {
                        alerts.providerPriceChanged(change.before(), change.after(),
                                change.ratio(), capturedAt);
                    }
                }
            });
        }
        private Set<UUID> existingIds(List<ProviderPriceSnapshotClient.PriceSnapshot> values) {
            String placeholders = String.join(",", Collections.nCopies(values.size(), "?"));
            Object[] ids = values.stream().map(value -> value.id().toString()).toArray();
            return new HashSet<>(jdbc.queryForList("""
                    SELECT original_price_id FROM platform.provider_price_snapshot
                    WHERE original_price_id IN (%s)
                    """.formatted(placeholders), String.class, ids).stream().map(UUID::fromString).toList());
        }
        private Map<PriceKey, ProviderPriceSnapshotClient.PriceSnapshot> latest(
                List<ProviderPriceSnapshotClient.PriceSnapshot> values) {
            List<PriceKey> keys = values.stream()
                    .map(value -> new PriceKey(value.provider(), value.upstreamModel())).distinct().toList();
            String predicate = String.join(" OR ", Collections.nCopies(keys.size(),
                    "(provider=? AND upstream_model=?)"));
            List<Object> arguments = new ArrayList<>();
            keys.forEach(key -> { arguments.add(key.provider()); arguments.add(key.model()); });
            Map<PriceKey, ProviderPriceSnapshotClient.PriceSnapshot> result = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT DISTINCT ON (provider, upstream_model) original_price_id, provider,
                           logical_model, upstream_model, input_price, output_price, currency,
                           cache_read_price, cache_write_price, reasoning_price,
                           exchange_rate, price_source, effective_from, effective_to
                    FROM platform.provider_price_snapshot WHERE %s
                    ORDER BY provider, upstream_model, captured_at DESC, id DESC
                    """.formatted(predicate), rs -> {
                ProviderPriceSnapshotClient.PriceSnapshot value = new ProviderPriceSnapshotClient.PriceSnapshot(
                        UUID.fromString(rs.getString("original_price_id")), rs.getString("provider"),
                        rs.getString("logical_model"), rs.getString("upstream_model"),
                        rs.getBigDecimal("input_price"), rs.getBigDecimal("output_price"),
                        rs.getBigDecimal("cache_read_price"), rs.getBigDecimal("cache_write_price"),
                        rs.getBigDecimal("reasoning_price"),
                        rs.getString("currency"), rs.getBigDecimal("exchange_rate"),
                        rs.getString("price_source"), rs.getTimestamp("effective_from").toInstant(),
                        rs.getTimestamp("effective_to") == null ? null
                                : rs.getTimestamp("effective_to").toInstant());
                result.put(new PriceKey(value.provider(), value.upstreamModel()), value);
            }, arguments.toArray());
            return result;
        }
        /**
         * 结束每一条被传入区间取代的镜像行。
         *
         * <p>镜像中的开放行一直延伸到无穷，因此它会与同一 (provider, upstream_model) 的
         * 每一个后续区间重叠，而唯一满足排他约束的结束点，是它所重叠的
         * 最早的那个传入 {@code effective_from}。区间是右开区间
         * （{@code [from, to)}），因此二者此后只是恰好相接。
         *
         * <p>两条限制使该操作既合法又最小化：只有仍然开放的行才会被关闭（已有
         * 结束点的行属于历史，保持写入时的原样），并且新的结束点必须向前移动，
         * 因为 {@code ck_provider_price_period} 要求 {@code effective_to > effective_from}。
         * 因此，起始时间等于或早于其所重叠的开放行的传入区间，根本无法
         * 被排序进镜像；这种情况由 {@link #discardUnrepresentable} 报告，
         * 而不是中止整批。
         */
        private void closeSupersededOpenRanges(List<ProviderPriceSnapshotClient.PriceSnapshot> values) {
            String incomingRows = String.join(",", Collections.nCopies(values.size(),
                    "(?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz))"));
            List<Object> arguments = new ArrayList<>();
            for (ProviderPriceSnapshotClient.PriceSnapshot value : values) {
                arguments.add(value.provider());
                arguments.add(value.upstreamModel());
                arguments.add(Timestamp.from(value.effectiveFrom()));
                arguments.add(value.effectiveTo() == null ? null : Timestamp.from(value.effectiveTo()));
            }
            jdbc.update("""
                    WITH incoming(provider, upstream_model, effective_from, effective_to) AS (
                      VALUES %s
                    ), superseded AS (
                      SELECT mirrored.provider, mirrored.upstream_model,
                             MIN(incoming.effective_from) AS close_to
                      FROM platform.provider_price_snapshot AS mirrored
                      JOIN incoming
                        ON incoming.provider = mirrored.provider
                       AND incoming.upstream_model = mirrored.upstream_model
                      WHERE mirrored.effective_to IS NULL
                        AND tstzrange(mirrored.effective_from, 'infinity'::timestamptz, '[)')
                            && tstzrange(incoming.effective_from,
                                   COALESCE(incoming.effective_to, 'infinity'::timestamptz), '[)')
                      GROUP BY mirrored.provider, mirrored.upstream_model
                    )
                    UPDATE platform.provider_price_snapshot AS mirrored
                    SET effective_to = superseded.close_to
                    FROM superseded
                    WHERE mirrored.provider = superseded.provider
                      AND mirrored.upstream_model = superseded.upstream_model
                      AND mirrored.effective_to IS NULL
                      AND mirrored.effective_from < superseded.close_to
                    """.formatted(incomingRows), arguments.toArray());
        }

        /**
         * 略过在关闭之后仍与某条镜像行重叠的传入区间，同时连带丢弃
         * 它们待发的变更告警。
         *
         * <p>镜像只是一份不完整的历史，因此有些区间在其中没有合法的表示形式：上面
         * 的关闭操作只有在把某条开放行的结束点向前移动时才能写入，而一条起始时间早于
         * 该开放行的补录修正做不到这一点。插入这样一行会违反排他约束，并且
         * 由于整批是一个事务，还会连累批次中其它每一个快照。跳过它并发出告警，
         * 可以让其余价格继续流转，并把冲突明确暴露出来，
         * 而不是把它掩盖掉。
         */
        private void discardUnrepresentable(List<ProviderPriceSnapshotClient.PriceSnapshot> inserts,
                List<PriceChange> changes) {
            List<MirroredRange> mirrored = mirroredRanges(inserts);
            for (int index = inserts.size() - 1; index >= 0; index--) {
                ProviderPriceSnapshotClient.PriceSnapshot value = inserts.get(index);
                MirroredRange collision = overlap(mirrored, value);
                if (collision == null) continue;
                inserts.remove(index);
                changes.remove(index);
                LOG.warn("provider price snapshot left unmirrored: range [{}, {}) for {}/{} overlaps "
                        + "the mirrored range [{}, {}) and cannot be ordered after it",
                        value.effectiveFrom(), value.effectiveTo(), value.provider(),
                        value.upstreamModel(), collision.from(), collision.to());
            }
        }

        private MirroredRange overlap(List<MirroredRange> mirrored,
                ProviderPriceSnapshotClient.PriceSnapshot value) {
            for (MirroredRange range : mirrored) {
                if (range.provider().equals(value.provider())
                        && range.upstreamModel().equals(value.upstreamModel())
                        && range.overlaps(value.effectiveFrom(), value.effectiveTo())) {
                    return range;
                }
            }
            return null;
        }

        /** 正在插入的各个键对应的全部镜像区间，无论是开放还是已关闭的。 */
        private List<MirroredRange> mirroredRanges(List<ProviderPriceSnapshotClient.PriceSnapshot> values) {
            List<PriceKey> keys = values.stream()
                    .map(value -> new PriceKey(value.provider(), value.upstreamModel())).distinct().toList();
            String predicate = String.join(" OR ", Collections.nCopies(keys.size(),
                    "(provider=? AND upstream_model=?)"));
            List<Object> arguments = new ArrayList<>();
            keys.forEach(key -> { arguments.add(key.provider()); arguments.add(key.model()); });
            return jdbc.query("""
                    SELECT provider, upstream_model, effective_from, effective_to
                    FROM platform.provider_price_snapshot WHERE %s
                    """.formatted(predicate), (rs, row) -> new MirroredRange(
                            rs.getString("provider"), rs.getString("upstream_model"),
                            rs.getTimestamp("effective_from").toInstant(),
                            rs.getTimestamp("effective_to") == null ? null
                                    : rs.getTimestamp("effective_to").toInstant()),
                    arguments.toArray());
        }

        private int[] insertBatch(List<ProviderPriceSnapshotClient.PriceSnapshot> values,
                Instant capturedAt) {
            if (values.isEmpty()) return new int[0];
            int[][] chunks = jdbc.batchUpdate("""
                    INSERT INTO platform.provider_price_snapshot(provider, logical_model, upstream_model,
                      input_price, output_price, cache_read_price, cache_write_price, reasoning_price,
                      currency, exchange_rate, price_source, effective_from,
                      effective_to, original_price_id, captured_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (original_price_id) DO NOTHING
                    """, values, values.size(), (statement, p) -> {
                statement.setString(1, p.provider()); statement.setString(2, p.logicalModel());
                statement.setString(3, p.upstreamModel()); statement.setBigDecimal(4, p.inputPrice());
                statement.setBigDecimal(5, p.outputPrice());
                statement.setBigDecimal(6, p.cacheReadPrice());
                statement.setBigDecimal(7, p.cacheWritePrice());
                statement.setBigDecimal(8, p.reasoningPrice());
                statement.setString(9, p.currency()); statement.setBigDecimal(10, p.exchangeRate());
                statement.setString(11, p.priceSource());
                statement.setTimestamp(12, Timestamp.from(p.effectiveFrom()));
                statement.setTimestamp(13, p.effectiveTo() == null ? null : Timestamp.from(p.effectiveTo()));
                statement.setString(14, p.id().toString());
                statement.setTimestamp(15, Timestamp.from(capturedAt));
            });
            return Arrays.stream(chunks).flatMapToInt(Arrays::stream).toArray();
        }
        @Override public void health(boolean healthy, String detail, Instant checkedAt) {
            healthTransaction.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO platform.provider_price_sync_health(
                      singleton, healthy, last_error, checked_at, last_success_at)
                    VALUES (true, ?, ?, ?, CASE WHEN ? THEN CAST(? AS timestamptz) ELSE NULL END)
                    ON CONFLICT (singleton) DO UPDATE SET
                      healthy=EXCLUDED.healthy, last_error=EXCLUDED.last_error,
                      checked_at=EXCLUDED.checked_at,
                      last_success_at=CASE WHEN EXCLUDED.healthy THEN EXCLUDED.checked_at
                                           ELSE platform.provider_price_sync_health.last_success_at END
                    """, healthy, detail, Timestamp.from(checkedAt), healthy,
                    Timestamp.from(checkedAt)));
        }
    }

    private record PriceKey(String provider, String model) { }
    private record PriceChange(ProviderPriceSnapshotClient.PriceSnapshot before,
            ProviderPriceSnapshotClient.PriceSnapshot after, BigDecimal ratio) { }
    /** 一条镜像价格区间；{@code to} 为 null 表示一直延伸到无穷的开放区间。 */
    private record MirroredRange(String provider, String upstreamModel, Instant from, Instant to) {
        boolean overlaps(Instant otherFrom, Instant otherTo) {
            return (to == null || otherFrom.isBefore(to))
                    && (otherTo == null || from.isBefore(otherTo));
        }
    }

    static BigDecimal maximumChange(
            ProviderPriceSnapshotClient.PriceSnapshot before,
            ProviderPriceSnapshotClient.PriceSnapshot after) {
        if (before == null) return BigDecimal.ZERO;
        return change(before.inputPrice(), after.inputPrice())
                .max(change(before.outputPrice(), after.outputPrice()))
                .max(optionalChange(before.cacheReadPrice(), after.cacheReadPrice()))
                .max(optionalChange(before.cacheWritePrice(), after.cacheWritePrice()))
                .max(optionalChange(before.reasoningPrice(), after.reasoningPrice()));
    }
    private static BigDecimal change(BigDecimal oldValue, BigDecimal newValue) {
        if (oldValue.signum() == 0) return newValue.signum() == 0 ? BigDecimal.ZERO : BigDecimal.ONE;
        return newValue.subtract(oldValue).abs().divide(oldValue.abs(), 8, RoundingMode.HALF_UP);
    }
    private static BigDecimal optionalChange(BigDecimal oldValue, BigDecimal newValue) {
        if (oldValue == null || newValue == null) {
            return oldValue == null && newValue == null ? BigDecimal.ZERO : BigDecimal.ONE;
        }
        return change(oldValue, newValue);
    }
}
