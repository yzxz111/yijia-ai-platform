package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ExpertPresentationCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.AdminExpertPresentation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class SkillExpertProfileCatalogEventsTest {

    @Test
    void savePublishesOnlyAfterTheProfileOperationCompletes() throws Exception {
        SkillExpertProfileService profiles = mock(SkillExpertProfileService.class);
        SkillCatalogEventService events = new SkillCatalogEventService();
        SkillExpertProfileAdminController controller =
                new SkillExpertProfileAdminController(profiles, events);
        AtomicInteger changes = new AtomicInteger();
        events.changes().subscribe(ignored -> changes.incrementAndGet());
        UUID id = UUID.randomUUID();
        ExpertPresentationCommand command = command();
        Sinks.One<AdminExpertPresentation> completion = Sinks.one();
        when(profiles.save(id, command, null)).thenReturn(completion.asMono());

        CompletableFuture<AdminExpertPresentation> result =
                controller.put(id, command, null).toFuture();
        assertThat(changes.get()).isZero();

        AdminExpertPresentation saved = new AdminExpertPresentation(id, "expert", "EXPERT", null);
        assertThat(completion.tryEmitValue(saved)).isEqualTo(Sinks.EmitResult.OK);
        assertThat(result.get(1, TimeUnit.SECONDS)).isSameAs(saved);
        assertThat(changes.get()).isEqualTo(1);
    }

    @Test
    void deletePublishesOnlyAfterTheProfileOperationCompletes() throws Exception {
        SkillExpertProfileService profiles = mock(SkillExpertProfileService.class);
        SkillCatalogEventService events = new SkillCatalogEventService();
        SkillExpertProfileAdminController controller =
                new SkillExpertProfileAdminController(profiles, events);
        AtomicInteger changes = new AtomicInteger();
        events.changes().subscribe(ignored -> changes.incrementAndGet());
        UUID id = UUID.randomUUID();
        Sinks.One<Void> completion = Sinks.one();
        when(profiles.delete(id, 1, null)).thenReturn(completion.asMono());

        CompletableFuture<Void> result = controller.delete(id, 1, null).toFuture();
        assertThat(changes.get()).isZero();

        assertThat(completion.tryEmitEmpty()).isEqualTo(Sinks.EmitResult.OK);
        assertThat(result.get(1, TimeUnit.SECONDS)).isNull();
        assertThat(changes.get()).isEqualTo(1);
    }

    @Test
    void failedWritesAndReadsDoNotPublish() {
        SkillExpertProfileService profiles = mock(SkillExpertProfileService.class);
        SkillCatalogEventService events = new SkillCatalogEventService();
        SkillExpertProfileAdminController controller =
                new SkillExpertProfileAdminController(profiles, events);
        AtomicInteger changes = new AtomicInteger();
        events.changes().subscribe(ignored -> changes.incrementAndGet());
        UUID id = UUID.randomUUID();
        ExpertPresentationCommand command = command();
        ResponseStatusException conflict = new ResponseStatusException(
                HttpStatus.CONFLICT, "skill_expert_profile_concurrent_modification");
        when(profiles.save(id, command, null)).thenReturn(Mono.error(conflict));
        when(profiles.delete(id, 1, null)).thenReturn(Mono.error(conflict));
        when(profiles.admin(id)).thenReturn(Mono.just(
                new AdminExpertPresentation(id, "expert", "EXPERT", null)));

        assertThatThrownBy(() -> controller.put(id, command, null).block()).isSameAs(conflict);
        assertThatThrownBy(() -> controller.delete(id, 1, null).block()).isSameAs(conflict);
        assertThat(controller.get(id).block()).isNotNull();
        assertThat(changes.get()).isZero();
    }

    private static ExpertPresentationCommand command() {
        return new ExpertPresentationCommand(
                null, null, null, null, null, null, null, null, 1);
    }
}
