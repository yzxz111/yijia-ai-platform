package com.yuzhihuihe.apiplatform.gateway.skill;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Public read-only projection for the full presentation of a published Expert. */
@RestController
@RequestMapping("/v1/skills")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillExpertProfilePublicController {

    private final SkillExpertProfileService profiles;

    public SkillExpertProfilePublicController(SkillExpertProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/{code}/expert-presentation")
    Mono<ResponseEntity<?>> get(@PathVariable String code, ServerWebExchange exchange) {
        String requestId = SkillClientErrors.requestId(exchange);
        return profiles.publicByCode(code)
                .<ResponseEntity<?>>map(value -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-request-id", requestId)
                        .body(value))
                .onErrorResume(error -> Mono.just(SkillClientErrors.error(error, requestId)));
    }
}
