package com.yuzhihuihe.apiplatform.gateway.image;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionController;
import com.yuzhihuihe.apiplatform.gateway.openai.OpenAiErrorMapper;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 同步图片生成，形态照搬 OpenAI 的图片 API。
 *
 * <p>{@code POST /v1/images/generations} 一次请求产出一次响应：
 * {@code {"created":…,"data":[{"url":…}]}}，同时可以返回内联的
 * {@code b64_json}。之所以选择这种形态而不是给视频那套异步任务协议再造一个
 * 孪生体，是因为图片生成本来就是同步的——厂商在一次 HTTP 应答里给出图片——
 * 而客户端已经有兼容 OpenAI 的管道，改 base URL 就能接上。
 *
 * <p>认证、速率限制与模型允许列表由守卫 {@code /v1/chat/completions} 的
 * 同一个 {@link ApiKeyWebFilter} 施加；本控制器只负责把 HTTP 翻译成
 * {@link ImageGenerationService} 调用。
 *
 * <p>有两个 id 会流经本端点，它们并不是同一回事。网关自己的
 * {@code x-request-id} 是追踪 id，按每次 HTTP 调用生成。生成 id 才是计费身份：
 * 提供 {@code Idempotency-Key} 的客户端选择的也是它，因此一次超时后的重试
 * 会返回已经付过费的那次生成，而不是再买一次。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ImageGenerationController {

    static final String REQUEST_ID_HEADER = "x-request-id";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** 与 {@code image_generation_task.request_id} 匹配：更长的键无法被存储。 */
    private static final int MAX_REQUEST_ID_LENGTH = 128;

    private final ImageGenerationService service;

    ImageGenerationController(ImageGenerationService service) {
        this.service = service;
    }

    @PostMapping(path = "/v1/images/generations", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<Object>> generate(
            @RequestBody ImageRequest body, ServerWebExchange exchange) {
        String traceId = traceId(exchange);
        return Mono.defer(() -> {
                    ApiKeyService.ApiKeyContext apiKey = apiKey(exchange);
                    return service.generate(new ImageGenerationService.SubmitCommand(
                            requestId(exchange, traceId),
                            apiKey,
                            body == null ? null : body.model(),
                            body == null ? null : body.prompt(),
                            inputImages(body == null ? null : body.image()),
                            inputImageFileIds(body),
                            body == null ? null : body.size(),
                            imageCount(body == null ? null : body.n()),
                            body == null ? null : body.responseFormat(),
                            // 默认要求水印：AI 生成内容应当可被识别，
                            // 而厂商的默认值不由本网关的契约决定。
                            body == null || body.watermark() == null || body.watermark(),
                            body != null && Boolean.TRUE.equals(body.layerDecomposition())))
                            .map(view -> ResponseEntity.ok()
                                    .header(REQUEST_ID_HEADER, traceId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body((Object) response(view)));
                })
                .onErrorResume(error -> Mono.just(errorResponse(error, traceId)));
    }

    /**
     * 一次调用买几张：**1~4**，缺省一张。
     *
     * <p>上限是 {@link ImageGenerationService#MAX_FANOUT}，不是随手定的：2026-10-12
     * 直连上游实测，厂商**受理但静默忽略** {@code n}（{@code n=2} 与 {@code n=4}
     * 都回 200 + 恰好一张图），所以 N 张必须由网关自己并发买 N 次。4 这个数字来自
     * 超时预算：图生图单张实测 112~116 秒，4 路并发的墙钟约等于最慢那张，
     * 相对 240 秒上限余量约 124 秒；再高就没有余量了。
     *
     * <p>{@code n} 大于 4 仍然拒绝，而不是静默截断到 4：客户要 8 张、只拿到 4 张
     * 而没有任何提示，与今天"要 4 张只给 1 张"是同一种病。{@code n} 小于 1 同样拒绝：
     * 一个"买零张"的生成没有可交付的东西。
     *
     * <p>错误码保持 {@code image_count_not_supported} 不变——因为**原因**没变：
     * 仍然是"这个张数不支持"。客户端已有的按 code 分支不需要改。
     *
     * @throws ResponseStatusException 422 {@code image_count_not_supported}
     */
    private int imageCount(Integer n) {
        if (n == null) {
            return 1;
        }
        if (n < 1 || n > ImageGenerationService.MAX_FANOUT) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "image_count_not_supported");
        }
        return n;
    }

    /**
     * 解析 {@code image_file_id}（单个或数组），并在这里挡住「输入图有两个来源」。
     *
     * <p><b>冲突必须在预留之前拒绝。</b>同时给了 {@code image} 与
     * {@code image_file_id} 时，无论猜哪一个优先，猜错的那一次都已经按张付费，
     * 而客户拿到的不是他要的那张图——静默选一个比报错贵得多。
     *
     * <p>长度与形状的**语义**校验不在这里：这里只挡住明显不合法的输入，
     * 真正的「这个 id 是不是你的、是不是一张已扫描的图」由服务层回答。
     *
     * @throws ResponseStatusException 422 {@code image_input_conflict} /
     *         {@code image_input_count_exceeded} / {@code invalid_image_request}
     */
    private List<String> inputImageFileIds(ImageRequest body) {
        JsonNode fileIds = body == null ? null : body.imageFileId();
        if (fileIds == null || fileIds.isNull() || fileIds.isMissingNode()) {
            return List.of();
        }
        JsonNode image = body.image();
        if (image != null && !image.isNull() && !image.isMissingNode()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "image_input_conflict");
        }
        List<String> ids = new java.util.ArrayList<>();
        if (fileIds.isTextual()) {
            ids.add(validatedFileId(fileIds.asText()));
        } else if (fileIds.isArray()) {
            if (fileIds.size() == 0) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "invalid_image_request");
            }
            if (fileIds.size() > ImageGenerationService.MAX_INPUT_IMAGES) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "image_input_count_exceeded");
            }
            for (JsonNode entry : fileIds) {
                if (!entry.isTextual()) {
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "image_input_array_not_supported");
                }
                ids.add(validatedFileId(entry.asText()));
            }
        } else {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_image_request");
        }
        return List.copyOf(ids);
    }

    private String validatedFileId(String raw) {
        String candidate = raw.strip();
        if (candidate.length() > ImageGenerationService.MAX_IMAGE_FILE_ID_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_image_request");
        }
        return candidate;
    }

    private ImageResponse response(ImageGenerationService.GenerationView view) {
        return new ImageResponse(
                view.created(),
                view.data().stream()
                        .map(image -> new ImageData(image.url(), image.b64Json()))
                        .toList(),
                // 只有真的少交付时才带上这个标记：单图响应因此与今天逐字节相同。
                view.partial() ? Boolean.TRUE : null);
    }

    private ApiKeyService.ApiKeyContext apiKey(ServerWebExchange exchange) {
        ApiKeyService.ApiKeyContext apiKey =
                exchange.getAttribute(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE);
        if (apiKey == null) {
            // 过滤器通常会在到达此处之前拒绝未认证的调用；
            // 此处为 null 意味着路由配置错误，因此 fail-closed，
            // 而不是放行一次匿名生成。
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_api_key");
        }
        return apiKey;
    }

    private String traceId(ServerWebExchange exchange) {
        return exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, "req_" + UUID.randomUUID());
    }

    /**
     * 生成 id：提供了客户端幂等键时用它，否则用网关的追踪 id。
     *
     * <p>该键是可选的，因为只调用一次的客户端没有什么需要重放；
     * 而它被限制在列宽之内、而不是被截断：两个不同的键若截断成同一个值，
     * 就会互相重放对方的生成。
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

    /**
     * 把请求体里的 {@code image} 解析成 1~8 个输入图 URL。
     *
     * <p>2026-10-10 实测：上游（天翼云 doubao-seedream-5.0-pro）接受 {@code image}
     * 为 URL 数组并在 {@code usage.input_images} 中如实报出张数，因此多参考图生图
     * 是真实支持的能力。字符串仍被接受（等价于单元素数组）；超过
     * {@link ImageGenerationService#MAX_INPUT_IMAGES} 张在网关侧拒绝，
     * 而不是把超量请求交给厂商去给一句无法分支的错误。
     *
     * @throws ResponseStatusException 422 {@code image_input_count_exceeded} /
     *         {@code image_input_array_not_supported} / {@code invalid_image_request}
     */
    private List<String> inputImages(JsonNode image) {
        if (image == null || image.isNull() || image.isMissingNode()) {
            return List.of();
        }
        if (image.isTextual()) {
            return List.of(image.asText());
        }
        if (image.isArray()) {
            if (image.size() == 0) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "invalid_image_request");
            }
            if (image.size() > ImageGenerationService.MAX_INPUT_IMAGES) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "image_input_count_exceeded");
            }
            List<String> urls = new java.util.ArrayList<>(image.size());
            for (JsonNode entry : image) {
                if (!entry.isTextual()) {
                    // 数组元素不是 URL 字符串（例如嵌套对象）：这种形状仍不支持，
                    // 保留既有错误码，让按 code 分支的老客户端语义不变。
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "image_input_array_not_supported");
                }
                urls.add(entry.asText());
            }
            return List.copyOf(urls);
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_image_request");
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
     * 请求体，字段名照搬 OpenAI 图片 API。
     *
     * @param model 逻辑生图别名，例如 {@code image-basic}
     * @param prompt 生成提示词；图生图且要求图层拆分时可以为空
     * @param image 可选的图生图输入：单个 URL 字符串，或 1~8 个 URL 的数组
     *        （多参考图生图）。调用方必须能提供**厂商拉得取的公网 http(s) 地址**
     * @param imageFileId 可选的图生图输入，**网关自己签发的上传文件 id**
     *        （{@code POST /v1/files} 返回的 {@code id}），单个或 1~8 个的数组。
     *        与 {@code image} 二选一：同时给会被 422 {@code image_input_conflict} 拒绝。
     *        它让桌面端不必自备公网图床——网关把 id 换成短时效的公网地址，
     *        厂商拉取的仍然是同一个形状的地址
     * @param size 可选尺寸：{@code 1K}/{@code 1.5K}/{@code 2K}/{@code 4K} 或 {@code 宽x高}，
     *        默认 {@code 2K}。可提交的档位以 {@code GET /v1/image-models} 的
     *        {@code parameters.submittableSizeTiers} 为准（更便宜的档位合法，更贵的被拒）
     * @param n 省略或 1~4：**一次请求买几张**。网关会并发扇出 N 次厂商调用
     *        （厂商侧实测静默忽略 {@code n}，见 {@link ImageGenerationService#MAX_FANOUT}），
     *        因此这一次 HTTP 请求的等待时间约等于**最慢的那一张**，而不是 N 张累加。
     *        {@code n} 为 0、负数或大于 4 时 422 {@code image_count_not_supported}。
     *        <b>允许部分成功</b>：成功几张交付几张、失败的逐张退款，
     *        因此 {@code data} 可能短于 {@code n}，响应里的 {@code partial}
     *        会把这件事明确告知
     * @param responseFormat {@code url} 或 {@code b64_json}，默认 {@code url}
     * @param watermark 是否要求厂商加水印，默认是
     * @param layerDecomposition 是否要求图层拆分；仅在提供了**恰好一张**输入图且没有
     *        提示词时有意义。它按**次**计费：一次调用一个单位，与返回多少个图层无关
     */
    record ImageRequest(
            String model,
            String prompt,
            JsonNode image,
            @JsonProperty("image_file_id") JsonNode imageFileId,
            String size,
            Integer n,
            @JsonProperty("response_format") String responseFormat,
            Boolean watermark,
            @JsonProperty("layer_decomposition") Boolean layerDecomposition) { }

    /**
     * OpenAI 形态的响应。
     *
     * @param created 生成的开始时刻（unix 秒）
     * @param data 生成的图片；每个条目恰好携带 {@code url} 或 {@code b64_json}
     * @param partial 交付的张数是否少于 {@code n}。只在扇出（{@code n > 1}）且
     *        有图片没能交付时为 true：那时 {@code data} 里是已经成功并已计费的那些，
     *        缺的那几张要么已退款、要么结果未知。缺省不序列化，因此单图响应
     *        与今天逐字节相同
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ImageResponse(long created, List<ImageData> data, Boolean partial) { }

    /** 两个字段互斥，因此缺省的那个不会被序列化成 null。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ImageData(
            @JsonProperty("url") String url,
            @JsonProperty("b64_json") String b64Json) { }
}
