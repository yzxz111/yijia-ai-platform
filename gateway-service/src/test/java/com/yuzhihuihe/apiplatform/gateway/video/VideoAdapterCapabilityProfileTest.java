package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.video.VideoGenerationAdapter.SubmissionProfile;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 能力画像与提交校验必须描述同一份契约。
 *
 * <p>这两个方法服务于同一份厂商文档，却会被两份不同的代码读：
 * {@code validateSubmission} 在提交时拒绝非法请求，
 * {@code submissionProfile} 让目录在提交**之前**把边界告诉客户端。
 * 两者一旦漂移，客户就会看到目录里可选的参数在提交时被 422 拒绝——
 * 那正是这次改动要消除的问题。因此这里的断言都成对出现：
 * 目录声称可用的取值，{@code validateSubmission} 必须放行。
 */
class VideoAdapterCapabilityProfileTest {

    private static final BailianVideoGenerationAdapter BAILIAN =
            new BailianVideoGenerationAdapter(WebClient.builder(), new ObjectMapper());
    private static final TianYiYunVideoGenerationAdapter TIANYI =
            new TianYiYunVideoGenerationAdapter(WebClient.builder(), new ObjectMapper());

    @Test
    void bailianWan25OnlyAcceptsFiveOrTenSecondsAndSaysSo() {
        SubmissionProfile profile = profile(BAILIAN, "wan2.5-t2v-preview");

        assertThat(profile.durationOptions()).containsExactly(5, 10);
        assertThat(profile.enumeratesDurations()).isTrue();
        assertThat(profile.minDurationSeconds()).isEqualTo(5);
        assertThat(profile.maxDurationSeconds()).isEqualTo(10);
        assertThat(profile.tiers()).containsExactlyInAnyOrder("480P", "720P", "1080P");
        // 480P 文生视频只收三种画幅，因此全模态共同可用的画幅比图生视频窄。
        assertThat(profile.mode("TEXT_TO_VIDEO").aspectRatios())
                .containsExactlyInAnyOrder("16:9", "9:16", "1:1");
        assertThat(profile.mode("IMAGE_TO_VIDEO").aspectRatios())
                .containsExactlyInAnyOrder("16:9", "9:16", "1:1", "4:3", "3:4");
        assertThat(profile.aspectRatios())
                .containsExactlyInAnyOrder("16:9", "9:16", "1:1", "4:3", "3:4");
        // Wan 不暴露音频开关，目录不得承诺它。
        assertThat(profile.audioSupported()).isFalse();

        // 目录说「只有 5 秒和 10 秒」，校验就必须放行这两个、
        // 并且只有这两个，否则端点会收下一段客户从未被报价的时长。
        assertThat(submits(BAILIAN, "wan2.5-t2v-preview", 5, "1080P", "16:9")).isTrue();
        assertThat(submits(BAILIAN, "wan2.5-t2v-preview", 10, "720P", "9:16")).isTrue();
        assertThat(submits(BAILIAN, "wan2.5-t2v-preview", 7, "720P", "16:9")).isFalse();
    }

    @Test
    void bailianWan26OffersTwoToFifteenSecondsAndNo480p() {
        SubmissionProfile profile = profile(BAILIAN, "wan2.6-t2v");

        assertThat(profile.durationOptions()).isNull();
        assertThat(profile.enumeratesDurations()).isFalse();
        assertThat(profile.minDurationSeconds()).isEqualTo(2);
        assertThat(profile.maxDurationSeconds()).isEqualTo(15);
        assertThat(profile.tiers()).containsExactlyInAnyOrder("720P", "1080P");
        assertThat(profile.aspectRatios())
                .containsExactlyInAnyOrder("16:9", "9:16", "1:1", "4:3", "3:4");
        assertThat(profile.imageInputSupported()).isTrue();

        assertThat(submits(BAILIAN, "wan2.6-t2v", 2, "720P", "16:9")).isTrue();
        assertThat(submits(BAILIAN, "wan2.6-t2v", 15, "1080P", "3:4")).isTrue();
        assertThat(submits(BAILIAN, "wan2.6-t2v", 1, "720P", "16:9")).isFalse();
        assertThat(submits(BAILIAN, "wan2.6-t2v", 16, "720P", "16:9")).isFalse();
        // 目录只说 720P/1080P，因此 480P 请求必须被拒。
        assertThat(submits(BAILIAN, "wan2.6-t2v", 5, "480P", "16:9")).isFalse();
    }

