package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

class ChatCompletionRetryContractTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void admissionFailureReturns429AndRetryHintBeforeStreamingStarts(boolean stream) {
        ChatCompletionService service = mock(ChatCompletionService.class);
        ResponseStatusException limited = new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded");
        when(service.complete(isNull(), any(), anyString())).thenReturn(Mono.error(limited));
        when(service.openStream(isNull(), any(), anyString())).thenReturn(Mono.error(limited));
        ChatCompletionController controller = new ChatCompletionController(service);
        var body = new ObjectMapper().createObjectNode().put("model", "chat-standard")
                .put("stream", stream);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions").build());

        var result = controller.complete(body, exchange).block();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(result.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(result.getHeaders().getFirst("x-request-id")).startsWith("req_");
        assertThat(((ChatCompletionController.OpenAiErrorResponse) result.getBody()).error().code())
                .isEqualTo("rate_limit_exceeded");
    }
}
