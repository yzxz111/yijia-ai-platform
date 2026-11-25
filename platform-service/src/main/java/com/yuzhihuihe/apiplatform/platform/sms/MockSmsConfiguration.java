package com.yuzhihuihe.apiplatform.platform.sms;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${sms.enabled:false}' == 'true' and '${sms.provider:aliyun}' == 'mock'")
class MockSmsConfiguration {

    @Bean
    SmsProvider mockSmsProvider() {
        return request -> SmsProvider.Result.success(
                "mock-" + UUID.randomUUID(), "mock-" + UUID.randomUUID());
    }
}
