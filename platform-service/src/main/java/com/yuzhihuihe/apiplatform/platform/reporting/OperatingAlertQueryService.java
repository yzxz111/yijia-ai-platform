package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 读取管理控制台渲染的运营告警分页。
 *
 * <p>在输出时会额外补充两个派生字段，因为控制台必须能够 区分自愈的告警与人手动关闭的告警，并说明原因：
 *
 * <ul>
 *   <li>{@code resolutionOrigin} —— 当定时对账关闭了该告警时为 {@code MACHINE} （审计为 {@code
 *       OPERATING_ALERT_AUTO_RESOLVE}，操作者为 NULL）， 否则为 {@code OPERATOR}，告警未解决时为 null。
 *   <li>{@code resolutionReason} —— 解决方写入该行的原因 （监控为 {@code RECOVERED} / {@code
 *       NO_LONGER_PUBLISHED}，人为解决时为 null， 因为这两种机器原因并不能描述人的判断），告警 未解决时为 null。
 * </ul>
 *
 * <p>两者都不是凭空编造的：都是从解决方已经写入的内容中读取的。
 */
@Service
public class OperatingAlertQueryService {

    private static final String RESOLVED = "RESOLVED";
    static final String MACHINE = "MACHINE";
    static final String OPERATOR = "OPERATOR";

    private final OperatingAlertQueryRepository repository;

    public OperatingAlertQueryService(OperatingAlertQueryRepository repository) {
        this.repository = repository;
    }

    AlertPage findByStatus(String status, int size, int offset) {
        OperatingAlertQueryRepository.AlertPage result =
                repository.findByStatus(status, size, offset);
        List<Alert> items =
                result.items().stream()
                        .map(
                                row ->
                                        new Alert(
                                                row.id(),
                                                row.type(),
                                                row.status(),
                                                row.logicalModel(),
                                                row.provider(),
                                                row.threshold(),
                                                row.observed(),
                                                row.currency(),
                                                row.firstObservedAt(),
                                                row.lastObservedAt(),
                                                origin(row),
                                                resolutionReason(row)))
                        .toList();
        return new AlertPage(items, result.total());
    }

    private static String origin(OperatingAlertQueryRepository.AlertRow row) {
        if (!RESOLVED.equals(row.status())) {
            return null;
        }
        return row.machineResolved() ? MACHINE : OPERATOR;
    }

    private static String resolutionReason(OperatingAlertQueryRepository.AlertRow row) {
        return RESOLVED.equals(row.status()) ? row.resolutionReason() : null;
    }

    record AlertPage(List<Alert> items, long total) {}

    record Alert(
            UUID id,
            String type,
            String status,
            String logicalModel,
            String provider,
            BigDecimal threshold,
            BigDecimal observed,
            String currency,
            Instant firstObservedAt,
            Instant lastObservedAt,
            String resolutionOrigin,
            String resolutionReason) {}
}
