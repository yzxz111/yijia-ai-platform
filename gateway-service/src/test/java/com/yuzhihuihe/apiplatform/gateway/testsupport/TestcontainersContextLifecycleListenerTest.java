package com.yuzhihuihe.apiplatform.gateway.testsupport;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;

import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.testcontainers.junit.jupiter.Testcontainers;

class TestcontainersContextLifecycleListenerTest {

    private final TestcontainersContextLifecycleListener listener =
            new TestcontainersContextLifecycleListener();

    @Test
    void closesSpringContextForTestcontainersClass() {
        TestContext context = mock(TestContext.class);
        doReturn(ContainerBackedTest.class).when(context).getTestClass();

        listener.afterTestClass(context);

        verify(context).markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
    }

    @Test
    void leavesOtherSpringContextsCacheable() {
        TestContext context = mock(TestContext.class);
        doReturn(PlainTest.class).when(context).getTestClass();

        listener.afterTestClass(context);

        verify(context).getTestClass();
        verifyNoMoreInteractions(context);
    }

    @Testcontainers
    private static final class ContainerBackedTest {}

    private static final class PlainTest {}
}
