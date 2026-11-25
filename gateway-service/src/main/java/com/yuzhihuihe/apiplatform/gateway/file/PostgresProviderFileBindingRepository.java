package com.yuzhihuihe.apiplatform.gateway.file;

import java.util.UUID;

import io.r2dbc.spi.Row;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class PostgresProviderFileBindingRepository implements ProviderFileBindingRepository {

    private static final String SELECT = """
            SELECT id, file_object_id, channel_id, upstream_file_id, status, claim_token
            FROM gateway.provider_file_binding
            WHERE file_object_id = :file_id AND channel_id = :channel_id
            """;
    private final DatabaseClient databaseClient;

    public PostgresProviderFileBindingRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<Binding> findReady(UUID fileObjectId, UUID channelId) {
        return databaseClient.sql(SELECT + " AND status = 'READY'")
                .bind("file_id", fileObjectId)
                .bind("channel_id", channelId)
                .map((row, metadata) -> binding(row))
                .one();
    }

    @Override
    public Mono<Binding> find(UUID fileObjectId, UUID channelId) {
        return databaseClient.sql(SELECT)
                .bind("file_id", fileObjectId)
                .bind("channel_id", channelId)
                .map((row, metadata) -> binding(row))
                .one();
    }

    @Override
    public Mono<Binding> claim(UUID fileObjectId, UUID channelId) {
        UUID claimToken = UUID.randomUUID();
        Mono<Void> ensure = databaseClient.sql("""
                        INSERT INTO gateway.provider_file_binding(
                            id, file_object_id, channel_id, status, created_at, updated_at)
                        VALUES (:id, :file_id, :channel_id, 'PENDING', now(), now())
                        ON CONFLICT (file_object_id, channel_id) DO NOTHING
                        """)
                .bind("id", UUID.randomUUID())
                .bind("file_id", fileObjectId)
                .bind("channel_id", channelId)
                .fetch().rowsUpdated().then();
        return ensure.then(databaseClient.sql("""
                        UPDATE gateway.provider_file_binding
                        SET status = 'UPLOADING', claim_token = :claim_token,
                            last_error = NULL, updated_at = now()
                        WHERE file_object_id = :file_id AND channel_id = :channel_id
                          AND (status IN ('PENDING', 'FAILED')
                               OR (status = 'UPLOADING'
                                   AND updated_at < now() - interval '5 minutes'))
                        RETURNING id, file_object_id, channel_id, upstream_file_id,
                                  status, claim_token
                        """)
                .bind("file_id", fileObjectId)
                .bind("channel_id", channelId)
                .bind("claim_token", claimToken)
                .map((row, metadata) -> binding(row))
                .one());
    }

    @Override
    public Mono<Void> markReady(UUID bindingId, UUID claimToken, String upstreamFileId) {
        return databaseClient.sql("""
                        UPDATE gateway.provider_file_binding
                        SET status = 'READY', upstream_file_id = :upstream_id,
                            claim_token = NULL, last_error = NULL, updated_at = now()
                        WHERE id = :id AND status = 'UPLOADING' AND claim_token = :claim_token
                        """)
                .bind("id", bindingId)
                .bind("claim_token", claimToken)
                .bind("upstream_id", upstreamFileId)
                .fetch().rowsUpdated().then();
    }

    @Override
    public Mono<Void> markFailed(UUID bindingId, UUID claimToken) {
        return databaseClient.sql("""
                        UPDATE gateway.provider_file_binding
                        SET status = 'FAILED', claim_token = NULL,
                            last_error = 'provider_file_upload_failed', updated_at = now()
                        WHERE id = :id AND status = 'UPLOADING' AND claim_token = :claim_token
                        """)
                .bind("id", bindingId)
                .bind("claim_token", claimToken)
                .fetch().rowsUpdated().then();
    }

    private Binding binding(Row row) {
        return new Binding(
                row.get("id", UUID.class),
                row.get("file_object_id", UUID.class),
                row.get("channel_id", UUID.class),
                row.get("upstream_file_id", String.class),
                row.get("status", String.class),
                row.get("claim_token", UUID.class));
    }
}
