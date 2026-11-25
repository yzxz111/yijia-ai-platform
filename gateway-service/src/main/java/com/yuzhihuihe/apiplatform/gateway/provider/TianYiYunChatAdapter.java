package com.yuzhihuihe.apiplatform.gateway.provider;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 天翼 AI 网关（{@code ai.ctaigw.cn}）的对话与视觉理解。
 *
 * <p><b>这个类补的是一个真实事故缺的那一半。</b>
 * {@code ProviderCapabilityCatalog} 从头注释就在讲同一件事：运营把对话/视觉
 * 渠道挂到了 {@code provider_type = TIANYIYUN} 的供应商下。天翼那把 key
 * 明明能跑对话与读图（厂商侧 {@code /v1/models} 里有
 * {@code Qwen2.5-VL-72B-Instruct}），但 {@code TIANYIYUN} 这个类型**只有
 * 生图与视频适配器**，于是每一条聊天请求都在运行时以 503
 * {@code upstream_unavailable} 失败，而控制台上渠道显示 HEALTHY。
 *
 * <p>当时的修复是正确的但只是一半：{@link
 * com.yuzhihuihe.apiplatform.gateway.routing.ProviderCapabilityCatalog}
 * 开始在**保存路由时**就拒绝这个组合，理由是没有适配器能服务它。
 * 那半边的代价是：天翼的视觉能力在配置层被彻底封死，只能用别家。
 *
 * <p>这个类提供另一半。适配器 bean 一旦存在，能力目录会自动把它读进来
 * ——目录从 bean 取能力，不读硬编码表——于是天翼的读图路由从
 * 「必须被拒」变成「可以挂」。**这不是放宽校验，而是补上被校验挡住的那个
 * 实现**；两者要一起改，否则我们要么继续丢掉一个可用的上游，
 * 要么重新引入那次事故。
 *
 * <p><b>协议是 OpenAI 兼容的</b>，因此复用
 * {@link OpenAiCompatibleProviderAdapter}：请求体被
 * {@code ProviderAdapter.sanitize} 收窄到 {@code FORWARDED_FIELDS}，
 * 发往 {@code {base}/chat/completions}。渠道的 {@code base_url} 因此必须
 * 存到 {@code /v1}（存成裸域名会得到 {@code https://ai.ctaigw.cn/chat/completions}，
 * 即一个 404 —— 正是「配好了却跑不通」的形态）。
 *
 * <p><b>能力声明是 {@code TEXT} 与 {@code IMAGE}，不含 {@code FILE}。</b>
 * {@code FILE} 在平台上是 {@code qwen-long} 那套专用文件接口
 * （见 {@link BailianAdapter} 的 {@code fileid://} 转换），天翼没有任何
 * 证据支持它。声明天翼支持 FILE 会让运营把一个文档模型挂上去，
 * 然后在运行时的第一次绑定失败——即这次事故的同一形态。
 * 只声明有证据的能力，是这个类存在的全部意义。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class TianYiYunChatAdapter extends OpenAiCompatibleProviderAdapter {

    /**
     * 天翼那个 key 已被证实能服务的模态：文本，以及读图
     * （厂商侧 {@code Qwen2.5-VL-*})。{@code FILE} 刻意不在其中，见类注释。
     */
    private static final Set<ChannelRouter.Capability> CAPABILITIES = Set.of(
            ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE);

    public TianYiYunChatAdapter(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        super(webClientBuilder, objectMapper);
    }

    /**
     * Spring 用的构造器：带上「首帧」与「空闲」两个等待窗口。
     *
     * <p>生产上的对话渠道就是这条，而它承载的正是推理模型：思考阶段可能远超空闲窗口，
     * 所以这个适配器是两窗口改动的直接受益者。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public TianYiYunChatAdapter(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            ChatStreamTimeoutPolicy timeouts) {
        super(webClientBuilder, objectMapper, timeouts);
    }

    /** 供能力矩阵与测试读取，避免它们各自再抄一份字面量。 */
    public static Set<ChannelRouter.Capability> supportedCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public String providerType() {
        return "TIANYIYUN";
    }

    @Override
    public Set<ChannelRouter.Capability> capabilities() {
        return CAPABILITIES;
    }
}
