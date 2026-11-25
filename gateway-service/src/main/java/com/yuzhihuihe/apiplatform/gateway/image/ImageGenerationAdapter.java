package com.yuzhihuihe.apiplatform.gateway.image;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 能够根据提示词（可选地根据一张输入图）生成图片的供应商。
 *
 * <p>与 {@code VideoGenerationAdapter} 分开，而不是复用它，因为两者的上游协议
 * 恰好相反：视频是异步任务协议——提交返回 task id、任务被轮询、只有终态才产出结果——
 * 而图片生成是**同步**的：一次请求就是一次生成，一次响应就是全部结果。
 * 把两者塞进同一个接口，就意味着要为一个同步应答发明一套 task 状态机，
 * 或者让视频路径假装自己可以在一次调用内拿到结果。两份契约各自保留，
 * 只共享渠道与路由这两个概念。
 *
 * <p>与视频的第二个差别是计费单位：视频按秒结算，图片按**张**结算，
 * 一次生成产出几张就结算几张。因此结果里携带的是可计费张数，
 * 而不是可计费秒数。
 *
 * <p>实现方负责把某一个厂商的请求/响应翻译成这些类型。
 * 本接口之上的任何代码都不知道用的是哪个厂商。
 */
public interface ImageGenerationAdapter {

    /**
     * 网关层面接受的尺寸档位；与
     * {@link ImageGenerationService#SIZE_TIERS} 同源。
     *
     * <p>它是**平台词汇表**，不是某个厂商的能力清单：厂商完全可能提供这里
     * 没有的档位，那种档位在提交时会被网关以 {@code invalid_image_request}
     * 拒绝，因此目录把它报进 {@code parameters.operator}，而不是当作一个
     * 可选项下发。
     *
     * <p>{@code 1.5K} 曾经正是这样一条「厂商有、平台没有」的档位，代价是客户端
     * 只能买 1K 或 2K、中间档位缺失。现在它与另外三档一视同仁：网关 V115 的
     * 费率卡、{@code image_alias_tier} 与任务表，平台 V106 的维度表都接受它。
     */
    Set<String> PLATFORM_SIZE_TIERS = Set.of("1K", "1.5K", "2K", "4K");

    /**
     * 平台下发给客户端的画幅白名单。
     *
     * <p>与视频路径的 {@code PLATFORM_ASPECT_RATIOS} 取**同一个集合**是刻意的：
     * 客户端在两个生成页上渲染同一组比例，不必为图片再学一套。
     *
     * <p>它与视频路径的关键差别是：图片的厂商契约里**没有画幅参数**。比例要么
     * 写在提示词里由模型判断（就是客户端 UI 上的「自适应」），要么由客户端换算成
     * {@code 宽x高} 提交。因此这个白名单是**平台**的口径，而其中哪些真的可提交，
     * 由适配器画像里的像素与比例边界筛出来（见 {@link ImageSubmissionProfile}）。
     */
    Set<String> PLATFORM_ASPECT_RATIOS = Set.of("1:1", "4:3", "3:4", "16:9", "9:16");

    /** 网关层面接受的单边像素下限；与 {@code ImageGenerationService.MIN_EDGE} 同源。 */
    int PLATFORM_MIN_EDGE = 64;

    /** 网关层面接受的单边像素上限；与 {@code ImageGenerationService.MAX_EDGE} 同源。 */
    int PLATFORM_MAX_EDGE = 4096;

    /** 与 {@code gateway.provider.provider_type} 匹配。 */
    String providerType();

    /**
     * 本适配器能够服务的能力。仅支持生图的适配器返回 {@code IMAGE_GEN}；
     * 同时提供对话的厂商可以列出更多，路由层按所请求的能力做匹配。
     */
    Set<String> capabilities();

