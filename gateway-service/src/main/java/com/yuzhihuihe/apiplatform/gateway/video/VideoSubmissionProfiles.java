package com.yuzhihuihe.apiplatform.gateway.video;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.video.VideoGenerationAdapter.ModeProfile;
import com.yuzhihuihe.apiplatform.gateway.video.VideoGenerationAdapter.SubmissionProfile;
import com.yuzhihuihe.apiplatform.gateway.video.VideoGenerationAdapter.TaskRequest;
import reactor.core.publisher.Mono;

/**
 * 把多条路由各自的可生成参数画像合并成一份**保守**答案。
 *
 * <p>为什么必须保守：一个别名可以同时挂在多张渠道上（同一条
 * {@code video-pro} 可能先走 Kling v3-turbo、再回落到某厂商的
 * 等价模型），而这些上游的契约并不相同。合并后的答案要回答的是
 * 「客户端提什么参数一定不会被 422 拒绝」，因此取的是交集而不是并集：
 * 若渠道 A 只收 3–15 秒、渠道 B 只收 5–10 秒，那么能承诺的区间是
 * 5–10 秒；报 3–15 秒就会让一部分请求在下单时才失败。
 *
 * <p>这条规则与 {@code VideoGenerationService.submitToChannel} 的
 * 实际行为一致：它在候选渠道中挑一条并立刻校验，因此一个
 * 「某些渠道支持」的参数并不是可提交的参数。
 *
 * <p>并集的例外只有一个：模态的**名字**。任何一条路由都不会因为
 * 另一条路由不支持图生视频而失去它（那条请求会路由到支持它的渠道），
 * 因此模态取自各路由的并集，而每个模态内部的分辨率与画幅取交集。
 *
 * <p>不可解析的路由（厂商类型没有对应适配器、或适配器不认这个上游模型）
 * 会被整条丢弃：能力目录宁可少报一项，也不能把某条路由的能力
 * 当成所有路由的共同能力。
 */
final class VideoSubmissionProfiles {

    /** 复用的空集：合并结果里「无」与「未记录」必须区分得开。 */
    private static final Set<String> NONE = Set.of();

    private VideoSubmissionProfiles() {
    }

    /**
     * 合并若干条路由的画像。
     *
     * @param profiles 可解析路由的画像
     * @return 合并后的画像；没有任何可解析路由时为空 Mono，
     *         由调用方决定退回哪一种口径（目录退回「上游能力未配置」）
     */
    static Mono<SubmissionProfile> forAlias(List<SubmissionProfile> profiles) {
        return Mono.justOrEmpty(conservative(profiles));
    }

