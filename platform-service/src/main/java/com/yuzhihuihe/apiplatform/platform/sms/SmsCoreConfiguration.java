package com.yuzhihuihe.apiplatform.platform.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.yuzhihuihe.apiplatform.platform.security.SecretEncryptionService;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sms", name = "enabled", havingValue = "true")
class SmsCoreConfiguration {

    @Bean
    SecretEncryptionService smsSecretEncryptionService(
            @Value("${sms.security.encryption-key:}") String encryptionKey) {
        return new SecretEncryptionService(encryptionKey);
    }
}
