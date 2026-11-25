package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class VideoModelCatalogServiceTest {

    private static final UUID API_KEY_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PACKAGE_ID = UUID.randomUUID();

    private VideoRouteRepository repository;
    private VideoAdapterRegistry adapterRegistry;
    private VideoModelCatalogService catalog;

    @BeforeEach
    void setUp() {
        repository = mock(VideoRouteRepository.class);
        adapterRegistry = mock(VideoAdapterRegistry.class);
        catalog = new VideoModelCatalogService(repository, adapterRegistry);
        // 默认没有任何档位映射：既有单档位用例走原路径（plain Mockito 无 strict stubbing）。
        when(repository.clientTierMappings(any(), any(), any(), anyString()))
                .thenReturn(Flux.empty());
    }

    @Test
    void returnsOnlyKeyAllowedAliasesWithoutCheckingProviderReadiness() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID)).thenReturn(Flux.just(
                row("video-basic", "Video Basic", "720P"),
                row("video-basic", "Video Basic", "720P"),
                row("video-budget", "Video Budget", "480P"),
                // 目录没有供应商/就绪状态的维度。允许该别名的 key 会
                // 拿到它，即便其上游路由尚未配置。
                row("video-max", "Video Max", "1080P")));
        when(repository.resolutionTier(anyString())).thenReturn(Mono.empty());
        when(repository.catalogRoutes(anyString())).thenReturn(Flux.empty());
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID,
                Set.of("video-basic", "video-max"));

        List<VideoModelCatalogService.VideoModelView> views =
                catalog.availableModels(context).collectList().block();

        assertThat(views).extracting(VideoModelCatalogService.VideoModelView::id)
                .containsExactly("video-basic", "video-max");
        assertThat(views).extracting(VideoModelCatalogService.VideoModelView::resolutionTier)
                .containsExactly("720P", "1080P");
        assertThat(views).extracting(VideoModelCatalogService.VideoModelView::usageDimension)
                .containsExactly("VIDEO_SECOND", "VIDEO_SECOND");
    }

    @Test
    void aKeyWithoutAnActivePackageHasNoVideoCatalogue() {
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, null, Set.of("video-basic"));

        assertThat(catalog.availableModels(context).collectList().block()).isEmpty();
        verifyNoInteractions(repository);
    }

    /**
     * 没有可解析路由时，目录必须区分「平台会放行什么」与「上游能生成什么」：
     * 前者是确定的，后者没有证据，因此分辨率/模态/音频一律报空，
     * 并用 operator 说明原因。
     */
    @Test
    void reportsNoGeneratableParametersWhenNoRouteCanBeInterpreted() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID))
                .thenReturn(Flux.just(row("video-basic", "Video Basic", "720P")));
        when(repository.resolutionTier("video-basic")).thenReturn(Mono.just("720P"));
        when(repository.catalogRoutes("video-basic")).thenReturn(Flux.just(
                new VideoRouteRepository.VideoCatalogRoute("SOME_UNKNOWN_VENDOR", "model-x")));
        when(adapterRegistry.supports("SOME_UNKNOWN_VENDOR")).thenReturn(false);

        VideoModelCatalogService.VideoParameters parameters = parameters("video-basic");

        assertThat(parameters.modes()).isEmpty();
        assertThat(parameters.upstreamResolutionTiers()).isEmpty();
        assertThat(parameters.imageInputSupported()).isFalse();
        assertThat(parameters.withAudioSupported()).isFalse();
        // 平台自己的边界仍然照实上报：客户端至少知道 1–60 秒会被网关接受。
        assertThat(parameters.durationSeconds().min()).isEqualTo(1);
        assertThat(parameters.durationSeconds().max()).isNull();
        assertThat(parameters.platformMaxSeconds()).isEqualTo(60);
        assertThat(parameters.operator()).containsExactly("upstream_capabilities_unconfigured");
    }

    /**
     * 上游可生成的档位多于别名售卖的那一档时，只报告交集：
     * 一个只卖 720P 的别名不能在目录里被说成也能出 480P 或 1080P。
     */
    @Test
    void reportsOnlyTheTierTheAliasSellsEvenWhenUpstreamOffersMore() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID))
                .thenReturn(Flux.just(row("video-basic", "Video Basic", "720P")));
        when(repository.resolutionTier("video-basic")).thenReturn(Mono.just("720P"));
        when(repository.catalogRoutes("video-basic")).thenReturn(Flux.just(
                new VideoRouteRepository.VideoCatalogRoute("MOCK", "wan2.6-t2v")));
        when(adapterRegistry.supports("MOCK")).thenReturn(true);
        when(adapterRegistry.forProviderType("MOCK")).thenReturn(new StubAdapter(
                VideoGenerationAdapter.SubmissionProfile.gatewayDefaults()));

        VideoModelCatalogService.VideoParameters parameters = parameters("video-basic");

        assertThat(parameters.soldResolutionTier()).isEqualTo("720P");
        assertThat(parameters.upstreamResolutionTiers())
                .containsExactlyInAnyOrder("480P", "720P", "1080P");
        assertThat(parameters.aspectRatios())
                .containsExactly("16:9", "1:1", "3:4", "4:3", "9:16");
        assertThat(parameters.durationSeconds().min()).isEqualTo(1);
        assertThat(parameters.durationSeconds().max()).isEqualTo(60);
        // 平台默认画像同时描述两种输入模态，且都只放行别名售卖的那一档。
        assertThat(parameters.modes()).hasSize(2);
        assertThat(parameters.modes()).allSatisfy(mode -> {
            assertThat(mode.resolutions()).containsExactly("720P");
            assertThat(mode.durationSeconds().options()).isEmpty();
            assertThat(mode.aspectRatios())
                    .containsExactly("16:9", "1:1", "3:4", "4:3", "9:16");
        });
        assertThat(parameters.modes()).extracting(
                        VideoModelCatalogService.ModeView::mode)
                .containsExactly("TEXT_TO_VIDEO", "IMAGE_TO_VIDEO");
        assertThat(parameters.operator()).isEmpty();
    }

    /**
     * 一条只收枚举时长的路由（百炼 Wan 2.5 只收 5/10 秒）必须把
     * 「哪些秒数真的能提交」告诉客户端，而不只是给一个区间：
     * 区间里的 7 秒会被上游拒绝。
     */
    @Test
    void reportsEnumeratedDurationsSoClientsNeverOfferARejectedValue() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID))
                .thenReturn(Flux.just(row("video-budget", "Video Budget", "480P")));
        when(repository.resolutionTier("video-budget")).thenReturn(Mono.just("480P"));
        when(repository.catalogRoutes("video-budget")).thenReturn(Flux.just(
                new VideoRouteRepository.VideoCatalogRoute("MOCK", "wan2.5-t2v-preview")));
        when(adapterRegistry.supports("MOCK")).thenReturn(true);
        when(adapterRegistry.forProviderType("MOCK")).thenReturn(new StubAdapter(
                new VideoGenerationAdapter.SubmissionProfile(
                        List.of(
                                new VideoGenerationAdapter.ModeProfile(
                                        VideoModelCatalogService.MODE_TEXT_TO_VIDEO,
                                        Set.of("480P", "720P", "1080P"),
                                        Set.of("16:9", "9:16", "1:1"))),
                        5,
                        10,
                        List.of(5, 10),
                        false)));

        VideoModelCatalogService.VideoParameters parameters = parameters("video-budget");

        assertThat(parameters.durationSeconds().min()).isEqualTo(5);
        assertThat(parameters.durationSeconds().max()).isEqualTo(10);
        assertThat(parameters.durationSeconds().options()).containsExactly(5, 10);
        assertThat(parameters.modes()).singleElement().satisfies(mode -> {
            assertThat(mode.resolutions()).containsExactly("480P");
            assertThat(mode.aspectRatios()).containsExactly("16:9", "1:1", "9:16");
        });
        assertThat(parameters.operator())
                .containsExactly("duration_is_enumerated_not_continuous");
        // 图生视频模态在上游声明里不存在，因此目录不得声称它可用。
        assertThat(parameters.imageInputSupported()).isFalse();
    }

    /**
     * 别名登记的档位没有任何路由能履约时，目录必须报空分辨率并说明原因，
     * 而不是把可达档位当成可售档位。
     */
    @Test
    void flagsARegisteredTierThatNoRouteCanFulfil() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID))
                .thenReturn(Flux.just(row("video-budget", "Video Budget", "480P")));
        when(repository.resolutionTier("video-budget")).thenReturn(Mono.just("480P"));
        when(repository.catalogRoutes("video-budget")).thenReturn(Flux.just(
                new VideoRouteRepository.VideoCatalogRoute("MOCK", "wan2.6-t2v")));
        when(adapterRegistry.supports("MOCK")).thenReturn(true);
        when(adapterRegistry.forProviderType("MOCK")).thenReturn(new StubAdapter(
                new VideoGenerationAdapter.SubmissionProfile(
                        List.of(new VideoGenerationAdapter.ModeProfile(
                                VideoModelCatalogService.MODE_TEXT_TO_VIDEO,
                                Set.of("720P", "1080P"),
                                Set.of("16:9"))),
                        2,
                        15,
                        null,
                        false)));

        VideoModelCatalogService.VideoParameters parameters = parameters("video-budget");

        assertThat(parameters.modes()).isEmpty();
        assertThat(parameters.operator())
                .containsExactly("sold_tier_not_supported_by_upstream");
    }

    /**
     * 多条路由的区间取交集（保守），因为提交时只会在其中一条上校验：
     * 3–15 秒的渠道与 5–10 秒的渠道同时存在时，只有 5–10 秒一定可提交。
     */
    @Test
    void intersectsDurationAndRatiosAcrossRoutes() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID))
                .thenReturn(Flux.just(row("video-pro", "Video Pro", "720P")));
        when(repository.resolutionTier("video-pro")).thenReturn(Mono.just("720P"));
        when(repository.catalogRoutes("video-pro")).thenReturn(Flux.just(
                new VideoRouteRepository.VideoCatalogRoute("MOCK", "kling-like"),
                new VideoRouteRepository.VideoCatalogRoute("BAILIAN", "wan-like")));
        when(adapterRegistry.supports("MOCK")).thenReturn(true);
        when(adapterRegistry.supports("BAILIAN")).thenReturn(true);
        when(adapterRegistry.forProviderType("MOCK")).thenReturn(new StubAdapter(
                new VideoGenerationAdapter.SubmissionProfile(
                        List.of(new VideoGenerationAdapter.ModeProfile(
                                VideoModelCatalogService.MODE_TEXT_TO_VIDEO,
                                Set.of("720P", "1080P"),
                                Set.of("16:9", "9:16", "1:1"))),
                        3,
                        15,
                        null,
                        true)));
        when(adapterRegistry.forProviderType("BAILIAN")).thenReturn(new StubAdapter(
                new VideoGenerationAdapter.SubmissionProfile(
                        List.of(new VideoGenerationAdapter.ModeProfile(
                                VideoModelCatalogService.MODE_TEXT_TO_VIDEO,
                                Set.of("720P", "1080P"),
                                Set.of("16:9", "1:1", "4:3"))),
                        5,
                        10,
                        null,
                        false)));

        VideoModelCatalogService.VideoParameters parameters = parameters("video-pro");

        assertThat(parameters.durationSeconds().min()).isEqualTo(5);
        assertThat(parameters.durationSeconds().max()).isEqualTo(10);
        // 音频必须两条渠道都支持才敢承诺：一条不支持时开关会被静默忽略，
        // 客户会以为买到的是带音频的视频。
        assertThat(parameters.withAudioSupported()).isFalse();
        assertThat(parameters.modes()).singleElement().satisfies(mode -> {
            assertThat(mode.resolutions()).containsExactly("720P");
            assertThat(mode.aspectRatios()).containsExactly("16:9", "1:1");
        });
    }

    /**
     * 同一别名挂两个**真实适配器**时的合并结果。
     *
     * <p>这不是假想场景：仓库种子里 {@code video-basic} 同时挂在百炼
     * （{@code wan2.6-t2v}，2–15 秒、五个画幅、支持首帧）与天翼云
     * （{@code minimax-h3-max}，5–15 秒、六个画幅、**不收首帧**）上，
     * 因此客户端看到的不是任何一条路由的能力，而是它们的交集。
     * 这条断言就是文档里那张"合并前后对照表"的机器可读版本。
     */
    @Test
    void intersectsTwoRealVendorContractsIntoTodaysAnswer() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID))
                .thenReturn(Flux.just(row("video-basic", "Video Basic", "720P")));
        when(repository.resolutionTier("video-basic")).thenReturn(Mono.just("720P"));
        when(repository.catalogRoutes("video-basic")).thenReturn(Flux.just(
                new VideoRouteRepository.VideoCatalogRoute("BAILIAN", "wan2.6-t2v"),
                new VideoRouteRepository.VideoCatalogRoute("TIANYIYUN", "minimax-h3-max")));
        VideoAdapterRegistry real = new VideoAdapterRegistry(List.of(
                new BailianVideoGenerationAdapter(WebClient.builder(), new ObjectMapper()),
                new TianYiYunVideoGenerationAdapter(WebClient.builder(), new ObjectMapper())));
        VideoModelCatalogService withRealAdapters =
                new VideoModelCatalogService(repository, real);
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-basic"));

        VideoModelCatalogService.VideoParameters parameters =
                withRealAdapters.availableModels(context).collectList().block().get(0).parameters();

        // 时长：2–15 与 5–15 的交集。天翼只给区间、不给枚举，所以合并后
        // 也没有枚举值——即使另一条路由（Wan 2.5 那类）只收 5/10 秒。
        assertThat(parameters.durationSeconds().min()).isEqualTo(5);
        assertThat(parameters.durationSeconds().max()).isEqualTo(15);
        assertThat(parameters.durationSeconds().options()).isEmpty();
        // 上游档位：{720P,1080P} ∩ {480P,720P} = {720P}
        assertThat(parameters.upstreamResolutionTiers()).containsExactly("720P");
        // 画幅：两条路由的文生契约都收这五种（天翼的文生也收 4:3/3:4，
        // 另有 21:9 被交集砍掉），因此交集是五种。
        assertThat(parameters.aspectRatios())
                .containsExactly("16:9", "1:1", "3:4", "4:3", "9:16");
        assertThat(parameters.modes()).singleElement().satisfies(mode -> {
            assertThat(mode.mode()).isEqualTo("TEXT_TO_VIDEO");
            assertThat(mode.resolutions()).containsExactly("720P");
            assertThat(mode.aspectRatios())
                    .containsExactly("16:9", "1:1", "3:4", "4:3", "9:16");
        });
        // 首帧：百炼支持、天翼**不支持** → 天翼那条路由的图生模态
        // 分辨率集合为空，交集后整个 IMAGE_TO_VIDEO 从目录里消失。
        // 这是本用例最重要的一条：生产上 video-basic 正是这个形态，
        // 与「只配百炼」时的 2–15 秒 + 支持首帧**不同**。
        assertThat(parameters.imageInputSupported()).isFalse();
        assertThat(parameters.withAudioSupported()).isFalse();
        assertThat(parameters.operator()).isEmpty();
    }

    /**
     * 同一套代码、同一别名，只配一条路由与配两条路由给出**不同**答案。
     *
     * <p>这就是"客户端必须读接口、不能写死"的机器可读证明：单路由给出
     * 2–15 秒 + 两个模态；加上天翼那条之后变成 5–15 秒 + 只剩文生。
     */
    @Test
    void theSameAliasAnswersDifferentlyOnceASecondRouteIsConfigured() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID))
                .thenReturn(Flux.just(row("video-basic", "Video Basic", "720P")));
        when(repository.resolutionTier("video-basic")).thenReturn(Mono.just("720P"));
        VideoAdapterRegistry real = new VideoAdapterRegistry(List.of(
                new BailianVideoGenerationAdapter(WebClient.builder(), new ObjectMapper()),
                new TianYiYunVideoGenerationAdapter(WebClient.builder(), new ObjectMapper())));
        VideoModelCatalogService withRealAdapters =
                new VideoModelCatalogService(repository, real);
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-basic"));

        when(repository.catalogRoutes("video-basic")).thenReturn(Flux.just(
                new VideoRouteRepository.VideoCatalogRoute("BAILIAN", "wan2.6-t2v")));
        VideoModelCatalogService.VideoParameters onlyBailian =
                withRealAdapters.availableModels(context).collectList().block().get(0).parameters();

        when(repository.catalogRoutes("video-basic")).thenReturn(Flux.just(
                new VideoRouteRepository.VideoCatalogRoute("BAILIAN", "wan2.6-t2v"),
                new VideoRouteRepository.VideoCatalogRoute("TIANYIYUN", "minimax-h3-max")));
        VideoModelCatalogService.VideoParameters withTianyi =
                withRealAdapters.availableModels(context).collectList().block().get(0).parameters();

        // 单路由：Wan 2.6 的完整契约。
        assertThat(onlyBailian.durationSeconds().min()).isEqualTo(2);
        assertThat(onlyBailian.imageInputSupported()).isTrue();
        assertThat(onlyBailian.modes()).extracting(VideoModelCatalogService.ModeView::mode)
                .containsExactly("TEXT_TO_VIDEO", "IMAGE_TO_VIDEO");

        // 加上天翼那条：区间收缩、图生模态消失。
        assertThat(withTianyi.durationSeconds().min()).isEqualTo(5);
        assertThat(withTianyi.imageInputSupported()).isFalse();
        assertThat(withTianyi.modes()).extracting(VideoModelCatalogService.ModeView::mode)
                .containsExactly("TEXT_TO_VIDEO");
    }

    /**
     * V110 合并模型：目录里只有一个「MiniMax」入口，可提交分辨率按档位逐个
     * 判定（480P 由 video-budget 履约、720P 由 video-minimax 履约），时长与
     * 画幅是跨档位的保守交集——客户端选任何档位都不会 422。
     */
    @Test
    void consolidatedModelListsEntitledTiersWithConservativeCrossTierCapabilities() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID))
                .thenReturn(Flux.just(row("video-minimax", "MiniMax", "720P")));
        when(repository.clientTierMappings(any(), any(), any(), anyString()))
                .thenReturn(Flux.just(
                        new VideoRouteRepository.ClientTierRow("480P", "video-budget"),
                        new VideoRouteRepository.ClientTierRow("720P", "video-minimax")));
        when(repository.catalogRoutes("video-budget")).thenReturn(Flux.just(
                new VideoRouteRepository.VideoCatalogRoute("MOCK_BUDGET", "wan2.5-like")));
        when(repository.catalogRoutes("video-minimax")).thenReturn(Flux.just(
                new VideoRouteRepository.VideoCatalogRoute("MOCK_MINIMAX", "wan2.6-like")));
        when(adapterRegistry.supports("MOCK_BUDGET")).thenReturn(true);
        when(adapterRegistry.supports("MOCK_MINIMAX")).thenReturn(true);
        when(adapterRegistry.forProviderType("MOCK_BUDGET")).thenReturn(new StubAdapter(
                new VideoGenerationAdapter.SubmissionProfile(
                        List.of(new VideoGenerationAdapter.ModeProfile(
                                VideoModelCatalogService.MODE_TEXT_TO_VIDEO,
                                Set.of("480P"),
                                Set.of("16:9", "1:1", "9:16"))),
                        5, 10, List.of(5, 10), false)));
        when(adapterRegistry.forProviderType("MOCK_MINIMAX")).thenReturn(new StubAdapter(
                new VideoGenerationAdapter.SubmissionProfile(
                        List.of(new VideoGenerationAdapter.ModeProfile(
                                VideoModelCatalogService.MODE_TEXT_TO_VIDEO,
                                Set.of("720P"),
                                Set.of("16:9", "1:1", "3:4", "4:3", "9:16"))),
                        2, 15, null, false)));

        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-minimax"));
        VideoModelCatalogService.VideoModelView view =
                catalog.availableModels(context).collectList().block().get(0);

        assertThat(view.id()).isEqualTo("video-minimax");
        assertThat(view.displayName()).isEqualTo("MiniMax");
        assertThat(view.resolutionTier()).isEqualTo("720P");
        VideoModelCatalogService.VideoParameters parameters = view.parameters();
        assertThat(parameters.modes()).singleElement().satisfies(mode -> {
            assertThat(mode.mode()).isEqualTo("TEXT_TO_VIDEO");
            assertThat(mode.resolutions()).containsExactly("480P", "720P");
            assertThat(mode.aspectRatios()).containsExactly("16:9", "1:1", "9:16");
        });
        // 时长：5–10（枚举）与 2–15（连续）的交集 = 5–10 连续。
        assertThat(parameters.durationSeconds().min()).isEqualTo(5);
        assertThat(parameters.durationSeconds().max()).isEqualTo(10);
        assertThat(parameters.durationSeconds().options()).isEmpty();
        assertThat(parameters.operator()).isEmpty();
    }

    /** 套餐不持有某个档位的内部别名时，该档位不出现在可提交分辨率里。 */
    @Test
    void consolidatedModelDropsTiersThePackageDoesNotHold() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID))
                .thenReturn(Flux.just(row("video-minimax", "MiniMax", "720P")));
        when(repository.clientTierMappings(any(), any(), any(), anyString()))
                .thenReturn(Flux.just(
                        new VideoRouteRepository.ClientTierRow("720P", "video-minimax")));
        when(repository.catalogRoutes("video-minimax")).thenReturn(Flux.just(
                new VideoRouteRepository.VideoCatalogRoute("MOCK", "wan2.6-like")));
        when(adapterRegistry.supports("MOCK")).thenReturn(true);
        when(adapterRegistry.forProviderType("MOCK")).thenReturn(new StubAdapter(
                new VideoGenerationAdapter.SubmissionProfile(
                        List.of(new VideoGenerationAdapter.ModeProfile(
                                VideoModelCatalogService.MODE_TEXT_TO_VIDEO,
                                Set.of("480P", "720P"),
                                Set.of("16:9", "1:1"))),
                        2, 15, null, false)));

        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-minimax"));
        VideoModelCatalogService.VideoParameters parameters =
                catalog.availableModels(context).collectList().block().get(0).parameters();

        assertThat(parameters.modes()).singleElement().satisfies(mode ->
                assertThat(mode.resolutions()).containsExactly("720P"));
    }

    /** 某个档位的内部别名没有任何可解析路由时，只把该档位从清单里去掉。 */
    @Test
    void consolidatedModelDropsTiersWhoseInternalAliasHasNoUsableRoute() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID))
                .thenReturn(Flux.just(row("video-minimax", "MiniMax", "720P")));
        when(repository.clientTierMappings(any(), any(), any(), anyString()))
                .thenReturn(Flux.just(
                        new VideoRouteRepository.ClientTierRow("480P", "video-budget"),
                        new VideoRouteRepository.ClientTierRow("720P", "video-minimax")));
        when(repository.catalogRoutes("video-budget")).thenReturn(Flux.just(
                new VideoRouteRepository.VideoCatalogRoute("SOME_UNKNOWN_VENDOR", "model-x")));
        when(repository.catalogRoutes("video-minimax")).thenReturn(Flux.just(
                new VideoRouteRepository.VideoCatalogRoute("MOCK", "wan2.6-like")));
        when(adapterRegistry.supports("SOME_UNKNOWN_VENDOR")).thenReturn(false);
        when(adapterRegistry.supports("MOCK")).thenReturn(true);
        when(adapterRegistry.forProviderType("MOCK")).thenReturn(new StubAdapter(
                new VideoGenerationAdapter.SubmissionProfile(
                        List.of(new VideoGenerationAdapter.ModeProfile(
                                VideoModelCatalogService.MODE_TEXT_TO_VIDEO,
                                Set.of("720P"),
                                Set.of("16:9"))),
                        2, 15, null, false)));

        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-minimax"));
        VideoModelCatalogService.VideoParameters parameters =
                catalog.availableModels(context).collectList().block().get(0).parameters();

        assertThat(parameters.modes()).singleElement().satisfies(mode ->
                assertThat(mode.resolutions()).containsExactly("720P"));
    }

    private VideoModelCatalogService.VideoParameters parameters(String alias) {
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of(alias));
        return catalog.availableModels(context)
                .collectList()
                .block()
                .get(0)
                .parameters();
    }

    private static VideoRouteRepository.VideoModelCatalogRow row(
            String alias, String displayName, String resolutionTier) {
        return new VideoRouteRepository.VideoModelCatalogRow(
                alias, displayName, resolutionTier);
    }

    /** 只实现能力画像的适配器；提交与轮询不参与目录测试。 */
    private static final class StubAdapter implements VideoGenerationAdapter {

        private final SubmissionProfile profile;

        StubAdapter(SubmissionProfile profile) {
            this.profile = profile;
        }

        @Override
        public String providerType() {
            return "STUB";
        }

        @Override
        public Mono<SubmittedTask> submit(TaskRequest request) {
            return Mono.error(new UnsupportedOperationException("not used by the catalogue"));
        }

        @Override
        public Mono<TaskStatus> poll(TaskRequest request, String upstreamTaskId) {
            return Mono.error(new UnsupportedOperationException("not used by the catalogue"));
        }

        @Override
        public Set<String> capabilities() {
            return Set.of("VIDEO");
        }

        @Override
        public SubmissionProfile submissionProfile(TaskRequest request) {
            return profile;
        }
    }
}
