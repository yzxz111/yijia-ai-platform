# 一加AI中台

面向多租户的模型 API 管理与转发平台。用户注册后领取或购买套餐，由客户端获取托管 API Key，通过 **OpenAI 兼容接口**调用文本 / 视觉 / 生图 / 视频 / 音频模型；平台负责渠道路由与故障转移、额度预留与结算、计费与钱包、支付与订阅，并提供管理控制台。

本文档的目标是**让一个新环境从零把项目跑起来**。命令均在仓库根目录执行，Windows PowerShell 可直接复制。

| 入口 | 地址 |
|---|---|
| 本地统一入口（Nginx 是唯一入口） | `http://127.0.0.1:18081` |
| 管理控制台登录 | `http://127.0.0.1:18081/login` |
| 健康检查 | `http://127.0.0.1:18081/health/platform`、`/health/gateway` |
| 模型 API Base URL | `http://127.0.0.1:18081/v1` |
| 生产环境 | `https://api.yuzhh.com`，Base URL `https://api.yuzhh.com/v1` |

---

## 一、这个仓库里有什么

### 1.1 架构

Nginx 是唯一对外发布的端口，其余服务都在 Compose 内部网络里：

```
                    ┌───────────────────────────────┐
  浏览器 / 客户端 ──▶│ nginx :18081 (宿主机唯一端口)  │
                    └───────────────┬───────────────┘
                                    │
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
     ` / ` 控制台           `/console/platform/*`    `/console/gateway/*`、`/v1/*`
              │                     │                     │
              ▼                     ▼                     ▼
        web-console         platform-service        gateway-service
     (Vue3 + 静态站点)      (MVC + JDBC 交易域)    (WebFlux + R2DBC 转发域)
                                    │                     │
                                    └────────┬────────────┘
                                             ▼
                        postgres(双 schema) / redis / minio / clamav
                                             gateway ──▶ wiremock(本地模型上游)
                                             gateway ──▶ document-converter(LibreOffice)
```

| 组件 | 职责 | 技术栈 |
|---|---|---|
| `web-console` | 管理端与用户端控制台 | Vue 3 + TypeScript + Element Plus + Vite |
| `platform-service` | 身份认证、短信/邮箱验证码、套餐、发放、订单、支付、退款、钱包、订阅、报表、审计 | Java 21 + Spring Boot MVC + JDBC |
| `gateway-service` | API Key、模型路由、限流、配额预留/结算、文件、OpenAI 兼容 API | Java 21 + Spring Boot WebFlux + R2DBC |
| `postgres` | 单库双 schema：`platform`（交易域）+ `gateway`（转发域），**无跨 schema 外键** | PostgreSQL 17 + Flyway |
| `redis` | API Key 缓存、限流、并发控制 | Redis 7 |
| `minio` | S3 兼容对象存储（生产可切阿里云 OSS 直传） | MinIO |
| `clamav` | 上传文件与技能包的恶意软件扫描（失败即拒绝） | ClamAV |
| `wiremock` | 本地模型上游，覆盖成功 / SSE / 失败 / 超时 / 故障转移 | WireMock |
| `document-converter` | 内网隔离的 DOC/XLS 转换服务（无宿主端口、无外网） | Node.js + LibreOffice |
| `nginx` | 唯一外部入口、反向代理、SSE 透传 | Nginx |

`postgres-extensions`、`jwt-keygen`、`minio-init`、`local-bootstrap` 是**一次性初始化任务**，分别补齐数据库扩展、生成本地 JWT 密钥、创建私有存储桶、引导默认管理员。它们正常退出码为 `0`，不应长期处于 `running`。

### 1.2 技术栈版本（本仓库实际要求）

| 工具 | 版本要求 | 说明 |
|---|---|---|
| JDK | **21** | `pom.xml` 里 `java.version=21`，用 17 会报 `不支持发行版本 21` |
| Maven | 3.9+ | 根 `pom.xml` 是聚合工程，两个模块一起管 |
| Node.js | 22.12+ | `jsdom` / Vite 7 要求；22 或 24 均可 |
| pnpm | **11.9.0** | 由 `web-console/package.json` 的 `packageManager` 固定 |
| Docker | 含 Compose v2 | 只跑全栈的话，宿主机**不需要**装上面任何工具 |

> ⚠️ **pnpm 版本坑**：`packageManager` 固定为 `pnpm@11.9.0`。如果本机是 pnpm 9/10，`pnpm install` 会因锁文件格式不一致而报错。先对齐版本：
>
> ```powershell
> corepack enable
> corepack prepare pnpm@11.9.0 --activate
> pnpm -v      # 应输出 11.9.0
> ```

---

## 二、已完成

### 2.1 已上生产并在运行

| 模块 | 实现内容 |
|---|---|
| **身份与账号** | 手机号 / 邮箱账号密码直接登录；管理员开通账号；桌面端 OAuth 授权码与设备登录恢复；浏览器会话恢复；终端会话闲置超时；注册同意服务端校验；短信与邮件双通道（验证码按 IP 限流 5+5 次/分钟） |
| **套餐与额度** | 免费套餐自助领取；套餐版本发布 / 退休；管理员定向发放（带可审计原因与业务号）；**四类额度维度分开计量**（`TOKEN` / `IMAGE` / `VIDEO_SECOND` / `AUDIO_SECOND`）；同维度排队生效、跨维度互不影响；库存与限购 |
| **套餐发放投递** | 平台用**事务 Outbox** 把发放事件推给网关，状态 `QUEUED → DELIVERED → ACTIVE` |
| **钱包与订单** | 钱包充值 / 余额 / 流水；订单生命周期；钱包与套餐是**两套独立账本** |
| **支付与退款** | 支付回调幂等；支付异常重试与保留；退款快照与审批；套餐撤销 Outbox；日对账 |
| **订阅与连续包月** | 钱包余额自动续费；价格变更确认与终止；接受价记录；失败重试与通知；到期提醒 |
| **网关密钥与路由** | API Key 发放 / 缓存 / 停用 / 撤销；托管密钥；模型、别名、渠道、路由、上游映射；能力探测与渠道健康；故障转移与熔断；四档限流（每分钟 / 并发 / 日请求 / 包级） |
| **计量与结算** | 用量日志 + `usage_stage` 分阶段（attempt、上游模型、错误类别、供应商请求 ID）；配额预留 / 结算 / 退款三段式；按 token、按张、按秒三种口径；成本与经济证据链；结算垫付与缺口 |
| **实时额度接口** | `GET /v1/quota` 实时额度；套餐权益接口逐模型额度与单次消耗；用量查询带 `quotaCharge` 逐行实际扣费 |
| **文本与视觉** | OpenAI 兼容 `/v1/chat/completions`（含 SSE）与 `/v1/responses`；组合模型 `multimodal-basic`（文本 + 图片 + 文件自动分流）；`vision-basic` / `vision-pro` / `vision-tianyi`；客户端目录只露「普通 / 深度」 |
| **图片生成** | 文生图、图生图、多参考图 1~8 张、图层拆分、水印；一次请求**扇出多张**（每张独立预留与结算、部分成功逐张退款、幂等键重放补齐）；`1.5K` 上架 / `4K` 下架；目录下发 `parameters`；审核拒绝映射为 `422 image_content_policy_violation`；超时上限 240 秒 |
| **视频生成** | 异步视频任务；内容回传；目录下发可生成参数（分辨率 / 画幅 / 秒数 / 图生 / 音频）；`video-minimax` 合进 5 个视频套餐并回填老用户 |
| **音频** | TTS / 配音、口播、字幕、换口型、ASR；音频别名进在售视频套餐并回填；按音频秒计量 |
| **文件与文档** | 上传 / 下载 / 删除 / 轮询；内容签名地址；上传会话；ClamAV 扫描（失败即拒绝）；内网隔离的 DOC/XLS 转换 |
| **Skill 与专家** | `SKILL` / `EXPERT` / `TEAM` 三值（含主理人与成员花名册、头像）；双语字段、示例图片、分类封面；ClawHub 导入导出 / 上下架 / 批量下载；可移植导入；目录快照导出与恢复校验 |
| **控制台** | 45 个视图：管理端 20（运营概览、经营报表、结算垫付、内容安全、用户与邀请、套餐发放、套餐版本、模型与渠道、Skill / 专家 / 专家团、调用与额度、订单支付、钱包、订阅、通讯与工单、系统运维、协议管理、审计、安全申诉）+ 用户端 16 + 认证 9 |
| **运维与可观测** | 健康检查快照；额度预留恢复作业；生图任务恢复扫描；文件清理；审计日志；结构化日志；渠道预算 / 毛利 / 库存 / 结算缺口告警 |
| **工程化与交付** | 10 服务 Compose 本地全栈；HTTPS 与证书续期；生产包装脚本；**镜像与源码双回滚**；部署契约测试；代码格式检查 |

### 2.2 已实现，但未在生产验证

| 模块 | 已写的部分 | 为什么不算完成 |
|---|---|---|
| 支付与退款 | 订单、支付回调幂等、退款快照与审批、日对账、钱包充值 / 余额 / 流水；本地 `MOCK` 支付闭环已跑通 | 真实微信 / 支付宝未过沙箱与生产门禁；支付宝退款异步通知被指向支付通知 URL，退款终态只能主动查询 |
| 视频真实生成 | 适配器与任务链路已构建，24 项检查通过 | 当前部署无上游凭据，调用返回 `503 video_channel_unavailable`，**一次真实付费生成都没发生过** |
| 文档转换真实用例 | 服务与测试已就绪 | 本机缺 LibreOffice，真实 DOC/XLS 转换用例处于跳过状态 |
| OSS 直传与备份恢复 | 配置、安全基线与验收脚本已写 | 缺测试凭据，PITR 未演练 |
| Skill / YCPS 门禁 | YCPS 1.1 标准与校验资源在仓库 | 8 条 Activation Gate 未走完；权威校验器 `scripts/ycps/*.mjs` 不在仓库 |

---

## 三、待完成

### 3.1 P0 —— 需优先处理

| # | 事项 | 说明 |
|---|---|---|
| 1 | **V116 迁移未上线** | `usage_log` 截断诊断五列已在仓库（`generation_status` / `finish_reason` / `effective_max_output_tokens` / `ceiling_source` / `attempt_count`），生产未应用。当前被截断的请求落库是 `outcome=SUCCEEDED, http_status=200, error_summary=NULL`，**与自然结束无法区分** |
| 2 | **迁移链在干净库上会中断** | `V89` 自检对可空列用普通 `=`，`NULL = NULL` 求值为 NULL → `PERFORM 1` 报错。已修至 19 项 3 失败 0 错误，未复跑到全绿 |
| 3 | **无 CI + 假绿门禁** | 仓库无 `.github/`；Maven 命令行带 `-Dmaven.test.failure.ignore=true`，失败与 `BUILD SUCCESS` 出现在同一份日志；平台测试中约 708 项是环境错误 |
| 4 | **无可用第二上游** | `chat-deep` / `chat-standard` / `image-basic` / `video-minimax` 都只有一条渠道；`bailian-primary` 与 `deepseek-primary` 实测 401（后者 `base_url` 错指天翼网关、`provider_id` 归属 tianyi），**换渠道逻辑因此无法生效** |
| 5 | **扣款前短信模板未审批** | 4 个模板仍为 `RESERVED` + 占位码，扣款前通知发不出去（合规要求必须通知）。**模板审批前不得开 `SUBSCRIPTION_JOBS_ENABLED`**（本机 `.env` 目前是 `true`，见 8.2） |
| 6 | **低于毛利下限的套餐仍在售** | V102 迁移直写绕过发布守卫，两个视频套餐为 **10.17% / 2.67%**；运行期监控只告警不阻断 |
| 7 | **计费口径未决** | 费率卡 `provider_cost_*` 与 `provider_model_price` 存在 **100x** 单位分歧；`gateway.model_usage_price` 是否作计费源未决；TPM / 日上限是否强制未决 |
| 8 | **语义审核的 profile 可能是 `local`** | MOCK 审核有启动期 fail-fast，但 `SPRING_PROFILES_ACTIVE` 仍默认 `local`，`compose.https.yaml` 未覆盖。需上服务器确认 `.secrets/*.env` 的真实 provider |



### 3.2 尚未开始的功能

| 功能 | 现状 |
|---|---|
| **套餐「即时覆盖」（取消排队）** | 未开始。现在有生效套餐时再买同维度套餐要排队等待，产品侧要改成立刻生效；**旧套餐剩余额度怎么处置（作废 / 折算 / 转钱包 / 顺延）还没定** |
| **邀请好友** | 未开始。邀请码目前只做归因记录，**没有奖励逻辑**，用户端也没有入口 |
| **子代理分销** | 未开始。平台目前没有任何代理 / 分销体系 |
| **按量付费** | 未开始 |



## 四、方式一：Docker Compose 全栈（推荐，最快）

宿主机只需 **Docker Desktop** 和**一个可用端口 `18081`**。JDK / Maven / Node / pnpm 全部由镜像内提供。

### 4.1 准备环境文件

```powershell
Copy-Item .\deploy\env\.env.example .\deploy\env\.env
```

> ⚠️ **第一步必做，否则启动一定失败**：`compose.yaml` 里有 7 个变量用 `${VAR:?}` 声明为**必填**，未设置时 Compose 直接拒绝解析并报
> `required variable ... is missing a value`。其中 6 个在 `.env.example` 里已经带了本地开发值，**只有 `DOCUMENT_CONVERTER_AUTH_TOKEN` 是空值**，必须自己填一个随机串：
>
> ```powershell
> # 生成 40 位随机串并写回（BOM-free UTF-8，PowerShell 5.1 / 7 均可）
> $chars = (48..57) + (97..122) | ForEach-Object { [char]$_ }
> $token = -join (1..40 | ForEach-Object { $chars | Get-Random })
> $enc  = New-Object System.Text.UTF8Encoding($false)          # $false = 不写 BOM
> $file = '.\deploy\env\.env'
> $text = [System.IO.File]::ReadAllText($file, $enc)
> $text = $text -replace '(?m)^DOCUMENT_CONVERTER_AUTH_TOKEN=.*$', "DOCUMENT_CONVERTER_AUTH_TOKEN=$token"
> [System.IO.File]::WriteAllText($file, $text, $enc)
>
> # 确认写进去了
> Select-String -Path $file -Pattern '^DOCUMENT_CONVERTER_AUTH_TOKEN='
> ```
>
> ⚠️ **不要用 `Get-Content` / `Set-Content` 改这个文件**，两个坑会同时踩中（本机 PowerShell 5.1 实测）：
>
> 1. `.env` 里有**中文注释**，5.1 的 `Get-Content` 在 GBK 代码页下会按错误编码解码（乱码），
>    再把乱码写回去，中文注释就永久损坏了，甚至会把相邻行**粘成一行**；
> 2. `Set-Content -Encoding utf8` 在 5.1 下会**写入 BOM**（`EF BB BF`）。`.env` 首行变成 `\ufeffNGINX_PORT=18081`，
>    Compose 就读不到这个变量了。
>
> 另外别把生成写成 `Get-Random -Count 40`：字符池只有 36 个字符，`-Count` 不做重复取样，
> 结果会静默只返回 36 位而不是报错。
>
> 这个令牌是网关调用 `document-converter` 的共享密钥，两端都从 `.env` 读同一个值。**两端必须一致**，否则 DOC/XLS 转换会 401。

改完先做一次静态校验，能提前暴露变量名拼错、值缺失：

```powershell
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml config --quiet
```

没有任何输出即为通过。

### 4.2 启动

```powershell
# 首次启动需要构建镜像，耗时较长
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml up --build -d

# 看状态：10 个长期服务应为 running/healthy，4 个一次性任务应为 exited (0)
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml ps --all
```

`local-bootstrap` 会等两个后端服务 healthy 后才运行，因此首次启动到控制台可用通常需要 1~3 分钟（ClamAV 首次加载病毒库较慢）。

### 4.3 验证健康状态

```powershell
$baseUrl = 'http://127.0.0.1:18081'
Invoke-RestMethod "$baseUrl/health/platform"    # 期望 status: UP
Invoke-RestMethod "$baseUrl/health/gateway"     # 期望 status: UP

# 一次性初始化任务应全部退出码 0
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml ps --all jwt-keygen minio-init local-bootstrap
```

### 4.4 登录控制台

| 项 | 值 |
|---|---|
| 地址 | `http://127.0.0.1:18081/login` |
| 邮箱 | `admin@local.test`（可用 `.env` 的 `LOCAL_ADMIN_EMAIL` 改） |
| 密码 | `.env` 里的 `LOCAL_ADMIN_PASSWORD` |

密码不在本文档里明文写死，需要时自己取：

```powershell
Select-String -Path .\deploy\env\.env -Pattern '^LOCAL_ADMIN_PASSWORD='
```

登录成功后进入 `/admin/overview`。

> `local-bootstrap` **只在管理员账号尚无密码时**写入该值，之后每次部署都不会覆盖 —— 你在控制台改过的密码不会被重新部署回滚。

### 4.5 停止与重置

```powershell
# 停止容器，保留容器与数据
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml stop

# 停止并移除容器，保留命名卷数据
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml down

# 彻底重置：删除容器 + 所有数据卷，再重建
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml down --volumes --remove-orphans
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml up --build -d
```

> ⚠️ `down --volumes` 会**永久删除**本地 PostgreSQL、Redis、MinIO 和 JWT 密钥卷，包括账号、套餐发放、API Key、调用记录和已上传文件。执行前确认这些数据可以丢弃。**不要**把它当作生产回滚手段。

### 4.6 日志与排查

```powershell
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml logs --tail 200 platform-service gateway-service nginx
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml logs -f gateway-service
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml ps --all
```

---

## 五、方式二：宿主机直接开发（不用 Docker 跑后端）

适合需要断点调试、改一行立刻生效的场景。**中间件仍然用容器跑**，只有两个 Spring Boot 服务和控制台跑在宿主机上。

### 5.1 宿主机需要

- JDK 21 + Maven 3.9+（`$env:JAVA_HOME` 指向 JDK 21）
- Node.js 22.12+ + pnpm 11.9.0
- Docker Desktop（只用来跑中间件）

### 5.2 第一步：把中间件端口暴露出来

**关键前提**：`deploy/compose.yaml` 只发布了 Nginx 的 `18081`，Postgres / Redis / MinIO **都没有映射宿主端口**，宿主机上的后端连不上。仓库里已经准备好一份端口覆盖文件 `deploy/compose.dev-ports.yaml`，用它叠加启动：

```powershell
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml -f .\deploy\compose.dev-ports.yaml `
  --env-file .\deploy\env\.env up -d postgres postgres-extensions redis minio minio-init
```

覆盖后会额外发布这些端口（仅用于本地开发，切勿用于生产）：

| 服务 | 宿主端口 | 用途 |
|---|---|---|
| postgres | `55432` | 数据库 |
| redis | `56379` | 缓存 / 限流 |
| minio | `59000` (API) / `59001` (控制台) | 对象存储 |

等 Postgres 就绪（首次要先跑完 `postgres-extensions`）：

```powershell
docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml -f .\deploy\compose.dev-ports.yaml ps --all
```

### 5.3 第二步：连接信息

数据库是**单库双 schema**，两个服务用各自的账号和 schema，互不干扰：

| 项 | platform-service | gateway-service |
|---|---|---|
| schema | `platform` | `gateway` |
| 账号 | `platform_app` | `gateway_app` |
| 密码变量 | `PLATFORM_DB_PASSWORD` | `GATEWAY_DB_PASSWORD` |
| 连接串 | `jdbc:postgresql://localhost:55432/api_model_platform?currentSchema=platform` | `r2dbc:postgresql://localhost:55432/api_model_platform?schema=gateway` |

密码取自 `deploy/env/.env`，用下面命令读取，**不要**在脚本或文档里硬编码：

```powershell
Select-String -Path .\deploy\env\.env -Pattern '^(PLATFORM_DB_PASSWORD|GATEWAY_DB_PASSWORD|REDIS_PASSWORD|MINIO_ROOT_PASSWORD)='
```

### 5.4 第三步：启动 platform-service

新建一个终端，在**仓库根目录**执行：

```powershell
$env:JAVA_HOME = 'E:\Java\jdk-21'        # 换成你机器上的 JDK 21 路径
$env:SPRING_PROFILES_ACTIVE       = 'local'
$env:SPRING_DATASOURCE_URL        = 'jdbc:postgresql://localhost:55432/api_model_platform?currentSchema=platform'
$env:SPRING_DATASOURCE_USERNAME   = 'platform_app'
$env:SPRING_DATASOURCE_PASSWORD   = (Select-String -Path .\deploy\env\.env -Pattern '^PLATFORM_DB_PASSWORD=').Line -replace '^PLATFORM_DB_PASSWORD=',''
$env:SPRING_FLYWAY_SCHEMAS        = 'platform'
$env:SPRING_FLYWAY_DEFAULT_SCHEMA = 'platform'
$env:SPRING_DATA_REDIS_HOST       = 'localhost'
$env:SPRING_DATA_REDIS_PORT       = '56379'
$env:SPRING_DATA_REDIS_PASSWORD   = (Select-String -Path .\deploy\env\.env -Pattern '^REDIS_PASSWORD=').Line -replace '^REDIS_PASSWORD=',''

# compose 里以 ${VAR:?} 声明为必填的密钥，宿主机运行也要显式提供（取 .env 中的同值）。
# GATEWAY_SECRETS_ENCRYPTION_KEY 只有网关用得到，platform 不读它。
$env:SECURITY_INTERNAL_HMAC_SECRET            = '<取自 .env，同值>'
$env:SECURITY_API_KEY_PEPPER                  = '<取自 .env，同值>'
$env:LOCAL_ADMIN_PASSWORD                     = '<取自 .env，同值>'
$env:LOGIN_RISK_PEPPER                        = '<取自 .env，同值>'
$env:PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY       = '<取自 .env，同值>'

mvn -o -pl platform-service -am spring-boot:run
```

启动时 Flyway 会自动把 `platform` schema 迁移到最新（平台序列至 `V106`）。看到 `Started PlatformServiceApplication` 即成功，健康检查：`http://localhost:8080/actuator/health/readiness`。

### 5.5 第四步：启动 gateway-service

再开一个终端：

> ⚠️ **两个服务的默认端口都是 8080**（`application.yaml` / `application.properties` 里都没有 `server.port`），
> 宿主机同时跑会**端口冲突**。所以第一步先把网关挪到 8081：

```powershell
$env:JAVA_HOME = 'E:\Java\jdk-21'
$env:SERVER_PORT = '8081'          # 必加：gateway 默认也是 8080，与 platform 冲突
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SPRING_R2DBC_URL       = 'r2dbc:postgresql://localhost:55432/api_model_platform?schema=gateway'
$env:SPRING_R2DBC_USERNAME  = 'gateway_app'
$env:SPRING_R2DBC_PASSWORD  = (Select-String -Path .\deploy\env\.env -Pattern '^GATEWAY_DB_PASSWORD=').Line -replace '^GATEWAY_DB_PASSWORD=',''
$env:SPRING_FLYWAY_URL      = 'jdbc:postgresql://localhost:55432/api_model_platform?currentSchema=gateway'
$env:SPRING_FLYWAY_USER     = 'gateway_app'
$env:SPRING_FLYWAY_PASSWORD = $env:SPRING_R2DBC_PASSWORD
$env:SPRING_FLYWAY_SCHEMAS  = 'gateway'
$env:SPRING_DATA_REDIS_HOST = 'localhost'
$env:SPRING_DATA_REDIS_PORT = '56379'
$env:SPRING_DATA_REDIS_PASSWORD = (Select-String -Path .\deploy\env\.env -Pattern '^REDIS_PASSWORD=').Line -replace '^REDIS_PASSWORD=',''

# 对象存储指向宿主暴露的 MinIO
$env:STORAGE_FILES_ENDPOINT = 'http://localhost:59000'

# 这几个默认值在 compose 里被改成容器主机名，宿主机运行必须改回 localhost
$env:GATEWAY_ECONOMICS_PLATFORM_URL            = 'http://localhost:8080'
$env:PLATFORM_SMS_INTERNAL_BASE_URL            = 'http://localhost:8080'
$env:PLATFORM_CONTENT_SAFETY_INTERNAL_BASE_URL = 'http://localhost:8080'
$env:DOCUMENT_CONVERTER_AUTH_TOKEN             = '<与 .env 中一致>'
# DOCUMENT_CONVERTER_BASE_URL 在宿主机模式下**留空即可**：它的默认值为空，
# 空值表示关闭 DOC/XLS 多模态预处理（文本、图片、生图、视频等功能不受影响）。
# 需要真实转换时，用 compose.dev-ports.yaml 把 document-converter 也发布出来。默认端口是 8080。

# 网关有两个内部地址默认指向容器主机名 platform-service，宿主机运行要改回 localhost，
# 否则控制台登录态校验与桌面端设备校验返回 401，控制台进不去。
# （Spring 的宽松绑定会把环境变量名映射成 gateway.console-token-validation.uri）
$env:GATEWAY_CONSOLE_TOKEN_VALIDATION_URI = 'http://localhost:8080/console/platform/auth/validate'
$env:GATEWAY_TERMINAL_DEVICE_VALIDATION_URI = 'http://localhost:8080/console/platform/auth/terminal-device/validate'

mvn -o -pl gateway-service -am spring-boot:run
```

启动时 Flyway 迁移 `gateway` schema（网关序列至 `V116`）。健康检查：`http://localhost:8081/actuator/health/readiness`。

> 上面前两个 `GATEWAY_*_VALIDATION_URI` 变量在 `application.properties` 里是硬编码的容器地址，
> 但在 Compose 中通过同名环境变量注入（见 `deploy/compose.yaml` 的 `GATEWAY_CONSOLE_TOKEN_VALIDATION_URI`），
> 宿主机运行沿用同一套变量名即可生效。若你的 Spring 版本没有开启宽松绑定，改用启动参数显式覆盖：
>
> ```powershell
> mvn -o -pl gateway-service -am spring-boot:run `
>   "-Dspring-boot.run.arguments=--gateway.console-token-validation.uri=http://localhost:8080/console/platform/auth/validate"
> ```

### 5.6 第五步：启动前端控制台（可选，Vite 热更新）

```powershell
cd web-console
pnpm install          # 首次
pnpm dev              # Vite Dev Server，默认 http://localhost:5173
```

`web-console` 通过 `/console/*` 与 `/v1/*` 调后端，`vite.config.ts` 里已配置代理；如果后端跑在宿主机 `8080/8081`，确认代理目标与你的实际端口一致。

### 5.7 方式二的取舍

- 中间件（Postgres/Redis/MinIO/ClamAV/WireMock）还是容器，只有后端与控制台在宿主机 —— 断点调试和热重载最舒服。
- ClamAV 与 WireMock 也在容器里，若网关要连它们，同样需要覆盖文件把它们暴露出来，或改用 `docker compose` 全栈。
- 四个一次性初始化任务（`jwt-keygen` / `minio-init` / `local-bootstrap` 等）**只在全栈模式下自动执行**。宿主机模式首次需要手动补齐管理员引导，否则没有账号可登录：

  ```powershell
  docker compose -f .\deploy\compose.yaml --env-file .\deploy\env\.env up local-bootstrap
  ```

---

## 六、测试

### 6.1 后端（宿主机，需 JDK 21）

```powershell
$env:JAVA_HOME = 'E:\Java\jdk-21'
mvn -o -pl gateway-service  -am test
mvn -o -pl platform-service -am test
```

### 6.2 前端

```powershell
cd web-console
pnpm install
pnpm build     # vue-tsc -b && vite build
pnpm test      # vitest run
pnpm test:e2e  # playwright test，首次需先 pnpm exec playwright install chromium
```

### 6.3 已知基线失败（与本仓库瘦身有关，非代码缺陷）

本仓库为「源码 + 文档」最小形态，`docs/` 目录已整体移除，因此**部分测试会失败**——它们依赖被删掉的夹具文件：

| 位置 | 现象 | 恢复方式 |
|---|---|---|
| `gateway-service` → `SkillPackageInspectorTest` | 约 19 项找不到 `docs/standards/ycps/*/test-vectors` | `git checkout HEAD -- docs/standards` |
| `deploy/tests/oss-backup-contract.test.mjs` | 读取 `docs/testing/aliyun-oss-backup-restore.md` 失败 | `git checkout HEAD -- docs/testing` |
| `platform-service` 套件 | 约 40 项失败 / 错误，均为**过期的测试夹具**（硬编码的套餐版本 UUID，已被迁移替换或每库随机生成） | 需更新夹具，与本次瘦身无关 |
| `web-console/src/views/auth/LoginViewOAuth.spec.ts` | 1 项路由竞态失败：`expected '/desktop-login' to be '/desktop-set-password'`（前端整体 1334/1335） | 需修路由时序 |

也就是说：**跑测试时看到上述失败属预期**。若要它们全绿，把对应 `docs/` 子目录从历史里取回来即可（不影响运行）。

---

## 七、本地账号与业务流程

打通「注册 → 发放套餐 → 建 Key → 调模型」这条主链：

### 7.1 账号密码直接登录

1. 打开 `http://127.0.0.1:18081/login`，输入已有账号的邮箱或已绑定手机号及密码，点击「登录」。页面直接显示密码输入框，无需切换登录方式。
2. 登录不调用邮箱、短信或滑块验证服务；邮箱服务到期、`EMAIL_ENABLED=false` 或 `SMS_ENABLED=false` 不影响已有账号的密码登录。
3. 普通账号登录后进入 `/app/dashboard`，管理员进入 `/admin/overview`；桌面端专用授权页同样使用账号密码登录。
4. 账号由管理员开通；忘记密码或历史账号尚未设置密码时，请联系管理员处理。通过 SQL 创建的邮箱账号需要有效的 BCrypt `password_hash` 及 `platform.user_role` 角色关联，不能填写明文密码；手机号登录使用平台已有的手机号绑定信息。
5. 密码校验、登录限流、账号状态、设备数量限制、会话恢复和受保护接口鉴权继续生效。设备达到上限时，按页面提示选择退出旧设备。

