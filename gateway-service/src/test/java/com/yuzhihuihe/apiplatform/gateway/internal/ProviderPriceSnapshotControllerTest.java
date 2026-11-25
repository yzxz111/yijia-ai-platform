package com.yuzhihuihe.apiplatform.gateway.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

class ProviderPriceSnapshotControllerTest {
    @Test
    void snapshotEndpointIsInternalAndCoveredByGlobalHmacFilter() throws Exception {
        GetMapping mapping = ProviderPriceSnapshotController.class.getDeclaredMethod("snapshot")
                .getAnnotation(GetMapping.class);
        assertThat(mapping.value()).containsExactly("/internal/provider-prices/snapshot");
        assertThat(ProviderPriceSnapshotController.class.getDeclaredMethod("snapshot")
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('INTERNAL')");
        assertThat(com.yuzhihuihe.apiplatform.gateway.security.InternalHmacWebFilter.class)
                .isNotNull();
        assertThat(ProviderPriceQueryRepository.SNAPSHOT_SQL)
                .contains("DISTINCT ON (provider.id, model.id)")
                .contains("cache_read_price_per_million")
                .contains("cache_write_price_per_million")
                .contains("reasoning_price_per_million")
                .contains("price.effective_from <= now()")
                .contains("price.effective_to IS NULL OR price.effective_to > now()")
                .contains("price.effective_from DESC, price.id DESC");
    }
}
