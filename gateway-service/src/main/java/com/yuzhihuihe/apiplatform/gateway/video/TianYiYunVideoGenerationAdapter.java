package com.yuzhihuihe.apiplatform.gateway.video;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 天翼 AI 网关（{@code ai.ctaigw.cn}）的视频生成。
 *
 * <p>它与百炼**没有任何协议重叠**，因此无法复用
 * {@link BailianVideoGenerationAdapter}：
 *
 * <ul>
 *   <li>提交是 {@code POST /v1/video_generation}，提示词装在
 *       {@code content} 数组的类型化条目里，而不是 {@code input.prompt}；
 *   <li>时长、分辨率与画幅是三个平铺的必填字段
 *       （{@code duration} / {@code resolution} / {@code ratio}），
 *       而不是百炼那种 {@code parameters.size} 或 {@code parameters.mode}；
 *   <li>轮询是 {@code GET /v1/query/video_generation/{task_id}}，
 *       即 task id 走**路径**而不是查询串，响应包在 {@code task} 对象里；
 *   <li>状态是小写字符串（{@code succeeded}），不是百炼的
 *       {@code output.task_status} 大写枚举。
 * </ul>
 *
 * <p><b>分辨率阶梯不一致，靠本适配器翻译。</b>平台的
 * {@code gateway.video_alias_tier.resolution_tier} 被 CHECK 约束钉死为
 * {@code 480P/720P/1080P}，而 MiniMax-H3-Max 只提供
 * {@code 480P} 与 {@code 768P}。因此 720P 别名由该厂商的 768P 档承接
 * ——768P 是 720P 的**超集**，对客户只多不少——而 1080P 在本厂商
 * **无法履约**，这里直接拒绝，让该别名的路由不要指向本渠道。
 * 这正是适配器该做的翻译，而不是去放宽数据库约束或对客户虚报档位。
 *
 * <p><b>计费依据上游自报。</b>终态响应在 {@code task.usage.total_seconds}
 * 给出实际产出秒数；成功但缺少该字段时回退到所请求的时长，
 * 因为成功任务确实消耗了 GPU 时间。失败任务只有在厂商明确
 * 报告了用量时才计费，否则计零。
 *
 * <p>契约由 2026-09-22 对该端点直接探测确认：必填参数依次为
 * {@code model} → {@code content} → {@code duration} → {@code resolution}
 * → {@code ratio}，时长支持 5–15 秒，画幅支持
 * 16:9 / 4:3 / 1:1 / 3:4 / 9:16 / 21:9。
 *
 * <p><b>重复提交。</b>该端点不接受幂等键，一次重试的 {@code submit}
 * 就是第二次付费生成，因此适配器只发出恰好一次上游请求，内部绝不重试。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class TianYiYunVideoGenerationAdapter implements VideoGenerationAdapter {

    private static final Set<String> CAPABILITIES = Set.of("VIDEO");

    /**
     * 每个上游模型实际提供哪些分辨率。未列出的模型一律拒绝，
     * 而不是猜测它的阶梯——把一个 1080P 的售卖档位发给一个
     * 只做 768P 的模型，客户拿到的会是一段比付费档位更差的视频。
     */
    private static final Map<String, Set<String>> MODEL_RESOLUTIONS = Map.of(
            "minimax-h3-max", Set.of("480P", "768P"));

    private static final Set<String> ASPECT_RATIOS =
            Set.of("16:9", "4:3", "1:1", "3:4", "9:16", "21:9");

    private static final int MIN_DURATION_SECONDS = 5;
    private static final int MAX_DURATION_SECONDS = 15;

    private static final String API_ROOT = "/v1";
    private static final String SUBMIT_PATH = "/v1/video_generation";
    private static final String QUERY_PATH = "/v1/query/video_generation/";

    /**
     * 一次提交或轮询只是一次很小的 JSON 交互；生成本身是异步的，
     * 因此它从不占用连接。把这些上限压在渠道预算之下，
     * 可以让 HTTP 调用不会比将要消费其应答的那个请求活得更久。
     */
    private static final Duration CALL_TIMEOUT_CAP = Duration.ofSeconds(30);

    /** 厂商错误分类，用于日志与支持的稳定字符串，而不是原始文本。 */
    private static final String ERROR_UNKNOWN_TASK = "provider_task_unknown";
    private static final String ERROR_OUTPUT_NOT_READY = "provider_output_not_ready";
    private static final String ERROR_REJECTED = "provider_rejected_generation";
    private static final String ERROR_GENERATION_FAILED = "provider_generation_failed";
    private static final String ERROR_CANCELLED = "provider_generation_cancelled";
    private static final String ERROR_INVALID_SUBMISSION = "provider_invalid_submission";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public TianYiYunVideoGenerationAdapter(
            WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerType() {
        return "TIANYIYUN";
    }

    @Override
    public Set<String> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public SubmissionProfile submissionProfile(TaskRequest request) {
        String model = normalizedModel(request.channel().upstreamModel());
        Set<String> offered = MODEL_RESOLUTIONS.get(model);
        if (offered == null) {
            // 未登记的模型一律不可提交，因此目录也不得声称它可以生成任何东西。
            return new SubmissionProfile(
                    List.of(
                            new ModeProfile(
                                    "TEXT_TO_VIDEO", Set.of(), Set.of()),
                            new ModeProfile(
                                    "IMAGE_TO_VIDEO", Set.of(), Set.of())),
                    MIN_DURATION_SECONDS,
                    MAX_DURATION_SECONDS,
                    null,
                    false);
        }
        // 把厂商档位反译回平台档位：本厂商没有 720P，768P 承接 720P 别名。
        Set<String> tiers = new java.util.TreeSet<>();
        if (offered.contains("480P")) {
            tiers.add("480P");
        }
        if (offered.contains("768P")) {
            tiers.add("720P");
        }
        if (offered.contains("1080P")) {
            tiers.add("1080P");
        }
        ModeProfile textMode = new ModeProfile(
                "TEXT_TO_VIDEO", tiers, ASPECT_RATIOS);
        return new SubmissionProfile(
                List.of(
                        textMode,
                        // 该端点不接受图生视频的媒体条目，因此图生视频模式
                        // 报告空集而不是照抄文生视频的能力。
                        new ModeProfile(
                                "IMAGE_TO_VIDEO", Set.of(), Set.of())),
                MIN_DURATION_SECONDS,
                MAX_DURATION_SECONDS,
                null,
                false);
    }

    @Override
    public void validateSubmission(TaskRequest request) {
        String model = normalizedModel(request.channel().upstreamModel());
        Set<String> offered = MODEL_RESOLUTIONS.get(model);
        if (offered == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "unsupported_tianyi_video_model");
        }
        if (hasFirstFrame(request)) {
            // 尚未确认该端点接受图生视频的媒体条目。静默丢弃首帧
            // 会让客户以为自己买的是图生视频，却收到一段纯文生视频。
            throw invalidRequest("tianyi_video_image_input_not_supported");
        }
        require(providerResolution(model, request.resolutionTier()) != null,
                "tianyi_video_resolution_not_supported");
        int duration = request.durationSeconds();
        require(duration >= MIN_DURATION_SECONDS && duration <= MAX_DURATION_SECONDS,
                "tianyi_video_duration_not_supported");
        require(ASPECT_RATIOS.contains(normalizedRatio(request.aspectRatio())),
                "tianyi_video_aspect_ratio_not_supported");
    }

    @Override
    public Mono<SubmittedTask> submit(TaskRequest request) {
        return Mono.defer(() -> {
            validateSubmission(request);
            return client()
                    .post()
                    .uri(submitUri(request.channel()))
                    .headers(headers -> authorize(headers, request.channel()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(submitPayload(request))
                    .exchangeToMono(response -> response.statusCode().isError()
                            ? rejected(response)
                            : response.bodyToMono(JsonNode.class).flatMap(body -> Mono
                                    .justOrEmpty(taskId(body))
                                    .map(SubmittedTask::new)
                                    // 没有 task id 的 2xx 是协议变更，而不是
                                    // 用户错误：按上游故障处理，而不是上报一次
                                    // 网关无法轮询的提交。
                                    .switchIfEmpty(Mono.error(protocolFailure()))))
                    .timeout(callTimeout(request))
                    // 超时与连接失败属于**上游**故障，而不是网关自己的 500。
                    .onErrorMap(ProviderAdapter.ProviderException::isTransportFailure,
                            ProviderAdapter.ProviderException::ofTransport);
        });
    }

    @Override
    public Mono<TaskStatus> poll(TaskRequest request, String upstreamTaskId) {
        return Mono.defer(() -> {
            if (upstreamTaskId == null || upstreamTaskId.isBlank()) {
                return Mono.just(unknownHeldStatus());
            }
            return client()
                    .get()
                    .uri(queryUri(request.channel(), upstreamTaskId))
                    .headers(headers -> authorize(headers, request.channel()))
                    .accept(MediaType.APPLICATION_JSON)
                    .exchangeToMono(response -> response.statusCode().isError()
                            ? rejected(response)
                            : response.bodyToMono(JsonNode.class)
                                    .map(body -> toStatus(body, request.durationSeconds()))
                                    .defaultIfEmpty(unknownHeldStatus()))
                    .timeout(callTimeout(request))
                    // 超时与连接失败属于**上游**故障，而不是网关自己的 500。
                    .onErrorMap(ProviderAdapter.ProviderException::isTransportFailure,
                            ProviderAdapter.ProviderException::ofTransport);
        });
    }

    private WebClient client() {
        return webClientBuilder.build();
    }

    /**
     * 构造厂商的请求体。
     *
     * <p>提示词必须包成 {@code content} 数组里的类型化条目：
     * 直接把字符串发进 {@code content} 会被厂商以
     * 「Mismatch type []*VideoGenContentItem」拒绝。
     */
    private JsonNode submitPayload(TaskRequest request) {
        String model = normalizedModel(request.channel().upstreamModel());
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);

        ArrayNode content = root.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", request.prompt());

        root.put("duration", request.durationSeconds());
        root.put("resolution", providerResolution(model, request.resolutionTier()));
        root.put("ratio", normalizedRatio(request.aspectRatio()));
        return root;
    }

    /**
     * 把平台售卖的档位翻译成该厂商的档位。
     *
     * @return 厂商分辨率，或 {@code null} 表示该厂商无法履约这个档位
     */
    private String providerResolution(String model, String platformTier) {
        Set<String> offered = MODEL_RESOLUTIONS.get(model);
        if (offered == null || platformTier == null) {
            return null;
        }
        String requested = switch (platformTier) {
            case "480P" -> "480P";
            // 该厂商没有 720P 这一档，768P 是它的上一级；
            // 用更高的档位履约不会亏待客户。
            case "720P" -> "768P";
            default -> null;
        };
        return requested != null && offered.contains(requested) ? requested : null;
    }

    private String normalizedModel(String upstreamModel) {
        return upstreamModel == null ? "" : upstreamModel.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizedRatio(String ratio) {
        return ratio == null || ratio.isBlank() ? "16:9" : ratio.trim();
    }

    private boolean hasFirstFrame(TaskRequest request) {
        return request.inputImageUrl() != null && !request.inputImageUrl().isBlank();
    }

    private void require(boolean valid, String reason) {
        if (!valid) {
            throw invalidRequest(reason);
        }
    }

    private ResponseStatusException invalidRequest(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    /**
     * 把一个厂商任务文档映射为网关状态。
     *
     * <p>状态字符串是小写的（{@code succeeded}），因此先归一化大小写
     * 再匹配，避免厂商改一次大小写就把每一次生成都变成
     * 「未知任务」并永久占用客户的秒数。
     *
     * @param requestedSeconds 调用方请求的时长，仅当厂商成功
     *        但省略了 {@code usage} 时使用
     */
    private TaskStatus toStatus(JsonNode body, int requestedSeconds) {
        JsonNode task = body.path("task");
        String state = task.path("status").asText("").trim().toUpperCase(Locale.ROOT);
        return switch (state) {
            case "PENDING", "QUEUED", "SUBMITTED", "WAITING" -> TaskStatus.pending();
            case "RUNNING", "PROCESSING", "GENERATING" -> TaskStatus.running();
            case "SUCCEEDED", "SUCCESS", "COMPLETED", "FINISHED" -> {
                String outputUrl = videoUrl(task);
                if (outputUrl == null) {
                    // 某些上游会在带签名的结果位置可读之前就发布终态。
                    // 对该响应计费会让客户端留下一个成功却永远无法获取的任务，
                    // 因此保持它打开并继续轮询，直到该位置出现为止。
                    yield new TaskStatus(
                            TaskStatus.State.PENDING, null, null, ERROR_OUTPUT_NOT_READY);
                }
                yield new TaskStatus(
                        TaskStatus.State.SUCCEEDED,
                        billedSeconds(task, requestedSeconds),
                        outputUrl,
                        null);
            }
            case "FAILED", "FAIL", "ERROR" -> new TaskStatus(
                    TaskStatus.State.FAILED,
                    failedSeconds(task),
                    null,
                    errorCategory(task, ERROR_GENERATION_FAILED));
            case "CANCELLED", "CANCELED" -> new TaskStatus(
                    TaskStatus.State.CANCELLED,
                    failedSeconds(task),
                    null,
                    errorCategory(task, ERROR_CANCELLED));
            // 缺失或无法识别的状态并不能证明被拒绝。在观测到显式的
            // 终态结果之前，保持预留不被释放。
            default -> unknownHeldStatus();
        };
    }

    /** 表示一种必须继续保持占用、留待后续向供应商对账的结果。 */
    private TaskStatus unknownHeldStatus() {
        return new TaskStatus(TaskStatus.State.PENDING, null, null, ERROR_UNKNOWN_TASK);
    }

    /**
     * 成功的生成据此计费的秒数。
     *
     * <p>厂商报告的是实际产出的时长；缺少该字段的响应同样产出了
     * 一段视频，因此回退到所订购的时长，而不是报告零并把这次生成白送出去。
     */
    private Integer billedSeconds(JsonNode task, int requestedSeconds) {
        Integer reported = usageSeconds(task);
        if (reported != null && reported > 0) {
            return reported;
        }
        return requestedSeconds > 0 ? requestedSeconds : null;
    }

    /**
     * 失败或取消的生成据此计费的秒数。
     *
     * <p>只有在厂商自己报告了用量时才收费。厂商没有报告，
     * 就没有证据表明这次尝试消耗了可计费的 GPU 时间，
     * 因此计零而不是按请求时长收费。
     */
    private Integer failedSeconds(JsonNode task) {
        Integer reported = usageSeconds(task);
        return reported != null && reported > 0 ? reported : 0;
    }

    private Integer usageSeconds(JsonNode task) {
        JsonNode usage = task.path("usage");
        if (usage.isMissingNode() || usage.isNull() || !usage.isObject()) {
            return null;
        }
        for (String field : new String[] {"total_seconds", "output_seconds", "duration"}) {
            JsonNode value = usage.path(field);
            if (value.isNumber() && value.asInt() > 0) {
                return value.asInt();
            }
        }
        return null;
    }

    private String videoUrl(JsonNode task) {
        JsonNode content = task.path("content");
        String url = content.path("url").asText("");
        return url.isBlank() ? null : url;
    }

    /** 优先采用厂商自己的错误码，它比通用错误码具体得多。 */
    private String errorCategory(JsonNode task, String fallback) {
        JsonNode baseResp = task.path("base_resp");
        String code = baseResp.path("status_code").asText("");
        if (!code.isBlank() && !"0".equals(code)) {
            return fallback + ":" + code;
        }
        String taskCode = task.path("code").asText("");
        return taskCode.isBlank() ? fallback : taskCode;
    }

    /**
     * 从创建响应中取出任务 id。
     *
     * <p>该端点返回扁平的 {@code {"task_id": "..."}}，但 SDK 形态的
     * 包装会把同一个 id 放进 {@code task.id}，因此两种形态都被读取，
     * 而不是假定只有一种形态、因厂商响应重构就让一次已付费的提交失败。
     */
    private String taskId(JsonNode body) {
        for (String path : new String[] {"task_id"}) {
            String value = body.path(path).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        String nested = body.path("task").path("id").asText("");
        return nested.isBlank() ? null : nested;
    }

    /**
     * 翻译非 2xx 的厂商响应。
     *
     * <p>厂商既在状态码上、也在响应体（{@code error.code}）中报告失败，
     * 因此先读取响应体再失败：错误码正是渠道配置错误时运维看到的东西，
     * 而 {@code WebClient} 的默认异常会丢弃这些，只留下一行光秃秃的状态。
     * 结果仍然是上游故障，而不是把 4xx 交回给调用方——调用方自己的
     * 请求已经通过了网关校验。
     */
    private <T> Mono<T> rejected(ClientResponse response) {
        HttpStatus status = HttpStatus.resolve(response.statusCode().value());
        HttpStatus mapped = status == null ? HttpStatus.BAD_GATEWAY : status;
        return response.bodyToMono(JsonNode.class)
                .defaultIfEmpty(objectMapper.createObjectNode())
                .onErrorResume(error -> Mono.just(objectMapper.createObjectNode()))
                .flatMap(body -> {
                    String code = body.path("error").path("code").asText("");
                    if (code.isBlank()) {
                        code = body.path("code").asText("");
                    }
                    String category = code.isBlank()
                            ? ERROR_REJECTED
                            : ERROR_REJECTED + ":" + code;
                    return Mono.error(new ProviderAdapter.ProviderException(
                            mapped.value(), category));
                });
    }

    /** 适配器无法解释的 2xx：厂商契约变了，不是调用方的错。 */
    private ProviderAdapter.ProviderException protocolFailure() {
        return new ProviderAdapter.ProviderException(
                HttpStatus.BAD_GATEWAY.value(), ERROR_INVALID_SUBMISSION);
    }

    private void authorize(HttpHeaders headers, Channel channel) {
        if (channel.apiKey() != null && !channel.apiKey().isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + channel.apiKey());
        }
    }

    /**
     * 从渠道配置的 URL 解析出带版本的 API 根。
     *
     * <p>运维可能存的是厂商源站、带版本的 API 根，或者从厂商文档里
     * 原样复制的提交端点。后者会先被归约到它的父路径，
     * 因此完整的提交 URL 绝不会再拼接上另一个提交路径，
     * 也不会被当作 {@code .../video_generation/query/video_generation}。
     */
    static String apiRoot(Channel channel) {
        String baseUrl = normalizedBaseUrl(channel);
        if (baseUrl.endsWith(SUBMIT_PATH)) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - SUBMIT_PATH.length());
        }
        return baseUrl.endsWith(API_ROOT) ? baseUrl : baseUrl + API_ROOT;
    }

    /** 解析厂商的异步提交端点，同时不丢弃运维显式配置的路径。 */
    static String submitUri(Channel channel) {
        String configured = normalizedBaseUrl(channel);
        return configured.endsWith(SUBMIT_PATH)
                ? configured
                : apiRoot(channel) + SUBMIT_PATH;
    }

    /** 解析厂商的任务查询端点：task id 走路径，不是查询串。 */
    static String queryUri(Channel channel, String upstreamTaskId) {
        return apiRoot(channel) + QUERY_PATH + upstreamTaskId;
    }

    private static String normalizedBaseUrl(Channel channel) {
        return channel.baseUrl().replaceAll("/+$", "");
    }

    private Duration callTimeout(TaskRequest request) {
        Duration channelTimeout = Duration.ofMillis(request.timeoutMs());
        return channelTimeout.compareTo(CALL_TIMEOUT_CAP) < 0 ? channelTimeout : CALL_TIMEOUT_CAP;
    }
}