> 本次取消的是网页与桌面授权页的验证码登录入口。其他业务（例如绑定手机号、重置 API Key、支付确认）已有的验证规则及历史验证码 API 保持原样；不将其改成免密码或免鉴权接口。

### 7.2 测试库普通账户（演示账号）

本地测试想跳过「注册 + 发套餐」两步直接有可用账户，用 `scripts/` 下的种子脚本按已发布套餐批量建号。

**怎么创建**

```powershell
# ① 建普通账户（每个已发布套餐一个账号）
docker exec -i yuzhihuihe-api-platform-postgres-1 `
  psql -U postgres -d api_model_platform -v ON_ERROR_STOP=1 `
  < .\scripts\seed-plan-users.sql

# ② 给这些账户发放并激活对应套餐
docker exec -i yuzhihuihe-api-platform-postgres-1 `
  psql -U postgres -d api_model_platform -v ON_ERROR_STOP=1 `
  < .\scripts\seed-plan-grants.sql
```

两个脚本都是**幂等**的，重跑只补缺、不会重复建号；重跑 `seed-plan-grants.sql` 会重新发放并刷新套餐有效期。

> ⚠️ 这两个脚本**不会随 Compose 自动执行**，需要手工跑。上面用的是容器名，若你的项目名不同，先 `docker compose --env-file .\deploy\env\.env -f .\deploy\compose.yaml ps` 确认 postgres 容器名。

