package com.yuzhihuihe.apiplatform.gateway.routing.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * 控制台模型视图的**顺序契约**（P1-3 回归）。
 *
 * <p>{@code aliases} 与 {@code capabilities} 会被序列化成 JSON 数组交给控制台与客户端。
 * 它们曾经由 {@code Set.copyOf} 构造，迭代顺序未定义（OpenJDK 按进程启动时的随机盐），
 * 于是同一条数据在不同进程里会返回不同顺序的数组——客户端的相等比较/缓存与测试因此变成偶发。
 *
 * <p>这里用"输入故意逆序"的方式把契约钉住：无论调用方给什么顺序，输出都必须是字母序。
 */
class GatewayAdminResponsesTest {

    @Test
    void sortsAliasesAndCapabilitiesSoTheJsonArrayOrderIsDeterministic() {
        GatewayAdminResponses.ModelView view = new GatewayAdminResponses.ModelView(
                UUID.randomUUID(),
                "multimodal-basic",
                "多模态基础版",
                new LinkedHashSet<>(List.of("text-basic", "multimodal-basic")),
                new LinkedHashSet<>(List.of("IMAGE", "TEXT", "FILE")),
                260096,
                32768,
                false,
                true,
                300,
                50,
                "COMPOSITE");

        assertThat(view.aliases()).containsExactly("multimodal-basic", "text-basic");
        assertThat(view.capabilities()).containsExactly("FILE", "IMAGE", "TEXT");
    }

    @Test
    void keepsEmptyCollectionsInsteadOfNulls() {
        GatewayAdminResponses.ModelView view = new GatewayAdminResponses.ModelView(
                UUID.randomUUID(), "text-basic", "文本基础版", null, null,
                991808, 65536, false, true, 300, 50, "DIRECT");

        assertThat(view.aliases()).isEmpty();
        assertThat(view.capabilities()).isEmpty();
        assertThat(view.routes()).isEmpty();
    }
}
