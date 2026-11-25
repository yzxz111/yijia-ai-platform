package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.scheduling.annotation.Scheduled;

class ReactiveFileJobSchedulingTest {

    @Test
    void letsSpringSubscribeToEveryReactiveFileJob() throws Exception {
        assertReactiveSchedule(ExpiredFileCleanupJob.class, "scheduledCleanup");
        assertReactiveSchedule(FileSafetyScanJob.class, "scan");
        assertReactiveSchedule(OrphanCleanObjectCleanupJob.class, "scheduledCleanup");
        assertReactiveSchedule(QuarantineObjectCleanupJob.class, "scheduledCleanup");
    }

    private static void assertReactiveSchedule(Class<?> jobType, String methodName) throws Exception {
        Method method = jobType.getDeclaredMethod(methodName);
        assertThat(method.getAnnotation(Scheduled.class)).isNotNull();
        assertThat(method.getReturnType()).isAssignableTo(Publisher.class);
    }
}
