package com.yuzhihuihe.apiplatform.gateway.limit;

import java.util.UUID;

/**
 * 唯一决定套餐售出的每分钟数值属于哪个维度的地方。
 *
 * <p><b>问题所在。</b>{@code gateway.package_instance.tokens_per_minute} 是承载维度的，
 * 与 {@code total_token_quota} 完全一样（见 V42__video_package_entitlements.sql，其中记录了
 * {@code VIDEO_SECOND} 实例的额度列是秒）。某个视频套餐把它的秒数额度写在了那里 —— VIDEO_STARTER 60、
 * VIDEO_CREATOR 190、VIDEO_PRO 600 秒每分钟，控制台因此把它渲染为 {@code SPM}
 * （{@code web-console/src/views/user/
 * PlansView.vue}、{@code planFeatures}）—— 而平台的 V63
 * 把它们替换成了真实的 token 速率（100,000 / 300,000 / 500,000），因为该套餐方案同时也卖
 * chat。该列的两种状态都存在于现网中，所以网关既不能简单地把它读作
 * token，也不能简单地把它读作秒：前者会用秒数去限制视频客户的 chat，后者会用 token
 * 数值去限制视频生成。
 *
 * <p><b>规则。</b>套餐方案自身的维度优先决定，并且绝不会从套餐方案并未作为 token 售出的
 * 数值推断出 token：
 *
 * <ol>
 *   <li>套餐实例的 {@code usage_dimension} 指明其配额的单位。维度缺失
 *       （在该列存在之前写入的行）即为 token 套餐，这与
 *       {@code QuotaReservationRepository} 读取该列时采用的默认值相同。
 *   <li>{@code TOKEN} 维度：售出的数值就是 token 速率 —— 既有的行为，
 *       保持不变，包括低于 {@link #TOKEN_RATE_FLOOR} 的值：token 套餐的低速率就是
 *       低速率，而不是 SPM。
 *   <li>{@code VIDEO_SECOND} 维度：只有当平台自身的发布守卫认可该数值是 token 速率时，
 *       售出的数值才是 token 速率。V63
 *       （{@code platform-service/.../V63__correct_sold_rate_limits_and_republish_plans.sql}）
 *       拒绝发布任何每分钟 token 数低于 {@link #TOKEN_RATE_FLOOR} 的授予 TEXT 权益的套餐方案，
 *       正是为了让「一个明显不是 token 速率的数值（60、190、600…… 无法通过」。
 *       因此，达到或高于该下限的数值就是该套餐方案的 chat token 速率；低于它的数值
 *       就是该套餐方案的每秒速率，并按秒强制执行 —— 绝不按 token 执行。
 *   <li>{@code IMAGE} 维度：与 {@code VIDEO_SECOND} 同形。它的每分钟数值是**每分钟张数**，
 *       而不是秒数或 token，因此低于下限的数值按张强制执行。缺少这一分支曾让一个按张
 *       计费的套餐完全不受每分钟上限约束：两个谓词都不匹配它，于是一个已售出的上限
 *       被静默地忽略了。
 * </ol>
 *
 * <p>由于这些分支互不相交（一个数值不可能既达到或高于该下限又
 * 低于它），{@link #tokenLimit()} 与 {@link #secondLimit()} 至多只有一个会被强制执行，因此
 * 没有任何一个售出数值会同时按两种单位计费。当套餐方案两者都不卖时 —— 每分钟数值是 token 速率的视频套餐方案
 * 没有每秒速率，而数值是秒的视频套餐方案没有 token 速率 —— 对应的方向保持
 * 不受限制，这是有意为之：网关不会凭空发明一个产品从未售出过的速率。
 * 生成仍然受该套餐自己的额度约束，该额度由 {@code QuotaService} 按请求强制执行。
 */
public final class PackageRateLimits {

    /** 未记录维度的行被读取时所采用的维度。 */
    public static final String TOKEN_DIMENSION = "TOKEN";

