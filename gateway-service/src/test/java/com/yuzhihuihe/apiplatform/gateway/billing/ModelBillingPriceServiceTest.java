package com.yuzhihuihe.apiplatform.gateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

class ModelBillingPriceServiceTest {

    private ModelBillingPriceRepository repository;
    private ModelBillingPriceService service;

    @BeforeEach
    void setUp() {
        repository = mock(ModelBillingPriceRepository.class);
        TransactionalOperator transactions = mock(TransactionalOperator.class);
        doAnswer(invocation -> invocation.getArgument(0))
                .when(transactions).transactional(any(Mono.class));
        service = new ModelBillingPriceService(repository, transactions);
    }

    @Test
    void publishesPositivePriceAfterClosingTheCurrentVersion() {
        Instant effectiveFrom = Instant.parse("2026-09-01T00:00:00Z");
        when(repository.lockModel("text-basic")).thenReturn(Mono.empty());
        when(repository.closeCurrent("text-basic", effectiveFrom)).thenReturn(Mono.empty());
        when(repository.insert(any())).thenReturn(Mono.empty());

        ModelBillingPriceCalculator.Price price = service.publish(
                new ModelBillingPriceService.PublishCommand(
                        "text-basic", 200, 400, 100, 200,
                        new BigDecimal("0.40"), "2026-09-v1", effectiveFrom,
                        UUID.randomUUID(), "official price page"))
                .block();

        assertThat(price).isEqualTo(new ModelBillingPriceCalculator.Price(
                "text-basic", 200, 400, "2026-09-v1"));
        InOrder order = inOrder(repository);
        order.verify(repository).lockModel("text-basic");
        order.verify(repository).closeCurrent("text-basic", effectiveFrom);
        order.verify(repository).insert(any());
    }

