package com.yuzhihuihe.apiplatform.platform.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/console/platform/support-tickets")
public class SupportTicketController {
    private final SupportTicketService service;

    public SupportTicketController(SupportTicketService service) { this.service = service; }

    @GetMapping
    SupportTicketService.PageResult<SupportTicketService.TicketView> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listUser(tenant(jwt), actor(jwt), page, size);
    }

    @GetMapping("/{id}")
    SupportTicketService.TicketView detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return withComments(service.detailUser(tenant(jwt), actor(jwt), id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SupportTicketService.TicketView create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateRequest request) {
        return service.create(tenant(jwt), actor(jwt), request.title(), request.body());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SupportTicketService.TicketView createWithAttachments(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("title") String title,
            @RequestPart("body") String body,
            @RequestPart(name = "attachments", required = false) List<MultipartFile> attachments) {
        return service.create(tenant(jwt), actor(jwt), title, body, uploads(attachments));
    }

    @PostMapping(value = "/{id}/comments", consumes = MediaType.APPLICATION_JSON_VALUE)
    SupportTicketService.TicketView comment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody CommentRequest request) {
        return withComments(service.addUserComment(tenant(jwt), actor(jwt), id, request.body()));
    }

    @PostMapping(value = "/{id}/comments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    SupportTicketService.TicketView commentWithAttachments(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestPart("body") String body,
            @RequestPart(name = "attachments", required = false) List<MultipartFile> attachments) {
        return withComments(service.addUserComment(
                tenant(jwt), actor(jwt), id, body, uploads(attachments)));
    }

    @GetMapping("/{id}/attachments")
    List<SupportTicketService.AttachmentView> attachments(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.attachmentsUser(tenant(jwt), actor(jwt), id);
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    ResponseEntity<byte[]> attachment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable UUID attachmentId) {
        SupportTicketService.AttachmentContent attachment =
                service.attachmentUser(tenant(jwt), actor(jwt), id, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(attachment.filename(), StandardCharsets.UTF_8).build().toString())
                .body(attachment.bytes());
    }

    @PostMapping("/{id}/close")
    SupportTicketService.TicketView close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return withComments(service.closeUser(tenant(jwt), actor(jwt), id));
    }

    private SupportTicketService.TicketView withComments(SupportTicketService.TicketView ticket) {
        return new SupportTicketService.TicketView(ticket.id(), ticket.ticketNo(), ticket.tenantId(),
                ticket.userId(), ticket.assigneeUserId(), ticket.title(), ticket.body(), ticket.status(),
                ticket.resolution(), ticket.completionBusinessNo(), ticket.completedAt(), ticket.createdAt(),
                ticket.updatedAt(), service.comments(ticket.tenantId(), ticket.id()));
    }

    private static UUID tenant(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("tenant_id")); }
    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    static List<SupportTicketService.AttachmentUpload> uploads(List<MultipartFile> files) {
        if (files == null) return List.of();
        return files.stream().map(file -> {
            try {
                return new SupportTicketService.AttachmentUpload(
                        file.getOriginalFilename(), file.getContentType(), file.getBytes());
            } catch (IOException exception) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "invalid_ticket_attachment", exception);
            }
        }).toList();
    }

    public record CreateRequest(String title, String body) { }
    public record CommentRequest(String body) { }
}
