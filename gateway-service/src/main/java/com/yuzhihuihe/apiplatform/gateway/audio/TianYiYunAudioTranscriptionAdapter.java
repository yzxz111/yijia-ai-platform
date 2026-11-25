package com.yuzhihuihe.apiplatform.gateway.audio;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 天翼云（{@code ai.ctaigw.cn}）的语音转写适配器。
 *
 * <p><b>它与百炼适配器是同一份协议，只有 provider_type 不同。</b>
 * 运维给出的、在天翼控制台上可直接运行的 {@code curl} 证明了这一点：路径尾部
 * （{@code /services/aigc/multimodal-generation/generation}、
 * {@code /services/audio/asr/transcription}、{@code /tasks/{id}}）、请求体
 * （{@code input.messages[].content[].input_audio.data}、filetrans 的
 * {@code input.file_urls}）以及 {@code Authorization} /
 * {@code X-DashScope-Async} / {@code X-DashScope-SSE} 三个头都与百炼逐字相同，
 * 价格也都是 0.00022 元/秒。**唯一的差别是版本根**：天翼是 {@code /v1}，
 * 百炼是 {@code /api/v1}。
 *
 * <p>版本根不在这里判断，而是由
 * {@link BailianAudioTranscriptionAdapter#versionRoot} 从**渠道自己的 base_url**
 * 推导——天翼音频渠道把 base_url 存成 {@code https://ai.ctaigw.cn/v1} 即可。
 * 因此本类只做一件事：把同一份实现注册到第二个 provider_type 上。
 *
 * <p><b>为什么必须是独立的一个 bean，而不是改百炼那一个的 providerType。</b>
 * {@code AudioAdapterRegistry} 按 provider_type 建索引，并且把重复注册当作
 * <b>启动失败</b>（{@code duplicate_audio_transcription_adapter}）。而天翼渠道的
 * provider_type 是 {@code TIANYIYUN}，与 {@code BAILIAN} 是两条不同的索引项；
 * 只声明一个类型会让另一条渠道在运行时以
 * {@code UnsupportedAudioProviderException} 找不到适配器。
 *
 * <p><b>为什么用委托而不是复制或继承。</b>
 * {@code BailianAudioTranscriptionAdapter} 是 {@code final}（刻意的：它不该被
 * 继承出一份会漂移的协议副本）。{@code AudioTranscriptionAdapter} 只有三个方法，
 * 因此委托既不必改动那份已经过契约测试的实现，也不会产生第二份协议代码——
 * 将来协议若有真变化，只需改一处。
 */
@Component
public final class TianYiYunAudioTranscriptionAdapter implements AudioTranscriptionAdapter {

    /** 与 {@code gateway.provider.provider_type} 的取值一致（见 V90/V93 的渠道注册）。 */
    private static final String PROVIDER_TYPE = "TIANYIYUN";

    private final BailianAudioTranscriptionAdapter delegate;

    public TianYiYunAudioTranscriptionAdapter(WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper) {
        this.delegate = new BailianAudioTranscriptionAdapter(webClientBuilder, objectMapper);
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public Set<String> capabilities() {
        return delegate.capabilities();
    }

    @Override
    public Mono<TranscriptionResult> transcribe(TranscriptionRequest request) {
        return delegate.transcribe(request);
    }
}
