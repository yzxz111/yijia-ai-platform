package com.yuzhihuihe.apiplatform.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

class UsageQueryControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000001");

    @Test
    void exposesUserAndAdminQueryRoutes() {
        UsageQueryService service = mock(UsageQueryService.class);
        UsageAnalyticsService analyticsService = mock(UsageAnalyticsService.class);
        QuotaEntitlementsService entitlementsService = mock(QuotaEntitlementsService.class);
        UsageQueryService.PageResult<?> empty = new UsageQueryService.PageResult<>(List.of(), 0, 0, 20);
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-08T00:00:00Z");
        when(service.summary(TENANT_ID, USER_ID)).thenReturn(Mono.just(
                new UsageQueryService.QuotaSummary(null, List.of(), List.of())));
        when(entitlementsService.entitlements(TENANT_ID, USER_ID)).thenReturn(Mono.just(
                new QuotaEntitlementsService.Entitlements(
                        Instant.parse("2026-08-02T00:00:00Z"),
                        new QuotaEntitlementsService.GiftQuota(
                                false, "not_implemented", null, null, null, null),
                        List.of(new QuotaEntitlementsService.PackageEntitlements(
                                UUID.fromString("81000000-0000-4000-8000-000000000013"),
                                "IMAGE_BASIC", "图片基础版", "ACTIVE",
                                "IMAGE", "IMAGE", 48, 0, 3, 45, 20,
                                Instant.parse("2026-09-02T00:00:00Z"), null,
                                List.of(new QuotaEntitlementsService.ModelEntitlement(
                                        "image-basic", "IMAGE", "IMAGE", "IMAGE", "MODEL",
                                        48L, 0L, 3L, 45L,
                                        new QuotaEntitlementsService.PerCall(
                                                "IMAGE", 1L, "ONE_IMAGE_PER_REQUEST",
                                                new QuotaEntitlementsService.ReferencePrice(
                                                        "CNY", "CENT_PER_UNIT", "2K", "RATE_CARD",
                                                        89L, 1L, 89L,
                                                        null, null, null, null, null)))))))));
        when(service.ledger(eq(TENANT_ID), eq(USER_ID), any(), any(), any()))
                .thenReturn(Mono.just(cast(empty)));
        when(service.usage(eq(TENANT_ID), eq(USER_ID), any(), any(), any(), any(), eq(from), eq(to)))
                .thenReturn(Mono.just(cast(new UsageQueryService.PageResult<>(
                        List.of(usageEntryWithCharge(), usageEntryWithoutCharge()), 2, 0, 20))));
        when(service.usage(eq(TENANT_ID), eq(USER_ID), any(), any(), any(), any()))
                .thenReturn(Mono.just(cast(new UsageQueryService.PageResult<>(
                        List.of(usageEntryWithCharge(), usageEntryWithoutCharge()), 2, 0, 20))));
        when(service.metrics(TENANT_ID, USER_ID))
                .thenReturn(Mono.just(new UsageQueryService.UsageMetrics(2, 900)));
        when(service.adminUsage(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(cast(empty)));
        when(service.adminLedger(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(cast(empty)));
        when(service.adminReservations(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(Mono.just(cast(empty)));
        when(service.providerPrices(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(cast(empty)));
        UsageAnalyticsService.AnalyticsRange range = new UsageAnalyticsService.AnalyticsRange(
                7, "Asia/Shanghai", LocalDate.parse("2026-07-27"),
                LocalDate.parse("2026-08-02"));
        UsageAnalyticsService.UsageSummary summary = new UsageAnalyticsService.UsageSummary(
                2, 1, 1, 30, 4, 80, 12);
        when(analyticsService.userAnalytics(TENANT_ID, USER_ID, 7)).thenReturn(Mono.just(
                new UsageAnalyticsService.UsageAnalytics(range, summary, List.of(), List.of())));
        when(analyticsService.adminAnalytics(7)).thenReturn(Mono.just(
                new UsageAnalyticsService.GatewayAdminAnalytics(
                        range, summary, List.of(), List.of(), List.of())));

        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(USER_ID.toString()).claim("tenant_id", TENANT_ID.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        WebTestClient client = WebTestClient.bindToController(
                        new UsageQueryController(service, analyticsService, entitlementsService))
                .argumentResolvers(configurer -> configurer.addCustomResolver(jwtResolver(jwt)))
                .build();

        client.get().uri("/console/gateway/quota/summary").exchange().expectStatus().isOk();
        client.get().uri("/console/gateway/quota/entitlements").exchange()
                .expectStatus().isOk()
                // 额度是余额类数据：客户端与任何中间层都不得缓存它。
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.giftQuota.implemented").isEqualTo(false)
                .jsonPath("$.giftQuota.reason").isEqualTo("not_implemented")
                // 字段名就是给客户端的契约：改名等于破坏客户端，因此在这里逐条钉住。
                .jsonPath("$.packages[0].packageCode").isEqualTo("IMAGE_BASIC")
                .jsonPath("$.packages[0].usageDimension").isEqualTo("IMAGE")
                .jsonPath("$.packages[0].quotaUnit").isEqualTo("IMAGE")
                .jsonPath("$.packages[0].remainingQuota").isEqualTo(45)
                .jsonPath("$.packages[0].models[0].modelCode").isEqualTo("image-basic")
                .jsonPath("$.packages[0].models[0].category").isEqualTo("IMAGE")
                .jsonPath("$.packages[0].models[0].quotaScope").isEqualTo("MODEL")
                .jsonPath("$.packages[0].models[0].perCall.units").isEqualTo(1)
                .jsonPath("$.packages[0].models[0].perCall.basis").isEqualTo("ONE_IMAGE_PER_REQUEST")
                .jsonPath("$.packages[0].models[0].perCall.referencePrice.unit")
                .isEqualTo("CENT_PER_UNIT")
                .jsonPath("$.packages[0].models[0].perCall.referencePrice.unitPrice").isEqualTo(89);
        client.get().uri("/console/gateway/quota/ledger?modelCode=text-pro&entryType=SETTLE")
                .exchange().expectStatus().isOk();
        client.get().uri("/console/gateway/usage?modelCode=text-pro&outcome=SUCCEEDED&requestId=req-1"
                        + "&from=2026-08-01T00:00:00Z&to=2026-08-08T00:00:00Z")
                .exchange().expectStatus().isOk()
                .expectBody()
                // 分页计数与既有字段不变：新增的扣量字段不能改变列表语义。
                .jsonPath("$.total").isEqualTo(2)
                .jsonPath("$.items[0].requestId").isEqualTo("req-1")
                .jsonPath("$.items[0].usageUnits").isEqualTo(100)
                .jsonPath("$.items[0].outcome").isEqualTo("SUCCEEDED")
                .jsonPath("$.items[0].quotaCharge.status").isEqualTo("SETTLED")
                .jsonPath("$.items[0].quotaCharge.quotaUnit").isEqualTo("CREDIT")
                .jsonPath("$.items[0].quotaCharge.packageChargedUnits").isEqualTo(60)
                .jsonPath("$.items[0].quotaCharge.modelChargedUnits").isEqualTo(60)
                .jsonPath("$.items[0].quotaCharge.walletChargedAmount").isEqualTo(2)
                .jsonPath("$.items[0].quotaCharge.settledAt").exists()
                // 结算状态与调用结果是两个字段，各自保留。
                .jsonPath("$.items[1].outcome").isEqualTo("FAILED")
                .jsonPath("$.items[1].quotaCharge.status").isEqualTo("RESERVED")
                .jsonPath("$.items[2]").doesNotExist();
        // JsonPath 读不出"键存在但值为 null"，因此"显式 null"这一条必须看原始 JSON：
        // 未结算时三个实扣字段的值是 null，但键不能消失——键消失会被客户端读成
        // "旧服务，未知"，与"确认尚未结算"是两件事。
        client.get().uri("/console/gateway/usage?requestId=req-1"
                        + "&from=2026-08-01T00:00:00Z&to=2026-08-08T00:00:00Z")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).value(rawBody -> {
                    assertThat(rawBody).contains("\"packageChargedUnits\":null");
                    assertThat(rawBody).contains("\"modelChargedUnits\":null");
                    assertThat(rawBody).contains("\"walletChargedAmount\":null");
                    // 数字不得被序列化成字符串。
                    assertThat(rawBody).doesNotContain("\"walletChargedAmount\":\"2\"");
                });
        client.get().uri("/console/gateway/usage/metrics").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sampleCount").isEqualTo(2)
                .jsonPath("$.platformAddedP95Ms").isEqualTo(900)
                .jsonPath("$.requestDurationP95Ms").doesNotExist();
        client.get().uri("/console/gateway/usage/analytics?days=7").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.range.days").isEqualTo(7)
                .jsonPath("$.range.timezone").isEqualTo("Asia/Shanghai")
                .jsonPath("$.summary.periodTokens").isEqualTo(80);
        client.get().uri("/console/gateway/admin/analytics?days=7").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.summary.todayFailedRequests").isEqualTo(1);
        client.get().uri("/console/gateway/admin/usage?userId=" + USER_ID
                        + "&sort=totalTokens&direction=asc")
                .exchange().expectStatus().isOk();
        client.get().uri("/console/gateway/admin/quota-ledger?userId=" + USER_ID
                        + "&modelCode=text-pro&entryType=SETTLE&sort=balanceAfter&direction=desc")
                .exchange().expectStatus().isOk();
        client.get().uri("/console/gateway/admin/reservations?status=RESERVED&anomalyOnly=true"
                        + "&sort=expiresAt&direction=asc")
                .exchange().expectStatus().isOk();
        client.get().uri("/console/gateway/admin/provider-prices?providerCode=bailian"
                        + "&sort=currency&direction=desc")
                .exchange().expectStatus().isOk();

        verify(service).summary(TENANT_ID, USER_ID);
        verify(entitlementsService).entitlements(TENANT_ID, USER_ID);
        verify(service).ledger(TENANT_ID, USER_ID,
                new UsageQueryService.PageRequest(0, 20), "text-pro", "SETTLE");
        verify(service).usage(TENANT_ID, USER_ID,
                new UsageQueryService.PageRequest(0, 20), "text-pro", "SUCCEEDED", "req-1",
                from, to);
        verify(service).metrics(TENANT_ID, USER_ID);
        verify(analyticsService).userAnalytics(TENANT_ID, USER_ID, 7);
        verify(analyticsService).adminAnalytics(7);
        verify(service).adminUsage(new UsageQueryService.PageRequest(0, 20),
                USER_ID, null, null, null, "totalTokens", "asc");
        verify(service).adminLedger(new UsageQueryService.PageRequest(0, 20),
                USER_ID, "text-pro", "SETTLE", "balanceAfter", "desc");
        verify(service).adminReservations(new UsageQueryService.PageRequest(0, 20),
                null, "RESERVED", null, true, "expiresAt", "asc");
        verify(service).providerPrices(new UsageQueryService.PageRequest(0, 20),
                "bailian", null, "currency", "desc");
    }

    @SuppressWarnings("unchecked")
    private static <T> UsageQueryService.PageResult<T> cast(UsageQueryService.PageResult<?> page) {
        return (UsageQueryService.PageResult<T>) page;
    }

    /** 一条已结算的用量记录：套餐与独立模型池各扣 60，另有 2 分钱包扣款。 */
    private static UsageQueryService.UsageEntry usageEntryWithCharge() {
        return usageEntry("req-1", "SUCCEEDED", new UsageQueryService.QuotaCharge(
                "SETTLED", "CREDIT", 60L, 60L, 2L, Instant.parse("2026-08-02T10:00:02Z")));
    }

    /** 一条仅预占的用量记录：扣量字段全部为 null，而调用结果仍是 SUCCEEDED 之外的失败。 */
    private static UsageQueryService.UsageEntry usageEntryWithoutCharge() {
        return usageEntry("req-2", "FAILED", new UsageQueryService.QuotaCharge(
                "RESERVED", "CREDIT", null, null, null, null));
    }

    private static UsageQueryService.UsageEntry usageEntry(
            String requestId, String outcome, UsageQueryService.QuotaCharge quotaCharge) {
        return new UsageQueryService.UsageEntry(
                UUID.randomUUID(), requestId, UUID.randomUUID(), TENANT_ID, USER_ID,
                null, "example", null, "text-pro",
                "bailian", "bailian-primary", "qwen-plus", 70L, 30L, 100L,
                "TOKEN", 100L, false, true, 120L, 1500L, 200, outcome,
                null, null,
                // T5/T3 的诊断字段：这条用例关心的是 JSON 里 null 与"键缺失"的区别，
                // 因此这里刻意给出一组**有值**的诊断字段，顺带钉住它们也参与序列化。
                "completed", "stop", 8_192L, "package", 1,
                Instant.parse("2026-08-02T10:00:00Z"), List.of(), quotaCharge);
    }

    private static HandlerMethodArgumentResolver jwtResolver(Jwt jwt) {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return Jwt.class.equals(parameter.getParameterType());
            }

            @Override
            public Mono<Object> resolveArgument(
                    MethodParameter parameter, BindingContext bindingContext,
                    ServerWebExchange exchange) {
                return Mono.just(jwt);
            }
        };
    }
}
