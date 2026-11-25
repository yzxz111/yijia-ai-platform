package com.yuzhihuihe.apiplatform.gateway.video;

import java.util.List;
import java.util.Set;

import reactor.core.publisher.Mono;

/**
 * 能够生成视频的供应商。
 *
 * <p>有意与 {@code ProviderAdapter} 分开。那个接口是围绕
 * {@code /chat/completions} 构造的——一次请求产生一次响应，可选流式——
 * 而视频生成是异步任务协议：提交返回一个 task id，任务被轮询，
 * 且只有终态的轮询才产出结果。把视频强行塞进对话接口，就意味着要发明一个
 * 合成的 completion，因此两份契约保持分离，只共享渠道与路由这两个概念。
 *
 * <p>实现方负责把某一个厂商的提交/轮询载荷翻译成这些类型。
 * 本接口之上的任何代码都不知道用的是哪个厂商。
 */
public interface VideoGenerationAdapter {

    /**
     * 平台允许挂靠的视频计费档位，与 {@code gateway.video_alias_tier} 的
     * CHECK 约束以及 {@code VideoGenerationService.validate} 保持同一份清单。
     */
    Set<String> PLATFORM_RESOLUTION_TIERS = Set.of("480P", "720P", "1080P");

    /** 网关层面接受的画幅白名单；在这五个之外的取值在提交时即被拒绝。 */
    Set<String> PLATFORM_ASPECT_RATIOS = Set.of("16:9", "9:16", "1:1", "4:3", "3:4");

    /** 网关层面接受的时长边界（秒）。 */
    int PLATFORM_MIN_DURATION_SECONDS = 1;
    int PLATFORM_MAX_DURATION_SECONDS = 60;

    /** 与 {@code gateway.provider.provider_type} 匹配。 */
    String providerType();

    /**
     * 提交一次生成任务并返回厂商的 task id。
     *
     * <p>对同一次逻辑提交，在没有幂等键的情况下绝不能被调用两次：
     * 重复提交对大多数厂商而言就是重复扣费。
     */
    Mono<SubmittedTask> submit(TaskRequest request);

    /**
     * 校验供应商特有的请求画像，不预留配额，也不发起网络调用。
     *
     * <p>视频厂商在时长、分辨率和宽高比上的契约差异很大。
     * 服务层在解析出渠道之后、预留秒数之前调用本钩子，
     * 因此本地即可发现的不良请求不会留下一个已付费的占用。
     */
    default void validateSubmission(TaskRequest request) {
        // 没有更窄契约的供应商可以依赖网关层面的校验。
    }

    /**
     * 该渠道的上游模型**允许被请求**哪些参数：分辨率、画幅与时长。
     *
     * <p>它与 {@link #validateSubmission(TaskRequest)} 必须描述同一个契约，
     * 却服务于不同对象：校验在提交时拒绝非法请求，而本方法让
     * {@code GET /v1/video-models} 能在**提交之前**把这些边界告诉客户端。
     * 只提供校验、不提供画像的适配器，其客户只能靠试错发现边界——
     * 一次 422 就是一次失败的购买尝试。
     *
     * <p>默认答案覆盖网关自身的准入边界（1–60 秒、480P/720P/1080P、
     * 平台的白名单画幅、允许首帧、不承诺音频），也就是
     * {@code VideoGenerationService} 会接受的请求。它绝不是
     * 「上游什么都支持」：反过来，适配器若知道自己更窄，就必须覆盖它，
     * 否则目录会再一次把客户送进 422。
     *
     * @param request 已解析出的渠道加上调用方参数；只有渠道部分有意义
     */
    default SubmissionProfile submissionProfile(TaskRequest request) {
        return SubmissionProfile.gatewayDefaults();
    }

    /** 轮询一个先前已提交的任务。 */
    Mono<TaskStatus> poll(TaskRequest request, String upstreamTaskId);

    /**
     * 本适配器能够服务的能力。仅支持视频的适配器返回
     * {@code VIDEO}；同时提供对话的厂商可以列出更多，
     * 路由层按所请求的能力做匹配。
     */
    Set<String> capabilities();