    @Test
    void rejectsZeroCustomerPriceBeforePersistence() {
        ModelBillingPriceService.PublishCommand command =
                new ModelBillingPriceService.PublishCommand(
                        "text-basic", 0, 400, 0, 200,
                        new BigDecimal("0.40"), "invalid", Instant.now(), null, null);

        assertThatThrownBy(() -> service.publish(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_model_billing_values");
        verify(repository, never()).insert(any());
    }

    @Test
    void publishesCategoryPricesWhenTheyClearTheirOwnProviderCost() {
        Instant effectiveFrom = Instant.parse("2026-09-01T00:00:00Z");
        when(repository.lockModel("text-basic")).thenReturn(Mono.empty());
        when(repository.closeCurrent("text-basic", effectiveFrom)).thenReturn(Mono.empty());
        when(repository.insert(any())).thenReturn(Mono.empty());

        // deepseek-flash 高峰成本：未命中 20 分/M，缓存读取 1 分/M，输出 80 分/M。
        // 零售价 30 / 120 / 8 使输出比价保持为成本的 1.5 倍，从而越过 validateMargin
        // 在分类检查运行之前所强制执行的那道 30% 下限。
        ModelBillingPriceCalculator.Price price = service.publish(
                new ModelBillingPriceService.PublishCommand(
                        "text-basic", 30, 120, 20, 80,
                        new BigDecimal("0.30"), "2026-09-v2", effectiveFrom,
                        UUID.randomUUID(), "official price page",
                        8L, 38L, 120L, 1L))
                .block();

        assertThat(price.cacheHitPricePerMillion()).isEqualTo(8L);
        assertThat(price.cacheWritePricePerMillion()).isEqualTo(38L);
        assertThat(price.reasoningPricePerMillion()).isEqualTo(120L);
    }

    @Test
    void rejectsACacheHitPriceBelowItsOwnCostNotTheMissCost() {
        // 缓存价 5 远低于未命中成本 40（若按下限以未命中成本为准就会要求达到该值），
        // 但本测试要说明的恰恰相反：它必须被拒绝，因为它既没有越过自身的缓存成本（11），
        // 也没有达到一个合理价格；而一旦越过缓存成本就应被接受，即使它低于未命中成本。
        // 被接受的情形见 publishesCategoryPrices...。
        ModelBillingPriceService.PublishCommand command =
                new ModelBillingPriceService.PublishCommand(
                        "text-standard", 58, 230, 40, 160,
                        new BigDecimal("0.30"), "2026-09-v2", Instant.now(), null, null,
                        5L, null, null, 11L);

        assertThatThrownBy(() -> service.publish(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("model_price_margin_invalid:cache_hit_price");
        verify(repository, never()).insert(any());
    }

    @Test
    void acceptsACacheHitPriceBelowTheMissCostWhenItClearsTheCacheCost() {
        Instant effectiveFrom = Instant.parse("2026-09-01T00:00:00Z");
        when(repository.lockModel("text-standard")).thenReturn(Mono.empty());
        when(repository.closeCurrent("text-standard", effectiveFrom)).thenReturn(Mono.empty());
        when(repository.insert(any())).thenReturn(Mono.empty());

        // 缓存读取 11 分/M 是 40 分/M 未命中成本的四分之一。16 分/M 的缓存零售价
        // 对买方是大幅折扣且依然盈利，这正是按缓存成本而非未命中成本给缓存定价的
        // 全部意义所在。
        ModelBillingPriceCalculator.Price price = service.publish(
                new ModelBillingPriceService.PublishCommand(
                        "text-standard", 58, 230, 40, 160,
                        new BigDecimal("0.30"), "2026-09-v3", effectiveFrom,
                        null, null, 16L, null, null, 11L))
                .block();

        assertThat(price.cacheHitPricePerMillion()).isEqualTo(16L);
        assertThat(price.cacheHitPricePerMillion()).isLessThan(price.inputPricePerMillion());
    }

    @Test
    void rejectsACachePriceThatIsBelowItsProviderCacheCost() {
        // text-standard：成本未命中 40 / 输出 160，零售 58 / 230（1.44 倍）越过下限，
        // 因此唯一的违规是缓存价 5 对缓存成本 11 —— qwen 的缓存比率是 1/5，
        // 比 DeepSeek 差十倍。
        ModelBillingPriceService.PublishCommand command =
                new ModelBillingPriceService.PublishCommand(
                        "text-standard", 58, 230, 40, 160,
                        new BigDecimal("0.30"), "2026-09-v2", Instant.now(), null, null,
                        5L, null, null, 11L);

        assertThatThrownBy(() -> service.publish(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("model_price_margin_invalid:cache_hit_price");
    }

    @Test
    void rejectsAReasoningPriceBelowTheOutputCost() {
        // text-pro：成本未命中 90 / 输出 270，零售 130 / 390（1.44 倍）越过下限。
        ModelBillingPriceService.PublishCommand command =
                new ModelBillingPriceService.PublishCommand(
                        "text-pro", 130, 390, 90, 270,
                        new BigDecimal("0.30"), "2026-09-v2", Instant.now(), null, null,
                        null, null, 100L, 0L);

        assertThatThrownBy(() -> service.publish(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("model_price_margin_invalid:reasoning_price");
    }

    @Test
    void omittingCategoryPricesKeepsThePreMigrationBehaviour() {
        Instant effectiveFrom = Instant.parse("2026-09-01T00:00:00Z");
        when(repository.lockModel("text-basic")).thenReturn(Mono.empty());
        when(repository.closeCurrent("text-basic", effectiveFrom)).thenReturn(Mono.empty());
        when(repository.insert(any())).thenReturn(Mono.empty());

        // 10 参数形式是本功能之前所有调用方一直使用的形式。
        ModelBillingPriceCalculator.Price price = service.publish(
                new ModelBillingPriceService.PublishCommand(
                        "text-basic", 200, 400, 100, 200,
                        new BigDecimal("0.40"), "2026-09-v3", effectiveFrom, null, null))
                .block();

        assertThat(price.cacheHitPricePerMillion()).isNull();
        assertThat(price.cacheWritePricePerMillion()).isNull();
        assertThat(price.reasoningPricePerMillion()).isNull();
    }
}
