package com.yuzhihuihe.apiplatform.platform.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 已发布服务条款与隐私政策的版本。每当这些文档发生变更时递增 {@code version}，
 * 以便已记录的接受行为保持可审计。提供空白值时回退到
 * 内置默认值，而不是启动失败。
 */
@Component
@ConfigurationProperties(prefix = "platform.consent")
public class ConsentProperties {

    static final String DEFAULT_VERSION = "2026-09-15";

    private String version = DEFAULT_VERSION;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        if (version == null || version.isBlank()) {
            this.version = DEFAULT_VERSION;
            return;
        }
        this.version = version.strip();
    }
}
