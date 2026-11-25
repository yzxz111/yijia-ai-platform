package com.yuzhihuihe.apiplatform.gateway.video;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.video.VideoGenerationAdapter.ModeProfile;
import com.yuzhihuihe.apiplatform.gateway.video.VideoGenerationAdapter.SubmissionProfile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 列出已认证 key 有权看到的视频别名，并回答**每个别名能生成什么参数**。
 *
 * <p>这是展示目录，不是供应商就绪状态检查。仓库已经施加了生效的
 * {@code VIDEO_SECOND} 套餐、网关模型以及别名/档位谓词。上游是否真能
 * 服务所选别名，由视频提交路由在之后检查。
 *
 * <p>能力这一半来自路由**配置**而不是路由**健康**：一条别名指向哪些
 * 上游模型，决定了它在分辨率、画幅与时长的真实边界；而那些渠道今天
 * 是否 HEALTHY、是否已录入凭据，与「这个模型能生成什么」无关。因此
 * 目录在渠道暂时不可用时依然报告能力，客户端据此渲染的选择器不会
 * 因为一次上游抖动而变空；提交时才会因为渠道不可用收到 503。
 *
 * <p>多条路由合并时取保守的交集（见 {@link VideoSubmissionProfiles}），
 * 并且每个字段都注明它是否只是「没人能确认」的保守值——
 * {@code operator} 里的条目就是这一类。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class VideoModelCatalogService {

    static final String DIMENSION = "VIDEO_SECOND";

    /** 与 {@code VideoGenerationService.validate} 的时长上界同源。 */
    private static final int PLATFORM_MAX_SECONDS =
            VideoGenerationAdapter.PLATFORM_MAX_DURATION_SECONDS;

    static final String MODE_TEXT_TO_VIDEO = "TEXT_TO_VIDEO";
    static final String MODE_IMAGE_TO_VIDEO = "IMAGE_TO_VIDEO";

    private final VideoRouteRepository repository;
    private final VideoAdapterRegistry adapterRegistry;

    VideoModelCatalogService(
            VideoRouteRepository repository, VideoAdapterRegistry adapterRegistry) {
        this.repository = repository;
        this.adapterRegistry = adapterRegistry;
    }

    Flux<VideoModelView> availableModels(ApiKeyService.ApiKeyContext context) {
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
                // 同一个别名可能因为历史数据出现多行；档位行是唯一的，
                // 但能力解析只该按别名做一次。
                .distinct(VideoRouteRepository.VideoModelCatalogRow::alias)
                .concatMap(row -> view(context, row))
                .sort(Comparator.comparing(VideoModelView::id));
    }

    /**
     * 合并模型（V110）与普通别名的两条路径：
     *
     * <p>有档位映射的别名（当前只有 video-minimax）走合并路径——可提交的
     * 分辨率是该套餐**持有**内部别名的那些档位，时长/画幅/首帧/音频是各档位
     * 内部别名能力的保守交集。没有映射的别名维持单档位路径原样。
     */
    private Mono<VideoModelView> view(
            ApiKeyService.ApiKeyContext context, VideoRouteRepository.VideoModelCatalogRow row) {
        return repository
                .clientTierMappings(
                        context.packageInstanceId(), context.tenantId(), context.userId(),
                        row.alias())
                .collectList()
                .flatMap(tiers -> tiers.isEmpty()
                        ? resolve(row.resolutionTier(), row.alias())
                                .map(parameters -> new VideoModelView(
                                        row.alias(), row.displayName(), row.resolutionTier(),
                                        DIMENSION, parameters))
                        : consolidated(context, row, tiers));
    }

    /**
     * 合并模型视图：逐档位取内部别名的合并画像，再跨档位取保守交集。
     *
     * <p>某个档位的内部别名没有任何可解析路由时，只把该档位从可提交
     * 分辨率里去掉（而不是让整个模型消失）——这与单档位路径把
     * 「sold_tier_not_supported_by_upstream」报进 operator 的精神一致：
     * 别的档位仍可提交。
     */
    private Mono<VideoModelView> consolidated(
            ApiKeyService.ApiKeyContext context,
            VideoRouteRepository.VideoModelCatalogRow row,
            List<VideoRouteRepository.ClientTierRow> tiers) {
        List<Mono<Optional<SubmissionProfile>>> perTier = tiers.stream()
                .map(tier -> mergedProfile(tier.internalModelCode()))
                .toList();
        return Mono.zip(perTier, objects -> {
            List<TierProfile> resolved = new ArrayList<>();
            for (int i = 0; i < tiers.size(); i++) {
                Optional<SubmissionProfile> profile = perTierProfile(objects[i]);
                if (profile.isPresent()) {
                    resolved.add(new TierProfile(tiers.get(i).resolutionTier(), profile.get()));
                }
            }
            return consolidatedView(row, resolved);
        });
    }

    @SuppressWarnings("unchecked")
    private static Optional<SubmissionProfile> perTierProfile(Object value) {
        return (Optional<SubmissionProfile>) value;
    }

    /** 某个档位连同其内部别名合并画像。 */
    private record TierProfile(String tier, SubmissionProfile profile) { }

    /**
     * 内部别名多条路由的合并画像；没有任何可解析路由时为空。
     */
    private Mono<Optional<SubmissionProfile>> mergedProfile(String internalAlias) {
        return Mono.defer(() -> catalogProfiles(internalAlias))
                .flatMap(VideoSubmissionProfiles::forAlias)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    /**
     * 合并模型视图。
     *
     * <p>可提交的分辨率**按档位逐个判定**：一个档位只要它自己的内部别名
     * 能生成该档位，就进入该模态的 resolutions（这正是「480P 由 video-budget
     * 履约、720P 由 video-minimax 履约」的语义）。时长与画幅则是跨档位的
     * 保守交集——客户端选任何档位，这些取值都不会 422。
     */
    private VideoModelView consolidatedView(
            VideoRouteRepository.VideoModelCatalogRow row, List<TierProfile> tiers) {
        String primary = row.resolutionTier() == null ? "" : row.resolutionTier();
        if (tiers.isEmpty()) {
            // 映射存在、却没有一个档位的内部别名能解析出路由：
            // 与单档位 unresolvable 同口径，客户端看到空 modes。
            return new VideoModelView(
                    row.alias(), row.displayName(), primary, DIMENSION, unresolvable(primary));
        }
        List<SubmissionProfile> profiles = tiers.stream().map(TierProfile::profile).toList();
        SubmissionProfile merged = VideoSubmissionProfiles.conservative(profiles);
        if (merged == null) {
            return new VideoModelView(
                    row.alias(), row.displayName(), primary, DIMENSION, unresolvable(primary));
        }
        List<ModeView> modes = new ArrayList<>();
        for (ModeProfile mode : merged.modes()) {
            List<String> resolutions = tiers.stream()
                    .filter(tier -> {
                        ModeProfile candidate = tier.profile().mode(mode.mode());
                        return candidate != null
                                && candidate.resolutions().contains(tier.tier());
                    })
                    .map(TierProfile::tier)
                    .sorted()
                    .toList();
            if (resolutions.isEmpty()) {
                continue;
            }
            modes.add(new ModeView(
                    mode.mode(), resolutions, durationView(merged), sorted(mode.aspectRatios())));
        }
        modes.sort(Comparator.comparingInt(
                mode -> SubmissionProfile.INPUT_MODES.indexOf(mode.mode())));
        List<String> operator = new ArrayList<>();
        if (modes.isEmpty()) {
            // 每个档位的内部别名都能解析出路由，却没有一个档位真的可提交：
            // 这是配置错配（映射档位与上游能力无交集），报给运维看。
            operator.add("sold_tier_not_supported_by_upstream");
        }
        if (merged.enumeratesDurations()) {
            operator.add("duration_is_enumerated_not_continuous");
        }
        VideoParameters parameters = new VideoParameters(
                List.copyOf(modes),
                primary,
                sorted(merged.tiers()),
                durationView(merged),
                PLATFORM_MAX_SECONDS,
                sorted(merged.aspectRatios()),
                merged.imageInputSupported(),
                merged.audioSupported(),
                List.copyOf(operator));
        return new VideoModelView(row.alias(), row.displayName(), primary, DIMENSION, parameters);
    }

    /**
     * 把「该别名售卖的那一档」与「它的路由实际能生成什么」合并成一份参数表。
     *
     * <p>档位读不到时不当作「任意档位都可以」：{@code VideoGenerationService}
     * 会以一个 422 拒绝这样的别名，因此这里报告一份空的分辨率清单，
     * 客户端由此看出该别名当下不可提交，而不是看到一个乐观的默认值。
     */
    private Mono<VideoParameters> resolve(String soldTier, String alias) {
        Mono<String> registered = repository.resolutionTier(alias).defaultIfEmpty("");
        Mono<List<SubmissionProfile>> profiles = Mono.defer(() -> catalogProfiles(alias))
                .flatMap(VideoSubmissionProfiles::forAlias)
                .map(List::of)
                .defaultIfEmpty(List.of());
        return Mono.zip(registered, profiles)
                .map(both -> parameters(both.getT1(), both.getT2()));
    }

    /**
     * 每条已配置路由各自的画像。
     *
     * <p>无法解析的厂商类型被丢弃而不是中断整条目录：一条尚未接线的
     * 渠道不应该让客户端看不到这个模型。真正没有任何可解析路由时
     * 返回空列表，调用方随后退回「无法确认上游能力」的诚实答案。
     */
    private Mono<List<SubmissionProfile>> catalogProfiles(String alias) {
        return repository.catalogRoutes(alias)
                .filter(route -> route.providerType() != null
                        && adapterRegistry.supports(route.providerType()))
                .map(route -> adapterRegistry
                        .forProviderType(route.providerType())
                        .submissionProfile(VideoSubmissionProfiles.profileRequest(
                                route.providerType(), route.upstreamModel())))
                .collectList();
    }

    private VideoParameters parameters(String registeredTier, List<SubmissionProfile> profiles) {
        String tier = registeredTier == null ? "" : registeredTier;
        if (tier.isEmpty() || profiles.isEmpty()) {
            return unresolvable(tier);
        }
        SubmissionProfile profile = profiles.get(0);
        List<ModeView> modes = new ArrayList<>();
        for (ModeProfile mode : profile.modes()) {
            List<String> resolutions = resolutionsForSale(tier, mode.resolutions());
            if (resolutions.isEmpty()) {
                continue;
            }
            modes.add(new ModeView(
                    mode.mode(),
                    resolutions,
                    durationView(profile),
                    sorted(mode.aspectRatios())));
        }
        modes.sort(Comparator.comparingInt(
                mode -> SubmissionProfile.INPUT_MODES.indexOf(mode.mode())));
        if (modes.isEmpty()) {
            // 路由存在、但没有任何一条能履约这个别名售卖的档位：
            // 这是配置问题，客户端看不到任何可提交的组合，正好照此呈现，
            // 并让运维在 operator 里看到原因。
            //
            // 顶层仍照实报告上游能做什么（档位/画幅/时长），因为那正是
            // 运维判断"该改别名还是该改上游模型"的依据；客户端只要读
            // modes 就不会误以为它现在可提交。
            return new VideoParameters(
                    List.of(),
                    tier,
                    List.of(),
                    durationView(profile),
                    PLATFORM_MAX_SECONDS,
                    sorted(profile.aspectRatios()),
                    profile.imageInputSupported(),
                    profile.audioSupported(),
                    List.of("sold_tier_not_supported_by_upstream"));
        }

        List<String> operator = new ArrayList<>();
        if (!profile.tiers().contains(tier)) {
            // 可达的档位存在，却不含该别名登记售卖的那一档。
            operator.add("sold_tier_not_supported_by_upstream");
        }
        if (profile.enumeratesDurations()) {
            // 上游只接受具体几个时长；顶层区间是它们的最小与最大，
            // 客户端应优先读 durationSeconds.options。
            operator.add("duration_is_enumerated_not_continuous");
        }
        return new VideoParameters(
                List.copyOf(modes),
                tier,
                sorted(profile.tiers()),
                durationView(profile),
                PLATFORM_MAX_SECONDS,
                sorted(profile.aspectRatios()),
                profile.imageInputSupported(),
                profile.audioSupported(),
                List.copyOf(operator));
    }

    /**
     * 无法确认上游能力时的诚实答案。
     *
     * <p>它只说清平台自己会把什么放行（1–60 秒、五个白名单画幅），
     * 并把分辨率、模态与音频全部报成空/假，同时在 {@code operator}
     * 里注明原因：没有任何证据表明上游支持某个档位，因此不能把
     * 「平台不拦」渲染成「上游能做」。
     */
    private VideoParameters unresolvable(String tier) {
        return new VideoParameters(
                List.of(),
                tier,
                List.of(),
                new DurationView(
                        VideoGenerationAdapter.PLATFORM_MIN_DURATION_SECONDS, null, List.of()),
                PLATFORM_MAX_SECONDS,
                List.of(),
                false,
                false,
                List.of("upstream_capabilities_unconfigured"));
    }

    /**
     * 该模态最终能卖给客户的分辨率：别名登记的那一档，且上游确实能生成它。
     *
     * <p>别名没登记档位（无法按分辨率定价，提交必被拒）时返回空集；
     * 上游不支持该档位时同样返回空集——两者都表示「这个模态现在不可提交」。
     */
    private List<String> resolutionsForSale(String registeredTier, Set<String> upstreamTiers) {
        if (registeredTier.isEmpty() || !upstreamTiers.contains(registeredTier)) {
            return List.of();
        }
        return List.of(registeredTier);
    }

    /**
     * 时长视图。
     *
     * <p>{@code min > max}（例如某路由只收 5–10 秒、另一条只收 12–15 秒）
     * 时没有任何共同时长，此时整个对象为 {@code null}：报一个上下界
     * 颠倒的区间只会让客户端渲染出一个空选择器却不知道原因。
     *
     * <p>枚举值在这里被**裁剪到区间之内**，而不是原样透传：客户端一旦
     * 看到非空的 {@code options} 就只提供其中的取值，因此一个落在
     * 区间之外的枚举值就是一条「照目录提交必被 422」的假选项。
     * 适配器目前不会给出这种组合，但目录不该依赖调用方守这个约定。
     */
    private DurationView durationView(SubmissionProfile profile) {
        int min = profile.minDurationSeconds();
        int max = profile.maxDurationSeconds();
        if (min > max) {
            return null;
        }
        List<Integer> options = profile.durationOptions();
        if (options != null) {
            options = options.stream()
                    .filter(seconds -> seconds != null && seconds >= min && seconds <= max)
                    .sorted()
                    .toList();
        }
        return new DurationView(min, max, options == null ? List.of() : options);
    }

    private static List<String> sorted(Set<String> values) {
        return values == null ? List.of() : List.copyOf(new TreeSet<>(values));
    }

    /**
     * @param id 逻辑别名，客户端把它作为 {@code model} 提交
     * @param displayName 控制台与桌面端展示的名称
     * @param resolutionTier 该别名**唯一售卖**的分辨率档位；提交别的档位必被 422 拒绝
     * @param usageDimension 计费维度；视频恒为 {@code VIDEO_SECOND}，单位是秒
     * @param parameters 该别名能生成的分辨率、画幅、时长与音频能力
     */
    record VideoModelView(
            String id,
            String displayName,
            String resolutionTier,
            String usageDimension,
            VideoParameters parameters) { }

    /**
     * @param modes 按输入模态拆分的可生成参数；只列出真正可提交的模态
     * @param soldResolutionTier 该别名登记售卖的档位
     * @param upstreamResolutionTiers 上游实际能生成的档位（多路由合并后，升序。
     *        注意"升序"是字符串序，所以 {@code 1080P} 排在 {@code 720P} 之前——
     *        客户端应当成**集合**用，不要依赖顺序）
     * @param durationSeconds 合并后的可提交时长；无共同时长时为 {@code null}
     * @param platformMaxSeconds 平台允许请求的最大秒数（{@code 60}）；
     *        与 {@code durationSeconds.max} 不是一回事——后者才是上游会接受的上界
     * @param aspectRatios 上游实际能生成的画幅（多路由合并后，升序）
     * @param imageInputSupported 传首帧（图生视频）是否能被履约
     * @param withAudioSupported 音频开关是否能被履约
     * @param operator 仅供联调/运维的缺口说明；配置完好时为空数组
     */
    record VideoParameters(
            List<ModeView> modes,
            String soldResolutionTier,
            List<String> upstreamResolutionTiers,
            DurationView durationSeconds,
            Integer platformMaxSeconds,
            List<String> aspectRatios,
            boolean imageInputSupported,
            boolean withAudioSupported,
            List<String> operator) { }

    /**
     * @param mode {@code TEXT_TO_VIDEO}（不提首帧）或 {@code IMAGE_TO_VIDEO}（提首帧）
     * @param resolutions 该模态可提交的分辨率档位；空数组表示该模态不可用
     * @param durationSeconds 该模态可提交的时长；{@code null} 表示无共同时长
     * @param aspectRatios 该模态可提交的画幅比例
     */
    record ModeView(
            String mode,
            List<String> resolutions,
            DurationView durationSeconds,
            List<String> aspectRatios) { }

    /**
     * @param min 最小可提交秒数
     * @param max 最大可提交秒数
     * @param options 上游只接受枚举时长时给出具体取值（升序）；连续区间时为空数组。
     *        非空时客户端应只提供这些取值，因为区间内的中间值会被上游拒绝
     */
    record DurationView(int min, Integer max, List<Integer> options) { }
}
