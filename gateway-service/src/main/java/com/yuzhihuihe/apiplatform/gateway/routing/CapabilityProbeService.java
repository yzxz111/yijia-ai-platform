package com.yuzhihuihe.apiplatform.gateway.routing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.CapabilityProbeView;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 用渠道自己的密钥，对**每条能力真正会打到的那个端点**探一次。
 *
 * <p><b>它解决什么问题。</b>原有的密钥探测（{@code /channels/{id}/models} 与
 * {@code /upstream-models}）只会请求 {@code {base}/models}。而那是一条**只属于对话/
 * 视觉能力**的端点：百炼的音频、视频、生图模型都不出现在上面。于是运维把一把正确的
 * key 填进音频渠道、点「检测密钥」，得到的是 {@code HTTP 404 upstream_rejected}
 * ——一个看起来像"key 错了"、实际与 key 毫无关系的结论。对一个正在配置音频套餐的
 * 人，这是纯粹的误导：他会去反复换 key，而问题从来不在 key 上。
 *
 * <p><b>判据。</b>能力端点上的 401/403 才是"凭据不行"；其余（400/422/404/429 乃至
 * 2xx）都证明**凭据本身被上游接受了**，因为一个没通过鉴权的请求不可能走到参数校验
 * 那一步。探测体刻意用空输入或占位素材，让请求在参数校验处停下：
 *
 * <ul>
 *   <li><b>绝不产生一次真实生成</b>：视频/生图/口型的探测体带的是不存在的占位 URL，
 *       上游会在校验阶段拒绝，不会建任务、不会计费；</li>
 *   <li>因此这类能力上"400/422"是**成功**的结论，不是失败。</li>
 * </ul>
 *
 * <p><b>为什么按能力分别探。</b>同一个渠道可能同时服务多种能力（例如百炼音频渠道上有
 * 转写、合成、口型三条），它们各自的端点与请求形状都不同。逐条探完才能回答运维真正
 * 想知道的那句话："这条渠道上，哪些能力现在能用。"
 */
@Component
public class CapabilityProbeService {

    private static final int TIMEOUT_FLOOR_MS = 1_000;
    private static final int TIMEOUT_CEIL_MS = 30_000;

    /** 探测体里用的占位素材：刻意不存在，让上游在校验阶段就拒绝。 */
    private static final String PLACEHOLDER_URL =
            "https://example.invalid/capability-probe";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    CapabilityProbeService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    /** 一次能力探测的目标：往哪个 URL、发什么体。 */
    record ProbeTarget(String capability, String method, String url, JsonNode body) { }

