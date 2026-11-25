package com.yuzhihuihe.apiplatform.gateway.skill;

import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Public skill catalog preview plus authenticated detail endpoints.
 *
 * <p>Contract (design 5.2 C1/C2): existing fields and {@code object:"list"}/{@code object:"skill"}
 * semantics are unchanged; {@code limit}/{@code after}/{@code category}/{@code kind} plus the
 * {@code has_more} cursor and the package fields
 * (md5/sha256/size_bytes/min_client_version/download_url) are additive.
 */
@RestController
@RequestMapping("/v1/skills")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillsController {

    private static final String IMAGE_CSP =
            "default-src 'none'; style-src 'none'; script-src 'none'; sandbox";
    /** R-30: the client version is taken from this explicit header, never from the User-Agent. */
    static final String CLIENT_VERSION_HEADER = "X-Client-Version";

    private final SkillCatalogService catalog;

    public SkillsController(SkillCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    Mono<ResponseEntity<?>> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String kind,
            @RequestParam(name = "include_unpublished", defaultValue = "false")
                    boolean includeUnpublished,
            @RequestHeader(name = CLIENT_VERSION_HEADER, required = false) String clientVersion,
            ServerWebExchange exchange) {
        String requestId = SkillClientErrors.requestId(exchange);
        return catalog.list(category, kind, clientVersion, limit, after, includeUnpublished)
                .<ResponseEntity<?>>map(skills -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-request-id", requestId)
                        .body(skills))
                .onErrorResume(error -> Mono.just(SkillClientErrors.error(error, requestId)));
    }

    @GetMapping("/{name}")
    Mono<ResponseEntity<?>> get(
            @PathVariable String name,
            @RequestHeader(name = CLIENT_VERSION_HEADER, required = false) String clientVersion,
            ServerWebExchange exchange) {
        String requestId = SkillClientErrors.requestId(exchange);
        return SkillAccessIdentity.resolve(exchange)
                .flatMap(identity -> catalog.loadSkill(name, clientVersion))
                .<ResponseEntity<?>>map(skill -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-request-id", requestId)
                        .body(skill))
                .onErrorResume(error -> Mono.just(SkillClientErrors.error(error, requestId)));
    }

    /** Published artwork; anonymous reads cover every catalog kind, and missing art returns 404. */
    @GetMapping("/{code}/illustration")
    Mono<ResponseEntity<?>> illustration(
            @PathVariable String code, ServerWebExchange exchange) {
        String requestId = SkillClientErrors.requestId(exchange);
        return catalog.illustration(code)
                .<ResponseEntity<?>>map(illustration -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(illustration.mediaType()))
                        .header("x-request-id", requestId)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", IMAGE_CSP)
                        .body(illustration.content()))
                .onErrorResume(error -> Mono.just(SkillClientErrors.error(error, requestId)));
    }

    /** Optional feature/output image for a published catalog package. */
    @GetMapping("/{code}/example-image")
    Mono<ResponseEntity<?>> exampleImage(
            @PathVariable String code, ServerWebExchange exchange) {
        String requestId = SkillClientErrors.requestId(exchange);
        return catalog.exampleImage(code)
                .<ResponseEntity<?>>map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.mediaType()))
                        .header("x-request-id", requestId)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", IMAGE_CSP)
                        .body(image.content()))
                .onErrorResume(error -> Mono.just(SkillClientErrors.error(error, requestId)));
    }

    @GetMapping("/{code}/icon")
    Mono<ResponseEntity<?>> icon(@PathVariable String code, ServerWebExchange exchange) {
        String requestId = SkillClientErrors.requestId(exchange);
        return catalog.icon(code, isAnonymousRequest(exchange))
                .<ResponseEntity<?>>map(icon -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(icon.mediaType()))
                        .header("x-request-id", requestId)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", IMAGE_CSP)
                        .body(icon.content()))
                .onErrorResume(error -> Mono.just(SkillClientErrors.error(error, requestId)));
    }

    private static boolean isAnonymousRequest(ServerWebExchange exchange) {
        if (exchange.getAttributes().containsKey(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE)) {
            return false;
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return authorization == null || authorization.isBlank();
    }
}
