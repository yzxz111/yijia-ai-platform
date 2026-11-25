package com.yuzhihuihe.apiplatform.platform.reporting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 可重启的历史数据导入器，为每个权威数据源维护各自独立的元组水位线。 */
@Component
public class OperatingBackfillJob {

    private static final Instant FIRST_TIME = Instant.parse("1970-01-01T00:00:00Z");
    private static final UUID FIRST_ID = new UUID(0, 0);
    private final int batchSize;
    private final Checkpoints checkpoints;
    private final Map<SourceType, Source> sources;
    private final Sink sink;

    @Autowired
    public OperatingBackfillJob(JdbcTemplate jdbc, ObjectMapper mapper,
            OperatingFactService facts, OperatingFactIngestionService ingestion,
            @Value("${operating.backfill.batch-size:500}") int batchSize,
            @Value("${gateway.internal.base-url:http://gateway-service:8081}") URI gatewayBase,
            @Value("${security.internal.hmac-secret:}") String hmacSecret) {
        this.batchSize = validBatchSize(batchSize);
        this.checkpoints = new JdbcCheckpoints(jdbc, mapper);
        this.sources = productionSources(jdbc, mapper, gatewayBase, hmacSecret);
        this.sink = new FactSink(mapper, facts, ingestion);
    }

