# 全栈开发专家：本地修复演练

仅修改 `server.mjs` 和 `client.mjs`，保留 `createTaskServer({ resolveUser, tasks })` 与 `updateTask(baseUrl, id, done, token)` 的导出签名，以及现有 Node HTTP 与内存 `Map` 接口。

服务端现在对缺失身份返回 JSON `401`；`GET /api/tasks` 只列调用者拥有的任务；`POST /api/tasks` 修剪标题并限制为 1–80 个字符，同时寻找当前 `Map` 中未占用的 ID；`PATCH /api/tasks/:id` 对不存在和非本人记录统一返回 `404`，只接受布尔 `done`。无效字段或 JSON 返回 `400`，在写入前拒绝。客户端 `updateTask` 对非成功 HTTP 响应抛出包含 `status` 的错误。

现有 `node --test acceptance.test.mjs` 修改前 9 项中 1 项通过、8 项失败；修改后 9 项全部通过。测试经本机 loopback 发出真实 HTTP 请求，覆盖身份缺失、按所有者列表、标题和 ID、越权更新、布尔状态、客户端拒绝错误和损坏 JSON 后服务继续工作。`resolveUser` 与存储 `Map` 由测试注入；这不证明生产身份服务、数据库或云宿主已联通。

操作边界：没有修改 `AGENTS.md`、`USER-NOTES.md`、`acceptance.test.mjs` 或专家包；没有新增依赖、数据库、外部网络调用，没有提交或发布。回退点是本演练开始时的两个实现文件。未运行迁移、生产部署或其他环境测试。

SHA-256（本地读取）：

| 文件 | SHA-256 |
| --- | --- |
| `system/fixtures/expert-expansion/fullstack-delivery-expert/SKILL.md` | `e7c50a3f57307cbc54e7c646099f606a40ebf7e4119f783593cc70dc517fadd4` |
| `system/fixtures/expert-expansion/fullstack-delivery-expert/fullstack-delivery-expert-1.0.0.ycps.zip`（封包，非 SKILL 文件） | `b9a13365bf887e485ffac7f71076cf9cd41095494ae028b608ac38bc73099e8b` |
| `AGENTS.md`（保护） | `3ffc31c30614849ce343a0174ec95b3bec986b554a7eb6f2c537aeb642419fd4` |
| `USER-NOTES.md`（保护） | `2846e5b8c5e70f2a37a13c9c78c7b6bb5a7d6d7446efa84a7643d854b5f96d2b` |
| `acceptance.test.mjs`（保护） | `c2cc8ce9ce104b18ae291de6eecc8807416f380fa3289c21680a304ee4b7deab` |
| `server.mjs`（修复后） | `427edfc5248be4514a4aa918dc8fa6c8cfbc4047a9f8c68770bf49a64df955c7` |
| `client.mjs`（修复后） | `42ca86ded838b341a68fd01fb2680ae2c01e33cd48c2f8ae37096663c1b5bad4` |
