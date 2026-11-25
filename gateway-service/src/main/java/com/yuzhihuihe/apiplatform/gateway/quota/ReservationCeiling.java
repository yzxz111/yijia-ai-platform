package com.yuzhihuihe.apiplatform.gateway.quota;

import com.yuzhihuihe.apiplatform.gateway.billing.ModelBillingPriceCalculator;

/**
 * 把请求的最坏情况换算为预留金额的唯一位置。
 *
 * <p><b>为什么需要它。</b> 网关过去预留的是它<em>估算</em>出的请求开销，随后
 * 调用供应商，再依据该估算进行结算。当供应商的计费高于该估算时——分词器不同、
 * 一次缓存写入、一次推理拆分——结算会以
 * {@code billing_reconciliation_required} 被拒绝，预留则永远停留在 {@code RESERVED}，
 * 于是平台为没有任何客户余额覆盖过的用量向供应商付了钱。
 *
 * <p>修复是结构性的：每个请求都在供应商被调用<em>之前</em>就限定上界，
 * 而被预留的正是这个上界。算术就放在这里，因此它恰好只有一份副本：
 *
 * <pre>
 *   effectiveMaxOutputTokens = min(client max_tokens,           // 客户端没传时取 DEFAULT
 *                                  套餐出售上限时套餐自己的 max_output_tokens,
 *                                  模型自己声明的 max_output_tokens,
 *                                  SYSTEM_MAX_OUTPUT_TOKENS)
 *
 *   reservedPromptTokens = ceil(gatewayPromptTokens * DRIFT_PAD)
 *   reservedOutputTokens = ceil(effectiveMaxOutputTokens * n * DRIFT_PAD)
 *
 *   reservedAmount = retail(reservedPromptTokens, reservedOutputTokens,
 *                           cacheHit   = 0,                 // 命中比未命中便宜
 *                           cacheWrite = reservedPromptTokens,  // 按写入费率计价
 *                           reasoning  = reservedOutputTokens)  // 按推理费率计价
 * </pre>
 *
 * <p>{@code effectiveMaxOutputTokens} 是<em>单次</em>补全的上界，{@code n} 是客户端
 * 请求的补全次数。发送给供应商的上限因此仍是按单次补全计的
 * （OpenAI 的 {@code max_tokens} 是按每个选择项计的——把它乘以 {@code n} 等于要求
 * 单个选择项产生 {@code n} 倍的 token），而预留必须覆盖每一个选择项，
 * 因为供应商会为它们全部计费。{@link #totalOutputTokens} 是唯一发生该乘法的地方，
 * 因此这两个数字永远不会彼此偏离。
 *
 * <p>这样一来，结算永远不会被要求收取超过为经此上界准入的请求所预留的金额；
 * 结算路径以预留额为上限，并把任何残余交给超额路径处理，而不是让它失败。
 *
 * <p><b>为什么用最坏情况类别而不是请求自身的拆分。</b> 网关无法预先知道供应商会从缓存
 * 提供提示词、把它写入缓存，还是把输出 token 花在推理上；它只有在供应商的用量报告中
 * 才能得知，而那份报告要等钱已经花出去之后才到达。按未命中/常规费率预留、
 * 事后才发现是一次写入，正是要消除的那个缺陷，因此该上限把每个输入 token 都按未命中费率
 * 与写入费率中更贵的一方计价，把每个输出 token 都按常规费率与推理费率中更贵的一方计价。
 * 费率卡已经校验了配置的写入价格至少为供应商的输入成本、推理价格至少为供应商的输出成本，
 * 因此这始终是保守的一侧。
 */
public final class ReservationCeiling {

    /**
     * 有文档记录的系统上限：一个请求最多可生成的 token 数。
     *
     * <p>1 000 000 与 {@code gateway.model.max_output_tokens} 上对所有公开模型声明的
     * 数字一致（V117），也是客户端显式传 {@code max_tokens} 时能达到的最高值。它仍然是
     * 套餐方案自身不出售输出上限时适用的上界。
     *
     * <p>它和 {@link #DEFAULT_MAX_OUTPUT_TOKENS} 是**两个不同的问题**，把它们合成一个
     * 正是这次调整最容易犯的错：上限回答"最多能要多少"，默认回答"没人要的时候要多少"。
     * 若没有默认这一层，一个不设上限的套餐上每个默认请求都会按 100 万 token 预留——
     * 而一个 FREE 套餐的总额度只有 10 万，于是"把上限放开"会先把普通请求全部变成
     * {@code quota_exhausted}。
     */
    public static final long SYSTEM_MAX_OUTPUT_TOKENS = 1_000_000L;

