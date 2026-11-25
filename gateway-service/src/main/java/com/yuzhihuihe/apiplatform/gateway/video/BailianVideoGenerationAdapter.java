package com.yuzhihuihe.apiplatform.gateway.video;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 阿里云百炼（Model Studio，即百炼 / DashScope）视频生成。
 *
 * <p>该厂商的视频 API 是一套异步任务协议，与 {@code /chat/completions}
 * 确实不同：带 {@code X-DashScope-Async: enable} 的 {@code POST
 * /api/v1/services/aigc/video-generation/video-synthesis} 返回一个 task id，
 * {@code GET /api/v1/tasks/{task_id}} 报告状态，并在成功之后报告已完成视频的位置。
 * HTTP 只支持异步形式——不带该请求头时，API 会回答
 * 「current user api does not support synchronous calls」——
 * 这正是视频无法由 {@code BailianAdapter} 提供的原因。两者共享
 * {@code BAILIAN} 供应商类型，靠能力区分：本适配器
 * 声明 {@code VIDEO}，对话适配器则不声明。
 *
 * <p>参考（2026 年 9 月核实）：
 * <a href="https://www.alibabacloud.com/help/en/model-studio/text-to-video-api-reference">Wan 文生视频 API 参考</a>。
 *
 * <p>下面这段代码由厂商的两条性质驱动，两者都与计费相关：
 *
 * <ol>
 *   <li><b>任务结果不依据响应计费。</b>失败任务的响应携带 {@code code} 与
 *       {@code message}，却没有 {@code usage} 对象；而成功任务的
 *       {@code usage} 会报告实际产出的时长。因此适配器对成功的生成
 *       按厂商实际报告的时长计费，对失败的生成计零，
 *       而不是把请求的时长当作已计费时长上报。费率卡上的
 *       {@code retry_rate} 仍然是失败尝试所消耗 GPU 时间的余量，
 *       因此两者不会被重复计算。
 *   <li><b>任务状态未知时保持占用，而不是结算。</b>缺失、{@code UNKNOWN}
 *       或其他无法识别的 {@code task_status} 并不能证明厂商拒绝或
 *       停止了该次生成。因此它保持非终态，并继续为客户保留秒数
 *       以待后续对账。只有显式的 {@code FAILED} 状态才被映射为
 *       终态失败。
 * </ol>
 *
 * <p><b>重复提交。</b>该端点不接受幂等键。一次重试的
 * {@code submit} 就是第二次付费生成，因此适配器只发出恰好一次
 * 上游请求，内部绝不重试；网关的
 * {@code request_id}——由客户端的 {@code Idempotency-Key} 选定——才是
 * 让客户端侧重试在本层之上变得安全的东西。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class BailianVideoGenerationAdapter implements VideoGenerationAdapter {

    private static final Set<String> CAPABILITIES = Set.of("VIDEO");
    private static final Set<String> WAN_27_TEXT_MODELS = Set.of(
            "wan2.7-t2v",
            "wan2.7-t2v-2026-04-25",
            "wan2.7-t2v-2026-06-12");
    private static final Set<String> WAN_27_IMAGE_MODELS = Set.of(
            "wan2.7-i2v",
            "wan2.7-i2v-2026-04-25");
    private static final Set<String> WAN_26_TEXT_MODELS = Set.of("wan2.6-t2v");
    private static final Set<String> WAN_26_IMAGE_MODELS = Set.of(
            "wan2.6-i2v", "wan2.6-i2v-flash");
    private static final Set<String> WAN_25_TEXT_MODELS = Set.of("wan2.5-t2v-preview");
    private static final Set<String> WAN_25_IMAGE_MODELS = Set.of("wan2.5-i2v-preview");
    private static final Set<String> KLING_MODELS = Set.of(
            "kling/kling-v3-turbo-video-generation",
            "kling/kling-v3-video-generation",
            "kling/kling-v3-omni-video-generation");
    private static final Set<String> WAN_ASPECT_RATIOS =
            Set.of("16:9", "9:16", "1:1", "4:3", "3:4");
    private static final Set<String> KLING_ASPECT_RATIOS =
            Set.of("16:9", "9:16", "1:1");

    /** 两个端点都位于该区域 base URL 的带版本 API 根之下。 */
    private static final String API_ROOT = "/api/v1";
    private static final String SUBMIT_PATH = "/services/aigc/video-generation/video-synthesis";
    private static final String TASK_PATH = "/tasks/";

    /**
     * 一次提交或轮询只是一次很小的 JSON 交互；生成本身是
     * 异步的，因此它从不占用连接。把这些上限压在渠道预算之下，
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

    public BailianVideoGenerationAdapter(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerType() {
        return "BAILIAN";
    }

    @Override
    public Set<String> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public void validateSubmission(TaskRequest request) {
        String configuredModel = request.channel().upstreamModel();
        ModelDialect dialect = dialect(configuredModel);
        String resolution = request.resolutionTier();
        int duration = request.durationSeconds();
        String ratio = normalizedRatio(request.aspectRatio());
        if (!hasFirstFrame(request)
                && (WAN_27_IMAGE_MODELS.contains(configuredModel)
                        || WAN_26_IMAGE_MODELS.contains(configuredModel)
                        || WAN_25_IMAGE_MODELS.contains(configuredModel))) {
            throw invalidRequest("bailian_video_first_frame_required");
        }

        switch (dialect) {
            case WAN_25 -> {
                require(Set.of("480P", "720P", "1080P").contains(resolution),
                        "bailian_video_resolution_not_supported");
                require(duration == 5 || duration == 10,
                        "bailian_video_duration_not_supported");
                if (!hasFirstFrame(request) && "480P".equals(resolution)) {
                    require(Set.of("16:9", "9:16", "1:1").contains(ratio),
                            "bailian_video_aspect_ratio_not_supported");
                } else if (!hasFirstFrame(request)) {
                    require(WAN_ASPECT_RATIOS.contains(ratio),
                            "bailian_video_aspect_ratio_not_supported");
                }
            }
            case WAN_26, WAN_27 -> {
                require(Set.of("720P", "1080P").contains(resolution),
                        "bailian_video_resolution_not_supported");
                require(duration >= 2 && duration <= 15,
                        "bailian_video_duration_not_supported");
                if (!hasFirstFrame(request)) {
                    require(WAN_ASPECT_RATIOS.contains(ratio),
                            "bailian_video_aspect_ratio_not_supported");
                }
            }
            case KLING -> {
                require(Set.of("720P", "1080P").contains(resolution),
                        "bailian_video_resolution_not_supported");
                require(duration >= 3 && duration <= 15,
                        "bailian_video_duration_not_supported");
                if (!hasFirstFrame(request)) {
                    require(KLING_ASPECT_RATIOS.contains(ratio),
                            "bailian_video_aspect_ratio_not_supported");
                }
            }
        }
    }

    @Override
    public SubmissionProfile submissionProfile(TaskRequest request) {
        return profileFor(request.channel().upstreamModel());
    }

    /**
     * 把厂商文档里「哪种模型接受哪些参数」写成客户端可见的画像。
     *
     * <p>它必须与 {@link #validateSubmission(TaskRequest)} 描述同一份契约：
     * 这里少写一个档位，客户端就会看到一个本可提交的组合被标成不可用；
     * 这里多写一个，客户端就会在下单时才收到 422。
     *
     * <p>差异集中在四处：Wan 2.5 只收 5/10 秒；Wan 2.5 的文生视频在 480P
     * 下只收三种画幅而图生视频收全部画幅；百炼自 2.6 起不再提供 480P
     * 档位（只有 720P/1080P 在费率卡里）；Kling 的 {@code turbo}
     * 变体不接受音频开关（其余 Kling 接受）。
     *
     * <p>Wan 的图生视频由本适配器改用文档里的图像兄弟模型
     * （{@code wan2.6-i2v} 等），因此两个模态的档位集合相同。
     */
    private SubmissionProfile profileFor(String configuredModel) {
        ModelDialect dialect = dialect(configuredModel);
        return switch (dialect) {
            case WAN_25 -> new SubmissionProfile(
                    List.of(
                            new ModeProfile(
                                    "TEXT_TO_VIDEO",
                                    Set.of("480P", "720P", "1080P"),
                                    // 480P 文生视频只收三种画幅，因此全档位
                                    // 共同可用的画幅是这三种与全集的交集。
                                    Set.of("16:9", "9:16", "1:1")),
                            new ModeProfile(
                                    "IMAGE_TO_VIDEO",
                                    Set.of("480P", "720P", "1080P"),
                                    WAN_ASPECT_RATIOS)),
                    5,
                    10,
                    List.of(5, 10),
                    false);
            // Wan 2.6 / 2.7 只提供 720P 与 1080P，时长 2–15 秒连续可选，
            // 音频由厂商自动协同生成、不暴露开关，因此不承诺 withAudio。
            case WAN_26, WAN_27 -> new SubmissionProfile(
                    List.of(
                            new ModeProfile(
                                    "TEXT_TO_VIDEO", Set.of("720P", "1080P"), WAN_ASPECT_RATIOS),
                            new ModeProfile(
                                    "IMAGE_TO_VIDEO", Set.of("720P", "1080P"), WAN_ASPECT_RATIOS)),
                    2,
                    15,
                    null,
                    false);
            case KLING -> new SubmissionProfile(
                    List.of(
                            new ModeProfile(
                                    "TEXT_TO_VIDEO", Set.of("720P", "1080P"), KLING_ASPECT_RATIOS),
                            // 图生视频沿用首帧自身的画幅，aspect_ratio 不参与校验，
                            // 因此这里照实报告同一组可提交的画幅。
                            new ModeProfile(
                                    "IMAGE_TO_VIDEO", Set.of("720P", "1080P"), KLING_ASPECT_RATIOS)),
                    3,
                    15,
                    null,
                    !"kling/kling-v3-turbo-video-generation".equals(configuredModel));
        };
    }

    @Override
    public Mono<SubmittedTask> submit(TaskRequest request) {
        return Mono.defer(() -> {
            validateSubmission(request);
            JsonNode payload = submitPayload(request);
            return client()
                    .post()
                    .uri(submitUri(request.channel()))
                    // 必需：HTTP API 对视频没有同步模式。
                    .header("X-DashScope-Async", "enable")
                    .headers(headers -> authorize(headers, request.channel()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
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
                    .uri(apiRoot(request.channel()) + TASK_PATH + upstreamTaskId)
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
     * <p>{@code prompt_extend} 保持厂商自己的默认值 true：它能改善
     * 短提示词，而厂商并不对提示词改写单独计费，
     * 因此关掉它只会损失质量。
     */
    private JsonNode submitPayload(TaskRequest request) {
        ModelDialect dialect = dialect(request.channel().upstreamModel());
        boolean imageToVideo = hasFirstFrame(request);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", submissionModel(request, dialect));

        ObjectNode input = root.putObject("input");
        input.put("prompt", request.prompt());
        if (imageToVideo) {
            if (dialect == ModelDialect.WAN_25 || dialect == ModelDialect.WAN_26) {
                // Wan 2.6 及更早版本使用旧版首帧协议。
                input.put("img_url", request.inputImageUrl());
            } else {
                // Wan 2.7 与 Kling 都使用当前的类型化媒体协议。
                ObjectNode firstFrame = input.putArray("media").addObject();
                firstFrame.put("type", "first_frame");
                firstFrame.put("url", request.inputImageUrl());
            }
        }

        ObjectNode parameters = root.putObject("parameters");
        if (dialect == ModelDialect.KLING) {
            parameters.put("mode", klingMode(request.resolutionTier()));
            if (!imageToVideo) {
                parameters.put("aspect_ratio", normalizedRatio(request.aspectRatio()));
            }
            parameters.put("duration", request.durationSeconds());
            if (!"kling/kling-v3-turbo-video-generation"
                    .equals(request.channel().upstreamModel())) {
                parameters.put("audio", request.withAudio());
            }
            parameters.put("watermark", false);
            return root;
        }

        if (imageToVideo || dialect == ModelDialect.WAN_27) {
            parameters.put("resolution", request.resolutionTier());
        } else {
            parameters.put(
                    "size",
                    legacyWanSize(request.resolutionTier(), normalizedRatio(request.aspectRatio())));
        }
        if (!imageToVideo && dialect == ModelDialect.WAN_27) {
            parameters.put("ratio", normalizedRatio(request.aspectRatio()));
        }
        parameters.put("duration", request.durationSeconds());
        // Wan 2.5、2.6 和 2.7 在未提供音频 URL 时会自动协同生成音频。
        // 它们不暴露通用的布尔音频开关，因此对这些路由而言 withAudio
        // 是有意不做转换的。
        parameters.put("prompt_extend", true);
        parameters.put("watermark", false);

        return root;
    }

    private String submissionModel(TaskRequest request, ModelDialect dialect) {
        String configured = request.channel().upstreamModel();
        if (!hasFirstFrame(request)) {
            if (WAN_27_IMAGE_MODELS.contains(configured)
                    || WAN_26_IMAGE_MODELS.contains(configured)
                    || WAN_25_IMAGE_MODELS.contains(configured)) {
                throw invalidRequest("bailian_video_first_frame_required");
            }
            return configured;
        }
        return switch (dialect) {
            // 渠道映射给出的是该别名所售的文本模型。当同一个
            // 别名收到首帧时，选择文档中对应的图像兄弟模型，
            // 而不是把图像字段发给一个纯文本的上游模型。
            case WAN_27 -> WAN_27_TEXT_MODELS.contains(configured)
                    ? "wan2.7-i2v" : configured;
            case WAN_26 -> WAN_26_TEXT_MODELS.contains(configured)
                    ? "wan2.6-i2v" : configured;
            case WAN_25 -> WAN_25_TEXT_MODELS.contains(configured)
                    ? "wan2.5-i2v-preview" : configured;
            case KLING -> configured;
        };
    }

    private ModelDialect dialect(String model) {
        if (WAN_27_TEXT_MODELS.contains(model) || WAN_27_IMAGE_MODELS.contains(model)) {
            return ModelDialect.WAN_27;
        }
        if (WAN_26_TEXT_MODELS.contains(model) || WAN_26_IMAGE_MODELS.contains(model)) {
            return ModelDialect.WAN_26;
        }
        if (WAN_25_TEXT_MODELS.contains(model) || WAN_25_IMAGE_MODELS.contains(model)) {
            return ModelDialect.WAN_25;
        }
        if (KLING_MODELS.contains(model)) {
            return ModelDialect.KLING;
        }
        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "unsupported_bailian_video_model");
    }

    private String normalizedRatio(String ratio) {
        return ratio == null || ratio.isBlank() ? "16:9" : ratio;
    }

    private boolean hasFirstFrame(TaskRequest request) {
        return request.inputImageUrl() != null && !request.inputImageUrl().isBlank();
    }

    private String klingMode(String resolutionTier) {
        return switch (resolutionTier) {
            case "720P" -> "std";
            case "1080P" -> "pro";
            default -> throw invalidRequest("bailian_video_resolution_not_supported");
        };
    }

    private String legacyWanSize(String resolutionTier, String ratio) {
        return switch (resolutionTier + ":" + ratio) {
            case "480P:16:9" -> "832*480";
            case "480P:9:16" -> "480*832";
            case "480P:1:1" -> "624*624";
            case "720P:16:9" -> "1280*720";
            case "720P:9:16" -> "720*1280";
            case "720P:1:1" -> "960*960";
            case "720P:4:3" -> "1088*832";
            case "720P:3:4" -> "832*1088";
            case "1080P:16:9" -> "1920*1080";
            case "1080P:9:16" -> "1080*1920";
            case "1080P:1:1" -> "1440*1440";
            case "1080P:4:3" -> "1632*1248";
            case "1080P:3:4" -> "1248*1632";
            default -> throw invalidRequest("bailian_video_resolution_not_supported");
        };
    }

    private void require(boolean valid, String reason) {
        if (!valid) {
            throw invalidRequest(reason);
        }
    }

    private ResponseStatusException invalidRequest(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private enum ModelDialect {
        WAN_25,
        WAN_26,
        WAN_27,
        KLING
    }

    /**
     * 把一个厂商任务文档映射为网关状态。
     *
     * @param requestedSeconds 调用方请求的时长，仅当厂商成功
     *        但省略了 {@code usage} 时使用
     */
    private TaskStatus toStatus(JsonNode body, int requestedSeconds) {
        JsonNode output = body.path("output");
        String state = output.path("task_status").asText("");
        return switch (state) {
            case "PENDING" -> TaskStatus.pending();
            case "RUNNING" -> TaskStatus.running();
            case "SUCCEEDED" -> {
                String outputUrl = videoUrl(output);
                if (outputUrl == null) {
                    // 某些上游会在带签名的结果位置可读之前就发布终态。
                    // 对该响应计费会让客户端留下一个成功却永远无法获取的任务，
                    // 因此保持它打开并继续轮询，
                    // 直到该位置出现为止。
                    yield new TaskStatus(
                            TaskStatus.State.PENDING, null, null, ERROR_OUTPUT_NOT_READY);
                }
                yield new TaskStatus(
                        TaskStatus.State.SUCCEEDED,
                        // 厂商报告的是实际产出的时长；没有该字段的响应
                        // 同样产出了一段视频，因此回退到所订购的时长，
                        // 而不是报告零并把这次生成白送出去。
                        billedSeconds(body, requestedSeconds),
                        outputUrl,
                        null);
            }
            case "FAILED" -> new TaskStatus(
                    TaskStatus.State.FAILED, 0, null, errorCategory(output, ERROR_GENERATION_FAILED));
            case "CANCELED", "CANCELLED" -> new TaskStatus(
                    TaskStatus.State.CANCELLED, 0, null, errorCategory(output, ERROR_CANCELLED));
            // 缺失、已过期或无法识别的任务状态并不能证明被拒绝。
            // 在观测到显式的终态结果之前，保持预留不被释放。
            case "UNKNOWN", "" -> unknownHeldStatus();
            default -> unknownHeldStatus();
        };
    }

    /** 表示一种必须继续保持占用、留待后续向供应商对账的结果。 */
    private TaskStatus unknownHeldStatus() {
        return new TaskStatus(TaskStatus.State.PENDING, null, null, ERROR_UNKNOWN_TASK);
    }

    /**
     * 本次生成据此计费的秒数。
     *
     * <p>wan2.6 及之后报告 {@code usage.output_video_duration} 外加一个
     * {@code duration} 总计；wan2.5 及之前报告的是 {@code usage.video_duration}。
     * 无论哪种情况，成功的任务都已消耗了 GPU 时间，因此回退值是
     * 所请求的时长，而不是零。
     */
    private Integer billedSeconds(JsonNode body, int requestedSeconds) {
        // HTTP 任务契约把 usage 放在 output 旁边，而不是放在它里面。保留
        // 嵌套回退是为了兼容 SDK 形态的 fixture 和更早的代理响应。
        JsonNode usage = body.path("usage");
        if (usage.isMissingNode() || usage.isNull() || !usage.isObject()) {
            usage = body.path("output").path("usage");
        }
        for (String field : new String[] {"output_video_duration", "video_duration", "duration"}) {
            JsonNode value = usage.path(field);
            if (value.isNumber() && value.asInt() > 0) {
                return value.asInt();
            }
        }
        return requestedSeconds > 0 ? requestedSeconds : null;
    }

    private String videoUrl(JsonNode output) {
        String url = output.path("video_url").asText("");
        return url.isBlank() ? null : url;
    }

    /** 优先采用厂商自己的错误码，它比通用错误码具体得多。 */
    private String errorCategory(JsonNode output, String fallback) {
        String code = output.path("code").asText("");
        return code.isBlank() ? fallback : code;
    }

    /**
     * 从创建响应中取出任务 id。
     *
     * <p>文档给出的成功响应体把它放在 {@code output.task_id}。SDK 形态的
     * 两步形式把创建结果建模为一个任务对象，并在顶层暴露同一个 id，
     * 因此两种形态都会被读取，而不是假定只有一种形态、
     * 因厂商响应重构就让一次已付费的提交失败。
     */
    private String taskId(JsonNode body) {
        String nested = body.path("output").path("task_id").asText("");
        if (!nested.isBlank()) {
            return nested;
        }
        String topLevel = body.path("task_id").asText("");
        return topLevel.isBlank() ? null : topLevel;
    }

    /**
     * 翻译非 2xx 的厂商响应。
     *
     * <p>厂商既在状态码上、也在响应体（{@code code} / {@code message}）中
     * 报告失败，因此先读取响应体再失败：{@code InvalidApiKey} 与
     * {@code InvalidParameter} 正是渠道配置错误时运维看到的东西，
     * 而 {@code WebClient} 的默认异常会丢弃这些，只留下
     * 一行光秃秃的状态。结果仍然是上游故障，而不是把 4xx 交回给
     * 调用方——调用方自己的请求已经通过了网关校验。
     */
    private <T> Mono<T> rejected(ClientResponse response) {
        HttpStatus status = HttpStatus.resolve(response.statusCode().value());
        HttpStatus mapped = status == null ? HttpStatus.BAD_GATEWAY : status;
        return response.bodyToMono(JsonNode.class)
                .defaultIfEmpty(objectMapper.createObjectNode())
                .onErrorResume(error -> Mono.just(objectMapper.createObjectNode()))
                .flatMap(body -> {
                    String code = body.path("code").asText("");
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
     * 原样复制的提交端点。后者会在构造轮询 URL 之前被归约到
     * 它的 {@code /api/v1} 父路径，因此完整的提交 URL 绝不会
     * 再拼接上另一个提交路径，也不会被当作 {@code .../video-synthesis/tasks}。
     */
    static String apiRoot(Channel channel) {
        String baseUrl = normalizedBaseUrl(channel);
        if (baseUrl.endsWith(SUBMIT_PATH)) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - SUBMIT_PATH.length());
        }
        return baseUrl.endsWith(API_ROOT) ? baseUrl : baseUrl + API_ROOT;
    }

    /**
     * 解析厂商的异步提交端点，同时不丢弃运维显式配置的路径。
     */
    static String submitUri(Channel channel) {
        String configured = normalizedBaseUrl(channel);
        return configured.endsWith(SUBMIT_PATH)
                ? configured
                : apiRoot(channel) + SUBMIT_PATH;
    }

    private static String normalizedBaseUrl(Channel channel) {
        return channel.baseUrl().replaceAll("/+$", "");
    }

    private Duration callTimeout(TaskRequest request) {
        Duration channelTimeout = Duration.ofMillis(request.timeoutMs());
        return channelTimeout.compareTo(CALL_TIMEOUT_CAP) < 0 ? channelTimeout : CALL_TIMEOUT_CAP;
    }
}
