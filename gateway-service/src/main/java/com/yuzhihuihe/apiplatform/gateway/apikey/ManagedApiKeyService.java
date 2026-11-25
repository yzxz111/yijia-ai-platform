package com.yuzhihuihe.apiplatform.gateway.apikey;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.routing.CredentialCipher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ManagedApiKeyService {

    private static final short SECRET_KEY_VERSION = 1;
    private static final String MANAGED_KEY_NAME = "Internal client";
    // 与 V42 的 ck_package_instance_usage_dimension 和 V80 的
    // ck_api_key_usage_dimension 同一套枚举（两个约束都由 V92 加入 AUDIO_SECOND）。
    // **同时也要与 ApiKeyService.USAGE_DIMENSIONS 同步**：托管 key 是每个套餐实例
    // 一把，缺一个维度就意味着那个维度的套餐无法被内部客户端使用。
    private static final Set<String> USAGE_DIMENSIONS =
            Set.of("TOKEN", "VIDEO_SECOND", "IMAGE", "AUDIO_SECOND");

    private final ManagedApiKeyRepository repository;
    private final TransactionalOperator transactions;
    private final CredentialCipher credentialCipher;
    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    public ManagedApiKeyService(
            ManagedApiKeyRepository repository,
            TransactionalOperator transactions,
            CredentialCipher credentialCipher,
            ApiKeyService apiKeyService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactions = transactions;
        this.credentialCipher = credentialCipher;
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
    }

    /**
     * 为该用户的某个用量维度发放（或取回）托管 key。
     *
     * <p>{@code usageDimension} 可以省略：此时只在用户恰好持有一个生效实例时生效，
     * 与这次改动之前的行为完全一致。一旦同时持有多个维度（文本 + 视频），
     * 省略维度就是歧义的，客户端必须显式指定，否则会拿到一个与它要发起的调用
     * 单位不符的 key。
     */
    public Mono<ClientBootstrap> bootstrap(
            UUID tenantId, UUID userId, String usageDimension) {
        if (tenantId == null || userId == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "invalid_credentials"));
        }
        Mono<ClientBootstrap> work = repository.lockUser(userId)
                .thenMany(repository.findActivePackagesForUpdate(tenantId, userId))
                .collectList()
                .flatMap(rows -> selectActivePackage(rows, usageDimension))
                .flatMap(activePackage -> activePackage.refundInProgress()
                        ? Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "package_refund_in_progress"))
                        : repository.findManagedKey(tenantId, userId, activePackage.id())
                                .map(row -> new ManagedKey(
                                        row.id(), row.prefix(), row.ciphertext()))
                                .switchIfEmpty(Mono.defer(() -> createManagedKey(
                                        tenantId, userId, activePackage)))
                                .flatMap(key -> reveal(tenantId, userId, activePackage, key)));
        return transactions.transactional(work)
                .onErrorMap(error -> !(error instanceof ResponseStatusException), error ->
                        new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "client_bootstrap_unavailable"));
    }

    private Mono<ActivePackage> selectActivePackage(
            List<ManagedApiKeyRepository.ActivePackageRow> rows, String requestedDimension) {
        String requested = normalizeUsageDimension(requestedDimension);
        if (requested != null) {
            return rows.stream()
                    .filter(row -> requested.equals(row.usageDimension()))
                    .findFirst()
                    .map(row -> Mono.just(toActivePackage(row)))
                    .orElseGet(() -> Mono.error(new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "active_package_required")));
        }
        if (rows.isEmpty()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "active_package_required"));
        }
        if (rows.size() > 1) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "package_dimension_required"));
        }
        return Mono.just(toActivePackage(rows.get(0)));
    }

    /**
     * 省略维度返回 {@code null}；给定了却不在枚举内则 422，而不是静默回退到
     * 「随便挑一个」——后者会把一个拼错的维度变成一个看起来成功、实际额度单位错误的 key。
     */
    private String normalizeUsageDimension(String usageDimension) {
        if (usageDimension == null || usageDimension.isBlank()) {
            return null;
        }
        String trimmed = usageDimension.trim().toUpperCase(Locale.ROOT);
        if (!USAGE_DIMENSIONS.contains(trimmed)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_usage_dimension");
        }
        return trimmed;
    }

    private ActivePackage toActivePackage(ManagedApiKeyRepository.ActivePackageRow row) {
        return new ActivePackage(
                row.id(),
                row.packageCode(),
                row.expiresAt(),
                readPackageModels(row.allowedModelsJson()),
                row.refundInProgress(),
                row.usageDimension());
    }

    public Mono<ManagedKeyRotation> rotate(
            UUID tenantId,
            UUID actorUserId,
            UUID keyId,
            UUID operationId) {
        if (tenantId == null || actorUserId == null || keyId == null || operationId == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_managed_api_key_rotation"));
        }
        Mono<RotationWork> work = repository.findManagedKeyUser(tenantId, keyId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "managed_api_key_not_found")))
                .flatMap(userId -> repository.lockUser(userId)
                        .then(repository.insertRotationIntent(
                                operationId, tenantId, actorUserId, userId, keyId))
                        .then(repository.lockRotation(operationId))
                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                "managed_api_key_rotation_intent_missing")))
                        .flatMap(rotation -> rotateLocked(
                                tenantId, actorUserId, keyId, userId, rotation)));
        return transactions.transactional(work)
                .flatMap(result -> evictRotatedKeyHashes(result)
                        .thenReturn(result.response()))
                .onErrorMap(error -> !(error instanceof ResponseStatusException), error ->
                        new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "managed_api_key_rotation_unavailable"));
    }

    private Mono<RotationWork> rotateLocked(
            UUID tenantId,
            UUID actorUserId,
            UUID keyId,
            UUID userId,
            ManagedApiKeyRepository.RotationRow rotation) {
        if (!tenantId.equals(rotation.tenantId())
                || !actorUserId.equals(rotation.actorUserId())
                || !userId.equals(rotation.targetUserId())
                || !keyId.equals(rotation.oldKeyId())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "managed_api_key_rotation_operation_reused"));
        }
        if ("COMPLETED".equals(rotation.status())) {
            return Mono.just(new RotationWork(
                    new ManagedKeyRotation(
                            rotation.operationId(), rotation.oldKeyId(), rotation.newKeyId(),
                            rotation.newKeyPrefix(), null, false, "COMPLETED"),
                    null,
                    null));
        }
        return repository.lockManagedKeyForRotation(tenantId, userId, keyId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "managed_api_key_not_rotatable")))
                .flatMap(locked -> rotateManagedKey(
                        tenantId, actorUserId, rotation.operationId(), locked));
    }

    private Mono<RotationWork> rotateManagedKey(
            UUID tenantId,
            UUID actorUserId,
            UUID operationId,
            ManagedApiKeyRepository.ManagedKeyForRotationRow locked) {
        return Mono.defer(() -> {
            ApiKeyService.GeneratedSecret generated = apiKeyService.generateSecret();
            byte[] ciphertext = credentialCipher.encrypt(generated.secret());
            UUID newKeyId = UUID.randomUUID();
            // 替代 Key 带**套餐当前**的授权名单，而不是旧 Key 的快照：轮换与
            // Bootstrap 是同一个语义（托管 Key 没有让用户挑子集这回事），照抄旧快照
            // 等于把一份过期的名单再钉一次——那正是 bootstrap 每次对齐要修的东西。
            // 包成字符串数组的形状与 insertManagedKey 一致（读取方两种形态都认）。
            String allowedModels = writeJson(readPackageModels(locked.packageAllowedModelsJson()));
            return repository.revokeManagedKey(locked.id())
                    .filter(updated -> updated == 1L)
                    .switchIfEmpty(Mono.error(new IllegalStateException(
                            "managed_api_key_revoke_failed")))
                    .then(repository.insertManagedKey(
                            newKeyId,
                            locked.tenantId(),
                            locked.userId(),
                            locked.packageId(),
                            locked.name(),
                            generated.prefix(),
                            generated.hash(),
                            allowedModels,
                            locked.expiresAt(),
                            ciphertext,
                            SECRET_KEY_VERSION,
                            locked.usageDimension()))
                    .filter(updated -> updated == 1L)
                    .switchIfEmpty(Mono.error(new IllegalStateException(
                            "managed_api_key_replacement_insert_failed")))
                    .then(writeRotationAudit(
                            operationId, tenantId, actorUserId, locked, newKeyId,
                            generated.prefix()))
                    .then(repository.completeRotation(operationId, newKeyId))
                    .filter(updated -> updated == 1L)
                    .switchIfEmpty(Mono.error(new IllegalStateException(
                            "managed_api_key_rotation_completion_failed")))
                    .thenReturn(new RotationWork(
                            new ManagedKeyRotation(
                                    operationId, locked.id(), newKeyId, generated.prefix(),
                                    generated.secret(), true, "COMPLETED"),
                            locked.hash(),
                            generated.hash()));
        });
    }

    private Mono<Void> evictRotatedKeyHashes(RotationWork result) {
        if (result.oldHash() == null) {
            return Mono.empty();
        }
        return Mono.when(
                        apiKeyService.evictAuthenticationCache(result.oldHash())
                                .onErrorResume(error -> Mono.empty()),
                        apiKeyService.evictAuthenticationCache(result.newHash())
                                .onErrorResume(error -> Mono.empty()))
                .then();
    }

    private Mono<ManagedKey> createManagedKey(
            UUID tenantId, UUID userId, ActivePackage activePackage) {
        ApiKeyService.GeneratedSecret generated = apiKeyService.generateSecret();
        byte[] ciphertext = credentialCipher.encrypt(generated.secret());
        UUID keyId = UUID.randomUUID();
        return repository.insertManagedKey(
                        keyId,
                        tenantId,
                        userId,
                        activePackage.id(),
                        MANAGED_KEY_NAME,
                        generated.prefix(),
                        generated.hash(),
                        writeJson(activePackage.allowedModels()),
                        activePackage.expiresAt(),
                        ciphertext,
                        SECRET_KEY_VERSION,
                        activePackage.usageDimension())
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(
                        new IllegalStateException("managed_api_key_insert_failed")))
                .then(writeAudit(
                        tenantId, userId, keyId, activePackage.id(),
                        generated.prefix(), "MANAGED_API_KEY_CREATE"))
                .thenReturn(new ManagedKey(keyId, generated.prefix(), ciphertext));
    }

    /**
     * 揭晓托管 Key，并把客户端的「套餐模型」下拉收敛到目录可见、且与本次
     * 套餐维度相关的别名。
     *
     * <p>响应里的 {@code allowedModels} 是套餐完整名单、{@code gateway.model}
     * {@code client_visible} 名单与**维度能力**三者的交集，不是套餐的完整名单：
     * 文本入口只回 chat-standard（普通）/ chat-deep（深度），视频入口只回
     * 合并后的 video-minimax（MiniMax）——音频别名不会出现在视频下拉里，
     * 被 {@code client_visible = false} 收起的别名（text-* / vision-* /
     * multimodal-basic / 档位视频别名）也不再以「当前入口不可选」的下拉项出现。
     *
     * <p>这份收敛只影响**展示**：托管 Key 的授权名单（{@code api_key.allowed_models}）
     * 每次 Bootstrap 都会与套餐当前名单对齐并顺手标记已揭晓
     * （{@code synchronizeAndMarkRevealed}），鉴权与路由只认那份完整名单，
     * 因此第三方兼容客户端按名字调用那些别名照旧。
     */
    private Mono<ClientBootstrap> reveal(
            UUID tenantId,
            UUID userId,
            ActivePackage activePackage,
            ManagedKey key) {
        String secret = credentialCipher.decrypt(key.ciphertext());
        // 网关有**两份**模型名单，请求要两边都放行才成立：套餐实例的 allowed_models
        // 与 API Key 的 allowed_models。托管 Key 的名单只在发放那一刻抄一次，此后套餐
        // 换版本或运维补发别名（V102/V103 把客户端文本入口换成 chat-standard/chat-deep）
        // 它都不会跟——两份名单一旦不交叉，客户端就是"套餐里有模型、目录里一个都没有"
        // （2026-10-09 生产故障：Bootstrap 回了 5 个别名，/v1/models 回 data: []）。
        // 因此每次 Bootstrap 都同步一次：这一条 UPDATE 的 租户/用户/套餐 三个条件由
        // 上面的 findManagedKey(tenantId, userId, activePackage.id()) 保证成立，
        // expires_at 条件也成立（findActivePackagesForUpdate 已过滤过期实例），
        // 所以 rowsUpdated 为 0 只可能是"行被并发改掉"，此时按失败处理是对的。
        return repository.findClientVisibleModels()
                .collect(Collectors.toList())
                .map(visible -> clientVisibleModels(activePackage, visible))
                .flatMap(models -> repository.synchronizeAndMarkRevealed(
                                key.id(), tenantId, userId, activePackage.id(),
                                writeJson(activePackage.allowedModels()))
                        .filter(updated -> updated == 1L)
                        .switchIfEmpty(Mono.error(
                                new IllegalStateException("managed_api_key_reveal_update_failed")))
                        .then(writeAudit(
                                tenantId, userId, key.id(), activePackage.id(),
                                key.prefix(), "MANAGED_API_KEY_REVEAL"))
                        .thenReturn(new ClientBootstrap(
                                secret,
                                activePackage.id(),
                                activePackage.packageCode(),
                                activePackage.expiresAt(),
                                models,
                                activePackage.usageDimension())));
    }

    /**
     * 套餐名单 ∩ 目录可见名单 ∩ 维度能力，按字母序返回。
     *
     * <p>结果是可观察的（JSON 数组），因此顺序必须确定：沿用套餐名单读取时
     * {@link #readPackageModels} 的同一个 {@link TreeSet} 排序，让客户端拿到的数组
     * 顺序稳定、可比较。维度能力的判据与目录接口同一套词：VIDEO_SECOND 入口
     * 只回带 {@code VIDEO} 能力的别名（音频别名因此不进视频下拉），TOKEN 入口
     * 沿用文本目录的口径（TEXT 且非生成类），IMAGE/AUDIO_SECOND 同理。
     */
    private Set<String> clientVisibleModels(
            ActivePackage activePackage, List<ManagedApiKeyRepository.ClientVisibleModelRow> visible) {
        TreeSet<String> entitled = new TreeSet<>(activePackage.allowedModels());
        TreeSet<String> result = new TreeSet<>();
        for (ManagedApiKeyRepository.ClientVisibleModelRow row : visible) {
            if (!entitled.contains(row.alias())) {
                continue;
            }
            if (relevantTo(activePackage.usageDimension(), row.capabilitiesJson())) {
                result.add(row.alias());
            }
        }
        return Collections.unmodifiableSortedSet(result);
    }

    private boolean relevantTo(String usageDimension, String capabilitiesJson) {
        Set<String> capabilities = readCapabilities(capabilitiesJson);
        String dimension = usageDimension == null || usageDimension.isBlank()
                ? "TOKEN" : usageDimension;
        return switch (dimension) {
            case "VIDEO_SECOND" -> capabilities.contains("VIDEO");
            case "IMAGE" -> capabilities.contains("IMAGE_GEN");
            case "AUDIO_SECOND" -> capabilities.contains("AUDIO_TRANSCRIBE")
                    || capabilities.contains("AUDIO_SYNTHESIS")
                    || capabilities.contains("LIP_SYNC");
            default -> capabilities.contains("TEXT")
                    && !capabilities.contains("VIDEO")
                    && !capabilities.contains("IMAGE_GEN");
        };
    }

    private Set<String> readCapabilities(String json) {
        try {
            Set<String> values = new LinkedHashSet<>();
            objectMapper.readTree(json).forEach(node -> values.add(node.asText()));
            return values;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "model_capabilities_deserialization_failed", exception);
        }
    }

    private Mono<Void> writeAudit(
            UUID tenantId,
            UUID userId,
            UUID keyId,
            UUID packageId,
            String prefix,
            String action) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("keyId", keyId.toString());
        detail.put("packageInstanceId", packageId.toString());
        detail.put("keyPrefix", prefix);
        return repository
                .insertAudit(
                        UUID.randomUUID(),
                        tenantId,
                        userId,
                        action,
                        keyId.toString(),
                        writeJson(detail))
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(
                        new IllegalStateException("managed_api_key_audit_failed")))
                .then();
    }

    private Mono<Void> writeRotationAudit(
            UUID operationId,
            UUID tenantId,
            UUID actorUserId,
            ManagedApiKeyRepository.ManagedKeyForRotationRow oldKey,
            UUID newKeyId,
            String newPrefix) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("operationId", operationId.toString());
        detail.put("actorUserId", actorUserId.toString());
        detail.put("targetUserId", oldKey.userId().toString());
        detail.put("packageInstanceId", oldKey.packageId().toString());
        detail.put("oldKeyId", oldKey.id().toString());
        detail.put("oldKeyPrefix", oldKey.prefix());
        detail.put("newKeyId", newKeyId.toString());
        detail.put("newKeyPrefix", newPrefix);
        return repository.insertAudit(
                        operationId,
                        tenantId,
                        actorUserId,
                        "MANAGED_API_KEY_ROTATE",
                        oldKey.id().toString(),
                        writeJson(detail))
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "managed_api_key_rotation_audit_failed")))
                .then();
    }

    /**
     * 套餐里授予的模型别名，按**字母序**返回。
     *
     * <p>这个集合会以 JSON 数组的形式出现在 Bootstrap 响应（{@code allowedModels}）里，
     * 因此顺序必须确定：{@code Set.copyOf} 的迭代顺序未定义（OpenJDK 按进程随机盐），
     * 会让同一个套餐在不同进程里下发不同顺序的数组，客户端的相等比较与缓存会因此变成偶发。
     */
    private Set<String> readPackageModels(String json) {
        try {
            Set<String> models = new LinkedHashSet<>();
            for (JsonNode node : objectMapper.readTree(json)) {
                String code = node.path("modelCode").asText();
                if (!code.isBlank()) {
                    models.add(code);
                }
            }
            return Collections.unmodifiableSortedSet(new TreeSet<>(models));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "managed_api_key_models_deserialization_failed", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "managed_api_key_serialization_failed", exception);
        }
    }

    public record ClientBootstrap(
            String apiKey,
            UUID packageInstanceId,
            String packageCode,
            Instant packageExpiresAt,
            Set<String> allowedModels,
            String usageDimension) {
    }

    public record ManagedKeyRotation(
            UUID operationId,
            UUID oldKeyId,
            UUID newKeyId,
            String newKeyPrefix,
            String apiKey,
            boolean secretAvailable,
            String status) {
    }

    private record ActivePackage(
            UUID id,
            String packageCode,
            Instant expiresAt,
            Set<String> allowedModels,
            boolean refundInProgress,
            String usageDimension) {
    }

    private record ManagedKey(UUID id, String prefix, byte[] ciphertext) {
    }

    private record RotationWork(
            ManagedKeyRotation response,
            String oldHash,
            String newHash) {
    }
}
