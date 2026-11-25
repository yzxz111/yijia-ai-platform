package com.yuzhihuihe.apiplatform.platform.sms;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(prefix = "sms", name = "enabled", havingValue = "true")
public class SmsTemplateTestDeliveryService {

    private static final Pattern BUSINESS_NO =
            Pattern.compile("sms-template-test-[A-Za-z0-9_-]{1,109}");

    private final SmsTemplateTestDeliveryRepository repository;
    private final PhoneProtectionService phones;
    private final SmsNotificationService notifications;

    SmsTemplateTestDeliveryService(
            SmsTemplateTestDeliveryRepository repository,
            PhoneProtectionService phones,
            SmsNotificationService notifications) {
        this.repository = repository;
        this.phones = phones;
        this.notifications = notifications;
    }

    @Transactional
    public TestDeliveryView enqueue(
            String templateKey, TestDeliveryRequest request, UUID actorUserId) {
        if (actorUserId == null || request == null || request.businessNo() == null
                || !BUSINESS_NO.matcher(request.businessNo()).matches()) {
            throw new IllegalArgumentException("invalid_sms_template_test_business_no");
        }
        repository.lockBusinessNo(request.businessNo());
        List<TestDeliveryView> previous = findByBusinessNo(request.businessNo());
        if (!previous.isEmpty()) {
            return previous.getFirst();
        }

        PhoneProtectionService.ProtectedPhone phone = phones.protect(request.phone());
        UUID deliveryId = notifications.enqueueAdminTest(
                templateKey, phone, request.variables(),
                "admin-template-test:" + request.businessNo());
        repository.audit(actorUserId, deliveryId, request.businessNo(), templateKey);
        return find(deliveryId);
    }

    private List<TestDeliveryView> findByBusinessNo(String businessNo) {
        return repository.findByBusinessNo(businessNo).stream().map(this::view).toList();
    }

    private TestDeliveryView find(UUID deliveryId) {
        SmsTemplateTestDeliveryRepository.DeliveryRow row = repository.find(deliveryId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "sms_delivery_not_found");
        }
        return view(row);
    }

    private TestDeliveryView view(SmsTemplateTestDeliveryRepository.DeliveryRow result) {
        return new TestDeliveryView(
                result.deliveryId(), result.templateKey(),
                "*******" + result.phoneLast4(), result.status());
    }

    public record TestDeliveryRequest(
            String phone, Map<String, Object> variables, String businessNo) { }

    public record TestDeliveryView(
            UUID deliveryId, String templateKey, String maskedPhone, String status) { }
}
