package com.yuzhihuihe.apiplatform.gateway.routing;

import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.CapabilityProbeView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 按能力探测渠道凭据的只读端点。
 *
 * <p>它独立于 {@link AdminGatewayController} 只是为了让这条能力的接线自成一体；
 * 路由前缀与那一个相同，控制台把它当成同一族接口即可。
 *
 * <p>与已存在的 {@code POST /console/gateway/admin/channels/{id}/models} 的区别，
 * 正是这个端点存在的理由：那一个只会请求 {@code {base}/models}，而那是一条只属于
 * **对话/视觉**的端点——百炼的音频、视频、生图模型都不出现在上面。给一条音频渠道点
 * 「检测密钥」，它必然回 {@code HTTP 404}，看起来像"key 错了"，实际与 key 毫无关系。
 * 本端点对每种能力都打**它自己真正会用的那个端点**，并把
 * 「凭据被拒（401/403）」「端点不存在（404）」「连不上（0）」三种情况分开报告。
 */
@RestController
@RequestMapping("/console/gateway/admin")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ChannelCapabilityProbeController {

    private final ChannelCapabilityProbeService probes;

    ChannelCapabilityProbeController(ChannelCapabilityProbeService probes) {
        this.probes = probes;
    }

    /**
     * 用这条渠道已保存的密钥，逐能力探一次它自己的端点。
     *
     * <p>返回数组，每项给出：能力标签、实际请求的地址与请求方法、上游应答码，以及
     * {@code credentialAccepted}。**空输入或占位素材**被刻意用在请求体里，让上游在参数
     * 校验处停下：视频/生图/口型因此不会创建任务、不会计费，而它们返回的 400/422
     * 恰恰证明鉴权已经通过——一个没通过鉴权的请求走不到参数校验那一步。
     */
    @PostMapping("/channels/{id}/capability-probe")
    Mono<List<CapabilityProbeView>> probeChannelCapabilities(@PathVariable UUID id) {
        return probes.probe(id);
    }
}
