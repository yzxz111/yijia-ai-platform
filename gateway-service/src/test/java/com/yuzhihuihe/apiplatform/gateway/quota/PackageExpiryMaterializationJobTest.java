package com.yuzhihuihe.apiplatform.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link PackageExpiryMaterializationJob} 的契约：只处理「已到期但状态仍是 ACTIVE」的实例，
 * 每条都走一次 {@code activateNext}（关旧 + 推进队列），单条失败不拖垮整轮。
 */
class PackageExpiryMaterializationJobTest {

    private static final UUID USER = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID EXPIRED = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_EXPIRED = UUID.fromString("40000000-0000-4000-8000-000000000002");
    private static final UUID ACTIVATED = UUID.fromString("50000000-0000-4000-8000-000000000001");

    private final PackageActivationRepository repository = mock(PackageActivationRepository.class);
    private final PackageActivationService activationService = mock(PackageActivationService.class);

    private PackageExpiryMaterializationJob job(int batchSize) {
        return new PackageExpiryMaterializationJob(repository, activationService, batchSize);
    }

    private static PackageActivationRepository.ExpiredPackageRow expired(
            UUID packageInstanceId, UUID userId, String usageDimension) {
        return new PackageActivationRepository.ExpiredPackageRow(
                packageInstanceId, userId, usageDimension);
    }

    private static PackageActivationService.ActivationResult activated(UUID packageInstanceId) {
        return new PackageActivationService.ActivationResult(packageInstanceId, "ACTIVE");
    }

    @Test
    void doesNothingWhenNothingHasExpired() {
        when(repository.expiredActivePackages(100)).thenReturn(Flux.empty());

        assertThat(job(100).materializeOnce().block()).isZero();
        verifyNoInteractions(activationService);
    }

    /**
     * 两条实例分属不同维度，必须各按自己的维度推进队列：一个文本实例终结时去激活排队的
     * 视频实例，会让用户拿到的额度单位与他的 key 完全不符。
     */
    @Test
    void advancesEachExpiredInstanceOnItsOwnDimension() {
        when(repository.expiredActivePackages(100)).thenReturn(Flux.just(
                expired(EXPIRED, USER, "TOKEN"),
                expired(OTHER_EXPIRED, OTHER_USER, "VIDEO_SECOND")));
        when(activationService.activateNext(USER, "TOKEN"))
                .thenReturn(Mono.just(activated(ACTIVATED)));
        // 没有排队实例：关掉旧的就结束了，这不算失败。
        when(activationService.activateNext(OTHER_USER, "VIDEO_SECOND"))
                .thenReturn(Mono.empty());

        assertThat(job(100).materializeOnce().block()).isEqualTo(2);

        verify(activationService).activateNext(USER, "TOKEN");
        verify(activationService).activateNext(OTHER_USER, "VIDEO_SECOND");
    }

    /**
     * 单条失败只记 WARN、不中断本轮；失败的那条不计入返回值，它下一轮还会被选中
     * （状态没变），因此是自愈重试而不是丢弃。
     */
    @Test
    void keepsSweepingWhenOneInstanceFails() {
        when(repository.expiredActivePackages(100)).thenReturn(Flux.just(
                expired(EXPIRED, USER, "TOKEN"),
                expired(OTHER_EXPIRED, OTHER_USER, "VIDEO_SECOND")));
        when(activationService.activateNext(USER, "TOKEN"))
                .thenReturn(Mono.error(new IllegalStateException("database down")));
        when(activationService.activateNext(OTHER_USER, "VIDEO_SECOND"))
                .thenReturn(Mono.empty());

        assertThat(job(100).materializeOnce().block()).isEqualTo(1);

        verify(activationService).activateNext(OTHER_USER, "VIDEO_SECOND");
    }

    /**
     * 关不掉的那种：退款审批中的 {@code refund_hold} 会让 {@code closeTerminalActive}
     * 跳过这条实例，{@code activateNext} 于是返回**原来那一个**实例。
     * 这不是错误，也不能算"处理失败"，否则它会每分钟被报一次。
     */
    @Test
    void countsAnInstanceThatCouldNotBeClosedAndStayedActive() {
        when(repository.expiredActivePackages(100))
                .thenReturn(Flux.just(expired(EXPIRED, USER, "TOKEN")));
        when(activationService.activateNext(USER, "TOKEN"))
                .thenReturn(Mono.just(activated(EXPIRED)));

        assertThat(job(100).materializeOnce().block()).isEqualTo(1);
    }

    @Test
    void passesItsConfiguredBatchSizeToTheQuery() {
        when(repository.expiredActivePackages(7)).thenReturn(Flux.empty());

        job(7).materializeOnce().block();

        verify(repository).expiredActivePackages(7);
    }

    @Test
    void rejectsAnInvalidBatchSize() {
        assertThatThrownBy(() -> job(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_package_expiry_batch_size");
        assertThatThrownBy(() -> job(1001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_package_expiry_batch_size");
    }
}
