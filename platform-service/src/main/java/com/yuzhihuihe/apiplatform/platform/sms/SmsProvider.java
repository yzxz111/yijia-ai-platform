package com.yuzhihuihe.apiplatform.platform.sms;

import java.util.Map;
import java.util.Objects;

public interface SmsProvider {

    Result send(Request request);

    record Request(String phoneNumber, String templateCode, Map<String, String> variables) {
        public Request {
            Objects.requireNonNull(phoneNumber, "phoneNumber");
            Objects.requireNonNull(templateCode, "templateCode");
            variables = Map.copyOf(variables);
        }

        @Override
        public String toString() {
            return "SmsProvider.Request[phoneNumber=REDACTED, templateCode="
                    + templateCode + ", variableKeys=" + variables.keySet() + "]";
        }
    }

    enum ResultClass {
        SUCCESS,
        TRANSIENT,
        PERMANENT
    }

    record Result(
            ResultClass resultClass,
            String errorCode,
            String requestId,
            String bizId) {

        public Result {
            Objects.requireNonNull(resultClass, "resultClass");
            errorCode = sanitizeCode(errorCode);
            requestId = sanitizeIdentifier(requestId);
            bizId = sanitizeIdentifier(bizId);
        }

        public static Result success(String requestId, String bizId) {
            return new Result(ResultClass.SUCCESS, null, requestId, bizId);
        }

        public static Result transientFailure(String errorCode) {
            return new Result(ResultClass.TRANSIENT, errorCode, null, null);
        }

        public static Result permanentFailure(String errorCode) {
            return new Result(ResultClass.PERMANENT, errorCode, null, null);
        }

        public boolean success() {
            return resultClass == ResultClass.SUCCESS;
        }

        public boolean retryable() {
            return resultClass == ResultClass.TRANSIENT;
        }

        private static String sanitizeCode(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.toUpperCase(java.util.Locale.ROOT)
                    .replaceAll("[^A-Z0-9_-]", "_");
            if (normalized.isBlank() || normalized.length() > 128) {
                return "UNCLASSIFIED_PROVIDER_ERROR";
            }
            return normalized;
        }

        private static String sanitizeIdentifier(String value) {
            if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
                return null;
            }
            return value;
        }
    }
}