    /**
     * 客户端没有指定 {@code max_tokens} / {@code max_completion_tokens} 时，
     * 网关实际向供应商索取、并据此预留的输出上限。
     *
     * <p>8 192 沿用 {@code ChatCompletionUsageEstimator} 一直在用的估算默认值，也接近
     * 供应商自己"未指定时"的默认输出。它是一个**有文档的代价**：默认请求最坏情况预留
     * 8 192 token，而不是某个跟着系统上限漂移的数字。
     *
     * <p>它只约束"客户端什么都没要"的那种请求。客户端显式传了上限时，仍按
     * {@link #ceiling} 的四层 {@code min()} 取，最大可到
     * {@link #SYSTEM_MAX_OUTPUT_TOKENS}——这才是"客户端真能要到 100 万"的路径。
     */
    public static final long DEFAULT_MAX_OUTPUT_TOKENS = 8_192L;

    /**
     * 分词器漂移补偿系数：供应商的计费可能比网关统计的多出多少。
     *
     * <p>网关并不附带供应商的分词器。它用字符启发式统计提示词 token
     * （{@code quota.estimator.characters-per-token}，默认每个 token 3 个码点），
     * 而供应商用自己的分词器计费，因此这两种统计在两个方向上都有差异：
     *
     * <ul>
     *   <li>该启发式把 JSON 外层结构
     *       （{@code {"role":"user","content":"..."}}）也当作提示词文本统计，而供应商的
     *       聊天模板对它的计费并不相同，并且拉丁文散文大约是每个 token 4 个码点，
     *       而这里假设的是 3 个——两者都偏高统计，这部分余量是该补偿系数并不需要的；
     *   <li>供应商的聊天模板会加上网关从来看不到的特殊 token 和系统前置内容
     *       （每个请求几十到几百个 token）；
     *   <li>在 DeepSeek/Qwen 分词器上，CJK 散文接近每个码点 1 个 token——
     *       最多可达该启发式统计值的 3 倍——这是任何固定补偿系数都无法覆盖的。
     * </ul>
     *
     * <p>1.25 是本代码库已经为同一类不确定性选定的系数：
     * {@code quota.estimator.media-safety-factor} 对媒体 token 默认取 1.25，
     * 那些 token 是用类似的经验法则估算的。它覆盖了启发式准确的那些请求上的聊天模板开销。
     * 它<em>并不</em>声称能覆盖纯 CJK 提示词；那正是 {@link QuotaService} 中超额路径
     * 存在的理由，也是该补偿系数是一个有文档记录的数字、而不是一个看不见的数字的原因。
     * 服务 CJK 占比较高租户的运维人员应当注意：低于 3.0 的任何补偿系数都不构成上界，
     * 这是该启发式本身的性质，而不是这个常量的性质。
     */
    public static final double DRIFT_PAD = 1.25d;

    private ReservationCeiling() {
    }

    /**
     * 单个请求的有效最大输出，单位为 token。
     *
     * @param clientMaxTokens 客户端的 {@code max_tokens}/{@code max_completion_tokens}；
     *        当客户端未要求特定限制时为 null——此时按 {@link #DEFAULT_MAX_OUTPUT_TOKENS}
     *        取，再以套餐方案、模型与系统上限为界
     * @param packageMaxOutputTokens 所售套餐方案的 {@code max_output_tokens}；当套餐方案
     *        不出售输出上限时为 null
     * @param modelMaxOutputTokens 公开模型所声明的 {@code max_output_tokens}；
     *        之所以纳入，是因为向供应商发送超过模型所宣称的值会造成供应商报错，
     *        而它只可能降低该上界
     */
    public static long effectiveMaxOutputTokens(
            Long clientMaxTokens, Integer packageMaxOutputTokens, int modelMaxOutputTokens) {
        return ceiling(clientMaxTokens, packageMaxOutputTokens, modelMaxOutputTokens).tokens();
    }