**账号规则**

| 项 | 值 |
|---|---|
| 登录邮箱 | `<套餐代码小写并把下划线换成短横>@yuzhi.test` |
| 密码 | `123456`（全部账号统一） |
| 登录入口 | `http://127.0.0.1:18081/login` 的「密码登录」，identifier 填邮箱 |

生成规则来自脚本：`lower(replace(plan_row.code, '_', '-')) || '@yuzhi.test'`。

**典型账号**（按 9 个已发布套餐）

| 登录邮箱 | 密码 | 套餐 | 额度 | 用量维度 |
|---|---|---|---|---|
| `free@yuzhi.test` | `123456` | 免费版 FREE | 50,000 | 词元 |
| `starter@yuzhi.test` | `123456` | 入门版 STARTER | 2,000,000 | 词元 |
| `advanced@yuzhi.test` | `123456` | 高级版 ADVANCED | 16,000,000 | 词元 |
| `enterprise@yuzhi.test` | `123456` | 企业版 ENTERPRISE | 100,000,000 | 词元 |
| `video-starter@yuzhi.test` | `123456` | Video Starter | 60 | 秒 |
| `video-lite@yuzhi.test` | `123456` | Video Lite | 100 | 秒 |
| `video-lite-annual@yuzhi.test` | `123456` | Video Lite Annual | 1,100 | 秒（365 天） |
| `video-creator@yuzhi.test` | `123456` | Video Creator | 190 | 秒 |
| `video-pro@yuzhi.test` | `123456` | Video Pro | 600 | 秒 |

