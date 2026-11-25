package com.yuzhihuihe.apiplatform.gateway.skill;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewBlock;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewSection;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewTable;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewTableStyle;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared bounds/shape checks for the optional rich preview and WYSIWYG canvas payloads. */
final class ExpertPresentationValidator {

    private static final int MAX_TEXT = 1024;
    private static final int MAX_BLOCKS = 16;
    private static final int MAX_SECTIONS = 8;
    private static final int MAX_COLUMNS = 8;
    private static final int MAX_ROWS = 32;
    private static final int MAX_NODES = 128;
    private static final int MAX_RUNS_PER_NODE = 128;
    private static final int MAX_TABLE_CELL_TEXT = 1024;
    /** The two localized layouts share the YCPS expert.* aggregate 32 KiB budget. */
    private static final int MAX_CANVAS_BYTES = 32 * 1024;
    private static final Set<String> TONES = Set.of("body", "muted", "accent");
    private static final Set<String> KINDS = Set.of("text", "card", "image", "tag", "table");
    private static final Set<String> ASSET_ROLES = Set.of("illustration", "icon", "exampleImage");

    private ExpertPresentationValidator() {}

    static ExpertPreviewContent preview(ExpertPreviewContent value, boolean required) {
        return preview(value, required, "preview");
    }

    static ExpertPreviewContent preview(
            ExpertPreviewContent value, boolean required, String field) {
        if (value == null) {
            if (required) throw new ExpertFieldValidationException(
                    "skill_expert_profile_preview_required", field);
            return null;
        }
        return new ExpertPreviewContent(
                at(field + ".heading", () -> requiredText(value.heading())),
                at(field + ".headingPrefix", () -> optionalText(value.headingPrefix())),
                at(field + ".scene", () -> optionalText(value.scene())),
                at(field + ".request", () -> requiredText(value.request())),
                at(field + ".material", () -> optionalText(value.material())),
                at(field + ".outputTitle", () -> requiredText(value.outputTitle())),
                at(field + ".outputParagraphs", () -> strings(value.outputParagraphs(), 8)),
                at(field + ".intro", () -> block(value.intro())),
                at(field + ".table", () -> table(value.table())),
                at(field + ".sections", () -> sections(value.sections())),
                at(field + ".blocks", () -> blocks(value.blocks())));
    }