    /**
     * 校验供应商特有的请求画像，不预留配额，也不发起网络调用。
     *
     * <p>图片厂商在尺寸档位、响应格式与输入图支持上的契约差异很大。
     * 服务层在解析出渠道之后、预留张数之前调用本钩子，
     * 因此本地即可发现的不良请求不会留下一个已付费的占用。
     */
    default void validateSubmission(GenerationRequest request) {
        // 没有更窄契约的供应商可以依赖网关层面的校验。
    }

    /**
     * 该上游模型**允许被请求**哪些参数：尺寸档位、显式像素尺寸的边界、
     * 参考图张数与图层拆分。
     *
     * <p>它与 {@link #validateSubmission(GenerationRequest)} 必须描述同一个契约，
     * 却服务于不同对象：校验在提交时拒绝非法请求，而本方法让
     * {@code GET /v1/image-models} 能在**提交之前**把这些边界告诉客户端。
     * 只提供校验、不提供画像的适配器，其客户只能靠试错发现边界——
     * 而生图每一次越界的尝试都是一次**已付费**的厂商调用。
     *
     * <p>默认答案覆盖网关自身的准入边界（1K/1.5K/2K/4K、任意 {@code 宽x高}、
     * 单边 64–4096 像素、8 张参考图、支持图层拆分），也就是
     * {@code ImageGenerationService} 会接受的请求。它绝不是「上游什么都支持」：
     * 适配器若知道自己更窄，就必须覆盖它，否则目录会把客户送进一次失败的生成。
     *
     * @param upstreamModel 该渠道配置的厂商模型名
     */
    default ImageSubmissionProfile submissionProfile(String upstreamModel) {
        return ImageSubmissionProfile.gatewayDefaults();
    }

    /**
     * 生成图片并返回厂商的应答。
     *
     * <p>对同一次逻辑生成，在没有幂等键的情况下绝不能被调用两次：
     * 重复调用对大多数厂商而言就是重复扣费。适配器内部绝不重试。
     */
    Mono<GenerationResult> generate(GenerationRequest request);

    /**
     * @param channel 解析出的上游渠道，包含其已解密的凭证
     * @param requestId 网关请求 id，可用作厂商侧的幂等键
     * @param prompt 生成提示词；图生图且要求图层拆分时可以为空
     * @param inputImageUrls 可选的图生图输入，1~8 个绝对 http(s) URL（多参考图）；
     *        为 null 或空表示文生图
     * @param size 交给厂商的尺寸：档位字符串（{@code 2K}）或 {@code 宽x高}
     * @param sizeTier {@code size} 归约出的 {@code 1K} / {@code 1.5K} / {@code 2K} / {@code 4K}
     *        档位，用于费率卡与别名的已售档位校验
     * @param imageCount 调用方要求的张数，也是预留上限；网关一次请求只买一张
     *        （服务层的上限就是 1），因此这里不再是「最多几张」
     * @param responseFormat {@code url} 或 {@code b64_json}
     * @param watermark 是否要求厂商加水印
     * @param layerDecomposition 是否要求图层拆分；仅在提供了**恰好一张**输入图且没有
     *        提示词时有意义。它按次计费：一次调用一个单位，与返回多少个图层无关
     * @param timeoutMs 该渠道的超时预算
     */
    record GenerationRequest(
            Channel channel,
            String requestId,
            String prompt,
            List<String> inputImageUrls,
            String size,
            String sizeTier,
            int imageCount,
            String responseFormat,
            boolean watermark,
            boolean layerDecomposition,
            int timeoutMs) {

        public GenerationRequest {
            if (channel == null || requestId == null || requestId.isBlank()
                    || size == null || size.isBlank()
                    || sizeTier == null || sizeTier.isBlank()
                    || imageCount <= 0
                    || responseFormat == null || responseFormat.isBlank()
                    || timeoutMs <= 0) {
                throw new IllegalArgumentException("invalid_image_generation_request");
            }
            boolean hasImages = inputImageUrls != null && !inputImageUrls.isEmpty();
            // 文生图靠提示词，图生图靠输入图。两者都没有的请求对厂商而言是空的，
            // 而它仍然会产生一次付费调用，因此在越过厂商边界之前就拒绝。
            if ((prompt == null || prompt.isBlank()) && !hasImages) {
                throw new IllegalArgumentException("invalid_image_generation_request");
            }
            // 图层拆分只能拆**一张**已有的图；多参考图没有「拆哪张」的语义，
            // 静默丢掉多余的输入图会让客户以为自己买的是图层拆分。
            if (layerDecomposition && (inputImageUrls == null || inputImageUrls.size() != 1)) {
                throw new IllegalArgumentException("invalid_image_generation_request");
            }
        }

        /**
         * 只有单张 URL（或什么都没有）的调用方。
         *
         * <p>保留这个重载是为了让「单图」这条既有路径的每一处调用点不必
         * 为列表改型——多参考图是新增能力，旧调用点应当原样工作。
         */
        public GenerationRequest(
                Channel channel,
                String requestId,
                String prompt,
                String inputImageUrl,
                String size,
                String sizeTier,
                int imageCount,
                String responseFormat,
                boolean watermark,
                boolean layerDecomposition,
                int timeoutMs) {
            this(channel, requestId, prompt,
                    inputImageUrl == null || inputImageUrl.isBlank()
                            ? List.of()
                            : List.of(inputImageUrl),
                    size, sizeTier, imageCount, responseFormat, watermark,
                    layerDecomposition, timeoutMs);
        }
    }

