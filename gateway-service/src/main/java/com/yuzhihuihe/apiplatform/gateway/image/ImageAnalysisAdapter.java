package com.yuzhihuihe.apiplatform.gateway.image;

import java.util.List;
import java.util.Set;

import reactor.core.publisher.Mono;

/**
 * 对一张已有图片产出**结构化判断**的服务。
 *
 * <p><b>这是预留，不是实现。</b>与 {@link ImagePostProcessAdapter} 同期定义，
 * 覆盖需求文档里那三项「不产出一张新图」的服务：
 *
 * <ul>
 *   <li><b>独立 OCR</b>——包装小字、型号、规格表的文字与位置提取。
 *       需求注明「第二批，Qwen 不够可靠时补充」，因此它存在的意义正是
 *       作为 {@code Qwen2.5-VL} 视觉理解那条路的**替代品**：当通用视觉模型读不准
 *       小字时，一个专用 OCR 服务是唯一的补救。这也解释了为什么它的结果里
 *       必须带**位置**：没有坐标的 OCR 无法被用来定位包装上的哪一个字段读错了。</li>
 *   <li><b>图片质量检查</b>——检查文字错误、主体变化、裁切、重复商品。
 *       需求注明「先复用 Qwen，结合程序检查」。因此这个接口的第一批调用方
 *       很可能不是一个新的适配器，而是**现有的视觉理解路径加上程序化检查**；
 *       接口先立在这里，是为了让「换成一个专用质检模型」成为一次新增 bean，
 *       而不是一次重构。</li>
 *   <li><b>内容审核</b>——见下，它刻意**不**实现在这里。</li>
 * </ul>
 *
 * <p><b>内容审核为什么不走这条接口。</b>需求把它列为「对外服务时按业务接入」
 * 的一项模型。但平台已经有完整的内容安全栈：{@code gateway} 的
 * V18/V19/V36（策略、提取输入方向、执法同步授权）、{@code platform} 的
 * V24/V25（基线策略与其修复），以及运行时的
 * {@code SafetyEnforcementWebFilter}。再实现一个审核适配器，会让同一个判断
 * 存在两条路径、两套策略版本和两处审计记录——而审核结论不一致时，
 * 没有任何一方能说清哪一条是权威的。因此内容审核应当**复用**那套栈，
 * 需要新增的是「把待审图片交给它」的调用，而不是一个新的供应商模型。
 * 这条接口因此只为 OCR 与质检而留。
 *
 * <p>与 {@link ImagePostProcessAdapter} 一样，结果**不交回计费数量**：
 * 按张、按页、按字段还是按千次调用，取决于第一个真正接入的服务，
 * 而那是一个定价决定，不该由适配器替它做。
 */
public interface ImageAnalysisAdapter {

    /** 与 {@code gateway.provider.provider_type} 匹配。 */
    String providerType();

    /** 本适配器能服务的能力，取自 {@link Capabilities}。 */
    Set<String> capabilities();

    /**
     * 校验供应商特有的请求画像，不预留配额，也不发起网络调用。
     *
     * <p>这一族服务的请求画像差异极大——OCR 关心语言与是否要坐标，
     * 质检关心要检查哪些缺陷类别——因此具体的校验几乎全部落在实现里。
     */
    default void validateSubmission(AnalysisRequest request) {
        // 没有更窄契约的供应商可以依赖网关层面的校验。
    }

    /** 分析一张图片并交回判断。 */
    Mono<AnalysisResult> analyse(AnalysisRequest request);

    /**
     * @param channel 解析出的上游渠道，包含其已解密的凭证
     * @param requestId 网关请求 id，可作为厂商侧的幂等键
     * @param operation 要求的能力，取自 {@link Capabilities}
     * @param imageUrl 要分析的图片位置；厂商自己去拉它
     * @param languages 期望的语种提示（OCR 用），可为空
     * @param options 供应商特有的开关（例如「是否返回词级坐标」、
     *        「要检查哪些缺陷类别」）。刻意是一个开放的键值集合：
     *        为一个还没选定的供应商预先列出它的参数，等于替它做产品决定
     * @param timeoutMs 该渠道的超时预算
     */
    record AnalysisRequest(
            Channel channel,
            String requestId,
            String operation,
            String imageUrl,
            List<String> languages,
            java.util.Map<String, String> options,
            int timeoutMs) {

        public AnalysisRequest {
            if (channel == null || requestId == null || requestId.isBlank()
                    || operation == null || operation.isBlank()
                    || imageUrl == null || imageUrl.isBlank()
                    || timeoutMs <= 0) {
                throw new IllegalArgumentException("invalid_image_analysis_request");
            }
            String candidate = imageUrl.strip().toLowerCase(java.util.Locale.ROOT);
            if (!candidate.startsWith("https://") && !candidate.startsWith("http://")) {
                throw new IllegalArgumentException("invalid_image_analysis_request");
            }
            languages = languages == null ? List.of() : List.copyOf(languages);
            options = options == null ? java.util.Map.of() : java.util.Map.copyOf(options);
        }
    }

