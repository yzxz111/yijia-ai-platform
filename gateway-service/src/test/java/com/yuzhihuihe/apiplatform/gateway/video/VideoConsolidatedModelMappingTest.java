package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * V110 合并模型（video-minimax / MiniMax）的映射语义：
 *
 * <ul>
 *   <li>提交 model=video-minimax + resolution=480P 时，网关把它解析成内部别名
 *       video-budget，档位校验、路由、计费全部按 video-budget 执行；720P 解析成
 *       video-minimax 自身。</li>
 *   <li>任务记录里存内部别名（轮询要沿同一别名重解析渠道），但任务视图向客户端
 *       回显它提交的合并模型名。</li>
 *   <li>{@link VideoGenerationService#CLIENT_MODEL_BY_INTERNAL_ALIAS} 必须与
 *       {@code gateway.video_client_tier_mapping} 的种子逐行对应——这里钉住它。</li>
 * </ul>
 */
class VideoConsolidatedModelMappingTest {

    private static final UUID TENANT_ID = UUID.fromString("9d000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("9d000000-0000-4000-8000-000000000002");
    private static final UUID KEY_ID = UUID.fromString("9d000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE_ID = UUID.fromString("9d000000-0000-4000-8000-000000000004");

    private final QuotaService quotaService = mock(QuotaService.class);
    private final VideoTaskRepository taskRepository = mock(VideoTaskRepository.class);
    private final VideoRouteRepository routeRepository = mock(VideoRouteRepository.class);
    private final VideoAdapterRegistry adapterRegistry = mock(VideoAdapterRegistry.class);
    private final VideoEconomicsEvidenceWriter economicsEvidence =
            mock(VideoEconomicsEvidenceWriter.class);

    @Test
    void mapsTheConsolidatedModelAndResolutionToTheInternalAlias() {
        when(taskRepository.findByRequestId("req-minimax-480")).thenReturn(Mono.empty());
        when(routeRepository.internalAliasFor(
                any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.just("video-budget"));
        when(routeRepository.resolutionTier("video-budget")).thenReturn(Mono.just("480P"));
        when(routeRepository.candidates(any(), anyString(), anyString()))
                .thenReturn(Flux.empty());

        assertThatThrownBy(() -> service()
                        .submit(command("req-minimax-480", "480P", "video-minimax"))
                        .block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("video_channel_unavailable"));

        // 档位校验与渠道查找针对的是内部别名，而不是客户端提交的合并别名。
        verify(routeRepository).resolutionTier("video-budget");
        verify(routeRepository).candidates(any(), eq("video-budget"), anyString());
    }

    @Test
    void sevenTwentyResolvesToTheConsolidatedAliasItself() {
        when(taskRepository.findByRequestId("req-minimax-720")).thenReturn(Mono.empty());
        when(routeRepository.internalAliasFor(
                any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.just("video-minimax"));
        when(routeRepository.resolutionTier("video-minimax")).thenReturn(Mono.just("720P"));
        when(routeRepository.candidates(any(), anyString(), anyString()))
                .thenReturn(Flux.empty());

        assertThatThrownBy(() -> service()
                        .submit(command("req-minimax-720", "720P", "video-minimax"))
                        .block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("video_channel_unavailable"));

        verify(routeRepository).resolutionTier("video-minimax");
    }

    @Test
    void theTaskViewReportsTheClientModelName() {
        VideoTaskRepository.TaskRow task = task("video-budget", "480P");
        assertThat(VideoGenerationService.TaskView.of(task).model())
                .isEqualTo("video-minimax");
        assertThat(VideoGenerationService.TaskView.of(task("video-minimax", "720P")).model())
                .isEqualTo("video-minimax");
        // 非合并别名保持原样（第三方兼容客户端按内部别名提交的任务不受影响）。
        assertThat(VideoGenerationService.TaskView.of(task("video-pro", "720P")).model())
                .isEqualTo("video-pro");
    }

    @Test
    void theDisplayMirrorMatchesTheMigrationSeed() {
        assertThat(VideoGenerationService.CLIENT_MODEL_BY_INTERNAL_ALIAS)
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        "video-budget", "video-minimax",
                        "video-minimax", "video-minimax"));
    }

    private VideoGenerationService service() {
        return new VideoGenerationService(quotaService, taskRepository, routeRepository,
                adapterRegistry, economicsEvidence);
    }

    private static VideoGenerationService.SubmitCommand command(
            String requestId, String resolution, String model) {
        return new VideoGenerationService.SubmitCommand(
                requestId,
                new ApiKeyService.ApiKeyContext(
                        KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-minimax")),
                model,
                "a lighthouse at dusk",
                null,
                5,
                resolution,
                "16:9",
                false);
    }

    private static VideoTaskRepository.TaskRow task(String modelCode, String tier) {
        return new VideoTaskRepository.TaskRow(
                UUID.randomUUID(),
                "req-" + modelCode,
                UUID.randomUUID(),
                TENANT_ID,
                USER_ID,
                KEY_ID,
                PACKAGE_ID,
                modelCode,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "vendor-model",
                tier,
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
