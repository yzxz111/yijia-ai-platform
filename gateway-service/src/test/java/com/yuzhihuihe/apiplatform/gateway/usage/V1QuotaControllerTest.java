package com.yuzhihuihe.apiplatform.gateway.usage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * {@code GET /v1/quota} 的契约：API-key 接口层针对该 key 所绑定的实例作答，使用套餐自身的
 * 单位，并且在没有任何可报告内容时失败即拒绝（fail-closed）。
 */
class V1QuotaControllerTest {

    private static final String REQUEST_ID = "req_quota_contract";
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID INSTANCE = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static WebTestClient clientFor(UsageQueryService service, ApiKeyService.ApiKeyContext context) {
        return WebTestClient.bindToController(new V1QuotaController(service))
                .webFilter((exchange, chain) -> {
                    if (context != null) {
                        exchange.getAttributes().put(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, context);
                    }
                    exchange.getAttributes().put(ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);
                    return chain.filter(exchange);
                })
                .build();
    }

    private static ApiKeyService.ApiKeyContext contextWith(UUID packageInstanceId) {
        return new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), TENANT, UUID.randomUUID(), packageInstanceId, Set.of("text-basic"));
    }

    private static UsageQueryService.ActivePackage videoSecondQuota() {
        return new UsageQueryService.ActivePackage(
                INSTANCE, "video-pro", "视频专业版", "ACTIVE",
                600L, 30L, 120L, 450L,
                200, Instant.parse("2026-10-12T03:47:03Z"),
                List.of("video-basic", "video-pro"),
                "VIDEO_SECOND",
                List.of(new UsageQueryService.ModelQuota("video-pro", 600L, 30L, 120L, 450L)),
                // 纯视频套餐不卖语音：第二份额度是 null，而不是 0。
                null);
    }

    /** 方案 C 的混合套餐：视频秒那一份 600，音频秒那一份 1800。 */
    private static UsageQueryService.ActivePackage mixedQuota() {
        return new UsageQueryService.ActivePackage(
                INSTANCE, "video-audio", "视频 + 语音", "ACTIVE",
                600L, 30L, 120L, 450L,
                200, Instant.parse("2026-10-12T03:47:03Z"),
                List.of("video-basic", "audio-asr-basic"),
                "VIDEO_SECOND",
                List.of(new UsageQueryService.ModelQuota("video-basic", 600L, 30L, 120L, 450L)),
                new UsageQueryService.AudioSeconds(1800L, 60L, 240L, 1500L));
    }

    @Test
    void returnsTheKeysOwnPackageQuotaWithReservedAlreadyDeducted() {
        UsageQueryService service = mock(UsageQueryService.class);
        when(service.packageQuota(TENANT, INSTANCE)).thenReturn(Mono.just(videoSecondQuota()));

        clientFor(service, contextWith(INSTANCE)).get().uri("/v1/quota").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("x-request-id", REQUEST_ID)
                // 缓存配额会破坏这个实时端点的意义。
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.packageCode").isEqualTo("video-pro")
                .jsonPath("$.packageName").isEqualTo("视频专业版")
                .jsonPath("$.status").isEqualTo("ACTIVE")
                .jsonPath("$.totalTokens").isEqualTo(600)
                .jsonPath("$.reservedTokens").isEqualTo(30)
                .jsonPath("$.usedTokens").isEqualTo(120)
                // remaining = total - reserved - used，即在途预留已经被扣除
                .jsonPath("$.remainingTokens").isEqualTo(450)
                .jsonPath("$.usageDimension").isEqualTo("VIDEO_SECOND")
                .jsonPath("$.dailyRequestLimit").isEqualTo(200)
                .jsonPath("$.allowedModels[1]").isEqualTo("video-pro")
                // 这个套餐不卖语音：客户端必须能区分"不卖"（null）与"卖了 0 秒"。
                .jsonPath("$.audioSeconds").doesNotExist();

        // 配额必须按该 key 的租户与实例读取，绝不能按账户范围读取。
        verify(service).packageQuota(TENANT, INSTANCE);
    }

    /**
     * 方案 C：混合套餐的两份额度必须在同一个响应里都看得到。
     *
     * <p>主那份额度仍由 {@code usageDimension} 标注单位（视频秒），第二份永远是秒、
     * 因此字段名直接写单位。缺了它，客户在配额页上只看到视频秒——
     * 他会以为语音额度没有到账。
     */
    @Test
    void exposesBothBucketsOfAMixedPackage() {
        UsageQueryService service = mock(UsageQueryService.class);
        when(service.packageQuota(TENANT, INSTANCE)).thenReturn(Mono.just(mixedQuota()));

        clientFor(service, contextWith(INSTANCE)).get().uri("/v1/quota").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalTokens").isEqualTo(600)
                .jsonPath("$.remainingTokens").isEqualTo(450)
                .jsonPath("$.audioSeconds.totalSeconds").isEqualTo(1800)
                .jsonPath("$.audioSeconds.reservedSeconds").isEqualTo(60)
                .jsonPath("$.audioSeconds.usedSeconds").isEqualTo(240)
                // 剩余同样扣除了在途占用。
                .jsonPath("$.audioSeconds.remainingSeconds").isEqualTo(1500);
    }

    @Test
    void rejectsARequestWithoutAnAuthenticatedApiKey() {
        clientFor(mock(UsageQueryService.class), null).get().uri("/v1/quota").exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_api_key")
                .jsonPath("$.error.type").isEqualTo("authentication_error");
    }

    @Test
    void reports404WhenTheKeyHasNoBoundPackageInstance() {
        clientFor(mock(UsageQueryService.class), contextWith(null)).get().uri("/v1/quota").exchange()
                .expectStatus().isNotFound()
                // OpenAI 信封格式，与其他每一个 /v1 路由一样——不是 Spring 的默认错误响应体
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("package_not_found")
                .jsonPath("$.error.type").isEqualTo("quota_error")
                .jsonPath("$.request_id").isNotEmpty();
    }

    @Test
    void reports404WhenTheBoundInstanceNoLongerExists() {
        UsageQueryService service = mock(UsageQueryService.class);
        when(service.packageQuota(any(), any())).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "package_not_found")));

        clientFor(service, contextWith(INSTANCE)).get().uri("/v1/quota").exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("package_not_found");
    }

    @Test
    void neverLeaksSpringsDefaultErrorBodyForAnUnexpectedFailure() {
        UsageQueryService service = mock(UsageQueryService.class);
        when(service.packageQuota(any(), any())).thenReturn(Mono.error(
                new IllegalStateException("database down")));

        clientFor(service, contextWith(INSTANCE)).get().uri("/v1/quota").exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("server_error")
                // 内部失败原因绝不能到达调用方。
                .jsonPath("$.error.message").isEqualTo("Internal server error");
    }
}
