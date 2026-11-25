package com.yuzhihuihe.apiplatform.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuzhihuihe.apiplatform.gateway.billing.ModelBillingPriceCalculator;
import org.junit.jupiter.api.Test;

/**
 * 单独考察预留上限的算术。
 *
 * <p>这些是结算路径其余部分所依赖的不变式，以无需数据库即可阅读的方式陈述：有效输出上限
 * 是什么，以及预留金额覆盖的是请求的最坏情况而非其估算值。
 */
class ReservationCeilingTest {

    /** text-pro 的线上零售价目表（V50）：仅在写入侧定价高于未命中缓存的情况。 */
    private static final ModelBillingPriceCalculator.Price PRICE =
            new ModelBillingPriceCalculator.Price(
                    "text-pro", 1_350, 4_050, "retail-v2", 45L, 2_025L, 4_050L);

    @Test
    void aRequestWithoutAClientLimitUsesTheDefaultNotTheSystemCeiling() {
        long cap = ReservationCeiling.effectiveMaxOutputTokens(null, null, 65_536);

        assertThat(cap).isEqualTo(ReservationCeiling.DEFAULT_MAX_OUTPUT_TOKENS);
        // 系统上限现在是 100 万：默认值必须明显低于它，否则"没人要"的请求会按 100 万预留。
        assertThat(ReservationCeiling.DEFAULT_MAX_OUTPUT_TOKENS)
                .isLessThan(ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS);
    }

    @Test
    void anExplicitClientLimitIsCappedByTheSystemCeiling() {
        long cap = ReservationCeiling.effectiveMaxOutputTokens(
                ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS + 1, null,
                (int) (ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS + 1));

        assertThat(cap).isEqualTo(ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS);
    }

    @Test
    void aClientCanAskForTheWholeSystemCeiling() {
        long cap = ReservationCeiling.effectiveMaxOutputTokens(
                ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS, null,
                (int) ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS);

        assertThat(cap).isEqualTo(ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS);
        assertThat(cap).isEqualTo(1_000_000L);
    }

    @Test
    void theSoldPlanCapWinsOverTheDefault() {
        long cap = ReservationCeiling.effectiveMaxOutputTokens(null, 2_000, 65_536);

        assertThat(cap).isEqualTo(2_000);
    }

    @Test
    void theClientLimitWinsOverThePlanCapAndIsNeverRaisedToIt() {
        assertThat(ReservationCeiling.effectiveMaxOutputTokens(500L, 2_000, 65_536))
                .isEqualTo(500);
        assertThat(ReservationCeiling.effectiveMaxOutputTokens(9_000L, 2_000, 65_536))
                .isEqualTo(2_000);
    }

    @Test
    void aZeroClientLimitCannotProduceAZeroTokenBound() {
        assertThat(ReservationCeiling.effectiveMaxOutputTokens(0L, null, 8_192)).isEqualTo(1);
    }

    @Test
    void oneCompletionKeepsTheReservedOutputAtItsPerCompletionValue() {
        assertThat(ReservationCeiling.totalOutputTokens(4_000, 1)).isEqualTo(4_000);
        assertThat(ReservationCeiling.totalOutputTokens(0, 1)).isZero();
    }

    @Test
    void theReservedOutputIsThePerCompletionCapTimesTheCompletionCount() {
        // 这里堵住的漏洞：请求 n 个补全时只预留了一个补全的输出，
        // 于是客户端可以请求一千个却只为其中一个付费。
        assertThat(ReservationCeiling.totalOutputTokens(64, 1_000)).isEqualTo(64_000);
        assertThat(ReservationCeiling.totalOutputTokens(64, 1_000))
                .isEqualTo(1_000L * ReservationCeiling.totalOutputTokens(64, 1));
        assertThat(ReservationCeiling.totalOutputTokens(64, 1_000))
                .isGreaterThan(ReservationCeiling.totalOutputTokens(64, 1));
    }

