package com.yuzhihuihe.apiplatform.gateway.openai;

import java.util.Map;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.http.HttpHeaders;

import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class OpenAiErrorMapper {

    /** Retry hints are shared by every downstream capability, including pre-stream failures. */
    public static HttpHeaders retryHeaders(Throwable error) {
        return retryHeaders(error, Clock.systemUTC());
    }

    static HttpHeaders retryHeaders(Throwable error, Clock clock) {
        HttpHeaders headers = new HttpHeaders();
        ErrorDescriptor descriptor = descriptor(error);
        if (descriptor.status() != HttpStatus.TOO_MANY_REQUESTS) {
            return headers;
        }
        long seconds = 60;
        if ("daily_request_limit_exceeded".equals(descriptor.code())) {
            ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
            Duration remaining = Duration.between(now, now.toLocalDate().plusDays(1)
                    .atStartOfDay(now.getZone()));
            seconds = Math.max(1, remaining.toSeconds() + (remaining.getNano() > 0 ? 1 : 0));
        }
        if (error instanceof ResponseStatusException response) {
            String supplied = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (supplied != null && supplied.matches("[0-9]{1,9}")) {
                long parsed = Long.parseLong(supplied);
                if (parsed > 0) seconds = parsed;
            }
        }
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        headers.setCacheControl("no-store");
        return headers;
    }


    private static final Map<String, ErrorDescriptor> APPROVED_ERRORS = Map.ofEntries(
            entry("model_not_allowed", HttpStatus.FORBIDDEN,
                    "Model is not allowed", "permission_error"),
            entry("unsupported_file_type", HttpStatus.BAD_REQUEST,
                    "Unsupported file type", "invalid_request_error"),
            entry("invalid_file_content", HttpStatus.BAD_REQUEST,
                    "Invalid file content", "invalid_request_error"),
            entry("file_too_large", HttpStatus.PAYLOAD_TOO_LARGE,
                    "File is too large", "invalid_request_error"),
            entry("file_complexity_limit", HttpStatus.UNPROCESSABLE_ENTITY,
                    "File exceeds processing limits", "invalid_request_error"),
            entry("encrypted_file_not_supported", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Encrypted files are not supported", "invalid_request_error"),
            entry("document_conversion_failed", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Document conversion failed", "invalid_request_error"),
            entry("file_not_found", HttpStatus.NOT_FOUND,
                    "File not found", "invalid_request_error"),
            // 未绑定套餐实例的 key 没有套餐配额可上报（它可能是一把仅有钱包的 key）。
            // 它自己的错误码使它区别于未知路由的 404，也区别于下面账户级的
            // 「无可用套餐」quota_error。
            entry("package_not_found", HttpStatus.NOT_FOUND,
                    "No package is bound to this API key", "quota_error"),
            entry("skill_not_found", HttpStatus.NOT_FOUND,
                    "Skill not found", "invalid_request_error"),
            entry("skill_icon_not_found", HttpStatus.NOT_FOUND,
                    "Skill icon not found", "invalid_request_error"),
            entry("invalid_skill_name", HttpStatus.BAD_REQUEST,
                    "Invalid skill name", "invalid_request_error"),
            entry("invalid_skill_metadata", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Skill metadata is invalid", "invalid_request_error"),
            entry("invalid_pagination", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Skill pagination is invalid", "invalid_request_error"),
            entry("skill_file_too_large", HttpStatus.UNPROCESSABLE_ENTITY,
                    "A skill file exceeds the size limit", "invalid_request_error"),
            entry("skill_too_large", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Skill exceeds the total size limit", "invalid_request_error"),
            entry("skill_too_many_files", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Skill contains too many files", "invalid_request_error"),
            entry("skill_package_too_large", HttpStatus.PAYLOAD_TOO_LARGE,
                    "Skill package is too large", "invalid_request_error"),
            entry("skill_package_rejected", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Skill package failed malware scanning", "invalid_request_error"),
            entry("skill_scan_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "Skill malware scanner is unavailable", "server_error"),
            entry("skill_storage_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "Skill storage is unavailable", "server_error"),
            entry("skill_unpublished", HttpStatus.CONFLICT,
                    "Skill is no longer published", "invalid_request_error"),
            // 专家名册 / kind 契约（设计 2026-09-14 3.1/3.2）。
            entry("invalid_skill_kind", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Invalid skill kind", "invalid_request_error"),
            entry("invalid_expert_manifest", HttpStatus.UNPROCESSABLE_ENTITY,
                    "The expert manifest is invalid", "invalid_request_error"),
            entry("expert_kind_mismatch", HttpStatus.UNPROCESSABLE_ENTITY,
                    "The expert manifest does not match the skill kind", "invalid_request_error"),
            entry("invalid_skill_member", HttpStatus.UNPROCESSABLE_ENTITY,
                    "The skill roster is invalid", "invalid_request_error"),
            entry("skill_member_not_found", HttpStatus.NOT_FOUND,
                    "Skill member not found", "invalid_request_error"),
            entry("skill_illustration_not_found", HttpStatus.NOT_FOUND,
                    "Skill illustration not found", "invalid_request_error"),
            entry("skill_illustration_too_large", HttpStatus.PAYLOAD_TOO_LARGE,
                    "Skill illustration is too large", "invalid_request_error"),
            entry("skill_example_image_not_found", HttpStatus.NOT_FOUND,
                    "Skill example image not found", "invalid_request_error"),
            entry("skill_example_image_too_large", HttpStatus.PAYLOAD_TOO_LARGE,
                    "Skill example image is too large", "invalid_request_error"),
            // 面向客户端的类目词表。桌面端从这些
            // 路由渲染其「精选场景」卡片，因此格式错误的 kind 与缺失的封面
            // 都必须带着各自的错误码到达客户端，而不是落到通用的 invalid_request 兜底。
            entry("invalid_skill_category", HttpStatus.BAD_REQUEST,
                    "Invalid skill category", "invalid_request_error"),
            entry("skill_category_image_not_found", HttpStatus.NOT_FOUND,
                    "Skill category cover not found", "invalid_request_error"),
            // YCPS 包摄取 / 版本生命周期，同样可从客户端界面触达。
            entry("skill_version_not_found", HttpStatus.NOT_FOUND,
                    "Skill version not found", "invalid_request_error"),
            entry("skill_version_conflict", HttpStatus.CONFLICT,
                    "Skill version already exists", "invalid_request_error"),
            entry("skill_version_sealed", HttpStatus.CONFLICT,
                    "Skill version is sealed and cannot be replaced", "invalid_request_error"),
            entry("skill_concurrent_modification", HttpStatus.CONFLICT,
                    "Skill was modified concurrently", "invalid_request_error"),
            entry("skill_package_mismatch", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Skill package does not match the upload target", "invalid_request_error"),
            entry("skill_zip_invalid", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Skill package is not a valid archive", "invalid_request_error"),
            entry("ycps_package_filename_required", HttpStatus.UNPROCESSABLE_ENTITY,
                    "A .ycps.zip package filename is required", "invalid_request_error"),
            entry("ycps_package_too_large", HttpStatus.PAYLOAD_TOO_LARGE,
                    "YCPS package exceeds the size limit for its kind", "invalid_request_error"),
            entry("ycps_dependency_unresolved", HttpStatus.UNPROCESSABLE_ENTITY,
                    "YCPS package references an unpublished or mismatched dependency", "invalid_request_error"),
            // 精确依赖的下载闭包。
            entry("invalid_exact_dependency_request", HttpStatus.BAD_REQUEST,
                    "Invalid exact-dependency request", "invalid_request_error"),
            entry("exact_dependency_not_in_parent", HttpStatus.CONFLICT,
                    "Exact dependency is not part of the parent package closure", "invalid_request_error"),
            entry("exact_dependency_artifact_unavailable", HttpStatus.CONFLICT,
                    "Exact dependency artifact is unavailable", "invalid_request_error"),
            entry("parent_download_not_active", HttpStatus.CONFLICT,
                    "Parent download session is no longer active", "invalid_request_error"),
            entry("client_version_too_low", HttpStatus.UPGRADE_REQUIRED,
                    "Client version is too low for this skill", "invalid_request_error"),
            entry("download_not_found", HttpStatus.NOT_FOUND,
                    "Download session not found", "invalid_request_error"),
            entry("download_session_expired", HttpStatus.GONE,
                    "Download session has expired", "invalid_request_error"),
            entry("invalid_download_request", HttpStatus.BAD_REQUEST,
                    "Invalid download request", "invalid_request_error"),
            entry("invalid_range", HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                    "Requested byte range is not satisfiable", "invalid_request_error"),
            entry("skill_package_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "Skill package is unavailable", "server_error"),
            entry("skill_download_idempotency_mismatch", HttpStatus.CONFLICT,
                    "Idempotency key belongs to another skill", "invalid_request_error"),
            entry("rate_limited", HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded", "rate_limit_error"),
            // 这是套餐售卖的上限，而不是突发限流：与分钟限流器使用同一个 rate_limit_error
            // 信封，但带有自己的错误码，使客户端能区分「太快了」与「今天的额度已用完」。
            entry("daily_request_limit_exceeded", HttpStatus.TOO_MANY_REQUESTS,
                    "Daily request limit exceeded", "rate_limit_error"),
            // 另一项套餐售卖上限：每分钟消耗的积分（积分/分钟）。它自己的错误码把
            // 「套餐烧完了这一分钟的积分」与「请求过多」以及「今天已用完」区分开。
            entry("tokens_per_minute_exceeded", HttpStatus.TOO_MANY_REQUESTS,
                    "Credits per minute exceeded", "rate_limit_error"),
            // 同一个售卖速率的秒数方向：视频套餐的 tokens_per_minute 是每分钟生成秒数（SPM），
            // 因此这是与上面的积分速率不同的额度，并带有自己的错误码。否则，一次被拒绝的生成若让
            // 客户端读到 tokens_per_minute_exceeded，它会以为自己的聊天积分用完了。
            entry("seconds_per_minute_exceeded", HttpStatus.TOO_MANY_REQUESTS,
                    "Generation seconds per minute exceeded", "rate_limit_error"),
            entry("direct_upload_disabled", HttpStatus.NOT_IMPLEMENTED,
                    "Direct upload is disabled", "invalid_request_error"),
            entry("billing_source_required", HttpStatus.UNPROCESSABLE_ENTITY,
                    "A billing source is required", "invalid_request_error"),
            entry("billing_eligibility_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "Billing eligibility is unavailable", "server_error"),
            entry("wallet_insufficient_balance", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient wallet balance", "quota_error"),
            // 在任何供应商调用之前就被拒绝：该请求的最坏情况开销超过了客户预付余额所能覆盖的
            // 范围，因此服务它就会让平台自己吞下差额。用 402 而不是 422，是因为这个请求格式良好
            // 且该模型确实在售 —— 只是已经没有余额可花了。
            entry("insufficient_balance", HttpStatus.PAYMENT_REQUIRED,
                    "Insufficient prepaid balance to cover this request", "quota_error"),
            entry("wallet_usage_idempotency_mismatch", HttpStatus.CONFLICT,
                    "Billing request conflicts with an existing request", "invalid_request_error"),
            entry("billing_reconciliation_required", HttpStatus.CONFLICT,
                    "Billing reconciliation is required", "server_error"),
            entry("wallet_usage_state_conflict", HttpStatus.CONFLICT,
                    "Wallet usage state conflict", "server_error"),
            entry("wallet_billing_request_invalid", HttpStatus.SERVICE_UNAVAILABLE,
                    "Billing service rejected the internal request", "server_error"),
            entry("wallet_billing_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "Billing service is unavailable", "server_error"),
            entry("invalid_idempotency_key", HttpStatus.BAD_REQUEST,
                    "Invalid idempotency key", "invalid_request_error"),
            entry("upload_session_expired", HttpStatus.GONE,
                    "Upload session has expired", "invalid_request_error"),
            entry("upload_session_not_found", HttpStatus.NOT_FOUND,
                    "Upload session not found", "invalid_request_error"),
            entry("upload_object_missing", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Uploaded object was not found", "invalid_request_error"),
            entry("upload_object_mismatch", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Uploaded object does not match the declared metadata", "invalid_request_error"),
            entry("file_processing", HttpStatus.CONFLICT,
                    "File is still processing", "invalid_request_error"),
            entry("file_storage_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "File storage is unavailable", "server_error"),
            // 匿名读图端点的限流器在 Redis 不可用时 fail closed
            // （见 FileContentRateLimiter）。给它一个**具名**的 5xx，而不是让它落到
            // 兜底的 dependency_unavailable：厂商据此能区分"图片本身有问题"与
            // "我们这侧暂时不可用，稍后重试"。
            entry("file_content_rate_limit_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "File content rate limiter is unavailable", "server_error"),
            entry("multimodal_route_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "Multimodal route is unavailable", "server_error"),
            entry("document_converter_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "Document converter is unavailable", "server_error"),
            entry("multimodal_preprocessing_timeout", HttpStatus.GATEWAY_TIMEOUT,
                    "Multimodal preprocessing timed out", "server_error"),
            entry("content_safety_blocked", HttpStatus.UNPROCESSABLE_ENTITY,
                    "The request was stopped by content safety policy.", "content_safety"),
            entry("content_safety_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "Content safety service is unavailable.", "content_safety"),
            // 视频生成（设计 2026-09-12 多模态计费与视频套餐）。
            // 视频请求按秒计量，因此授予 token 的套餐会被拒绝，而不会对按秒定价的东西
            // 按 token 速率计费。
            entry("invalid_video_request", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Invalid video request", "invalid_request_error"),
            entry("invalid_video_request_id", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Invalid video request id", "invalid_request_error"),
            entry("video_task_not_found", HttpStatus.NOT_FOUND,
                    "Video task not found", "invalid_request_error"),
            entry("video_output_not_ready", HttpStatus.CONFLICT,
                    "The video is not ready", "invalid_request_error"),
            entry("video_request_id_conflict", HttpStatus.CONFLICT,
                    "Request id is already in use", "invalid_request_error"),
            entry("video_requires_second_denominated_package", HttpStatus.UNPROCESSABLE_ENTITY,
                    "This package does not include video seconds", "quota_error"),
            entry("video_channel_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "No video channel is available", "server_error"),
            // 图片生成（设计 2026-09-28 图片生成与混合池）。图片按张计量，
            // 因此授予 token 的套餐会被拒绝，而不会对按张定价的东西按 token 速率计费。
            entry("invalid_image_request", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Invalid image generation request", "invalid_request_error"),
            entry("image_input_array_not_supported", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Image input must be a single URL", "invalid_request_error"),
            // 多参考图生图（2026-10-10 实测上游支持 image 数组）：上限 8 张，
            // 超过在网关侧拒绝，而不是把超量请求交给厂商去给一句无法分支的错误。
            entry("image_input_count_exceeded", HttpStatus.UNPROCESSABLE_ENTITY,
                    "At most 8 input images are supported per generation",
                    "invalid_request_error"),
            // 一次调用产出一张，这是端点本身的形状（上游同步接口，网关也不转发厂商
            // 未确认接受的 n）。客户端要求多于一张时明确拒绝，而不是收下 n 却只交付
            // 一张：静默少交付会让客户以为自己买的是多图，并且只有在拿到响应时才
            // 发现不是。错误码与尺寸、输入图那两个一样是稳定字符串。
            // V114 起 n 接受 1~4（网关为 N 张并发扇出 N 次厂商调用），超出上限
            // 或小于 1 才拒绝。这个字符串随上限一起更新：客户端与运维都是照着它
            // 排障的，停在"只支持一张"会让一个明明合法、只是要多了的请求看起来
            // 像功能缺失。
            entry("image_count_not_supported", HttpStatus.UNPROCESSABLE_ENTITY,
                    "n must be between 1 and 4 images per request", "invalid_request_error"),
            // 本部署没开「上传本地图做参考图」这条路（没有配签名密钥）。
            //
            // 它必须在这里有一条**显式**映射。501 属于 5xx，而下面的 5xx 兜底会把
            // 任何未被识别的 5xx 一律说成「没有可用渠道」——那对客户端是**误导**：
            // 它会去重试、去查渠道，而真相是「这个功能在这台机器上关着」，重试
            // 一万次也不会变。回滚（取消 GATEWAY_FILES_CONTENT_TOKEN_SECRET）
            // 就会走到这个分支，所以它不是一条走不到的路。
            entry("image_file_input_disabled", HttpStatus.NOT_IMPLEMENTED,
                    "Image file input is disabled on this deployment", "server_error"),
            entry("image_request_id_conflict", HttpStatus.CONFLICT,
                    "Request id is already in use", "invalid_request_error"),
            entry("image_generation_outcome_unknown", HttpStatus.CONFLICT,
                    "The previous generation with this id has an unknown outcome",
                    "invalid_request_error"),
            entry("image_generation_previously_failed", HttpStatus.CONFLICT,
                    "The generation with this id already failed", "invalid_request_error"),
            entry("image_generation_result_not_replayable", HttpStatus.CONFLICT,
                    "The generation with this id cannot be replayed", "invalid_request_error"),
            entry("image_requires_image_denominated_package", HttpStatus.UNPROCESSABLE_ENTITY,
                    "This package does not include images", "quota_error"),
            entry("image_channel_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "No image channel is available", "server_error"),
            entry("image_model_size_unregistered", HttpStatus.UNPROCESSABLE_ENTITY,
                    "This model has no registered size tier", "invalid_request_error"),
            entry("image_size_not_sold_by_model", HttpStatus.UNPROCESSABLE_ENTITY,
                    "The requested size is not sold by this model", "invalid_request_error"),
            // 每秒方向已有一个自己的错误码；张数是另一种额度，因此同样需要自己的码，
            // 否则一次被拒绝的生图会让客户端以为自己的视频秒数用完了。
            entry("images_per_minute_exceeded", HttpStatus.TOO_MANY_REQUESTS,
                    "Generation images per minute exceeded", "rate_limit_error"),
            entry("account_safety_restricted", HttpStatus.FORBIDDEN,
                    "Account access is restricted.", "permission_error"),
            // 技能客户端界面：未认证的读取（例如成员头像）与
            // 被拒绝的 API key 是两种不同结果，因此客户端能区分
            // 「请登录」和「该凭证不被接受」。
            entry("authentication_required", HttpStatus.UNAUTHORIZED,
                    "Authentication required", "authentication_error"));

    private OpenAiErrorMapper() { }

    public static ErrorDescriptor descriptor(Throwable error) {
        if (error instanceof ResponseStatusException response) {
            String reason = response.getReason();
            ErrorDescriptor approved = reason == null ? null : APPROVED_ERRORS.get(reason);
            if (approved != null) {
                return approved;
            }
            if ("active_package_required".equals(reason)
                    || "quota_exhausted".equals(reason)
                    || "model_quota_exhausted".equals(reason)) {
                return new ErrorDescriptor(
                        HttpStatus.UNPROCESSABLE_ENTITY, "No usable package",
                        "quota_error", "package_unavailable");
            }
            if ("dependency_unavailable".equals(reason)) {
                return new ErrorDescriptor(HttpStatus.SERVICE_UNAVAILABLE,
                        "Dependency unavailable", "server_error", "dependency_unavailable");
            }
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            status = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                return new ErrorDescriptor(status,
                        "Rate limit exceeded", "rate_limit_error", "rate_limit_exceeded");
            }
            if (status == HttpStatus.UNAUTHORIZED) {
                return new ErrorDescriptor(status,
                        "Invalid API key", "authentication_error", "invalid_api_key");
            }
            if (status == HttpStatus.FORBIDDEN) {
                return new ErrorDescriptor(status,
                        "Model is not allowed", "permission_error", "model_not_allowed");
            }
            if (status == HttpStatus.BAD_REQUEST) {
                return new ErrorDescriptor(status,
                        "Invalid request", "invalid_request_error", "invalid_request");
            }
            if (status.is4xxClientError()) {
                return new ErrorDescriptor(status,
                        "Invalid request", "invalid_request_error",
                        reason == null ? "invalid_request" : reason);
            }
            if (status.is5xxServerError()) {
                return new ErrorDescriptor(HttpStatus.SERVICE_UNAVAILABLE,
                        "No upstream channel is available", "server_error", "upstream_unavailable");
            }
        }
        if (error instanceof ProviderAdapter.ProviderException provider) {
            if (provider.contentPolicyViolation()) {
                // 上游对生成**内容**的审核拒绝（例如版权角色）。改提示词是下一步，
                // 重试同一个提示词毫无意义——因此它必须与「上游故障」的 502 兜底
                // 分开，客户端才能提示「内容审核未通过，请修改描述」。
                // 2026-10-10 线上首个实锤：OutputImageSensitiveContentDetected.PolicyViolation。
                return new ErrorDescriptor(HttpStatus.UNPROCESSABLE_ENTITY,
                        "The generated content was rejected by the provider content policy",
                        "invalid_request_error", "image_content_policy_violation");
            }
            if (provider.status() == 429) {
                return new ErrorDescriptor(HttpStatus.TOO_MANY_REQUESTS,
                        "Upstream provider request failed", "rate_limit_error", "provider_error");
            }
            if (provider.status() == 504) {
                // 超时必须与「上游明确拒绝」区分开：对一次超时，用**同一个幂等键**
                // 重试是对的；对一次明确拒绝，重试只会再失败一次。
                // 两者此前都被压成 502 provider_error，客户端因此无从选择。
                return new ErrorDescriptor(HttpStatus.GATEWAY_TIMEOUT,
                        "Upstream provider timed out", "server_error", "provider_timeout");
            }
            return new ErrorDescriptor(HttpStatus.BAD_GATEWAY,
                    "Upstream provider request failed", "server_error", "provider_error");
        }
        return new ErrorDescriptor(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error", "server_error", "internal_error");
    }

    private static Map.Entry<String, ErrorDescriptor> entry(
            String code, HttpStatus status, String message, String type) {
        return Map.entry(code, new ErrorDescriptor(status, message, type, code));
    }

    public record ErrorDescriptor(HttpStatus status, String message, String type, String code) { }
}
