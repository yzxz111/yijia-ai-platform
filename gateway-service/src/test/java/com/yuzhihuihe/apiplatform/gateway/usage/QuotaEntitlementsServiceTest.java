package com.yuzhihuihe.apiplatform.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 套餐权益接口的真实 SQL 行为。
 *
 * <p>用真库而不是 mock：这个接口的价值全在"数从哪一列读、单位是什么"上，而 mock 的
 * 仓储会把这类错误整片删掉——把秒读成积分正是它要防的那件事。
 *
 * <p>断言里的费率数字来自迁移链的末端状态（{@code chat-standard} 的 430 / 430 / 100
 * 由 V102 写入，{@code text-basic} 的 300 / 1200 / 15 由 V50 写入计费卡，
 * {@code image-basic} 2K 的 89 由 V82 写入，{@code video-minimax} 720P 的 83 由 V99
 * 写入）。这些别名与价格是**真实的**，不是为测试造的：参考单价取自两张不同的卡，
 * 而"取哪一张"本身就是被测行为。
 */
@Testcontainers
class QuotaEntitlementsServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000002");

    private static final String TOKEN_PACKAGE = "81000000-0000-4000-8000-000000000011";
    private static final String VIDEO_PACKAGE = "81000000-0000-4000-8000-000000000012";
    private static final String IMAGE_PACKAGE = "81000000-0000-4000-8000-000000000013";
    private static final String QUEUED_PACKAGE = "81000000-0000-4000-8000-000000000014";
    private static final String OTHER_PACKAGE = "81000000-0000-4000-8000-000000000021";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private QuotaEntitlementsService service;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void reset() throws Exception {
        execute("TRUNCATE gateway.usage_log, gateway.quota_ledger, gateway.quota_reservation, "
                + "gateway.api_key, gateway.package_instance_model_quota, "
                + "gateway.package_instance, gateway.consumed_event CASCADE");

        // 积分套餐：一个模型有独立额度（chat-standard），一个没有（text-basic）。
        seedPackage(TOKEN_PACKAGE, USER_ID, "ACTIVE", "TOKEN", 1000, 30, 120, null, """
                [{"modelCode":"chat-standard","modelTokenQuota":500,"capabilities":["TEXT"]},
                 {"modelCode":"text-basic","modelTokenQuota":null,"capabilities":["TEXT"]}]""");
        seedModelQuota("8a000000-0000-4000-8000-000000000001", TOKEN_PACKAGE,
                "chat-standard", "TOKEN", 500, 20, 80);

        // 视频套餐：视频秒 + 方案 C 的第二份额度（音频秒）。带一个不带 modelCode 的
        // 标记项，证明它不会被当成一个名为 "" 的模型下发。
        seedPackage(VIDEO_PACKAGE, USER_ID, "ACTIVE", "VIDEO_SECOND", 600, 0, 40, 100L, """
                [{"mixedPools":true},
                 {"modelCode":"video-minimax","modelTokenQuota":60,"capabilities":["VIDEO"],
                  "usageDimension":"VIDEO_SECOND"},
                 {"modelCode":"audio-tts-basic","modelTokenQuota":null,
                  "capabilities":["AUDIO_SYNTHESIS"],"usageDimension":"AUDIO_SECOND"}]""");
        seedModelQuota("8a000000-0000-4000-8000-000000000002", VIDEO_PACKAGE,
                "video-minimax", "VIDEO_SECOND", 60, 0, 0);
        seedModelQuota("8a000000-0000-4000-8000-000000000003", VIDEO_PACKAGE,
                "audio-tts-basic", "AUDIO_SECOND", 100, 0, 0);

        // 图片套餐：按张计费，模型自带额度。
        seedPackage(IMAGE_PACKAGE, USER_ID, "ACTIVE", "IMAGE", 48, 0, 3, null, """
                [{"modelCode":"image-basic","modelTokenQuota":48,"capabilities":["IMAGE_GEN"],
                  "usageDimension":"IMAGE"}]""");
        seedModelQuota("8a000000-0000-4000-8000-000000000004", IMAGE_PACKAGE,
                "image-basic", "IMAGE", 48, 0, 3);

        // 排队中的实例：没有任何实时计数，绝不能出现在权益数组里。
        seedPackage(QUEUED_PACKAGE, USER_ID, "QUEUED", "TOKEN", 9999, 0, 0, null, """
                [{"modelCode":"chat-standard","modelTokenQuota":null,"capabilities":["TEXT"]}]""");

        // 另一个用户的同维度实例：跨账号泄漏必须不可能。
        seedPackage(OTHER_PACKAGE, OTHER_USER_ID, "ACTIVE", "TOKEN", 777, 0, 0, null, """
                [{"modelCode":"chat-deep","modelTokenQuota":null,"capabilities":["TEXT"]}]""");

        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                .option(ConnectionFactoryOptions.PORT, POSTGRES.getFirstMappedPort())
                .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                .build();
        // 一个连接工厂给两个仓储：池只开一份，两个仓储读的是同一个库。
        DatabaseClient databaseClient = DatabaseClient.create(ConnectionFactories.get(options));
        service = new QuotaEntitlementsService(
                new UsageQueryRepository(databaseClient),
                new QuotaEntitlementsRepository(databaseClient),
                new ObjectMapper());
    }

    @Test
    void exposesOnlyTheSignedInUsersActivePackagesWithThePackagePoolArithmetic() {
        QuotaEntitlementsService.Entitlements entitlements = load();

        assertThat(entitlements.packages()).extracting(
                        QuotaEntitlementsService.PackageEntitlements::packageInstanceId)
                .containsExactlyInAnyOrder(
                        UUID.fromString(TOKEN_PACKAGE), UUID.fromString(VIDEO_PACKAGE),
                        UUID.fromString(IMAGE_PACKAGE));

        QuotaEntitlementsService.PackageEntitlements token = packageOf(entitlements, "TOKEN");
        // 与 /quota/summary 同一套算术：总量 − 在途 − 已用。
        assertThat(token.totalQuota()).isEqualTo(1000);
        assertThat(token.reservedQuota()).isEqualTo(30);
        assertThat(token.usedQuota()).isEqualTo(120);
        assertThat(token.remainingQuota()).isEqualTo(850);
        assertThat(token.quotaUnit()).isEqualTo("CREDIT");
        assertThat(token.audioSeconds()).isNull();

        assertThat(token.models()).extracting(
                        QuotaEntitlementsService.ModelEntitlement::modelCode)
                .containsExactly("chat-standard", "text-basic");
    }

    @Test
    void reportsEachModelQuotaWithItsOwnUnitAndScope() {
        QuotaEntitlementsService.PackageEntitlements token = packageOf(load(), "TOKEN");

        QuotaEntitlementsService.ModelEntitlement chat = modelOf(token, "chat-standard");
        assertThat(chat.category()).isEqualTo("CHAT");
        assertThat(chat.usageDimension()).isEqualTo("TOKEN");
        assertThat(chat.quotaUnit()).isEqualTo("CREDIT");
        assertThat(chat.quotaScope()).isEqualTo("MODEL");
        assertThat(chat.totalQuota()).isEqualTo(500L);
        assertThat(chat.reservedQuota()).isEqualTo(20L);
        assertThat(chat.usedQuota()).isEqualTo(80L);
        assertThat(chat.remainingQuota()).isEqualTo(400L);

        // 没有独立额度的模型花的是套餐那份共享池：数值照给，但必须标成 PACKAGE，
        // 否则客户端把三个模型的剩余量相加会把同一份额度算三遍。
        QuotaEntitlementsService.ModelEntitlement shared = modelOf(token, "text-basic");
        assertThat(shared.quotaScope()).isEqualTo("PACKAGE");
        assertThat(shared.totalQuota()).isEqualTo(1000L);
        assertThat(shared.remainingQuota()).isEqualTo(850L);
    }

    /**
     * 套餐名单里的顺序就是返回顺序。
     *
     * <p>客户端拿这个列表渲染、并当作缓存键与相等比较。若改成按编码排序，
     * 同一份套餐的顺序仍然稳定，但它不再等于"授权列表顺序"——而授权列表才是
     * 客户实际买到的东西的顺序。这里刻意让一个不在名单里的编码排在字典序最前，
     * 于是"排序了"与"照抄名单"会给出不同答案。
     */
    @Test
    void keepsThePackageAuthorizationOrderInsteadOfSortingModelCodes() throws Exception {
        execute("""
                INSERT INTO gateway.package_instance_model_quota(
                    id, package_instance_id, model_code, usage_dimension,
                    token_quota, seconds_quota, reserved_tokens, used_tokens)
                VALUES ('8a000000-0000-4000-8000-00000000000a', '%s', 'aaa-first',
                        'TOKEN', 300, NULL, 0, 0)
                """.formatted(TOKEN_PACKAGE));

        QuotaEntitlementsService.PackageEntitlements token = packageOf(load(), "TOKEN");

        // 'aaa-first' 在字典序上排在 'chat-standard' 前面，但它不在授权名单里，
        // 因此既不参与排序、也不出现在结果中。
        assertThat(token.models()).extracting(
                        QuotaEntitlementsService.ModelEntitlement::modelCode)
                .containsExactly("chat-standard", "text-basic");
    }

    /**
     * 跨维度授权的模型：维度与套餐主维度不一致，又没有独立额度或音频第二份额度，
     * 四项额度只能是 {@code null}——不能套用套餐那一份其它单位的余额。
     */
    @Test
    void reportsNullQuotaForACrossDimensionModelInsteadOfBorrowingAnotherPool()
            throws Exception {
        // 一个用户每个维度只能有一份生效套餐，先把种子里的视频套餐换掉。
        // 图片模型可以合法地列在视频套餐里（决定维度的是它自己的声明与额度行），
        // 而它在这份套餐里没有任何额度池。视频别名与非按秒别名并存必须声明
        // mixedPools：否则 V40 的触发器会在发放那一刻拒掉这种套餐。
        execute("DELETE FROM gateway.package_instance WHERE id = '" + VIDEO_PACKAGE + "'");
        seedPackage("81000000-0000-4000-8000-000000000015", USER_ID, "ACTIVE", "VIDEO_SECOND",
                600, 0, 0, null,
                """
                [{"mixedPools":true},\
                {"modelCode":"video-minimax","usageDimension":"VIDEO_SECOND"},\
                {"modelCode":"image-basic","usageDimension":"IMAGE"}]""");

        QuotaEntitlementsService.PackageEntitlements video = packageOf(load(), "VIDEO_SECOND");

        // 维度与套餐一致：照给套餐那份共享池。
        QuotaEntitlementsService.ModelEntitlement sameDimension = modelOf(video, "video-minimax");
        assertThat(sameDimension.quotaScope()).isEqualTo("PACKAGE");
        assertThat(sameDimension.quotaUnit()).isEqualTo("SECOND");
        assertThat(sameDimension.totalQuota()).isEqualTo(600L);
        assertThat(sameDimension.remainingQuota()).isEqualTo(600L);

        QuotaEntitlementsService.ModelEntitlement crossDimension = modelOf(video, "image-basic");
        assertThat(crossDimension.category()).isEqualTo("IMAGE");
        assertThat(crossDimension.quotaUnit()).isEqualTo("IMAGE");
        // 600 秒不是 600 张；把套餐那一份套上来就会得到一份不存在的图片额度。
        assertThat(crossDimension.totalQuota()).isNull();
        assertThat(crossDimension.reservedQuota()).isNull();
        assertThat(crossDimension.usedQuota()).isNull();
        assertThat(crossDimension.remainingQuota()).isNull();
        // 规则仍按该模型自己的维度说明，而不是落进"未知"。
        assertThat(crossDimension.perCall().unit()).isEqualTo("IMAGE");
        assertThat(crossDimension.perCall().basis()).isEqualTo("ONE_IMAGE_PER_REQUEST");
    }

    /**
     * 混合套餐声明了音频、却没有第二份额度：报 {@code null}，而不是套餐那一份视频秒。
     */
    @Test
    void reportsNullAudioQuotaWhenTheSecondBucketIsAbsent() throws Exception {
        execute("DELETE FROM gateway.package_instance WHERE id = '" + VIDEO_PACKAGE + "'");
        seedPackage("81000000-0000-4000-8000-000000000016", USER_ID, "ACTIVE", "VIDEO_SECOND",
                600, 0, 0, null,
                """
                [{"mixedPools":true},\
                {"modelCode":"video-minimax","usageDimension":"VIDEO_SECOND"},\
                {"modelCode":"audio-tts-basic","usageDimension":"AUDIO_SECOND"}]""");

        QuotaEntitlementsService.PackageEntitlements video = packageOf(load(), "VIDEO_SECOND");

        assertThat(video.audioSeconds()).isNull();
        QuotaEntitlementsService.ModelEntitlement audio = modelOf(video, "audio-tts-basic");
        assertThat(audio.category()).isEqualTo("AUDIO");
        assertThat(audio.quotaUnit()).isEqualTo("SECOND");
        assertThat(audio.totalQuota()).isNull();
        assertThat(audio.remainingQuota()).isNull();
        // 规则本身照给：这个模型确实是按请求秒计量，即使这份套餐没给它额度。
        assertThat(audio.perCall().unit()).isEqualTo("SECOND");
        assertThat(audio.perCall().basis()).isEqualTo("PER_REQUESTED_SECOND");
    }

    /**
     * 套餐主维度不是音频、但有第二份额度时，音频模型读的是**第二份**那一池。
     *
     * <p>这是"音频秒不属于视频秒"的核心：两者都以秒计，但来源不同，
     * 混用会让客户以为视频套餐的秒数就是语音额度。
     */
    @Test
    void readsTheSecondAudioBucketForAnAudioModelWithoutItsOwnQuota() throws Exception {
        execute("DELETE FROM gateway.package_instance WHERE id = '" + VIDEO_PACKAGE + "'");
        // 有第二份额度（100 秒），但音频模型没有独立额度行。
        seedPackage("81000000-0000-4000-8000-000000000017", USER_ID, "ACTIVE", "VIDEO_SECOND",
                600, 0, 0, 100L,
                """
                [{"mixedPools":true},\
                {"modelCode":"video-minimax","usageDimension":"VIDEO_SECOND"},\
                {"modelCode":"audio-tts-basic","usageDimension":"AUDIO_SECOND"}]""");

        QuotaEntitlementsService.PackageEntitlements video = packageOf(load(), "VIDEO_SECOND");

        QuotaEntitlementsService.ModelEntitlement audio = modelOf(video, "audio-tts-basic");
        // 没有独立额度行 ⇒ 共享那一份，而共享的那一份正是第二份音频池。
        assertThat(audio.quotaScope()).isEqualTo("PACKAGE");
        assertThat(audio.quotaUnit()).isEqualTo("SECOND");
        assertThat(audio.totalQuota()).isEqualTo(100L);
        assertThat(audio.remainingQuota()).isEqualTo(100L);
        // 600 秒是视频池；音频读的必须是那 100 秒的第二份池。
        assertThat(audio.totalQuota()).isNotEqualTo(video.totalQuota());
        assertThat(video.audioSeconds().totalSeconds()).isEqualTo(100L);
    }

    @Test
    void chargesOneImagePerRequestAndOneSecondPerRequestedSecond() {
        QuotaEntitlementsService.Entitlements entitlements = load();

        QuotaEntitlementsService.ModelEntitlement image = modelOf(
                packageOf(entitlements, "IMAGE"), "image-basic");
        assertThat(image.category()).isEqualTo("IMAGE");
        assertThat(image.usageDimension()).isEqualTo("IMAGE");
        assertThat(image.quotaUnit()).isEqualTo("IMAGE");
        assertThat(image.totalQuota()).isEqualTo(48L);
        assertThat(image.remainingQuota()).isEqualTo(45L);
        assertThat(image.perCall().unit()).isEqualTo("IMAGE");
        // n 只接受 1，于是一次生成恰好扣一张——这是计量方式，不是估算。
        assertThat(image.perCall().units()).isEqualTo(1);
        assertThat(image.perCall().basis()).isEqualTo("ONE_IMAGE_PER_REQUEST");
        assertThat(image.perCall().referencePrice().unit()).isEqualTo("CENT_PER_UNIT");
        assertThat(image.perCall().referencePrice().resolutionTier()).isEqualTo("2K");
        assertThat(image.perCall().referencePrice().unitPrice()).isEqualTo(89);
        // 逐张行上的"每百万 token 价"列必须为 null，否则分/张会被当成
        // 分/百万 token 再乘一次。
        assertThat(image.perCall().referencePrice().inputPerMillion()).isNull();
        assertThat(image.perCall().referencePrice().outputPerMillion()).isNull();

        QuotaEntitlementsService.PackageEntitlements videoPackage = packageOf(entitlements, "VIDEO_SECOND");
        QuotaEntitlementsService.ModelEntitlement video = modelOf(videoPackage, "video-minimax");
        assertThat(video.category()).isEqualTo("VIDEO");
        assertThat(video.quotaUnit()).isEqualTo("SECOND");
        assertThat(video.totalQuota()).isEqualTo(60L);
        assertThat(video.remainingQuota()).isEqualTo(60L);
        assertThat(video.perCall().unit()).isEqualTo("SECOND");
        assertThat(video.perCall().units()).isEqualTo(1);
        assertThat(video.perCall().basis()).isEqualTo("PER_REQUESTED_SECOND");
        // 售卖档位那一行：video-minimax 只卖 720P，费率卡上 83 分/秒。
        assertThat(video.perCall().referencePrice().resolutionTier()).isEqualTo("720P");
        assertThat(video.perCall().referencePrice().unitPrice()).isEqualTo(83);

        // 方案 C 的第二份额度：秒钟类维度，独立于主额度，两份额度不得相加。
        assertThat(videoPackage.totalQuota()).isEqualTo(600);
        assertThat(videoPackage.audioSeconds().totalSeconds()).isEqualTo(100);
        assertThat(videoPackage.audioSeconds().remainingSeconds()).isEqualTo(100);
        QuotaEntitlementsService.ModelEntitlement audio = modelOf(videoPackage, "audio-tts-basic");
        assertThat(audio.category()).isEqualTo("AUDIO");
        assertThat(audio.quotaUnit()).isEqualTo("SECOND");
        assertThat(audio.quotaScope()).isEqualTo("MODEL");
        assertThat(audio.totalQuota()).isEqualTo(100L);
        assertThat(audio.perCall().unit()).isEqualTo("SECOND");
        assertThat(audio.perCall().basis()).isEqualTo("PER_REQUESTED_SECOND");
        assertThat(audio.perCall().referencePrice().unit()).isEqualTo("CENT_PER_UNIT");
        assertThat(audio.perCall().referencePrice().unitPrice()).isPositive();
    }

    @Test
    void givesTokenModelsNoFixedPerCallAmountButDoesGiveTheRate() {
        QuotaEntitlementsService.ModelEntitlement chat = modelOf(packageOf(load(), "TOKEN"), "chat-standard");

        // 按实际 token 结算，因此没有"每次对话消耗多少积分"这个数：
        // null 表示没有固定值，绝不是 0。
        assertThat(chat.perCall().unit()).isEqualTo("CREDIT");
        assertThat(chat.perCall().units()).isNull();
        assertThat(chat.perCall().basis()).isEqualTo("METERED_BY_TOKENS");
        assertThat(chat.perCall().referencePrice().unit()).isEqualTo("CENT_PER_MILLION_TOKENS");
        assertThat(chat.perCall().referencePrice().inputPerMillion()).isEqualTo(430);
        assertThat(chat.perCall().referencePrice().outputPerMillion()).isEqualTo(430);
        assertThat(chat.perCall().referencePrice().cacheHitPerMillion()).isEqualTo(100);
    }

    @Test
    void pricesTokensFromTheBillingCardAndFallsBackToTheRateCardForNewerAliases() {
        QuotaEntitlementsService.PackageEntitlements token = packageOf(load(), "TOKEN");

        // text-basic 在两张卡上都有价，且两张卡的数字不同（计费卡 1200，费率卡 1100/80）。
        // 计费卡才是钱包实际收费的那张，因此展示价必须取它。
        QuotaEntitlementsService.ReferencePrice billed =
                modelOf(token, "text-basic").perCall().referencePrice();
        assertThat(billed.source()).isEqualTo("BILLING_CARD");
        assertThat(billed.currency()).isEqualTo("CNY");
        assertThat(billed.inputPerMillion()).isEqualTo(300);
        assertThat(billed.outputPerMillion()).isEqualTo(1200);
        assertThat(billed.cacheHitPerMillion()).isEqualTo(15);
        // 与 unit 不符的列恒为 null：token 行上的逐单位价照搬过来会被读成
        // "每次对话至少 1 分"。
        assertThat(billed.unitPrice()).isNull();
        assertThat(billed.minimumUnits()).isNull();
        assertThat(billed.minimumCharge()).isNull();
        assertThat(billed.resolutionTier()).isEmpty();

        // chat-standard 是 V102 之后新增的客户端通道，计费卡里没有它，
        // 只有在维度费率卡上才有价；参考单价必须回退到那张卡而不是留空。
        QuotaEntitlementsService.ReferencePrice rated =
                modelOf(token, "chat-standard").perCall().referencePrice();
        assertThat(rated.source()).isEqualTo("RATE_CARD");
        assertThat(rated.inputPerMillion()).isEqualTo(430);
        assertThat(rated.resolutionTier()).isEmpty();
        assertThat(rated.unitPrice()).isNull();
        assertThat(rated.minimumUnits()).isNull();
        assertThat(rated.minimumCharge()).isNull();
    }

    @Test
    void leavesTheInvitationGiftBlankInsteadOfInventingAZero() {
        QuotaEntitlementsService.GiftQuota gift = load().giftQuota();

        assertThat(gift.implemented()).isFalse();
        assertThat(gift.reason()).isEqualTo("not_implemented");
        // 0 会被客户端渲染成"你有 0 额度"，而这是两回事：功能还没有数据来源。
        assertThat(gift.total()).isNull();
        assertThat(gift.used()).isNull();
        assertThat(gift.reserved()).isNull();
        assertThat(gift.remaining()).isNull();
    }

    private QuotaEntitlementsService.Entitlements load() {
        QuotaEntitlementsService.Entitlements entitlements =
                service.entitlements(TENANT_ID, USER_ID).block();
        assertThat(entitlements).isNotNull();
        return entitlements;
    }

    private static QuotaEntitlementsService.PackageEntitlements packageOf(
            QuotaEntitlementsService.Entitlements entitlements, String usageDimension) {
        List<QuotaEntitlementsService.PackageEntitlements> matched = entitlements.packages().stream()
                .filter(value -> usageDimension.equals(value.usageDimension()))
                .toList();
        assertThat(matched).as("packages with dimension %s", usageDimension).hasSize(1);
        return matched.getFirst();
    }

    private static QuotaEntitlementsService.ModelEntitlement modelOf(
            QuotaEntitlementsService.PackageEntitlements packageEntitlements, String modelCode) {
        List<QuotaEntitlementsService.ModelEntitlement> matched =
                packageEntitlements.models().stream()
                        .filter(value -> modelCode.equals(value.modelCode()))
                        .toList();
        assertThat(matched).as("model %s", modelCode).hasSize(1);
        return matched.getFirst();
    }

    private static void seedPackage(
            String id, UUID userId, String status, String usageDimension,
            long totalQuota, long reserved, long used, Long audioSecondsQuota, String allowedModels)
            throws Exception {
        String audioSeconds = audioSecondsQuota == null ? "NULL" : audioSecondsQuota.toString();
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    identity_key, package_plan_version_id, package_code, package_name,
                    price_amount, currency, allowed_models, total_token_quota,
                    reserved_tokens, used_tokens, max_api_keys, requests_per_minute,
                    tokens_per_minute, max_concurrency, duration_days, daily_request_limit,
                    max_file_size_bytes, once_per_user, usage_dimension, audio_seconds_quota,
                    status, starts_at, expires_at, activated_at)
                VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', '%s',
                    'identity-%s', gen_random_uuid(), 'STARTER', 'Starter', 1900, 'CNY',
                    '%s', %d,
                    %d, %d, 3, 20, 100000, 3, 30, 200, 20971520, false, '%s', %s,
                    '%s',
                    CASE WHEN '%s'='ACTIVE' THEN now() ELSE NULL END,
                    CASE WHEN '%s'='ACTIVE' THEN now() + interval '30 days' ELSE NULL END,
                    CASE WHEN '%s'='ACTIVE' THEN now() ELSE NULL END)
                """.formatted(id, TENANT_ID, userId, userId, allowedModels.replace("'", "''"),
                totalQuota, reserved, used, usageDimension, audioSeconds,
                status, status, status, status));
    }

    private static void seedModelQuota(
            String id, String packageId, String modelCode, String usageDimension,
            long quota, long reserved, long used) throws Exception {
        String tokenQuota = "TOKEN".equals(usageDimension) ? String.valueOf(quota) : "NULL";
        String secondsQuota = "TOKEN".equals(usageDimension) ? "NULL" : String.valueOf(quota);
        execute("""
                INSERT INTO gateway.package_instance_model_quota(
                    id, package_instance_id, model_code, usage_dimension,
                    token_quota, seconds_quota, reserved_tokens, used_tokens)
                VALUES ('%s', '%s', '%s', '%s', %s, %s, %d, %d)
                """.formatted(id, packageId, modelCode, usageDimension,
                tokenQuota, secondsQuota, reserved, used));
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
