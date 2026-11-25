package com.yuzhihuihe.apiplatform.gateway.economics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class UsageEconomicsEventTest {

    @Test
    void versionThreeStageContractCarriesDetailedUsageBillabilityAndTheDimension() {
        // V3 是明确写出其单位的版本。平台仍能读取 V1/V2，而
        // PREVIOUS_VERSION 记录了它们究竟是哪一个，因此总能靠事件自身的
        // version 字段把它与它的前身区分开。
        assertThat(UsageEconomicsEvent.VERSION).isEqualTo("USAGE_ECONOMICS_V3");
        assertThat(UsageEconomicsEvent.PREVIOUS_VERSION).isEqualTo("USAGE_ECONOMICS_V2");
        assertThat(UsageEconomicsEvent.LEGACY_VERSION).isEqualTo("USAGE_ECONOMICS_V1");
        Set<String> eventComponents =
                Arrays.stream(UsageEconomicsEvent.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(Collectors.toSet());
        assertThat(eventComponents).contains(
                "usageDimension", "usageUnits", "packageSettledUnits");

        Set<String> components = Arrays.stream(UsageEconomicsEvent.Stage.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(components).contains(
                "cacheHitTokens", "cacheMissTokens", "cacheWriteTokens",
                "reasoningTokens", "textTokens", "imageTokens", "audioTokens",
                "usageSource", "providerRequestId", "customerBillable",
                "providerBillable", "reconciliationStatus",
                "usageDimension", "usageUnits");
    }

    @Test
    void aStageWrittenWithoutADimensionIsTokenDenominatedAndSaysSo() {
        // token 维度不是猜出来的：计量后的单位数本身就是 token 总数，因此
        // 调用方若没有什么维度特定的信息要表达，就不可能发出一个
        // 与其 token 不一致的单位数值。
        UsageEconomicsEvent.Stage stage = new UsageEconomicsEvent.Stage(
                0, "FINAL_RESPONSE", "text-basic", "deepseek-chat", null, null,
                30, 10, 40, null, null, null, null, null, null, null, null, null, null,
                false, false);

        assertThat(stage.usageDimension()).isEqualTo(UsageEconomicsEvent.DIMENSION_TOKEN);
        assertThat(stage.usageUnits()).isEqualTo(stage.totalTokens());
    }
}
