package com.yuzhihuihe.apiplatform.gateway.audio;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
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
 * 阿里云百炼（DashScope，华北2 北京）的语音转写。
 *
 * <p><b>这个适配器要跨越厂商的两套协议，而它们恰好对应需求的两种说法。</b>
 * 百炼把「语音识别」分成两个端点，返回的东西不一样：
 *
 * <table>
 *   <caption>两种模式</caption>
 *   <tr><th>模式</th><th>模型</th><th>端点</th><th>时长</th><th>时间戳</th></tr>
 *   <tr>
 *     <td>同步</td><td>{@code qwen-audio-3.0-asr-flash}</td>
 *     <td>{@code POST /services/aigc/multimodal-generation/generation}</td>
 *     <td><b>不返回</b></td><td><b>不返回</b></td>
 *   </tr>
 *   <tr>
 *     <td>异步</td><td>{@code qwen-audio-3.0-asr-flash-filetrans}</td>
 *     <td>{@code POST /services/audio/asr/transcription} + {@code GET /tasks/{id}}</td>
 *     <td>毫秒</td><td>句/词级毫秒</td>
 *   </tr>
 * </table>
 *
 * <p><b>需求文档写的是「提取口播、生成带时间戳的字幕」，并指名
 * {@code qwen-audio-3.0-asr-flash}。这两件事在百炼上是两个不同的模型。</b>
 * 上游的同步接口文档明确说明返回结构是
 * {@code {"output":{"output":{"sentence":{"text":"…"}},"text":"…"}}}，
 * 没有 {@code sentences} 数组、也没有 {@code original_duration_in_milliseconds}；
 * 时间戳只在 {@code -filetrans} 系列的「识别结果下载文件」里出现。
 * 因此本适配器按模型名分派：
 *
 * <ul>
 *   <li>模型名以 {@code filetrans} 结尾 → 异步路径，交回**真正的字幕**
 *       （句级起止毫秒）与厂商实测的音频时长，两者都用于计费与下游剪辑；</li>
 *   <li>其余（含需求点名的 {@code qwen-audio-3.0-asr-flash}）→ 同步路径，
 *       交回文本且 {@code segments} 为空，并且**不假装有字幕**。
 *       这种情况下的计费秒数取服务层的预留值——见下。</li>
 * </ul>
 *
 * <p>分派是**按模型名**而不是按一个开关，因为运维在渠道上改的是
 * {@code channel_model.upstream_model}：把 {@code -filetrans} 写上去就该拿到
 * 字幕，写回同步模型就该拿到文本。把这件事藏进配置而不是数据结构，
 * 会让「为什么没有字幕」变成一个需要读代码才能回答的问题。
 *
 * <p><b>计费秒数的来源，两种模式不同，且这是一个诚实的妥协。</b>
 * 异步路径的下载文件里有 {@code properties.original_duration_in_milliseconds}，
 * 那是厂商对音频本身唯一的权威度量，因此直接用它。同步路径的响应里
 * **没有任何时长字段**——上游只回报文本。此时回退到服务层的预留秒数，
 * 即「网关按客户声明的音频长度预留、并按同一长度结算」，而不是凭空发明一个
 * 时长。这样做的后果必须说清楚：**一次同步转写的结算金额取决于客户的声明，
 * 而不是厂商的度量**。要收紧它只有两条路——换用 {@code -filetrans}
 * 模型（推荐，它同时还给出字幕），或让网关自己探测音频时长后再预留。
 * 在此之前，这里的回退是唯一不会把时长算成零、也不会虚报一个数字的选择。
 *
 * <p><b>重复提交。</b>两个端点都不接受幂等键，一次重试的提交就是第二次付费转写，
 * 因此本适配器只发出恰好一次上游请求，内部绝不重试。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class BailianAudioTranscriptionAdapter implements AudioTranscriptionAdapter {

    private static final Set<String> CAPABILITIES = Set.of("AUDIO_TRANSCRIBE");

    /**
     * 每个上游模型走哪条路径，以及它是否真的交回时间戳。
     *
     * <p>未列出的模型一律拒绝，而不是猜测它属于哪一类：把一次同步模型的响应
     * 当成异步模型去解析，会得到「成功但没有字幕」的结果，
     * 而那正是需求里那件做不到的事——它必须在这里失败，而不是在下游被误读。
     */
    private static final Map<String, Boolean> MODELS_WITH_TIMESTAMPS = Map.of(
            "qwen-audio-3.0-asr-flash", false,
            "qwen-audio-3.1-asr-flash", false,
            "qwen-audio-3.0-asr-flash-filetrans", true,
            "qwen-audio-3.1-asr-flash-filetrans", true);

    private static final String API_ROOT = "/api/v1";
    /**
     * 天翼网关（{@code ai.ctaigw.cn}）用**同一套 DashScope 协议**，版本根却是
     * {@code /v1}——少一层 {@code /api}。逐字核对过：路径尾部、请求体、以及
     * {@code X-DashScope-Async} / {@code X-DashScope-SSE} 两个头都一模一样，
     * 价格也都是 0.00022 元/秒。因此适配器不需要第二份实现，只需要认识这个根。
     */
    private static final String V1_ROOT = "/v1";
    /** 各端点相对**版本根**的尾部；两个平台共用。 */
    private static final String SYNC_TAIL = "/services/aigc/multimodal-generation/generation";
    private static final String SUBMIT_TAIL = "/services/audio/asr/transcription";
    private static final String TASK_TAIL = "/tasks/";
    private static final String SYNC_PATH = API_ROOT + SYNC_TAIL;
    private static final String SUBMIT_PATH = API_ROOT + SUBMIT_TAIL;
    private static final String TASK_PATH = API_ROOT + TASK_TAIL;

    /**
     * 一次转写的最小 JSON 交互；真正的下载与识别在厂商侧。
     * 上限压在渠道预算之下，使一次挂死的调用不会把预留无限期占住。
     */
    private static final Duration CALL_TIMEOUT_CAP = Duration.ofSeconds(60);

    /** 轮询之间的间隔。转写是顺序批处理，空转的轮询只会白占连接。 */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    /** 厂商错误分类，用于日志与支持的稳定字符串，而不是原始文本。 */
    private static final String ERROR_REJECTED = "provider_rejected_transcription";
    private static final String ERROR_INVALID_RESPONSE = "provider_invalid_transcription";
    private static final String ERROR_ERROR_BODY = "provider_error_response";
    private static final String ERROR_TRANSCRIPTION_FAILED = "provider_transcription_failed";
    private static final String ERROR_UNKNOWN_TASK = "provider_task_unknown";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public BailianAudioTranscriptionAdapter(
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
    public void validateSubmission(TranscriptionRequest request) {
        String model = normalizedModel(request.channel().upstreamModel());
        if (!MODELS_WITH_TIMESTAMPS.containsKey(model)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "unsupported_bailian_asr_model");
        }
    }

    @Override
    public Mono<TranscriptionResult> transcribe(TranscriptionRequest request) {
        return Mono.defer(() -> {
            validateSubmission(request);
            String model = normalizedModel(request.channel().upstreamModel());
            return Boolean.TRUE.equals(MODELS_WITH_TIMESTAMPS.get(model))
                    ? transcribeAsynchronously(request, model)
                    : transcribeSynchronously(request, model);
        });
    }

    private WebClient client() {
        return webClientBuilder.build();
    }

    // -----------------------------------------------------------------------
    // 同步路径：只有文本，没有时长、没有时间戳
    // -----------------------------------------------------------------------

    /**
     * {@code qwen-audio-3.x-asr-flash} 的同步调用。
     *
     * <p>响应结构与标准多模态接口**不同**，这一点上游用加粗专门警告过：
     * 文本在 {@code output.text}，同时也在 {@code output.output.sentence.text}，
     * 而**没有** {@code choices}。两种形态都被读取，因为只认一种的话，
     * 厂商哪次收敛回标准形态就让每一次转写都变成协议失败。
     */
    private Mono<TranscriptionResult> transcribeSynchronously(
            TranscriptionRequest request, String model) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        ArrayNode messages = root.putObject("input").putArray("messages");
        ArrayNode content = messages.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "input_audio")
                .putObject("input_audio").put("data", request.audioUrl());
        ObjectNode parameters = root.putObject("parameters");
        parameters.put("format", request.audioFormat());
        parameters.put("sample_rate", request.sampleRate());

        return client()
                .post()
                .uri(syncUri(request.channel()))
                .headers(headers -> authorize(headers, request.channel()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(root)
                .exchangeToMono(response -> response.statusCode().isError()
                        ? rejected(response)
                        : response.bodyToMono(JsonNode.class)
                                .flatMap(body -> Mono.fromCallable(
                                                () -> toSyncResult(request, body))
                                        .onErrorMap(error -> error
                                                        instanceof ProviderAdapter.ProviderException
                                                        ? error
                                                        : protocolFailure()))
                                .switchIfEmpty(Mono.error(protocolFailure())))
                .timeout(callTimeout(request))
                // 超时与连接失败属于**上游**故障，而不是网关自己的 500。
                .onErrorMap(ProviderAdapter.ProviderException::isTransportFailure,
                        ProviderAdapter.ProviderException::ofTransport);
    }

    /**
     * 同步响应 → 结果。
     *
     * <p>计费秒数取预留值：上游这个端点的响应里没有任何时长字段，
     * 所以「客户声明了多长」是此刻唯一存在的长度。类注释里写明了这是妥协，
     * 也写明了收紧它的两条路。
     */
    private TranscriptionResult toSyncResult(TranscriptionRequest request, JsonNode body) {
        throwIfErrorBody(body);
        String text = firstNonBlank(
                body.path("output").path("text").asText(""),
                body.path("output").path("output").path("sentence").path("text").asText(""));
        if (text == null) {
            throw protocolFailure();
        }
        // 空字幕列表是这个模型的**事实**，不是解析失败：上游只回报文本。
        // 把它伪装成一个空字符串段落会让下游以为字幕存在但内容为空。
        return new TranscriptionResult(text, List.of(), request.reservedSeconds());
    }

    // -----------------------------------------------------------------------
    // 异步路径：交回真正的字幕与厂商实测的音频时长
    // -----------------------------------------------------------------------

    /**
     * {@code ...-filetrans} 的异步调用：提交任务、轮询、然后下载结果文件。
     *
     * <p>时间戳不在任务查询的响应里，而在 {@code transcription_url} 指向的
     * JSON 文件里。因此这里必须多下载一次——那是厂商的契约，不是多余的一步。
     * 该 URL 默认 24 小时有效，所以网关不缓存它，而是在终态时立刻取回。
     */
    private Mono<TranscriptionResult> transcribeAsynchronously(
            TranscriptionRequest request, String model) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.putObject("input").putArray("file_urls").add(request.audioUrl());
        ObjectNode parameters = root.putObject("parameters");
        if (!request.languageHints().isEmpty()) {
            ArrayNode hints = parameters.putArray("language_hints");
            request.languageHints().forEach(hints::add);
        }

        return submitAsync(request, root)
                .flatMap(taskId -> awaitTerminal(
                                request, taskId, maxPolls(request))
                        .flatMap(terminal -> Boolean.TRUE.equals(terminal.get("succeeded"))
                                ? downloadTranscription(request, terminal.get("url"))
                                : Mono.error(new ProviderAdapter.ProviderException(
                                        HttpStatus.BAD_GATEWAY.value(),
                                        ERROR_TRANSCRIPTION_FAILED + ":"
                                                + terminal.get("code")))));
    }

    /**
     * 轮询次数的下限，与声明秒数无关。
     *
     * <p>等待来自厂商的**排队与前置处理**，它与音频长度不成比例：一段 3 秒的音频
     * 同样可能排十几秒。{@code MIN_POLLS} × {@link #POLL_INTERVAL} ≈ 30 秒，
     * 足够覆盖一次正常的排队，而不足以让一次真挂死的调用长期占住预留
     * （预留本来就要等到终态才结算）。
     */
    private static final int MIN_POLLS = 15;

    /**
     * 轮询次数的上限：一个声明了 12 小时音频的请求不该让网关按小时轮询。
     * 上限换算过来是 20 分钟，远低于任何渠道超时预算。
     */
    private static final int MAX_POLLS = 600;

    /**
     * 允许的轮询次数：随预留秒数增长，但有独立的**下限**与上限。
     *
     * <p>轮询等的是厂商排队，不是音频播放，因此下限不能是 1 次：那样一次如实声明
     * 5 秒的转写会在厂商仍处于前置处理时耗尽预算，被判成
     * {@code provider_task_unknown}——任务落 FAILED、预留按「结果未知」保留。
     * 那套保守策略是为 5xx 与协议异常写的，用在「排队中」是错的：客户看到的是
     * 上游故障，而厂商马上就会给出结果。
     * 契约用例 {@code aShortDeclarationStillGetsEnoughPollsForTheVendorsQueue}
     * 钉住这条下限。
     */
    private int maxPolls(TranscriptionRequest request) {
        return Math.min(MAX_POLLS, Math.max(MIN_POLLS, request.reservedSeconds() / 10));
    }

    /** 提交异步转写任务，取回厂商的 task id。 */
    private Mono<String> submitAsync(TranscriptionRequest request, ObjectNode payload) {
        return client()
                .post()
                .uri(submitUri(request.channel()))
                .headers(headers -> authorize(headers, request.channel()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                // 这个端点靠请求头而不是请求体宣告异步；漏掉它会被当成同步调用，
                // 而同步调用不接受 file_urls。
                .header("X-DashScope-Async", "enable")
                .bodyValue(payload)
                .exchangeToMono(response -> response.statusCode().isError()
                        ? rejected(response)
                        : response.bodyToMono(JsonNode.class)
                                .flatMap(body -> Mono.justOrEmpty(
                                                text(body.path("output").path("task_id")))
                                        .switchIfEmpty(Mono.error(protocolFailure())))
                                .switchIfEmpty(Mono.error(protocolFailure())))
                .timeout(callTimeout(request))
                // 超时与连接失败属于**上游**故障，而不是网关自己的 500。
                .onErrorMap(ProviderAdapter.ProviderException::isTransportFailure,
                        ProviderAdapter.ProviderException::ofTransport);
    }

    /**
     * 轮询任务直到终态。
     *
     * <p>只对**显式终态**做决定。缺失或无法识别的状态不足以证明任务被拒绝，
     * 因此继续等待——与 {@code TianYiYunVideoGenerationAdapter} 的
     * {@code unknownHeldStatus} 是同一条规则。
     *
     * <p>轮询次数由预留秒数决定：一次转写的代价与音频长度成正比，
     * 而预留长度正是服务层对「这次调用最多该花多久」的判断。
     * 轮询用 {@code repeatWhenEmpty} 表达，而**不是**靠「抛一个超时异常再重试」：
     * 那样一来「还在跑」与「真的超时了」在代码里长得一样，而它们对预留的含义
     * 完全相反——前者要继续持有，后者要上报上游故障。
     *
     * @return 任务终态的中间表示（见 {@link #toTaskStatus}）
     */
    private Mono<Map<String, Object>> awaitTerminal(
            TranscriptionRequest request, String taskId, int remainingPolls) {
        return pollOnce(request, taskId)
                .flatMap(status -> {
                    if (Boolean.TRUE.equals(status.get("terminal"))) {
                        return Mono.just(status);
                    }
                    if (remainingPolls <= 1) {
                        // 用尽轮询预算仍未到终态：这是上游故障，不是「成功」。
                        // 预留随之释放，而不是无限期持有。
                        return Mono.error(new ProviderAdapter.ProviderException(
                                HttpStatus.GATEWAY_TIMEOUT.value(), ERROR_UNKNOWN_TASK));
                    }
                    return Mono.delay(POLL_INTERVAL).then(
                            awaitTerminal(request, taskId, remainingPolls - 1));
                });
    }

    /** 查询一次任务状态。 */
    private Mono<Map<String, Object>> pollOnce(
            TranscriptionRequest request, String taskId) {
        return client()
                .get()
                .uri(taskUri(request.channel(), taskId))
                .headers(headers -> authorize(headers, request.channel()))
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> response.statusCode().isError()
                        ? rejected(response)
                        : response.bodyToMono(JsonNode.class)
                                .map(this::toTaskStatus)
                                .defaultIfEmpty(unknownHeldStatus()))
                .timeout(callTimeout(request))
                // 超时与连接失败属于**上游**故障，而不是网关自己的 500。
                .onErrorMap(ProviderAdapter.ProviderException::isTransportFailure,
                        ProviderAdapter.ProviderException::ofTransport);
    }

    /**
     * 把一次任务查询映射成一个中间状态。
     *
     * @return 至少含 {@code terminal}；终态时还含 {@code succeeded} 与
     *         {@code url}（成功）或 {@code code}（失败）
     */
    private Map<String, Object> toTaskStatus(JsonNode body) {
        JsonNode output = body.path("output");
        String state = output.path("task_status").asText("").trim().toUpperCase(Locale.ROOT);
        return switch (state) {
            case "SUCCEEDED" -> Map.of(
                    "terminal", true,
                    "succeeded", true,
                    "url", firstResultTranscriptionUrl(output));
            case "FAILED" -> Map.of(
                    "terminal", true,
                    "succeeded", false,
                    "code", firstNonBlank(
                            output.path("code").asText(""), "provider_transcription_failed"));
            // 显式终态之外的一切都保持等待：缺失状态不能证明被拒绝。
            default -> unknownHeldStatus();
        };
    }

    private Map<String, Object> unknownHeldStatus() {
        return Map.of("terminal", false);
    }

    /**
     * 从任务终态里取出第一个子任务的 {@code transcription_url}。
     *
     * <p>一个任务可以带多个文件，也就有多个子任务结果。网关一次只提交一个
     * 文件，因此取第一个成功子任务的结果；全部未成功时按协议失败处理，
     * 而不是交付一份空转写。
     */
    private String firstResultTranscriptionUrl(JsonNode output) {
        JsonNode results = output.path("results");
        if (!results.isArray()) {
            return null;
        }
        for (JsonNode result : results) {
            String status = result.path("subtask_status").asText("").trim().toUpperCase(Locale.ROOT);
            String url = text(result.path("transcription_url"));
            if ("SUCCEEDED".equals(status) && url != null) {
                return url;
            }
        }
        return null;
    }

    /**
     * 下载识别结果文件并解析出字幕与音频时长。
     *
     * <p>这是唯一一次**非 API** 的 HTTP 调用：厂商把结果放在一个签名 URL 上。
     * 它必须带鉴权头吗？不需要——该 URL 自带签名。因此这里刻意**不**发
     * Authorization，免得把网关的凭证泄漏给一个对象存储域名。
     */
    private Mono<TranscriptionResult> downloadTranscription(
            TranscriptionRequest request, Object url) {
        if (!(url instanceof String location) || location.isBlank()) {
            return Mono.error(protocolFailure());
        }
        return client()
                .get()
                // 必须用 URI 而不是 String 重载：这个地址是**已签名**的，签名覆盖逐字的
                // 路径与查询串，而 String 重载会把它当 URI 模板再编码一次
                // （%3A → %253A），对象存储随即报 SignatureDoesNotMatch。
                // 那个错误返回 XML，网关解析不出错误码，最终表现为一条没有错误码的
                // provider_rejected_transcription —— 看起来像上游拒绝，实为自伤。
                .uri(URI.create(location))
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> response.statusCode().isError()
                        ? rejected(response)
                        : response.bodyToMono(JsonNode.class)
                                .flatMap(body -> Mono.fromCallable(
                                                () -> toAsyncResult(request, body))
                                        .onErrorMap(error -> error
                                                        instanceof ProviderAdapter.ProviderException
                                                        ? error
                                                        : protocolFailure()))
                                .switchIfEmpty(Mono.error(protocolFailure())))
                .timeout(callTimeout(request))
                // 超时与连接失败属于**上游**故障，而不是网关自己的 500。
                .onErrorMap(ProviderAdapter.ProviderException::isTransportFailure,
                        ProviderAdapter.ProviderException::ofTransport);
    }

    /**
     * 识别结果文件 → 结果。
     *
     * <p>计费秒数取 {@code properties.original_duration_in_milliseconds}：
     * 厂商按**输入音频时长**计费（费率是 0.00022 元/秒），而这是整个契约里
     * 唯一给出该时长的字段。取整采用向上取整，因为一次 3.834 秒的音频
     * 确实按 3.834 秒被收费，报告 3 秒会少收。
     */
    private TranscriptionResult toAsyncResult(TranscriptionRequest request, JsonNode body) {
        JsonNode transcripts = body.path("transcripts");
        if (!transcripts.isArray() || transcripts.isEmpty()) {
            throw protocolFailure();
        }
        StringBuilder text = new StringBuilder();
        List<Segment> segments = new ArrayList<>();
        for (JsonNode transcript : transcripts) {
            String whole = text(transcript.path("text"));
            if (whole != null) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(whole);
            }
            JsonNode sentences = transcript.path("sentences");
            if (sentences.isArray()) {
                for (JsonNode sentence : sentences) {
                    String sentenceText = text(sentence.path("text"));
                    if (sentenceText == null) {
                        continue;
                    }
                    segments.add(new Segment(
                            sentence.path("begin_time").asLong(0),
                            sentence.path("end_time").asLong(0),
                            sentenceText));
                }
            }
        }
        if (text.length() == 0) {
            throw protocolFailure();
        }

        long millis = body.path("properties")
                .path("original_duration_in_milliseconds").asLong(0);
        // 厂商没有报告时长时回退到预留值：一次成功的转写确实产生了费用，
        // 报告零会把这次调用白送出去（与 TianYiYunVideoGenerationAdapter 的
        // billedSeconds 同一条规则）。
        int billedSeconds = millis > 0
                ? (int) Math.ceil(millis / 1000.0)
                : request.reservedSeconds();
        return new TranscriptionResult(text.toString(), segments, billedSeconds);
    }

    // -----------------------------------------------------------------------
    // 共用
    // -----------------------------------------------------------------------

    /** 200 携带错误体：厂商拒绝了这次转写，但它用 200 回答，因此按上游故障上报。 */
    private void throwIfErrorBody(JsonNode body) {
        JsonNode error = body.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new ProviderAdapter.ProviderException(
                    HttpStatus.BAD_GATEWAY.value(),
                    ERROR_ERROR_BODY + ":" + firstNonBlank(
                            error.path("code").asText(""), "unknown"));
        }
        // 百炼另有一个顶层 code 形态（成功时为空字符串）。
        String code = body.path("code").asText("");
        if (!code.isBlank()) {
            throw new ProviderAdapter.ProviderException(
                    HttpStatus.BAD_GATEWAY.value(), ERROR_ERROR_BODY + ":" + code);
        }
    }

    private String normalizedModel(String upstreamModel) {
        return upstreamModel == null ? "" : upstreamModel.trim().toLowerCase(Locale.ROOT);
    }

    private String text(JsonNode node) {
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private void authorize(HttpHeaders headers, Channel channel) {
        if (channel.apiKey() != null && !channel.apiKey().isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + channel.apiKey());
        }
    }

    /**
     * 渠道 base_url 的**源站**部分：去掉尾部斜杠、去掉可能被整段粘进来的已知
     * 端点、去掉任务端点、再去掉版本根（{@code /api/v1} 或 {@code /v1}）。
     *
     * <p>运维可能存的是厂商源站、带版本的根，或者从文档里原样复制的端点，
     * 三种写法都必须归约到同一个源站。
     *
     * <p><b>返回的是不带版本根的前缀，这一点是本方法存在的全部理由。</b>
     * 拼 URL 时统一走 {@link #syncUri}/{@link #submitUri}/{@link #taskUri}：
     * 它们把 {@link #versionRoot} 与各端点的**尾部**拼起来。早先的实现把方向搞反了
     * （先把 {@code /api/v1} 补进前缀，再拼自带头部版本的完整路径），于是得到
     * {@code /api/v1/api/v1/services/...}——**每一次真实调用都 404**，
     * 而症状表现为 {@code provider_rejected_transcription:NotFound}，
     * 看上去像上游拒绝，其实是自己多拼了一层。这类错误不会在阅读时暴露，
     * 只有在请求真的被发出去（契约测试的桩服务器）时才现形。
     */
    static String apiOrigin(Channel channel) {
        String baseUrl = normalizedBaseUrl(channel);
        for (String tail : new String[] {SYNC_TAIL, SUBMIT_TAIL}) {
            if (baseUrl.endsWith(tail)) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - tail.length());
            }
        }
        int taskAt = baseUrl.indexOf(TASK_TAIL);
        if (taskAt >= 0) {
            baseUrl = baseUrl.substring(0, taskAt);
        }
        // 先长后短："/api/v1" 里含有 "/v1"，先判长的才不会只剥掉 "/v1"。
        if (baseUrl.endsWith(API_ROOT)) {
            return baseUrl.substring(0, baseUrl.length() - API_ROOT.length());
        }
        return baseUrl.endsWith(V1_ROOT)
                ? baseUrl.substring(0, baseUrl.length() - V1_ROOT.length())
                : baseUrl;
    }

    /**
     * 渠道用的版本根：{@code /api/v1}（百炼）或 {@code /v1}（天翼网关）。
     *
     * <p>从配置里认，而不是写死：百炼必须在 base_url 里带上 {@code /api/v1}，
     * 天翼的音频渠道必须带上 {@code /v1}。两者都不带（只存裸域名）时按百炼的老
     * 口径回退到 {@code /api/v1}——那正是既有渠道的写法，不能因为新增一个平台
     * 就把它们改坏。
     */
    static String versionRoot(Channel channel) {
        String configured = normalizedBaseUrl(channel);
        if (configured.contains(API_ROOT)) {
            return API_ROOT;
        }
        return configured.contains(V1_ROOT) ? V1_ROOT : API_ROOT;
    }

    /** 带版本的 API 根 {@code {origin}/api/v1} 或 {@code {origin}/v1}，用于日志与调用归属。 */
    static String apiRoot(Channel channel) {
        return apiOrigin(channel) + versionRoot(channel);
    }

    /**
     * 同步多模态端点。
     *
     * <p>百炼：{@code {origin}/api/v1/services/aigc/multimodal-generation/generation}；
     * 天翼：同一个尾部，版本根换成 {@code /v1}。
     */
    static String syncUri(Channel channel) {
        String configured = normalizedBaseUrl(channel);
        return configured.endsWith(SYNC_TAIL)
                ? configured
                : apiOrigin(channel) + versionRoot(channel) + SYNC_TAIL;
    }

    /** 异步提交端点：尾部是 {@code /services/audio/asr/transcription}。 */
    static String submitUri(Channel channel) {
        String configured = normalizedBaseUrl(channel);
        return configured.endsWith(SUBMIT_TAIL)
                ? configured
                : apiOrigin(channel) + versionRoot(channel) + SUBMIT_TAIL;
    }

    /** 任务查询端点：task id 走路径，不是查询串。 */
    static String taskUri(Channel channel, String taskId) {
        return apiOrigin(channel) + versionRoot(channel) + TASK_TAIL + taskId;
    }

    private static String normalizedBaseUrl(Channel channel) {
        return channel.baseUrl().replaceAll("/+$", "");
    }

    private Duration callTimeout(TranscriptionRequest request) {
        Duration channelTimeout = Duration.ofMillis(request.timeoutMs());
        return channelTimeout.compareTo(CALL_TIMEOUT_CAP) < 0 ? channelTimeout : CALL_TIMEOUT_CAP;
    }

    /** 适配器无法解释的 2xx：厂商契约变了，不是调用方的错。 */
    private ProviderAdapter.ProviderException protocolFailure() {
        return new ProviderAdapter.ProviderException(
                HttpStatus.BAD_GATEWAY.value(), ERROR_INVALID_RESPONSE);
    }

    /**
     * 翻译非 2xx 的厂商响应。
     *
     * <p>先读响应体再失败：错误码正是渠道配错时运维需要看到的东西，
     * 而 {@code WebClient} 的默认异常会丢弃这些，只留下一行光秃秃的状态。
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
}
