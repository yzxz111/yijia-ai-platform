package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.NullNode;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ExpertPresentationCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.AdminExpertPresentation;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPresentation;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.PublicExpertPresentation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Business rules for the structured Expert presentation row and its public projection. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillExpertProfileService {

    static final int MAX_TASK_TYPES = 6;
    static final int MAX_PROMPTS = 8;
    static final int MAX_TEXT = 1024;

    private final SkillRepository skills;
    private final SkillExpertProfileRepository profiles;
    private final TransactionalOperator transactions;

    public SkillExpertProfileService(
            SkillRepository skills,
            SkillExpertProfileRepository profiles,
            TransactionalOperator transactions) {
        this.skills = skills;
        this.profiles = profiles;
        this.transactions = transactions;
    }

    public Mono<AdminExpertPresentation> admin(UUID skillId) {
        return skills.skill(skillId)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> requireExpert(skill)
                        .then(profiles.findBySkillId(skillId))
                        .defaultIfEmpty(empty(skill))
                        .map(stored -> new AdminExpertPresentation(
                                skill.id(), skill.code(), skill.kind(),
                                presentation(skill, stored, adminAssets(skill)))))
                .cast(AdminExpertPresentation.class);
    }

    public Mono<AdminExpertPresentation> save(
            UUID skillId, ExpertPresentationCommand request, UUID actor) {
        ExpertPresentationCommand normalized = validate(request);
        return skills.skill(skillId)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> requireExpert(skill).thenReturn(skill))
                .flatMap(skill -> profiles.findBySkillId(skillId)
                        .flatMap(existing -> update(skill, existing, normalized, actor))
                        .switchIfEmpty(insert(skill, normalized, actor)))
                .flatMap(stored -> admin(stored.skillId()))
                .onErrorMap(DuplicateKeyException.class, error -> conflict(
                        "skill_expert_profile_concurrent_modification"));
    }

    public Mono<Void> delete(UUID skillId, Integer rowVersion, UUID actor) {
        if (rowVersion == null || rowVersion < 1) {
            return Mono.error(invalid("skill_expert_profile_row_version_required"));
        }
        Mono<Void> action = skills.skill(skillId)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(SkillExpertProfileService::requireExpert)
                .then(profiles.delete(skillId, rowVersion, actor))
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(conflict(
                        "skill_expert_profile_concurrent_modification")))
                .then();
        return transactions == null ? action : transactions.transactional(action);
    }

    /** Read-only storefront detail for published and unpublished Experts, never package bytes. */
    public Mono<PublicExpertPresentation> publicByCode(String code) {
        return skills.skillByCode(code)
                .filter(skill -> "EXPERT".equalsIgnoreCase(skill.kind())
                        && ("PUBLISHED".equals(skill.status())
                                || "UNPUBLISHED".equals(skill.status()))
                        && !"CLAWHUB_SKILL".equals(skill.sourceFormat()))
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> profiles.findBySkillId(skill.id())
                        .defaultIfEmpty(empty(skill))
                        .map(stored -> new PublicExpertPresentation(
                                PublicExpertPresentation.OBJECT,
                                skill.code(),
                                skill.kind(),
                                skill.iconUpdatedAt(),
                                skill.illustrationUpdatedAt(),
                                skill.exampleImageUpdatedAt(),
                                presentation(skill, stored, publicAssets(skill)))));
    }

    private Mono<SkillExpertProfileRepository.StoredProfile> update(
            SkillView skill,
            SkillExpertProfileRepository.StoredProfile existing,
            ExpertPresentationCommand command,
            UUID actor) {
        if (command.rowVersion() == null || command.rowVersion() != existing.rowVersion()) {
            return Mono.error(conflict("skill_expert_profile_concurrent_modification"));
        }
        Instant now = Instant.now();
        Mono<SkillExpertProfileRepository.StoredProfile> action = profiles
                .update(skill.id(), command, existing.rowVersion(), actor, now)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(conflict(
                        "skill_expert_profile_concurrent_modification")))
                .then(profiles.findBySkillId(skill.id()))
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "skill_expert_profile_disappeared")));
        return transactions == null ? action : transactions.transactional(action);
    }

    private Mono<SkillExpertProfileRepository.StoredProfile> insert(
            SkillView skill,
            ExpertPresentationCommand command,
            UUID actor) {
        int expectedRowVersion = command.rowVersion() == null
                ? skill.rowVersion() : command.rowVersion();
        if (expectedRowVersion != skill.rowVersion()) {
            return Mono.error(conflict("skill_expert_profile_concurrent_modification"));
        }
        Mono<SkillExpertProfileRepository.StoredProfile> action = profiles
                .insert(skill.id(), command, expectedRowVersion, actor, Instant.now())
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "skill_expert_profile_not_inserted")))
                .then(profiles.findBySkillId(skill.id()))
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "skill_expert_profile_disappeared")));
        return transactions == null ? action : transactions.transactional(action);
    }

    private ExpertPresentation presentation(
            SkillView skill,
            SkillExpertProfileRepository.StoredProfile stored,
            Map<String, String> assets) {
        return new ExpertPresentation(
                skill.name(), skill.nameEn(), skill.summary(), skill.summaryEn(),
                skill.description(), skill.descriptionEn(),
                stored.taskTypes(), stored.taskTypesEn(), stored.usageGuide(),
                stored.usageGuideEn(), stored.examplePrompts(), stored.examplePromptsEn(),
                stored.preview(), stored.previewEn(), assets, stored.portableRevision(),
                stored.rowVersion(), stored.updatedAt(), stored.canvas(),
                stored.exampleImageLocale());
    }

    /** Public media roles are always emitted; an empty value explicitly clears older artwork. */
    private static Map<String, String> publicAssets(SkillView skill) {
        Map<String, String> assets = new LinkedHashMap<>();
        // Public presentation snapshots are presence-aware: an empty value is an explicit clear,
        // whereas an omitted role would permit a desktop to revive package/legacy artwork.
        assets.put("icon", "");
        assets.put("illustration", "");
        assets.put("exampleImage", "");
        if (skill.iconUrl() != null) {
            assets.put("icon", "/v1/skills/" + skill.code() + "/icon");
        }
        if (skill.illustrationUrl() != null) {
            assets.put("illustration", "/v1/skills/" + skill.code() + "/illustration");
        }
        if (skill.exampleImageUrl() != null) {
            assets.put("exampleImage", "/v1/skills/" + skill.code() + "/example-image");
        }
        return Map.copyOf(assets);
    }

    private static Map<String, String> adminAssets(SkillView skill) {
        Map<String, String> assets = new LinkedHashMap<>();
        if (skill.iconUrl() != null) {
            assets.put("icon", skill.iconUrl());
        }
        if (skill.illustrationUrl() != null) {
            assets.put("illustration", skill.illustrationUrl());
        }
        if (skill.exampleImageUrl() != null) {
            assets.put("exampleImage", skill.exampleImageUrl());
        }
        return Map.copyOf(assets);
    }

    private static ExpertPresentationCommand validate(ExpertPresentationCommand request) {
        if (request == null) {
            throw invalid("skill_expert_profile_required");
        }
        List<String> taskTypes = strings(request.taskTypes(), MAX_TASK_TYPES, 64,
                "skill_expert_profile_task_types_invalid");
        List<String> taskTypesEn = strings(request.taskTypesEn(), MAX_TASK_TYPES, 64,
                "skill_expert_profile_task_types_invalid");
        List<String> prompts = strings(request.examplePrompts(), MAX_PROMPTS, 300,
                "skill_expert_profile_prompts_invalid");
        List<String> promptsEn = strings(request.examplePromptsEn(), MAX_PROMPTS, 300,
                "skill_expert_profile_prompts_invalid");
        if (taskTypes.isEmpty()) {
            throw invalid("skill_expert_profile_task_types_required");
        }
        String usageGuide = text(request.usageGuide(), MAX_TEXT,
                "skill_expert_profile_usage_guide_invalid");
        if (usageGuide == null) {
            throw invalid("skill_expert_profile_usage_guide_required");
        }
        if (prompts.isEmpty()) {
            throw invalid("skill_expert_profile_prompts_required");
        }
        ExpertPreviewContent preview = validatePreview(request.preview());
        if (preview == null || preview.outputParagraphs().isEmpty()) {
            throw invalid("skill_expert_profile_preview_required");
        }
        ExpertPreviewContent previewEn = validatePreview(request.previewEn());
        String exampleImageLocale;
        try {
            exampleImageLocale = ExpertImageLocale.normalize(request.exampleImageLocale());
        } catch (IllegalArgumentException error) {
            throw invalid("skill_expert_profile_example_image_locale_invalid");
        }
        return new ExpertPresentationCommand(
                taskTypes,
                taskTypesEn,
                usageGuide,
                text(request.usageGuideEn(), MAX_TEXT, "skill_expert_profile_usage_guide_invalid"),
                prompts,
                promptsEn,
                preview,
                previewEn,
                request.rowVersion(),
                ExpertPresentationValidator.canvas(request.canvas(), preview, previewEn),
                exampleImageLocale);
    }

    private static ExpertPreviewContent validatePreview(ExpertPreviewContent preview) {
        return ExpertPresentationValidator.preview(preview, false);
    }

    private static List<String> strings(
            List<String> values, int maxItems, int maxChars, String reason) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > maxItems) {
            throw invalid(reason);
        }
        List<String> result = values.stream()
                .map(value -> required(value, maxChars, reason))
                .toList();
        if (result.stream().distinct().count() != result.size()) {
            throw invalid(reason);
        }
        return List.copyOf(result);
    }

    /** Preview paragraphs are ordered prose; repeated paragraphs are valid content, not duplicate keys. */
    private static List<String> stringsAllowingDuplicates(
            List<String> values, int maxItems, int maxChars, String reason) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > maxItems) {
            throw invalid(reason);
        }
        return List.copyOf(values.stream()
                .map(value -> required(value, maxChars, reason))
                .toList());
    }

    private static String text(String value, int maxChars, String reason) {
        return value == null ? null : optional(value, maxChars, reason);
    }

    private static String required(String value, int maxChars, String reason) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxChars) {
            throw invalid(reason);
        }
        return normalized;
    }

    private static String optional(String value, int maxChars, String reason) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxChars) {
            throw invalid(reason);
        }
        return normalized;
    }

    private static Mono<SkillView> requireExpert(SkillView skill) {
        return "EXPERT".equalsIgnoreCase(skill.kind())
                ? Mono.just(skill)
                : Mono.error(invalid("skill_expert_profile_kind_required"));
    }

    private static SkillExpertProfileRepository.StoredProfile empty(SkillView skill) {
        return new SkillExpertProfileRepository.StoredProfile(
                // Operational tags are a one-time V68 migration source, not a live fallback.
                // Keeping this shell empty prevents a later generic EXPERT row from silently
                // re-coupling business task types to gateway routing/operations metadata.
                skill.id(), skill.code(), skill.kind(), skill.status(), List.of(), List.of(),
                null, null, List.of(), List.of(), null, null, null, null, NullNode.getInstance(), 0,
                skill.rowVersion(),
                null, null);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "skill_not_found");
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
