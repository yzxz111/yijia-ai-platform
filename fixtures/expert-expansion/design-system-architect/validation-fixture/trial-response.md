# 设计系统架构师：本地修复演练

仅修改 `styles.css`。沿用原有 `--action-bg`、`--action-fg`、`--focus-ring` 等语义变量及原生 `<button>`：浅色主题将启用按钮前景色改为 `#172033`，深色主题改为 `#ffffff`；两主题分别设置清晰的焦点环，并在 `:focus-visible` 时显示 3px 描边；`main` 宽度限制在视口内。

本地浏览器验收使用现有 `node --test acceptance.test.cjs`，实际启动本地 Chrome 和 loopback 页面。修改前 6 项中 2 项通过、4 项失败；修改后 6 项全部通过。测试覆盖两主题启用按钮的计算色值对比度、Tab 焦点样式、键盘激活与禁用按钮、语义变量存在、390px 视口无水平溢出、用户说明保留。修复后计算色值的文字/背景对比度为浅色 8.14:1、深色 6.12:1。`trial-mobile.png` 为验收测试生成的 390px 页面截图。

2026-10-08 核对 [W3C WCAG 2.2 对比度最低要求说明](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)：普通文字的最低比值为 4.5:1；停用控件属于该条的例外。本演练只证明上述本地页面的局部测试结果，未进行完整 WCAG 或辅助技术审计，也未连接 Figma。

操作边界：没有修改 `AGENTS.md`、`index.html`、`acceptance.test.cjs` 或专家包；没有新增依赖、脚本或网络资源，没有提交或发布。回退点是本演练开始时的 `styles.css`；本次只改动该文件三处规则。

SHA-256（本地读取）：

| 文件 | SHA-256 |
| --- | --- |
| `system/fixtures/expert-expansion/design-system-architect/SKILL.md` | `199141eb4ff577f2ba2debe65a7c7f9db197a0bb06545064550aabba73d5ad52` |
| `system/fixtures/expert-expansion/design-system-architect/design-system-architect-1.0.0.ycps.zip`（封包，非 SKILL 文件） | `aef1317e95b72061a9572af9fbd3444ddb8716897ad7b04d7c935cdbcbaf63ad` |
| `AGENTS.md`（保护） | `a0774bf473d857c34834fc8b2c4779c432b25db737c1b48ec114dd7325679fc3` |
| `index.html`（保护） | `e6e8f5cc933499db7c97288824e90f72f1a1ae4d045abd7037df171e892e2783` |
| `acceptance.test.cjs`（保护） | `a429a1d9b0276502b5c8b3107535769c34f723f1e5ed4978c241d93800365f21` |
| `styles.css`（修复后） | `0f385fd9c38807588dd8434654d223b212217e007e26e6b9553b21d0227c27f2` |
