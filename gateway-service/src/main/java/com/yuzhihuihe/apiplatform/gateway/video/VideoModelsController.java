package com.yuzhihuihe.apiplatform.gateway.video;

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
 * 异步视频 API 的只读权益目录。
 *
 * <p>响应描述调用方生效的秒数套餐所售的模型。它有意
 * 不报告供应商/渠道就绪状态或任何上游路由细节；网关正是在提交时
 * 才判断已配置的供应商能否真正服务所选模型。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class VideoModelsController {

    private final VideoModelCatalogService catalog;

    VideoModelsController(VideoModelCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/v1/video-models")
    Mono<ResponseEntity<VideoModelListResponse>> models(ServerWebExchange exchange) {
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
                        // 与 /v1/quota、/v1/models 一致：权益目录不允许被缓存。
                        .header("Cache-Control", "no-store")
                        .body(new VideoModelListResponse("list", models)));
    }

    private String requestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    record VideoModelListResponse(
            String object, List<VideoModelCatalogService.VideoModelView> data) { }
}
