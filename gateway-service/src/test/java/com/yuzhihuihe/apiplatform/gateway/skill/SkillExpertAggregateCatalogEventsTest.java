package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ExpertAggregateCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertAggregateResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class SkillExpertAggregateCatalogEventsTest {

    @Test
    void createPublishesOnlyAfterTheAggregateOperationCompletes() throws Exception {
        SkillExpertAggregateService aggregates = mock(SkillExpertAggregateService.class);
        SkillCatalogEventService events = new SkillCatalogEventService();
        SkillExpertAggregateController controller =
                new SkillExpertAggregateController(aggregates, new ObjectMapper(), events);
        AtomicInteger changes = new AtomicInteger();
        events.changes().subscribe(ignored -> changes.incrementAndGet());
        Sinks.One<ExpertAggregateResponse> completion = Sinks.one();
        when(aggregates.create(any(ExpertAggregateCommand.class), isNull(), isNull(),
                isNull(), isNull())).thenReturn(completion.asMono());

        CompletableFuture<ExpertAggregateResponse> result =
                controller.create("{}", null, null, null, null, null, null, null).toFuture();
        assertThat(changes.get()).isZero();

        ExpertAggregateResponse saved = new ExpertAggregateResponse(UUID.randomUUID(), null, null);
        assertThat(completion.tryEmitValue(saved)).isEqualTo(Sinks.EmitResult.OK);
        assertThat(result.get(1, TimeUnit.SECONDS)).isSameAs(saved);
        assertThat(changes.get()).isEqualTo(1);
    }

    @Test
    void updatePublishesOnlyAfterTheAggregateOperationCompletes() throws Exception {
        SkillExpertAggregateService aggregates = mock(SkillExpertAggregateService.class);
        SkillCatalogEventService events = new SkillCatalogEventService();
        SkillExpertAggregateController controller =
                new SkillExpertAggregateController(aggregates, new ObjectMapper(), events);
        AtomicInteger changes = new AtomicInteger();
        events.changes().subscribe(ignored -> changes.incrementAndGet());
        Sinks.One<ExpertAggregateResponse> completion = Sinks.one();
        UUID id = UUID.randomUUID();
        when(aggregates.update(eq(id), any(ExpertAggregateCommand.class), isNull(),
                isNull(), isNull(), isNull())).thenReturn(completion.asMono());

        CompletableFuture<ExpertAggregateResponse> result =
                controller.update(id, "{}", null, null, null, null, null, null, null).toFuture();
        assertThat(changes.get()).isZero();

        ExpertAggregateResponse saved = new ExpertAggregateResponse(id, null, null);
        assertThat(completion.tryEmitValue(saved)).isEqualTo(Sinks.EmitResult.OK);
        assertThat(result.get(1, TimeUnit.SECONDS)).isSameAs(saved);
        assertThat(changes.get()).isEqualTo(1);
    }

    @Test
    void failedAggregateOperationsDoNotPublish() {
        SkillExpertAggregateService aggregates = mock(SkillExpertAggregateService.class);
        SkillCatalogEventService events = new SkillCatalogEventService();
        SkillExpertAggregateController controller =
                new SkillExpertAggregateController(aggregates, new ObjectMapper(), events);
        AtomicInteger changes = new AtomicInteger();
        events.changes().subscribe(ignored -> changes.incrementAndGet());
        IllegalStateException failure = new IllegalStateException("write failed");
        UUID id = UUID.randomUUID();
        when(aggregates.create(any(ExpertAggregateCommand.class), isNull(), isNull(),
                isNull(), isNull())).thenReturn(Mono.error(failure));
        when(aggregates.update(eq(id), any(ExpertAggregateCommand.class), isNull(),
                isNull(), isNull(), isNull())).thenReturn(Mono.error(failure));

        assertThatThrownBy(() -> controller.create(
                "{}", null, null, null, null, null, null, null).block()).isSameAs(failure);
        assertThatThrownBy(() -> controller.update(
                id, "{}", null, null, null, null, null, null, null).block())
                .isSameAs(failure);
        assertThat(changes.get()).isZero();
    }
}
