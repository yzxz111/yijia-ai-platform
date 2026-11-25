package com.yuzhihuihe.apiplatform.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration
public class GatewayWorkSchedulerConfiguration {

    @Bean(name = "contentSafetyScheduler", destroyMethod = "dispose")
    Scheduler contentSafetyScheduler() {
        return Schedulers.newBoundedElastic(4, 200, "content-safety");
    }

    @Bean(name = "fileSafetyScheduler", destroyMethod = "dispose")
    Scheduler fileSafetyScheduler() {
        return Schedulers.newBoundedElastic(4, 100, "file-safety");
    }

    @Bean(name = "multimodalPreprocessingScheduler", destroyMethod = "dispose")
    Scheduler multimodalPreprocessingScheduler() {
        return Schedulers.newBoundedElastic(4, 100, "multimodal-preprocessing");
    }
}