    /**
     * 合并若干条路由的画像。
     *
     * <p><b>单条路由也走同一条合并路径。</b>这里曾有一个"只有一条路由就
     * 原样返回"的捷径。它在结果上确实是等价的（一条路由与自身求交集就是
     * 它自己），但它让"客户端看到的集合从哪来"这件事依赖调用方是否恰好
     * 只配了一条路由——一条路由的答案由这里给出，两条路由的答案由这里
     * 重新计算，同一条别名因此有两种产生路径，而其中一条没有任何测试
     * 覆盖。现在只有一条路径：交集。代价是一次可忽略的拷贝。
     *
     * @return 合并后的画像；{@code profiles} 为空时返回 {@code null}
     */
    static SubmissionProfile conservative(List<SubmissionProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return null;
        }
        List<ModeProfile> modes = List.of();
        for (String name : modesOf(profiles)) {
            modes = append(modes, mergeMode(name, profiles));
        }
        return new SubmissionProfile(
                modes,
                profiles.stream().mapToInt(SubmissionProfile::minDurationSeconds).max().orElse(0),
                profiles.stream().mapToInt(SubmissionProfile::maxDurationSeconds).min().orElse(0),
                mergeDurationOptions(profiles),
                profiles.stream().allMatch(SubmissionProfile::audioSupported));
    }

    /**
     * 各路由报告的模态名字，按 {@link SubmissionProfile#INPUT_MODES} 的
     * 固定顺序排列，未在固定清单里的自定义模态按字母序追加。
     */
    private static Set<String> modesOf(List<SubmissionProfile> profiles) {
        Set<String> extra = new TreeSet<>();
        Set<String> modes = new LinkedHashSet<>(SubmissionProfile.INPUT_MODES);
        for (SubmissionProfile profile : profiles) {
            for (ModeProfile mode : profile.modes()) {
                if (!modes.contains(mode.mode())) {
                    extra.add(mode.mode());
                }
            }
        }
        modes.addAll(extra);
        return modes;
    }

    /**
     * 某个模态在所有路由上的共同能力。
     *
     * <p>缺少该模态的路由按空集处理：它与「明确声明不支持该模态」
     * 在提交时的后果相同（校验会拒绝），因此不能因为别处
     * 没有这条记录就放宽答案。
     */
    private static ModeProfile mergeMode(String name, List<SubmissionProfile> profiles) {
        Set<String> resolutions = null;
        Set<String> ratios = null;
        for (SubmissionProfile profile : profiles) {
            ModeProfile mode = profile.mode(name);
            Set<String> modeResolutions = mode == null ? NONE : mode.resolutions();
            Set<String> modeRatios = mode == null ? NONE : mode.aspectRatios();
            resolutions = resolutions == null
                    ? modeResolutions : intersect(resolutions, modeResolutions);
            ratios = ratios == null ? modeRatios : intersect(ratios, modeRatios);
        }
        return new ModeProfile(
                name,
                resolutions == null ? NONE : resolutions,
                ratios == null ? NONE : ratios);
    }

    /**
     * 各路由都接受的具体时长值。
     *
     * <p>只要有一条路由把时长描述成连续区间，答案就是区间
     * （返回 {@code null}），因为区间里可能有另一条路由
     * 不接受的取值，而合并后的区间上下界已经把两侧都收紧了。
     */
    private static List<Integer> mergeDurationOptions(List<SubmissionProfile> profiles) {
        if (profiles.stream().anyMatch(profile -> !profile.enumeratesDurations())) {
            return null;
        }
        Set<Integer> allowed = null;
        for (SubmissionProfile profile : profiles) {
            Set<Integer> options = new TreeSet<>(profile.durationOptions());
            allowed = allowed == null ? options : intersect(allowed, options);
        }        if (allowed == null || allowed.isEmpty()) {
            // 没有任何共同时长：报告的区间也会是空区间，客户端由此看出
            // 这个别名买不到任何可提交的时长，而不是收到一份看起来
            // 可用、提交必失败的清单。
            return List.of();
        }
        int min = profiles.stream().mapToInt(SubmissionProfile::minDurationSeconds).max().orElse(0);
        int max = profiles.stream().mapToInt(SubmissionProfile::maxDurationSeconds).min().orElse(0);
        return allowed.stream()
                .filter(seconds -> seconds >= min && seconds <= max)
                .toList();
    }

    /**
     * 两个集合的交集，保持字母/数值升序。
     *
     * <p>泛型是必要的：同一份合并逻辑既用于字符串（档位、画幅），
     * 也用于整数（枚举时长）。
     */
    private static <T> Set<T> intersect(Set<T> left, Set<T> right) {
        Set<T> shared = new TreeSet<>(left);
        shared.retainAll(right);
        return Set.copyOf(shared);
    }

    private static List<ModeProfile> append(List<ModeProfile> modes, ModeProfile mode) {
        List<ModeProfile> grown = new java.util.ArrayList<>(modes);
        grown.add(mode);
        return List.copyOf(grown);
    }

    /**
     * 一条只用于**问能力**的请求。
     *
     * <p>适配器的画像只读 {@link TaskRequest#channel()}，但
     * {@code TaskRequest} 会把其余字段当作必填校验，因此这里填的是
     * 合法的最小值，而不是随便传空。它绝不会被提交到上游。
     */
    static TaskRequest profileRequest(String providerType, String upstreamModel) {
        return new TaskRequest(
                new VideoGenerationAdapter.Channel(
                        UUID.nameUUIDFromBytes(("profile:" + providerType + ':' + upstreamModel)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        UUID.nameUUIDFromBytes(("profile-provider:" + providerType)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        providerType,
                        "profile://capability-catalog",
                        null,
                        upstreamModel),
                "profile",
                "profile",
                null,
                VideoGenerationAdapter.PLATFORM_MIN_DURATION_SECONDS,
                "480P",
                null,
                false,
                60_000);
    }
}
