package com.yuzhihuihe.apiplatform.platform.testsupport;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.testcontainers.junit.jupiter.Testcontainers;

public final class TestcontainersContextLifecycleListener
        extends AbstractTestExecutionListener {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        if (AnnotatedElementUtils.hasAnnotation(testContext.getTestClass(), Testcontainers.class)) {
            testContext.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
        }
    }
}
