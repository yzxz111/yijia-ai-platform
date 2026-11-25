package com.yuzhihuihe.apiplatform.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuzhihuihe.apiplatform.platform.payment.MockPaymentAdminController;
import com.yuzhihuihe.apiplatform.platform.payment.MockPaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.MockPaymentController;
import com.yuzhihuihe.apiplatform.platform.payment.MockPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(
        classes = PlatformServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
class PlatformServiceApplicationTests {

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        TestJwtKeyRing.configure(registry);
    }

    @MockitoBean
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    PlatformTransactionManager transactionManager;

    @Autowired
    Environment environment;

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext.getBeansOfType(MockPaymentAdapter.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(MockPaymentService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(MockPaymentController.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(MockPaymentAdminController.class)).isEmpty();
    }

    @Test
    void testProfileDefersRefundSchedulers() {
        assertThat(environment.getProperty(
                "refund.jobs.preparation-delay-ms", Long.class)).isEqualTo(3_600_000L);
        assertThat(environment.getProperty(
                "refund.jobs.compensation-delay-ms", Long.class)).isEqualTo(3_600_000L);
    }
}
