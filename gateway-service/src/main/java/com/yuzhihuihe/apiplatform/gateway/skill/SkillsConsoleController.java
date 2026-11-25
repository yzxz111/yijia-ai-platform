package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionController;
import com.yuzhihuihe.apiplatform.gateway.openai.OpenAiErrorMapper;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillCatalogViews.SkillList;
import reactor.core.publisher.Mono;

/**
 * Console-facing skill catalog mirror for administrators.
 *
 * <p>Guarded by the interactive console login (console JWT + {@code ConsoleTokenVersionWebFilter}),
 * never by an API key: the browser must not have to know or hold any {@code sk-yzh-...} secret. The
 * response bodies are byte-for-byte the same shape as the API-key protected {@code /v1/skills}
 * endpoints so the console and external clients share one contract.
 */
@RestController
@RequestMapping("/console/gateway/skills")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillsConsoleController {

    private static final String REQUEST_ID_HEADER = "x-request-id";
    private static final String IMAGE_CSP =
            "default-src 'none'; style-src 'none'; script-src 'none'; sandbox";

    /** Sentinel version used by the admin preview so no skill is filtered or answered with 426. */
    private static final String CONSOLE_CLIENT_VERSION = "9999.12.31";

    private final SkillCatalogService catalog;

    public SkillsConsoleController(SkillCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    Mono<ResponseEntity<?>> list(ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        return catalog.listSkills()
                .<ResponseEntity<?>>map(skills -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(REQUEST_ID_HEADER, requestId)
                        .body(new SkillList("list", skills)))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    @GetMapping("/{name}")
    Mono<ResponseEntity<?>> get(@PathVariable String name, ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        // The console mirror is an administrator preview, not a client: it must not be gated by the
        // skill's minClientVersion, otherwise imported skills answer 426 to the admin.
        return catalog.loadSkill(name, CONSOLE_CLIENT_VERSION)
                .<ResponseEntity<?>>map(skill -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(REQUEST_ID_HEADER, requestId)
                        .body(skill))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    /**
     * The icon bytes of one package, keyed by code.
     *
     * <p>The console mirror of {@code /v1/skills/{code}/icon}. A roster entry is a reference to
     * another expert package, so the console renders a member's portrait by fetching that expert's
     * icon through this route — the admin surface is keyed by skill id, which a roster row does not
     * carry.
     */
    @GetMapping("/{code}/icon")
    Mono<ResponseEntity<?>> icon(
            @PathVariable String code,
            ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        return catalog.icon(code)
                .<ResponseEntity<?>>map(icon -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(icon.mediaType()))
                        .header(REQUEST_ID_HEADER, requestId)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", IMAGE_CSP)
                        .body(icon.content()))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    @GetMapping("/{code}/illustration")
    Mono<ResponseEntity<?>> illustration(
            @PathVariable String code,
            ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        return catalog.illustration(code)
                .<ResponseEntity<?>>map(illustration -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(illustration.mediaType()))
                        .header(REQUEST_ID_HEADER, requestId)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", IMAGE_CSP)
                        .body(illustration.content()))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    @GetMapping("/{code}/example-image")
    Mono<ResponseEntity<?>> exampleImage(
            @PathVariable String code,
            ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        return catalog.exampleImage(code)
                .<ResponseEntity<?>>map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.mediaType()))
                        .header(REQUEST_ID_HEADER, requestId)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", IMAGE_CSP)
                        .body(image.content()))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    /** Reuses the inbound request id when a proxy supplied one so console logs stay correlatable. */
    private String requestId(ServerWebExchange exchange) {
        String inbound = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (inbound != null && !inbound.isBlank()) {
            return inbound.strip();
        }
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    private ResponseEntity<ChatCompletionController.OpenAiErrorResponse> errorResponse(
            Throwable error, String requestId) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(error);
        return ResponseEntity.status(descriptor.status())
                .headers(OpenAiErrorMapper.retryHeaders(error))
                .header(REQUEST_ID_HEADER, requestId)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatCompletionController.OpenAiErrorResponse(
                        new ChatCompletionController.OpenAiError(
                                descriptor.message(), descriptor.type(), descriptor.code()),
                        requestId));
    }
}
