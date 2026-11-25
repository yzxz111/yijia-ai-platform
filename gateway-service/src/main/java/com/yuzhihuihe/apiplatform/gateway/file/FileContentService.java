package com.yuzhihuihe.apiplatform.gateway.file;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * 公网匿名读图：校验签名，然后把**一张已扫描通过的图片**交给发起方。
 *
 * <p>它服务的对象不是我们的客户端，而是**厂商**——天翼要自己去拉那张参考图，
 * 而它不会带我们的 API key。因此这个链路上唯一能被要求的东西就是 URL 里的签名。
 *
 * <p>三条边界是这里存在的全部理由：
 *
 * <ol>
 *   <li><b>只发 {@code READY}。</b>未扫描、扫描失败、已被拒的文件一律不可读——
 *       把一个还没过安全扫描的上传物交到公网，等于绕过了整条扫描链路。</li>
 *   <li><b>只发图片。</b>MIME 不在白名单里的一律 404。少这一条，这个端点就会
 *       变成对象存储的通用下载口。</li>
 *   <li><b>越权与不存在不可区分。</b>所有拒绝都走同一句 404，枚举者拿不到
 *       任何反馈信号。</li>
 * </ol>
 *
 * <p>另有一件刻意**不做**的事：这里不校验归属。归属校验发生在**签发**那一侧
 * （{@code FileService.requireUsable} 按 tenant+user 查文件），而签名一旦发出，
 * 它本身就是这次读取的完整授权——这正是「把地址交给第三方」这件事的语义。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class FileContentService {

    /** 上限 5 分钟：图片内容在一次生成里是稳定的，但地址本身是短时效的。 */
    private static final long MAX_CACHE_SECONDS = 300;

    private final FileObjectRepository repository;
    private final FileObjectStore objectStore;
    private final FileContentTokenSigner signer;
    private final long maxBytes;
    private final Clock clock;

    /**
     * 装配用的构造器。
     *
     * <p>必须显式标 {@code @Autowired}：本类还有一条给测试用的构造器（它多收一个
     * {@code Clock}），两个构造器都不带注解时 Spring 无从选择，会在启动期报
     * 「没有默认构造器」——而那是一个只有跑起完整上下文才会暴露的失败。
     */
    @Autowired
    FileContentService(
            FileObjectRepository repository,
            FileObjectStore objectStore,
            FileContentTokenSigner signer,
            FileContentTokenProperties properties) {
        this(repository, objectStore, signer,
                properties == null ? 0L : properties.getMaxBytes(), Clock.systemUTC());
    }

    FileContentService(
            FileObjectRepository repository,
            FileObjectStore objectStore,
            FileContentTokenSigner signer,
            long maxBytes,
            Clock clock) {
        this.repository = repository;
        this.objectStore = objectStore;
        this.signer = signer;
        this.maxBytes = maxBytes <= 0 ? 20L * 1024 * 1024 : maxBytes;
        this.clock = clock;
    }

    /**
     * 读出一次可交付的图片内容。
     *
     * <p>签名先验证、再查库：这样一次伪造请求连数据库都碰不到，
     * 一个只改签名的扫描器不会变成对 {@code file_object} 的压力。
     */
    Mono<Content> read(String externalFileId, String expires, String signature) {
        return Mono.defer(() -> {
            signer.requireValid(externalFileId, expires, signature, clock.instant());
            return repository.findReadyForRead(externalFileId)
                    .switchIfEmpty(Mono.error(FileContentTokenSigner.notFound()))
                    .flatMap(this::load);
        });
    }

    /**
     * 只验签、不读对象。
     *
     * <p>给"签名合法才计数、但必须在**读对象之前**计数"这条顺序用：限流器挂在
     * 完整取回之后，等于只限制了交付出去的字节，而超限请求照样能把 HMAC 校验、
     * 一次数据库查询和一整份 ≤20 MiB 的对象读全都做完——那正是"被当免费图床"
     * 要挡住的那部分代价，只是没有出口带宽而已。
     *
     * <p>与 {@link #read} 同样把签名放在最前面：伪造的洪流连数据库都不该碰到。
     *
     * @throws ResponseStatusException 404 {@code file_not_found}（签名或有效期不合法）
     */
    void requireToken(String externalFileId, String expires, String signature) {
        signer.requireValid(externalFileId, expires, signature, clock.instant());
    }

    /**
     * 准入之后读出内容。
     *
     * <p>与 {@link #read} 的区别是它**不再重复验签**——调用方已经用
     * {@link #requireToken} 验过一次。保留它们分开是为了让"读对象"这一步无法在
     * 限流之前被顺手调用：顺序一旦写错，代价是整份对象的读取。
     */
    Mono<Content> readAdmitted(String externalFileId) {
        return Mono.defer(() -> repository.findReadyForRead(externalFileId)
                .switchIfEmpty(Mono.error(FileContentTokenSigner.notFound()))
                .flatMap(this::load));
    }

    private Mono<Content> load(FileObjectRepository.FileObject file) {
        if (!ServableImageTypes.isServable(file.mimeType())) {
            return Mono.error(FileContentTokenSigner.notFound());
        }
        return objectStore.get(file.objectKey(), maxBytes)
                .map(bytes -> new Content(file.mimeType(), bytes, maxAgeSeconds(file)))
                // 行在、对象没了：这是数据不一致，但对外与「不存在」同一副面孔，
                // 因为两者的正确处置都是「这个地址拿不到图」。
                .onErrorMap(FileObjectMissingException.class,
                        error -> FileContentTokenSigner.notFound());
    }

    /**
     * 允许下游缓存多久：不越过文件自身的保留期，也不越过 5 分钟的上限。
     *
     * <p>给 0 而不是给负数是刻意的——一个已经过期的文件本不该走到这里，
     * 但即使走到了，也不该被指示成「可以缓存」。
     */
    private long maxAgeSeconds(FileObjectRepository.FileObject file) {
        Instant expiresAt = file.expiresAt();
        if (expiresAt == null) {
            return 0;
        }
        long remaining = Duration.between(clock.instant(), expiresAt).toSeconds();
        return Math.max(0, Math.min(remaining, MAX_CACHE_SECONDS));
    }

    /** @param maxAgeSeconds 建议的 {@code Cache-Control: max-age} */
    record Content(String mimeType, byte[] bytes, long maxAgeSeconds) {
    }
}
