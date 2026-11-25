package com.yuzhihuihe.apiplatform.gateway.file;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 公网匿名读图的签名配置，也是「图生图能不能传本地图」的总开关。
 *
 * <p><b>默认方向是「关」。</b>{@link #getSecret()} 为空时：内容端点一律 404、
 * 生图的 {@code image_file_id} 一律 501，图生图退回「调用方自备公网图片 URL」。
 * 这条默认值是刻意的——一个没配好密钥的公网读图端点，就是一个任何人都能枚举的下载口。
 */
@ConfigurationProperties("gateway.files.content-token")
public class FileContentTokenProperties {

    /**
     * base64 编码的 HMAC-SHA256 密钥（至少 32 字节）。空 = 功能关闭。
     *
     * <p><b>有意不复用 {@code security.internal.hmac-secret}</b>：那是对内的服务间
     * 签名，这里是会被写进 URL、发到公网的读取凭证。用途不同就不该共用一把钥匙——
     * 一把钥匙的泄露半径应该等于它的用途半径。
     */
    private String secret = "";

    /**
     * 签名有效期。
     *
     * <p>厂商拉图通常在数秒内完成，10 分钟覆盖它自己的重试与排队；
     * 再长就只是把「有效期内的地址等于凭证」这句话的窗口开大。
     */
    private Duration ttl = Duration.ofMinutes(10);

    /** 拼在签名地址前面的公网根（**不带** {@code /v1}）。 */
    private URI publicBaseUrl = URI.create("https://api.yuzhh.com");

    /**
     * 单次可交付的最大字节数。
     *
     * <p>与上传侧的上限一致：内容端点只该把「客户端上传回来的图片」原样交给厂商，
     * 不该变成对象存储的大文件出口。
     */
    private long maxBytes = 20L * 1024 * 1024;

    /**
     * 每个来源 IP 在 {@link #getRateLimitWindow()} 窗口内可拉取的最大次数。
     *
     * <p><b>为什么必须限流。</b>这个端点是匿名的、公网可达的，且每次最多交付
     * {@link #getMaxBytes()} 字节。不限流就等于把它当成免费图床：一个泄露的签名地址
     * 在有效期内可被任意来源反复拉取。设计文档
     * {@code docs/operations/图生图方案B-网关中转设计.md:71} 把这一条写成必做项
     * （"必须限流（否则被当免费图床）…超限 429"），而它此前一直没落地。
     *
     * <p>默认值取"够厂商正常拉图、不够当图床"：厂商一次图生图通常只拉 1~8 张，
     * 且会重试。120 次/分钟既覆盖批量与重试，也把单 IP 的出口压在
     * 120 × 20MiB ≈ 2.4 GiB/分钟的量级之下。
     */
    private int rateLimitPerIp = 120;

    /**
     * 限流窗口。
     *
     * <p>与签名有效期同量级（默认 10 分钟）没有意义——窗口越长，越像总量配额而不是
     * 突发保护。一分钟是"突发"与"配额"之间的常用折中。
     */
    private Duration rateLimitWindow = Duration.ofMinutes(1);

    /**
     * Redis 不可用时的行为。
     *
     * <p><b>默认 fail closed（拒绝）。</b>与 {@code SkillDownloadRateLimiter} 同一口径：
     * 限流器绝不降级成"全部放行"，否则一次 Redis 抖动就等于把免费图床的门打开。
     * 代价是 Redis 故障期间厂商拉图会拿到 503——它会看见明确的"暂时不可用"并重试，
     * 而不是我们静默承担带宽成本。
     */
    private boolean rateLimitFailClosed = true;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret == null ? "" : secret;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public URI getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(URI publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getRateLimitPerIp() {
        return rateLimitPerIp;
    }

    public void setRateLimitPerIp(int rateLimitPerIp) {
        this.rateLimitPerIp = rateLimitPerIp;
    }

    public Duration getRateLimitWindow() {
        return rateLimitWindow;
    }

    public void setRateLimitWindow(Duration rateLimitWindow) {
        this.rateLimitWindow = rateLimitWindow;
    }

    public boolean isRateLimitFailClosed() {
        return rateLimitFailClosed;
    }

    public void setRateLimitFailClosed(boolean rateLimitFailClosed) {
        this.rateLimitFailClosed = rateLimitFailClosed;
    }
}
