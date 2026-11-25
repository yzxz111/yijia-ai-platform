package com.yuzhihuihe.apiplatform.gateway.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 收尾那些**永远不会自己得出结论**的图片生成。
 *
 * <p>与 {@code VideoTaskRecoveryJob} 和 {@code AudioTaskRecoveryJob} 同形，
 * 但它服务的是三处里最难的一处：图片是同步协议，厂商从不返回 task id，
 * 所以这里没有「再轮询一次」可做——只有「问一次还有没有别的办法知道」，
 * 问不到就按 TTL 收尾。
 *
 * <p>没有它，一次读超时的生图会让客户的额度**永久**少一张：任务停在
 * {@code SUBMITTED}，预留停在 {@code RESERVED}，而通用预留清扫对非 TOKEN 维度
 * 是 fail-closed 的（见 {@code ReservationRecoveryJob}），所以它永远不会替我们
 * 释放。线上一个视频用户已经因为同一个缺口被冻住了全部剩余额度 17 天。
 *
 * <p><b>只扫异步作业的反面——这里只扫同步作业。</b>与音频那个任务刻意分开，
 * 是因为两者需要的信息不同：音频能再问上游一次，图片不能。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class ImageTaskRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(ImageTaskRecoveryJob.class);

    private final ImageGenerationService imageGenerationService;

    ImageTaskRecoveryJob(ImageGenerationService imageGenerationService) {
        this.imageGenerationService = imageGenerationService;
    }

    @Scheduled(
            initialDelayString = "${gateway.image.recovery.initial-delay-ms:60000}",
            fixedDelayString = "${gateway.image.recovery.fixed-delay-ms:60000}")
    public void recoverExpiredTasks() {
        imageGenerationService.openTasks(ImageGenerationService.RECOVERY_BATCH_SIZE)
                .filter(task -> task.expiresAt() != null
                        && !task.expiresAt().isAfter(java.time.Instant.now()))
                .flatMap(task -> imageGenerationService
                        .recoverExpired(task)
                        .onErrorResume(error -> {
                            log.warn("image_task_recovery_failed requestId={} error={}",
                                    task.requestId(), error.getClass().getSimpleName());
                            return Mono.empty();
                        }), 4)
                .then()
                .subscribe(
                        ignored -> { },
                        error -> log.warn("image_task_recovery_sweep_failed error={}",
                                error.getClass().getSimpleName()));
    }
}