    @Test
    void aProductThatWouldOverflowIsRefusedRatherThanWrappedSmaller() {
        assertThatThrownBy(() -> ReservationCeiling.totalOutputTokens(Long.MAX_VALUE, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_reservation_completions");
        assertThatThrownBy(() -> ReservationCeiling.totalOutputTokens(65_536, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_reservation_completions");
        assertThatThrownBy(() -> ReservationCeiling.totalOutputTokens(-1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_reservation_completions");
    }

    @Test
    void thePadIsARealCeilingAndRoundsUp() {
        assertThat(ReservationCeiling.driftPadded(0, 1.25)).isZero();
        assertThat(ReservationCeiling.driftPadded(1, 1.25)).isEqualTo(2);
        assertThat(ReservationCeiling.driftPadded(400, 1.25)).isEqualTo(500);
        assertThat(ReservationCeiling.driftPadded(401, 1.25)).isEqualTo(502);
    }

    @Test
    void theReservationPricesEveryInputTokenAtTheWriteRateAndEveryOutputAtTheReasoningRate() {
        long reserved = ReservationCeiling.worstCaseAmount(PRICE, 400, 400);

        long paddedInput = ReservationCeiling.driftPadded(400, ReservationCeiling.DRIFT_PAD);
        long paddedOutput = ReservationCeiling.driftPadded(400, ReservationCeiling.DRIFT_PAD);
        assertThat(reserved).isEqualTo(PRICE.amount(
                paddedInput, paddedOutput, 0L, paddedInput, paddedOutput));
        // 并且严格高于同一请求按全部命中缓存、无推理计价的结果，
        // 而那正是旧估算实际上假定的廉价情形。
        assertThat(reserved).isGreaterThan(
                PRICE.amount(paddedInput, paddedOutput, paddedInput, 0L, 0L));
    }

    @Test
    void anActualThatIsAllCacheWriteAndAllReasoningAtTheReservedCountsFitsTheReservation() {
        long writeTokens = 4_000;
        long reasoningTokens = 4_000;
        long reserved = ReservationCeiling.worstCaseAmount(PRICE, writeTokens, reasoningTokens);

        long actual = PRICE.amount(
                writeTokens, reasoningTokens, 0L, writeTokens, reasoningTokens);

        assertThat(reserved).isGreaterThanOrEqualTo(actual);
    }

    @Test
    void aRequestWithoutTokensReservesNothing() {
        assertThat(ReservationCeiling.worstCaseAmount(PRICE, 0, 0)).isZero();
    }

    /**
     * 生效上限必须能说出**它是被哪一层压出来的**（T3）。
     *
     * <p>这条能力是为 2026-10-15 的排查加的。一个请求的输出恰好停在 4096：它可能是
     * 客户端传的 {@code max_tokens}、可能是套餐出售的上限、可能是模型声明的上限、
     * 也可能只是系统兜底；2026-10-19 起还有第五种：客户端什么都没要，网关按默认值兜底。
     * 五种来源的正确处置完全不同——改客户端、改套餐、换模型、查系统上限，
     * 或者确认"是默认值在起作用，谁都没限制它"——而只看数字只能靠猜。
     */
    @Test
    void theEffectiveCeilingNamesTheLayerThatProducedIt() {
        // 客户端什么都没要：来源是网关的默认值，不能报成系统上限——
        // 系统上限是 100 万，报它会让运维去查一个根本不起作用的数字。
        assertThat(ReservationCeiling.ceiling(null, null, 65_536).source())
                .isEqualTo(ReservationCeiling.CeilingSource.DEFAULT);
        assertThat(ReservationCeiling.ceiling(null, null, 65_536).tokens())
                .isEqualTo(ReservationCeiling.DEFAULT_MAX_OUTPUT_TOKENS);

        // 只有客户端要得比系统上限还多时，系统上限才真正成为那一层。
        assertThat(ReservationCeiling.ceiling(
                        1_000_001L, null, (int) ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS)
                        .source())
                .isEqualTo(ReservationCeiling.CeilingSource.SYSTEM);

        // 模型自己声明的上限比默认值更紧。
        assertThat(ReservationCeiling.ceiling(null, null, 8_192).source())
                .isEqualTo(ReservationCeiling.CeilingSource.MODEL);

        // 套餐出售的上限比模型更紧。
        assertThat(ReservationCeiling.ceiling(null, 2_000, 8_192).source())
                .isEqualTo(ReservationCeiling.CeilingSource.PACKAGE);

        // 客户端的 max_tokens 最紧——这正是那条 4096 请求的形态。
        assertThat(ReservationCeiling.ceiling(4_096L, 8_192, 65_536).source())
                .isEqualTo(ReservationCeiling.CeilingSource.CLIENT);
        assertThat(ReservationCeiling.ceiling(4_096L, 8_192, 65_536).tokens())
                .isEqualTo(4_096);

        // 套餐上限恰好等于 4096 时，来源应当报"套餐"——这是最容易被误判成客户端的那一种。
        assertThat(ReservationCeiling.ceiling(null, 4_096, 65_536).source())
                .isEqualTo(ReservationCeiling.CeilingSource.PACKAGE);
        assertThat(ReservationCeiling.ceiling(4_096L, 4_096, 65_536).source())
                .isEqualTo(ReservationCeiling.CeilingSource.PACKAGE);

        // 客户端显式要 100 万：数字是 100 万。来源按"平局保留先收紧的那层"报 system——
        // 客户端要的恰好等于系统上限，两者同时收紧，先收紧的是系统上限。
        // 运维看到 system + 1 000 000 只有一种成因：客户端确实要到了上限。
        assertThat(ReservationCeiling.ceiling(1_000_000L, null, 1_000_000).source())
                .isEqualTo(ReservationCeiling.CeilingSource.SYSTEM);
        assertThat(ReservationCeiling.ceiling(1_000_000L, null, 1_000_000).tokens())
                .isEqualTo(1_000_000L);
        // 差一个 token 时客户端就是真正收紧的那层。
        assertThat(ReservationCeiling.ceiling(999_999L, null, 1_000_000).source())
                .isEqualTo(ReservationCeiling.CeilingSource.CLIENT);
    }

    /**
     * 两条路径必须给出同一个数字。
     *
     * <p>{@link ReservationCeiling#ceiling} 与
     * {@link ReservationCeiling#effectiveMaxOutputTokens} 各自写了一遍 {@code min()}；
     * 一旦分叉，用量记录里的上限就会与真正发给供应商的那个数不一致——那种偏差不会报错，
     * 只会让排查照着错数字走。这里对一批组合逐一对齐。
     */
    @Test
    void bothCeilingPathsAlwaysAgreeOnTheNumber() {
        Long[] clientLimits = {null, 0L, 1L, 500L, 4_096L, 65_536L, 200_000L, 1_000_000L};
        Integer[] packageLimits = {null, 0, 1, 2_000, 4_096, 65_536, 1_000_000};
        int[] modelLimits = {0, 1, 8_192, 65_536, 200_000, 1_000_000};

        for (Long client : clientLimits) {
            for (Integer plan : packageLimits) {
                for (int model : modelLimits) {
                    ReservationCeiling.Ceiling ceiling =
                            ReservationCeiling.ceiling(client, plan, model);
                    assertThat(ceiling.ceilingMatchesThePlainValue(client, plan, model))
                            .as("client=%s plan=%s model=%s", client, plan, model)
                            .isTrue();
                    assertThat(ceiling.tokens())
                            .as("client=%s plan=%s model=%s", client, plan, model)
                            .isEqualTo(ReservationCeiling.effectiveMaxOutputTokens(
                                    client, plan, model));
                }
            }
        }
    }

    /**
     * 零上限仍然收敛到 1，且来源如实报"客户端"。
     *
     * <p>零 token 的输出会让预留失去意义，因此 1 是最小的诚实上界；而来源报客户端
     * 是因为确实是他把上限压到了 0——把它报成系统上限会让运维去查错的地方。
     */
    @Test
    void aZeroClientLimitStillNamesTheClientAsTheSource() {
        ReservationCeiling.Ceiling ceiling = ReservationCeiling.ceiling(0L, null, 8_192);

        assertThat(ceiling.tokens()).isEqualTo(1);
        assertThat(ceiling.source()).isEqualTo(ReservationCeiling.CeilingSource.CLIENT);
    }

    /** 落库用的名字必须与 {@code ck_usage_log_ceiling_source} 的词表逐字一致。 */
    @Test
    void ceilingSourceWireNamesMatchTheDatabaseVocabulary() {
        assertThat(ReservationCeiling.CeilingSource.CLIENT.wireName()).isEqualTo("client");
        assertThat(ReservationCeiling.CeilingSource.MODEL.wireName()).isEqualTo("model");
        assertThat(ReservationCeiling.CeilingSource.PACKAGE.wireName()).isEqualTo("package");
        assertThat(ReservationCeiling.CeilingSource.SYSTEM.wireName()).isEqualTo("system");
        // V118 把这第五个值加进了 ck_usage_log_ceiling_source；两边必须同步，
        // 否则一条"默认值兜底"的用量记录会因为 CHECK 失败而写不进去。
        assertThat(ReservationCeiling.CeilingSource.DEFAULT.wireName()).isEqualTo("default");
    }
}
