package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

class OpenAiExceptionHandlerTest {

    @Test
    void doesNotCreateASecondResponseAfterStreamingResponseIsCommitted() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(exchange.getResponse()).thenReturn(response);
        when(response.isCommitted()).thenReturn(true);

        ResponseEntity<ChatCompletionController.OpenAiErrorResponse> result = new OpenAiExceptionHandler()
                .invalidRequest(new ServerWebInputException("client disconnected"), exchange);

        assertThat(result).isNull();
    }
}
