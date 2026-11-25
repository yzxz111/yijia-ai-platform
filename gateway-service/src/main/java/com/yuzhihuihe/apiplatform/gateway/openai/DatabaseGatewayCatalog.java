package com.yuzhihuihe.apiplatform.gateway.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.limit.PackageRateLimits;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelCircuitRegistry;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import com.yuzhihuihe.apiplatform.gateway.routing.CredentialCipher;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
final class DatabaseGatewayCatalog implements ChatCompletionService.GatewayCatalog {

    private final DatabaseClient databaseClient;
    private final ChannelCircuitRegistry circuits;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;

    DatabaseGatewayCatalog(
            DatabaseClient databaseClient,
            ChannelCircuitRegistry circuits,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.circuits = circuits;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<ChatCompletionService.PreparedRequest> prepare(
            ApiKeyService.ApiKeyContext apiKey,
            String publicModelAlias,
            String routingModelAlias,
            Set<ChannelRouter.Capability> capabilities) {
        return databaseClient
                .sql(
                        """
                        SELECT public_model.id AS public_model_id,
                               routing_model.id AS routing_model_id,
                               public_model.max_output_tokens,
                               package.max_output_tokens AS package_max_output_tokens,
                               package.id AS package_instance_id,
                               package.daily_request_limit AS package_daily_request_limit,
                               package.tokens_per_minute AS package_tokens_per_minute,
                               package.usage_dimension AS package_usage_dimension,
                               COALESCE(package.requests_per_minute, key.requests_per_minute,
                                   user_limit.requests_per_minute,
                                   public_model.requests_per_minute, channel.requests_per_minute, 60)
                                   AS package_rpm,
                               COALESCE(package.max_concurrency, key.max_concurrency,
                                   user_limit.max_concurrency,
                                   public_model.max_concurrency, channel.max_concurrency, 10)
                                   AS package_concurrency,
                               COALESCE(key.requests_per_minute, user_limit.requests_per_minute,
                                   public_model.requests_per_minute, channel.requests_per_minute, 60)
                                   AS key_rpm,
                               COALESCE(key.max_concurrency, user_limit.max_concurrency,
                                   public_model.max_concurrency, channel.max_concurrency, 10)
                                   AS key_concurrency,
                               COALESCE(user_limit.requests_per_minute, key.requests_per_minute,
                                   public_model.requests_per_minute, channel.requests_per_minute, 60)
                                   AS user_rpm,
                               COALESCE(user_limit.max_concurrency, key.max_concurrency,
                                   public_model.max_concurrency, channel.max_concurrency, 10)
                                   AS user_concurrency,
                               COALESCE(channel.requests_per_minute, key.requests_per_minute,
                                   user_limit.requests_per_minute, public_model.requests_per_minute, 60)
                                   AS channel_rpm,
                               COALESCE(channel.max_concurrency, key.max_concurrency,
                                   user_limit.max_concurrency, public_model.max_concurrency, 10)
                                   AS channel_concurrency,
                               COALESCE(public_model.requests_per_minute, key.requests_per_minute,
                                   user_limit.requests_per_minute, channel.requests_per_minute, 60)
                                   AS model_rpm,
                               COALESCE(public_model.max_concurrency, key.max_concurrency,
                                   user_limit.max_concurrency, channel.max_concurrency, 10)
                                   AS model_concurrency,
                               provider.id AS provider_id, provider.provider_type,
                               channel.id AS channel_id, channel.base_url, channel.encrypted_api_key,
                               channel.timeout_ms,
                               CASE
                                 WHEN route_override.channel_id IS NOT NULL THEN 2147483647
                                 ELSE COALESCE(mapping.priority_override, channel.priority)
                               END AS route_priority,
                               COALESCE(mapping.weight_override, channel.weight) AS route_weight,
                               mapping.upstream_model, mapping.capabilities::text AS capabilities
                        FROM gateway.api_key key
                        JOIN gateway.model_alias public_alias
                          ON public_alias.alias = :public_model_alias
                        JOIN gateway.model public_model
                          ON public_model.id = public_alias.model_id AND public_model.enabled
                         AND NOT (public_model.capabilities @> '["VIDEO"]'::jsonb)
                         AND NOT (public_model.capabilities @> '["IMAGE_GEN"]'::jsonb)
                         AND NOT EXISTS (
                             SELECT 1
                             FROM gateway.model_usage_price video_price
                             WHERE video_price.model_code = public_alias.alias
                               AND video_price.usage_dimension = 'VIDEO_SECOND'
                               AND video_price.effective_to IS NULL)
                         AND NOT EXISTS (
                             SELECT 1
                             FROM gateway.video_alias_tier video_alias
                             WHERE video_alias.model_code = public_alias.alias)
                        LEFT JOIN gateway.package_instance package
                          ON package.id = key.package_instance_id
                         AND package.tenant_id = key.tenant_id
                         AND package.user_id = key.user_id
                        LEFT JOIN gateway.package_instance_model_quota model_quota
                          ON model_quota.package_instance_id = package.id
                         AND model_quota.model_code = public_alias.alias
                        JOIN gateway.model_alias routing_alias
                          ON routing_alias.alias = :routing_model_alias
                        JOIN gateway.model routing_model
                          ON routing_model.id = routing_alias.model_id AND routing_model.enabled
                         AND NOT (routing_model.capabilities @> '["VIDEO"]'::jsonb)
                         AND NOT (routing_model.capabilities @> '["IMAGE_GEN"]'::jsonb)
                         AND NOT EXISTS (
                             SELECT 1
                             FROM gateway.model_usage_price video_price
                             WHERE video_price.model_code = routing_alias.alias
                               AND video_price.usage_dimension = 'VIDEO_SECOND'
                               AND video_price.effective_to IS NULL)
                         AND NOT EXISTS (
                             SELECT 1
                             FROM gateway.video_alias_tier video_alias
                             WHERE video_alias.model_code = routing_alias.alias)
                        JOIN gateway.channel_model mapping
                          ON mapping.model_id = routing_model.id AND mapping.enabled
                        JOIN gateway.channel channel ON channel.id = mapping.channel_id
                          AND channel.enabled AND channel.last_health_status = 'HEALTHY'
                        JOIN gateway.provider provider ON provider.id = channel.provider_id AND provider.enabled
                        LEFT JOIN gateway.routing_override route_override
                          ON route_override.routing_model_alias = routing_alias.alias
                         AND route_override.channel_id = channel.id
                        LEFT JOIN gateway.user_request_limit user_limit ON user_limit.user_id = key.user_id
                        WHERE key.id = :key_id AND key.tenant_id = :tenant_id AND key.user_id = :user_id
                          AND key.status = 'ACTIVE'
                          AND (key.expires_at IS NULL OR key.expires_at > now())
                          AND key.allowed_models @> CAST(:key_allowed_model AS jsonb)
                          AND (key.package_instance_id IS NULL OR (
                              package.id IS NOT NULL
                              AND package.status = 'ACTIVE'
                              AND package.expires_at > now()
                              AND COALESCE(package.usage_dimension, 'TOKEN') <> 'VIDEO_SECOND'
                              AND package.used_tokens + package.reserved_tokens
                                  < package.total_token_quota
                              AND EXISTS (
                                  SELECT 1
                                  FROM jsonb_array_elements(package.allowed_models) allowed
                                  WHERE allowed ->> 'modelCode' = public_alias.alias)
                              -- 秒维度（VIDEO_SECOND）套餐的配额在 seconds_quota，token_quota 为 NULL，见 V42 迁移
                              AND (model_quota.id IS NULL OR
                                   model_quota.used_tokens + model_quota.reserved_tokens
                                       < COALESCE(model_quota.token_quota, model_quota.seconds_quota))))
                          AND mapping.capabilities @> CAST(:capability AS jsonb)
                        """)
                .bind("public_model_alias", publicModelAlias)
                .bind("routing_model_alias", routingModelAlias)
                .bind("key_id", apiKey.apiKeyId())
                .bind("tenant_id", apiKey.tenantId())
                .bind("user_id", apiKey.userId())
                .bind("key_allowed_model", allowedModelJson(publicModelAlias))
                .bind("capability", capabilityJson(capabilities))
                .map((row, metadata) -> row(row, routingModelAlias))
                .all()
                .collectList()
                .filter(rows -> !rows.isEmpty())
                .map(rows -> prepared(rows, publicModelAlias, routingModelAlias));
    }

    @Override
    public Mono<ChatCompletionService.PreparedPublicRequest> preparePublic(
            ApiKeyService.ApiKeyContext apiKey, String publicModelAlias) {
        return databaseClient
                .sql(
                        """
                        SELECT public_model.id AS public_model_id,
                               public_model.max_output_tokens,
                               package.max_output_tokens AS package_max_output_tokens,
                               package.id AS package_instance_id,
                               package.daily_request_limit AS package_daily_request_limit,
                               package.tokens_per_minute AS package_tokens_per_minute,
                               package.usage_dimension AS package_usage_dimension,
                               COALESCE(package.requests_per_minute, key.requests_per_minute,
                                   user_limit.requests_per_minute,
                                   public_model.requests_per_minute, 60) AS package_rpm,
                               COALESCE(package.max_concurrency, key.max_concurrency,
                                   user_limit.max_concurrency,
                                   public_model.max_concurrency, 10) AS package_concurrency,
                               COALESCE(key.requests_per_minute, user_limit.requests_per_minute,
                                   public_model.requests_per_minute, 60) AS key_rpm,
                               COALESCE(key.max_concurrency, user_limit.max_concurrency,
                                   public_model.max_concurrency, 10) AS key_concurrency,
                               COALESCE(user_limit.requests_per_minute, key.requests_per_minute,
                                   public_model.requests_per_minute, 60) AS user_rpm,
                               COALESCE(user_limit.max_concurrency, key.max_concurrency,
                                   public_model.max_concurrency, 10) AS user_concurrency,
                               COALESCE(public_model.requests_per_minute, key.requests_per_minute,
                                   user_limit.requests_per_minute, 60) AS model_rpm,
                               COALESCE(public_model.max_concurrency, key.max_concurrency,
                                   user_limit.max_concurrency, 10) AS model_concurrency
                        FROM gateway.api_key key
                        JOIN gateway.model_alias public_alias
                          ON public_alias.alias = :public_model_alias
                        JOIN gateway.model public_model
                          ON public_model.id = public_alias.model_id AND public_model.enabled
                         AND NOT (public_model.capabilities @> '["VIDEO"]'::jsonb)
                         AND NOT (public_model.capabilities @> '["IMAGE_GEN"]'::jsonb)
                         AND NOT EXISTS (
                             SELECT 1
                             FROM gateway.model_usage_price video_price
                             WHERE video_price.model_code = public_alias.alias
                               AND video_price.usage_dimension = 'VIDEO_SECOND'
                               AND video_price.effective_to IS NULL)
                         AND NOT EXISTS (
                             SELECT 1
                             FROM gateway.video_alias_tier video_alias
                             WHERE video_alias.model_code = public_alias.alias)
                        LEFT JOIN gateway.package_instance package
                          ON package.id = key.package_instance_id
                         AND package.tenant_id = key.tenant_id
                         AND package.user_id = key.user_id
                        LEFT JOIN gateway.package_instance_model_quota model_quota
                          ON model_quota.package_instance_id = package.id
                         AND model_quota.model_code = public_alias.alias
                        LEFT JOIN gateway.user_request_limit user_limit
                          ON user_limit.user_id = key.user_id
                        WHERE key.id = :key_id
                          AND key.tenant_id = :tenant_id
                          AND key.user_id = :user_id
                          AND key.status = 'ACTIVE'
                          AND (key.expires_at IS NULL OR key.expires_at > now())
                          AND key.allowed_models @> CAST(:key_allowed_model AS jsonb)
                          AND (key.package_instance_id IS NULL OR (
                              package.id IS NOT NULL
                              AND package.status = 'ACTIVE'
                              AND package.expires_at > now()
                              AND COALESCE(package.usage_dimension, 'TOKEN') <> 'VIDEO_SECOND'
                              AND package.used_tokens + package.reserved_tokens
                                  < package.total_token_quota
                              AND EXISTS (
                                  SELECT 1
                                  FROM jsonb_array_elements(package.allowed_models) allowed
                                  WHERE allowed ->> 'modelCode' = public_alias.alias)
                              -- 秒维度（VIDEO_SECOND）套餐的配额在 seconds_quota，token_quota 为 NULL，见 V42 迁移
                              AND (model_quota.id IS NULL OR
                                   model_quota.used_tokens + model_quota.reserved_tokens
                                       < COALESCE(model_quota.token_quota, model_quota.seconds_quota))))
                        """)
                .bind("public_model_alias", publicModelAlias)
                .bind("key_id", apiKey.apiKeyId())
                .bind("tenant_id", apiKey.tenantId())
                .bind("user_id", apiKey.userId())
                .bind("key_allowed_model", allowedModelJson(publicModelAlias))
                .map(
                        (row, metadata) ->
                                new ChatCompletionService.PreparedPublicRequest(
                                        row.get("public_model_id", UUID.class),
                                        publicModelAlias,
                                        row.get("max_output_tokens", Integer.class),
                                        new RequestLimitService.Thresholds(
                                                row.get("package_rpm", Integer.class),
                                                row.get("key_rpm", Integer.class),
                                                row.get("user_rpm", Integer.class),
                                                Integer.MAX_VALUE,
                                                row.get("model_rpm", Integer.class)),
                                        new RequestLimitService.Thresholds(
                                                row.get("package_concurrency", Integer.class),
                                                row.get("key_concurrency", Integer.class),
                                                row.get("user_concurrency", Integer.class),
                                                Integer.MAX_VALUE,
                                                row.get("model_concurrency", Integer.class)),
                                        dailyLimit(row),
                                        tokenLimit(row),
                                        row.get("package_max_output_tokens", Integer.class)))
                .one();
    }

    private String allowedModelJson(String publicModelAlias) {
        try {
            return objectMapper.writeValueAsString(List.of(publicModelAlias));
        } catch (Exception exception) {
            throw new IllegalStateException("allowed_model_serialization_failed", exception);
        }
    }

    private String capabilityJson(Set<ChannelRouter.Capability> capabilities) {
        try {
            return objectMapper.writeValueAsString(
                    capabilities.stream().map(Enum::name).sorted().toList());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "requested_capabilities_serialization_failed", exception);
        }
    }

