package com.yuzhihuihe.apiplatform.gateway.audio;

import java.util.List;
import java.util.Set;

import reactor.core.publisher.Mono;

/**
 * 能够把音频转写成文字与带时间戳字幕的供应商。
 *
 * <p><b>为什么单独一个接口，而不是复用 {@code ProviderAdapter}。</b>
 * {@code ProviderAdapter} 是围绕 {@code /chat/completions} 构造的：它的输入是
 * {@code messages}，它的计费单位是 token。而一次语音转写吃的是一个音频位置、
 * 吐出的是一段文字加一串时间戳，计费单位是**音频秒数**。把它塞进对话接口就意味着
 * 要发明一条假的用户消息、并且从 token 数里反推音频时长——那会让成本基准
 * （分/秒）与计费单位（token）两套东西互相污染，而 V40 的
 * {@code ck_model_usage_price_shape} 正是为了阻止这种混用才存在的。
 *
 * <p><b>与图片/视频路径的关系。</b>本接口是**同步**的：一次请求就是一次转写，
 * 一次响应就是全部结果，没有 task id、没有轮询、没有终态（与
 * {@code ImageGenerationAdapter} 同形，而非与 {@code VideoGenerationAdapter} 同形）。
 * 因此它只发出恰好一次上游请求，内部绝不重试：该端点不接受幂等键，
 * 一次重试就是第二次付费转写。
 */
public interface AudioTranscriptionAdapter {

    /** 与 {@code gateway.provider.provider_type} 匹配。 */
    String providerType();

    /**
     * 本适配器能够服务的能力。仅支持转写的适配器返回 {@code AUDIO_TRANSCRIBE}。
     */
    Set<String> capabilities();

    /**
     * 校验供应商特有的请求画像，不预留配额，也不发起网络调用。
     *
     * <p>服务层在解析出渠道之后、预留音频秒数之前调用本钩子，
     * 因此本地即可发现的不良请求不会留下一个已付费的占用。
     */
    default void validateSubmission(TranscriptionRequest request) {
        // 没有更窄契约的供应商可以依赖网关层面的校验。
    }

    /** 转写一段音频并返回文字与时间戳。 */
    Mono<TranscriptionResult> transcribe(TranscriptionRequest request);

    /**
     * @param channel 解析出的上游渠道，包含其已解密的凭证
     * @param requestId 网关请求 id，可用于日志与对账
     * @param audioUrl 音频的公网可访问位置；厂商自己去拉这个文件，
     *        因此它必须是绝对 http(s) 地址
     * @param audioFormat 厂商需要的容器格式提示（{@code wav} / {@code mp3} …）
     * @param sampleRate 采样率提示；厂商在多数格式下会自行探测
     * @param languageHints 期望的语种提示，可为空
     * @param reservedSeconds 服务层为这次调用预留的秒数，也是计费秒数的上限
     * @param timeoutMs 该渠道的超时预算
     */
    record TranscriptionRequest(
            Channel channel,
            String requestId,
            String audioUrl,
            String audioFormat,
            String sampleRate,
            List<String> languageHints,
            int reservedSeconds,
            int timeoutMs) {

        public TranscriptionRequest {
            if (channel == null || requestId == null || requestId.isBlank()
                    || audioUrl == null || audioUrl.isBlank()
                    || audioFormat == null || audioFormat.isBlank()
                    || sampleRate == null || sampleRate.isBlank()
                    || reservedSeconds <= 0
                    || timeoutMs <= 0) {
                throw new IllegalArgumentException("invalid_audio_transcription_request");
            }
            // 厂商要自己去下载这个文件，因此相对路径或一段 base64 只会在上游
            // 变成一次难懂的错误，而不是一次本地就可发现的拒绝。
            String candidate = audioUrl.strip().toLowerCase(java.util.Locale.ROOT);
            if (!candidate.startsWith("https://") && !candidate.startsWith("http://")) {
                throw new IllegalArgumentException("invalid_audio_transcription_request");
            }
            languageHints = languageHints == null ? List.of() : List.copyOf(languageHints);
        }
    }

    /**
     * @param id 渠道 id，用于路由以及熔断器键
     * @param providerId 供应商行 id，持久化在生成记录上
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

    /**
     * 一句/一段带时间戳的识别结果。
     *
     * <p><b>时间戳是这个接口存在的产品理由。</b>需求要的是「带时间戳的字幕」，
     * 而字幕的消费者是视频剪辑，不是人眼：它需要结构化的起止毫秒数，
     * 而不是一段已经格式化好的 {@code "00:00:01,200 --> 00:00:03,400"} 文本。
     * 因此这里交出毫秒整数，把格式化留给真正的消费方。
     *
     * @param beginTimeMs 该段起始毫秒
     * @param endTimeMs 该段结束毫秒
     * @param text 该段的文本
     */
    record Segment(long beginTimeMs, long endTimeMs, String text) {

        public Segment {
            if (beginTimeMs < 0 || endTimeMs < beginTimeMs
                    || text == null || text.isBlank()) {
                throw new IllegalArgumentException("invalid_audio_transcription_segment");
            }
        }
    }

    /**
     * 一次已完成的转写。
     *
     * @param text 完整识别文本
     * @param segments 带时间戳的分句；**厂商不返回时间戳时为空列表**。
     *        调用方必须把「空」读成「本次没有字幕」，而不是「字幕为空字符串」——
     *        两者对下游是完全不同的事实
     * @param billedSeconds 厂商将据以计费的音频秒数。语音识别的厂商按**输入音频
     *        时长**计费，而不是按转写耗时，因此该值来自厂商对音频本身的度量
     */
    record TranscriptionResult(String text, List<Segment> segments, int billedSeconds) {

        public TranscriptionResult {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("invalid_audio_transcription_result");
            }
            if (billedSeconds < 0) {
                throw new IllegalArgumentException("invalid_audio_transcription_result");
            }
            segments = segments == null ? List.of() : List.copyOf(segments);
        }

        /** 本次结果是否真的带回了可用的字幕。 */
        public boolean hasTimestamps() {
            return !segments.isEmpty();
        }
    }
}
