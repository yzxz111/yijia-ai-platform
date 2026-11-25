package com.yuzhihuihe.apiplatform.gateway.image;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.image.ImageGenerationAdapter.ImageSubmissionProfile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 列出已认证 key 有权看到的生图别名，并回答**每个别名能生成什么参数**。
 *
 * <p>这是展示目录，不是供应商就绪状态检查。仓库已经施加了生效的
 * {@code IMAGE} 套餐、网关模型以及别名/尺寸档位谓词。
 * 上游是否真能服务所选别名，由生图路由在之后检查。
 *
 * <p>能力这一半来自路由**配置**而不是路由**健康**：一条别名指向哪些上游
 * 模型，决定了它在尺寸档位、像素尺寸与画幅上的真实边界；那些渠道今天是否
 * {@code HEALTHY}、是否已录入凭据，与「这个模型能生成什么」无关。因此目录
 * 在渠道暂时不可用时依然报告能力，客户端据此渲染的选择器不会因为一次上游
 * 抖动而变空；提交时才会因为渠道不可用收到 503。
 *
 * <p><b>为什么不能只报 {@code resolutionTier}。</b>那个字段是**价格档位**
 * （该别名按哪一档定价），不是能力清单。客户端的「清晰度」下拉框此前只能
 * 拿到这一个值，于是永远只显示一档；「比例」则连数据来源都没有——图片的
 * 厂商契约里没有画幅参数，比例要么写在提示词里由模型判断，要么由客户端
 * 换算成 {@code 宽x高}。{@link ImageParameters} 把这两件事都补齐：档位是
 * 模型支持什么、现在能提交什么，比例是平台白名单里**真的可提交**的那几条
 * 以及它们对应的像素尺寸。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class ImageModelCatalogService {

    private static final String USAGE_DIMENSION = "IMAGE";

    /** 能力无人可确认时的理由；与视频目录的同名字段同形。 */
    static final String UPSTREAM_CAPABILITIES_UNCONFIGURED = "upstream_capabilities_unconfigured";

    static final String SOLD_SIZE_TIER_UNREGISTERED = "sold_size_tier_unregistered";

    private final ImageRouteRepository repository;
    private final ImageAdapterRegistry adapterRegistry;

    ImageModelCatalogService(ImageRouteRepository repository, ImageAdapterRegistry adapterRegistry) {
        this.repository = repository;
        this.adapterRegistry = adapterRegistry;
    }

    Flux<ImageModelView> availableModels(ApiKeyService.ApiKeyContext context) {
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
                // 历史数据可能让同一个别名出现多行；能力解析只该按别名做一次。
                .distinct(ImageRouteRepository.ImageModelCatalogRow::alias)
                .concatMap(this::view)
                .sort(Comparator.comparing(ImageModelView::id));
    }

    private Mono<ImageModelView> view(ImageRouteRepository.ImageModelCatalogRow row) {
        return catalogProfiles(row.alias())
                .map(profiles -> new ImageModelView(
                        row.alias(),
                        row.displayName(),
                        row.resolutionTier(),
                        USAGE_DIMENSION,
                        parameters(row, profiles)));
    }

    /**
     * 每条已配置路由各自的画像。
     *
     * <p>无法解析的厂商类型被丢弃而不是中断整条目录：一条尚未接线的渠道
     * 不应该让客户端看不到这个模型。真正没有任何可解析路由时返回空列表，
     * 调用方随后退回「无法确认上游能力」的诚实答案。
     */
    private Mono<List<ImageSubmissionProfile>> catalogProfiles(String alias) {
        return repository.catalogRoutes(alias)
                .filter(route -> route.providerType() != null
                        && adapterRegistry.supports(route.providerType()))
                .map(route -> adapterRegistry
                        .forProviderType(route.providerType())
                        .submissionProfile(route.upstreamModel()))
                .collectList();
    }

    /**
     * 把一个别名的在售档位与它的上游能力合成一份参数表。
     *
     * <p>档位读不到时不当作「任意档位都可以」：{@code ImageGenerationService}
     * 会以 {@code image_model_size_unregistered} 拒绝这样的别名，因此这里报一份
     * 空的能力清单，客户端由此看出该别名当下不可提交，而不是看到一个乐观的默认值。
     */
    private ImageParameters parameters(
            ImageRouteRepository.ImageModelCatalogRow row, List<ImageSubmissionProfile> profiles) {
        String sold = row.resolutionTier() == null ? "" : row.resolutionTier();
        int soldRank = ImageGenerationService.tierRank(sold);
        if (soldRank < 0) {
            return unavailable(sold, SOLD_SIZE_TIER_UNREGISTERED);
        }
        if (profiles.isEmpty()) {
            return unavailable(sold, UPSTREAM_CAPABILITIES_UNCONFIGURED);
        }
        ImageSubmissionProfile profile = conservative(profiles);
        if (profile.sizeTiers().isEmpty()) {
            return unavailable(sold, UPSTREAM_CAPABILITIES_UNCONFIGURED);
        }
        // 客户端要能读到**模型支持的全部档位**，而不只是能提交的那几档：
        // 适配器受理的（当前 1K/1.5K/2K/4K）与厂商文档口径的（当前 1K/1.5K/2K）
        // 取并集，差集由 operator 说明原因。钱的事（在售档位只到 2K）只影响
        // submittableSizeTiers，不影响客户端能读到什么。
        Set<String> supportedTiers = new TreeSet<>(profile.sizeTiers());
        supportedTiers.addAll(profile.documentedSizeTiers());
        List<String> supported = sortedSizeTiers(supportedTiers);
        // 可提交 = 适配器受理 ∩ 网关词汇表 ∩ 不高于在售档位。
        // 最后一条是定价规则：更便宜的档位被允许，更贵的会把成本击穿。
        List<String> submittable = supported.stream()
                .filter(tier -> profile.sizeTiers().contains(tier))
                .filter(tier -> ImageGenerationService.tierRank(tier) >= 0)
                .filter(tier -> ImageGenerationService.tierRank(tier) <= soldRank)
                .toList();
        Map<String, String> aspectRatioSizes = aspectRatioSizes(profile, sold);
        return new ImageParameters(
                sold,
                supported,
                submittable,
                List.copyOf(aspectRatioSizes.keySet()),
                aspectRatioSizes,
                profile.explicitPixelSize(),
                profile.minPixels(),
                profile.maxPixels(),
                profile.minAspectRatio(),
                profile.maxAspectRatio(),
                // 一次请求最多能买几张。V114 起这是「一次请求」的上限（4），不是
                // 「一次厂商调用」的上限——厂商侧实测静默忽略 n，网关在自己这一侧
                // 并发扇出，因此目录必须报 4，否则客户端会以为多张仍然不可提交。
                ImageGenerationService.MAX_FANOUT,
                Math.min(profile.maxInputImages(), ImageGenerationService.MAX_INPUT_IMAGES),
                // 厂商载荷里恒有 watermark 字段（适配器按调用方的布尔值填充），
                // 因此这里是能力事实而不是猜测。
                true,
                profile.layerDecomposition(),
                profile.pixelSizeWithLayerDecomposition(),
                responseFormats(),
                operator(sold, profile, submittable, aspectRatioSizes));
    }

    /**
     * 多条路由的画像取**保守交集**。
     *
     * <p>一个别名可能同时挂在两张渠道上（同一厂商的主备，或两个厂商的等价模型），
     * 而它们的契约并不相同。合并后的答案要回答的是「客户端提什么参数一定不会
     * 被拒」，因此取交集而不是并集：只看其中一条更宽的渠道，就会让另一条上的
     * 请求在下单时才失败。
     *
     * <p>只有一个例外：{@code documentedSizeTiers} 取**并集**。它不是准入依据，
     * 而是「厂商文档声称过哪些档位」的清单，用于把「文档说支持、网关不受理」
     * 报进 {@code operator}；取交集会把要报告的缺口正好抹掉。
     */
    private static ImageSubmissionProfile conservative(List<ImageSubmissionProfile> profiles) {
        Set<String> tiers = null;
        Set<String> documented = new TreeSet<>();
        long minPixels = 0;
        long maxPixels = Long.MAX_VALUE;
        double minRatio = 0;
        double maxRatio = Double.MAX_VALUE;
        int maxInputImages = Integer.MAX_VALUE;
        boolean explicitPixelSize = true;
        boolean layerDecomposition = true;
        boolean pixelSizeWithLayerDecomposition = true;
        for (ImageSubmissionProfile profile : profiles) {
            tiers = tiers == null
                    ? profile.sizeTiers() : intersect(tiers, profile.sizeTiers());
            documented.addAll(profile.documentedSizeTiers());
            minPixels = Math.max(minPixels, profile.minPixels());
            maxPixels = Math.min(maxPixels, profile.maxPixels());
            minRatio = Math.max(minRatio, profile.minAspectRatio());
            maxRatio = Math.min(maxRatio, profile.maxAspectRatio());
            maxInputImages = Math.min(maxInputImages, profile.maxInputImages());
            explicitPixelSize &= profile.explicitPixelSize();
            layerDecomposition &= profile.layerDecomposition();
            pixelSizeWithLayerDecomposition &= profile.pixelSizeWithLayerDecomposition();
        }
        if (maxPixels < minPixels || maxRatio < minRatio) {
            // 两条路由的像素/比例区间没有交集（例如一条只收 1K、另一条只收 4K）。
            // 这不是「参数非法」，而是「没有任何参数一定能被接受」，因此答案是
            // 一份空画像，而不是一个会在构造器里抛异常、把整条目录打成 500 的值。
            return ImageSubmissionProfile.unavailable();
        }
        return new ImageSubmissionProfile(
                tiers == null ? Set.of() : tiers,
                documented,
                explicitPixelSize,
                minPixels,
                maxPixels,
                minRatio,
                maxRatio,
                maxInputImages == Integer.MAX_VALUE ? 0 : maxInputImages,
                layerDecomposition,
                pixelSizeWithLayerDecomposition);
    }

    /**
     * 平台白名单里**真的可提交**的比例，以及它在在售档位下对应的 {@code 宽x高}。
     *
     * <p>图片的厂商契约没有画幅参数，比例只能由客户端换算成像素尺寸提交。
     * 因此这里不是照抄一份常量，而是拿适配器画像的边界去筛平台白名单——
     * 只有同时满足「支持显式像素尺寸、像素总量在范围内、宽高比在范围内、
     * 且归约出的档位不高于在售档位」的比例才会下发。一条下发却提交必被拒的
     * 比例，比没有这个下拉框更糟。
     *
     * <p>顺序是**字符串升序**（集合语义），与视频目录的 {@code aspectRatios}
     * 一致：客户端应当成集合用，不要依赖顺序。
     */
    private static Map<String, String> aspectRatioSizes(
            ImageSubmissionProfile profile, String sold) {
        Map<String, String> sizes = new LinkedHashMap<>();
        if (!profile.explicitPixelSize()) {
            return sizes;
        }
        for (String ratio : sorted(ImageGenerationAdapter.PLATFORM_ASPECT_RATIOS)) {
            String size = sizeForRatio(ratio, sold);
            if (size == null) {
                continue;
            }
            String[] edges = size.split("x");
            long width = Long.parseLong(edges[0]);
            long height = Long.parseLong(edges[1]);
            long pixels = width * height;
            double value = (double) width / height;
            if (pixels < profile.minPixels() || pixels > profile.maxPixels()
                    || value < profile.minAspectRatio() || value > profile.maxAspectRatio()) {
                continue;
            }
            int rank = ImageGenerationService.tierRank(ImageGenerationService.sizeTier(size));
            if (rank < 0 || rank > ImageGenerationService.tierRank(sold)) {
                continue;
            }
            sizes.put(ratio, size);
        }
        return sizes;
    }

    /**
     * 把一个比例换算成某个档位下的 {@code 宽x高}。
     *
     * <p>最长边取该档位的单边上限（{@link ImageGenerationService#tierMaxEdge(String)}），
     * 短边按比例计算并向下取偶：厂商按像素取值，奇数只会让实际比例偏离请求的比例。
     * 这样算出来的尺寸**一定**落在这个档位里，档位本身随后还会被
     * {@link ImageGenerationService#sizeTier(String)} 复核一次，因此这里不可能
     * 偷偷把一个 2K 别名卖成 4K。
     */
    private static String sizeForRatio(String ratio, String tier) {
        String[] parts = ratio.split(":");
        if (parts.length != 2) {
            return null;
        }
        int widthRatio;
        int heightRatio;
        try {
            widthRatio = Integer.parseInt(parts[0].trim());
            heightRatio = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException error) {
            return null;
        }
        int edge = ImageGenerationService.tierMaxEdge(tier);
        if (edge <= 0 || widthRatio <= 0 || heightRatio <= 0) {
            return null;
        }
        int width;
        int height;
        if (widthRatio >= heightRatio) {
            width = edge;
            height = even((long) edge * heightRatio / widthRatio);
        } else {
            height = edge;
            width = even((long) edge * widthRatio / heightRatio);
        }
        return width + "x" + height;
    }

    private static int even(long value) {
        long floored = value - value % 2;
        return (int) Math.max(ImageGenerationAdapter.PLATFORM_MIN_EDGE, floored);
    }

    /**
     * 仅供联调/运维的缺口说明；配置完好且文档与代码一致时留下一条口径说明。
     *
     * <p>它是客户端**看不到**的那些边界的出口：一个厂商文档里有、而网关词汇表
     * 里没有的档位（V115 之后词汇表是四档 `1K/1.5K/2K/4K`，所以像 {@code 3K}
     * 这样的档位会走这条路）必须出现在这里，而不是被静默丢掉——
     * 静默丢掉的结果是运维永远不知道「模型其实还能出这一档」。
     */
    private static List<String> operator(
            String sold,
            ImageSubmissionProfile profile,
            List<String> submittable,
            Map<String, String> aspectRatioSizes) {
        List<String> operator = new ArrayList<>();
        if (!profile.sizeTiers().contains(sold)) {
            operator.add("sold_size_tier_not_supported_by_upstream");
        }
        if (submittable.isEmpty()) {
            operator.add("no_submittable_size_tier");
        }
        if (aspectRatioSizes.isEmpty()) {
            operator.add("no_submittable_aspect_ratio");
        }
        for (String tier : sorted(profile.documentedSizeTiers())) {
            if (ImageGenerationService.tierRank(tier) < 0) {
                operator.add("documented_size_tier_not_supported_by_gateway:" + tier);
            } else if (!profile.sizeTiers().contains(tier)) {
                operator.add("documented_size_tier_not_served_by_upstream:" + tier);
            }
        }
        if (!profile.documentedSizeTiers().isEmpty()
                && !profile.documentedSizeTiers().containsAll(profile.sizeTiers())) {
            operator.add("declared_size_tier_not_in_vendor_documentation");
        }
        // 比例不是厂商的参数：它只给出范围（像素总量与宽高比），客户端提交的是
        // 具体像素。这一条把「为什么下拉框里只有这几种」讲清楚。
        operator.add("aspect_ratios_are_a_platform_whitelist_of_the_supported_range");
        return List.copyOf(operator);
    }

    /**
     * 「什么都不可提交」的诚实答案：空的能力清单 + 说明原因。
     *
     * <p>边界字段留 {@code null}（序列化时被省略）而不是填 0：填 0 会让客户端
     * 把「没人确认」渲染成「最小 0 像素」。
     */
    private static ImageParameters unavailable(String sold, String reason) {
        return new ImageParameters(
                sold == null ? "" : sold,
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                false,
                null,
                null,
                null,
                null,
                // 无法提交任何图片时也报一次请求的上限：「这个别名不可用」由
                // operator 里的缺口说明表达，而不是靠把张数报成 0。
                ImageGenerationService.MAX_FANOUT,
                0,
                true,
                false,
                false,
                responseFormats(),
                List.of(reason));
    }

    private static List<String> responseFormats() {
        return List.copyOf(new TreeSet<>(ImageGenerationService.RESPONSE_FORMATS));
    }

    /**
     * 档位清单按「清晰度」的自然阶梯排序：1K → 1.5K → 2K → 4K。
     *
     * <p>这里**有意不用字符串升序**：那会得到 `1.5K`、`1K`、`2K`、`4K`，而这个数组
     * 是直接渲染成下拉框给用户看的。无法识别的档位排在最后，按名字升序，
     * 因此一个新的档位（例如 `3K`）会被插到正确的位置，不需要改这里。
     */
    private static List<String> sortedSizeTiers(Set<String> tiers) {
        List<String> ordered = new ArrayList<>(tiers);
        ordered.sort(Comparator
                .comparingDouble((String tier) -> tierValue(tier))
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(ordered);
    }

    /** 档位的数值口径（{@code 1.5K} → 1.5）；无法识别时返回最大值，即排在最后。 */
    private static double tierValue(String tier) {
        if (tier == null) {
            return Double.MAX_VALUE;
        }
        String trimmed = tier.trim().toUpperCase(java.util.Locale.ROOT);
        if (trimmed.length() < 2 || !trimmed.endsWith("K")) {
            return Double.MAX_VALUE;
        }
        try {
            return Double.parseDouble(trimmed.substring(0, trimmed.length() - 1));
        } catch (NumberFormatException error) {
            return Double.MAX_VALUE;
        }
    }

    private static List<String> sorted(Set<String> values) {
        return values == null ? List.of() : List.copyOf(new TreeSet<>(values));
    }

    private static <T> Set<T> intersect(Set<T> left, Set<T> right) {
        Set<T> shared = new TreeSet<>(left);
        shared.retainAll(right);
        return Set.copyOf(shared);
    }

    /**
     * @param id 逻辑别名，客户端把它作为 {@code model} 提交
     * @param displayName 控制台与桌面端展示的名称
     * @param resolutionTier 该别名出售的尺寸档位（1K / 1.5K / 2K / 4K）。它是**价格档位**，
     *        不是可选清晰度清单；能提交哪些档位见 {@code parameters}
     * @param usageDimension 计费维度；生图恒为 {@code IMAGE}，单位是张
     * @param parameters 该模型能生成什么：档位、画幅与数量边界
     */
    record ImageModelView(
            String id,
            String displayName,
            String resolutionTier,
            String usageDimension,
            ImageParameters parameters) { }

    /**
     * 生图能力参数表。
     *
     * <p>它是客户端渲染「清晰度 / 比例 / 生成张数 / 添加水印」四个控件的唯一
     * 数据来源；客户端不应再硬编码任何一项。
     *
     * @param soldSizeTier 该别名登记售卖的档位（价格档位）
     * @param supportedSizeTiers **模型支持的档位**（适配器受理的 ∪ 厂商文档口径的），
     *        按清晰度阶梯升序：`1K` → `1.5K` → `2K` → `4K`。客户端用它展示
     *        「这个模型能出什么」；不在 `submittableSizeTiers` 里的档位应当置灰，
     *        原因见 `operator`
     * @param submittableSizeTiers 当前**真的能提交**的档位：模型支持 ∩ 网关词汇表
     *        ∩ 不高于在售档位。客户端应当只让用户选这一个列表
     * @param aspectRatios 平台白名单里可提交的画幅比例，与
     *        {@code aspectRatioSizes} 的键集合相同、顺序相同
     * @param aspectRatioSizes 每个可提交比例在在售档位下对应的 {@code 宽x高}；
     *        客户端提交 {@code size} 时用它，不必自己做像素换算
     * @param explicitPixelSize {@code size} 是否接受 {@code 宽x高}
     * @param minPixels 显式像素尺寸的像素总量下限；{@code null} 表示未确认
     * @param maxPixels 像素总量上限；{@code null} 表示未确认
     * @param minAspectRatio 宽高比下限（宽 ÷ 高）；{@code null} 表示未确认
     * @param maxAspectRatio 宽高比上限（宽 ÷ 高）；{@code null} 表示未确认
     * @param maxImages 一次请求能买几张（1~4）。网关为 N 张并发扇出 N 次厂商调用，
     *        因为厂商侧实测静默忽略 {@code n}（见 {@code ImageGenerationService.MAX_FANOUT}）。
     *        传更大的 {@code n} 会被 422 拒绝。它**不是**「一次厂商调用几张」——
     *        那恒为 1
     * @param maxInputImages 单次最多几张参考图（图生图）；0 表示不支持
     * @param watermarkSupported 是否支持水印开关
     * @param layerDecompositionSupported 是否支持图层拆分（按次计费）
     * @param pixelSizeWithLayerDecomposition 图层拆分是否也接受 {@code 宽x高}；
     *        为 {@code false} 时该模式只能用档位字符串
     * @param responseFormats 可用的 {@code response_format}
     * @param operator 仅供联调/运维的缺口说明
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ImageParameters(
            String soldSizeTier,
            List<String> supportedSizeTiers,
            List<String> submittableSizeTiers,
            List<String> aspectRatios,
            Map<String, String> aspectRatioSizes,
            boolean explicitPixelSize,
            Long minPixels,
            Long maxPixels,
            Double minAspectRatio,
            Double maxAspectRatio,
            int maxImages,
            int maxInputImages,
            boolean watermarkSupported,
            boolean layerDecompositionSupported,
            boolean pixelSizeWithLayerDecomposition,
            List<String> responseFormats,
            List<String> operator) { }
}
