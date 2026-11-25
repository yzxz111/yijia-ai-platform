package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportRequests.CommitRequest;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportViews.CommitResponse;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportViews.ImportResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Minimal two-step admin API for importing a YCPS 1.1 Expert presentation. */
@RestController
@RequestMapping("/console/gateway/admin/skill-portable-imports")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillPortableImportController {

    private final SkillPortableImportService imports;

    public SkillPortableImportController(SkillPortableImportService imports) {
        this.imports = imports;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Mono<ImportResponse> upload(
            @RequestPart(name = "file") FilePart file,
            @AuthenticationPrincipal Jwt jwt) {
        return imports.upload(file, actor(jwt));
    }

    @PostMapping("/{importId}/commit")
    Mono<CommitResponse> commit(
            @PathVariable UUID importId,
            @RequestBody(required = false) CommitRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return imports.commit(importId, request, actor(jwt));
    }

    private static UUID actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) return null;
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