    OperatingBackfillJob(int batchSize, Checkpoints checkpoints,
            Map<SourceType, Source> sources, Sink sink) {
        this.batchSize = validBatchSize(batchSize);
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.sources = Map.copyOf(sources);
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Scheduled(fixedDelayString = "${operating.backfill.fixed-delay-ms:60000}")
    void runOnce() {
        for (SourceType type : SourceType.values()) {
            try {
                runBatch(type);
            } catch (RuntimeException ignored) {
                // 检查点保留有界的错误信息与未变的水位线，以便重试。
            }
        }
    }

    public BatchResult runBatch(SourceType type) {
        Source source = sources.get(type);
        if (source == null) return new BatchResult(0, true, false);
        Claim claim = checkpoints.claim(type);
        if (claim == null) {
            Checkpoint current = checkpoints.load(type);
            if (current.status() == Status.PAUSED) return new BatchResult(0, false, true);
            if (current.status() == Status.COMPLETED) return new BatchResult(0, true, false);
            return new BatchResult(0, false, false);
        }
        Checkpoint checkpoint = claim.checkpoint();
        try {
            List<Item> items = List.copyOf(source.fetch(checkpoint.cursor(), batchSize));
            if (items.size() > batchSize) throw new IllegalStateException("backfill_source_page_exceeded");
            Cursor previous = checkpoint.cursor();
            for (Item item : items) {
                if (item == null || item.cursor() == null || item.key() == null
                        || (previous != null && item.cursor().compareTo(previous) <= 0)) {
                    throw new IllegalStateException("backfill_source_cursor_invalid");
                }
                sink.accept(type, item);
                previous = item.cursor();
            }
            boolean completed = items.size() < batchSize;
            if (!checkpoints.advanced(type, claim.token(), checkpoint.cursor(), previous, completed)) {
                throw new IllegalStateException("backfill_checkpoint_claim_lost");
            }
            return new BatchResult(items.size(), completed, false);
        } catch (RuntimeException failure) {
            checkpoints.failed(type, claim.token(), checkpoint.cursor(), bounded(failure.getMessage()));
            throw failure;
        }
    }

    public void pause(SourceType type) { checkpoints.status(type, Status.PAUSED); }
    public void resume(SourceType type) { checkpoints.status(type, Status.PENDING); }

    private static int validBatchSize(int value) {
        if (value < 1 || value > 500) throw new IllegalArgumentException("backfill_batch_size_invalid");
        return value;
    }

    private static String bounded(String value) {
        if (value == null) return "backfill_failed";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private static Map<SourceType, Source> productionSources(JdbcTemplate jdbc,
            ObjectMapper mapper, URI gatewayBase, String secret) {
        Map<SourceType, Source> result = new EnumMap<>(SourceType.class);
        result.put(SourceType.PACKAGE_ORDER, new SqlSource(jdbc, PACKAGE_SQL));
        result.put(SourceType.RECHARGE_ORDER, new SqlSource(jdbc, RECHARGE_SQL));
        result.put(SourceType.REFUND, new SqlSource(jdbc, REFUND_SQL));
        result.put(SourceType.WALLET_USAGE, new SqlSource(jdbc, WALLET_SQL));
        result.put(SourceType.GATEWAY_USAGE,
                new GatewaySource(mapper, gatewayBase, secret));
        return result;
    }

    static final String PACKAGE_SQL = """
            SELECT orders.id, orders.created_at,
                   jsonb_build_object('id', orders.id, 'tenantId', orders.tenant_id,
                     'userId', orders.user_id, 'packagePlanVersionId', orders.package_plan_version_id,
                     'paymentTransactionId', orders.successful_payment_transaction_id,
                     'amount', orders.amount, 'currency', orders.currency,
                     'settlementMethod', orders.settlement_method,
                     'occurredAt', coalesce(orders.paid_at, orders.created_at))::text payload
            FROM platform.customer_order orders
            WHERE orders.status IN ('PAID','REFUNDED','PARTIALLY_REFUNDED')
              AND (orders.created_at, orders.id) > (?, ?)
            ORDER BY orders.created_at, orders.id LIMIT ?
            """;
    static final String RECHARGE_SQL = """
            SELECT recharge.id, recharge.created_at,
                   jsonb_build_object('id', recharge.id, 'tenantId', recharge.tenant_id,
                     'userId', recharge.user_id, 'paymentTransactionId', payment.id,
                     'amount', recharge.paid_amount, 'currency', recharge.currency,
                     'occurredAt', coalesce(recharge.paid_at, recharge.created_at))::text payload
            FROM platform.recharge_order recharge
            JOIN LATERAL (SELECT id FROM platform.payment_transaction payment
                          WHERE payment.recharge_order_id=recharge.id AND payment.applied_to_order
                          ORDER BY payment.created_at DESC, payment.id DESC LIMIT 1) payment ON true
            WHERE recharge.status='PAID' AND (recharge.created_at, recharge.id) > (?, ?)
            ORDER BY recharge.created_at, recharge.id LIMIT ?
            """;
    static final String REFUND_SQL = """
            SELECT refund.id, refund.created_at,
                   jsonb_build_object('id', refund.id, 'orderId', refund.order_id,
                     'tenantId', orders.tenant_id, 'userId', orders.user_id,
                     'amount', refund.amount, 'currency', orders.currency,
                     'fundingSource', refund.funding_source,
                     'occurredAt', coalesce(refund.completed_at, refund.created_at))::text payload
            FROM platform.refund refund JOIN platform.customer_order orders ON orders.id=refund.order_id
            WHERE refund.status='COMPLETED' AND (refund.created_at, refund.id) > (?, ?)
            ORDER BY refund.created_at, refund.id LIMIT ?
            """;
    static final String WALLET_SQL = """
            SELECT usage.id, usage.created_at,
                   jsonb_build_object('id', usage.id, 'tenantId', usage.tenant_id,
                     'userId', usage.user_id, 'requestId', usage.request_id,
                     'modelCode', usage.model_code, 'priceVersion', usage.price_version,
                     'amount', usage.settled_amount, 'inputTokens', usage.actual_input_tokens,
                     'outputTokens', usage.actual_output_tokens, 'billingMode', usage.billing_mode,
                     'occurredAt', coalesce(usage.settled_at, usage.created_at))::text payload
            FROM platform.wallet_usage_reservation usage
            WHERE usage.status='SETTLED' AND usage.settled_amount > 0
              AND (usage.created_at, usage.id) > (?, ?)
            ORDER BY usage.created_at, usage.id LIMIT ?
            """;

    enum SourceType {
        PACKAGE_ORDER("platform"), RECHARGE_ORDER("platform"), REFUND("platform"),
        WALLET_USAGE("platform"), GATEWAY_USAGE("gateway");
        private final String sourceService;
        SourceType(String sourceService) { this.sourceService = sourceService; }
        String sourceService() { return sourceService; }
    }
    enum Status { PENDING, RUNNING, PAUSED, COMPLETED, FAILED }
    record Cursor(Instant createdAt, UUID id) implements Comparable<Cursor> {
        Cursor {
            Objects.requireNonNull(createdAt, "createdAt"); Objects.requireNonNull(id, "id");
        }
        @Override public int compareTo(Cursor other) {
            int time = createdAt.compareTo(other.createdAt);
            if (time != 0) return time;
            int high = Long.compareUnsigned(id.getMostSignificantBits(),
                    other.id.getMostSignificantBits());
            return high != 0 ? high : Long.compareUnsigned(id.getLeastSignificantBits(),
                    other.id.getLeastSignificantBits());
        }
    }
    record Item(String key, Cursor cursor, byte[] payload) {
        Item { payload = payload == null ? new byte[0] : payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
    record Checkpoint(Cursor cursor, Status status, String lastError) { }
    record Claim(UUID token, Checkpoint checkpoint) { }
    record BatchResult(int processed, boolean completed, boolean paused) { }

    interface Source { List<Item> fetch(Cursor after, int limit); }
    interface Sink { void accept(SourceType type, Item item); }
    interface Checkpoints {
        Checkpoint load(SourceType type);
        Claim claim(SourceType type);
        boolean advanced(SourceType type, UUID token, Cursor expected,
                Cursor cursor, boolean completed);
        boolean failed(SourceType type, UUID token, Cursor expected, String error);
        void status(SourceType type, Status status);
    }

    private static final class SqlSource implements Source {
        private final JdbcTemplate jdbc;
        private final String sql;
        private SqlSource(JdbcTemplate jdbc, String sql) { this.jdbc = jdbc; this.sql = sql; }
        @Override public List<Item> fetch(Cursor after, int limit) {
            Cursor cursor = after == null ? new Cursor(FIRST_TIME, FIRST_ID) : after;
            return jdbc.query(sql, (result, row) -> item(result), Timestamp.from(cursor.createdAt()),
                    cursor.id(), limit);
        }
        private Item item(ResultSet result) throws SQLException {
            UUID id = result.getObject("id", UUID.class);
            Instant created = result.getTimestamp("created_at").toInstant();
            return new Item(id.toString(), new Cursor(created, id),
                    result.getString("payload").getBytes(StandardCharsets.UTF_8));
        }
    }

    static final class FactSink implements Sink {
        private final ObjectMapper mapper;
        private final OperatingFactService facts;
        private final OperatingFactIngestionService ingestion;
        FactSink(ObjectMapper mapper, OperatingFactService facts,
                OperatingFactIngestionService ingestion) {
            this.mapper = mapper; this.facts = facts; this.ingestion = ingestion;
        }
        @Override public void accept(SourceType type, Item item) {
            try {
                JsonNode value = mapper.readTree(item.payload());
                switch (type) {
                    case PACKAGE_ORDER -> facts.recordPackagePurchase(new OperatingFactService.PackagePurchase(
                            uuid(value, "id"), uuid(value, "tenantId"), uuid(value, "userId"),
                            uuid(value, "packagePlanVersionId"), optionalUuid(value, "paymentTransactionId"),
                            value.path("amount").longValue(), text(value, "currency"),
                            text(value, "settlementMethod"), instant(value, "occurredAt")));
                    case RECHARGE_ORDER -> facts.recordWalletRecharge(new OperatingFactService.WalletRecharge(
                            uuid(value, "id"), uuid(value, "tenantId"), uuid(value, "userId"),
                            uuid(value, "paymentTransactionId"), value.path("amount").longValue(),
                            text(value, "currency"), instant(value, "occurredAt")));
                    case REFUND -> facts.recordPackageRefund(new OperatingFactService.PackageRefund(
                            uuid(value, "id"), uuid(value, "orderId"), uuid(value, "tenantId"),
                            uuid(value, "userId"), value.path("amount").longValue(),
                            text(value, "currency"), text(value, "fundingSource"),
                            instant(value, "occurredAt")));
                    case WALLET_USAGE -> {
                        OperatingFactService.WalletUsage usage = new OperatingFactService.WalletUsage(
                                uuid(value, "id"), uuid(value, "tenantId"), uuid(value, "userId"),
                                text(value, "requestId"), text(value, "modelCode"),
                                nullableText(value, "priceVersion"), value.path("amount").longValue(),
                                value.path("inputTokens").longValue(), value.path("outputTokens").longValue(),
                                text(value, "billingMode"), instant(value, "occurredAt"));
                        if ("UNKNOWN".equals(usage.billingMode())) {
                            facts.recordLegacyWalletUsage(usage);
                        } else {
                            facts.recordWalletUsage(usage);
                        }
                    }
                    case GATEWAY_USAGE -> {
                        byte[] body = item.payload();
                        OperatingFactIngestionService.Result result = ingestion.ingest(body, sha256(body));
                        if (result != OperatingFactIngestionService.Result.ACCEPTED
                                && result != OperatingFactIngestionService.Result.REPLAYED) {
                            throw new IllegalStateException("gateway_backfill_ingestion_" + result.name().toLowerCase());
                        }
                    }
                }
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("operating_backfill_payload_invalid", failure);
            }
        }
        private static UUID uuid(JsonNode value, String field) { return UUID.fromString(text(value, field)); }
        private static UUID optionalUuid(JsonNode value, String field) {
            String text = nullableText(value, field); return text == null ? null : UUID.fromString(text);
        }
        private static String text(JsonNode value, String field) {
            String result = nullableText(value, field);
            if (result == null || result.isBlank()) throw new IllegalArgumentException("backfill_field_missing:" + field);
            return result;
        }
        private static String nullableText(JsonNode value, String field) {
            JsonNode node = value.get(field); return node == null || node.isNull() ? null : node.asText();
        }
        private static Instant instant(JsonNode value, String field) { return Instant.parse(text(value, field)); }
    }

    private static final class JdbcCheckpoints implements Checkpoints {
        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper;
        private JdbcCheckpoints(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
        @Override public Checkpoint load(SourceType type) {
            jdbc.update("""
                    INSERT INTO platform.operating_backfill_checkpoint(source_service, source_type)
                    VALUES (?, ?) ON CONFLICT DO NOTHING
                    """, type.sourceService(), type.name());
            return jdbc.queryForObject("""
                    SELECT watermark::text, status, last_error
                    FROM platform.operating_backfill_checkpoint
                    WHERE source_service=? AND source_type=?
                    """, (result, row) -> new Checkpoint(cursor(result.getString(1)),
                            Status.valueOf(result.getString(2)), result.getString(3)),
                    type.sourceService(), type.name());
        }
        @Override public Claim claim(SourceType type) {
            load(type);
            UUID token = UUID.randomUUID();
            List<Checkpoint> claimed = jdbc.query("""
                    UPDATE platform.operating_backfill_checkpoint
                    SET status='RUNNING', job_claim_token=?,
                        job_claimed_until=now() + interval '2 minutes',
                        started_at=coalesce(started_at, now()), updated_at=now()
                    WHERE source_service=? AND source_type=?
                      AND status IN ('PENDING','RUNNING','FAILED')
                      AND next_attempt_at <= now()
                      AND (job_claimed_until IS NULL OR job_claimed_until < now())
                    RETURNING watermark::text, status, last_error
                    """, (result, row) -> new Checkpoint(cursor(result.getString(1)),
                            Status.valueOf(result.getString(2)), result.getString(3)),
                    token, type.sourceService(), type.name());
            return claimed.isEmpty() ? null : new Claim(token, claimed.getFirst());
        }
        @Override public boolean advanced(SourceType type, UUID token, Cursor expected,
                Cursor cursor, boolean completed) {
            try {
                String watermark = cursor == null ? "{}" : mapper.writeValueAsString(Map.of(
                        "createdAt", cursor.createdAt().toString(), "id", cursor.id().toString()));
                String expectedWatermark = expected == null ? "{}" : mapper.writeValueAsString(Map.of(
                        "createdAt", expected.createdAt().toString(), "id", expected.id().toString()));
                return jdbc.update("""
                        UPDATE platform.operating_backfill_checkpoint
                        SET watermark=CAST(? AS jsonb), status=?, last_error=NULL,
                            completed_at=CASE WHEN ? THEN now() ELSE NULL END,
                            job_claim_token=NULL, job_claimed_until=NULL, updated_at=now()
                        WHERE source_service=? AND source_type=? AND status='RUNNING'
                          AND job_claim_token=? AND watermark=CAST(? AS jsonb)
                        """, watermark, completed ? Status.COMPLETED.name() : Status.RUNNING.name(),
                        completed, type.sourceService(), type.name(), token, expectedWatermark) == 1;
            } catch (Exception error) { throw new IllegalStateException("backfill_checkpoint_serialization_failed", error); }
        }
        @Override public boolean failed(SourceType type, UUID token, Cursor expected, String error) {
            try {
                String expectedWatermark = expected == null ? "{}" : mapper.writeValueAsString(Map.of(
                        "createdAt", expected.createdAt().toString(), "id", expected.id().toString()));
                return jdbc.update("""
                    UPDATE platform.operating_backfill_checkpoint
                    SET status='FAILED', last_error=?, attempt_count=attempt_count+1,
                        next_attempt_at=now(), job_claim_token=NULL,
                        job_claimed_until=NULL, updated_at=now()
                    WHERE source_service=? AND source_type=? AND status='RUNNING'
                      AND job_claim_token=? AND watermark=CAST(? AS jsonb)
                    """, error, type.sourceService(), type.name(), token, expectedWatermark) == 1;
            } catch (Exception serialization) {
                throw new IllegalStateException("backfill_checkpoint_serialization_failed", serialization);
            }
        }
        @Override public void status(SourceType type, Status status) {
            load(type);
            String expected = status == Status.PAUSED ? "status <> 'COMPLETED'" : "status = 'PAUSED'";
            jdbc.update("""
                    UPDATE platform.operating_backfill_checkpoint
                    SET status=?, last_error=NULL, job_claim_token=NULL,
                        job_claimed_until=NULL, updated_at=now()
                    WHERE source_service=? AND source_type=? AND """ + expected,
                    status.name(), type.sourceService(), type.name());
        }
        private Cursor cursor(String json) {
            try {
                JsonNode value = mapper.readTree(json);
                if (!value.hasNonNull("createdAt") || !value.hasNonNull("id")) return null;
                return new Cursor(Instant.parse(value.path("createdAt").asText()),
                        UUID.fromString(value.path("id").asText()));
            } catch (Exception error) { throw new IllegalStateException("backfill_checkpoint_invalid", error); }
        }
    }

    private static final class GatewaySource implements Source {
        private static final String PATH = "/internal/usage-economics/backfill";
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
        private final ObjectMapper mapper;
        private final URI base;
        private final InternalRequestSigner signer;
        private final boolean configured;
        private GatewaySource(ObjectMapper mapper, URI base, String secret) {
            this.mapper = mapper; this.base = base; this.signer = new InternalRequestSigner(secret);
            this.configured = secret != null && !secret.isBlank();
        }
        @Override public List<Item> fetch(Cursor after, int limit) {
            if (!configured) throw new IllegalStateException("security.internal.hmac-secret must be configured");
            try {
                byte[] body = mapper.writeValueAsBytes(new GatewayBackfillRequest(limit,
                        after == null ? null : after.createdAt(), after == null ? null : after.id()));
                UUID requestId = UUID.randomUUID();
                InternalRequestSigner.SignedHeaders signed = signer.sign(Instant.now().getEpochSecond(),
                        requestId, "POST", PATH, body);
                HttpRequest request = HttpRequest.newBuilder(base.resolve(PATH))
                     .timeout(java.time.Duration.ofSeconds(10))
                     .header("Content-Type", "application/json")
                     .header("X-Internal-Timestamp", signed.timestamp())
                     .header("X-Internal-Request-Id", signed.requestId())
                     .header("X-Internal-Content-Sha256", signed.contentSha256())
                     .header("X-Internal-Signature", signed.signature())
                     .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) throw new IllegalStateException("gateway_backfill_failed:" + response.statusCode());
                JsonNode root = mapper.readTree(response.body());
                List<Item> items = new ArrayList<>();
                JsonNode cursors = root.path("eventCursors");
                int index = 0;
                for (JsonNode event : root.path("events")) {
                    UUID id = UUID.fromString(event.path("event_id").asText());
                    JsonNode cursor = cursors.path(index++);
                    if (!id.toString().equals(cursor.path("usageId").asText())) {
                        throw new IllegalStateException("gateway_backfill_cursor_mismatch");
                    }
                    Instant createdAt = Instant.parse(cursor.path("createdAt").asText());
                    items.add(new Item(id.toString(), new Cursor(createdAt, id), mapper.writeValueAsBytes(event)));
                }
                return items;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("gateway_backfill_interrupted", error);
            } catch (RuntimeException error) { throw error;
            } catch (Exception error) { throw new IllegalStateException("gateway_backfill_failed", error); }
        }
        private record GatewayBackfillRequest(int limit, Instant cursorCreatedAt, UUID cursorUsageId) { }
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception error) { throw new IllegalStateException("sha256_unavailable", error); }
    }
}
