package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.audio.AudioAdapterRegistry;
import com.yuzhihuihe.apiplatform.gateway.image.ImageAdapterRegistry;
import com.yuzhihuihe.apiplatform.gateway.provider.BailianAdapter;
import com.yuzhihuihe.apiplatform.gateway.provider.DeepSeekAdapter;
import com.yuzhihuihe.apiplatform.gateway.provider.TianYiYunChatAdapter;
import com.yuzhihuihe.apiplatform.gateway.video.VideoAdapterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 能力目录的回归测试。
 *
 * <p>它钉住的是一次真实生产事故：把对话/视觉渠道挂到 {@code TIANYIYUN} 类型下，
 * 保存时一切正常、渠道显示 HEALTHY，而每一次聊天/读图请求都以 503 失败。
 * 修复后的行为是**在保存路由时**就拒绝这种组合。
 */
class ProviderCapabilityCatalogTest {

    private static ProviderCapabilityCatalog catalog() {
        ImageAdapterRegistry images = mock(ImageAdapterRegistry.class);
        VideoAdapterRegistry videos = mock(VideoAdapterRegistry.class);
        for (String type : List.of("TIANYIYUN", "BAILIAN", "MOCK")) {
            when(images.supportsProviderType(type)).thenReturn(true);
            when(videos.supportsProviderType(type)).thenReturn(true);
        }
        // 音频只有百炼（V93 的三个别名都挂在 bailian-audio-primary 下），
        // 因此 MOCK 与 TIANYIYUN 在这个模拟里**没有**音频能力。
        // 这正是测试要覆盖的粒度：一个供应商可以做生成却不做音频。
        AudioAdapterRegistry audio = mock(AudioAdapterRegistry.class);
        when(audio.supportsProviderType("BAILIAN")).thenReturn(true);
        when(audio.capabilitiesOf("BAILIAN")).thenReturn(
                Set.of("AUDIO_TRANSCRIBE", "AUDIO_SYNTHESIS", "LIP_SYNC"));
        when(audio.capabilitiesOf("TIANYIYUN")).thenReturn(Set.of());
        when(audio.capabilitiesOf("MOCK")).thenReturn(Set.of());
        when(audio.capabilitiesOf("DEEPSEEK")).thenReturn(Set.of());
        // 没有生图/视频适配器的类型：DEEPSEEK。这与生产 bean 的实际情况一致。
        ObjectMapper mapper = new ObjectMapper();
        return new ProviderCapabilityCatalog(
                List.of(new BailianAdapter(WebClient.builder(), mapper),
                        new DeepSeekAdapter(WebClient.builder(), mapper),
                        new TianYiYunChatAdapter(WebClient.builder(), mapper)),
                images,
                videos,
                audio);
    }

