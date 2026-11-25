package com.yuzhihuihe.apiplatform.gateway.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.yuzhihuihe.apiplatform.gateway.audio.AudioAdapterRegistry;
import com.yuzhihuihe.apiplatform.gateway.image.ImageAdapterRegistry;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ProviderTypeCapabilitiesView;
import com.yuzhihuihe.apiplatform.gateway.video.VideoAdapterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * 供应商类型的能力目录。
 *
 * <p><b>它存在的理由是一次真实事故</b>：运营把对话/视觉渠道挂到了
 * {@code provider_type = TIANYIYUN} 的供应商下。天翼云的那个 key 明明能跑
 * 对话与读图（厂商侧 {@code /v1/models} 里有 {@code Qwen2.5-VL-72B-Instruct}），
 * 但 {@code TIANYIYUN} 这个类型**只有生图/视频适配器**，于是每一条聊天请求都在
 * 运行时以 503 {@code upstream_unavailable} 失败，控制台却看不出原因——
 * 「配好了」和「能跑」之间隔着一个只有代码才知道的能力矩阵。
 *
 * <p>因此这里把散落在各适配器 bean 里的能力**读出来**，让管理端在保存路由的那一刻
 * 就能判断「这个类型到底能不能服务这个模型」，并把原因直接回给运营，
 * 而不是等真实请求失败。能力矩阵来自 bean，不来自硬编码表：新增一个适配器
 * 就自动出现在这里。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class ProviderCapabilityCatalog {

    /** 管理端在保存路由被拒时使用的稳定原因码（控制台据此给中文提示）。 */
    public static final String UNAVAILABLE_REASON = "provider_capability_unavailable";

    private static final String CAPABILITY_TEXT = "TEXT";
    private static final String CAPABILITY_IMAGE = "IMAGE";
    private static final String CAPABILITY_FILE = "FILE";
    private static final String CAPABILITY_IMAGE_GEN = "IMAGE_GEN";
    private static final String CAPABILITY_VIDEO = "VIDEO";

    /**
     * 三个音频能力名，与 V93 挂在三个音频别名上的标记一一对应。
     *
     * <p>它们与 {@code IMAGE_GEN}、{@code VIDEO} 同类：描述的是**产出**，
     * 而不是输入模态。一个音频别名只带其中一个，因为转写、合成与换口型
     * 是三件不同的事，而管理端必须在保存路由时就能分辨。
     */
    private static final Set<String> AUDIO_CAPABILITIES =
            Set.of("AUDIO_TRANSCRIBE", "AUDIO_SYNTHESIS", "LIP_SYNC");

    private final Map<String, Set<ChannelRouter.Capability>> chatCapabilities;
    private final ImageAdapterRegistry imageAdapters;
    private final VideoAdapterRegistry videoAdapters;
    private final AudioAdapterRegistry audioAdapters;

    public ProviderCapabilityCatalog(
            List<ProviderAdapter> chatAdapters,
            ImageAdapterRegistry imageAdapters,
            VideoAdapterRegistry videoAdapters,
            AudioAdapterRegistry audioAdapters) {
        Map<String, Set<ChannelRouter.Capability>> byType = new LinkedHashMap<>();
        for (ProviderAdapter adapter : chatAdapters) {
            byType.put(normalize(adapter.providerType()), Set.copyOf(adapter.capabilities()));
        }
        this.chatCapabilities = Map.copyOf(byType);
        this.imageAdapters = imageAdapters;
        this.videoAdapters = videoAdapters;
        this.audioAdapters = audioAdapters;
    }

    // **这里刻意只有一个构造器。**
    //
    // 曾经还有一个三参数的重载（音频注册表默认为 null），目的是让既有测试不必
    // 多传一个协作者。它让整个网关起不来：Spring 在一个类上遇到多个构造器、
    // 而其中没有任何一个标注 @Autowired 时，会退回去找**无参**构造器，于是以
    //     NoSuchMethodException: ProviderCapabilityCatalog.<init>()
    // 失败。这个失败表现为"ApplicationContext 无法加载"，一次拖垮 282 个测试，
    // 而错误信息里连"构造器"这个词都不出现。
    // 需要"没有音频适配器"的调用方显式传 null —— 那是一个被支持的取值
    // （见 supportsAudio 与 audioCapabilities 里的空判断），但必须是写出来的。

    /** 该类型下对话适配器声明的能力；没有对话适配器时为空集。 */
    public Set<ChannelRouter.Capability> chatCapabilities(String providerType) {
        return chatCapabilities.getOrDefault(normalize(providerType), Set.of());
    }

    public boolean supportsChat(String providerType) {
        return chatCapabilities.containsKey(normalize(providerType));
    }

    public boolean supportsImageGeneration(String providerType) {
        return imageAdapters.supportsProviderType(normalize(providerType));
    }

    public boolean supportsVideoGeneration(String providerType) {
        return videoAdapters.supportsProviderType(normalize(providerType));
    }

    /** 该类型能否服务**任何一种**按秒计费的音频能力。 */
    public boolean supportsAudio(String providerType) {
        return audioAdapters != null
                && audioAdapters.supportsProviderType(normalize(providerType));
    }

    /**
     * 该类型能服务的**具体**音频能力名，按字母序；没有音频适配器时为空集。
     *
     * <p>返回具体名字而不是布尔值，因为「能做音频」在选型时没有信息量：
     * 一个供应商完全可以只做转写而不做换口型。
     *
     * <p>顺序是**字母序**：这些名字会被序列化给控制台，而注册表给出的顺序
     * 未定义（{@code Set.copyOf}），同一个类型在不同进程里会渲染成不同顺序。
     */
    public Set<String> audioCapabilities(String providerType) {
        if (audioAdapters == null) {
            return Set.of();
        }
        return Collections.unmodifiableSortedSet(
                new TreeSet<>(audioAdapters.capabilitiesOf(normalize(providerType))));
    }

    /** 目录里出现过的全部供应商类型（对话 ∪ 生图 ∪ 视频），按字母序。 */
    public List<String> providerTypes() {
        Set<String> types = new TreeSet<>(chatCapabilities.keySet());
        for (ProviderTypeCapabilitiesView view : describe()) {
            types.add(view.providerType());
        }
        return List.copyOf(types);
    }

    /**
     * 一个模型能否挂在该供应商类型下。
     *
     * @return 可服务时返回 {@code null}；不可服务时返回 {@link #UNAVAILABLE_REASON}
     */
    public String unavailableReason(String providerType, Set<String> modelCapabilities) {
        String type = normalize(providerType);
        Set<String> capabilities = upper(modelCapabilities);
        if (capabilities.contains(CAPABILITY_IMAGE_GEN)) {
            return imageAdapters.supportsProviderType(type) ? null : UNAVAILABLE_REASON;
        }
        if (capabilities.contains(CAPABILITY_VIDEO)) {
            return videoAdapters.supportsProviderType(type) ? null : UNAVAILABLE_REASON;
        }
        // 音频：模型声明的每一个音频能力都必须由某个适配器提供。
        // 用 containsAll 而不是「任意一个」：一个同时声明转写与换口型的别名
        // 若被挂到一个只做转写的供应商下，另一个能力会在运行时才失败，
        // 而那正是本类存在要提前阻止的事。
        Set<String> requiredAudio = new LinkedHashSet<>(capabilities);
        requiredAudio.retainAll(AUDIO_CAPABILITIES);
        if (!requiredAudio.isEmpty()) {
            return audioCapabilities(type).containsAll(requiredAudio)
                    ? null : UNAVAILABLE_REASON;
        }
        Set<ChannelRouter.Capability> required = new LinkedHashSet<>();
        required.add(ChannelRouter.Capability.TEXT);
        if (capabilities.contains(CAPABILITY_IMAGE)) {
            required.add(ChannelRouter.Capability.IMAGE);
        }
        if (capabilities.contains(CAPABILITY_FILE)) {
            required.add(ChannelRouter.Capability.FILE);
        }
        Set<ChannelRouter.Capability> available = chatCapabilities.get(type);
        return available != null && available.containsAll(required) ? null : UNAVAILABLE_REASON;
    }

    /**
     * 供控制台展示的能力目录。
     *
     * <p>三类能力分别来自各自的适配器 bean：只有对话适配器的类型（如
     * {@code DEEPSEEK}）会显示 `chatCapabilities=[TEXT]` 且两个生成能力都是 false，
     * 运营一眼就能看出「它服务不了读图」。
     */
    public List<ProviderTypeCapabilitiesView> describe() {
        Set<String> types = new TreeSet<>(chatCapabilities.keySet());
        types.addAll(declaredTypes());
        List<ProviderTypeCapabilitiesView> views = new ArrayList<>(types.size());
        for (String type : types) {
            Set<String> names = new TreeSet<>();
            for (ChannelRouter.Capability capability : chatCapabilities(type)) {
                names.add(capability.name());
            }
            views.add(new ProviderTypeCapabilitiesView(
                    type,
                    Collections.unmodifiableSortedSet(new TreeSet<>(names)),
                    imageAdapters.supportsProviderType(type),
                    videoAdapters.supportsProviderType(type),
                    audioCapabilities(type)));
        }
        return List.copyOf(views);
    }

    /**
     * 三类生成适配器的类型集合。
     *
     * <p>没有任何注册表暴露「全部类型」，因此用各自支持的类型探测一遍已知枚举；
     * 这样做的好处是**不需要**为了目录去改三个注册表的封装，代价是新类型要么出现在
     * 对话适配器里，要么需要在此登记。现有四种类型都在这里。
     *
     * <p>音频必须一并探测：一个只服务音频的类型若只被生图/视频探测，
     * 它会从目录里消失，于是运营在控制台里看不到它——而那正是本类
     * 要消除的「配好了却看不出来」。
     */
    private Set<String> declaredTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (String candidate : List.of("DEEPSEEK", "BAILIAN", "MOCK", "TIANYIYUN")) {
            if (imageAdapters.supportsProviderType(candidate)
                    || videoAdapters.supportsProviderType(candidate)
                    || supportsAudio(candidate)) {
                types.add(candidate);
            }
        }
        return types;
    }

    private static String normalize(String providerType) {
        return providerType == null ? "" : providerType.strip().toUpperCase(Locale.ROOT);
    }

    private static Set<String> upper(Set<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.strip().toUpperCase(Locale.ROOT));
                }
            }
        }
        return normalized;
    }
}
