package com.yuzhihuihe.apiplatform.platform.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${sms.enabled:false}' == 'true' and '${sms.provider:aliyun}' == 'aliyun'")
public class AliyunSmsConfiguration {

    private final String endpoint;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String signName;
    private final String regionId;

    public AliyunSmsConfiguration(
            @Value("${sms.aliyun.endpoint:}") String endpoint,
            @Value("${sms.aliyun.access-key-id:}") String accessKeyId,
            @Value("${sms.aliyun.access-key-secret:}") String accessKeySecret,
            @Value("${sms.aliyun.sign-name:}") String signName,
            @Value("${sms.aliyun.region-id:}") String regionId) {
        this.endpoint = required(endpoint, "sms_aliyun_endpoint_required");
        this.accessKeyId = required(accessKeyId, "sms_aliyun_access_key_id_required");
        this.accessKeySecret = required(accessKeySecret, "sms_aliyun_access_key_secret_required");
        this.signName = required(signName, "sms_aliyun_sign_name_required");
        this.regionId = required(regionId, "sms_aliyun_region_id_required");
    }

    @Bean
    Client aliyunSmsClient() throws Exception {
        Config config = new Config()
                .setAccessKeyId(accessKeyId)
                .setAccessKeySecret(accessKeySecret)
                .setEndpoint(endpoint)
                .setRegionId(regionId);
        return new Client(config);
    }

    @Bean
    SmsProvider aliyunSmsProvider(Client aliyunSmsClient, ObjectMapper objectMapper) {
        return new AliyunSmsProvider(aliyunSmsClient, signName, objectMapper);
    }

    private String required(String value, String errorCode) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(errorCode);
        }
        return value;
    }
}
