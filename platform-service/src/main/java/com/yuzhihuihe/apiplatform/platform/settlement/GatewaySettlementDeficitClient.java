package com.yuzhihuihe.apiplatform.platform.settlement;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.internalapi.GatewayInternalGet;
import com.yuzhihuihe.apiplatform.platform.internalapi.GatewayInternalUnavailableException;
import org.springframework.stereotype.Component;

/**
 * 读取平台因套餐已无可扣而不得不垫付的资金与 token——网关将其
 * 记录在 {@code gateway.quota_reservation.wallet_deficit_amount} 与
 * {@code wallet_deficit_tokens} 上。
 *
 * <p>这些是财务数字，因此读取失败绝不能看起来像「没有缺口」：
 * 管理员若依据一个捏造的零行事，就会停止追讨平台已经损失的资金。
 * 网关不可达、非 200、无法解析的响应体，以及 items 与其自身
 * count 不一致的响应体，都会抛出
 * {@link GatewayInternalUnavailableException}，由 console 上报为 503。空答复则不同：
 * 网关确实可以上报没有任何垫付，{@code count: 0} 加空列表仍是 200。
 *
 * <p>可选区间在此处以及在控制器中都会校验，与
 * {@code GatewayEconomicsHealthClient.scan} 保持一致：该端点绝不能转发一个反向的或
 * 开放式的窗口，让网关去猜所指的是什么。366 天上限与对账读取方所用的
 * 上限相同，因此没有任何单个请求可以要求无界扫描。
 */
@Component
public class GatewaySettlementDeficitClient {

    static final String PATH = "/internal/settlement-deficit";
    private static final Duration MAXIMUM_RANGE = Duration.ofDays(366);

    private final ObjectMapper objectMapper;
    private final GatewayInternalGet internalGet;

    public GatewaySettlementDeficitClient(ObjectMapper objectMapper, GatewayInternalGet internalGet) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.internalGet = Objects.requireNonNull(internalGet, "internalGet");
    }

    /**
     * @param from 窗口的起始（含），或 {@code null}（此时同时给出 {@code to}）
     *             以让网关使用它自己的默认窗口
     * @param to   窗口的结束（不含）
     * @throws IllegalArgumentException 当只给出一个边界、窗口反向，
     *                                  或跨度超过 366 天时
     */
    public SettlementDeficit fetch(Instant from, Instant to) {
        byte[] body = internalGet.get(PATH, query(from, to));
        SettlementDeficit deficit;
        try {
            deficit = objectMapper.readValue(body, SettlementDeficit.class);
        } catch (Exception exception) {
            throw new GatewayInternalUnavailableException(
                    "gateway_settlement_deficit_payload_invalid", exception);
        }
        if (!isWellFormed(deficit)) {
            throw new GatewayInternalUnavailableException("gateway_settlement_deficit_payload_invalid");
        }
        return deficit;
    }

    private static String query(Instant from, Instant to) {
        if (from == null && to == null) {
            return null;
        }
        if (from == null || to == null
                || !to.isAfter(from)
                || Duration.between(from, to).compareTo(MAXIMUM_RANGE) > 0) {
            throw new IllegalArgumentException("gateway_settlement_deficit_range_invalid");
        }
        return "from=" + encode(from.toString()) + "&to=" + encode(to.toString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 一条缺口记录就是钱，因此无法归属（没有 reservation id）或
     * 无法定时（没有 occurrence）的条目不是 console 可以展示的记录；而一个与
     * 列表不一致的 {@code count} 会让运维把一页被截断的数据读成整起事件。
     * plan code 保持可空，因为它来自一个旧版或已归档套餐可能不满足的连接。
     */
    private static boolean isWellFormed(SettlementDeficit deficit) {
        if (deficit == null
                || deficit.items() == null
                || deficit.totalAmount() < 0
                || deficit.totalTokens() < 0
                || deficit.count() < 0
                || !countMatchesItems(deficit)) {
            return false;
        }
        for (SettlementDeficit.Item item : deficit.items()) {
            if (item == null
                    || item.reservationId() == null
                    || item.userId() == null
                    || item.occurredAt() == null
                    || item.amount() < 0
                    || item.tokens() < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code count} 是总体数量，{@code items} 可能是它的一个有界切片。
     *
     * <p>一旦某个窗口内的缺口数超过其条目上限，被截断的答复就是网关的正常形态：
     * 总计保持完整，而列表是最近的一个切片，并由 {@code truncated}
     * 说明这一点。无条件要求两者相等会拒绝每一个这样的窗口，于是 console 恰恰对
     * 本页面存在的意义所在的事件显示「暂时取不到数据」——而背后是真实已垫付
     * 的资金。该标志使用装箱类型，因为省略它的网关并未做出任何承诺：
     * 缺失意味着「假定该列表就是全部」，这正是本项检查一直以来的严格解读。
     */
    private static boolean countMatchesItems(SettlementDeficit deficit) {
        long listed = deficit.items().size();
        if (Boolean.TRUE.equals(deficit.truncated())) {
            // 切片可以短于总体数量，绝不会更长。
            return listed <= deficit.count();
        }
        return listed == deficit.count();
    }

    /**
     * 明细行，从大到小是网关的选择；上面的合计是它的契约。
     *
     * <p>{@code truncated} 会透传给 console，以便其「明细范围」能够说明
     * 下方列表是全部总体还是仅最近的一个切片。它有意可空：
     * {@code null} 表示网关没有说明，console 将其渲染为「—」而不是声称
     * 「全部明细」（参见 web-console 的 {@code SettlementDeficit.truncated}）。
     */
    public record SettlementDeficit(
            long totalAmount,
            long totalTokens,
            long count,
            Boolean truncated,
            List<Item> items) {

        public record Item(
                UUID reservationId,
                long amount,
                long tokens,
                Instant occurredAt,
                String planCode,
                UUID userId) {
        }
    }
}
