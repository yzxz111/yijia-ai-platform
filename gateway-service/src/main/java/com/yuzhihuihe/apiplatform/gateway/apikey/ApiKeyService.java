package com.yuzhihuihe.apiplatform.gateway.apikey;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.sms.PlatformSmsClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SECRET_PREFIX = "sk-yzh-";
    // 与 V80 的 ck_api_key_usage_dimension 同一套枚举（该约束由 V92 加入
    // AUDIO_SECOND）。**两边必须同步**：这里少一个维度时，一个音频套餐的用户
    // 连一把 key 都开不出来，而错误码只会说「维度无效」，不会指向这张清单。
    private static final Set<String> USAGE_DIMENSIONS =
            Set.of("TOKEN", "VIDEO_SECOND", "IMAGE", "AUDIO_SECOND");

    private final ApiKeyRepository repository;
    private final TransactionalOperator transactions;
    private final ObjectMapper objectMapper;
    private final ReactiveStringRedisTemplate redis;
    private final byte[] pepper;
    private final Duration cacheTtl;
    private final Duration redisTimeout;
    private final PlatformSmsClient platformSmsClient;
    private final int walletMaxKeys;

    public ApiKeyService(
            ApiKeyRepository repository,
            TransactionalOperator transactions,
            ObjectMapper objectMapper,
            ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
            ObjectProvider<PlatformSmsClient> platformSmsClientProvider,
            @Value("${security.api-key.pepper:}") String pepper,
            @Value("${security.api-key.cache-ttl:5m}") Duration cacheTtl,
            @Value("${spring.data.redis.timeout:1s}") Duration redisTimeout,
            @Value("${gateway.api-key.wallet-max-keys:5}") int walletMaxKeys) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalArgumentException("api_key_pepper_required");
        }
        if (redisTimeout == null || redisTimeout.isZero() || redisTimeout.isNegative()) {
            throw new IllegalArgumentException("redis_timeout_required");
        }
        if (walletMaxKeys <= 0) {
            throw new IllegalArgumentException("wallet_api_key_limit_required");
        }
        this.repository = repository;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.redis = redisProvider.getIfAvailable();
        this.platformSmsClient = platformSmsClientProvider.getIfAvailable();
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
        this.cacheTtl = cacheTtl;
        this.redisTimeout = redisTimeout;
        this.walletMaxKeys = walletMaxKeys;
    }

    public Mono<CreatedApiKey> create(UUID tenantId, UUID userId, CreateApiKeyRequest request) {
        validateOwner(tenantId, userId);
        if (request == null || request.name() == null || request.name().isBlank()
                || request.name().strip().length() > 100
                || request.allowedModels() == null || request.allowedModels().isEmpty()
                || request.allowedModels().stream().anyMatch(this::blank)) {
            return Mono.error(unprocessable("invalid_api_key_request"));
        }
        Set<String> requestedModels = Set.copyOf(request.allowedModels());
        // 自 V80 起「生效套餐」按维度解析。省略维度时回退到 TOKEN，与这次改动之前
        // 的行为一致。控制台页面默认使用 TOKEN；API 可显式传入其他维度，
        // 桌面端继续走 /client/bootstrap。
        String usageDimension = normalizeUsageDimension(request.usageDimension());
        return transactions.transactional(lockCreation(userId)
                .then(loadCreationPolicyForUpdate(tenantId, userId, usageDimension))
                .flatMap(snapshot -> validateRequestedModels(requestedModels, snapshot.allowedModels())
                        .then(countLiveKeys(tenantId, userId))
                        .flatMap(count -> {
                            if (count >= snapshot.maxApiKeys()) {
                                return Mono.error(unprocessable("api_key_limit_exceeded"));
                            }
                            return insertKey(tenantId, userId, snapshot.id(),
                                    request.name().strip(), requestedModels, snapshot.expiresAt(),
                                    usageDimension);
                        })));
    }

    public Flux<String> eligibleModels(UUID tenantId, UUID userId, String usageDimension) {
        validateOwner(tenantId, userId);
        String dimension = normalizeUsageDimension(usageDimension);
        return loadActivePackage(tenantId, userId, dimension)
                .switchIfEmpty(loadWalletKeyPolicy())
                .flatMapMany(snapshot -> Flux.fromIterable(snapshot.allowedModels()));
    }

    private String normalizeUsageDimension(String usageDimension) {
        if (usageDimension == null || usageDimension.isBlank()) {
            return "TOKEN";
        }
        String trimmed = usageDimension.trim().toUpperCase(Locale.ROOT);
        if (!USAGE_DIMENSIONS.contains(trimmed)) {
            throw unprocessable("invalid_usage_dimension");
        }
        return trimmed;
    }

    public Flux<ApiKeyView> list(UUID tenantId, UUID userId) {
        validateOwner(tenantId, userId);
        return repository.list(tenantId, userId).map(this::toView);
    }

    public Mono<ApiKeyView> changeStatus(UUID tenantId, UUID userId, UUID id, String status) {
        validateOwner(tenantId, userId);
        if (id == null || !("ACTIVE".equals(status) || "DISABLED".equals(status))) {
            return Mono.error(unprocessable("invalid_api_key_status"));
        }
        return updateStatus(tenantId, userId, id, status, false)
                .flatMap(updated -> invalidate(updated.keyHash()).thenReturn(updated.view()));
    }

    public Mono<Void> revoke(UUID tenantId, UUID userId, UUID id) {
        validateOwner(tenantId, userId);
        if (id == null) {
            return Mono.error(unprocessable("invalid_api_key_id"));
        }
        return updateStatus(tenantId, userId, id, "REVOKED", true)
                .flatMap(updated -> invalidate(updated.keyHash()))
                .then();
    }

    public Mono<ResetApiKey> reset(
            UUID tenantId, UUID userId, UUID id, String proof) {
        validateOwner(tenantId, userId);
        if (id == null || proof == null || proof.isBlank()) {
            return Mono.error(unprocessable("invalid_sms_proof"));
        }
        if (platformSmsClient == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "sms_proof_service_unavailable"));
        }
        return preflightResettableKey(tenantId, userId, id)
                .then(loadOrCreateResetIntent(tenantId, userId, id, proof))
                .flatMap(intent -> consumeResetProof(
                                tenantId, userId, id, intent.operationId(), proof)
                        .then(Mono.defer(() -> completeReset(
                                tenantId, userId, id, intent.operationId()))));
    }

    public Mono<ApiKeyContext> authenticate(String secret) {
        if (secret == null || !secret.startsWith(SECRET_PREFIX) || secret.length() < 50) {
            return Mono.empty();
        }
        String keyHash = digest(secret);
        Mono<ApiKeyContext> databaseLookup = Mono.defer(() -> loadContext(keyHash)
                .flatMap(context -> cache(keyHash, context).thenReturn(context)));
        Mono<ApiKeyContext> databaseLookupWithoutCache = Mono.defer(() -> loadContext(keyHash));
        if (redis == null) {
            return databaseLookup;
        }
        Mono<String> cachedPayload = redis.opsForValue().get(cacheKey(keyHash))
                .timeout(redisTimeout)
                .onErrorMap(error -> new RedisCacheUnavailableException());
        return cachedPayload
                .flatMap(this::readCachedContext)
                .filter(cached -> "ACTIVE".equals(cached.status()))
                .flatMap(cached -> loadCachedContext(keyHash, cached))
                .switchIfEmpty(databaseLookup)
                .onErrorResume(RedisCacheUnavailableException.class,
                        error -> databaseLookupWithoutCache);
    }

    private Mono<PackageSnapshot> loadActivePackageForUpdate(
            UUID tenantId, UUID userId, String usageDimension) {
        return repository.findActivePackageForUpdate(tenantId, userId, usageDimension)
                .map(this::toPackageSnapshot);
    }

    private Mono<PackageSnapshot> loadActivePackage(
            UUID tenantId, UUID userId, String usageDimension) {
        return repository.findActivePackage(tenantId, userId, usageDimension)
                .map(this::toPackageSnapshot);
    }

    private Mono<PackageSnapshot> loadCreationPolicyForUpdate(
            UUID tenantId, UUID userId, String usageDimension) {
        return loadActivePackageForUpdate(tenantId, userId, usageDimension)
                .switchIfEmpty(loadWalletKeyPolicy());
    }

    private Mono<PackageSnapshot> loadWalletKeyPolicy() {
        return repository.findEnabledModelAliases()
                .collectList()
                .filter(models -> !models.isEmpty())
                .map(models -> new PackageSnapshot(
                        null, Set.copyOf(models), walletMaxKeys, null))
                .switchIfEmpty(Mono.error(unprocessable("api_key_model_unavailable")));
    }

    private Mono<Void> lockCreation(UUID userId) {
        return repository.lockCreation(userId);
    }

    private Mono<Void> validateRequestedModels(Set<String> requested, Set<String> packageModels) {
        return packageModels.containsAll(requested)
                ? Mono.empty()
                : Mono.error(unprocessable("api_key_model_not_allowed"));
    }

    private Mono<Long> countLiveKeys(UUID tenantId, UUID userId) {
        return repository.countLiveKeys(tenantId, userId);
    }

    private Mono<CreatedApiKey> insertKey(
            UUID tenantId, UUID userId, UUID packageId, String name,
            Set<String> allowedModels, Instant expiresAt, String usageDimension) {
        UUID id = UUID.randomUUID();
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String secret = SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String prefix = secret.substring(0, Math.min(secret.length(), SECRET_PREFIX.length() + 8));
        String keyHash = digest(secret);
        String models = writeJson(allowedModels);
        return repository.insertKey(
                        id, tenantId, userId, packageId, name, prefix, keyHash, models, expiresAt,
                        usageDimension)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("api_key_insert_failed")))
                .thenReturn(new CreatedApiKey(id, name, prefix, allowedModels, "ACTIVE", expiresAt, secret));
    }

    private Mono<UpdatedKey> updateStatus(
            UUID tenantId, UUID userId, UUID id, String status, boolean revoke) {
        return repository.updateStatus(tenantId, userId, id, status, revoke)
                .map(updated -> new UpdatedKey(updated.keyHash(), toView(updated.view())))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "api_key_not_found")));
    }

    private Mono<ApiKeyContext> loadContext(String keyHash) {
        return repository.findContext(keyHash)
                .map(row -> new ApiKeyContext(
                        row.id(), row.tenantId(), row.userId(), row.packageInstanceId(),
                        readStringSet(row.allowedModelsJson()), true))
                .flatMap(this::markUsed);
    }

    private Mono<ApiKeyContext> loadCachedContext(
            String keyHash, CachedApiKeyContext cached) {
        return repository.findCachedContext(cached.apiKeyId(), cached.userId(), keyHash)
                .map(row -> {
                    Set<String> allowedModels = new LinkedHashSet<>(readStringSet(
                            row.allowedModelsJson()));
                    return new ApiKeyContext(
                            cached.apiKeyId(), row.tenantId(), cached.userId(),
                            row.packageInstanceId(), allowedModels, true);
                })
                .flatMap(this::markUsed);
    }

    private Mono<Void> consumeResetProof(
            UUID tenantId, UUID userId, UUID keyId, UUID operationId, String proof) {
        return Mono.defer(() -> platformSmsClient.consumeApiKeyResetProof(
                        tenantId, userId, operationId, proof))
                .onErrorResume(PlatformSmsClient.PlatformSmsClientException.class, error -> {
                    if (Integer.valueOf(422).equals(error.httpStatus())) {
                        return deleteRejectedResetIntent(
                                        tenantId, userId, keyId, operationId)
                                .then(Mono.error(unprocessable("invalid_sms_proof")));
                    }
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE, "sms_proof_service_unavailable"));
                })
                .onErrorMap(error -> !(error instanceof ResponseStatusException), error ->
                        new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, "sms_proof_service_unavailable"));
    }

    private Mono<Void> deleteRejectedResetIntent(
            UUID tenantId, UUID userId, UUID keyId, UUID operationId) {
        return repository.deletePendingResetIntent(tenantId, userId, keyId, operationId);
    }

    private Mono<ResetIntent> loadOrCreateResetIntent(
            UUID tenantId, UUID userId, UUID keyId, String proof) {
        String fingerprint = digest("api-key-reset-proof:" + proof);
        UUID operationId = UUID.randomUUID();
        return repository.insertResetIntent(
                        operationId, tenantId, userId, keyId, fingerprint)
                .then(loadPendingResetIntent(keyId)
                        .switchIfEmpty(loadResetIntent(fingerprint)))
                .flatMap(intent -> {
                    if (!tenantId.equals(intent.tenantId())
                            || !userId.equals(intent.userId())
                            || !keyId.equals(intent.apiKeyId())) {
                        return Mono.error(unprocessable("invalid_sms_proof"));
                    }
                    if (!"PENDING".equals(intent.status())) {
                        return Mono.error(unprocessable("invalid_sms_proof"));
                    }
                    if (!fingerprint.equals(intent.proofFingerprint())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "api_key_reset_already_pending"));
                    }
                    return Mono.just(intent);
                })
                .onErrorMap(error -> !(error instanceof ResponseStatusException), error ->
                        new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, "api_key_reset_intent_unavailable"));
    }

    private Mono<ResetIntent> loadResetIntent(String fingerprint) {
        return repository.findResetIntent(fingerprint)
                .map(this::toResetIntent)
                .switchIfEmpty(Mono.error(
                        new IllegalStateException("api_key_reset_intent_missing")));
    }

    private Mono<ResetIntent> loadPendingResetIntent(UUID keyId) {
        return repository.findPendingResetIntent(keyId).map(this::toResetIntent);
    }

    private Mono<ResetApiKey> completeReset(
            UUID tenantId, UUID userId, UUID id, UUID operationId) {
        GeneratedSecret generated = generateSecret();
        Mono<ResetResult> databaseReset = lockPendingResetIntent(
                        tenantId, userId, id, operationId)
                .then(lockResettableKey(tenantId, userId, id))
                .flatMap(locked -> repository.updateKeySecret(
                                tenantId, userId, id, generated.prefix(), generated.hash())
                        .filter(updated -> updated == 1L)
                        .switchIfEmpty(Mono.error(
                                new IllegalStateException("api_key_reset_update_failed")))
                        .then(writeResetAudit(
                                operationId, tenantId, userId, id,
                                locked.prefix(), generated.prefix()))
                        .then(markResetIntentCompleted(operationId))
                        .thenReturn(new ResetResult(locked.hash(), generated.hash(),
                                new ResetApiKey(id, generated.prefix(), generated.secret()))));
        return transactions.transactional(databaseReset)
                .flatMap(result -> Mono.when(
                                invalidate(result.oldHash()).onErrorResume(error -> Mono.empty()),
                                invalidate(result.newHash()).onErrorResume(error -> Mono.empty()))
                        .thenReturn(result.response()))
                .onErrorMap(error -> !(error instanceof ResponseStatusException), error ->
                        new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, "api_key_reset_pending_retry"));
    }

    private Mono<Void> lockPendingResetIntent(
            UUID tenantId, UUID userId, UUID keyId, UUID operationId) {
        return repository.lockPendingResetIntent(tenantId, userId, keyId, operationId)
                .filter("PENDING"::equals)
                .switchIfEmpty(Mono.error(unprocessable("invalid_sms_proof")))
                .then();
    }

    private Mono<Void> markResetIntentCompleted(UUID operationId) {
        return repository.completeResetIntent(operationId)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(
                        new IllegalStateException("api_key_reset_intent_completion_failed")))
                .then();
    }

    private Mono<LockedKey> lockResettableKey(UUID tenantId, UUID userId, UUID id) {
        return repository.lockResettableKey(tenantId, userId, id)
                .map(row -> new LockedKey(row.hash(), row.prefix()))
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "api_key_not_found")));
    }

    private Mono<Void> preflightResettableKey(UUID tenantId, UUID userId, UUID id) {
        return repository.findResettableKey(tenantId, userId, id)
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "api_key_not_found")))
                .then();
    }

    private Mono<Void> writeResetAudit(
            UUID operationId, UUID tenantId, UUID userId, UUID keyId,
            String oldPrefix, String newPrefix) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("keyId", keyId.toString());
        detail.put("oldPrefix", oldPrefix);
        detail.put("newPrefix", newPrefix);
        detail.put("secretMask", newPrefix + "****");
        return repository.insertResetAudit(
                        operationId, tenantId, userId, keyId, writeJson(detail))
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("api_key_reset_audit_failed")))
                .then();
    }

    GeneratedSecret generateSecret() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String secret = SECRET_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String prefix = secret.substring(0, Math.min(secret.length(), SECRET_PREFIX.length() + 8));
        return new GeneratedSecret(secret, prefix, digest(secret));
    }

    String digestSecret(String secret) {
        return digest(secret);
    }

    private Mono<ApiKeyContext> markUsed(ApiKeyContext context) {
        return repository.markUsed(context.apiKeyId()).thenReturn(context);
    }

    private Mono<Void> cache(String keyHash, ApiKeyContext context) {
        if (redis == null) {
            return Mono.empty();
        }
        CachedApiKeyContext cached = new CachedApiKeyContext(
                context.apiKeyId(), context.userId(), context.packageInstanceId(),
                context.allowedModels(), context.walletEligible(), "ACTIVE");
        return redis.opsForValue().set(cacheKey(keyHash), writeJson(cached), cacheTtl)
                .timeout(redisTimeout)
                .onErrorResume(error -> Mono.just(false)).then();
    }

    private Mono<Void> invalidate(String keyHash) {
        if (redis == null) {
            return Mono.empty();
        }
        return redis.delete(cacheKey(keyHash)).timeout(redisTimeout).then();
    }

    Mono<Void> evictAuthenticationCache(String keyHash) {
        return invalidate(keyHash);
    }

    private Mono<CachedApiKeyContext> readCachedContext(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, CachedApiKeyContext.class));
        } catch (Exception exception) {
            return Mono.empty();
        }
    }

    private Set<String> readPackageModels(String json) {
        try {
            Set<String> models = new LinkedHashSet<>();
            for (JsonNode node : objectMapper.readTree(json)) {
                String code = node.path("modelCode").asText();
                if (!code.isBlank()) {
                    models.add(code);
                }
            }
            return Set.copyOf(models);
        } catch (Exception exception) {
            throw new IllegalStateException("package_models_deserialization_failed", exception);
        }
    }

    /**
     * 读一列"别名集合"，两种形态都接受。
     *
     * <p>这张表里同一个列名下面真的存在两种写法，而且两种都是正常产生的：
     *
     * <ul>
     *   <li>{@code ["video-basic", "audio-tts-basic"]} —— 平台下发的托管 Key
     *       （{@code ManagedApiKeyService} 用 {@code Set<String>} 写入）；
     *   <li>{@code [{"modelCode": "video-basic", "capabilities": [...],
     *       "modelTokenQuota": 100}]} —— 控制台/客户端自助创建的 Key
     *       （{@code ApiKeyService.create} 原样保存 {@code package_instance.allowed_models}
     *       的 JSONB，那是带能力的对象数组）。
     * </ul>
     *
     * <p><b>只认字符串会让第二种形态解析成一个空集合</b>，而这不是"少几个别名"，
     * 是**整个别名集合为空**：{@code /v1/video-models}、{@code /v1/audio-models}
     * 都会先用它过滤（{@code ModelCatalogService}/{@code AudioModelCatalogService}），
     * 于是这些 Key 的目录恒为空数组——客户看到"我的套餐里什么都没有"，而数据库里
     * 那份权益一直是对的。2026-10-09 生产实测：16 把 Key 是对象形态，其中
     * 4 把绑定在视频套餐上（正是"视频套餐看不到配音"的直接原因）。
     *
     * <p>因此这里对两种形态一视同仁：对象取 {@code modelCode}，非对象按字符串读。
     * 空对象或没有 {@code modelCode} 的条目被跳过，而不是被读成 {@code "null"}。
     */
    private Set<String> readStringSet(String json) {
        try {
            Set<String> values = new LinkedHashSet<>();
            for (JsonNode node : objectMapper.readTree(json)) {
                String value = node.isObject() ? node.path("modelCode").asText(null) : node.asText();
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
            return Set.copyOf(values);
        } catch (Exception exception) {
            throw new IllegalStateException("api_key_models_deserialization_failed", exception);
        }
    }

    private PackageSnapshot toPackageSnapshot(ApiKeyRepository.PackageSnapshotRow row) {
        return new PackageSnapshot(
                row.id(), readPackageModels(row.allowedModelsJson()), row.maxApiKeys(), row.expiresAt());
    }

    private ApiKeyView toView(ApiKeyRepository.ApiKeyViewRow row) {
        return new ApiKeyView(
                row.id(), row.name(), row.keyPrefix(), readStringSet(row.allowedModelsJson()),
                row.status(), row.lastUsedAt(), row.expiresAt(), row.createdAt());
    }

    private ResetIntent toResetIntent(ApiKeyRepository.ResetIntentRow row) {
        return new ResetIntent(
                row.operationId(), row.tenantId(), row.userId(), row.apiKeyId(),
                row.proofFingerprint(), row.status());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("api_key_serialization_failed", exception);
        }
    }

    private String digest(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("api_key_digest_failed", exception);
        }
    }

    private String cacheKey(String keyHash) {
        return "api-key:" + keyHash;
    }

    private void validateOwner(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw unprocessable("api_key_owner_required");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || value.length() > 128;
    }

    private ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public record CreateApiKeyRequest(
            String name, List<String> allowedModels, String usageDimension) { }

    public record CreatedApiKey(
            UUID id, String name, String keyPrefix, Set<String> allowedModels,
            String status, Instant expiresAt, String secret) { }

    public record ApiKeyView(
            UUID id, String name, String keyPrefix, Set<String> allowedModels,
            String status, Instant lastUsedAt, Instant expiresAt, Instant createdAt) { }

    public record ResetApiKey(UUID id, String keyPrefix, String secret) { }

    public record ApiKeyContext(
            UUID apiKeyId, UUID tenantId, UUID userId, UUID packageInstanceId,
            Set<String> allowedModels, boolean walletEligible) {
        public ApiKeyContext(
                UUID apiKeyId, UUID tenantId, UUID userId, UUID packageInstanceId,
                Set<String> allowedModels) {
            this(apiKeyId, tenantId, userId, packageInstanceId, allowedModels, true);
        }
    }

    private record CachedApiKeyContext(
            UUID apiKeyId, UUID userId, UUID packageInstanceId,
            Set<String> allowedModels, boolean walletEligible, String status) { }

    private record PackageSnapshot(
            UUID id, Set<String> allowedModels, int maxApiKeys, Instant expiresAt) { }

    private record UpdatedKey(String keyHash, ApiKeyView view) { }

    record GeneratedSecret(String secret, String prefix, String hash) { }

    private record LockedKey(String hash, String prefix) { }

    private record ResetIntent(
            UUID operationId, UUID tenantId, UUID userId, UUID apiKeyId,
            String proofFingerprint, String status) { }

    private record ResetResult(String oldHash, String newHash, ResetApiKey response) { }

    private static final class RedisCacheUnavailableException extends RuntimeException {
    }
}
