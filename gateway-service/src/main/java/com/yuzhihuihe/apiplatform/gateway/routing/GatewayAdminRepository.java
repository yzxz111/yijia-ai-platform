package com.yuzhihuihe.apiplatform.gateway.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.AdminListQuery;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateChannelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateModelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.ModelRouteConfiguration;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.UpdateChannelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.UpdateModelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ModelRouteView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ModelView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.PageResult;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ProviderView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.RoutingTargetView;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class GatewayAdminRepository {

    private static final Map<String, Boolean> LIST_STATUSES =
            Map.of("ENABLED", true, "DISABLED", false);
    private static final Map<String, String> MODEL_SORTS =
            Map.of(
                    "code", "model.code",
                    "displayName", "model.display_name",
                    "contextWindow", "model.context_window",
                    "maxOutputTokens", "model.max_output_tokens",
                    "requestsPerMinute", "model.requests_per_minute",
                    "maxConcurrency", "model.max_concurrency",
                    "enabled", "model.enabled");
    private static final Map<String, String> CHANNEL_SORTS =
            Map.ofEntries(
                    Map.entry("code", "channel.code"),
                    Map.entry("name", "channel.name"),
                    Map.entry("provider", "provider.code"),
                    Map.entry("environment", "channel.environment"),
                    Map.entry("priority", "channel.priority"),
                    Map.entry("weight", "channel.weight"),
                    Map.entry("timeoutMs", "channel.timeout_ms"),
                    Map.entry("requestsPerMinute", "channel.requests_per_minute"),
                    Map.entry("maxConcurrency", "channel.max_concurrency"),
                    Map.entry("enabled", "channel.enabled"),
                    Map.entry("lastHealthCheckedAt", "channel.last_health_checked_at"));

    /**
     * 限流列的兜底值，与客户端路径的最终兜底一致：{@code DatabaseGatewayCatalog} 的 COALESCE 链末尾写的就是 60 与
     * 10。它只在"这一层没设限、也没有任何一层设限" 时才会成为有效值。
     */
    private static final int FALLBACK_REQUESTS_PER_MINUTE = 60;

    private static final int FALLBACK_MAX_CONCURRENCY = 10;

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GatewayAdminRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Mono<PageResult<ModelView>> listModels(AdminListQuery request) {
        PageRequest page = page(request.page(), request.size());
        String statusPredicate = modelStatusPredicate(request.status());
        String search = search(request.query());
        String from =
                """
                FROM gateway.model model
                WHERE (
                """
                        + statusPredicate
                        + """
                ) AND (:search IS NULL
                       OR lower(model.code) LIKE :search ESCAPE '!'
                       OR lower(model.display_name) LIKE :search ESCAPE '!'
                       OR EXISTS (SELECT 1 FROM gateway.model_alias model_alias
                                  WHERE model_alias.model_id = model.id
                                    AND lower(model_alias.alias) LIKE :search ESCAPE '!'))
                """;
        String ordering =
                order(
                        request,
                        MODEL_SORTS,
                        "code",
                        "ASC",
                        " ORDER BY model.code ASC, model.id ASC",
                        "model.id");
        DatabaseClient.GenericExecuteSpec items =
                databaseClient
                        .sql(modelSelect() + from + ordering + " LIMIT :limit OFFSET :offset")
                        .bind("limit", page.size())
                        .bind("offset", page.offset());
        DatabaseClient.GenericExecuteSpec count = databaseClient.sql("SELECT count(*) " + from);
        items = bindNullable(items, "search", search, String.class);
        count = bindNullable(count, "search", search, String.class);
        Mono<List<ModelView>> rows =
                items.map((row, metadata) -> modelView(row)).all().collectList();
        return page(rows, count, page);
    }

    Flux<ProviderView> listProviders() {
        return databaseClient
                .sql(
                        """
                        SELECT id, code, name, provider_type, base_url, enabled
                        FROM gateway.provider ORDER BY code
                        """)
                .map(
                        (row, metadata) ->
                                new ProviderView(
                                        row.get("id", UUID.class),
                                        row.get("code", String.class),
                                        row.get("name", String.class),
                                        row.get("provider_type", String.class),
                                        row.get("base_url", String.class),
                                        Boolean.TRUE.equals(row.get("enabled", Boolean.class))))
                .all();
    }

    Mono<ProviderView> provider(UUID id) {
        return databaseClient
                .sql(
                        """
                        SELECT id, code, name, provider_type, base_url, enabled
                        FROM gateway.provider WHERE id = :id
                        """)
                .bind("id", id)
                .map(
                        (row, metadata) ->
                                new ProviderView(
                                        row.get("id", UUID.class),
                                        row.get("code", String.class),
                                        row.get("name", String.class),
                                        row.get("provider_type", String.class),
                                        row.get("base_url", String.class),
                                        Boolean.TRUE.equals(row.get("enabled", Boolean.class))))
                .one();
    }

    /**
     * 该服务商被用量、成本与生成任务引用了几行。
     *
     * <p>大于 0 时删除必须被拒绝：这些行是计费与审计的凭据，删掉服务商等于把历史变成 孤儿。正确处置是**停用**（{@code enabled=false}），而不是删除。
     *
     * <p>这里**不排除**扇出请求的父锚点行。锚点是 2026-10-15 加的（见 {@code
     * ImageGenerationService.persistFanoutAnchor}），它确实会让每个扇出请求
     * 多计一行；但那只会让计数更保守，而不会改变结论：一次扇出既写出锚点，也必然写出 引用同一服务商的 N 行子任务，所以"是否大于 0"在加锚点前后完全一致。
     * 这条查询只看是否为零，不看具体数值。
     */
    Mono<Long> providerUsageReferences(UUID providerId) {
        return databaseClient
                .sql(
                        """
                        SELECT (SELECT count(*) FROM gateway.usage_stage WHERE provider_id = :id)
                             + (SELECT count(*) FROM gateway.usage_log WHERE provider_id = :id)
                             + (SELECT count(*) FROM gateway.provider_cost_record WHERE provider_id = :id)
                             + (SELECT count(*) FROM gateway.image_generation_task WHERE provider_id = :id)
                             + (SELECT count(*) FROM gateway.video_generation_task WHERE provider_id = :id)
                             AS rows
                        """)
                .bind("id", providerId)
                .map((row, metadata) -> row.get("rows", Long.class))
                .one()
                .map(count -> count == null ? 0L : count);
    }

    /** 该服务商名下的渠道 id。 */
    Flux<UUID> channelIdsOfProvider(UUID providerId) {
        return databaseClient
                .sql("SELECT id FROM gateway.channel WHERE provider_id = :id")
                .bind("id", providerId)
                .map((row, metadata) -> row.get("id", UUID.class))
                .all();
    }

    /** 这些渠道上有多少条别名映射（删除前统计，用于回报删掉了什么）。 */
    Mono<Long> countChannelModels(List<UUID> channelIds) {
        if (channelIds.isEmpty()) {
            return Mono.just(0L);
        }
        var spec =
                databaseClient.sql(
                        "SELECT count(*) AS rows FROM gateway.channel_model WHERE channel_id IN ("
                                + uuidBinds(channelIds, "channel")
                                + ")");
        spec = bindUuids(spec, channelIds, "channel");
        return spec.map((row, metadata) -> row.get("rows", Long.class))
                .one()
                .map(count -> count == null ? 0L : count);
    }

    /**
     * 删除挂在指定渠道上的配置行。
     *
     * <p>除了别名映射，还有三张以 channel_id 外键指向渠道的表（供应商文件绑定、路由覆盖、 限流兜底租约）。它们都是配置而非历史，因此随渠道一起删除；不先删它们，外键会让
     * 整个删除失败，运营只会看到一个语焉不详的 500。
     */
    Mono<Long> deleteChannelScopedRows(List<UUID> channelIds) {
        if (channelIds.isEmpty()) {
            return Mono.just(0L);
        }
        long[] deleted = new long[1];
        Mono<Void> sequence = Mono.empty();
        for (String table :
                List.of(
                        "gateway.channel_model",
                        "gateway.provider_file_binding",
                        "gateway.routing_override",
                        "gateway.request_limit_fallback_lease")) {
            sequence =
                    sequence.then(
                            Mono.defer(
                                    () -> {
                                        var spec =
                                                databaseClient.sql(
                                                        "DELETE FROM "
                                                                + table
                                                                + " WHERE channel_id IN ("
                                                                + uuidBinds(channelIds, "channel")
                                                                + ")");
                                        spec = bindUuids(spec, channelIds, "channel");
                                        return spec.fetch()
                                                .rowsUpdated()
                                                .doOnNext(rows -> deleted[0] += rows)
                                                .then();
                                    }));
        }
        return sequence.then(Mono.fromSupplier(() -> deleted[0]));
    }

    Mono<Long> deleteChannelsOfProvider(UUID providerId) {
        return databaseClient
                .sql("DELETE FROM gateway.channel WHERE provider_id = :id")
                .bind("id", providerId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> deleteProviderPrices(UUID providerId) {
        return databaseClient
                .sql("DELETE FROM gateway.provider_model_price WHERE provider_id = :id")
                .bind("id", providerId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> deleteProvider(UUID providerId) {
        return databaseClient
                .sql("DELETE FROM gateway.provider WHERE id = :id")
                .bind("id", providerId)
                .fetch()
                .rowsUpdated();
    }

    private static String uuidBinds(List<UUID> ids, String prefix) {
        StringBuilder binds = new StringBuilder();
        for (int index = 0; index < ids.size(); index++) {
            if (index > 0) {
                binds.append(", ");
            }
            binds.append(':').append(prefix).append(index);
        }
        return binds.toString();
    }

    private static DatabaseClient.GenericExecuteSpec bindUuids(
            DatabaseClient.GenericExecuteSpec spec, List<UUID> ids, String prefix) {
        var bound = spec;
        for (int index = 0; index < ids.size(); index++) {
            bound = bound.bind(prefix + index, ids.get(index));
        }
        return bound;
    }

    Mono<Long> insertProvider(ProviderView provider) {
        return databaseClient
                .sql(
                        """
                        INSERT INTO gateway.provider(id, code, name, provider_type, base_url, enabled)
                        VALUES (:id, :code, :name, :provider_type, :base_url, :enabled)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .bind("id", provider.id())
                .bind("code", provider.code())
                .bind("name", provider.name())
                .bind("provider_type", provider.providerType())
                .bind("base_url", provider.baseUrl())
                .bind("enabled", provider.enabled())
                .fetch()
                .rowsUpdated();
    }

    Mono<Void> insertModel(UUID id, CreateModelCommand command, Set<String> aliases) {
        Mono<Void> insertModel =
                databaseClient
                        .sql(
                                """
                                INSERT INTO gateway.model(
                                    id, code, display_name, capabilities, context_window,
                                    max_output_tokens, premium, enabled, requests_per_minute, max_concurrency)
                                VALUES (:id, :code, :display_name, :capabilities::jsonb, :context_window,
                                        :max_output_tokens, :premium, :enabled, :rpm, :concurrency)
                                """)
                        .bind("id", id)
                        .bind("code", command.code())
                        .bind("display_name", command.displayName())
                        .bind("capabilities", json(command.capabilities()))
                        .bind("context_window", command.contextWindow())
                        .bind("max_output_tokens", command.maxOutputTokens())
                        .bind("premium", command.premium())
                        .bind("enabled", command.enabled())
                        .bind("rpm", command.requestsPerMinute())
                        .bind("concurrency", command.maxConcurrency())
                        .fetch()
                        .rowsUpdated()
                        .then();
        Mono<Void> insertAliases =
                Flux.fromIterable(aliases).concatMap(alias -> insertAlias(id, alias)).then();
        return insertModel.then(insertAliases);
    }

    Mono<Void> updateModel(UpdateModelCommand command, String canonicalCode, Set<String> aliases) {
        Mono<Void> update =
                databaseClient
                        .sql(
                                """
                                UPDATE gateway.model
                                SET display_name = :display_name, capabilities = :capabilities::jsonb,
                                    context_window = :context_window, max_output_tokens = :max_output_tokens,
                                    premium = :premium, enabled = :enabled,
                                    requests_per_minute = :rpm, max_concurrency = :concurrency,
                                    updated_at = now()
                                WHERE id = :id
                                """)
                        .bind("id", command.id())
                        .bind("display_name", command.displayName())
                        .bind("capabilities", json(command.capabilities()))
                        .bind("context_window", command.contextWindow())
                        .bind("max_output_tokens", command.maxOutputTokens())
                        .bind("premium", command.premium())
                        .bind("enabled", command.enabled())
                        .bind("rpm", command.requestsPerMinute())
                        .bind("concurrency", command.maxConcurrency())
                        .fetch()
                        .rowsUpdated()
                        .filter(updated -> updated == 1L)
                        .switchIfEmpty(Mono.error(notFound("model_not_found")))
                        .then();
        Mono<Void> replaceAliases =
                aliases == null
                        ? Mono.empty()
                        : databaseClient
                                .sql(
                                        """
                                        DELETE FROM gateway.model_alias
                                        WHERE model_id = :id AND alias <> :canonical
                                        """)
                                .bind("id", command.id())
                                .bind("canonical", canonicalCode)
                                .fetch()
                                .rowsUpdated()
                                .then()
                                .thenMany(
                                        Flux.fromIterable(aliases)
                                                .filter(alias -> !alias.equals(canonicalCode))
                                                .concatMap(
                                                        alias -> insertAlias(command.id(), alias)))
                                .then();
        return update.then(replaceAliases);
    }

    Mono<Void> updateModelEnabled(UUID id, boolean enabled) {
        return databaseClient
                .sql(
                        """
                        UPDATE gateway.model
                        SET enabled = :enabled, updated_at = now()
                        WHERE id = :id
                        """)
                .bind("id", id)
                .bind("enabled", enabled)
                .fetch()
                .rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(notFound("model_not_found")))
                .then();
    }

    Mono<Void> deleteModel(UUID id) {
        return databaseClient
                .sql("DELETE FROM gateway.model WHERE id = :id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(notFound("model_not_found")))
                .then();
    }

    Mono<Long> countCompositeModelReferences(UUID id) {
        return databaseClient
                .sql(
                        """
                        SELECT count(*)
                        FROM gateway.model referencing_model
                        WHERE referencing_model.routing_strategy = 'COMPOSITE'
                          AND EXISTS (
                              SELECT 1
                              FROM gateway.model_alias referenced_alias
                              WHERE referenced_alias.model_id = :id
                                AND referenced_alias.alias IN (
                                    referencing_model.routing_policy ->> 'textModel',
                                    referencing_model.routing_policy ->> 'imageModel',
                                    referencing_model.routing_policy ->> 'fileModel',
                                    referencing_model.routing_policy ->> 'mixedFinalModel'))
                        """)
                .bind("id", id)
                .map((row, metadata) -> row.get(0, Long.class))
                .one();
    }

    Mono<PageResult<ChannelView>> listChannels(AdminListQuery request) {
        PageRequest page = page(request.page(), request.size());
        Boolean enabled = enabledFilter(request.status(), "invalid_channel_status");
        String search = search(request.query());
        String from =
                """
                FROM gateway.channel channel
                JOIN gateway.provider provider ON provider.id = channel.provider_id
                WHERE (:enabled IS NULL OR channel.enabled = :enabled)
                  AND (:search IS NULL
                       OR lower(channel.code) LIKE :search ESCAPE '!'
                       OR lower(channel.name) LIKE :search ESCAPE '!'
                       OR lower(channel.environment) LIKE :search ESCAPE '!'
                       OR lower(channel.base_url) LIKE :search ESCAPE '!'
                       OR lower(provider.code) LIKE :search ESCAPE '!'
                       OR lower(provider.name) LIKE :search ESCAPE '!')
                """;
        String ordering =
                order(
                        request,
                        CHANNEL_SORTS,
                        "priority",
                        "DESC",
                        " ORDER BY channel.priority DESC, channel.code ASC, channel.id ASC",
                        "channel.id");
        DatabaseClient.GenericExecuteSpec items =
                databaseClient
                        .sql(channelSelect() + from + ordering + " LIMIT :limit OFFSET :offset")
                        .bind("limit", page.size())
                        .bind("offset", page.offset());
        DatabaseClient.GenericExecuteSpec count = databaseClient.sql("SELECT count(*) " + from);
        items = bindNullable(items, "enabled", enabled, Boolean.class);
        items = bindNullable(items, "search", search, String.class);
        count = bindNullable(count, "enabled", enabled, Boolean.class);
        count = bindNullable(count, "search", search, String.class);
        Mono<List<ChannelView>> rows =
                items.map((row, metadata) -> channelView(row)).all().collectList();
        return page(rows, count, page);
    }

    Flux<ChannelView> listAllChannels() {
        return databaseClient
                .sql(
                        channelSelect()
                                + """
                                FROM gateway.channel channel
                                ORDER BY channel.priority DESC, channel.code ASC, channel.id ASC
                                """)
                .map((row, metadata) -> channelView(row))
                .all();
    }

    Mono<Boolean> providerExists(UUID id) {
        return databaseClient
                .sql("SELECT EXISTS (SELECT 1 FROM gateway.provider WHERE id = :id) AS present")
                .bind("id", id)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("present", Boolean.class)))
                .one();
    }

    Mono<Long> insertChannel(UUID id, CreateChannelCommand command, byte[] encryptedCredential) {
        return databaseClient
                .sql(
                        """
                        INSERT INTO gateway.channel(
                            id, provider_id, code, name, environment, base_url, encrypted_api_key,
                            enabled, priority, weight, timeout_ms, requests_per_minute, max_concurrency)
                        VALUES (:id, :provider_id, :code, :name, :environment, :base_url, :credential,
                                :enabled, :priority, :weight, :timeout_ms, :rpm, :concurrency)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .bind("id", id)
                .bind("provider_id", command.providerId())
                .bind("code", command.code())
                .bind("name", command.name())
                .bind("environment", command.environment())
                .bind("base_url", command.baseUrl())
                .bind("credential", encryptedCredential)
                .bind("enabled", command.enabled())
                .bind("priority", command.priority())
                .bind("weight", command.weight())
                .bind("timeout_ms", command.timeoutMs())
                .bind("rpm", command.requestsPerMinute())
                .bind("concurrency", command.maxConcurrency())
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> updateChannel(UpdateChannelCommand command, byte[] encryptedCredential) {
        DatabaseClient.GenericExecuteSpec spec =
                databaseClient
                        .sql(
                                """
                                UPDATE gateway.channel
                                SET name = :name, base_url = :base_url, enabled = :enabled,
                                    priority = :priority, weight = :weight, timeout_ms = :timeout_ms,
                                    requests_per_minute = :rpm, max_concurrency = :concurrency,
                                    encrypted_api_key = COALESCE(:credential, encrypted_api_key),
                                    last_health_status = 'UNKNOWN', last_health_checked_at = NULL,
                                    updated_at = clock_timestamp()
                                WHERE id = :id
                                """)
                        .bind("id", command.id())
                        .bind("name", command.name())
                        .bind("base_url", command.baseUrl())
                        .bind("enabled", command.enabled())
                        .bind("priority", command.priority())
                        .bind("weight", command.weight())
                        .bind("timeout_ms", command.timeoutMs())
                        .bind("rpm", command.requestsPerMinute())
                        .bind("concurrency", command.maxConcurrency());
        spec =
                encryptedCredential == null
                        ? spec.bindNull("credential", byte[].class)
                        : spec.bind("credential", encryptedCredential);
        return spec.fetch().rowsUpdated();
    }

    Mono<Long> updateChannelConfiguration(
            ModelRouteConfiguration configuration, byte[] encryptedCredential) {
        DatabaseClient.GenericExecuteSpec spec =
                databaseClient
                        .sql(
                                """
                                UPDATE gateway.channel
                                SET provider_id = :provider_id, environment = :environment,
                                    base_url = :base_url, enabled = :enabled,
                                    timeout_ms = :timeout_ms, requests_per_minute = :rpm,
                                    max_concurrency = :concurrency,
                                    encrypted_api_key = COALESCE(:credential, encrypted_api_key),
                                    last_health_status = 'UNKNOWN', last_health_checked_at = NULL,
                                    updated_at = clock_timestamp()
                                WHERE id = :id
                                """)
                        .bind("id", configuration.channelId())
                        .bind("provider_id", configuration.providerId())
                        .bind("environment", configuration.environment())
                        .bind("base_url", configuration.baseUrl())
                        .bind("enabled", configuration.enabled())
                        .bind("timeout_ms", configuration.timeoutMs())
                        .bind("rpm", configuration.requestsPerMinute())
                        .bind("concurrency", configuration.maxConcurrency());
        spec =
                encryptedCredential == null
                        ? spec.bindNull("credential", byte[].class)
                        : spec.bind("credential", encryptedCredential);
        return spec.fetch().rowsUpdated();
    }

    Mono<Long> upsertModelRoute(
            UUID mappingId, UUID channelId, UUID modelId, ModelRouteConfiguration configuration) {
        return databaseClient
                .sql(
                        """
                        INSERT INTO gateway.channel_model(
                            id, channel_id, model_id, upstream_model, enabled,
                            priority_override, weight_override, capabilities)
                        SELECT :id, :channel_id, model.id, :upstream_model, :enabled,
                               :priority, :weight, model.capabilities
                        FROM gateway.model model WHERE model.id = :model_id
                        ON CONFLICT (channel_id, model_id) DO UPDATE
                        SET upstream_model = EXCLUDED.upstream_model,
                            enabled = EXCLUDED.enabled,
                            priority_override = EXCLUDED.priority_override,
                            weight_override = EXCLUDED.weight_override,
                            capabilities = EXCLUDED.capabilities,
                            updated_at = now()
                        """)
                .bind("id", mappingId)
                .bind("channel_id", channelId)
                .bind("model_id", modelId)
                .bind("upstream_model", configuration.upstreamModel())
                .bind("enabled", configuration.enabled())
                .bind("priority", configuration.priority())
                .bind("weight", configuration.weight())
                .fetch()
                .rowsUpdated();
    }

    Mono<Void> reconcileProviderRouting() {
        Mono<Void> realProviders =
                databaseClient
                        .sql(
                                """
                                UPDATE gateway.provider AS real_provider
                                SET enabled = EXISTS (
                                        SELECT 1
                                        FROM gateway.channel AS real_channel
                                        WHERE real_channel.provider_id = real_provider.id
                                          AND real_channel.enabled
                                          AND real_channel.encrypted_api_key IS NOT NULL
                                          AND octet_length(real_channel.encrypted_api_key) > 0
                                    ),
                                    updated_at = now()
                                WHERE real_provider.provider_type IN ('DEEPSEEK', 'BAILIAN')
                                """)
                        .fetch()
                        .rowsUpdated()
                        .then();
        Mono<Void> mockChannels =
                databaseClient
                        .sql(
                                """
                                UPDATE gateway.channel AS mock_channel
                                SET enabled = NOT EXISTS (
                                        SELECT 1
                                        FROM gateway.provider AS real_provider
                                        JOIN gateway.channel AS real_channel
                                          ON real_channel.provider_id = real_provider.id
                                        WHERE real_provider.provider_type IN ('DEEPSEEK', 'BAILIAN')
                                          AND real_provider.enabled
                                          AND real_channel.enabled
                                          AND real_channel.encrypted_api_key IS NOT NULL
                                          AND octet_length(real_channel.encrypted_api_key) > 0
                                    ),
                                    updated_at = now()
                                FROM gateway.provider AS mock_provider
                                WHERE mock_provider.id = mock_channel.provider_id
                                  AND mock_provider.code = 'local-mock'
                                """)
                        .fetch()
                        .rowsUpdated()
                        .then();
        Mono<Void> mockProvider =
                databaseClient
                        .sql(
                                """
                                UPDATE gateway.provider AS mock_provider
                                SET enabled = NOT EXISTS (
                                        SELECT 1
                                        FROM gateway.provider AS real_provider
                                        JOIN gateway.channel AS real_channel
                                          ON real_channel.provider_id = real_provider.id
                                        WHERE real_provider.provider_type IN ('DEEPSEEK', 'BAILIAN')
                                          AND real_provider.enabled
                                          AND real_channel.enabled
                                          AND real_channel.encrypted_api_key IS NOT NULL
                                          AND octet_length(real_channel.encrypted_api_key) > 0
                                    ),
                                    updated_at = now()
                                WHERE mock_provider.code = 'local-mock'
                                """)
                        .fetch()
                        .rowsUpdated()
                        .then();
        return realProviders.then(mockChannels).then(mockProvider);
    }

    Mono<ModelView> model(UUID id) {
        return databaseClient
                .sql(modelSelect() + " FROM gateway.model model WHERE model.id = :id")
                .bind("id", id)
                .map((row, metadata) -> modelView(row))
                .one();
    }

    Mono<UUID> modelId(String code) {
        return databaseClient
                .sql("SELECT id FROM gateway.model WHERE code = :code")
                .bind("code", code)
                .map((row, metadata) -> row.get("id", UUID.class))
                .one();
    }

    Mono<ChannelView> channel(UUID id) {
        return databaseClient
                .sql(
                        """
                        SELECT id, provider_id, code, name, environment, base_url, enabled,
                               priority, weight, timeout_ms, requests_per_minute, max_concurrency,
                               last_health_status, encrypted_api_key IS NOT NULL AS credential_configured
                        FROM gateway.channel WHERE id = :id
                        """)
                .bind("id", id)
                .map((row, metadata) -> channelView(row))
                .one();
    }

    Flux<RoutingTargetView> routingTargets(String routingModelAlias) {
        return databaseClient
                .sql(
                        """
                        SELECT channel.id AS channel_id, channel.code AS channel_code,
                               channel.name AS channel_name, provider.name AS provider_name,
                               mapping.upstream_model, channel.enabled AS channel_enabled,
                               mapping.enabled AS mapping_enabled, channel.last_health_status,
                               channel.encrypted_api_key IS NOT NULL
                                   AND octet_length(channel.encrypted_api_key) > 0 AS credential_configured,
                               override.channel_id IS NOT NULL AS selected,
                               (channel.enabled AND provider.enabled AND mapping.enabled
                                AND channel.last_health_status = 'HEALTHY'
                                AND channel.encrypted_api_key IS NOT NULL
                                AND octet_length(channel.encrypted_api_key) > 0) AS eligible
                        FROM gateway.model_alias routing_alias
                        JOIN gateway.model routing_model ON routing_model.id = routing_alias.model_id
                         AND routing_model.enabled
                        JOIN gateway.channel_model mapping ON mapping.model_id = routing_model.id
                        JOIN gateway.channel channel ON channel.id = mapping.channel_id
                        JOIN gateway.provider provider ON provider.id = channel.provider_id
                        LEFT JOIN gateway.routing_override override
                          ON override.routing_model_alias = routing_alias.alias
                         AND override.channel_id = channel.id
                        WHERE routing_alias.alias = :routing_model_alias
                        ORDER BY selected DESC, eligible DESC, channel.priority DESC,
                                 channel.code ASC, channel.id ASC
                        """)
                .bind("routing_model_alias", routingModelAlias)
                .map((row, metadata) -> routingTarget(row))
                .all();
    }

    Mono<RoutingTargetView> switchRoute(
            String routingModelAlias, UUID channelId, UUID actorUserId) {
        return databaseClient
                .sql(
                        """
                        INSERT INTO gateway.routing_override(routing_model_alias, channel_id, actor_user_id)
                        SELECT :routing_model_alias, channel.id, :actor_user_id
                        FROM gateway.model_alias routing_alias
                        JOIN gateway.model routing_model ON routing_model.id = routing_alias.model_id
                         AND routing_model.enabled
                        JOIN gateway.channel_model mapping ON mapping.model_id = routing_model.id
                         AND mapping.channel_id = :channel_id AND mapping.enabled
                        JOIN gateway.channel channel ON channel.id = mapping.channel_id
                         AND channel.enabled AND channel.last_health_status = 'HEALTHY'
                         AND channel.encrypted_api_key IS NOT NULL
                         AND octet_length(channel.encrypted_api_key) > 0
                        JOIN gateway.provider provider ON provider.id = channel.provider_id
                         AND provider.enabled
                        WHERE routing_alias.alias = :routing_model_alias
                        ON CONFLICT (routing_model_alias) DO UPDATE
                        SET channel_id = EXCLUDED.channel_id,
                            actor_user_id = EXCLUDED.actor_user_id,
                            updated_at = now()
                        RETURNING routing_model_alias
                        """)
                .bind("routing_model_alias", routingModelAlias)
                .bind("channel_id", channelId)
                .bind("actor_user_id", actorUserId)
                .map((row, metadata) -> row.get("routing_model_alias", String.class))
                .one()
                .flatMapMany(this::routingTargets)
                .filter(target -> target.channelId().equals(channelId) && target.selected())
                .next()
                .switchIfEmpty(Mono.error(invalid("routing_target_unavailable")));
    }

    Mono<RoutingTargetView> selectedRoute(String routingModelAlias) {
        return routingTargets(routingModelAlias).filter(RoutingTargetView::selected).next();
    }

    Mono<Boolean> clearRoute(String routingModelAlias) {
        return databaseClient
                .sql(
                        "DELETE FROM gateway.routing_override WHERE routing_model_alias = :routing_model_alias")
                .bind("routing_model_alias", routingModelAlias)
                .fetch()
                .rowsUpdated()
                .map(updated -> updated > 0);
    }

    Mono<ChannelTestConfig> channelTestConfig(UUID id) {
        return databaseClient
                .sql(
                        """
                        SELECT base_url, environment, timeout_ms, updated_at,
                               enabled AND encrypted_api_key IS NOT NULL
                               AND base_url <> '' AS configured
                        FROM gateway.channel WHERE id = :id
                        """)
                .bind("id", id)
                .map(
                        (row, metadata) ->
                                new ChannelTestConfig(
                                        row.get("base_url", String.class),
                                        row.get("environment", String.class),
                                        row.get("timeout_ms", Integer.class),
                                        Boolean.TRUE.equals(row.get("configured", Boolean.class)),
                                        row.get("updated_at", OffsetDateTime.class)))
                .one();
    }

    Mono<Long> updateChannelHealth(UUID id, String status, OffsetDateTime observedUpdatedAt) {
        return databaseClient
                .sql(
                        """
                        UPDATE gateway.channel
                        SET last_health_status = :status, last_health_checked_at = now(),
                            updated_at = clock_timestamp()
                        WHERE id = :id AND updated_at = :observed_updated_at
                        """)
                .bind("id", id)
                .bind("status", status)
                .bind("observed_updated_at", observedUpdatedAt)
                .fetch()
                .rowsUpdated();
    }

    /**
     * 该渠道上登记过的「别名 ← 上游模型」映射，供真实模型调用测试选择目标。
     *
     * <p>刻意把未启用的映射与模型也读出来（{@code probeable} 为假而不是过滤掉）： 运营点「测试模型」时最常见的困惑正是「这条映射存在但被停用了」，直接看不见它
     * 会把人引向「是不是没配对」的错误结论。
     *
     * <p><b>{@code text_capable} 问的是「能不能用 {@code /chat/completions} 探它」， 而不是「它的能力表里有没有 {@code
     * TEXT}」。</b>生图别名 {@code image-basic} 正带着 {@code ["IMAGE","TEXT","IMAGE_GEN"]}：那里的 {@code TEXT}
     * 描述的是它的**输入** （文本提示词），而它产出的是图片。只按 {@code TEXT} 判断会把它送进对话探测， 上游对「用对话端点调生图模型」回
     * 401/403，网关据此记成「密钥被上游拒绝」并把 整条渠道写成 {@code UNHEALTHY}——而生图路由要求 {@code HEALTHY}，一次误判就把
     * 整个生图能力堵死（2026-10-09 线上实测）。生成类与音频类能力因此在谓词里被排除； 只有非文本**输入**（{@code IMAGE}/{@code
     * FILE}）不算生成，照旧可以对话探测。
     */
    Flux<ChannelModelChoice> channelModels(UUID channelId) {
        return databaseClient
                .sql(
                        """
                        SELECT model.code AS model_code, model.display_name, mapping.upstream_model,
                               mapping.enabled AS mapping_enabled, model.enabled AS model_enabled,
                               (model.capabilities @> '["TEXT"]'::jsonb
                                AND NOT (model.capabilities @> '["VIDEO"]'::jsonb
                                         OR model.capabilities @> '["IMAGE_GEN"]'::jsonb
                                         OR model.capabilities @> '["LIP_SYNC"]'::jsonb
                                         OR model.capabilities @> '["AUDIO_TRANSCRIBE"]'::jsonb
                                         OR model.capabilities @> '["AUDIO_SYNTHESIS"]'::jsonb))
                                   AS text_capable
                        FROM gateway.channel_model mapping
                        JOIN gateway.model model ON model.id = mapping.model_id
                        WHERE mapping.channel_id = :channel_id
                        ORDER BY model.code, mapping.upstream_model
                        """)
                .bind("channel_id", channelId)
                .map(
                        (row, metadata) ->
                                new ChannelModelChoice(
                                        row.get("model_code", String.class),
                                        row.get("display_name", String.class),
                                        row.get("upstream_model", String.class),
                                        Boolean.TRUE.equals(
                                                row.get("mapping_enabled", Boolean.class)),
                                        Boolean.TRUE.equals(
                                                row.get("model_enabled", Boolean.class)),
                                        Boolean.TRUE.equals(
                                                row.get("text_capable", Boolean.class))))
                .all();
    }

    Mono<Void> audit(
            UUID actorUserId,
            String action,
            String resourceType,
            Object resourceId,
            Object before,
            Object after,
            Boolean credentialChanged) {
        ObjectNode detail = objectMapper.createObjectNode();
        if (before == null) {
            detail.putNull("before");
        } else {
            detail.set("before", objectMapper.valueToTree(before));
        }
        detail.set("after", objectMapper.valueToTree(after));
        if (credentialChanged != null) {
            detail.put("credentialChanged", credentialChanged);
        }
        return databaseClient
                .sql(
                        """
                        INSERT INTO gateway.admin_operation_audit(
                            operation_id, actor_user_id, action, status, affected,
                            completed_at, resource_type, resource_id, detail)
                        VALUES (:operation_id, :actor_user_id, :action, 'COMPLETED', 1,
                            now(), :resource_type, :resource_id, :detail::jsonb)
                        """)
                .bind("operation_id", UUID.randomUUID())
                .bind("actor_user_id", actorUserId)
                .bind("action", action)
                .bind("resource_type", resourceType)
                .bind("resource_id", resourceId.toString())
                .bind("detail", json(detail))
                .fetch()
                .rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("gateway_audit_not_written")))
                .then();
    }

    private Mono<Void> insertAlias(UUID modelId, String alias) {
        return databaseClient
                .sql(
                        """
                        INSERT INTO gateway.model_alias(id, model_id, alias)
                        VALUES (:id, :model_id, :alias)
                        """)
                .bind("id", UUID.randomUUID())
                .bind("model_id", modelId)
                .bind("alias", alias)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private String channelSelect() {
        return """
                SELECT channel.id, channel.provider_id, channel.code, channel.name,
                       channel.environment, channel.base_url, channel.enabled,
                       channel.priority, channel.weight, channel.timeout_ms,
                       channel.requests_per_minute, channel.max_concurrency,
                       channel.last_health_status,
                       channel.encrypted_api_key IS NOT NULL AS credential_configured
                """;
    }

    private PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("invalid_pagination");
        }
        try {
            return new PageRequest(page, size, Math.multiplyExact(page, size));
        } catch (ArithmeticException error) {
            throw invalid("invalid_pagination");
        }
    }

    private Boolean enabledFilter(String status, String reason) {
        if (blank(status)) {
            return null;
        }
        Boolean enabled = LIST_STATUSES.get(status.strip().toUpperCase(Locale.ROOT));
        if (enabled == null) {
            throw invalid(reason);
        }
        return enabled;
    }

    private String modelStatusPredicate(String status) {
        if (blank(status)) {
            return "TRUE";
        }
        return switch (status.strip().toUpperCase(Locale.ROOT)) {
            case "ENABLED" -> "model.enabled";
            case "DISABLED" -> "NOT model.enabled";
            case "NORMAL" -> "model.enabled AND " + eligibleModelRoutePredicate();
            case "ABNORMAL" -> "model.enabled AND NOT " + eligibleModelRoutePredicate();
            default -> throw invalid("invalid_model_status");
        };
    }

    private String eligibleModelRoutePredicate() {
        return """
                EXISTS (
                    SELECT 1
                    FROM gateway.model component_model
                    JOIN gateway.channel_model mapping
                      ON mapping.model_id = component_model.id
                     AND mapping.enabled
                     AND mapping.capabilities @> component_model.capabilities
                    JOIN gateway.channel channel
                      ON channel.id = mapping.channel_id
                     AND channel.enabled
                     AND channel.last_health_status = 'HEALTHY'
                     AND channel.encrypted_api_key IS NOT NULL
                     AND octet_length(channel.encrypted_api_key) > 0
                    JOIN gateway.provider provider
                      ON provider.id = channel.provider_id AND provider.enabled
                    WHERE component_model.enabled
                      AND ((model.routing_strategy = 'DIRECT' AND component_model.id = model.id)
                           OR (model.routing_strategy = 'COMPOSITE' AND EXISTS (
                               SELECT 1
                               FROM gateway.model_alias component_alias
                               WHERE component_alias.model_id = component_model.id
                                 AND component_alias.alias IN (
                                     model.routing_policy ->> 'textModel',
                                     model.routing_policy ->> 'imageModel',
                                     model.routing_policy ->> 'fileModel',
                                     model.routing_policy ->> 'mixedFinalModel'))))
                )
                """;
    }

    private String search(String query) {
        if (blank(query)) {
            return null;
        }
        String escaped =
                query.strip()
                        .toLowerCase(Locale.ROOT)
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private String order(
            AdminListQuery request,
            Map<String, String> allowed,
            String defaultSort,
            String defaultDirection,
            String defaultOrder,
            String tieBreaker) {
        boolean defaultRequest = blank(request.sort()) && blank(request.direction());
        String sort = blank(request.sort()) ? defaultSort : request.sort().strip();
        String column = allowed.get(sort);
        if (column == null) {
            throw invalid("invalid_sort");
        }
        String direction =
                blank(request.direction())
                        ? defaultDirection
                        : request.direction().strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ASC", "DESC").contains(direction)) {
            throw invalid("invalid_sort_direction");
        }
        return defaultRequest
                ? defaultOrder
                : " ORDER BY " + column + " " + direction + ", " + tieBreaker + " " + direction;
    }

    private <T> Mono<PageResult<T>> page(
            Mono<List<T>> rows, DatabaseClient.GenericExecuteSpec count, PageRequest page) {
        Mono<Long> total = count.map((row, metadata) -> row.get(0, Long.class)).one();
        return Mono.zip(rows, total)
                .map(
                        result ->
                                new PageResult<>(
                                        result.getT1(), result.getT2(), page.page(), page.size()));
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, Object value, Class<?> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private ModelView modelView(io.r2dbc.spi.Row row) {
        return new ModelView(
                row.get("id", UUID.class),
                row.get("code", String.class),
                row.get("display_name", String.class),
                stringSet(row.get("aliases", String.class)),
                stringSet(row.get("capabilities", String.class)),
                row.get("context_window", Integer.class),
                row.get("max_output_tokens", Integer.class),
                Boolean.TRUE.equals(row.get("premium", Boolean.class)),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                limitOr(row, "requests_per_minute", FALLBACK_REQUESTS_PER_MINUTE),
                limitOr(row, "max_concurrency", FALLBACK_MAX_CONCURRENCY),
                row.get("routing_strategy", String.class),
                modelRoutes(row.get("routes", String.class)));
    }

    private String modelSelect() {
        return """
                SELECT model.id, model.code, model.display_name,
                       model.capabilities::text AS capabilities,
                       COALESCE((SELECT jsonb_agg(model_alias.alias ORDER BY model_alias.alias)::text
                           FROM gateway.model_alias model_alias
                           WHERE model_alias.model_id = model.id), '[]') AS aliases,
                       context_window, max_output_tokens, premium, enabled,
                       requests_per_minute, max_concurrency, routing_strategy,
                       COALESCE((
                           SELECT jsonb_agg(
                               jsonb_build_object(
                                   'componentModelCode', route.component_model_code,
                                   'componentDisplayName', route.component_display_name,
                                   'providerId', route.provider_id,
                                   'providerCode', route.provider_code,
                                   'providerName', route.provider_name,
                                   'channelId', route.channel_id,
                                   'channelCode', route.channel_code,
                                   'channelName', route.channel_name,
                                   'upstreamModel', route.upstream_model,
                                   'environment', route.environment,
                                   'baseUrl', route.base_url,
                                   'enabled', route.enabled,
                                   'healthStatus', route.health_status,
                                   'credentialConfigured', route.credential_configured,
                                   'priority', route.priority,
                                   'weight', route.weight,
                                   'timeoutMs', route.timeout_ms,
                                   'requestsPerMinute', route.requests_per_minute,
                                   'maxConcurrency', route.max_concurrency,
                                   'eligible', route.eligible)
                               ORDER BY route.component_model_code, route.eligible DESC,
                                        route.priority DESC, route.weight DESC,
                                        route.provider_code, route.channel_code)
                           FROM (
                               SELECT component_model.code AS component_model_code,
                                      component_model.display_name AS component_display_name,
                                      provider.id AS provider_id, provider.code AS provider_code,
                                      provider.name AS provider_name,
                                      channel.id AS channel_id, channel.code AS channel_code,
                                      channel.name AS channel_name, mapping.upstream_model,
                                      channel.environment, channel.base_url,
                                      channel.timeout_ms, channel.requests_per_minute,
                                      channel.max_concurrency,
                                      (component_model.enabled AND provider.enabled
                                       AND channel.enabled AND mapping.enabled) AS enabled,
                                      channel.last_health_status AS health_status,
                                      channel.encrypted_api_key IS NOT NULL
                                          AND octet_length(channel.encrypted_api_key) > 0
                                          AS credential_configured,
                                      COALESCE(mapping.priority_override, channel.priority) AS priority,
                                      COALESCE(mapping.weight_override, channel.weight) AS weight,
                                      (component_model.enabled AND provider.enabled
                                       AND channel.enabled AND mapping.enabled
                                       AND mapping.capabilities @> component_model.capabilities
                                       AND channel.last_health_status = 'HEALTHY'
                                       AND channel.encrypted_api_key IS NOT NULL
                                       AND octet_length(channel.encrypted_api_key) > 0) AS eligible
                               FROM gateway.model component_model
                               JOIN gateway.channel_model mapping
                                 ON mapping.model_id = component_model.id
                               JOIN gateway.channel channel ON channel.id = mapping.channel_id
                               JOIN gateway.provider provider ON provider.id = channel.provider_id
                               WHERE (model.routing_strategy = 'DIRECT'
                                      AND component_model.id = model.id)
                                  OR (model.routing_strategy = 'COMPOSITE' AND EXISTS (
                                      SELECT 1
                                      FROM gateway.model_alias component_alias
                                      WHERE component_alias.model_id = component_model.id
                                        AND component_alias.alias IN (
                                            model.routing_policy ->> 'textModel',
                                            model.routing_policy ->> 'imageModel',
                                            model.routing_policy ->> 'fileModel',
                                            model.routing_policy ->> 'mixedFinalModel')))
                           ) route
                       ), '[]'::jsonb)::text AS routes
                """;
    }

    private ChannelView channelView(io.r2dbc.spi.Row row) {
        return new ChannelView(
                row.get("id", UUID.class),
                row.get("provider_id", UUID.class),
                row.get("code", String.class),
                row.get("name", String.class),
                row.get("environment", String.class),
                row.get("base_url", String.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                row.get("priority", Integer.class),
                row.get("weight", Integer.class),
                row.get("timeout_ms", Integer.class),
                limitOr(row, "requests_per_minute", FALLBACK_REQUESTS_PER_MINUTE),
                limitOr(row, "max_concurrency", FALLBACK_MAX_CONCURRENCY),
                row.get("last_health_status", String.class),
                Boolean.TRUE.equals(row.get("credential_configured", Boolean.class)));
    }

    private RoutingTargetView routingTarget(io.r2dbc.spi.Row row) {
        return new RoutingTargetView(
                row.get("channel_id", UUID.class),
                row.get("channel_code", String.class),
                row.get("channel_name", String.class),
                row.get("provider_name", String.class),
                row.get("upstream_model", String.class),
                Boolean.TRUE.equals(row.get("channel_enabled", Boolean.class)),
                Boolean.TRUE.equals(row.get("mapping_enabled", Boolean.class)),
                row.get("last_health_status", String.class),
                Boolean.TRUE.equals(row.get("credential_configured", Boolean.class)),
                Boolean.TRUE.equals(row.get("selected", Boolean.class)),
                Boolean.TRUE.equals(row.get("eligible", Boolean.class)));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("json_serialization_failed", exception);
        }
    }

    /**
     * 把 JSONB 字符串数组读成**有序**集合（字母序）。
     *
     * <p>不能用 {@code Set.copyOf}：它的迭代顺序未定义（OpenJDK 按进程启动时的 随机盐），而这两个字段（{@code capabilities}、{@code
     * aliases}）会被序列化成 JSON 数组交给控制台与客户端。顺序一旦可见就必须确定，否则同一条数据在 不同进程里返回不同顺序，客户端的相等比较/缓存与测试都会变成偶发。
     */
    private Set<String> stringSet(String json) {
        try {
            Set<String> values = new LinkedHashSet<>();
            objectMapper.readTree(json).forEach(node -> values.add(node.asText()));
            return Collections.unmodifiableSortedSet(new TreeSet<>(values));
        } catch (Exception exception) {
            throw new IllegalStateException("json_deserialization_failed", exception);
        }
    }

    private List<ModelRouteView> modelRoutes(String json) {
        try {
            return objectMapper.readerForListOf(ModelRouteView.class).readValue(json);
        } catch (Exception exception) {
            throw new IllegalStateException("json_deserialization_failed", exception);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 读一个可空的限流列，NULL 时取兜底值。
     *
     * <p>{@code gateway.model} 与 {@code gateway.channel} 的 {@code requests_per_minute} / {@code
     * max_concurrency} 是可空的（V4 加入时没有 NOT NULL），而且 NULL 有语义：
     * 它表示**这一层不设限**，有效值由套餐、key、用户、渠道、模型各层取最小值决定 （{@code DatabaseGatewayCatalog} 的 COALESCE 链 +
     * {@code RequestLimitService.Thresholds} 的 {@code Math.min}）。所以这里回填兜底值只是让管理端有一个可显示的数， **不是**把
     * NULL 当成 60 去执行——执行路径读的是原始列。
     *
     * <p>不能直接拆箱：视图记录用的是原始类型 {@code int}，控制台会对它做 {@code toLocaleString()} 与 {@code > 0} 校验，因此 NULL
     * 既不能透出去， 也不能让拆箱抛 NPE。2026-10-09 的生产事故正是后者——V102 插入 {@code chat-standard} / {@code chat-deep}
     * 时没有写这两列，{@code modelView} 拆箱读到 NULL，后台模型列表**整页** 500，看起来就像"模型全没了"。
     * 本方法让"数据少一列"最多显示成兜底值，而不是打挂整个列表。
     */
    private static int limitOr(io.r2dbc.spi.Row row, String column, int fallback) {
        Integer value = row.get(column, Integer.class);
        return value == null ? fallback : value;
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }

    private record PageRequest(int page, int size, int offset) {}

    /**
     * 写一条「别名 ← 上游模型」映射（批量接入用）。
     *
     * <p>能力列取该模型自己的声明：路由候选的能力判定读的就是这一列， 让调用方另填一份迟早会与模型不一致。
     *
     * @return 受影响行数；目标模型不存在时为 0（而不是插入一行悬空映射）
     */
    Mono<Long> upsertChannelRoute(
            UUID channelId,
            UUID modelId,
            String upstreamModel,
            boolean enabled,
            Integer priorityOverride,
            Integer weightOverride) {
        var spec =
                databaseClient
                        .sql(
                                """
                        INSERT INTO gateway.channel_model(
                            id, channel_id, model_id, upstream_model, enabled,
                            priority_override, weight_override, capabilities)
                        SELECT gen_random_uuid(), :channel_id, model.id, :upstream_model, :enabled,
                               :priority, :weight, model.capabilities
                        FROM gateway.model model WHERE model.id = :model_id
                        ON CONFLICT (channel_id, model_id) DO UPDATE
                        SET upstream_model = EXCLUDED.upstream_model,
                            enabled = EXCLUDED.enabled,
                            priority_override = EXCLUDED.priority_override,
                            weight_override = EXCLUDED.weight_override,
                            capabilities = EXCLUDED.capabilities,
                            updated_at = now()
                        """)
                        .bind("channel_id", channelId)
                        .bind("model_id", modelId)
                        .bind("upstream_model", upstreamModel)
                        .bind("enabled", enabled);
        spec =
                priorityOverride == null
                        ? spec.bindNull("priority", Integer.class)
                        : spec.bind("priority", priorityOverride);
        spec =
                weightOverride == null
                        ? spec.bindNull("weight", Integer.class)
                        : spec.bind("weight", weightOverride);
        return spec.fetch().rowsUpdated();
    }

    /** 该渠道上是否已经有这条别名的映射（批量接入据此区分新建与覆盖）。 */
    Mono<Boolean> mappingExists(UUID channelId, UUID modelId) {
        return databaseClient
                .sql(
                        """
                        SELECT count(*) AS rows FROM gateway.channel_model
                        WHERE channel_id = :channel_id AND model_id = :model_id
                        """)
                .bind("channel_id", channelId)
                .bind("model_id", modelId)
                .map((row, metadata) -> row.get("rows", Long.class))
                .one()
                .map(count -> count != null && count > 0);
    }

    /** 该别名当前是否有生效的费率行（视频别名缺了它就只能路由、不能计价）。 */
    Mono<Boolean> hasCurrentUsagePrice(String modelCode, String usageDimension) {
        return databaseClient
                .sql(
                        """
                        SELECT count(*) AS rows
                        FROM gateway.model_usage_price
                        WHERE model_code = :model_code
                          AND usage_dimension = :usage_dimension
                          AND effective_to IS NULL
                        """)
                .bind("model_code", modelCode)
                .bind("usage_dimension", usageDimension)
                .map((row, metadata) -> row.get("rows", Long.class))
                .one()
                .map(count -> count != null && count > 0);
    }

    /** 该别名当前挂在哪些渠道上、优先级多少（用于提示「旧渠道仍会抢先命中」）。 */
    Flux<String> higherPriorityChannels(String modelCode, UUID excludeChannelId) {
        return databaseClient
                .sql(
                        """
                        SELECT channel.code
                        FROM gateway.channel_model mapping
                        JOIN gateway.model model ON model.id = mapping.model_id
                        JOIN gateway.channel channel ON channel.id = mapping.channel_id
                        JOIN gateway.provider provider ON provider.id = channel.provider_id
                        WHERE model.code = :model_code
                          AND mapping.channel_id <> :exclude_channel_id
                          AND mapping.enabled AND channel.enabled AND provider.enabled
                          AND channel.priority >= COALESCE(
                              (SELECT self.priority FROM gateway.channel self
                               WHERE self.id = :exclude_channel_id), 0)
                        ORDER BY channel.code
                        """)
                .bind("model_code", modelCode)
                .bind("exclude_channel_id", excludeChannelId)
                .map((row, metadata) -> row.get("code", String.class))
                .all();
    }

    /**
     * 探测上游模型清单所需的最小信息：地址、超时、供应商类型与密钥密文。
     *
     * <p>密钥密文由调用方（管理服务）用 {@code CredentialCipher} 解密后仅用于这次 上游请求，绝不回显给客户端。
     */
    Mono<ChannelProbeRow> channelProbe(UUID channelId) {
        return databaseClient
                .sql(
                        """
                        SELECT channel.code, channel.base_url, channel.environment, channel.timeout_ms,
                               channel.updated_at, channel.encrypted_api_key, provider.provider_type
                        FROM gateway.channel channel
                        JOIN gateway.provider provider ON provider.id = channel.provider_id
                        WHERE channel.id = :id
                        """)
                .bind("id", channelId)
                .map(
                        (row, metadata) ->
                                new ChannelProbeRow(
                                        row.get("code", String.class),
                                        row.get("base_url", String.class),
                                        row.get("environment", String.class),
                                        row.get("timeout_ms", Integer.class) == null
                                                ? 60_000
                                                : row.get("timeout_ms", Integer.class),
                                        row.get("updated_at", OffsetDateTime.class),
                                        row.get("encrypted_api_key", byte[].class),
                                        row.get("provider_type", String.class)))
                .one();
    }

    /**
     * 这条渠道上已配置的能力映射：每条给出「能力标签 + 上游模型名」。
     *
     * <p>按能力探测（{@code CapabilityProbeService}）需要知道"这条渠道服务哪些能力"，
     * 才能去请求各自对应的端点。同一能力下可能有多条映射（不同上游模型），取哪一条都 能回答"凭据在这个端点上通不通"，因此调用方只取每能力的第一条即可。
     *
     * <p>{@code capabilities} 是 jsonb 数组（例如 {@code ["AUDIO_TRANSCRIBE"]}），
     * 这里按文本返回，由服务端解析——保持与其它查询一致的"数据库不认识业务枚举"的写法。
     */
    Flux<ChannelCapabilityRow> channelCapabilityRows(UUID channelId) {
        return databaseClient
                .sql(
                        """
                        SELECT mapping.upstream_model, mapping.capabilities::text AS capabilities
                        FROM gateway.channel_model mapping
                        WHERE mapping.channel_id = :id AND mapping.enabled
                        ORDER BY mapping.upstream_model
                        """)
                .bind("id", channelId)
                .map(
                        (row, metadata) ->
                                new ChannelCapabilityRow(
                                        row.get("upstream_model", String.class),
                                        row.get("capabilities", String.class)))
                .all();
    }

    record ChannelCapabilityRow(String upstreamModel, String capabilitiesJson) {}

    record ChannelProbeRow(
            String code,
            String baseUrl,
            String environment,
            int timeoutMs,
            OffsetDateTime observedUpdatedAt,
            byte[] encryptedApiKey,
            String providerType) {

        ChannelProbeRow {
            encryptedApiKey = encryptedApiKey == null ? null : encryptedApiKey.clone();
        }

        @Override
        public byte[] encryptedApiKey() {
            return encryptedApiKey == null ? null : encryptedApiKey.clone();
        }
    }

    record ChannelTestConfig(
            String baseUrl,
            String environment,
            int timeoutMs,
            boolean configured,
            OffsetDateTime observedUpdatedAt) {}

    /** 一条映射的上游模型名与可用性，用于选真实调用的目标。 */
    record ChannelModelChoice(
            String modelCode,
            String displayName,
            String upstreamModel,
            boolean mappingEnabled,
            boolean modelEnabled,
            boolean textCapable) {

        boolean probeable() {
            return mappingEnabled && modelEnabled && textCapable;
        }
    }
}
