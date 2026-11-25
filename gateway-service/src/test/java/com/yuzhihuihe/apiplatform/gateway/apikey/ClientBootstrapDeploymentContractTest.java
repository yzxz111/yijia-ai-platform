package com.yuzhihuihe.apiplatform.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ClientBootstrapDeploymentContractTest {

    @Test
    void publishesTheRealApiBaseUrlToGatewayDeployments() throws Exception {
        String compose = Files.readString(Path.of("..", "deploy", "compose.yaml"));

        assertThat(compose).contains(
                "GATEWAY_CLIENT_API_BASE_URL: ${GATEWAY_CLIENT_API_BASE_URL:-http://127.0.0.1:18081/v1}");
    }

    /**
     * 桌面端会拒绝 origin 与其自身 OAuth base 不同的 bootstrap apiBaseUrl
     * （Yuzai/src/main/libs/authDesktopFlow.ts:496-499）。未打包的构建总是使用
     * http://127.0.0.1:18081 ——本地部署配置必须与之共享完全相同的 origin；
     * 诸如 192.168.x.x 这样的局域网 IP 会通不过检查，账号方案也就永远不渲染。
     */
    @Test
    void localBootstrapBaseUrlSharesTheDesktopDevOrigin() throws Exception {
        String environment = Files.readString(
                Path.of("..", "deploy", "env", ".env.example"));

        assertThat(environment).contains(
                "GATEWAY_CLIENT_API_BASE_URL=http://127.0.0.1:18081/v1");
    }

    @Test
    void httpsDeploymentUsesTheProductionOrigin() throws Exception {
        String compose = Files.readString(Path.of("..", "deploy", "compose.https.yaml"));

        assertThat(compose).contains(
                "GATEWAY_CLIENT_API_BASE_URL: https://api.yuzhh.com/v1");
    }

    /**
     * application.properties 为同一属性携带了一个 Spring 层面的兜底值。它在
     * compose 下不可达（compose 总是注入环境变量），但裸跑
     * `spring-boot:run` 时会取到它，因此它必须满足同样的 origin 规则。
     */
    @Test
    void springFallbackBaseUrlSharesTheDesktopDevOrigin() throws Exception {
        String properties = Files.readString(Path.of(
                "src", "main", "resources", "application.properties"));

        assertThat(properties).contains(
                "gateway.client.api-base-url=${GATEWAY_CLIENT_API_BASE_URL:http://127.0.0.1:18081/v1}");
    }
}
