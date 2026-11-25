package com.yuzhihuihe.apiplatform.gateway.provider;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class DeepSeekAdapter extends OpenAiCompatibleProviderAdapter {

    private static final Set<ChannelRouter.Capability> CAPABILITIES = Set.of(ChannelRouter.Capability.TEXT);

    public DeepSeekAdapter(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        super(webClientBuilder, objectMapper);
    }

    /**
     * Spring 用的构造器：带上「首帧」与「空闲」两个等待窗口。
     *
     * <p>保留两参构造器是给测试与无上下文场景用的（走默认窗口）：那些用例关心的是协议
     * 转换与错误分类，不该为一个与它们无关的策略签名而集体改动。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public DeepSeekAdapter(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            ChatStreamTimeoutPolicy timeouts) {
        super(webClientBuilder, objectMapper, timeouts);
    }

    public static Set<ChannelRouter.Capability> supportedCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public String providerType() {
        return "DEEPSEEK";
    }

    @Override
    public Set<ChannelRouter.Capability> capabilities() {
        return CAPABILITIES;
    }
}
