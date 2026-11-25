package com.yuzhihuihe.apiplatform.gateway.economics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

class UsageEconomicsOutboxHealthControllerTest {

    @Test
    void endpointIsHmacProtectedBoundedAndNeverSelectsPayload() throws Exception {
        var method = UsageEconomicsOutboxHealthController.class.getDeclaredMethod("health",
                Instant.class, Instant.class, int.class, Instant.class, java.util.UUID.class,
                boolean.class);
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly(UsageEconomicsOutboxHealthController.PATH);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('INTERNAL')");
        assertThat(UsageEconomicsOutboxHealthRepository.EVENT_SQL)
                .contains("status='SENT'", "payload_hash", "LIMIT :fetch_limit")
                .doesNotContain("payload::", " payload,");
        assertThat(UsageEconomicsOutboxHealthRepository.STATUS_SQL)
                .contains("status='PENDING'", "status='SENDING'", "status='FAILED'", "status='DEAD'");
        assertThat(com.yuzhihuihe.apiplatform.gateway.security.InternalHmacWebFilter.class).isNotNull();
    }

    @Test
    void validatesRangeCursorAndPageBounds() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        assertThatThrownBy(() -> UsageEconomicsOutboxHealthController.validate(
                from, from.plus(java.time.Duration.ofDays(367)), 500, null, null))
                .hasMessage("outbox_health_range_exceeds_366_days");
        assertThatThrownBy(() -> UsageEconomicsOutboxHealthController.validate(
                from, from.plusSeconds(1), 501, null, null))
                .hasMessage("outbox_health_page_limit_invalid");
        assertThatThrownBy(() -> UsageEconomicsOutboxHealthController.validate(
                from, from.plusSeconds(1), 1, from, null))
                .hasMessage("outbox_health_cursor_invalid");
    }

    @Test
    void onlyFirstPageQueriesStatusAggregate() {
        UsageEconomicsOutboxHealthService service = mock(UsageEconomicsOutboxHealthService.class);
        when(service.status()).thenReturn(reactor.core.publisher.Mono.just(
                new UsageEconomicsOutboxHealthService.Status(1, 2, 3, 4, null)));
        when(service.events(any(), any(), any(), any(), anyInt()))
                .thenReturn(reactor.core.publisher.Flux.empty());
        UsageEconomicsOutboxHealthController controller =
                new UsageEconomicsOutboxHealthController(service);
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = from.plusSeconds(60);

        controller.health(from, to, 1, null, null, true).block();
        controller.health(from, to, 1, null, null, false).block();

        verify(service).status();
        verify(service, times(2)).events(any(), any(), any(), any(), anyInt());
    }
}
