package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class GatewayAdminExceptionHandlerTest {

    @Test
    void exposesStableModelDeletionReason() {
        var response = new GatewayAdminExceptionHandler().handle(
                new ResponseStatusException(HttpStatus.CONFLICT, "model_referenced_by_package"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("model_referenced_by_package");
        assertThat(response.getBody().message()).contains("套餐版本引用");
    }
}
