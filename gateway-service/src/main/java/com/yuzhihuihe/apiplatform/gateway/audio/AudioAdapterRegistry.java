package com.yuzhihuihe.apiplatform.gateway.audio;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * 查找能够服务给定供应商类型的音频适配器。
 *
 * <p><b>为什么是三个映射而不是一个。</b>音频链路上的三个能力有不同的协议形状
 * ——转写与合成是同步的，换口型是异步任务——因此它们对应三个不同的接口，
 * 而一个供应商可以只实现其中一部分。百炼现在三个都实现，
 * 但把它表达成「一个类型一个适配器」会强迫每个新供应商也实现全部三个，
 * 或者在运行时抛「未实现」。三个映射让「这个供应商能做哪几件音频的事」
 * 成为一个可以被查询的事实，而能力目录正好需要这个答案。
 *
 * <p>与 {@code VideoAdapterRegistry} 相同的注册语义：同一个能力下出现两个
 * 适配器是启动期故障，而不是运行时挑一个。原因是同样的——把一次付费转写
 * 静默路由到另一个厂商是一起计费事故，而它应当让应用起不来，
 * 而不是等第一位客户的账单出来才发现。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class AudioAdapterRegistry {

    private final Map<String, AudioTranscriptionAdapter> transcriptionByProviderType;
    private final Map<String, AudioSynthesisAdapter> synthesisByProviderType;
    private final Map<String, LipSyncAdapter> lipSyncByProviderType;

    AudioAdapterRegistry(
            List<AudioTranscriptionAdapter> transcriptionAdapters,
            List<AudioSynthesisAdapter> synthesisAdapters,
            List<LipSyncAdapter> lipSyncAdapters) {
        this.transcriptionByProviderType = index(
                transcriptionAdapters,
                AudioTranscriptionAdapter::providerType,
                "duplicate_audio_transcription_adapter");
        this.synthesisByProviderType = index(
                synthesisAdapters,
                AudioSynthesisAdapter::providerType,
                "duplicate_audio_synthesis_adapter");
        this.lipSyncByProviderType = index(
                lipSyncAdapters,
                LipSyncAdapter::providerType,
                "duplicate_lip_sync_adapter");
    }

    private static <T> Map<String, T> index(
            List<T> adapters, java.util.function.Function<T, String> typeOf, String failure) {
        Map<String, T> registered = new LinkedHashMap<>();
        for (T adapter : adapters) {
            String type = normalize(typeOf.apply(adapter));
            T previous = registered.putIfAbsent(type, adapter);
            if (previous != null) {
                throw new IllegalStateException(failure + ":" + type);
            }
        }
        return Map.copyOf(registered);
    }

    /**
     * @throws UnsupportedAudioProviderException 当没有任何转写适配器能服务它时
     */
    AudioTranscriptionAdapter transcriptionFor(String providerType) {
        return require(transcriptionByProviderType, providerType, "transcription");
    }

    /**
     * @throws UnsupportedAudioProviderException 当没有任何合成适配器能服务它时
     */
    AudioSynthesisAdapter synthesisFor(String providerType) {
        return require(synthesisByProviderType, providerType, "synthesis");
    }

    /**
     * @throws UnsupportedAudioProviderException 当没有任何换口型适配器能服务它时
     */
    LipSyncAdapter lipSyncFor(String providerType) {
        return require(lipSyncByProviderType, providerType, "lip_sync");
    }

    private static <T> T require(Map<String, T> registered, String providerType, String ability) {
        T adapter = providerType == null ? null : registered.get(normalize(providerType));
        if (adapter == null) {
            throw new UnsupportedAudioProviderException(providerType, ability);
        }
        return adapter;
    }

    boolean supportsTranscription(String providerType) {
        return providerType != null
                && transcriptionByProviderType.containsKey(normalize(providerType));
    }

    boolean supportsSynthesis(String providerType) {
        return providerType != null
                && synthesisByProviderType.containsKey(normalize(providerType));
    }

    /**
     * 所有已注册的合成适配器，供目录把各自声明的内置音色下发。
     *
     * <p>返回顺序是注册序，目录会自行排序；这里不承诺任何顺序。
     */
    List<AudioSynthesisAdapter> synthesisAdapters() {
        return List.copyOf(synthesisByProviderType.values());
    }

    boolean supportsLipSync(String providerType) {
        return providerType != null
                && lipSyncByProviderType.containsKey(normalize(providerType));
    }

    /**
     * 管理端（能力目录 / 路由守卫）读取用：该供应商类型能否服务**任何一种**
     * 按秒计费的音频能力。
     *
     * <p>「任何一种」而不是「全部」：路由守卫要回答的是「这个类型能不能挂这个
     * 模型」，而模型自己带着能力标记（{@code AUDIO_TRANSCRIBE} /
     * {@code AUDIO_SYNTHESIS} / {@code LIP_SYNC}），细粒度的匹配由
     * {@code ProviderCapabilityCatalog} 按能力做。这里只回答粗粒度问题。
     */
    public boolean supportsProviderType(String providerType) {
        return supportsTranscription(providerType)
                || supportsSynthesis(providerType)
                || supportsLipSync(providerType);
    }

    /**
     * 该供应商类型能服务的音频能力名集合，按字母序。
     *
     * <p>能力目录用它回答「这个类型能做什么音频的事」，因此它必须说出
     * **具体**的能力名，而不是一个布尔值——运营在控制台里要看的是
     * 「能转写但不能换口型」这种粒度。
     */
    public java.util.Set<String> capabilitiesOf(String providerType) {
        String type = normalize(providerType);
        java.util.Set<String> capabilities = new java.util.TreeSet<>();
        if (transcriptionByProviderType.containsKey(type)) {
            capabilities.add("AUDIO_TRANSCRIBE");
        }
        if (synthesisByProviderType.containsKey(type)) {
            capabilities.add("AUDIO_SYNTHESIS");
        }
        if (lipSyncByProviderType.containsKey(type)) {
            capabilities.add("LIP_SYNC");
        }
        return java.util.Set.copyOf(capabilities);
    }

    private static String normalize(String providerType) {
        return providerType == null ? "" : providerType.strip().toUpperCase(Locale.ROOT);
    }

    /** 渠道存在但没有任何东西能驱动它：这是配置故障，不是用户错误。 */
    static final class UnsupportedAudioProviderException extends RuntimeException {
        UnsupportedAudioProviderException(String providerType, String ability) {
            super("unsupported_audio_provider:" + ability + ":"
                    + (providerType == null ? "null" : providerType));
        }
    }
}
