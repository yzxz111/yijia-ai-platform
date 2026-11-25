package com.yuzhihuihe.apiplatform.platform.sms;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.notification.NotificationTypeRegistry;

@Service
public class SmsTemplateActivationService {
    private final SmsTemplateActivationRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    SmsTemplateActivationService(SmsTemplateActivationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ActivationResult activate(ActivationRequest request, UUID actorUserId) {
        if (request == null || actorUserId == null || !request.providerTemplateApproved()
                || blank(request.templateKey()) || blank(request.providerBusinessNo())
                || blank(request.providerTemplateNo())) {
            throw invalid("invalid_sms_template_activation");
        }
        NotificationTypeRegistry.TypeDefinition definition = NotificationTypeRegistry.types().values().stream()
                .filter(type -> request.templateKey().equals(type.smsTemplateKey()))
                .findFirst().orElseThrow(() -> invalid("sms_template_not_in_notification_registry"));
        SmsTemplateActivationRepository.TemplateRow row =
                repository.lockTemplate(request.templateKey());
        if (row == null) {
            throw invalid("sms_template_not_found");
        }
        Template template = new Template(
                row.key(), row.code(), row.type(), variables(row.variablesJson()), row.status());
        if (!"NOTIFICATION".equals(template.type())
                || !template.code().equals(request.providerTemplateNo())
                || !template.variables().equals(definition.allowedPayloadKeys())) {
            throw invalid("sms_template_registry_mismatch");
        }
        if (!"PENDING_INTEGRATION".equals(template.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "sms_template_not_pending");
        }
        repository.activate(template.key());
        repository.audit(actorUserId, template.key(),
                request.providerBusinessNo(), template.code());
        return new ActivationResult(template.key(), "ACTIVE");
    }

    private Set<String> variables(String json) {
        try { return Set.copyOf(mapper.readValue(json, new TypeReference<List<String>>() { })); }
        catch (Exception error) { throw new IllegalStateException("invalid_sms_template_registry", error); }
    }
    private boolean blank(String value) { return value == null || value.isBlank() || value.length() > 128; }
    private ResponseStatusException invalid(String reason) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason); }

    public record ActivationRequest(String templateKey, boolean providerTemplateApproved,
                                    String providerBusinessNo, String providerTemplateNo) { }
    public record ActivationResult(String templateKey, String integrationStatus) { }
    private record Template(String key, String code, String type, Set<String> variables, String status) { }
}
