package com.yuzhihuihe.apiplatform.gateway.file;

import java.time.Duration;

import com.yuzhihuihe.apiplatform.gateway.security.RemoteUrlPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class RemoteImageConfiguration {

    @Bean
    RemoteUrlPolicy remoteUrlPolicy(
            @Value("${gateway.responses.remote-image.dns-timeout:2s}") Duration dnsTimeout) {
        return new RemoteUrlPolicy(dnsTimeout);
    }

    @Bean
    RemoteImageFetcher remoteImageFetcher(
            RemoteUrlPolicy policy,
            PinnedReactorNettyTransport transport,
            @Value("${gateway.responses.max-image-bytes:20971520}") long maxBytes,
            @Value("${gateway.responses.remote-image.max-redirects:3}") int maxRedirects,
            @Value("${gateway.responses.remote-image.total-timeout:10s}") Duration totalTimeout) {
        return new RemoteImageFetcher(policy, transport, maxBytes, maxRedirects, totalTimeout);
    }
}