    private CatalogRow row(io.r2dbc.spi.Row row, String modelAlias) {
        UUID channelId = row.get("channel_id", UUID.class);
        String capabilitiesJson = row.get("capabilities", String.class);
        byte[] encrypted = bytes(row.get("encrypted_api_key"));
        Set<ChannelRouter.Capability> capabilities = capabilities(capabilitiesJson);
        ProviderAdapter.Channel channel =
                new ProviderAdapter.Channel(
                        channelId,
                        row.get("provider_id", UUID.class),
                        row.get("provider_type", String.class),
                        row.get("base_url", String.class),
                        "",
                        row.get("timeout_ms", Integer.class),
                        row.get("upstream_model", String.class));
        ChannelRouter.RouteCandidate candidate =
                new ChannelRouter.RouteCandidate(
                        channelId,
                        channel.providerType(),
                        modelAlias,
                        channel.upstreamModel(),
                        capabilities,
                        row.get("route_priority", Integer.class),
                        row.get("route_weight", Integer.class),
                        true,
                        true,
                        circuits.isAvailable(channelId, modelAlias),
                        true,
                        true);
        int channelRpm = row.get("channel_rpm", Integer.class);
        int channelConcurrency = row.get("channel_concurrency", Integer.class);
        return new CatalogRow(
                row.get("public_model_id", UUID.class),
                row.get("routing_model_id", UUID.class),
                row.get("max_output_tokens", Integer.class),
                row.get("package_max_output_tokens", Integer.class),
                dailyLimit(row),
                tokenLimit(row),
                row.get("package_rpm", Integer.class),
                row.get("package_concurrency", Integer.class),
                row.get("key_rpm", Integer.class),
                row.get("key_concurrency", Integer.class),
                row.get("user_rpm", Integer.class),
                row.get("user_concurrency", Integer.class),
                row.get("model_rpm", Integer.class),
                row.get("model_concurrency", Integer.class),
                channelRpm,
                channelConcurrency,
                new ChatCompletionService.RouteTarget(
                        candidate,
                        channel,
                        channelRpm,
                        channelConcurrency,
                        encryptedCredential(encrypted, credentialCipher)));
    }

