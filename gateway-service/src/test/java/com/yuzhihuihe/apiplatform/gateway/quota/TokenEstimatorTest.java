package com.yuzhihuihe.apiplatform.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator(3, 1.25);

    @Test
    void usesDefaultOutputAndCapsMediaAdjustedEstimateAtModelLimit() {
        long estimate = estimator.estimate(new TokenEstimator.EstimateCommand(
                "abcdef", 100, 20, null, 400, 300));

        assertThat(estimate).isEqualTo(452);
    }

    @Test
    void capsClientOutputAtModelLimit() {
        long estimate = estimator.estimate(new TokenEstimator.EstimateCommand(
                "", 0, 0, 500L, 400, 300));

        assertThat(estimate).isEqualTo(300);
    }

    @Test
    void rejectsOverflowWhenCombiningMediaTokens() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> estimator.estimate(new TokenEstimator.EstimateCommand(
                        "", Long.MAX_VALUE, 1, 0L, 1, 1)))
                .withMessage("invalid_token_estimate_request")
                .withNoCause();
    }

    @Test
    void rejectsOverflowWhenCombiningEstimateComponents() {
        TokenEstimator unitEstimator = new TokenEstimator(1, 1.0);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> unitEstimator.estimate(new TokenEstimator.EstimateCommand(
                        "", Long.MAX_VALUE, 0, 1L, 1, 1)))
                .withMessage("invalid_token_estimate_request");
    }

    @Test
    void rejectsOverflowWhenApplyingMediaSafetyFactor() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> estimator.estimate(new TokenEstimator.EstimateCommand(
                        "", Long.MAX_VALUE, 0, 0L, 1, 1)))
                .withMessage("invalid_token_estimate_request")
                .withNoCause();
    }

    @Test
    void rejectsNonFiniteMediaSafetyFactors() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TokenEstimator(3, Double.NaN))
                .withMessage("invalid_token_estimator_configuration");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TokenEstimator(3, Double.POSITIVE_INFINITY))
                .withMessage("invalid_token_estimator_configuration");
    }

    @Test
    void estimatesTextTokensDirectlyFromCodePointCountsWithoutOverflow() {
        assertThat(estimator.estimateTextTokens(0)).isZero();
        assertThat(estimator.estimateTextTokens(1)).isEqualTo(1);
        assertThat(estimator.estimateTextTokens(3)).isEqualTo(1);
        assertThat(estimator.estimateTextTokens(4)).isEqualTo(2);
        assertThat(estimator.estimateTextTokens(Long.MAX_VALUE)).isEqualTo(3_074_457_345_618_258_603L);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> estimator.estimateTextTokens(-1))
                .withMessage("invalid_token_estimate_request");
    }
}
