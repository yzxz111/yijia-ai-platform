package com.yuzhihuihe.apiplatform.gateway.multimodal;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.file.FileObjectRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

public interface AttachmentExtractionCache {

    Mono<NormalizedAttachment> find(
            FileObjectRepository.FileObject source,
            String extractorVersion,
            String policyVersion);

    Mono<Void> put(
            FileObjectRepository.FileObject source,
            NormalizedAttachment attachment,
            String extractorVersion,
            String policyVersion,
            Duration cacheTtl);
}

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class PostgresAttachmentExtractionCache implements AttachmentExtractionCache {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    PostgresAttachmentExtractionCache(DatabaseClient databaseClient, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<NormalizedAttachment> find(
            FileObjectRepository.FileObject source,
            String extractorVersion,
            String policyVersion) {
        return databaseClient.sql("""
                        SELECT cache.normalized_text, cache.metadata::text AS metadata
                        FROM gateway.attachment_extraction_cache cache
                        JOIN gateway.file_object source ON source.id = cache.source_file_id
                        WHERE cache.tenant_id = :tenant_id
                          AND cache.owner_user_id = :owner_id
                          AND cache.file_sha256 = :sha256
                          AND cache.extractor_version = :extractor_version
                          AND cache.policy_version = :policy_version
                          AND cache.expires_at > now()
                          AND NOT (cache.metadata ? 'imageDataUris')
                          AND source.status = 'READY'
                          AND source.expires_at > now()
                          AND source.tenant_id = cache.tenant_id
                          AND source.owner_user_id = cache.owner_user_id
                          AND source.sha256 = cache.file_sha256
                        """)
                .bind("tenant_id", source.tenantId())
                .bind("owner_id", source.ownerUserId())
                .bind("sha256", source.sha256())
                .bind("extractor_version", extractorVersion)
                .bind("policy_version", policyVersion)
                .map((row, metadata) -> decode(
                        source,
                        row.get("normalized_text", String.class),
                        row.get("metadata", String.class)))
                .one();
    }

    @Override
    public Mono<Void> put(
            FileObjectRepository.FileObject source,
            NormalizedAttachment attachment,
            String extractorVersion,
            String policyVersion,
            Duration cacheTtl) {
        if (!attachment.imageDataUris().isEmpty()) {
            return delete(source, extractorVersion, policyVersion);
        }
        String metadata = encode(attachment);
        return databaseClient.sql("""
                        INSERT INTO gateway.attachment_extraction_cache(
                            id, source_file_id, tenant_id, owner_user_id, file_sha256,
                            extractor_version, policy_version, normalized_text, metadata,
                            expires_at)
                        VALUES (
                            :id, :source_file_id, :tenant_id, :owner_id, :sha256,
                            :extractor_version, :policy_version, :normalized_text,
                            CAST(:metadata AS jsonb),
                            LEAST(:source_expires_at,
                                now() + (:cache_ttl_ms * interval '1 millisecond')))
                        ON CONFLICT (
                            tenant_id, owner_user_id, file_sha256,
                            extractor_version, policy_version)
                        DO UPDATE SET
                            source_file_id = EXCLUDED.source_file_id,
                            normalized_text = EXCLUDED.normalized_text,
                            metadata = EXCLUDED.metadata,
                            expires_at = EXCLUDED.expires_at,
                            created_at = now()
                        """)
                .bind("id", UUID.randomUUID())
                .bind("source_file_id", source.id())
                .bind("tenant_id", source.tenantId())
                .bind("owner_id", source.ownerUserId())
                .bind("sha256", source.sha256())
                .bind("extractor_version", extractorVersion)
                .bind("policy_version", policyVersion)
                .bind("normalized_text", attachment.normalizedText())
                .bind("metadata", metadata)
                .bind("source_expires_at", source.expiresAt())
                .bind("cache_ttl_ms", cacheTtl.toMillis())
                .fetch().rowsUpdated().then();
    }

    private String encode(NormalizedAttachment attachment) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("kind", attachment.kind().name());
            root.set("details", objectMapper.valueToTree(attachment.metadata()));
            return objectMapper.writeValueAsString(root);
        } catch (Exception error) {
            throw new IllegalStateException("attachment_cache_encode_failed", error);
        }
    }

    private NormalizedAttachment decode(
            FileObjectRepository.FileObject source, String normalizedText, String metadata) {
        try {
            JsonNode root = objectMapper.readTree(metadata);
            NormalizedAttachment.Kind kind = NormalizedAttachment.Kind.valueOf(
                    root.path("kind").asText(NormalizedAttachment.Kind.TEXT.name()));
            Map<String, Object> details = root.has("details")
                    ? objectMapper.convertValue(root.get("details"), OBJECT_MAP)
                    : Map.of();
            return new NormalizedAttachment(
                    source.externalFileId(), source.originalFilename(), kind,
                    normalizedText, java.util.List.of(), details);
        } catch (Exception error) {
            throw new IllegalStateException("attachment_cache_decode_failed", error);
        }
    }

    private Mono<Void> delete(
            FileObjectRepository.FileObject source,
            String extractorVersion,
            String policyVersion) {
        return databaseClient.sql("""
                        DELETE FROM gateway.attachment_extraction_cache
                        WHERE tenant_id = :tenant_id
                          AND owner_user_id = :owner_id
                          AND file_sha256 = :sha256
                          AND extractor_version = :extractor_version
                          AND policy_version = :policy_version
                        """)
                .bind("tenant_id", source.tenantId())
                .bind("owner_id", source.ownerUserId())
                .bind("sha256", source.sha256())
                .bind("extractor_version", extractorVersion)
                .bind("policy_version", policyVersion)
                .fetch().rowsUpdated().then();
    }
}
