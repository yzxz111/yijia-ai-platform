package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 运维人员可以为视频别名的按秒计费保存什么。
 *
 * <p>正是这些数据行让视频别名从根本上变得可售：它售卖的分辨率（在请求时由
 * {@code VideoGenerationService} 强制执行），以及套餐发布守卫据以定价的按秒成本与零售价。
 * 这里固定下来的规则正是控制台字段可能填错的那些 —— 产品并不售卖的分辨率、低于 30% 下限的
 * 零售价，或者本身就不支持 VIDEO 的别名。
 */
class VideoAliasBillingServiceTest {

    private final VideoAliasBillingRepository repository = mock(VideoAliasBillingRepository.class);
    private final TransactionalOperator transactions = mock(TransactionalOperator.class);

    private VideoAliasBillingService service;

    @BeforeEach
    void setUp() {
        service = new VideoAliasBillingService(repository, transactions);
        // 这些用例关注的不是事务包装；让工作直接透传，这样拒绝就能以它本来的错误形式
        // 被观察到。
        when(transactions.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void theRetailPriceFloorIsTheProductsThirtyPercentMarginRoundedUp() {
        // ceil(cost / (1 - 0.30)) —— 与控制台展示给运维人员的数字相同，这样表单与服务端
        // 就不会对什么可接受产生分歧。
        assertThat(VideoAliasBillingService.requiredUnitPrice(35)).isEqualTo(50);
        assertThat(VideoAliasBillingService.requiredUnitPrice(69)).isEqualTo(99);
        assertThat(VideoAliasBillingService.requiredUnitPrice(115)).isEqualTo(165);
    }

    @Test
    void refusesAResolutionTheProductDoesNotSell() {
        assertThatThrownBy(() -> service.save("video-budget", command("4K", 35, 50)).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("invalid_video_resolution_tier");
                });
        verify(repository, never()).insertBilling(
                anyString(), anyString(), anyInt(), anyInt(), any(), any(), anyInt(), anyString());
    }

    @Test
    void refusesARetailPriceBelowTheMarginFloor() {
        // 成本 35 分/秒对应 69 分/秒，低于下限的 50：发布该价格会让 480P 入门档亏本
        // 出售，而这正是下限存在的意义。
        assertThatThrownBy(() -> service.save("video-budget", command("480P", 35, 49)).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("video_price_below_margin_floor"));
    }

    @Test
    void refusesARetailPriceThatClearsTheRatioButNotTheAbsoluteFloor() {
        // 成本 20 分/秒对应 29，恰好是 30% 下限（ceil(20 / 0.7)）—— 因此比率规则得到满足，
        // 而每秒的贡献只有 9 分，低于设计为视频设定的每单位 0.10 元下限。两条规则都必须
        // 成立。
        assertThatThrownBy(() -> service.save("video-budget", command("480P", 20, 29)).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("video_price_below_absolute_margin"));
    }

    @Test
    void refusesAnAliasThatIsNotVideoCapable() {
        when(repository.videoAlias("text-basic")).thenReturn(Mono.empty());

        assertThatThrownBy(() -> service.save("text-basic", command("480P", 35, 50)).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("video_alias_not_found"));
    }

    @Test
    void supersedesTheRateCardAndRegistersTheTier() {
        when(repository.videoAlias("video-budget")).thenReturn(Mono.just("video-budget"));
        when(repository.closeCurrentBilling("video-budget")).thenReturn(Mono.just(1L));
        when(repository.insertBilling(
                anyString(), anyString(), anyInt(), anyInt(), any(), any(), anyInt(), anyString()))
                .thenReturn(Mono.just(1L));
        when(repository.registerTier("video-budget", "480P")).thenReturn(Mono.just(1L));
        when(repository.one("video-budget")).thenReturn(Mono.just(
                new GatewayAdminResponses.VideoAliasBillingView(
                        "video-budget", "480P", 35, 50,
                        new BigDecimal("0.15000"), new BigDecimal("0.30000"), 10, "console-1")));

        GatewayAdminResponses.VideoAliasBillingView saved =
                service.save("video-budget", command("480P", 35, 50)).block();

        assertThat(saved).isNotNull();
        assertThat(saved.resolutionTier()).isEqualTo("480P");
        // 当前行在写入替代行之前先被关闭：已经按旧价格版本运行中的结算仍然必须能够解析
        // （V46/V47/V50 的规则）。
        verify(repository).closeCurrentBilling("video-budget");
        verify(repository).registerTier("video-budget", "480P");
    }

    private static GatewayAdminRequests.SaveVideoAliasBillingCommand command(
            String tier, int unitCost, int unitPrice) {
        return new GatewayAdminRequests.SaveVideoAliasBillingCommand(tier, unitCost, unitPrice, null);
    }
}