    static ChatCompletionService.ChannelCredential encryptedCredential(
            byte[] encrypted, CredentialCipher credentialCipher) {
        if (encrypted == null) {
            return () -> "";
        }
        byte[] ciphertext = encrypted.clone();
        return () -> credentialCipher.decrypt(ciphertext);
    }

    private ChatCompletionService.PreparedRequest prepared(
            List<CatalogRow> rows, String publicModelAlias, String routingModelAlias) {
        CatalogRow first = rows.getFirst();
        int channelRpm = rows.stream().mapToInt(CatalogRow::channelRpm).min().orElseThrow();
        int channelConcurrency =
                rows.stream().mapToInt(CatalogRow::channelConcurrency).min().orElseThrow();
        return new ChatCompletionService.PreparedRequest(
                first.publicModelId(),
                publicModelAlias,
                first.routingModelId(),
                routingModelAlias,
                first.maxOutputTokens(),
                new RequestLimitService.Thresholds(
                        first.packageRpm(),
                        first.keyRpm(),
                        first.userRpm(),
                        channelRpm,
                        first.modelRpm()),
                new RequestLimitService.Thresholds(
                        first.packageConcurrency(),
                        first.keyConcurrency(),
                        first.userConcurrency(),
                        channelConcurrency,
                        first.modelConcurrency()),
                first.dailyLimit(),
                first.tokenLimit(),
                rows.stream().map(CatalogRow::target).toList(),
                first.packageMaxOutputTokens());
    }

