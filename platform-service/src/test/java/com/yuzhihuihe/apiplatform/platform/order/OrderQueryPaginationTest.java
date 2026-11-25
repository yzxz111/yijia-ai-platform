package com.yuzhihuihe.apiplatform.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OrderQueryPaginationTest {

    @Test
    void rejectsOffsetOverflowAsInvalidPagination() {
        OrderQueryRepository repository = mock(OrderQueryRepository.class);
        OrderQueryService service = new OrderQueryService(repository, new ObjectMapper());

        assertThatThrownBy(() -> service.list(
                UUID.randomUUID(), UUID.randomUUID(), Integer.MAX_VALUE, 100, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(response.getReason()).isEqualTo("invalid pagination");
                });
        verifyNoInteractions(repository);
    }
}
