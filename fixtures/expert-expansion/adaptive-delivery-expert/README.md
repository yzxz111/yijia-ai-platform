# 自适应成果交付专家 · 1.0.0

把模糊请求落成恰当的文稿、表格、文件修改或操作清单；按实际材料与工具交付，明确证据范围和可验收结果。

- **可导入包：**[adaptive-delivery-expert-1.0.0.ycps.zip](adaptive-delivery-expert-1.0.0.ycps.zip)
- **双语预览：**[preview.html](preview.html)
- **完整指令：**[SKILL.md](SKILL.md)
- **身份：**YCPS 1.1.0 / EXPERT / `adaptive-delivery-expert@1.0.0`。
- **最低客户端：**2026.6.30；无额外 Skill 依赖。专家包不会增加宿主工具、账号或发布权限。
- **大小：**96,166 字节，8 个包内文件。
- **SHA-256：**`0f510e904d3546dceedfa2fe6cb408efd5a724301673760384582d82cd9011dd`。

## 使用方式

1. 在中台专家管理选择“从资源包导入”→“导入 YCPS / 完整包”，选择本专家的 `.ycps.zip`。
2. 核对 code、版本、EXPERT 类型，以及中英文说明、示例和画布。已有同名对象时先核对身份与版本。
3. 按当前环境的版本流程试用。说清想达成什么、给谁使用和期限；提供已有材料、文件或格式要求。专家会选择最省事的可用形式，完成成果并标出真实验证范围。

首次可以这样试用：

> 把这六条会议纪要整理成发给团队的行动清单，责任人和日期只用原文里的信息。

## 质量与验收

本专家重点：**实际文件、最小完整成果、冲突材料与数字一致性**。执行流程和边界在正文中，两份包内资料提供[交付模板](references/deliverable-templates.md)与[虚构完整示例](references/worked-example.md)。引用的示例不冒充用户资料或实际执行记录。

- **4 项离线导入检查通过：**生产检查器、上传暂存、重编译再验包、桌面安装与专家注册；三张图片和两份参考资料逐字节保留。见 [import-validation.json](import-validation.json)。数据库、对象存储、扫描及桌面边界使用测试替身。
- **10 个具体场景完成静态复核：**包括信息不足、材料冲突、工具缺失、嵌入指令与英文请求。见 [quality-review.json](quality-review.json) 与 [quality-scenarios.json](quality-scenarios.json)。这不代表 10 次真实宿主对话。
- **实际试用：**独立文件试用已生成实际成品，主代理复核内容与数字；成品见 validation-fixture/output。 最终仅收短三个英文展示标签；执行正文与参考资料不变，版本对应见 trial-source-equivalence.json。 见 [隔离试用与成品](validation-fixture/README.md)、[执行验收](execution-validation.json)。运行代理使用 GPT-6-sol（ultra），主代理另行审查。
- **双语展示：**中文、英文预览逐张查看且无检测到的文字溢出。横幅为 1440×480 纯图形，无文字、字母或数字，两种语言共用。图标 240×240；中文示例图声明 zh locale，英文使用英文元数据与可编辑画布。见 [visual-check.json](visual-check.json)。

包内只有运行所需的 8 个文件；审查、测试、试用输入与输出在 ZIP 外，不随专家安装自动执行。

本版本已通过本地离线验收，尚未在线导入或发布。试用使用虚构数据与隔离环境，不能代替真实宿主对话、生产服务、真实账号或设备验收。
