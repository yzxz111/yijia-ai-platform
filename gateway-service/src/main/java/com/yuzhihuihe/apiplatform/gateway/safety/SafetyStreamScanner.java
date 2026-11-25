package com.yuzhihuihe.apiplatform.gateway.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

@Service
public final class SafetyStreamScanner {
    private final ObjectMapper mapper;
    private final OutputSafetyInspector inspector;
    private final ContentSafetyProperties properties;
    private final ContentSafetyMetrics metrics;

    public SafetyStreamScanner(ObjectMapper mapper, OutputSafetyInspector inspector, ContentSafetyProperties properties,
            ContentSafetyMetrics metrics) {
        this.mapper = mapper; this.inspector = inspector; this.properties = properties; this.metrics = metrics;
    }

    public Flux<String> scan(Flux<String> source, ApiKeyService.ApiKeyContext context, String requestId) {
        int limit = Math.max(1, properties.getStream().getWindowCodePoints());
        AtomicBoolean stopped = new AtomicBoolean();
        return source.materialize()
                .bufferTimeout(64, properties.getStream().getMaxWait(), true)
                .concatMap(batch -> inspectSignals(batch, context, requestId, limit, stopped))
                .takeUntil(chunk -> stopped.get() && "[DONE]".equals(chunk));
    }

    private Flux<String> inspectSignals(List<Signal<String>> signals,
            ApiKeyService.ApiKeyContext context, String requestId, int limit, AtomicBoolean stopped) {
        List<String> batch = signals.stream()
                .filter(Signal::hasValue)
                .map(Signal::get)
                .toList();
        Throwable failure = signals.stream()
                .filter(Signal::isOnError)
                .map(Signal::getThrowable)
                .findFirst()
                .orElse(null);
        Flux<String> inspected = inspectBatch(batch, context, requestId, limit, stopped);
        return failure == null ? inspected : inspected.concatWith(Flux.error(failure));
    }

    private Flux<String> inspectBatch(List<String> batch, ApiKeyService.ApiKeyContext context,
            String requestId, int limit, AtomicBoolean stopped) {
        if (stopped.get() || batch.isEmpty()) return Flux.empty();
        String text;
        try {
            text = batch.stream().map(this::textOf).reduce("", String::concat);
        } catch (MalformedStreamChunkException malformed) {
            return stop(stopped, "content_safety_unavailable");
        }
        List<String> windows = split(text, limit);
        if (windows.isEmpty()) return Flux.fromIterable(batch);
        return Flux.fromIterable(windows)
                .concatMap(window -> inspector.inspectText(context, window, requestId))
                .filter(OutputSafetyInspector.Decision::blocked)
                .next()
                .flatMapMany(decision -> stop(stopped, decision.code()))
                .switchIfEmpty(Flux.fromIterable(batch));
    }

    private List<String> split(String value, int maxCodePoints) {
        if (value == null || value.isEmpty()) return List.of();
        java.util.ArrayList<String> windows = new java.util.ArrayList<>();
        for (int start = 0; start < value.length();) {
            int remaining = value.codePointCount(start, value.length());
            int end = remaining <= maxCodePoints ? value.length()
                    : value.offsetByCodePoints(start, maxCodePoints);
            windows.add(value.substring(start, end));
            start = end;
        }
        return windows;
    }

    private Flux<String> stop(AtomicBoolean stopped, String code) {
        stopped.set(true);
        metrics.streamStopped(code);
        // **不发 [DONE]。**
        //
        // 契约：`[DONE]` 是唯一的「这次生成完整结束」标记（见
        // docs/operations/chat-stream-client-notice.md，2026-10-14 与桌面端约定的
        // 联调基线）。内容安全拦截是一次**失败终态**，不是完整结束；错误帧之后补
        // [DONE]，客户端会把一次被拦截的回答读成成功——正是本次改造要消灭的
        // 「假完成信号」，只不过走的是安全这一条通路。
        //
        // 与 ChatCompletionService 自己的失败收尾保持一致：那里同样只发结构化
        // 失败帧、不补 [DONE]。一条流只允许一个确定的语义终态。
        return Flux.just(SseSafetyErrorWriter.error(code));
    }

    private String textOf(String chunk) {
        if (chunk == null || chunk.equals("[DONE]")) return "";
        try {
            JsonNode node = mapper.readTree(chunk);
            JsonNode delta = node.path("choices").path(0).path("delta").path("content");
            if (delta.isTextual()) return delta.asText();
            if (node.path("delta").isTextual()) return node.path("delta").asText();
            return node.path("text").asText("");
        } catch (Exception invalidJson) {
            throw new MalformedStreamChunkException(invalidJson);
        }
    }

    private static final class MalformedStreamChunkException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private MalformedStreamChunkException(Throwable cause) {
            super(cause);
        }
    }
}
