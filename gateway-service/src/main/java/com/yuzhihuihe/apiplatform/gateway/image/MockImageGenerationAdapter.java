package com.yuzhihuihe.apiplatform.gateway.image;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 一个不生成任何图片、也不产生任何费用的生图适配器，用于在没有上游凭证的
 * 情况下跑通整条 reserve → generate → settle 链路。
 *
 * <p>它之所以存在，是因为按张计费必须可验证。网关现有的
 * {@code MockProviderAdapter} 覆盖了聊天，{@code MockVideoGenerationAdapter}
 * 覆盖了秒，而如果没有对应的生图版本，「张」的预留、结算与释放就只能靠
 * 打桩的服务层来测试，那会让路由、持久化与幂等代码都得不到演练。
 *
 * <p>行为由渠道的 base URL 驱动，因此测试无需改动代码就能选择结果：
 *
 * <ul>
 *   <li>{@code mock://image/succeed} —— 返回所请求张数的 {@code url} 结果
 *   <li>{@code mock://image/succeed-b64} —— 返回所请求张数的 {@code b64_json} 结果
 *   <li>{@code mock://image/succeed-fewer} —— 无论请求几张都只返回一张，
 *       用来演练「按实际张数结算、释放多余预留」
 *   <li>{@code mock://image/succeed-layers} —— 无论请求几张都返回三张，
 *       用来演练图层拆分：厂商对一次调用合法地报出多张图层，
 *       而那次调用必须恰好结算一个单位
 *   <li>{@code mock://image/succeed-partial} —— **扇出专用**：让这一次扇出里
 *       序号为偶数的那些子任务（{@code "<父>#2"}、{@code "<父>#4"}）
 *       以 4xx 明确拒绝，其余照常出图。用来演练"部分成功"：交付成功的那些、
 *       失败的逐张退款，而整批不因为一张失败就被丢掉
 *   <li>{@code mock://image/fail} —— 厂商明确拒绝（4xx），
 *       因此是确定性失败，预留会被释放
 *   <li>{@code mock://image/unavailable} —— 上游故障（5xx），
 *       结果未知，因此预留保持不动
 *   <li>{@code mock://image/empty} —— 2xx 但一张图都没有，
 *       按协议失败处理
 * </ul>
 *
 * <p>本 bean 只在 {@code gateway.image.allow-mock=true} 时存在，
 * 与视频的开关一样默认关闭：生产环境里一个会返回假图片的适配器
 * 比一次失败的调用危险得多。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "gateway.image", name = "allow-mock", havingValue = "true")
public final class MockImageGenerationAdapter implements ImageGenerationAdapter {

    private static final Set<String> CAPABILITIES = Set.of("IMAGE_GEN");
    private static final String BEHAVIOUR_PREFIX = "mock://image/";
    private static final String OUTPUT_PREFIX = "mock://image/output/";

    /**
     * {@code succeed-layers} 返回的图层数。固定的三张，而不是所请求的张数：
     * 图层拆分的要点正是厂商返回的张数与请求无关——它数的是图层，
     * 而那次调用仍然是一个计费单位。
     */
    private static final int LAYER_COUNT = 3;

    @Override
    public String providerType() {
        return "MOCK";
    }

    @Override
    public Set<String> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Mono<GenerationResult> generate(GenerationRequest request) {
        return Mono.fromSupplier(() -> {
            String behaviour = behaviour(request.channel().baseUrl());
            return switch (behaviour) {
                case "fail" -> throw new ProviderAdapter.ProviderException(
                        400, "mock_generation_rejected");
                // 扇出的"部分成功"：偶数序号的那几张明确拒绝，其余照常出图。
                // 4xx 是确定性拒绝，因此网关会释放那一张自己的预留并退款，
                // 而另外几张照常交付——这正是 n > 1 相对"全有或全无"的差别。
                case "succeed-partial" -> {
                    if (isEvenFanoutChild(request.requestId())) {
                        throw new ProviderAdapter.ProviderException(
                                400, "mock_generation_rejected_for_fanout_child");
                    }
                    yield GenerationResult.of(
                            urlImages(request, behaviour, request.imageCount()));
                }
                case "unavailable" -> throw new ProviderAdapter.ProviderException(
                        502, "mock_generation_unavailable");
                case "empty" -> throw new ProviderAdapter.ProviderException(
                        502, "provider_invalid_response");
                case "succeed-b64" -> GenerationResult.of(base64Images(request, behaviour));
                case "succeed-fewer" -> GenerationResult.of(urlImages(request, behaviour, 1));
                case "succeed-layers" -> GenerationResult.of(
                        urlImages(request, behaviour, LAYER_COUNT));
                default -> GenerationResult.of(
                        urlImages(request, behaviour, request.imageCount()));
            };
        });
    }

    /**
     * 这次调用是不是一个**偶数序号**的扇出子任务。
     *
     * <p>序号由 {@code ImageGenerationService.fanoutChildRequestId} 拼在 request id
     * 末尾（{@code "<父>#<序号>"}）。这里只做字符串解析，不反向依赖服务层的常量：
     * 一个测试替身不该成为被测试对象的耦合点。解析不出序号（单图请求）时返回 false，
     * 因此这个行为对 n = 1 完全无影响。
     */
    private static boolean isEvenFanoutChild(String requestId) {
        int separator = requestId == null ? -1 : requestId.lastIndexOf('#');
        if (separator <= 0 || separator == requestId.length() - 1) {
            return false;
        }
        try {
            int index = Integer.parseInt(requestId.substring(separator + 1));
            return index % 2 == 0;
        } catch (NumberFormatException notAFanoutChild) {
            return false;
        }
    }

    private List<GeneratedImage> urlImages(
            GenerationRequest request, String behaviour, int count) {
        List<GeneratedImage> images = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            images.add(new GeneratedImage(
                    OUTPUT_PREFIX + behaviour + "/" + request.requestId() + "/" + index, null));
        }
        return images;
    }

    private List<GeneratedImage> base64Images(GenerationRequest request, String behaviour) {
        List<GeneratedImage> images = new ArrayList<>();
        for (int index = 0; index < request.imageCount(); index++) {
            // 一段合法但极小的 PNG 头，足以让「这是 base64 形态」可被断言。
            images.add(new GeneratedImage(null, "iVBORw0KGgoAAAANSUhEUg" + index));
        }
        return images;
    }

    private String behaviour(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (!value.startsWith(BEHAVIOUR_PREFIX)) {
            // 无法识别的 base URL 会成功而不是静默失败，因为配置错误的渠道
            // 应该表现为路由问题，而不是让客户被计费的生成失败。
            return "succeed";
        }
        String behaviour = value.substring(BEHAVIOUR_PREFIX.length());
        return behaviour.isBlank() ? "succeed" : behaviour;
    }
}
