package com.yuzhihuihe.apiplatform.gateway.video;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 一个不生成任何内容、也不产生任何费用的视频适配器，用于在没有上游凭证的情况下
 * 跑通整条 submit → poll → settle 链路。
 *
 * <p>它之所以存在，是因为视频计费必须可验证。网关现有的
 * {@code MockProviderAdapter} 覆盖了聊天，而如果没有对应的视频版本，
 * 「秒」的预留与结算行为就只能靠 mock 适配器自身来测试，
 * 那会让路由、持久化与幂等代码都得不到演练。
 *
 * <p>行为由渠道的 base URL 驱动，因此测试无需改动代码就能选择结果：
 * <ul>
 *   <li>{@code mock://video/succeed} — 开始运行，一次轮询后成功</li>
 *   <li>{@code mock://video/succeed-immediately} — 首次轮询即成功</li>
 *   <li>{@code mock://video/fail} — 一次轮询后失败，并对该次尝试计费
 *       （Kling 的行为：失败的生成仍会在上游产生费用）</li>
 *   <li>{@code mock://video/fail-free} — 一次轮询后失败，不计任何费用
 *       （Veo 的行为）</li>
 *   <li>{@code mock://video/stuck} — 永远不会离开 RUNNING，因此可以演练
 *       过期恢复路径</li>
 * </ul>
 *
 * <p>轮询按上游 task id 计数，因此重复的 GET 会推进状态，
 * 结算也就必须对终态转换保持幂等。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "gateway.video", name = "allow-mock", havingValue = "true")
public final class MockVideoGenerationAdapter implements VideoGenerationAdapter {

    private static final Set<String> CAPABILITIES = Set.of("VIDEO");
    private static final String OUTPUT_URL = "mock://video/output/";
    private static final String BEHAVIOUR_PREFIX = "mock://video/";

    private final ConcurrentHashMap<String, AtomicInteger> pollCounts = new ConcurrentHashMap<>();

    @Override
    public String providerType() {
        return "MOCK";
    }

    @Override
    public Set<String> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Mono<SubmittedTask> submit(TaskRequest request) {
        return Mono.fromSupplier(() -> {
            String behaviour = behaviour(request.channel().baseUrl());
            String taskId = behaviour + "/" + request.requestId() + "/" + UUID.randomUUID();
            pollCounts.put(taskId, new AtomicInteger());
            return new SubmittedTask(taskId);
        });
    }

    @Override
    public Mono<TaskStatus> poll(TaskRequest request, String upstreamTaskId) {
        return Mono.fromSupplier(() -> {
            if (upstreamTaskId == null || upstreamTaskId.isBlank()) {
                return new TaskStatus(TaskStatus.State.FAILED, 0, null, "invalid_task_id");
            }
            String behaviour = upstreamTaskId.substring(0, upstreamTaskId.indexOf('/'));
            int poll = pollCounts
                    .computeIfAbsent(upstreamTaskId, ignored -> new AtomicInteger())
                    .incrementAndGet();

            if ("succeed-immediately".equals(behaviour)) {
                return succeeded(request, upstreamTaskId);
            }
            if ("stuck".equals(behaviour)) {
                // 永远不会进入终态，因此网关的过期机制最终必须对它做结算。
                return TaskStatus.running();
            }
            if (poll < 2) {
                // 只有一个中间状态，因此不再轮询的调用方会把任务留在打开状态，
                // 这正是恢复任务存在的意义。
                return TaskStatus.running();
            }
            return switch (behaviour) {
                case "succeed" -> succeeded(request, upstreamTaskId);
                case "fail" -> new TaskStatus(
                        TaskStatus.State.FAILED, request.durationSeconds(), null, "mock_generation_failed");
                case "fail-free" -> new TaskStatus(
                        TaskStatus.State.FAILED, 0, null, "mock_generation_failed_unbilled");
                default -> new TaskStatus(
                        TaskStatus.State.FAILED, 0, null, "mock_unknown_behaviour");
            };
        });
    }

    private TaskStatus succeeded(TaskRequest request, String upstreamTaskId) {
        return new TaskStatus(
                TaskStatus.State.SUCCEEDED,
                request.durationSeconds(),
                OUTPUT_URL + upstreamTaskId,
                null);
    }

    private String behaviour(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (!value.startsWith(BEHAVIOUR_PREFIX)) {
            // 无法识别的 base URL 会成功而不是静默失败，因为配置错误的渠道
            // 应该表现为路由问题，而不是让客户被计费的生成失败。
            return "succeed-immediately";
        }
        String behaviour = value.substring(BEHAVIOUR_PREFIX.length());
        return behaviour.isBlank() ? "succeed-immediately" : behaviour;
    }
}
