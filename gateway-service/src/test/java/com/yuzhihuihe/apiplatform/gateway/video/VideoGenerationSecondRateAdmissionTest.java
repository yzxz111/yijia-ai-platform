package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.limit.PackageSecondRateLimitService;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 视频路径与所售「每秒每分钟」速率相交之处。
 *
 * <p>两条性质：当某套餐的每分钟生成秒数已用完时，它会在预留任何配额或调用任何供应商之前
 * 拒绝该提交；而一次结算会把它实际计费的秒数回馈进该速率——这是计数器唯一能据以构建的数字。
 */
class VideoGenerationSecondRateAdmissionTest {

    private static final UUID TENANT_ID = UUID.fromString("9b000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("9b000000-0000-4000-8000-000000000002");
    private static final UUID KEY_ID = UUID.fromString("9b000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE_ID = UUID.fromString("9b000000-0000-4000-8000-000000000004");

    private final QuotaService quotaService = mock(QuotaService.class);
    private final VideoTaskRepository taskRepository = mock(VideoTaskRepository.class);
    private final VideoRouteRepository routeRepository = mock(VideoRouteRepository.class);
    private final VideoAdapterRegistry adapterRegistry = mock(VideoAdapterRegistry.class);
    private final VideoEconomicsEvidenceWriter economicsEvidence =
            mock(VideoEconomicsEvidenceWriter.class);
    private final RecordingSecondRate secondRate = new RecordingSecondRate();

    @BeforeEach
    void theAliasSellsTheResolutionUnderTest() {
        // gateway V54：未注册分辨率档位的别名会失败即拒绝（fail-closed），而本类
        // 关注的是速率而非档位。此处的每个用例都对 video-basic 请求 720P，
        // 因此该别名必须声明它售卖 720P，准入才会被触达。
        when(routeRepository.resolutionTier("video-basic")).thenReturn(Mono.just("720P"));
    }

    @Test
    void refusesAGenerationThePackagesMinuteCannotPayForBeforeReservingOrCallingAVendor() {
        when(taskRepository.findByRequestId("req-over-rate")).thenReturn(Mono.empty());
        secondRate.admission = Mono.error(new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "seconds_per_minute_exceeded"));

        assertThatThrownBy(() -> service().submit(command("req-over-rate")).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getReason()).isEqualTo("seconds_per_minute_exceeded");
                });

        assertThat(secondRate.admittedPackages).containsExactly(PACKAGE_ID);
        verify(quotaService, never()).reserve(any());
        verify(routeRepository, never()).candidates(any(), anyString(), anyString());
    }

    @Test
    void admitsAgainstThePackageTheKeyBelongsTo() {
        when(taskRepository.findByRequestId("req-admitted")).thenReturn(Mono.empty());
        secondRate.admission = Mono.empty();
        // 准入之后的渠道查找不是本测试的主题；空的路由集合会触发服务自身的 503，
        // 这足以证明准入先运行并放行了该请求。
        when(routeRepository.candidates(any(), anyString(), anyString()))
                .thenReturn(Flux.empty());

        assertThatThrownBy(() -> service().submit(command("req-admitted")).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("video_channel_unavailable"));
        assertThat(secondRate.admittedPackages).containsExactly(PACKAGE_ID);
    }

    @Test
    void aSettledGenerationFeedsItsChargedSecondsBackIntoTheRate() {
        VideoTaskRepository.TaskRow task = task();
        when(taskRepository.completeTerminal(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(Mono.just(1L));
        when(quotaService.settle(any(), any(), any())).thenReturn(Mono.just(
                new QuotaService.Settlement(
                        task.reservationId(), 7L, 7L, 0L, "SETTLED",
                        QuotaService.UsageQuality.ACTUAL)));
        when(economicsEvidence.write(any(), anyString(), anyInt())).thenReturn(Mono.empty());
        when(taskRepository.findByRequestId(task.requestId())).thenReturn(Mono.just(task));

        service().apply(task, new VideoGenerationAdapter.TaskStatus(
                        VideoGenerationAdapter.TaskStatus.State.SUCCEEDED, 7, "https://vendor/video",
                        null))
                .block();

        assertThat(secondRate.recordedPackages).containsExactly(PACKAGE_ID);
        assertThat(secondRate.recordedSeconds).containsExactly(7L);
    }

    @Test
    void aGenerationTheVendorBilledNothingForRecordsNothing() {
        VideoTaskRepository.TaskRow task = task();
        when(taskRepository.completeTerminal(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(Mono.just(1L));
        when(quotaService.settle(any(), any(), any())).thenReturn(Mono.just(
                new QuotaService.Settlement(
                        task.reservationId(), 0L, 0L, 0L, "SETTLED",
                        QuotaService.UsageQuality.ACTUAL)));
        when(economicsEvidence.write(any(), anyString(), anyInt())).thenReturn(Mono.empty());
        when(taskRepository.findByRequestId(task.requestId())).thenReturn(Mono.just(task));

        service().apply(task, new VideoGenerationAdapter.TaskStatus(
                        VideoGenerationAdapter.TaskStatus.State.FAILED, 0, null, "vendor_failed"))
                .block();

        assertThat(secondRate.recordedSeconds).isEmpty();
    }

    @Test
    void anIdempotentReplayOfAnExistingTaskIsNotChargedAgainstTheRateAgain() {
        VideoTaskRepository.TaskRow task = task();
        when(taskRepository.findByRequestId(task.requestId())).thenReturn(Mono.just(task));

        service().submit(command(task.requestId())).block();

        assertThat(secondRate.admittedPackages)
                .as("returning the task already paid for must not consume the minute again")
                .isEmpty();
    }

    private VideoGenerationService service() {
        return new VideoGenerationService(quotaService, taskRepository, routeRepository,
                adapterRegistry, economicsEvidence, secondRate);
    }

    private static VideoGenerationService.SubmitCommand command(String requestId) {
        return new VideoGenerationService.SubmitCommand(
                requestId,
                new ApiKeyService.ApiKeyContext(
                        KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-basic")),
                "video-basic",
                "a cat on a skateboard",
                null,
                8,
                "720P",
                null,
                false);
    }

    private static VideoTaskRepository.TaskRow task() {
        return new VideoTaskRepository.TaskRow(
                UUID.randomUUID(),
                "req-settled",
                UUID.randomUUID(),
                TENANT_ID,
                USER_ID,
                KEY_ID,
                PACKAGE_ID,
                "video-basic",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "vendor-model",
                "720P",
                8,
                8,
                7,
                "upstream-1",
                "RUNNING",
                null,
                null,
                Instant.now().minusSeconds(30),
                null,
                Instant.now().plusSeconds(600));
    }

    private static final class RecordingSecondRate implements PackageSecondRateLimitService {

        final List<UUID> admittedPackages = new ArrayList<>();
        final List<UUID> recordedPackages = new ArrayList<>();
        final List<Long> recordedSeconds = new ArrayList<>();
        Mono<Void> admission = Mono.empty();

        @Override
        public Mono<Void> admit(UUID packageInstanceId, UUID userId) {
            admittedPackages.add(packageInstanceId);
            return admission;
        }

        @Override
        public void record(UUID packageInstanceId, long chargedSeconds) {
            if (chargedSeconds > 0) {
                recordedPackages.add(packageInstanceId);
                recordedSeconds.add(chargedSeconds);
            }
        }

        @Override
        public Mono<Long> used(
                com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService.PackageSecondLimit limit,
                UUID userId) {
            return Mono.just(0L);
        }
    }
}
