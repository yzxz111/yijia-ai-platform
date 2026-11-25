package com.yuzhihuihe.apiplatform.gateway.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * 从 V52 记录结算缺口的那些列中，读取结算未能收回的部分。
 *
 * <p>{@code wallet_deficit_amount} 是客户余额未能覆盖的零售金额，单位为分；
 * {@code wallet_deficit_tokens} 则是其套餐容量未能覆盖的套餐单位数。两者都由结算自身在
 * 按预留所持有的数量计费、且无法收回其余部分时写入，因此把它们相加就是平台吸收的金额
 * —— 这是对已发生事实的记录，绝不是估算。
 *
 * <p>时间窗口是与缺口发生的那一刻相比较的，即结算时间（{@code settled_at}），对于因
 * 某种原因没有结算时间戳的行则以 {@code created_at} 作为回退。合计覆盖整个窗口；明细项
 * 是最新的 {@value #MAX_ITEMS} 条，按从新到旧排列，因此即使行数很大，调用方也总能看到
 * 它所请求窗口的形态。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class SettlementDeficitRepository {

    /** 一次响应携带的最多明细条数。合计永远不会被截断。 */
    static final int MAX_ITEMS = 200;

    /**
     * 一个时间窗口内的缺口，带上了 reservation 的别名限定：明细查询会连接
     * {@code package_instance}，而后者有自己的 {@code created_at}，因此不加限定的
     * 引用会产生歧义。
     */
    private static final String WINDOW =
            "(reservation.wallet_deficit_amount > 0 OR reservation.wallet_deficit_tokens > 0)"
                    + " AND COALESCE(reservation.settled_at, reservation.created_at) >= :from"
                    + " AND COALESCE(reservation.settled_at, reservation.created_at) < :to";

    private final DatabaseClient databaseClient;

    SettlementDeficitRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Mono<Totals> totals(Instant from, Instant to) {
        return databaseClient.sql("SELECT COALESCE(sum(reservation.wallet_deficit_amount), 0)"
                        + " AS total_amount,"
                        + " COALESCE(sum(reservation.wallet_deficit_tokens), 0) AS total_tokens,"
                        + " count(*) AS total_count"
                        + " FROM gateway.quota_reservation reservation WHERE " + WINDOW)
                .bind("from", from)
                .bind("to", to)
                .map((row, metadata) -> new Totals(
                        amount(row.get("total_amount", BigDecimal.class)),
                        amount(row.get("total_tokens", BigDecimal.class)),
                        row.get("total_count", Long.class)))
                .one()
                .defaultIfEmpty(new Totals(0L, 0L, 0L));
    }

    Mono<List<Item>> items(Instant from, Instant to) {
        return databaseClient.sql("SELECT reservation.id AS reservation_id,"
                        + " reservation.wallet_deficit_amount AS amount,"
                        + " reservation.wallet_deficit_tokens AS tokens,"
                        + " COALESCE(reservation.settled_at, reservation.created_at) AS occurred_at,"
                        + " package.package_code AS plan_code,"
                        + " reservation.user_id AS user_id"
                        + " FROM gateway.quota_reservation reservation"
                        + " JOIN gateway.package_instance package"
                        + " ON package.id = reservation.package_instance_id"
                        + " WHERE " + WINDOW
                        + " ORDER BY occurred_at DESC, reservation.id DESC"
                        + " LIMIT :limit")
                .bind("from", from)
                .bind("to", to)
                .bind("limit", MAX_ITEMS)
                .map((row, metadata) -> new Item(
                        row.get("reservation_id", UUID.class),
                        number(row.get("amount", Long.class)),
                        number(row.get("tokens", Long.class)),
                        row.get("occurred_at", Instant.class),
                        row.get("plan_code", String.class),
                        row.get("user_id", UUID.class)))
                .all()
                .collectList();
    }

    private static long amount(BigDecimal value) {
        return value == null ? 0L : value.longValue();
    }

    private static long number(Long value) {
        return value == null ? 0L : value;
    }

    /** 窗口内的合计。{@code count} 是其中每一行缺口，而不只是返回的明细项。 */
    record Totals(long totalAmount, long totalTokens, long count) { }

    record Item(
            UUID reservationId,
            long amount,
            long tokens,
            Instant occurredAt,
            String planCode,
            UUID userId) { }
}
