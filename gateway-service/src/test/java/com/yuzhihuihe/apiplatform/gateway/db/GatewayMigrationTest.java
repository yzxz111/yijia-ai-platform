package com.yuzhihuihe.apiplatform.gateway.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.file.PostgresProviderFileBindingRepository;
import com.yuzhihuihe.apiplatform.gateway.file.PostgresFileObjectRepository;
import com.yuzhihuihe.apiplatform.gateway.file.ProviderFileBindingRepository;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class GatewayMigrationTest {

    private static final String GATEWAY_USER = "gateway_app";
    private static final String GATEWAY_PASSWORD = "gateway-test-password";
    private static final String PLATFORM_USER = "platform_app";
    private static final String PLATFORM_PASSWORD = "platform-test-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void createIsolatedSchemas() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + GATEWAY_USER + " LOGIN PASSWORD '" + GATEWAY_PASSWORD + "'");
            statement.execute("CREATE ROLE " + PLATFORM_USER + " LOGIN PASSWORD '" + PLATFORM_PASSWORD + "'");
            statement.execute("CREATE SCHEMA platform AUTHORIZATION " + POSTGRES.getUsername());
            statement.execute("REVOKE ALL ON SCHEMA platform FROM PUBLIC");
            statement.execute("CREATE SCHEMA gateway AUTHORIZATION " + GATEWAY_USER);
        }
    }

    @Test
    void createsGatewayTablesAndCannotWritePlatformSchema() throws SQLException {
        migrateGateway();

        assertThat(tableNames("gateway")).contains(
                "api_key",
                "package_instance",
                "package_instance_model_quota",
                "quota_reservation",
                "quota_ledger",
                "provider",
                "channel",
                "model",
                "model_alias",
                "channel_model",
                "provider_model_price",
                "file_object",
                "provider_file_binding",
                "usage_log",
                "provider_cost_record",
                "usage_economics_outbox",
                "consumed_event",
                "admin_operation_audit");
        assertThat(tableNames("platform")).isEmpty();

        assertThatThrownBy(() -> {
            try (Connection connection = gatewayConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE platform.forbidden_write(id bigint)");
            }
        }).isInstanceOf(SQLException.class);
    }

    @Test
    void addsOptionalExampleImageLocaleWithAClosedValueSet() throws SQLException {
        migrateGateway();

        assertThat(columnNames("skill_expert_profile"))
                .contains("example_image_locale");
        assertThat(constraintDefinition(
                "skill_expert_profile", "ck_skill_expert_profile_example_image_locale"))
                .contains("'zh'")
                .contains("'en'")
                .contains("'neutral'");
    }

    @Test
    void addsDirectUploadSessionsWithoutBreakingLegacyUploadingRows() throws SQLException {
        cleanAndMigrateGatewayTo24();
        UUID legacyId = UUID.randomUUID();
        try (Connection connection = gatewayConnection(); var statement = connection.prepareStatement("""
                INSERT INTO gateway.file_object(
                    id, external_file_id, tenant_id, owner_user_id, object_key,
                    original_filename, sha256, mime_type, size_bytes, status, expires_at)
                VALUES (?, ?, ?, ?, ?, 'legacy.pdf', ?, 'application/pdf', 1, 'UPLOADING', now() + interval '1 hour')
                """)) {
            statement.setObject(1, legacyId);
            statement.setString(2, "file_" + legacyId);
            statement.setObject(3, UUID.randomUUID());
            statement.setObject(4, UUID.randomUUID());
            statement.setString(5, "files/legacy/" + legacyId);
            statement.setString(6, "0".repeat(64));
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        migrateGateway();

        assertThat(columnNames("file_object")).contains(
                "api_key_id", "upload_session_id", "upload_idempotency_key", "upload_expires_at",
                "declared_sha256", "etag", "quarantine_object_key", "scan_claim_token",
                "scan_claimed_at", "scan_attempts", "next_scan_at", "last_error_code",
                "quarantine_cleanup_claim_token", "quarantine_cleanup_claimed_at");
        assertThat(columnProperty("file_object", "scan_attempts", "is_nullable")).isEqualTo("NO");
        assertThat(columnProperty("file_object", "scan_attempts", "column_default")).isEqualTo("0");
        assertThat(indexDefinition("uq_file_upload_idempotency"))
                .contains("api_key_id", "upload_idempotency_key", "WHERE (upload_idempotency_key IS NOT NULL)");
        assertThat(indexDefinition("uq_file_upload_session"))
                .contains("upload_session_id", "WHERE (upload_session_id IS NOT NULL)");
        assertThat(indexDefinition("ix_file_scan_claim"))
                .contains("status", "next_scan_at", "scan_claimed_at", "created_at", "UPLOADED", "SCANNING");
        assertThat(indexDefinition("ix_file_pending_upload_cleanup"))
                .contains("COALESCE(upload_expires_at, expires_at)", "created_at", "id", "PENDING_UPLOAD");
        assertThat(indexDefinition("ix_file_quarantine_cleanup"))
                .contains("(created_at, id)", "quarantine_cleanup_claim_token",
                        "quarantine_cleanup_claimed_at", "quarantine_object_key IS NOT NULL",
                        "READY", "REJECTED", "FAILED");
        assertThat(constraintDefinition("file_object", "ck_file_declared_sha256"))
                .contains("^[0-9a-f]{64}$");
        assertThat(constraintDefinition("file_object", "ck_file_scan_attempts"))
                .contains("scan_attempts >= 0");
        assertThat(constraintDefinition("file_object", "ck_file_status"))
                .contains("UPLOADING", "PENDING_UPLOAD", "UPLOADED", "SCANNING", "READY",
                        "REJECTED", "FAILED", "DELETING", "DELETED");
        assertThat(values("SELECT status FROM gateway.file_object WHERE id = '" + legacyId + "'"))
                .containsExactly("UPLOADING");
    }

    @Test
    void createsContentSafetyChecksEventsAndEnforcementCache() throws SQLException {
        migrateGateway();

        assertThat(tableNames("gateway")).contains(
                "content_safety_check",
                "content_safety_event_outbox",
                "safety_enforcement_cache");
        assertThat(columnNames("content_safety_check")).contains(
                "request_id", "direction", "result", "policy_version", "local_result",
                "semantic_result", "file_result", "safety_fingerprint");
        assertThat(constraintDefinition("content_safety_check", "ck_content_safety_check_result"))
                .contains("ALLOW", "BLOCK", "UNAVAILABLE", "TIMEOUT");
        assertThat(constraintDefinition("content_safety_check", "ck_content_safety_check_direction"))
                .contains("INPUT", "INPUT_EXTRACTED", "OUTPUT", "FILE");
        assertThat(constraintDefinition("content_safety_event_outbox", "ck_content_safety_outbox_status"))
                .contains("PENDING", "DELIVERING", "DELIVERED", "FAILED");
        assertThat(indexDefinition("uq_content_safety_event_outbox_fingerprint"))
                .contains("UNIQUE", "event_fingerprint");
        assertThat(indexDefinition("uq_safety_enforcement_cache_owner"))
                .contains("UNIQUE", "tenant_id", "user_id");
    }

    @Test
    void createsDurableApiKeyResetIntentsWithoutPlaintextSecrets() throws SQLException {
        migrateGateway();

        assertThat(tableNames("gateway")).contains("api_key_reset_intent");
        assertThat(columnNames("api_key_reset_intent")).containsExactlyInAnyOrder(
                "operation_id", "tenant_id", "user_id", "api_key_id",
                "proof_fingerprint", "status", "completed_at", "created_at", "updated_at");
        assertThat(constraintDefinition("api_key_reset_intent", "fk_api_key_reset_intent_owner"))
                .contains("api_key_id", "tenant_id", "user_id")
                .contains("api_key", "id");
        assertThat(constraintDefinition("api_key_reset_intent", "ck_api_key_reset_intent_fingerprint"))
                .contains("proof_fingerprint", "[0-9a-f]{64}");
        assertThat(constraintDefinition("api_key_reset_intent", "ck_api_key_reset_intent_status"))
                .contains("PENDING", "COMPLETED");
        assertThat(constraintDefinition("api_key_reset_intent", "ck_api_key_reset_intent_completion"))
                .contains("completed_at", "PENDING", "COMPLETED");
        assertThat(indexDefinition("uq_api_key_reset_intent_fingerprint"))
                .contains("UNIQUE", "proof_fingerprint");
        assertThat(indexDefinition("uq_api_key_reset_intent_pending_key"))
                .contains("UNIQUE", "api_key_id", "WHERE", "status", "PENDING");
        assertThat(indexDefinition("ix_api_key_reset_intent_owner_status"))
                .contains("tenant_id", "user_id", "api_key_id", "status");
    }

    @Test
    void createsManagedApiKeySecretBoundary() throws SQLException {
        migrateGateway();

        assertThat(columnNames("api_key")).contains(
                "managed", "secret_ciphertext", "secret_key_version", "last_revealed_at");
        assertThat(columnProperty("api_key", "managed", "is_nullable")).isEqualTo("NO");
        assertThat(columnProperty("api_key", "managed", "column_default")).isEqualTo("false");
        assertThat(constraintDefinition("api_key", "ck_api_key_managed_secret"))
                .contains("managed", "package_instance_id", "secret_ciphertext", "secret_key_version")
                .contains("last_revealed_at");
        assertThat(indexDefinition("uq_api_key_active_managed_package"))
                .contains("UNIQUE", "package_instance_id", "managed", "status", "ACTIVE");
    }

    @Test
    void indexesPackageExpiryMaterializationByExpiryAndIdForOnlyMaterializedStatuses()
            throws SQLException {
        migrateGateway();

        String definition = indexDefinition("ix_package_instance_expiry_materialization");
        assertThat(definition)
                .contains("ON gateway.package_instance", "(expires_at, id)", "WHERE")
                .contains("ACTIVE", "EXPIRED")
                .doesNotContain("PENDING", "REVOKED");
        assertThat(explainWithSequentialScanDisabled("""
                SELECT id, tenant_id, user_id, expires_at
                FROM gateway.package_instance
                WHERE status IN ('ACTIVE', 'EXPIRED')
                ORDER BY expires_at, id
                LIMIT 100
                """))
                .contains("ix_package_instance_expiry_materialization")
                .doesNotContain("Sort");
    }

    @Test
    void grantsPlatformReadOnlyAccessOnlyToOperationAudits() throws SQLException {
        migrateGateway();

        try (Connection connection = platformConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT count(*) FROM gateway.admin_operation_audit")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isZero();
        }
        assertThatThrownBy(() -> {
            try (Connection connection = platformConnection(); Statement statement = connection.createStatement()) {
                statement.execute("SELECT count(*) FROM gateway.file_object");
            }
        }).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> {
            try (Connection connection = platformConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM gateway.admin_operation_audit");
            }
        }).isInstanceOf(SQLException.class);
    }

    @Test
    void freezesPackageSnapshotAndUsesMinorCurrencyUnits() throws SQLException {
        migrateGateway();

        assertThat(columnNames("package_instance")).contains("price_amount", "currency", "allowed_models");
        assertThat(columnType("provider_model_price", "input_price_per_million")).isEqualTo("bigint");
        assertThat(columnType("provider_model_price", "output_price_per_million")).isEqualTo("bigint");
        assertThat(columnType("provider_cost_record", "amount")).isEqualTo("bigint");
        assertThat(packageStatusConstraint()).contains("REVOKED").doesNotContain("CANCELLED");
    }

    @Test
    void addsNullableNonnegativePlatformLatencyWithARecentWindowIndex() throws SQLException {
        migrateGateway();

        assertThat(columnNames("usage_log")).contains("platform_added_latency_ms");
        assertThat(columnProperty("usage_log", "platform_added_latency_ms", "is_nullable"))
                .isEqualTo("YES");
        assertThat(columnProperty("usage_log", "platform_added_latency_ms", "column_default"))
                .isNull();
        assertThat(constraintDefinition("usage_log", "ck_usage_platform_added_latency"))
                .contains("platform_added_latency_ms >= 0");
        assertThat(indexDefinition("ix_usage_recent_platform_latency"))
                .contains("tenant_id", "user_id", "created_at", "platform_added_latency_ms");
    }

    @Test
    void addsNullableUpstreamModelSnapshotAndGlobalUsageAnalyticsIndex() throws SQLException {
        migrateGateway();

        assertThat(columnNames("usage_log")).contains("upstream_model");
        assertThat(columnProperty("usage_log", "upstream_model", "is_nullable"))
                .isEqualTo("YES");
        assertThat(columnProperty("usage_log", "upstream_model", "column_default"))
                .isNull();
        assertThat(indexDefinition("ix_usage_created_analytics"))
                .contains("created_at", "user_id", "model_code", "provider_id",
                        "upstream_model", "input_tokens", "output_tokens", "total_tokens",
                        "outcome", "platform_added_latency_ms");
    }

    @Test
    void createsUnifiedMultimodalCatalogCacheAndStageAccounting() throws SQLException {
        migrateGateway();

        assertThat(columnNames("model")).contains("routing_strategy", "routing_policy");
        assertThat(tableNames("gateway"))
                .contains("attachment_extraction_cache", "usage_stage");
        assertThat(constraintDefinition("model", "ck_model_routing_strategy"))
                .contains("DIRECT", "COMPOSITE");
        assertThat(constraintDefinition("model", "ck_model_routing_policy"))
                .contains("textModel", "imageModel", "fileModel", "mixedFinalModel", "extractorVersion");
        assertThat(values("""
                SELECT model.code || ':' || alias.alias || ':' || model.routing_strategy
                FROM gateway.model model
                JOIN gateway.model_alias alias ON alias.model_id = model.id
                WHERE model.code = 'multimodal-basic'
                """)).containsExactly("multimodal-basic:multimodal-basic:COMPOSITE");
        assertThat(values("""
                SELECT jsonb_array_elements_text(capabilities)
                FROM gateway.model
                WHERE code = 'multimodal-basic'
                ORDER BY 1
                """)).containsExactly("FILE", "IMAGE", "TEXT");
        assertThat(singleLong("""
                SELECT count(*)
                FROM gateway.channel_model mapping
                JOIN gateway.model model ON model.id = mapping.model_id
                WHERE model.code = 'multimodal-basic'
                """)).isZero();
        assertThat(columnNames("usage_stage")).contains(
                "usage_log_id", "stage_index", "stage_type", "logical_model_code",
                "model_id", "provider_id", "channel_id", "upstream_model",
                "input_tokens", "output_tokens", "total_tokens", "usage_estimated",
                "duration_ms", "outcome", "error_category", "provider_cost_amount",
                "provider_cost_currency", "price_estimated");
    }

    @Test
    void createsRefundHoldsLifecycleAuditAndReservationLookupIndex() throws SQLException {
        migrateGateway();

        assertThat(tableNames("gateway"))
                .contains("package_refund_hold", "package_lifecycle_event");
        assertThat(columnNames("package_refund_hold")).contains(
                "business_no", "package_instance_id", "grant_request_id", "original_status",
                "status", "payload_hash", "total_tokens", "used_tokens", "remaining_tokens",
                "snapshot_at", "released_at", "revoked_at");
        assertThat(indexDefinition("ix_reservation_package_status_expiry"))
                .contains("package_instance_id", "status", "expires_at");
        assertThat(constraintDefinition("package_refund_hold", "ck_refund_hold_status"))
                .contains("WAITING", "READY", "RELEASED", "REVOKED");
    }

    @Test
    void assignsBailianTextBasicPriorityWhenRouteIsDisabledDuringUpgrade() throws SQLException {
        cleanAndMigrateGatewayTo("26");
        try (Connection connection = gatewayConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE gateway.provider SET enabled = false WHERE code = 'bailian';
                    UPDATE gateway.channel SET enabled = false
                    WHERE code = 'bailian-primary';
                    UPDATE gateway.channel_model AS mapping SET enabled = false
                    FROM gateway.channel AS channel
                    CROSS JOIN gateway.model AS model
                    WHERE mapping.channel_id = channel.id
                      AND mapping.model_id = model.id
                      AND channel.code = 'bailian-primary'
                      AND model.code = 'text-basic';
                    """);
        }

        try {
            migrateGateway();

            assertThat(values("""
                    SELECT mapping.priority_override || ':' || provider.enabled || ':'
                           || channel.enabled || ':' || mapping.enabled
                    FROM gateway.channel_model mapping
                    JOIN gateway.channel channel ON channel.id = mapping.channel_id
                    JOIN gateway.provider provider ON provider.id = channel.provider_id
                    JOIN gateway.model model ON model.id = mapping.model_id
                    WHERE provider.code = 'bailian' AND model.code = 'text-basic'
                    """)).containsExactly("110:false:false:false");
        } finally {
            try (Connection connection = gatewayConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    UPDATE gateway.provider SET enabled = true WHERE code = 'bailian';
                    UPDATE gateway.channel SET enabled = true
                    WHERE code = 'bailian-primary';
                    UPDATE gateway.channel_model AS channel_model SET enabled = true
                    FROM gateway.channel AS channel
                    CROSS JOIN gateway.model AS model
                    WHERE channel_model.channel_id = channel.id
                      AND channel_model.model_id = model.id
                      AND channel.code = 'bailian-primary'
                      AND model.code = 'text-basic';
                    """);
            }
        }
    }

    @Test
    void localizesBuiltinModelDisplayNamesWithoutOverwritingAdministratorChanges() throws SQLException {
        cleanAndMigrateGatewayTo("28");
        try (Connection connection = gatewayConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE gateway.model
                    SET display_name = '管理员自定义名称'
                    WHERE code = 'text-basic'
                    """);
        }

        migrateGateway();

        assertThat(values("""
                SELECT code || ':' || display_name
                FROM gateway.model
                WHERE code IN (
                    'file-basic', 'image-basic', 'multimodal-basic', 'text-basic', 'text-max',
                    'text-pro', 'text-standard', 'vision-basic', 'vision-pro')
                ORDER BY code
                """)).containsExactly(
                        "file-basic:文件基础版",
                        "image-basic:图片基础版",
                        "multimodal-basic:多模态基础版",
                        "text-basic:管理员自定义名称",
                        "text-max:文本旗舰版",
                        "text-pro:文本专业版",
                        "text-standard:文本标准版",
                        "vision-basic:视觉基础版",
                        "vision-pro:视觉专业版");
    }

    @Test
    void localizesBuiltinProviderAndChannelNamesWithoutOverwritingAdministratorChanges()
            throws SQLException {
        cleanAndMigrateGatewayTo("29");
        try (Connection connection = gatewayConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE gateway.channel
                    SET name = '管理员自定义渠道'
                    WHERE code = 'deepseek-primary'
                    """);
        }

        migrateGateway();

        assertThat(values("""
                SELECT code || ':' || name
                FROM gateway.provider
                WHERE code IN ('bailian', 'deepseek')
                ORDER BY code
                """)).containsExactly(
                        "bailian:阿里云百炼",
                        "deepseek:DeepSeek");
        assertThat(values("""
                SELECT code || ':' || name
                FROM gateway.channel
                WHERE code IN ('bailian-primary', 'deepseek-primary')
                ORDER BY code
                """)).containsExactly(
                        "bailian-primary:百炼主渠道",
                        "deepseek-primary:管理员自定义渠道");
    }

    @Test
    void publishesSellableRetailPricesInsteadOfTheBootstrapGiveaway() throws SQLException {
        migrateGateway();

        // V21 给每个 alias 播种的输入价为 1 分/M、输出价为 2 分/M，这低于每一个可路由
        // alias 的供应商成本，而且没有任何端点能改动它。V43 用可售的价格卡替换了它；
        // 不允许留下任何残留。
        assertThat(singleLong("""
                SELECT count(*) FROM gateway.model_billing_price
                WHERE effective_to IS NULL
                  AND (input_price_per_million <= 1 OR output_price_per_million <= 2)
                """)).isZero();

        // 每一个当前 token 价格都必须高于它自己的供应商成本。
        assertThat(singleLong("""
                SELECT count(*) FROM gateway.model_billing_price
                WHERE effective_to IS NULL
                  AND (input_price_per_million < provider_cost_input_per_million
                       OR output_price_per_million < provider_cost_output_per_million)
                """)).isZero();

        // 缓存定价派生自缓存读取成本，因此对每一个配置了缓存价的 alias 来说，
        // 缓存命中都必须严格便宜于未命中。
        assertThat(singleLong("""
                SELECT count(*) FROM gateway.model_billing_price
                WHERE effective_to IS NULL
                  AND cache_hit_price_per_million IS NOT NULL
                  AND cache_hit_price_per_million >= input_price_per_million
                """)).isZero();

        // 每个 alias 恰好有一个当前价格，并且八个 token alias 全部存在。
        assertThat(singleLong("""
                SELECT count(*) FROM gateway.model_billing_price WHERE effective_to IS NULL
                """)).isEqualTo(8);

        assertThat(values("""
                SELECT model_code || ':' || input_price_per_million || ':'
                       || output_price_per_million || ':' || cache_hit_price_per_million
                FROM gateway.model_billing_price
                WHERE effective_to IS NULL
                ORDER BY model_code
                """)).containsExactly(
                        // 期望值即当前迁移链的末端状态。本地后续的价格/额度调整
                        // （写入在售额度与价格卡的那几条迁移）已把这几档改写，
                        // 断言必须跟着迁移走，而不是停留在 V43 当年的数字。
                        "file-basic:75:300:15",
                        "multimodal-basic:300:1200:60",
                        "text-basic:300:1200:15",
                        "text-max:1800:5400:360",
                        "text-pro:1350:4050:45",
                        "text-standard:450:1350:15",
                        "vision-basic:180:1080:36",
                        "vision-pro:300:1800:60");
    }

    @Test
    void pricesVideoSecondsFromTheStoredCostWithoutChargingRetryTwice() throws SQLException {
        migrateGateway();

        // 价格卡存的是生效后（含重试）的成本——720P 批发价 60 分被存成 69——而发布
        // 守卫拿到的正是视图里的 effective_unit_cost。在那里再次套用 retry_rate
        // 会让每个视频 alias 的定价高出 15%，从而把一个按已发布价格卡精确标价的套餐
        // （1.00 CNY/秒 对 0.69）压到 20% 毛利，低于秒维度 25% 的下限。
        assertThat(values("""
                SELECT model_code || ':' || resolution_tier || ':' || provider_unit_cost
                       || ':' || effective_unit_cost
                FROM gateway.video_effective_cost
                ORDER BY model_code, resolution_tier
                """)).containsExactly(
                        "video-basic:720P:69:69",
                        "video-budget:480P:35:35",
                        "video-max:1080P:345:345",
                        // V95 新增的天翼分镜别名：厂商原价 50 分/秒，存成含 5% 重试的 53
                        // （视频维度存的是**含重试**的有效成本，视图原样透出）。
                        "video-minimax:720P:53:53",
                        "video-pro:720P:92:92",
                        "video-standard:1080P:115:115");
        // 每个视频 alias 都带有失败余量：否则一个会对失败生成计费的上游
        // 会被以低于其自身成本的价格售出。
        assertThat(singleLong("""
                SELECT count(*) FROM gateway.video_effective_cost WHERE retry_rate <= 0
                """)).isZero();
        // unitPrice >= effectiveCost / 0.75 就是秒维度 25% 的下限，每一个播种的档位
        // 都按存储成本通过了它。
        assertThat(singleLong("""
                SELECT count(*) FROM gateway.video_effective_cost
                WHERE unit_price * 3 < effective_unit_cost * 4
                """)).isZero();
    }

    @Test
    void storesTheVideoOutputLocationOnlyForASucceededTask() throws SQLException {
        migrateGateway();

        // 已完成的生成必须可被取回，因此供应商的位置被保存在任务行上——但只有
        // SUCCEEDED 的行才允许携带它。内容端点信任这一列，所以失败的任务
        // 绝不能声称自己有一个视频。
        assertThat(tableNames("gateway")).contains("video_generation_task");
        assertThat(columnNames("video_generation_task")).contains(
                "usage_dimension", "requested_seconds", "reserved_seconds", "billed_seconds",
                "upstream_task_id", "output_url", "expires_at");
        assertThat(columnProperty("video_generation_task", "output_url", "is_nullable"))
                .isEqualTo("YES");
        assertThat(columnProperty("video_generation_task", "output_url", "column_default"))
                .isNull();
        assertThat(constraintDefinition(
                "video_generation_task", "ck_video_task_dimension"))
                .contains("VIDEO_SECOND");
        assertThat(constraintDefinition(
                "video_generation_task", "ck_video_task_output_url"))
                .contains("output_url IS NULL", "SUCCEEDED");
        // token 计数器绝不能由视频任务写入。
        assertThat(columnNames("package_instance_model_quota"))
                .contains("usage_dimension", "seconds_quota");
    }

    @Test
    void registersTheResolutionEachVideoAliasSells() throws SQLException {
        migrateGateway();

        // V54。视频 alias 是按分辨率定价的——video-budget 作为 480P 的 alias 成本是
        // 35 分/秒，而 1080P 时是 115 分——而目录侧会按 alias 声称出售的档位计算某个套餐
        // 版本的 worst-case 毛利。网关必须强制执行同样的映射，因为 480P 入门套餐
        // （platform V66，59 分/秒，worst-case 毛利 40.7%）在第一次 1080P 渲染上就亏钱。
        assertThat(tableNames("gateway")).contains("video_alias_tier");
        assertThat(columnNames("video_alias_tier")).containsExactlyInAnyOrder(
                "model_code", "resolution_tier", "registered_at");
        assertThat(constraintDefinition("video_alias_tier", "ck_video_alias_tier_value"))
                .contains("480P", "720P", "1080P");

        assertThat(values("""
                SELECT model_code || ':' || resolution_tier
                FROM gateway.video_alias_tier
                ORDER BY model_code
                """))
                .containsExactly(
                        "video-basic:720P",
                        "video-budget:480P",
                        "video-max:1080P",
                        // V95 新增的天翼分镜别名，出售 720P 档。
                        "video-minimax:720P",
                        "video-pro:720P",
                        "video-standard:1080P");

        // 该守卫失败即拒绝，因此一个未登记的、支持 VIDEO 的 alias 会在交付时被拒，
        // 而不是以一个无人能计算的价格售出。迁移正是让它安全的原因：
        // 迁移拒绝漏掉任何一个。
        assertThat(singleLong("""
                SELECT count(*) FROM gateway.model model
                WHERE model.capabilities @> '["VIDEO"]'::jsonb
                  AND NOT EXISTS (
                        SELECT 1 FROM gateway.video_alias_tier tier
                        WHERE tier.model_code = model.code)
                """)).isZero();
    }

    @Test
    void registersTheSizeTierEachImageAliasSellsAndItsPerImageCost() throws SQLException {
        migrateGateway();

        // V81。生图别名是按尺寸档位定价的：一个 2K 别名的成本基准是一次 2K 渲染的
        // 成本，而套餐版本的 worst-case 毛利正是依据该档位计算的。网关必须强制执行
        // 同一个映射，失败即拒绝（image_model_size_unregistered /
        // image_size_not_sold_by_model），否则一个按 2K 定价的套餐会在第一次 4K
        // 渲染上亏钱。同时表本身也是幂等锚点：没有它，一次客户端重试就是第二次付费生成。
        assertThat(tableNames("gateway")).contains("image_alias_tier", "image_generation_task");
        assertThat(columnNames("image_alias_tier")).containsExactlyInAnyOrder(
                "model_code", "resolution_tier", "registered_at");
        assertThat(constraintDefinition("image_alias_tier", "ck_image_alias_tier_value"))
                .contains("1K", "1.5K", "2K", "4K");
        // 任务表归档的档位用同一套词表：归约得出的档位必须写得进去，否则一次
        // 1.5K 的生成会在**扣费之后**落库失败。
        assertThat(constraintDefinition("image_generation_task", "ck_image_task_tier"))
                .contains("1K", "1.5K", "2K", "4K");

        assertThat(values("""
                SELECT model_code || ':' || resolution_tier
                FROM gateway.image_alias_tier
                ORDER BY model_code
                """)).containsExactly("image-basic:2K");

        // V92/V93/V94。音频走与视频、图片完全相同的「按秒计量」形状，因此它
        // 同样需要三件东西：一个把自己钉在 AUDIO_SECOND 上的任务表（幂等锚点）、
        // 一张档位登记表（这个别名按什么卖）、以及一个按秒的成本视图。
        // 三者缺一时，一次音频调用的结算就没有依据：没有锚点则重试是第二次
        // 付费调用，没有档位则该别名无法按其消耗计价，没有视图则套餐发布守卫
        // 拿不到成本基准而以 provider_price_missing 拒绝发布。
        assertThat(tableNames("gateway")).contains(
                "audio_alias_tier", "audio_generation_task");
        assertThat(columnNames("audio_alias_tier")).containsExactlyInAnyOrder(
                "model_code", "billing_unit", "registered_at");
        assertThat(constraintDefinition("audio_alias_tier", "ck_audio_alias_tier_unit"))
                .contains("SECOND");

        assertThat(values("""
                SELECT model_code || ':' || billing_unit
                FROM gateway.audio_alias_tier
                ORDER BY model_code
                """)).containsExactly(
                        "audio-asr-basic:SECOND",
                        // V96：天翼的时间戳字幕别名（异步 -filetrans 模型）。
                        "audio-asr-subtitle:SECOND",
                        "audio-retalk:SECOND",
                        "audio-tts-basic:SECOND");

        // 任务表必须把维度钉死，且 kind 只认三个能力。这两条约束各自防住一件
        // 不会报错的事：抄错的维度会让音频用量落进视频计数器，而一个
        // 没有服务能分派的 kind 会让那一行的预留挂到过期才被回收。
        assertThat(constraintDefinition("audio_generation_task", "ck_audio_task_dimension"))
                .contains("AUDIO_SECOND");
        assertThat(constraintDefinition("audio_generation_task", "ck_audio_task_kind"))
                .contains("TRANSCRIPTION", "SYNTHESIS", "LIP_SYNC");

        // 成本视图按分/秒暴露当前行，且**只**暴露 AUDIO_SECOND 维度。
        // 有效成本是 ceil(原价 * (1 + retry_rate))：VideoRetalk ceiling(8*1.05)=9。
        //
        // ASR 与 TTS 的厂商原价是 0.022 / 0.02 分/秒，**都不到 1 分**，而
        // provider_unit_cost 是 bigint（V40:36）——直接把小数写进去会被
        // PostgreSQL 静默四舍五入成 0，成本基准就没了。因此这两行的成本基准
        // 存的是取整后的 1 分/秒，有效成本 ceiling(1*1.05)=2。
        // 这条断言是当初抓出"成本被存成 0"的地方，所以把 1:2 明确钉在这里。
        // 零售价与两个下限的关系见 V93 的独立重算断言。
        assertThat(values("""
                SELECT model_code || ':' || provider_unit_cost || ':'
                       || effective_unit_cost || ':' || unit_price || ':'
                       || retry_rate || ':' || minimum_charge
                FROM gateway.audio_effective_cost
                ORDER BY model_code
                """)).containsExactly(
                        "audio-asr-basic:1:2:5:0.05000:5",
                        // V96 的字幕别名与 ASR 同价（天翼与百炼都是 0.00022 元/秒），
                        // 因此成本基准与有效成本逐字相同。
                        "audio-asr-subtitle:1:2:5:0.05000:5",
                        "audio-retalk:8:9:14:0.05000:14",
                        "audio-tts-basic:1:2:5:0.05000:5");

        // 费率卡的档位词表按维度收窄：AUDIO_SECOND 与 TOKEN 一样用空档位，
        // 因为它的单位就是秒、没有尺寸阶梯。这里同时证明 V92 放宽维度词表
        // **没有**顺手放宽档位词表。
        assertThat(values("""
                SELECT model_code || ':' || COALESCE(resolution_tier, '<null>')
                FROM gateway.model_usage_price
                WHERE usage_dimension = 'AUDIO_SECOND'
                ORDER BY model_code
                """)).containsExactly(
                        "audio-asr-basic:",
                        "audio-asr-subtitle:",
                        "audio-retalk:",
                        "audio-tts-basic:");

        // 费率卡的档位词表按维度收窄：TOKEN 为空、VIDEO_SECOND 是分辨率、
        // IMAGE 是尺寸。在本迁移之前，一条 2K 的图片行根本无法表达
        // （ck_model_usage_price_tier 只承认 '', 480P, 720P, 1080P）；V115 起
        // IMAGE 那一支是四档，厂商文档里的 1.5K 也在其中。
        assertThat(constraintDefinition("model_usage_price", "ck_model_usage_price_tier"))
                .contains("1K", "1.5K", "2K", "4K", "480P", "TOKEN");

        // 平台毛利守卫读的就是这个数值（分/张）。62 = 最坏情况的输出图 60 分 +
        // 输入图 2 分（V82），零售 89 是同时越过绝对下限 25 与比率下限 30% 的最小
        // 价格（ceil(62 / 0.7) = 89），因此那 2 分输入成本由客户承担。它与 platform
        // 镜像登记的 effective_unit_cost 62 / retail_unit_price 89 必须一致：端点
        // 解析出来的就是这个视图，两边的差额会在发布时被发现。
        //
        // V115 起 image-basic 在这个视图里有**两行**：2K 与 1.5K。后者是新增的
        // 中间档，费率与 2K 逐字相同（厂商对 1.5K 的单独口径尚未核实，低报成本
        // 会让毛利守卫按低于实际的价格发布），因此取成本的那条查询在同一维度内
        // 取最贵的一档时仍然得到 62。
        assertThat(values("""
                SELECT model_code || ':' || resolution_tier || ':'
                       || provider_unit_cost || ':' || effective_unit_cost || ':'
                       || unit_price || ':' || retry_rate
                FROM gateway.image_effective_cost
                ORDER BY model_code, resolution_tier
                """)).containsExactly(
                        "image-basic:1.5K:62:62:89:0.00000",
                        "image-basic:2K:62:62:89:0.00000");

        // 视图只暴露当前行，因此上一条断言本身就说明它读到的是 image-v2 而不是
        // image-v1。这里再把「为什么 2K 只有一行」写下来：旧行是被 effective_to
        // 关闭的，而不是被改写或删除——uq_model_usage_price_current
        //（V40:137-139）不允许同一 (别名, 维度, 档位) 上有两个当前行，
        // 而已经按 image-v1 结算过的扣费必须仍能用那一行解释。档位在唯一键里，
        // 所以新增 1.5K 那一行不需要关闭 2K 的行，两者可以同时在册。
        assertThat(values("""
                SELECT price_version || ':' || (effective_to IS NULL)
                FROM gateway.model_usage_price
                WHERE model_code = 'image-basic' AND usage_dimension = 'IMAGE'
                  AND resolution_tier = '2K'
                ORDER BY price_version
                """)).containsExactly("image-v1:false", "image-v2:true");

        // 图层拆分的计费标记：它是结算判断所读的那一列，必须存在、非空、默认 false
        //（历史行没有图层调用这一说，false 是它们的真实取值）。
        assertThat(values("""
                SELECT column_name || ':' || is_nullable || ':' || column_default
                FROM information_schema.columns
                WHERE table_schema = 'gateway'
                  AND table_name = 'image_generation_task'
                  AND column_name = 'layer_decomposition'
                """)).containsExactly("layer_decomposition:NO:false");

        // V114 的扇出链接列：一次请求买 N 张时，每一张都是一行独立任务（因为
        // request_id 唯一、而 usage_log/quota_ledger 不允许两张共用一份预留），
        // 这两列是「这一行属于哪次请求的第几张」的唯一线索——重放与恢复扫描都靠它
        // 把一家人捞回来。
        //
        // 历史行必须保持 NULL：它们全都是单图请求，而恢复扫描与 n=1 路径读到的
        // 必须与 V114 之前逐字节相同。两列都可空、无默认值，因此既有行不需要回填。
        //
        // 注意别名必须是 `pgsql`：information_schema.columns 在 MySQL 上叫 `mysql`，
        // 在那里整段表达式会返回 NULL，让断言变成一个看不出原因的 `[null]`。
        // column_default 也要 coalesce，因为这两列本来就没有默认值。
        assertThat(values("""
                SELECT column_name || ':' || is_nullable || ':'
                       || coalesce(column_default, 'none')
                FROM information_schema.columns
                WHERE table_schema = 'gateway'
                  AND table_name = 'image_generation_task'
                  AND column_name IN ('fanout_parent_request_id', 'fanout_index')
                ORDER BY column_name
                """)).containsExactly(
                        "fanout_index:YES:none",
                        "fanout_parent_request_id:YES:none");

        // 半条链接是不可存储的：只有 parent 没有 index（或反之）的行永远无法被重放
        // 找到，而那种行正是扇出最不能产生的东西。索引同样必须存在，否则按 parent
        // 分组读会退化成全表扫描。
        assertThat(constraintDefinition("image_generation_task", "ck_image_task_fanout_pair"))
                .contains("fanout_parent_request_id", "fanout_index");
        assertThat(indexDefinition("ix_image_task_fanout_parent"))
                .contains("image_generation_task", "fanout_parent_request_id", "fanout_index");

        // V83 让一次生图结算有地方写下它的运营证据：结算路径与对话、视频写同一组
        // 事实（usage_log + usage_stage + provider_cost_record + 经济性事件），
        // 而在此之前 ck_usage_stage_type 只承认两种对话阶段与一种视频阶段，
        // 按张的阶段根本没有合法的行可以落。这里断言的是拓宽而不是替换：
        // 三种既有阶段一个都不能少，否则 V13/V49 的证据会被新约束拒绝。
        assertThat(constraintDefinition("usage_stage", "ck_usage_stage_type"))
                .contains("IMAGE_GENERATION", "VIDEO_GENERATION", "AUDIO_GENERATION",
                        "FINAL_RESPONSE", "FILE_ANALYSIS");
        // 恰好一次的保证是约束级的，不是约定级的：四张表各自的冲突目标都必须存在，
        // 否则一次重放就会写出第二套证据，或者第二笔供应商成本记录。
        assertThat(indexDefinition("usage_log_request_id_key")).contains("UNIQUE", "request_id");
        assertThat(indexDefinition("usage_log_reservation_id_key"))
                .contains("UNIQUE", "reservation_id");
        assertThat(indexDefinition("provider_cost_record_usage_log_id_key"))
                .contains("UNIQUE", "usage_log_id");
        assertThat(indexDefinition("usage_stage_usage_log_id_stage_index_key"))
                .contains("UNIQUE", "usage_log_id", "stage_index");
        assertThat(indexDefinition("uq_usage_economics_outbox_fingerprint"))
                .contains("UNIQUE", "event_fingerprint");
        // 张数维度同样不得低于 30% 毛利地板。
        assertThat(singleLong("""
                SELECT count(*) FROM gateway.image_effective_cost
                WHERE unit_price * 7 < effective_unit_cost * 10
                """)).isZero();

        // 天翼渠道按 V71 先例登记：无凭据、禁用、健康度未知。
        // 因此这些行本身并不能让图片别名可调用——POST /v1/images/generations
        // 的候选查询仍然要求已启用的渠道、健康的健康度与真实的凭证。
        assertThat(values("""
                SELECT channel.code || ':' || channel.enabled || ':'
                       || channel.last_health_status || ':'
                       || (channel.encrypted_api_key IS NULL) || ':' || channel.base_url || ':'
                       || (channel.settings ->> 'credentialOwner')
                FROM gateway.channel channel
                WHERE channel.code = 'tianyi-image-primary'
                """)).containsExactly(
                        "tianyi-image-primary:false:UNKNOWN:true:https://ai.ctaigw.cn:system-gateway");

        // 生图是**同步**协议，所以这个预算就是「一次生成允许多久」，
        // 它绝不能低于 TianYiYunImageGenerationAdapter.CALL_TIMEOUT_CAP（240 秒）。
        // 历史：V81 按列默认值登记成 60 秒（单图实测 74 秒，被误杀）；V112 抬到
        // 180000；多参考图上线后实测最长 172 秒（2026-10-12：3 张参考图），
        // 上限放宽到 240 秒，V113 据此把渠道预算抬到 240000。
        // 预算低于上限时，每一次慢生成都会被网关自己掐断，客户端拿到「结果未知」，
        // 恢复扫描之后同一个幂等键永久变成 409 image_generation_previously_failed，
        // 而平台可能已经为那张被丢弃的图付过钱。
        assertThat(singleLong("""
                SELECT timeout_ms FROM gateway.channel WHERE code = 'tianyi-image-primary'
                """)).isGreaterThanOrEqualTo(240_000L);
    }

    @Test
    void refusesAVideoAliasInsideATokenPackageEntitlement() throws SQLException {
        migrateGateway();

        // 该触发器是整套设计所依赖的 schema 规则的最后一道防线：
        // 按秒计费的 alias 绝不能从一个 token 计数器里出账，UI 或 admin-API 的
        // 失误也不能造成这种负债。
        assertThatThrownBy(() -> executeUpdate(entitlementInsert("ent-mixed", """
                [{"modelCode":"video-basic","capabilities":["VIDEO"]},
                 {"modelCode":"text-basic","capabilities":["TEXT"]}]
                """, "TOKEN")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("video_model_in_token_package");

        // 仅视频权益是每个视频 SKU 都采用的形态。
        assertThat(executeUpdate(entitlementInsert("ent-video", """
                [{"modelCode":"video-basic","capabilities":["VIDEO"]}]
                """, "VIDEO_SECOND"))).isEqualTo(1);

        // 混合权益只有在声明了两个相互独立的池时才被允许，企业版 SKU 正是如此，
        // 这样两者就绝不能共享同一份余额。
        assertThat(executeUpdate(entitlementInsert("ent-pools", """
                [{"modelCode":"video-basic","capabilities":["VIDEO"]},
                 {"modelCode":"text-basic","capabilities":["TEXT"]},
                 {"mixedPools":true}]
                """, "VIDEO_SECOND"))).isEqualTo(1);

        // 方案 C（V97）：视频秒与音频秒**各自有一列额度**，因此两者并存不再需要
        // 声明 mixedPools —— 它们本来就没有共享的池。这一条是本次改动最可能
        // 被顺手弄坏的地方：V40 的判据只把 VIDEO_SECOND 数进"按秒"那一侧，
        // 于是这个插入会以 video_model_in_token_package 失败，而失败发生在
        // **发放**那一刻，也就是客户已经付过款之后。
        //
        // 触发器刻意**不**因此扩宽到"音频别名 + token 别名"：那需要把前置条件
        // "有视频别名"改成"有按秒别名"，会同时改变一个今天无人守卫的组合的行为。
        // 音频与 token 混装由平台侧的 resolveDimension 拒绝，那里才是这条规则的
        // 第一道门。
        assertThat(executeUpdate(entitlementInsert("ent-video-audio", """
                [{"modelCode":"video-basic","capabilities":["VIDEO"]},
                 {"modelCode":"audio-asr-basic","capabilities":["AUDIO_TRANSCRIBE"]}]
                """, "VIDEO_SECOND"))).isEqualTo(1);
    }

    /**
     * 方案 C 第 4、5 步的两处 schema 变化：实例上的第二份额度，以及预留记住
     * 自己的计费单位。
     *
     * <p>两者都是"加了就静默生效"的形状：一个漏掉的列不会让任何已有测试失败，
     * 而它带来的后果（音频秒无处安放、或一次 ASR 结算去扣视频秒）只在真实
     * 流量下才显现。因此这里对列、类型、默认值、约束文本与约束**行为**都做断言。
     */
    @Test
    void addsASecondAudioBucketToPackagesAndRecordsAReservationsUnit() throws SQLException {
        migrateGateway();

        assertThat(columnNames("package_instance")).contains(
                "audio_seconds_quota", "audio_seconds_reserved", "audio_seconds_used");
        assertThat(constraintDefinition("package_instance", "ck_package_instance_audio_seconds"))
                .contains("VIDEO_SECOND")
                .contains("audio_seconds_quota");
        // 两个计数器必须是 NOT NULL DEFAULT 0：可空会让 NULL 流进这条约束的
        // 算术里，而 NULL 在 CHECK 里是通过。
        assertThat(values("""
                SELECT column_name || ':' || is_nullable || ':' || column_default
                FROM information_schema.columns
                WHERE table_schema = 'gateway' AND table_name = 'package_instance'
                  AND column_name IN ('audio_seconds_reserved', 'audio_seconds_used')
                ORDER BY column_name
                """)).containsExactly(
                        "audio_seconds_reserved:NO:0", "audio_seconds_used:NO:0");

        // 预留的计费单位是**存下来的**，不是现算的：一个实例可能同时持有两份
        // 额度，而"这笔预留占的是哪个桶"无法再从实例的主维度反推。
        assertThat(columnNames("quota_reservation")).contains("usage_dimension");
        assertThat(constraintDefinition("quota_reservation", "ck_reservation_usage_dimension"))
                .contains("AUDIO_SECOND");

        // 行为：一个 token 实例不得携带语音额度（这条约束的文本里没有 TOKEN，
        // 因此它必须是被"维度不等于 VIDEO_SECOND"这一侧挡下来的）。
        assertThatThrownBy(() -> executeUpdate("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, package_kind,
                    price_amount, currency, allowed_models, total_token_quota,
                    audio_seconds_quota, max_api_keys, requests_per_minute, tokens_per_minute,
                    max_concurrency, duration_days, status, starts_at, activated_at,
                    expires_at, usage_dimension)
                VALUES (
                    gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
                    gen_random_uuid(), 'identity-audio-on-token',
                    gen_random_uuid(), 'audio-on-token', 'audio on token', 'STANDARD',
                    12900, 'CNY', '[{"modelCode":"text-basic"}]'::jsonb, 60,
                    600, 2, 20, 60, 2, 30, 'ACTIVE', now(), now(),
                    now() + interval '30 days', 'TOKEN')
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_package_instance_audio_seconds");
    }

    @Test
    void seedsStableAliasesProviderMappingsCapabilitiesAndDatedOfficialPrices() throws SQLException {
        migrateGateway();

        // V40 把五个视频 alias 加入了目录，V81 加入了一个生图 alias，
        // V91 加入了一个天翼视觉 alias，V93 加入了三个音频 alias，
        // V95 加入了一个天翼分镜视频 alias，V96 加入了天翼的时间戳字幕 alias，
        // V102 加入了两个客户端文本通道 alias（chat-standard / chat-deep）。
        // 它们让这里保持为精确集合断言，
        // 而不是子集检查，因此意外多出一个 alias 仍会让构建失败。
        assertThat(values("SELECT alias FROM gateway.model_alias ORDER BY alias")).containsExactly(
                "audio-asr-basic", "audio-asr-subtitle", "audio-retalk", "audio-tts-basic",
                "chat-deep", "chat-standard",
                "file-basic", "image-basic", "multimodal-basic", "text-basic", "text-max",
                "text-pro", "text-standard", "video-basic", "video-budget", "video-max",
                "video-minimax", "video-pro", "video-standard", "vision-basic", "vision-pro",
                "vision-tianyi");
        assertThat(values("""
                SELECT provider.code || ':' || model.code || ':' || mapping.upstream_model
                FROM gateway.channel_model mapping
                JOIN gateway.channel channel ON channel.id = mapping.channel_id
                JOIN gateway.provider provider ON provider.id = channel.provider_id
                JOIN gateway.model model ON model.id = mapping.model_id
                ORDER BY 1
                """)).containsExactly(
                        // ORDER BY 1 对这个拼接串排序，因此本清单必须逐字符有序：
                        // 'audio-' < 'chat-' < 'file-' < 'text-' < 'video-' < 'vision-'，
                        // 而 'bailian:' < 'deepseek:' < 'tianyi:'。
                        //
                        // V96 把 ASR 换到天翼并删掉百炼那条；V108 又把百炼两条 ASR 路由
                        // 加回来（priority_override=200，作为天翼未开通 filetrans 时的替代），
                        // 因此 bailian 分组里重新出现 audio-asr-basic / audio-asr-subtitle。
                        // V105 把 audio-tts-basic 的上游名从 qwen-audio-3.0-tts-flash 修正为
                        // qwen3-tts-flash（公网百炼的合法 id）。
                        "bailian:audio-asr-basic:qwen-audio-3.0-asr-flash",
                        "bailian:audio-asr-subtitle:qwen-audio-3.0-asr-flash-filetrans",
                        "bailian:audio-retalk:videoretalk",
                        "bailian:audio-tts-basic:qwen3-tts-flash",
                        "bailian:file-basic:qwen-long",
                        "bailian:text-basic:qwen-turbo",
                        "bailian:text-max:qwen-max",
                        "bailian:text-pro:qwen-plus",
                        "bailian:video-basic:wan2.6-t2v",
                        "bailian:video-budget:wan2.5-t2v-preview",
                        "bailian:video-max:kling/kling-v3-omni-video-generation",
                        "bailian:video-pro:kling/kling-v3-turbo-video-generation",
                        "bailian:video-standard:wan2.6-t2v",
                        "bailian:vision-basic:qwen-vl-plus",
                        "bailian:vision-pro:qwen-vl-max",
                        "deepseek:text-basic:deepseek-chat",
                        "deepseek:text-pro:deepseek-reasoner",
                        "deepseek:text-standard:deepseek-chat",
                        // V96：ASR 换回天翼（需求指定），并新增时间戳字幕别名。
                        // 同步的 -flash 不返回时间戳，字幕由异步的 -filetrans 产出。
                        "tianyi:audio-asr-basic:qwen-audio-3.0-asr-flash",
                        "tianyi:audio-asr-subtitle:qwen-audio-3.0-asr-flash-filetrans",
                        // V102：两个客户端文本通道挂在 tianyi-chat-primary 上。
                        "tianyi:chat-deep:Qwen2.5-VL-72B-Instruct",
                        "tianyi:chat-standard:Qwen2.5-VL-72B-Instruct",
                        "tianyi:image-basic:doubao-seedream-5.0-pro",
                        "tianyi:video-minimax:minimax-h3-max",
                        "tianyi:vision-tianyi:Qwen2.5-VL-72B-Instruct");
        // V71 记录的是真实的供应商模型 id，但在运维提供
        // 匹配的工作空间端点与加密 key 之前，无法让它们看起来可调用。
        assertThat(values("""
                SELECT channel.code || ':' || channel.enabled || ':'
                       || channel.last_health_status || ':'
                       || (channel.encrypted_api_key IS NULL) || ':' || channel.base_url
                FROM gateway.channel channel
                WHERE channel.code = 'bailian-video-primary'
                """)).containsExactly(
                        "bailian-video-primary:false:UNKNOWN:true:https://dashscope.aliyuncs.com");
        assertThat(values("""
                SELECT DISTINCT mapping.capabilities::text
                FROM gateway.channel_model mapping
                JOIN gateway.channel channel ON channel.id = mapping.channel_id
                WHERE channel.code = 'bailian-video-primary'
                """)).containsExactly("[\"VIDEO\"]");
        assertThat(values("""
                SELECT provider.code || ':' || model.code || ':'
                       || COALESCE(mapping.priority_override, channel.priority)
                FROM gateway.channel_model mapping
                JOIN gateway.channel channel ON channel.id = mapping.channel_id
                JOIN gateway.provider provider ON provider.id = channel.provider_id
                JOIN gateway.model model ON model.id = mapping.model_id
                WHERE model.code = 'text-basic' AND provider.code IN ('bailian', 'deepseek')
                ORDER BY COALESCE(mapping.priority_override, channel.priority) DESC
                """)).containsExactly(
                        "bailian:text-basic:110",
                        "deepseek:text-basic:100");
        assertThat(values("""
                SELECT DISTINCT capability.value
                FROM gateway.channel_model mapping
                CROSS JOIN LATERAL jsonb_array_elements_text(mapping.capabilities) capability(value)
                JOIN gateway.channel channel ON channel.id = mapping.channel_id
                JOIN gateway.provider provider ON provider.id = channel.provider_id
                WHERE provider.code = 'deepseek'
                ORDER BY 1
                """)).containsExactly("TEXT");
        assertThat(singleLong("SELECT count(*) FROM gateway.provider_model_price")).isEqualTo(14);
        // V48 关闭了八条播种行中的五条并插入了第六条，因此当前价格卡以供应商报价单
        // 为依据：六条已修正的行，加上没有任何供应商文档与之矛盾的
        // 三行（qwen-long 上的 file-basic，以及 text-basic 和 text-pro 的两条旧百炼
        // 行——它们的 qwen-turbo/qwen-plus 价格两份文档都未覆盖）。
        // 六条已修正的行才是引用了文档的那些。
        assertThat(values("""
                SELECT provider.code || ':' || model.code || ':'
                       || price.input_price_per_million || ':' || price.output_price_per_million || ':'
                       || COALESCE(price.cache_read_price_per_million::text, '-') || ':'
                       || price.currency || ':' || trim_scale(price.internal_exchange_rate)::text
                FROM gateway.provider_model_price price
                JOIN gateway.provider provider ON provider.id = price.provider_id
                JOIN gateway.model model ON model.id = price.model_id
                WHERE price.effective_to IS NULL
                ORDER BY 1
                """)).containsExactly(
                        "bailian:file-basic:50:200:-:CNY:1",
                        "bailian:text-basic:14:28:-:CNY:1",
                        "bailian:text-max:1200:3600:-:CNY:1",
                        "bailian:text-pro:40:160:-:CNY:1",
                        "bailian:vision-basic:120:720:-:CNY:1",
                        "bailian:vision-pro:200:1200:-:CNY:1",
                        "deepseek:text-basic:200:800:4:CNY:1",
                        "deepseek:text-pro:900:2700:30:CNY:1",
                        "deepseek:text-standard:300:900:10:CNY:1");
        // V48 替换掉的行是被关闭，而不是被改写或删除，因此一个已经按其中某一行
        // 定过价的请求，仍然解析到它运行时生效的那个数字——
        // 包括那两条依据已被废止的 USD 行。
        assertThat(values("""
                SELECT provider.code || ':' || model.code || ':'
                       || price.input_price_per_million || ':' || price.output_price_per_million || ':'
                       || price.currency || ':' || trim_scale(price.internal_exchange_rate)::text
                FROM gateway.provider_model_price price
                JOIN gateway.provider provider ON provider.id = price.provider_id
                JOIN gateway.model model ON model.id = price.model_id
                WHERE price.effective_to IS NOT NULL
                ORDER BY 1
                """)).containsExactly(
                        "bailian:text-max:250:750:CNY:1",
                        "bailian:vision-basic:15:60:CNY:1",
                        "bailian:vision-pro:20:160:CNY:1",
                        "deepseek:text-pro:44:87:USD:7.2",
                        "deepseek:text-standard:14:28:USD:7.2");
        // 每一个当前行都是汇率为 1 的人民币数字，因此 ProviderCostCalculator
        // 记录的就是供应商自己公布的数字，不承担任何汇率风险。
        assertThat(singleLong("""
                SELECT count(*) FROM gateway.provider_model_price
                WHERE effective_to IS NULL
                  AND (currency <> 'CNY' OR internal_exchange_rate <> 1)
                """)).isZero();
        // 缓存命中花费绝不能超过未命中，无论它属于哪一类行。
        assertThat(singleLong("""
                SELECT count(*) FROM gateway.provider_model_price
                WHERE effective_to IS NULL
                  AND cache_read_price_per_million IS NOT NULL
                  AND cache_read_price_per_million > input_price_per_million
                """)).isZero();
        // 只有六条已修正的行引用了供应商文档，八条播种行则保留它们写入时
        // 就已确定的 effective_from 日期。
        assertThat(singleLong("""
                SELECT count(*) FROM gateway.provider_model_price
                WHERE official_source NOT LIKE 'invoice:%' AND official_source NOT LIKE 'pdf:%'
                """)).isEqualTo(8);
        assertThat(values("""
                SELECT DISTINCT to_char(effective_from AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')
                FROM gateway.provider_model_price
                WHERE official_source NOT LIKE 'invoice:%' AND official_source NOT LIKE 'pdf:%'
                """)).containsExactly("2026-07-30T00:00:00Z");
    }

    @Test
    void reclaimsAndFencesStaleProviderFileBindings() {
        migrateGateway();
        DatabaseClient database = gatewayDatabase();
        PostgresProviderFileBindingRepository repository =
                new PostgresProviderFileBindingRepository(database);
        UUID fileId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        UUID staleToken = UUID.randomUUID();
        UUID channelId = database.sql("SELECT id FROM gateway.channel ORDER BY id LIMIT 1")
                .map((row, metadata) -> row.get("id", UUID.class))
                .one().block(Duration.ofSeconds(3));
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        database.sql("""
                        INSERT INTO gateway.file_object(
                            id, external_file_id, tenant_id, owner_user_id, object_key,
                            original_filename, sha256, mime_type, size_bytes, status,
                            expires_at, created_at, updated_at)
                        VALUES (:id, :external_id, :tenant_id, :owner_id, :object_key,
                            'stale.pdf', :sha256, 'application/pdf', 10, 'READY',
                            :expires_at, :created_at, :created_at)
                        """)
                .bind("id", fileId)
                .bind("external_id", "file_" + UUID.randomUUID())
                .bind("tenant_id", UUID.randomUUID())
                .bind("owner_id", UUID.randomUUID())
                .bind("object_key", "files/" + UUID.randomUUID())
                .bind("sha256", "0".repeat(64))
                .bind("expires_at", now.plusSeconds(3600))
                .bind("created_at", now)
                .fetch().rowsUpdated().block(Duration.ofSeconds(3));
        database.sql("""
                        INSERT INTO gateway.provider_file_binding(
                            id, file_object_id, channel_id, status, claim_token,
                            created_at, updated_at)
                        VALUES (:id, :file_id, :channel_id, 'UPLOADING', :claim_token,
                            now() - interval '10 minutes', now() - interval '10 minutes')
                        """)
                .bind("id", bindingId)
                .bind("file_id", fileId)
                .bind("channel_id", channelId)
                .bind("claim_token", staleToken)
                .fetch().rowsUpdated().block(Duration.ofSeconds(3));

        ProviderFileBindingRepository.Binding reclaimed = repository.claim(fileId, channelId)
                .block(Duration.ofSeconds(3));

        assertThat(reclaimed).isNotNull();
        assertThat(reclaimed.claimToken()).isNotNull().isNotEqualTo(staleToken);
        repository.markReady(bindingId, staleToken, "stale-upstream")
                .block(Duration.ofSeconds(3));
        assertThat(bindingValue(database, bindingId, "upstream_file_id")).isNull();
        repository.markReady(bindingId, reclaimed.claimToken(), "current-upstream")
                .block(Duration.ofSeconds(3));
        assertThat(bindingValue(database, bindingId, "upstream_file_id"))
                .isEqualTo("current-upstream");
        assertThat(bindingValue(database, bindingId, "status")).isEqualTo("READY");
    }

    @Test
    void reclaimsStaleManualDeletionBeforeFileExpiry() {
        migrateGateway();
        DatabaseClient database = gatewayDatabase();
        UUID fileId = UUID.randomUUID();
        database.sql("""
                        INSERT INTO gateway.file_object(
                            id, external_file_id, tenant_id, owner_user_id, object_key,
                            original_filename, sha256, mime_type, size_bytes, status,
                            expires_at, created_at, updated_at)
                        VALUES (:id, :external_id, :tenant_id, :owner_id, :object_key,
                            'manual.pdf', :sha256, 'application/pdf', 10, 'DELETING',
                            now() + interval '23 hours', now() - interval '10 minutes',
                            now() - interval '10 minutes')
                        """)
                .bind("id", fileId)
                .bind("external_id", "file_" + UUID.randomUUID())
                .bind("tenant_id", UUID.randomUUID())
                .bind("owner_id", UUID.randomUUID())
                .bind("object_key", "files/" + UUID.randomUUID())
                .bind("sha256", "0".repeat(64))
                .fetch().rowsUpdated().block(Duration.ofSeconds(3));

        var claimed = new PostgresFileObjectRepository(database).claimExpired(10)
                .collectList().block(Duration.ofSeconds(3));

        assertThat(claimed).extracting(file -> file.id()).contains(fileId);
    }

    @Test
    void fencesAStaleManualDeleteAfterCleanupReclaimsTheFile() {
        migrateGateway();
        DatabaseClient database = gatewayDatabase();
        PostgresFileObjectRepository repository = new PostgresFileObjectRepository(database);
        UUID fileId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String externalId = "file_" + UUID.randomUUID();
        database.sql("""
                        INSERT INTO gateway.file_object(
                            id, external_file_id, tenant_id, owner_user_id, object_key,
                            original_filename, sha256, mime_type, size_bytes, status,
                            expires_at, created_at, updated_at)
                        VALUES (:id, :external_id, :tenant_id, :owner_id, :object_key,
                            'manual.pdf', :sha256, 'application/pdf', 10, 'READY',
                            now() + interval '23 hours', now(), now())
                        """)
                .bind("id", fileId)
                .bind("external_id", externalId)
                .bind("tenant_id", tenantId)
                .bind("owner_id", ownerId)
                .bind("object_key", "files/" + UUID.randomUUID())
                .bind("sha256", "0".repeat(64))
                .fetch().rowsUpdated().block(Duration.ofSeconds(3));

        var original = repository.claimDelete(externalId, tenantId, ownerId)
                .block(Duration.ofSeconds(3));
        database.sql("UPDATE gateway.file_object SET updated_at = now() - interval '10 minutes' WHERE id = :id")
                .bind("id", fileId).fetch().rowsUpdated().block(Duration.ofSeconds(3));
        var reclaimed = repository.claimExpired(10).next().block(Duration.ofSeconds(3));

        assertThat(original).isNotNull();
        assertThat(reclaimed).isNotNull();
        assertThat(reclaimed.deletionClaimToken())
                .isNotNull().isNotEqualTo(original.deletionClaimToken());
        repository.restoreReady(fileId, original.deletionClaimToken()).block(Duration.ofSeconds(3));
        repository.markDeleted(fileId, original.deletionClaimToken(), Instant.now())
                .block(Duration.ofSeconds(3));
        assertThat(fileValue(database, fileId, "status")).isEqualTo("DELETING");
        assertThat(fileValue(database, fileId, "deletion_claim_token"))
                .isEqualTo(reclaimed.deletionClaimToken().toString());

        repository.markDeleted(fileId, reclaimed.deletionClaimToken(), Instant.now())
                .block(Duration.ofSeconds(3));
        assertThat(fileValue(database, fileId, "status")).isEqualTo("DELETED");
    }

    @Test
    void claimsEveryExpiredFileStateOnceButLeavesActiveScanningAlone() {
        migrateGateway();
        DatabaseClient database = gatewayDatabase();
        Instant now = Instant.now();
        Set<UUID> expected = new LinkedHashSet<>();
        expected.add(insertCleanupCandidate(database, "PENDING_UPLOAD",
                now.plusSeconds(3600), now.minusSeconds(1), null));
        expected.add(insertCleanupCandidate(database, "UPLOADED",
                now.minusSeconds(1), null, null));
        expected.add(insertCleanupCandidate(database, "SCANNING",
                now.minusSeconds(1), null, now.minusSeconds(600)));
        expected.add(insertCleanupCandidate(database, "REJECTED",
                now.minusSeconds(1), null, null));
        expected.add(insertCleanupCandidate(database, "FAILED",
                now.minusSeconds(1), null, null));
        expected.add(insertCleanupCandidate(database, "READY",
                now.minusSeconds(1), null, null));
        UUID activeScanning = insertCleanupCandidate(database, "SCANNING",
                now.minusSeconds(1), null, now);

        PostgresFileObjectRepository repository = new PostgresFileObjectRepository(database);
        var first = repository.claimExpired(20).collectList().block(Duration.ofSeconds(3));
        var second = repository.claimExpired(20).collectList().block(Duration.ofSeconds(3));

        assertThat(first).extracting(file -> file.id()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(first).allSatisfy(file -> {
            assertThat(file.status()).isEqualTo("DELETING");
            assertThat(file.deletionClaimToken()).isNotNull();
        });
        assertThat(second).isEmpty();
        assertThat(fileValue(database, activeScanning, "status")).isEqualTo("SCANNING");
    }

    @Test
    void liveObjectKeyLookupIncludesEveryNonDeletedStatus() {
        migrateGateway();
        DatabaseClient database = gatewayDatabase();
        Instant now = Instant.now();
        UUID liveId = insertCleanupCandidate(database, "REJECTED",
                now.plusSeconds(3600), null, null);
        UUID deletedId = insertCleanupCandidate(database, "DELETED",
                now.minusSeconds(1), null, null);
        String liveKey = fileValue(database, liveId, "object_key");
        String deletedKey = fileValue(database, deletedId, "object_key");
        PostgresFileObjectRepository repository = new PostgresFileObjectRepository(database);

        assertThat(repository.existsLiveObjectKey(liveKey).block(Duration.ofSeconds(3))).isTrue();
        assertThat(repository.existsLiveObjectKey(deletedKey).block(Duration.ofSeconds(3))).isFalse();
        assertThat(repository.existsLiveObjectKey("clean/missing").block(Duration.ofSeconds(3))).isFalse();
    }

    /** The clean-object sweeper must retain a published native archive and its source. */
    @Test
    void liveObjectKeyLookupIncludesNativeClawhubSourceAndRelease() {
        migrateGateway();
        DatabaseClient database = gatewayDatabase();
        UUID skillId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        String sha256 = "a".repeat(64);
        String sourceKey = "quarantine/native-clawhub/" + sourceId + "/" + sha256 + ".zip";
        String releaseKey = "clean/native-clawhub/" + skillId + "/" + sha256 + ".zip";

        database.sql("""
                        INSERT INTO gateway.skill(
                            id, code, name, status, published_version, source_format)
                        VALUES (:id, :code, 'Native ClawHub regression', 'PUBLISHED',
                                '1.0.0', 'CLAWHUB_SKILL')
                        """)
                .bind("id", skillId)
                .bind("code", "native-ref-" + skillId.toString().substring(0, 8))
                .then()
                .block(Duration.ofSeconds(3));
        database.sql("""
                        INSERT INTO gateway.native_clawhub_source(
                            id, skill_id, owner, slug, source_version, display_name,
                            source_sha256, size_bytes, file_name, object_key, status,
                            adapter_version)
                        VALUES (:id, :skill_id, 'regression', 'archive', '1.0.0',
                                'Native ClawHub regression', :sha256, 10, 'archive.zip',
                                :object_key, 'PUBLISHED', 'test')
                        """)
                .bind("id", sourceId)
                .bind("skill_id", skillId)
                .bind("sha256", sha256)
                .bind("object_key", sourceKey)
                .then()
                .block(Duration.ofSeconds(3));
        database.sql("""
                        INSERT INTO gateway.native_clawhub_release(
                            id, skill_id, source_id, version, source_sha256,
                            size_bytes, object_key)
                        VALUES (:id, :skill_id, :source_id, '1.0.0', :sha256,
                                10, :object_key)
                        """)
                .bind("id", UUID.randomUUID())
                .bind("skill_id", skillId)
                .bind("source_id", sourceId)
                .bind("sha256", sha256)
                .bind("object_key", releaseKey)
                .then()
                .block(Duration.ofSeconds(3));

        PostgresFileObjectRepository repository = new PostgresFileObjectRepository(database);
        assertThat(repository.existsLiveObjectKey(sourceKey).block(Duration.ofSeconds(3))).isTrue();
        assertThat(repository.existsLiveObjectKey(releaseKey).block(Duration.ofSeconds(3))).isTrue();
    }

    /**
     * 类目封面只被 {@code gateway.skill_category.image_object_key} 引用，因此
     * 孤儿清理探针也必须从该列解析它。
     *
     * <p>这是对一次真实数据丢失的回归守卫：该探针列出了技能图标、成员
     * 头像、版本归档与下载快照，却没有列出类目封面。于是每一张上传的
     * 封面都看起来无人引用，定时的清理约一小时后把它删掉——
     * 控制台仍保留着指针，而对象已经不在，因此即便管理员
     * 上传了图片，卡片与桌面端都回退到了占位图。
     */
    @Test
    void liveObjectKeyLookupIncludesCategoryCovers() {
        migrateGateway();
        DatabaseClient database = gatewayDatabase();
        String coverKey = "clean/skill-categories/regression/cover/" + UUID.randomUUID() + ".png";
        UUID categoryId = UUID.randomUUID();
        database.sql("""
                        INSERT INTO gateway.skill_category(
                            id, kind, code, name, sort_order, enabled,
                            image_object_key, image_updated_at, created_at, updated_at)
                        VALUES (:id, 'EXPERT', :code, :name, 0, true,
                                :object_key, now(), now(), now())
                        """)
                .bind("id", categoryId)
                .bind("code", "regression-cover")
                .bind("name", "回归封面")
                .bind("object_key", coverKey)
                .then()
                .block(Duration.ofSeconds(3));

        PostgresFileObjectRepository repository = new PostgresFileObjectRepository(database);

        assertThat(repository.existsLiveObjectKey(coverKey).block(Duration.ofSeconds(3))).isTrue();
    }

    /** V66 只存指针；图片字节仍留在对象存储中。 */
    @Test
    void addsExampleImagePointerAndProtectsItsObjectFromOrphanCleanup() throws SQLException {
        migrateGateway();

        assertThat(columnNames("skill"))
                .contains("example_image_object_key", "example_image_updated_at");

        DatabaseClient database = gatewayDatabase();
        UUID skillId = UUID.randomUUID();
        String code = "example-image-" + skillId.toString().substring(0, 8);
        String objectKey = "clean/skills/" + skillId + "/" + code
                + "/example-image/" + UUID.randomUUID() + ".png";
        database.sql("""
                        INSERT INTO gateway.skill(
                            id, code, name, status,
                            example_image_object_key, example_image_updated_at)
                        VALUES (:id, :code, 'Example image regression', 'DRAFT',
                                :object_key, now())
                        """)
                .bind("id", skillId)
                .bind("code", code)
                .bind("object_key", objectKey)
                .then()
                .block(Duration.ofSeconds(3));

        PostgresFileObjectRepository repository = new PostgresFileObjectRepository(database);

        assertThat(repository.existsLiveObjectKey(objectKey).block(Duration.ofSeconds(3))).isTrue();
    }

    /**
     * 已发布的原生 ClawHub 包必须被视为「活对象」，否则孤儿清理任务会把它删掉。
     *
     * <p>背景：发布时包体从 {@code quarantine/} 复制到 {@code clean/}，只登记在
     * {@code native_clawhub_release.object_key} 上（草稿行在 {@code native_clawhub_source.object_key}）。
     * 若 {@code existsLiveObjectKey} 不认这两个来源，清理任务会在约一小时后删掉对象，
     * 而数据库仍显示 PUBLISHED —— 下载从此 500。
     *
     * <p>原来的 {@code replacesOnlyLegacyYcpsExpertTagsWithBusinessCapabilities} 已按产品口径删除：
     * skill.tags 只保留 {@code ['ycps']} 运营标记（业务标签归 task_types），
     * 该断言的方向已被 V86 反转，由 {@code restoresCuratedOperationalTagsWithoutOverwritingAdministratorTags} 覆盖。
     */
    @Test
    void protectsPublishedNativeClawhubObjectsFromOrphanCleanup() throws SQLException {
        migrateGateway();

        DatabaseClient database = gatewayDatabase();
        UUID skillId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        String code = "native-clawhub-" + skillId.toString().substring(0, 8);
        String sha = "a".repeat(64);
        String quarantineKey = "quarantine/native-clawhub/" + skillId + "/" + sha + ".zip";
        String cleanKey = "clean/native-clawhub/" + skillId + "/" + sha + ".zip";

        database.sql("""
                        INSERT INTO gateway.skill(id, code, name, status)
                        VALUES (:id, :code, 'Native clawhub regression', 'DRAFT')
                        """)
                .bind("id", skillId)
                .bind("code", code)
                .then()
                .block(Duration.ofSeconds(3));
        database.sql("""
                        INSERT INTO gateway.native_clawhub_source(
                            id, skill_id, owner, slug, display_name, source_sha256,
                            size_bytes, file_name, object_key, adapter_version)
                        VALUES (:id, :skill_id, 'yuzhi', :slug, 'Native clawhub regression',
                                :sha, 1024, 'archive.zip', :object_key, 'clawhub-v1')
                        """)
                .bind("id", sourceId)
                .bind("skill_id", skillId)
                .bind("slug", code)
                .bind("sha", sha)
                .bind("object_key", quarantineKey)
                .then()
                .block(Duration.ofSeconds(3));
        database.sql("""
                        INSERT INTO gateway.native_clawhub_release(
                            id, skill_id, source_id, version, source_sha256, size_bytes, object_key)
                        VALUES (gen_random_uuid(), :skill_id, :source_id, '1.0.0', :sha, 1024,
                                :object_key)
                        """)
                .bind("skill_id", skillId)
                .bind("source_id", sourceId)
                .bind("sha", sha)
                .bind("object_key", cleanKey)
                .then()
                .block(Duration.ofSeconds(3));

        PostgresFileObjectRepository repository = new PostgresFileObjectRepository(database);

        assertThat(repository.existsLiveObjectKey(cleanKey).block(Duration.ofSeconds(3)))
                .as("已发布的 clean/ 包体不得被孤儿清理删除")
                .isTrue();
        assertThat(repository.existsLiveObjectKey(quarantineKey).block(Duration.ofSeconds(3)))
                .as("草稿期的 quarantine/ 包体同样要保护")
                .isTrue();
    }

    @Test
    void backfillsAllSixCuratedExpertPresentationsAndAdvancesEachAggregateOnce() throws SQLException {
        cleanAndMigrateGatewayTo("72");
        try (Connection connection = gatewayConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO gateway.skill(id, code, name, kind, tags, row_version)
                    VALUES
                      ('00000000-0000-0000-0000-000000000731', 'ai-office-assistant', 'AI 办公助手', 'EXPERT', ARRAY['legacy'], 4),
                      ('00000000-0000-0000-0000-000000000732', 'content-summarizer', '内容总结专家', 'EXPERT', ARRAY['legacy'], 4),
                      ('00000000-0000-0000-0000-000000000733', 'content-writing-expert', '内容写作专家', 'EXPERT', ARRAY['legacy'], 4),
                      ('00000000-0000-0000-0000-000000000734', 'document-review-expert', '文档审校专家', 'EXPERT', ARRAY['legacy'], 4),
                      ('00000000-0000-0000-0000-000000000735', 'sales-analysis-expert', '销售分析专家', 'EXPERT', ARRAY['legacy'], 4),
                      ('00000000-0000-0000-0000-000000000736', 'social-media-operations-expert', '社媒运营专家', 'EXPERT', ARRAY['legacy'], 4);

                    INSERT INTO gateway.skill_expert_profile(skill_id, task_types)
                    SELECT id, ARRAY['legacy']::text[]
                    FROM gateway.skill
                    WHERE code IN (
                      'ai-office-assistant', 'content-summarizer', 'content-writing-expert',
                      'document-review-expert', 'sales-analysis-expert',
                      'social-media-operations-expert');
                    """);
        }

        migrateGateway();

        assertThat(singleLong("""
                SELECT count(*)
                FROM gateway.skill skill
                JOIN gateway.skill_expert_profile profile ON profile.skill_id = skill.id
                WHERE skill.code IN (
                  'ai-office-assistant', 'content-summarizer', 'content-writing-expert',
                  'document-review-expert', 'sales-analysis-expert',
                  'social-media-operations-expert')
                  AND skill.row_version = CASE
                    WHEN skill.code IN ('ai-office-assistant', 'content-summarizer',
                                        'sales-analysis-expert') THEN 6
                    ELSE 5
                  END
                  AND cardinality(profile.task_types) BETWEEN 3 AND 5
                  AND cardinality(profile.task_types_en) = cardinality(profile.task_types)
                  AND profile.usage_guide IS NOT NULL
                  AND profile.usage_guide_en IS NOT NULL
                  AND jsonb_array_length(profile.example_prompts) BETWEEN 2 AND 5
                  AND jsonb_array_length(profile.example_prompts_en)
                      = jsonb_array_length(profile.example_prompts)
                  AND jsonb_typeof(profile.preview_zh) = 'object'
                  AND jsonb_typeof(profile.preview_en) = 'object'
                  AND jsonb_array_length(profile.preview_zh -> 'outputParagraphs') BETWEEN 1 AND 8
                  AND jsonb_array_length(profile.preview_en -> 'outputParagraphs') BETWEEN 1 AND 8
                  AND CASE
                    WHEN skill.code IN ('ai-office-assistant', 'content-summarizer',
                                        'sales-analysis-expert')
                    THEN jsonb_typeof(profile.preview_zh -> 'intro') = 'object'
                     AND jsonb_typeof(profile.preview_en -> 'intro') = 'object'
                     AND jsonb_typeof(profile.preview_zh -> 'table') = 'object'
                     AND jsonb_typeof(profile.preview_en -> 'table') = 'object'
                    ELSE profile.preview_zh -> 'table' IS NULL
                     AND profile.preview_en -> 'table' IS NULL
                  END
                """)).isEqualTo(6);
        assertThat(values("""
                SELECT preview_zh ->> 'heading'
                FROM gateway.skill_expert_profile profile
                JOIN gateway.skill skill ON skill.id = profile.skill_id
                WHERE skill.code = 'document-review-expert'
                """)).containsExactly("一句话，找出差异");
    }

    @Test
    void curatedExpertBackfillNeverOverwritesAnAdministratorPresentation() throws SQLException {
        cleanAndMigrateGatewayTo("72");
        try (Connection connection = gatewayConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO gateway.skill(id, code, name, kind, tags, row_version)
                    VALUES ('00000000-0000-0000-0000-000000000737',
                            'content-summarizer', '内容总结专家', 'EXPERT', ARRAY['legacy'], 9);

                    INSERT INTO gateway.skill_expert_profile(
                        skill_id, task_types, usage_guide, example_prompts, preview_zh)
                    VALUES (
                        '00000000-0000-0000-0000-000000000737',
                        ARRAY['管理员标签'], '管理员说明', '["管理员示例"]'::jsonb,
                        '{"heading":"管理员标题","request":"请求","outputTitle":"输出","outputParagraphs":["正文"]}'::jsonb);
                    """);
        }

        migrateGateway();

        assertThat(values("""
                SELECT skill.row_version || ':' || profile.usage_guide || ':'
                       || (profile.preview_zh ->> 'heading')
                FROM gateway.skill skill
                JOIN gateway.skill_expert_profile profile ON profile.skill_id = skill.id
                WHERE skill.code = 'content-summarizer'
                """)).containsExactly("9:管理员说明:管理员标题");
    }

    @Test
    void repairsOnlyTheKnownMalformedAiOfficeCanvasAndPreservesPresentationAssets() throws SQLException {
        cleanAndMigrateGatewayTo("75");
        try (Connection connection = gatewayConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO gateway.skill(
                        id, code, name, kind, status, published_version, row_version,
                        icon_object_key, illustration_object_key, example_image_object_key)
                    VALUES ('00000000-0000-0000-0000-000000000738', 'ai-office-assistant',
                            'AI办公助手', 'EXPERT', 'PUBLISHED', '1.0.0', 25,
                            'clean/ai-office/icon.png', 'clean/ai-office/illustration.webp',
                            'clean/ai-office/example.png');

                    WITH nodes AS (
                        SELECT n,
                               jsonb_build_object(
                                   'id', CASE WHEN n = 1 THEN 'preview-heading'
                                             WHEN n = 25 THEN 'asset-example-image'
                                             ELSE 'node-' || n END,
                                   'kind', CASE WHEN n = 25 THEN 'image' ELSE 'text' END,
                                   'field', CASE WHEN n = 1 THEN 'preview.heading'
                                                 WHEN n = 25 THEN 'asset.exampleImage'
                                                 ELSE 'unused.' || n END,
                                   'x', n - 1, 'y', n - 1, 'width', 1, 'height', 1,
                                   'runs', CASE WHEN n = 1
                                       THEN jsonb_build_array(
                                           jsonb_build_object('text', '一句话，'),
                                           jsonb_build_object('text', '理清表格', 'color', '#80ffff'))
                                       ELSE jsonb_build_array(jsonb_build_object('text', 'x')) END,
                                   'assetRole', CASE WHEN n = 25 THEN 'exampleImage' END
                               ) AS node
                        FROM generate_series(1, 25) AS series(n)
                    )
                    INSERT INTO gateway.skill_expert_profile(
                        skill_id, task_types, task_types_en, usage_guide, usage_guide_en,
                        example_prompts, example_prompts_en, preview_zh, preview_en,
                        portable_revision, canvas_zh)
                    SELECT '00000000-0000-0000-0000-000000000738',
                           ARRAY['办公文件处理']::text[], ARRAY['Office-file processing']::text[],
                           '添加要处理的办公文件。', 'Attach the office files.',
                           '["从多个 Excel 汇总工资和考勤"]'::jsonb,
                           '["Reconcile payroll and attendance"]'::jsonb,
                           '{"heading":"一句话，理清表格","request":"帮我汇总考勤表。","outputTitle":"考勤汇总与核对","outputParagraphs":["单位：天","姓名｜应出勤｜实际出勤｜差额","林然｜22｜21｜1","周宁｜22｜22｜0","合计｜44｜43｜1","核对结果：姓名均可对应。","需要核实：林然有 1 天差额。","差额不直接认定为旷工。"]}'::jsonb,
                           '{"heading":"One sentence to organize your data","request":"Combine these sheets.","outputTitle":"Attendance summary and checks","outputParagraphs":["Unit: days","Name | Expected | Actual | Difference","Lin Ran | 22 | 21 | 1","Zhou Ning | 22 | 22 | 0","Total | 44 | 43 | 1","Names match.","Check the one-day difference.","Difference alone does not establish an absence."]}'::jsonb,
                           4,
                           jsonb_build_object('version', 1, 'width', 1080, 'height', 704,
                               'nodes', (SELECT jsonb_agg(node ORDER BY n) FROM nodes));
                    """);
        }

        // Verify V76 independently of V81's later preview-table conversion.
        migrateGatewayTo("76");

        assertThat(values("""
                SELECT concat(skill.row_version, ':', (profile.canvas_zh IS NULL)::text, ':',
                              (profile.canvas_en IS NULL)::text, ':', skill.icon_object_key, ':',
                              skill.illustration_object_key, ':', skill.example_image_object_key,
                              ':', profile.preview_zh ->> 'outputTitle', ':',
                              jsonb_array_length(profile.preview_zh -> 'outputParagraphs'))
                FROM gateway.skill skill
                JOIN gateway.skill_expert_profile profile ON profile.skill_id = skill.id
                WHERE skill.code = 'ai-office-assistant'
                """)).containsExactly(
                // 布尔是 `(x IS NULL)::text`，PostgreSQL 把它渲染成 **true/false**，
                // 不是 t/f —— 原注释写"布尔经 concat() 渲染为 t/f"是错的，
                // 而这两个用例的期望值正是照着那个错误推断写的。
                // （concat() 对**原生** boolean 才给 t/f；这里已经先 ::text 了。）
                //
                // 数字是迁移链末端状态：row_version 26 而非 27，因为本用例只迁到
                // V76（`migrateGatewayTo("76")`，上面那行注释也写明了"独立于 V81 校验 V76"），
                // 而 27 是更后面那条恢复运营标签的迁移才会推到的值；
                // 末位是该预览的段落数，同样随迁移链变化。
                "26:true:true:clean/ai-office/icon.png:clean/ai-office/illustration.webp:"
                        + "clean/ai-office/example.png:考勤汇总与核对:8");

        // 第二次 Flyway 调用是 no-op；在畸形画布被清除之后，
        // 带守卫的修复无法再次推进 CAS 令牌。
        migrateGateway();
        assertThat(singleLong("""
                SELECT row_version
                FROM gateway.skill
                WHERE code = 'ai-office-assistant'
                """)).isEqualTo(27);
    }

    @Test
    void clearsAnAiOfficeSemanticCanvasResavedAfterTheOriginalRepair() throws SQLException {
        cleanAndMigrateGatewayTo("76");
        try (Connection connection = gatewayConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO gateway.skill(
                        id, code, name, kind, status, published_version, row_version,
                        icon_object_key, illustration_object_key, example_image_object_key)
                    VALUES ('00000000-0000-0000-0000-000000000739', 'ai-office-assistant',
                            'AI办公助手', 'EXPERT', 'PUBLISHED', '1.0.0', 28,
                            'clean/ai-office/icon.png', 'clean/ai-office/illustration.webp',
                            'clean/ai-office/example.png');

                    INSERT INTO gateway.skill_expert_profile(
                        skill_id, task_types, usage_guide, example_prompts, preview_zh,
                        canvas_zh, canvas_en)
                    VALUES (
                        '00000000-0000-0000-0000-000000000739',
                        ARRAY['办公文件处理']::text[], '添加要处理的办公文件。',
                        '["从多个 Excel 汇总工资和考勤"]'::jsonb,
                        '{"heading":"一句话，理清表格","request":"帮我汇总考勤表。",'
                          '"outputTitle":"考勤汇总与核对","outputParagraphs":['
                          '"单位：天","姓名｜应出勤｜实际出勤｜差额","林然｜22｜21｜1",'
                          '"周宁｜22｜22｜0","合计｜44｜43｜1"]}'::jsonb,
                        '{"version":1,"width":1080,"height":704,"nodes":['
                          '{"id":"stale","field":"preview.heading","kind":"text",'
                          '"x":1,"y":1,"width":100,"height":20,'
                          '"runs":[{"text":"一句话，理清表格"}]}]}'::jsonb,
                        '{"version":1,"width":1080,"height":704,"nodes":['
                          '{"id":"stale-en","field":"preview.heading","kind":"text",'
                          '"x":1,"y":1,"width":100,"height":20,'
                          '"runs":[{"text":"Organize the data"}]}]}'::jsonb);
                    """);
        }

        migrateGatewayTo("77");

        assertThat(values("""
                SELECT concat(skill.row_version, ':', (profile.canvas_zh IS NULL)::text, ':',
                              (profile.canvas_en IS NULL)::text, ':', skill.icon_object_key, ':',
                              skill.illustration_object_key, ':', skill.example_image_object_key,
                              ':', profile.preview_zh ->> 'outputTitle')
                FROM gateway.skill skill
                JOIN gateway.skill_expert_profile profile ON profile.skill_id = skill.id
                WHERE skill.code = 'ai-office-assistant'
                """)).containsExactly(
                // 同上：`(x IS NULL)::text` 渲染成 true/false，不是 t/f。
                "29:true:true:clean/ai-office/icon.png:clean/ai-office/illustration.webp:"
                        + "clean/ai-office/example.png:考勤汇总与核对");

        migrateGatewayTo("77");
        assertThat(singleLong("""
                SELECT row_version FROM gateway.skill WHERE code = 'ai-office-assistant'
                """)).isEqualTo(29);
    }

    @Test
    void restoresCuratedOperationalTagsWithoutOverwritingAdministratorTags() throws SQLException {
        cleanAndMigrateGatewayTo("77");
        try (Connection connection = gatewayConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO gateway.skill(id, code, name, kind, tags, row_version)
                    VALUES
                      ('00000000-0000-0000-0000-000000000741', 'ai-office-assistant',
                       'AI办公助手', 'EXPERT',
                       ARRAY['办公文件处理', '表格汇总核验', '跨文件分析', '格式整理']::text[], 11),
                      ('00000000-0000-0000-0000-000000000742', 'content-summarizer',
                       '内容总结助手', 'EXPERT', ARRAY['管理员自定义']::text[], 9),
                      ('00000000-0000-0000-0000-000000000743', 'unrelated-expert',
                       '其他专家', 'EXPERT',
                       ARRAY['办公文件处理', '表格汇总核验', '跨文件分析', '格式整理']::text[], 5);
                    """);
        }

        migrateGateway();

        assertThat(values("""
                SELECT code || ':' || array_to_string(tags, '|') || ':' || row_version
                FROM gateway.skill
                WHERE code IN ('ai-office-assistant', 'content-summarizer', 'unrelated-expert')
                ORDER BY code
                """)).containsExactly(
                        "ai-office-assistant:ycps:12",
                        "content-summarizer:管理员自定义:9",
                        "unrelated-expert:办公文件处理|表格汇总核验|跨文件分析|格式整理:5");

        migrateGateway();
        assertThat(singleLong("""
                SELECT row_version FROM gateway.skill WHERE code = 'ai-office-assistant'
                """)).isEqualTo(12);
    }

    @Test
    void restoresLegacyCuratedTablesWithoutOverwritingAdministratorTables() throws SQLException {
        cleanAndMigrateGatewayTo("85");
        try (Connection connection = gatewayConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO gateway.skill(id, code, name, kind, row_version)
                    VALUES
                      ('00000000-0000-0000-0000-000000000811', 'ai-office-assistant',
                       'AI办公助手', 'EXPERT', 14),
                      ('00000000-0000-0000-0000-000000000812', 'content-summarizer',
                       '内容总结助手', 'EXPERT', 20);

                    INSERT INTO gateway.skill_expert_profile(
                        skill_id, task_types, usage_guide, example_prompts, preview_zh, preview_en)
                    VALUES
                      ('00000000-0000-0000-0000-000000000811', ARRAY['办公文件处理'],
                       '上传文件', '["示例"]'::jsonb,
                       '{"heading":"一句话，理清表格","request":"汇总",'
                         '"outputTitle":"考勤汇总与核对","table":null,"outputParagraphs":['
                         '"单位：天","姓名｜应出勤｜实际出勤｜差额","林然｜22｜21｜1",'
                         '"周宁｜22｜22｜0","合计｜44｜43｜1","核对结果：两个表各 2 人，姓名均可对应，未发现重名记录。",'
                         '"需要核实：林然有 1 天差额，需结合请假或漏打卡记录确认原因。",'
                         '"差额＝应出勤－实际出勤，不直接认定为旷工。"]}'::jsonb,
                       '{"heading":"One sentence to organize your data","request":"Reconcile",'
                         '"outputTitle":"Attendance summary and checks","table":null,"outputParagraphs":['
                         '"Unit: days","Name | Expected | Actual | Difference","Lin Ran | 22 | 21 | 1",'
                         '"Zhou Ning | 22 | 22 | 0","Total | 44 | 43 | 1",'
                         '"Check: Both sheets contain 2 people. Names match and no duplicate names were found.",'
                         '"To verify: Lin Ran has a 1-day difference. Check leave records or missing clock-ins to establish the reason.",'
                         '"Difference = expected - actual days; this alone does not establish an unauthorized absence."]}'::jsonb),
                      ('00000000-0000-0000-0000-000000000812', ARRAY['摘要'],
                       '粘贴材料', '["示例"]'::jsonb,
                       '{"heading":"管理员标题","request":"请求","outputTitle":"输出",'
                         '"outputParagraphs":["正文"],"table":{"headers":["自定义"],'
                         '"columnWidths":[100],"rows":[["保留"]],"totalRow":false}}'::jsonb,
                       '{"heading":"Admin title","request":"Request","outputTitle":"Output",'
                         '"outputParagraphs":["Body"],"table":{"headers":["Custom"],'
                         '"columnWidths":[100],"rows":[["Keep"]],"totalRow":false}}'::jsonb);
                    """);
        }

        migrateGateway();

        assertThat(values("""
                SELECT concat(skill.code, ':', skill.row_version, ':',
                              profile.preview_zh #>> '{intro,text}', ':',
                              profile.preview_zh #>> '{table,headers,0}', ':',
                              profile.preview_zh #>> '{table,rows,2,0}', ':',
                              jsonb_array_length(profile.preview_zh -> 'outputParagraphs'))
                FROM gateway.skill skill
                JOIN gateway.skill_expert_profile profile ON profile.skill_id = skill.id
                WHERE skill.code IN ('ai-office-assistant', 'content-summarizer')
                ORDER BY skill.code
                """)).containsExactly(
                        "ai-office-assistant:15:单位：天:姓名:合计:3",
                        "content-summarizer:20::自定义::1");

        migrateGateway();
        assertThat(values("""
                SELECT code || ':' || row_version
                FROM gateway.skill
                WHERE code IN ('ai-office-assistant', 'content-summarizer')
                ORDER BY code
                """)).containsExactly(
                        "ai-office-assistant:15",
                        "content-summarizer:20");
    }

    private UUID insertCleanupCandidate(
            DatabaseClient database,
            String status,
            Instant expiresAt,
            Instant uploadExpiresAt,
            Instant scanClaimedAt) {
        UUID id = UUID.randomUUID();
        DatabaseClient.GenericExecuteSpec spec = database.sql("""
                        INSERT INTO gateway.file_object(
                            id, external_file_id, tenant_id, owner_user_id, object_key,
                            original_filename, sha256, mime_type, size_bytes, status,
                            upload_expires_at, scan_claimed_at, expires_at, created_at, updated_at)
                        VALUES (:id, :external_id, :tenant_id, :owner_id, :object_key,
                            'cleanup.pdf', :sha256, 'application/pdf', 10, :status,
                            :upload_expires_at, :scan_claimed_at, :expires_at, now(), now())
                        """)
                .bind("id", id)
                .bind("external_id", "file_" + UUID.randomUUID())
                .bind("tenant_id", UUID.randomUUID())
                .bind("owner_id", UUID.randomUUID())
                .bind("object_key", status.equals("READY") ? "clean/" + id : "quarantine/" + id)
                .bind("sha256", "0".repeat(64))
                .bind("status", status)
                .bind("expires_at", expiresAt);
        spec = uploadExpiresAt == null
                ? spec.bindNull("upload_expires_at", Instant.class)
                : spec.bind("upload_expires_at", uploadExpiresAt);
        spec = scanClaimedAt == null
                ? spec.bindNull("scan_claimed_at", Instant.class)
                : spec.bind("scan_claimed_at", scanClaimedAt);
        spec.fetch().rowsUpdated().block(Duration.ofSeconds(3));
        return id;
    }

    private void migrateGateway() {
        migrateGatewayTo("latest");
    }

    private void migrateGatewayTo(String version) {
        assertNoDuplicateMigrationVersions();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), GATEWAY_USER, GATEWAY_PASSWORD)
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    /**
     * 迁移目录里不能有两个同版本号的文件。
     *
     * <p><b>这条守卫是为一次真实事故加的，而不是假想的风险。</b>一条以 V86 命名的
     * 新迁移与同一目录下既有的 V86 撞号，而 Flyway 的报错（{@code Found more than
     * one migration with version 86}）只在**运行迁移的那一刻**出现——于是它的表现
     * 是一堆 schema 测试同时失败，而不是一句「你撞号了」。撞号的那份文件在本地
     * 与代码评审里都完全合法：它自己语法正确、内容是新的、文件名也符合约定。
     *
     * <p>把这条检查前置到迁移之前，等于把「两个文件各自都合法」这个盲区变成
     * 一句指名道姓的失败：它直接说出是哪两个文件、哪个版本号。
     *
     * <p>选择在测试里读 classpath 而不是读源码目录：测试运行时 classpath 上的
     * {@code db/migration} 就是 Flyway 将要读的那一份，因此这条断言检查的
     * 是**运行期事实**，而不是一个可能与构建输出不同的源码视图。
     * （构建会把它复制到 {@code target/classes}，两者在正常构建下一致。）
     */
    static void assertNoDuplicateMigrationVersions() {
        java.util.Map<String, java.util.List<String>> byVersion = new java.util.TreeMap<>();
        try {
            java.net.URL location = GatewayMigrationTest.class
                    .getClassLoader().getResource("db/migration");
            assertThat(location)
                    .as("the gateway migration directory must be on the test classpath")
                    .isNotNull();
            try (java.util.stream.Stream<java.nio.file.Path> files =
                         java.nio.file.Files.list(java.nio.file.Paths.get(location.toURI()))) {
                files.map(path -> path.getFileName().toString())
                        .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                        .forEach(name -> byVersion
                                .computeIfAbsent(versionOf(name), key ->
                                        new java.util.ArrayList<>())
                                .add(name));
            }
        } catch (java.io.IOException | java.net.URISyntaxException error) {
            throw new IllegalStateException("migration_directory_unreadable", error);
        }

        java.util.List<String> duplicates = byVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + " -> " + entry.getValue())
                .toList();

        assertThat(duplicates)
                .as("Flyway fails at startup when two files share a version; "
                        + "rename one of them rather than discovering this mid-migration")
                .isEmpty();
    }

    /** {@code V88__audio_dimension_and_policy.sql} 的版本号是 {@code 88}。 */
    private static String versionOf(String fileName) {
        int end = fileName.indexOf("__");
        return end < 0 ? fileName : fileName.substring(1, end);
    }

    private void cleanAndMigrateGatewayTo24() {
        cleanAndMigrateGatewayTo("24");
    }

    private void cleanAndMigrateGatewayTo(String version) {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), GATEWAY_USER, GATEWAY_PASSWORD)
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .target(version)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection gatewayConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), GATEWAY_USER, GATEWAY_PASSWORD);
    }

    private static Connection platformConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), PLATFORM_USER, PLATFORM_PASSWORD);
    }

    private static DatabaseClient gatewayDatabase() {
        String url = "r2dbc:postgresql://" + GATEWAY_USER + ":" + GATEWAY_PASSWORD
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/" + POSTGRES.getDatabaseName();
        return DatabaseClient.create(ConnectionFactories.get(url));
    }

    private String bindingValue(DatabaseClient database, UUID bindingId, String column) {
        return database.sql("SELECT " + column
                        + " FROM gateway.provider_file_binding WHERE id = :id")
                .bind("id", bindingId)
                .map((row, metadata) -> Optional.ofNullable(row.get(column, String.class)))
                .one().block(Duration.ofSeconds(3)).orElse(null);
    }

    private String fileValue(DatabaseClient database, UUID fileId, String column) {
        return database.sql("SELECT " + column + " FROM gateway.file_object WHERE id = :id")
                .bind("id", fileId)
                .map((row, metadata) -> Optional.ofNullable(row.get(column))
                        .map(Object::toString))
                .one().block(Duration.ofSeconds(3)).orElse(null);
    }

    private Set<String> tableNames(String schema) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = adminConnection();
                ResultSet result = connection.getMetaData().getTables(null, schema, "%", new String[] {"TABLE"})) {
            while (result.next()) {
                names.add(result.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private Set<String> columnNames(String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = adminConnection();
                ResultSet result = connection.getMetaData().getColumns(null, "gateway", table, "%")) {
            while (result.next()) {
                names.add(result.getString("COLUMN_NAME"));
            }
        }
        return names;
    }

    private String columnType(String table, String column) throws SQLException {
        try (Connection connection = adminConnection();
                var statement = connection.prepareStatement("""
                        SELECT data_type
                        FROM information_schema.columns
                        WHERE table_schema = 'gateway' AND table_name = ? AND column_name = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private String packageStatusConstraint() throws SQLException {
        try (Connection connection = adminConnection();
                var statement = connection.prepareStatement("""
                        SELECT pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE connamespace = 'gateway'::regnamespace
                          AND conrelid = 'gateway.package_instance'::regclass
                          AND conname = 'ck_package_instance_status'
                        """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private String columnProperty(String table, String column, String property) throws SQLException {
        try (Connection connection = adminConnection();
                var statement = connection.prepareStatement("SELECT " + property + " "
                        + "FROM information_schema.columns WHERE table_schema = 'gateway' "
                        + "AND table_name = ? AND column_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private String constraintDefinition(String table, String constraint) throws SQLException {
        try (Connection connection = adminConnection();
                var statement = connection.prepareStatement("""
                        SELECT pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE connamespace = 'gateway'::regnamespace
                          AND conrelid = ('gateway.' || ?)::regclass
                          AND conname = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private String indexDefinition(String index) throws SQLException {
        try (Connection connection = adminConnection();
                var statement = connection.prepareStatement("""
                        SELECT indexdef FROM pg_indexes
                        WHERE schemaname = 'gateway' AND indexname = ?
                        """)) {
            statement.setString(1, index);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private String explainWithSequentialScanDisabled(String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            statement.execute("SET enable_bitmapscan = off");
            try (ResultSet result = statement.executeQuery("EXPLAIN " + sql)) {
                while (result.next()) {
                    plan.append(result.getString(1)).append('\n');
                }
            }
        }
        return plan.toString();
    }

    private Set<String> values(String sql) throws SQLException {
        Set<String> values = new LinkedHashSet<>();
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private int executeUpdate(String sql) throws SQLException {
        try (Connection connection = gatewayConnection(); Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    /**
     * 一条套餐权益，维度规则正是在这里强制执行：触发器读取的是
     * {@code allowed_models}，而不是该权益自己声明的维度。
     */
    private static String entitlementInsert(
            String packageCode, String allowedModels, String usageDimension) {
        return """
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, package_kind,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, status, starts_at, activated_at, expires_at, usage_dimension)
                VALUES (
                    gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
                    gen_random_uuid(), 'identity-%1$s',
                    gen_random_uuid(), '%1$s', '%1$s', 'STANDARD',
                    12900, 'CNY', '%2$s'::jsonb, 60,
                    2, 20, 60, 2,
                    30, 'ACTIVE', now(), now(), now() + interval '30 days', '%3$s')
                """.formatted(packageCode, allowedModels, usageDimension);
    }

    private long singleLong(String sql) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
