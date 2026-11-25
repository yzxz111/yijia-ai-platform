package com.yuzhihuihe.apiplatform.gateway.audio;

import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 音频别名的只读权益目录，与 {@code /v1/video-models}、{@code /v1/image-models} 对称。
 *
 * <p>它有意不报告供应商/渠道就绪状态，也不报告剩余额度：网关在真正调用时才判断
 * 已配置的供应商能否服务所选别名（见 {@link AudioGenerationService} 的执行路径）。
 * 与另外两条目录一样返回 {@code Cache-Control: no-store}——权益目录不允许被缓存，
 * 否则套餐撤销后客户端会继续展示旧别名。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class AudioModelsController {

    private final AudioModelCatalogService catalog;

    AudioModelsController(AudioModelCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/v1/audio-models")
    Mono<ResponseEntity<AudioModelListResponse>> models(ServerWebExchange exchange) {
        ApiKeyService.ApiKeyContext context =
                exchange.getAttribute(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE);
        if (context == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "invalid_api_key"));
        }
        String requestId = exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, requestId());
        return catalog.availableModels(context).collectList()
                .map(models -> ResponseEntity.ok()
                        .header("x-request-id", requestId)
                        .header("Cache-Control", "no-store")
                        .body(new AudioModelListResponse("list", models)));
    }

    private String requestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    record AudioModelListResponse(
            String object, List<AudioModelCatalogService.AudioModelView> data) { }
}
