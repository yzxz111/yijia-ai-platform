# 工作台搭建师：本地修复演练

仅修改 `app.js`，保留现有页面与原生控件。用户可输入 1–120 字符的任务标题并新增任务，按稳定 ID 完成或删除；任务以数组保存在本浏览器 `localStorage` 的 `task-desk-v1` 键，重载后恢复。列表使用文本节点显示用户内容，避免将标题解释为 HTML。此隔离任务的标题编辑不在既定验收范围内，当前界面未提供标题编辑。

“导出备份”下载 UTF-8 JSON `{version:1,tasks:[{id,title,done}]}`，只包含约定字段；导入先检查版本、数组、最多 500 条、每项唯一且非空的字符串 ID、1–120 字符标题与布尔完成态，再经确认替换。错误文件不改变当前列表或存档。启动时若存档损坏，会保留原内容、显示警告并提供原始内容下载；普通修改不能覆盖它。浏览器拒绝写入时，当前任务仍留在内存中，状态明确写出“未保存”并提示导出。备份文件由用户自行保管，本站没有云同步。

隔离试验的冻结基线为 10 项中 1 项通过、9 项失败。修复后 `node --test acceptance.test.cjs` 真实运行 10 项全部通过，退出码 0；完整输出见 `trial-test-output.txt`。验收由 Playwright 驱动本地 Chrome，通过 loopback 加载页面，覆盖空输入、新增及重载、完成、删除、HTML 注入防护、有效与无效导入、损坏存档、受阻写入、下载导出和 390px 无水平溢出。`trial-mobile.png` 是测试生成的 390px 截图。此结果不证明跨设备保存、云备份或完整无障碍审计。

操作边界：没有修改 `AGENTS.md`、`index.html`、`acceptance.test.cjs` 或专家包；没有新增依赖、外部网络请求、生产数据操作、提交或发布。回退点是本演练开始时的 `app.js`。

SHA-256（本地读取）：

| 文件 | SHA-256 |
| --- | --- |
| `system/fixtures/expert-expansion/workspace-builder/SKILL.md` | `430df1333deed3a4c7b5800e81df68d474914fbe84f95abd07ed340bdc71b667` |
| `references/deliverable-templates.md` | `41f0742a11a1941d3ecaa48b743c66296253105e283ccee9f18d1cf240244abf` |
| `references/worked-example.md` | `0fec6035901844a711c3a63d4c61414684a42cd5726b0b62ddc99f21d1da54a1` |
| `system/fixtures/expert-expansion/workspace-builder/workspace-builder-1.0.0.ycps.zip`（封包，非 SKILL 文件） | `be989cca0d7fe90bf06abefe4fd15623b98b5383622545f46cb78a5dc5ea6a9f` |
| `AGENTS.md`（保护） | `aaa306f4fe9df2734ef519a7fe9e975f1c3e102ce3f270713627f44732380b4c` |
| `index.html`（保护） | `a62d0ddaf40b17d3a48fa0aeab9186fc544afa5981f3147da165f0666da59a56` |
| `acceptance.test.cjs`（保护） | `24190004d1804a49736f602dd3a028d63b2bafca9b523ef255bef0471072ef60` |
| `app.js`（修复后） | `eddbf663107e1130771af67b3aa3add21141cf1e5d1984509ec74ec56ff57b7b` |
| `trial-test-output.txt`（完整最终测试输出） | `b19b8e1fa3e82b41edd1afdcd51b3d0d823c1320d71e18543a454d0df3f7d709` |
