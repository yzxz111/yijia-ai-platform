package com.yuzhihuihe.apiplatform.platform.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "outbox.publisher.initial-delay-ms=3600000"
})
class PackageFulfillmentTest {

    private static final UUID TENANT_ID = UUID.fromString("91000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("91000000-0000-4000-8000-000000000002");
    private static final UUID ACTOR_ID = UUID.fromString("91000000-0000-4000-8000-000000000003");
    /**
     * 当前 PUBLISHED 的 ADVANCED 版本。V61 对该套餐方案重新定价并退役了 v1
     * （{@code ...103}），V63 又为修正后的售卖速率上限把它重新发布为 v3（{@code ...123}），
     * 因此这个 id 移动了两次：{@code loadPublished} 只解析 PUBLISHED 行，
     * 而指向已退役版本的 id 是 404，而不是过期的快照。
     */
    private static final UUID ADVANCED_VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000123");
    /**
     * VIDEO_STARTER 的版本行。注意两个 id 空间：套餐方案携带
     * {@code ...0011}，而其已发布版本自 V64 重新发布以恢复
     * 每分钟秒数上限后携带 {@code ...0321}（v1，即 {@code ...0301}，以及
     * V63 的行 {@code ...0311}，两者都是 RETIRED）；快照按版本 id 装载。
     */
    private static final UUID VIDEO_STARTER_VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000321");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PackageSnapshotService snapshotService;

    @Autowired
    PackageFulfillmentService fulfillmentService;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("TRUNCATE platform.admin_audit_log, platform.outbox_event, "
                + "platform.grant_request, platform.refresh_token, platform.user_role, "
                + "platform.invitation, platform.app_user RESTART IDENTITY CASCADE");
        insertUser(USER_ID, TENANT_ID, "fulfillment.user@example.com", "Fulfillment User");
        insertUser(ACTOR_ID, TENANT_ID, "fulfillment.actor@example.com", "Fulfillment Actor");
    }

