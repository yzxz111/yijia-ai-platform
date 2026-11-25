package com.yuzhihuihe.apiplatform.gateway.testsupport;

import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

public final class GatewaySchedulingTestExecutionListener
        extends AbstractTestExecutionListener {

    private static final String PROPERTY = "gateway.scheduling.enabled";
    private static final String APPLIED_ATTRIBUTE =
            GatewaySchedulingTestExecutionListener.class.getName() + ".applied";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (System.getProperty(PROPERTY) == null) {
            System.setProperty(PROPERTY, "false");
            testContext.setAttribute(APPLIED_ATTRIBUTE, true);
        }
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        if (Boolean.TRUE.equals(testContext.getAttribute(APPLIED_ATTRIBUTE))) {
            System.clearProperty(PROPERTY);
        }
    }
}
