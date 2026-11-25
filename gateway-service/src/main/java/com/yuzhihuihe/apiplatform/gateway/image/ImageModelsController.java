package com.yuzhihuihe.apiplatform.gateway.image;

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
 * 同步图片生成 API 的只读权益目录，与 {@code /v1/video-models} 对称。
 *
 * <p>响应描述调用方生效的 {@code IMAGE} 套餐所售的模型。它有意
 * 不报告供应商/渠道就绪状态或任何上游路由细节；网关正是在生成时才判断
 * 已配置的供应商能否真正服务所选模型。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ImageModelsController {

    private final ImageModelCatalogService catalog;

    ImageModelsController(ImageModelCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/v1/image-models")
    Mono<ResponseEntity<ImageModelListResponse>> models(ServerWebExchange exchange) {
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
                        .body(new ImageModelListResponse("list", models)));
    }

    private String requestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    record ImageModelListResponse(
            String object, List<ImageModelCatalogService.ImageModelView> data) { }
}
