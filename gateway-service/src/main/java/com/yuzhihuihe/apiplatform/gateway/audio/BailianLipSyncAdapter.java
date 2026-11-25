package com.yuzhihuihe.apiplatform.gateway.audio;

import java.time.Duration;
import java.util.Locale;
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
 * 阿里云百炼（DashScope，华北2 北京）的声动人像 VideoRetalk：用一段配音
 * 替换人物视频的口型。
 *
 * <p><b>异步任务协议</b>，与 {@code BailianVideoGenerationAdapter} 同形：
 * 提交带 {@code X-DashScope-Async: enable} 到
 * {@code POST /api/v1/services/aigc/image2video/video-synthesis}，
 * 轮询 {@code GET /api/v1/tasks/{task_id}}。
 *
 * <p><b>秒与帧单位边界的守卫是本适配器最重要的职责。</b>厂商终态回报
 * {@code usage.video_duration}（秒，带小数）以及 {@code usage.fps}
 * （与输入视频一致的帧率）。账单按**秒**计（0.08 元/秒），
 * 而 V92 按 {@code AUDIO_SECOND} 结算。因此这里只读秒数，
 * 并且**向上取整**：厂商报 7.2 秒就按 7.2 秒收费，报 7 秒会少收 0.2 秒。
 * {@code fps} 被刻意忽略——把它乘进去会让一次 25fps 的作业按 25 倍结算，
 * 而那是一个不会失败、只会静默多收客户钱的错误。
 *
 * <p><b>输入限制在本地校验，理由很直接。</b>厂商对视频与音频的限制是
 * 2 秒&lt;时长&lt;120 秒、视频边长 640-2048、帧率 15-60fps。违反它们的请求
 * 会在**网关已经付过一次提交**之后才被拒绝，而其中「时长必须大于 2 秒」
 * 这一条完全可以从预留秒数推出来。能在本地发现的部分都在
 * {@link #validateSubmission} 里发现。
 *
 * <p><b>每账号 1 个并发作业。</b>厂商同时只运行一个作业，其余排队。
 * 渠道的 {@code max_concurrency} 因此应当配成 1 或 2；配成 4 只会让三个请求
 * 在厂商侧排队，而不是在网关侧被限流——两者对客户的体验不同，
 * 后者至少是快速失败。这一点写在渠道的 settings 里，见 V93。
 *
 * <p><b>重复提交。</b>该端点不接受幂等键，一次重试的 {@code submit}
 * 就是第二次付费作业，因此适配器只发出恰好一次上游请求，内部绝不重试。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class BailianLipSyncAdapter implements LipSyncAdapter {

    private static final Set<String> CAPABILITIES = Set.of("LIP_SYNC");

    /**
     * 已确认的上游模型名。VideoRetalk 只有一个模型，
     * 未列出的名字一律拒绝而不是转发——那只会浪费一次付费提交。
     */
    private static final Set<String> MODELS = Set.of("videoretalk");

    private static final String API_ROOT = "/api/v1";
    private static final String SUBMIT_PATH =
            "/api/v1/services/aigc/image2video/video-synthesis";
    private static final String TASK_PATH = "/api/v1/tasks/";

    /**
     * 厂商对视频与音频的硬性时长限制，单位秒。
     * 刻意是**开区间**（严格大于 2、严格小于 120），与文档一致：
     * 恰好 2 秒的文件会被厂商拒绝，因此网关也拒绝它。
     */
    static final int MIN_INPUT_SECONDS = 2;
    static final int MAX_INPUT_SECONDS = 120;

    /**
     * 一次提交或轮询只是一次很小的 JSON 交互；换口型本身是异步的，
     * 因此它从不占用连接。上限压在渠道预算之下。
     */
    private static final Duration CALL_TIMEOUT_CAP = Duration.ofSeconds(30);

    /** 厂商错误分类，用于日志与支持的稳定字符串，而不是原始文本。 */
    private static final String ERROR_UNKNOWN_TASK = "provider_task_unknown";
    private static final String ERROR_OUTPUT_NOT_READY = "provider_output_not_ready";
    private static final String ERROR_REJECTED = "provider_rejected_lip_sync";
    private static final String ERROR_INVALID_SUBMISSION = "provider_invalid_submission";
    private static final String ERROR_GENERATION_FAILED = "provider_lip_sync_failed";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public BailianLipSyncAdapter(
            WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
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
    public void validateSubmission(LipSyncRequest request) {
        String model = normalizedModel(request.channel().upstreamModel());
        if (!MODELS.contains(model)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "unsupported_bailian_lip_sync_model");
        }
        // 预留秒数就是网关对这段音频/视频长度的判断。厂商要求在开区间
        // (2, 120) 之内，因此这里用同一区间做本地前置检查：一个 1 秒的请求
        // 不该先付一次提交再被上游拒绝。
        int seconds = request.reservedSeconds();
        if (seconds <= MIN_INPUT_SECONDS || seconds >= MAX_INPUT_SECONDS) {
            throw invalidRequest("lip_sync_duration_not_supported");
        }
    }

    @Override
    public Mono<SubmittedLipSync> submit(LipSyncRequest request) {
        return Mono.defer(() -> {
            validateSubmission(request);
            return client()
                    .post()
                    .uri(submitUri(request.channel()))
                    .headers(headers -> authorize(headers, request.channel()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    // 这个端点靠请求头而不是请求体宣告异步。
                    .header("X-DashScope-Async", "enable")
                    .bodyValue(submitPayload(request))
                    .exchangeToMono(response -> response.statusCode().isError()
                            ? rejected(response)
                            : response.bodyToMono(JsonNode.class).flatMap(body -> Mono
                                    .justOrEmpty(taskId(body))
                                    .map(SubmittedLipSync::new)
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
    public Mono<LipSyncStatus> poll(LipSyncRequest request, String upstreamTaskId) {
        return Mono.defer(() -> {
            if (upstreamTaskId == null || upstreamTaskId.isBlank()) {
                return Mono.just(unknownHeldStatus());
            }
            return client()
                    .get()
                    .uri(taskUri(request.channel(), upstreamTaskId))
                    .headers(headers -> authorize(headers, request.channel()))
                    .accept(MediaType.APPLICATION_JSON)
                    .exchangeToMono(response -> response.statusCode().isError()
                            ? rejected(response)
                            : response.bodyToMono(JsonNode.class)
                                    .map(this::toStatus)
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
     * <p>{@code model} 取自渠道的 {@code upstream_model}，绝不取自客户端。
     * {@code ref_image_url} 只在调用方真的提供时发送：空字符串在这里与
     * 「未指定」的语义不同，而厂商会拿它去做人脸匹配。
     */
    private ObjectNode submitPayload(LipSyncRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", normalizedModel(request.channel().upstreamModel()));
        ObjectNode input = root.putObject("input");
        input.put("video_url", request.videoUrl());
        input.put("audio_url", request.audioUrl());
        if (request.referenceImageUrl() != null && !request.referenceImageUrl().isBlank()) {
            input.put("ref_image_url", request.referenceImageUrl());
        }
        // 显式发送，不依赖厂商默认值：这个开关决定「音频比视频长时是扩展画面
        // 还是截断音频」，两者对客户是完全不同的产出，因此由调用方决定。
        root.putObject("parameters").put("video_extension", request.extendVideo());
        return root;
    }

    /**
     * 把一个厂商任务文档映射为网关状态。
     *
     * <p>{@code PRE-PROCESSING} 与 {@code POST-PROCESSING} 是厂商特有的两个
     * 中间态，都归入 {@code RUNNING}：它们都表示作业已被接受且正在推进，
     * 而网关对这两者的处理完全相同（继续轮询、继续持有预留）。
     *
     * <p>{@code UNKNOWN}（作业不存在或状态未知）**不是**终态：
     * 它无法证明任务被拒绝，因此在拿到显式终态之前继续持有预留，
     * 与 {@code TianYiYunVideoGenerationAdapter} 的
     * {@code unknownHeldStatus} 是同一条规则。
     */
    private LipSyncStatus toStatus(JsonNode body) {
        JsonNode output = body.path("output");
        String state = output.path("task_status").asText("").trim().toUpperCase(Locale.ROOT);
        return switch (state) {
            case "PENDING", "PRE-PROCESSING", "POST-PROCESSING", "RUNNING" ->
                    LipSyncStatus.running();
            case "SUCCEEDED" -> {
                String outputUrl = text(output.path("video_url"));
                if (outputUrl == null) {
                    // 某些上游会在带签名的结果位置可读之前就发布终态。
                    // 对该响应计费会让客户端留下一个成功却永远无法获取的任务，
                    // 因此保持它打开并继续轮询。
                    yield new LipSyncStatus(
                            LipSyncStatus.State.PENDING, null, null, ERROR_OUTPUT_NOT_READY);
                }
                yield new LipSyncStatus(
                        LipSyncStatus.State.SUCCEEDED,
                        billedSeconds(body),
                        outputUrl,
                        null);
            }
            case "FAILED" -> new LipSyncStatus(
                    LipSyncStatus.State.FAILED,
                    failedSeconds(body),
                    null,
                    errorCategory(output, ERROR_GENERATION_FAILED));
            default -> unknownHeldStatus();
        };
    }

    /** 表示一种必须继续保持占用、留待后续向供应商对账的结果。 */
    private LipSyncStatus unknownHeldStatus() {
        return new LipSyncStatus(
                LipSyncStatus.State.PENDING, null, null, ERROR_UNKNOWN_TASK);
    }

    /**
     * 成功的作业据此计费的秒数。
     *
     * <p>{@code usage.video_duration} 是**唯一的**计费依据。它是产出视频的秒数，
     * 与厂商的 0.08 元/秒同单位。缺失时返回 {@code null} 而不是 0：
     * 服务层据此判断「无法确定」，并保持任务打开继续轮询，
     * 而不是按零结算一次已经产出了视频的作业。
     */
    private Integer billedSeconds(JsonNode body) {
        return usageSeconds(body);
    }

    /**
     * 失败或取消的作业据此计费的秒数。
     *
     * <p>只有在厂商自己报告了用量时才收费。VideoRetalk 是后付费且按产出计费，
     * 因此一次失败的作业通常报告零——但这里读厂商实际说的数字，
     * 而不是假定它一定是零。
     */
    private Integer failedSeconds(JsonNode body) {
        Integer reported = usageSeconds(body);
        return reported != null && reported > 0 ? reported : 0;
    }

    /**
     * 取出 {@code usage.video_duration} 并向上取整成秒。
     *
     * <p>**只读秒，绝不读 fps。** {@code usage} 里同时有 {@code fps} 与
     * {@code size}，它们是产出的描述而不是计费单位；把它们乘进来会让一次
     * 25fps 的作业按 25 倍结算。这个错误不会让任何测试变红，
     * 只会让客户多付 25 倍的钱，因此它值得一段注释。
     *
     * @return 秒数，或 {@code null} 表示厂商没有报告时长
     */
    private Integer usageSeconds(JsonNode body) {
        JsonNode usage = body.path("usage");
        if (usage.isMissingNode() || usage.isNull() || !usage.isObject()) {
            return null;
        }
        JsonNode value = usage.path("video_duration");
        if (!value.isNumber() || value.asDouble() <= 0) {
            return null;
        }
        return (int) Math.ceil(value.asDouble());
    }

    /** 优先采用厂商自己的错误码，它比通用错误码具体得多。 */
    private String errorCategory(JsonNode output, String fallback) {
        String code = output.path("code").asText("");
        return code.isBlank() ? fallback : code;
    }

    /**
     * 从提交响应中取出任务 id。
     *
     * <p>该端点把 id 放在 {@code output.task_id}；同时容忍一个扁平的
     * {@code task_id}，因为只认一种形态的话，厂商哪次调整包装就让每一次
     * 已付费的提交都无法被轮询。
     */
    private String taskId(JsonNode body) {
        String nested = text(body.path("output").path("task_id"));
        return nested != null ? nested : text(body.path("task_id"));
    }

    private String normalizedModel(String upstreamModel) {
        return upstreamModel == null ? "" : upstreamModel.trim().toLowerCase(Locale.ROOT);
    }

    private String text(JsonNode node) {
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }

    private ResponseStatusException invalidRequest(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private void authorize(HttpHeaders headers, Channel channel) {
        if (channel.apiKey() != null && !channel.apiKey().isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + channel.apiKey());
        }
    }

    /**
     * 渠道 base_url 的**源站**部分：去掉尾部斜杠、去掉可能被整段粘进来的提交
     * 端点或任务端点、再去掉可能已经带上的 API 根（{@code /api/v1}）。
     *
     * <p><b>返回的是不带 {@code /api/v1} 的前缀。</b>{@link #SUBMIT_PATH} 与
     * {@link #TASK_PATH} 自己就带 {@code /api/v1}。早先的实现先把
     * {@code /api/v1} 补进前缀再拼这两个常量，于是得到
     * {@code /api/v1/api/v1/services/...}，**每一次真实提交与轮询都 404**。
     * 它与两个同级音频适配器里的同名错误是同一处设计错误的三份拷贝。
     */
    static String apiOrigin(Channel channel) {
        String baseUrl = normalizedBaseUrl(channel);
        if (baseUrl.endsWith(SUBMIT_PATH)) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - SUBMIT_PATH.length());
        }
        if (baseUrl.contains(TASK_PATH)) {
            baseUrl = baseUrl.substring(0, baseUrl.indexOf(TASK_PATH));
        }
        return baseUrl.endsWith(API_ROOT)
                ? baseUrl.substring(0, baseUrl.length() - API_ROOT.length())
                : baseUrl;
    }

    /** 带版本的 API 根 {@code {origin}/api/v1}，用于日志与调用归属。 */
    static String apiRoot(Channel channel) {
        return apiOrigin(channel) + API_ROOT;
    }

    /** 解析厂商的异步提交端点，同时不丢弃运维显式配置的路径。 */
    static String submitUri(Channel channel) {
        String configured = normalizedBaseUrl(channel);
        return configured.endsWith(SUBMIT_PATH)
                ? configured
                : apiOrigin(channel) + SUBMIT_PATH;
    }

    /** 解析厂商的任务查询端点：task id 走路径，不是查询串。 */
    static String taskUri(Channel channel, String upstreamTaskId) {
        return apiOrigin(channel) + TASK_PATH + upstreamTaskId;
    }

    private static String normalizedBaseUrl(Channel channel) {
        return channel.baseUrl().replaceAll("/+$", "");
    }

    private Duration callTimeout(LipSyncRequest request) {
        Duration channelTimeout = Duration.ofMillis(request.timeoutMs());
        return channelTimeout.compareTo(CALL_TIMEOUT_CAP) < 0 ? channelTimeout : CALL_TIMEOUT_CAP;
    }

    /** 适配器无法解释的 2xx：厂商契约变了，不是调用方的错。 */
    private ProviderAdapter.ProviderException protocolFailure() {
        return new ProviderAdapter.ProviderException(
                HttpStatus.BAD_GATEWAY.value(), ERROR_INVALID_SUBMISSION);
    }

    /**
     * 翻译非 2xx 的厂商响应。先读响应体再失败：错误码正是渠道配错时
     * 运维需要看到的东西。
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
                        code = body.path("output").path("code").asText("");
                    }
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
}
