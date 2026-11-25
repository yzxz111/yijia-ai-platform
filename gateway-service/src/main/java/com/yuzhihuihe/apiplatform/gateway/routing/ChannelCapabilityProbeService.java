package com.yuzhihuihe.apiplatform.gateway.routing;

import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.CapabilityProbeView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 「按能力探测」的编排：取渠道与凭据 → 取该渠道的能力映射 → 逐能力探它自己的端点。
 *
 * <p>为什么自成一个组件、而不是挂到 {@code GatewayAdminService} 上：那个类的构造器在
 * 生产与本地之间已经分叉（本地多出若干依赖），往它的构造器里加参数会把一次小改动
 * 变成一次需要同步整份文件的改动——而同步整份文件会把别人尚未定稿的在途改动一起
 * 带上生产。这个类只依赖三件稳定的事：仓库、凭据解密、探针实现。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ChannelCapabilityProbeService {

    private final GatewayAdminRepository repository;
    private final CredentialCipher cipher;
    private final CapabilityProbeService probe;

    ChannelCapabilityProbeService(
            GatewayAdminRepository repository, CredentialCipher cipher,
            CapabilityProbeService probe) {
        this.repository = repository;
        this.cipher = cipher;
        this.probe = probe;
    }

    /**
     * 用这条渠道已保存的密钥，对每种已配置能力探它自己真正的端点。
     *
     * <p>密钥解密后只用于这几次请求，绝不回显；探测体用空输入或占位素材，
     * 不会产生一次真实的生成或计费。
     */
    public Mono<List<CapabilityProbeView>> probe(UUID channelId) {
        if (channelId == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "channel_not_found"));
        }
        return repository
                .channelProbe(channelId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "channel_not_found")))
                .flatMap(row -> {
                    byte[] encrypted = row.encryptedApiKey();
                    if (encrypted == null || encrypted.length == 0) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY, "channel_credential_required"));
                    }
                    String key;
                    try {
                        key = cipher.decrypt(encrypted);
                    } catch (RuntimeException undecryptable) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, "credential_decryption_failed"));
                    }
                    String baseUrl = row.baseUrl() == null ? "" : row.baseUrl().trim();
                    if (baseUrl.isBlank()) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_channel_base_url"));
                    }
                    return repository.channelCapabilityRows(channelId)
                            .map(mapping -> new CapabilityProbeService.CapabilityMapping(
                                    mapping.upstreamModel(), mapping.capabilitiesJson()))
                            .collectList()
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.UNPROCESSABLE_ENTITY,
                                    "channel_has_no_capability_mapping")))
                            .flatMap(rows -> probe.probe(
                                    channelId, row.providerType(), baseUrl, key,
                                    row.timeoutMs(), rows));
                });
    }
}
