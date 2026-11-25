package com.yuzhihuihe.apiplatform.gateway.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProviderPriceSnapshot(
        UUID id,
        String providerCode,
        String modelCode,
        long inputPricePerMillion,
        long outputPricePerMillion,
        Long cacheReadPricePerMillion,
        Long cacheWritePricePerMillion,
        Long reasoningPricePerMillion,
        String currency,
        BigDecimal internalExchangeRate,
        String officialSource,
        Instant effectiveFrom,
        Instant effectiveTo) {}
