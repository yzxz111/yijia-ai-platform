package com.yuzhihuihe.apiplatform.gateway.routing;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = AdminGatewayController.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class GatewayAdminExceptionHandler {

    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("model_referenced_by_package", "该模型已被套餐版本引用，不能删除。"),
            Map.entry("model_referenced_by_routing", "该模型正被组合模型路由引用，不能删除。"),
            Map.entry("model_has_usage_history", "该模型已有调用记录，为保留审计数据不能删除。"),
            Map.entry("package_reference_check_unavailable", "暂时无法核验套餐引用，请稍后重试。"),
            Map.entry("model_not_found", "模型不存在或已被删除。"),
            Map.entry("invalid_model_status", "模型状态筛选无效。"),
            // 能力守卫：把「配好了却跑不通」提前到保存那一刻，并说清怎么改。
            //
            // 这段文案必须跟着能力矩阵走。它此前写着「TIANYIYUN 只有生图与
            // 视频适配器」——在 TianYiYunChatAdapter 落地之后那句话不再为真，
            // 而一条会误导运营去改错的提示，比没有提示更糟。音频能力同样要
            // 出现在这里：V93 之后音频别名是常见配置，而「为什么挂不上」
            // 必须能在这句话里找到答案。
            Map.entry("provider_capability_unavailable",
                    "该服务商类型没有能服务这个模型的能力：对话/读图模型必须挂在"
                            + "BAILIAN 或 TIANYIYUN 类型下；生图与视频需要"
                            + "TIANYIYUN/BAILIAN/MOCK；音频（转写、合成、换口型）"
                            + "目前只有 BAILIAN。请改用对应类型的服务商。"),
            Map.entry("channel_credential_required", "该渠道还没有配置 API 密钥，请先填写密钥。"),
            Map.entry("channel_probe_unavailable", "暂时无法探测上游模型清单，请稍后重试。"),
            Map.entry("credential_decryption_failed",
                    "渠道密钥无法解密（加密密钥可能已轮换），请重新填写该渠道的 API 密钥。"),
            Map.entry("invalid_channel_base_url", "渠道的接口地址无效，请检查后重试。"),
            Map.entry("provider_not_found", "服务商不存在或已被删除。"),
            Map.entry("provider_has_channels",
                    "该服务商名下还有渠道，不能直接删除。请在渠道列表里先删除这些渠道，"
                            + "或确认要连同渠道与别名映射一起删除后再试。"),
            Map.entry("provider_has_usage_history",
                    "该服务商已有调用、成本或生成任务记录，为保留计费与审计数据不能删除，"
                            + "请改为「停用」。"));

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<AdminErrorResponse> handle(ResponseStatusException error) {
        String code = error.getReason() == null ? "gateway_admin_operation_failed" : error.getReason();
        String message = MESSAGES.getOrDefault(
                code,
                error.getStatusCode().is4xxClientError()
                        ? "请求未通过校验，请检查后重试。"
                        : "网关管理服务暂时不可用，请稍后重试。");
        return ResponseEntity.status(error.getStatusCode())
                .body(new AdminErrorResponse(code, message));
    }

    record AdminErrorResponse(String code, String message) {}
}