    /**
     * 原始事故的回归守卫，**重新指向**而不是删除。
     *
     * <p>它原本断言 {@code TIANYIYUN} 不能读图，因为那时那个类型确实只有
     * 生图/视频适配器，而运营把视觉渠道挂上去会得到「保存成功、渠道
     * HEALTHY、每次请求 503」。那个缺陷是**缺一个适配器**，不是校验太松；
     * {@link TianYiYunChatAdapter} 补上了适配器，因此对 TIANYIYUN 的期望
     * 必须翻转——但这条测试要防的东西没有变：**一个没有任何对话适配器的
     * 供应商类型，绝不能被允许挂上读图路由。**
     *
     * <p>所以它现在用 {@code DEEPSEEK}（生产里确实只有 TEXT 适配器）
     * 继续守住那个不变量。删掉这条测试等于删掉整个能力守卫唯一的回归证据。
     */
    @Test
    void aProviderTypeWithNoChatAdapterCannotServeImageReadingEvenThoughItsKeyCan() {
        // DEEPSEEK 的 key 或许也能读图，但这个类型没有声明 IMAGE 的对话适配器，
        // 因此这个组合必须在保存时被拒，而不是写进去再让请求 503。
        assertThat(catalog().unavailableReason("DEEPSEEK", Set.of("TEXT", "IMAGE")))
                .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);
        assertThat(catalog().unavailableReason("MOCK", Set.of("TEXT", "IMAGE", "FILE")))
                .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);
    }

    /**
     * TianYiYunChatAdapter 落地后新成立的正面用例。
     *
     * <p>天翼的那把 key 能跑对话与读图，而现在有适配器声明了这一点，
     * 因此天翼的视觉路由从「必须被拒」变成「可以挂」。
     * 两件事必须同时为真：补上实现之后要能用，且没有实现的类型仍然被拒。
     */
    @Test
    void tianyiYunCanServeTextAndImageNowThatItsChatAdapterExists() {
        assertThat(catalog().unavailableReason("TIANYIYUN", Set.of("TEXT"))).isNull();
        assertThat(catalog().unavailableReason("TIANYIYUN", Set.of("TEXT", "IMAGE"))).isNull();
        // FILE 不在天翼适配器声明的能力里（那是 qwen-long 的专用文件接口），
        // 因此文档模型挂到天翼仍必须被拒。
        assertThat(catalog().unavailableReason("TIANYIYUN", Set.of("TEXT", "FILE")))
                .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);
    }

    @Test
    void bailianServesTextImageAndFile() {
        ProviderCapabilityCatalog catalog = catalog();
        assertThat(catalog.unavailableReason("BAILIAN", Set.of("TEXT", "IMAGE"))).isNull();
        assertThat(catalog.unavailableReason("BAILIAN", Set.of("TEXT", "FILE"))).isNull();
        assertThat(catalog.unavailableReason("BAILIAN", Set.of("TEXT"))).isNull();
    }

    @Test
    void deepSeekCannotServeImages() {
        assertThat(catalog().unavailableReason("DEEPSEEK", Set.of("TEXT"))).isNull();
        assertThat(catalog().unavailableReason("DEEPSEEK", Set.of("TEXT", "IMAGE")))
                .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);
    }

    @Test
    void generationCapabilitiesFollowTheirOwnAdapterRegistries() {
        ProviderCapabilityCatalog catalog = catalog();
        // 按张计费的生图：TIANYIYUN 有生图适配器，DEEPSEEK 没有。
        assertThat(catalog.unavailableReason("TIANYIYUN", Set.of("TEXT", "IMAGE", "IMAGE_GEN")))
                .isNull();
        assertThat(catalog.unavailableReason("DEEPSEEK", Set.of("TEXT", "IMAGE_GEN")))
                .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);
        // 按秒计费的视频：同上。
        assertThat(catalog.unavailableReason("TIANYIYUN", Set.of("TEXT", "VIDEO"))).isNull();
        assertThat(catalog.unavailableReason("DEEPSEEK", Set.of("TEXT", "VIDEO")))
                .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);
    }

    /**
     * 音频能力的守卫，粒度是**具体能力**而不是「能不能做音频」。
     *
     * <p>V93 给三个音频别名挂了三个不同的能力标记，而守卫必须逐个比对：
     * 一个供应商完全可以只做转写而不做换口型，把后者挂上去会在运行时才失败，
     * 而那正是 {@code ProviderCapabilityCatalog} 存在要提前阻止的事。
     */
    @Test
    void audioCapabilitiesAreMatchedOneAbilityAtATime() {
        ProviderCapabilityCatalog catalog = catalog();

        // 百炼三个音频能力齐全，逐个与合并都该通过。
        assertThat(catalog.unavailableReason(
                "BAILIAN", Set.of("AUDIO_TRANSCRIBE"))).isNull();
        assertThat(catalog.unavailableReason(
                "BAILIAN", Set.of("AUDIO_SYNTHESIS"))).isNull();
        assertThat(catalog.unavailableReason(
                "BAILIAN", Set.of("LIP_SYNC"))).isNull();
        assertThat(catalog.unavailableReason(
                "BAILIAN", Set.of("AUDIO_TRANSCRIBE", "AUDIO_SYNTHESIS", "LIP_SYNC")))
                .isNull();

        // 同一个 key 能读图，却没有任何音频适配器：音频别名必须被拒，
        // 而不是写进去再让每次请求 503。
        assertThat(catalog.unavailableReason(
                "TIANYIYUN", Set.of("AUDIO_TRANSCRIBE")))
                .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);
        assertThat(catalog.unavailableReason("DEEPSEEK", Set.of("LIP_SYNC")))
                .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);

        // **混合能力：只要有一个不被服务就必须整体拒绝。**
        // 这条是本次改动里最容易写错的一处——用「任意一个被服务就放行」，
        // 会让换口型那半边在运行时才失败。
        assertThat(catalog.unavailableReason(
                "BAILIAN", Set.of("AUDIO_TRANSCRIBE", "LIP_SYNC"))).isNull();
        assertThat(catalog.unavailableReason(
                "TIANYIYUN", Set.of("AUDIO_TRANSCRIBE", "LIP_SYNC")))
                .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);
    }

    /**
     * 音频能力绝不能把 TOKEN 模型误判为音频模型，反之亦然。
     *
     * <p>{@code unavailableReason} 的分支顺序决定了这件事：一个纯文本模型
     * 不含任何音频标记，因此它走的是对话那条路，与音频适配器是否存在无关。
     */
    @Test
    void audioCapabilitiesDoNotLeakIntoTheChatDecision() {
        assertThat(catalog().unavailableReason("DEEPSEEK", Set.of("TEXT"))).isNull();
        assertThat(catalog().unavailableReason("TIANYIYUN", Set.of("TEXT", "IMAGE"))).isNull();
        // 一个既没有音频适配器、也没有音频标记的组合不会因为音频分支而改变结论。
        assertThat(catalog().unavailableReason("DEEPSEEK", Set.of("TEXT", "FILE")))
                .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);
    }

    @Test
    void directoryExposesWhichTypesCanServeWhichCapabilities() {
        var views = catalog().describe();
        var tianyi = views.stream()
                .filter(view -> view.providerType().equals("TIANYIYUN"))
                .findFirst()
                .orElseThrow();
        // 天翼现在三条路都有：对话/读图（TianYiYunChatAdapter）、生图、视频。
        // 这正是运营在控制台里该看到的东西——它此前显示为空，
        // 于是「配好了」和「能跑」之间的差距只能靠真实请求失败来发现。
        assertThat(tianyi.chatCapabilities()).containsExactlyInAnyOrder("TEXT", "IMAGE");
        assertThat(tianyi.imageGeneration()).isTrue();
        assertThat(tianyi.videoGeneration()).isTrue();
        // 天翼没有音频适配器，因此目录里它的音频能力是空的——
        // 而运营在控制台里要看到的正是这个区别。
        assertThat(tianyi.audioCapabilities()).isEmpty();

        var bailian = views.stream()
                .filter(view -> view.providerType().equals("BAILIAN"))
                .findFirst()
                .orElseThrow();
        assertThat(bailian.chatCapabilities()).containsExactlyInAnyOrder("TEXT", "IMAGE", "FILE");
        assertThat(bailian.audioCapabilities()).containsExactlyInAnyOrder(
                "AUDIO_TRANSCRIBE", "AUDIO_SYNTHESIS", "LIP_SYNC");

        var deepseek = views.stream()
                .filter(view -> view.providerType().equals("DEEPSEEK"))
                .findFirst()
                .orElseThrow();
        assertThat(deepseek.chatCapabilities()).containsExactly("TEXT");
        assertThat(deepseek.imageGeneration()).isFalse();
        assertThat(deepseek.videoGeneration()).isFalse();
        assertThat(deepseek.audioCapabilities()).isEmpty();
    }

    @Test
    void unknownProviderTypeIsRejectedRatherThanAssumedCapable() {
        assertThat(catalog().unavailableReason("NOPE", Set.of("TEXT")))
                .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);
    }

    /**
     * 探测地址的候选顺序。
     *
     * <p>天翼生图渠道的 base_url 是裸域名 {@code https://ai.ctaigw.cn}，只试
     * {@code /models} 会把一个完全可用的 key 报成 401——这正是本地实测踩到的误报。
     */
    @Test
    void probeCandidatesCoverBothBaseUrlShapes() {
        assertThat(GatewayAdminService.probeCandidates("https://ai.ctaigw.cn"))
                .containsExactly("https://ai.ctaigw.cn/models", "https://ai.ctaigw.cn/v1/models");
        assertThat(GatewayAdminService.probeCandidates("https://ai.ctaigw.cn/v1/"))
                .containsExactly("https://ai.ctaigw.cn/v1/models");
        assertThat(GatewayAdminService.probeCandidates("https://api.deepseek.com/v1"))
                .containsExactly("https://api.deepseek.com/v1/models");
    }
}
