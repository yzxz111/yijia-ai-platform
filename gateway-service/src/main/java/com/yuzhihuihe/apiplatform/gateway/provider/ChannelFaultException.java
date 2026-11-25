package com.yuzhihuihe.apiplatform.gateway.provider;

/**
 * 渠道侧的故障 —— 用来把"渠道配置坏了"与"客户端参数坏了"分开。
 *
 * <h2>为什么需要这个类型</h2>
 *
 * <p>这两类错误以前**抛的是同一种异常**（{@link IllegalArgumentException}），
 * 例如：
 * <pre>
 *   // 渠道的错：baseUrl 没配 / upstreamModel 没配 → 这个渠道根本没救
 *   throw new IllegalArgumentException("invalid_audio_channel");
 *   // 客户端的错：audioUrl 不是 http(s) → 换一个请求就好了
 *   throw new IllegalArgumentException("invalid_audio_transcription_request");
 * </pre>
 *
 * <p>而熔断器只能看类型，因此它的判定
 * （{@code ChatCompletionProviderExecutor.isCircuitFailure}：
 * "不是 {@code IllegalArgumentException} 就算失败"）必然把**渠道配置错误**
 * 也算成"不该熔断"，于是这种渠道永远熔不断、每次请求都白跑一趟。
 *
 * <h2>判定规则</h2>
 *
 * <p>一个错误属于"渠道的错"，当且仅当**重试同一个请求、或者换成任何一个合法请求，
 * 只要还走这个渠道就一定会再失败**。典型是：
 * <ul>
 *   <li>渠道行缺少必需字段（baseUrl / upstreamModel / providerType 为空）——
 *       见各适配器里 {@code record Channel} 的紧凑构造器；</li>
 *   <li>渠道配置的值本身非法（例如 baseUrl 不是合法 URL）；</li>
 *   <li>该渠道不支持所请求的能力（{@code provider_capability_not_supported}）。</li>
 * </ul>
 *
 * <p>反过来，**不要**把它用于上游返回了畸形响应（那是上游的错，
 * 由 {@link ProviderAdapter.ProviderException} 承载），也不要用于
 * 客户端传了非法参数（那是调用方的错）。
 *
 * <p>本类型继承 {@link IllegalArgumentException} 以保持向后兼容：
 * 既有只认 {@code IllegalArgumentException} 的调用方与测试不受影响。
 */
public class ChannelFaultException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public ChannelFaultException(String message) {
        super(message);
    }

    public ChannelFaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
