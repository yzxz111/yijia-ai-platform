# 隔离执行演练结果

已在虚构的纯 JavaScript 项目中修复 `search-controller.mjs`，保留 `createSearchController` 导出和 `search` / `dispose` API。项目使用注入的回调式请求模拟器，不是可运行的微信小程序；没有发起真实网络请求。

## 复现与原因

修复前运行 `npm test`：15 项中通过 4 项、失败 11 项。旧实现未区分请求是否仍对应当前搜索词，所有 `success`、`fail` 和 `complete` 回调都直接写页面状态。因此旧成功结果会覆盖新结果，旧失败或完成回调会改动新请求的错误与加载态；清空关键词仍发请求；`dispose()` 为空操作。成功回调直接读取 `response.data.items`，没有校验 HTTP 状态、`data.ok` 或 `items` 数组形状，同步抛出的请求错误也未转成可恢复状态。这些局部原因由源码及基线测试共同确认，微信端运行行为未验证。

## 改动

- 每次搜索（包括清空）递增请求序号；三个回调只有在序号仍有效且控制器未释放时才能发布状态。
- 空关键词立即恢复 `idle` 并清空结果和错误，不发请求；`dispose()` 使现有回调和后续搜索失效。
- 仅接受 HTTP 2xx、`data.ok === true` 且 `data.items` 是数组的结果；空数组进入 `empty`。请求失败、无效响应和同步请求异常进入可重试的 `error`，并结束加载。
- 没有调用 `abort()`；修复依赖回调失效保护，不声称取消了底层请求。

## 验证与保护范围

修复后 `npm test`：15 项全部通过、0 项失败。`node --check search-controller.mjs` 和 `git diff --check` 均以退出码 0 结束。测试覆盖旧成功/失败/完成回调、空输入、HTTP 401/500、业务拒绝、响应形状、空结果、重试、释放与同步请求异常。

只编辑了 `search-controller.mjs`，另在本隔离目录写入本报告及 `trial-result.json`。`AGENTS.md`、`package.json`、`search-controller.test.mjs` 未改动；原本未提交的 `USER-NOTES.md` 增补保持原样。未新增依赖，未提交、发布或操作其他仓库。

执行命令：`git status --short --untracked-files=all`、`git diff -- USER-NOTES.md`、`npm test`（修复前后各一次）、`node --check search-controller.mjs`、`git diff --check`、`git diff -- search-controller.mjs`、`Get-FileHash -Algorithm SHA256`（保护文件）。

剩余未验：微信开发者工具模拟器、微信平台行为、真实网络和后端接口、真机、宿主模型集成。本演练仅证明隔离项目中的回调控制逻辑和本地测试结果。
