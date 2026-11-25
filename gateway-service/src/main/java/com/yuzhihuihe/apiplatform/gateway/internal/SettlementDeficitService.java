package com.yuzhihuihe.apiplatform.gateway.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * 平台不得不自行吸收多少：即结算记录为无法收回的部分之和。
 *
 * <p><b>这个数字是记录，不是估算。</b>它只来自
 * {@code gateway.quota_reservation.wallet_deficit_amount} 和 {@code wallet_deficit_tokens}，
 * 由结算在客户余额或套餐容量不足以覆盖时写入（V52）。
 * 一笔被全额收回的记录在这两列上都为 0，因此不计入；这里也不会外推任何速率、价格或预测。
 *
 * <p>任一边界被省略时，时间窗默认为最近 30 天；{@code from} 为闭区间，{@code to} 为开区间。
 * 边界采用 ISO-8601：瞬时时间（{@code 2026-09-01T00:00:00Z}）、
 * 带偏移量的日期时间（{@code 2026-09-01T00:00:00+08:00}）或纯日期（按 UTC 午夜解读）。
 * 其他任何形式，或一个不向前推进的时间窗，都返回
 * {@code 400 invalid_time_window}。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class SettlementDeficitService {

    static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final SettlementDeficitRepository repository;
    private final Clock clock;

    @Autowired
    SettlementDeficitService(SettlementDeficitRepository repository) {
        this(repository, Clock.systemUTC());
    }

    SettlementDeficitService(SettlementDeficitRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    Mono<SettlementDeficitView> deficit(String rawFrom, String rawTo) {
        Instant now = Instant.now(clock);
        Instant to = rawTo == null || rawTo.isBlank() ? now : parse(rawTo);
        Instant from = rawFrom == null || rawFrom.isBlank() ? to.minus(DEFAULT_WINDOW) : parse(rawFrom);
        if (!from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_time_window");
        }
        return Mono.zip(repository.totals(from, to), repository.items(from, to))
                .map(sources -> new SettlementDeficitView(
                        sources.getT1().totalAmount(),
                        sources.getT1().totalTokens(),
                        sources.getT1().count(),
                        sources.getT1().count() > sources.getT2().size(),
                        sources.getT2().stream().map(SettlementDeficitView.Item::of).toList()));
    }

    private Instant parse(String value) {
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException notAnInstant) {
            // 带偏移量的日期时间表示的是同一时刻，只是按本地运维人员的思维习惯书写。
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException notAnOffsetDateTime) {
            // 纯日期按 UTC 午夜解读；上面那些带偏移量的形式才是精确的。
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException malformed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_time_window");
        }
    }

    /**
     * 该时间窗的合计值及其最近的明细项。当时间窗内的缺口数量超过返回的
     * {@value SettlementDeficitRepository#MAX_ITEMS} 个明细项时 {@code truncated} 为 true，
     * 这样在合计值保持完整的同时，调用方绝不会把一份有上限的列表误当作全部。
     */
    record SettlementDeficitView(
            long totalAmount,
            long totalTokens,
            long count,
            boolean truncated,
            List<Item> items) {

        record Item(
                UUID reservationId,
                long amount,
                long tokens,
                Instant occurredAt,
                String planCode,
                UUID userId) {

            static Item of(SettlementDeficitRepository.Item item) {
                return new Item(
                        item.reservationId(),
                        item.amount(),
                        item.tokens(),
                        item.occurredAt(),
                        item.planCode(),
                        item.userId());
            }
        }
    }
}
