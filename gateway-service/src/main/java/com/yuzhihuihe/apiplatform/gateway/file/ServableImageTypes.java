package com.yuzhihuihe.apiplatform.gateway.file;

import java.util.Locale;
import java.util.Set;

/**
 * 网关愿意当作「图片」交出去、也愿意收下做图生图的类型。
 *
 * <p><b>两处必须用同一份定义</b>：公网读图端点用它决定「发不发」，图生图入口用它决定
 * 「收不收」。分成两份的那天起，「能被读到」与「能被拿去生图」就会变成两个会各自漂移
 * 的答案——而漂移出来的那个空隙正好是一个缺陷：一个 PDF 被图生图收下，厂商拿到一个
 * 读不到图（或读到 PDF）的地址，然后失败。
 *
 * <p>写成白名单而不是「{@code image/} 开头」：后者会放行 {@code image/svg+xml}，
 * 而 SVG 是可以内嵌脚本的文档格式，把它当图片从公网发出去是另一类问题。
 */
public final class ServableImageTypes {

    private static final Set<String> TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    private ServableImageTypes() {
    }

    /** 大小写与空白归一化后判断；{@code null} 不是图片。 */
    public static boolean isServable(String mimeType) {
        return mimeType != null && TYPES.contains(mimeType.strip().toLowerCase(Locale.ROOT));
    }
}
