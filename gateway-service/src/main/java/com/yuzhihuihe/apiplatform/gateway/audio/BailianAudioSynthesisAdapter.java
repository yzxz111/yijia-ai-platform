package com.yuzhihuihe.apiplatform.gateway.audio;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
 * 阿里云百炼（DashScope，华北2 北京）的语音合成。
 *
 * <p><b>契约是同步的多模态生成端点</b>：
 * {@code POST {root}/services/aigc/multimodal-generation/generation}，请求体为
 * {@code {"model":…,"input":{"text":…,"voice":…,"language_type":…}}}，
 * 响应为：
 *
 * <pre>
 * { "output": { "audio": { "url": "https://…wav", "data": "", "id": "audio_…",
 *                          "expires_at": 1766113409 } },
 *   "usage": { "characters": 195 } }
 * </pre>
 *
 * <p><b>计费秒数从哪来，是这个适配器唯一真正需要想清楚的事。</b>
 * 这个端点的 {@code usage} 回报的是 **字符数**，不是音频时长——而网关按秒计价
 * 与结算（{@code AUDIO_SECOND}，见 V92）。两条路可走：
 *
 * <ol>
 *   <li>把 {@code usage.characters} 按 {@link
 *       AudioSynthesisAdapter#estimateSecondsFromCharacters} 折成秒数。
 *       这与费率卡的成本基准（1 元/万字符按同一比例折成 0.02 分/秒）
 *       **用的是同一个常量**，因此毛利不会随文案密度漂移。
 *   <li>取回音频后自己探测时长。那需要一次下载加一次解码，
 *       而网关此刻并不持有音频字节（厂商给的是 URL）。
 * </ol>
 *
 * <p>这里采用第一条，并把 {@code estimatedDuration = true} 交给调用方，
 * 因此每一笔估算计费都能在运营报表里与实测计费区分开。这个选择是刻意的：
 * 一条无法与实测区分的估算计费，会让「为什么这个月的毛利不对」变成一个
 * 无法回答的问题。
 *
 * <p><b>图层那种「一次调用一个单位」的诱惑在这里不存在。</b>厂商按字符收费，
 * 字符越多越贵，因此按折算秒数结算与厂商的成本是同向的——不存在
 * {@code TianYiYunImageGenerationAdapter} 里图层拆分那种「一次调用无论产出
 * 多少层都只收一次」的结构，也就没有对应的特殊分支。
 *
 * <p><b>重复提交。</b>该端点不接受幂等键，一次重试就是第二次付费合成，
 * 因此本适配器只发出恰好一次上游请求，内部绝不重试。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class BailianAudioSynthesisAdapter implements AudioSynthesisAdapter {

    private static final Set<String> CAPABILITIES = Set.of("AUDIO_SYNTHESIS");

    /**
     * 已确认可用的上游模型。未列出的模型一律拒绝，而不是猜测它的响应形态——
     * 一个猜错的模型名只会浪费一次付费调用，并在上游留下一句与配置无关的错误。
     */
    private static final Set<String> MODELS = Set.of(
            "qwen-audio-3.0-tts-flash",
            "qwen-audio-3.1-tts-flash",
            "qwen3-tts-flash");

    /**
     * 公网百炼 {@code qwen3-tts-flash}（非实时 Qwen3-TTS-Flash）的内置音色清单。
     *
     * <p>它来自厂商音色列表页「非实时语音合成」表格里标注支持
     * {@code qwen3-tts-flash} 的行（2026-10 快照），是**展示清单**而非校验
     * 白名单。把它硬编码在这里是刻意的：音色是厂商的属性、且会随版本增减，
     * 因此 {@code BailianAudioSynthesisAdapterContractTest} 用一条逐项断言
     * 钉住这份清单——厂商一变，测试就红，逼着人回到厂商页同步，而不是让
     * 客户端拿着一个悄悄过期的下拉框。名称（{@code name}）是提交值，
     * 描述是厂商页的中文一句话介绍，方言音色在描述里点名了方言。
     */
    private static final List<Voice> VOICES = List.of(
            new Voice("Cherry", "阳光积极、亲切自然的小姐姐（女）"),
            new Voice("Serena", "温柔的小姐姐（女）"),
            new Voice("Ethan", "标准普通话带北方口音，阳光温暖有活力（男）"),
            new Voice("Chelsie", "二次元虚拟女友（女）"),
            new Voice("Momo", "撒娇搞怪，逗你开心（女）"),
            new Voice("Vivian", "拽拽的、可爱的小暴躁（女）"),
            new Voice("Moon", "率性帅气的月白（男）"),
            new Voice("Maia", "知性与温柔的碰撞（女）"),
            new Voice("Kai", "耳朵的一场听觉 SPA（男）"),
            new Voice("Nofish", "不分平翘舌的设计师（男）"),
            new Voice("Bella", "萌系小萝莉（女）"),
            new Voice("Jennifer", "电影质感美式英语女声（女）"),
            new Voice("Ryan", "节奏拉满、戏感炸裂（男）"),
            new Voice("Katerina", "御姐音色，韵律回味十足（女）"),
            new Voice("Aiden", "精通厨艺的美语大男孩（男）"),
            new Voice("Eldric Sage", "沉稳睿智的老者，沧桑如松（男）"),
            new Voice("Mia", "温顺如春水、乖巧如初雪（女）"),
            new Voice("Mochi", "聪明伶俐的小大人（男）"),
            new Voice("Bellona", "洪亮清晰、热血大气（女）"),
            new Voice("Vincent", "独特的沙哑烟嗓（男）"),
            new Voice("Bunny", "萌属性爆棚的小萝莉（女）"),
            new Voice("Neil", "字正腔圆的新闻主持人（男）"),
            new Voice("Elias", "严谨又具叙事感的讲师（女）"),
            new Voice("Arthur", "被岁月浸泡的质朴嗓音（男）"),
            new Voice("Nini", "软糯粘人的邻家妹妹（女）"),
            new Voice("Seren", "温和舒缓的助眠声线（女）"),
            new Voice("Pip", "调皮捣蛋、充满童真的小孩（男）"),
            new Voice("Stella", "甜到发腻的少女音（女）"),
            new Voice("Bodega", "热情的西班牙大叔（男）"),
            new Voice("Sonrisa", "热情开朗的拉美大姐（女）"),
            new Voice("Alek", "冷峻又温暖的俄式男声（男）"),
            new Voice("Dolce", "慵懒的意大利大叔（男）"),
            new Voice("Sohee", "温柔开朗的韩国欧尼（女）"),
            new Voice("Ono Anna", "鬼灵精怪的青梅竹马（女）"),
            new Voice("Lenn", "理性又叛逆的德国青年（男）"),
            new Voice("Emilien", "浪漫的法国大哥哥（男）"),
            new Voice("Andre", "磁性自然、沉稳的男声（男）"),
            new Voice("Radio Gol", "足球解说诗人（男）"),
            new Voice("Jada", "上海话·风风火火的沪上阿姐（女）"),
            new Voice("Dylan", "北京话·胡同里长大的少年（男）"),
            new Voice("Li", "南京话·耐心的瑜伽老师（男）"),
            new Voice("Marcus", "陕西话·面宽话短的秦川（男）"),
            new Voice("Roy", "闽南语·诙谐爽直的台湾哥仔（男）"),
            new Voice("Peter", "天津话·相声专业捧哏（男）"),
            new Voice("Sunny", "四川话·甜到心里的川妹子（女）"),
            new Voice("Eric", "四川话·跳脱市井的成都男子（男）"),
            new Voice("Rocky", "粤语·幽默风趣的阿强（男）"),
            new Voice("Kiki", "粤语·甜美的港妹闺蜜（女）"));

    /** 音色名集合，从 {@link #VOICES} 派生，供提交校验与目录共用同一份来源。 */
    private static final Set<String> VOICE_NAMES = VOICES.stream()
            .map(Voice::name)
            .collect(Collectors.toUnmodifiableSet());

    private static final String API_ROOT = "/api/v1";
    private static final String SYNTHESIS_PATH =
            "/api/v1/services/aigc/multimodal-generation/generation";

    /**
     * 一次合成是同步的，但产出一段完整音频需要时间。上限压在渠道预算之下，
     * 使一次挂死的调用不会把预留无限期占住。
     */
    private static final Duration CALL_TIMEOUT_CAP = Duration.ofSeconds(90);

    /** 厂商错误分类，用于日志与支持的稳定字符串，而不是原始文本。 */
    private static final String ERROR_REJECTED = "provider_rejected_synthesis";
    private static final String ERROR_INVALID_RESPONSE = "provider_invalid_synthesis";
    private static final String ERROR_ERROR_BODY = "provider_error_response";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public BailianAudioSynthesisAdapter(
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
    public List<Voice> voices() {
        return VOICES;
    }

    @Override
    public void validateSubmission(SynthesisRequest request) {
        if (!MODELS.contains(normalizedModel(request.channel().upstreamModel()))) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "unsupported_bailian_tts_model");
        }
        // 目录已经把 VOICES 下发给客户端，因此提交一个不在清单里的音色名是
        // 客户端错误而非上游问题：在付费调用之前以 422 拒绝，而不是等厂商
        // 用一次调用才回「voice is not supported」。清单是展示与校验共用的
        // 同一份来源，两者不会漂移成两套答案。
        if (!VOICE_NAMES.contains(request.voice())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_bailian_tts_voice");
        }
    }

    @Override
    public Mono<SynthesisResult> synthesize(SynthesisRequest request) {
        return Mono.defer(() -> {
            validateSubmission(request);
            return client()
                    .post()
                    .uri(synthesisUri(request.channel()))
                    .headers(headers -> authorize(headers, request.channel()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload(request))
                    .exchangeToMono(response -> response.statusCode().isError()
                            ? rejected(response)
                            : response.bodyToMono(JsonNode.class)
                                    .flatMap(body -> Mono.fromCallable(
                                                    () -> toResult(request, body))
                                            .onErrorMap(error -> error
                                                            instanceof ProviderAdapter.ProviderException
                                                            ? error
                                                            : protocolFailure()))
                                    .switchIfEmpty(Mono.error(protocolFailure())))
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
     * {@code voice} 与 {@code language_type} 放在 {@code input} 里——
     * 这是上游文档给出的位置，把它放在顶层会在厂商侧变成一次难懂的
     * 「缺少必填参数」。
     */
    private ObjectNode payload(SynthesisRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", normalizedModel(request.channel().upstreamModel()));
        ObjectNode input = root.putObject("input");
        input.put("text", request.text());
        input.put("voice", request.voice());
        String language = AudioSynthesisAdapter.normalizeLanguage(request.languageType());
        if (language != null) {
            // 只在调用方指定时发送：交给厂商自动判断（Auto）是它的默认行为，
            // 多发一个 "Auto" 反而把「未指定」与「明确要求自动」混为一谈。
            input.put("language_type", language);
        }
        return root;
    }

    /**
     * 把厂商响应映射为一次已完成的合成。
     *
     * <p>位置优于内联数据：网关不镜像生成结果，而 base64 会让响应体大出
     * 三个数量级，两者对客户是同一段音频（与
     * {@code TianYiYunImageGenerationAdapter.toResult} 同一条规则）。
     *
     * <p>秒数取 {@code usage.characters} 的折算值。厂商报告零字符是
     * 「本次不计费」还是「响应缺字段」无法区分，而一次成功的合成确实产出了
     * 音频，因此零与缺失都回退到预留值，而不是把这次调用白送出去。
     */
    private SynthesisResult toResult(SynthesisRequest request, JsonNode body) {
        if (body == null || body.isNull()) {
            throw protocolFailure();
        }
        JsonNode error = body.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            // 200 携带错误体：厂商拒绝了这次合成，但它用 200 回答，
            // 因此无法证明它没有接单。按上游故障上报，预留保持不动。
            throw new ProviderAdapter.ProviderException(
                    HttpStatus.BAD_GATEWAY.value(),
                    ERROR_ERROR_BODY + ":" + firstNonBlank(
                            error.path("code").asText(""), "unknown"));
        }
        String code = body.path("code").asText("");
        if (!code.isBlank()) {
            throw new ProviderAdapter.ProviderException(
                    HttpStatus.BAD_GATEWAY.value(), ERROR_ERROR_BODY + ":" + code);
        }

        JsonNode audio = body.path("output").path("audio");
        if (audio.isMissingNode() || audio.isNull()) {
            throw protocolFailure();
        }
        String url = text(audio.path("url"));
        String base64 = text(audio.path("data"));
        String location = url != null ? url : base64;
        if (location == null) {
            // 既没有位置也没有内联数据：这不是一段音频，按协议失败处理，
            // 而不是向客户上报一次成功却拿不到音频的合成。
            throw protocolFailure();
        }

        int characters = body.path("usage").path("characters").asInt(0);
        // 这个端点从不回报产出音频的时长，只回报字符数，因此本次计费
        // **总是**估算值——字符缺失时更是如此（回退到预留秒数）。
        // 把 estimatedDuration 固定为 true 是对这一事实的陈述，
        // 而不是一个恒真的形式参数。
        int billedSeconds = characters > 0
                ? AudioSynthesisAdapter.estimateSecondsFromCharacters(characters)
                : request.reservedSeconds();
        return new SynthesisResult(location, billedSeconds, true);
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
     * 渠道 base_url 的**源站**部分：去掉尾部斜杠、去掉可能被整段粘进来的合成
     * 端点、再去掉可能已经带上的 API 根（{@code /api/v1}）。
     *
     * <p><b>返回的是不带 {@code /api/v1} 的前缀。</b>{@link #SYNTHESIS_PATH}
     * 自己就带 {@code /api/v1}。早先的实现先把 {@code /api/v1} 补进前缀再拼
     * 这个常量，于是得到 {@code /api/v1/api/v1/services/...}，**每一次真实合成
     * 都 404**。它与转写适配器里的同名错误是同一处设计错误的两份拷贝。
     */
    static String apiOrigin(Channel channel) {
        String baseUrl = normalizedBaseUrl(channel);
        if (baseUrl.endsWith(SYNTHESIS_PATH)) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - SYNTHESIS_PATH.length());
        }
        return baseUrl.endsWith(API_ROOT)
                ? baseUrl.substring(0, baseUrl.length() - API_ROOT.length())
                : baseUrl;
    }

    /** 带版本的 API 根 {@code {origin}/api/v1}，用于日志与调用归属。 */
    static String apiRoot(Channel channel) {
        return apiOrigin(channel) + API_ROOT;
    }

    /** 合成端点：完整路径是 {@code {origin}/api/v1/services/aigc/multimodal-generation/generation}。 */
    static String synthesisUri(Channel channel) {
        String configured = normalizedBaseUrl(channel);
        return configured.endsWith(SYNTHESIS_PATH)
                ? configured
                : apiOrigin(channel) + SYNTHESIS_PATH;
    }

    private static String normalizedBaseUrl(Channel channel) {
        return channel.baseUrl().replaceAll("/+$", "");
    }

    private Duration callTimeout(SynthesisRequest request) {
        Duration channelTimeout = Duration.ofMillis(request.timeoutMs());
        return channelTimeout.compareTo(CALL_TIMEOUT_CAP) < 0 ? channelTimeout : CALL_TIMEOUT_CAP;
    }

    /** 适配器无法解释的 2xx：厂商契约变了，不是调用方的错。 */
    private ProviderAdapter.ProviderException protocolFailure() {
        return new ProviderAdapter.ProviderException(
                HttpStatus.BAD_GATEWAY.value(), ERROR_INVALID_RESPONSE);
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