    /**
     * 某个上游模型可生成参数的完整画像。
     *
     * <p>每个字段都是**边界**，不是保证。目录据此算出「客户端提交什么不会被拒」，
     * 并在下发之前把越界的取值剔掉——一条下发却提交必被拒的选项，比没有这个
     * 下拉框更糟：客户会按目录去点，然后为一次失败的生成等上几十秒。
     *
     * <p>字段留空的含义与视频路径一致，是「没人能确认」，而不是「什么都行」。
     *
     * @param sizeTiers 适配器受理的尺寸档位（{@link #validateSubmission} 的口径）；
     *        空集表示这个模型一个档位都不受理
     * @param documentedSizeTiers 厂商**文档**口径的档位。它不参与准入，只用于在
     *        {@code parameters.operator} 里报告「文档说支持、代码不受理」这类差异，
     *        因为那正是运维需要实测一次（而不是让客户端去试）的缺口
     * @param explicitPixelSize {@code size} 是否接受 {@code 宽x高}；为 {@code false}
     *        时客户端只能提交档位字符串
     * @param minPixels 显式像素尺寸的像素总量下限（含）；仅当 {@code explicitPixelSize} 为真时有意义
     * @param maxPixels 像素总量上限（含）
     * @param minAspectRatio 宽高比下限（宽 ÷ 高）
     * @param maxAspectRatio 宽高比上限（宽 ÷ 高）
     * @param maxInputImages 单次最多几张参考图；0 表示不支持图生图
     * @param layerDecomposition 是否支持图层拆分
     * @param pixelSizeWithLayerDecomposition 图层拆分是否也接受 {@code 宽x高}。
     *        为 {@code false} 时该模式只能用档位字符串，客户端必须据此收窄选项
     */
    record ImageSubmissionProfile(
            Set<String> sizeTiers,
            Set<String> documentedSizeTiers,
            boolean explicitPixelSize,
            long minPixels,
            long maxPixels,
            double minAspectRatio,
            double maxAspectRatio,
            int maxInputImages,
            boolean layerDecomposition,
            boolean pixelSizeWithLayerDecomposition) {

        public ImageSubmissionProfile {
            sizeTiers = sizeTiers == null ? Set.of() : Set.copyOf(sizeTiers);
            documentedSizeTiers = documentedSizeTiers == null
                    ? Set.of() : Set.copyOf(documentedSizeTiers);
            if (minPixels <= 0 || maxPixels < minPixels
                    || !(minAspectRatio > 0) || maxAspectRatio < minAspectRatio
                    || maxInputImages < 0) {
                throw new IllegalArgumentException("invalid_image_submission_profile");
            }
        }

        /**
         * 网关自身会接受的那份画像。
         *
         * <p>像素边界就是 {@link #PLATFORM_MIN_EDGE}–{@link #PLATFORM_MAX_EDGE}
         * 的正方形域（64×64 到 4096×4096），比例边界是同一组边长的比值域，
         * 因此这里报出的每一条都真的能通过网关校验。
         */
        static ImageSubmissionProfile gatewayDefaults() {
            return new ImageSubmissionProfile(
                    PLATFORM_SIZE_TIERS,
                    PLATFORM_SIZE_TIERS,
                    true,
                    (long) PLATFORM_MIN_EDGE * PLATFORM_MIN_EDGE,
                    (long) PLATFORM_MAX_EDGE * PLATFORM_MAX_EDGE,
                    (double) PLATFORM_MIN_EDGE / PLATFORM_MAX_EDGE,
                    (double) PLATFORM_MAX_EDGE / PLATFORM_MIN_EDGE,
                    ImageGenerationService.MAX_INPUT_IMAGES,
                    true,
                    true);
        }

        /**
         * 「什么都没确认」的画像：没有档位、不接受显式像素尺寸、没有参考图能力。
         *
         * <p>它用在**未记录的厂商模型**上。这里不能退回
         * {@link #gatewayDefaults()}：那会把「没人确认过这个模型」渲染成
         * 「这个模型支持平台能做的一切」，而目录是客户端唯一的依据。
         */
        static ImageSubmissionProfile unavailable() {
            return new ImageSubmissionProfile(
                    Set.of(), Set.of(), false, 1L, 1L, 1d, 1d, 0, false, false);
        }
    }