    /**
     * 生效上限**以及它是被哪一层压出来的**。
     *
     * <p>数字本身回答不了运维真正要问的那个问题。2026-10-15 的排查里，
     * 一个请求的输出恰好停在 4096：它可能是客户端传了 {@code max_tokens=4096}、
     * 可能是套餐方案出售的上限、可能是模型自己声明的上限、也可能只是系统兜底。
     * 只看数字只能靠猜，而四种来源的正确处置完全不同——客户端要改请求、套餐要改配置、
     * 模型要换别名、系统上限则说明谁都没限制住。2026-10-19 起多出第五种：客户端根本
     * 没传上限，网关按 {@link #DEFAULT_MAX_OUTPUT_TOKENS} 兜底——它既不是客户端的
     * 意图，也不是任何一层的能力上限，报成它们中的任何一个都会把排查引到错的地方。
     *
     * <p>取值顺序与 {@link #effectiveMaxOutputTokens} 严格一致：从系统上限出发依次
     * 收紧，每次收紧都记下是谁收的。因此 {@code tokens()} 与那个方法逐位相同，
     * 这条不变量由 {@link #ceilingMatchesThePlainValue} 钉住。
     *
     * <p>平局时保留**先收紧的那一层**（模型先于套餐、套餐先于客户端），因为顺序就是
     * 上面的收紧顺序：同值时后一层没有真正起约束作用，报前面那层更接近事实。
     *
     * @param source 见 {@link CeilingSource}
     */
    public static Ceiling ceiling(
            Long clientMaxTokens, Integer packageMaxOutputTokens, int modelMaxOutputTokens) {
        long limit = SYSTEM_MAX_OUTPUT_TOKENS;
        CeilingSource source = CeilingSource.SYSTEM;
        if (modelMaxOutputTokens > 0 && modelMaxOutputTokens < limit) {
            limit = modelMaxOutputTokens;
            source = CeilingSource.MODEL;
        }
        if (packageMaxOutputTokens != null && packageMaxOutputTokens > 0
                && packageMaxOutputTokens < limit) {
            limit = packageMaxOutputTokens;
            source = CeilingSource.PACKAGE;
        }
        if (clientMaxTokens != null) {
            long requested = Math.max(0L, clientMaxTokens);
            if (requested < limit) {
                limit = requested;
                source = CeilingSource.CLIENT;
            }
        } else if (DEFAULT_MAX_OUTPUT_TOKENS < limit) {
            // 客户端什么都没要，就不要按上限去要：预留跟着索取走，而"没人要"的请求不该
            // 冻结 100 万 token 的额度（见 DEFAULT_MAX_OUTPUT_TOKENS 的文档）。
            limit = DEFAULT_MAX_OUTPUT_TOKENS;
            source = CeilingSource.DEFAULT;
        }
        // 与 effectiveMaxOutputTokens 同样的下限：零 token 的输出会让预留失去意义。
        if (limit < 1L) {
            return new Ceiling(1L, CeilingSource.CLIENT);
        }
        return new Ceiling(limit, source);
    }

    /** 有效输出上限的五个可能来源。 */
    public enum CeilingSource {
        /** 客户端的 {@code max_tokens} / {@code max_completion_tokens}。 */
        CLIENT("client"),
        /** 模型自己声明的 {@code max_output_tokens}。 */
        MODEL("model"),
        /** 套餐方案出售的 {@code max_output_tokens}。 */
        PACKAGE("package"),
        /** 网关的系统上限 {@link #SYSTEM_MAX_OUTPUT_TOKENS}——没有任何一层真正限制住。 */
        SYSTEM("system"),
        /** 客户端没有指定上限，网关按 {@link #DEFAULT_MAX_OUTPUT_TOKENS} 兜底。 */
        DEFAULT("default");

        private final String wireName;

        CeilingSource(String wireName) {
            this.wireName = wireName;
        }

        /** 落库用的稳定小写名，与 {@code ck_usage_log_ceiling_source} 的词表同源。 */
        public String wireName() {
            return wireName;
        }
    }

