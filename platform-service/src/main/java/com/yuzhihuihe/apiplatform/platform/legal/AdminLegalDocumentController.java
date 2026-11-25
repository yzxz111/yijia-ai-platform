package com.yuzhihuihe.apiplatform.platform.legal;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 服务条款与隐私政策的管理员编辑入口。{@code /console/platform/
 * admin/**} 由安全配置限制为 ADMIN 角色可访问。
 */
@RestController
@RequestMapping("/console/platform/admin/legal-documents")
public class AdminLegalDocumentController {

    private final LegalDocumentService service;

    public AdminLegalDocumentController(LegalDocumentService service) {
        this.service = service;
    }

    @GetMapping
    LegalDocumentService.AdminState state() {
        return service.adminState();
    }

    @PutMapping("/{kind}/draft")
    LegalDocumentService.AdminState saveDraft(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String kind,
            @RequestBody JsonNode body) {
        return service.saveDraft(
                kind, command(body), actor(jwt));
    }

    @PostMapping("/{kind}/publish")
    ResponseEntity<LegalDocumentService.AdminState> publish(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String kind,
            @RequestBody JsonNode body) {
        JsonNode version = body == null ? null : body.get("version");
        if (version != null && !version.isTextual()) {
            throw invalid();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.publish(
                kind,
                version == null ? null : version.textValue(),
                command(body),
                actor(jwt)));
    }

    @PostMapping("/{kind}/versions/{version}/restore")
    LegalDocumentService.AdminState restore(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String kind,
            @PathVariable String version) {
        return service.restore(kind, version, actor(jwt));
    }

    private LegalDocumentService.DraftCommand command(JsonNode body) {
        JsonNode title = body == null ? null : body.get("title");
        JsonNode content = body == null ? null : body.get("content");
        if (title != null && !title.isTextual()) {
            throw invalid();
        }
        return new LegalDocumentService.DraftCommand(
                title == null ? null : title.textValue(), content);
    }

    private UUID actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication is required");
        }
        return UUID.fromString(jwt.getSubject());
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_legal_document_request");
    }
}