    /**
     * @param id 渠道 id，用于路由
     * @param providerId 供应商行 id，持久化在生成记录上
     * @param providerType 用于挑选适配器的判别值
     * @param baseUrl 厂商 API 基址
     * @param apiKey 已解密的凭证，厂商不需要凭证时为 null
     * @param upstreamModel 该别名对应的厂商模型名
     * @param timeoutMs 该渠道配置的调用预算，来自 {@code channel.timeout_ms}
     */
    record Channel(
            java.util.UUID id,
            java.util.UUID providerId,
            String providerType,
            String baseUrl,
            String apiKey,
            String upstreamModel,
            int timeoutMs) {

        public Channel {
            if (id == null || providerId == null || providerType == null || providerType.isBlank()
                    || baseUrl == null || baseUrl.isBlank()
                    || upstreamModel == null || upstreamModel.isBlank()) {
                throw new com.yuzhihuihe.apiplatform.gateway.provider.ChannelFaultException(
                        "invalid_image_channel");
            }
            // 预算必须由渠道给出。一个 0 或负数的预算会让调用立刻超时，
            // 那时客户端看到的是一次「明明配了渠道却永远超时」的故障，
            // 而配置本身看起来完全正常。
            if (timeoutMs <= 0) {
                throw new com.yuzhihuihe.apiplatform.gateway.provider.ChannelFaultException(
                        "invalid_image_channel");
            }
        }
    }

    /**
     * 一张已生成的图片：要么是一个位置，要么是内联的 base64。
     *
     * <p>两者必须恰好有一个存在。两个都在的行会让下游不知道该相信哪一个，
     * 两个都不在的行则是一张没有图片的图片。
     */
    record GeneratedImage(String url, String b64Json) {

        public GeneratedImage {
            boolean hasUrl = url != null && !url.isBlank();
            boolean hasBase64 = b64Json != null && !b64Json.isBlank();
            if (hasUrl == hasBase64) {
                throw new IllegalArgumentException("invalid_image_generation_result");
            }
        }
    }

    /**
     * 一次已完成的生成。
     *
     * @param images 厂商返回的图片，顺序即客户看到的顺序
     * @param billedImages 厂商将据以计费的张数。通常等于 {@code images.size()}，
     *        但厂商可以报告更少的张数（例如对部分失败只收一张的钱），而一次图层
     *        拆分调用无论返回多少张图层都只计 1——它是一次付费调用。服务层据此
     *        结算，并以调用方所请求的张数为上限
     */
    record GenerationResult(List<GeneratedImage> images, int billedImages) {

