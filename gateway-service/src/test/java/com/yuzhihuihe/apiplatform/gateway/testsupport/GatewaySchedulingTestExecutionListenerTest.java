package com.yuzhihuihe.apiplatform.gateway.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.test.context.TestContext;

class GatewaySchedulingTestExecutionListenerTest {

    private static final String PROPERTY = "gateway.scheduling.enabled";

    private final GatewaySchedulingTestExecutionListener listener =
            new GatewaySchedulingTestExecutionListener();
    private String originalValue;

    @BeforeEach
    void clearSchedulingOverride() {
        originalValue = System.clearProperty(PROPERTY);
    }

    @AfterEach
    void restoreSchedulingOverride() {
        if (originalValue == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, originalValue);
        }
    }

    @Test
    void disablesSchedulingOnlyForTheSpringTestClassLifecycle() {
        TestContext context = mock(TestContext.class);
        when(context.getAttribute(anyString())).thenReturn(true);

        listener.beforeTestClass(context);
        assertThat(System.getProperty(PROPERTY)).isEqualTo("false");

        listener.afterTestClass(context);
        assertThat(System.getProperty(PROPERTY)).isNull();
        verify(context).setAttribute(anyString(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void preservesAnExplicitSchedulingOverride() {
        System.setProperty(PROPERTY, "true");
        TestContext context = mock(TestContext.class);

        listener.beforeTestClass(context);
        listener.afterTestClass(context);

        assertThat(System.getProperty(PROPERTY)).isEqualTo("true");
    }
}
