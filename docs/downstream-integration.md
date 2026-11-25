# 下游 App 接入与本地运行

本指南覆盖平台自助 API Key 与客户端托管 Key 两种接入方式。`web-console` 是平台控制台；`examples/downstream-app` 是独立的接入示例，包含可直接运行的聊天网页和无第三方依赖的 Node.js 客户端。

## 1. 在这台机器上跑出下游 App

需要 Node.js 22.12 或以上。本项目现有运行环境已提供 Node.js。先在 `E:\deep\兴兴项目` 双击 `启动本地.cmd`，等待平台和网关健康，再在 PowerShell 执行：

```powershell
Set-Location 'E:\deep\兴兴项目\jieya\api-zt-system-main'
node .\examples\downstream-app\server.mjs --local-demo
```

打开 **http://127.0.0.1:18084**，点击“使用本地演示账号”。页面会调用真实平台登录、托管 Key 引导、模型目录和实时额度接口；选择模型后即可发送消息，观察流式回复。演示开关只支持连接本机回环地址，密码从本地 `deploy/env/.env` 的 `LOCAL_DEMO_PASSWORD` 读取，不返回网页、不打印日志。示例要求本地启动阶段已经初始化 `demo@local.test` 并激活 TOKEN 套餐，不会自行购买、发放或修改额度。

本地模型上游是 WireMock：登录、鉴权、套餐、限流、数据库扣费和日志链路真实运行，模型回复是模拟结果。每次生成仍会增加本地演示套餐的用量。此结果不代表真实云模型、生产部署或付费生成验收。

关闭示例：在运行它的终端按 `Ctrl+C`。平台原有服务继续运行。

其他环境或使用自己的已有账号：

```powershell
$env:YIJIA_PLATFORM_URL = 'http://127.0.0.1:18081'
$env:PORT = '18084'
node .\examples\downstream-app\server.mjs
```

此时网页提供账号密码登录；需要有效的 TOKEN 套餐。远程地址只修改 `YIJIA_PLATFORM_URL`，并使用 HTTPS。示例只监听 `127.0.0.1`，请按输出的 `127.0.0.1` 地址打开，不能改用 `localhost`。它是联调样例，不是生产 App 的会话系统。

## 2. 认证和接口顺序

平台登录令牌与模型 API Key 用于不同接口，不能混用。普通用户登录后可从左侧“API 密钥”（`/app/api-keys`）创建平台 Key，立即复制完整密钥，并把页面显示的 API Base URL 与该 Key 配置到下游应用。关闭对话框后只能看到前缀；遗失时按原有短信验证流程重置，或撤销后重新创建。该页面管理自助 Key，客户端托管 Key 继续独立管理。

自助接口为 `/console/gateway/api-keys`：列表 `GET`、创建 `POST`、停用/启用 `PATCH /{id}/status`、撤销 `DELETE /{id}`、短信重置 `POST /{id}/reset`。可选模型从 `GET /eligible-models` 获取；创建 JSON 为 `{name,allowedModels,usageDimension?}`，默认维度 `TOKEN`，其他维度需在可选模型查询与创建时传入一致的 `usageDimension`。模型范围、密钥数量和套餐/钱包计费继续由现有服务校验；创建 Key 不代表已经获得可用额度。

已有平台 Key 的下游应用可直接从下表第 3 步开始。以下第 1–2 步是示例 App 自动获取托管 Key 的另一条路径：

| 顺序 | 请求 | 凭证和约束 |
|---|---|---|
| 1 | `POST /console/platform/auth/login` | JSON `{identifier,password}`；桌面 Web 会话携带 `X-Device-Id` 和正确客户端标识 |
| 2 | `POST /console/gateway/client/bootstrap?usageDimension=TOKEN` | `Authorization: Bearer <accessToken>`；必须复用登录时的 `X-Device-Id`；当前要求 `DESKTOP_WEB` 会话 |
| 3 | `GET /v1/models` | `Authorization: Bearer <apiKey>`；以接口返回的模型目录为准 |
| 4 | `GET /v1/quota` | 同一 API Key；查询绑定套餐的实时额度，不缓存 |
| 5 | `POST /v1/chat/completions` | 同一 API Key；JSON 中设置模型、消息、可选 `stream` |

`X-Device-Id` 为 32 位小写十六进制字符串。正式客户端应按已有终端协议保存并复用设备标识，服务端会校验设备与会话的绑定。示例客户端会为一次登录生成设备 ID 并在 bootstrap 时复用；没有绕过会话验证，也没有取消套餐要求。

bootstrap 响应提供 `apiBaseUrl`、`apiKey`、`packageInstanceId`、`packageCode`、`packageExpiresAt`、`allowedModels`、`usageDimension`。模型调用使用返回的 URL 和密钥；默认本地 URL 为 `http://127.0.0.1:18081/v1`，不要重复拼接 `/v1`。示例要求登录与模型 API 同源，跨源部署需要集成方明确配置可信地址后再扩展。

现有 OpenAI 兼容 SDK 可配置 `base_url=<apiBaseUrl>`、`api_key=<apiKey>`，调用超时建议 **310 秒**，覆盖网关现有 300 秒总预算；请关闭其对生成请求的无条件自动重试。不同 SDK 的具体配置语法以所用版本为准。

示例 Node.js 客户端也可独立使用：

```javascript
import { GatewayClient } from './examples/downstream-app/client.mjs'

const client = new GatewayClient({
  baseUrl: process.env.YIJIA_API_BASE_URL,
  apiKey: process.env.YIJIA_API_KEY,
  maxRetries: 1
})
console.log(await client.models())
console.log(await client.quota())
for await (const event of client.stream({
  model: 'chat-standard',
  messages: [{ role: 'user', content: '你好，请简短回答。' }],
  max_tokens: 256
})) {
  process.stdout.write(event.choices?.[0]?.delta?.content || '')
}
```

