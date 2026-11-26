# 一加 AI 中台（API Model Platform）

[![CI](https://github.com/yzxz111/yijia-ai-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/yzxz111/yijia-ai-platform/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5_WebFlux-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![Vue 3](https://img.shields.io/badge/Vue_3-TypeScript-4FC08D?style=flat-square&logo=vuedotjs&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

面向多租户与下游智能体/客户端的大模型 API 管理与转发网关平台。提供 **OpenAI 兼容接口（`/v1`）**、多渠道路由与熔断故障转移、四档并发限流、多维度额度预留结算，以及配套的运营管理与用户控制台。

> 本仓库已提供「一加 AI 中台」核心服务端、控制台与 Docker Compose 全栈编排，可作为 [LogOps-Agent](https://github.com/yzxz111/logops-agent) 与 [一加灵犀桌面工作台](https://github.com/yzxz111/yijia-lingxi) 的统一模型接入底座。

---

## 一、系统架构

Nginx 作为统一外部入口，其余核心服务与中间件运行在内部网络中：

```text
                    ┌───────────────────────────────┐
  浏览器 / 客户端 ──▶│ nginx :18081 (统一网关入口)     │
                    └───────────────┬───────────────┘
                                    │
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
        `/` 控制台          `/console/platform/*`    `/console/gateway/*`、`/v1/*`
              │                     │                     │
              ▼                     ▼                     ▼
        web-console         platform-service        gateway-service
     (Vue 3 + TS + Vite)   (MVC + JDBC 交易域)    (WebFlux + R2DBC 转发域)
                                    │                     │
                                    └────────┬────────────┘
                                             ▼
                        PostgreSQL (双 schema) / Redis 7 / MinIO / ClamAV
                                             gateway ──▶ WireMock (本地模拟上游)
                                             gateway ──▶ document-converter (内网隔离)
```

| 组件 | 核心职责 | 技术栈 |
|---|---|---|
| `gateway-service` | API Key 鉴权、模型路由与熔断、并发限流、额度预留结算、OpenAI 兼容 `/v1` 与 SSE 流式网关 | Java 21 · Spring Boot WebFlux · R2DBC · Resilience4j |
| `platform-service` | 账号与设备认证、套餐发放与 Outbox 投递、订单/钱包/订阅账本、审计与对账 | Java 21 · Spring Boot MVC · JDBC · Flyway |
| `web-console` | 管理端运营配置与用户端工作台（API Key 管理、额度明细、调用记录） | Vue 3 · TypeScript · Element Plus · Vite |
| `postgres` | 单库双 Schema 隔离：`platform`（交易域）与 `gateway`（转发域），无跨库外键 | PostgreSQL 17 · Flyway |
| `redis` | API Key 缓存、Redis Lua 多维度限流与并发租约控制 | Redis 7 |
| `minio` / `clamav` | S3 兼容文件存储与上传恶意文件扫描（Fail-Closed） | MinIO · ClamAV |
| `document-converter` | 无宿主端口、无外网的内网隔离 DOC/XLS 预处理服务 | Node.js · LibreOffice |
| `wiremock` | 本地模拟模型上游（覆盖正常响应、SSE 分片、超时、429 与故障转移场景） | WireMock |

---

## 二、核心工程亮点

### 1. OpenAI 兼容协议与下游标准化接入
- **统一接口契约**：在 `docs/downstream-openapi.json` 与 `docs/downstream-integration.md` 中定义 OpenAPI 3.1 接口契约，串联登录鉴权、API Key 托管引导、`/v1/models` 授权模型目录、`/v1/quota` 实时额度查询与 `/v1/chat/completions`（`/v1/responses`）对话。
- **全链路请求追踪**：全链路注入并透传 `X-Request-ID`，结合结构化日志定位跨网关、路由与上游调用的异常耗时。

### 2. SSE 流式协议治理与防重复计费
- **跨块流式解析**：针对网络分片场景正确处理 UTF-8 多字节跨块截断、CRLF、心跳保活帧、`stream_options.include_usage` 统计帧、输出截断标记（`finish_reason=length`）与流中结构化错误帧。
- **准入级退避边界**：严格区分“准入前 429 限流”与“流已开始后的上游失败”，仅对明确准入阶段 429 返回 `Retry-After` 与结构化退避指引，避免流式重发引发模型重复生成与二次计费。

### 3. Redis Lua 并发限流与三段式额度结算
- **并发租约 TTL 修复**：在并发槽位限流 Lua 脚本中修复“短租约请求覆盖缩短共享并发集合 TTL”的边界缺陷，保障高并发下的窗口隔离与自动释放。
- **四档限流与三段式结算**：支持每分钟请求数、并发数、日请求上限与套餐级限流；调用执行“预留（Reserve）→ 结算（Settle）→ 补偿退还（Refund）”三段式额度控制，覆盖多模态、生图扇出与异常中断回滚。

### 4. 安全边界与工程可靠性
- **密钥与凭据安全**：API Key 采用 HMAC 加椒盐摘要存储，渠道上游密钥加密落库，匿名文件签名接口默认 Fail-Closed 并按 IP 限流。
- **ArchUnit 架构约束与单元测试**：网关核心域（流式协议、准入限流、配额结算、路由熔断、持久层边界）内置单元测试与 ArchUnit 架构守卫测试，通过 GitHub Actions CI 自动验证。

---

## 三、快速开始

### 方式一：Docker Compose 全栈运行（推荐）

宿主机需具备 **Docker（Compose v2）** 与可用端口 `18081`。

```powershell
# 1. 复制环境变量模板
Copy-Item .\deploy\env\.env.example .\deploy\env\.env
```

> 请在 `.\deploy\env\.env` 中为 `DOCUMENT_CONVERTER_AUTH_TOKEN` 设置一个随机字符串（必填校验）。如在 Windows PowerShell 写入 `.env`，请确保使用 **UTF-8 无 BOM** 编码。

```powershell
# 2. 校验配置并启动全栈
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml config --quiet
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml up --build -d

# 3. 检查服务与初始化任务状态
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml ps --all
```

启动完成后访问：
- **统一控制台入口**：`http://127.0.0.1:18081/login`
- **健康检查**：`http://127.0.0.1:18081/health/platform`、`http://127.0.0.1:18081/health/gateway`
- **OpenAI 兼容 Base URL**：`http://127.0.0.1:18081/v1`

### 方式二：宿主机本地调试与测试

环境要求：**JDK 21**、**Maven 3.9+**、**Node.js 22.12+**、**pnpm 11.9.0**。

```powershell
# 编译全工程（platform-service + gateway-service）
mvn -B test-compile

# 运行网关核心协议、配额与架构单元测试（与 GitHub Actions CI 保持一致）
mvn -B -pl gateway-service -Dtest="GatewayServiceApplicationTests,GatewaySchedulingConfigurationTest,com.yuzhihuihe.apiplatform.gateway.architecture.*,com.yuzhihuihe.apiplatform.gateway.billing.*,com.yuzhihuihe.apiplatform.gateway.limit.PackageRateLimitsTest,com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitServiceTest,com.yuzhihuihe.apiplatform.gateway.limit.RedisRequestLimitStoreLocalTest,com.yuzhihuihe.apiplatform.gateway.openai.*,!com.yuzhihuihe.apiplatform.gateway.openai.DatabaseGatewayCatalogPublicAdmissionTest,!com.yuzhihuihe.apiplatform.gateway.openai.ChatDailyRequestLimitAdmissionTest,!com.yuzhihuihe.apiplatform.gateway.openai.ChatPackageTokenRateLimitAdmissionTest,com.yuzhihuihe.apiplatform.gateway.routing.*,!com.yuzhihuihe.apiplatform.gateway.routing.ChannelModelProbeabilityRepositoryTest,!com.yuzhihuihe.apiplatform.gateway.routing.ChannelModelTestServiceTest,!com.yuzhihuihe.apiplatform.gateway.routing.GatewayAdminServiceTest,!com.yuzhihuihe.apiplatform.gateway.routing.ModelCatalogServiceTest,com.yuzhihuihe.apiplatform.gateway.quota.*,!com.yuzhihuihe.apiplatform.gateway.quota.AudioSecondBucketSettlementTest,!com.yuzhihuihe.apiplatform.gateway.quota.PackageActivationTest,!com.yuzhihuihe.apiplatform.gateway.quota.PackageRefundServiceTest,!com.yuzhihuihe.apiplatform.gateway.quota.QuotaConcurrencyTest,!com.yuzhihuihe.apiplatform.gateway.quota.SettlementReservationBoundTest" test

# 前端类型检查与构建
cd web-console
pnpm install --frozen-lockfile
pnpm build
```

---

## 四、仓库结构

```text
yijia-ai-platform/
├── gateway-service/        # WebFlux + R2DBC 转发域网关（路由/限流/配额/OpenAI 兼容接口）
├── platform-service/       # MVC + JDBC 交易域服务（账号/套餐/Outbox/订单/钱包/审计）
├── web-console/            # Vue 3 + TypeScript + Element Plus 管理端与用户控制台
├── document-converter/     # 内网隔离文档预处理服务（LibreOffice）
├── deploy/                 # Docker Compose 全栈编排、Nginx 代理配置与环境变量模板
├── docs/                   # 下游接入指南与 OpenAPI 3.1 接口契约
├── examples/               # 下游应用接入示例
├── fixtures/               # 专家包与目录导入测试夹具
├── scripts/                # 本地全栈辅助与校验脚本
└── pom.xml                 # Maven 聚合工程配置（Java 21 / Spring Boot 3.5）
```

---

## 五、开源协议

本项目基于 [MIT License](LICENSE) 开源。
