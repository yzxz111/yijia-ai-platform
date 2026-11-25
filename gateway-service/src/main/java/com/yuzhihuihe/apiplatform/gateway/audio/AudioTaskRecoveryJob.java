package com.yuzhihuihe.apiplatform.gateway.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 恢复那些供应商或配额转换未被观测到完成的**异步**音频作业。
 *
 * <p>与 {@code VideoTaskRecoveryJob} 同形，而它服务的是同一类缺陷：
 * 客户端停止轮询之后，一次换口型作业就再也得不到终态结算。
 * 没有它，那笔预留会一直挂到 {@code expires_at}，然后由通用预留清扫释放——
 * 而通用清扫对非 TOKEN 维度是 fail-closed 的
 * （{@code VideoGenerationService:66-70}），因此**该收的秒数永远收不回来**，
 * 同时客户可能已经拿到了产出。
 *
 * <p>本任务做最后一次上游轮询，正是为了让「在客户端离开之后才完成的作业」
 * 仍然被计费。只有轮询之后仍非终态才释放。
 *
 * <p><b>同步作业也要扫。</b>转写与合成是同步的：它们的预留与结算发生在同一次
 * 调用里，因此一条非终态的同步行只可能来自「进程在调用中途死掉」。
 * 那一条没有上游 task id 可查，网关手上没有任何句柄——但那不是继续持有它的
 * 理由，而是**不能**继续持有它的理由：下一次清扫会看到同样的空，永远做不出
 * 不同的决定。任务已过 TTL，{@link AudioGenerationService#recoverExpired}
 * 对那种行按「已经不可能有结果」收尾并释放预留。
 *
 * <p>这条区分（能问上游 vs 不能）决定的是**顺序**，不是**要不要释放**：
 * 能问的先用最后一次轮询换取「在客户端离开之后才完成的作业仍被计费」，
 * 不能问的直接收尾。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class AudioTaskRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(AudioTaskRecoveryJob.class);

    private final AudioGenerationService audioGenerationService;

    AudioTaskRecoveryJob(AudioGenerationService audioGenerationService) {
        this.audioGenerationService = audioGenerationService;
    }

    @Scheduled(
            initialDelayString = "${gateway.audio.recovery.initial-delay-ms:60000}",
            fixedDelayString = "${gateway.audio.recovery.fixed-delay-ms:60000}")
    public void recoverExpiredTasks() {
        audioGenerationService.openTasks(AudioGenerationService.RECOVERY_BATCH_SIZE)
                .filter(task -> task.expiresAt() != null
                        && !task.expiresAt().isAfter(java.time.Instant.now()))
                .flatMap(task -> audioGenerationService
                        .recoverExpired(task)
                        .onErrorResume(error -> {
                            log.warn("audio_task_recovery_failed requestId={} error={}",
                                    task.requestId(), error.getClass().getSimpleName());
                            return Mono.empty();
                        }), 4)
                .then()
                .subscribe(
                        ignored -> { },
                        error -> log.warn("audio_task_recovery_sweep_failed error={}",
                                error.getClass().getSimpleName()));
    }
}
