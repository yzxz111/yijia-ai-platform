package com.yuzhihuihe.apiplatform.gateway.skill;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Client-facing category vocabulary for the desktop featured-scene cards.
 *
 * <p>The desktop renders its "精选场景" cards from the console-curated category list, so it has to read
 * the same vocabulary the console writes. Both routes are public like the {@code /v1/skills} list: the
 * featured scenes are the storefront the desktop shows before anyone signs in, so requiring a session would
 * leave the section empty for a signed-out user.
 */
@RestController
@RequestMapping("/v1/skill-categories")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillCategoriesController {

    private static final String IMAGE_CSP =
            "default-src 'none'; style-src 'none'; script-src 'none'; sandbox";

    private final SkillCatalogService catalog;

    public SkillCategoriesController(SkillCatalogService catalog) {
        this.catalog = catalog;
    }

    /**
     * The published categories of one package kind. Only categories that still have a published package
     * are returned, so a card never renders without an expert behind it. {@code kind} defaults to
     * {@code EXPERT} because the desktop directory is the expert surface.
     */
    @GetMapping
    Mono<ResponseEntity<?>> list(
            @RequestParam(required = false) String kind,
            ServerWebExchange exchange) {
        String requestId = SkillClientErrors.requestId(exchange);
        return catalog.listCategories(kind)
                .<ResponseEntity<?>>map(categories -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-request-id", requestId)
                        .body(categories))
                .onErrorResume(error -> Mono.just(SkillClientErrors.error(error, requestId)));
    }

    /**
     * The cover bytes of one published category. 404 {@code skill_category_image_not_found} when the
     * category has no cover, so the desktop keeps the image it ships with.
     */
    @GetMapping("/{code}/image")
    Mono<ResponseEntity<?>> image(
            @PathVariable String code,
            @RequestParam(required = false) String kind,
            ServerWebExchange exchange) {
        String requestId = SkillClientErrors.requestId(exchange);
        return catalog.categoryImage(kind, code)
                .<ResponseEntity<?>>map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.mediaType()))
                        .header("x-request-id", requestId)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", IMAGE_CSP)
                        .body(image.content()))
                .onErrorResume(error -> Mono.just(SkillClientErrors.error(error, requestId)));
    }
}