额度与有效期由脚本按套餐的 `duration_days` 滚动发放，所以具体数字随执行时间变化，以 `/app/quota` 页面为准。

**命令行登录示例**

```powershell
# X-Device-Id 必须是 32 位小写十六进制，且应当固定复用
# （每次随机会累积 terminal_session，超过 max_login_devices 默认 5 后登录被拒）
$deviceId = '550e8400e29b41d4a716446655440000'
$body = @{ identifier = 'video-pro@yuzhi.test'; password = '123456' } | ConvertTo-Json

$login = Invoke-RestMethod -Method Post `
  -Uri 'http://127.0.0.1:18081/console/platform/auth/login' `
  -Headers @{ 'X-Device-Id' = $deviceId } -ContentType 'application/json' -Body $body

# 查该账号的套餐与额度
Invoke-RestMethod -Uri 'http://127.0.0.1:18081/console/gateway/quota/summary' `
  -Headers @{ Authorization = "Bearer $($login.accessToken)" }
```

> **密码说明**：`123456` **不满足注册页的强密码策略**（需 8 位以上、含大小写与符号），但登录路径只做哈希比对、不校验强度，因此这些账号可以正常登录。要换成强密码，改 `scripts/seed-plan-users.sql` 里的 `crypt('123456', ...)` 后重跑。
>
> **仅限本地测试**：这些账号与统一口令只用于本地演示库，**不要在任何共享或生产环境保留**。

### 7.3 管理员发放套餐

1. 管理员进入「套餐发放」（`/admin/grants`）。
2. 选择启用用户、已发布套餐版本，填写可审计的发放原因，点「确认发放」。
3. 页面生成唯一业务号，初始状态 `QUEUED`。Outbox 变 `DELIVERED` 表示网关已接收：用户没有活动套餐时新套餐立即 `ACTIVE`；已有活动套餐时保持 `QUEUED`，待当前套餐到期或用尽后自动激活。
4. 用户可在 `/app/quota` 查看活动套餐、排队套餐、可用模型与剩余额度。

### 7.4 下游 App 获取平台 API Key

普通用户登录后可进入左侧 **API 密钥**（`/app/api-keys`），创建并复制平台生成的 Key，将页面的 **API Base URL** 与该 Key 配置到下游应用。完整密钥只在创建或短信验证重置后显示；模型权限、密钥数量、套餐/钱包额度与计费规则继续生效。

客户端自动接入仍可在已有账号和有效套餐的基础上，完成桌面 Web 登录并调用 `POST /console/gateway/client/bootstrap?usageDimension=TOKEN`；模型请求使用返回的 `apiBaseUrl` 与 `apiKey`。自助 Key 与托管 Key 两种方式均支持现有 `/v1` 接口。

完整接口顺序、设备标识、错误码、429 重试和 SSE 终态说明见 [下游接入指南](docs/downstream-integration.md)；最小 OpenAPI 契约见 [downstream-openapi.json](docs/downstream-openapi.json)。仓库新增无需安装第三方依赖的下游网页示例：

```powershell
node .\examples\downstream-app\server.mjs --local-demo
```

本机先按工作区的《本地运行说明》启动平台，再打开 `http://127.0.0.1:18084`。`--local-demo` 使用已初始化的本地演示账号；其他环境去掉该开关并使用已有账号登录。上游为 WireMock 时只证明本地联调，不代表真实模型验收。

