package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 别名所售卖的分辨率是被强制执行的，而不是只写在文档里。
 *
 * <p>视频别名按分辨率定价——{@code video-budget} 作为 480P 别名每秒 35 分，在 1080P 下每秒
 * 115 分——而 480P 入门套餐（平台 V66，59 分/秒）只有在套餐不能被花在更贵的渲染上时，才能
 * 挣到其 40.7% 的最坏情况利润率。这三个用例钉住了网关 V54 中的守卫：别名不售卖的分辨率，
 * 以及没有登记档位的别名，都会在任何配额被持有、任何供应商被调用之前被拒绝；而别名确实
 * 售卖的那个分辨率则被放行到渠道查找。
 */
class VideoResolutionGuardTest {

    private static final UUID TENANT_ID = UUID.fromString("9c000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("9c000000-0000-4000-8000-000000000002");
    private static final UUID KEY_ID = UUID.fromString("9c000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE_ID = UUID.fromString("9c000000-0000-4000-8000-000000000004");

    private final QuotaService quotaService = mock(QuotaService.class);
    private final VideoTaskRepository taskRepository = mock(VideoTaskRepository.class);
    private final VideoRouteRepository routeRepository = mock(VideoRouteRepository.class);
    private final VideoAdapterRegistry adapterRegistry = mock(VideoAdapterRegistry.class);
    private final VideoEconomicsEvidenceWriter economicsEvidence =
            mock(VideoEconomicsEvidenceWriter.class);

    @Test
    void refusesAResolutionTheAliasDoesNotSellBeforeReservingOrCallingAVendor() {
        when(taskRepository.findByRequestId("req-wrong-tier")).thenReturn(Mono.empty());
        when(routeRepository.resolutionTier("video-budget")).thenReturn(Mono.just("480P"));
        when(routeRepository.internalAliasFor(any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.empty());

        assertThatThrownBy(() -> service().submit(command("req-wrong-tier", "1080P")).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("video_resolution_not_sold_by_model");
                });

        // 提前拒绝的全部意义所在：不持有任何秒数，不产生任何供应商开销。
        verify(quotaService, never()).reserve(any());
        verify(routeRepository, never()).candidates(any(), anyString(), anyString());
    }

    @Test
    void refusesAnAliasWithNoRegisteredResolutionTier() {
        when(taskRepository.findByRequestId("req-unregistered")).thenReturn(Mono.empty());
        // 没有打桩：该别名在 gateway.video_alias_tier 中没有对应行，因此没有任何东西能给它定价。
        when(routeRepository.resolutionTier("video-unpriced")).thenReturn(Mono.empty());
        when(routeRepository.internalAliasFor(any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.empty());

        assertThatThrownBy(() -> service().submit(command("req-unregistered", "720P")).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("video_model_resolution_unregistered");
                });

        verify(quotaService, never()).reserve(any());
    }

    @Test
    void admitsTheResolutionItsAliasSells() {
        when(taskRepository.findByRequestId("req-right-tier")).thenReturn(Mono.empty());
        when(routeRepository.resolutionTier("video-budget")).thenReturn(Mono.just("480P"));
        when(routeRepository.internalAliasFor(any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.empty());
        // 渠道查找不是本测试的主题；空的路由集合代表该服务自身的 503，
        // 这恰好证明守卫放行了该请求。
        when(routeRepository.candidates(any(), anyString(), anyString()))
                .thenReturn(Flux.empty());

        assertThatThrownBy(() -> service().submit(command("req-right-tier", "480P")).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("video_channel_unavailable"));
    }

    @Test
    void providerProfileValidationRunsBeforeReservingOrSubmitting() {
        VideoGenerationAdapter adapter = mock(VideoGenerationAdapter.class);
        VideoGenerationAdapter.Channel channel = new VideoGenerationAdapter.Channel(
                UUID.randomUUID(), UUID.randomUUID(), "BAILIAN",
                "https://workspace.cn-beijing.maas.aliyuncs.com", "opaque", "wan2.5-t2v-preview");
        ResponseStatusException invalidProfile = new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "bailian_video_duration_not_supported");

        when(taskRepository.findByRequestId("req-provider-profile")).thenReturn(Mono.empty());
        when(routeRepository.resolutionTier("video-budget")).thenReturn(Mono.just("480P"));
        when(routeRepository.internalAliasFor(any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.empty());
        when(routeRepository.candidates(any(), anyString(), anyString()))
                .thenReturn(Flux.just(channel));
        when(adapterRegistry.supports("BAILIAN")).thenReturn(true);
        when(adapterRegistry.forProviderType("BAILIAN")).thenReturn(adapter);
        doThrow(invalidProfile).when(adapter).validateSubmission(any());

        assertThatThrownBy(() -> service()
                        .submit(command("req-provider-profile", "480P"))
                        .block())
                .isSameAs(invalidProfile);

        verify(quotaService, never()).reserve(any());
        verify(adapter, never()).submit(any());
    }

    @Test
    void anIdempotentReplayIsNotReJudgedAgainstTheCurrentTier() {
        VideoTaskRepository.TaskRow task = existingTask();
        when(taskRepository.findByRequestId(task.requestId())).thenReturn(Mono.just(task));
        when(routeRepository.internalAliasFor(any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.empty());

        // 已经付过费的请求 id 会直接返回其任务，而不查询档位：
        // 事后发生变更的已登记档位绝不能把客户的任务搁置不管。
        assertThat(service().submit(command(task.requestId(), "1080P")).block()).isNotNull();
        verify(routeRepository, never()).resolutionTier(anyString());
    }

    @Test
    void refusesAnUnsupportedAspectRatioBeforeLookingUpATaskOrSpendingQuota() {
        assertThatThrownBy(() -> service()
                        .submit(command("req-wide", "720P", "21:9"))
                        .block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("invalid_video_request");
                });

        verify(taskRepository, never()).findByRequestId(anyString());
        verify(routeRepository, never()).resolutionTier(anyString());
        verify(routeRepository, never()).candidates(any(), anyString(), anyString());
        verify(quotaService, never()).reserve(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "16:9", "9:16", "1:1", "4:3", "3:4"})
    void admitsSupportedOrOmittedAspectRatios(String aspectRatio) {
        when(taskRepository.findByRequestId("req-ratio")).thenReturn(Mono.empty());
        when(routeRepository.resolutionTier("video-budget")).thenReturn(Mono.just("720P"));
        when(routeRepository.internalAliasFor(any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.empty());
        when(routeRepository.candidates(any(), anyString(), anyString()))
                .thenReturn(Flux.empty());

        assertThatThrownBy(() -> service()
                        .submit(command("req-ratio", "720P", aspectRatio))
                        .block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("video_channel_unavailable"));

        verify(routeRepository).candidates(any(), anyString(), anyString());
        verify(quotaService, never()).reserve(any());
    }

    private VideoGenerationService service() {
        return new VideoGenerationService(quotaService, taskRepository, routeRepository,
                adapterRegistry, economicsEvidence);
    }

    private static VideoGenerationService.SubmitCommand command(String requestId, String resolution) {
        return command(requestId, resolution, null);
    }

    private static VideoGenerationService.SubmitCommand command(
            String requestId, String resolution, String aspectRatio) {
        return new VideoGenerationService.SubmitCommand(
                requestId,
                new ApiKeyService.ApiKeyContext(
                        KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-budget")),
                "video-budget",
                "a lighthouse at dusk",
                null,
                5,
                resolution,
                aspectRatio,
                false);
    }

    private static VideoTaskRepository.TaskRow existingTask() {
        return new VideoTaskRepository.TaskRow(
                UUID.randomUUID(),
                "req-replayed",
                UUID.randomUUID(),
                TENANT_ID,
                USER_ID,
                KEY_ID,
                PACKAGE_ID,
                "video-budget",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "vendor-model",
                "480P",
                5,
                5,
                5,
                "upstream-1",
                "RUNNING",
                null,
                null,
                java.time.Instant.now().minusSeconds(10),
                null,
                java.time.Instant.now().plusSeconds(600));
    }
}