    /**
     * 渠道声明的能力 → 该能力的探测目标。
     *
     * <p>同一能力下可能配了多条映射（同一能力的不同上游模型），探测取第一条：
     * 目的是回答"这条渠道的凭据在这个端点上通不通"，不需要把每个模型都试一遍。
     *
     * <p>非静态：请求体由注入的 {@code ObjectMapper} 构造，而 JSON 的构造方式应当只有
     * 一处来源——用 {@code new ObjectMapper()} 另起一个会在节点工厂/特性上与全局配置分叉。
     */
    List<ProbeTarget> targetsFor(String providerType, String baseUrl, List<CapabilityRow> rows) {
        String origin = origin(baseUrl);
        String root = versionRoot(baseUrl);
        String type = providerType == null ? "" : providerType.toUpperCase(Locale.ROOT);
        boolean tianyi = "TIANYIYUN".equals(type);

        Map<String, CapabilityRow> firstByCapability = new LinkedHashMap<>();
        for (CapabilityRow row : rows) {
            for (String capability : row.capabilities()) {
                firstByCapability.putIfAbsent(capability.toUpperCase(Locale.ROOT), row);
            }
        }

        List<ProbeTarget> targets = new ArrayList<>();
        for (Map.Entry<String, CapabilityRow> entry : firstByCapability.entrySet()) {
            String capability = entry.getKey();
            String model = entry.getValue().upstreamModel();
            switch (capability) {
                case "AUDIO_TRANSCRIBE" -> targets.add(json(
                        capability,
                        origin + (tianyi ? "/v1" : "/api/v1")
                                + "/services/audio/asr/transcription",
                        audioTranscriptionBody(model)));
                case "AUDIO_SYNTHESIS" -> targets.add(json(
                        capability,
                        origin + "/api/v1/services/aigc/multimodal-generation/generation",
                        synthesisBody(model)));
                case "LIP_SYNC" -> targets.add(json(
                        capability,
                        origin + "/api/v1/services/aigc/image2video/video-synthesis",
                        lipSyncBody(model)));
                case "VIDEO" -> targets.add(json(
                        capability,
                        tianyi ? origin + "/v1/video_generation"
                               : origin + "/api/v1/services/aigc/video-generation/video-synthesis",
                        videoBody(model, tianyi)));
                case "IMAGE_GEN" -> targets.add(json(
                        capability,
                        tianyi ? origin + "/v1/images/generations"
                               : origin + "/api/v1/services/aigc/multimodal-generation/generation",
                        imageBody(model, tianyi)));
                // 对话/视觉/文件走的就是 /models —— 原探针那种拼法在这里本来就是对的。
                default -> targets.add(new ProbeTarget(
                        capability, "GET",
                        origin + root + "/models", null));
            }
        }
        return List.copyOf(targets);
    }

    private static ProbeTarget json(String capability, String url, JsonNode body) {
        return new ProbeTarget(capability, "POST", url, body);
    }

