package com.yuzhihuihe.apiplatform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuzhihuihe.apiplatform.gateway.safety.ContentSafetyOrchestrator;
import com.yuzhihuihe.apiplatform.gateway.safety.ContentSafetyProperties;
import com.yuzhihuihe.apiplatform.gateway.safety.PolicySnapshotClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.reactive.TransactionalOperator;

@SpringBootTest(
        classes = GatewayServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "security.api-key.pepper=test-api-key-pepper",
                "security.internal.hmac-secret=test-internal-secret",
                "gateway.economics.outbox.enabled=false",
                "gateway.scheduling.enabled=false"
        })
class GatewayServiceApplicationTests {

    @Autowired ApplicationContext applicationContext;

    @MockitoBean DatabaseClient databaseClient;
    @MockitoBean TransactionalOperator transactionalOperator;
    /**
     * 本用例以 webEnvironment=NONE 启动，而 PolicySnapshotClient 标了
     * {@code @ConditionalOnWebApplication(REACTIVE)}，条件不成立就不会注册；
     * 但它被 ContentSafetyOrchestrator 无条件依赖，于是上下文起不来。
     * 这里按测试既有的 @MockitoBean 风格补一个替身（等价于给它一份 bean 定义）。
     */
    @MockitoBean
    com.yuzhihuihe.apiplatform.gateway.safety.PolicySnapshotClient policySnapshotClient;
    /**
     * 同理：SkillPackageIngestService 也是 @ConditionalOnWebApplication(REACTIVE)，
     * 但 ClawHub 导入服务（无条件注解）依赖它。补一份替身让上下文能起来。
     */
    @MockitoBean
    com.yuzhihuihe.apiplatform.gateway.skill.SkillPackageIngestService skillPackageIngestService;

    @Test
    void contextLoads() {
        assertThat(applicationContext.getBeansOfType(SecurityWebFilterChain.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(PolicySnapshotClient.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ContentSafetyOrchestrator.class)).hasSize(1);
        assertThat(applicationContext.getBean(ContentSafetyProperties.class).isEnabled()).isTrue();
        assertThat(applicationContext.getBean(ContentSafetyProperties.class).isFailClosed()).isTrue();
        assertThat(applicationContext.containsBean(
                        TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                .isFalse();
    }
}
