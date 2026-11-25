package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillVersionView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** The Expert-only YCPS 1.1 portable revision compiler endpoint. */
@RestController
@RequestMapping("/console/gateway/admin/skills")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillPortableRevisionAdminController {

    private final SkillPortableRevisionService portable;
    private final SkillCatalogEventService catalogEvents;

    public SkillPortableRevisionAdminController(
            SkillPortableRevisionService portable,
            SkillCatalogEventService catalogEvents) {
        this.portable = portable;
        this.catalogEvents = catalogEvents;
    }

    /**
     * Compiles the selected version/revision into YCPS 1.1. The old artifact is never deleted or
     * replaced; repeated requests with the same base and presentation hashes return its successor.
     */
    @PostMapping("/{id}/versions/{versionId}/portable-revisions")
    Mono<SkillVersionView> generate(
            @PathVariable UUID id,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal Jwt jwt) {
        return catalogEvents.publishAfter(portable.generate(id, versionId, actor(jwt)));
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
