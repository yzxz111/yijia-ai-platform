package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * 对话请求的时间预算必须在**三层配置之间保持一致**，而这三层分散在不同文件里。
 *
 * <p>2026-10-14 的「长任务执行到一半流式输出断了」根因就在这里：总预算是一个 30 秒的
 * {@code @Value} 兜底默认值，{@code application.properties} 里根本没有这一项，运维既看不到
 * 也无从调整。长任务的单轮模型调用经常超过 30 秒，于是网关自己把上游掐断。
 *
 * <p><b>本测试直接读主 {@code application.properties} 的源码文件，不走 classpath。</b>
 * 因为 {@code src/test/resources/application.properties} 会**遮盖**主文件（测试 jar 里同名
 * 资源只有一个），走 classpath 读到的是那份 7 行的测试覆盖，主文件的默认值根本看不见——
 * 我第一版就是这么写错的，四个断言全红且原因具有误导性。
 *
 * <p>这些断言刻意写得啰嗦：它们把**跨文件的耦合**变成可执行的检查。任何一层被单独调走
 * （为了省资源把 nginx 超时调小、或把总预算调到比渠道超时还短），都会在这里变红，
 * 而不是等到线上长任务再次断流。
 */
class ChatStreamTimeBudgetTest {

    /** nginx `^~ /v1/` 段的 proxy_read_timeout（见 deploy/nginx/nginx.https.conf）。 */
    private static final Duration NGINX_PROXY_READ_TIMEOUT = Duration.ofSeconds(600);

    /** 对话渠道的 channel.timeout_ms（V91 种下 tianyi-chat-primary 时为 60000）。 */
    private static final Duration CHAT_CHANNEL_TIMEOUT = Duration.ofSeconds(60);

    private static Properties mainApplicationProperties() throws Exception {
        Path source = Path.of("src", "main", "resources", "application.properties");
        assertThat(Files.exists(source))
                .as("必须从模块目录运行测试才能定位 %s", source.toAbsolutePath())
                .isTrue();
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(source)) {
            properties.load(stream);
        }
        return properties;
    }

    private static Duration resolve(Properties properties, String key) {
        String raw = properties.getProperty(key);
        assertThat(raw).as("%s 必须在主配置里显式声明（否则运维看不到它）", key).isNotNull();
        // 形如 ${ENV_VAR:300s}：取冒号后的默认值，即无环境覆盖时实际使用的值。
        int marker = raw.lastIndexOf(':');
        String value = marker >= 0 ? raw.substring(marker + 1, raw.length() - 1) : raw;
        return parseDuration(value);
    }

    private static Duration parseDuration(String value) {
        String trimmed = value.strip();
        char unit = trimmed.charAt(trimmed.length() - 1);
        long amount = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            default -> throw new IllegalArgumentException("unsupported unit in " + value);
        };
    }

    @Test
    void theChatTimeBudgetIsConfiguredExplicitlyRatherThanLeftToACodeDefault() throws Exception {
        Properties properties = mainApplicationProperties();

        // 这一项的存在本身就是修复的一半：此前它只活在 @Value 的兜底值里。
        assertThat(properties.getProperty("gateway.chat.total-attempt-timeout")).isNotNull();
        assertThat(properties.getProperty("gateway.chat.reservation-ttl")).isNotNull();
    }

    @Test
    void theTotalBudgetIsLongEnoughForALongTask() throws Exception {
        Duration budget = resolve(mainApplicationProperties(), "gateway.chat.total-attempt-timeout");

        // 30 秒是这次故障的元凶：一次「写一个页面 / 生成一份文档」的单轮调用经常超过它。
        assertThat(budget)
                .as("总预算必须明显长于 30s，否则长任务仍会在写到一半时被网关掐断")
                .isGreaterThan(Duration.ofSeconds(60));
        // 也不该长到让一次卡死的请求把额度预留占满整个窗口。
        assertThat(budget).isLessThanOrEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void theThreeLayersAgreeOnTheirOrdering() throws Exception {
        Properties properties = mainApplicationProperties();
        Duration budget = resolve(properties, "gateway.chat.total-attempt-timeout");
        Duration reservationTtl = resolve(properties, "gateway.chat.reservation-ttl");

        // nginx 必须比网关更晚断：否则客户端先被 nginx 断开，拿不到网关的结构化错误帧，
        // 又回到「只有连接重置、没有原因码」的老问题。
        assertThat(budget)
                .as("网关总预算必须 ≤ nginx proxy_read_timeout(%s)", NGINX_PROXY_READ_TIMEOUT)
                .isLessThanOrEqualTo(NGINX_PROXY_READ_TIMEOUT);

        // 网关必须比渠道更晚断：否则渠道自己的 timeout_ms 形同虚设——渠道还没到自己的
        // 超时，就被这里按「尝试超时」掐掉了。
        assertThat(budget)
                .as("网关总预算必须 ≥ 渠道超时(%s)，否则渠道配置变成摆设", CHAT_CHANNEL_TIMEOUT)
                .isGreaterThanOrEqualTo(CHAT_CHANNEL_TIMEOUT);

        // 预留必须活得比请求久：否则一次正常的长任务会在跑动中预留过期，恢复扫描随后
        // 就要按「无终态证据」去处理一个其实还在进行的请求。
        assertThat(reservationTtl)
                .as("预留 TTL 必须 ≥ 总预算，否则预留会在长任务进行中过期")
                .isGreaterThanOrEqualTo(budget);
    }

    @Test
    void theCodeDefaultMatchesTheConfiguredDefault() throws Exception {
        // @Value 的兜底值与主配置的默认值必须一致。两者不一致时，「配置没生效」与
        // 「配置被改过」会表现得一模一样：只在一个不加载该文件的环境里才用的是另一个值。
        Duration configured = resolve(
                mainApplicationProperties(), "gateway.chat.total-attempt-timeout");

        java.lang.reflect.Field field = ChatCompletionService.class
                .getDeclaredField("DEFAULT_TOTAL_ATTEMPT_TIMEOUT");
        field.setAccessible(true);
        Duration codeDefault = (Duration) field.get(null);

        assertThat(codeDefault)
                .as("代码兜底值(%s)必须与主配置默认值(%s)一致", codeDefault, configured)
                .isEqualTo(configured);
    }
}
