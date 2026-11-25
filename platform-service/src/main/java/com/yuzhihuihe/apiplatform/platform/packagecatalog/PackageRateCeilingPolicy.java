package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code package_plan_version.tokens_per_minute} 的单位守卫。
 *
 * <p>该列是承载维度的：对 TOKEN 版本它是 token 速率，对 VIDEO_SECOND（或 IMAGE）
 * 版本它是每分钟秒数上限，而该列本身并不提示它想要哪个单位。
 * 这不是理论上的风险——V63 把它当作 token 速率读取，
 * 用视频套餐各自 token 档位的 100,000 / 300,000 / 500,000 覆盖了它们的 60 / 190 / 600，
 * 并且上线了一个渲染出 "SPM 100,000" 的控制台。V64 修复了数据并在迁移中
 * 断言了该不变式，但迁移只有在有人写出它时才会运行：若没有这个
 * 守卫，下一个通过管理控制台创建视频套餐的运维就会犯完全
 * 相同的错误，而且在下一个迁移拒绝应用之前不会有任何提示。
 *
 * <p>因此该规则在版本被创建时和被发布时强制执行——这正是
 * 毛利守卫已经运行的那两个位置，所以运维会得到一致的失败风格和
 * 一致的错误信封。这些边界就是 V64 对已发布行断言的边界，这里以
 * 具名常量而非字面量的形式保留，使迁移、守卫与控制台消息不会
 * 彼此漂移：
 *
 * <ul>
 *   <li>{@code VIDEO_SECOND} / {@code IMAGE}：每分钟 {@code 1..86400} 秒。86,400 是
 *       合理性边界，而不是物理边界——它是一天的秒数，其作用是让
 *       一个 token 量级的数字无法被存进来。
 *   <li>授予 TEXT 的 {@code TOKEN}：每分钟至少 {@link #MINIMUM_TOKEN_RATE_FOR_TEXT} 个
 *       token。10,000 远低于任何真实档位，之所以选它，只是为了让一个
 *       明显不是 token 速率的值（60、190、600）无法卖给聊天客户。
 *   <li>任何其它形态（不授予 TEXT 的 TOKEN 版本，或没有
 *       每分钟语义的维度）都不予干涉：它的每分钟速率并不是有
 *       被混淆风险的那个单位，拒绝它会凭空发明一条没人要求的产品规则。
 * </ul>
 *
 * <p>没有需要保留的「无限」取值。{@code tokens_per_minute} 在数据库层是 NOT NULL
 * 且带有 {@code CHECK (tokens_per_minute > 0)}，而 {@code CreateVersionRequest} 以
 * 基本类型 {@code long} 承载它，因此一个不售卖任何每分钟速率的版本根本无法
 * 用这一列表达——真正具有 NULL 表示无限语义的限制是
 * {@code daily_request_limit} 与 {@code max_output_tokens}，本守卫不涉及它们。
 */
public final class PackageRateCeilingPolicy {

    /**
     * {@code tokens_per_minute} 表示每分钟**单位数**上限的那些维度：
     * 视频秒、音频秒、图片张。
     *
     * <p><b>漏掉一个维度的后果是这条守卫被整体跳过。</b>不在其中的维度既不会
     * 被 {@link #requireSoldRate} 的秒分支检查，也不会被 TOKEN 分支检查
     * （除非它恰好是 TOKEN），于是它可以把一个 token 量级的数字当作
     * 「每分钟秒数」卖出去——而 §39-43 说明这一列没有 NULL 表示无限，
     * 因此那个数字就是实际生效的上限。这正是本类存在要防的错误。
     */
    static final Set<String> SECOND_DIMENSIONS =
            Set.of("VIDEO_SECOND", "IMAGE", "AUDIO_SECOND");

    /**
     * 一分钟能容纳的最多秒数。这是 V64 对已发布秒版本的边界；它的存在
     * 是为了让 token 速率（100,000 及以上）无法写入这一列。
     */
    public static final long MAXIMUM_SECONDS_PER_MINUTE = 86_400L;

    /**
     * 一个授予 TEXT 的版本可以售卖的最低每分钟 token 速率。这是 V64 对
     * 已发布 token 版本的边界。
     */
    public static final long MINIMUM_TOKEN_RATE_FOR_TEXT = 10_000L;

    /**
     * 秒列中的取值一旦达到这个量级，一眼就能看出它是 token 速率：每个 token
     * 档位每分钟至少售卖这么多 token（60、190 和 600 离它差得很远）。
     * 以常量形式携带，好让错误消息能指出它拒绝的是哪种形态。
     */
    static final long TOKEN_RATE_SHAPED_AT_LEAST = 10_000L;

    /** 一个 VIDEO_SECOND 版本被售出的速率并不是每分钟秒数上限。 */
    public static final String VIDEO_SECONDS_CODE = "video_seconds_per_minute_out_of_range";
    /** 对以秒计的 IMAGE 维度施加的同一条规则。 */
    public static final String IMAGE_SECONDS_CODE = "image_seconds_per_minute_out_of_range";
    /**
     * 对 AUDIO_SECOND 施加的同一条规则。
     *
     * <p>刻意与视频分开一个码：两者的单位相同（秒），但一个音频套餐被误配成
     * 视频量级的上限时，运维需要看到的是「音频」这个词——否则他会去查视频
     * 版本的配置，而那里没有问题。
     */
    public static final String AUDIO_SECONDS_CODE = "audio_seconds_per_minute_out_of_range";
    /** 一个授予 TEXT 的 TOKEN 版本被售出的速率过低，无法支撑一次对话。 */
    public static final String TOKEN_RATE_CODE = "text_tokens_per_minute_below_minimum";

    private PackageRateCeilingPolicy() {
    }

    /**
     * 拒绝一个售出的每分钟速率与其维度所计量的单位相矛盾的版本。
     *
     * @param usageDimension 该版本的维度，由它自己的模型权限解析得到
     * @param tokensPerMinute 该版本将要售出的速率
     * @param permissions 该版本授予的每一项模型权限，好让 TEXT 检查读取的是
     *        该版本实际会携带的同一集合，而不是调用方对它的概括
     * @throws ResponseStatusException 422，携带上面三个错误码之一，由目录的
     *         控制器通知把它转换成控制台的 {@code {code,message}} 信封
     */
    static void requireSoldRate(
            String usageDimension, long tokensPerMinute,
            Set<PackageVersionService.ModelPermission> permissions) {
        Objects.requireNonNull(permissions, "permissions");
        if (SECOND_DIMENSIONS.contains(usageDimension)) {
            if (tokensPerMinute > 0 && tokensPerMinute <= MAXIMUM_SECONDS_PER_MINUTE) {
                return;
            }
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, secondsCode(usageDimension));
        }
        if ("TOKEN".equals(usageDimension)
                && tokensPerMinute < MINIMUM_TOKEN_RATE_FOR_TEXT
                && grantsText(permissions)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, TOKEN_RATE_CODE);
        }
    }

    /**
     * 该维度的秒上限违规码。
     *
     * <p>每个维度都有**自己**的码，而不是让未知维度落到视频那一个。
     * 后者会在「给一个秒维度加了上限检查、却忘了给它一个码」时，
     * 把一条音频的配置错误报成视频的——运维于是会去查错的那张卡片。
     * 因此这里对未知维度**大声失败**，而不是挑一个看起来合理的默认值：
     * 走到这个分支说明 {@link #SECOND_DIMENSIONS} 与这里不同步，
     * 而那是一个必须在开发期就炸掉的错误。
     */
    private static String secondsCode(String usageDimension) {
        return switch (usageDimension) {
            case "VIDEO_SECOND" -> VIDEO_SECONDS_CODE;
            case "IMAGE" -> IMAGE_SECONDS_CODE;
            case "AUDIO_SECOND" -> AUDIO_SECONDS_CODE;
            default -> throw new IllegalStateException(
                    "second_dimension_without_a_ceiling_code:" + usageDimension);
        };
    }

    private static boolean grantsText(Set<PackageVersionService.ModelPermission> permissions) {
        return permissions.stream()
                .anyMatch(permission -> permission.capabilities() != null
                        && permission.capabilities().contains("TEXT"));
    }
}
