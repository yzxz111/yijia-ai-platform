package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 当任务持久化结果不确定时，一个已被上游接受的任务不得丢失它的预留。 */
class VideoPersistenceFailurePolicyTest {

    private static final UUID TENANT_ID = UUID.fromString("9d000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("9d000000-0000-4000-8000-000000000002");
    private static final UUID API_KEY_ID = UUID.fromString("9d000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE_ID = UUID.fromString("9d000000-0000-4000-8000-000000000004");

    @Test
    void acceptedSubmitWithUnknownTaskAttachOutcomeKeepsTheReservationAndCannotResubmit() {
        QuotaService quotaService = mock(QuotaService.class);
        VideoTaskRepository taskRepository = mock(VideoTaskRepository.class);
        VideoRouteRepository routeRepository = mock(VideoRouteRepository.class);
        VideoAdapterRegistry adapterRegistry = mock(VideoAdapterRegistry.class);
        VideoGenerationAdapter adapter = mock(VideoGenerationAdapter.class);

        VideoGenerationAdapter.Channel channel = new VideoGenerationAdapter.Channel(
                UUID.randomUUID(), UUID.randomUUID(), "MOCK", "mock://video", null, "mock-model");
        QuotaService.Reservation reservation = new QuotaService.Reservation(
                UUID.randomUUID(), "req-persist-unknown", PACKAGE_ID, "video-basic", 5,
                "RESERVED", Instant.now().plusSeconds(35 * 60L), "VIDEO_SECOND");
        VideoGenerationService.SubmitCommand command = new VideoGenerationService.SubmitCommand(
                "req-persist-unknown",
                new ApiKeyService.ApiKeyContext(
                        API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-basic")),
                "video-basic", "a lighthouse at dusk", null, 5, "720P", null, false);
        VideoTaskRepository.TaskRow intent = new VideoTaskRepository.TaskRow(
                UUID.randomUUID(), command.requestId(), reservation.id(), TENANT_ID, USER_ID,
                API_KEY_ID, PACKAGE_ID, command.modelAlias(), channel.providerId(), channel.id(),
                channel.upstreamModel(), command.resolutionTier(), command.durationSeconds(),
                command.durationSeconds(), null, null, "SUBMITTED", null, null,
                Instant.now(), null, Instant.now().plusSeconds(30 * 60L));

        when(taskRepository.findByRequestId(command.requestId()))
                .thenReturn(Mono.empty(), Mono.just(intent), Mono.just(intent));
        when(routeRepository.resolutionTier(command.modelAlias())).thenReturn(Mono.just("720P"));
        when(routeRepository.candidates(
                any(ApiKeyService.ApiKeyContext.class), anyString(), anyString()))
                .thenReturn(Flux.just(channel));
        when(adapterRegistry.supports("MOCK")).thenReturn(true);
        when(adapterRegistry.forProviderType("MOCK")).thenReturn(adapter);
        when(quotaService.reserve(any())).thenReturn(Mono.just(reservation));
        // 厂商已经返回了 task id，但 INSERT 的结果未知。
        when(adapter.submit(any())).thenReturn(Mono.just(new VideoGenerationAdapter.SubmittedTask(
                "accepted-upstream-task")));
        when(taskRepository.insert(any())).thenReturn(Mono.just(1L));
        when(taskRepository.attachUpstreamTaskId(
                command.requestId(), "accepted-upstream-task"))
                .thenReturn(Mono.error(new IllegalStateException("task_attach_timeout")));

        VideoGenerationService service = new VideoGenerationService(
                quotaService, taskRepository, routeRepository, adapterRegistry,
                mock(VideoEconomicsEvidenceWriter.class));

        assertThatThrownBy(() -> service.submit(command).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task_attach_timeout");

        // 意图行是持久化的幂等锚点。重试会返回它，并且必须
        // 绝不第二次调用一个不暴露幂等键的供应商。
        service.submit(command).block();

        verify(adapter, times(1)).submit(any());
        verify(quotaService, never()).release(any(), any());
    }
}