    @Test
    void fulfillsAtomicallyAndReplaysTheSameBusinessSnapshot() {
        PackageSnapshotService.PackageSnapshot snapshot = snapshotService.loadPublished(ADVANCED_VERSION_ID);
        PackageFulfillmentService.FulfillmentCommand command = command(snapshot);

        GrantService.GrantResult first = fulfillmentService.fulfill(command);
        GrantService.GrantResult replay = fulfillmentService.fulfill(command);

        assertThat(replay).isEqualTo(first);
        assertThat(count("platform.grant_request", "business_no", "order:ord_001")).isEqualTo(1);
        assertThat(count("platform.outbox_event", "business_key", "order:ord_001")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'PACKAGE_GRANT_CREATED' AND resource_id = ?
                """, Integer.class, first.id().toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.outbox_event
                WHERE business_key = 'order:ord_001' AND event_type = 'PACKAGE_GRANTED_V1'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsReusedBusinessNumberWithDifferentSnapshot() {
        PackageSnapshotService.PackageSnapshot snapshot = snapshotService.loadPublished(ADVANCED_VERSION_ID);
        fulfillmentService.fulfill(command(snapshot));
        PackageSnapshotService.PackageSnapshot changed = new PackageSnapshotService.PackageSnapshot(
                snapshot.packageVersionId(), snapshot.packageCode(), snapshot.packageName(),
                snapshot.packageKind(),
                snapshot.priceAmount(), snapshot.currency(), snapshot.totalTokenQuota() + 1,
                // 这个版本不卖语音（方案 C 的第二份额度只有混合版本才有）。
                null,
                snapshot.durationDays(), snapshot.maxApiKeys(), snapshot.requestsPerMinute(),
                snapshot.tokensPerMinute(), snapshot.maxConcurrency(), snapshot.dailyRequestLimit(),
                snapshot.maxOutputTokens(), snapshot.maxFileSizeBytes(), snapshot.oncePerUser(),
                snapshot.usageDimension(), snapshot.models());

        assertThatThrownBy(() -> fulfillmentService.fulfill(command(changed)))
                .hasMessageContaining("grant_business_payload_mismatch");
        assertThat(count("platform.grant_request", "business_no", "order:ord_001")).isEqualTo(1);
        assertThat(count("platform.outbox_event", "business_key", "order:ord_001")).isEqualTo(1);
    }

    @Test
    void packageSnapshotDefensivelyCopiesOrderedModels() {
        PackageSnapshotService.PackageSnapshot loaded = snapshotService.loadPublished(ADVANCED_VERSION_ID);
        var mutableModels = new ArrayList<>(loaded.models());
        PackageSnapshotService.PackageSnapshot snapshot = copyWithModels(loaded, mutableModels);

        mutableModels.clear();

        assertThat(snapshot.models()).isNotEmpty().isSortedAccordingTo(
                java.util.Comparator.comparing(PackageSnapshotService.ModelPermission::modelCode));
        assertThatThrownBy(() -> snapshot.models().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void loadsPublishedSnapshotFieldsAndCapabilities() {
        PackageSnapshotService.PackageSnapshot snapshot = snapshotService.loadPublished(ADVANCED_VERSION_ID);

        assertThat(snapshot.packageVersionId()).isEqualTo(ADVANCED_VERSION_ID);
        assertThat(snapshot.packageCode()).isEqualTo("ADVANCED");
        // ADVANCED v3 的售卖形态：V61 把额度缩减到 16,000,000 并把 text-pro 限制在
        // 6,000,000，V63 又补上了它此前以 NULL 售卖的单日与输出上限。
        assertThat(snapshot.totalTokenQuota()).isEqualTo(16_000_000L);
        assertThat(snapshot.dailyRequestLimit()).isEqualTo(10_000);
        assertThat(snapshot.usageDimension()).isEqualTo("TOKEN");
        assertThat(snapshot.models())
                .extracting(PackageSnapshotService.ModelPermission::modelCode)
                .containsExactly(
                        "file-basic",
                        "multimodal-basic",
                        "text-basic",
                        "text-pro",
                        "text-standard",
                        "vision-basic",
                        "vision-pro");

        PackageSnapshotService.ModelPermission textPro = snapshot.models().stream()
                .filter(model -> model.modelCode().equals("text-pro"))
                .findFirst()
                .orElseThrow();
        assertThat(textPro.modelTokenQuota()).isEqualTo(6_000_000L);
        assertThat(textPro.capabilities()).containsExactly("TEXT");

        PackageSnapshotService.ModelPermission visionBasic = snapshot.models().stream()
                .filter(model -> model.modelCode().equals("vision-basic"))
                .findFirst()
                .orElseThrow();
        assertThat(visionBasic.modelTokenQuota()).isNull();
        assertThat(visionBasic.capabilities()).containsExactlyInAnyOrder("TEXT", "IMAGE");
    }

    /**
     * 秒套餐必须带着秒套餐的标注送达网关。
     *
     * <p>这是决定视频套餐究竟能否被交付的契约。
     * 网关会把缺失的维度默认为 {@code TOKEN}，然后拒绝其维度
     * 与自身模型能力不一致的套餐，因此省略
     * {@code usage_dimension} 的快照会产生一个被网关以
     * {@code package_dimension_capability_mismatch} 拒绝的权益发放 —— 而且是在客户已经付款之后。该
     * 字段此前从仓库的 SELECT 中缺失，因此载荷从未携带它，
     * 每一份售出的视频套餐都无法交付。
     *
     * <p>断言发出去的 JSON 而不是快照记录是刻意的：
     * 该缺陷存在于两个服务之间的序列化路径上，只测试
     * 任一侧自身类型的用例无法发现它。
     */
    @Test
    void videoPackageGrantCarriesItsUsageDimensionToTheGateway() {
        PackageSnapshotService.PackageSnapshot snapshot =
                snapshotService.loadPublished(VIDEO_STARTER_VERSION_ID);
        assertThat(snapshot.usageDimension()).isEqualTo("VIDEO_SECOND");

        fulfillmentService.fulfill(command(snapshot, "order:video_001"));

        String payload = jdbcTemplate.queryForObject("""
                SELECT payload::text FROM platform.outbox_event
                WHERE business_key = 'order:video_001' AND event_type = 'PACKAGE_GRANTED_V1'
                """, String.class);
        // 采用解析而不是子串匹配：发出的文档并不紧凑，而对空白
        // 敏感的断言会因此失败，其失败原因与
        // 被测契约毫无关系。
        assertThat(dimensionOf(payload)).isEqualTo("VIDEO_SECOND");

        // 网关之所以接受这个带标注的套餐，仅因为每一项权限都声明了
        // VIDEO，这是同一不变式的另一半。
        //
        // V103 之后 VIDEO_STARTER 同时卖四个音频别名（配音/口播/字幕/换口型），
        // 而它们声明的是 AUDIO_TRANSCRIBE / AUDIO_SYNTHESIS / LIP_SYNC ——
        // 断言因此按**能力**收窄，而不是"每一项权限都带 VIDEO"：网关对混合版本
        // 的检查本来就是逐模型的（PackageActivationService
        // .dimensionMatchesCapabilities 的 VIDEO_SECOND 与 AUDIO_SECOND 两个分支），
        // 把音频别名也算进"必须声明 VIDEO"会让这条测试与网关的真实规则不一致，
        // 而那是它唯一存在的理由。
        assertThat(snapshot.models()).isNotEmpty();
        assertThat(snapshot.models())
                .filteredOn(model -> model.capabilities().contains("AUDIO_TRANSCRIBE")
                        || model.capabilities().contains("AUDIO_SYNTHESIS")
                        || model.capabilities().contains("LIP_SYNC"))
                .isNotEmpty();
        assertThat(snapshot.models())
                .filteredOn(model -> model.capabilities().contains("VIDEO")
                        || model.capabilities().contains("AUDIO_TRANSCRIBE")
                        || model.capabilities().contains("AUDIO_SYNTHESIS")
                        || model.capabilities().contains("LIP_SYNC"))
                .hasSize(snapshot.models().size());
        // 视频那半仍必须逐项声明 VIDEO：一个只有 TEXT/IMAGE 能力的别名被放进
        // 视频版本，网关会在发放时以 package_dimension_capability_mismatch 拒绝。
        assertThat(snapshot.models())
                .filteredOn(model -> !model.capabilities().contains("AUDIO_TRANSCRIBE")
                        && !model.capabilities().contains("AUDIO_SYNTHESIS")
                        && !model.capabilities().contains("LIP_SYNC"))
                .isNotEmpty()
                .allSatisfy(model -> assertThat(model.capabilities()).contains("VIDEO"));

        // 以及反向契约：token 套餐不得声称按秒计。
        jdbcTemplate.update("TRUNCATE platform.outbox_event CASCADE");
        fulfillmentService.fulfill(command(
                snapshotService.loadPublished(ADVANCED_VERSION_ID), "order:token_001"));
        String tokenPayload = jdbcTemplate.queryForObject("""
                SELECT payload::text FROM platform.outbox_event
                WHERE business_key = 'order:token_001' AND event_type = 'PACKAGE_GRANTED_V1'
                """, String.class);
        assertThat(dimensionOf(tokenPayload)).isEqualTo("TOKEN");
    }

    /** 发出的权益发放载荷中的维度字段，按网关读取它的方式呈现。 */
    private String dimensionOf(String payload) {
        try {
            return new ObjectMapper().readTree(payload).path("usageDimension").asText();
        } catch (Exception exception) {
            throw new IllegalStateException("grant_payload_unreadable", exception);
        }
    }

    @Test
    void outboxInsertFailureRollsBackGrantAndAudit() {
        String businessNo = "order:ord_outbox_failure";
        jdbcTemplate.update("""
                INSERT INTO platform.outbox_event(
                    id, aggregate_type, aggregate_id, event_type, business_key, payload)
                VALUES (?, 'TEST', ?, 'TEST_EVENT', ?, '{}'::jsonb)
                """, UUID.randomUUID(), UUID.randomUUID(), businessNo);
        PackageSnapshotService.PackageSnapshot snapshot = snapshotService.loadPublished(ADVANCED_VERSION_ID);

        assertThatThrownBy(() -> fulfillmentService.fulfill(command(snapshot, businessNo)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(count("platform.grant_request", "business_no", businessNo)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.admin_audit_log", Integer.class)).isZero();
    }

    @Test
    void auditInsertFailureRollsBackGrantAndOutbox() {
        String businessNo = "order:ord_audit_failure";
        jdbcTemplate.execute("""
                ALTER TABLE platform.admin_audit_log
                ADD CONSTRAINT ck_test_reject_package_grant
                CHECK (action <> 'PACKAGE_GRANT_CREATED')
                """);
        try {
            PackageSnapshotService.PackageSnapshot snapshot = snapshotService.loadPublished(ADVANCED_VERSION_ID);
            assertThatThrownBy(() -> fulfillmentService.fulfill(command(snapshot, businessNo)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE platform.admin_audit_log
                    DROP CONSTRAINT ck_test_reject_package_grant
                    """);
        }