    /**
     * Canvas is an additive JSON contract.  The current console sends a localized object with
     * {@code zh}/{@code en} layouts, while older/low-level callers may send a single layout.  Both
     * forms are accepted so a profile can be round-tripped without making the canvas mandatory.
     */
    static JsonNode canvas(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isObject()) throw new ExpertFieldValidationException(
                "skill_expert_profile_canvas_invalid", "canvas");
        if (value.has("version")) {
            atVoid("canvas.zh", () -> validateLayout(value, "canvas.zh"));
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            result.set("zh", value.deepCopy());
            atVoid("canvas", () -> checkCanvasSize(result));
            return result;
        }
        JsonNode zh = value.get("zh");
        JsonNode en = value.get("en");
        if (zh == null && en == null) throw new ExpertFieldValidationException(
                "skill_expert_profile_canvas_invalid", "canvas");
        Iterator<String> fields = value.fieldNames();
        while (fields.hasNext()) {
            if (!Set.of("zh", "en").contains(fields.next())) {
                throw new ExpertFieldValidationException(
                        "skill_expert_profile_canvas_invalid", "canvas");
            }
        }
        if (zh != null && !zh.isNull()) atVoid("canvas.zh", () -> validateLayout(zh, "canvas.zh"));
        if (en != null && !en.isNull()) atVoid("canvas.en", () -> validateLayout(en, "canvas.en"));
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (zh != null && !zh.isNull()) result.set("zh", zh.deepCopy());
        if (en != null && !en.isNull()) result.set("en", en.deepCopy());
        if (result.isEmpty()) return null;
        atVoid("canvas", () -> checkCanvasSize(result));
        return result;
    }

    /**
     * Validate a canvas alongside its semantic preview without changing the canvas payload.
     *
     * <p>The semantic preview and the WYSIWYG canvas are two independent representations in the
     * YCPS 1.1 contract.  A canvas may intentionally contain text nodes for a preview that also
     * has a table or other structured content: the package and snapshot must round-trip exactly,
     * and the client decides which representation to render.  Keep the preview arguments for
     * source compatibility with callers that already perform both validations here.</p>
     */
    static JsonNode canvas(
            JsonNode value,
            ExpertPreviewContent previewZh,
            ExpertPreviewContent previewEn) {
        // Keep both arguments in the signature so profile and aggregate callers do not need a
        // coordinated wire/API change.  Validation of preview and canvas remains independent.
        return canvas(value);
    }

    private static void validateLayout(JsonNode value, String field) {
        if (!value.isObject()
                || value.path("version").asInt(-1) != 1
                || value.path("width").asInt(-1) != 1080
                || value.path("height").asInt(-1) != 704
                || !value.path("nodes").isArray()
                || value.path("nodes").size() > MAX_NODES) {
            throw invalid("skill_expert_profile_canvas_invalid");
        }
        Set<String> nodeIds = new HashSet<>();
        int index = 0;
        for (JsonNode node : value.path("nodes")) {
            String nodeField = field + ".nodes[" + index++ + "]";
            atVoid(nodeField, () -> validateNode(node, nodeIds, nodeField));
        }
    }

    private static void validateNode(JsonNode node, Set<String> nodeIds, String field) {
        if (!node.isObject()
                || !textNode(node.get("id"), 128)
                || !textNode(node.get("field"), 128)
                || !KINDS.contains(node.path("kind").asText("text"))
                || !finite(node.get("x")) || !finite(node.get("y"))
                || !finite(node.get("width")) || !finite(node.get("height"))
                || node.path("x").asDouble() < 0 || node.path("y").asDouble() < 0
                || node.path("width").asDouble() <= 0 || node.path("height").asDouble() <= 0
                || node.path("x").asDouble() + node.path("width").asDouble() > 1080
                || node.path("y").asDouble() + node.path("height").asDouble() > 704) {
            throw invalid("skill_expert_profile_canvas_invalid");
        }
        if (!nodeIds.add(node.path("id").asText())) {
            throw new ExpertFieldValidationException("skill_expert_profile_canvas_invalid",
                    field + ".id");
        }
        JsonNode runs = node.get("runs");
        if (runs != null) {
            if (!runs.isArray() || runs.size() > MAX_RUNS_PER_NODE) {
                throw new ExpertFieldValidationException("skill_expert_profile_canvas_invalid",
                        field + ".runs");
            }
            int runIndex = 0;
            for (JsonNode run : runs) {
                if (!run.isObject() || !textNode(run.get("text"), MAX_TEXT)
                        || !optionalRange(run.get("fontSize"), 8, 72)
                        || !optionalNumber(run.get("fontWeight"))
                        || !optionalColor(run.get("color"))
                        || (run.get("italic") != null && !run.get("italic").isBoolean())) {
                    throw new ExpertFieldValidationException(
                            "skill_expert_profile_canvas_invalid",
                            field + ".runs[" + runIndex + "]");
                }
                runIndex++;
            }
        }
        if ("table".equals(node.path("kind").asText())) {
            atVoid(field + ".table", () -> tableNode(node));
        }
        JsonNode style = node.get("style");
        if (style != null && (!style.isObject()
                || !optionalColor(style.get("color"))
                || !optionalColor(style.get("backgroundColor"))
                || !optionalColor(style.get("borderColor"))
                || !optionalNumber(style.get("borderRadius"))
                || !(style.get("overflow") == null || style.get("overflow").isNull()
                        || "auto".equals(style.get("overflow").asText())
                        || "visible".equals(style.get("overflow").asText())))) {
            throw new ExpertFieldValidationException("skill_expert_profile_canvas_invalid",
                    field + ".style");
        }
        JsonNode assetRole = node.get("assetRole");
        if (assetRole != null && !assetRole.isNull()
                && (!assetRole.isTextual() || !ASSET_ROLES.contains(assetRole.asText()))) {
            throw new ExpertFieldValidationException("skill_expert_profile_canvas_invalid",
                    field + ".assetRole");
        }
    }

    /**
     * Validate the payload of a canvas table node without flattening it into text runs.  The
     * canonical shape is {@code node.table = {headers,columnWidths,rows,totalRow,style}}, while
     * accepting the same fields directly on the node keeps imports produced by early editor
     * builds forward compatible.  Table values are intentionally JSON scalars so the desktop
     * renderer can preserve numbers instead of receiving preformatted strings.
     */
    private static void tableNode(JsonNode node) {
        JsonNode value = node.get("table");
        if (value == null || value.isNull()) value = node;
        if (!value.isObject()
                || !value.path("headers").isArray()
                || !value.path("columnWidths").isArray()
                || !value.path("rows").isArray()) {
            throw invalid("skill_expert_profile_canvas_invalid");
        }
        JsonNode headers = value.path("headers");
        JsonNode widths = value.path("columnWidths");
        JsonNode rows = value.path("rows");
        if (headers.size() == 0 || headers.size() > MAX_COLUMNS
                || widths.size() != headers.size() || rows.size() > MAX_ROWS
                || widths.size() == 0) {
            throw invalid("skill_expert_profile_canvas_invalid");
        }
        int widthTotal = 0;
        for (JsonNode header : headers) {
            if (!textNode(header, MAX_TABLE_CELL_TEXT)) {
                throw invalid("skill_expert_profile_canvas_invalid");
            }
        }
        for (JsonNode width : widths) {
            if (!width.isIntegralNumber() || width.asInt() <= 0 || width.asInt() > 100) {
                throw invalid("skill_expert_profile_canvas_invalid");
            }
            widthTotal += width.asInt();
        }
        if (widthTotal != 100) throw invalid("skill_expert_profile_canvas_invalid");
        JsonNode totalRow = value.get("totalRow");
        if (totalRow != null && !totalRow.isNull() && !totalRow.isBoolean()) {
            throw invalid("skill_expert_profile_canvas_invalid");
        }
        JsonNode tableStyle = value.get("style");
        if (tableStyle != null && !tableStyle.isNull()) validateTableStyle(tableStyle);
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() != headers.size()) {
                throw invalid("skill_expert_profile_canvas_invalid");
            }
            for (JsonNode cell : row) validateTableCell(cell);
        }
    }

    private static void validateTableCell(JsonNode value) {
        if (value == null || value.isNull() || value.isBoolean()
                || (!value.isTextual() && !value.isNumber() && !value.isObject())
                || (value.isTextual() && value.asText().length() > MAX_TABLE_CELL_TEXT)
                || (value.isTextual() && value.asText().isBlank())
                || (value.isNumber() && !Double.isFinite(value.asDouble()))) {
            throw invalid("skill_expert_profile_canvas_invalid");
        }
        // A cell object is useful when an editor needs per-cell typography.  Its text/value is
        // still scalar, while optional style fields mirror a table's style contract.
        if (value.isObject()) {
            JsonNode text = value.get("text");
            if (text == null) text = value.get("value");
            if (text == null || (!text.isTextual() && !text.isNumber())
                    || (text.isTextual() && (text.asText().isBlank()
                            || text.asText().length() > MAX_TABLE_CELL_TEXT))
                    || (text.isNumber() && !Double.isFinite(text.asDouble()))) {
                throw invalid("skill_expert_profile_canvas_invalid");
            }
            validateTableStyle(value);
        }
    }

    private static void validateTableStyle(JsonNode style) {
        if (!style.isObject()
                || !optionalRange(style.get("fontSize"), 8, 72)
                || !optionalColor(style.get("color"))
                || !optionalColor(style.get("headerColor"))
                || !optionalColor(style.get("headerBackgroundColor"))
                || !optionalColor(style.get("borderColor"))
                || !optionalRange(style.get("borderRadius"), 0, 32)
                || !optionalRange(style.get("cellPadding"), 0, 64)
                || !optionalRange(style.get("rowHeight"), 1, 256)) {
            throw invalid("skill_expert_profile_canvas_invalid");
        }
    }

    private static void checkCanvasSize(JsonNode value) {
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > MAX_CANVAS_BYTES) {
            throw invalid("skill_expert_profile_canvas_too_large");
        }
    }

    private static List<String> strings(List<String> values, int maximum) {
        if (values == null) return List.of();
        if (values.size() > maximum) throw invalid("skill_expert_profile_preview_invalid");
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) result.add(requiredText(value));
        return List.copyOf(result);
    }

    private static ExpertPreviewBlock block(ExpertPreviewBlock value) {
        if (value == null) return null;
        String tone = value.tone() == null || value.tone().isBlank() ? "body" : value.tone().strip();
        if (!TONES.contains(tone)) throw invalid("skill_expert_profile_preview_invalid");
        return new ExpertPreviewBlock(tone, requiredText(value.text()));
    }

    private static List<ExpertPreviewBlock> blocks(List<ExpertPreviewBlock> values) {
        if (values == null) return null;
        if (values.size() > MAX_BLOCKS) throw invalid("skill_expert_profile_preview_invalid");
        return values.stream().map(ExpertPresentationValidator::block).toList();
    }

    private static List<ExpertPreviewSection> sections(List<ExpertPreviewSection> values) {
        if (values == null) return null;
        if (values.size() > MAX_SECTIONS) throw invalid("skill_expert_profile_preview_invalid");
        return values.stream().map(section -> {
            if (section == null) throw invalid("skill_expert_profile_preview_invalid");
            String title = requiredText(section.title());
            List<ExpertPreviewBlock> sectionBlocks = blocks(section.blocks());
            if (sectionBlocks == null || sectionBlocks.isEmpty()) {
                throw invalid("skill_expert_profile_preview_invalid");
            }
            return new ExpertPreviewSection(title, sectionBlocks);
        }).toList();
    }

    private static ExpertPreviewTable table(ExpertPreviewTable value) {
        if (value == null) return null;
        if (value.headers().isEmpty() || value.headers().size() > MAX_COLUMNS
                || value.columnWidths().size() != value.headers().size()
                || value.rows().size() > MAX_ROWS
                || value.columnWidths().stream().anyMatch(width -> width == null || width <= 0)
                || value.columnWidths().stream().mapToInt(Integer::intValue).sum() != 100) {
            throw invalid("skill_expert_profile_preview_invalid");
        }
        List<String> headers = value.headers().stream().map(ExpertPresentationValidator::requiredText).toList();
        List<List<Object>> rows = value.rows().stream().map(row -> {
            if (row == null || row.size() != headers.size()) {
                throw invalid("skill_expert_profile_preview_invalid");
            }
            return row.stream().map(ExpertPresentationValidator::cell).toList();
        }).toList();
        return new ExpertPreviewTable(headers, List.copyOf(value.columnWidths()), rows,
                value.totalRow() == null ? Boolean.FALSE : value.totalRow(),
                tableStyle(value.style()));
    }

    private static ExpertPreviewTableStyle tableStyle(ExpertPreviewTableStyle value) {
        if (value == null) return null;
        if (value.fontSize() != null && (value.fontSize() < 8 || value.fontSize() > 72)
                || !optionalColor(value.color())
                || !optionalColor(value.headerColor())
                || !optionalColor(value.headerBackgroundColor())
                || !optionalColor(value.borderColor())
                || value.borderRadius() != null
                        && (value.borderRadius() < 0 || value.borderRadius() > 32)
                || value.cellPadding() != null
                        && (value.cellPadding() < 0 || value.cellPadding() > 64)
                || value.rowHeight() != null
                        && (value.rowHeight() < 1 || value.rowHeight() > 256)) {
            throw invalid("skill_expert_profile_preview_invalid");
        }
        return value;
    }

    private static Object cell(Object value) {
        if (value instanceof String text) return requiredText(text);
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) return value;
        throw invalid("skill_expert_profile_preview_invalid");
    }

    private static String requiredText(String value) {
        if (value == null || value.isBlank() || value.strip().length() > MAX_TEXT) {
            throw invalid("skill_expert_profile_preview_invalid");
        }
        return value.strip();
    }

    private static String optionalText(String value) {
        if (value == null || value.isBlank()) return null;
        return requiredText(value);
    }

    private static boolean textNode(JsonNode value, int maximum) {
        return value != null && value.isTextual() && !value.asText().isBlank()
                && value.asText().strip().length() <= maximum;
    }

    private static boolean finite(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.asDouble());
    }

    private static boolean optionalNumber(JsonNode value) {
        return value == null || value.isNull() || finite(value);
    }

    private static boolean optionalRange(JsonNode value, double minimum, double maximum) {
        return value == null || value.isNull()
                || (finite(value) && value.asDouble() >= minimum && value.asDouble() <= maximum);
    }

    private static boolean optionalColor(JsonNode value) {
        return value == null || value.isNull()
                || (value.isTextual() && value.asText().matches("^#[0-9a-fA-F]{6}$"));
    }

    private static boolean optionalColor(String value) {
        return value == null || value.matches("^#[0-9a-fA-F]{6}$");
    }

    private static <T> T at(String field, Supplier<T> validation) {
        try {
            return validation.get();
        } catch (ResponseStatusException error) {
            throw withField(error, field);
        }
    }

    private static void atVoid(String field, Runnable validation) {
        try {
            validation.run();
        } catch (ResponseStatusException error) {
            throw withField(error, field);
        }
    }

    private static ResponseStatusException withField(ResponseStatusException error, String field) {
        if (error instanceof ExpertFieldValidationException) return error;
        return new ExpertFieldValidationException(error.getReason(), field);
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
