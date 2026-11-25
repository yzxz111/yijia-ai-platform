package com.yuzhihuihe.apiplatform.gateway.audio;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 列出已认证 key 有权看到的音频别名。
 *
 * <p>这是**展示目录**，不是供应商就绪状态检查：仓库已经施加了生效的
 * {@code AUDIO_SECOND} 套餐、模型启用状态以及 {@code audio_alias_tier} 登记谓词，
 * 而上游是否真的能服务所选别名，由音频调用路由在之后检查。
 *
 * <p><b>为什么这条目录必须存在（P2-1）。</b>在此之前音频是唯一没有客户端目录的家族，
 * 客户端只能拿到 {@code allowedModels} 里的裸别名。最要命的是
 * {@code audio-asr-basic}（同步端点，**不返回时间戳**）与
 * {@code audio-asr-subtitle}（{@code -filetrans}，**带句子级时间戳**）的
 * {@code capabilities} 完全相同（都是 {@code AUDIO_TRANSCRIBE}），
 * 因此客户端无法从模型列表判断哪个能出字幕。这里把 {@code displayName}
 * 与能力一起下发，客户端至少能展示"语音识别·字幕版"这样的标签，
 * 而不是让用户在两个同能力别名里猜。
 *
 * <p>能力数组按字母序（与 {@code /v1/models} 同一条顺序契约：顺序一旦可见就必须确定）。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class AudioModelCatalogService {

    private static final String USAGE_DIMENSION = "AUDIO_SECOND";

    private final AudioRouteRepository repository;
    private final AudioAdapterRegistry adapterRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    AudioModelCatalogService(
            AudioRouteRepository repository, AudioAdapterRegistry adapterRegistry) {
        this.repository = repository;
        this.adapterRegistry = adapterRegistry;
    }

    Flux<AudioModelView> availableModels(ApiKeyService.ApiKeyContext context) {
        if (context == null
                || context.packageInstanceId() == null
                || context.allowedModels() == null
                || context.allowedModels().isEmpty()) {
            return Flux.empty();
        }
        return repository
                .availableModels(
                        context.packageInstanceId(), context.tenantId(), context.userId())
                .filter(row -> context.allowedModels().contains(row.alias()))
                .map(row -> {
                    Set<String> caps = capabilities(row.capabilities());
                    return new AudioModelView(
                            row.alias(),
                            row.displayName(),
                            caps,
                            row.billingUnit(),
                            USAGE_DIMENSION,
                            voices(caps));
                })
                .distinct(AudioModelView::id)
                .sort(Comparator.comparing(AudioModelView::id));
    }

    /**
     * 该别名的音色清单：只给合成能力（{@code AUDIO_SYNTHESIS}）下发，其余能力恒为空。
     *
     * <p>汇总所有合成适配器各自声明的内容并按名字去重、排序——顺序一旦对客户端
     * 可见就必须确定，因此这里绝不允许按 {@code Map#values()} 的迭代序裸透传。
     */
    private List<AudioSynthesisAdapter.Voice> voices(Set<String> caps) {
        if (!caps.contains("AUDIO_SYNTHESIS")) {
            return List.of();
        }
        Map<String, AudioSynthesisAdapter.Voice> byName = new TreeMap<>();
        for (AudioSynthesisAdapter adapter : adapterRegistry.synthesisAdapters()) {
            for (AudioSynthesisAdapter.Voice voice : adapter.voices()) {
                byName.putIfAbsent(voice.name(), voice);
            }
        }
        return List.copyOf(byName.values());
    }

    private Set<String> capabilities(String json) {
        try {
            Set<String> values = new LinkedHashSet<>();
            objectMapper.readTree(json).forEach(node -> values.add(node.asText()));
            return Collections.unmodifiableSortedSet(new TreeSet<>(values));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "audio_model_capabilities_deserialization_failed", exception);
        }
    }

    /**
     * @param id 逻辑别名，客户端调用时作为 {@code model} 提交
     * @param displayName 展示名（字幕版是"语音识别·字幕版"，据此可区分同步版）
     * @param capabilities 能力名，字母序；三个音频别名分别是
     *        {@code AUDIO_TRANSCRIBE} / {@code AUDIO_SYNTHESIS} / {@code LIP_SYNC}
     * @param billingUnit 该别名登记的计费单位，目前只有 {@code SECOND}；
     *        未登记时为空（{@code null}），调用方据此拒绝请求
     * @param usageDimension 计费维度，音频恒为 {@code AUDIO_SECOND}
     * @param voices 该别名可提交的内置音色清单，按名字排序；仅合成能力非空，
     *        其余能力恒为空数组
     */
    record AudioModelView(
            String id,
            String displayName,
            Set<String> capabilities,
            String billingUnit,
            String usageDimension,
            List<AudioSynthesisAdapter.Voice> voices) { }
}