        public GenerationResult {
            if (images == null || images.isEmpty() || billedImages < 0) {
                throw new IllegalArgumentException("invalid_image_generation_result");
            }
            images = List.copyOf(images);
        }

        /** 成功返回几张就计费几张，这是绝大多数厂商的行为。 */
        public static GenerationResult of(List<GeneratedImage> images) {
            return new GenerationResult(images, images.size());
        }
    }

    /**
     * 就一次**结果未知**的生成向上游问一个确定答案。
     *
     * <p>这个钩子存在的前提是图片协议的不对称：它是同步的，一次请求就是一次生成，
     * 厂商**从不返回 task id**。因此一次读超时或连接中断之后，网关手上没有任何
     * 句柄可以事后去查——它只能等 TTL 到期，然后按政策赌一次。
     *
     * <p>钩子的意义就是给「将来某个厂商支持按请求 id 查结果」留一个位置：
     * 到那时**只需要这一个适配器实现它**，恢复规则本身一个字都不用改，
     * 而误释放率会从「按政策赌」降到零。
     *
     * <p><b>默认值必须是「问不出来」。</b>它与「问出来没生成」的后果完全不同：
     * 前者只能等 TTL 到期后按政策释放（有可能误释放我们已付费的生成），
     * 后者可以立刻释放且毫无风险。把默认值写成后者，等于让每一个还没实现
     * 这个能力的适配器都谎报军情。
     */
    default Mono<RecoveryVerdict> resolve(RecoveryQuery query) {
        return Mono.just(RecoveryVerdict.unresolved());
    }

    /**
     * @param channelId 该次生成当时命中的渠道 id。渠道可能已被删除，因此只给 id，
     *        不给那个渠道对象本身
     * @param providerId 供应商行 id
     * @param upstreamModel 当时使用的厂商模型名
     * @param requestId 网关请求 id，也是交给厂商作幂等键的那个值
     * @param submittedAt 提交时刻，供厂商按时间窗检索
     */
    record RecoveryQuery(
            UUID channelId,
            UUID providerId,
            String upstreamModel,
            String requestId,
            Instant submittedAt) {

        public RecoveryQuery {
            if (requestId == null || requestId.isBlank()) {
                throw new IllegalArgumentException("invalid_image_recovery_query");
            }
        }
    }

    /**
     * 上游对一次结果未知的生成给出的答案。
     *
     * @param status 上游的结论
     * @param images 仅当 {@code status == PRODUCED} 时有意义。**不带图片的 PRODUCED
     *        是契约违规**，构造器会把它降级成 {@code UNRESOLVED}：一次「生成了但
     *        拿不出图」的调用既不能交付也不能计费，把它当成确定答案会让恢复路径
     *        去结算一批客户永远拿不到的图片
     */
    record RecoveryVerdict(Status status, List<GeneratedImage> images) {

        public enum Status { PRODUCED, NOT_PRODUCED, UNRESOLVED }

        public RecoveryVerdict {
            images = images == null ? List.of() : List.copyOf(images);
            if (status == Status.PRODUCED && images.isEmpty()) {
                status = Status.UNRESOLVED;
            }
        }

        /** 问不出来——今天所有渠道的默认答案。 */
        public static RecoveryVerdict unresolved() {
            return new RecoveryVerdict(Status.UNRESOLVED, List.of());
        }

        /** 上游确认这次生成**没有发生**。只有它才允许立刻释放。 */
        public static RecoveryVerdict notProduced() {
            return new RecoveryVerdict(Status.NOT_PRODUCED, List.of());
        }

        /** 上游确认这次生成发生了，并把图片交回来。 */
        public static RecoveryVerdict produced(List<GeneratedImage> images) {
            return new RecoveryVerdict(Status.PRODUCED, images);
        }
    }
}
