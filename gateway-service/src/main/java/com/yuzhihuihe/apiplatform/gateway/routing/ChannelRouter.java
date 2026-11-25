package com.yuzhihuihe.apiplatform.gateway.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongUnaryOperator;

import org.springframework.stereotype.Component;

@Component
public class ChannelRouter {

    private static final int MAX_ATTEMPTS = 3;

    private final LongUnaryOperator randomIndex;

    public ChannelRouter() {
        this(bound -> java.util.concurrent.ThreadLocalRandom.current().nextLong(bound));
    }

    ChannelRouter(LongUnaryOperator randomIndex) {
        this.randomIndex = randomIndex;
    }

    public List<RouteCandidate> plan(RouteRequest request, List<RouteCandidate> candidates) {
        if (request == null || request.modelCode() == null || request.capability() == null) {
            return List.of();
        }
        Map<Integer, List<RouteCandidate>> byPriority = new LinkedHashMap<>();
        candidates.stream()
                .filter(candidate -> candidate.matches(request))
                .sorted(Comparator.comparingInt(RouteCandidate::priority).reversed())
                .forEach(candidate -> byPriority
                        .computeIfAbsent(candidate.priority(), ignored -> new ArrayList<>())
                        .add(candidate));

        int limit = Math.min(MAX_ATTEMPTS, Math.max(0, request.maxAttempts()));
        List<RouteCandidate> selected = new ArrayList<>(limit);
        for (List<RouteCandidate> group : byPriority.values()) {
            List<RouteCandidate> remaining = new ArrayList<>(group);
            while (!remaining.isEmpty() && selected.size() < limit) {
                selected.add(removeWeighted(remaining));
            }
            if (selected.size() == limit) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    public AttemptCursor start(RouteRequest request, List<RouteCandidate> candidates) {
        return new AttemptCursor(plan(request, candidates), 0, false);
    }

    private RouteCandidate removeWeighted(List<RouteCandidate> candidates) {
        long totalWeight = candidates.stream().mapToLong(RouteCandidate::weight).sum();
        long selectedWeight = Math.floorMod(randomIndex.applyAsLong(totalWeight), totalWeight);
        long cumulative = 0;
        for (int index = 0; index < candidates.size(); index++) {
            cumulative += candidates.get(index).weight();
            if (selectedWeight < cumulative) {
                return candidates.remove(index);
            }
        }
        throw new IllegalStateException("weighted_route_selection_failed");
    }

    public enum Capability {
        TEXT, IMAGE, FILE,
        /**
         * 异步视频生成。它被识别为一种路由能力，因此视频别名只能被路由到声明了该能力的
         * 渠道，但它不是 {@code ProviderAdapter} 的能力：视频是「先提交后轮询」的任务
         * 协议而非聊天补全，由 {@code gateway.video.VideoGenerationAdapter} 提供服务。
         */
        VIDEO
    }

    public record RouteRequest(String modelCode, Capability capability, int maxAttempts) {
    }

    public record RouteCandidate(
            UUID channelId,
            String providerCode,
            String modelCode,
            String upstreamModel,
            Set<Capability> capabilities,
            int priority,
            int weight,
            boolean enabled,
            boolean concurrencyAvailable,
            boolean circuitPermitted,
            boolean packageAllowed,
            boolean apiKeyAllowed) {

        public RouteCandidate {
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
            if (channelId == null || weight <= 0) {
                throw new IllegalArgumentException("invalid_route_candidate");
            }
        }

        boolean matches(RouteRequest request) {
            return request.modelCode().equals(modelCode)
                    && capabilities.contains(request.capability())
                    && enabled && concurrencyAvailable && circuitPermitted
                    && packageAllowed && apiKeyAllowed;
        }

        public String circuitKey() {
            return channelId + ":" + modelCode;
        }
    }

    public record AttemptCursor(List<RouteCandidate> plan, int index, boolean firstByteSent) {

        public AttemptCursor {
            plan = List.copyOf(plan);
        }

        public Optional<RouteCandidate> current() {
            return firstByteSent || index >= plan.size() ? Optional.empty() : Optional.of(plan.get(index));
        }

        public AttemptCursor failBeforeFirstByte() {
            return firstByteSent ? this : new AttemptCursor(plan, index + 1, false);
        }

        public AttemptCursor markFirstByteSent() {
            return new AttemptCursor(plan, index, true);
        }
    }
}
