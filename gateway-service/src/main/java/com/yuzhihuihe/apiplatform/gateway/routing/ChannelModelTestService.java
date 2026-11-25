package com.yuzhihuihe.apiplatform.gateway.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelModelChoiceView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelModelTestView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 「测试模型」：用渠道里已保存的配置，真的向上游发一次最小对话请求。
 *
 * <p><b>为什么不能在可达性探测上打勾。</b>既有连接测试只做 DNS/TCP/TLS （{@link ChannelConnectivityProbe}），它对三种最常见的故障给出同一个
 * HEALTHY： 密钥是错的、上游模型名拼错了、地址少了一段版本号。运营于是得到「已配好」的结论， 而第一次真实调用 401 或 404。这里把那次调用提前到控制台上，并且只提前，
 * 不改变线上调用路径——用的是同一个适配器、同一个地址、同一把解密后的密钥。
 *
 * <p><b>代价与边界。</b>每次测试都会向上游计费一次真实的 {@code max_tokens=1} 请求， 因此它只能由管理端显式触发（不是定时任务），并且结果连同上游应答一起落进审计。
 * 这条请求完全绕开配额、钱包与用量记账：它是运营动作，不是客户消费，也绝不能让一次 探测污染某个客户的用量或账单。
 *
 * <p><b>不是每条别名都该被这条探测打。</b>探测走的是对话适配器 （{@code {base}/chat/completions}），而音频、视频、生图别名可以与对话别名挂在同一个
 * 服务商类型下（天翼就是如此），却不在那条端点上：拿 ASR 的模型名去打 chat/completions， 上游回
 * 404，界面于是显示「上游没有这个模型」——模型其实存在，只是不该用这条路径问它。 因此非对话别名在这里就被挡住（{@code
 * alias_not_chat_capable}），并且**不改写健康态**： 一次测错端点的调用不该把一个渠道标成异常。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ChannelModelTestService {

    /** 测试用提示词与输出上限：足以证明「这个模型在这个地址上能用」，且成本可忽略。 */
    static final String PROBE_PROMPT = "Reply with the single word: pong";

    static final int PROBE_MAX_TOKENS = 1;

    /** 上游原文回给控制台的长度上限；只用于定位问题，不是日志。 */
    static final int MAX_UPSTREAM_MESSAGE = 300;

    private static final Duration PROBE_TIMEOUT_CAP = Duration.ofSeconds(30);
    private static final Pattern PROVIDER_HTTP_STATUS = Pattern.compile("provider_http_(\\d{3})");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GatewayAdminRepository repository;
    private final ChannelConnectivityProbe connectivityProbe;
    private final CredentialCipher cipher;
    private final Map<String, ProviderAdapter> adapters;

    /**
     * 生产构造器。
     *
     * <p>{@code @Autowired} 是必需的：这个类还有一个（包可见的）测试构造器，两个构造器 并存时 Spring 无法自行挑选，会退回去找无参构造器并以 {@code
     * NoSuchMethodException: <init>()} 失败——那会让**每一个**加载完整上下文的 测试类都起不来，而不只是这一个类的测试。注入点必须显式标注。
     */
    @Autowired
    public ChannelModelTestService(
            GatewayAdminRepository repository,
            CredentialCipher cipher,
            List<ProviderAdapter> adapters,
            @Value("${gateway.channel.local-host-allowlist:}") String localHostAllowlist) {
        this.repository = repository;
        this.cipher = cipher;
        this.connectivityProbe = new ChannelConnectivityProbe(localHostAllowlist);
        Map<String, ProviderAdapter> indexed = new LinkedHashMap<>();
        if (adapters != null) {
            adapters.forEach(
                    adapter ->
                            indexed.put(adapter.providerType().toUpperCase(Locale.ROOT), adapter));
        }
        this.adapters = Map.copyOf(indexed);
    }

    /** 测试用构造器：注入假适配器时不需要真的网络。 */
    ChannelModelTestService(
            GatewayAdminRepository repository,
            CredentialCipher cipher,
            List<ProviderAdapter> adapters) {
        this(repository, cipher, adapters, "localhost,127.0.0.1,::1");
    }

    /**
     * @param upstreamModel 要测的上游模型名（来自某条别名的映射）；为空时由网关挑选 该渠道上第一条可用的文本映射，因此控制台可以不传。
     * @param connectivityOnly 为真时退化成旧的可达性探测：只回答「地址通不通」， 不发真实请求、不声称模型可用。
     */
    public Mono<ChannelModelTestView> test(
            UUID channelId, String upstreamModel, boolean connectivityOnly) {
        if (channelId == null) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "channel_not_found"));
        }
        String requested = upstreamModel == null ? "" : upstreamModel.strip();
        if (requested.length() > 128) {
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "invalid_upstream_model"));
        }
        return repository
                .channelProbe(channelId)
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "channel_not_found")))
                .flatMap(
                        row ->
                                repository
                                        .channelModels(channelId)
                                        .collectList()
                                        .flatMap(
                                                mappings ->
                                                        probe(
                                                                channelId,
                                                                row,
                                                                requested,
                                                                connectivityOnly,
                                                                mappings)));
    }

    private Mono<ChannelModelTestView> probe(
            UUID channelId,
            GatewayAdminRepository.ChannelProbeRow row,
            String requested,
            boolean connectivityOnly,
            List<GatewayAdminRepository.ChannelModelChoice> mappings) {
        List<ChannelModelChoiceView> choices = choices(mappings);
        if (row.encryptedApiKey() == null || row.encryptedApiKey().length == 0) {
            // 没有凭据时连可达性都不必探：结论不会因为网络而改变。
            return Mono.just(unconfigured(channelId, row, choices));
        }
        if (connectivityOnly) {
            return connectivityProbe
                    .probe(row.baseUrl(), row.environment(), row.timeoutMs())
                    .map(
                            reachable ->
                                    new ChannelModelTestView(
                                            channelId,
                                            row.code(),
                                            row.providerType(),
                                            true,
                                            reachable,
                                            false,
                                            // 只探可达性绝不等于「模型可用」：modelOk 必须是 false。
                                            false,
                                            "UNKNOWN",
                                            null,
                                            null,
                                            reachable ? null : "upstream_unreachable",
                                            null,
                                            null,
                                            null,
                                            null,
                                            false,
                                            null,
                                            choices));
        }
        Optional<GatewayAdminRepository.ChannelModelChoice> target = select(mappings, requested);
        if (target.isEmpty()) {
            return Mono.just(
                    new ChannelModelTestView(
                            channelId,
                            row.code(),
                            row.providerType(),
                            true,
                            false,
                            false,
                            false,
                            "UNKNOWN",
                            null,
                            null,
                            mappings.isEmpty() ? "channel_has_no_model" : "model_mapping_missing",
                            null,
                            null,
                            null,
                            null,
                            false,
                            null,
                            choices));
        }
        GatewayAdminRepository.ChannelModelChoice choice = target.get();
        if (!choice.textCapable()) {
            // 这条别名不是对话模型（音频、视频、生图）。继续往下走只会把上游
            // 「这个模型不在 chat/completions 上」翻译成「上游没有这个模型」，而模型名、
            // 密钥、地址都可能是对的——那正是最误导人的一条结论。这里因此不发请求，
            // 也不写健康态：一次没打对端点的探测不构成「这个渠道不能用」的证据。
            return Mono.just(
                    new ChannelModelTestView(
                            channelId,
                            row.code(),
                            row.providerType(),
                            true,
                            false,
                            false,
                            false,
                            "UNKNOWN",
                            choice.modelCode(),
                            choice.upstreamModel(),
                            "alias_not_chat_capable",
                            null,
                            null,
                            null,
                            null,
                            false,
                            null,
                            choices));
        }
        ProviderAdapter adapter = adapters.get(normalize(row.providerType()));
        if (adapter == null || !adapter.supports(ChannelRouter.Capability.TEXT)) {
            // 「连接正常」在一个没有对话适配器的类型上毫无意义：这类渠道的每一次对话
            // 请求都会 503，而可达性探测对此一无所知（ProviderCapabilityCatalog 记录了
            // 引发它的那次真实事故）。
            return Mono.just(
                    new ChannelModelTestView(
                            channelId,
                            row.code(),
                            row.providerType(),
                            true,
                            false,
                            false,
                            false,
                            "UNKNOWN",
                            choice.modelCode(),
                            choice.upstreamModel(),
                            "provider_not_chat_capable",
                            null,
                            null,
                            null,
                            null,
                            false,
                            null,
                            choices));
        }
        return invoke(channelId, row, choice, adapter, choices)
                .flatMap(result -> persistHealth(channelId, row, result));
    }

    /**
     * 真实调用一次并把结果翻译成原因码。
     *
     * <p>先做可达性探测再调用不是多余的：它把「地址根本连不上」与「上游明确拒绝」 区分开，而这两者的处置完全不同（改地址 vs 换密钥）。
     */
    private Mono<ProbeResult> invoke(
            UUID channelId,
            GatewayAdminRepository.ChannelProbeRow row,
            GatewayAdminRepository.ChannelModelChoice choice,
            ProviderAdapter adapter,
            List<ChannelModelChoiceView> choices) {
        return connectivityProbe
                .probe(row.baseUrl(), row.environment(), row.timeoutMs())
                .flatMap(
                        reachable ->
                                reachable
                                        ? complete(row, choice, adapter, choices)
                                        : Mono.just(ProbeResult.unreachable(choice, choices)));
    }

    private Mono<ProbeResult> complete(
            GatewayAdminRepository.ChannelProbeRow row,
            GatewayAdminRepository.ChannelModelChoice choice,
            ProviderAdapter adapter,
            List<ChannelModelChoiceView> choices) {
        String key;
        try {
            key = cipher.decrypt(row.encryptedApiKey());
        } catch (RuntimeException undecryptable) {
            // 密钥解不开（换过加密密钥、库里是脏数据）时必须给出一个可读的原因，
            // 而不是把 500 丢给控制台：这是配置问题，不是系统故障。
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE, "credential_decryption_failed"));
        }
        ProviderAdapter.Channel channel =
                new ProviderAdapter.Channel(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        normalize(row.providerType()),
                        row.baseUrl(),
                        key,
                        row.timeoutMs(),
                        choice.upstreamModel());
        ProviderAdapter.RoutedRequest request =
                new ProviderAdapter.RoutedRequest(
                        UUID.randomUUID().toString(), choice.modelCode(), prompt(), channel);
        long started = System.nanoTime();
        return adapter.complete(request)
                .map(
                        response ->
                                ProbeResult.success(
                                        choice,
                                        elapsedMillis(started),
                                        response.body(),
                                        response.usage(),
                                        choices))
                .onErrorResume(
                        error ->
                                Mono.just(
                                        ProbeResult.failed(
                                                choice, elapsedMillis(started), error, choices)))
                // 适配器自己也有超时（渠道的 timeout_ms），这里只是兜底：
                // 一个不守超时的上游不该让控制台的按钮永远转圈。
                .timeout(PROBE_TIMEOUT_CAP)
                .onErrorResume(
                        timeout ->
                                Mono.just(
                                        ProbeResult.of(
                                                choice,
                                                elapsedMillis(started),
                                                "timeout",
                                                null,
                                                "provider_attempt_timeout",
                                                choices)))
                // 兜底再兜一层：任何漏出来的异常都必须变成一条可读的测试结论。
                // 按钮抛 500 的话，运营既看不到原因，也不会得到健康态更新，
                // 而这恰恰是最需要说清楚的一次测试。
                .onErrorResume(
                        error ->
                                Mono.just(
                                        ProbeResult.failed(
                                                choice, elapsedMillis(started), error, choices)));
    }

    private Mono<ChannelModelTestView> persistHealth(
            UUID channelId, GatewayAdminRepository.ChannelProbeRow row, ProbeResult result) {
        String status = statusFor(result);
        return repository
                .updateChannelHealth(channelId, status, row.observedUpdatedAt())
                .map(updated -> view(channelId, row, result, status, updated > 0, null))
                .defaultIfEmpty(
                        view(channelId, row, result, "UNKNOWN", false, "health_not_updated"))
                // 测试结论本身不能因为「写不回健康态」而消失：结果照报，
                // 只把 healthPersisted 打成 false，让界面提示重新测试。
                .onErrorResume(
                        error ->
                                Mono.just(
                                        view(
                                                channelId,
                                                row,
                                                result,
                                                status,
                                                false,
                                                "health_update_failed")));
    }

    private ChannelModelTestView view(
            UUID channelId,
            GatewayAdminRepository.ChannelProbeRow row,
            ProbeResult result,
            String status,
            boolean persisted,
            String extraMessage) {
        return new ChannelModelTestView(
                channelId,
                row.code(),
                row.providerType(),
                true,
                result.reachable(),
                true,
                result.ok(),
                status,
                result.modelCode(),
                result.upstreamModel(),
                result.reason(),
                result.httpStatus(),
                result.latencyMs(),
                result.inputTokens(),
                result.outputTokens(),
                persisted,
                result.upstreamMessage() != null ? result.upstreamMessage() : extraMessage,
                result.choices());
    }

    private ChannelModelTestView unconfigured(
            UUID channelId,
            GatewayAdminRepository.ChannelProbeRow row,
            List<ChannelModelChoiceView> choices) {
        return new ChannelModelTestView(
                channelId,
                row.code(),
                row.providerType(),
                false,
                false,
                false,
                false,
                "UNHEALTHY",
                null,
                null,
                "channel_credential_required",
                null,
                null,
                null,
                null,
                false,
                null,
                choices);
    }

    /** 选中要测试的那条映射：指定的上游模型优先，否则第一条可用映射。 */
    static Optional<GatewayAdminRepository.ChannelModelChoice> select(
            List<GatewayAdminRepository.ChannelModelChoice> mappings, String requested) {
        if (mappings == null || mappings.isEmpty()) {
            return Optional.empty();
        }
        if (requested == null || requested.isBlank()) {
            return mappings.stream()
                    .filter(GatewayAdminRepository.ChannelModelChoice::probeable)
                    .findFirst();
        }
        return mappings.stream()
                .filter(choice -> requested.equals(choice.upstreamModel()))
                .findFirst();
    }

    /**
     * 结果到渠道健康态的映射。
     *
     * <p>只有「这次配置本身不能用」（连不通、密钥被拒、模型不存在）才落到不健康的 {@code UNHEALTHY} —— 那几种状态重试多少次都一样，把它们留在健康态只会让分流
     * 一直选中一个必然失败的目标。而上游 5xx、429、超时是暂时的：报 {@code DEGRADED}， 分流仍然可以使用这个渠道，同时控制台看得见它现在不稳。
     */
    static String statusFor(ProbeResult result) {
        if (result.ok()) {
            return "HEALTHY";
        }
        if (!result.reachable() || result.reason() == null) {
            return "UNHEALTHY";
        }
        return switch (result.reason()) {
            case "credential_rejected",
                            "model_not_found_on_provider",
                            "upstream_endpoint_not_found",
                            "invalid_upstream_response",
                            "empty_provider_response",
                            "upstream_error_in_body" ->
                    "UNHEALTHY";
            default -> "DEGRADED";
        };
    }

    /**
     * 只看状态码的原因码；界面据此给中文，而不是拿状态码猜。
     *
     * <p>404 在这里仍按「模型不存在」翻译，因为**只有状态码时它确实是那个意思**。 有异常可看时请走 {@link #reasonFor(Integer,
     * Throwable)}：它会读上游原文， 把一个打错地址的 404 与一个真的缺模型的 404 分开。
     */
    static String reasonForStatus(int httpStatus) {
        return switch (httpStatus) {
            case 401, 403 -> "credential_rejected";
            case 402 -> "upstream_insufficient_balance";
            case 404 -> "model_not_found_on_provider";
            case 408, 429 -> "upstream_overloaded";
            default -> httpStatus >= 500 ? "upstream_server_error" : "upstream_rejected";
        };
    }

    /**
     * 状态码 **加上上游原文** 到原因码的翻译。
     *
     * <p>404 是唯一一个「同一个状态码对应两种完全不同的处置」的状态：上游没有这个模型名， 与地址少了一段版本段（例如 {@code /v1}），上游都回
     * 404。一律记成「上游没有这个模型」 的代价是一次打错地址的探测会把人送去改一个本来正确的模型名。
     *
     * <p>因此这里要求**证据**：只有上游原文里出现模型相关的字眼时才说模型；上游什么都没说 （HTML 404 页、空体、非
     * JSON）时说地址，让运营对着上游原文自己判断——而不是替他断言。
     */
    static String reasonFor(Integer httpStatus, Throwable error) {
        if (httpStatus == null) {
            return "upstream_unreachable";
        }
        if (httpStatus != 404) {
            return reasonForStatus(httpStatus);
        }
        return mentionsModel(ProbeResult.upstreamText(error))
                ? "model_not_found_on_provider"
                : "upstream_endpoint_not_found";
    }

    /**
     * 上游原文里是否真的提到了模型。
     *
     * <p>刻意只做这一件窄事：它是「这个 404 在说什么」唯一可用的证据。宽到「404 就等于 模型不存在」会退回旧行为；窄到只认某一家厂商的错误码，则会在换一家厂商时静默失效。
     */
    static boolean mentionsModel(String upstreamText) {
        if (upstreamText == null || upstreamText.isBlank()) {
            return false;
        }
        String text = upstreamText.toLowerCase(Locale.ROOT);
        return text.contains("model") || text.contains("模型");
    }

    /** 从适配器的异常里抽出上游 HTTP 状态码：{@code provider_http_401} 这类消息是稳定的。 */
    static Integer httpStatusOf(Throwable error) {
        if (error instanceof ProviderAdapter.ProviderException provider) {
            return provider.status() > 0 ? provider.status() : null;
        }
        String message = error == null ? null : error.getMessage();
        if (message == null) {
            return null;
        }
        Matcher matcher = PROVIDER_HTTP_STATUS.matcher(message);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private List<ChannelModelChoiceView> choices(
            List<GatewayAdminRepository.ChannelModelChoice> mappings) {
        List<ChannelModelChoiceView> views = new ArrayList<>();
        if (mappings == null) {
            return views;
        }
        for (GatewayAdminRepository.ChannelModelChoice choice : mappings) {
            views.add(
                    new ChannelModelChoiceView(
                            choice.modelCode(),
                            choice.displayName(),
                            choice.upstreamModel(),
                            choice.mappingEnabled(),
                            choice.modelEnabled(),
                            choice.textCapable(),
                            choice.probeable()));
        }
        return views;
    }

    private ObjectNode prompt() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", "probe");
        body.putArray("messages").addObject().put("role", "user").put("content", PROBE_PROMPT);
        body.put("max_tokens", PROBE_MAX_TOKENS);
        body.put("stream", false);
        return body;
    }

    private static String normalize(String providerType) {
        return providerType == null ? "" : providerType.strip().toUpperCase(Locale.ROOT);
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    /** 一次真实调用的结论。 */
    record ProbeResult(
            boolean reachable,
            boolean ok,
            String reason,
            Integer httpStatus,
            Long latencyMs,
            Long inputTokens,
            Long outputTokens,
            String modelCode,
            String upstreamModel,
            String upstreamMessage,
            List<ChannelModelChoiceView> choices) {

        static ProbeResult success(
                GatewayAdminRepository.ChannelModelChoice choice,
                long latencyMs,
                JsonNode body,
                ProviderAdapter.TokenUsage usage,
                List<ChannelModelChoiceView> choices) {
            String upstreamError = upstreamError(body);
            boolean ok = upstreamError == null;
            return new ProbeResult(
                    true,
                    ok,
                    ok ? null : "upstream_error_in_body",
                    200,
                    latencyMs,
                    usage == null ? null : usage.inputTokens(),
                    usage == null ? null : usage.outputTokens(),
                    choice.modelCode(),
                    choice.upstreamModel(),
                    upstreamError,
                    choices);
        }

        static ProbeResult failed(
                GatewayAdminRepository.ChannelModelChoice choice,
                long latencyMs,
                Throwable error,
                List<ChannelModelChoiceView> choices) {
            Integer httpStatus = httpStatusOf(error);
            return new ProbeResult(
                    true,
                    false,
                    reasonFor(httpStatus, error),
                    httpStatus,
                    latencyMs,
                    null,
                    null,
                    choice.modelCode(),
                    choice.upstreamModel(),
                    upstreamText(error),
                    choices);
        }

        static ProbeResult unreachable(
                GatewayAdminRepository.ChannelModelChoice choice,
                List<ChannelModelChoiceView> choices) {
            return new ProbeResult(
                    false,
                    false,
                    "upstream_unreachable",
                    null,
                    null,
                    null,
                    null,
                    choice.modelCode(),
                    choice.upstreamModel(),
                    null,
                    choices);
        }

        static ProbeResult of(
                GatewayAdminRepository.ChannelModelChoice choice,
                long latencyMs,
                String reason,
                Integer httpStatus,
                String message,
                List<ChannelModelChoiceView> choices) {
            return new ProbeResult(
                    true,
                    false,
                    reason,
                    httpStatus,
                    latencyMs,
                    null,
                    null,
                    choice.modelCode(),
                    choice.upstreamModel(),
                    message,
                    choices);
        }

        /** 上游用 200 回错误对象是常见形状（余额不足、模型未开通都以这种方式出现）。 只看状态码会把这种应答记成通过，因此这里必须读一次 body。 */
        static String upstreamError(JsonNode body) {
            if (body == null || body.isNull()) {
                return "empty_provider_response";
            }
            JsonNode error = body.get("error");
            if (error != null && !error.isNull() && !error.isMissingNode()) {
                String message =
                        error.isTextual()
                                ? error.asText()
                                : error.path("message").asText(error.toString());
                return truncate(message);
            }
            boolean hasChoices = body.path("choices").isArray() && !body.path("choices").isEmpty();
            return hasChoices ? null : "invalid_upstream_response";
        }

        /**
         * 回给控制台的「上游原文」。
         *
         * <p>优先用上游错误信封里的 {@code message}：那才是运营要读的那句话。拿不到信封时 退回到异常消息（通常只剩 {@code
         * provider_http_404}），因为**网关刻意不保留原始 响应体**——上游可能回 HTML 错误页或纯文本，把它整段搬进管理端不是诊断而是风险。
         */
        static String upstreamText(Throwable error) {
            if (error instanceof ProviderAdapter.ProviderException provider
                    && provider.upstreamMessage() != null) {
                return truncate(provider.upstreamMessage());
            }
            return messageOf(error);
        }

        static String messageOf(Throwable error) {
            if (error == null) {
                return null;
            }
            String message = error.getMessage();
            return message == null || message.isBlank()
                    ? error.getClass().getSimpleName()
                    : truncate(message);
        }

        private static String truncate(String value) {
            if (value == null) {
                return null;
            }
            String stripped = value.strip();
            return stripped.length() <= MAX_UPSTREAM_MESSAGE
                    ? stripped
                    : stripped.substring(0, MAX_UPSTREAM_MESSAGE);
        }
    }
}
