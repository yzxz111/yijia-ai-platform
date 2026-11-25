package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ExpertPresentationCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.AdminExpertPresentation;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPresentation;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.PublicExpertPresentation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** JSON contract test for the WYSIWYG profile API used by the console editor. */
class SkillExpertProfileControllerContractTest {

    @Test
    void servesThePublishedPresentationWithoutAClientIdentity() throws Exception {
        JsonNode canvas = new ObjectMapper().readTree("""
                {"zh":{"version":1,"width":1080,"height":704,"nodes":[
                  {"id":"heading","field":"preview.heading","kind":"text",
                   "x":32,"y":28,"width":560,"height":42,
                   "runs":[{"text":"一句话，理清表格"}]}]}}
                """);
        ExpertPresentation presentation = new ExpertPresentation(
                "内容写作专家", null, "简介", null, "详情", null,
                List.of("文章写作"), List.of(), "输入主题。", null,
                List.of("写一段介绍。"), List.of(),
                new ExpertPreviewContent(
                        "示例", null, "请写介绍", null, "输出", List.of("正文")),
                null,
                Map.of("illustration", "/v1/skills/content-writing-expert/illustration"),
                0, 1, Instant.parse("2026-09-21T00:00:00Z"), canvas);
        SkillExpertProfileService service = org.mockito.Mockito.mock(SkillExpertProfileService.class);
        when(service.publicByCode("content-writing-expert")).thenReturn(Mono.just(
                new PublicExpertPresentation(
                        PublicExpertPresentation.OBJECT,
                        "content-writing-expert",
                        "EXPERT",
                        Instant.parse("2026-09-20T10:00:00Z"),
                        Instant.parse("2026-09-20T11:00:00Z"),
                        Instant.parse("2026-09-20T12:00:00Z"),
                        presentation)));
        WebTestClient client = WebTestClient.bindToController(
                new SkillExpertProfilePublicController(service)).build();

        client.get().uri("/v1/skills/content-writing-expert/expert-presentation")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.iconUpdatedAt").isEqualTo("2026-09-20T10:00:00Z")
                .jsonPath("$.illustrationUpdatedAt").isEqualTo("2026-09-20T11:00:00Z")
                .jsonPath("$.exampleImageUpdatedAt").isEqualTo("2026-09-20T12:00:00Z")
                .jsonPath("$.presentation.usageGuide").isEqualTo("输入主题。")
                .jsonPath("$.presentation.preview.outputParagraphs[0]").isEqualTo("正文")
                .jsonPath("$.presentation.assets.illustration")
                .isEqualTo("/v1/skills/content-writing-expert/illustration")
                .jsonPath("$.presentation.canvas.zh.width").isEqualTo(1080)
                .jsonPath("$.presentation.canvas.zh.height").isEqualTo(704)
                .jsonPath("$.presentation.canvas.zh.nodes[0].field")
                .isEqualTo("preview.heading");
    }

    @Test
    void preservesPreviewShapeAndReturnsParentAggregateRowVersion() throws Exception {
        UUID skillId = UUID.randomUUID();
        AdminExpertPresentation body = new AdminExpertPresentation(
                skillId,
                "content-writing-expert",
                "EXPERT",
                new ExpertPresentation(
                        "内容写作专家",
                        "Content Writing Expert",
                        "简介",
                        "Summary",
                        "详情",
                        "Description",
                        List.of("文章写作"),
                        List.of("Article writing"),
                        "输入主题和受众。",
                        "Provide topic and audience.",
                        List.of("请写一段产品介绍。"),
                        List.of("Write a product introduction."),
                        new ExpertPreviewContent(
                                "示例",
                                "营销场景",
                                "请写产品介绍",
                                "产品资料",
                                "输出",
                                List.of("这是一段结构化输出。")),
                        null,
                        Map.of("icon", "/v1/skills/content-writing-expert/icon"),
                        0,
                        9,
                        Instant.parse("2026-09-20T00:00:00Z")));
        SkillExpertProfileService service = org.mockito.Mockito.mock(SkillExpertProfileService.class);
        when(service.admin(skillId)).thenReturn(Mono.just(body));
        when(service.save(eq(skillId), any(ExpertPresentationCommand.class), isNull(UUID.class)))
                .thenReturn(Mono.just(body));
        WebTestClient client = WebTestClient.bindToController(
                new SkillExpertProfileAdminController(service)).build();

        client.get().uri("/console/gateway/admin/skills/{id}/expert-presentation", skillId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.skillId").isEqualTo(skillId.toString())
                .jsonPath("$.presentation.preview.heading").isEqualTo("示例")
                .jsonPath("$.presentation.preview.outputParagraphs[0]")
                .isEqualTo("这是一段结构化输出。")
                .jsonPath("$.presentation.rowVersion").isEqualTo(9)
                .jsonPath("$.presentation.assets.icon")
                .isEqualTo("/v1/skills/content-writing-expert/icon");

        String commandJson = """
                {
                  "taskTypes":["文章写作"],
                  "taskTypesEn":["Article writing"],
                  "usageGuide":"输入主题和受众。",
                  "usageGuideEn":"Provide topic and audience.",
                  "examplePrompts":["请写一段产品介绍。"],
                  "examplePromptsEn":["Write a product introduction."],
                  "preview":{"heading":"示例","scene":"营销场景","request":"请写产品介绍",
                    "material":"产品资料","outputTitle":"输出","outputParagraphs":["这是一段结构化输出。"]},
                  "canvas":{"zh":{"version":1,"width":1080,"height":704,"nodes":[
                    {"id":"heading","field":"preview.heading","kind":"text","x":32,"y":28,
                     "width":560,"height":42,"runs":[{"text":"示例"}]}]}},
                  "rowVersion":9
                }
                """;
        ExpertPresentationCommand parsed = new ObjectMapper()
                .readValue(commandJson, ExpertPresentationCommand.class);
        new SkillExpertProfileAdminController(service).put(skillId, parsed, null)
                .block();

        ArgumentCaptor<ExpertPresentationCommand> captured =
                ArgumentCaptor.forClass(ExpertPresentationCommand.class);
        verify(service).save(eq(skillId), captured.capture(), isNull(UUID.class));
        assertThat(captured.getValue().rowVersion()).isEqualTo(9);
        assertThat(captured.getValue().preview().outputParagraphs())
                .containsExactly("这是一段结构化输出。");
        assertThat(captured.getValue().canvas().path("zh").path("width").asInt())
                .isEqualTo(1080);
    }
}
