package com.yuzhihuihe.apiplatform.gateway.image;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 天翼 AI 网关（{@code ai.ctaigw.cn}）的图片生成，真实上游为
 * {@code doubao-seedream-5.0-pro}。
 *
 * <p><b>契约是同步的 OpenAI 兼容接口。</b>{@code POST {base}/v1/images/generations}，
 * 一次请求产出一次响应：
 *
 * <pre>
 * { "model": "doubao-seedream-5.0-pro",   // 取自 channel_model.upstream_model
 *   "prompt": "…", "image": "https://…",  // image 可选，单张，图生图输入
 *   "response_format": "url", "size": "2K", "stream": false,
 *   "watermark": true, "layer_decomposition": true }
 * </pre>
 *
 * 响应为 {@code {"created":…,"data":[{"url":"…"}]}}，同时容忍
 * {@code data[].b64_json} 与一个 {@code usage} 对象。与视频路径的关键差别是
 * **没有 task id、没有轮询、没有终态**：因此本适配器只发出恰好一次上游请求，
 * 内部绝不重试——该端点不接受幂等键，一次重试就是第二次付费生成。
 *
 * <p><b>为什么不发 {@code n}。</b>上游载荷保持为已确认的那组字段，不额外塞入一个
 * 厂商未确认接受的参数——若该端点拒绝未知字段，多发的每一个字段都会让整条链路失效。
 *
 * <p>而 2026-10-12 的直连实测把「干脆发过去试试」这条路也堵死了：厂商**受理但静默
 * 忽略** {@code n}——{@code n=2} 与 {@code n=4} 都回 HTTP 200、恰好一张图、
 * {@code usage.generated_images = 1}（见 {@code scripts/Probe-UpstreamImageCount.ps1}）。
 * 透传它不会报错，只会让客户按 N 张付费却拿到 1 张。因此 {@code n} 永不越过这条边界：
 * 一次请求要 N 张由**服务层并发扇出 N 次调用**实现
 * （{@code ImageGenerationService.generateFanout}），本适配器始终只买一张。
 *
 * <p><b>尺寸是客户可选的，但受档位约束。</b>{@code size} 既接受档位字符串
 * （pro 是 {@code 1K}/{@code 1.5K}/{@code 2K}，见 {@link #MODEL_TIERS}）也接受
 * {@code 宽x高}，原样发给厂商；服务层负责把它归约成档位并对照别名已售的档位，
 * 本适配器只校验该厂商是否提供这个档位。未列出的模型与未列出的档位一律拒绝，
 * 而不是猜测它的阶梯——把一个不受理的档位发上去，客户拿到的是一次失败，
 * 或一张比付费档位更差的图。
 *
 * <p><b>错误分类是稳定的字符串，绝不是厂商的原始文本。</b>非 2xx 与
 * 2xx 里的错误体都映射成 {@link ProviderAdapter.ProviderException}：
 * 前者带上厂商的错误码（它在渠道配错时正是运维需要看到的东西），
 * 后者按协议失败上报，因为一个以 200 返回错误体的厂商无法证明它没有接单。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class TianYiYunImageGenerationAdapter implements ImageGenerationAdapter {

    private static final Set<String> CAPABILITIES = Set.of("IMAGE_GEN");

    /**
     * 每个上游模型实际提供哪些尺寸档位。未列出的模型一律拒绝，
     * 而不是猜测它的阶梯。
     *
     * <p>{@code 1.5K} 由网关 V115 起进入这份受理清单与平台词表：厂商文档里 pro
     * 的档位本来就是 1K/1.5K/2K，此前只把 1.5K 放在「文档口径」里，结果是客户端
     * 读得到、提交必被拒——中间那一档客户买不到，而平台也没有任何一行费率卡
     * 可以给它计成本。
     *
     * <p><b>{@code 4K} 于 2026-10-13 被实测删除。</b>它此前只是「提交口径里声明了」
     * 的档位：{@code scripts/Probe-UpstreamImageSize.ps1} 用生产渠道凭证直连厂商
     * 逐个档位打真实调用（并下载返回图量真实边长），结果是 {@code size:"4K"} 被
     * 厂商以 {@code HTTP 400 InvalidParameter} 明确拒绝、{@code error.param = size}
     * （证据落盘在 {@code test-results/upstream-image-size-probe/}）。也就是说它不是
     * 「文档还没写」，而是**厂商根本不提供**；把一个不受理的档位留在受理清单里，
     * 只会让 {@code supportedSizeTiers} 多一个永远不可提交的项。
     *
     * <p>同一次实测确认了两件事：{@code 1.5K} 受理（HTTP 200），以及
     * **显式像素尺寸被逐位精确执行**（{@code 1536x864} 真的回 1536x864），
     * 因此目录下发的 {@code aspectRatioSizes} 是可靠的那条路。
     *
     * <p>还有一件事要说清楚：厂商的档位预设与平台的档位区间**不是同一把尺子**
     * （实测 {@code 1K} → 1152x864、{@code 1.5K} → 1872x1248、{@code 2K} → 2496x1664）。
     * 平台按**请求**归约出的档位计价与记账（{@link ImageGenerationService#sizeTier}），
     * 与厂商最终产出多少像素无关，因此这里不需要、也不应该为它做映射。
     */
    private static final Map<String, Set<String>> MODEL_TIERS = Map.of(
            "doubao-seedream-5.0-pro", Set.of("1K", "1.5K", "2K"));

    /**
     * 同一个模型在厂商**文档**里登记的档位与像素边界。
     *
     * <p>它们与 {@link #MODEL_TIERS} 分开保存，因为两者**可能**不一致，而把差异
     * 抹平在任何一边都是撒谎：{@code MODEL_TIERS} 是**提交**口径（准入判断读它，
     * 改它会让今天能用的档位明天被拒），文档口径是**厂商声称**的档位。目录把
     * 两边的差异报进 {@code parameters.operator}，运维据此决定是补一次实测
     * 还是改一个常量——这正是「不要让客户端去试」的意思。
     *
     * <p>2026-10-13 的实测把这两份常量收敛成了同一个集合（{@code 1K/1.5K/2K}）：
     * {@code 1.5K} 进了受理清单，{@code 4K} 被实测删除。它们仍然分开保存，
     * 因为回答的是两个不同的问题，将来也会再次分叉。
     *
     * <p>来源：Seedream 生图 API 的模型说明。{@code size} 有两种互斥写法——
     * 档位，或 {@code 宽x高}；pro 的档位是 1K/1.5K/2K，像素总量从
     * 1280x720 = 921600 起到 2048x2048x1.1025 = 4624220，宽高比 1/16–16；
     * 图层拆分只接受档位（默认 {@code auto}）。lite 是 2K/3K/4K 且边界不同，
     * 所以这些值按模型记录，而不是写成一组全局常量。
     */
    private static final Map<String, Set<String>> DOCUMENTED_SIZE_TIERS = Map.of(
            "doubao-seedream-5.0-pro", Set.of("1K", "1.5K", "2K"));

    /** 显式像素尺寸的像素总量下限：1280x720。仅 pro 已核对，见上。 */
    private static final long DOCUMENTED_MIN_PIXELS = 921_600L;

    /** 显式像素尺寸的像素总量上限：2048x2048x1.1025。 */
    private static final long DOCUMENTED_MAX_PIXELS = 4_624_220L;

    /** 显式像素尺寸的宽高比范围：1/16–16。 */
    private static final double DOCUMENTED_MIN_ASPECT_RATIO = 1.0 / 16.0;

    private static final double DOCUMENTED_MAX_ASPECT_RATIO = 16.0;

    private static final Set<String> RESPONSE_FORMATS = Set.of("url", "b64_json");

    private static final String API_ROOT = "/v1";
    /** 相对 API 根的生成路径：完整端点是 {@code /v1/images/generations}。 */
    private static final String GENERATE_PATH = "/images/generations";
    private static final String FULL_GENERATE_PATH = API_ROOT + GENERATE_PATH;

    /**
     * 一次图片生成是同步的：HTTP 调用的时长就是生成的时长。
     * 上限压在渠道预算之下，使一次挂死的厂商调用不会把请求
     * （以及它持有的预留）无限期占住。
     *
     * <p><b>它从 150 秒放宽到 240 秒，依据是 2026-10-12 的多参考图实测。</b>
     * 单图曾在 90 秒上限下被误杀（成功图生图实测 74 秒），据此放宽到 150；
     * 多参考图上线后再次实测：2 张 104 秒、3 张 <b>172 秒</b>、4 张 140 秒、
     * 6 张 150.8 秒、8 张 136.7 秒——150 秒会再次变成「在正常延迟上切一刀」。
     * 240 秒给观察到的最大值留出约 40% 余量；每一次误判的代价都不小：
     * 客户端拿到一次「结果未知」，额度要等恢复扫描才回来，
     * 而平台可能已经为那张图付过钱。
     *
     * <p>真正的保护来自**渠道预算**（{@code channel.timeout_ms}）与这里的上限
     * 取小值：运维可以把某条慢渠道调大，而上限保证一次配错（比如配成一小时）
     * 不会把连接和预留一起挂死。它必须保持**小于**
     * {@code ImageGenerationService.TASK_TTL}（8 分钟），否则恢复扫描会在一次
     * 仍在途中的调用身上动手。
     */
    private static final Duration CALL_TIMEOUT_CAP = Duration.ofSeconds(240);

    private static final Logger log = LoggerFactory.getLogger(TianYiYunImageGenerationAdapter.class);

    /**
     * 已经就「渠道预算低于同步生图所需」告警过的渠道与其配置值。
     *
     * <p>这是一条**一次性**告警，按渠道去重，因此每个进程对每处错误配置
     * 只留下恰好一行，不会把日志刷满。
     */
    private final Set<String> warnedShortBudgets = ConcurrentHashMap.newKeySet();

    /** 厂商错误分类，用于日志与支持的稳定字符串，而不是原始文本。 */
    private static final String ERROR_REJECTED = "provider_rejected_generation";
    private static final String ERROR_INVALID_RESPONSE = "provider_invalid_response";
    private static final String ERROR_RESPONSE_ERROR_BODY = "provider_error_response";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public TianYiYunImageGenerationAdapter(
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

    /**
     * 该模型在厂商文档里的契约边界（见 {@link #DOCUMENTED_SIZE_TIERS}）。
     *
     * <p>它让 {@code GET /v1/image-models} 能在提交之前把边界交给客户端：
     * 客户端据此渲染「清晰度」与「比例」，而不是把一个不受理的档位提交上来撞一次
     * 上游错误——生图每一次越界尝试都是一次已付费的调用。
     *
     * <p><b>2026-10-13 起提交口径与文档口径已经收敛</b>（都是 {@code 1K/1.5K/2K}），
     * 因此目录的 {@code parameters.operator} 里不会再出现尺寸档位缺口：
     * {@code 1.5K} 由 V115 补进受理清单，{@code 4K} 由实测（厂商 400 拒绝）删除。
     * 这两份常量若将来再次分叉，差异会自动回到 {@code operator} 里。
     */
    @Override
    public ImageSubmissionProfile submissionProfile(String upstreamModel) {
        String model = normalizedModel(upstreamModel);
        if (!MODEL_TIERS.containsKey(model)) {
            // 未记录的模型不猜它的边界：目录据此把它渲染成不可提交，
            // 而不是把「没人确认过」渲染成「平台能做的一切」。
            return ImageSubmissionProfile.unavailable();
        }
        return new ImageSubmissionProfile(
                MODEL_TIERS.get(model),
                DOCUMENTED_SIZE_TIERS.getOrDefault(model, Set.of()),
                // 显式像素尺寸被支持；但图层拆分只接受档位——拆出来的每一层
                // 都必须与它在原图里的比例一致，一个显式的宽x高在那里没有语义。
                true,
                DOCUMENTED_MIN_PIXELS,
                DOCUMENTED_MAX_PIXELS,
                DOCUMENTED_MIN_ASPECT_RATIO,
                DOCUMENTED_MAX_ASPECT_RATIO,
                ImageGenerationService.MAX_INPUT_IMAGES,
                true,
                false);
    }

    @Override
    public void validateSubmission(GenerationRequest request) {
        String model = normalizedModel(request.channel().upstreamModel());
        Set<String> offered = MODEL_TIERS.get(model);
        if (offered == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "unsupported_tianyi_image_model");
        }
        require(offered.contains(request.sizeTier()), "tianyi_image_size_not_supported");
        require(RESPONSE_FORMATS.contains(request.responseFormat()),
                "tianyi_image_response_format_not_supported");
        for (String inputImageUrl : request.inputImageUrls()) {
            if (inputImageUrl != null
                    && !inputImageUrl.isBlank()
                    && !isAbsoluteHttpUrl(inputImageUrl)) {
                // 厂商需要自己去拉这些图，因此每一张都必须是一个绝对 http(s) 位置；
                // 一个相对路径或一段 base64 只会在上游变成一次难懂的错误。
                throw invalidRequest("tianyi_image_input_url_not_supported");
            }
        }
    }

    @Override
    public Mono<GenerationResult> generate(GenerationRequest request) {
        return Mono.defer(() -> {
            validateSubmission(request);
            return client()
                    .post()
                    .uri(generateUri(request.channel()))
                    .headers(headers -> authorize(headers, request.channel()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(generatePayload(request))
                    .exchangeToMono(response -> response.statusCode().isError()
                            ? rejected(response)
                            : response.bodyToMono(JsonNode.class)
                                    .flatMap(body -> Mono.fromCallable(
                                                    () -> toResult(request, body))
                                            // 2xx 但没有可解析的图片是协议变更，
                                            // 而不是用户错误：按上游故障处理，
                                            // 而不是向客户上报一次成功却无图的生成。
                                            .onErrorMap(error -> error
                                                            instanceof ProviderAdapter.ProviderException
                                                            ? error
                                                            : protocolFailure()))
                                    // 空响应体同样无法交付：没有 data 就没有图片。
                                    .switchIfEmpty(Mono.error(protocolFailure())))
                    .timeout(callTimeout(request))
                    // 超时与连接失败是**上游**故障，不是网关自己的 500。
                    // 没有这一步，客户只看到「服务器内部错误」，既不知道问题在
                    // 上游，也不知道该不该用同一个幂等键重试。
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
     * <p>{@code model} 取自渠道的 {@code upstream_model}，绝不取自客户端：
     * 客户端提交的是网关别名，把它直接转发给厂商既会暴露目录，
     * 也会让一次别名改动绕不过去。
     *
     * <p>{@code prompt} 与 {@code image} 按需携带：图生图且要求图层拆分时
     * 提示词可以为空，此时不发空字符串（厂商无法区分「空提示词」与
     * 「没有提示词」，而后者才是图层拆分的语义）。
     */
    private JsonNode generatePayload(GenerationRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", normalizedModel(request.channel().upstreamModel()));
        if (request.prompt() != null && !request.prompt().isBlank()) {
            root.put("prompt", request.prompt());
        }
        List<String> inputImages = request.inputImageUrls();
        if (inputImages != null && !inputImages.isEmpty()) {
            if (inputImages.size() == 1) {
                // 单图维持既有字符串形状：厂商两种形状都接受（2026-10-10 实测），
                // 而字符串让已验收的单图契约保持不变。
                root.put("image", inputImages.get(0));
            } else {
                // 多参考图：数组形状。2026-10-10 实测厂商接受，
                // 并在 usage.input_images 中如实报出吃了几张。
                com.fasterxml.jackson.databind.node.ArrayNode images = root.putArray("image");
                inputImages.forEach(images::add);
            }
        }
        root.put("response_format", request.responseFormat());
        root.put("size", request.size());
        // 同步接口，绝不流式。
        root.put("stream", false);
        root.put("watermark", request.watermark());
        if (request.layerDecomposition()) {
            // 只在有意义时发送：它描述的是「把这张图拆成图层」，
            // 没有输入图时该字段没有对象。
            root.put("layer_decomposition", true);
        }
        return root;
    }

    /**
     * 把厂商响应映射为一次已完成的生成。
     *
     * <p>一个条目同时给出位置与内联数据时取位置：网关不镜像生成结果，
     * 而 base64 会让响应体大出三个数量级，两者对客户是同一张图。
     *
     * <p>计费张数优先采用厂商自报的 {@code usage}；成功但缺少该字段的响应
     * 同样产出了图片，因此回退到实际返回的张数，
     * 而不是报告零并把这次生成白送出去。
     */
    private GenerationResult toResult(GenerationRequest request, JsonNode body) {
        if (body == null || body.isNull()) {
            throw protocolFailure();
        }
        JsonNode error = body.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            // 200 携带错误体：厂商拒绝了这次生成，但它用 200 回答，
            // 因此无法证明它没有接单。按上游故障上报，预留保持不动。
            throw new ProviderAdapter.ProviderException(
                    HttpStatus.BAD_GATEWAY.value(),
                    ERROR_RESPONSE_ERROR_BODY + ":" + errorCode(error));
        }
        JsonNode data = body.path("data");
        if (!data.isArray()) {
            throw protocolFailure();
        }
        List<GeneratedImage> images = new ArrayList<>();
        for (JsonNode entry : data) {
            String url = text(entry, "url");
            if (url != null) {
                images.add(new GeneratedImage(url, null));
                continue;
            }
            String base64 = text(entry, "b64_json");
            if (base64 != null) {
                images.add(new GeneratedImage(null, base64));
            }
            // 既没有位置也没有内联数据的条目不是一张图片；整个响应里
            // 一张可用的都没有时，下面的空列表会按协议失败处理。
        }
        if (images.isEmpty()) {
            throw protocolFailure();
        }
        return new GenerationResult(images, billedImages(request, body, images.size()));
    }

    /**
     * 该次生成将据以计费的张数。
     *
     * <p><b>图层拆分是一次调用，因此是一个单位。</b>厂商对图层拆分只收一次钱：
     * 一张输入图进去、一层一层出来，而它自报的 {@code usage} 在那种调用上数的正是
     * **图层数**，因此完全可能合法地大于 1。按它结算就是对同一次调用重复收钱，
     * 所以这里直接回答 1。服务层在 {@code chargedImages} 上还有同一条判断——它读的是
     * 持久化在生成记录上的标记，覆盖重放与补偿路径——两处都答 1，是为了让「一次
     * 调用一个单位」在适配器与结算两侧同时成立，而不是只靠其中一侧。
     *
     * <p>其余情况：只有在厂商明确报告了一个正数时才采用它；报告零意味着
     * 「本次不计费」，而那与一次成功的生成相矛盾，因此回退到实际返回的张数。
     */
    private int billedImages(GenerationRequest request, JsonNode body, int returned) {
        if (request.layerDecomposition()) {
            return 1;
        }
        JsonNode usage = body.path("usage");
        if (!usage.isObject()) {
            return returned;
        }
        for (String field : new String[] {
                "generated_images", "images", "total_images", "image_count"}) {
            JsonNode value = usage.path(field);
            if (value.isNumber() && value.asInt() > 0) {
                return value.asInt();
            }
        }
        return returned;
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    /** 优先采用厂商自己的错误码，它比通用错误码具体得多。 */
    private String errorCode(JsonNode error) {
        String code = error.path("code").asText("");
        if (code.isBlank()) {
            code = error.path("type").asText("");
        }
        return code.isBlank() ? "unknown" : code;
    }

    private String normalizedModel(String upstreamModel) {
        return upstreamModel == null ? "" : upstreamModel.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isAbsoluteHttpUrl(String value) {
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        return candidate.startsWith("https://") || candidate.startsWith("http://");
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
                            mapped.value(), category, null, null, isContentPolicyRejection(code)));
                });
    }

    /**
     * 上游对「生成出来的内容」的内容安全拒绝。
     *
     * <p>2026-10-10 线上实锤：提示词包含版权角色（蜘蛛侠）时，厂商**先画完**
     * （约 1 分钟）再审核，然后返回
     * {@code OutputImageSensitiveContentDetected.PolicyViolation}。这类拒绝与
     * 「渠道配置错误」的 4xx 完全不同——改提示词就能通过。识别它，是为了让
     * {@code OpenAiErrorMapper} 给客户端一个可操作的稳定错误码，而不是和
     * 「上游故障」一起压成一句无法区分的 502。
     *
     * <p>判定收窄到厂商码里的这两个标记：宁可漏判（仍按普通拒绝处理），
     * 也不把一次渠道故障误判成「用户内容有问题」。
     */
    private static boolean isContentPolicyRejection(String upstreamCode) {
        return upstreamCode.contains("PolicyViolation")
                || upstreamCode.contains("SensitiveContentDetected");
    }

    /** 适配器无法解释的 2xx：厂商契约变了，不是调用方的错。 */
    private ProviderAdapter.ProviderException protocolFailure() {
        return new ProviderAdapter.ProviderException(
                HttpStatus.BAD_GATEWAY.value(), ERROR_INVALID_RESPONSE);
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
     * 原样复制的生成端点。后者会先被归约到它的父路径，
     * 因此完整的生成 URL 绝不会再拼接上另一个生成路径，
     * 也不会被当作 {@code .../images/generations/v1/images/generations}。
     */
    static String apiRoot(Channel channel) {
        String baseUrl = normalizedBaseUrl(channel);
        if (baseUrl.endsWith(FULL_GENERATE_PATH)) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - FULL_GENERATE_PATH.length());
        } else if (baseUrl.endsWith(GENERATE_PATH)) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - GENERATE_PATH.length());
        }
        return baseUrl.endsWith(API_ROOT) ? baseUrl : baseUrl + API_ROOT;
    }

    /** 解析厂商的生成端点，同时不丢弃运维显式配置的路径。 */
    static String generateUri(Channel channel) {
        String configured = normalizedBaseUrl(channel);
        return configured.endsWith(GENERATE_PATH)
                ? configured
                : apiRoot(channel) + GENERATE_PATH;
    }

    private static String normalizedBaseUrl(Channel channel) {
        return channel.baseUrl().replaceAll("/+$", "");
    }

    /**
     * 取「渠道预算」与上游调用上限的较小值，并在渠道预算**低于**该上限时告警一次。
     *
     * <p>取小值本身是设计：渠道预算是运维的旋钮，上限只是防止一次配错
     * （比如配成一小时）把连接和预留一起挂死。但生图是**同步**协议，
     * 这里的超时就是「这次生成允许多久」——而实测图生图要 74~120.7 秒。
     * 把渠道预算配成 60 秒，等于在正常延迟上切一刀，且后果是**静默**的：
     * 客户端拿到一次「结果未知」，额度要等 5 分钟的恢复扫描才回来，
     * 同一个幂等键此后永远只回 409 {@code image_generation_previously_failed}，
     * 而平台可能已经为那张图付过钱（见 V112 迁移）。渠道健康检查查不出这件事
     * ——它只做 DNS/TLS，不发 Authorization。
     *
     * <p>因此这里不改变取小值的语义，只让错误配置**自己喊出来**：
     * 每个渠道的每个预算值只告警一次，运维据此把 {@code channel.timeout_ms}
     * 调到 240000 以上。
     */
    private Duration callTimeout(GenerationRequest request) {
        Duration channelTimeout = Duration.ofMillis(request.timeoutMs());
        if (channelTimeout.compareTo(CALL_TIMEOUT_CAP) < 0
                && warnedShortBudgets.add(request.channel().id() + ":" + request.timeoutMs())) {
            log.warn("image_channel_budget_below_call_cap channelId={} configuredMs={} capMs={}",
                    request.channel().id(), request.timeoutMs(), CALL_TIMEOUT_CAP.toMillis());
        }
        return channelTimeout.compareTo(CALL_TIMEOUT_CAP) < 0 ? channelTimeout : CALL_TIMEOUT_CAP;
    }
}