---

## 八、模型 API 调用示例

先通过托管 Key 引导接口获取调用凭证，并从 `/v1/models` 选择已授权模型。套餐允许 `multimodal-basic` 时，客户端可始终发送这个 `model`；网关按整段请求历史中的文本、图片、文件选择实际组件渠道。配置了真实渠道时请求会到百炼 / DeepSeek / 天翼，否则本地栈用 WireMock 兜底。

`multimodal-basic` 是**无状态**的 OpenAI 兼容模型，不保存服务端对话历史。客户端下一轮必须重发需要保留的历史消息与附件引用；24 小时内未删除的 `file_id` 可以重发。

> ⚠️ **Windows PowerShell 5.1 中文编码坑**：`Invoke-RestMethod -Body "<字符串>"` 会按 **Latin-1** 发送，中文提示词到服务端就是乱码 —— 生图会返回一张与提示词无关的图，但 HTTP 仍是 200，极难发现。
> 因此下面含中文的示例都先把 JSON 写成 **UTF-8（无 BOM）文件**再 `-InFile`。**纯英文请求不受影响**，所以现象通常是「英文正常、中文出怪结果」。

### 8.1 文本请求

```powershell
$baseUrl = 'http://127.0.0.1:18081'
$apiKey  = 'sk-yzh-...'   # 替换为你的密钥

$body = @{
  model = 'multimodal-basic'
  stream = $false
  messages = @(@{ role = 'user'; content = '请用一句话介绍这个平台。' })
} | ConvertTo-Json -Depth 6
[IO.File]::WriteAllText("$PWD\req.json", $body, (New-Object Text.UTF8Encoding($false)))

Invoke-RestMethod -Method Post -Uri "$baseUrl/v1/chat/completions" `
  -Headers @{ Authorization = "Bearer $apiKey" } `
  -ContentType 'application/json' -InFile "$PWD\req.json"
```

