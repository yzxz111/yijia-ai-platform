package com.yuzhihuihe.apiplatform.gateway.economics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

import reactor.core.publisher.Flux;

class UsageEconomicsBackfillControllerTest {

    private static final Instant CREATED = Instant.parse("2026-08-08T08:00:00Z");

    @Test
    void productionConstructorsDependOnApplicationServices() throws Exception {
        assertThat(UsageEconomicsBackfillController.class
                .getConstructor(UsageEconomicsBackfillService.class)).isNotNull();
        assertThat(UsageEconomicsOutboxHealthController.class
                .getConstructor(UsageEconomicsOutboxHealthService.class)).isNotNull();
    }

    @Test
    void endpointIsInternalBoundedAndUsesStableTupleCursorWithoutSensitiveData() throws Exception {
        var method = UsageEconomicsBackfillController.class.getDeclaredMethod(
                "backfill", UsageEconomicsBackfillController.Request.class);

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly(UsageEconomicsBackfillController.PATH);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('INTERNAL')");
        assertThat(UsageEconomicsBackfillRepository.PAGE_SQL)
                .contains("(usage.created_at, usage.id) >", "LIMIT :fetch_limit")
                .doesNotContain("prompt", "response", "attachment", "api_key_hash", "credentials");
        assertThatThrownBy(() -> UsageEconomicsBackfillController.validate(501, null, null))
                .hasMessage("usage_economics_backfill_limit_invalid");
        assertThatThrownBy(() -> UsageEconomicsBackfillController.validate(10, CREATED, null))
                .hasMessage("usage_economics_backfill_cursor_invalid");
    }

    @Test
    void returnsAtMostRequestedRowsAndSanitizesLegacyUnsplittableCost() {
        UUID first = UUID.fromString("97000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("97000000-0000-4000-8000-000000000002");
        UsageEconomicsEvent legacy = new UsageEconomicsEvent(
                UsageEconomicsEvent.VERSION, first, "req-1", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, "text-basic", "qwen-turbo", UUID.randomUUID(),
                UUID.randomUUID(), 8, 2, 10, "WALLET_ONLY", 0, 5, null, "CNY", null,
                null, null, 9L, null, null, null, null, true, true, List.of());
        UsageEconomicsBackfillService service = mock(UsageEconomicsBackfillService.class);
        when(service.snapshots(Instant.parse("1970-01-01T00:00:00Z"), new UUID(0, 0), 2))
                .thenReturn(Flux.just(
                        new UsageEconomicsBackfillService.Snapshot(legacy, CREATED),
                        new UsageEconomicsBackfillService.Snapshot(legacy, CREATED.plusSeconds(1))));
        var controller = new UsageEconomicsBackfillController(service);

        var page = controller.backfill(new UsageEconomicsBackfillController.Request(
                1, null, null)).block();

        assertThat(page.events()).hasSize(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCreatedAt()).isEqualTo(CREATED);
        assertThat(page.nextUsageId()).isEqualTo(first);
        UsageEconomicsEvent event = page.events().getFirst();
        assertThat(event.priceMissing()).isTrue();
        assertThat(event.providerInputCostAmount()).isNull();
        assertThat(event.providerOutputCostAmount()).isNull();
        assertThat(event.providerTotalCostAmount()).isNull();
        verify(service).snapshots(
                Instant.parse("1970-01-01T00:00:00Z"), new UUID(0, 0), 2);
    }
}