    private RequestLimitService.PackageDailyLimit dailyLimit(io.r2dbc.spi.Row row) {
        return RequestLimitService.PackageDailyLimit.of(
                row.get("package_instance_id", UUID.class),
                row.get("package_daily_request_limit", Integer.class));
    }

    /** 套餐为 TOKEN 套餐售出的每分钟速率。VIDEO_SECOND 套餐会被 上面的对话目录拒绝，并走自己的视频准入路径， 因此秒数绝不会在这里被解释为每分钟信用速率。 */
    private RequestLimitService.PackageTokenLimit tokenLimit(io.r2dbc.spi.Row row) {
        return PackageRateLimits.of(
                        row.get("package_instance_id", UUID.class),
                        row.get("package_usage_dimension", String.class),
                        row.get("package_tokens_per_minute", Long.class))
                .tokenLimit();
    }

    private Set<ChannelRouter.Capability> capabilities(String json) {
        try {
            Set<ChannelRouter.Capability> result = new LinkedHashSet<>();
            objectMapper
                    .readTree(json)
                    .forEach(value -> result.add(ChannelRouter.Capability.valueOf(value.asText())));
            return Set.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "channel_capabilities_deserialization_failed", exception);
        }
    }

    private byte[] bytes(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof ByteBuffer buffer) {
            ByteBuffer copy = buffer.duplicate();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return bytes;
        }
        throw new IllegalStateException("unsupported_credential_storage_type");
    }

    private record CatalogRow(
            UUID publicModelId,
            UUID routingModelId,
            int maxOutputTokens,
            Integer packageMaxOutputTokens,
            RequestLimitService.PackageDailyLimit dailyLimit,
            RequestLimitService.PackageTokenLimit tokenLimit,
            int packageRpm,
            int packageConcurrency,
            int keyRpm,
            int keyConcurrency,
            int userRpm,
            int userConcurrency,
            int modelRpm,
            int modelConcurrency,
            int channelRpm,
            int channelConcurrency,
            ChatCompletionService.RouteTarget target) {}
}
