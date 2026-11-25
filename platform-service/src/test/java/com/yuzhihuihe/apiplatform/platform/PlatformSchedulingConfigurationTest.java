package com.yuzhihuihe.apiplatform.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

class PlatformSchedulingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PlatformSchedulingConfiguration.class);

    @Test
    void schedulingIsEnabledByDefault() {
        contextRunner.run(context -> assertThat(context.containsBean(
                        TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                .isTrue());
    }

    @Test
    void schedulingCanBeDisabledForManagedTestLifecycles() {
        contextRunner
                .withPropertyValues("platform.scheduling.enabled=false")
                .run(context -> assertThat(context.containsBean(
                                TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                        .isFalse());
    }
}
