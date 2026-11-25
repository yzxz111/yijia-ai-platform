package com.yuzhihuihe.apiplatform.gateway.image;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * 查找能够服务给定供应商类型的图片生成适配器。
 *
 * <p>与 {@link com.yuzhihuihe.apiplatform.gateway.video.VideoAdapterRegistry} 分开，
 * 因为同一个供应商类型可以同时提供两种能力：{@code MOCK} 既有对话适配器，
 * 也有视频与图片两个生成适配器。因此路由依据的是解析出的渠道的供应商类型，
 * 而无法识别的类型会大声失败而不是回退到默认值，
 * 因为把生图请求静默路由到错误的厂商会是一起计费事故。
 *
 * <p>接入一个真实厂商只需新增一个 {@link ImageGenerationAdapter} bean
 * 以及它在供应商表中的行；服务层或 API 层的任何代码都不用改。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class ImageAdapterRegistry {

    private final Map<String, ImageGenerationAdapter> byProviderType;

    ImageAdapterRegistry(List<ImageGenerationAdapter> adapters) {
        Map<String, ImageGenerationAdapter> registered = new LinkedHashMap<>();
        for (ImageGenerationAdapter adapter : adapters) {
            ImageGenerationAdapter previous = registered.putIfAbsent(
                    adapter.providerType(), adapter);
            if (previous != null) {
                throw new IllegalStateException(
                        "duplicate_image_adapter:" + adapter.providerType());
            }
        }
        this.byProviderType = Map.copyOf(registered);
    }

    /**
     * @param providerType 解析出的渠道的供应商类型
     * @throws UnsupportedImageProviderException 当没有任何适配器能够服务它时
     */
    ImageGenerationAdapter forProviderType(String providerType) {
        ImageGenerationAdapter adapter = providerType == null
                ? null
                : byProviderType.get(providerType);
        if (adapter == null) {
            throw new UnsupportedImageProviderException(providerType);
        }
        return adapter;
    }

    boolean supports(String providerType) {
        return providerType != null && byProviderType.containsKey(providerType);
    }

    /** 管理端（能力目录 / 路由守卫）读取用：该供应商类型能否服务按张计费的生图。 */
    public boolean supportsProviderType(String providerType) {
        return providerType != null
                && byProviderType.containsKey(providerType.toUpperCase(java.util.Locale.ROOT));
    }

    /** 渠道存在但没有任何东西能驱动它：这是配置故障，不是用户错误。 */
    static final class UnsupportedImageProviderException extends RuntimeException {
        UnsupportedImageProviderException(String providerType) {
            super("unsupported_image_provider:" + (providerType == null ? "null" : providerType));
        }
    }
}
