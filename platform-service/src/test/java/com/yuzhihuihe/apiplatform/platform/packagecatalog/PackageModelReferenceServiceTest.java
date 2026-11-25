package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PackageModelReferenceServiceTest {

    @Test
    void returnsTotalAndReferenceDetailsAcrossVersionStates() {
        PackageVersionRepository repository = mock(PackageVersionRepository.class);
        when(repository.countModelReferences("text-basic")).thenReturn(2L);
        when(repository.findModelReferences("text-basic", 10)).thenReturn(List.of(
                new PackageVersionRepository.ModelReferenceRow(
                        "STARTER", "入门套餐", 2, "PUBLISHED"),
                new PackageVersionRepository.ModelReferenceRow(
                        "STARTER", "入门套餐", 1, "RETIRED")));
        PackageVersionService service = new PackageVersionService(
                repository,
                new ObjectMapper(),
                mock(ProviderPriceClient.class),
                mock(ProviderUnitCostClient.class),
                mock(ProviderRetailPriceClient.class),
                mock(ModelUsageDimensionRepository.class),
                mock(DimensionPricePolicyRepository.class),
                mock(PackageMarginPolicy.class));

        PackageVersionService.ModelReferenceView result = service.modelReferences("text-basic");

        assertThat(result.referenceCount()).isEqualTo(2);
        assertThat(result.references()).extracting(
                PackageVersionService.PackageModelReference::versionStatus)
                .containsExactly("PUBLISHED", "RETIRED");
    }

    @Test
    void rejectsInvalidModelCodesBeforeQueryingTheCatalog() {
        PackageVersionService service = new PackageVersionService(
                mock(PackageVersionRepository.class),
                new ObjectMapper(),
                mock(ProviderPriceClient.class),
                mock(ProviderUnitCostClient.class),
                mock(ProviderRetailPriceClient.class),
                mock(ModelUsageDimensionRepository.class),
                mock(DimensionPricePolicyRepository.class),
                mock(PackageMarginPolicy.class));

        assertThatThrownBy(() -> service.modelReferences("  "))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("invalid_model_code");
                });
    }
}
