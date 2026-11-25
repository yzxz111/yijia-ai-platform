package com.yuzhihuihe.apiplatform.gateway.routing;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.multimodal.MultimodalProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ModelCatalogService {

    private final ModelCatalogRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    ModelCatalogService(ModelCatalogRepository repository, ChannelCircuitRegistry circuits) {
        this.repository = repository;
    }

    @Autowired
    ModelCatalogService(
            ModelCatalogRepository repository,
            ChannelCircuitRegistry circuits,
            MultimodalProperties properties) {
        this.repository = repository;
    }

    public Flux<ModelView> availableModels(ApiKeyService.ApiKeyContext context) {
        if (context == null || context.allowedModels().isEmpty()) {
            return Flux.empty();
        }
        return repository
                .findEntitledModels(
                        context.packageInstanceId(), context.tenantId(), context.userId())
                .filter(model -> context.allowedModels().contains(model.alias()))
                .map(model -> new ModelView(
                        model.alias(), "model", "yuzhihuihe", model.displayName(),
                        capabilities(model.capabilitiesJson()), model.contextWindow(),
                        model.maxOutputTokens(), model.routingStrategy()))
                .sort(Comparator.comparing(ModelView::id));
    }

    /**
     * 能力数组按**字母序**返回。
     *
     * <p>这里刻意不用 {@code Set.copyOf}：它返回的不可变 Set 迭代顺序**未定义**
     * （OpenJDK 用进程启动时的随机盐），于是同一行数据在不同进程里会序列化成
     * 不同顺序的 JSON 数组——而客户端可能拿它做相等比较或缓存键。
     * 顺序一旦被观察，就必须是确定的，因此这里给一个有序集合。
     */
    private Set<String> capabilities(String json) {
        try {
            Set<String> values = new LinkedHashSet<>();
            objectMapper.readTree(json).forEach(value -> values.add(value.asText()));
            return Collections.unmodifiableSortedSet(new TreeSet<>(values));
        } catch (Exception exception) {
            throw new IllegalStateException("model_capabilities_deserialization_failed", exception);
        }
    }

    /**
     * 目录为空时的**诊断原因**（P1-5）。
     *
     * <p>在此之前四种完全不同的情况在客户端看来是同一个响应
     * （{@code 200 + data: []}）：套餐不含任何聊天模型、套餐过期/被停用、
     * Key 的快照里没有模型、或者这条 Key 根本没绑套餐。客户端因此只能笼统地
     * 显示"暂无可用模型"，无法给出"套餐已过期，请续费"这类可操作提示。
     *
     * <p>取值（稳定字符串，客户端可按需映射文案）：
     * {@code key_has_no_allowed_models}、{@code no_package_bound}、
     * {@code package_not_found}、{@code package_expired}、{@code package_not_active}、
     * {@code no_chat_models}、{@code key_allowed_models_out_of_sync}。只在结果为空的
     * 路径上计算。
     *
     * <p>{@code key_allowed_models_out_of_sync}（2026-10-09 生产故障）与
     * {@code no_chat_models} 的区别是**责任方**：前者表示套餐这一侧确实有可用的对话
     * 别名（去掉 Key 过滤后目录非空），是这把 Key 自己的名单没跟上；后者表示套餐
     * 本来就不含对话模型（按秒/按张计费的视频、图片、音频套餐）。修复方向完全不同
     * ——前者重新登录/同步即可，后者要换套餐——因此不能合并成同一个码。
     */
    Mono<String> emptyReason(ApiKeyService.ApiKeyContext context) {
        if (context == null) {
            return Mono.just("key_not_recognized");
        }
        if (context.allowedModels() == null || context.allowedModels().isEmpty()) {
            return Mono.just("key_has_no_allowed_models");
        }
        if (context.packageInstanceId() == null) {
            return Mono.just("no_package_bound");
        }
        return repository.findPackageState(context.packageInstanceId())
                .map(this::reasonFor)
                .defaultIfEmpty("package_not_found")
                .flatMap(reason -> "no_chat_models".equals(reason)
                        ? explainKeyMismatch(context, reason)
                        : Mono.just(reason));
    }

    /**
     * 套餐生效、未过期，却一个模型都没列出来：分清是"套餐本来就没有对话模型"，
     * 还是"套餐有、但这把 Key 的名单里没有"。
     */
    private Mono<String> explainKeyMismatch(
            ApiKeyService.ApiKeyContext context, String reasonWhenPackageIsEmpty) {
        return repository
                .hasEntitledChatModels(
                        context.packageInstanceId(), context.tenantId(), context.userId())
                .filter(Boolean::booleanValue)
                .map(entitled -> "key_allowed_models_out_of_sync")
                .defaultIfEmpty(reasonWhenPackageIsEmpty);
    }

    private String reasonFor(ModelCatalogRepository.PackageStateRow row) {
        if (!"ACTIVE".equals(row.status())) {
            return "package_not_active";
        }
        if (row.expiresAt() != null && !row.expiresAt().isAfter(OffsetDateTime.now())) {
            return "package_expired";
        }
        // 生效、未过期，却没有任何聊天模型：这一般是按秒/按张计费的套餐
        // （视频、图片、音频），它们的别名本来就不属于这条对话目录。
        return "no_chat_models";
    }

    public record ModelView(
            String id, String object, String ownedBy, String displayName,
            Set<String> capabilities, int contextWindow, int maxOutputTokens,
            String routingStrategy) {
        public ModelView(
                String id, String object, String ownedBy, String displayName,
                Set<String> capabilities, int contextWindow, int maxOutputTokens) {
            this(id, object, ownedBy, displayName, capabilities,
                    contextWindow, maxOutputTokens, "DIRECT");
        }
    }
}
