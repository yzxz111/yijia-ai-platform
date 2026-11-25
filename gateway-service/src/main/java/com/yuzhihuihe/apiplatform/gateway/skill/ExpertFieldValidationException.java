package com.yuzhihuihe.apiplatform.gateway.skill;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A validation code with a server-generated field path for the Expert editor. */
final class ExpertFieldValidationException extends ResponseStatusException {

    private final String field;

    ExpertFieldValidationException(String code, String field) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code);
        this.field = field;
    }

    String field() {
        return field;
    }
}
