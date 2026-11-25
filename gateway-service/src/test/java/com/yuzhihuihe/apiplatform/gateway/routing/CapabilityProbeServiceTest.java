package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 钉住「每种能力打到哪个端点」。
 *
 * <p>这正是本次修复的全部价值所在：原有的密钥探测无论如何都只请求
 * {@code {base}/models}，而那是一条只属于对话/视觉的端点——百炼的音频、视频、
 * 生图模型都不在它上面，于是一把正确的 key 在音频渠道上必然得到 404。若将来有人
 * 把分发逻辑改回"一律打 /models"，这些用例会失败，而不是等到运维在控制台上
 * 再次被 404 误导。
 */
class CapabilityProbeServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CapabilityProbeService service = new CapabilityProbeService(null, mapper);

    private CapabilityProbeService.CapabilityRow row(String upstreamModel, String... capabilities) {
        // 用 LinkedHashSet 而不是 Set.of：生产代码按声明顺序遍历能力，
        // Set.of 的无序性会让"哪一种能力先被探"变得不确定，测试也就钉不住顺序契约。
        return new CapabilityProbeService.CapabilityRow(
                upstreamModel, new java.util.LinkedHashSet<>(java.util.Arrays.asList(capabilities)));
    }

    private List<CapabilityProbeService.ProbeTarget> targets(
            String providerType, String baseUrl, CapabilityProbeService.CapabilityRow... rows) {
        return service.targetsFor(providerType, baseUrl, List.of(rows));
    }

    @Test
    void audioTranscriptionOnBailianGoesToTheAsrEndpoint() {
        var targets = targets("BAILIAN", "https://dashscope.aliyuncs.com",
                row("qwen-audio-3.0-asr-flash", "AUDIO_TRANSCRIBE"));

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).capability()).isEqualTo("AUDIO_TRANSCRIBE");
        assertThat(targets.get(0).method()).isEqualTo("POST");
        assertThat(targets.get(0).url())
                .isEqualTo("https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription");
        assertThat(targets.get(0).body().path("model").asText())
                .isEqualTo("qwen-audio-3.0-asr-flash");
    }

    @Test
    void audioTranscriptionOnTianyiSwapsTheVersionRoot() {
        var targets = targets("TIANYIYUN", "https://ai.ctaigw.cn/v1",
                row("qwen-audio-3.0-asr-flash", "AUDIO_TRANSCRIBE"));

        // 天翼是 /v1，百炼是 /api/v1 —— 设计文档里唯一确认过的差异。
        assertThat(targets.get(0).url())
                .isEqualTo("https://ai.ctaigw.cn/v1/services/audio/asr/transcription");
    }

    @Test
    void synthesisAndLipSyncGoToTheirOwnEndpoints() {
        var targets = targets("BAILIAN", "https://dashscope.aliyuncs.com",
                row("qwen-audio-3.0-tts-flash", "AUDIO_SYNTHESIS"),
                row("videoretalk", "LIP_SYNC"));

        assertThat(targets).extracting(CapabilityProbeService.ProbeTarget::url)
                .containsExactly(
                        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                        "https://dashscope.aliyuncs.com/api/v1/services/aigc/image2video/video-synthesis");
    }

    @Test
    void videoGoesToTheVideoEndpointOnBothVendors() {
        assertThat(targets("BAILIAN", "https://dashscope.aliyuncs.com",
                row("wan2.6-t2v", "VIDEO")).get(0).url())
                .isEqualTo("https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis");
        assertThat(targets("TIANYIYUN", "https://ai.ctaigw.cn",
                row("minimax-h3-max", "VIDEO")).get(0).url())
                .isEqualTo("https://ai.ctaigw.cn/v1/video_generation");
    }

    @Test
    void imageGenerationUsesEachVendorsOwnShape() {
        assertThat(targets("TIANYIYUN", "https://ai.ctaigw.cn",
                row("doubao-seedream-5.0-pro", "IMAGE_GEN")).get(0).url())
                .isEqualTo("https://ai.ctaigw.cn/v1/images/generations");
        assertThat(targets("BAILIAN", "https://dashscope.aliyuncs.com",
                row("wan2.5-t2i", "IMAGE_GEN")).get(0).url())
                .isEqualTo("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation");
    }

    @Test
    void conversationalCapabilitiesStillUseTheModelsEndpoint() {
        var targets = targets("BAILIAN", "https://ai.ctaigw.cn/v1",
                row("Qwen2.5-VL-72B-Instruct", "TEXT", "IMAGE"));

        // /models 对对话/视觉本来就是对的端点；这里断言的是"没有把它改成别的"。
        assertThat(targets).allSatisfy(target -> {
            assertThat(target.method()).isEqualTo("GET");
            assertThat(target.url()).isEqualTo("https://ai.ctaigw.cn/v1/models");
        });
    }

    @Test
    void oneMappingServingTwoAbilitiesProducesTwoProbes() {
        var targets = targets("BAILIAN", "https://dashscope.aliyuncs.com",
                row("Qwen2.5-VL-72B-Instruct", "TEXT", "IMAGE"));

        assertThat(targets).extracting(CapabilityProbeService.ProbeTarget::capability)
                .containsExactly("TEXT", "IMAGE");
    }

    @Test
    void originDropsWhateverPathTheOperatorTyped() {
        assertThat(CapabilityProbeService.origin("https://dashscope.aliyuncs.com")).isEqualTo("https://dashscope.aliyuncs.com");
        assertThat(CapabilityProbeService.origin("https://dashscope.aliyuncs.com/api/v1")).isEqualTo("https://dashscope.aliyuncs.com");
        assertThat(CapabilityProbeService.origin("https://dashscope.aliyuncs.com/api/v1/")).isEqualTo("https://dashscope.aliyuncs.com");
        assertThat(CapabilityProbeService.origin("https://dashscope.aliyuncs.com/compatible-mode/v1")).isEqualTo("https://dashscope.aliyuncs.com");
        // 整条端点也应当被归约到源站——正是截图里那种填法。
        assertThat(CapabilityProbeService.origin(
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/image2video/video-synthesis"))
                .isEqualTo("https://dashscope.aliyuncs.com");
        // 带端口
        assertThat(CapabilityProbeService.origin("http://wiremock:8080/mock-primary"))
                .isEqualTo("http://wiremock:8080");
    }

    @Test
    void versionRootFollowsWhatTheOperatorTyped() {
        assertThat(CapabilityProbeService.versionRoot("https://dashscope.aliyuncs.com")).isEmpty();
        assertThat(CapabilityProbeService.versionRoot("https://ai.ctaigw.cn/v1")).isEqualTo("/v1");
        assertThat(CapabilityProbeService.versionRoot("https://dashscope.aliyuncs.com/api/v1")).isEqualTo("/api/v1");
    }

    @Test
    void capabilityJsonIsParsedAndUnreadableInputIsIgnored() {
        assertThat(CapabilityProbeService.capabilitySet("[\"AUDIO_TRANSCRIBE\"]", mapper))
                .containsExactly("AUDIO_TRANSCRIBE");
        assertThat(CapabilityProbeService.capabilitySet("", mapper)).isEmpty();
        assertThat(CapabilityProbeService.capabilitySet("not json", mapper)).isEmpty();
    }
}
