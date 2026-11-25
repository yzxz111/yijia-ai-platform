package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Flat {@code {code,message}} admin error: the code is the stable machine-readable identifier the
 * console maps to Chinese copy, the message is operator-facing detail (design 5.1 error envelope).
 *
 * <p>{@code details} is optional and additive: it carries structured context for codes whose copy
 * alone is not actionable — {@code skill_code_conflict} reports which record already owns the code,
 * so the console can name its kind instead of telling the operator to "pick another one".
 */
class SkillAdminException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> details;

    SkillAdminException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    SkillAdminException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    static SkillAdminException invalid(String message) {
        return new SkillAdminException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_skill_input", message);
    }

    /** Same 422 envelope, explicit stable code ({@code invalid_skill_kind}/{@code invalid_skill_member}). */
    static SkillAdminException invalid(String code, String message) {
        return new SkillAdminException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /** A conflict that names the record already holding the resource. */
    static SkillAdminException conflict(String code, String message, Map<String, Object> details) {
        return new SkillAdminException(HttpStatus.CONFLICT, code, message, details);
    }

    String code() {
        return code;
    }

    HttpStatus status() {
        return status;
    }

    Map<String, Object> details() {
        return details;
    }
}

