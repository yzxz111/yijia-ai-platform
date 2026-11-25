package com.yuzhihuihe.apiplatform.gateway.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 恢复那些供应商或配额转换未被观测到完成的视频任务。
 *
 * <p>没有它，客户端停止轮询的任务就永远得不到终态
 * 结算。本任务会做最后一次上游轮询——这样在客户端离开之后才完成的
 * 任务仍会被计费，而不是被白送出去。如果供应商仍然报告
 * 非终态，或者轮询结果未知，本任务就保持预留
 * 不放、稍后重试：一次已被接受的生成不能仅仅因为其
 * 状态暂时不可得就被白送出去。
 *
 * <p>终态路径走的是状态端点所用的同一条带守卫的转换，
 * 因此本任务与实时轮询竞争时仍然只结算一次。配额
 * 转换失败的终态任务，会从其持久化的任务状态重试。未知路径
 * 有意为 no-op，并让该任务对后续的恢复清扫保持可见。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class VideoTaskRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(VideoTaskRecoveryJob.class);
    private static final int BATCH_SIZE = 25;

    private final VideoGenerationService videoGenerationService;

    VideoTaskRecoveryJob(VideoGenerationService videoGenerationService) {
        this.videoGenerationService = videoGenerationService;
    }

    @Scheduled(
            initialDelayString = "${gateway.video.recovery.initial-delay-ms:60000}",
            fixedDelayString = "${gateway.video.recovery.fixed-delay-ms:60000}")
    public void recoverExpiredTasks() {
        videoGenerationService.openTasks(BATCH_SIZE)
                .filter(task -> task.expiresAt() != null
                        && !task.expiresAt().isAfter(java.time.Instant.now()))
                .flatMap(task -> videoGenerationService
                        .recoverExpired(task)
                        .onErrorResume(error -> {
                            log.warn("video_task_recovery_failed requestId={} error={}",
                                    task.requestId(), error.getClass().getSimpleName());
                            return reactor.core.publisher.Mono.empty();
                        }), 4)
                .then(videoGenerationService.terminalTasksWithReservedQuota(BATCH_SIZE)
                        .flatMap(task -> videoGenerationService
                                .compensateTerminal(task)
                                .onErrorResume(error -> {
                                    log.warn("video_terminal_compensation_failed requestId={} "
                                                    + "status={} error={}",
                                            task.requestId(), task.status(),
                                            error.getClass().getSimpleName());
                                    return reactor.core.publisher.Mono.empty();
                                }), 4)
                        .then())
                .subscribe(
                        ignored -> { },
                        error -> log.warn("video_task_recovery_sweep_failed error={}",
                                error.getClass().getSimpleName()));
    }
}
