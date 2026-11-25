# 专家包交付目录

按最新要求，参考截图的 10 位专家已逐一制作，前 3 位保留原包，本轮补齐剩余 7 位。每位都有独立内容、图形、两份参考资料与验收记录。

- [打开图文目录](index.html)
- [本轮验收总表](remaining-delivery-check.json)

| 顺序 | 专家 | 包 | 重点 |
| --- | --- | --- | --- |
| 01 | [论文写作导师](academic-writing-mentor/README.md) | [1.0.0](academic-writing-mentor/academic-writing-mentor-1.0.0.ycps.zip) | 论证、文献与返修 |
| 02 | [高级开发工程师](senior-software-engineer/README.md) | [1.0.0](senior-software-engineer/senior-software-engineer-1.0.0.ycps.zip) | 诊断、最小修复与重构 |
| 03 | [微信小程序开发者](wechat-miniapp-developer/README.md) | [1.0.0](wechat-miniapp-developer/wechat-miniapp-developer-1.0.0.ycps.zip) | 小程序页面、异步请求与分层验证 |
| 04 | [微信公众号运营专家](wechat-official-account-operator/README.md) | [1.0.0](wechat-official-account-operator/wechat-official-account-operator-1.0.0.ycps.zip) | 可粘贴稿件、事实依据、统计分母与发布状态 |
| 05 | [自适应成果交付专家](adaptive-delivery-expert/README.md) | [1.0.0](adaptive-delivery-expert/adaptive-delivery-expert-1.0.0.ycps.zip) | 实际文件、最小完整成果、冲突材料与数字一致性 |
| 06 | [资讯速递专家](news-briefing-expert/README.md) | [1.0.0](news-briefing-expert/news-briefing-expert-1.0.0.ycps.zip) | 原始来源、更正链、截止时刻与转载去重 |
| 07 | [设计系统架构师](design-system-architect/README.md) | [1.0.0](design-system-architect/design-system-architect-1.0.0.ycps.zip) | 语义变量、组件状态、实际消费方与键盘操作 |
| 08 | [全栈开发专家](fullstack-delivery-expert/README.md) | [1.0.0](fullstack-delivery-expert/fullstack-delivery-expert-1.0.0.ycps.zip) | 前后端契约、服务端权限、真实链路与验证分层 |
| 09 | [创业伙伴](startup-partner/README.md) | [1.0.0](startup-partner/startup-partner-1.0.0.ycps.zip) | 问题证据、意向与成交、现金测算及验证门槛 |
| 10 | [工作台搭建师](workspace-builder/README.md) | [1.0.0](workspace-builder/workspace-builder-1.0.0.ycps.zip) | 可操作任务流、保存重载、导出恢复与坏存档保护 |

## 本轮七个专家的验收

28 项离线导入/安装检查通过；70 个场景完成静态审查；7 次独立本地试用均留下成品。三项技术试用共 25 个实际测试通过，四项文档试用核对了完整成果、数字与材料边界。双语共 14 张预览逐张查看。静态审查、文件试用与真实宿主对话明确分开；未声称在线导入或发布，也未执行真实客户操作。

横幅统一要求纯图形，不含中文、英文、字母、数字或文字标签，两种语言共用同一资产。每个专家单独设计图形与流程，文字位于可翻译的 metadata/canvas。中文示例图单独声明 locale。

导入入口为中台专家管理的“从资源包导入”→“导入 YCPS / 完整包”。选择单个专家 `.ycps.zip`，核对 code、1.0.0、EXPERT 和展示信息，再按环境版本流程试用。最低客户端版本为 2026.6.30。包不会为宿主增加工具或账号权限。

## 内容源和重建

每位内容源在 specs/<code>.json、<code>/references/，独立图形在 scripts/expert-artwork/；论文导师图形保留在既有渲染入口。各专家目录含包、双语预览、说明、验收 JSON 及实际试用成品。

从 system/ 执行（将 CODE 替换为目标专家）：

```powershell
node scripts/render-expert-expansion.cjs --code CODE --assets
python scripts/build-expert-expansion.py --code CODE
node scripts/render-expert-expansion.cjs --code CODE --preview
python scripts/build-expert-expansion.py --code CODE --check
```

Python 需 Pillow，图片渲染使用已安装的 Playwright 和 Chrome。重建不调用云端接口。7 位新专家的网关测试入口为 RemainingExpertPackageAcceptanceTest；桌面测试为 remainingExpertPackageAcceptance.test.ts。本机测试使用临时 Vite 解析器，原因、环境与真实边界见各包 import-validation.json。

预览 PNG、图形渲染过程 HTML、合集 ZIP 和原始导入运行报告不进入版本库。单专家包、包内资源、内容源、质量摘要和隔离试用成品保留；preview.html 与图文目录可直接查看。需要预览截图时重新执行上面的渲染命令。
