package com.yuzhihuihe.apiplatform.platform.settlement;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 面向管理员的「平台承担了多少？」。
 *
 * <p>该路径位于 {@code /console/platform/admin/**} 之下，安全配置
 * 已将其限制为 {@code ROLE_ADMIN}；本控制器不自行添加任何授权，也
 * 不需要操作者，因为它既不写入也不审计任何内容。可选的 {@code from}/{@code to}
 * 时间点要么两者都缺省 —— 此时网关使用自己的默认窗口 —— 要么构成一个完整的、
 * 有顺序的、至多 366 天的窗口。只给一半、无法解析或顺序颠倒的区间会以 422 被拒绝，
 * 而不是被忽略：静默地放宽回默认窗口，将会回答一个与
 * 所提问题不同的问题，而这是关于钱的问题。
 */
@RestController
@RequestMapping("/console/platform/admin/operations")
public class SettlementDeficitController {

    private static final Duration MAXIMUM_RANGE = Duration.ofDays(366);

    private final SettlementDeficitService deficitService;

    public SettlementDeficitController(SettlementDeficitService deficitService) {
        this.deficitService = deficitService;
    }

    @GetMapping("/settlement-deficit")
    GatewaySettlementDeficitClient.SettlementDeficit settlementDeficit(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Range range = range(from, to);
        return deficitService.deficit(range.from(), range.to());
    }

    private static Range range(String from, String to) {
        if (isAbsent(from) && isAbsent(to)) {
            return new Range(null, null);
        }
        Instant start = instant(from);
        Instant end = instant(to);
        if (!end.isAfter(start)) {
            throw invalid("invalid_settlement_deficit_range");
        }
        if (Duration.between(start, end).compareTo(MAXIMUM_RANGE) > 0) {
            throw invalid("settlement_deficit_range_exceeds_366_days");
        }
        return new Range(start, end);
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("invalid_settlement_deficit_range");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid("invalid_settlement_deficit_range");
        }
    }

    private static boolean isAbsent(String value) {
        return value == null;
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private record Range(Instant from, Instant to) {
    }
}
