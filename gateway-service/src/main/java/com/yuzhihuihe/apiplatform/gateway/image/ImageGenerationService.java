package com.yuzhihuihe.apiplatform.gateway.image;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.file.FileContentTokenSigner;
import com.yuzhihuihe.apiplatform.gateway.file.FileService;
import com.yuzhihuihe.apiplatform.gateway.file.ServableImageTypes;
import com.yuzhihuihe.apiplatform.gateway.limit.PackageSecondRateLimitService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 端到端地执行一次图片生成请求。
 *
 * <p>流程：解析别名已售的尺寸档位、解析渠道、校验供应商特有的画像、
 * 按最大张数预留、持久化一条 request-id 意图、调用上游，随后**在同一次调用内**
 * 按厂商实际返回的张数结算一次。与视频的异步任务不同，这里没有终态轮询，
 * 也没有恢复扫描：一次请求就是一次生成。
 *
 * <p>本类负责四个性质：
 *
 * <ol>
 *   <li><b>先预留、后花费。</b>张数在上游调用之前就针对套餐预留，
 *       因此没有余额的用户无法发起一次最终要由平台买单的生成。
 *   <li><b>按上游实际交付计费。</b>成功返回几张就结算几张，预留与结算之间的
 *       差额被释放回套餐。一次**厂商调用**最多买一张（{@link #MAX_IMAGES}），
 *       但一次**请求**可以买 {@link #MAX_FANOUT} 张——那是 N 次并发调用，
 *       每张各有自己的预留，因此允许部分成功（见 {@link #generateFanout}）。
 *       而厂商仍可能为一次调用返回多张——图层拆分就是这样，因此那条路径按次结算，
 *       恰好一个单位（见 {@link #chargedImages}）。
 *   <li><b>恰好结算一次。</b>终态转换是一条带守卫的 UPDATE，返回受影响行数；
 *       只有观测到 1 的调用方才执行结算。重放同一个幂等键因此永远不会
 *       再买一次生成，也不会再扣一次费。
 *   <li><b>结果未知时绝不释放。</b>只有厂商显式的 4xx 拒绝才是确定性的拒绝；
 *       超时与 5xx 保持预留，因为厂商可能已经接受了一次响应始终未到达的生成。
 *   <li><b>每一次终态都留下经济性证据。</b>结算成功之后由
 *       {@link ImageEconomicsEvidenceWriter} 写入用量与成本证据，因此这次生成
 *       在「调用与额度」里可见，供应商成本也进入经济性上报。此前这条路径扣费正确、
 *       却什么证据都不写，于是生图成本在平台的利润数字里完全不存在。
 * </ol>
 *
 * <p>计费单位是**张**，维度是既有的 {@code IMAGE}：一张图一个单位。
 * 不新增计量器，也不新增维度——{@code IMAGE} 早已是套餐与费率卡上的
 * 一个维度，生图只是第一个按它计费的路径。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    /**
     * 一条生成记录在网关放弃它之前最多保持非终态多久，同时也是它持有的
     * 预留的存活时间。
     *
     * <p>视频把预留持有到任务截止时刻**之后**，好让结算先于通用预留清扫发生。
     * 图片不需要那段余量：同步调用的结算发生在同一个请求内，因此不存在
     * 「稍后才结算」的竞态。结果未知的请求在这个时刻之后由
     * {@link ImageTaskRecoveryJob} 收尾并释放。
     *
     * <p><b>为什么是 8 分钟而不是 5 分钟。</b>适配器最迟在
     * {@code TianYiYunImageGenerationAdapter.CALL_TIMEOUT_CAP}（240 秒）就放弃了，
     * 因此「这次调用已经不可能还在跑」在 4 分钟之后就成立。剩下的时间纯粹是让
     * 客户干等——而他等的是**自己的额度**：开发人员撞上一次超时，旧值会让他
     * 半个小时不能再生成。
     *
     * <p>这个值必须**大于**超时上限，否则恢复扫描会在一次仍在途中的调用身上动手，
     * 把一张正在生成的图当成失败释放掉。480 秒对 240 秒留了一倍余量；
     * 如果以后把超时上限放宽到 240 秒以上，这里必须跟着动。
     */
    static final Duration TASK_TTL = Duration.ofMinutes(8);

    private static final String IMAGE_CAPABILITY_JSON = "[\"IMAGE_GEN\"]";
    private static final String DIMENSION = "IMAGE";
    private static final String UPSTREAM_REJECTED = "upstream_rejected";

    /** 任务过了 TTL 仍无上游结论：按「不可能再有结果」收尾。 */
    static final String TASK_EXPIRED = "gateway_task_expired";

    /**
     * 上游**确认**那次生成没有发生。
     *
     * <p>它与 {@link #TASK_EXPIRED} 分开记录，好让运维一眼看出恢复钩子到底有没有
     * 在真正回答问题：清一色都是前者，说明一个适配器都还没实现
     * {@link ImageGenerationAdapter#resolve}；出现后者，说明它在工作。
     */
    static final String UPSTREAM_CONFIRMED_NOT_PRODUCED = "upstream_confirmed_not_produced";

    /** 恢复扫描每轮最多处理多少条。 */
    static final int RECOVERY_BATCH_SIZE = 50;

    /**
     * 一次请求最多购买几张：**恰好一张**。
     *
     * <p>这个上限就是端点自身的形状，而不是一个可调的运营参数：一次调用就是一次
     * 同步厂商调用，产出恰好一张图。网关不向厂商转发 {@code n}
     * （见 {@link TianYiYunImageGenerationAdapter}），厂商也从未确认
     * 接受它，因此收下「四张」的订单只会交付一张——那种静默少交付正是控制器
     * 以 422 {@code image_count_not_supported} 拒绝 {@code n > 1} 的原因。
     *
     * <p>它同时是服务层自己的边界，而不只是控制器的：{@link #validate} 用它拒绝
     * 更大的张数，因此任何绕过 HTTP 层的调用方（内部调用、重放补偿、将来的第二个
     * 入口）都无法把一个未经校验的张数送进预留。预留按最大值发生，而一次 4K
     * 生成的成本是 1K 的数倍，所以这个边界是钱的问题。
     */
    static final int MAX_IMAGES = 1;

    /**
     * 一次请求最多买几张：**4**。
     *
     * <p>这个数字由实测确定，而不是拍的。2026-10-12 直连上游实测：厂商**受理
     * 但静默忽略** {@code n}——{@code n=2} 与 {@code n=4} 都回 HTTP 200、恰好
     * 一张图、{@code usage.generated_images = 1}，文生图与图生图皆然
     * （见 {@code scripts/Probe-UpstreamImageCount.ps1}）。所以「一次调用出 N 张」
     * 这条路不存在，网关只能自己扇出 N 次厂商调用，而扇出的次数就是这里的上限。
     *
     * <p>为什么是 4 而不是更高：一次 HTTP 请求要在 {@link #FANOUT_BUDGET} 之内
     * 完成 N 路调用与聚合，而图生图单张实测 112~116 秒。4 路并发时墙钟约等于
     * 最慢那张（约 116 秒），相对 240 秒的上限余量约 124 秒；再往上就没有余量了。
     *
     * <p>{@link #MAX_IMAGES} 保持为 1 **不动**：那是单个子任务的张数上限，也是
     * 「厂商产出多于所订就收窄并告警」那条防线所比较的对象。扇出把「一次请求买几张」
     * 提高到 4，但没有任何一次厂商调用被允许买超过 1 张。
     */
    static final int MAX_FANOUT = 4;

    /**
     * 一次扇出请求完成 N 路厂商调用与聚合的总预算。
     *
     * <p>扇出最容易被忽略的代价：超时预算从「一次厂商调用」变成「一次 HTTP 请求」。
     * 今天每个子调用各自可用 240 秒（{@code CALL_TIMEOUT_CAP}），但扇出后它们共用
     * 同一个客户端连接，因此整批必须在客户端超时之前结束。
     *
     * <p>取 240 秒与适配器上限一致：MAX_FANOUT = 4 时实测墙钟约 116 秒，余量充足。
     * 一旦超预算，尚未开始的子任务会被直接判失败并退款，而不是让整个请求被掐断——
     * 后者会让已经出图成功的那几张一起变成「结果未知」。
     */
    static final Duration FANOUT_BUDGET = Duration.ofSeconds(240);

    /** 子任务 request id 的分隔符：{@code "<父 requestId>#<序号>"}。 */
    private static final String FANOUT_KEY_SEPARATOR = "#";

    /** 派生预留 id 的命名空间，避免与任何其它 uuid 派生物相撞。 */
    private static final String FANOUT_RESERVATION_NAMESPACE = "image-fanout-reservation:";

    static final int MAX_PROMPT_LENGTH = 8000;
    static final int MAX_IMAGE_URL_LENGTH = 2000;

    /**
     * 一次生成最多吃几张参考图（多参考图生图）。
     *
     * <p>2026-10-10 实测：上游（天翼云 doubao-seedream-5.0-pro）接受 {@code image}
     * 为 URL 数组并在 {@code usage.input_images} 中如实报出张数；产品侧确认上限为
     * 8 张（绝大多数用户用 4 张做参考）。这里与上游对齐，超过的部分在网关侧
     * 以 422 {@code image_input_count_exceeded} 拒绝，而不是让厂商去给一句
     * 无法分支的错误。
     */
    static final int MAX_INPUT_IMAGES = 8;

    /**
     * 外部文件 id 的长度上限。
     *
     * <p>网关自己生成的形状是 {@code file_} + 32 位十六进制（34 字符）。
     * 这里留出余量，但绝不允许一个任意长的字符串被当成 id 带进查询。
     */
    static final int MAX_IMAGE_FILE_ID_LENGTH = 128;

    /** 上传接口给出的外部文件 id 前缀。 */
    static final String FILE_ID_PREFIX = "file_";

    /**
     * 一次成功生成了多少张，就至少有多少字节要写进幂等锚点。
     * 超过它的载荷（大尺寸图片的 base64）不再镜像：把它写进数据库
     * 换不来任何契约上的收益，而重放会以
     * {@code image_generation_result_not_replayable} fail-closed，
     * 而不是再买一次生成。
     */
    static final int MAX_STORED_RESULT_BYTES = 8 * 1024 * 1024;

    static final Set<String> RESPONSE_FORMATS = Set.of("url", "b64_json");
    static final String DEFAULT_SIZE = "2K";

    /**
     * 已售档位由小到大；序号同时用于「不得大于已售档位」的比较。
     *
     * <p>{@code 1.5K} 在列表中而不是被跳过：它是厂商文档里的一档（Seedream 5.0
     * pro 是 1K/1.5K/2K），一度只是「能读不能交」。把它拒绝掉的真实后果不是
     * 「客户少一个选项」，而是**客户端拿到的档位阶梯与厂商不一致**：客户想按
     * 中间档付费、平台却只能卖 1K 或 2K 两档。现在它是一等档位——目录会把它
     * 列进 {@code submittableSizeTiers}，提交与落库、费率卡、平台控制台四处
     * 词表由网关 V115 与平台 V106 一起放开。
     */
    static final List<String> SIZE_TIERS = List.of("1K", "1.5K", "2K", "4K");

    private static final Pattern WIDTH_HEIGHT = Pattern.compile("^(\\d{2,4})[xX](\\d{2,4})$");
    private static final int MIN_EDGE = 64;
    private static final int MAX_EDGE = 4096;

    private static final int TIER_1K_MAX_EDGE = 1024;
    private static final int TIER_1_5K_MAX_EDGE = 1536;
    private static final int TIER_2K_MAX_EDGE = 2048;

    /**
     * 错误分类：厂商已产出、但网关无法向客户计费的生成。
     *
     * <p>与一次普通的失败不同：图片确实存在，而客户被拒绝交付，
     * 这是需要对账的事件，而不是一个单纯的失败。
     */
    static final String SETTLEMENT_REFUSED = "gateway_settlement_refused";

    /**
     * 错误分类：扇出请求里这一张还没开始就被放弃（总预算已尽）。
     *
     * <p>它与"厂商拒绝"分开记录：那个是上游说不，这个是网关自己说不。
     * 运维看到的分布不同，处置也不同——前者要看厂商，后者要看
     * {@link #FANOUT_BUDGET} 与 MAX_FANOUT 是否配得下实测耗时。
     */
    static final String FANOUT_ABANDONED = "gateway_fanout_budget_exhausted";

    private final QuotaService quotaService;
    private final ImageTaskRepository taskRepository;
    private final ImageRouteRepository routeRepository;
    private final ImageAdapterRegistry adapterRegistry;
    private final ObjectMapper objectMapper;
    private final PackageSecondRateLimitService packageUnitRateLimitService;
    private final ImageEconomicsEvidenceWriter economicsEvidence;

    /**
     * 图生图的两块拼图：把「客户端上传回来的文件」换成厂商拉得取的地址。
     *
     * <p>两者都**不做 null 兜底**：缺了它们，{@code image_file_id} 会变成一条
     * 静默失效的入口——客户端以为传了参考图，而发给厂商的请求里根本没有图，
     * 那次生成仍然按张计费。
     */
    private final FileService fileService;
    private final FileContentTokenSigner contentTokenSigner;

    @Autowired
    ImageGenerationService(
            QuotaService quotaService,
            ImageTaskRepository taskRepository,
            ImageRouteRepository routeRepository,
            ImageAdapterRegistry adapterRegistry,
            ObjectMapper objectMapper,
            PackageSecondRateLimitService packageUnitRateLimitService,
            ImageEconomicsEvidenceWriter economicsEvidence,
            FileService fileService,
            FileContentTokenSigner contentTokenSigner) {
        this.quotaService = quotaService;
        this.taskRepository = taskRepository;
        this.routeRepository = routeRepository;
        this.adapterRegistry = adapterRegistry;
        this.objectMapper = objectMapper;
        this.packageUnitRateLimitService = packageUnitRateLimitService == null
                ? PackageSecondRateLimitService.unlimited() : packageUnitRateLimitService;
        // 有意不做 null 兜底：一个「悄悄不写证据」的写入器正是本类此前那个
        // 让生图成本对平台不可见的缺陷，因此缺失的协作者必须是启动期失败。
        this.economicsEvidence = economicsEvidence;
        this.fileService = fileService;
        this.contentTokenSigner = contentTokenSigner;
    }

    /** 没有已售出的每分钟张数速率需要强制执行：套餐的张数额度依然有效。 */
    ImageGenerationService(
            QuotaService quotaService,
            ImageTaskRepository taskRepository,
            ImageRouteRepository routeRepository,
            ImageAdapterRegistry adapterRegistry,
            ObjectMapper objectMapper,
            ImageEconomicsEvidenceWriter economicsEvidence,
            FileService fileService,
            FileContentTokenSigner contentTokenSigner) {
        this(quotaService, taskRepository, routeRepository, adapterRegistry, objectMapper,
                PackageSecondRateLimitService.unlimited(), economicsEvidence,
                fileService, contentTokenSigner);
    }

    /**
     * 生成图片。
     *
     * <p>request id 就是幂等键：重放同一个 request id 会返回既有结果，
     * 而不是再买一次生成。
     *
     * <p>两条路径在这里分岔，而 {@code n = 1} 那条**逐字节保持原样**：
     * 单图请求走的一直是原来的 {@link #generateSingle}，扇出只被 {@code n > 1}
     * 触发。这是本次改造的回归底线——单图的行为、张数与扣费都不该有任何变化。
     *
     * <p><b>重放必须在解析输入图之前发生。</b>这不是顺序偏好：一次幂等重放
     * 不需要图，而那张上传图可能已经过了 24 小时保留期——若先解析，一次本该返回
     * 既有结果的调用会变成一个 404，等于把一个已经交付过的生图结果弄丢了。
     * 因此这里只做「是不是重放」这一个判断，解析与生成都留在非重放那一支。
     */
    public Mono<GenerationView> generate(SubmitCommand command) {
        validate(command);
        return taskRepository.findByRequestId(command.requestId())
                // 已有这一行：重放它，绝不重买。扇出请求的父行同样在这里被认出来，
                // 并由 replay 把整族聚合出去（见 replay 里 requestedImages > 1 那支）。
                .flatMap(existing -> replay(existing, command))
                .switchIfEmpty(Mono.defer(() -> resolveInputImage(command)
                        .flatMap(resolved -> resolved.imageCount() > 1
                                ? generateFanout(resolved)
                                : generateSingle(resolved))));
    }

    /**
     * 一次只买一张：既有的那条路径，行为不变。
     *
     * <p>准入（尺寸档位、张/分钟）刻意留在**这里**而不是提升到 {@link #generate}：
     * 它必须在"这次不是重放"之后才发生，否则一次重放会凭白吃掉客户这一分钟的
     * 张数额度——而重放没有买任何新东西。
     */
    private Mono<GenerationView> generateSingle(SubmitCommand command) {
        return generateNew(command).then(Mono.<GenerationView>empty())
                // generateNew 把结果落在记录里；读回来的那一行才是对外的视图。
                .switchIfEmpty(Mono.defer(() -> taskRepository
                        .findByRequestId(command.requestId())
                        .map(this::view)));
    }

    /**
     * 把「客户端上传回来的文件 id」换成一个厂商拉得取的短时效地址。
     *
     * <p><b>只在新生成这一侧做，重放那条路径不碰它。</b>一次幂等重放不需要图，
     * 而那张上传图可能已经过了 24 小时保留期——若在重放时解析，一次本该返回
     * 既有结果的调用会变成一个 404，等于把一个已经交付过的生图结果弄丢了。
     *
     * <p>归属校验由 {@link FileService#requireUsable} 完成（按 tenant+user 查），
     * 因此拿别人的 file id 只会得到 404 {@code file_not_found}——生成的输入图
     * 与生成的调用者必须属于同一个人。
     *
     * <p>签名由网关**现签**：地址的有效期因此从这一刻开始算，覆盖厂商拉图的
     * 整个窗口，而不是从上传那一刻开始算。
     */
    private Mono<SubmitCommand> resolveInputImage(SubmitCommand command) {
        List<String> fileIds = command.inputImageFileIds();
        if (fileIds.isEmpty()) {
            return Mono.just(command);
        }
        if (!contentTokenSigner.enabled()) {
            // 功能关闭时明确失败，而不是把一张**没有图**的请求发给厂商：
            // 后者会让客户为一次「忽略了他传的参考图」的生成付费。
            return Mono.error(fileInputDisabled());
        }
        // 逐个解析、保持调用方的顺序；任何一个不通过（不属于该用户 / 非图片 /
        // 签名不可用）都整体拒绝——静默丢掉某一张参考图等于改了客户的单。
        return Flux.fromIterable(fileIds)
                .concatMap(fileId -> fileService.requireUsable(command.apiKey(), fileId)
                        .flatMap(file -> ServableImageTypes.isServable(file.mimeType())
                                ? Mono.just(contentTokenSigner
                                        .signedUrl(file.externalFileId(), Instant.now())
                                        .orElseThrow(ImageGenerationService::fileInputDisabled))
                                // 非图片（PDF / 文本 / 表格）在**这里**就要拒绝：放它过去，
                                // 厂商会拿到一个它读不出图的地址，然后用一句与配置无关的
                                // 上游错误失败——一次按张付费的调用就这么浪费掉了。
                                : Mono.error(new ResponseStatusException(
                                        HttpStatus.UNPROCESSABLE_ENTITY,
                                        "image_file_not_an_image"))))
                .collectList()
                .map(command::withInputImageUrls);
    }

    private static ResponseStatusException fileInputDisabled() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED, "image_file_input_disabled");
    }

    /**
     * 重放一次已经存在的 request id。
     *
     * <p>三种结果分别处理，而它们都不是「再生成一次」：已成功的返回原样结果；
     * 已失败/已取消的返回冲突，因为同一个键下的第二次购买会是第二次扣费；
     * 仍非终态的说明上一次调用跨过了厂商边界却没有留下结果，
     * 因此同样 fail-closed。幂等键由客户端选定，与别人的记录相撞时
     * 既不返回那条记录，也不在它的 id 下再买一次。
     *
     * <p><b>扇出的重放交付的是整族，而不是那一行本身。</b>一次 {@code n = 4} 的
     * 请求会写下四行任务（每张一行，因为 {@code request_id} 与 {@code reservation_id}
     * 都唯一），而客户端只认得它给的那一个幂等键。因此重放必须把这一族重新聚合
     * 起来，否则客户重放一次 4 张的请求只会拿回 1 张——那正是这次改造要消灭的
     * 静默少交付，只不过换了个地方发生。家族里已经有终态的照常交付，未终结的
     * 不去重买（重放不是重试）。
     */
    private Mono<GenerationView> replay(ImageTaskRepository.TaskRow existing, SubmitCommand command) {
        if (!existing.userId().equals(command.apiKey().userId())
                || !existing.tenantId().equals(command.apiKey().tenantId())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "image_request_id_conflict"));
        }
        // **扇出父行必须在终态判定之前分支。**
        //
        // 父行永远停在 SUBMITTED：它自己不携带图片，而 ck_image_task_result 要求
        // SUCCEEDED 的行必须有 result_images > 0，所以它无法被"结束"。若让它落到下面
        // 的 !terminal() 分支，每一次同键重试都会得到一个 409
        // image_generation_outcome_unknown —— 那恰好把这次修复要交付的
        // 「复用原键重试」变成一句空话。
        //
        // 判据不是"子任务们跑完了没有"，而是"这一行是不是扇出锚点"：只要它是，
        // 这次请求的答案就在它那一族子任务行上，聚合出来即可。
        if (isFanoutAnchor(existing)) {
            return compensateTerminalIfNeeded(existing)
                    .then(Mono.defer(() -> reloadFanout(existing.requestId())
                            .flatMap(family -> aggregateFanout(command, family))));
        }
        if (!existing.terminal()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "image_generation_outcome_unknown"));
        }
        if (!existing.succeeded()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "image_generation_previously_failed"));
        }
        // 终态行可能领先于它的配额转换（并发的重放会看到这一点），
        // 因此在把图片交出去之前，先把那次转换补上。
        return compensateTerminalIfNeeded(existing).then(Mono.defer(() -> {
            if (existing.resultData() == null) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "image_generation_result_not_replayable"));
            }
            return Mono.just(view(existing));
        }));
    }

    /**
     * 这一行是不是一次扇出请求的**父行锚点**。
     *
     * <p>判据只有一条且不会误判：父行是唯一一种 {@code requested_images > 1}
     * 却 {@code fanout_parent_request_id} 为 null 的行。子任务行的 requested_images
     * 恒为 1（每一次厂商调用只买一张），单图行则两者都指向 1 与 null。
     * 因此它既不会把单图请求误认成扇出，也不会把某一张子任务误认成父行。
     */
    private static boolean isFanoutAnchor(ImageTaskRepository.TaskRow row) {
        return row.requestedImages() > 1 && row.fanoutParentRequestId() == null;
    }

    private Mono<Void> generateNew(SubmitCommand command) {
        // 别名所售的尺寸档位要在买下任何东西之前就确定，理由与视频的分辨率
        // 相同：针对某一档位定价的套餐，不得被花在更贵的档位上。
        // 随后，已售出的「张/分钟」速率在此处即作为准入条件，早于张数被预留、
        // 也早于任何渠道被查找：套餐的分钟额度付不起的一次生成，
        // 既不得占用配额，也不得触达厂商。
        return requireSoldSize(command)
                .then(admitUnitRate(command))
                .then(Mono.defer(() -> generateAtChannel(command)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 扇出：一次请求买 N 张
    //
    // 为什么要扇出而不是透传 n：2026-10-12 直连上游实测，厂商**受理但静默忽略**
    // n —— n=2 与 n=4 都回 HTTP 200、恰好一张图、usage.generated_images = 1，
    // 文生图与图生图皆然（scripts/Probe-UpstreamImageCount.ps1）。透传它不会报错，
    // 只会让客户按 N 张付费却拿到 1 张。因此 N 张必须由网关自己并发买 N 次。
    //
    // 形状由表约束决定，而不是偏好：image_generation_task.request_id 唯一、
    // reservation_id 上也有唯一索引（V114 的断言会把这两条钉住），所以 N 张图
    // 必然是 N 行任务、N 个独立预留。这恰好就是"允许部分成功"需要的形状——
    // 一张失败只释放它自己的那一个预留，退款天然按张，不需要发明"部分退款"。
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 一次扇出请求：启动 N 个子任务，等齐，聚合。
     *
     * <p>三条要点：
     *
     * <p>① <b>先确认付得起，再买。</b>N 个预留全部成功之后才发出任何一次厂商调用。
     * 否则会出现"4 张里前 2 张占上了、第 3 张额度不足"的中间态：钱已经花了，
     * 交付却注定不完整。任何一次预留失败都会释放已经占用的那些，然后整体拒绝。
     *
     * <p>② <b>重放只补做还没终结的那些。</b>已有终态的子任务直接交付，绝不重买；
     * 只有 SUBMITTED 的子任务会被重新跑一次。这就是"同一个幂等键重放可以补齐
     * 部分失败"的实现——也是扇出相对"客户端并发 N 次"最大的体验优势。
     *
     * <p>③ <b>按分钟的张数速率按 N 次准入。</b>扇出一次请求就是买 N 张，
     * 只准入一次会让套餐的"张/分钟"被绕开 N 倍。
     */
    private Mono<GenerationView> generateFanout(SubmitCommand command) {
        return requireSoldSize(command)
                .then(admitFanoutUnitRate(command))
                .then(Mono.defer(() -> resolveChannel(command)))
                .flatMap(channel -> reserveFanoutChildren(command, channel)
                        // **锚点在预留之后写。**顺序是被外键定的：
                        // image_generation_task.reservation_id 引用 quota_reservation，
                        // 而锚点自己没有任何预留——它必须借用某一条真实存在的预留行。
                        // 放在预留之前写，就只能填一个推导出来的 id，那会直接撞上
                        // image_generation_task_reservation_id_fkey（这条在开发时确实撞到过）。
                        //
                        // 把锚点放在后面并不会削弱幂等：N 个子任务的预留 id 是由父请求 id
                        // 确定性派生的，而 QuotaService.reserve 按 reservationId 幂等，
                        // 子任务行的 request_id 上还有唯一约束——所以并发重试在锚点还没写下的
                        // 那个窗口里也不会重复购买，最多是多做几次幂等的插入尝试。
                        .flatMap(reservations -> persistFanoutAnchor(
                                        command, channel, reservations.get(0).id())
                                .then(Mono.defer(() -> executeFanoutChildren(
                                        command,
                                        channel,
                                        adapterRegistry.forProviderType(channel.providerType()),
                                        reservations))))
                        .then(Mono.<GenerationView>empty())
                        // 子任务行已经落库，读回来聚合才是对外的答案。
                        .switchIfEmpty(Mono.defer(() -> reloadFanout(command.requestId())
                                .flatMap(family -> aggregateFanout(command, family)))));
    }

    /**
     * 按张数逐次准入"张/分钟"速率。
     *
     * <p>单图路径只准入一次，因为一次只买一张。扇出买 N 张，因此这里准入 N 次——
     * Redis 计数器是自增的，N 次准入就是 N 张的额度，一次不够就整体拒绝（429），
     * 而不是先把图买回来再发现超额。
     *
     * <p>中途被拒时，先前那几次准入的计数不会被退回：没有可用的回退操作
     * （{@code PackageSecondRateLimitService} 只有 admit / record / used）。
     * 代价是这一分钟少掉一张的余量，而不是多交付一张——方向是安全的。
     */
    private Mono<Void> admitFanoutUnitRate(SubmitCommand command) {
        return Flux.range(1, command.imageCount())
                .concatMap(index -> admitUnitRate(command))
                .then();
    }

    /**
     * 读回一次扇出请求的全部子任务，以及一个可以拿来读父级信息的代表行。
     *
     * <p>空的一族是**不可能**的：调用它的每一处都已经知道这一族存在（要么刚写完
     * 锚点，要么刚跑完子任务，要么父行声称自己是一次成功的扇出）。真出现空，
     * 说明父子链接没被写对——那是会让重放静默返回不完整结果的那类缺陷，
     * 因此这里 fail-closed 并说清原因，而不是让一个 {@code get(0)} 抛出一句
     * 与现场无关的 {@code IndexOutOfBoundsException}。
     */
    private Mono<FanoutFamily> reloadFanout(String parentRequestId) {
        return taskRepository.findByFanoutParent(parentRequestId)
                .collectList()
                .map(children -> {
                    if (children.isEmpty()) {
                        throw new IllegalStateException(
                                "image_fanout_family_missing:" + parentRequestId);
                    }
                    return new FanoutFamily(children, children.get(0));
                });
    }

    /**
     * 为 N 张图各预留一张的额度，全部成功才算数。
     *
     * <p>预留 id 由父请求 id 与序号**确定性派生**（见 {@link #fanoutReservationId}），
     * 因为 {@link QuotaService#reserve} 是按 reservationId 幂等的：重放一次失败的
     * 扇出请求会命中同一批预留，而不是再占一份额度。
     *
     * <p>顺序预留、逐个累加，任何一个失败就释放已占用的那些。全部预留成功后才返回
     * 那一串预留，供调用方去写锚点。
     */
    private Mono<List<QuotaService.Reservation>> reserveFanoutChildren(
            SubmitCommand command, ImageGenerationAdapter.Channel channel) {
        List<QuotaService.Reservation> held = new ArrayList<>();
        return Flux.range(1, command.imageCount())
                .concatMap(index -> reserve(childCommand(command, index)))
                .doOnNext(held::add)
                .then(Mono.defer(() -> Mono.just(List.copyOf(held))))
                // 中途失败：已占用的额度必须还回去，否则客户会为一个从未发生的
                // 请求冻结 N 张额度直到 TTL 到期。
                .onErrorResume(error -> Flux.fromIterable(held)
                        .concatMap(reservation -> quotaService.release(
                                reservation.id(), QuotaService.ReleaseReason.CLIENT_CANCELLED))
                        .then(Mono.error(error)));
    }

    /**
     * 并发跑完全部子任务，返回重新读回的那一家子。
     *
     * <p>并发度就是 {@link #MAX_FANOUT}：一次请求不会推开超过 N 路厂商调用。
     * 子任务是**隔离**的——{@link #runFanoutChild} 把错误的三种归宿（确定拒绝、
     * 结果未知、超预算）都在内部落成终态，因此这里不会有一个孩子的失败掀翻整批。
     * 真出了意料之外的错误，也只把它按失败结算，另外几张照常交付。
     */
    private Mono<FanoutFamily> executeFanoutChildren(
            SubmitCommand command,
            ImageGenerationAdapter.Channel channel,
            ImageGenerationAdapter adapter,
            List<QuotaService.Reservation> reservations) {
        Instant deadline = Instant.now().plus(FANOUT_BUDGET);
        return Flux.range(0, reservations.size())
                .flatMap(offset -> runFanoutChild(
                                command,
                                channel,
                                adapter,
                                offset + 1,
                                reservations.get(offset),
                                deadline)
                        .onErrorResume(error -> {
                            log.warn("image_fanout_child_failed requestId={} index={} error={}",
                                    command.requestId(), offset + 1, error.getMessage());
                            return Mono.empty();
                        }),
                        MAX_FANOUT)
                .then(Mono.defer(() -> reloadFanout(command.requestId())));
    }

    /**
     * 跑一张图，并把它的**每一种**结局都落成自己的终态。
     *
     * <p>四种归宿各自处理，而它们都只影响这一张：
     *
     * <p>① <b>已经终结</b>（重放）：原样保留，绝不重买。
     *
     * <p>② <b>还没有锚点且预算已尽</b>：这一次调用永远不会发生，只需把为它占住的
     * 那个预留还回去。宁可少交付一张并退款，也不要让它去调用厂商、把整批一起拖过
     * 客户端的超时线——那时已经出图成功的那几张会一起变成"结果未知"，钱花了却
     * 一张都交付不了。
     *
     * <p>③ <b>有锚点但预算已尽</b>：不能只还额度。调用方已经为这一张写过锚点，
     * 而那一行停在 SUBMITTED 会被 8 分钟后的恢复扫描当成"结果未知"处理，于是
     * 一个已经被放弃的请求会在后台再被追问一次。因此把它明确转成 CANCELLED 并退款，
     * 并记下 {@link #FANOUT_ABANDONED}——"网关自己说不"与"厂商说不"要分得开。
     *
     * <p>④ <b>正常跑</b>：交给 {@link #generateAtChannel}，它是唯一知道怎么区分
     * "厂商确定拒绝"（落 CANCELLED 并退款）与"结果未知"（保持预留）的地方。
     * 这里**绝不**吞掉那个区分——把结果未知当成失败来退款，会让重试针对同一份套餐
     * 再买一次，而第一次的图可能已经产出。
     */
    private Mono<Void> runFanoutChild(
            SubmitCommand command,
            ImageGenerationAdapter.Channel channel,
            ImageGenerationAdapter adapter,
            int index,
            QuotaService.Reservation reservation,
            Instant deadline) {
        String childRequestId = fanoutChildRequestId(command.requestId(), index);
        return taskRepository.findByRequestId(childRequestId)
                .flatMap(existing -> {
                    if (existing.terminal()) {
                        // 这一张上一轮已经有了答案：交付它，不再花钱。
                        return Mono.empty();
                    }
                    if (Instant.now().isAfter(deadline)) {
                        return abandonAnchoredFanoutChild(command, index, childRequestId);
                    }
                    return fanoutChild(command, channel, adapter, index, childRequestId);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    if (Instant.now().isAfter(deadline)) {
                        log.warn("image_fanout_budget_exhausted requestId={} index={}",
                                command.requestId(), index);
                        // 没有锚点，因此这一张从未开始：只需还回额度。
                        return quotaService.release(
                                reservation.id(), QuotaService.ReleaseReason.CLIENT_CANCELLED);
                    }
                    return fanoutChild(command, channel, adapter, index, childRequestId);
                }));
    }

    /**
     * 跑一次真正的生成，并把"结果未知"留成结果未知。
     *
     * <p>错误在这里被吞掉是**有意**的：一个子任务失败不该掀翻整批。但它被吞得
     * 很窄——{@link #generateAtChannel} 内部已经把确定拒绝落成 CANCELLED 并释放了
     * 预留，因此走到这里的错误要么是"结果未知"（预留保持不动，等客户端重放补齐），
     * 要么是配额/持久化的瞬时故障（同样保持不动，宁可让客户重试也不要凭空退款）。
     */
    private Mono<Void> fanoutChild(
            SubmitCommand command,
            ImageGenerationAdapter.Channel channel,
            ImageGenerationAdapter adapter,
            int index,
            String childRequestId) {
        return generateAtChannel(command, channel, adapter, Integer.valueOf(index), command.requestId())
                .onErrorResume(error -> {
                    log.warn("image_fanout_child_unresolved requestId={} index={} error={}",
                            childRequestId, index, error.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 收尾一个"有锚点、但预算已尽"的子任务：转终态，然后退款。
     *
     * <p>退款只针对这个子任务自己的那一个预留。终态转换带守卫（只能从 SUBMITTED
     * 转入），因此它与一个正在跑的同名子任务不会互相覆盖。
     */
    private Mono<Void> abandonAnchoredFanoutChild(
            SubmitCommand command, int index, String childRequestId) {
        log.warn("image_fanout_budget_exhausted requestId={} index={} anchored=true",
                command.requestId(), index);
        return taskRepository.completeTerminal(
                        childRequestId, "CANCELLED", 0, FANOUT_ABANDONED, null, null)
                .flatMap(updated -> taskRepository.findByRequestId(childRequestId))
                .flatMap(task -> quotaService.release(
                        task.reservationId(), QuotaService.ReleaseReason.CLIENT_CANCELLED));
    }

    /**
     * 把一家子子任务聚合成一次响应。
     *
     * <p>交付规则是"成功几张交付几张"：终态为 SUCCEEDED 的子任务贡献它的图片，
     * 其余（失败、已退款、或结果未知）不贡献。因此 {@code data[]} 可能短于 N，
     * 而 {@code partial} 标记把这件事明确告诉客户端——静默少交付正是这次改造
     * 要消灭的东西。
     *
     * <p>按 {@code fanout_index} 排序，因此同一个幂等键的重放返回的图片顺序
     * 与第一次相同。
     */
    private Mono<GenerationView> aggregateFanout(SubmitCommand command, FanoutFamily family) {
        return Flux.fromIterable(family.children())
                .concatMap(child -> child.terminal()
                        ? compensateTerminalIfNeeded(child).then(Mono.just(child))
                        : Mono.just(child))
                .collectList()
                .map(children -> fanoutView(command, family.parent(), children));
    }

    private GenerationView fanoutView(
            SubmitCommand command,
            ImageTaskRepository.TaskRow parent,
            List<ImageTaskRepository.TaskRow> children) {
        List<ImageGenerationAdapter.GeneratedImage> delivered = new ArrayList<>();
        int billed = 0;
        for (ImageTaskRepository.TaskRow child : children) {
            billed += child.billedImages() == null ? 0 : child.billedImages();
            if (child.succeeded()) {
                // 载荷过大而没有被镜像的那一张仍然交付：它的 result_data 是 null，
                // 但这一次调用刚拿到过图片，而计费是真的。丢掉它等于收了钱不给图。
                delivered.addAll(parseResult(child));
            }
        }
        // 「部分」按交付张数判定，而不是按失败个数：结果未知的那一张既没交付、
        // 也没有退款，客户同样是少拿到了东西。
        boolean partial = delivered.size() < command.imageCount();
        return new GenerationView(
                command.requestId(),
                parent.modelCode(),
                parent.submittedAt() == null
                        ? Instant.now().getEpochSecond()
                        : parent.submittedAt().getEpochSecond(),
                command.imageCount(),
                billed,
                List.copyOf(delivered),
                null,
                partial);
    }

    /**
     * 一个子任务的命令：request id 派生自父请求与序号，张数恒为 1。
     *
     * <p>张数必须归 1：扇出的每一次厂商调用只买一张（厂商那侧本来也只给一张），
     * 因此子任务行上的 {@code requested_images} 是 1，而"厂商产出多于所订就收窄"
     * 那条防线仍然按 1 生效。
     */
    private SubmitCommand childCommand(SubmitCommand command, int index) {
        return command
                .withRequestId(fanoutChildRequestId(command.requestId(), index))
                .withImageCount(1);
    }

    /** 子任务 request id：{@code "<父 requestId>#<序号>"}。 */
    static String fanoutChildRequestId(String parentRequestId, int index) {
        return parentRequestId + FANOUT_KEY_SEPARATOR + index;
    }

    /**
     * 子任务预留 id：由父请求 id 与序号确定性派生。
     *
     * <p>必须是确定性的，因为 {@link QuotaService#reserve} 按 reservationId 幂等
     * （{@code QuotaService.java:110-122}：id 与 requestId/租户/用户/密钥/模型/张数
     * 全部相同才复用，否则 409 {@code reservation_idempotency_mismatch}）。
     * 一个随机的 id 会让重放去占第二份额度，或直接撞上那条冲突。
     */
    static UUID fanoutReservationId(String parentRequestId, int index) {
        return UUID.nameUUIDFromBytes(
                (FANOUT_RESERVATION_NAMESPACE + parentRequestId + FANOUT_KEY_SEPARATOR + index)
                        .getBytes(StandardCharsets.UTF_8));
    }

    /** 一次扇出请求的一家子：子任务按张序，外加一个读父级信息的代表行。 */
    private record FanoutFamily(
            List<ImageTaskRepository.TaskRow> children,
            ImageTaskRepository.TaskRow parent) { }

    /**
     * 在套餐售出了「张/分钟」速率时强制执行它。
     *
     * <p>{@code IMAGE} 实例上的 {@code package_instance.tokens_per_minute} 是每分钟
     * **张数**，与 {@code VIDEO_SECOND} 实例上的每分钟秒数同形。
     * {@link PackageSecondRateLimitService} 自行解析套餐的维度与速率
     * （{@link com.yuzhihuihe.apiplatform.gateway.limit.PackageRateLimits}），
     * 因此张数绝不会被当作 token 或秒计费，而两者都未售出的套餐
     * 在此处直接准入——单次生成的张数已经由 {@link #reserve} 所校验的
     * 套餐张数额度约束。
     */
    private Mono<Void> admitUnitRate(SubmitCommand command) {
        return packageUnitRateLimitService.admit(
                command.apiKey().packageInstanceId(), command.apiKey().userId());
    }

    /**
     * 拒绝大于该别名已售档位的尺寸。
     *
     * <p>每个生图别名都按尺寸档位定价：2K 别名的成本基准是 2K 渲染的成本，
     * 而套餐版本的最坏情况毛利正是依据该档位计算的。渲染一个**更大**的尺寸，
     * 就等于让客户按 2K 的价格买 4K 的算力，这正是该映射要被强制执行
     * 而非仅记录在文档里的原因（网关迁移 V81）。
     *
     * <p>更小的尺寸是允许的：它只会更便宜，因此绝不会击穿那份成本基准。
     * 未登记档位的别名，以及什么也不回答的协作者，都会以各自的错误码
     * fail-closed——空答案绝不能被读作「任何尺寸都允许」。
     */
    private Mono<Void> requireSoldSize(SubmitCommand command) {
        Mono<String> sold = routeRepository.sizeTier(command.modelAlias());
        return (sold == null ? Mono.<String>empty() : sold)
                .defaultIfEmpty("")
                .flatMap(tier -> {
                    int soldRank = tierRank(tier);
                    if (soldRank < 0) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "image_model_size_unregistered"));
                    }
                    if (tierRank(command.sizeTier()) > soldRank) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "image_size_not_sold_by_model"));
                    }
                    return Mono.empty();
                })
                .then();
    }

    /**
     * 跑完一次单图生成：解析渠道、预留、写锚点、调厂商、结算。
     *
     * <p>只返回完成信号，不再顺带把视图带出来——调用方要么自己读回那一行
     * （单图路径），要么读一整族（扇出路径）。把视图的构造留在读回那一步，
     * 是为了让「结果从数据库来」这件事在两条路径上都成立。
     */
    private Mono<Void> generateAtChannel(SubmitCommand command) {
        return resolveChannel(command)
                .flatMap(channel -> generateAtChannel(
                        command,
                        channel,
                        adapterRegistry.forProviderType(channel.providerType()),
                        // 单图路径：它不是扇出的任何一张，因此没有序号也没有父请求。
                        // 任务行不带扇出链接，预留用随机 id，一切与 V114 之前相同。
                        null,
                        null));
    }

    /**
     * 扇出的一次子调用：与单图路径**同一套**步骤，只是张数与身份不同。
     *
     * <p>{@code fanoutIndex} 为 null 时是单图路径：request id 就是客户给的幂等键，
     * 预留按 {@code command.imageCount()}（此时恒为 1）预留，任务行不带扇出链接——
     * 这一切与 V114 之前逐字节相同。
     *
     * <p>非 null 时是扇出的一张：request id 是派生的
     * {@code "<父 requestId>#<序号>"}，预留是**派生 id 的独立预留**（每张图各有
     * 自己的一份，因此一张失败只释放它自己那一份），任务行记下父子链接。
     *
     * <p><b>父请求 id 必须单独传进来，不能从 {@code command} 上取。</b>
     * {@link #childCommand} 已经把 command 的 requestId 换成了子 id，因此
     * {@code command.requestId()} 在扇出路径上指的是**这一张自己**。用它当父 id
     * 会给每一行都写下"自己是自己的父亲"，而家族查询按父 id 取子任务——
     * 结果是每一张都找不到，聚合返回空。
     */
    private Mono<Void> generateAtChannel(
            SubmitCommand command,
            ImageGenerationAdapter.Channel channel,
            ImageGenerationAdapter adapter,
            Integer fanoutIndex,
            String fanoutParentRequestId) {
        SubmitCommand childCommand = fanoutIndex == null
                ? command
                : childCommand(command, fanoutIndex);
        ImageGenerationAdapter.GenerationRequest upstreamRequest =
                generationRequest(childCommand, channel);
        // 供应商约束是确定性的，必须在存在任何张数占用之前校验。
        adapter.validateSubmission(upstreamRequest);
        return reserve(childCommand).flatMap(reservation -> {
            if (!DIMENSION.equals(reservation.usageDimension())) {
                // 该套餐授予的是 token，而不是图片。对它计费会用张数
                // 去消耗 token 余额，因此拒绝。
                return quotaService.release(
                                reservation.id(),
                                QuotaService.ReleaseReason.CLIENT_CANCELLED)
                        .then(Mono.error(new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "image_requires_image_denominated_package")));
            }
            // 在跨越厂商边界之前，先持久化幂等锚点。
            // 并发的请求，或在上报结果未知之后重试的请求，
            // 会看到这一行，从而永远不会再买第二次生成。
            return persistSubmissionIntent(
                            childCommand, channel, reservation, fanoutIndex, fanoutParentRequestId)
                    .flatMap(intent -> {
                        if (!intent.created()) {
                            // 这一张已经有锚点了：重放它，绝不再买一次。
                            return replay(intent.task(), childCommand).then();
                        }
                        return adapter.generate(upstreamRequest)
                                .onErrorResume(error -> releaseRejectedGeneration(
                                        intent.task(), reservation, error))
                                .flatMap(result -> completeSuccess(
                                        intent.task(), result))
                                .then();
                    });
        });
    }

    /**
     * 解析出这次生成要用的渠道与适配器。
     *
     * <p>从 {@link #generateAtChannel} 里提出来，好让扇出在启动 N 个子任务之前
     * **只解析一次**：N 张图必须在同一个渠道上、用同一个适配器生成，否则一次请求
     * 里的几张图会落在不同上游，而聚合响应无法向客户解释这件事。
     */
    private Mono<ImageGenerationAdapter.Channel> resolveChannel(SubmitCommand command) {
        return routeRepository
                .candidates(command.apiKey(), command.modelAlias(), IMAGE_CAPABILITY_JSON)
                .filter(channel -> adapterRegistry.supports(channel.providerType()))
                .next()
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "image_channel_unavailable")));
    }

    /**
     * 在厂商直接拒绝该次调用时释放预留。
     *
     * <p>只有上游显式的 4xx 拒绝才是确定性的拒绝：厂商拒绝了该请求、
     * 没有任何图片被生成，因此没有消耗任何张数，而把客户余额扣住整整一个
     * TTL 就是在撒谎。HTTP 408 属于超时形态，它与所有 5xx、客户端/网络超时、
     * 连接重置以及 2xx 协议失败一样保持预留，因为厂商可能已经完成了一次
     * 响应始终未到达的生成；释放一个结果未知的预留，会让重试针对同一套餐
     * 再买第二次生成，而第一次的图片可能已经产出。
     */
    private <T> Mono<T> releaseRejectedGeneration(
            ImageTaskRepository.TaskRow task,
            QuotaService.Reservation reservation,
            Throwable error) {
        if (!isDefiniteUpstreamRejection(error)) {
            return Mono.error(error);
        }
        log.warn("image_generation_rejected reservationId={} reason={}",
                reservation.id(), error.getMessage());
        return taskRepository
                .completeTerminal(task.requestId(), "CANCELLED", 0, UPSTREAM_REJECTED, null, null)
                // 终态转换就是认领，因此只有看到 1 的调用方写这次失败的经济性证据：
                // 并发的重放不可能写第二套。写的是刚落库的终态行，因此
                // error_category 是真实的原因（upstream_rejected），
                // 而不是转换之前那份内存快照上的 null。
                .flatMap(updated -> updated == 1L
                        ? writeTerminalEvidenceTolerantly(task.requestId(), "CANCELLED", 0)
                        : Mono.<Void>empty())
                .onErrorResume(persistenceError -> {
                    log.warn("image_generation_rejection_persist_failed requestId={} error={}",
                            task.requestId(), persistenceError.getMessage());
                    return Mono.empty();
                })
                .then(quotaService.release(
                        reservation.id(), QuotaService.ReleaseReason.UPSTREAM_FAILURE))
                // 释放失败不得掩盖生成失败的原因：
                // 调用方需要看到的是厂商的显式拒绝。
                .onErrorResume(releaseError -> {
                    log.warn("image_generation_release_failed reservationId={} error={}",
                            reservation.id(), releaseError.getMessage());
                    return Mono.empty();
                })
                .then(Mono.error(error));
    }

    /**
     * 收尾一条**已过期、但仍开放**的图片任务。
     *
     * <p>这个方法存在的唯一理由：图片是同步协议，一次读超时或连接中断之后，
     * 网关**永远不知道自己那笔钱花没花出去**——厂商从不返回 task id，没有任何
     * 句柄可以事后去查。在此之前这种行会永远停在 {@code SUBMITTED}，它的预留也
     * 永远停在 {@code RESERVED}，客户那几张额度一直占着。这不是假设：线上一个
     * 视频用户就是被同一个缺口冻住了他**全部**剩余的视频额度，从 9 月 23 日起
     * 整整 17 天。
     *
     * <p>顺序是刻意的——**先问上游，再按政策赌**：
     *
     * <ol>
     *   <li>上游能证明「没生成」：立刻释放，零风险。这是唯一无代价的路径，
     *       但今天的渠道一个都用不上（见 {@link ImageGenerationAdapter#resolve}）。
     *   <li>上游能证明「生成了」：照常交付并结算，客户拿到图，平台收回钱。
     *   <li>问不出来：TTL 到了就当它没发生过，标 FAILED 并释放。这一步有可能
     *       误释放一次我们其实已付费的生成，代价是 {@code image_effective_cost}
     *       里的供应商单价（{@code image-basic}/2K 是 62 分），**有上界**。
     *       而继续持有它的代价，是客户的额度被无限期冻结——那不是有上界的代价。
     * </ol>
     *
     * <p>释放**不会**让客户白拿一张已经交付的图：任务行本身就是幂等锚点，被标成
     * FAILED 之后同一个 {@code Idempotency-Key} 重放会得到
     * {@code image_generation_previously_failed}，客户必须换一个新 key 才构成
     * 一次真正的新购买——而他确实什么都没拿到。
     */
    /**
     * 恢复扫描的读取入口。
     *
     * <p>包内可见，好让调度层不必直接依赖仓库——与音频路径
     * （{@code AudioGenerationService.openTasks}）保持一致。
     */
    Flux<ImageTaskRepository.TaskRow> openTasks(int limit) {
        return taskRepository.openTasks(limit);
    }

    Mono<Void> recoverExpired(ImageTaskRepository.TaskRow task) {
        if (task == null || !"SUBMITTED".equals(task.status())) {
            return Mono.empty();
        }
        return resolveUnknownOutcome(task).flatMap(verdict -> switch (verdict.status()) {
            case PRODUCED -> completeSuccess(
                    task, ImageGenerationAdapter.GenerationResult.of(verdict.images())).then();
            case NOT_PRODUCED -> expireUnknownOutcome(task, UPSTREAM_CONFIRMED_NOT_PRODUCED);
            case UNRESOLVED -> expireUnknownOutcome(task, TASK_EXPIRED);
        });
    }

    /**
     * 问一次上游。任何失败都归约为「问不出来」——恢复扫描不能因为一次查询失败
     * 就整轮停下，而「问不出来」本来就是安全的默认值。
     */
    private Mono<ImageGenerationAdapter.RecoveryVerdict> resolveUnknownOutcome(
            ImageTaskRepository.TaskRow task) {
        return routeRepository.providerType(task.providerId())
                .filter(adapterRegistry::supports)
                .flatMap(providerType -> adapterRegistry.forProviderType(providerType).resolve(
                        new ImageGenerationAdapter.RecoveryQuery(
                                task.channelId(),
                                task.providerId(),
                                task.upstreamModel(),
                                task.requestId(),
                                task.submittedAt())))
                .defaultIfEmpty(ImageGenerationAdapter.RecoveryVerdict.unresolved())
                .onErrorResume(error -> {
                    log.warn("image_task_resolve_failed requestId={} error={}",
                            task.requestId(), error.getClass().getSimpleName());
                    return Mono.just(ImageGenerationAdapter.RecoveryVerdict.unresolved());
                });
    }

    /**
     * 把一个结果未知的任务按终态收尾，并释放它的预留。
     *
     * <p>带守卫的 UPDATE 先执行，**而它的结果决定是否释放**——与音频的
     * {@code expireAndRelease} 是同一条规则：如果并发的那次调用刚刚把它推到了
     * 终态，处置权就归它，我们不能再去释放一个已经结算过的预留。
     */
    private Mono<Void> expireUnknownOutcome(
            ImageTaskRepository.TaskRow task, String errorCategory) {
        return taskRepository
                .completeTerminal(task.requestId(), "FAILED", 0, errorCategory, null, null)
                .flatMap(claimed -> claimed == 1L
                        ? writeTerminalEvidenceTolerantly(task.requestId(), "FAILED", 0)
                                .then(quotaService.release(
                                        task.reservationId(),
                                        QuotaService.ReleaseReason.RECOVERY_TIMEOUT))
                        : Mono.empty());
    }

    /**
     * 只有当上游显式拒绝了该请求时，供应商状态才是可释放的。
     * 协议失败被表示为 502，必须保持预留，因为即便响应体在其他方面格式良好，
     * 厂商的接受状态也无从得知。
     */
    static boolean isDefiniteUpstreamRejection(Throwable error) {
        return error instanceof ProviderAdapter.ProviderException provider
                && provider.status() >= 400
                && provider.status() < 500
                && provider.status() != 408;
    }

    private Mono<QuotaService.Reservation> reserve(SubmitCommand command) {
        Instant expiresAt = Instant.now().plus(TASK_TTL);
        return quotaService.reserve(new QuotaService.ReserveCommand(
                reservationIdFor(command),
                command.requestId(),
                command.apiKey().tenantId(),
                command.apiKey().userId(),
                command.apiKey().apiKeyId(),
                command.modelAlias(),
                command.imageCount(),
                expiresAt,
                command.imageCount(),
                0,
                DIMENSION));
    }

    /**
     * 这次预留用哪个 id。
     *
     * <p>单图请求：随机 uuid，与 V114 之前完全相同。
     *
     * <p>扇出的子任务：**派生** id。这一点是硬要求，不是风格——扇出在发出任何
     * 厂商调用之前会先把 N 个预留全部占好（见 {@link #reserveFanoutChildren}），
     * 随后每个子任务自己再走一遍 {@code reserve}。若子任务用的是随机 id，
     * 那次调用会去占**第二份**额度：客户的 N 张额度被扣成 2N 张，
     * 而其中一半永远不会被结算或释放，直到 TTL 到期。
     *
     * <p>派生的另一个好处是重放安全：同一次扇出重放时，派生 id 相同，
     * {@link QuotaService#reserve} 的幂等分支会复用既有预留（而不是报冲突）。
     */
    private UUID reservationIdFor(SubmitCommand command) {
        FanoutChild fanout = FanoutChild.parse(command.requestId());
        return fanout == null
                ? UUID.randomUUID()
                : fanoutReservationId(fanout.parentRequestId(), fanout.index());
    }

    /**
     * 一个扇出子请求的身份：它属于哪个父请求、是第几张。
     *
     * <p>子请求 id 的形状是 {@code "<父>#<序号>"}。解析必须**从末尾**进行，
     * 因为父 request id 由客户端给定（幂等键），本身完全可能含有 {@code #}——
     * 从左往右找会把一个正常的父请求误认成子请求，而那会改变它的预留 id。
     */
    private record FanoutChild(String parentRequestId, int index) {

        /** @return 这不是一个扇出子请求时为 null */
        static FanoutChild parse(String requestId) {
            if (requestId == null) {
                return null;
            }
            int separator = requestId.lastIndexOf(FANOUT_KEY_SEPARATOR);
            // 分隔符必须在中间：开头（没有父）或结尾（没有序号）都不是子请求。
            if (separator <= 0 || separator == requestId.length() - 1) {
                return null;
            }
            try {
                int index = Integer.parseInt(requestId.substring(separator + 1));
                return index >= 1
                        ? new FanoutChild(requestId.substring(0, separator), index)
                        : null;
            } catch (NumberFormatException notAChild) {
                return null;
            }
        }
    }

    /**
     * 在调用非幂等的厂商 API 之前，创建持久化的 request id 锚点。
     *
     * <p>如果插入在唯一键竞争中落败，或者其结果未知但随后能读到该行，
     * 则调用方就是一次重放，绝不能向上游提交。若两种结果都无法证明，
     * 操作就 fail-closed，同时预留仍然保持。
     *
     * @param fanoutIndex 这一张在扇出请求里的 1-based 序号；单图路径为 null，
     *        此时任务行不带扇出链接，插入的列与 V114 之前完全相同
     * @param fanoutParentRequestId 这一张所属的父请求 id；单图路径为 null。
     *        它必须由调用方显式传入，不能从 {@code command} 上取——扇出路径上
     *        {@code command} 的 requestId 已经是这一张自己的子 id 了
     */
    private Mono<SubmissionIntent> persistSubmissionIntent(
            SubmitCommand command,
            ImageGenerationAdapter.Channel channel,
            QuotaService.Reservation reservation,
            Integer fanoutIndex,
            String fanoutParentRequestId) {
        ImageTaskRepository.NewTask task = newTask(
                command, channel, reservation, fanoutIndex, fanoutParentRequestId);
        return taskRepository.insert(task)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("image_task_insert_failed")))
                .then(taskRepository.findByRequestId(command.requestId()))
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "image_task_read_after_insert_failed")))
                .map(inserted -> new SubmissionIntent(inserted, true))
                .onErrorResume(error -> existingSubmissionIntent(command, error));
    }

    private Mono<SubmissionIntent> existingSubmissionIntent(
            SubmitCommand command, Throwable insertionError) {
        return taskRepository.findByRequestId(command.requestId())
                .flatMap(existing -> existing.userId().equals(command.apiKey().userId())
                        && existing.tenantId().equals(command.apiKey().tenantId())
                        ? Mono.just(new SubmissionIntent(existing, false))
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "image_request_id_conflict")))
                .switchIfEmpty(Mono.error(insertionError));
    }

    /**
     * 组装一条任务行。抽出来是为了让**扇出父行**与子任务行、单图行走同一条列清单：
     * 三者字段一一对应，任何一处漏填都是"以后才发现"的那类缺陷。
     */
    private ImageTaskRepository.NewTask newTask(
            SubmitCommand command,
            ImageGenerationAdapter.Channel channel,
            QuotaService.Reservation reservation,
            Integer fanoutIndex,
            String fanoutParentRequestId) {
        return new ImageTaskRepository.NewTask(
                UUID.randomUUID(),
                command.requestId(),
                reservation.id(),
                command.apiKey().tenantId(),
                command.apiKey().userId(),
                command.apiKey().apiKeyId(),
                command.apiKey().packageInstanceId(),
                command.modelAlias(),
                channel.providerId(),
                channel.id(),
                channel.upstreamModel(),
                command.sizeTier(),
                command.imageCount(),
                command.imageCount(),
                command.size(),
                command.responseFormat(),
                "SUBMITTED",
                // 与张数一起落库：结算（含重放与补偿）按这条记录做，因此「这是一次
                // 图层拆分调用」必须随记录存活，而不是只活在这次请求的内存里。
                command.layerDecomposition(),
                Instant.now().plus(TASK_TTL),
                // 扇出链接：单图路径两个都是 null（V114 之前的行为），扇出的每一张
                // 记下自己的父请求与序号，重放与恢复扫描据此把一家人捞回来。
                // **父行自己两个也都是 null**（见 persistFanoutAnchor）——它正是靠这一点
                // 与子任务行区分开。
                fanoutParentRequestId,
                fanoutIndex);
    }

    /**
     * 为一次扇出请求写下它的**父行锚点**：用客户端那个幂等键占一行。
     *
     * <h2>为什么必须存在这一行</h2>
     *
     * <p>没有它的时候，一次扇出请求在库里的痕迹只有派生子 id（{@code <父键>#1..#n}），
     * 于是 {@code generate()} 按客户端键查不到任何东西，重试**不是重放而是重新买**：
     * 客户端文档却明确承诺「复用原 Idempotency-Key 重试，系统只补做缺的那几张」。
     * 这一行就是让那句承诺成立的唯一依据。
     *
     * <h2>为什么它是 SUBMITTED 而不是 SUCCEEDED</h2>
     *
     * <p>{@code ck_image_task_result} 要求 SUCCEEDED 的行必须有 {@code result_images > 0}，
     * 而父行自己**不携带任何图片**（每一张都在子任务行上）。文件里也留了注释说明这一点。
     * 所以父行只能停在 SUBMITTED，而"别把它当成一张还没跑完的图"这件事由
     * {@code ImageTaskRepository.openTasks} 负责——它只捞真正的子任务行/单图行。
     *
     * <h2>并发</h2>
     *
     * <p>两个同键请求同时到达时，第二个的插入会撞上 {@code request_id} 唯一约束；
     * 这里把它当作"锚点已存在"接受下来（读回那一行继续），而不是像子任务行那样
     * 把并发视为需要重放。父行本身不花任何钱，重复发现它没有任何代价。
     *
     * <p>调用点把它放在**预留之后**，因此并发窗口里可能出现"两个请求都还没写下锚点"
     * 的瞬间。那不会导致重复购买：子任务的预留 id 由父请求 id 确定性派生
     * （{@link QuotaService#reserve} 按 reservationId 幂等），子任务行的
     * {@code request_id} 上也有唯一约束，所以重复尝试只会命中幂等分支。
     *
     * @param anchorReservationId 一条**已经存在**的预留 id（取第一个子任务的）。
     *        这一列是 NOT NULL 且有外键，因此不能填推导值——开发时用
     *        {@code fanoutReservationId(parent, 1)} 正是撞在
     *        {@code image_generation_task_reservation_id_fkey} 上失败的。
     */
    private Mono<ImageTaskRepository.TaskRow> persistFanoutAnchor(
            SubmitCommand command, ImageGenerationAdapter.Channel channel, UUID anchorReservationId) {
        ImageTaskRepository.NewTask anchor = new ImageTaskRepository.NewTask(
                UUID.randomUUID(),
                command.requestId(),
                // 锚点自己没有预留，因此借用第一个子任务那条**真实存在**的预留：
                // 这一列是 NOT NULL 且有外键，不能填推导值。它只用于"这一行属于哪次消费"
                // 的追溯，锚点从不参与结算（结算以子任务行为准）。
                anchorReservationId,
                command.apiKey().tenantId(),
                command.apiKey().userId(),
                command.apiKey().apiKeyId(),
                command.apiKey().packageInstanceId(),
                command.modelAlias(),
                channel.providerId(),
                channel.id(),
                channel.upstreamModel(),
                command.sizeTier(),
                // requested_images = n 是这一行唯一的"我是扇出"的标记，
                // replay 据此把整族聚合出去。
                command.imageCount(),
                command.imageCount(),
                command.size(),
                command.responseFormat(),
                "SUBMITTED",
                command.layerDecomposition(),
                Instant.now().plus(TASK_TTL),
                // 父行：两个链接列都是 null，与所有子任务行相反。
                null,
                null);
        return taskRepository.insert(anchor)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("image_anchor_insert_failed")))
                .then(taskRepository.findByRequestId(command.requestId()))
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "image_anchor_read_after_insert_failed")))
                // 并发插入落败 = 锚点已由另一个同键请求写下，读回来继续就是正确行为。
                .onErrorResume(error -> taskRepository.findByRequestId(command.requestId()));
    }

    private record SubmissionIntent(ImageTaskRepository.TaskRow task, boolean created) { }

    /**
     * 记录一次成功的生成，然后按厂商实际交付的张数结算一次。
     *
     * <p>带守卫的终态转换就是认领：看到 {@code rowsUpdated == 1} 的调用方是
     * 唯一会结算的调用方，因此并发的重放绝不可能去结算另一个调用方已经结算过的
     * 预留。结果与状态写在同一条 UPDATE 里，因此一行绝不可能在结果尚未存入时
     * 就已经是 SUCCEEDED。
     *
     * <p><b>绝不交付未付费的图片。</b>若扣费被拒绝，赢得转换的调用方会做补偿：
     * 该生成以 FAILED 关闭并带上 {@link #SETTLEMENT_REFUSED}，于是客户被告知
     * 该生成不可用，而不是拿到一批平台从未计费的图片。
     *
     * <p><b>记录经济性。</b>同一个胜出者在扣费之后写入用量证据
     * （{@link ImageEconomicsEvidenceWriter}），而且只有胜出者可以写，因为带守卫的
     * UPDATE 正是使该写入恰好一次的认领。写入失败不会撤销扣费；
     * 它会上报给调用方并记录日志，因为丢失观测绝不能被误认为丢失了钱。
     *
     * <p>返回落地后的那一行，而不是视图：扇出路径要的是「这一张成了没有」，
     * 单图路径再把它 {@link #view} 出去。结果始终从数据库读回，因此两条路径
     * 交付的都是记录里的事实，而不是这次调用的内存快照。
     */
    private Mono<ImageTaskRepository.TaskRow> completeSuccess(
            ImageTaskRepository.TaskRow task, ImageGenerationAdapter.GenerationResult result) {
        int charged = chargedImages(task, result.billedImages());
        String resultData = serializeResult(task.requestId(), result.images());
        return taskRepository
                .completeTerminal(
                        task.requestId(),
                        "SUCCEEDED",
                        charged,
                        null,
                        resultData,
                        result.images().size())
                .flatMap(updated -> updated == 1L
                        ? settle(task, charged)
                                .onErrorResume(UnsettleableImageTaskException.class, refused -> {
                                    log.warn("image_task_settlement_refused requestId={} reason={}",
                                            task.requestId(), refused.getMessage());
                                    return taskRepository
                                            .markSettlementRefused(task.requestId())
                                            // 图片确实生成了、而平台从未收款：这是一次
                                            // 需要运维对账的终态。写下的是一条零用量的
                                            // 真实用量行（不写成本），因此它不会像以前
                                            // 那样对运营完全不可见。
                                            .then(writeTerminalEvidenceTolerantly(
                                                    task.requestId(), "FAILED", 0))
                                            .then(Mono.error(refused));
                                })
                                .then(Mono.defer(() -> economicsEvidence.write(
                                        task, "SUCCEEDED", charged)))
                                .then(Mono.just(true))
                        : Mono.just(false))
                .then(taskRepository.findByRequestId(task.requestId()));
    }

    /** 一次无法完成的结算：该生成必须被拒绝交付，而不是交付出去。 */
    static final class UnsettleableImageTaskException extends RuntimeException {
        UnsettleableImageTaskException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 要计费的张数。
     *
     * <p><b>图层拆分按次计费，恰好一个单位。</b>图层拆分是一次**已付费的厂商调用**，
     * 它恰好产出若干张图层，而厂商对这次调用只收一次钱（一张输入图进去、一层一层
     * 出来）。因此一次调用一个单位才是诚实的收费，按返回的图层数收费就是对同一次
     * 调用重复收钱。厂商的 {@code usage} 在图层调用上完全可能合法地报出
     * {@code generated_images > 1}（它数的是图层），所以这里按请求意图而不是按厂商
     * 自报的张数决定，并且**只有这条路径**不去比对「厂商报的比请求的多」——
     * 对图层调用而言那条告警是误导：多出来的不是多买的生成，而是同一次调用的图层。
     *
     * <p>其余路径保持原样：按厂商报告的张数结算，并以所请求的张数为上限。这个上限
     * 是针对「厂商产出多于所订」的有意收窄——那通常意味着网关的预留没有覆盖实际
     * 产出，因此会被记录日志，而不是被悄悄吸收。
     *
     * <p>这条判断读的是持久化在生成记录上的 {@code layer_decomposition}，而不是
     * 内存里的请求：重放与崩溃后的补偿（{@link #compensateTerminal}）都在几分钟后、
     * 可能在另一个进程里，从同一条记录重新推导出这次扣费，因此标记必须随记录一起
     * 存活（网关迁移 V82）。
     */
    private int chargedImages(ImageTaskRepository.TaskRow task, int billed) {
        if (task.layerDecomposition()) {
            return 1;
        }
        if (billed <= 0) {
            return 0;
        }
        if (billed > task.requestedImages()) {
            log.warn("image_vendor_returned_more_than_requested requestId={} requested={} billed={}",
                    task.requestId(), task.requestedImages(), billed);
            return task.requestedImages();
        }
        return billed;
    }

    /**
     * 按厂商实际交付的张数结算该预留。
     *
     * <p>张数以预留的单位携带，因此配额账本对待它们与 token 完全一致：
     * 套餐余额与按模型的张数配额都从 reserved 转为 used，未被使用的部分
     * 由结算本身释放回套餐。
     *
     * <p>拒绝会被包装，以便调用方能区分「这个预留已无法再计费」——
     * 这种状态必须中止交付——与可能由一次重放解决的瞬时数据库故障。
     */
    private Mono<Void> settle(ImageTaskRepository.TaskRow task, int chargedImages) {
        return quotaService.settle(
                        task.reservationId(),
                        new QuotaService.TokenUsage(chargedImages, 0, chargedImages),
                        QuotaService.UsageQuality.ACTUAL)
                // 套餐实际被计费的张数正是已售出的「张/分钟」速率所统计的对象，
                // 与秒数方向的 record 完全一致。它运行在一次已经提交的结算的尾部
                // 并立即返回，因此既不会延迟、也不会使客户看到的扣费失败。
                .doOnNext(settlement -> packageUnitRateLimitService.record(
                        task.packageInstanceId(), settlement.chargedTokens()))
                .onErrorMap(error -> error instanceof ResponseStatusException
                        ? new UnsettleableImageTaskException(
                                "image_generation_unsettleable:" + error.getMessage(), error)
                        : error)
                .then();
    }

    /**
     * 把厂商返回的图片序列化为要镜像的 {@code data} 数组。
     *
     * @return JSON 文本；超过 {@link #MAX_STORED_RESULT_BYTES} 时为 null，
     *         此时该生成仍然被计费与交付，只是无法被重放
     */
    private String serializeResult(String requestId, List<ImageGenerationAdapter.GeneratedImage> images) {
        ArrayNode data = objectMapper.createArrayNode();
        for (ImageGenerationAdapter.GeneratedImage image : images) {
            ObjectNode entry = data.addObject();
            if (image.url() != null) {
                entry.put("url", image.url());
            } else {
                entry.put("b64_json", image.b64Json());
            }
        }
        String json = data.toString();
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_STORED_RESULT_BYTES) {
            log.warn("image_result_too_large_to_mirror requestId={} bytes={}", requestId, bytes);
            return null;
        }
        return json;
    }

    /**
     * 确保终态生成在其配额占用仍未解决时不会被交付出去。
     *
     * <p>同步路径上这是一道窄缝：赢家在写入终态之后、扣费之前的那一瞬间。
     * 同一个键的并发重放会读到 SUCCEEDED 并试图交付，因此它必须先把那次
     * 扣费补上，而不是交付一批未付费的图片。
     *
     * <p>预留已经结算（或已释放）时配额无事可做，但**证据**可能缺失：崩溃也可能
     * 落在「结算已提交、证据尚未落库」之间，而那一刻之后预留不再是 RESERVED，
     * 于是补偿路径再也不会看它一眼。重放是唯一还会重新看到这条记录的地方，
     * 因此在那里补一次——写入本身是幂等的，已经写过就一行都不会新增。
     */
    private Mono<Void> compensateTerminalIfNeeded(ImageTaskRepository.TaskRow task) {
        if (task == null || !task.terminal()) {
            return Mono.empty();
        }
        return taskRepository.reservationIsReserved(task.reservationId())
                .flatMap(reserved -> reserved
                        ? compensateTerminal(task)
                        : healMissingEvidence(task));
    }

    /**
     * 补写一条已结算终态记录缺失的证据。
     *
     * <p>只补那些**确实该有证据**的终态：已成功的生成（按记录上的张数）、
     * 结算被拒的生成、厂商明确拒绝的取消。其余终态（例如结果未知的取消）
     * 一律放过——它们既没有结算，也没有「厂商没有产出」的证明，
     * 凭空写一条就等于替厂商声明一件无人能证明的事。
     */
    private Mono<Void> healMissingEvidence(ImageTaskRepository.TaskRow task) {
        if (task.succeeded() && task.billedImages() != null && task.billedImages() >= 0) {
            return writeEvidenceTolerantly(task, "SUCCEEDED", task.billedImages());
        }
        if (SETTLEMENT_REFUSED.equals(task.errorCategory())
                || "CANCELLED".equals(task.status())
                        && UPSTREAM_REJECTED.equals(task.errorCategory())) {
            return writeEvidenceTolerantly(task, task.status(), 0);
        }
        return Mono.empty();
    }

    /**
     * 为一条终态记录重试配额转换，并补上缺失的经济性证据。
     *
     * <p>成功与厂商失败按记录的张数结算（包括零，它把占用转成一条已结算的
     * 零用量记录）。已取消的生成只针对厂商 4xx 拒绝所写入的显式
     * {@code upstream_rejected} 标记才释放：结果未知的取消不是厂商没有生成
     * 图片的证明。
     *
     * <p>证据写在配额结果之后：事件里的 {@code package_settled_units} 读的正是
     * 结算刚落库的 {@code quota_reservation.package_settled_tokens}，
     * 因此顺序反了就会申报一个尚未发生的结算。三条路径都是幂等的，
     * 而崩溃恰好落在配额结果与证据之间时，下一次重放会把缺的那半补上。
     */
    Mono<Void> compensateTerminal(ImageTaskRepository.TaskRow task) {
        if (task == null || !task.terminal()) {
            return Mono.empty();
        }
        if ("CANCELLED".equals(task.status())) {
            if (!UPSTREAM_REJECTED.equals(task.errorCategory())
                    || task.billedImages() == null
                    || task.billedImages() != 0) {
                return Mono.empty();
            }
            return quotaService.release(
                            task.reservationId(), QuotaService.ReleaseReason.UPSTREAM_FAILURE)
                    .then(Mono.defer(() -> writeEvidenceTolerantly(task, "CANCELLED", 0)));
        }
        if (!task.succeeded() && !"FAILED".equals(task.status())) {
            return Mono.empty();
        }
        // 结算被拒的生成已被终态路径有意做成不可交付。
        // 它的配额状态需要运维对账，而不是一次自动的零用量结算——
        // 那可能悄悄送掉一次本可计费的生成。它的证据因此也是零用量的：
        // 一次没有任何一方被计费的终态，而不是一笔凭空的成本。
        if (SETTLEMENT_REFUSED.equals(task.errorCategory())) {
            return writeEvidenceTolerantly(task, "FAILED", 0);
        }
        if (task.billedImages() == null || task.billedImages() < 0) {
            log.warn("image_terminal_compensation_skipped requestId={} reason=missing_billed_images",
                    task.requestId());
            return Mono.empty();
        }
        return settle(task, task.billedImages())
                .then(Mono.defer(() -> economicsEvidence.write(
                        task, task.status(), task.billedImages())));
    }

    /**
     * 写入一次终态生成的经济性证据，写入失败只记录、不上抛。
     *
     * <p>用在失败与补偿路径上：那里的首要事实是厂商拒绝、或一次已经提交的释放，
     * 而证据只是它的补充说明；让一次观测失败把客户看到的厂商拒绝换成另一个错误，
     * 会掩盖真正需要知道的原因。成功结算路径不走这里——那条路径上证据是与钱
     * 同等重要的产物，因此它的失败必须被上报（见 {@link #completeSuccess}）。
     */
    private Mono<Void> writeEvidenceTolerantly(
            ImageTaskRepository.TaskRow task, String terminalStatus, int billedImages) {
        return tolerateEvidenceFailure(task.requestId(),
                economicsEvidence.write(task, terminalStatus, billedImages));
    }

    /**
     * 先读回落库的终态行、再写证据，同样只记录失败。
     *
     * <p>失败路径手里那份行是终态转换**之前**读到的快照，它的
     * {@code error_category} 还是 null；真实的原因（{@code upstream_rejected}、
     * {@code gateway_settlement_refused}）是刚落库的那一行才有的，
     * 而一条不说明原因的失败记录等于半个事实。
     */
    private Mono<Void> writeTerminalEvidenceTolerantly(
            String requestId, String terminalStatus, int billedImages) {
        return tolerateEvidenceFailure(requestId, taskRepository.findByRequestId(requestId)
                .flatMap(terminal -> economicsEvidence.write(terminal, terminalStatus, billedImages)));
    }

    private Mono<Void> tolerateEvidenceFailure(String requestId, Mono<Void> write) {
        return write.onErrorResume(error -> {
            log.warn("image_economics_evidence_failed requestId={} error={}",
                    requestId, error.getMessage());
            return Mono.empty();
        });
    }

    private ImageGenerationAdapter.GenerationRequest generationRequest(
            SubmitCommand command, ImageGenerationAdapter.Channel channel) {
        return new ImageGenerationAdapter.GenerationRequest(
                channel,
                command.requestId(),
                command.prompt(),
                command.inputImageUrls(),
                command.size(),
                command.sizeTier(),
                command.imageCount(),
                command.responseFormat(),
                command.watermark(),
                command.layerDecomposition(),
                // 预算来自渠道配置，而不是写死在服务层的常量：
                // 运维把渠道超时从 60 秒改成 150 秒，就该真的生效。
                channel.timeoutMs());
    }

    /**
     * 调用方所看到的内容。绝不暴露厂商的 task id 或原始错误文本。
     *
     * <p>{@code created} 取自记录的提交时刻而不是响应的构造时刻，
     * 因此同一幂等键的重放会返回与第一次完全相同的时间戳。
     */
    private GenerationView view(ImageTaskRepository.TaskRow task) {
        return new GenerationView(
                task.requestId(),
                task.modelCode(),
                task.submittedAt() == null ? Instant.now().getEpochSecond() : task.submittedAt().getEpochSecond(),
                task.requestedImages(),
                task.billedImages() == null ? 0 : task.billedImages(),
                parseResult(task),
                task.errorCategory(),
                // 这张路径上的任务恰好买一张，交付的就是它自己，因此不可能"部分"。
                false);
    }

    /** 解析被镜像的 {@code data} 数组；只有成功的记录才持有它。 */
    private List<ImageGenerationAdapter.GeneratedImage> parseResult(
            ImageTaskRepository.TaskRow task) {
        if (!task.succeeded() || task.resultData() == null) {
            return List.of();
        }
        try {
            JsonNode data = objectMapper.readTree(task.resultData());
            List<ImageGenerationAdapter.GeneratedImage> images = new ArrayList<>();
            for (JsonNode entry : data) {
                String url = text(entry, "url");
                if (url != null) {
                    images.add(new ImageGenerationAdapter.GeneratedImage(url, null));
                    continue;
                }
                String base64 = text(entry, "b64_json");
                if (base64 != null) {
                    images.add(new ImageGenerationAdapter.GeneratedImage(null, base64));
                }
            }
            return List.copyOf(images);
        } catch (Exception error) {
            // 这一行是本进程写下的，因此无法解析属于不变式被破坏；
            // 交付一个空列表会让客户为一次没有任何图片的生成付费。
            throw new IllegalStateException("image_result_unreadable:" + task.requestId(), error);
        }
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    /**
     * 服务层自己的准入边界。
     *
     * <p>张数上限是 {@link #MAX_FANOUT}（4），而不是 {@link #MAX_IMAGES}（1）：
     * 后者是**单个子任务**买几张的上限，扇出把"一次请求买几张"抬到 4，但没有
     * 任何一次厂商调用被允许买超过 1 张。绕过 HTTP 层的调用方（内部调用、重放补偿、
     * 将来的第二个入口）因此也只能把 {@code n} 提到 4，提不上去。
     *
     * <p>这个边界仍然是钱的问题：4 张 4K 的成本是一张 1K 的数倍，而预留按
     * 张数发生。
     */
    private void validate(SubmitCommand command) {
        if (command == null
                || command.apiKey() == null
                || command.requestId() == null || command.requestId().isBlank()
                || command.requestId().length() > 128
                || command.modelAlias() == null || command.modelAlias().isBlank()
                || command.modelAlias().length() > 128
                || command.prompt() != null && command.prompt().length() > MAX_PROMPT_LENGTH
                || command.imageCount() <= 0 || command.imageCount() > MAX_FANOUT
                || command.responseFormat() == null
                || !RESPONSE_FORMATS.contains(command.responseFormat())
                || !validInputImages(command.inputImageUrls())
                || !validInputImageFileIds(command.inputImageFileIds())
                || hasBothInputImages(command)
                || command.layerDecomposition() && countInputImages(command) != 1
                || sizeTier(command.size()) == null
                || !validContent(command)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_image_request");
        }
    }

    /**
     * 提示词与输入图至少要有一个。
     *
     * <p>文生图靠提示词；图生图既可以「改这张图」也可以只要求图层拆分，
     * 后者没有提示词。除此之外，一个既没有提示词也没有图的请求对厂商而言
     * 是空的，而它仍然会产生一次付费调用，因此在本地就拒绝。
     */
    private boolean validContent(SubmitCommand command) {
        if (command.prompt() != null && !command.prompt().isBlank()) {
            return true;
        }
        return hasInputImage(command) && command.layerDecomposition();
    }

    /**
     * 外部文件 id 的形状校验：**每一个**都要符合网关自己签发的前缀。
     *
     * <p>只接受网关自己签发的前缀：一个不以 {@code file_} 开头的值不可能是上传接口
     * 给出的 id，放它进查询只是把一次注定为空的查询变成一次请求。
     */
    private boolean validInputImageFileIds(List<String> inputImageFileIds) {
        if (inputImageFileIds.isEmpty()) {
            return true;
        }
        if (inputImageFileIds.size() > MAX_INPUT_IMAGES) {
            return false;
        }
        return inputImageFileIds.stream().allMatch(this::validInputImageFileId);
    }

    private boolean validInputImageFileId(String inputImageFileId) {
        if (inputImageFileId == null || inputImageFileId.isBlank()) {
            return false;
        }
        String candidate = inputImageFileId.strip();
        return candidate.length() <= MAX_IMAGE_FILE_ID_LENGTH
                && candidate.startsWith(FILE_ID_PREFIX);
    }

    /**
     * 输入图只能有一个来源：公网 URL 与上传文件 id 不能同时给。
     *
     * <p>同时给 URL 与 file id 时**不猜哪一个优先**：猜错的那一次是按张付费的，
     * 而客户拿到的图不是他要的那张。宁可 422。
     */
    private boolean hasBothInputImages(SubmitCommand command) {
        return !command.inputImageUrls().isEmpty() && !command.inputImageFileIds().isEmpty();
    }

    private boolean hasInputImage(SubmitCommand command) {
        return countInputImages(command) > 0;
    }

    private int countInputImages(SubmitCommand command) {
        return command.inputImageUrls().size() + command.inputImageFileIds().size();
    }

    private boolean validInputImages(List<String> inputImageUrls) {
        if (inputImageUrls.isEmpty()) {
            return true;
        }
        if (inputImageUrls.size() > MAX_INPUT_IMAGES) {
            return false;
        }
        return inputImageUrls.stream().allMatch(this::validInputImage);
    }

    private boolean validInputImage(String inputImageUrl) {
        if (inputImageUrl == null || inputImageUrl.isBlank()) {
            return false;
        }
        String candidate = inputImageUrl.trim();
        if (candidate.length() > MAX_IMAGE_URL_LENGTH) {
            return false;
        }
        String scheme = candidate.toLowerCase(Locale.ROOT);
        return scheme.startsWith("https://") || scheme.startsWith("http://");
    }

    /**
     * 把调用方给的尺寸归约为网关理解的规范形式。
     *
     * <p>档位字符串统一成大写；{@code 宽x高} 统一成小写分隔符。
     *
     * @return 规范尺寸，调用方未指定时为 {@link #DEFAULT_SIZE}
     */
    static String normalizedSize(String size) {
        if (size == null || size.isBlank()) {
            return DEFAULT_SIZE;
        }
        String trimmed = size.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (SIZE_TIERS.contains(upper)) {
            return upper;
        }
        Matcher matcher = WIDTH_HEIGHT.matcher(trimmed);
        return matcher.matches()
                ? matcher.group(1) + "x" + matcher.group(2)
                : trimmed;
    }

    /**
     * 尺寸所属的档位。
     *
     * <p>{@code 宽x高} 按最长边归约：最长边不超过 1024 是 1K，不超过 1536 是
     * 1.5K，不超过 2048 是 2K，其余是 4K。以最长边而不是面积判断，是因为厂商的
     * 档位是围绕可渲染的边长定义的，而一个 1024x2048 的请求在 1K 与 2K 之间必须
     * 归到更高的一档，否则它会被按低于其成本的档位计费。
     *
     * <p>1536 这一档与 {@link #SIZE_TIERS} 里的 {@code 1.5K} 同源：归约得出的
     * 档位必须永远是词表里的一个值，否则一次合法的像素尺寸会在
     * {@link #validate} 处以 {@code invalid_image_request} 被拒——那是「词表
     * 少一档」最容易被误诊成「客户参数写错」的形态。
     *
     * @return 档位，尺寸不是网关理解的任何形式时为 null
     */
    static String sizeTier(String size) {
        String normalized = normalizedSize(size);
        if (SIZE_TIERS.contains(normalized)) {
            return normalized;
        }
        Matcher matcher = WIDTH_HEIGHT.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }
        int width;
        int height;
        try {
            width = Integer.parseInt(matcher.group(1));
            height = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException error) {
            return null;
        }
        if (width < MIN_EDGE || height < MIN_EDGE || width > MAX_EDGE || height > MAX_EDGE) {
            return null;
        }
        int longestEdge = Math.max(width, height);
        if (longestEdge <= TIER_1K_MAX_EDGE) {
            return "1K";
        }
        if (longestEdge <= TIER_1_5K_MAX_EDGE) {
            return "1.5K";
        }
        return longestEdge <= TIER_2K_MAX_EDGE ? "2K" : "4K";
    }

    /** 档位在售阶梯中的序号；无法识别的档位返回 -1，即「没有这一档」。 */
    static int tierRank(String tier) {
        if (tier == null) {
            return -1;
        }
        return SIZE_TIERS.indexOf(tier.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 档位对应的单边像素上限；无法识别的档位返回 -1。
     *
     * <p>它是 {@link #sizeTier(String)} 那条归约规则的另一半：目录要把一个
     * 画幅比例换算成「一定属于这个档位」的像素尺寸，只能取该档位的单边上限
     * （2048 的 16:9 是 2048x1152，而 2048x1153 已经不是 2K 了）。
     * 因此这条映射必须与 {@link #TIER_1K_MAX_EDGE} / {@link #TIER_1_5K_MAX_EDGE}
     * / {@link #TIER_2K_MAX_EDGE} 同源，而不是在目录里再抄一份数字。
     */
    static int tierMaxEdge(String tier) {
        int rank = tierRank(tier);
        if (rank < 0) {
            return -1;
        }
        return switch (SIZE_TIERS.get(rank)) {
            case "1K" -> TIER_1K_MAX_EDGE;
            case "1.5K" -> TIER_1_5K_MAX_EDGE;
            case "2K" -> TIER_2K_MAX_EDGE;
            default -> MAX_EDGE;
        };
    }

    /**
     * @param requestId 幂等键：客户端提供的 {@code Idempotency-Key}，
     *        缺省时是网关的追踪 id
     * @param prompt 生成提示词；图生图且要求图层拆分时可以为空
     * @param inputImageUrls 可选的图生图输入，1~8 个绝对 http(s) URL（多参考图）
     * @param inputImageFileIds 可选的图生图输入，**网关自己签发的上传文件 id**，
     *        同样 1~8 个。与 {@code inputImageUrls} 二选一（同时给会被
     *        {@link #validate} 拒绝）：它让客户端不必自备公网图床，由网关在
     *        上游调用之前逐个换成短时效地址
     * @param size 调用方给的尺寸，档位字符串或 {@code 宽x高}；null 表示默认 2K
     * @param imageCount 请求的张数：**恰好 1**，也是预留上限与结算上限。
     *        一次调用产出一张图，因此更大的值在 {@link #validate} 处被拒绝
     * @param responseFormat {@code url} 或 {@code b64_json}
     * @param watermark 是否要求厂商加水印；网关默认要求
     * @param layerDecomposition 是否要求图层拆分；仅在提供了**恰好一张**输入图且
     *        没有提示词时有效。按次计费：无论厂商返回多少张图层，这次调用只结算 1 个单位
     */
    public record SubmitCommand(
            String requestId,
            ApiKeyService.ApiKeyContext apiKey,
            String modelAlias,
            String prompt,
            List<String> inputImageUrls,
            List<String> inputImageFileIds,
            String size,
            int imageCount,
            String responseFormat,
            boolean watermark,
            boolean layerDecomposition) {

        public SubmitCommand {
            size = normalizedSize(size);
            responseFormat = responseFormat == null
                    ? "url"
                    : responseFormat.strip().toLowerCase(Locale.ROOT);
            inputImageUrls = inputImageUrls == null ? List.of() : List.copyOf(inputImageUrls);
            inputImageFileIds = inputImageFileIds == null
                    ? List.of()
                    : List.copyOf(inputImageFileIds);
        }

        /**
         * 只有 URL（或什么都没有）的调用方。
         *
         * <p>保留这个重载是为了让「客户端自备公网图片地址」这条既有路径的每一处
         * 调用点都不必知道 file id 的存在——它在这个构造器里恒为空列表。
         */
        public SubmitCommand(
                String requestId,
                ApiKeyService.ApiKeyContext apiKey,
                String modelAlias,
                String prompt,
                String inputImageUrl,
                String size,
                int imageCount,
                String responseFormat,
                boolean watermark,
                boolean layerDecomposition) {
            this(requestId, apiKey, modelAlias, prompt,
                    inputImageUrl == null || inputImageUrl.isBlank()
                            ? List.of()
                            : List.of(inputImageUrl),
                    List.of(), size, imageCount, responseFormat, watermark,
                    layerDecomposition);
        }

        /** 该尺寸归约出的档位。 */
        public String sizeTier() {
            return ImageGenerationService.sizeTier(size);
        }

        /**
         * 同一个请求，但把输入图换成解析出来的地址。
         *
         * <p>{@code inputImageFileIds} 被原样保留：它们是这次调用的**来源证据**，
         * 而解析出来的地址只是派生物。丢掉前者会让「这次生成到底用了哪些上传图」
         * 变得无法回答。
         */
        SubmitCommand withInputImageUrls(List<String> resolved) {
            return new SubmitCommand(requestId, apiKey, modelAlias, prompt, resolved,
                    inputImageFileIds, size, imageCount, responseFormat, watermark,
                    layerDecomposition);
        }

        /**
         * 同一个请求，但换一个 request id。
         *
         * <p>扇出用它把父请求的幂等键派生成每一张自己的键
         * （{@code "<父>#<序号>"}）。派生是**确定性**的，因此重放同一次扇出会把
         * 每一张都找回原来那一行，而不是再买一次。
         */
        SubmitCommand withRequestId(String derivedRequestId) {
            return new SubmitCommand(derivedRequestId, apiKey, modelAlias, prompt,
                    inputImageUrls, inputImageFileIds, size, imageCount, responseFormat,
                    watermark, layerDecomposition);
        }

        /**
         * 同一个请求，但换一个张数。
         *
         * <p>扇出用它把子任务的张数归 1：每一次厂商调用只买一张。留下的
         * {@code imageCount = 1} 同时让「厂商产出多于所订就收窄」那条防线按 1 生效。
         */
        SubmitCommand withImageCount(int singleImage) {
            return new SubmitCommand(requestId, apiKey, modelAlias, prompt,
                    inputImageUrls, inputImageFileIds, size, singleImage, responseFormat,
                    watermark, layerDecomposition);
        }
    }

    /**
     * 调用方所看到的生成结果。
     *
     * @param created 该次生成的开始时刻（unix 秒），重放时保持不变
     * @param requestedImages 请求的张数
     * @param billedImages 实际计费的张数；失败时为 0
     * @param data 生成的图片，失败时为空
     * @param errorCategory 稳定的失败分类，成功时为 null
     * @param partial 交付的张数是否少于请求的张数。为 true 时 {@code data} 里
     *        是**已经成功并已计费**的那些，其余的那几张要么已退款、要么结果未知。
     *        单图请求恒为 false
     */
    public record GenerationView(
            String requestId,
            String model,
            long created,
            int requestedImages,
            int billedImages,
            List<ImageGenerationAdapter.GeneratedImage> data,
            String errorCategory,
            boolean partial) { }
}