    /**
     * @param channel 解析出的上游渠道，包含其已解密的凭证
     * @param requestId 网关请求 id，可用作厂商侧的幂等键
     * @param prompt 生成提示词
     * @param inputImageUrl 可选的图生视频首帧图片
     * @param durationSeconds 请求的片段时长，单位为秒
     * @param resolutionTier 480P / 720P / 1080P 之一
     * @param aspectRatio 可选的输出画幅比例
     * @param withAudio 调用方是否要求生成音频
     * @param timeoutMs 该渠道的超时预算
     */
    record TaskRequest(
            Channel channel,
            String requestId,
            String prompt,
            String inputImageUrl,
            int durationSeconds,
            String resolutionTier,
            String aspectRatio,
            boolean withAudio,
            int timeoutMs) {

        public TaskRequest {
            if (channel == null || requestId == null || requestId.isBlank()
                    || prompt == null || prompt.isBlank()
                    || durationSeconds <= 0 || resolutionTier == null || resolutionTier.isBlank()
                    || timeoutMs <= 0) {
                throw new IllegalArgumentException("invalid_video_task_request");
            }
        }
    }

    /**
     * 该上游模型可生成参数的完整画像。
     *
     * <p>{@code INPUT_MODES} 是固定的两种输入模态，因此调用方总能
     * 依赖这个数组的长度与顺序。{@code durationOptions} 为 {@code null}
     * 表示时长是**区间**（{@code minDurationSeconds}–{@code maxDurationSeconds}
     * 内的任意整数），非 null 表示上游只接受枚举出的这几个值
     * （例如仅 5 或 10 秒）。客户端据此渲染时长选择器，
     * 不必先提交一次才被 422 告知。
     *
     * <p>每个模态各自携带分辨率集合，因为上游对两种模态的契约
     * 并不总是相同：天翼云只支持文生视频，百炼 Wan 2.5 的文生视频
     * 在 480P 下只收三种画幅、图生视频却收全部画幅。集合为空表示
     * **该模态不可用**（而不是「任意分辨率都可以」）。这里列出的是该
     * 上游实际能履约的平台计费档位，而客户能买到的是别名登记的那一档
     * （{@code gateway.video_alias_tier}）：目录返回的
     * {@code resolutions} 是两者的交集。
     */
    record SubmissionProfile(
            List<ModeProfile> modes,
            int minDurationSeconds,
            int maxDurationSeconds,
            List<Integer> durationOptions,
            boolean audioSupported) {

        public static final List<String> INPUT_MODES = List.of("TEXT_TO_VIDEO", "IMAGE_TO_VIDEO");

        public SubmissionProfile {
            if (modes == null || modes.isEmpty()
                    || minDurationSeconds <= 0 || maxDurationSeconds < minDurationSeconds) {
                throw new IllegalArgumentException("invalid_video_submission_profile");
            }
            if (durationOptions != null
                    && (durationOptions.isEmpty()
                        || durationOptions.stream().anyMatch(
                                seconds -> seconds == null || seconds <= 0))) {
                throw new IllegalArgumentException("invalid_video_submission_profile");
            }
        }

        /**
         * 网关自身准入边界所对应的画像：1–60 秒、三个档位、
         * 平台白名单画幅、允许首帧、不承诺音频。
         *
         * <p>网关的 {@code VideoGenerationService.validate} 恰好接受这些值，
         * 因此没有更窄契约的适配器可以照此回答；知道自己更窄的适配器
         * 必须覆盖 {@link #submissionProfile(TaskRequest)}，否则目录会
         * 再一次把客户端送进一次 422。
         */
        public static SubmissionProfile gatewayDefaults() {
            return new SubmissionProfile(
                    List.of(
                            ModeProfile.of("TEXT_TO_VIDEO"),
                            ModeProfile.of("IMAGE_TO_VIDEO")),
                    PLATFORM_MIN_DURATION_SECONDS,
                    PLATFORM_MAX_DURATION_SECONDS,
                    null,
                    false);
        }

        /** 该模态的画像；模态不在列表中时返回 {@code null}。 */
        public ModeProfile mode(String name) {
            return modes.stream()
                    .filter(profile -> profile.mode().equals(name))
                    .findFirst()
                    .orElse(null);
        }

        /**
         * 客户端可用的画幅：只统计可提交的模态（分辨率集合非空），
         * 返回这些模态画幅表的并集。
         *
         * <p>取并集而不是交集，是因为模态各自的画幅表已经逐个列在
         * 返回给客户端的 {@code modes[]} 里，顶层字段只是便利视图；
         * 而取交集会在「文生视频收全部画幅、图生视频不支持」这类
         * 真实配置下把画幅报成空集。
         */
        public Set<String> aspectRatios() {
            return unionAcrossUsableModes(ModeProfile::aspectRatios);
        }

        /** 客户端可用的平台计费档位，口径同 {@link #aspectRatios()}。 */
        public Set<String> tiers() {
            return unionAcrossUsableModes(ModeProfile::resolutions);
        }

        /** 该上游是否接受首帧：图生视频模态本身可生成内容。 */
        public boolean imageInputSupported() {
            ModeProfile profile = mode("IMAGE_TO_VIDEO");
            return profile != null && !profile.resolutions().isEmpty();
        }

        /** 时长是否只能取枚举值（上游只接受 5 或 10 秒这类契约）。 */
        public boolean enumeratesDurations() {
            return durationOptions != null;
        }

        private Set<String> unionAcrossUsableModes(
                java.util.function.Function<ModeProfile, Set<String>> values) {
            Set<String> union = new java.util.TreeSet<>();
            for (String name : INPUT_MODES) {
                ModeProfile profile = mode(name);
                if (profile != null && !profile.resolutions().isEmpty()) {
                    union.addAll(values.apply(profile));
                }
            }
            return Set.copyOf(union);
        }
    }

