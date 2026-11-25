package com.yuzhihuihe.apiplatform.platform.sms;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.tea.TeaException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class AliyunSmsProvider implements SmsProvider {

    private static final Set<String> TRANSIENT_CODES = Set.of(
            "ISV_BUSINESS_LIMIT_CONTROL",
            "ISP_SYSTEM_ERROR",
            "REQUEST_TIMEOUT",
            "SERVICE_UNAVAILABLE",
            "THROTTLING",
            "INTERNAL_ERROR");
    private static final Set<String> PERMANENT_CODES = Set.of(
            "ISV_MOBILE_NUMBER_ILLEGAL",
            "ISV_SMS_TEMPLATE_ILLEGAL",
            "ISV_TEMPLATE_MISSING_PARAMETERS",
            "ISV_SMS_SIGN_ILLEGAL",
            "INVALID_ACCESS_KEY_ID_NOT_FOUND",
            "SIGNATURE_DOES_NOT_MATCH",
            "RAM_PERMISSION_DENY",
            "INVALID_PARAMETER");
    private static final Map<String, String> CODE_ALIASES = Map.of(
            "REQUESTTIMEOUT", "REQUEST_TIMEOUT",
            "INVALIDACCESSKEYID_NOTFOUND", "INVALID_ACCESS_KEY_ID_NOT_FOUND",
            "SERVICEUNAVAILABLE", "SERVICE_UNAVAILABLE",
            "SIGNATUREDOESNOTMATCH", "SIGNATURE_DOES_NOT_MATCH");

    private final Client client;
    private final String signName;
    private final ObjectMapper objectMapper;

    AliyunSmsProvider(Client client, String signName, ObjectMapper objectMapper) {
        this.client = client;
        this.signName = signName;
        this.objectMapper = objectMapper;
    }

    @Override
    public Result send(Request request) {
        try {
            String providerPhone = request.phoneNumber().startsWith("+86")
                    ? request.phoneNumber().substring(3) : request.phoneNumber();
            SendSmsRequest aliyunRequest = new SendSmsRequest()
                    .setPhoneNumbers(providerPhone)
                    .setSignName(signName)
                    .setTemplateCode(request.templateCode())
                    .setTemplateParam(objectMapper.writeValueAsString(request.variables()));
            SendSmsResponse response = client.sendSms(aliyunRequest);
            if (response == null || response.getBody() == null) {
                return Result.transientFailure("EMPTY_PROVIDER_RESPONSE");
            }
            return mapResult(
                    response.getBody().getCode(),
                    response.getBody().getRequestId(),
                    response.getBody().getBizId());
        } catch (Exception providerFailure) {
            return mapException(providerFailure);
        }
    }

    static Result mapResult(String code, String requestId, String bizId) {
        if ("OK".equalsIgnoreCase(code)) {
            return Result.success(requestId, bizId);
        }
        return classifyCode(code);
    }

    static Result mapException(Throwable error) {
        if (hasCause(error, SocketTimeoutException.class)) {
            return Result.transientFailure("PROVIDER_TIMEOUT");
        }
        if (hasCause(error, IOException.class)) {
            return Result.transientFailure("PROVIDER_NETWORK_ERROR");
        }
        if (error instanceof TeaException teaException) {
            return classifyCode(teaException.getCode());
        }
        return Result.permanentFailure("UNCLASSIFIED_PROVIDER_ERROR");
    }

    private static Result classifyCode(String code) {
        String normalized = normalizeCode(code);
        if (TRANSIENT_CODES.contains(normalized)) {
            return Result.transientFailure(normalized);
        }
        if (PERMANENT_CODES.contains(normalized)) {
            return Result.permanentFailure(normalized);
        }
        return Result.permanentFailure("UNCLASSIFIED_PROVIDER_ERROR");
    }

    private static String normalizeCode(String code) {
        if (code == null || !code.matches("[A-Za-z][A-Za-z0-9._-]{0,127}")) {
            return "UNCLASSIFIED_PROVIDER_ERROR";
        }
        String normalized = code.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        return CODE_ALIASES.getOrDefault(normalized, normalized);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