    /** 其每分钟数值是生成秒数的维度。 */
    public static final String VIDEO_SECOND_DIMENSION = "VIDEO_SECOND";

    /**
     * 其每分钟数值是生成张数的维度。
     *
     * <p>{@code IMAGE} 在计费上是「一张图一个单位」，与 {@code VIDEO_SECOND} 的
     * 「一秒一个单位」同形；区别只在单位名称，因此二者共享同一条准入算术，
     * 但绝不会共享同一个计数器，也绝不会互相解释对方的数字。
     */
    public static final String IMAGE_DIMENSION = "IMAGE";

    /**
     * 其每分钟数值是生成**音频**秒数的维度。
     *
     * <p>与 {@code VIDEO_SECOND} 在算术上完全同形（秒对秒），因此二者共享
     * {@link #isSecondRate}；区别只在单位名称与降级计数所读的用量维度，
     * 而那个维度由上限自己携带。它们绝不会共享同一个计数器：一个音频秒
     * 的成本是 1 分（ASR）或 9 分（换口型），一个视频秒是 69 分，
     * 用同一个数字互相解释就是在两个量级之间错价。
     *
     * <p>漏掉这个维度不会让任何东西报错——它只会让一个音频套餐售出的
     * 每分钟秒数上限被静默忽略，于是客户可以无限速地消耗它。
     * 因此它必须与 V92 的词表同步存在。
     */
    public static final String AUDIO_SECOND_DIMENSION = "AUDIO_SECOND";

    /**
     * 对于授予 TEXT 能力的套餐方案，平台会将其作为 token 速率发布的最低每分钟数值。
     * V63 把它断言为一条发布不变式：
     * {@code published_plan_text_capability_below_a_usable_token_rate}，并附带注释：
     * 10,000「是一个很低的标准 —— 远低于所有真实档位 —— 之所以这样选，只是为了
     * 让一个明显不是 token 速率的数值（60、190、600 或 NULL）无法通过」。
     * 在以秒计价的套餐方案上，低于该下限的数值只能是该套餐方案自身维度的速率。
     */
    public static final long TOKEN_RATE_FLOOR = 10_000L;

    private static final PackageRateLimits UNLIMITED = new PackageRateLimits(null, null, null);

    private final UUID packageInstanceId;
    private final String usageDimension;
    private final Long soldPerMinute;

    private PackageRateLimits(UUID packageInstanceId, String usageDimension, Long soldPerMinute) {
        this.packageInstanceId = packageInstanceId;
        this.usageDimension = usageDimension;
        this.soldPerMinute = soldPerMinute;
    }

    public static PackageRateLimits unlimited() {
        return UNLIMITED;
    }

    /**
     * 读取一个套餐实例售出的每分钟速率。{@code packageInstanceId} 是该速率所属的实例，
     * {@code usageDimension} 是它的 {@code usage_dimension}，{@code soldPerMinute}
     * 是它的 {@code tokens_per_minute}（该套餐方案不售出任何速率时为 null）。
     */
    public static PackageRateLimits of(
            UUID packageInstanceId, String usageDimension, Long soldPerMinute) {
        String dimension = normalizedDimension(usageDimension);
        Long sold = soldPerMinute == null || soldPerMinute <= 0 ? null : soldPerMinute;
        if (packageInstanceId == null || sold == null) {
            return new PackageRateLimits(packageInstanceId, dimension, null);
        }
        return new PackageRateLimits(packageInstanceId, dimension, sold);
    }

    public UUID packageInstanceId() {
        return packageInstanceId;
    }

    /** 套餐方案自身的维度，缺失时读取为 {@link #TOKEN_DIMENSION}。 */
    public String usageDimension() {
        return usageDimension;
    }

    /** 按存储原样返回售出的每分钟数值，套餐方案不售出任何速率时为 null。 */
    public Long soldPerMinute() {
        return soldPerMinute;
    }