密钥可由控制台创建后保存到安全存储，或由 bootstrap 提供，不要写入源码或提交 `.env`。浏览器示例只收到 HttpOnly 会话 Cookie，API Key 保留在示例服务进程内存。进程重启或会话过期后重新登录；生产应用还需实现现有平台的会话刷新、设备恢复与退出协议。

机器可读的接入契约见 `docs/downstream-openapi.json`。这是本次接入链路的最小契约，不是对图片、视频、音频、文件等全部 API 的完整描述；这些生成任务需要各自套餐与现有幂等协议。

## 3. 限流与错误处理

| 响应 | `error.code` | 下游行为 |
|---|---|---|
| 401 | `invalid_api_key` | 停止生成，恢复登录或重新 bootstrap；不要循环重试 |
| 403 | `model_not_allowed` | 刷新模型目录，检查套餐授权 |
| 422 | `package_unavailable` 等额度错误 | 查询 `/v1/quota`，提示套餐状态 |
| 429 | `edge_rate_limit_exceeded` | Nginx 来源 IP 粗限流；按 `Retry-After` 退避 |
| 429 | `rate_limit_exceeded` / `rate_limited` | 网关 RPM 或并发拒绝；限制本地并发并退避 |
| 429 | `tokens_per_minute_exceeded` | 套餐每分钟积分速率达到上限 |
| 429 | `images_per_minute_exceeded` / `seconds_per_minute_exceeded` | 生成类套餐每分钟张数或秒数达到上限 |
| 429 | `daily_request_limit_exceeded` | 当日请求已达上限；等待北京时间下一日，不做短间隔自动重试 |
| 429 | `provider_error` | 上游限流；保留诊断信息，避免不确定重放 |
| 503 / 网络中断 / 超时 | 依赖或上游异常 | 保留请求 ID，先查询调用记录与用量，再决定是否重发 |

错误正文使用 `{error:{message,type,code},request_id}`，响应头使用 `x-request-id`。流开始之后的失败只能通过 SSE 错误帧上报，此时 HTTP 可能已经是 200，不能只看状态码。

本次为模型 API 的主要能力控制器统一补上了 `Retry-After`：分钟级拒绝给保守的 60 秒；日请求上限按北京时间次日零点计算并向上取整；已有自定义等待时间的限流器继续保留其等待值。Nginx 边缘拒绝为 1 秒，且不会覆盖后端自己的 429 错误码和等待值。

Node 客户端只对明确的网关准入 429 自动重试，默认最多一次、累计最多等待 65 秒，包含少量随机退避。超过等待预算就直接把错误和建议等待时间交给调用方，不会把 1 小时等待强行截成 1 分钟。不会自动重试日上限、401、422、上游 429、5xx、网络错误或流中途错误，避免重复生成与重复计费。

本次没有放宽已售套餐的 RPM、并发、每日请求、积分/张/秒速率，也没有调整价格、扣费和额度结算。现有分钟计数仍是固定时间窗，不是平滑滑动窗；它不能保证边界前后的瞬时请求速率完全平滑。共享模型/渠道计数与最小阈值策略继续沿用现有实现，若要改成每个维度独立额度，需要另行修改 Redis 与数据库降级契约。

## 4. 正确处理 SSE

1. 使用流式 UTF-8 解码器，再按 SSE 空行分帧；网络 chunk 可能切在中文字符、JSON 或 CRLF 中间。
2. 忽略 `:` 开头的心跳注释，识别 `data:`，最后的 usage 帧可能没有 choices。
3. 正常结束处理 `[DONE]`；本地已验证的兼容流可能在内容和 usage 后直接发送 `[DONE]`，没有单独的 `finish_reason` 帧，不能因此误报失败。无内容也无终态的空流不能按成功处理。输出上限 `finish_reason=length` 时网关可能不发 `[DONE]`，应显示“达到输出上限”。
4. 结构化 `error` 帧须显示为失败，不能把部分回复当完整成功。
5. 未收到有效终态就断流时报告“不完整”；流已经开始后不自动重发整个请求。
6. 用户停止生成时中止到网关的请求。部分输出可能已计量，停止不等于免费。

示例 SDK 实现了这些处理。它会限制单个 SSE 帧大小，并在消费者退出时取消上游读取。多轮聊天由下游重发需要保留的历史；网关不会替客户端保存聊天上下文。

## 5. 验证命令

```powershell
# 无需后端，验证客户端协议、错误、重试、取消与流解析
node --test .\examples\downstream-app\client.test.mjs

# 启动下游示例（需已有本地服务）
node .\examples\downstream-app\server.mjs --local-demo
```

Java 专项：`mvn -pl gateway-service -Dtest=DownstreamRetryHeadersTest,ApiKeyResponseHeadersTest,ChatCompletionRetryContractTest,RequestLimitServiceTest,OpenAiErrorMapperTest test`，需要 JDK 21 与 Maven 3.9+。真实 Redis/Garnet 专项 `RedisRequestLimitStoreLocalTest` 通过环境变量 `GATEWAY_TEST_REDIS_PORT` 和 `GATEWAY_TEST_REDIS_PASSWORD` 选择本机实例；只清理测试自己生成的 UUID 键，不执行 FLUSH。

本机验证证据与已知边界会记录到工作区的《下游接入与限流改进说明》。本地验证不等同于 Docker Compose 全套验收、完整前端回归或生产验收。
