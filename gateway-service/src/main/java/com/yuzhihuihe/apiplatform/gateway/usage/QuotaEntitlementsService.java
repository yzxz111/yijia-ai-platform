package com.yuzhihuihe.apiplatform.gateway.usage;

import static com.yuzhihuihe.apiplatform.gateway.usage.UsageQueryRows.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * {@code GET /console/gateway/quota/entitlements} —— 账号级套餐权益明细。
 *
 * <p>回答的是"我买了什么、还剩多少、每调一次大概扣多少"这三件事，比
 * {@code /quota/summary} 多出两样东西：**每个模型自己的额度**（含没有独立额度的模型
 * 花的是哪一份共享额度），以及**每次调用会扣掉多少额度**。会话 JWT 认证、账号级，
 * 因为桌面客户端要在一个页面上把用户持有的每个维度都渲染出来。
 *
 * <p><b>与 {@code /quota/summary} 的关系。</b>"剩余"的算术逐字相同
 * （总量 − 在途预留 − 已用，不做下限截断，可以为负），两个接口共用
 * {@link UsageQueryRepository#summary} 这一条查询，因此不会出现两个口径。
 * 这里**刻意不返回排队中的套餐**：排队实例还没有任何实时计数，
 * 把它和生效实例放进同一个数组，正是"把还没生效的额度显示成可用额度"那类事故的形态
 * （{@code queuedPackages} 继续由 {@code /quota/summary} 提供）。
 *
 * <p><b>单位。</b>套餐额度的单位随 {@code usageDimension} 变：TOKEN 是积分（底层就是
 * token 数），IMAGE 是张，VIDEO_SECOND 与 AUDIO_SECOND 是秒。每个额度和每次消耗都带
 * 自己的单位字段，客户端**不得**假定是积分。产品口径上视频/音频套餐的秒数额度不对
 * 客户展示——本接口照给（它是账号自己的数据），由客户端决定不渲染，规则见
 * {@code docs/desktop-client-usage-dimension-spec.md}。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class QuotaEntitlementsService {

    static final String DIMENSION_TOKEN = "TOKEN";
    static final String DIMENSION_VIDEO_SECOND = "VIDEO_SECOND";
    static final String DIMENSION_IMAGE = "IMAGE";
    static final String DIMENSION_AUDIO_SECOND = "AUDIO_SECOND";

    /** 额度单位词表：积分 / 张 / 秒。 */
    private static final String UNIT_CREDIT = "CREDIT";
    private static final String UNIT_IMAGE = "IMAGE";
    private static final String UNIT_SECOND = "SECOND";

    /** 每次调用扣减量的口径。 */
    private static final String BASIS_METERED_BY_TOKENS = "METERED_BY_TOKENS";
    private static final String BASIS_ONE_IMAGE_PER_REQUEST = "ONE_IMAGE_PER_REQUEST";
    private static final String BASIS_PER_REQUESTED_SECOND = "PER_REQUESTED_SECOND";
    /** 未知维度：没有可用的计量规则。与"每次扣 0"是两回事。 */
    private static final String BASIS_UNKNOWN = "UNKNOWN";

    /** 额度行的归属：模型自己那一份，还是与同套餐其它模型共享的套餐那一份。 */
    private static final String SCOPE_MODEL = "MODEL";
    private static final String SCOPE_PACKAGE = "PACKAGE";

    /** 金额单位（分是这套费率卡的最小货币单位，币种恒为人民币）。 */
    private static final String MONEY_CURRENCY = "CNY";
    private static final String MONEY_PER_MILLION_TOKENS = "CENT_PER_MILLION_TOKENS";
    private static final String MONEY_PER_UNIT = "CENT_PER_UNIT";

    private static final String PRICE_SOURCE_BILLING_CARD = "BILLING_CARD";
    private static final String PRICE_SOURCE_RATE_CARD = "RATE_CARD";

    private final UsageQueryRepository usageRepository;
    private final QuotaEntitlementsRepository repository;
    private final ObjectMapper objectMapper;

    public QuotaEntitlementsService(
            UsageQueryRepository usageRepository,
            QuotaEntitlementsRepository repository,
            ObjectMapper objectMapper) {
        this.usageRepository = usageRepository;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Mono<Entitlements> entitlements(UUID tenantId, UUID userId) {
        requireIdentity(tenantId, userId);
        return usageRepository
                .summary(tenantId, userId)
                .flatMap(summary -> Mono.zip(
                                repository.tokenPrices().collectList(),
                                repository.unitPrices().collectList(),
                                repository.aliasTiers().collectList())
                        .map(loaded -> assemble(
                                summary, loaded.getT1(), loaded.getT2(), loaded.getT3())));
    }

    private Entitlements assemble(
            SummaryRow summary,
            List<QuotaEntitlementsRepository.TokenPriceRow> tokenPrices,
            List<QuotaEntitlementsRepository.UnitPriceRow> unitPrices,
            List<QuotaEntitlementsRepository.AliasTierRow> aliasTiers) {
        Map<String, QuotaEntitlementsRepository.TokenPriceRow> billingCard =
                indexUnique(tokenPrices, QuotaEntitlementsRepository.TokenPriceRow::modelCode);
        Map<PriceKey, QuotaEntitlementsRepository.UnitPriceRow> rateCard =
                soldRateCardRows(unitPrices, aliasTiers);
        List<PackageEntitlements> packages = summary.activePackages().stream()
                .map(row -> toPackage(row, billingCard, rateCard))
                .toList();
        return new Entitlements(Instant.now(), GiftQuota.notImplemented(), packages);
    }

    private PackageEntitlements toPackage(
            ActivePackageRow row,
            Map<String, QuotaEntitlementsRepository.TokenPriceRow> billingCard,
            Map<PriceKey, QuotaEntitlementsRepository.UnitPriceRow> rateCard) {
        String packageDimension = dimension(row.usageDimension());
        Map<String, ModelQuotaRow> quotas = new LinkedHashMap<>();
        row.modelQuotas().forEach(quota -> quotas.putIfAbsent(quota.modelCode(), quota));
        // 模型列表**完全来自这份套餐的授权名单**，顺序也沿用名单顺序：客户端会拿它
        // 渲染列表、并当作缓存键与相等比较，因此同一份套餐的两次请求必须给出同一个顺序。
        // 不把全平台模型目录、也不把"有额度行但名单里没有"的模型塞进来——一个客户买不到
        // 的模型出现在权益里，客户端会显示一个调不通的条目。
        List<DeclaredModel> declared = declaredModels(row.allowedModelsJson());
        Map<String, String> declaredDimensions = declared.stream()
                .filter(model -> model.usageDimension() != null)
                .collect(Collectors.toMap(
                        DeclaredModel::modelCode, DeclaredModel::usageDimension,
                        (first, second) -> first));

        List<ModelEntitlement> models = declared.stream()
                .map(model -> toModel(
                        model.modelCode(), row, packageDimension, quotas.get(model.modelCode()),
                        declaredDimensions.get(model.modelCode()), billingCard, rateCard))
                .toList();

        return new PackageEntitlements(
                row.packageInstanceId(), row.packageCode(), row.packageName(), row.status(),
                packageDimension, quotaUnit(packageDimension),
                row.totalTokens(), row.reservedTokens(), row.usedTokens(),
                remaining(row.totalTokens(), row.reservedTokens(), row.usedTokens()),
                row.dailyRequestLimit(), row.expiresAt(),
                audioSeconds(row), models);
    }

    /**
     * 一个模型的权益。
     *
     * <p>没有独立额度行的模型（套餐只给了总池、没有 {@code modelTokenQuota}）在维度与
     * 套餐主维度一致时拿到的是**套餐那一份共享额度**，并用 {@code quotaScope = PACKAGE}
     * 说明。这是刻意的：直接把剩余量报成 0 会让客户端把"没有独立额度"读成"额度用完了"
     * （网关对这类模型的准入正是看套餐总池），而报出套餐的数字又必须同时告诉客户端
     * 它不能被逐模型相加——否则十个模型会把同一份额度算十遍。
     *
     * <p>维度**不一致**时就没有可报的池子，四项额度为 {@code null}
     * （跨维度授权、或套餐没有第二份音频额度），判据见 {@link #quotaAmounts}。
     * {@code null} 与 {@code quotaScope = MODEL} 是两件独立的事：前者说"没有这个池"，
     * 后者说"数值来自哪个池"。
     */
    private ModelEntitlement toModel(
            String modelCode,
            ActivePackageRow packageRow,
            String packageDimension,
            ModelQuotaRow quota,
            String declaredDimension,
            Map<String, QuotaEntitlementsRepository.TokenPriceRow> billingCard,
            Map<PriceKey, QuotaEntitlementsRepository.UnitPriceRow> rateCard) {
        // 维度的优先级与网关准入时逐字相同（PackageActivationService.effectiveUsageDimension）：
        // 额度行 → 套餐名单里该模型声明的维度 → 实例主维度。
        String dimension = quota != null
                ? dimension(quota.usageDimension())
                : declaredDimension != null ? dimension(declaredDimension) : packageDimension;
        boolean ownQuota = quota != null;
        Quota amounts = quotaAmounts(quota, packageRow, packageDimension, dimension);
        return new ModelEntitlement(
                modelCode, category(dimension), dimension, quotaUnit(dimension),
                ownQuota ? SCOPE_MODEL : SCOPE_PACKAGE,
                amounts.total(), amounts.reserved(), amounts.used(),
                remaining(amounts),
                perCall(dimension, modelCode, billingCard, rateCard));
    }

    /**
     * 一个模型的四项额度，**允许为 {@code null}**。
     *
     * <p>能给出数的地方只有三处，且必须按维度挑对那一处；挑不到就报 {@code null}，
     * 绝不拿别的维度的余额顶上：
     *
     * <ol>
     *   <li><b>独立模型额度</b>（{@code quotaScope=MODEL}）：总量取
     *       {@code COALESCE(token_quota, seconds_quota)}。只读 {@code token_quota}
     *       会让按张／按秒计费的模型报出总额 0——同一行的 reserved/used 却是真实的
     *       张数或秒数，客户就会看到一个"用掉几十张、总额却是 0"的模型。
     *       {@code ck_model_quota_values} 保证两列恰好只有一个非空，因此没有歧义。
     *   <li><b>套餐共享额度</b>（{@code quotaScope=PACKAGE}，且模型维度等于套餐主维度）：
     *       直接报套餐主额度那一份。多个模型引用同一份余额，**不能按模型条目相加**。
     *   <li><b>音频第二份额度</b>（{@code PACKAGE} + 模型是 AUDIO_SECOND + 套餐主维度
     *       不是音频）：报 {@link #audioSeconds} 那一份。纯音频套餐的模型走上面第 2 条，
     *       它的主额度本身就是音频秒。
     * </ol>
     *
     * <p>剩下两种情形只能是 {@code null}：模型维度与套餐主维度不一致、又没有独立额度或
     * 音频第二份额度（一次跨维度授权，说明这个模型在这份套餐里压根没有额度池）；
     * 以及套餐声明了要卖音频、但没有第二份额度。把它们报成 0 或报成套餐的数字，
     * 都是把"不适用"说成"余额为零"。
     */
    private static Quota quotaAmounts(ModelQuotaRow quota, ActivePackageRow packageRow,
            String packageDimension, String dimension) {
        if (quota != null) {
            return new Quota(quota.totalTokens(), quota.reservedTokens(), quota.usedTokens());
        }
        if (dimension.equals(packageDimension)) {
            return new Quota(packageRow.totalTokens(), packageRow.reservedTokens(),
                    packageRow.usedTokens());
        }
        if (DIMENSION_AUDIO_SECOND.equals(dimension)
                && !DIMENSION_AUDIO_SECOND.equals(packageDimension)
                && packageRow.audioSecondsQuota() != null) {
            return new Quota(packageRow.audioSecondsQuota(), audioSecondsReserved(packageRow),
                    audioSecondsUsed(packageRow));
        }
        return new Quota(null, null, null);
    }

    /**
     * 每次调用扣掉多少额度。
     *
     * <p>三种口径都是**网关自己的计量方式**，不是估算出来的营销数字：
     *
     * <ul>
     *   <li>图片：{@code n} 只接受 1，于是一次生成恰好扣 1 张；
     *   <li>视频与音频：提交时按 {@code durationSeconds} 预占、按产出时长结算，
     *       因此单位消耗是"每 1 秒 1 秒额度"，客户端乘以自己要提交的秒数；
     *   <li>对话：按实际 token 结算，**没有**每次对话的固定值，所以
     *       {@code units} 为 {@code null}。这里给的是费率（{@code referencePrice}），
     *       客户端可以据此显示"按量计费"，但不能把费率说成一次对话的价格。
     * </ul>
     *
     * <p>{@code units} 为 {@code null} 与 {@code 0} 是两件事：前者是"没有固定值"，
     * 后者会读成"这次不花钱"。
     */
    private PerCall perCall(
            String dimension,
            String modelCode,
            Map<String, QuotaEntitlementsRepository.TokenPriceRow> billingCard,
            Map<PriceKey, QuotaEntitlementsRepository.UnitPriceRow> rateCard) {
        return switch (dimension) {
            case DIMENSION_IMAGE -> new PerCall(
                    UNIT_IMAGE, 1L, BASIS_ONE_IMAGE_PER_REQUEST,
                    price(dimension, modelCode, billingCard, rateCard));
            case DIMENSION_VIDEO_SECOND, DIMENSION_AUDIO_SECOND -> new PerCall(
                    UNIT_SECOND, 1L, BASIS_PER_REQUESTED_SECOND,
                    price(dimension, modelCode, billingCard, rateCard));
            case DIMENSION_TOKEN -> new PerCall(
                    UNIT_CREDIT, null, BASIS_METERED_BY_TOKENS,
                    price(dimension, modelCode, billingCard, rateCard));
            // 第五个维度（如果将来出现）**不得**默认落进上面任一支：把它算成积分
            // 正是"新增模型族默认落进对话目录"那个已发生过的缺陷的同类形态。
            // 这里明确说"没有可用计量规则"，让新维度必须被有意地加进来。
            // 对象本身仍然返回（单位与基数都是"未知"，不是"零"），因为"这条规则
            // 不存在"也是一条客户端要能显示的信息。
            default -> new PerCall(null, null, BASIS_UNKNOWN, null);
        };
    }

    /**
     * 参考单价：token 先问计费卡、再回退费率卡；其余维度只问费率卡。
     *
     * <p>两个来源都给不出价时返回 {@code null}（例如套餐里的别名还没定价），
     * 而不是 0——0 会被客户端渲染成"免费"。
     *
     * <p><b>只填与 {@code unit} 相符的那几列，其余一律 {@code null}。</b>
     * 这不是洁癖：费率卡的 TOKEN 行按约束也带着 {@code minimum_units = 1} 与
     * {@code minimum_charge = 1}（V40 的形状约束），照搬过来就会让客户端读到
     * "每次对话至少 1 分"——那两列在 token 维度上根本没有业务含义。
     * 反过来，逐秒/逐张行上的每百万 token 价必须是 {@code null}，否则
     * "分/秒"会被当成"分/百万 token"再乘一次。单位混用在这个仓库里是
     * 反复出现过的真实缺陷，接口这一层就不该给出可以被误读的数字。
     *
     * <p>token 与音频的 {@code resolutionTier} 统一为空串（不是 {@code null}）：
     * 客户端对"没有档位"只该处理一种形态。
     */
    private ReferencePrice price(
            String dimension,
            String modelCode,
            Map<String, QuotaEntitlementsRepository.TokenPriceRow> billingCard,
            Map<PriceKey, QuotaEntitlementsRepository.UnitPriceRow> rateCard) {
        if (DIMENSION_TOKEN.equals(dimension)) {
            QuotaEntitlementsRepository.TokenPriceRow card = billingCard.get(modelCode);
            if (card != null) {
                return new ReferencePrice(
                        MONEY_CURRENCY, MONEY_PER_MILLION_TOKENS, "",
                        PRICE_SOURCE_BILLING_CARD,
                        null, null, null,
                        card.inputPerMillion(), card.outputPerMillion(),
                        card.cacheHitPerMillion(), card.cacheWritePerMillion(),
                        card.reasoningPerMillion());
            }
        }
        QuotaEntitlementsRepository.UnitPriceRow row =
                rateCard.get(new PriceKey(modelCode, dimension));
        if (row == null) {
            return null;
        }
        if (DIMENSION_TOKEN.equals(dimension)) {
            return new ReferencePrice(
                    MONEY_CURRENCY, MONEY_PER_MILLION_TOKENS, "",
                    PRICE_SOURCE_RATE_CARD,
                    null, null, null,
                    row.inputPerMillion(), row.outputPerMillion(),
                    row.cacheHitPerMillion(), row.cacheWritePerMillion(),
                    row.reasoningPerMillion());
        }
        return new ReferencePrice(
                MONEY_CURRENCY, MONEY_PER_UNIT, row.resolutionTier(), PRICE_SOURCE_RATE_CARD,
                row.unitPrice(), row.minimumUnits(), row.minimumCharge(),
                null, null, null, null, null);
    }

    /**
     * 每个「别名 × 维度」保留一行当前费率。
     *
     * <p>优先取**登记在卖**的那一档（{@code *_alias_tier}）。没有档位登记时取档位字典序
     * 最小的一行：结果与数据库返回顺序无关，且同一份输入永远得到同一个答案。
     * 之所以不是"取最便宜的一行"，是因为那会让展示价与"客户端实际能提交的档位"
     * 悄悄分叉——档位登记才是唯一权威，缺登记是数据问题，不该被一个猜测掩盖。
     */
    private static Map<PriceKey, QuotaEntitlementsRepository.UnitPriceRow> soldRateCardRows(
            List<QuotaEntitlementsRepository.UnitPriceRow> rows,
            List<QuotaEntitlementsRepository.AliasTierRow> aliasTiers) {
        Map<String, String> soldTiers = aliasTiers.stream().collect(Collectors.toMap(
                QuotaEntitlementsRepository.AliasTierRow::modelCode,
                QuotaEntitlementsRepository.AliasTierRow::resolutionTier,
                (first, second) -> first));
        Map<PriceKey, QuotaEntitlementsRepository.UnitPriceRow> chosen = new LinkedHashMap<>();
        for (QuotaEntitlementsRepository.UnitPriceRow row : rows) {
            PriceKey key = new PriceKey(row.modelCode(), dimension(row.usageDimension()));
            QuotaEntitlementsRepository.UnitPriceRow current = chosen.get(key);
            if (current == null
                    || preferred(row, current, soldTiers.get(row.modelCode()))) {
                chosen.put(key, row);
            }
        }
        return chosen;
    }

    private static boolean preferred(
            QuotaEntitlementsRepository.UnitPriceRow candidate,
            QuotaEntitlementsRepository.UnitPriceRow current,
            String soldTier) {
        if (soldTier != null) {
            boolean candidateSold = soldTier.equals(candidate.resolutionTier());
            boolean currentSold = soldTier.equals(current.resolutionTier());
            if (candidateSold != currentSold) {
                return candidateSold;
            }
        }
        return tierKey(candidate.resolutionTier()).compareTo(tierKey(current.resolutionTier())) < 0;
    }

    private static String tierKey(String resolutionTier) {
        return resolutionTier == null ? "" : resolutionTier;
    }

    /**
     * 套餐名单里的模型项。
     *
     * <p>{@code allowed_models} 里既有模型项，也可能有一个不带 {@code modelCode} 的
     * 标记项（{@code {"mixedPools": true}}），后者不是模型，必须被跳过——把它当成
     * 一个名为 {@code ""} 的模型下发，客户端会多出一个调不通的条目。
     */
    private List<DeclaredModel> declaredModels(String json) {
        try {
            List<DeclaredModel> models = new ArrayList<>();
            objectMapper.readTree(json).forEach(node -> {
                String modelCode = node.path("modelCode").asText("");
                if (!modelCode.isBlank()) {
                    String rawDimension = node.path("usageDimension").asText("");
                    models.add(new DeclaredModel(
                            modelCode, rawDimension.isBlank() ? null : rawDimension));
                }
            });
            return List.copyOf(models);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("package allowed_models is invalid", exception);
        }
    }

    private static long audioSecondsReserved(ActivePackageRow row) {
        return row.audioSecondsReserved() == null ? 0L : row.audioSecondsReserved();
    }

    private static long audioSecondsUsed(ActivePackageRow row) {
        return row.audioSecondsUsed() == null ? 0L : row.audioSecondsUsed();
    }

    /** 第二份额度（音频秒）。{@code null} 表示这个套餐不卖语音，客户端必须不渲染。 */
    private static UsageQueryService.AudioSeconds audioSeconds(ActivePackageRow row) {
        Long quota = row.audioSecondsQuota();
        if (quota == null) {
            return null;
        }
        long reserved = audioSecondsReserved(row);
        long used = audioSecondsUsed(row);
        return new UsageQueryService.AudioSeconds(quota, reserved, used, quota - reserved - used);
    }

    private static String category(String dimension) {
        return switch (dimension) {
            case DIMENSION_TOKEN -> "CHAT";
            case DIMENSION_IMAGE -> "IMAGE";
            case DIMENSION_VIDEO_SECOND -> "VIDEO";
            case DIMENSION_AUDIO_SECOND -> "AUDIO";
            default -> "OTHER";
        };
    }

    private static String quotaUnit(String dimension) {
        return switch (dimension) {
            case DIMENSION_TOKEN -> UNIT_CREDIT;
            case DIMENSION_IMAGE -> UNIT_IMAGE;
            case DIMENSION_VIDEO_SECOND, DIMENSION_AUDIO_SECOND -> UNIT_SECOND;
            default -> null;
        };
    }

    /** 维度值归一化；缺列或空串按 TOKEN 处理，与网关准入的默认值一致。 */
    private static String dimension(String raw) {
        if (raw == null || raw.isBlank()) {
            return DIMENSION_TOKEN;
        }
        return raw.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static long remaining(long total, long reserved, long used) {
        return total - reserved - used;
    }

    /**
     * 模型额度的剩余量：**三项来源齐全时**才是总量 − 预占 − 已用，否则 {@code null}。
     *
     * <p>{@code null} 不是 0：它表示"这个额度池不适用或无从得知"，而 0 会被客户端
     * 读成"额度已经用完"。负值原样返回——一次结算高于预占时它本来就可以是负数，
     * 那正是客户判断还能不能发下一次调用所需要的读数。
     */
    private static Long remaining(Quota quota) {
        if (quota.total() == null || quota.reserved() == null || quota.used() == null) {
            return null;
        }
        return quota.total() - quota.reserved() - quota.used();
    }

    private static <T> Map<String, T> indexUnique(List<T> rows, Function<T, String> key) {
        Map<String, T> index = new LinkedHashMap<>();
        rows.forEach(row -> index.putIfAbsent(key.apply(row), row));
        return index;
    }

    private static void requireIdentity(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_identity");
        }
    }

    private record PriceKey(String modelCode, String usageDimension) {}

    private record DeclaredModel(String modelCode, String usageDimension) {}

    /**
     * 一个模型四项额度的来源值；三项同为 {@code null} 表示"这个额度池不适用"。
     * 用一个记录而不是三个散装参数，是为了让"要么都给、要么都不给"这件事无法被写错。
     */
    private record Quota(Long total, Long reserved, Long used) {}

    /**
     * 账号当前的套餐权益快照。
     *
     * <p>{@code packages} 只含**生效中**的实例（一个用户最多每个维度一个：
     * TOKEN / VIDEO_SECOND / IMAGE / AUDIO_SECOND）。
     */
    public record Entitlements(
            Instant generatedAt,
            /**
             * 邀请赠送额度。**当前平台尚未实现**，因此恒为
             * {@code implemented=false} + {@code reason="not_implemented"} + 全 {@code null}。
             * 客户端必须按 {@code implemented} 判断是否渲染，而不是按数值是否为 0：
             * 一个写死的 0 在功能上线那天会静默变成"你有 0 额度"。
             */
            GiftQuota giftQuota,
            List<PackageEntitlements> packages) {}

    /**
     * 邀请赠送额度（预留字段）。
     *
     * <p>数值为 {@code null} 而不是 0：这个功能今天没有数据来源
     * （{@code platform.referral_*} 只记录邀请码与归因人数，没有任何额度赠送），
     * 因此"0"会是一个凭空捏造的承诺。
     */
    public record GiftQuota(
            boolean implemented,
            String reason,
            Long total,
            Long used,
            Long reserved,
            Long remaining) {

        static GiftQuota notImplemented() {
            return new GiftQuota(false, "not_implemented", null, null, null, null);
        }
    }

    /**
     * 一个生效套餐实例的权益。
     *
     * <p>{@code usageDimension} 是**主维度**，{@code totalQuota / reservedQuota /
     * usedQuota / remainingQuota} 是主维度那一份的计数，单位由 {@code quotaUnit} 决定。
     * {@code audioSeconds} 是方案 C 的第二份额度，为 {@code null} 时表示这个套餐
     * 不卖语音，客户端必须**不渲染**（而不是显示 0 秒）；两份额度不可相加。
     */
    public record PackageEntitlements(
            UUID packageInstanceId,
            String packageCode,
            String packageName,
            String status,
            String usageDimension,
            String quotaUnit,
            long totalQuota,
            long reservedQuota,
            long usedQuota,
            long remainingQuota,
            Integer dailyRequestLimit,
            Instant expiresAt,
            UsageQueryService.AudioSeconds audioSeconds,
            List<ModelEntitlement> models) {}

    /**
     * 一个模型在该套餐下的额度与每次调用消耗。
     *
     * @param category      {@code CHAT} / {@code IMAGE} / {@code VIDEO} / {@code AUDIO}
     *                      （未知维度为 {@code OTHER}，此时 {@code quotaUnit} 与
     *                      {@code perCall} 均为 {@code null}）
     * @param quotaScope    {@code MODEL} = 这个模型有独立额度；{@code PACKAGE} = 没有独立
     *                      额度，数值是**与同套餐其它模型共享**的套餐额度。
     *                      {@code PACKAGE} 的模型不得被逐模型相加。
     * @param totalQuota    该模型额度池的总量。{@code null} = 这个池不适用或无法确定
     *                      （跨维度授权、或套餐声明的音频第二份额度不存在），**不是 0**
     * @param remainingQuota 三项来源齐全时是总量 − 预占 − 已用，可以为负（一次结算高于
     *                      预占时），这正是网关主计数器的约定；任一来源缺失时为 {@code null}
     * @param perCall       每次调用扣减量。未知维度时仍然返回，{@code basis} 为
     *                      {@code UNKNOWN}、单位与基数都是 {@code null}
     */
    public record ModelEntitlement(
            String modelCode,
            String category,
            String usageDimension,
            String quotaUnit,
            String quotaScope,
            Long totalQuota,
            Long reservedQuota,
            Long usedQuota,
            Long remainingQuota,
            PerCall perCall) {}

    /**
     * 每次调用扣减的额度。
     *
     * @param unit  扣减单位：{@code CREDIT}（积分）/ {@code IMAGE}（张）/ {@code SECOND}（秒）；
     *              未知维度为 {@code null}
     * @param units 参考扣减量。{@code null} 表示这个类别没有固定值（对话按实际 token 计、
     *              未知维度没有可用规则），**不是** 0
     * @param basis 这个数字是怎么来的：{@code METERED_BY_TOKENS} /
     *              {@code ONE_IMAGE_PER_REQUEST} / {@code PER_REQUESTED_SECOND} /
     *              {@code UNKNOWN}
     * @param referencePrice 参考零售价；定价缺失时为 {@code null}
     */
    public record PerCall(
            String unit,
            Long units,
            String basis,
            ReferencePrice referencePrice) {}

    /**
     * 参考零售价。
     *
     * <p>金额单位是**分**（这套费率卡的最小货币单位，币种恒为人民币）。{@code unit}
     * 说明哪些字段有意义：{@code CENT_PER_MILLION_TOKENS} 时读
     * {@code input/output/cacheHit/cacheWrite/reasoningPerMillion}；
     * {@code CENT_PER_UNIT} 时读 {@code unitPrice / minimumUnits / minimumCharge}
     * （视频与音频是"分/秒"，图片是"分/张"，看 {@code usageDimension}）。
     * 两个单位**不可混用**：把每秒价当每百万 token 价读会差好几个数量级。
     *
     * @param source {@code BILLING_CARD}（钱包计费读的那张卡）或 {@code RATE_CARD}
     *               （按维度的费率卡）；两者对同一个别名可能不同，排查价格问题时要看它
     */
    public record ReferencePrice(
            String currency,
            String unit,
            String resolutionTier,
            String source,
            Long unitPrice,
            Long minimumUnits,
            Long minimumCharge,
            Long inputPerMillion,
            Long outputPerMillion,
            Long cacheHitPerMillion,
            Long cacheWritePerMillion,
            Long reasoningPerMillion) {}
}