    /** 每分钟 token 方向，仅当售出的数值是 token 速率时才强制执行。 */
    public RequestLimitService.PackageTokenLimit tokenLimit() {
        return isTokenRate(usageDimension, soldPerMinute)
                ? RequestLimitService.PackageTokenLimit.of(packageInstanceId, soldPerMinute)
                : RequestLimitService.PackageTokenLimit.unlimited();
    }

    /**
     * 每分钟单位方向 —— 秒数（{@code VIDEO_SECOND} / {@code AUDIO_SECOND}）或张数
     * （{@code IMAGE}）—— 仅当售出的数值是该套餐自身维度的单位为分钟速率时才强制执行。
     *
     * <p>上限本身是**套餐级**的：一个混合套餐（视频秒 + 音频秒）的两个维度
     * 共用这一个数字，计数是两者之和（两条计数器都按套餐实例计，见
     * {@code PackageSecondRateLimitService}）。
     */
    public RequestLimitService.PackageSecondLimit secondLimit() {
        return isSecondRate(usageDimension, soldPerMinute)
                ? RequestLimitService.PackageSecondLimit.of(
                        packageInstanceId, usageDimension, soldPerMinute)
                : RequestLimitService.PackageSecondLimit.unlimited();
    }

    /**
     * 该套餐方案售出的数值是否约束每分钟 token 消耗量。token 套餐方案总是约束
     * —— 这就是该字段原本就有的行为 —— 而以秒计价的套餐方案只在
     * 该数值是平台可以作为 token 速率发布的数值时才约束。
     */
    public static boolean isTokenRate(String usageDimension, Long soldPerMinute) {
        if (soldPerMinute == null || soldPerMinute <= 0) {
            return false;
        }
        return TOKEN_DIMENSION.equals(normalizedDimension(usageDimension))
                || soldPerMinute >= TOKEN_RATE_FLOOR;
    }

    /**
     * 该套餐方案售出的数值是否是它自身维度的**每分钟单位**速率：秒
     * （{@code VIDEO_SECOND} 或 {@code AUDIO_SECOND}）或张（{@code IMAGE}）。
     *
     * <p>三个维度在下限之下都表示该维度自己的单位，而算术完全相同
     * （已计入单位数与已售单位数的比较），因此它们共享本谓词；只有单位名称不同，
     * 而单位名称由 {@link RequestLimitService.PackageSecondLimit#usageDimension()}
     * 携带，拒绝码据此选择措辞。
     *
     * <p>达到或高于 {@link #TOKEN_RATE_FLOOR} 的数值从来不是单位速率：
     * 在那个量级上它是套餐方案的 chat token 速率（V63 的发布守卫保证了这一点），
     * 而把 token 数当作秒数或张数来限制，正是本类要防的错误。
     *
     * <p><b>维度词表必须与网关 V92 同步。</b>这里漏掉一个维度不会让任何东西报错，
     * 只会让那个维度的套餐售出的每分钟上限被静默忽略——即客户无限速。
     * 那是一个不会失败、只会少收限制的缺陷，因此每加一个按单位计费的维度，
     * 这里都要跟着加。
     */
    public static boolean isSecondRate(String usageDimension, Long soldPerMinute) {
        if (soldPerMinute == null || soldPerMinute <= 0) {
            return false;
        }
        String dimension = normalizedDimension(usageDimension);
        return (VIDEO_SECOND_DIMENSION.equals(dimension)
                || IMAGE_DIMENSION.equals(dimension)
                || AUDIO_SECOND_DIMENSION.equals(dimension))
                && soldPerMinute < TOKEN_RATE_FLOOR;
    }

    private static String normalizedDimension(String usageDimension) {
        return usageDimension == null || usageDimension.isBlank()
                ? TOKEN_DIMENSION : usageDimension;
    }
}
