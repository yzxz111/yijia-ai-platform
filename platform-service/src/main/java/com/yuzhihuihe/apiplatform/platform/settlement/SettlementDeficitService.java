package com.yuzhihuihe.apiplatform.platform.settlement;

import java.time.Instant;

import com.yuzhihuihe.apiplatform.platform.internalapi.GatewayInternalUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 把失败的结算垫付缺口读取转换为管理控制台可以据以行动的应答。
 *
 * <p>该端点是纯读取，因此没有操作者需要记录，也没有状态需要变更；唯一的
 * 决策是当网关无法应答时该怎么做。上报零会告诉管理员：平台没有
 * 吸收任何东西，而这与无法读取的垫付缺口所代表的含义正好相反，
 * 因此该失败会以 503 {@code gateway_settlement_deficit_unavailable} 呈现。
 */
@Service
public class SettlementDeficitService {

    private final GatewaySettlementDeficitClient deficitClient;

    public SettlementDeficitService(GatewaySettlementDeficitClient deficitClient) {
        this.deficitClient = deficitClient;
    }

    public GatewaySettlementDeficitClient.SettlementDeficit deficit(Instant from, Instant to) {
        try {
            return deficitClient.fetch(from, to);
        } catch (GatewayInternalUnavailableException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "gateway_settlement_deficit_unavailable", unavailable);
        }
    }
}