    /**
     * 某个输入模态下的可生成参数。
     *
     * <p>它是能力画像的一等公民而不是 {@link SubmissionProfile} 的嵌套类型：
     * 适配器、目录与测试都要单独构造它，嵌套两层只会让每一处引用
     * 多一段与本意无关的限定名。
     *
     * @param mode {@code TEXT_TO_VIDEO} 或 {@code IMAGE_TO_VIDEO}
     * @param resolutions 该模态可生成的平台计费档位；空集表示该模态不可用
     * @param aspectRatios 该模态可生成的画幅比例；仅在模态可用时有意义
     */
    record ModeProfile(
            String mode,
            Set<String> resolutions,
            Set<String> aspectRatios) {

        public ModeProfile {
            if (mode == null || mode.isBlank() || resolutions == null
                    || aspectRatios == null) {
                throw new IllegalArgumentException("invalid_video_mode_profile");
            }
        }

        /** 平台默认画像下的某个模态（三个档位、五个画幅）。 */
        static ModeProfile of(String mode) {
            return new ModeProfile(mode, PLATFORM_RESOLUTION_TIERS, PLATFORM_ASPECT_RATIOS);
        }
    }

    /**
     * @param id 渠道 id，用于路由以及熔断器键
     * @param providerId 供应商行 id，持久化在任务记录上
     * @param providerType 用于挑选适配器的判别值
     * @param baseUrl 厂商 API 基址
     * @param apiKey 已解密的凭证，厂商不需要凭证时为 null
     * @param upstreamModel 该别名对应的厂商模型名
     */
    record Channel(
            java.util.UUID id,
            java.util.UUID providerId,
            String providerType,
            String baseUrl,
            String apiKey,
            String upstreamModel) {

        public Channel {
            if (id == null || providerId == null || providerType == null || providerType.isBlank()
                    || baseUrl == null || baseUrl.isBlank()
                    || upstreamModel == null || upstreamModel.isBlank()) {
                throw new com.yuzhihuihe.apiplatform.gateway.provider.ChannelFaultException(
                        "invalid_video_channel");
            }
        }
    }

    /** 已被厂商接受的任务。 */
    record SubmittedTask(String upstreamTaskId) {
        public SubmittedTask {
            if (upstreamTaskId == null || upstreamTaskId.isBlank()) {
                throw new IllegalArgumentException("invalid_video_task_id");
            }
        }
    }

    /**
     * 任务的状态。
     *
     * @param billedSeconds 厂商将据以计费的秒数，仅在任务进入终态后才存在。
     *        对失败的生成仍会计费的厂商会在此报告实际尝试的时长；
     *        不计费的厂商报告零，结算随后就不向客户收取任何费用。
     * @param outputUrl 成功时，已完成的视频可从何处获取
     * @param errorCategory 用于日志与支持的稳定分类，绝不是厂商的原始文本
     */
    record TaskStatus(
            State state,
            Integer billedSeconds,
            String outputUrl,
            String errorCategory) {

        public enum State { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

        public TaskStatus {
            if (state == null) {
                throw new IllegalArgumentException("invalid_video_task_state");
            }
            if (billedSeconds != null && billedSeconds < 0) {
                throw new IllegalArgumentException("invalid_video_task_state");
            }
            if (state != State.SUCCEEDED && state != State.FAILED && state != State.CANCELLED
                    && billedSeconds != null) {
                // 非终态还无法知道将会被计费多少。
                throw new IllegalArgumentException("invalid_video_task_state");
            }
        }

        public boolean terminal() {
            return state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED;
        }

        public static TaskStatus pending() {
            return new TaskStatus(State.PENDING, null, null, null);
        }

        public static TaskStatus running() {
            return new TaskStatus(State.RUNNING, null, null, null);
        }
    }
}
