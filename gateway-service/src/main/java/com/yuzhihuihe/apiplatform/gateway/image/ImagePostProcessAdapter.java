package com.yuzhihuihe.apiplatform.gateway.image;

import java.util.Set;

import reactor.core.publisher.Mono;

/**
 * 对一张已有图片做**保持画面尺寸**的加工，并交回一张新图。
 *
 * <p><b>这是预留，不是实现。</b>需求文档列出了七项图片后期服务
 * （商品抠图/精细蒙版、图片编辑、高清放大/降噪、独立 OCR、图片质量检查、
 * 虚拟试穿、图片内容审核），其中前两项「首批验证」、其余分批。本次改动
 * **只定义接口与能力词表，不建表、不建渠道、不建别名**——理由见类注释末尾。
 *
 * <p><b>为什么与 {@code ImageGenerationAdapter} 分开。</b>两者的输入契约不同：
 * 生成吃一个提示词（可选一张输入图），产出是**从无到有**的；后期加工吃一张
 * **必须存在的**输入图，产出是同一画面的改写。把后期加工塞进生成接口，
 * 会让「没有输入图」这个对生成完全合法的请求，在后期加工里变成一次
 * 静默的错误——而客户为它付了费。
 *
 * <p><b>为什么这一族又分成「交回图片」与「交回判断」两个接口。</b>
 * 七项服务恰好落在两种协议形状上：
 *
 * <ul>
 *   <li><b>图片进、图片出</b>（本接口）：抠图/蒙版、图片编辑、超分/降噪，
 *       以及虚拟试穿（两张图进、一张出，见 {@link #secondaryImageUrl}）。</li>
 *   <li><b>图片进、判断出</b>（见 {@link ImageAnalysisAdapter}）：
 *       独立 OCR、质量检查、内容审核。它们不产出一张新图，
 *       而是产出一份**结构化的判断**，因此计费与交付形状都不同。</li>
 * </ul>
 *
 * <p>把两者合成一个接口，就会让「返回的是一张图还是一段 JSON」变成
 * 每个实现各自的口头约定，而那正是最容易被写错的一类约定。
 *
 * <p><b>计费单位的预留。</b>本接口的结果只交回一个位置与一个可选的
 * 产出尺寸，**不交回任何计费数量**。原因是这七项服务在市场上按三种不同的
 * 单位售卖（按张、按次、按百万像素），而哪一种适用于「图片编辑」还没有定论。
 * 让适配器现在猜一个单位，就是把一个尚未做出的定价决定固化进代码。
 * 计费单位在真正接入第一个后期服务时，与它的费率行一起定义。
 *
 * <p><b>为什么现在不建表、不建别名。</b>V81 有一条断言
 * （{@code image_capable_alias_without_a_registered_tier}）要求每一个声明
 * {@code IMAGE_GEN} 的别名都有已登记的档位与成本基准。一个为了「预留」而建的
 * 别名，只要带了能力标记就会立刻撞上那条断言——而如果为了绕开它去放宽断言，
 * 就正好破坏了它存在的意义。因此预留的是**接口与词表**，
 * 而不是一张没有供应商、没有价格、也服务不了任何请求的目录行。
 */
public interface ImagePostProcessAdapter {

    /** 与 {@code gateway.provider.provider_type} 匹配。 */
    String providerType();

    /**
     * 本适配器能服务的能力，取自 {@link Capabilities}。
     *
     * <p>一个适配器可以声明多项：同一个供应商的商品抠图与图片编辑
     * 往往就是同一套分割模型的两种调用方式。
     */
    Set<String> capabilities();

    /**
     * 校验供应商特有的请求画像，不预留配额，也不发起网络调用。
     *
     * <p>服务层在解析出渠道之后、预留之前调用本钩子，因此本地即可发现的
     * 不良请求不会留下一个已付费的占用。
     */
    default void validateSubmission(PostProcessRequest request) {
        // 没有更窄契约的供应商可以依赖网关层面的校验。
    }

    /** 加工一张图片。 */
    Mono<PostProcessResult> process(PostProcessRequest request);

