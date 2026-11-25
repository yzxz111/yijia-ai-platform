package com.yuzhihuihe.apiplatform.gateway.video;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * 查找能够服务给定供应商类型的适配器。
 *
 * <p>当两个适配器服务不同的能力时，它们可以共享同一个供应商类型 ——
 * 对话用的 {@code MockProviderAdapter} 与 {@code MockVideoGenerationAdapter} 都是
 * {@code MOCK}。因此路由依据的是解析出的渠道的供应商类型，而无法识别的类型会大声
 * 失败而不是回退到默认值，因为把视频请求静默路由到错误的厂商会是一起计费事故。
 *
 * <p>接入一个真实厂商（Wan、Kling、Seedance）只需新增一个
 * {@link VideoGenerationAdapter} bean 以及它在供应商表中的行；服务层或
 * API 层的任何代码都不用改。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class VideoAdapterRegistry {

    private final Map<String, VideoGenerationAdapter> byProviderType;

    VideoAdapterRegistry(List<VideoGenerationAdapter> adapters) {
        Map<String, VideoGenerationAdapter> registered = new LinkedHashMap<>();
        for (VideoGenerationAdapter adapter : adapters) {
            VideoGenerationAdapter previous = registered.putIfAbsent(
                    adapter.providerType(), adapter);
            if (previous != null) {
                throw new IllegalStateException(
                        "duplicate_video_adapter:" + adapter.providerType());
            }
        }
        this.byProviderType = Map.copyOf(registered);
    }

    /**
     * @param providerType 解析出的渠道的供应商类型
     * @throws UnsupportedVideoProviderException 当没有任何适配器能够服务它时
     */
    VideoGenerationAdapter forProviderType(String providerType) {
        VideoGenerationAdapter adapter = providerType == null
                ? null
                : byProviderType.get(providerType);
        if (adapter == null) {
            throw new UnsupportedVideoProviderException(providerType);
        }
        return adapter;
    }

    boolean supports(String providerType) {
        return providerType != null && byProviderType.containsKey(providerType);
    }

    /** 管理端（能力目录 / 路由守卫）读取用：该供应商类型能否服务按秒计费的视频生成。 */
    public boolean supportsProviderType(String providerType) {
        return providerType != null
                && byProviderType.containsKey(providerType.toUpperCase(java.util.Locale.ROOT));
    }

    /** 渠道存在但没有任何东西能驱动它：这是配置故障，不是用户错误。 */
    static final class UnsupportedVideoProviderException extends RuntimeException {
        UnsupportedVideoProviderException(String providerType) {
            super("unsupported_video_provider:" + (providerType == null ? "null" : providerType));
        }
    }
}
