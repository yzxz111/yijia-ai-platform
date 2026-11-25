package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class PackageCatalogControllerAdviceTest {

    private final PackageCatalogControllerAdvice advice = new PackageCatalogControllerAdvice();

    @Test
    void mapsInvalidModelPermissionToSafeReadableError() {
        ResponseEntity<PackageCatalogControllerAdvice.ErrorBody> response = advice.handle(
                new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_model_permission"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("invalid_model_permission");
        assertThat(response.getBody().message()).isEqualTo("模型权限无效，请至少选择一个模型并检查模型能力。");
        assertThat(response.getBody().message()).doesNotContain("invalid_model_permission");
    }

    @Test
    void mapsMarginPolicyFailureToSafeReadableError() {
        ResponseEntity<PackageCatalogControllerAdvice.ErrorBody> response = advice.handle(
                new PackageMarginPolicy.MarginPolicyException("token_margin_below_minimum"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("token_margin_below_minimum");
        assertThat(response.getBody().message()).isEqualTo("套餐预计毛利率低于平台最低要求，请提高售价或降低额度。");
        assertThat(response.getBody().message()).doesNotContain("token_margin_below_minimum");
    }

    @Test
    void rethrowsUnknownResponseStatusReason() {
        ResponseStatusException exception = new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "unrelated_catalog_failure");

        assertThatThrownBy(() -> advice.handle(exception))
                .isSameAs(exception);
    }
}
