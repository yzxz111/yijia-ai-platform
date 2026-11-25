package com.yuzhihuihe.apiplatform.gateway.skill.api;

import java.util.List;

/** Request contracts for {@code /console/gateway/admin/skills/**} (design 5.1 A1-A14). */
public final class SkillAdminRequests {

    private SkillAdminRequests() {}

    public record SkillListQuery(
            int page,
            int size,
            String query,
            String status,
            String category,
            String kind,
            String sort,
            String direction) {}

    /**
     * A2. {@code kind} is only honoured on create (E-01); the {@code *En} fields are the English
     * overlay of {@code name}/{@code summary}/{@code description} and never replace them (D2).
     */
    public record CreateSkillCommand(
            String code,
            String name,
            String nameEn,
            String category,
            List<String> tags,
            String summary,
            String summaryEn,
            String description,
            String descriptionEn,
            String icon,
            String minClientVersion,
            String kind,
            Integer sortWeight) {}

    /**
     * A4. {@code kind} is accepted for contract symmetry but must equal the stored value: changing it
     * is rejected with 422 {@code invalid_skill_kind} (E-01).
     */
    public record UpdateSkillCommand(
            String name,
            String nameEn,
            String category,
            List<String> tags,
            String summary,
            String summaryEn,
            String description,
            String descriptionEn,
            String icon,
            String minClientVersion,
            String kind,
            Integer sortWeight,
            Integer rowVersion) {}

    /** A9: optional body of A9; when {@code version} is absent the newest READY version is published. */
    public record PublishSkillCommand(String version) {}

    /** Optional body of A8. */
    public record PublishVersionCommand(String note) {}

    /**
     * Whole-roster replacement (A15): the console edits the table and saves once. {@code memberKey}
     * is mandatory and unique inside the list (E-05); at most one entry may be {@code LEAD} (E-04).
     */
    public record ReplaceMembersCommand(List<MemberCommand> members) {}

    public record MemberCommand(
            String memberKey,
            String name,
            String nameEn,
            String profession,
            String professionEn,
            String role,
            Integer sortOrder) {}

    /**
     * A23. Create one category of a kind's vocabulary. {@code name} is the value the console writes
     * into {@code gateway.skill.category}, so it must be unique inside the kind; {@code code} is the
     * stable identifier used by the console and defaults to the name when omitted.
     */
    public record CreateCategoryCommand(
            String kind,
            String code,
            String name,
            String nameEn) {}

    /**
     * A25. Rename one category of a kind's vocabulary.
     *
     * <p>{@code name} becomes the new value written into {@code gateway.skill.category}, so the
     * gateway re-points every live package still carrying the old name in the same transaction. That is
     * why the old name is part of the path: it is the value being migrated, not just the row identity.
     * {@code code} is not accepted — the stable identifier never changes, so the console can keep using
     * it in saved filters.
     */
    public record UpdateCategoryCommand(String name, String nameEn) {}
}

