package com.yuzhihuihe.apiplatform.gateway.audio;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import reactor.core.publisher.Mono;

/**
 * 能够把文本合成为配音的供应商。
 *
 * <p>与 {@code AudioTranscriptionAdapter} 分开，而不是用一个「音频适配器」两面用：
 * 两者的输入方向、计费度量与失败模式都相反——转写吃音频吐文本、按**输入**音频
 * 时长计费；合成吃文本吐音频、按**产出**音频时长计费。把它们合成一个接口，
 * 就会让「计费的到底是输入还是输出」变成每个实现各自的口头约定，
 * 而那正是最容易被写错、又最贵的一种约定。
 *
 * <p><b>本接口是同步的</b>（百炼的 TTS 一次请求返回完整音频位置），
 * 因此它只发出恰好一次上游请求，内部绝不重试。
 */
public interface AudioSynthesisAdapter {

    /** 与 {@code gateway.provider.provider_type} 匹配。 */
    String providerType();

    /** 仅支持合成的适配器返回 {@code AUDIO_SYNTHESIS}。 */
    Set<String> capabilities();

    /**
     * 校验供应商特有的请求画像，不预留配额，也不发起网络调用。
     *
     * <p>服务层在解析出渠道之后、预留音频秒数之前调用本钩子，
     * 因此本地即可发现的不良请求不会留下一个已付费的占用。
     */
    default void validateSubmission(SynthesisRequest request) {
        // 没有更窄契约的供应商可以依赖网关层面的校验。
    }

    /** 合成一段配音。 */
    Mono<SynthesisResult> synthesize(SynthesisRequest request);

    /**
     * 该适配器已知的厂商内置音色，供 {@code GET /v1/audio-models} 在提交之前
     * 下发，让客户端能渲染一个音色选择器而不是照文档试错。
     *
     * <p>返回空列表表示该适配器不承诺任何内置音色。它是**展示清单**，不是
     * 校验白名单：提交时 {@code voice} 仍然原样透传给厂商，本方法不参与拒绝。
     * 与 {@code VideoGenerationAdapter.submissionProfile} 相同的定位——把边界
     * 在提交之前告诉客户端，而不是让一次 4xx/5xx 成为第一声反馈。
     */
    default List<Voice> voices() {
        return List.of();
    }

    /**
     * @param channel 解析出的上游渠道，包含其已解密的凭证
     * @param requestId 网关请求 id，可用于日志与对账
     * @param text 要合成的文案
     * @param voice 厂商音色名。**必填**，因为「默认音色」是厂商的实现细节，
     *        把它留在网关侧意味着一次厂商改动就能让所有客户的配音换人
     * @param languageType 语种提示（{@code Chinese} / {@code English} …）；
     *        为空时交给厂商自动判断
     * @param audioFormat 期望的容器格式
     * @param reservedSeconds 服务层为这次调用预留的秒数，也是计费秒数的上限
     * @param timeoutMs 该渠道的超时预算
     */
    record SynthesisRequest(
            Channel channel,
            String requestId,
            String text,
            String voice,
            String languageType,
            String audioFormat,
            int reservedSeconds,
            int timeoutMs) {

        public SynthesisRequest {
            if (channel == null || requestId == null || requestId.isBlank()
                    || text == null || text.isBlank()
                    || voice == null || voice.isBlank()
                    || audioFormat == null || audioFormat.isBlank()
                    || reservedSeconds <= 0
                    || timeoutMs <= 0) {
                throw new IllegalArgumentException("invalid_audio_synthesis_request");
            }
            languageType = languageType == null || languageType.isBlank()
                    ? null : languageType.strip();
        }
    }

    /**
     * @param id 渠道 id，用于路由
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
     * 一次已完成的合成。
     *
     * @param audioUrl 产出的音频位置
     * @param billedSeconds 据以计费的**产出**音频秒数
     * @param estimatedDuration 该秒数是否是估算值而非厂商实测。
     *        {@code true} 表示厂商没有交回音频时长，网关按字符数换算得出。
     *        这个标记必须一路带到结算证据里：一条估算的计费与一条实测的计费
     *        在运营报表里必须是可区分的，否则对账时无从判断偏差从哪来
     */
    record SynthesisResult(String audioUrl, int billedSeconds, boolean estimatedDuration) {

        public SynthesisResult {
            if (audioUrl == null || audioUrl.isBlank() || billedSeconds < 0) {
                throw new IllegalArgumentException("invalid_audio_synthesis_result");
            }
        }
    }

    /**
     * 一个厂商内置音色。
     *
     * @param name 客户端提交 {@code voice} 时使用的值，例如 {@code "Cherry"}
     * @param description 展示用的一句话描述，例如「阳光积极的小姐姐」
     */
    record Voice(String name, String description) {

        public Voice {
            if (name == null || name.isBlank()
                    || description == null || description.isBlank()) {
                throw new IllegalArgumentException("invalid_audio_voice");
            }
        }
    }

    /**
     * 把一段文案换算成它大约会产出多少秒音频。
     *
     * <p><b>这个换算同时用在两个地方，而它们必须一致。</b>费率卡的成本基准由
     * 厂商的「元/万字符」按同一个比例折成「分/秒」（见设计文档），而结算时的
     * 计费秒数也由它得出。两者若用了不同的比例，毛利就随文案密度漂移：
     * 用 2 字符/秒折算成本、却按 7 字符/秒结算，则每一秒都少收 3.5 倍的钱。
     *
     * <p><b>为什么取 2 而不是正常中文语速的 7-8 字符/秒。</b>这里刻意取一个
     * **偏保守（偏小）** 的值，于是同样一段文案折算出**更长**的秒数。
     * 原因是这个换算在成本侧本来就是最坏情况口径：一次交付会按实际产出的
     * 秒数收费，而厂商按字符收费，因此按秒结算天然会在语速快时多收、
     * 语速慢时少收。取一个偏长的秒数是在误差里偏向平台，
     * 与 V81:312-318 给图片定的「存峰值而不是平均值」是同一条规矩。
     *
     * <p>厂商一旦开始回报真实时长（本适配器优先采用它），这个常量就只剩
     * 成本折算这一个用途。
     */
    int CHARACTERS_PER_SECOND = 2;

    /** 由字符数估算产出秒数，向上取整——不足一秒的配音也是一次付费合成。 */
    static int estimateSecondsFromCharacters(int characters) {
        if (characters <= 0) {
            return 1;
        }
        return (int) Math.ceil(characters / (double) CHARACTERS_PER_SECOND);
    }

    /** 归一化语种提示。厂商只认自己的枚举，因此只做去空白与大小写，不做映射猜测。 */
    static String normalizeLanguage(String languageType) {
        if (languageType == null || languageType.isBlank()) {
            return null;
        }
        String trimmed = languageType.strip();
        return trimmed.substring(0, 1).toUpperCase(Locale.ROOT) + trimmed.substring(1);
    }
}
