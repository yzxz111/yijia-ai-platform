package com.yuzhihuihe.apiplatform.platform.settlement;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 为这个端点能给出的每一个非数据回答，把稳定的错误码放到响应上。
 *
 * <p>其中两个对管理员很重要。以 Spring 的默认错误文档到达的 503 无法与任何其它
 * 服务器故障区分开，而结算缺口正是这样一个数字：
 * 「读不出」绝不能被误认为「什么都没被吸收」。对无效区间的 422 必须把该区间
 * 指为问题所在，而不是看起来像一个下游故障。与同类 advice 一样，
 * 仅重新发出本控制器抛出的原因；
 * 其它一切原样再抛。
 */
@RestControllerAdvice(assignableTypes = SettlementDeficitController.class)
public class SettlementDeficitControllerAdvice {

    private static final Map<String, String> SAFE_MESSAGES = Map.of(
            "gateway_settlement_deficit_unavailable", "结算缺口数据暂时不可用，请稍后重试。",
            "invalid_settlement_deficit_range", "统计区间无效，请检查起止时间。",
            "settlement_deficit_range_exceeds_366_days", "统计区间不能超过 366 天。");

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorBody> handle(ResponseStatusException exception) {
        String reason = exception.getReason();
        String message = reason == null ? null : SAFE_MESSAGES.get(reason);
        if (message == null) {
            throw exception;
        }
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ErrorBody(reason, message));
    }

    public record ErrorBody(String code, String message) {
    }
}