        assertThat(count("platform.grant_request", "business_no", businessNo)).isZero();
        assertThat(count("platform.outbox_event", "business_key", businessNo)).isZero();
    }

    private PackageFulfillmentService.FulfillmentCommand command(
            PackageSnapshotService.PackageSnapshot snapshot) {
        return command(snapshot, "order:ord_001");
    }

    private PackageFulfillmentService.FulfillmentCommand command(
            PackageSnapshotService.PackageSnapshot snapshot, String businessNo) {
        return new PackageFulfillmentService.FulfillmentCommand(
                TENANT_ID, USER_ID, snapshot.packageVersionId(), businessNo, "ORDER",
                "paid order fulfillment", ACTOR_ID, snapshot);
    }

    private PackageSnapshotService.PackageSnapshot copyWithModels(
            PackageSnapshotService.PackageSnapshot snapshot,
            java.util.List<PackageSnapshotService.ModelPermission> models) {
        return new PackageSnapshotService.PackageSnapshot(
                snapshot.packageVersionId(), snapshot.packageCode(), snapshot.packageName(),
                snapshot.packageKind(),
                snapshot.priceAmount(), snapshot.currency(), snapshot.totalTokenQuota(),
                snapshot.audioSecondsQuota(),
                snapshot.durationDays(), snapshot.maxApiKeys(), snapshot.requestsPerMinute(),
                snapshot.tokensPerMinute(), snapshot.maxConcurrency(), snapshot.dailyRequestLimit(),
                snapshot.maxOutputTokens(), snapshot.maxFileSizeBytes(), snapshot.oncePerUser(),
                snapshot.usageDimension(), models);
    }

    private int count(String table, String column, String value) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
    }

    private void insertUser(UUID id, UUID tenantId, String email, String displayName) {
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'test-password-hash', ?, 'ACTIVE')
                """, id, tenantId, email, displayName);
    }
}
