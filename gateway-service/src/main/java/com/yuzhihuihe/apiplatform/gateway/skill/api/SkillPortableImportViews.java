package com.yuzhihuihe.apiplatform.gateway.skill.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillVersionView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportRequests.FieldAction;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportRequests.ImageAction;

/** Response contracts for YCPS 1.1 Expert portable reverse import. */
public final class SkillPortableImportViews {

    private SkillPortableImportViews() {}

    public record PackageIdentity(
            String code,
            String kind,
            String version,
            /** The next server-side revision reserved for this import, not author metadata. */
            Integer revision) {}

    /** A diff preserves whether the package supplied a field separately from its value. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FieldDiff(
            boolean present,
            JsonNode packageValue,
            JsonNode currentValue,
            String change,
            FieldAction defaultAction,
            List<FieldAction> allowedActions) {
        public FieldDiff {
            allowedActions = allowedActions == null ? List.of(FieldAction.FILL_EMPTY,
                    FieldAction.OVERWRITE) : List.copyOf(allowedActions);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ImageDiff(
            boolean present,
            JsonNode packageValue,
            JsonNode currentValue,
            String change,
            ImageAction defaultAction,
            List<ImageAction> allowedActions) {
        public ImageDiff {
            allowedActions = allowedActions == null ? List.of(ImageAction.FILL_EMPTY,
                    ImageAction.OVERWRITE) : List.copyOf(allowedActions);
        }
    }

    public record Resource(
            String role,
            String path,
            String mediaType,
            long sizeBytes,
            String sha256) {}

    public record ResourceInventory(
            int count,
            long totalBytes,
            List<Resource> resources) {
        public ResourceInventory {
            resources = resources == null ? List.of() : List.copyOf(resources);
        }
    }

    public record ImportResponse(
            UUID importId,
            Instant expiresAt,
            String artifactSha256,
            PackageIdentity identity,
            Map<String, FieldDiff> fields,
            Map<String, ImageDiff> images,
            ResourceInventory resources,
            List<String> warnings,
            String status,
            UUID targetSkillId,
            Integer targetRowVersion) {
        public ImportResponse {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
            images = images == null ? Map.of() : Map.copyOf(images);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record CommitResponse(
            UUID importId,
            String status,
            UUID skillId,
            int revision,
            SkillView skill,
            SkillVersionView packageRevision,
            Map<String, FieldAction> fieldActions,
            Map<String, ImageAction> imageActions) {
        public CommitResponse {
            fieldActions = fieldActions == null ? Map.of() : Map.copyOf(fieldActions);
            imageActions = imageActions == null ? Map.of() : Map.copyOf(imageActions);
        }
    }
}
