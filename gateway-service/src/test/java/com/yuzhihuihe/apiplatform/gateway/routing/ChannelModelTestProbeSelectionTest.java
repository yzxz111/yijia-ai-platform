package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.provider.DeepSeekAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelModelTestView;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 「测试模型」挑不挑这条别名：能力守卫与健康态副作用。
 *
 * <p>刻意不启容器。真实 HTTP 的端到端路径由 {@code ChannelModelTestServiceTest} 覆盖，而这里
 * 钉的是两条不依赖网络的结论：非对话别名**一个请求都不发**、**一个健康态都不写**；同一条渠道上
 * 的对话别名则照旧走真实探测。这两条如果只写在容器用例里，在不能跑 Docker 的机器上就无人看守。
 */
class ChannelModelTestProbeSelectionTest {

    private static final UUID CHANNEL_ID =
            UUID.fromString("84000000-0000-4000-8000-000000000076");

    private final GatewayAdminRepository repository = mock(GatewayAdminRepository.class);
    private final ChannelModelTestService service = new ChannelModelTestService(
            repository, cipher(),
            List.of(new DeepSeekAdapter(WebClient.builder(), new ObjectMapper())));

    @Test
    void doesNotChatProbeAnAudioAliasAndLeavesTheHealthAlone() {
        when(repository.channelProbe(CHANNEL_ID)).thenReturn(Mono.just(row("DEEPSEEK")));
        when(repository.channelModels(CHANNEL_ID))
                .thenReturn(Flux.just(choice(
                        "audio-asr-basic", "语音识别基础版", "qwen-audio-3.0-asr-flash", false)));

        ChannelModelTestView result =
                service.test(CHANNEL_ID, "qwen-audio-3.0-asr-flash", false).block();

        assertThat(result).isNotNull();
        assertThat(result.modelInvoked()).isFalse();
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo("alias_not_chat_capable");
        assertThat(result.upstreamModel()).isEqualTo("qwen-audio-3.0-asr-flash");
        // 一次没打对端点的探测不能构成「这个渠道异常」的证据：音频路由要求 HEALTHY，
        // 写进去就等于把整条音频链路堵死。
        assertThat(result.healthPersisted()).isFalse();
        verify(repository, never()).updateChannelHealth(any(), any(), any());
    }

    @Test
    void stillProbesATextAliasOnTheSameChannel() {
        when(repository.channelProbe(CHANNEL_ID)).thenReturn(Mono.just(row("DEEPSEEK")));
        when(repository.channelModels(CHANNEL_ID))
                .thenReturn(Flux.just(choice(
                        "vision-tianyi", "天翼视觉理解", "Qwen2.5-VL-72B-Instruct", true)));
        when(repository.updateChannelHealth(any(), any(), any())).thenReturn(Mono.just(1L));

        ChannelModelTestView result =
                service.test(CHANNEL_ID, "Qwen2.5-VL-72B-Instruct", false).block();

        assertThat(result).isNotNull();
        // 地址打不通（{@code 127.0.0.1:1}），但这条别名**确实**走上了真实探测那条路：
        // 结论不是能力守卫给的那个。
        assertThat(result.reason()).isEqualTo("upstream_unreachable");
        assertThat(result.reachable()).isFalse();
    }

    private static GatewayAdminRepository.ChannelModelChoice choice(
            String modelCode, String displayName, String upstreamModel, boolean textCapable) {
        return new GatewayAdminRepository.ChannelModelChoice(
                modelCode, displayName, upstreamModel, true, true, textCapable);
    }

    /**
     * 渠道探测行：地址必须是本机且打不通，因此真实探测路径会以「连不上」结束，
     * 而不是真的发出一次请求。
     */
    private static GatewayAdminRepository.ChannelProbeRow row(String providerType) {
        return new GatewayAdminRepository.ChannelProbeRow(
                "tianyi-audio-primary", "http://127.0.0.1:1", "LOCAL", 30_000,
                OffsetDateTime.now(), "provider-secret".getBytes(StandardCharsets.UTF_8),
                providerType);
    }

    /** 与线上同形的一次性密钥：这条路径不会解密它，构造器却需要一把。 */
    private static CredentialCipher cipher() {
        String key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        return new CredentialCipher(key);
    }
}
