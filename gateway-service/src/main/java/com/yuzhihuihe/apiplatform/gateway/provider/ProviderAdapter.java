package com.yuzhihuihe.apiplatform.gateway.provider;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProviderAdapter {

    /**
     * 单个请求可以要求的最大 {@code n}（completions 的个数）。
     *
     * <p>{@code n} 会成倍放大最坏情况预留中的输出那一半，而预留正是钱包与套餐在调用供应商
     * <em>之前</em>就持有的额度，因此不受限制的 {@code n} 就是不受限制的占用<em>以及</em>
     * 不受限制的供应商支出：客户端可以要求一百万个 completions，网关却只预留一个的费用，
     * 而供应商会为全部这些 completions 计费。因此该字段必须在网关侧设有上限，而不能交由
     * 上游厂商碰巧接受什么来决定。
     *
     * <p>该上限被刻意设得远低于厂商侧的限额，因为提高它在每个方向上的代价都是线性的
     * （预留的 token、供应商支出、响应大小），而收益却是恒定的：{@code n} 的存在是为了服务
     * best-of-N 采样和多选客户端，它们只会用到很小的值。8 足以覆盖这些场景，并且在文档所述的
     * 系统输出上限下，它把一个请求最坏情况的输出占用限制在 {@code 8 * 65536} = 524 288 个
     * 输出 token（在 1.25 漂移余量之前）——对平台售卖的任何套餐方案都足够大，又足够小，
     * 使得一个荒谬的 {@code n} 会像其他任何格式错误的字段一样，以同一个 400
     * {@code invalid_n} 应答被拒绝，而不是悄悄少预留。提高它就意味着要重新检查最坏情况下的
     * 占用是否仍然适配平台所售卖的套餐和余额。
     *
     * <p>{@code ChatCompletionUsageEstimator.requestedCompletions} 是同一条规则的读取侧，
     * 会拒绝 {@code 1..this bound} 之外的任何值；请保持两者步调一致。
     */
    int MAX_COMPLETIONS = 8;

    Set<String> FORWARDED_FIELDS = Set.of(
            "messages", "max_tokens", "max_completion_tokens", "temperature", "top_p", "stop", "n",
            "tools", "tool_choice", "response_format", "seed", "frequency_penalty", "presence_penalty",
            "logprobs", "top_logprobs", "stream_options");

    String providerType();

    Set<ChannelRouter.Capability> capabilities();

    Mono<ProviderResponse> complete(RoutedRequest request);

    Flux<ProviderChunk> stream(RoutedRequest request);

    Mono<ProviderFileRef> bindFile(FileObject file, Channel channel);

    default boolean supports(ChannelRouter.Capability capability) {
        return capabilities().contains(capability);
    }

    static ObjectNode sanitize(JsonNode source, String upstreamModel, boolean stream) {
        // 这两种情况混在一个条件里，但**责任方完全不同**，因此分开抛：
        //   * body 不是对象 → 客户端的错（换一个请求就好），不该熔断渠道；
        //   * upstreamModel 为空 → **渠道的错**（这个别名在该渠道上没有配厂商模型名），
        //     重试多少次都一样，必须算熔断失败。
        // 以前两者都抛 invalid_provider_request / IllegalArgumentException，
        // 结果是渠道配置错误被当成客户端错误放过，那个渠道永远熔不断。
        if (source == null || !source.isObject()) {
            throw new IllegalArgumentException("invalid_provider_request");
        }
        if (upstreamModel == null || upstreamModel.isBlank()) {
            throw new ChannelFaultException("channel_upstream_model_missing");
        }
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode target = mapper.createObjectNode();
        target.put("model", upstreamModel);
        target.put("stream", stream);
        for (String field : FORWARDED_FIELDS) {
            JsonNode value = source.get(field);
            if (value != null) {
                JsonNode sanitized = sanitizeField(field, value, mapper);
                if (sanitized != null) {
                    target.set(field, sanitized);
                }
            }
        }
        JsonNode topP = target.get("top_p");
        if (topP != null && (!topP.isNumber() || topP.asDouble() <= 0 || topP.asDouble() > 1)) {
            throw new IllegalArgumentException("invalid_top_p");
        }
        validateSupportedOptions(source);
        return target;
    }

    private static void validateSupportedOptions(JsonNode source) {
        validateBoolean(source, "stream");
        validateBoolean(source, "logprobs");
        validatePositiveInteger(source, "max_tokens");
        validatePositiveInteger(source, "max_completion_tokens");
        // n 不只是「一个正整数」：它会成倍放大预留，因此它受限于网关愿意为其占压资金的
        // 最大 completions 数。任何非整数、非正数或超过 MAX_COMPLETIONS 的值都是
        // invalid_n，这与 validatePositiveInteger 过去对非正数情况给出的答案相同。
        validateIntegerRange(source, "n", 1, MAX_COMPLETIONS);
        validateIntegerRange(source, "top_logprobs", 0, 20);
        validateInteger(source, "seed");
        validateNumberRange(source, "temperature", 0, 2);
        validateNumberRange(source, "frequency_penalty", -2, 2);
        validateNumberRange(source, "presence_penalty", -2, 2);
        JsonNode stop = source.get("stop");
        if (stop != null && !stop.isNull() && !stop.isTextual()
                && (!stop.isArray() || !allTextual(stop))) {
            throw new IllegalArgumentException("invalid_stop");
        }
        JsonNode streamOptions = source.get("stream_options");
        if (streamOptions != null && !streamOptions.isNull()) {
            JsonNode includeUsage = streamOptions.get("include_usage");
            if (includeUsage != null && !includeUsage.isNull() && !includeUsage.isBoolean()) {
                throw new IllegalArgumentException("invalid_stream_options");
            }
        }
    }

    private static boolean allTextual(JsonNode values) {
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                return false;
            }
        }
        return true;
    }

    private static void validateBoolean(JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull() && !value.isBoolean()) {
            throw new IllegalArgumentException("invalid_" + field);
        }
    }

    private static void validatePositiveInteger(JsonNode source, String field) {
        validateIntegerRange(source, field, 1, Long.MAX_VALUE);
    }

    private static void validateInteger(JsonNode source, String field) {
        validateIntegerRange(source, field, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    private static void validateIntegerRange(JsonNode source, String field, long minimum, long maximum) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull()
                && (!value.isIntegralNumber() || !value.canConvertToLong()
                        || value.longValue() < minimum || value.longValue() > maximum)) {
            throw new IllegalArgumentException("invalid_" + field);
        }
    }

    private static void validateNumberRange(JsonNode source, String field, double minimum, double maximum) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull()) {
            double number = value.asDouble(Double.NaN);
            if (!value.isNumber() || !Double.isFinite(number) || number < minimum || number > maximum) {
                throw new IllegalArgumentException("invalid_" + field);
            }
        }
    }

    private static JsonNode sanitizeField(String field, JsonNode value, ObjectMapper mapper) {
        return switch (field) {
            case "messages" -> sanitizeMessages(value, mapper);
            case "tools" -> sanitizeTools(value, mapper);
            case "tool_choice" -> sanitizeToolChoice(value, mapper);
            case "response_format" -> sanitizeResponseFormat(value, mapper);
            case "stream_options" -> sanitizeObject(value, mapper, "include_usage");
            default -> value.deepCopy();
        };
    }

    private static ArrayNode sanitizeMessages(JsonNode value, ObjectMapper mapper) {
        if (!value.isArray()) {
            throw new IllegalArgumentException("invalid_messages");
        }
        ArrayNode messages = mapper.createArrayNode();
        value.forEach(message -> {
            if (!message.isObject()) {
                throw new IllegalArgumentException("invalid_message");
            }
            if (!message.path("role").isTextual() || message.path("role").asText().isBlank()) {
                throw new IllegalArgumentException("invalid_message_role");
            }
            if (message.has("name") && !isValidFunctionName(message.path("name"))) {
                throw new IllegalArgumentException("invalid_message_name");
            }
            if (message.has("tool_call_id")
                    && (!message.path("tool_call_id").isTextual()
                            || message.path("tool_call_id").asText().isBlank())) {
                throw new IllegalArgumentException("invalid_tool_call_id");
            }
            ObjectNode clean = sanitizeObject(
                    message, mapper, "role", "name", "tool_call_id");
            JsonNode content = message.get("content");
            if (content != null) {
                clean.set("content", sanitizeContent(content, mapper));
            }
            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null) {
                clean.set("tool_calls", sanitizeToolCalls(toolCalls, mapper));
            }
            messages.add(clean);
        });
        return messages;
    }

    private static JsonNode sanitizeContent(JsonNode content, ObjectMapper mapper) {
        if (content.isTextual() || content.isNull()) {
            return content.deepCopy();
        }
        if (!content.isArray()) {
            throw new IllegalArgumentException("invalid_message_content");
        }
        ArrayNode parts = mapper.createArrayNode();
        content.forEach(part -> {
            if (!part.isObject()) {
                throw new IllegalArgumentException("invalid_message_content_part");
            }
            ObjectNode clean = sanitizeObject(
                    part, mapper, "type", "text", "file_id", "filename", "file_data", "url");
            JsonNode imageUrl = part.get("image_url");
            if (imageUrl != null) {
                clean.set("image_url", imageUrl.isObject()
                        ? sanitizeObject(imageUrl, mapper, "url", "detail")
                        : imageUrl.deepCopy());
            }
            parts.add(clean);
        });
        return parts;
    }

    private static ArrayNode sanitizeTools(JsonNode value, ObjectMapper mapper) {
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException("invalid_tools");
        }
        ArrayNode tools = mapper.createArrayNode();
        value.forEach(tool -> {
            if (!tool.isObject()) {
                throw new IllegalArgumentException("invalid_tool");
            }
            if (!"function".equals(tool.path("type").asText())) {
                throw new IllegalArgumentException("invalid_tool_type");
            }
            JsonNode function = tool.get("function");
            if (function == null || !function.isObject()
                    || !isValidFunctionName(function.path("name"))
                    || function.has("description") && !function.path("description").isTextual()
                    || function.has("parameters") && !function.path("parameters").isObject()
                    || function.has("strict") && !function.path("strict").isBoolean()) {
                throw new IllegalArgumentException("invalid_tool_function");
            }
            ObjectNode clean = sanitizeObject(tool, mapper, "type");
            clean.set("function", sanitizeObject(
                    function, mapper, "name", "description", "parameters", "strict"));
            tools.add(clean);
        });
        return tools;
    }

    private static ArrayNode sanitizeToolCalls(JsonNode value, ObjectMapper mapper) {
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException("invalid_tool_calls");
        }
        ArrayNode calls = mapper.createArrayNode();
        value.forEach(call -> {
            if (!call.isObject()
                    || !call.path("id").isTextual() || call.path("id").asText().isBlank()
                    || !"function".equals(call.path("type").asText())) {
                throw new IllegalArgumentException("invalid_tool_call");
            }
            JsonNode function = call.get("function");
            if (function == null || !function.isObject()
                    || !isValidFunctionName(function.path("name"))
                    || !function.path("arguments").isTextual()) {
                throw new IllegalArgumentException("invalid_tool_call_function");
            }
            ObjectNode clean = sanitizeObject(call, mapper, "id", "type");
            clean.set("function", sanitizeObject(function, mapper, "name", "arguments"));
            calls.add(clean);
        });
        return calls;
    }

    private static JsonNode sanitizeToolChoice(JsonNode value, ObjectMapper mapper) {
        if (value.isNull()) {
            return value.deepCopy();
        }
        if (value.isTextual()) {
            if (!Set.of("none", "auto", "required").contains(value.asText())) {
                throw new IllegalArgumentException("invalid_tool_choice");
            }
            return value.deepCopy();
        }
        if (!value.isObject() || !"function".equals(value.path("type").asText())) {
            throw new IllegalArgumentException("invalid_tool_choice");
        }
        JsonNode function = value.get("function");
        if (function == null || !function.isObject() || !isValidFunctionName(function.path("name"))) {
            throw new IllegalArgumentException("invalid_tool_choice");
        }
        ObjectNode clean = sanitizeObject(value, mapper, "type");
        clean.set("function", sanitizeObject(function, mapper, "name"));
        return clean;
    }

    private static JsonNode sanitizeResponseFormat(JsonNode value, ObjectMapper mapper) {
        if (!value.isObject()) {
            throw new IllegalArgumentException("invalid_response_format");
        }
        String type = value.path("type").isTextual() ? value.path("type").asText() : "";
        if (!Set.of("text", "json_object", "json_schema").contains(type)) {
            throw new IllegalArgumentException("invalid_response_format");
        }
        ObjectNode clean = sanitizeObject(value, mapper, "type");
        JsonNode schema = value.get("json_schema");
        if ("json_schema".equals(type)) {
            if (schema == null || !schema.isObject()
                    || !isValidFunctionName(schema.path("name"))
                    || schema.has("description") && !schema.path("description").isTextual()
                    || !schema.path("schema").isObject()
                    || schema.has("strict") && !schema.path("strict").isBoolean()) {
                throw new IllegalArgumentException("invalid_response_format");
            }
            clean.set("json_schema", sanitizeObject(
                    schema, mapper, "name", "description", "schema", "strict"));
        }
        return clean;
    }

    private static boolean isValidFunctionName(JsonNode name) {
        return name.isTextual() && name.asText().matches("[A-Za-z0-9_-]{1,64}");
    }

    private static ObjectNode sanitizeObject(
            JsonNode value, ObjectMapper mapper, String... allowedFields) {
        if (!value.isObject()) {
            throw new IllegalArgumentException("invalid_provider_object");
        }
        ObjectNode clean = mapper.createObjectNode();
        for (String field : allowedFields) {
            JsonNode child = value.get(field);
            if (child != null) {
                clean.set(field, child.deepCopy());
            }
        }
        return clean;
    }

    record Channel(
            UUID id,
            UUID providerId,
            String providerType,
            String baseUrl,
            String apiKey,
            int timeoutMs,
            String upstreamModel) {
        public Channel {
            if (id == null || providerId == null || providerType == null || providerType.isBlank()
                    || baseUrl == null || baseUrl.isBlank() || timeoutMs <= 0
                    || upstreamModel == null || upstreamModel.isBlank()) {
                throw new ChannelFaultException("invalid_provider_channel");
            }
        }
    }

    record RoutedRequest(String requestId, String modelAlias, JsonNode body, Channel channel) {
        public RoutedRequest {
            if (requestId == null || requestId.isBlank() || modelAlias == null || modelAlias.isBlank()
                    || body == null || channel == null) {
                throw new IllegalArgumentException("invalid_routed_request");
            }
        }
    }

    record ProviderResponse(JsonNode body, TokenUsage usage) { }

    record ProviderChunk(JsonNode data, TokenUsage usage) { }

    enum UsageSource {
        PROVIDER_ACTUAL,
        LOCAL_ESTIMATED,
        BILL_RECONCILED,
        MANUAL_CORRECTED
    }

    record TokenUsage(
            long inputTokens,
            long outputTokens,
            long totalTokens,
            Long cacheHitTokens,
            Long cacheMissTokens,
            Long cacheWriteTokens,
            Long reasoningTokens,
            Long textTokens,
            Long imageTokens,
            Long audioTokens,
            UsageSource source,
            String providerRequestId,
            JsonNode rawUsage) {

        public TokenUsage(long inputTokens, long outputTokens, long totalTokens) {
            this(inputTokens, outputTokens, totalTokens,
                    null, null, null, null, null, null, null,
                    UsageSource.LOCAL_ESTIMATED, null, null);
        }

        public TokenUsage {
            if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
                throw new IllegalArgumentException("invalid_provider_usage");
            }
            long expectedTotal;
            try {
                expectedTotal = Math.addExact(inputTokens, outputTokens);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("invalid_provider_usage");
            }
            if (totalTokens != expectedTotal) {
                throw new IllegalArgumentException("invalid_provider_usage");
            }
            validateDetail(cacheHitTokens, inputTokens);
            validateDetail(cacheMissTokens, inputTokens);
            validateDetail(cacheWriteTokens, inputTokens);
            validateDetail(reasoningTokens, outputTokens);
            validateDetail(textTokens, inputTokens);
            validateDetail(imageTokens, inputTokens);
            validateDetail(audioTokens, inputTokens);
            if (number(cacheHitTokens) > inputTokens - number(cacheWriteTokens)) {
                throw new IllegalArgumentException("invalid_provider_usage");
            }
            if (source == null) {
                throw new IllegalArgumentException("invalid_provider_usage");
            }
            providerRequestId = providerRequestId == null || providerRequestId.isBlank()
                    ? null : providerRequestId.strip();
            rawUsage = rawUsage == null ? null : rawUsage.deepCopy();
        }

        @Override
        public JsonNode rawUsage() {
            return rawUsage == null ? null : rawUsage.deepCopy();
        }

        private static void validateDetail(Long value, long maximum) {
            if (value != null && (value < 0 || value > maximum)) {
                throw new IllegalArgumentException("invalid_provider_usage");
            }
        }

        private static long number(Long value) {
            return value == null ? 0 : value;
        }
    }

    record FileObject(UUID id, String objectKey, String mediaType, long sizeBytes, byte[] content) {
        public FileObject {
            if (id == null || objectKey == null || objectKey.isBlank()
                    || mediaType == null || mediaType.isBlank() || sizeBytes < 0
                    || content != null && content.length != sizeBytes) {
                throw new IllegalArgumentException("invalid_file_object");
            }
            content = content == null ? null : content.clone();
        }

        public FileObject(UUID id, String objectKey, String mediaType, long sizeBytes) {
            this(id, objectKey, mediaType, sizeBytes, null);
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    record ProviderFileRef(String upstreamFileId) { }

    final class ProviderException extends RuntimeException {
        private final int status;
        private final String reason;
        private final String upstreamCode;
        private final String upstreamMessage;
        private final boolean contentPolicyViolation;

        public ProviderException(int status, String message) {
            this(status, message, null, null, false);
        }

        /**
         * @param upstreamCode 上游错误信封里的 {@code code}；没有结构化信封时为 null
         * @param upstreamMessage 上游错误信封里的 {@code message}；没有结构化信封时为 null
         */
        public ProviderException(
                int status, String message, String upstreamCode, String upstreamMessage) {
            this(status, message, upstreamCode, upstreamMessage, false);
        }

        /**
         * @param contentPolicyViolation 上游对**生成内容**做内容安全审核后拒绝
         *        （例如版权角色触发 {@code PolicyViolation}）。这类失败的正确客户端动作是
         *        修改提示词，而不是重试或排查渠道——因此它必须能被区分出来，
         *        而不是和「上游故障」一起压成一个 502。
         */
        public ProviderException(
                int status, String message, String upstreamCode, String upstreamMessage,
                boolean contentPolicyViolation) {
            super(message == null || message.isBlank() ? "provider_request_failed" : message);
            this.status = status;
            this.reason = reason(message);
            this.upstreamCode = blankToNull(upstreamCode);
            this.upstreamMessage = blankToNull(upstreamMessage);
            this.contentPolicyViolation = contentPolicyViolation;
        }

        public int status() {
            return status;
        }

        /**
         * 这次失败的**受控原因码**，与状态码分开保存。
         *
         * <p><b>为什么不能只留 message。</b>用量日志的 {@code error_summary} 与
         * {@code usage_stage.error_category} 是日后唯一的定责证据，而过去它们只写
         * {@code "ProviderException(status=502)"}：11 次生产失败因此全部长得一模一样，
         * 无法区分「上游真的返回了非 2xx」「上游流少了 [DONE]」「响应块不是合法 JSON」
         * 「用量字段自相矛盾」——这四种原因的处置方（供应商 / 网关适配 / 中台解析）
         * 完全不同。把原因码单独存成字段，是为了让它**不依赖 message 的措辞**而存活：
         * {@link #providerFailure} 这类构造点会把上游原文拼进 message 并截断，
         * 从 message 里反推原因码是不可靠的。
         *
         * <p><b>它必须是网关自己写下的标识符，而不是任何上游文本。</b>取值的唯一来源是
         * message 里 {@code ": "} 之前的那一段（{@link #providerFailure} 的形状是
         * {@code provider_http_<status>[: <上游 code>][: <上游 message>]}），并且**只有当它
         * 形如标识符时才被接受**：以 {@code [a-z]} 开头、只含 {@code [a-z0-9_]}；其余一律
         * 为 null。
         *
         * <p>这条白名单不是洁癖。{@code new ProviderException(429, "provider secret")} 这样
         * 的自由文本若被当成原因码写进用量表，就等于把任意上游或凭据文本持久化进日志——
         * 正是本类注释反复要求避免的那件事。无法确认安全的文本宁可不留：调用方拿到 null
         * 只会退回原来的「类名 + 状态码」，不会因此丢掉可诊断性。
         */
        public String reason() {
            return reason;
        }

        private static String reason(String message) {
            if (message == null) {
                return null;
            }
            String head = message.strip();
            int detail = head.indexOf(": ");
            String code = detail > 0 ? head.substring(0, detail) : head;
            return code.matches("[a-z][a-z0-9_]*") ? code : null;
        }

        /**
         * 上游是否以「内容安全政策」拒绝了这次生成。
         *
         * <p>只有确定上游拒绝的是**内容本身**时才为 true；渠道配置错误、限流、
         * 模型不存在等其它 4xx 一律为 false。为 true 时，重试同一个提示词毫无意义，
         * 客户端应提示用户修改描述。
         */
        public boolean contentPolicyViolation() {
            return contentPolicyViolation;
        }

        /**
         * 上游自己的错误码，例如 {@code model_not_found}。
         *
         * <p>它是「上游究竟在说什么」的结构化形态：控制台据此区分「模型名不对」与
         * 「地址不对」，而这两件事在 404 上是同一个状态码。上游没有给结构化信封时为 null。
         */
        public String upstreamCode() {
            return upstreamCode;
        }

        /**
         * 上游自己的错误说明，控制台的「上游原文」显示它。
         *
         * <p>为 null 表示这次应答没有可保留的结构化信封（HTML 错误页、纯文本、空体）——
         * 那时刻意什么都不带，而不是把上游响应体原样搬进来。
         */
        public String upstreamMessage() {
            return upstreamMessage;
        }

        /**
         * 把传输层的失败翻译成一次**上游**故障。
         *
         * <p>没有这一步，一次读超时或连接失败会以「网关自己的 500」交回给客户：
         * 客户端据此会去查自己的请求，而它真正该做的是稍后重试或换一条渠道。
         *
         * <p>这一步只做**分类**，不改变「这次生成到底成没成」的判定：504 与 502
         * 都不是明确拒绝，因此预留照旧被保持、任务照旧留在非终态，等恢复扫描去
         * 收敛一个结果未知的调用。把超时说成「已拒绝」才是真正危险的——
         * 那会让重试针对同一套餐再买第二次生成。
         */
        public static ProviderException ofTransport(Throwable error) {
            return timedOut(error)
                    ? new ProviderException(504, "provider_attempt_timeout")
                    : new ProviderException(502, "provider_unreachable");
        }

        /**
         * 是否值得翻译成上游故障。
         *
         * <p>刻意收窄到传输层与超时：适配器自己抛出的协议失败
         * （{@code provider_invalid_response} 之类）已经在各自的调用点分好类，
         * 而一个 {@code IllegalArgumentException} 是网关自己的编程错误——
         * 把它说成「上游故障」等于替自己的 bug 找借口。
         */
        public static boolean isTransportFailure(Throwable error) {
            return timedOut(error)
                    || error instanceof WebClientRequestException
                    || error instanceof IOException;
        }

        private static boolean timedOut(Throwable error) {
            // Reactor 的 timeout 抛 java.util.concurrent.TimeoutException；
            // Reactor Netty 的读超时抛 Netty 的同名异常（ReadTimeoutException 是它的子类）。
            return error instanceof TimeoutException
                    || error instanceof io.netty.handler.timeout.TimeoutException
                    || error instanceof SocketTimeoutException;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }
}

abstract class OpenAiCompatibleProviderAdapter implements ProviderAdapter {

    /**
     * 从上游错误信封里保留的字符数上限。
     *
     * <p>它是给人看的诊断，不是日志：控制台只显示前 300 个字符，一个上游把整篇
     * HTML 塞进 {@code message} 的应答不该把管理端页面撑爆。
     */
    private static final int MAX_UPSTREAM_DETAIL = 300;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final ChatStreamTimeoutPolicy timeouts;

    OpenAiCompatibleProviderAdapter(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this(webClientBuilder, objectMapper, ChatStreamTimeoutPolicy.defaults());
    }

    OpenAiCompatibleProviderAdapter(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            ChatStreamTimeoutPolicy timeouts) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.timeouts = timeouts == null ? ChatStreamTimeoutPolicy.defaults() : timeouts;
    }

    @Override
    public Mono<ProviderResponse> complete(RoutedRequest request) {
        requireSupported(request);
        return request(request, false)
                .exchangeToMono(response -> response.statusCode().isError()
                        ? providerError(response)
                        : response.bodyToMono(JsonNode.class)
                                .map(body -> new ProviderResponse(body, usage(body))))
                .timeout(Duration.ofMillis(request.channel().timeoutMs()))
                // 超时与连接失败属于**上游**故障，而不是网关自己的 500。
                .onErrorMap(ProviderException::isTransportFailure,
                        ProviderException::ofTransport);
    }

    @Override
    public Flux<ProviderChunk> stream(RoutedRequest request) {
        requireSupported(request);
        Flux<ProviderChunk> upstream = request(request, true)
                .exchangeToFlux(response -> response.statusCode().isError()
                        ? this.<ProviderChunk>providerError(response).flux()
                        : readStream(response.bodyToFlux(String.class)));
        // 两个窗口分开计时：首个 token 用 first-token 预算，之后每个 chunk 之间用 idle 预算。
        // 合成一个值（此前就是）会让「思考久一点」和「卡死」共用同一个数字，二选一必然错一头。
        return timeouts.apply(upstream, Duration.ofMillis(request.channel().timeoutMs()))
                // 超时与连接失败属于**上游**故障，而不是网关自己的 500。
                .onErrorMap(ProviderException::isTransportFailure,
                        ProviderException::ofTransport);
    }

    /**
     * SSE 里除了 {@code data:} 之外的**协议字段**：注释（以 {@code :} 开头）与
     * {@code event:} / {@code id:} / {@code retry:}。
     *
     * <p>这些行按 SSE 规范是必须被忽略的，它们不是载荷。此前它们会原样落到 JSON 解析器上，
     * 于是上游发一次心跳（很多网关用 {@code : keep-alive} 或 {@code event: ping} 维持长连接）
     * 就会让整条流以 {@code invalid_provider_stream_chunk} 失败。对一条**已经在产出内容**
     * 的流来说，这是纯粹的误伤：客户端看到回答写了一半就断了，而原因与内容毫无关系。
     *
     * <p>这个筛子刻意很窄，只认协议字段本身：它不是「放宽为只要 EOF 就成功」。
     * {@code [DONE]} 仍然必需（缺失照样 {@code provider_stream_missing_done}），
     * 一个 {@code data:} 行里的坏 JSON 仍然照样失败。把注释当成空行丢掉，
     * 与把任意无法解析的内容当成成功，是两件不同的事。
     */
    private static boolean isSseFramingLine(String value) {
        return value.startsWith(":") || value.startsWith("event:")
                || value.startsWith("id:") || value.startsWith("retry:");
    }

    private Flux<ProviderChunk> readStream(Flux<String> events) {
        return Flux.defer(() -> {
            AtomicBoolean done = new AtomicBoolean();
            return events.map(String::strip)
                    .filter(value -> !isSseFramingLine(value))
                    .map(value -> value.startsWith("data:") ? value.substring(5).strip() : value)
                    .filter(value -> !value.isBlank())
                    .takeUntil(value -> {
                        boolean terminal = "[DONE]".equals(value);
                        if (terminal) {
                            done.set(true);
                        }
                        return terminal;
                    })
                    .filter(value -> !"[DONE]".equals(value))
                    .map(this::readChunk)
                    .concatWith(Flux.defer(() -> done.get()
                            ? Flux.empty()
                            : Flux.error(new ProviderException(502, "provider_stream_missing_done"))));
        });
    }

    @Override
    public Mono<ProviderFileRef> bindFile(FileObject file, Channel channel) {
        return Mono.defer(() -> {
            if (!capabilities().contains(ChannelRouter.Capability.FILE)) {
                return Mono.error(new IllegalArgumentException("provider_capability_not_supported"));
            }
            if (file == null || channel == null || file.content() == null) {
                return Mono.error(new IllegalArgumentException("file_content_required"));
            }
            byte[] content = file.content();
            MultipartBodyBuilder multipart = new MultipartBodyBuilder();
            multipart.part("purpose", "file-extract");
            multipart.part("file", new ByteArrayResource(content) {
                        @Override
                        public String getFilename() {
                            return file.id().toString();
                        }
                    })
                    .contentType(MediaType.parseMediaType(file.mediaType()));
            String baseUrl = channel.baseUrl().replaceAll("/+$", "");
            WebClient.RequestBodySpec request = webClientBuilder.build().post()
                    .uri(baseUrl + "/files")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", file.id() + "-" + channel.id());
            if (channel.apiKey() != null && !channel.apiKey().isBlank()) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + channel.apiKey());
            }
            return request.body(BodyInserters.fromMultipartData(multipart.build()))
                    .exchangeToMono(response -> response.statusCode().isError()
                            ? providerError(response)
                            : response.bodyToMono(JsonNode.class).flatMap(body -> {
                                String upstreamFileId = body.path("id").asText();
                                return upstreamFileId.isBlank()
                                        ? Mono.error(new ProviderException(
                                                502, "invalid_provider_file_response"))
                                        : Mono.just(new ProviderFileRef(upstreamFileId));
                            }))
                    .timeout(Duration.ofMillis(channel.timeoutMs()))
                    // 超时与连接失败属于**上游**故障，而不是网关自己的 500。
                    .onErrorMap(ProviderException::isTransportFailure,
                            ProviderException::ofTransport);
        });
    }

    protected WebClient.RequestHeadersSpec<?> request(RoutedRequest request, boolean stream) {
        String baseUrl = request.channel().baseUrl().replaceAll("/+$", "");
        WebClient.RequestBodySpec spec = webClientBuilder.build().post()
                .uri(baseUrl + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(stream ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON);
        if (request.channel().apiKey() != null && !request.channel().apiKey().isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + request.channel().apiKey());
        }
        addProviderHeaders(spec, stream);
        ObjectNode sanitized = ProviderAdapter.sanitize(
                request.body(), request.channel().upstreamModel(), stream);
        ObjectNode providerBody = ProviderAdapter.sanitize(
                prepareProviderBody(sanitized, request.channel().upstreamModel()),
                request.channel().upstreamModel(), stream);
        if (stream) {
            providerBody.withObject("stream_options").put("include_usage", true);
        }
        return spec.bodyValue(providerBody);
    }

    protected JsonNode prepareProviderBody(JsonNode body, String upstreamModel) {
        return body;
    }

    protected void addProviderHeaders(WebClient.RequestBodySpec spec, boolean stream) {
    }

    private ProviderChunk readChunk(String value) {
        try {
            JsonNode data = objectMapper.readTree(value);
            return new ProviderChunk(data, usage(data));
        } catch (Exception exception) {
            throw new ProviderException(502, "invalid_provider_stream_chunk");
        }
    }

    private TokenUsage usage(JsonNode body) {
        return ProviderUsageParser.parse(providerType(), body);
    }

    private <T> Mono<T> providerError(ClientResponse response) {
        int status = response.statusCode().value();
        return response
                .bodyToMono(String.class)
                .defaultIfEmpty("")
                .onErrorResume(error -> Mono.just(""))
                .flatMap(body -> Mono.error(providerFailure(status, body)));
    }

    /**
     * 非 2xx 的上游应答 → 异常。
     *
     * <p><b>先读响应体，但只保留结构化的错误信封。</b>上游原文是运维唯一能用来区分
     * 「模型名不对」与「地址少了版本段」的证据，而旧实现调用 {@code releaseBody()}
     * 直接把它丢掉，控制台于是只剩 {@code provider_http_404}——一次打错地址的探测
     * 被读成「模型不存在」，而模型名本来是对的。
     *
     * <p>反向的错误同样存在：把整段响应体搬进网关错误里。{@code
     * upstreamErrorBodiesAreNotRetainedInGatewayErrors} 钉住了那一点（上游可能回
     * HTML 错误页、纯文本，甚至与本次调用无关的内容）。因此这里的规则是精确的：
     * 整段能被解析成 JSON 错误信封时，取出它的 {@code code} 与 {@code message}；
     * 其余一律不带。
     *
     * <p>{@code provider_http_<status>} 这个前缀必须保留：管理端从异常消息里抽
     * 上游状态码（{@link com.yuzhihuihe.apiplatform.gateway.routing.ChannelModelTestService}）。
     */
    private ProviderException providerFailure(int status, String rawBody) {
        UpstreamError upstream = upstreamError(rawBody);
        String code = upstream == null ? null : upstream.code();
        String message = upstream == null ? null : upstream.message();
        StringBuilder summary = new StringBuilder("provider_http_").append(status);
        if (code != null) {
            summary.append(": ").append(code);
        }
        if (message != null && !message.equals(code)) {
            summary.append(": ").append(message);
        }
        return new ProviderException(status, truncate(summary.toString()), code, message);
    }

    /** 上游错误信封里被保留的两个字段。 */
    private record UpstreamError(String code, String message) {}

    /**
     * 从上游应答里取出错误信封：{@code {"error":{"code":..,"message":..}}}、
     * {@code {"code":..,"message":..}} 与 {@code {"error":"文本"}} 三种形状。
     *
     * <p>只有整段是一个 JSON 对象、并且真的带有 {@code code} 或 {@code message} 时才认它；
     * 其余形状返回 null（= 什么都不保留）。
     */
    private UpstreamError upstreamError(String rawBody) {
        String body = rawBody == null ? "" : rawBody.strip();
        // 便宜的早退：不以 JSON 对象开头的应答（HTML 错误页、纯文本）连解析都不必。
        if (body.isEmpty() || body.charAt(0) != '{') {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception notJson) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode error = root.get("error");
        JsonNode source = error != null && error.isObject() ? error : root;
        String code = firstText(source.get("code"), source.get("type"));
        String message = firstText(
                source.get("message"), error != null && error.isTextual() ? error : null);
        if (code == null && message == null) {
            return null;
        }
        return new UpstreamError(truncate(code), truncate(message));
    }

    private static String firstText(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isTextual() && !candidate.asText().isBlank()) {
                return candidate.asText().strip();
            }
        }
        return null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= MAX_UPSTREAM_DETAIL
                ? stripped
                : stripped.substring(0, MAX_UPSTREAM_DETAIL);
    }

    private void requireSupported(RoutedRequest request) {
        Set<ChannelRouter.Capability> required = requiredCapabilities(request.body());
        if (!capabilities().containsAll(required)) {
            // **必须是 ChannelFaultException，不能是普通 IllegalArgumentException。**
            //
            // 判据见 ChannelFaultException 的文档（它把 provider_capability_not_supported
            // 列为典型渠道故障）：一个渠道的能力集比它绑定的适配器窄（例如目录把它标成
            // 支持 IMAGE，适配器只声明 TEXT），那么**重试同一个请求、或换成任何合法请求，
            // 只要还走这个渠道就一定会再失败**——这正是"渠道坏了"的定义。
            //
            // 抛普通 IllegalArgumentException 的后果很具体：熔断器的分类
            // （ChatCompletionProviderExecutor.isCircuitFailure：不是
            // IllegalArgumentException 才算失败）会把它归到"不该熔断"，于是这个渠道
            // **永远熔不断**、每次请求都白跑一趟，而路由仍然一直选中它。改前这里就是
            // 普通 IllegalArgumentException，本类型自己的文档却已经把它算作渠道故障。
            throw new ChannelFaultException("provider_capability_not_supported");
        }
    }

    private Set<ChannelRouter.Capability> requiredCapabilities(JsonNode body) {
        Set<ChannelRouter.Capability> result = new LinkedHashSet<>();
        result.add(ChannelRouter.Capability.TEXT);
        body.path("messages").forEach(message -> {
            JsonNode content = message.path("content");
            if (content.isArray()) {
                content.forEach(part -> {
                    String type = part.path("type").asText();
                    if ("image_url".equals(type) || "input_image".equals(type)) {
                        result.add(ChannelRouter.Capability.IMAGE);
                    }
                    if ("file".equals(type) || "file_url".equals(type) || "input_file".equals(type)) {
                        result.add(ChannelRouter.Capability.FILE);
                    }
                });
            }
        });
        return Set.copyOf(result);
    }
}
