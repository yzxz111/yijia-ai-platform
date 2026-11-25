package com.yuzhihuihe.apiplatform.gateway.file;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 公网匿名读图的装配。
 *
 * <p>签名器**总是**被建出来，即使密钥为空——「功能关闭」是签名器自己的一个状态
 * （{@link FileContentTokenSigner#enabled()} 为假），而不是一个缺失的 bean。
 * 于是调用方只有一条代码路径，不必在每一处都判「这个 bean 在不在」。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties(FileContentTokenProperties.class)
public class FileContentConfiguration {

    @Bean
    FileContentTokenSigner fileContentTokenSigner(FileContentTokenProperties properties) {
        return new FileContentTokenSigner(properties);
    }
}
