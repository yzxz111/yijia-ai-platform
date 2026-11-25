package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ExpertAggregateCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Wire-level contract for the new multipart Expert editor errors. */
class SkillExpertAggregateErrorContractTest {

    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private SkillExpertAggregateService service;
    private SkillExpertProfileService profiles;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = org.mockito.Mockito.mock(SkillExpertAggregateService.class);
        profiles = org.mockito.Mockito.mock(SkillExpertProfileService.class);
        client = WebTestClient.bindToController(
                        new SkillExpertAggregateController(service, new ObjectMapper()),
                        new SkillExpertProfileAdminController(profiles))
                .controllerAdvice(new SkillAdminExceptionHandler())
                .argumentResolvers(config -> config.addCustomResolver(
                        new HandlerMethodArgumentResolver() {
                            @Override
                            public boolean supportsParameter(MethodParameter parameter) {
                                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                            }

                            @Override
                            public Mono<Object> resolveArgument(MethodParameter parameter,
                                    BindingContext bindingContext, ServerWebExchange exchange) {
                                return Mono.just(new Jwt("test-token", Instant.now(),
                                        Instant.now().plusSeconds(60), Map.of("alg", "none"),
                                        Map.of("sub", ACTOR.toString())));
                            }
                        }))
                .build();
    }

    @Test
    void malformedMultipartJsonHasStableSafeEnvelope() {
        submit("{invalid secret-value")
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("expert_aggregate_json_invalid")
                .jsonPath("$.message").isEqualTo("提交内容格式无效，请刷新页面后重试。")
                .jsonPath("$.details.field").isEqualTo("data")
                .consumeWith(result -> assertThat(new String(
                        result.getResponseBody(), StandardCharsets.UTF_8))
                        .doesNotContain("secret-value"));
    }

    @Test
    void invalidJsonFieldReportsOnlyAWhitelistedFieldName() {
        submit("{\"sortWeight\":\"secret-value\"}")
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("expert_aggregate_json_invalid")
                .jsonPath("$.details.field").isEqualTo("sortWeight")
                .consumeWith(result -> assertThat(new String(
                        result.getResponseBody(), StandardCharsets.UTF_8))
                        .doesNotContain("secret-value"));
    }

    @Test
    void copyLimitNamesTheFieldWithoutLeakingTheSubmittedText() {
        failWith(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "skill_description_invalid"));

        submit("{}")
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_description_invalid")
                .jsonPath("$.message").isEqualTo("中文详情不能超过 1024 个字符。")
                .jsonPath("$.details.field").isEqualTo("description");
    }

    @Test
    void imageDimensionAndSizeErrorsNameTheirRole() {
        failWith(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid_expert_image_illustration_illustration_dimensions"));
        submit("{}")
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo(
                        "invalid_expert_image_illustration_illustration_dimensions")
                .jsonPath("$.message").isEqualTo(
                        "介绍插画必须为 3:1，宽不超过 1440 像素、高不超过 480 像素。")
                .jsonPath("$.details.field").isEqualTo("images.illustration");

        failWith(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "expert_image_too_large_exampleImage"));
        submit("{}")
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.code").isEqualTo("expert_image_too_large_exampleImage")
                .jsonPath("$.message").isEqualTo("示例图片超过 512 KiB 大小限制。")
                .jsonPath("$.details.field").isEqualTo("images.exampleImage");
    }

    @Test
    void updateEndpointUsesTheSameErrorEnvelope() {
        UUID id = UUID.randomUUID();
        when(service.update(eq(id), any(ExpertAggregateCommand.class), isNull(), isNull(),
                isNull(), eq(ACTOR)))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "skill_summary_invalid")));

        client.put().uri("/console/gateway/admin/skills/{id}/expert-presentation", id)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("data", "{}"))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_summary_invalid")
                .jsonPath("$.message").isEqualTo("中文简介不能超过 512 个字符。")
                .jsonPath("$.details.field").isEqualTo("summary");
    }

    @Test
    void legacyProfileEndpointKeepsItsExistingErrorCopy() {
        UUID id = UUID.randomUUID();
        when(profiles.admin(id)).thenReturn(Mono.error(new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "skill_expert_profile_preview_invalid")));

        client.get().uri("/console/gateway/admin/skills/{id}/expert-presentation", id)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_expert_profile_preview_invalid")
                .jsonPath("$.message").isEqualTo("专家示例预览结构无效。")
                .jsonPath("$.details").doesNotExist();
    }

    @Test
    void previewAndCanvasValidatorsRetainPreciseSafePaths() throws Exception {
        ExpertPreviewContent preview = new ExpertPreviewContent(
                "", null, null, "请求", null, "输出", List.of("正文"),
                null, null, null, null);
        ExpertFieldValidationException heading = catchThrowableOfType(
                ExpertFieldValidationException.class,
                () -> ExpertPresentationValidator.preview(preview, true, "previewEn"));
        assertThat(heading.getReason()).isEqualTo("skill_expert_profile_preview_invalid");
        assertThat(heading.field()).isEqualTo("previewEn.heading");

        ExpertFieldValidationException table = catchThrowableOfType(
                ExpertFieldValidationException.class,
                () -> ExpertPresentationValidator.canvas(new ObjectMapper().readTree("""
                        {"zh":{"version":1,"width":1080,"height":704,"nodes":[
                          {"id":"table-1","field":"preview.table","kind":"table",
                           "x":10,"y":10,"width":100,"height":100,
                           "table":{"headers":["名称"],"columnWidths":[50],"rows":[]}}]}}
                        """)));
        assertThat(table.getReason()).isEqualTo("skill_expert_profile_canvas_invalid");
        assertThat(table.field()).isEqualTo("canvas.zh.nodes[0].table");
    }

    private void failWith(ResponseStatusException error) {
        when(service.create(any(ExpertAggregateCommand.class), isNull(), isNull(), isNull(),
                eq(ACTOR)))
                .thenReturn(Mono.error(error));
    }

    private WebTestClient.ResponseSpec submit(String json) {
        return client.post().uri("/console/gateway/admin/skills/experts")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("data", json))
                .exchange();
    }
}