    /**
     * @param channel 解析出的上游渠道，包含其已解密的凭证
     * @param requestId 网关请求 id，可作为厂商侧的幂等键
     * @param operation 要求的能力，取自 {@link Capabilities}；必须是
     *        {@link #capabilities()} 的子集，否则请求在本地被拒
     * @param imageUrl 要加工的图片位置。厂商自己去拉它，因此必须是绝对 http(s)
     * @param secondaryImageUrl 可选的第二张输入图。**虚拟试穿的形状**：
     *        一张人物图加一张服装图。为空表示本次操作只需要一张图，
     *        而需要两张的操作在缺它时必须被拒绝，而不是让厂商猜
     * @param instruction 可选的自然语言指令（例如「把背景换成沙滩」）。
     *        对抠图这类不需要指令的操作可以为空
     * @param maskUrl 可选的目标区域蒙版，用于「选中换色区域」这类
     *        只在指定区域内生效的操作
     * @param outputFormat 期望的产出格式
     * @param timeoutMs 该渠道的超时预算
     */
    record PostProcessRequest(
            Channel channel,
            String requestId,
            String operation,
            String imageUrl,
            String secondaryImageUrl,
            String instruction,
            String maskUrl,
            String outputFormat,
            int timeoutMs) {

        public PostProcessRequest {
            if (channel == null || requestId == null || requestId.isBlank()
                    || operation == null || operation.isBlank()
                    || imageUrl == null || imageUrl.isBlank()
                    || outputFormat == null || outputFormat.isBlank()
                    || timeoutMs <= 0) {
                throw new IllegalArgumentException("invalid_image_post_process_request");
            }
            requireAbsoluteHttp(imageUrl, "invalid_image_post_process_request");
            if (secondaryImageUrl != null && !secondaryImageUrl.isBlank()) {
                requireAbsoluteHttp(secondaryImageUrl, "invalid_image_post_process_request");
            } else {
                secondaryImageUrl = null;
            }
            if (maskUrl != null && !maskUrl.isBlank()) {
                requireAbsoluteHttp(maskUrl, "invalid_image_post_process_request");
            } else {
                maskUrl = null;
            }
            instruction = instruction == null || instruction.isBlank()
                    ? null : instruction.strip();
        }

        private static void requireAbsoluteHttp(String value, String reason) {
            String candidate = value.strip().toLowerCase(java.util.Locale.ROOT);
            if (!candidate.startsWith("https://") && !candidate.startsWith("http://")) {
                throw new IllegalArgumentException(reason);
            }
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
     * 加工结果。
     *
     * @param imageUrl 产出图片的位置
     * @param width 产出宽度，厂商未报告时为 {@code null}。
     *        它**不是**计费依据，而是让网关能回答「4K 超分到底产出多大」；
     *        一个报不出来又按像素计费的服务，在定价之前必须先解决这件事
     * @param height 产出高度，厂商未报告时为 {@code null}
     */
    record PostProcessResult(String imageUrl, Integer width, Integer height) {

        public PostProcessResult {
            if (imageUrl == null || imageUrl.isBlank()) {
                throw new IllegalArgumentException("invalid_image_post_process_result");
            }
            if ((width != null && width <= 0) || (height != null && height <= 0)) {
                throw new IllegalArgumentException("invalid_image_post_process_result");
            }
        }
    }

    /**
     * 图片后期服务的能力词表。
     *
     * <p><b>常量而不是硬编码字符串，因为这些名字要被三处一起读</b>：
     * 适配器声明自己支持哪些、{@code gateway.model.capabilities} 上挂哪些、
     * 以及管理端的能力守卫拿哪一个去比对。三处各写一遍字面量，
     * 就是那种「改名之后有一处没跟着改、于是某条路由静默失效」的来源。
     *
     * <p>这些名字**目前没有任何适配器实现**，因此把它们挂到一个模型上，
     * 会让 {@code ProviderCapabilityCatalog} 以
     * {@code provider_capability_unavailable} 拒绝那条路由。那是**正确**的
     * 失败方向：它说的是「这个能力还没有人实现」，而不是「保存成功然后
     * 每次请求 503」。接入第一个后期服务时，新增一个实现本接口的 bean，
     * 这些名字就自动变得可挂——能力矩阵从 bean 读，不读硬编码表。
     */
    public static final class Capabilities {

        private Capabilities() {
        }

        /** 去背景、保护主体、选中换色区域。需求的首批预留项。 */
        public static final String MATTING = "IMAGE_MATTING";

        /** 图片复刻、产品精修、替换背景、局部修改。需求的首批预留项。 */
        public static final String EDIT = "IMAGE_EDIT";

        /** 小图放大、清晰度增强、高分辨率导出。 */
        public static final String UPSCALE = "IMAGE_UPSCALE";

        /** 服装上身、人物穿搭。**唯一需要两张输入图**的一项。 */
        public static final String TRY_ON = "IMAGE_TRY_ON";

        /**
         * 全部能力名，供管理端的允许清单与能力目录使用。
         *
         * <p>{@code OCR}、{@code IMAGE_QC} 与 {@code MODERATION} 刻意**不在**
         * 这里：前两者属于 {@link ImageAnalysisAdapter} 的词表，
         * 而内容审核**不应该**成为一个新的适配器能力——
         * 平台已经有完整的内容安全栈（gateway V18/V19/V36、
         * platform V24/V25、{@code SafetyEnforcementWebFilter}）。
         * 再加一条并行的审核路径会把策略分成两半，
         * 而两半策略意味着两套不一致的判断。
         */
        public static final Set<String> GENERATIVE = Set.of(MATTING, EDIT, UPSCALE, TRY_ON);
    }
}
