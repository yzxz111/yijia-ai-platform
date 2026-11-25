package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 「测试模型」挑不挑这条别名，取决于 {@code channelModels} 里 {@code text_capable} 怎么算。
 *
 * <p><b>这个谓词出过一次真实的线上事故（2026-10-09）。</b>生图别名 {@code image-basic}
 * 带着 {@code ["IMAGE","TEXT","IMAGE_GEN"]}，旧谓词只看 {@code capabilities @> '["TEXT"]'}，
 * 于是它被当成对话模型送进 {@code /chat/completions} 的探测；上游对「用对话端点调生图模型」
 * 回 401/403，网关记成「密钥被上游拒绝」，把整条生图渠道写成 {@code UNHEALTHY}——而生图路由
 * 要求 {@code HEALTHY}，一次误判就把整个 {@code image-basic} 能力堵成了 503。
 *
 * <p>因此这条谓词必须区分「能对话」与「带 {@code TEXT} 输入」：下面既钉住生图别名不再入选，
 * 也钉住纯对话别名与非生成的多模态别名**仍然**入选——一个把所有人都排除掉的谓词会让
 * 「测试模型」对任何渠道都失效，那不叫修好。
 */
@Testcontainers
class ChannelModelProbeabilityRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static GatewayAdminRepository repository;
    static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        String r2dbcUrl = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
                + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName();
        repository = new GatewayAdminRepository(
                DatabaseClient.create(ConnectionFactories.get(r2dbcUrl)));
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test
    void anImageAliasThatAlsoCarriesTextIsNotChatProbeable() {
        // image-basic 的 TEXT 说的是「我能吃文本提示词」，不是「我能对话」。
        assertThat(choiceFor("image-basic").textCapable()).isFalse();
        assertThat(choiceFor("image-basic").probeable()).isFalse();
    }

    @Test
    void aPureChatAliasStaysChatProbeable() {
        assertThat(choiceFor("text-basic").textCapable()).isTrue();
        assertThat(choiceFor("text-basic").probeable()).isTrue();
    }

    @Test
    void aNonGeneratorMultimodalAliasStaysChatProbeable() {
        // file-basic 带非文本输入（IMAGE/FILE）却不是生成类能力：把「非纯 TEXT」
        // 一律排除会连它一起误伤，而它本来是可以对话探测的。
        assertThat(choiceFor("file-basic").textCapable()).isTrue();
    }

    /**
     * 该别名在它自己那条渠道上的映射选择。
     *
     * <p>按模型名反查渠道、再问仓库，而不是按渠道名字硬取：渠道归属是迁移链的产物，
     * 硬编码一个渠道 code 会让测试在迁移调整后因为「找不到渠道」而红，而不是因为
     * 谓词本身错了。
     */
    private static GatewayAdminRepository.ChannelModelChoice choiceFor(String modelCode) {
        UUID channelId = jdbcTemplate.queryForObject(
                """
                SELECT mapping.channel_id
                FROM gateway.channel_model mapping
                JOIN gateway.model model ON model.id = mapping.model_id
                WHERE model.code = ?
                ORDER BY mapping.channel_id
                LIMIT 1
                """,
                UUID.class, modelCode);
        assertThat(channelId).as("%s 必须至少挂在一条渠道上", modelCode).isNotNull();

        List<GatewayAdminRepository.ChannelModelChoice> choices =
                repository.channelModels(channelId).collectList().block();
        assertThat(choices).as("渠道 %s 的映射", channelId).isNotNull().isNotEmpty();
        return choices.stream()
                .filter(choice -> modelCode.equals(choice.modelCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "渠道 " + channelId + " 上必须有一条 " + modelCode + " 映射"));
    }
}
