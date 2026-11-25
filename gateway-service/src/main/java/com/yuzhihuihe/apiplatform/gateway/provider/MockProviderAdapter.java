package com.yuzhihuihe.apiplatform.gateway.provider;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class MockProviderAdapter extends OpenAiCompatibleProviderAdapter {

    private static final Set<ChannelRouter.Capability> CAPABILITIES = Set.of(
            ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE, ChannelRouter.Capability.FILE);

    public MockProviderAdapter(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        super(webClientBuilder, objectMapper);
    }

    /**
     * Spring 用的构造器。MOCK 渠道也走同一条流式超时链路：如果它只拿到默认值，
     * 「测试渠道」与「真实渠道」的超时语义就会分叉，本地能过、生产不能过。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public MockProviderAdapter(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            ChatStreamTimeoutPolicy timeouts) {
        super(webClientBuilder, objectMapper, timeouts);
    }

    @Override
    public String providerType() {
        return "MOCK";
    }

    @Override
    public Set<ChannelRouter.Capability> capabilities() {
        return CAPABILITIES;
    }
}