    private ObjectNode audioTranscriptionBody(String model) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.putObject("input").putArray("messages");
        return root;
    }

    /**
     * 合成的探测体。
     *
     * <p>刻意留空 {@code text}：一个空的待合成文本会被上游在参数校验处拒绝（HTTP 400，
     * 带上 {@code InvalidParameter} 之类的错误码），这正是我们要的——**鉴权已经过去了，
     * 而一次真实合成没有发生**。反过来 401/403 说明凭据不行。
     */
    private ObjectNode synthesisBody(String model) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        ObjectNode input = root.putObject("input");
        input.putArray("messages").addObject()
                .put("role", "user")
                .putArray("content").addObject().put("text", "");
        return root;
    }

    private ObjectNode lipSyncBody(String model) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        ObjectNode input = root.putObject("input");
        input.put("video_url", PLACEHOLDER_URL);
        input.put("audio_url", PLACEHOLDER_URL);
        return root;
    }

    private ObjectNode videoBody(String model, boolean tianyi) {
        ObjectNode root = objectMapper.createObjectNode();
        if (tianyi) {
            // 天翼的视频提交是扁平体：{model, prompt, ...}
            root.put("model", model);
            root.put("prompt", "");
            root.put("duration", 5);
        } else {
            root.put("model", model);
            root.putObject("input").put("prompt", "");
            root.putObject("parameters").put("duration", 5);
        }
        return root;
    }

    private ObjectNode imageBody(String model, boolean tianyi) {
        ObjectNode root = objectMapper.createObjectNode();
        if (tianyi) {
            // 天翼生图是 OpenAI 形状：{model, prompt}
            root.put("model", model);
            root.put("prompt", "");
        } else {
            root.put("model", model);
            root.putObject("input").putArray("messages").addObject()
                    .put("role", "user")
                    .putArray("content").addObject().put("text", "");
        }
        return root;
    }

    /**
     * 从渠道的 base_url 里取出**源站**（{@code scheme://host[:port]}）。
     *
     * <p>运维会按各厂商文档把地址填成各种形状——裸域名、{@code /v1}、
     * {@code /api/v1}、{@code /compatible-mode/v1}，甚至整条端点。探测自己按能力拼路径，
     * 因此必须先把这些前缀剥掉，而不是信任 base_url 已经"干净"。
     */
    static String origin(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        int schemeEnd = normalized.indexOf("://");
        if (schemeEnd < 0) {
            return normalized;
        }
        int pathStart = normalized.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? normalized : normalized.substring(0, pathStart);
    }

    /** base_url 里显式带的版本段：拿它决定 {@code /models} 该拼在哪儿。 */
    static String versionRoot(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (normalized.contains("/api/v1")) {
            return "/api/v1";
        }
        return normalized.contains("/v1") ? "/v1" : "";
    }

    /**
     * 逐条能力探一遍。返回的顺序与渠道声明的能力一致，便于在不改客户端的情况下
     * 让人一眼对上是哪一条。
     *
     * <p>入参是数据库里原样的 jsonb 文本（{@code ["AUDIO_TRANSCRIBE"]}），由本服务解析：
     * 调用方只需把行读出来，不必也持有 ObjectMapper、也不必认识能力枚举的表示。
     */
    Mono<List<CapabilityProbeView>> probe(
            UUID channelId, String providerType, String baseUrl, String key, int timeoutMs,
            List<CapabilityMapping> mappings) {
        if (webClientBuilder == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "channel_probe_unavailable"));
        }
        List<CapabilityRow> rows = new ArrayList<>(mappings.size());
        for (CapabilityMapping mapping : mappings) {
            rows.add(new CapabilityRow(
                    mapping.upstreamModel(),
                    capabilitySet(mapping.capabilitiesJson(), objectMapper)));
        }
        List<ProbeTarget> targets = targetsFor(providerType, baseUrl, rows);
        return Flux.fromIterable(targets)
                .concatMap(target -> probeOnce(channelId, target, key, timeoutMs))
                .collectList();
    }

    /** 渠道上已配置的一条能力映射：上游模型名 + 数据库原样的能力 jsonb 文本。 */
    public record CapabilityMapping(String upstreamModel, String capabilitiesJson) { }

    private Mono<CapabilityProbeView> probeOnce(
            UUID channelId, ProbeTarget target, String key, int timeoutMs) {
        boolean withBody = target.body() != null;
        WebClient.RequestBodySpec spec = webClientBuilder.build()
                .method(HttpMethod.valueOf(target.method()))
                .uri(target.url())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .accept(MediaType.APPLICATION_JSON);
        WebClient.RequestHeadersSpec<?> request = withBody
                ? spec.contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(target.body().toString())
                : spec;

        return request.exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    boolean rejected = status == 401 || status == 403;
                    String error = null;
                    if (rejected) {
                        error = "upstream_rejected_credential";
                    } else if (status == 404) {
                        error = "endpoint_not_found";
                    } else if (status >= 500) {
                        error = "upstream_error";
                    }
                    return response.releaseBody().thenReturn(new CapabilityProbeView(
                            target.capability(), target.method(), target.url(), status,
                            !rejected, error));
                })
                .timeout(Duration.ofMillis(
                        Math.min(Math.max(timeoutMs, TIMEOUT_FLOOR_MS), TIMEOUT_CEIL_MS)))
                .onErrorReturn(new CapabilityProbeView(
                        target.capability(), target.method(), target.url(), 0, false,
                        "upstream_unreachable"));
    }

    /** 渠道上已配置的一条能力映射：能力标签 + 上游模型名。 */
    record CapabilityRow(String upstreamModel, Set<String> capabilities) { }

    static Set<String> capabilitySet(String capabilitiesJson, ObjectMapper mapper) {
        Set<String> values = new LinkedHashSet<>();
        if (capabilitiesJson == null || capabilitiesJson.isBlank()) {
            return values;
        }
        try {
            JsonNode node = mapper.readTree(capabilitiesJson);
            if (node.isArray()) {
                node.forEach(element -> {
                    String text = element.asText("");
                    if (!text.isBlank()) {
                        values.add(text);
                    }
                });
            }
        } catch (Exception ignored) {
            // 一条读不懂的能力标签不该让整次探测失败；它只会少一条结论。
        }
        return values;
    }
}