响应头含 `x-request-id`；正文是 OpenAI Chat Completions 兼容结构并包含 `usage`。

### 8.2 SSE 流式请求

用 Windows 自带的 `curl.exe`，`-N` 禁用客户端缓冲：

```powershell
$sseBody = @{
  model = 'multimodal-basic'
  stream = $true
  stream_options = @{ include_usage = $true }
  messages = @(@{ role = 'user'; content = 'stream response' })
} | ConvertTo-Json -Depth 6 -Compress

$sseBody | curl.exe -N -X POST "$baseUrl/v1/chat/completions" `
  -H "Authorization: Bearer $apiKey" `
  -H 'Content-Type: application/json' `
  --data-binary '@-'
```

> **流式响应不再保证以 `[DONE]` 收尾**：命中输出预算被截断时，最后一块带 `finish_reason=length` 且**不发** `[DONE]`；首段之后的失败会发一帧结构化错误帧（含 `error.code` / `message` / `type` / `reason` / `request_id`）后正常关闭。客户端要按 `finish_reason` 与错误帧判断终态，不能只看有没有收到 `[DONE]`。
>
> **客户端超时必须 ≥ 300 秒**：网关一次对话请求的总时间预算为 300 秒（`gateway.chat.total-attempt-timeout`）。客户端若仍用 30/60/90 秒，会在网关之前先断开，症状看起来和没修一样。

---

## 九、环境变量

`deploy/env/.env` 由 `deploy/env/.env.example` 复制而来，已被 `.gitignore` 排除，**不要提交**。

### 9.1 必填（`${VAR:?}`，缺失则拒绝启动）

| 变量 | 用途 |
|---|---|
| `LOCAL_ADMIN_PASSWORD` | 本地引导管理员密码，仅在账号无密码时写入一次 |
| `SECURITY_INTERNAL_HMAC_SECRET` | 内部事件与 `/internal/**` 签名 |
| `SECURITY_API_KEY_PEPPER` | API Key HMAC 摘要 pepper |
| `GATEWAY_SECRETS_ENCRYPTION_KEY` | 渠道凭据加密密钥，**必须恰好 32 字节** |
| `DOCUMENT_CONVERTER_AUTH_TOKEN` | 网关调用文档转换服务的共享令牌（`.env.example` 中为空，**必须自己填**） |
| `LOGIN_RISK_PEPPER` | 登录风控指纹 pepper |
| `PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY` | 口令登录限流的 HMAC 键 |

### 9.2 本地开发开关（务必确认取值）

| 变量 | 本地建议值 | 说明 |
|---|---|---|
| `PAYMENT_MOCK_ENABLED` | `true` | 注册模拟支付端点；**非本地环境必须为 `false`** |
| `CONTENT_SAFETY_SEMANTIC_ALLOW_MOCK` | `true` | 语义审核走 WireMock，不是真实审核 |
| `SUBSCRIPTION_JOBS_ENABLED` | `false` | 自动续费调度；见下方警告 |
| `EMAIL_ENABLED` | `false` | 邮箱验证码开关 |
| `NGINX_PORT` | `18081` | Nginx 宿主端口，被占用时改这里 |

> ⚠️ **`SUBSCRIPTION_JOBS_ENABLED=true` 需谨慎**：扣款前短信模板（`SUBSCRIPTION_RENEWAL_DUE` / `RENEWED` / `RENEWAL_FAILED` / `PRICE_CHANGED`）仍为 `RESERVED` + 占位码，未送审。开启调度意味着会在「扣款前通知」被 `TEMPLATE_INACTIVE` 静默抑制的情况下扣款，而合规要求扣款前必须通知。**模板审批前请保持 `false`。**

> ⚠️ **凭据轮换坑**：轮换 `GATEWAY_SECRETS_ENCRYPTION_KEY` 会让所有渠道密文无法解密，且仓库中没有重加密脚本 —— 必须同时重新录入每个渠道的密钥。

> **仅限本机**：`.env` 中的数据库、Redis、MinIO 密码与上述密钥都是**本地开发值**。共享 / 测试 / 预发布 / 生产环境必须改成独立生成的强随机值。

---

## 十、生产部署与回滚要点

生产在阿里云 ECS（目录 `/opt/yuzhihuihe-api-platform`，**不是 git 仓库**，源码靠 tar 包覆盖上传），入口 `https://api.yuzhh.com`。

```bash
cd /opt/yuzhihuihe-api-platform
sudo bash deploy/production.sh config --quiet
sudo bash deploy/production.sh ps --all
```

发布与回滚必须遵守：

- 生产 ECS **无外网**：Java 服务需在容器内多阶段构建；`web-console` 需在开发机构建 `prebuilt-dist` 后再上传。
- 网关与平台启动都会执行 **Flyway 迁移**。**已发布的迁移不得修改** —— 改了必须同步 Flyway checksum，否则服务起不来（`scripts/flyway-checksum-sync.py` 可辅助）。
- 每次部署前先给当前镜像打回滚标签（`rollback-<变更名>-<时间戳>`），并 `pg_dump` 到 `.backups/`。
- **不要**用 `down --volumes` 作为回滚手段。
- **不要**手工把套餐版本状态改回 `PUBLISHED`（要写新迁移）；周期状态置 `TERMINATED` 后**不得**手工改回 `ACTIVE`。
- 渠道的「测试」按钮只做 DNS 解析与 TCP/TLS 握手、**不发 `Authorization`**，错误密钥同样显示 HEALTHY。唯一可靠的验证是真实调用。
- 生产启用 HTTPS 用 `deploy/compose.https.yaml` 叠加；证书续期见 `deploy/nginx/reload-tls.sh`。

---

## 十一、常见问题（FAQ）

| 现象 | 原因与处理 |
|---|---|
| 启动即报 `required variable ... is missing a value` | 没复制 `.env`，或 `DOCUMENT_CONVERTER_AUTH_TOKEN` 仍为空。见 4.1 |
| 编译报 `不支持发行版本 21` | `JAVA_HOME` 指向了 JDK 17。切到 JDK 21，见 1.2 |
| `pnpm install` 报锁文件不兼容 | 本机 pnpm 版本与 `pnpm@11.9.0` 不一致。`corepack prepare pnpm@11.9.0 --activate` |
| 宿主机起后端连不上数据库 | Postgres 没发布宿主端口。用 `compose.dev-ports.yaml` 叠加启动，见 5.2 |
| 宿主机起两个后端时其中一个启动失败报端口占用 | 两个服务默认都是 8080。给网关设 `SERVER_PORT=8081`，见 5.5 |
| 控制台登录一直 401 | 宿主机模式下 `gateway.console-token-validation.uri` 仍指向容器主机名 `platform-service:8080`。设 `GATEWAY_CONSOLE_TOKEN_VALIDATION_URI`，见 5.5 |
| 生图中文提示词返回无关图片 | PowerShell 5.1 的 Latin-1 编码坑，改用 UTF-8 文件 `-InFile`，见 8.1 |
| 流式输出到一半断了 | 客户端超时小于网关的 300 秒预算。把客户端超时调到 ≥300 秒，见 8.2 |
| 想跳过注册直接拿普通测试账号 | 跑 `scripts/seed-plan-users.sql` + `seed-plan-grants.sql`，见 7.2 |
| 首次启动很久没就绪 | ClamAV 首次加载病毒库慢，`local-bootstrap` 在等两个后端 healthy。看 `ps --all` 而不是只看 `ps` |
| 迁移在干净库上中断 | 历史遗留：`V89` 自检对可空列用 `=`，`NULL = NULL` 求值为 NULL 导致 `PERFORM 1` 报错。需按新迁移修复，见 3.1 |

---

## 十二、仓库结构

| 路径 | 内容 |
|---|---|
| `platform-service/` | 交易域服务（身份、套餐、支付、订阅、审计）；Flyway 迁移在 `src/main/resources/db/migration`，序列至 `V106` |
| `gateway-service/` | 转发域服务（API Key、路由、限流、结算、文件、OpenAI 兼容 API）；迁移序列至 `V116` |
| `web-console/` | Vue 3 控制台（管理端 + 用户端），`pnpm dev` 起开发服务器 |
| `document-converter/` | 内网隔离的 LibreOffice DOC/XLS 转换服务 |
| `deploy/` | `compose.yaml` 全栈编排、`production.sh`、Nginx 配置、`env/` 环境样例、`compose.dev-ports.yaml` 本地开发端口覆盖 |
| `fixtures/` | YCPS 专家包、目录快照、可移植导入夹具（**测试依赖，勿删**） |
| `skills/` | YCPS 技能 / 专家包样例 |
| `scripts/` | 目录快照校验、专家包构建、Flyway checksum 同步、生产排障脚本 |
| `pom.xml` | Maven 聚合工程根（`platform-service` + `gateway-service`） |
| `2026-08-01-api-model-platform-phase2-design.md` | 二期商业交易闭环初始设计文档（从原 `docs/` 移到根目录保留） |

> **本仓库为「源码 + 文档」最小形态**：`docs/` 目录已整体移除（含部分测试夹具），原来由 `docs/` 承载的设计稿、接口文档、排障记录不再随仓库提供。若需查阅，从 git 历史里取：`git log --diff-filter=D --name-only` 可列出已删除的文件。

> **以下内容只在本机、不进版本库**（已被 `.gitignore` 排除，因此克隆后看不到）：
>
> | 路径 | 内容 |
> |---|---|
> | `reports/` | 项目交接说明、风险底账、缺陷排查、部署记录与事故复盘、实测报告 |
> | `安全审计与压测报告.md` | 安全审计结论、未修项清单、压测数据。**含未修漏洞的利用条件，刻意不随仓库发布** |
>
> 这些文件只存在于产出它们的机器上。若在别的机器上需要，得从原始来源单独获取。
