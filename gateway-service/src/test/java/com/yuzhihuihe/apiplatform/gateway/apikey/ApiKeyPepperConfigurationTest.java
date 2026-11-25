package com.yuzhihuihe.apiplatform.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

class ApiKeyPepperConfigurationTest {

    private final ReactiveWebApplicationContextRunner contextRunner =
            new ReactiveWebApplicationContextRunner()
                    .withInitializer(context -> context.getBeanFactory().setConversionService(
                            ApplicationConversionService.getSharedInstance()))
                    .withBean(ApiKeyRepository.class, () -> mock(ApiKeyRepository.class))
                    .withBean(DatabaseClient.class, () -> mock(DatabaseClient.class))
                    .withBean(TransactionalOperator.class, () -> mock(TransactionalOperator.class))
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withUserConfiguration(ApiKeyService.class);

    @Test
    void failsClosedWhenApiKeyPepperIsNotConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("api_key_pepper_required");
        });
    }
}
