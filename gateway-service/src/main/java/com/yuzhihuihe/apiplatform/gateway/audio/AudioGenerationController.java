package com.yuzhihuihe.apiplatform.gateway.audio;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionController;
import com.yuzhihuihe.apiplatform.gateway.openai.OpenAiErrorMapper;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 音频生成的两个同步端点：语音转写与配音合成。
 *
 * <p>路径是网关形态而不是 OpenAI 形态（{@code /v1/audio/recognitions}、
 * {@code /v1/audio/syntheses}），与 {@code /v1/images/generations} 和
 * {@code /v1/videos} 保持一致：这两个能力在 OpenAI 的接口族里没有对应物，
 * 硬套一个形似的名字只会让客户端以为可以拿别的 SDK 直接接上。
 *
 * <p>认证、速率限制与模型允许列表由守卫 {@code /v1/chat/completions} 的
 * 同一个 {@link ApiKeyWebFilter} 施加；本控制器只负责把 HTTP 翻译成
 * {@link AudioGenerationService} 调用。
 *
 * <p><b>两个 id 不是同一回事</b>，与生图端点相同。网关自己的
 * {@code x-request-id} 是追踪 id，按每次 HTTP 调用生成；**计费身份**是
 * {@code Idempotency-Key}，客户端提供时用它。一次超时后的重试因此会返回
 * 已经付过费的那次调用，而不是再买一次——这条规则对同步的音频调用同样必要，
 * 因为一次超时的转写可能已经在上游被计费。
 *
 * <p><b>为什么转写要求客户声明音频秒数。</b>按秒计费的维度无法像按张那样
 * 知道这次会花几个单位：音频有多长只有音频自己知道，而网关此刻并不下载它
 * （厂商给的是 URL，厂商自己去拉）。因此 {@code durationSeconds} 是必填的，
 * 它同时是预留上限与结算上限。声明得更长只会多预留一阵子，差额在结算时释放；
 * 声明得更短会让一次调用的成本超出预留，而结算以预留为硬上限，
 * 因此那是**少收**的方向——网关宁可少收一次，也不让一次谎报把套餐扣穿。
 *
 * <p>合成不需要客户声明时长：文案长度是网关手里的信息，预留由它与费率卡
 * 共用的那一个字符→秒常量算出。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class AudioGenerationController {

    static final String REQUEST_ID_HEADER = "x-request-id";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** 与 {@code audio_generation_task.request_id} 匹配：更长的键无法被存储。 */
    private static final int MAX_REQUEST_ID_LENGTH = 128;

    private final AudioGenerationService service;

    AudioGenerationController(AudioGenerationService service) {
        this.service = service;
    }

    @PostMapping(path = "/v1/audio/recognitions", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<Object>> recognize(
            @RequestBody RecognitionRequest body, ServerWebExchange exchange) {
        String traceId = traceId(exchange);
        return Mono.defer(() -> {
                    ApiKeyService.ApiKeyContext apiKey = apiKey(exchange);
                    return service.transcribe(new AudioGenerationService.TranscribeCommand(
                            apiKey,
                            requestId(exchange, traceId),
                            body == null ? null : body.model(),
                            body == null ? null : body.audioUrl(),
                            body == null ? null : body.format(),
                            body == null ? null : body.sampleRate(),
                            body == null ? null : body.languageHints(),
                            body == null || body.durationSeconds() == null
                                    ? 0 : body.durationSeconds()))
                            .map(view -> ResponseEntity.ok()
                                    .header(REQUEST_ID_HEADER, traceId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body((Object) recognitionResponse(view)));
                })
                .onErrorResume(error -> Mono.just(errorResponse(error, traceId)));
    }

    @PostMapping(path = "/v1/audio/syntheses", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<Object>> synthesize(
            @RequestBody SynthesisRequest body, ServerWebExchange exchange) {
        String traceId = traceId(exchange);
        return Mono.defer(() -> {
                    ApiKeyService.ApiKeyContext apiKey = apiKey(exchange);
                    return service.synthesize(new AudioGenerationService.SynthesizeCommand(
                            apiKey,
                            requestId(exchange, traceId),
                            body == null ? null : body.model(),
                            body == null ? null : body.text(),
                            body == null ? null : body.voice(),
                            body == null ? null : body.languageType(),
                            body == null ? null : body.format()))
                            .map(view -> ResponseEntity.ok()
                                    .header(REQUEST_ID_HEADER, traceId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body((Object) synthesisResponse(view)));
                })
                .onErrorResume(error -> Mono.just(errorResponse(error, traceId)));
    }

    /**
     * 转写响应。
     *
     * <p>{@code segments} 为空意味着**这个模型不产出字幕**，而不是「字幕内容
     * 为空」。这条区别是产品可见的：需求点名的
     * {@code qwen-audio-3.0-asr-flash} 走同步端点，它只回报文本；
     * 要拿到带时间戳的字幕必须换成 {@code -filetrans} 系列的模型
     * （见 {@link BailianAudioTranscriptionAdapter} 的类注释）。
     */
    private RecognitionResponse recognitionResponse(
            AudioGenerationService.TranscriptionView view) {
        return new RecognitionResponse(
                view.requestId(),
                view.text(),
                view.segments().stream()
                        .map(segment -> new SegmentData(
                                segment.beginTimeMs(), segment.endTimeMs(), segment.text()))
                        .toList(),
                view.hasTimestamps(),
                view.chargedSeconds());
    }

    private SynthesisResponse synthesisResponse(AudioGenerationService.SynthesisView view) {
        return new SynthesisResponse(
                view.requestId(),
                view.audioUrl(),
                view.chargedSeconds(),
                // 这个端点从不回报产出音频的时长，因此本次计费**总是**估算值。
                // 把它交给调用方，是为了让一条估算计费在运营报表里能与将来的
                // 实测计费区分开，而不是混进同一列数字里。
                view.estimatedDuration());
    }

    /**
     * 提交一次口型替换作业。
     *
     * <p><b>这个端点是异步的，因此它成功返回的未必是结果。</b>202 之后客户拿到
     * 的是一个已受理的作业，用 {@code GET /v1/video-lipsyncs/{requestId}} 轮询。
     * 之所以不像 {@code /v1/images/generations} 那样同步等待，是因为厂商自己的
     * 契约就是提交 + 轮询（且每账号只允许 1 个作业在跑），把它包装成一次
     * 阻塞调用只会让网关的每一个连接都停在那里等。
     *
     * <p>用 202 而不是 200：它表达的是「已受理、尚未完成」，而客户端据此知道
     * 该去轮询。对一次已经完成的作业（重放）仍然返回 202 并带上终态，
     * 因为状态字段才是权威，状态码在这里不重要。
     */
    @PostMapping(path = "/v1/video-lipsyncs", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<Object>> submitLipSync(
            @RequestBody LipSyncRequest body, ServerWebExchange exchange) {
        String traceId = traceId(exchange);
        return Mono.defer(() -> {
                    ApiKeyService.ApiKeyContext apiKey = apiKey(exchange);
                    return service.submitLipSync(new AudioGenerationService.LipSyncCommand(
                            apiKey,
                            requestId(exchange, traceId),
                            body == null ? null : body.model(),
                            body == null ? null : body.videoUrl(),
                            body == null ? null : body.audioUrl(),
                            body == null ? null : body.referenceImageUrl(),
                            body != null && Boolean.TRUE.equals(body.extendVideo()),
                            body == null || body.videoSeconds() == null
                                    ? 0 : body.videoSeconds(),
                            body == null || body.audioSeconds() == null
                                    ? 0 : body.audioSeconds()))
                            .map(view -> ResponseEntity.status(HttpStatus.ACCEPTED)
                                    .header(REQUEST_ID_HEADER, traceId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body((Object) lipSyncResponse(view)));
                })
                .onErrorResume(error -> Mono.just(errorResponse(error, traceId)));
    }

    @GetMapping("/v1/video-lipsyncs/{requestId}")
    Mono<ResponseEntity<Object>> lipSyncStatus(
            @PathVariable String requestId, ServerWebExchange exchange) {
        String traceId = traceId(exchange);
        return Mono.defer(() -> {
                    ApiKeyService.ApiKeyContext apiKey = apiKey(exchange);
                    return service.lipSyncStatus(apiKey, requestId)
                            .map(view -> ResponseEntity.ok()
                                    .header(REQUEST_ID_HEADER, traceId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body((Object) lipSyncResponse(view)));
                })
                .onErrorResume(error -> Mono.just(errorResponse(error, traceId)));
    }

    /**
     * 解析一次已完成作业的可交付位置，以 302 指向它。
     *
     * <p>与视频路径 {@code GET /v1/videos/{id}/content} 相同：客户端拿到的是
     * **网关自己**的这个地址，而不是厂商的签名 URL。厂商的位置可能绑定平台的
     * 凭证，也可能过期（VideoRetalk 的位置 24 小时失效），两者都不该成为
     * 客户可见的契约。
     */
    @GetMapping("/v1/video-lipsyncs/{requestId}/content")
    Mono<ResponseEntity<Object>> lipSyncContent(
            @PathVariable String requestId, ServerWebExchange exchange) {
        String traceId = traceId(exchange);
        return Mono.defer(() -> {
                    ApiKeyService.ApiKeyContext apiKey = apiKey(exchange);
                    return service.lipSyncContentUrl(apiKey, requestId)
                            .map(location -> ResponseEntity.status(HttpStatus.FOUND)
                                    .header(HttpHeaders.LOCATION, location)
                                    .header(REQUEST_ID_HEADER, traceId)
                                    // 厂商位置会过期，因此这个重定向绝不能被缓存：
                                    // 一个被缓存的 302 会把客户指向一个已经失效的地址。
                                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                                    .body((Object) null));
                })
                .onErrorResume(error -> Mono.just(errorResponse(error, traceId)));
    }

    private LipSyncResponse lipSyncResponse(AudioGenerationService.LipSyncView view) {
        return new LipSyncResponse(
                view.requestId(),
                view.state(),
                // 客户可见的交付地址永远是网关自己的内容端点；
                // 厂商的签名位置留在网关之后（见 lipSyncContent）。
                view.outputUrl() == null
                        ? null
                        : "/v1/video-lipsyncs/" + view.requestId() + "/content",
                view.chargedSeconds(),
                view.errorCategory());
    }

    private ApiKeyService.ApiKeyContext apiKey(ServerWebExchange exchange) {
        ApiKeyService.ApiKeyContext apiKey =
                exchange.getAttribute(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE);
        if (apiKey == null) {
            // 过滤器通常会在到达此处之前拒绝未认证的调用；
            // 此处为 null 意味着路由配置错误，因此 fail-closed，
            // 而不是放行一次匿名音频调用。
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_api_key");
        }
        return apiKey;
    }

    private String traceId(ServerWebExchange exchange) {
        return exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, "req_" + UUID.randomUUID());
    }

    /**
     * 计费身份：提供了客户端幂等键时用它，否则用网关的追踪 id。
     *
     * <p>该键被限制在列宽之内、而不是被截断：两个不同的键若截断成同一个值，
     * 就会互相重放对方的调用。
     */
    private String requestId(ServerWebExchange exchange, String traceId) {
        String supplied = exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
        if (supplied == null || supplied.isBlank()) {
            return traceId;
        }
        String key = supplied.strip();
        if (key.length() > MAX_REQUEST_ID_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_idempotency_key");
        }
        return key;
    }

    private ResponseEntity<Object> errorResponse(Throwable error, String traceId) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(error);
        return ResponseEntity.status(descriptor.status())
                .headers(OpenAiErrorMapper.retryHeaders(error))
                .header(REQUEST_ID_HEADER, traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatCompletionController.OpenAiErrorResponse(
                        new ChatCompletionController.OpenAiError(
                                descriptor.message(), descriptor.type(), descriptor.code()),
                        traceId));
    }

    /**
     * 转写请求体。
     *
     * @param model 逻辑音频别名，例如 {@code audio-asr-basic}
     * @param audioUrl 音频的公网可访问位置；厂商自己去拉它，因此必须是绝对 http(s)
     * @param format 容器格式提示，缺省 {@code wav}
     * @param sampleRate 采样率提示，缺省 {@code 16000}
     * @param languageHints 期望的语种提示，可为空
     * @param durationSeconds **必填**的音频秒数，见类注释
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecognitionRequest(
            @JsonProperty("model") String model,
            @JsonProperty("audio_url") String audioUrl,
            @JsonProperty("format") String format,
            @JsonProperty("sample_rate") String sampleRate,
            @JsonProperty("language_hints") List<String> languageHints,
            @JsonProperty("duration_seconds") Integer durationSeconds) { }

    /**
     * 合成请求体。
     *
     * @param model 逻辑音频别名，例如 {@code audio-tts-basic}
     * @param text 要合成的文案
     * @param voice 厂商音色名。**必填**，因为「默认音色」是厂商的实现细节，
     *        把它留在网关侧意味着一次厂商改动就能让所有客户的配音换人
     * @param languageType 语种提示，可为空（交给厂商自动判断）
     * @param format 期望的容器格式，缺省 {@code wav}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SynthesisRequest(
            @JsonProperty("model") String model,
            @JsonProperty("text") String text,
            @JsonProperty("voice") String voice,
            @JsonProperty("language_type") String languageType,
            @JsonProperty("format") String format) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecognitionResponse(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("text") String text,
            @JsonProperty("segments") List<SegmentData> segments,
            @JsonProperty("has_timestamps") boolean hasTimestamps,
            @JsonProperty("charged_seconds") int chargedSeconds) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SegmentData(
            @JsonProperty("begin_time_ms") long beginTimeMs,
            @JsonProperty("end_time_ms") long endTimeMs,
            @JsonProperty("text") String text) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SynthesisResponse(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("audio_url") String audioUrl,
            @JsonProperty("charged_seconds") int chargedSeconds,
            @JsonProperty("estimated_duration") boolean estimatedDuration) { }

    /**
     * 口型替换请求体。
     *
     * @param model 逻辑音频别名，例如 {@code audio-retalk}
     * @param videoUrl 人物视频位置；厂商自己去拉它
     * @param audioUrl 配音音频位置
     * @param referenceImageUrl 可选的人脸参考图，用于视频中有多张人脸时指定目标
     * @param extendVideo 音频比视频长时是否扩展画面；{@code false} 时厂商截断音频
     * @param videoSeconds 视频秒数，**必填**：厂商按生成视频的秒数计费，
     *        而网关要预留它；网关不下载媒体，因此只有客户知道这个数
     * @param audioSeconds 音频秒数，用途同上。开启扩展时预留取它，
     *        否则取两者中较短的一方（与厂商的截断行为一致）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LipSyncRequest(
            @JsonProperty("model") String model,
            @JsonProperty("video_url") String videoUrl,
            @JsonProperty("audio_url") String audioUrl,
            @JsonProperty("reference_image_url") String referenceImageUrl,
            @JsonProperty("extend_video") Boolean extendVideo,
            @JsonProperty("video_seconds") Integer videoSeconds,
            @JsonProperty("audio_seconds") Integer audioSeconds) { }

    /**
     * 口型替换的状态响应。
     *
     * @param state 归一化状态：{@code SUBMITTED} / {@code RUNNING} 表示还要继续
     *        轮询，{@code SUCCEEDED} / {@code FAILED} / {@code CANCELLED} 是终态
     * @param contentUrl 客户可取内容的网关地址；非成功时为 null。
     *        它是**网关自己**的路径，不是厂商的签名地址
     * @param errorCategory 终态为失败时的稳定分类
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LipSyncResponse(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("state") String state,
            @JsonProperty("content_url") String contentUrl,
            @JsonProperty("charged_seconds") int chargedSeconds,
            @JsonProperty("error_category") String errorCategory) { }
}
