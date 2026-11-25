package com.yuzhihuihe.apiplatform.gateway.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * 目录下发的生图参数表。
 *
 * <p>这些断言盯着的是本缺陷本身：{@code resolutionTier} 只是**价格档位**，
 * 客户端据此渲染「清晰度」时永远只看到一档；而「比例」此前连数据来源都没有。
 * 因此测试分两头：一头确认档位清单来自**适配器画像**而不是那一个价格档位，
 * 另一头确认比例与像素尺寸是**算出来并经网关规则复核**的，而不是抄来的常量。
 */
class ImageModelCatalogServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("98000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("98000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID =
            UUID.fromString("98000000-0000-4000-8000-000000000003");

    private static final String ALIAS = "image-basic";
    private static final String TIANYI_MODEL = "doubao-seedream-5.0-pro";

    /**
     * 目录该报的一次请求张数上限。
     *
     * <p>刻意**不写死数字**：它与 {@code ImageGenerationService.MAX_FANOUT} 是同一个
     * 事实的两处表述，写死会让"改了上限却忘了改目录"这种偏差在测试里看不出来——
     * 而那正是客户端会照着点、然后在提交时才失败的场景。
     */
    private static final int FANOUT_LIMIT = fanoutLimitFromService();

    private static int fanoutLimitFromService() {
        try {
            var field = ImageGenerationService.class.getDeclaredField("MAX_FANOUT");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException unavailable) {
            throw new AssertionError(
                    "ImageGenerationService.MAX_FANOUT 不存在，目录与端点已失去共同事实",
                    unavailable);
        }
    }

    private final ImageRouteRepository repository = mock(ImageRouteRepository.class);

    /** 真实适配器 + 真实注册表：能力必须从厂商画像来，而不是测试自己编。 */
    private final ImageAdapterRegistry registry = new ImageAdapterRegistry(List.of(
            new TianYiYunImageGenerationAdapter(WebClient.builder(), new ObjectMapper())));

    private final ImageModelCatalogService service =
            new ImageModelCatalogService(repository, registry);

    @Test
    void publishesEverySubmittableSizeTierNotJustThePricedOne() {
        givenModels(row(ALIAS, "图片基础版", "2K"));
        givenRoutes(ALIAS, route("TIANYIYUN", TIANYI_MODEL));

        ImageModelCatalogService.ImageParameters parameters = only().parameters();

        // 价格档位原样保留（它仍是提交与计费的口径）……
        assertThat(only().resolutionTier()).isEqualTo("2K");
        assertThat(parameters.soldSizeTier()).isEqualTo("2K");
        // ……但客户端渲染清晰度用的是这两个清单：模型支持什么，现在能提交什么。
        // 顺序是清晰度的自然阶梯，不是字符串升序（否则 1.5K 会排在 1K 前面）。
        // 4K 已于 2026-10-13 被实测删除（厂商 400 InvalidParameter 拒绝），
        // 因此两个清单现在只差在「在售档位」这一条上。
        assertThat(parameters.supportedSizeTiers())
                .containsExactly("1K", "1.5K", "2K");
        // 1.5K 从"能读不能交"变成一等档位（网关 V115 + 平台 V106）：它比在售的 2K
        // 便宜，因此按「不得大于在售档位」的规则可提交。
        assertThat(parameters.submittableSizeTiers()).containsExactly("1K", "1.5K", "2K");
    }

    @Test
    void publishesAspectRatiosAsPixelSizesTheGatewayItselfAccepts() {
        givenModels(row(ALIAS, "图片基础版", "2K"));
        givenRoutes(ALIAS, route("TIANYIYUN", TIANYI_MODEL));

        ImageModelCatalogService.ImageParameters parameters = only().parameters();

        assertThat(parameters.aspectRatios())
                .containsExactly("16:9", "1:1", "3:4", "4:3", "9:16");
        assertThat(parameters.aspectRatioSizes())
                .containsEntry("1:1", "2048x2048")
                .containsEntry("4:3", "2048x1536")
                .containsEntry("3:4", "1536x2048")
                .containsEntry("16:9", "2048x1152")
                .containsEntry("9:16", "1152x2048");
        // 每一个下发出去的像素尺寸都必须真的归约到在售档位，否则客户端照目录
        // 提交就会被 image_size_not_sold_by_model 拒绝——这正是本测试要挡住的回归。
        assertThat(parameters.aspectRatioSizes().values())
                .allSatisfy(size -> assertThat(ImageGenerationService.sizeTier(size))
                        .isEqualTo(parameters.soldSizeTier()));
        // 键集合与顺序契约：与 aspectRatios 相同，字符串升序。
        assertThat(parameters.aspectRatioSizes().keySet())
                .containsExactlyElementsOf(parameters.aspectRatios());
    }

    @Test
    void publishesTheOtherControlsTheClientRenders() {
        givenModels(row(ALIAS, "图片基础版", "2K"));
        givenRoutes(ALIAS, route("TIANYIYUN", TIANYI_MODEL));

        ImageModelCatalogService.ImageParameters parameters = only().parameters();

        assertThat(parameters.explicitPixelSize()).isTrue();
        assertThat(parameters.minPixels()).isEqualTo(921_600L);
        assertThat(parameters.maxPixels()).isEqualTo(4_624_220L);
        assertThat(parameters.minAspectRatio()).isEqualTo(1.0 / 16.0);
        assertThat(parameters.maxAspectRatio()).isEqualTo(16.0);
        // 一次请求最多买几张。V114 起这是 4（网关并发扇出四次厂商调用），不再是 1：
        // 厂商侧实测静默忽略 n，所以「一次厂商调用出 N 张」不存在，但「一次请求买
        // 4 张」现在是真的能交付的，目录必须如实报出来，否则客户端会以为多张不可提交。
        assertThat(parameters.maxImages()).isEqualTo(FANOUT_LIMIT);
        assertThat(parameters.maxInputImages()).isEqualTo(8);
        assertThat(parameters.watermarkSupported()).isTrue();
        assertThat(parameters.layerDecompositionSupported()).isTrue();
        // 图层拆分只接受档位：拆出来的每一层都要与它在原图里的比例一致。
        assertThat(parameters.pixelSizeWithLayerDecomposition()).isFalse();
        assertThat(parameters.responseFormats()).containsExactly("b64_json", "url");
    }

    /**
     * 文档口径与代码口径**分叉时**，缺口必须被报出来而不是被抹平；
     * 两者收敛之后，缺口必须**消失**。
     *
     * <p>今天两个尺寸档位缺口都已收敛：{@code 1.5K} 由网关 V115 并进词表与受理清单，
     * {@code 4K} 由 2026-10-13 的直连实测删除（厂商 {@code 400 InvalidParameter}，
     * {@code param=size}）。因此 {@code operator} 里只剩那条口径说明。
     *
     * <p>这条断言是双向的：如果哪天有人把 4K 加回受理清单、或把文档口径改成与
     * 受理清单不一致，`declared_size_tier_not_in_vendor_documentation` 会立刻回来，
     * 这条用例就会红——它挡的是「缺口被静默抹平」，也挡「收敛被悄悄改回去」。
     */
    @Test
    void reportsSizeTierGapsOnlyWhileTheyExist() {
        givenModels(row(ALIAS, "图片基础版", "2K"));
        givenRoutes(ALIAS, route("TIANYIYUN", TIANYI_MODEL));

        assertThat(only().parameters().operator())
                .containsExactly(
                        "aspect_ratios_are_a_platform_whitelist_of_the_supported_range");
    }

    @Test
    void reportsNothingSubmittableWhenNoRouteCanBeResolved() {
        givenModels(row(ALIAS, "图片基础版", "2K"));
        givenRoutes(ALIAS);

        ImageModelCatalogService.ImageParameters parameters = only().parameters();

        assertThat(parameters.supportedSizeTiers()).isEmpty();
        assertThat(parameters.submittableSizeTiers()).isEmpty();
        assertThat(parameters.aspectRatios()).isEmpty();
        assertThat(parameters.aspectRatioSizes()).isEmpty();
        // 未确认的边界必须是 null（序列化时被省略），而不是 0：
        // 0 会被客户端渲染成「最小 0 像素」。
        assertThat(parameters.minPixels()).isNull();
        assertThat(parameters.maxPixels()).isNull();
        assertThat(parameters.maxInputImages()).isZero();
        assertThat(parameters.operator())
                .containsExactly(ImageModelCatalogService.UPSTREAM_CAPABILITIES_UNCONFIGURED);
    }

    @Test
    void treatsAnUnknownUpstreamModelAsUnconfirmedRatherThanOmnipotent() {
        givenModels(row(ALIAS, "图片基础版", "2K"));
        givenRoutes(ALIAS, route("TIANYIYUN", "some-unrecorded-model"));

        ImageModelCatalogService.ImageParameters parameters = only().parameters();

        assertThat(parameters.supportedSizeTiers()).isEmpty();
        assertThat(parameters.submittableSizeTiers()).isEmpty();
        assertThat(parameters.operator())
                .containsExactly(ImageModelCatalogService.UPSTREAM_CAPABILITIES_UNCONFIGURED);
    }

    @Test
    void dropsRoutesWhoseProviderTypeHasNoAdapter() {
        givenModels(row(ALIAS, "图片基础版", "2K"));
        givenRoutes(ALIAS, route("SOME-UNWIRED-PROVIDER", TIANYI_MODEL));

        assertThat(only().parameters().operator())
                .containsExactly(ImageModelCatalogService.UPSTREAM_CAPABILITIES_UNCONFIGURED);
    }

    @Test
    void reportsAnAliasWhoseSoldTierIsNotRegistered() {
        givenModels(row(ALIAS, "图片基础版", ""));
        givenRoutes(ALIAS, route("TIANYIYUN", TIANYI_MODEL));

        ImageModelCatalogService.ImageParameters parameters = only().parameters();

        assertThat(parameters.soldSizeTier()).isEmpty();
        assertThat(parameters.submittableSizeTiers()).isEmpty();
        assertThat(parameters.operator())
                .containsExactly(ImageModelCatalogService.SOLD_SIZE_TIER_UNREGISTERED);
    }

    @Test
    void hidesAliasesTheKeyIsNotAllowedToUse() {
        givenModels(row(ALIAS, "图片基础版", "2K"));
        givenRoutes(ALIAS, route("TIANYIYUN", TIANYI_MODEL));

        ApiKeyService.ApiKeyContext other = new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), TENANT_ID, USER_ID, PACKAGE_ID, Set.of("text-basic"));

        assertThat(service.availableModels(other).collectList().block()).isEmpty();
    }

    /**
     * 序列化形态本身就是契约：客户端按字段名读取，因此这里断言的是**报文**，
     * 而不是内存里的那个 record。
     */
    @Test
    void serializesEveryParameterUnderItsDocumentedName() throws Exception {
        givenModels(row(ALIAS, "图片基础版", "2K"));
        givenRoutes(ALIAS, route("TIANYIYUN", TIANYI_MODEL));

        com.fasterxml.jackson.databind.JsonNode json = new ObjectMapper()
                .valueToTree(only()).path("parameters");

        assertThat(json.path("soldSizeTier").asText()).isEqualTo("2K");
        assertThat(json.path("supportedSizeTiers").get(1).asText()).isEqualTo("1.5K");
        assertThat(json.path("submittableSizeTiers").get(0).asText()).isEqualTo("1K");
        // 1.5K 必须在**可提交**清单里也出现一次：只出现在 supported 里正是本次要
        // 修掉的那个"能读不能交"。
        assertThat(json.path("submittableSizeTiers").get(1).asText()).isEqualTo("1.5K");
        assertThat(json.path("submittableSizeTiers").get(2).asText()).isEqualTo("2K");
        assertThat(json.path("aspectRatioSizes").path("16:9").asText()).isEqualTo("2048x1152");
        assertThat(json.path("maxImages").asInt()).isEqualTo(FANOUT_LIMIT);
        assertThat(json.path("maxInputImages").asInt()).isEqualTo(8);
        assertThat(json.path("watermarkSupported").asBoolean()).isTrue();
        assertThat(json.path("pixelSizeWithLayerDecomposition").asBoolean()).isFalse();
        assertThat(json.path("responseFormats").get(0).asText()).isEqualTo("b64_json");
        assertThat(json.path("operator").isArray()).isTrue();
    }

    /** 未确认的边界不能以 0 出现：0 会被客户端渲染成「最小 0 像素」。 */
    @Test
    void omitsUnconfirmedBoundariesInsteadOfReportingZero() {
        givenModels(row(ALIAS, "图片基础版", "2K"));
        givenRoutes(ALIAS);

        com.fasterxml.jackson.databind.JsonNode json = new ObjectMapper()
                .valueToTree(only()).path("parameters");

        assertThat(json.has("minPixels")).isFalse();
        assertThat(json.has("maxPixels")).isFalse();
        assertThat(json.has("minAspectRatio")).isFalse();
        assertThat(json.has("maxAspectRatio")).isFalse();
    }

    private ImageModelCatalogService.ImageModelView only() {
        List<ImageModelCatalogService.ImageModelView> views = views();
        assertThat(views).hasSize(1);
        return views.get(0);
    }

    private List<ImageModelCatalogService.ImageModelView> views() {
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), TENANT_ID, USER_ID, PACKAGE_ID, Set.of(ALIAS));
        return service.availableModels(context).collectList().block();
    }

    private void givenModels(ImageRouteRepository.ImageModelCatalogRow... rows) {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID))
                .thenReturn(Flux.fromArray(rows));
    }

    private void givenRoutes(String alias, ImageRouteRepository.ImageCatalogRoute... routes) {
        when(repository.catalogRoutes(alias)).thenReturn(Flux.fromArray(routes));
    }

    private static ImageRouteRepository.ImageModelCatalogRow row(
            String alias, String displayName, String resolutionTier) {
        return new ImageRouteRepository.ImageModelCatalogRow(alias, displayName, resolutionTier);
    }

    private static ImageRouteRepository.ImageCatalogRoute route(
            String providerType, String upstreamModel) {
        return new ImageRouteRepository.ImageCatalogRoute(providerType, upstreamModel);
    }
}
