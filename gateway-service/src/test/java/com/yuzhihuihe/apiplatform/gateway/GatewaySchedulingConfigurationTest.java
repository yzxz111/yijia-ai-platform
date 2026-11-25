package com.yuzhihuihe.apiplatform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

class GatewaySchedulingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GatewaySchedulingConfiguration.class);

    @Test
    void schedulingIsEnabledByDefault() {
        contextRunner.run(context -> assertThat(context.containsBean(
                        TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                .isTrue());
    }

    @Test
    void schedulingCanBeDisabledForManagedTestLifecycles() {
        contextRunner
                .withPropertyValues("gateway.scheduling.enabled=false")
                .run(context -> assertThat(context.containsBean(
                                TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                        .isFalse());
    }
}
