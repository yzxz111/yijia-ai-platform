package com.yuzhihuihe.apiplatform.gateway.openai;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.file.FilesController;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice(assignableTypes = {
        ChatCompletionController.class, ResponsesController.class, FilesController.class,
        // POST /v1/videos 上的畸形请求体会在控制器运行之前就被解码，因此该视频端点
        // 也需要同样的 advice，才能用 OpenAI 的信封作答。
        com.yuzhihuihe.apiplatform.gateway.video.VideoGenerationController.class
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class OpenAiExceptionHandler {

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ChatCompletionController.OpenAiErrorResponse> invalidRequest(
            ServerWebInputException error, ServerWebExchange exchange) {
        // 流式响应可能在客户端断开之前就已经发出了它的 200 响应头。
        // 不要试图用一个错误信封去替换那个已提交的响应。
        if (exchange.getResponse().isCommitted()) {
            return null;
        }
        String requestId = exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, requestId());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("x-request-id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatCompletionController.OpenAiErrorResponse(
                        new ChatCompletionController.OpenAiError(
                                "Invalid request",
                                "invalid_request_error", "invalid_request"),
                        requestId));
    }

    private String requestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }
}
