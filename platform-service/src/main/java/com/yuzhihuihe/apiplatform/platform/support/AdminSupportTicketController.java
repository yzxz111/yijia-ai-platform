package com.yuzhihuihe.apiplatform.platform.support;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/console/platform/admin/support-tickets")
public class AdminSupportTicketController {
    private final SupportTicketService service;
    public AdminSupportTicketController(SupportTicketService service) { this.service = service; }

    @GetMapping
    SupportTicketService.PageResult<SupportTicketService.TicketView> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listAdmin(status, query, from, to, page, size);
    }

    @PostMapping("/batch-claim")
    List<SupportTicketService.TicketView> batchClaim(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody BatchClaimRequest request) {
        return service.batchClaim(actor(jwt), request.ids());
    }

    @GetMapping("/{id}")
    SupportTicketService.TicketView detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        SupportTicketService.TicketView ticket = service.detailAdmin(id);
        return withComments(ticket);
    }

    @PostMapping("/{id}/claim")
    SupportTicketService.TicketView claim(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.claim(actor(jwt), id);
    }

    @PostMapping("/{id}/process")
    SupportTicketService.TicketView process(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.process(actor(jwt), id);
    }

    @PostMapping(value = "/{id}/reply", consumes = MediaType.APPLICATION_JSON_VALUE)
    SupportTicketService.TicketView reply(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody ReplyRequest request) {
        return service.reply(actor(jwt), id, request.body());
    }

    @PostMapping(value = "/{id}/reply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    SupportTicketService.TicketView replyWithAttachments(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestPart("body") String body,
            @RequestPart(name = "attachments", required = false) List<MultipartFile> attachments) {
        return service.reply(actor(jwt), id, body, SupportTicketController.uploads(attachments));
    }

    @GetMapping("/{id}/attachments")
    List<SupportTicketService.AttachmentView> attachments(@PathVariable UUID id) {
        return service.attachmentsAdmin(id);
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    ResponseEntity<byte[]> attachment(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        SupportTicketService.AttachmentContent attachment = service.attachmentAdmin(id, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(attachment.filename(), StandardCharsets.UTF_8).build().toString())
                .body(attachment.bytes());
    }

    @PostMapping("/{id}/complete")
    SupportTicketService.TicketView complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody CompletionRequest request) {
        return service.complete(actor(jwt), id, request.resolution(), request.providerBusinessNo());
    }

    @PostMapping("/{id}/close")
    SupportTicketService.TicketView close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.closeAdmin(actor(jwt), id);
    }

    private SupportTicketService.TicketView withComments(SupportTicketService.TicketView ticket) {
        return new SupportTicketService.TicketView(ticket.id(), ticket.ticketNo(), ticket.tenantId(),
                ticket.userId(), ticket.assigneeUserId(), ticket.title(), ticket.body(), ticket.status(),
                ticket.resolution(), ticket.completionBusinessNo(), ticket.completedAt(), ticket.createdAt(),
                ticket.updatedAt(), service.comments(ticket.tenantId(), ticket.id()));
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record ReplyRequest(String body) { }
    public record CompletionRequest(String resolution, String providerBusinessNo) { }
    public record BatchClaimRequest(List<UUID> ids) { }
}
