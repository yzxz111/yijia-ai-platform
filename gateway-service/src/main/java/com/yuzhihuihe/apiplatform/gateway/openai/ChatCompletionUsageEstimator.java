package com.yuzhihuihe.apiplatform.gateway.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.ReservationCeiling;
import com.yuzhihuihe.apiplatform.gateway.quota.TokenEstimator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class ChatCompletionUsageEstimator {

    private final TokenEstimator tokenEstimator;
    private final ObjectMapper objectMapper;

    ChatCompletionUsageEstimator(TokenEstimator tokenEstimator, ObjectMapper objectMapper) {
        this.tokenEstimator = tokenEstimator;
        this.objectMapper = objectMapper;
    }

    /**
     * 客户端自己请求的输出上限；当它没有请求任何上限时为 null。
     *
     * <p>与 {@link #boundOutputLimit} 分开读取，因为生效的上限是 {@code min(client, plan, system
     * ceiling)}，其中套餐方案与系统上限这两半位于 {@code ReservationCeiling}；调用方需要客户端的数字作为该决策的输入， 并且要在请求体被改写之前拿到。
     */
    Long requestedOutputLimit(JsonNode body) {
        JsonNode requested =
                body.has("max_completion_tokens")
                        ? body.get("max_completion_tokens")
                        : body.get("max_tokens");
        return requested == null || requested.isNull() ? null : requested.longValue();
    }

    /**
     * 客户端请求的补全份数，默认为一。
     *
     * <p>OpenAI 的 {@code n} 是一种扇出：供应商被要求生成 {@code n} 份补全，每一份都以请求中 按份计的 {@code max_tokens}
     * 为界，并且每一份都会被计费。而提示词只发送一次、只计费一次。 因此预留覆盖的是「按份输出上限 × 这个份数」，{@link #estimates(JsonNode, int, int)}
     * 只把它施加在输出的那一半上。
     *
     * <p>在这里读取它，同时也在 {@code ProviderAdapter} 中校验它，是为了让估算的每个调用方 得到相同的答案：任何超出 {@code
     * 1..ProviderAdapter.MAX_COMPLETIONS} 的值都会按请求 校验的 {@code invalid_n} 被拒绝，而不是悄悄缩小预留。
     */
    int requestedCompletions(JsonNode body) {
        JsonNode requested = body.get("n");
        if (requested == null || requested.isNull()) {
            return 1;
        }
        if (!requested.isIntegralNumber()
                || !requested.canConvertToInt()
                || requested.intValue() < 1
                || requested.intValue() > ProviderAdapter.MAX_COMPLETIONS) {
            throw new IllegalArgumentException("invalid_n");
        }
        return requested.intValue();
    }

    /**
     * 把请求的输出上限改写成它当初被准入时所依据的那个生效上限，并保留客户端原本使用的字段。
     *
     * <p>这正是让预留成为一个界限、而不是一个猜测的原因：供应商被要求生成不超过钱包与套餐 正在为其持有资金的数字，因此用量唯一可能超出预留的途径，就是提示词一侧的分词器漂移。
     * 流路径会通过在上限处中止上游流，第二次强制同一个数字，因为否则一个忽略 {@code max_tokens} 的供应商就能生成超出预留的内容。
     */
    JsonNode boundOutputLimit(JsonNode body, long effectiveMaxOutputTokens) {
        ObjectNode normalized = ((ObjectNode) body).deepCopy();
        String field = body.has("max_completion_tokens") ? "max_completion_tokens" : "max_tokens";
        normalized.remove(List.of("max_tokens", "max_completion_tokens"));
        normalized.put(field, effectiveMaxOutputTokens);
        return normalized;
    }

    /**
     * V51 之前的输出规则，保留给那些直接把模型上限交给估算器的调用方：当客户端没有指定任何 上限时，网关请求 {@code
     * ReservationCeiling.DEFAULT_MAX_OUTPUT_TOKENS}，并以该模型上限为界。
     */
    JsonNode normalizeOutputLimit(JsonNode body, int maxOutputTokens) {
        Long requested = requestedOutputLimit(body);
        long defaultOutput = Math.min(ReservationCeiling.DEFAULT_MAX_OUTPUT_TOKENS, maxOutputTokens);
        long bounded = requested == null ? defaultOutput : Math.min(requested, maxOutputTokens);
        return boundOutputLimit(body, bounded);
    }

    Estimates estimates(JsonNode body, int maxOutputTokens) {
        return estimates(body, maxOutputTokens, 1);
    }

    /**
     * 一次请求 {@code completions} 份补全的请求的预留输入。
     *
     * <p>{@code maxOutputTokens} 是<em>按份</em>的界限；{@code completions} 是客户端的 {@code n}。返回的 {@code
     * inputEstimate} 是提示词，只计一次，因为供应商只发送并计费一次； {@code reservationEstimate} 则是该提示词加上 {@code
     * completions} 乘以按份输出上限， 因为供应商对每一份补全都计费。当 {@code completions == 1} 时，这恰好就是 {@code n} 出现之前的那个数字。
     */
    Estimates estimates(JsonNode body, int maxOutputTokens, int completions) {
        JsonNode requested =
                body.has("max_completion_tokens")
                        ? body.get("max_completion_tokens")
                        : body.get("max_tokens");
        Long requestedOutput =
                requested == null || requested.isNull() ? null : requested.longValue();
        MediaEstimateInput media = mediaEstimateInput(body.path("messages"));
        long perCompletionOutput =
                requestedOutput == null
                        ? Math.min(ReservationCeiling.DEFAULT_MAX_OUTPUT_TOKENS, maxOutputTokens)
                        : Math.min(requestedOutput, maxOutputTokens);
        long inputEstimate =
                Math.subtractExact(
                        tokenEstimator.estimate(
                                new TokenEstimator.EstimateCommand(
                                        media.text(),
                                        tokenEstimator.estimateImageTokens(media.imageCount()),
                                        0,
                                        requestedOutput,
                                        Math.min(
                                                ReservationCeiling.DEFAULT_MAX_OUTPUT_TOKENS,
                                                maxOutputTokens),
                                        maxOutputTokens)),
                        perCompletionOutput);
        long totalOutput = ReservationCeiling.totalOutputTokens(perCompletionOutput, completions);
        return new Estimates(inputEstimate, Math.addExact(inputEstimate, totalOutput));
    }

    ProviderAdapter.TokenUsage estimatedUsage(long estimate) {
        return new ProviderAdapter.TokenUsage(estimate, 0, estimate);
    }

    ProviderAdapter.TokenUsage estimatedCompletionUsage(
            long inputEstimate, long reservationEstimate, JsonNode response) {
        long boundedInput = Math.min(inputEstimate, reservationEstimate);
        long outputCapacity = Math.max(0, reservationEstimate - boundedInput);
        long codePoints = 0;
        for (JsonNode choice : response.path("choices")) {
            codePoints = accumulateTextEvidence(choice.path("message"), codePoints, outputCapacity);
            if (tokenEstimator.estimateTextTokens(codePoints) >= outputCapacity) {
                break;
            }
        }
        long output = Math.min(tokenEstimator.estimateTextTokens(codePoints), outputCapacity);
        return new ProviderAdapter.TokenUsage(boundedInput, output, boundedInput + output);
    }

    StreamUsageEstimate streamUsageEstimate(long inputEstimate, long reservationEstimate) {
        return new StreamUsageEstimate(inputEstimate, reservationEstimate);
    }

    private MediaEstimateInput mediaEstimateInput(JsonNode messages) {
        long imageCount = 0;
        for (JsonNode message : messages) {
            for (JsonNode part : message.path("content")) {
                String type = part.path("type").asText();
                if ("image_url".equals(type) || "input_image".equals(type)) {
                    imageCount = Math.incrementExact(imageCount);
                }
            }
        }
        if (imageCount == 0) {
            return new MediaEstimateInput(messages.toString(), 0);
        }
        ArrayNode sanitized = messages.deepCopy();
        sanitized.forEach(
                message -> {
                    JsonNode content = message.path("content");
                    if (!content.isArray()) {
                        return;
                    }
                    ArrayNode parts = (ArrayNode) content;
                    for (int index = 0; index < parts.size(); index++) {
                        String type = parts.get(index).path("type").asText();
                        if ("image_url".equals(type) || "input_image".equals(type)) {
                            parts.set(index, objectMapper.createObjectNode().put("type", type));
                        }
                    }
                });
        return new MediaEstimateInput(sanitized.toString(), imageCount);
    }

    private long accumulateTextEvidence(JsonNode value, long current, long tokenCapacity) {
        if (value == null
                || value.isMissingNode()
                || value.isNull()
                || tokenEstimator.estimateTextTokens(current) >= tokenCapacity) {
            return current;
        }
        if (value.isTextual()) {
            int codePoints = value.textValue().codePointCount(0, value.textValue().length());
            return Long.MAX_VALUE - current < codePoints ? Long.MAX_VALUE : current + codePoints;
        }
        if (value.isArray()) {
            for (JsonNode child : value) {
                current = accumulateTextEvidence(child, current, tokenCapacity);
                if (tokenEstimator.estimateTextTokens(current) >= tokenCapacity) {
                    break;
                }
            }
            return current;
        }
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!isStructuralOutputField(field.getKey())) {
                    current = accumulateTextEvidence(field.getValue(), current, tokenCapacity);
                }
                if (tokenEstimator.estimateTextTokens(current) >= tokenCapacity) {
                    break;
                }
            }
        }
        return current;
    }

    private boolean isStructuralOutputField(String field) {
        return "role".equals(field)
                || "id".equals(field)
                || "type".equals(field)
                || "index".equals(field)
                || "finish_reason".equals(field);
    }

    record Estimates(long inputEstimate, long reservationEstimate) {}

    private record MediaEstimateInput(String text, long imageCount) {}

    final class StreamUsageEstimate {
        private final long boundedInput;
        private final long outputCapacity;
        private long receivedCodePoints;
        private boolean saturated;

        private StreamUsageEstimate(long inputEstimate, long reservationEstimate) {
            this.boundedInput = Math.min(inputEstimate, reservationEstimate);
            this.outputCapacity = Math.max(0, reservationEstimate - boundedInput);
        }

        void reset() {
            receivedCodePoints = 0;
            saturated = false;
        }

        /**
         * 当已计数的输出达到预留所持有的输出容量时返回 true，即当流已经产出了所支付的全部内容时。 容量为零时也为
         * true，而这是正确的答案：一个在没有任何输出额度的情况下被准入的请求， 绝不允许生成任何内容。
         */
        boolean outputCapReached() {
            return saturated
                    || outputCapacity <= 0
                    || tokenEstimator.estimateTextTokens(receivedCodePoints) >= outputCapacity;
        }

        /**
         * 是否**因为撞上输出上限而少写了内容**——与 {@link #outputCapReached()} 刻意分开。
         *
         * <p>两者在 "容量为零" 时结论相反，而这个差别正是对外该说什么话的分界：
         *
         * <ul>
         *   <li>{@code saturated == true}：确实产出了内容，且是在写满预留容量的那一刻被切断的 ——这是一次**截断**，客户端必须被告知（{@code
         *       finish_reason=length}）。
         *   <li>{@code outputCapacity <= 0} 且未饱和：这次请求从来没有输出额度，一个字都没写。 没有"被截断的回答"这回事，把它说成 {@code
         *       length} 反而是在描述一个不存在的回答。
         * </ul>
         *
         * <p>用 {@code outputCapReached()} 代替本方法去发截断信号，就会把第二种情况也标成 {@code
         * length}——那是在为一个零额度请求编造"回答写了一半"的假象。
         */
        boolean truncatedByOutputCap() {
            return saturated;
        }

        void received(JsonNode chunk) {
            chunk.path("choices")
                    .forEach(
                            choice -> {
                                if (!saturated) {
                                    receivedCodePoints =
                                            accumulateTextEvidence(
                                                    choice.path("delta"),
                                                    receivedCodePoints,
                                                    outputCapacity);
                                    saturated =
                                            tokenEstimator.estimateTextTokens(receivedCodePoints)
                                                    >= outputCapacity;
                                }
                            });
        }

        ProviderAdapter.TokenUsage usage() {
            long output =
                    saturated
                            ? outputCapacity
                            : Math.min(
                                    tokenEstimator.estimateTextTokens(receivedCodePoints),
                                    outputCapacity);
            return new ProviderAdapter.TokenUsage(boundedInput, output, boundedInput + output);
        }
    }
}