    /** 生效上限与它的来源。 */
    public record Ceiling(long tokens, CeilingSource source) {

        /**
         * 这条不变量把"新旧两条路径给出同一个数字"钉住。
         *
         * <p>两个方法各写一遍 min() 是必要的（一个要来源、一个不要），而两份实现
         * 一旦分叉，用量记录里的上限就会与真正发给供应商的那个数不一致——那种偏差
         * 不会报错，只会让排查照着错数字走。
         */
        public boolean ceilingMatchesThePlainValue(
                Long clientMaxTokens, Integer packageMaxOutputTokens, int modelMaxOutputTokens) {
            return tokens == effectiveMaxOutputTokens(
                    clientMaxTokens, packageMaxOutputTokens, modelMaxOutputTokens);
        }
    }

    /**
     * 单个请求在其全部补全中最多可生成的输出 token 数。
     *
     * <p>OpenAI 的 {@code n} 向供应商要求 {@code n} 个补全，每个补全都可以跑到该请求的
     * 单次补全输出上限，而供应商会为它们全部计费。因此<em>发送给</em>供应商的上限
     * 仍按单次补全计，而预留所覆盖的上界则是这个乘积。
     *
     * <p>该乘积会给钱包冻结提供依据，因此它绝不能回绕：回绕后的值会比该请求可能的花费更小，
     * 而这正是本类存在的目的所要防止的预留不足。该乘积用 {@link Math#multiplyExact} 计算，
     * 溢出时会被拒绝而不是被截断，因为一个被截断到 {@code Long.MAX_VALUE} 的冻结
     * 会在后面的价格算术中失败，而不是被报告为它本来的那个无效请求。
     * 经由网关时该上界是不可达的——{@code n} 会先按 {@code ProviderAdapter.MAX_COMPLETIONS} 校验，
     * 单次补全上限会先按 {@link #SYSTEM_MAX_OUTPUT_TOKENS} 校验——因此这是给直接调用方的防护。
     *
     * @param perCompletionOutputTokens 单次补全最多可生成的上限
     * @param completions 客户端的 {@code n}，至少为 1
     * @throws IllegalArgumentException 当输入超出范围，或该乘积会使 {@code long} 溢出时
     */
    public static long totalOutputTokens(long perCompletionOutputTokens, int completions) {
        if (perCompletionOutputTokens < 0 || completions < 1) {
            throw new IllegalArgumentException("invalid_reservation_completions");
        }
        try {
            return Math.multiplyExact(perCompletionOutputTokens, completions);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("invalid_reservation_completions");
        }
    }

    /** {@code ceil(tokens * pad)}，溢出安全。 */
    public static long driftPadded(long tokens, double driftPad) {
        if (tokens <= 0) {
            return 0L;
        }
        double padded = Math.ceil(tokens * driftPad);
        return padded >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) padded;
    }

    /** 单个请求在最坏情况下的零售金额，按 {@link #DRIFT_PAD} 计算。 */
    public static long worstCaseAmount(
            ModelBillingPriceCalculator.Price price, long promptTokens, long effectiveMaxOutputTokens) {
        return worstCaseAmount(price, promptTokens, effectiveMaxOutputTokens, DRIFT_PAD);
    }

    /**
     * 单个请求在最坏情况下的零售金额：加上漂移补偿后的提示词与加上补偿后的
     * 有效最大输出，两者都按该请求可能产生的最贵费率计价。
     */
    public static long worstCaseAmount(
            ModelBillingPriceCalculator.Price price,
            long promptTokens,
            long effectiveMaxOutputTokens,
            double driftPad) {
        if (price == null || !Double.isFinite(driftPad) || driftPad < 1.0d) {
            throw new IllegalArgumentException("invalid_reservation_ceiling_request");
        }
        long inputCeiling = driftPadded(promptTokens, driftPad);
        long outputCeiling = driftPadded(effectiveMaxOutputTokens, driftPad);
        if (inputCeiling == 0 && outputCeiling == 0) {
            return 0L;
        }
        return price.amount(
                inputCeiling, outputCeiling,
                0L, inputCeiling, outputCeiling);
    }
}
