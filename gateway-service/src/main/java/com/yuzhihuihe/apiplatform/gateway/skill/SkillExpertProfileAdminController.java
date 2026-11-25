package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ExpertPresentationCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.AdminExpertPresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Admin CRUD for the structured Expert presentation edited by the WYSIWYG page. */
@RestController
@RequestMapping("/console/gateway/admin/skills")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillExpertProfileAdminController {

    private final SkillExpertProfileService profiles;
    private final SkillCatalogEventService catalogEvents;

    public SkillExpertProfileAdminController(SkillExpertProfileService profiles) {
        this(profiles, new SkillCatalogEventService());
    }

    @Autowired
    public SkillExpertProfileAdminController(
            SkillExpertProfileService profiles, SkillCatalogEventService catalogEvents) {
        this.profiles = profiles;
        this.catalogEvents = catalogEvents;
    }

    @GetMapping("/{id}/expert-presentation")
    Mono<AdminExpertPresentation> get(@PathVariable UUID id) {
        return profiles.admin(id);
    }

    /**
     * Replaces text/structured presentation fields under the parent skill row_version. Image
     * bytes are intentionally not accepted by this JSON endpoint yet; callers must use the
     * existing skill image upload endpoints and then save the profile, so this endpoint does not
     * claim an atomic text+asset transaction.
     */
    @PutMapping(value = "/{id}/expert-presentation", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<AdminExpertPresentation> put(
            @PathVariable UUID id,
            @RequestBody ExpertPresentationCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        return catalogEvents.publishAfter(profiles.save(id, command, actor(jwt)));
    }

    @DeleteMapping("/{id}/expert-presentation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> delete(
            @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestParam int rowVersion,
            @AuthenticationPrincipal Jwt jwt) {
        return catalogEvents.publishAfter(profiles.delete(id, rowVersion, actor(jwt)));
    }

    private static UUID actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            return null;
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