    /**
     * @param id 渠道 id，用于路由
     * @param providerId 供应商行 id
     * @param providerType 用于挑选适配器的判别值
     * @param baseUrl 厂商 API 基址
     * @param apiKey 已解密的凭证，厂商不需要凭证时为 null
     * @param upstreamModel 该别名对应的厂商模型名
     */
    record Channel(
            java.util.UUID id,
            java.util.UUID providerId,
            String providerType,
            String baseUrl,
            String apiKey,
            String upstreamModel) {

        public Channel {
            if (id == null || providerId == null || providerType == null || providerType.isBlank()
                    || baseUrl == null || baseUrl.isBlank()
                    || upstreamModel == null || upstreamModel.isBlank()) {
                throw new com.yuzhihuihe.apiplatform.gateway.provider.ChannelFaultException(
                        "invalid_image_channel");
            }
        }
    }

    /**
     * 一处被识别出来的内容及其位置。
     *
     * <p>坐标是**必须**的，不是可选的补充：质检要回答的是「包装上哪一个字段
     * 读错了」，而没有坐标的 OCR 只能回答「这段文字出现了」。
     * 四边都用像素整数，原点在左上角——与 {@code ImagePostProcessAdapter}
     * 的产出尺寸同一坐标系，因此两者可以直接相减。
     */
    record Finding(
            String text,
            int left,
            int top,
            int right,
            int bottom,
            /** 置信度，0..1；厂商不提供时为 {@code null}。 */
            Double confidence) {

        public Finding {
            if (text == null || text.isBlank()
                    || left < 0 || top < 0 || right < left || bottom < top) {
                throw new IllegalArgumentException("invalid_image_analysis_finding");
            }
            if (confidence != null && (confidence < 0 || confidence > 1)) {
                throw new IllegalArgumentException("invalid_image_analysis_finding");
            }
        }
    }

    /**
     * 分析结果。
     *
     * @param findings 识别/检查出的事项，顺序即厂商给出的顺序
     * @param text 完整文本（OCR 用），可为空
     * @param passed 整体是否通过（质检/审核用）；不适用时为 {@code null}。
     *        三态而不是布尔值：{@code null} 表示「这项服务不给出通过与否」，
     *        而 {@code false} 表示「明确不通过」。把前者压成后者，
     *        会让一次只做了识别的调用看起来像一次失败的质检
     */
    record AnalysisResult(List<Finding> findings, String text, Boolean passed) {

        public AnalysisResult {
            findings = findings == null ? List.of() : List.copyOf(findings);
            text = text == null || text.isBlank() ? null : text;
            // 既没有事项也没有文本的结果没有任何信息量；一个成功的分析
            // 至少要说出一件事，否则它无法与「分析没跑」区分开。
            if (findings.isEmpty() && text == null) {
                throw new IllegalArgumentException("invalid_image_analysis_result");
            }
        }
    }

    /**
     * 分析类服务的能力词表。
     *
     * <p>与 {@link ImagePostProcessAdapter.Capabilities} 相同的理由：
     * 常量而不是散落的字面量，因为这些名字要被适配器、模型能力标记与
     * 管理端守卫三处一起读。
     *
     * <p>这里**没有** {@code MODERATION}，理由见类注释：内容审核走平台
     * 已有的内容安全栈，而不是一个新的适配器。
     */
    public static final class Capabilities {

        private Capabilities() {
        }

        /** 包装小字、型号、规格表的文字与位置提取。 */
        public static final String OCR = "OCR";

        /** 检查文字错误、主体变化、裁切、重复商品。 */
        public static final String QUALITY_CHECK = "IMAGE_QC";

        /** 本接口的全部能力名。 */
        public static final Set<String> ALL = Set.of(OCR, QUALITY_CHECK);
    }
}
