package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class VideoTerminalCompensationTest {

    private static final UUID TENANT_ID = UUID.fromString("9e000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("9e000000-0000-4000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("9e000000-0000-4000-8000-000000000003");
    private static final UUID API_KEY_ID = UUID.fromString("9e000000-0000-4000-8000-000000000004");
    private static final UUID PACKAGE_ID = UUID.fromString("9e000000-0000-4000-8000-000000000005");

    @Test
    void retriesSettlementAndWritesTheSameAccountingEvidence() {
        QuotaService quotaService = mock(QuotaService.class);
        VideoTaskRepository taskRepository = mock(VideoTaskRepository.class);
        VideoEconomicsEvidenceWriter economicsEvidence = mock(VideoEconomicsEvidenceWriter.class);
        VideoGenerationService service = service(
                quotaService, taskRepository, economicsEvidence, mock(VideoRouteRepository.class),
                mock(VideoAdapterRegistry.class));
        VideoTaskRepository.TaskRow task = task("req-terminal-settle", "SUCCEEDED", 7, null);
        QuotaService.TokenUsage usage = new QuotaService.TokenUsage(7, 0, 7);

        when(quotaService.settle(task.reservationId(), usage, QuotaService.UsageQuality.ACTUAL))
                .thenReturn(
                        Mono.<QuotaService.Settlement>error(new IllegalStateException("temporary_db_fault")),
                        Mono.just(new QuotaService.Settlement(
                                task.reservationId(), 7, 7, 0, "SETTLED", QuotaService.UsageQuality.ACTUAL)));
        when(economicsEvidence.write(task, "SUCCEEDED", 7)).thenReturn(Mono.empty());

        assertThatThrownBy(() -> service.compensateTerminal(task).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary_db_fault");
        service.compensateTerminal(task).block();

        verify(quotaService, times(2)).settle(
                task.reservationId(), usage, QuotaService.UsageQuality.ACTUAL);
        verify(economicsEvidence).write(task, "SUCCEEDED", 7);
    }

    @Test
    void retriesReleaseOnlyForAnExplicitProviderRejection() {
        QuotaService quotaService = mock(QuotaService.class);
        VideoTaskRepository.TaskRow taskRepositoryRow = task(
                "req-terminal-release", "CANCELLED", 0, "upstream_rejected");
        VideoGenerationService service = service(
                quotaService, mock(VideoTaskRepository.class), mock(VideoEconomicsEvidenceWriter.class),
                mock(VideoRouteRepository.class), mock(VideoAdapterRegistry.class));

        when(quotaService.release(
                        taskRepositoryRow.reservationId(), QuotaService.ReleaseReason.UPSTREAM_FAILURE))
                .thenReturn(
                        Mono.<Void>error(new IllegalStateException("temporary_release_fault")),
                        Mono.empty());

        assertThatThrownBy(() -> service.compensateTerminal(taskRepositoryRow).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary_release_fault");
        service.compensateTerminal(taskRepositoryRow).block();

        verify(quotaService, times(2)).release(
                taskRepositoryRow.reservationId(), QuotaService.ReleaseReason.UPSTREAM_FAILURE);
    }

    @Test
    void aFourHundredReleaseFailureLeavesACompensableTerminalMarker() {
        QuotaService quotaService = mock(QuotaService.class);
        VideoTaskRepository repository = mock(VideoTaskRepository.class);
        VideoRouteRepository routes = mock(VideoRouteRepository.class);
        VideoAdapterRegistry adapters = mock(VideoAdapterRegistry.class);
        VideoGenerationAdapter adapter = mock(VideoGenerationAdapter.class);
        VideoGenerationAdapter.Channel channel = new VideoGenerationAdapter.Channel(
                UUID.randomUUID(), UUID.randomUUID(), "MOCK", "mock://video", null, "vendor-model");
        QuotaService.Reservation reservation = new QuotaService.Reservation(
                UUID.randomUUID(), "req-four-hundred", PACKAGE_ID, "video-basic", 5,
                "RESERVED", Instant.now().plusSeconds(600), "VIDEO_SECOND");
        VideoGenerationService.SubmitCommand command = new VideoGenerationService.SubmitCommand(
                "req-four-hundred", owner(), "video-basic", "a lighthouse", null, 5,
                "720P", null, false);
        VideoTaskRepository.TaskRow intent = new VideoTaskRepository.TaskRow(
                UUID.randomUUID(), command.requestId(), reservation.id(), TENANT_ID, USER_ID,
                API_KEY_ID, PACKAGE_ID, "video-basic", channel.providerId(), channel.id(),
                channel.upstreamModel(), "720P", 5, 5, null, null, "SUBMITTED", null, null,
                Instant.now(), null, Instant.now().plusSeconds(1800));

        when(repository.findByRequestId(command.requestId()))
                .thenReturn(Mono.empty(), Mono.just(intent));
        when(routes.resolutionTier("video-basic")).thenReturn(Mono.just("720P"));
        when(routes.candidates(any(), eq("video-basic"), any())).thenReturn(Flux.just(channel));
        when(adapters.supports("MOCK")).thenReturn(true);
        when(adapters.forProviderType("MOCK")).thenReturn(adapter);
        when(quotaService.reserve(any())).thenReturn(Mono.just(reservation));
        when(repository.insert(any())).thenReturn(Mono.just(1L));
        when(adapter.submit(any())).thenReturn(Mono.error(
                new ProviderAdapter.ProviderException(400, "provider_rejected_generation")));
        when(repository.completeTerminal(any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(1L));
        when(quotaService.release(
                        reservation.id(), QuotaService.ReleaseReason.UPSTREAM_FAILURE))
                .thenReturn(
                        Mono.<Void>error(new IllegalStateException("temporary_release_fault")),
                        Mono.empty());

        VideoGenerationService service = service(
                quotaService, repository, mock(VideoEconomicsEvidenceWriter.class), routes, adapters);
        assertThatThrownBy(() -> service.submit(command).block())
                .isInstanceOf(ProviderAdapter.ProviderException.class);

        VideoTaskRepository.TaskRow rejected = new VideoTaskRepository.TaskRow(
                intent.id(), intent.requestId(), intent.reservationId(), intent.tenantId(),
                intent.userId(), intent.apiKeyId(), intent.packageInstanceId(), intent.modelCode(),
                intent.providerId(), intent.channelId(), intent.upstreamModel(), intent.resolutionTier(),
                intent.requestedSeconds(), intent.reservedSeconds(), 0, intent.upstreamTaskId(),
                "CANCELLED", "upstream_rejected", null, intent.submittedAt(), Instant.now(),
                intent.expiresAt());
        service.compensateTerminal(rejected).block();

        verify(quotaService, times(2)).release(
                reservation.id(), QuotaService.ReleaseReason.UPSTREAM_FAILURE);
    }

    @Test
    void doesNotReleaseUnknownCancellationOrOpenTasks() {
        QuotaService quotaService = mock(QuotaService.class);
        VideoGenerationService service = service(
                quotaService, mock(VideoTaskRepository.class), mock(VideoEconomicsEvidenceWriter.class),
                mock(VideoRouteRepository.class), mock(VideoAdapterRegistry.class));

        service.compensateTerminal(task(
                "req-unknown-cancel", "CANCELLED", 0, "gateway_task_expired")).block();
        service.compensateTerminal(task(
                "req-open", "RUNNING", null, null)).block();

        verify(quotaService, never()).release(any(), any());
        verify(quotaService, never()).settle(any(), any(), any());
    }

    @Test
    void statusDoesNotExposeASettledTaskWhileItsReservationIsStillHeld() {
        QuotaService quotaService = mock(QuotaService.class);
        VideoTaskRepository repository = mock(VideoTaskRepository.class);
        VideoEconomicsEvidenceWriter economicsEvidence = mock(VideoEconomicsEvidenceWriter.class);
        VideoGenerationService service = service(
                quotaService, repository, economicsEvidence, mock(VideoRouteRepository.class),
                mock(VideoAdapterRegistry.class));
        VideoTaskRepository.TaskRow task = task("req-status-compensation", "SUCCEEDED", 4, null);
        QuotaService.TokenUsage usage = new QuotaService.TokenUsage(4, 0, 4);
        when(repository.findByRequestId(task.requestId())).thenReturn(Mono.just(task));
        when(repository.reservationIsReserved(task.reservationId())).thenReturn(Mono.just(true));
        when(quotaService.settle(task.reservationId(), usage, QuotaService.UsageQuality.ACTUAL))
                .thenReturn(Mono.<QuotaService.Settlement>error(
                        new IllegalStateException("temporary_settle_fault")),
                        Mono.just(new QuotaService.Settlement(
                                task.reservationId(), 4, 4, 0, "SETTLED",
                                QuotaService.UsageQuality.ACTUAL)));
        when(economicsEvidence.write(task, "SUCCEEDED", 4)).thenReturn(Mono.empty());

        assertThatThrownBy(() -> service.status(owner(), task.requestId()).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary_settle_fault");
        assertThat(service.status(owner(), task.requestId()).block().status())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void contentDoesNotExposeAnUnchargedVendorUrl() {
        QuotaService quotaService = mock(QuotaService.class);
        VideoTaskRepository repository = mock(VideoTaskRepository.class);
        VideoGenerationService service = service(
                quotaService, repository, mock(VideoEconomicsEvidenceWriter.class),
                mock(VideoRouteRepository.class), mock(VideoAdapterRegistry.class));
        VideoTaskRepository.TaskRow task = taskWithOutput("req-content-compensation", 4);
        when(repository.findByRequestId(task.requestId())).thenReturn(Mono.just(task));
        when(repository.reservationIsReserved(task.reservationId())).thenReturn(Mono.just(true));
        when(quotaService.settle(any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("temporary_settle_fault")));

        assertThatThrownBy(() -> service.contentUrl(owner(), task.requestId()).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary_settle_fault");
    }

    @Test
    void recoverySweepIncludesTerminalCompensationCandidates() {
        VideoGenerationService service = mock(VideoGenerationService.class);
        VideoTaskRepository.TaskRow task = task("req-sweep", "SUCCEEDED", 3, null);
        when(service.openTasks(25)).thenReturn(Flux.empty());
        when(service.terminalTasksWithReservedQuota(25)).thenReturn(Flux.just(task));
        when(service.compensateTerminal(task)).thenReturn(Mono.empty());

        new VideoTaskRecoveryJob(service).recoverExpiredTasks();

        verify(service).compensateTerminal(task);
    }

    @Test
    void taskOwnershipIncludesTenantBoundary() {
        VideoTaskRepository repository = mock(VideoTaskRepository.class);
        VideoGenerationService service = service(
                mock(QuotaService.class), repository, mock(VideoEconomicsEvidenceWriter.class),
                mock(VideoRouteRepository.class), mock(VideoAdapterRegistry.class));
        VideoTaskRepository.TaskRow task = task("req-cross-tenant", "SUCCEEDED", 1, null);
        when(repository.findByRequestId(task.requestId())).thenReturn(Mono.just(task));
        ApiKeyService.ApiKeyContext otherTenant = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, OTHER_TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-basic"));

        assertThatThrownBy(() -> service.status(otherTenant, task.requestId()).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.getStatusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND));

        VideoGenerationService.SubmitCommand collidingSubmit =
                new VideoGenerationService.SubmitCommand(
                        task.requestId(), otherTenant, "video-basic", "a lighthouse", null,
                        5, "720P", null, false);
        assertThatThrownBy(() -> service.submit(collidingSubmit).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.getStatusCode())
                                .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void unsupportedSubmitChannelIsSkippedBeforeReservation() {
        QuotaService quotaService = mock(QuotaService.class);
        VideoTaskRepository taskRepository = mock(VideoTaskRepository.class);
        VideoRouteRepository routeRepository = mock(VideoRouteRepository.class);
        VideoAdapterRegistry adapters = mock(VideoAdapterRegistry.class);
        VideoGenerationService service = service(
                quotaService, taskRepository, mock(VideoEconomicsEvidenceWriter.class),
                routeRepository, adapters);
        VideoGenerationAdapter.Channel unknown = new VideoGenerationAdapter.Channel(
                UUID.randomUUID(), UUID.randomUUID(), "UNKNOWN", "mock://unknown", null, "model");
        when(taskRepository.findByRequestId("req-unsupported")).thenReturn(Mono.empty());
        when(routeRepository.resolutionTier("video-basic")).thenReturn(Mono.just("720P"));
        when(routeRepository.candidates(any(), eq("video-basic"), any())).thenReturn(Flux.just(unknown));
        when(adapters.supports("UNKNOWN")).thenReturn(false);

        assertThatThrownBy(() -> service.submit(new VideoGenerationService.SubmitCommand(
                        "req-unsupported",
                        new ApiKeyService.ApiKeyContext(
                                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-basic")),
                        "video-basic", "a lighthouse", null, 5, "720P", null, false)).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(quotaService, never()).reserve(any());
    }

    /**
     * 上游始终给不出结论时，恢复必须**收尾**，而不是像以前那样无限期保持预留。
     *
     * <p>这是线上事件的回归测试：一个真实用户的 5 条视频预留停在 {@code RESERVED}
     * 长达 17 天，合计 50 秒，恰好等于他视频套餐全部的剩余额度（190 总量、
     * 140 已用）——他在那段时间里一秒视频都生成不出来。
     *
     * <p>「无法证明厂商没有跑过」解释了为什么不能在**第一时间**释放；
     * 但它不是无限期持有的理由。任务已经过了 TTL，最后一次轮询之后仍然未知，
     * 就按「已经不可能有结果」收尾。
     */
    @Test
    void recoveryReleasesWhenTheUpstreamOutcomeStaysUnknown() {
        QuotaService quotaService = mock(QuotaService.class);
        VideoTaskRepository repository = mock(VideoTaskRepository.class);
        VideoRouteRepository routeRepository = mock(VideoRouteRepository.class);
        VideoGenerationService service = service(
                quotaService, repository, mock(VideoEconomicsEvidenceWriter.class),
                routeRepository, mock(VideoAdapterRegistry.class));
        VideoTaskRepository.TaskRow task = task("req-unknown-outcome", "RUNNING", null, null);
        // 没有任何可用渠道 -> 轮询失败 -> 结果未知。
        when(routeRepository.candidates(any(), eq("video-basic"), any())).thenReturn(Flux.empty());
        when(repository.completeTerminal(task.requestId(), "CANCELLED", 0,
                "gateway_task_expired", null)).thenReturn(Mono.just(1L));
        when(quotaService.release(task.reservationId(),
                QuotaService.ReleaseReason.RECOVERY_TIMEOUT)).thenReturn(Mono.empty());

        service.recoverExpired(task).block();

        verify(repository).completeTerminal(task.requestId(), "CANCELLED", 0,
                "gateway_task_expired", null);
        verify(quotaService).release(task.reservationId(),
                QuotaService.ReleaseReason.RECOVERY_TIMEOUT);
    }

    private static VideoGenerationService service(
            QuotaService quotaService,
            VideoTaskRepository taskRepository,
            VideoEconomicsEvidenceWriter economicsEvidence,
            VideoRouteRepository routeRepository,
            VideoAdapterRegistry adapters) {
        return new VideoGenerationService(
                quotaService, taskRepository, routeRepository, adapters, economicsEvidence);
    }

    private static VideoTaskRepository.TaskRow task(
            String requestId, String status, Integer billedSeconds, String errorCategory) {
        return new VideoTaskRepository.TaskRow(
                UUID.randomUUID(), requestId, UUID.randomUUID(), TENANT_ID, USER_ID, API_KEY_ID,
                PACKAGE_ID, "video-basic", UUID.randomUUID(), UUID.randomUUID(), "vendor-model",
                "720P", 8, 8, billedSeconds, "upstream-1", status, errorCategory, null,
                Instant.now().minusSeconds(30),
                status.equals("RUNNING") ? null : Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(600));
    }

    private static VideoTaskRepository.TaskRow taskWithOutput(String requestId, int billedSeconds) {
        VideoTaskRepository.TaskRow task = task(requestId, "SUCCEEDED", billedSeconds, null);
        return new VideoTaskRepository.TaskRow(
                task.id(), task.requestId(), task.reservationId(), task.tenantId(), task.userId(),
                task.apiKeyId(), task.packageInstanceId(), task.modelCode(), task.providerId(),
                task.channelId(), task.upstreamModel(), task.resolutionTier(), task.requestedSeconds(),
                task.reservedSeconds(), task.billedSeconds(), task.upstreamTaskId(), task.status(),
                task.errorCategory(), "https://vendor/video", task.submittedAt(), task.terminalAt(),
                task.expiresAt());
    }

    private static ApiKeyService.ApiKeyContext owner() {
        return new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-basic"));
    }
}
