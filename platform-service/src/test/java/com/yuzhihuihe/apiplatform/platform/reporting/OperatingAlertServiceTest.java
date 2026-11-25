package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperatingAlertServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");

    @Test
    void opensLowMarginAndAbnormalCostAlertsOnlyOncePerOpenFingerprint() {
        MemoryStore store = new MemoryStore();
        OperatingAlertService service = new OperatingAlertService(store, () -> NOW);
        UUID fact = UUID.randomUUID();

        service.evaluatePackageMargin(UUID.randomUUID(), new BigDecimal("100"),
                new BigDecimal("75"), List.of(fact));
        service.evaluateProviderCost("qwen-plus", "bailian", new BigDecimal("31"),
                new BigDecimal("10"), false, List.of(fact));
        service.evaluateProviderCost("qwen-plus", "bailian", new BigDecimal("31"),
                new BigDecimal("10"), false, List.of(fact));

        assertThat(store.alerts).extracting(OperatingAlertService.Alert::type)
                .containsExactly("LOW_MARGIN_PACKAGE", "ABNORMAL_PROVIDER_COST");
    }

    @Test
    void missingPriceAlertsAndAdminTransitionsAreAudited() {
        MemoryStore store = new MemoryStore();
        OperatingAlertService service = new OperatingAlertService(store, () -> NOW);
        service.evaluateProviderCost("deepseek-chat", "deepseek", BigDecimal.ZERO,
                BigDecimal.ZERO, true, List.of(UUID.randomUUID()));
        UUID alertId = store.alerts.get(0).id();
        UUID admin = UUID.randomUUID();

        service.acknowledge(alertId, admin);
        service.resolve(alertId, admin);

        assertThat(store.audit).containsExactly("ACK:" + alertId, "RESOLVE:" + alertId);
    }

    /**
     * 监控程序作出的解决必须说明它为何关闭该告警，而人的解决必须
     * 什么都不说：这两个原因值描述的是监控程序自身的条件，因此
     * 把其中之一归给运维只会是控制台随后要渲染的猜测。
     */
    @Test
    void monitorResolutionsCarryTheirReasonAndOperatorResolutionsCarryNone() {
        MemoryStore store = new MemoryStore();
        OperatingAlertService service = new OperatingAlertService(store, () -> NOW);
        UUID versionId = UUID.randomUUID();
        service.evaluatePublishedPlanMargin(
                versionId, "STARTER", new BigDecimal("0.20"), new BigDecimal("0.30"), NOW);
        UUID alertId = store.alerts.get(0).id();

        service.resolvePublishedPlanMargin(
                versionId, OperatingAlertService.RESOLUTION_RECOVERED, NOW);

        assertThat(store.resolutions).containsExactly(alertId + ":RECOVERED");

        service.resolve(alertId, UUID.randomUUID());

        assertThat(store.resolutions)
                .containsExactly(alertId + ":RECOVERED", alertId + ":null");
        assertThatThrownBy(() -> service.resolvePublishedPlanMargin(versionId, "GUESSED", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid_alert_resolution_reason");
    }

    @Test
    void firstMissingPriceAlertsWithoutRequestCostOrP95History() {
        MemoryStore store = new MemoryStore();
        OperatingAlertService service = new OperatingAlertService(store, () -> NOW);

        service.evaluateProviderCost("qwen-vl-plus", "bailian", null, null,
                true, List.of(UUID.randomUUID()));

        assertThat(store.alerts).singleElement().satisfies(alert -> {
            assertThat(alert.type()).isEqualTo("ABNORMAL_PROVIDER_COST");
            assertThat(alert.threshold()).isZero();
            assertThat(alert.observed()).isZero();
        });
    }

    static final class MemoryStore implements OperatingAlertService.Store {
        final List<OperatingAlertService.Alert> alerts = new ArrayList<>();
        final List<String> audit = new ArrayList<>();
        /** 服务请求过的每一次解决，以便它传入的原因可被观测。 */
        final List<String> resolutions = new ArrayList<>();
        @Override public boolean open(OperatingAlertService.Alert value) {
            if (alerts.stream().anyMatch(a -> a.fingerprint().equals(value.fingerprint())
                    && !"RESOLVED".equals(a.status()))) return false;
            alerts.add(value); return true;
        }
        @Override public OperatingAlertService.TransitionResult transition(
                UUID id, String status, UUID actor, Instant at, String resolutionReason) {
            if ("RESOLVED".equals(status)) {
                resolutions.add(id + ":" + resolutionReason);
            }
            return OperatingAlertService.TransitionResult.UPDATED;
        }
        @Override public void audit(UUID id, UUID actor, String action, Instant at) { audit.add(action + ":" + id); }
        @Override public List<UUID> openLowMarginPackageVersions() {
            return alerts.stream()
                    .filter(alert -> alert.fingerprint().startsWith("package-margin:"))
                    .filter(alert -> !"RESOLVED".equals(alert.status()))
                    .map(alert -> UUID.fromString(
                            alert.fingerprint().substring("package-margin:".length())))
                    .toList();
        }
        @Override public java.util.Optional<UUID> openLowMarginPackageAlert(UUID packageVersionId) {
            return alerts.stream()
                    .filter(alert -> alert.fingerprint()
                            .equals("package-margin:" + packageVersionId))
                    .filter(alert -> !"RESOLVED".equals(alert.status()))
                    .map(OperatingAlertService.Alert::id)
                    .findFirst();
        }
    }
    @Test
    void repeatedFingerprintDoesNotOverwriteFirstEvidence() {
        assertThat(OperatingAlertRepository.OPEN_ALERT_SQL)
                .doesNotContain("evidence=EXCLUDED.evidence");
    }
}
