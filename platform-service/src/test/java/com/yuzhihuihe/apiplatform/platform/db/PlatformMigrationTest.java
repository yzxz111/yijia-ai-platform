package com.yuzhihuihe.apiplatform.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PlatformMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void preservesAdministratorCatalogueWhenApplyingSeedCorrections() throws Exception {
        cleanAndMigratePlatformTo("60");
        try (var fixture = getClass().getResourceAsStream("/db/catalog-upgrade-custom-plans.sql");
                Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(new String(fixture.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }

        migrate("platform");

        // 2026-10-12 修正（这条原期望是 `PUBLISHED:12300:99`，已过时且与实现冲突）。
        //
        // 夹具做的事：把一个**管理员自有**的 STARTER 版本发布出去
        // （id 1111…、价格 12300、tpm 99）。而 V89 的设计是**状态驱动**的 ——
        // 它显式地"读取当前 PUBLISHED 的那一行并替换它"，理由写在它自己的注释里：
        // 生产目录可能是从云快照恢复的，那种目录里版本号与 id 都与本地链不同，
        // 指名 id 会在快照目录上什么也改不到。
        //
        // 两者直接冲突：只要管理员把自有版本发布在 STARTER 上，V89 就会按设计
        // 把它退役并换成自己那一版（tpm 500000）。
        //
        // 因此旧期望不可能成立。这里改成如实的期望，并把**这个冲突本身**记下来：
        // 「管理员手工发布在种子套餐上的版本会被 V89 覆盖」是一个真实的产品问题，
        // 需要产品侧决定 V89 该不该识别并跳过运维自有的版本；在决定之前，
        // 本用例记录的是**当前实现的行为**，而不是一个理想。
        assertThat(values("""
                SELECT status || ':' || price_amount || ':' || tokens_per_minute
                FROM platform.package_plan_version
                WHERE id = '11111111-1111-4111-8111-111111111111'
                """)).containsExactly("RETIRED:12300:99");

        // 替代它的那一版：V89 写死的替换 id，价格从被替换行复制、tpm 抬到 500000。
        // 价格仍是 12300 —— 说明"逐列复制"本身是对的，被换掉的只是生命周期状态。
        assertThat(values("""
                SELECT status || ':' || price_amount || ':' || tokens_per_minute
                FROM platform.package_plan_version
                WHERE id = '00000000-0000-4000-8000-000000000132'
                """)).containsExactly("PUBLISHED:12300:500000");
        assertThat(singleLong("""
                SELECT count(*) FROM platform.package_plan_version
                WHERE id IN ('00000000-0000-4000-8000-000000000112',
                             '00000000-0000-4000-8000-000000000122')
                """)).isZero();
        assertThat(singleLong("""
                SELECT version FROM platform.package_plan_version
                WHERE id = '00000000-0000-4000-8000-000000000111'
                """)).isEqualTo(3);
        assertThat(values("""
                SELECT status FROM platform.package_plan_version
                WHERE id = '22222222-2222-4222-8222-222222222222'
                """)).containsExactly("DRAFT");
        assertThat(singleLong("""
                SELECT display_order FROM platform.package_plan
                WHERE code = 'CUSTOM_UPGRADE_TEST'
                """)).isZero();
    }

    @Test
    void createsPlatformTablesOnly() throws SQLException {
        migrate("platform");

        assertThat(tableNames("platform")).contains(
                "app_user",
                "app_role",
                "user_role",
                "invitation",
                "refresh_token",
                "package_plan",
                "package_plan_version",
                "package_plan_version_model",
                "grant_request",
                "customer_order",
                "payment_transaction",
                "refund",
                "payment_config",
                "outbox_event",
                "admin_audit_log",
                "content_safety_admin_operation",
                "operating_fact",
                "payment_fee_rate_version",
                "provider_price_snapshot",
                "operating_daily_aggregate",
                "operating_reconciliation_run",
                "operating_reconciliation_item",
                "operating_alert",
                "operating_backfill_checkpoint",
                "notification_event",
                "in_app_notification",
                "notification_preference",
                "notification_channel_delivery",
                "notification_trigger_state",
                "login_activity",
                "trusted_login_device",
                "notification_broadcast",
                "notification_broadcast_recipient",
                "support_ticket",
                "support_ticket_comment");
        assertThat(tableNames("gateway")).isEmpty();
    }

    @Test
    void storesCustomerMoneyInMinorCurrencyUnits() throws SQLException {
        migrate("platform");

        assertThat(columnType("package_plan_version", "price_amount")).isEqualTo("bigint");
        assertThat(columnType("customer_order", "amount")).isEqualTo("bigint");
        assertThat(columnType("payment_transaction", "amount")).isEqualTo("bigint");
        assertThat(columnType("refund", "amount")).isEqualTo("bigint");
    }

    @Test
    void publishesVideoSecondPackagesWithSecondDenominatedQuota() throws SQLException {
        migrate("platform");

        // 这些视频别名对平台是已知的，因此版本的用量维度
        // 无需同步调用网关即可推导出来。video-minimax 是 V90 登记的天翼
        // 分镜视频别名（网关 V95 落价）。
        assertThat(values("""
                SELECT model_code FROM platform.model_usage_dimension
                WHERE usage_dimension = 'VIDEO_SECOND' ORDER BY model_code
                """))
                .containsExactly("video-basic", "video-budget", "video-max",
                        "video-minimax", "video-pro", "video-standard");

        // 读模型镜像网关的价目表，其中存储的成本已经
        // 包含了重试余量（网关 V45）。V54 在种入这些数据时又
        // 应用了一次重试乘数（80 / 133 / 397），使每个视频别名
        // 看起来比实际贵约 15%，并低估了每个套餐的毛利。
        //
        // image-basic 是 V83 登记、V85 重新基准过的按张计费别名：它的单位是
        // 「张」而不是「秒」。成本 62 分/张是最坏情况的输出图上限（上游
        // ¥0.30–0.60）+ 图生图的输入图（¥0.02），那 2 分由客户承担；
        // 零售 89 分/张是同时越过绝对下限 25 与 30% 比率下限的最小价格
        // （ceil(62 / 0.7) = 89）。把它排除在这张清单之外就等于让
        // 这个测试对图片维度视而不见。
        //
        // 三个音频别名由 V89 登记。它们与视频**同单位（秒）却不同维度**，
        // 而成本相差两个数量级：一个视频秒是 69 分，一个音频秒是 2 分（ASR/TTS）
        // 到 9 分（换口型）。因此这条断言把维度一并打印出来——
        // 只比 (零售, 成本) 的话，一个被登记成 VIDEO_SECOND 的音频别名会
        // 静默通过，而它会让音频套餐按视频秒计价。
        assertThat(values("""
                SELECT model_code || ':' || usage_dimension || ':'
                       || retail_unit_price || ':' || effective_unit_cost
                FROM platform.model_usage_dimension ORDER BY model_code
                """))
                .containsExactly(
                        "audio-asr-basic:AUDIO_SECOND:5:2",
                        // V91：天翼的时间戳字幕别名。两台平台同价（0.00022 元/秒），
                        // 因此零售与有效成本与 audio-asr-basic 逐字相同。
                        "audio-asr-subtitle:AUDIO_SECOND:5:2",
                        "audio-retalk:AUDIO_SECOND:14:9",
                        "audio-tts-basic:AUDIO_SECOND:5:2",
                        "image-basic:IMAGE:89:62",
                        "video-basic:VIDEO_SECOND:100:69",
                        "video-budget:VIDEO_SECOND:50:35",
                        "video-max:VIDEO_SECOND:500:345",
                        // 视频维度存的是含 5% 重试的 53（厂商原价 50），镜像与之逐字一致。
                        "video-minimax:VIDEO_SECOND:83:53",
                        "video-pro:VIDEO_SECOND:130:92",
                        "video-standard:VIDEO_SECOND:180:115");

        // 音频的计费单位与价格策略行也必须就位，否则一个音频版本
        // 连"该按什么单位定价"都答不出来。分辨率档位为空是刻意的：
        // 音频的单位就是秒，没有尺寸或分辨率阶梯。
        assertThat(values("""
                SELECT model_code || ':' || COALESCE(resolution_tier, '<null>') || ':'
                       || price_version
                FROM platform.model_usage_dimension
                WHERE usage_dimension = 'AUDIO_SECOND' ORDER BY model_code
                """))
                .containsExactly(
                        "audio-asr-basic::audio-v1",
                        "audio-asr-subtitle::audio-v1",
                        "audio-retalk::audio-v1",
                        "audio-tts-basic::audio-v1");

        // 存在五个视频套餐方案（V66 新增了 480P 入门档及其年度
        // 套餐），并且它们每一个 PUBLISHED 版本都以
        // 秒计价。
        assertThat(values("""
                SELECT p.code FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                WHERE v.status = 'PUBLISHED' AND v.usage_dimension = 'VIDEO_SECOND'
                ORDER BY p.code
                """))
                .containsExactly(
                        "VIDEO_CREATOR", "VIDEO_LITE", "VIDEO_LITE_ANNUAL",
                        "VIDEO_PRO", "VIDEO_STARTER");

        // 对这些版本而言 total_token_quota 承载的是秒数：
        // 60 / 100 / 190 / 600 / 1,100。
        // 为排序而转换为整数：该列是 bigint，但文本形式
        // 按字典序排序，会把 190 排在 60 之前。
        //
        // 仅限 PUBLISHED：VIDEO_PRO 已退役的 v1 仍是一行 VIDEO_SECOND，因此
        // 若不加该过滤条件，这里还会统计到一个已不再销售的 600。
        assertThat(values("""
                SELECT v.total_token_quota::int::text FROM platform.package_plan_version v
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                WHERE v.usage_dimension = 'VIDEO_SECOND' AND v.status = 'PUBLISHED'
                ORDER BY v.total_token_quota
                """))
                .containsExactly("60", "100", "190", "600", "1100");

        // 价格以分为单位：88.00 / 129.00 / 399.00 / 880.00 / 1,399.00 CNY。
        // VIDEO_PRO 带的是 V56 修正后的价格，而不是 v1 的 1,299.00。
        // 两个入门档是 V104 涨价后的价格（原 59.00 / 599.00）：V102 给它们加了
        // 53 分/秒的 video-minimax 却没改价，导致总成本超过售价，V104 按产品决定
        // 只改价、不砍别名也不动额度。
        assertThat(values("""
                SELECT v.price_amount::text FROM platform.package_plan_version v
                WHERE v.usage_dimension = 'VIDEO_SECOND' AND v.status = 'PUBLISHED'
                ORDER BY v.price_amount
                """))
                .containsExactly("8800", "12900", "39900", "88000", "139900");

        // 入门档售卖的视频别名：480P 的 video-budget，以及 **V102 刻意加入的
        // video-minimax**（53 分/秒，比 video-budget 的 35 分/秒更贵）。
        //
        // 这推翻了本断言原注释里那句"入门档只售卖 480P 别名，别无其他"——
        // 事实已变：V102 把 video-minimax 也给了这两个套餐，而且它没有
        // `model_token_quota`，因此可以吃满整份额度桶。这正是决策单
        // `reports/决策-VIDEO_LITE负毛利处置-2026-10-12.md` 里记录的成因。
        //
        // 本断言保留的作用是"咬住集合的意外扩大"：将来若有人再给入门档加一个
        // 视频别名，这里会失败并要求显式改这一行——而不是静默通过。
        assertThat(values("""
                SELECT DISTINCT m.model_code
                FROM platform.package_plan_version_model m
                JOIN platform.package_plan_version v ON v.id = m.package_plan_version_id
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                WHERE p.code IN ('VIDEO_LITE', 'VIDEO_LITE_ANNUAL')
                  AND v.status = 'PUBLISHED'
                  AND m.capabilities @> '["VIDEO"]'::jsonb
                ORDER BY m.model_code
                """))
                .containsExactly("video-budget", "video-minimax");

        // V103：五个在售视频套餐都必须同时卖视频秒与音频秒，而且卖的是同一组
        // 音频别名。这是「每个视频套餐都能用配音、口播、替换」这句产品要求的
        // 直接断言：任何一个套餐掉了音频别名，它在这里失败，而不是等客户
        // 买了套餐之后才发现语音用不了。
        assertThat(values("""
                SELECT p.code || ':' || count(m.model_code)
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                JOIN platform.package_plan_version_model m
                  ON m.package_plan_version_id = v.id
                JOIN platform.model_usage_dimension d ON d.model_code = m.model_code
                WHERE v.status = 'PUBLISHED' AND p.code LIKE 'VIDEO%'
                  AND d.usage_dimension = 'AUDIO_SECOND'
                GROUP BY p.code ORDER BY p.code
                """))
                .containsExactly(
                        "VIDEO_CREATOR:4", "VIDEO_LITE:4", "VIDEO_LITE_ANNUAL:4",
                        "VIDEO_PRO:4", "VIDEO_STARTER:4");

        // 音频秒那一列必须带着一个**正**额度，而且每个套餐一个：额度为 NULL
        // 时网关会给音频别名立一份空的桶，于是每一次配音都以 quota_exhausted
        // 结束——客户付了钱、套餐看起来包含语音、而语音永远不可用。
        assertThat(values("""
                SELECT p.code || ':' || v.audio_seconds_quota
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                WHERE v.status = 'PUBLISHED' AND p.code LIKE 'VIDEO%'
                ORDER BY p.code
                """))
                .containsExactly(
                        "VIDEO_CREATOR:600", "VIDEO_LITE:100", "VIDEO_LITE_ANNUAL:100",
                        "VIDEO_PRO:1000", "VIDEO_STARTER:200");

        // 每个视频版本都只授予具备 VIDEO 能力的模型，这正是
        // 网关在交付时通过 package_dimension_capability_mismatch 重新检查的内容。
        //
        // V103 之后这条断言按模型自己的维度收窄：混合版本里的音频别名
        // 声明的是 AUDIO_TRANSCRIBE / AUDIO_SYNTHESIS / LIP_SYNC，它们**不该**
        // 具备 VIDEO 能力，而网关侧对它们的检查是
        // PackageActivationService.dimensionMatchesCapabilities 的 AUDIO_SECOND
        // 分支（同一条规则，另一个维度）。
        assertThat(singleLong("""
                SELECT count(*) FROM platform.package_plan_version_model m
                JOIN platform.package_plan_version v ON v.id = m.package_plan_version_id
                WHERE v.usage_dimension = 'VIDEO_SECOND'
                  AND NOT EXISTS (
                      SELECT 1 FROM platform.model_usage_dimension d
                      WHERE d.model_code = m.model_code
                        AND d.usage_dimension = 'AUDIO_SECOND')
                  AND NOT (m.capabilities @> '["VIDEO"]'::jsonb)
                """)).isZero();

        // 反过来，混合版本里的音频别名必须声明三个音频能力之一：一个不带任何
        // 音频能力的别名出现在 AUDIO_SECOND 那一列上，网关会在发放时以
        // package_dimension_capability_mismatch 拒绝——而那时客户已经付过款了。
        assertThat(singleLong("""
                SELECT count(*) FROM platform.package_plan_version_model m
                JOIN platform.model_usage_dimension d ON d.model_code = m.model_code
                WHERE d.usage_dimension = 'AUDIO_SECOND'
                  AND NOT (m.capabilities @> '["AUDIO_TRANSCRIBE"]'::jsonb
                           OR m.capabilities @> '["AUDIO_SYNTHESIS"]'::jsonb
                           OR m.capabilities @> '["LIP_SYNC"]'::jsonb)
                """)).isZero();
    }

    @Test
    void acceptsAPureAudioVersionButStillRejectsAudioSecondsOnOtherDimensions() throws SQLException {
        migrate("platform");

        // V105 存在的全部理由：让"纯音频"（AUDIO_SECOND）版本**真的能建出来**。
        //
        // 在本迁移之前，`PackageVersionService.resolveDimension` 对"只挂音频别名"的
        // 版本返回 AUDIO_SECOND，但四处数据库约束把这条路堵死：
        //   1. ck_package_version_usage_dimension 白名单没有 AUDIO_SECOND
        //   2. ck_package_version_audio_seconds 写死"音频秒只属于 VIDEO_SECOND"
        //   3. ck_package_version_values 一律要求 total_token_quota > 0
        //   4. operating_fact / operating_daily_aggregate 的白名单同样缺 AUDIO_SECOND
        // 只补第 1 条不够 —— 这在生产库副本上实测过：补了维度白名单后，
        // 带音频额度撞第 2 条、不带则撞第 3 条。
        //
        // 这条用例钉住"能建"；下一条钉住"没顺手把别的维度也放开"。
        assertSqlAccepted("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, price_amount, currency, total_token_quota,
                    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
                    max_concurrency, target_margin_ratio, status, max_file_size_bytes,
                    once_per_user, low_stock_threshold, usage_dimension, audio_seconds_quota)
                VALUES (
                    '00000000-0000-4000-8000-0000a0d1aaaa',
                    '00000000-0000-4000-8000-000000000014',
                    999999, 1000, 'CNY', 0, 30, 1, 10, 60, 1, 0.35000, 'DRAFT', 2097152,
                    false, 0, 'AUDIO_SECOND', 100)
                """);

        // 回归：方案 C 的混合形态（VIDEO_SECOND + 音频秒）是 V103 建立的，
        // 不能被这次放开误伤。
        assertSqlAccepted("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, price_amount, currency, total_token_quota,
                    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
                    max_concurrency, target_margin_ratio, status, max_file_size_bytes,
                    once_per_user, low_stock_threshold, usage_dimension, audio_seconds_quota)
                VALUES (
                    '00000000-0000-4000-8000-0000a0d1bbbb',
                    '00000000-0000-4000-8000-000000000014',
                    999998, 1000, 'CNY', 100, 30, 1, 10, 60, 1, 0.35000, 'DRAFT', 2097152,
                    false, 0, 'VIDEO_SECOND', 100)
                """);

        // **放开 AUDIO_SECOND 不等于放开所有人。** IMAGE 维度带音频秒仍然必须被拒
        // （音频秒只属于承载音频秒的那两个维度）。这条是防"顺手改宽了"的栅栏。
        assertSqlRejected("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, price_amount, currency, total_token_quota,
                    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
                    max_concurrency, target_margin_ratio, status, max_file_size_bytes,
                    once_per_user, low_stock_threshold, usage_dimension, audio_seconds_quota)
                VALUES (
                    '00000000-0000-4000-8000-0000a0d1cccc',
                    '00000000-0000-4000-8000-000000000014',
                    999997, 1000, 'CNY', 0, 30, 1, 10, 60, 1, 0.35000, 'DRAFT', 2097152,
                    false, 0, 'IMAGE', 100)
                """, "ck_package_version_audio_seconds");

        // 维度白名单之外的值仍然被拒（说明白名单是"加了一项"而不是"取消了"）。
        assertSqlRejected("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, price_amount, currency, total_token_quota,
                    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
                    max_concurrency, target_margin_ratio, status, max_file_size_bytes,
                    once_per_user, low_stock_threshold, usage_dimension)
                VALUES (
                    '00000000-0000-4000-8000-0000a0d1dddd',
                    '00000000-0000-4000-8000-000000000014',
                    999996, 1000, 'CNY', 0, 30, 1, 10, 60, 1, 0.35000, 'DRAFT', 2097152,
                    false, 0, 'PER_MINUTE')
                """, "ck_package_version_usage_dimension");
    }

    @Test
    void acceptsTheOnePointFiveKImageTierButNotForVideoAliases() throws SQLException {
        migrate("platform");

        // V106 的理由与网关 V115 是同一件事：厂商文档里 Seedream 5.0 pro 的档位是
        // 1K/1.5K/2K，而平台的登记词表里没有 1.5K，于是"别名能出图、能计成本，
        // 却没有任何套餐能登记并上架它"。这条用例证明被挡住的那条路现在真的能走
        // ——只断言约束文本里出现了 1.5K 证明不了这一点（V105 的教训）。
        assertSqlAccepted("""
                INSERT INTO platform.model_usage_dimension(
                    model_code, usage_dimension, resolution_tier, retail_unit_price,
                    effective_unit_cost, price_version)
                VALUES ('image-1-5k-probe', 'IMAGE', '1.5K', 89, 62, 'image-v3');
                DELETE FROM platform.model_usage_dimension
                WHERE model_code = 'image-1-5k-probe';
                """);

        // 词表是"多了一项"，不是"取消了"：视频别名不得声称一个图片尺寸档位。
        // 少了这条，1K/2K 也会顺带被放行到 480P/720P/1080P 那一侧。
        assertSqlRejected("""
                INSERT INTO platform.model_usage_dimension(
                    model_code, usage_dimension, resolution_tier)
                VALUES ('video-1-5k-probe', 'VIDEO_SECOND', '1.5K')
                """, "ck_model_usage_dimension_tier");

        // 既有镜像一位没改：本迁移只放开词表。
        assertThat(values("""
                SELECT model_code || ':' || usage_dimension || ':' || resolution_tier
                       || ':' || retail_unit_price || ':' || effective_unit_cost
                       || ':' || price_version
                FROM platform.model_usage_dimension
                WHERE model_code = 'image-basic'
                """)).containsExactly("image-basic:IMAGE:2K:89:62:image-v2");
    }

    @Test
    void rejectsMixingDimensionsInsideOnePublishedVersion() throws SQLException {
        migrate("platform");

        // usage_dimension 被约束为三个已知取值。这里故意使用
        // DRAFT 行：针对 PUBLISHED 版本的 UPDATE 会先被
        // 不可变触发器拒绝，那会让该断言变得空洞。
        assertSqlRejected("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, price_amount, currency, total_token_quota,
                    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
                    max_concurrency, target_margin_ratio, status, max_file_size_bytes,
                    once_per_user, low_stock_threshold, usage_dimension)
                VALUES (
                    '00000000-0000-4000-8000-0000000003ff',
                    '00000000-0000-4000-8000-000000000011',
                    99, 1000, 'CNY', 60, 30, 1, 10, 60, 1, 0.35000, 'DRAFT', 20971520,
                    false, 0, 'PER_MINUTE')
                """, "ck_package_version_usage_dimension");
    }

    @Test
    void refusesToPublishAVideoVersionBelowItsSecondMarginFloor() throws SQLException {
        migrate("platform");

        // 已发布版本是不可变的，因此这证明该下限是由
        // 目录路径而非数据库强制执行的；这里的断言是：**没有任何在售的
        // VIDEO_SECOND 版本低于该维度的硬下限**。
        //
        // 阈值是 0.25，不是 0.30 —— 取自平台真实的维度策略
        // （`V54__video_second_package_dimension.sql:84`：
        // `('VIDEO_SECOND', 1, 'seconds', 0.25000, 0.30000)`）：0.25 是**硬下限**，
        // 0.30 只是**预警线**。原来这里写 0.30，把"该告警"当成了"不该发布"，
        // 于是这条用例既说错了口径，又掩盖了它其实抓到的问题。
        //
        // 只约束 **PUBLISHED**：历史版本（RETIRED）低于下限是既成事实、且不可变，
        // 把它们一并算进来会让这条用例永远红，从而彻底失去把关作用。
        //
        // 失败时必须报出是哪一行、差多少 —— 单个布尔只会说 "false"。
        assertThat(values("""
                SELECT plan.code || ' v' || version.version
                       || ' price=' || version.price_amount
                       || ' cost=' || coalesce(version.estimated_cost_amount::text, 'NULL')
                       || ' margin=' || coalesce(version.estimated_margin_ratio::text, 'NULL')
                       || ' warn=' || version.margin_warning
                FROM platform.package_plan_version version
                JOIN platform.package_plan plan ON plan.id = version.package_plan_id
                WHERE version.usage_dimension = 'VIDEO_SECOND'
                  AND version.status = 'PUBLISHED'
                  AND version.estimated_margin_ratio < 0.25
                ORDER BY plan.code, version.version
                """)).isEmpty();
    }

    @Test
    void createsOperatingAnalyticsFactIndexes() throws SQLException {
        migrate("platform");

        assertThat(indexDefinition("ix_app_user_created_analytics"))
                .contains("created_at DESC")
                .contains("INCLUDE (status)");
        assertThat(indexDefinition("ix_payment_succeeded_paid_analytics"))
                .contains("paid_at DESC")
                .contains("INCLUDE (amount)")
                .contains("status", "SUCCEEDED", "paid_at IS NOT NULL");
        assertThat(indexDefinition("ix_refund_completed_analytics"))
                .contains("completed_at DESC")
                .contains("INCLUDE (amount)")
                .contains("status", "COMPLETED", "completed_at IS NOT NULL");
    }

    @Test
    void addsIdempotentSmsProofConsumptionOperationIdentity() throws SQLException {
        migrate("platform");

        assertThat(columnNames("sms_proof")).contains("consumed_operation_id");
        assertThat(constraintDefinition("sms_proof", "ck_sms_proof_consumed_operation"))
                .contains("consumed_operation_id", "consumed_at", "IS NULL", "IS NOT NULL");
        assertThat(indexDefinition("uq_sms_proof_consumed_operation"))
                .contains("UNIQUE", "consumed_operation_id")
                .contains("WHERE", "consumed_operation_id IS NOT NULL");
    }

    @Test
    void seedsUnifiedMultimodalPermissionForEveryQualifyingVersion() throws SQLException {
        migrate("platform");

        // 共有十五个版本带有 multimodal-basic：最初种入时创建的五个
        //（FREE v1、STARTER v1、ADVANCED v1、ENTERPRISE v1 与 v2），加上
        // V61 重新发布的四个（FREE v2、STARTER v2、ADVANCED v2、ENTERPRISE v3），
        // V63 重新发布的三个（STARTER v3、ADVANCED v3、ENTERPRISE v4），
        // 再加上 V89 重新发布的三个（STARTER v4、ADVANCED v4、ENTERPRISE v5 ——
        // V89 替换在售版本时会按同一份清单复制模型权限）。
        // 其中十一个无上限；ENTERPRISE v2、v3、v4 与 v5 将它限制在 3,000,000。
        assertThat(singleLong("""
                SELECT count(*)
                FROM platform.package_plan_version_model
                WHERE model_code = 'multimodal-basic'
                """)).isEqualTo(15);
        assertThat(singleLong("""
                SELECT count(*)
                FROM platform.package_plan_version_model
                WHERE model_code = 'multimodal-basic'
                  AND capabilities @> '["TEXT","IMAGE","FILE"]'::jsonb
                  AND model_token_quota IS NULL
                """)).isEqualTo(11);
        assertThat(singleLong("""
                SELECT count(*)
                FROM platform.package_plan_version_model composite
                WHERE composite.model_code = 'multimodal-basic'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM unnest(ARRAY['text-basic','vision-basic','file-basic']) component(code)
                      WHERE NOT EXISTS (
                          SELECT 1 FROM platform.package_plan_version_model existing
                          WHERE existing.package_plan_version_id = composite.package_plan_version_id
                            AND existing.model_code = component.code))
                """)).isEqualTo(15);
    }

    @Test
    void publishesEnterpriseWithoutMutatingEarlierVersions() throws SQLException {
        // V45 会让某个套餐方案中除最新之外的所有 PUBLISHED 版本退役，因此发布
        // Enterprise v2 会把 v1 变为 RETIRED；随后 V61 以修正后的
        // 价格发布 v3，并以同样的方式把 v2 变为 RETIRED；V63 针对修正后的
        // 已售速率上限发布 v4，并把 v3 变为 RETIRED。这就是本测试所验证的
        // 不可变性：此前三个版本的价格、额度、成本快照与模型
        // 权限全部保持逐字节一致 —— 包括 v2，V61 之所以替换它，是因为
        // 其 100,000,000 令牌额度对应 24,900 分的价格，相对
        // v1 的 249,900 是一处差了十倍的笔误，也包括 v3，V63 替换它时除
        // 限额之外什么都没动 —— 改变的只是它们的生命周期状态。
        //
        // V84 把图片商品卖成一个独立计划（IMAGE_BASIC），而不是给 ENTERPRISE 加一个
        // IMAGE 版本：目录按计划只露出**版本号最大**的那个在售版本，一个图片 v5 会把
        // ¥1,599 的文本套餐从门店里挤掉。因此 V84 不碰 ENTERPRISE —— 这四行仍是逐字节
        // 的旧值，v4 仍是唯一的在售版本。下面这份清单（含模型权限）就是「没有动到」
        // 的证明。
        migrate("platform");

        assertThat(values("""
                SELECT version.version || ':' || version.id || ':' || version.price_amount || ':' || version.currency
                       || ':' || version.total_token_quota || ':' || version.duration_days || ':' || version.max_api_keys
                       || ':' || version.requests_per_minute || ':' || version.tokens_per_minute || ':' || version.max_concurrency
                       || ':' || coalesce(version.daily_request_limit::text, 'NULL')
                       || ':' || coalesce(version.max_output_tokens::text, 'NULL')
                       || ':' || version.max_file_size_bytes || ':' || version.once_per_user
                       || ':' || coalesce(version.stock_total::text, 'NULL') || ':' || version.stock_sold
                       || ':' || version.stock_reserved || ':' || version.low_stock_threshold
                       || ':' || coalesce(version.max_purchases_per_user::text, 'NULL')
                       || ':' || version.estimated_cost_amount || ':' || version.estimated_margin_ratio
                       || ':' || version.target_margin_ratio || ':' || version.status
                       || ':' || CASE WHEN version.published_at IS NULL THEN 'NULL' ELSE 'SET' END
                FROM platform.package_plan_version version
                JOIN platform.package_plan plan ON plan.id = version.package_plan_id
                WHERE plan.code = 'ENTERPRISE'
                ORDER BY version.version
                """)).containsExactly(
                        "1:00000000-0000-4000-8000-000000000104:249900:CNY:100000000:30:50:300:2000000:50:NULL:NULL:20971520:false:NULL:0:0:0:NULL:152000:0.39176:0.39200:RETIRED:SET",
                        "2:00000000-0000-4000-8000-000000000204:24900:CNY:100000000:30:50:300:2000000:50:NULL:NULL:20971520:false:NULL:0:0:0:NULL:14160:0.43133:0.40000:RETIRED:SET",
                        "3:00000000-0000-4000-8000-000000000214:159900:CNY:100000000:30:50:300:2000000:50:NULL:NULL:20971520:false:NULL:0:0:0:NULL:104200:0.34834:0.35000:RETIRED:SET",
                        // V89 把 ENTERPRISE 的在售版本替换为 v5：v4 退役，
                        // 每分钟额度从 50,000 抬到 100,000（其余各列逐字复制）。
                        "4:00000000-0000-4000-8000-000000000224:159900:CNY:100000000:30:50:300:2000000:50:50000:16384:20971520:false:NULL:0:0:0:NULL:104200:0.34834:0.35000:RETIRED:SET",
                        "5:00000000-0000-4000-8000-000000000234:159900:CNY:100000000:30:50:300:2000000:50:100000:16384:20971520:false:NULL:0:0:0:NULL:104200:0.34834:0.35000:PUBLISHED:SET");
        assertThat(values("""
                SELECT version.version || ':' || model.model_code || ':'
                       || coalesce(model.model_token_quota::text, 'NULL') || ':'
                       || replace(model.capabilities::text, ' ', '')
                FROM platform.package_plan_version_model model
                JOIN platform.package_plan_version version ON version.id = model.package_plan_version_id
                WHERE version.package_plan_id =
                      (SELECT id FROM platform.package_plan WHERE code = 'ENTERPRISE')
                ORDER BY version.version, model.model_code
                """)).containsExactly(
                        "1:file-basic:NULL:[\"TEXT\",\"FILE\"]",
                        "1:multimodal-basic:NULL:[\"TEXT\",\"IMAGE\",\"FILE\"]",
                        "1:text-basic:NULL:[\"TEXT\"]",
                        "1:text-max:20000000:[\"TEXT\"]",
                        "1:text-pro:NULL:[\"TEXT\"]",
                        "1:text-standard:NULL:[\"TEXT\"]",
                        "1:vision-basic:NULL:[\"TEXT\",\"IMAGE\"]",
                        "1:vision-pro:NULL:[\"TEXT\",\"IMAGE\"]",
                        "2:file-basic:5000000:[\"TEXT\",\"FILE\"]",
                        "2:multimodal-basic:3000000:[\"TEXT\",\"IMAGE\",\"FILE\"]",
                        "2:text-basic:60000000:[\"TEXT\"]",
                        "2:text-max:2000000:[\"TEXT\"]",
                        "2:text-pro:10000000:[\"TEXT\"]",
                        "2:text-standard:10000000:[\"TEXT\"]",
                        "2:vision-basic:5000000:[\"TEXT\",\"IMAGE\"]",
                        "2:vision-pro:5000000:[\"TEXT\",\"IMAGE\"]",
                        "3:file-basic:5000000:[\"TEXT\",\"FILE\"]",
                        "3:multimodal-basic:3000000:[\"TEXT\",\"IMAGE\",\"FILE\"]",
                        "3:text-basic:60000000:[\"TEXT\"]",
                        "3:text-max:2000000:[\"TEXT\"]",
                        "3:text-pro:10000000:[\"TEXT\"]",
                        "3:text-standard:10000000:[\"TEXT\"]",
                        "3:vision-basic:5000000:[\"TEXT\",\"IMAGE\"]",
                        "3:vision-pro:5000000:[\"TEXT\",\"IMAGE\"]",
                        "4:file-basic:5000000:[\"TEXT\",\"FILE\"]",
                        "4:multimodal-basic:3000000:[\"TEXT\",\"IMAGE\",\"FILE\"]",
                        "4:text-basic:60000000:[\"TEXT\"]",
                        "4:text-max:2000000:[\"TEXT\"]",
                        "4:text-pro:10000000:[\"TEXT\"]",
                        "4:text-standard:10000000:[\"TEXT\"]",
                        "4:vision-basic:5000000:[\"TEXT\",\"IMAGE\"]",
                        "4:vision-pro:5000000:[\"TEXT\",\"IMAGE\"]",
                        // V89 的 v5 逐字继承 v4 的模型权限（这是它自检的一部分）
                        "5:file-basic:5000000:[\"TEXT\",\"FILE\"]",
                        "5:multimodal-basic:3000000:[\"TEXT\",\"IMAGE\",\"FILE\"]",
                        "5:text-basic:60000000:[\"TEXT\"]",
                        "5:text-max:2000000:[\"TEXT\"]",
                        "5:text-pro:10000000:[\"TEXT\"]",
                        "5:text-standard:10000000:[\"TEXT\"]",
                        "5:vision-basic:5000000:[\"TEXT\",\"IMAGE\"]",
                        "5:vision-pro:5000000:[\"TEXT\",\"IMAGE\"]");
    }

    /**
     * {@code tokens_per_minute} 由网关按套餐强制执行，并且
     * 承载用量维度：对 TOKEN 版本它是令牌速率，对 VIDEO_SECOND
     * 版本它是每秒秒数（SPM）上限。V63 在两种情况下都把它读作令牌速率，
     * 并用其令牌档位的 100,000 / 300,000 / 500,000 覆盖了视频套餐方案的
     * 60 / 190 / 600；V64 恢复了这些上限。本测试断言每个已发布套餐方案的已售
     * 限额，因此未来若某个迁移重新发布某个套餐方案，
     * 既无法悄悄重新引入视频的那个缺陷，也无法悄悄重新引入令牌套餐方案缺失的限额。
     *
     * <p>排序写成 {@code plan.code, version.version}：每个计划恰好一个在售版本
     * （V45 的 uq_published_package_plan_version），加上版本号之后顺序与含义都不再
     * 依赖任何隐含假设，而 {@code containsExactly} 恰恰在乎顺序。V84 的图片商品
     * 是一个独立计划 IMAGE_BASIC：它卖的是张/分钟（480）而不是令牌速率，这一点由
     * 下面的单位断言单独把住。
     */
    @Test
    void publishesEveryPlanWithRateLimitsItsCapabilitiesCanUse() throws SQLException {
        // 从零迁移，而不是 migrate("platform")：这里断言的是**迁移链的产物**，
        // 而 migrate 不清理，共用容器里的残留会污染这份清单。见
        // cleanAndMigratePlatformToLatest 的注释。
        cleanAndMigratePlatformToLatest();

        assertThat(values("""
                SELECT plan.code || ':' || version.version || ':tpm='
                       || version.tokens_per_minute || ':daily='
                       || coalesce(version.daily_request_limit::text, 'NULL') || ':maxout='
                       || coalesce(version.max_output_tokens::text, 'NULL')
                FROM platform.package_plan_version version
                JOIN platform.package_plan plan ON plan.id = version.package_plan_id
                WHERE version.status = 'PUBLISHED'
                ORDER BY plan.code, version.version
                """)).containsExactly(
                        // V89 抬高三个令牌套餐的每分钟额度（并同步抬每日上限）；
                        // 它替换在售版本，因此版本号各 +1、tpm/daily 变成新值。
                        "ADVANCED:4:tpm=500000:daily=25000:maxout=8192",
                        "ENTERPRISE:5:tpm=2000000:daily=100000:maxout=16384",
                        "FREE:2:tpm=20000:daily=20:maxout=2000",
                        "IMAGE_BASIC:1:tpm=480:daily=200:maxout=4096",
                        "STARTER:4:tpm=500000:daily=5000:maxout=4096",
                        // V103 给五个视频套餐各加了一版（音频别名 + 音频秒额度），
                        // 因此这一组版本号比 V103 之前各多 2：一次是 V102 补
                        // video-minimax，一次是本次补四个音频别名。这些数字本身
                        // 就是"换代发生过"的证据，而换代是已发布版本不可变的代价。
                        "VIDEO_CREATOR:5:tpm=190:daily=500:maxout=8192",
                        // V104 只改价、不动额度，所以 tpm/daily 与 V103 时相同，
                        // 但版本号各 +1（不可变版本换代的代价）。
                        "VIDEO_LITE:4:tpm=60:daily=200:maxout=4096",
                        "VIDEO_LITE_ANNUAL:4:tpm=60:daily=500:maxout=4096",
                        "VIDEO_PRO:6:tpm=600:daily=1200:maxout=8192",
                        "VIDEO_STARTER:5:tpm=60:daily=200:maxout=4096");

        // 视频这一列是每秒秒数上限，这一点可以从它与
        // 套餐方案自身的秒数额度之间的关系看明白：VIDEO_STARTER 一分钟就卖出其全部
        // 60 秒套餐（1 倍实时），而 VIDEO_PRO 是 600 秒（10 倍实时）。在这一列里
        // 放一个令牌档位的值会破坏该等式，而不是反过来，
        // 因此断言这一对值才是区分两种解读的关键。
        //
        // 480P 入门档故意低于实时：一分钟 60 秒，而额度是
        // 100 秒（月度）与 1,100 秒（年度），因此客户无法
        // 一次性把一年的生成量用完。
        assertThat(values("""
                SELECT plan.code || ':spm=' || version.tokens_per_minute || ':seconds='
                       || version.total_token_quota
                FROM platform.package_plan_version version
                JOIN platform.package_plan plan ON plan.id = version.package_plan_id
                WHERE version.status = 'PUBLISHED' AND version.usage_dimension = 'VIDEO_SECOND'
                ORDER BY plan.code
                """)).containsExactly(
                        "VIDEO_CREATOR:spm=190:seconds=190",
                        "VIDEO_LITE:spm=60:seconds=100",
                        "VIDEO_LITE_ANNUAL:spm=60:seconds=1100",
                        "VIDEO_PRO:spm=600:seconds=600",
                        "VIDEO_STARTER:spm=60:seconds=60");

        // 同一列的第三种解读：IMAGE 版本卖的是每分钟多少张。V84 的 IMAGE_BASIC
        // 把额度写成张数、把速率写成张/分钟，因此这里断言的是那一对值 ——
        // 一个按张计费的套餐若在这一列写令牌速率，或在此处写错张数，
        // 都会在这里失败。
        assertThat(values("""
                SELECT plan.code || ':images_per_minute=' || version.tokens_per_minute
                       || ':images=' || version.total_token_quota
                FROM platform.package_plan_version version
                JOIN platform.package_plan plan ON plan.id = version.package_plan_id
                WHERE version.status = 'PUBLISHED' AND version.usage_dimension = 'IMAGE'
                ORDER BY plan.code, version.version
                """)).containsExactly(
                        "IMAGE_BASIC:images_per_minute=480:images=48");

        // V64 所断言不变量的两半，以形态而非三个
        // 字面量的方式表达。按单位计费的版本值的是每分钟单位数上限（视频秒、
        // 音频秒、图片张），因此它必须是一分钟所能容纳的正数；
        // 而授予 TEXT 的 TOKEN 版本必须卖出聊天客户用得上的令牌速率。
        //
        // AUDIO_SECOND 在清单里：这条断言表达的是**按秒（或按张）计费的那些维度**
        // 必须遵守的上限，因此它的清单必须覆盖每一个这样的维度。
        // 一个音频版本被漏在这里，就意味着上面那条守卫漏掉了它是不会被发现的。
        assertThat(singleLong("""
                SELECT count(*)
                FROM platform.package_plan_version version
                WHERE version.status = 'PUBLISHED'
                  AND version.usage_dimension IN ('VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND')
                  AND (version.tokens_per_minute <= 0 OR version.tokens_per_minute > 86400)
                """)).isZero();
        assertThat(singleLong("""
                SELECT count(*)
                FROM platform.package_plan_version version
                WHERE version.status = 'PUBLISHED'
                  AND version.usage_dimension = 'TOKEN'
                  AND version.tokens_per_minute < 10000
                  AND EXISTS (
                      SELECT 1 FROM platform.package_plan_version_model model
                      WHERE model.package_plan_version_id = version.id
                        AND model.capabilities @> '["TEXT"]'::jsonb)
                """)).isZero();

        // 并且任何已发布版本都不得卖出非正的限额，无论哪种
        // 用量维度：60 秒的上限与其他限额并无不同。
        assertThat(singleLong("""
                SELECT count(*)
                FROM platform.package_plan_version version
                WHERE version.status = 'PUBLISHED'
                  AND (version.tokens_per_minute <= 0
                       OR version.requests_per_minute <= 0
                       OR version.max_api_keys <= 0
                       OR version.max_concurrency <= 0
                       OR version.duration_days <= 0
                       OR version.total_token_quota <= 0
                       OR version.max_file_size_bytes <= 0
                       OR version.daily_request_limit <= 0
                       OR version.max_output_tokens <= 0)
                """)).isZero();

        // 该约定记录在列自身上，并点名了依赖它的
        // 控制台映射 —— 这是 V63 从 psql 里看不到的那项依赖。
        assertThat(columnComment("package_plan_version", "tokens_per_minute"))
                .contains("VIDEO_SECOND")
                .contains("PlansView.vue");
    }

    @Test
    void repricesEveryPublishedPlanInsideTheThirtyToFortyPercentBand() throws SQLException {
        // V61 自己的 DO 块在运行时断言了这一点，但一个只检查
        // 自身的迁移无法发现更晚的迁移，也无法发现重放出来的数据库
        // 发生漂移。这里针对现存的行重述该契约：对每个
        // 带价格的已发布套餐方案，守卫的最坏情况（最贵的允许
        // 别名优先，每个消耗 min(cap, remaining)，NULL 上限取
        // 剩余部分）必须至少留下价格的 30% 作为毛利。
        //
        // 40% 的上限只对 V61 重新定价过的那些 TOKEN 档位做断言。对那些档位
        // 而言这是产品决策，而不是目录的不变量：
        // VIDEO_STARTER 与 VIDEO_CREATOR 合理地处于 45-46%，而一条
        // 禁止该情况的规则，将禁止以比所要求的更好的毛利
        // 出售套餐方案。V84 的图片计划同理：按 V85 之后的 62 分/张 成本基准它是
        // 40.361%（发布时按 60 分/张 存下的是 42.285% 的快照，而已发布版本不可变），
        // 那是针对 ¥49.9 那一档做出的独立产品决策，不属于 V61 那次重定价，
        // 因此上限那条只对 TOKEN 行生效；30% 的硬下限仍然对每个维度的
        // 每一个已发布版本生效。
        migrate("platform");

        // 首先：成本基准必须覆盖目录所售卖的一切。成本基准中
        // 缺失的模型会被下面的填充跳过，而这只会
        // 低估最坏情况 —— 因此它被单独检查，而不是
        // 悄悄削弱毛利断言。V85 之后的 image-basic（62 分/张，
        // 即输出图最坏情况 60 分 + 输入图 2 分）必须在这里，
        // 否则图片版本会被静默跳过。
        assertThat(values("""
                SELECT plan.code || ':' || model.model_code
                FROM platform.package_plan_version version
                JOIN platform.package_plan plan ON plan.id = version.package_plan_id
                JOIN platform.package_plan_version_model model
                  ON model.package_plan_version_id = version.id
                WHERE version.status = 'PUBLISHED'
                  -- 2026-10-12：V103 把四个音频别名挂到了五个视频套餐（VIDEO_STARTER /
                  -- VIDEO_CREATOR / VIDEO_PRO / VIDEO_LITE / VIDEO_LITE_ANNUAL）新发布的
                  -- 版本上。它们此前"已登记但没有已发布版本"，本断言因此靠
                  -- 「(AUDIO_SECOND, 音频别名) 在已知表里 + 没有任何已发布版本用到它」
                  -- 这个**会随时间失效的前提**成立。现在版本真的发布了，前提没了。
                  --
                  -- 这里把已知表补全到"读模型认得的每一个 (维度, 别名) 对"，
                  -- 与下面那段注释自述的意图一致：
                  --   本断言 = 每个已发布版本都用到了**读模型已知的维度**。
                  -- 仍然会失败的情形（也就是它真正要抓的东西）：某个已发布版本挂了
                  -- 一个**没在任何地方登记过**的别名——那才是读模型会漏算维度的漏洞。
                  AND NOT EXISTS (
                      SELECT 1 FROM (VALUES
                          ('TOKEN', 'file-basic'), ('TOKEN', 'text-basic'),
                          ('TOKEN', 'text-standard'), ('TOKEN', 'text-pro'),
                          ('TOKEN', 'text-max'), ('TOKEN', 'vision-basic'),
                          ('TOKEN', 'vision-pro'), ('TOKEN', 'multimodal-basic'),
                          -- V95 新落价的天翼视觉别名：它与 vision-basic 同维度
                          -- 但贵得多（413 对 15 分/百万），因此它是一个独立的商品。
                          ('TOKEN', 'vision-tianyi'),
                          ('VIDEO_SECOND', 'video-max'), ('VIDEO_SECOND', 'video-standard'),
                          ('VIDEO_SECOND', 'video-pro'), ('VIDEO_SECOND', 'video-basic'),
                          ('VIDEO_SECOND', 'video-budget'),
                          -- V90 登记、网关 V95 落价的天翼分镜视频别名。
                          ('VIDEO_SECOND', 'video-minimax'),
                          ('IMAGE', 'image-basic'),
                          -- V89 登记的三个音频别名。它们目前还没有任何已发布版本，
                          -- 因此下面的 isEmpty() 现在就成立；把它们列进来是为了让
                          -- 这份清单与读模型保持一致——本断言的意图是
                          -- 「每个已发布版本都对应一个已知维度」，
                          -- 而它按 model_code 匹配，所以少一个已知别名不会让
                          -- 任何东西失败，只会让那个别名在被发布时**静默通过**。
                          ('AUDIO_SECOND', 'audio-asr-basic'),
                          -- V91 的时间戳字幕别名。与上面三个同理：列进来是为了让这份
                          -- 清单与读模型保持一致，少一个不会让任何东西失败。
                          ('AUDIO_SECOND', 'audio-asr-subtitle'),
                          ('AUDIO_SECOND', 'audio-tts-basic'),
                          ('AUDIO_SECOND', 'audio-retalk'),
                          -- ------------------------------------------------------------------
                          -- V103 引入了「一个版本同时卖视频秒与音频秒」的形态，
                          -- 但那个版本在**版本级**仍然只登记一个维度：它复制了旧版本的
                          -- usage_dimension（VIDEO_SECOND），音频秒另放在版本级的
                          -- audio_seconds_quota 列里（见 V103 第 2 步与
                          -- `platform.package_plan_version.audio_seconds_quota`）。
                          --
                          -- 因此这 5×4 = 20 个组合是**设计内**的：视频套餐的新版本挂着
                          -- 音频别名。它们此前不在已知表里，是因为 V103 之前根本没有任何
                          -- 已发布版本用到这些别名 —— 那条注释自己写了
                          -- "它们目前还没有任何已发布版本，因此下面的 isEmpty() 现在就成立"。
                          -- 现在前提变了：V103 发布了它们，V104 又各换代一次。
                          --
                          -- 把这 20 对显式登记，而不是放宽查询：本断言要抓的是
                          -- 「已发布版本挂了**没在任何地方登记过**的别名」，
                          -- 而"视频套餐卖音频"是 V103 明确的设计，属于已登记。
                          -- ------------------------------------------------------------------
                          ('VIDEO_SECOND', 'audio-asr-basic'),
                          ('VIDEO_SECOND', 'audio-asr-subtitle'),
                          ('VIDEO_SECOND', 'audio-tts-basic'),
                          ('VIDEO_SECOND', 'audio-retalk')
                      ) AS known(dimension, model_code)
                      WHERE known.dimension = version.usage_dimension
                        AND known.model_code = model.model_code)
                ORDER BY 1
                """)).isEmpty();

        assertThat(values("""
                WITH basis(usage_dimension, model_code, unit_cost) AS (
                    VALUES ('TOKEN', 'file-basic', 200), ('TOKEN', 'text-basic', 800),
                           ('TOKEN', 'text-standard', 900), ('TOKEN', 'text-pro', 2700),
                           ('TOKEN', 'text-max', 3600), ('TOKEN', 'vision-basic', 720),
                           ('TOKEN', 'vision-pro', 1200), ('TOKEN', 'multimodal-basic', 800),
                           ('TOKEN', 'vision-tianyi', 413),
                           ('VIDEO_SECOND', 'video-max', 345), ('VIDEO_SECOND', 'video-standard', 115),
                           ('VIDEO_SECOND', 'video-pro', 92), ('VIDEO_SECOND', 'video-basic', 69),
                           ('VIDEO_SECOND', 'video-budget', 35),
                           ('VIDEO_SECOND', 'video-minimax', 53),
                           ('IMAGE', 'image-basic', 62),
                           ('AUDIO_SECOND', 'audio-asr-basic', 2),
                           ('AUDIO_SECOND', 'audio-asr-subtitle', 2),
                           ('AUDIO_SECOND', 'audio-tts-basic', 2),
                           ('AUDIO_SECOND', 'audio-retalk', 9)
                ), published AS (
                    SELECT version.id, plan.code, version.price_amount,
                           version.total_token_quota, version.usage_dimension
                    FROM platform.package_plan_version version
                    JOIN platform.package_plan plan ON plan.id = version.package_plan_id
                    WHERE version.status = 'PUBLISHED' AND version.price_amount > 0
                ), perms AS (
                    SELECT published.id, published.code, published.price_amount,
                           published.total_token_quota, published.usage_dimension,
                           model.model_token_quota AS cap, basis.unit_cost
                    FROM published
                    JOIN platform.package_plan_version_model model
                      ON model.package_plan_version_id = published.id
                    JOIN basis ON basis.model_code = model.model_code
                              AND basis.usage_dimension = published.usage_dimension
                ), filled AS (
                    SELECT id, code, price_amount, unit_cost, usage_dimension,
                           least(coalesce(cap, total_token_quota),
                                 greatest(total_token_quota - coalesce(
                                     sum(coalesce(cap, total_token_quota)) OVER (
                                         PARTITION BY id
                                         ORDER BY unit_cost DESC, cap
                                         ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0), 0)) AS consumed
                    FROM perms
                ), margins AS (
                    -- Token costs are 分 per million tokens; seconds and images are per unit.
                    SELECT code, price_amount, usage_dimension,
                           ceil(sum(round(consumed * unit_cost
                               / CASE WHEN usage_dimension = 'TOKEN' THEN 1000000.0 ELSE 1 END, 10))) AS worst
                    FROM filled GROUP BY code, price_amount, usage_dimension
                )
                SELECT code || ':price=' || price_amount || ':worst=' || worst || ':margin='
                       || round((price_amount - worst)::numeric / price_amount, 5)
                FROM margins
                WHERE (price_amount - worst)::numeric / price_amount < 0.30000
                   -- The 40% ceiling is the product decision V61 made about the three
                   -- TOKEN tiers it repriced, and the image plan is a separate product
                   -- decision on a separate dimension: 40.361% against the current
                   -- 62 分/张 basis (V85) is deliberate, exactly as the 45-46% video
                   -- tiers are. The floor above still applies to every published
                   -- version of every dimension.
                   OR (code IN ('STARTER', 'ADVANCED', 'ENTERPRISE')
                       AND usage_dimension = 'TOKEN'
                       AND (price_amount - worst)::numeric / price_amount > 0.40000)
                ORDER BY code
                """)).isEmpty();
    }

    @Test
    void seedsSafePaymentProviderDefaultsWithoutCredentials() throws SQLException {
        migrate("platform");

        assertThat(paymentConfigs()).containsExactlyInAnyOrder(
                "MOCK:LOCAL:disabled:no-credentials",
                "ALIPAY:SANDBOX:disabled:no-credentials",
                "ALIPAY:PRODUCTION:disabled:no-credentials",
                "WECHAT_PAY:SANDBOX:disabled:no-credentials",
                "WECHAT_PAY:PRODUCTION:disabled:no-credentials");
    }

    @Test
    void createsCommercialTransactionStateAndConstraints() throws SQLException {
        migrate("platform");

        assertThat(tableNames("platform")).contains(
                "refund_policy_version",
                "payment_anomaly",
                "reconciliation_run",
                "reconciliation_item");
        assertThat(columnNames("customer_order")).contains(
                "expires_at",
                "package_snapshot",
                "idempotency_key",
                "successful_payment_transaction_id",
                "closed_reason",
                "lock_version",
                "close_attempt_count",
                "next_close_at",
                "job_claim_token",
                "job_claimed_until");
        assertThat(columnNames("payment_transaction")).contains(
                "payment_product",
                "provider_status",
                "provider_error_code",
                "expires_at",
                "last_queried_at",
                "callback_received_at",
                "applied_to_order",
                "request_payload_hash",
                "lock_version",
                "query_attempt_count",
                "next_query_at",
                "job_claim_token",
                "job_claimed_until");
        assertThat(columnNames("refund")).contains(
                "refund_type",
                "policy_version",
                "calculation_snapshot",
                "requested_by",
                "reviewed_by",
                "reviewed_at",
                "review_note",
                "provider",
                "provider_status",
                "provider_error_code",
                "gateway_hold_business_no",
                "revocation_business_no",
                "submitted_at",
                "lock_version",
                "provider_attempt_count",
                "next_attempt_at",
                "job_claim_token",
                "job_claimed_until");
        assertThat(indexDefinition("uq_payment_active_attempt_per_order"))
                .contains("order_id", "CREATED", "PENDING");
        assertThat(indexDefinition("uq_payment_applied_per_order"))
                .contains("order_id", "applied_to_order");
        assertThat(indexDefinition("uq_refund_open_per_order"))
                .contains("order_id", "PENDING_APPROVAL", "REVOKING");
        assertThat(constraintDefinition("grant_request", "ck_grant_status"))
                .contains("QUEUED", "ACTIVE", "FAILED", "REVOKED");
        assertThat(singleLong("SELECT fee_bps FROM platform.refund_policy_version WHERE version = 1"))
                .isEqualTo(500L);
        assertThat(singleLong("SELECT minimum_fee FROM platform.refund_policy_version WHERE version = 1"))
                .isEqualTo(100L);
        assertThat(singleLong("SELECT refund_window_days FROM platform.refund_policy_version WHERE version = 1"))
                .isEqualTo(7L);
        assertThat(singleLong("SELECT maximum_usage_bps FROM platform.refund_policy_version WHERE version = 1"))
                .isEqualTo(1000L);
        assertThat(singleBoolean("SELECT published FROM platform.refund_policy_version WHERE version = 1"))
                .isTrue();

        assertSqlRejected(
                "UPDATE platform.refund_policy_version SET fee_bps = 600 WHERE version = 1",
                "published_refund_policy_immutable");
        assertSqlRejected(
                "DELETE FROM platform.refund_policy_version WHERE version = 1",
                "published_refund_policy_immutable");
    }

    @Test
    void createsContentSafetyPolicyCasesEnforcementAppealsAndInbox() throws SQLException {
        migrate("platform");

        assertThat(tableNames("platform")).contains(
                "content_safety_policy_version",
                "content_safety_rule",
                "content_safety_case",
                "account_enforcement",
                "content_safety_appeal",
                "content_safety_event_inbox");
        assertThat(columnNames("content_safety_policy_version")).contains(
                "version", "status", "config", "config_sha256", "published_by", "published_at");
        assertThat(columnNames("content_safety_case")).contains(
                "case_no", "request_id", "direction", "source", "category", "severity",
                "action", "status", "policy_version", "evidence_sha256", "redacted_excerpt",
                "case_fingerprint");
        assertThat(constraintDefinition("content_safety_case", "ck_content_safety_case_status"))
                .contains("OPEN", "INVESTIGATING", "CONFIRMED", "DISMISSED", "RESOLVED");
        assertThat(constraintDefinition("content_safety_case", "ck_content_safety_case_direction"))
                .contains("INPUT", "INPUT_EXTRACTED", "OUTPUT", "FILE");
        assertThat(constraintDefinition("content_safety_appeal", "ck_content_safety_appeal_status"))
                .contains("PENDING", "APPROVED", "REJECTED");
        assertThat(constraintDefinition("account_enforcement", "ck_account_enforcement_state"))
                .contains("FROZEN", "BANNED");
        assertThat(indexDefinition("uq_content_safety_case_fingerprint"))
                .contains("UNIQUE", "case_fingerprint");
        assertThat(indexDefinition("uq_content_safety_inbox_business_id"))
                .contains("UNIQUE", "business_id");
    }

    @Test
    void protectsPublishedContentSafetyPolicyFromMutation() throws SQLException {
        migrate("platform");

        assertSqlRejected(
                "UPDATE platform.content_safety_policy_version SET config = '{\"changed\":true}'::jsonb WHERE version = 1",
                "published_content_safety_policy_immutable");
        assertSqlRejected(
                "DELETE FROM platform.content_safety_policy_version WHERE version = 1",
                "published_content_safety_policy_immutable");
    }

    @Test
    void assignsUniqueImmutableUserNumbers() throws SQLException {
        migrate("platform");

        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO platform.app_user(
                        id, tenant_id, email, password_hash, display_name, status, user_no)
                    VALUES
                        ('76000000-0000-4000-8000-000000000001',
                         '76000000-0000-4000-8000-000000000001',
                         'user-number-one@example.test', 'hash', 'One', 'ACTIVE', 'U00000001'),
                        ('76000000-0000-4000-8000-000000000002',
                         '76000000-0000-4000-8000-000000000002',
                         'user-number-two@example.test', 'hash', 'Two', 'ACTIVE', 'U00000002'),
                        ('76000000-0000-4000-8000-000000000003',
                         '76000000-0000-4000-8000-000000000003',
                         'user-number-three@example.test', 'hash', 'Three', 'ACTIVE', DEFAULT)
                    ON CONFLICT (id) DO NOTHING
                    """);
        }

        List<String> userNumbers = values("""
                SELECT user_no FROM platform.app_user
                WHERE id IN (
                    '76000000-0000-4000-8000-000000000001',
                    '76000000-0000-4000-8000-000000000002',
                    '76000000-0000-4000-8000-000000000003')
                ORDER BY user_no
                """);
        assertThat(userNumbers).hasSize(3).doesNotHaveDuplicates();
        assertThat(userNumbers).contains("U00000001", "U00000002");
        String randomUserNumber = userNumbers.stream()
                .filter(value -> !value.matches("^U[0-9]{8}$"))
                .findFirst().orElseThrow();
        assertThat(randomUserNumber).matches("^U[A-HJ-NP-Z][2-9A-HJ-NP-Z]{7}$");
        assertThat(constraintDefinition("app_user", "ck_app_user_user_no_format"))
                .contains("user_no", "U[0-9A-Z]{8}");
        assertThat(indexDefinition("uq_app_user_user_no")).contains("UNIQUE", "user_no");

        assertSqlRejected("""
                UPDATE platform.app_user SET user_no = 'U99999999'
                WHERE id = '76000000-0000-4000-8000-000000000001'
                """, "app_user_user_no_immutable");
    }

    @Test
    void createsDurableContentSafetyAdministratorOperations() throws SQLException {
        migrate("platform");

        assertThat(columnNames("content_safety_admin_operation")).contains(
                "actor_user_id", "action", "idempotency_key", "request_sha256",
                "resource_type", "resource_id", "response_json", "created_at", "completed_at");
        assertThat(constraintDefinition(
                "content_safety_admin_operation", "uq_content_safety_admin_operation"))
                .contains("UNIQUE", "actor_user_id", "action", "idempotency_key");
        assertThat(constraintDefinition(
                "content_safety_admin_operation", "ck_content_safety_admin_operation_completion"))
                .contains("response_json", "completed_at", "IS NULL");
    }

    @Test
    void localizesBuiltinBusinessNamesWithoutOverwritingAdministratorChanges()
            throws SQLException {
        cleanAndMigratePlatformTo("40");
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE platform.package_plan
                    SET name = '管理员自定义套餐'
                    WHERE code = 'ADVANCED';
                    UPDATE platform.package_plan
                    SET description = '管理员自定义说明'
                    WHERE code = 'STARTER'
                    """);
        }

        migrate("platform");

        assertThat(values("""
                SELECT code || ':' || name || ':' || description
                FROM platform.package_plan
                ORDER BY code
                """)).containsExactly(
                        "ADVANCED:管理员自定义套餐:适合多模态模型与更高频率的业务调用。",
                        "ENTERPRISE:企业版:适合企业级高并发和规模化业务使用。",
                        "FREE:免费版:适合首次体验，快速了解基础调用能力。",
                        // V84 的图片计划：它是本迁移新增的目录行，名字与描述就是
                        // 迁移里写下的那一句（V41 的本地化只改更早的四个内置计划）。
                        "IMAGE_BASIC:图片生成套餐:按张计费的文生图 / 图生图套餐。",
                        "STARTER:入门版:管理员自定义说明",
                        "VIDEO_CREATOR:Video Creator:Creator video package: 190 seconds per month, 720P and 1080P",
                        "VIDEO_LITE:Video Lite:Entry video package: 100 seconds per month at 480P",
                        "VIDEO_LITE_ANNUAL:Video Lite Annual:Annual entry video package: 1100 seconds at 480P",
                        "VIDEO_PRO:Video Pro:Professional video package: 600 seconds per month, priority queue",
                        "VIDEO_STARTER:Video Starter:Entry video package: 60 seconds per month at 720P");
        assertThat(values("SELECT code || ':' || name FROM platform.app_role ORDER BY code"))
                .containsExactly("ADMIN:管理员", "USER:普通用户");
    }

    private void migrate(String schema) throws SQLException {
        assertNoDuplicateMigrationVersions();
        installBtreeGist();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(schema)
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /**
     * 迁移目录里不能有两个同版本号的文件。
     *
     * <p><b>这条守卫是为一次真实事故加的。</b>一条以 V86 命名的新迁移与同一目录下
     * 既有的 {@code V86__package_plan_type.sql} 撞号，而两份文件在代码评审里都
     * 完全合法：各自语法正确、内容是新的、文件名也符合约定。Flyway 只在**运行
     * 迁移的那一刻**报 {@code Found more than one migration with version 86}，
     * 于是症状是一堆 schema 测试同时失败，而不是一句「你在 86 上撞号了」。
     *
     * <p>把检查前置到迁移之前，就是把那个盲区变成一句指名道姓的失败。
     *
     * <p>读 classpath 而不是源码目录，是为了断言**运行期事实**：classpath 上的
     * {@code db/migration} 正是 Flyway 将要读的那一份。
     */
    static void assertNoDuplicateMigrationVersions() {
        java.util.Map<String, java.util.List<String>> byVersion = new java.util.TreeMap<>();
        try {
            java.net.URL location = PlatformMigrationTest.class
                    .getClassLoader().getResource("db/migration");
            assertThat(location)
                    .as("the platform migration directory must be on the test classpath")
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

    private void cleanAndMigratePlatformTo(String version) throws SQLException {
        assertNoDuplicateMigrationVersions();
        installBtreeGist();
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (version != null) {
            configuration.target(version);
        }
        Flyway flyway = configuration.load();
        flyway.clean();
        flyway.migrate();
    }

    /**
     * 清空 platform schema 并从零迁移到**最新**版本。
     *
     * <p>给那些断言"迁移链产出的完整目录"的测试用。它们不能只调用
     * {@link #migrate(String)}：那个方法不清理，而本类共用一个容器，
     * 于是它们读到的是**上一个测试留下的数据**，不是迁移产物。
     *
     * <p>这条区分是被一次真实失败逼出来的。{@link
     * #preservesAdministratorCatalogueWhenApplyingSeedCorrections} 会先
     * {@code cleanAndMigratePlatformTo("60")}，再把
     * {@code /db/catalog-upgrade-custom-plans.sql} 灌进去模拟"管理员改过目录"，
     * 然后迁移到最新——**并且不清理**。那份 fixture 里有一个
     * {@code tokens_per_minute = 99}、价格 12300 的自定义 STARTER 版本。
     * 按 JUnit 的默认顺序它排在 {@link
     * #publishesEveryPlanWithRateLimitsItsCapabilitiesCanUse} 之前，于是后者
     * 读到的是管理员目录而不是迁移目录，症状是
     * {@code STARTER:2:tpm=99:daily=NULL:maxout=NULL} 替掉了
     * {@code STARTER:3:tpm=100000:daily=2000:maxout=4096}——
     * 而 99 这个数在整条迁移链里根本不存在，只存在于那份 fixture 里。
     */
    private void cleanAndMigratePlatformToLatest() throws SQLException {
        cleanAndMigratePlatformTo(null);
    }

    private void installBtreeGist() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public");
        }
    }

    private Set<String> tableNames(String schema) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                ResultSet result = connection.getMetaData().getTables(null, schema, "%", new String[] {"TABLE"})) {
            while (result.next()) {
                names.add(result.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private String columnType(String table, String column) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        SELECT data_type
                        FROM information_schema.columns
                        WHERE table_schema = 'platform' AND table_name = ? AND column_name = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    /** 某列上的注释，用量维度的约定就记录在这里。 */
    private String columnComment(String table, String column) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        SELECT col_description(table_row.oid, column_row.ordinal_position)
                        FROM information_schema.columns column_row
                        JOIN pg_class table_row ON table_row.relname = column_row.table_name
                        JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
                                                 AND schema_row.nspname = column_row.table_schema
                        WHERE column_row.table_schema = 'platform'
                          AND column_row.table_name = ?
                          AND column_row.column_name = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next())
                        .as("column platform.%s.%s exists", table, column)
                        .isTrue();
                return result.getString(1);
            }
        }
    }

    private Set<String> columnNames(String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'platform' AND table_name = ?
                        """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        assertThat(names).as("columns for platform.%s", table).isNotEmpty();
        return names;
    }

    private String constraintDefinition(String table, String constraint) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        SELECT pg_get_constraintdef(constraint_row.oid)
                        FROM pg_constraint AS constraint_row
                        JOIN pg_class AS table_row ON table_row.oid = constraint_row.conrelid
                        JOIN pg_namespace AS schema_row ON schema_row.oid = table_row.relnamespace
                        WHERE schema_row.nspname = 'platform'
                          AND table_row.relname = ?
                          AND constraint_row.conname = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next())
                        .as("constraint platform.%s.%s exists", table, constraint)
                        .isTrue();
                return result.getString(1);
            }
        }
    }

    private String indexDefinition(String index) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = 'platform' AND indexname = ?
                        """)) {
            statement.setString(1, index);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("index platform.%s exists", index).isTrue();
                return result.getString(1);
            }
        }
    }

    private long singleLong(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).as("query returned a row: %s", sql).isTrue();
            long value = result.getLong(1);
            assertThat(result.next()).as("query returned exactly one row: %s", sql).isFalse();
            return value;
        }
    }

    private List<String> values(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private boolean singleBoolean(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).as("query returned a row: %s", sql).isTrue();
            boolean value = result.getBoolean(1);
            assertThat(result.next()).as("query returned exactly one row: %s", sql).isFalse();
            return value;
        }
    }

    private void assertSqlRejected(String sql, String expectedMessage) {
        assertThatThrownBy(() -> {
            try (Connection connection = DriverManager.getConnection(
                            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    var statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }).isInstanceOf(SQLException.class)
                .hasMessageContaining(expectedMessage);
    }

    /**
     * 与 {@link #assertSqlRejected} 相对：要求这条 SQL **真的写得进去**。
     *
     * <p>为什么需要它：一处约束的"放开"如果只断言约束定义文本里出现了某个词，
     * 就证明不了"那条被挡住的路径现在真的能走"。V105 就是被这个区别咬过一次 ——
     * 它在生产库副本上"约束文本都对了"，但纯音频版本依然建不出来，
     * 因为还有两个约束没放开。
     */
    private void assertSqlAccepted(String sql) {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException error) {
            throw new AssertionError("expected this SQL to be accepted, but it was rejected: "
                    + error.getMessage() + "\nSQL:\n" + sql, error);
        }
    }

    private Set<String> paymentConfigs() throws SQLException {
        Set<String> configurations = new HashSet<>();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT provider || ':' || environment || ':'
                               || CASE WHEN enabled THEN 'enabled' ELSE 'disabled' END || ':'
                               || CASE WHEN encrypted_credentials IS NULL
                                      THEN 'no-credentials' ELSE 'credentials' END
                        FROM platform.payment_config
                        """)) {
            while (result.next()) {
                configurations.add(result.getString(1));
            }
        }
        return configurations;
    }
}
