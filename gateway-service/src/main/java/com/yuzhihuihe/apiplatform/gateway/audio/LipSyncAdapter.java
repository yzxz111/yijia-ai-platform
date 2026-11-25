package com.yuzhihuihe.apiplatform.gateway.audio;

import java.util.Set;

import reactor.core.publisher.Mono;

/**
 * 能够用一段配音替换人物视频口型的供应商。
 *
 * <p><b>它是异步任务协议</b>，与 {@code VideoGenerationAdapter} 同形而不是与
 * 本包的另外两个适配器同形：提交返回一个 task id、任务被轮询、只有终态才产出
 * 结果。因此它同样需要一张幂等锚点表与一套恢复扫描——一次丢失的响应必须能被
 * 重新查到，而一次重放绝不能买第二次付费作业。
 *
 * <p>百炼的 VideoRetalk 还有一个必须写下来的容量事实：**同一时刻每个账号只允许
 * 1 个作业处于运行状态**，其余排队。排队时长因此不是异常而是常态，
 * 而它直接决定了渠道的 {@code max_concurrency} 该配多少——配成 4 只会让三个
 * 请求在厂商侧排队而不是在网关侧被拒绝，两者对客户是不同的体验。
 *
 * <p><b>计费单位与产出物的关系是本接口最容易出错的地方。</b>产出物是一段**视频**，
 * 但需求里的这个能力属于音频链路（配音 → 换口型），而 V92 按
 * {@code AUDIO_SECOND} 给它定价。厂商按**生成视频的秒数**计费
 * （{@code usage.video_duration}，单位秒，带小数）。因此：
 *
 * <ul>
 *   <li>本接口只交回**秒**，绝不交回帧数。厂商回报 {@code fps} 是描述产出，
 *       不是计费单位；把它乘进去会让一次 25fps 的 7.2 秒作业按 180 秒结算，
 *       即 25 倍的错价。</li>
 *   <li>秒数向上取整：厂商报 7.2 秒并按 7.2 秒收费，报 7 秒会少收。</li>
 * </ul>
 */
public interface LipSyncAdapter {

    /** 与 {@code gateway.provider.provider_type} 匹配。 */
    String providerType();

    /** 仅支持换口型的适配器返回 {@code LIP_SYNC}。 */
    Set<String> capabilities();

    /**
     * 校验供应商特有的请求画像，不预留配额，也不发起网络调用。
     *
     * <p>这个厂商的输入限制非常具体（视频 2-120 秒、边长 640-2048、
     * 帧率 15-60fps、音频 2-120 秒），而违反它们的请求会**在付过一次提交之后**
     * 才被拒绝。能在本地发现的部分都在这里发现。
     */
    default void validateSubmission(LipSyncRequest request) {
        // 没有更窄契约的供应商可以依赖网关层面的校验。
    }

    /**
     * 提交一次换口型作业并返回厂商的 task id。
     *
     * <p>对同一次逻辑提交，在没有幂等键的情况下绝不能被调用两次：
     * 重复提交对这个厂商而言就是第二次付费作业。
     */
    Mono<SubmittedLipSync> submit(LipSyncRequest request);

    /** 轮询一个先前已提交的作业。 */
    Mono<LipSyncStatus> poll(LipSyncRequest request, String upstreamTaskId);

    /**
     * @param channel 解析出的上游渠道，包含其已解密的凭证
     * @param requestId 网关请求 id，用于日志与对账
     * @param videoUrl 人物视频的公网可访问位置；厂商自己去拉它
     * @param audioUrl 配音音频的公网可访问位置
     * @param referenceImageUrl 可选的人脸参考图，用于视频中有多张人脸时指定目标
     * @param extendVideo 音频比视频长时是否扩展视频长度。
     *        {@code false} 时厂商会**截断音频**——这是一个产品可见的取舍，
     *        因此由调用方显式决定，而不是由适配器挑一个默认值
     * @param reservedSeconds 服务层预留的秒数，也是计费秒数的上限
     * @param timeoutMs 该渠道的超时预算
     */
    record LipSyncRequest(
            Channel channel,
            String requestId,
            String videoUrl,
            String audioUrl,
            String referenceImageUrl,
            boolean extendVideo,
            int reservedSeconds,
            int timeoutMs) {

        public LipSyncRequest {
            if (channel == null || requestId == null || requestId.isBlank()
                    || videoUrl == null || videoUrl.isBlank()
                    || audioUrl == null || audioUrl.isBlank()
                    || reservedSeconds <= 0
                    || timeoutMs <= 0) {
                throw new IllegalArgumentException("invalid_lip_sync_request");
            }
            // 厂商需要自己去拉这两个文件，因此它们必须是绝对 http(s) 位置。
            requireAbsoluteHttp(videoUrl);
            requireAbsoluteHttp(audioUrl);
            if (referenceImageUrl != null && !referenceImageUrl.isBlank()) {
                requireAbsoluteHttp(referenceImageUrl);
            } else {
                referenceImageUrl = null;
            }
        }

        private static void requireAbsoluteHttp(String value) {
            String candidate = value.strip().toLowerCase(java.util.Locale.ROOT);
            if (!candidate.startsWith("https://") && !candidate.startsWith("http://")) {
                throw new IllegalArgumentException("invalid_lip_sync_request");
            }
        }
    }

    /**
     * @param id 渠道 id，用于路由
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
                        "invalid_audio_channel");
            }
        }
    }

    /** 已被厂商接受的作业。 */
    record SubmittedLipSync(String upstreamTaskId) {
        public SubmittedLipSync {
            if (upstreamTaskId == null || upstreamTaskId.isBlank()) {
                throw new IllegalArgumentException("invalid_lip_sync_task_id");
            }
        }
    }

    /**
     * 作业状态。
     *
     * @param state 归一化后的状态
     * @param billedSeconds 厂商将据以计费的**生成视频秒数**，仅在终态后存在。
     *        失败的作业只有在厂商明确报告了用量时才计费，否则计零
     * @param outputUrl 成功时，已完成的视频可从何处获取
     * @param errorCategory 用于日志与支持的稳定分类，绝不是厂商的原始文本
     */
    record LipSyncStatus(
            State state,
            Integer billedSeconds,
            String outputUrl,
            String errorCategory) {

        public enum State { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

        public LipSyncStatus {
            if (state == null) {
                throw new IllegalArgumentException("invalid_lip_sync_state");
            }
            if (billedSeconds != null && billedSeconds < 0) {
                throw new IllegalArgumentException("invalid_lip_sync_state");
            }
            if (state != State.SUCCEEDED && state != State.FAILED && state != State.CANCELLED
                    && billedSeconds != null) {
                // 非终态还无法知道将会被计费多少。
                throw new IllegalArgumentException("invalid_lip_sync_state");
            }
        }

        public boolean terminal() {
            return state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED;
        }

        public static LipSyncStatus pending() {
            return new LipSyncStatus(State.PENDING, null, null, null);
        }

        public static LipSyncStatus running() {
            return new LipSyncStatus(State.RUNNING, null, null, null);
        }
    }
}
