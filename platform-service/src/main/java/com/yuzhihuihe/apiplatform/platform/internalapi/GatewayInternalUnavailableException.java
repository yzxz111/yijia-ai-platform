package com.yuzhihuihe.apiplatform.platform.internalapi;

/**
 * 平台无法从网关的内部 API 获得可信的应答。
 *
 * <p>每一个读取网关自有计数器的调用方都面临同样的诱惑：当读取失败时，
 * 返回零。零与「什么都没发生」无法区分，因此控制台会把
 * 一个正被主动限流的套餐显示为空闲的套餐，管理员也会看不到任何被吸收的垫付缺口，
 * 而网关正在悄悄吞掉资金。把该失败放在它自身的类型中承载，
 * 可将这一判断留在边界处：在那里选择唯一诚实的应答 —— 503，而不是让
 * 编造或残缺的响应体到达控制台。
 *
 * <p>该消息是稳定的机器可读原因（例如
 * {@code gateway_limit_usage_payload_invalid}），而非面向用户的文案。
 */
public class GatewayInternalUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GatewayInternalUnavailableException(String reason) {
        super(reason);
    }

    public GatewayInternalUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
