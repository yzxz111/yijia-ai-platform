package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = PackageCatalogController.class)
public class PackageCatalogControllerAdvice {

    private static final Map<String, String> SAFE_MESSAGES = Map.ofEntries(
            Map.entry("invalid_package_version", "套餐版本参数无效，请检查售价、额度、有效期和限流配置。"),
            Map.entry("invalid_free_package_version", "免费领取套餐的售价必须为 0，并启用每位用户限领一次。"),
            Map.entry("invalid_model_permission", "模型权限无效，请至少选择一个模型并检查模型能力。"),
            Map.entry("token_margin_below_minimum", "套餐预计毛利率低于平台最低要求，请提高售价或降低额度。"),
            // 单位守卫。每条消息都必须讲清这一约定，因为列名
            // （"tokens_per_minute"）正是让视频套餐的秒数上限看起来
            // 像 token 速率的原因 —— V63 犯过这个错误，而 API 里对此一言未发。
            Map.entry("video_seconds_per_minute_out_of_range",
                    "该版本按秒计费：tokens_per_minute 表示「每分钟可生成的视频秒数」（SPM 上限，60 约等于实时，"
                            + "最大 86400），不是每分钟 token 数。填 100000 这类数字即表示把 token 档位的速率写进了"
                            + "秒维度的列；请改为 1-86400 之间的秒数。"),
            Map.entry("image_seconds_per_minute_out_of_range",
                    "该版本按秒计费：tokens_per_minute 表示「每分钟可生成的秒数上限」（最大 86400），"
                            + "不是每分钟 token 数；请改为 1-86400 之间的数值。"),
            Map.entry("text_tokens_per_minute_below_minimum",
                    "该版本包含 TEXT 能力：tokens_per_minute 是每分钟 token 速率，必须不小于 10000，"
                            + "否则一次对话就会被限流；请填写至少 10000 的 token 速率。"),
            Map.entry("package_plan_code_mismatch", "套餐代码无效，请重新选择套餐目录。"),
            Map.entry("invalid_admin_actor", "管理员身份已失效，请重新登录后重试。"),
            Map.entry("package_plan_not_found", "套餐不存在，请刷新页面后重试。"),
            Map.entry("package_plan_name_required", "套餐名称不能为空。"),
            Map.entry("package_plan_name_too_long", "套餐名称不能超过 100 个字符。"),
            Map.entry("package_plan_description_too_long", "套餐描述不能超过 500 个字符。"),
            Map.entry("invalid_package_plan_kind", "套餐类型无效，请重新选择。"),
            Map.entry("invalid_package_plan_status", "套餐状态筛选无效。"),
            Map.entry("invalid_package_catalog_page", "套餐分页参数无效。"),
            Map.entry("invalid_package_catalog_query", "套餐搜索内容不能超过 100 个字符。"),
            Map.entry("active_free_package_exists", "当前已有可领取的免费套餐，请先下架后再创建或恢复。"),
            // 从控制台注册别名的计量维度（运维通过网关控制台添加的视频别名，
            // 在他们这样做之前在这里没有对应行）。
            Map.entry("invalid_model_dimension", "计量维度无效：只能是 token（积分）或按秒计费的视频/图片维度，"
                    + "且模型代码不能为空。"),
            Map.entry("invalid_model_resolution_tier",
                    "分辨率档位与计量维度不匹配：按秒计费的别名必须且只能选一个档位（480P/720P/1080P），"
                            + "积分（token）别名必须留空。"),
            Map.entry("invalid_model_unit_cost", "每秒成本不能为负数（单位：分/秒）。"),
            Map.entry("invalid_model_unit_price", "每秒零售价必须大于 0（单位：分/秒）。"),
            Map.entry("package_plan_status_conflict", "套餐当前状态不支持此操作，请刷新页面后重试。"));

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

    @ExceptionHandler(PackageMarginPolicy.MarginPolicyException.class)
    ResponseEntity<ErrorBody> handle(PackageMarginPolicy.MarginPolicyException exception) {
        String reason = exception.getMessage();
        String message = reason == null ? null : SAFE_MESSAGES.get(reason);
        if (message == null) {
            throw exception;
        }
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorBody(reason, message));
    }

    public record ErrorBody(String code, String message) {
    }
}
