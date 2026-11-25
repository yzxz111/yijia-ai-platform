package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent;
import org.junit.jupiter.api.Test;

class ExpertPresentationValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void preservesCanvasAlongsideALegacyPipeTablePreview() throws Exception {
        JsonNode canvas = MAPPER.readTree("""
                {"zh":{"version":1,"width":1080,"height":704,"nodes":[
                  {"id":"heading","field":"preview.heading","kind":"text","x":1,"y":1,
                   "width":100,"height":20,"runs":[{"text":"一句话"}]}
                ]}}
                """);
        ExpertPreviewContent preview = preview(List.of(
                "单位：天",
                "姓名｜应出勤｜实际出勤｜差额",
                "林然｜22｜21｜1",
                "周宁｜22｜22｜0"));

        JsonNode result = ExpertPresentationValidator.canvas(canvas, preview, null);

        assertThat(result).isNotNull();
        assertThat(result.path("zh").path("nodes")).hasSize(1);
    }

    @Test
    void keepsCanvasForSimpleProsePreview() throws Exception {
        JsonNode canvas = MAPPER.readTree("""
                {"zh":{"version":1,"width":1080,"height":704,"nodes":[
                  {"id":"heading","field":"preview.heading","kind":"text","x":1,"y":1,
                   "width":100,"height":20,"runs":[{"text":"一句话"}],
                   "style":{"overflow":"visible"}}
                ]}}
                """);

        JsonNode result = ExpertPresentationValidator.canvas(
                canvas, preview(List.of("第一段", "第二段")), null);

        assertThat(result).isNotNull();
        assertThat(result.path("zh").path("nodes").path(0).path("style").path("overflow").asText())
                .isEqualTo("visible");
    }

    @Test
    void acceptsATableCanvasNodeWithContentAndStyle() throws Exception {
        JsonNode canvas = MAPPER.readTree("""
                {"version":1,"width":1080,"height":704,"nodes":[
                  {"id":"preview-table","field":"preview.table","kind":"table",
                   "x":24,"y":120,"width":600,"height":220,
                   "table":{"headers":["姓名","应出勤","实际出勤","差额"],
                    "columnWidths":[25,25,25,25],
                    "rows":[["林然",22,21,1],["周宁",22,22,0],["合计",44,43,1]],
                    "totalRow":true,
                    "style":{"fontSize":14,"color":"#333333","headerColor":"#666666",
                              "headerBackgroundColor":"#f1f1f1","borderColor":"#dddddd",
                              "borderRadius":8,"cellPadding":8,"rowHeight":36}}}
                ]}
                """);

        JsonNode result = ExpertPresentationValidator.canvas(canvas, null, null);

        assertThat(result).isNotNull();
        assertThat(result.path("zh").path("nodes").path(0).path("kind").asText())
                .isEqualTo("table");
        assertThat(result.path("zh").path("nodes").path(0).path("table").path("rows"))
                .hasSize(3);
    }

    @Test
    void rejectsMalformedTableCanvasNode() throws Exception {
        JsonNode canvas = MAPPER.readTree("""
                {"version":1,"width":1080,"height":704,"nodes":[
                  {"id":"preview-table","field":"preview.table","kind":"table",
                   "x":24,"y":120,"width":600,"height":220,
                   "table":{"headers":["姓名","实际"],"columnWidths":[50,40],
                    "rows":[["林然",21]]}}
                ]}
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> ExpertPresentationValidator.canvas(canvas, null, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private static ExpertPreviewContent preview(List<String> paragraphs) {
        return new ExpertPreviewContent(
                "一句话", null, null, "请求", null, "结果", paragraphs,
                null, null, null, null);
    }
}