    @Test
    void bailianKlingTurboReportsNoAudioWhileOmniReportsAudio() {
        SubmissionProfile turbo = profile(BAILIAN, "kling/kling-v3-turbo-video-generation");
        SubmissionProfile omni = profile(BAILIAN, "kling/kling-v3-omni-video-generation");

        assertThat(turbo.audioSupported()).isFalse();
        assertThat(omni.audioSupported()).isTrue();
        for (SubmissionProfile profile : new SubmissionProfile[] {turbo, omni}) {
            assertThat(profile.minDurationSeconds()).isEqualTo(3);
            assertThat(profile.maxDurationSeconds()).isEqualTo(15);
            assertThat(profile.tiers()).containsExactlyInAnyOrder("720P", "1080P");
            assertThat(profile.aspectRatios())
                    .containsExactlyInAnyOrder("16:9", "9:16", "1:1");
        }

        assertThat(submits(BAILIAN, "kling/kling-v3-turbo-video-generation", 3, "720P", "16:9"))
                .isTrue();
        assertThat(submits(BAILIAN, "kling/kling-v3-turbo-video-generation", 16, "720P", "16:9"))
                .isFalse();
        // Kling 不收 4:3，目录与校验都必须拒绝它。
        assertThat(submits(BAILIAN, "kling/kling-v3-turbo-video-generation", 5, "720P", "4:3"))
                .isFalse();
    }

    @Test
    void tianyiReports720pFulfilledByIts768pTierAndNoImageInput() {
        SubmissionProfile profile = profile(TIANYI, "minimax-h3-max");

        // 厂商只有 480P/768P，目录必须把它翻译回平台档位 480P/720P，
        // 而不是把 768P 这个平台不存在的档位报给客户端。
        assertThat(profile.tiers()).containsExactlyInAnyOrder("480P", "720P");
        assertThat(profile.tiers()).doesNotContain("1080P", "768P");
        assertThat(profile.minDurationSeconds()).isEqualTo(5);
        assertThat(profile.maxDurationSeconds()).isEqualTo(15);
        assertThat(profile.aspectRatios())
                .containsExactlyInAnyOrder("16:9", "4:3", "1:1", "3:4", "9:16", "21:9");
        // 该端点不接受首帧，因此目录不得声称图生视频可用。
        assertThat(profile.imageInputSupported()).isFalse();
        assertThat(profile.mode("IMAGE_TO_VIDEO").resolutions()).isEmpty();

        assertThat(submits(TIANYI, "minimax-h3-max", 5, "720P", "21:9")).isTrue();
        assertThat(submits(TIANYI, "minimax-h3-max", 15, "480P", "16:9")).isTrue();
        assertThat(submits(TIANYI, "minimax-h3-max", 4, "720P", "16:9")).isFalse();
        // 该厂商无法履约 1080P，目录与校验都必须拒绝。
        assertThat(submits(TIANYI, "minimax-h3-max", 5, "1080P", "16:9")).isFalse();
    }

    @Test
    void anUnknownModelIsReportedAsGeneratingNothing() {
        SubmissionProfile profile = profile(BAILIAN, "wan2.5-i2v-preview");

        // 已登记的图像模型仍然是一份可查询的画像；关键是未知模型
        // 绝不回退成「什么都支持」。
        assertThat(profile.tiers()).isNotEmpty();

        SubmissionProfile unknown = profile(TIANYI, "some-unreleased-model");
        assertThat(unknown.tiers()).isEmpty();
        assertThat(unknown.modes())
                .allSatisfy(mode -> assertThat(mode.resolutions()).isEmpty());
        assertThat(unknown.imageInputSupported()).isFalse();
    }

    /** 画像必须能对着只带渠道的请求构造出来：目录没有提示词、也没有首帧。 */
    @Test
    void profilesNeedNothingButTheChannel() {
        for (String upstream : new String[] {
                "wan2.5-t2v-preview", "wan2.6-t2v", "kling/kling-v3-turbo-video-generation"}) {
            assertThat(profile(BAILIAN, upstream).modes()).isNotEmpty();
        }
        assertThat(profile(TIANYI, "minimax-h3-max").modes()).isNotEmpty();
    }

    private static SubmissionProfile profile(
            VideoGenerationAdapter adapter, String upstreamModel) {
        return adapter.submissionProfile(request(adapter.providerType(), upstreamModel));
    }

    private static VideoGenerationAdapter.TaskRequest request(
            String providerType, String upstreamModel) {
        return VideoSubmissionProfiles.profileRequest(providerType, upstreamModel);
    }

    /**
     * 用一份请求真的走一次适配器校验。
     *
     * @return 校验是否放行；<b>只有</b> 422 表示「上游不收这个参数」，
     *         其他异常都是实现错误，必须冒泡而不是被当成拒绝
     */
    private static boolean submits(
            VideoGenerationAdapter adapter,
            String upstreamModel,
            int durationSeconds,
            String resolutionTier,
            String aspectRatio) {
        VideoGenerationAdapter.TaskRequest request = new VideoGenerationAdapter.TaskRequest(
                new VideoGenerationAdapter.Channel(
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(),
                        adapter.providerType(),
                        "https://vendor.example.com",
                        "secret",
                        upstreamModel),
                "req-profile-consistency",
                "a lighthouse at dusk",
                null,
                durationSeconds,
                resolutionTier,
                aspectRatio,
                false,
                60_000);
        try {
            adapter.validateSubmission(request);
            return true;
        } catch (org.springframework.web.server.ResponseStatusException rejected) {
            assertThat(rejected.getStatusCode().value()).isEqualTo(422);
            return false;
        }
    }
}
