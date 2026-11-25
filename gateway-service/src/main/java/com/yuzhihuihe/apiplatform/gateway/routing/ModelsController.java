package com.yuzhihuihe.apiplatform.gateway.routing;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
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

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ModelsController {

    private final ModelCatalogService catalog;

    public ModelsController(ModelCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/v1/models")
    Mono<ResponseEntity<ModelListResponse>> models(ServerWebExchange exchange) {
        ApiKeyService.ApiKeyContext context = exchange.getAttribute(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE);
        if (context == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_api_key"));
        }
        String requestId = exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, requestId());
        return catalog.availableModels(context).collectList()
                .flatMap(models -> models.isEmpty()
                        // 空目录才计算原因（P1-5）：非空时客户端不需要理由，
                        // 也就不该为此多付一次查询。
                        ? catalog.emptyReason(context)
                                .map(reason -> response(requestId, models, reason))
                                .defaultIfEmpty(response(requestId, models, null))
                        : Mono.just(response(requestId, models, null)));
    }

    private ResponseEntity<ModelListResponse> response(
            String requestId, List<ModelCatalogService.ModelView> models, String reason) {
        return ResponseEntity.ok()
                .header("x-request-id", requestId)
                // 权益目录是"你还拥有什么"的实时视图：套餐撤销/模型停用后必须
                // 立刻反映，因此与 /v1/quota 用同一条规则显式禁止缓存
                // （nginx 侧 /v1/ 已是 proxy_cache off，这是第二道防线）。
                .header("Cache-Control", "no-store")
                .body(new ModelListResponse("list", models, reason));
    }

    private String requestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * @param reason 仅当 {@code data} 为空时出现（P1-5）：稳定原因码，说明"为什么没有模型"。
     *        非空时为 null，并由 {@code NON_NULL} 从 JSON 中省略——这样既不影响
     *        OpenAI 客户端的既有解析，也让接入方能给出可操作的提示。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ModelListResponse(
            String object, List<ModelCatalogService.ModelView> data, String reason) {
    }
}
