package com.yuzhihuihe.apiplatform.gateway.skill.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Requests for the two-phase YCPS 1.1 Expert reverse-import workflow. */
public final class SkillPortableImportRequests {

    private SkillPortableImportRequests() {}

    /** Actions apply only to fields listed in the import diff. Omitted fields use FILL_EMPTY. */
    public enum FieldAction {
        FILL_EMPTY,
        OVERWRITE,
        MERGE
    }

    /** Images intentionally have a smaller action set; list MERGE never applies to assets. */
    public enum ImageAction {
        FILL_EMPTY,
        OVERWRITE
    }

    public record CommitRequest(
            UUID skillId,
            Integer expectedRowVersion,
        Map<String, FieldAction> fields,
            Map<String, ImageAction> images) {
        public CommitRequest {
            // Keep null action values long enough for the service to return its documented 422
            // action error instead of Map.copyOf throwing an unhandled NPE (500).
            fields = fields == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            images = images == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(images));
        }
    }
}
