# AI 学习助手：YCPS 1.1 完整导入夹具

这是专门验证中台专家编辑器“导入 → 回显 → 导出”的独立测试包，不替换或发布现有六个云端专家。包名为 `ai-learning-assistant`，版本 `1.0.0`，类型 `EXPERT`。

直接在中台“专家分类管理”顶部的“导入 Expert YCPS 1.1”区域选择 [ai-learning-assistant-1.0.0.ycps.zip](ai-learning-assistant-1.0.0.ycps.zip)，完成预检后确认导入。测试建议保持默认的“仅填空”，不要选择已有专家作为覆盖目标。导入后打开新专家编辑页，检查中文和 English 两种语言，再导出完整 YCPS 包并重新导入到隔离测试环境比较。

应能回显：

- 独立头像、3:1 介绍插画和中文示例输入图。示例图只替换中文的请求/素材区域；切换 English 时应显示英文结构化文字，不应出现中文烘焙图片。
- 本包示例图是 1072×280，恰为中台编辑器、实时预览及桌面画布中请求/素材合并区域 536×140 的 2 倍像素尺寸，正常缩放即可无留白、无裁剪、无比例变形。三端仅对不同比例的历史/上传图用 `object-fit: fill` 兜底；这不会改写包内图片字节，但比例差异过大时画面会变形。
- 1080×704 双语画布；醒目标语只有“一句话”/“One sentence”使用蓝色，标点及后半句保持黑色；任务标签沿用其他专家包的宿主默认字体颜色。标题、说明、使用引导和效率提示仍包含局部蓝色/橙色文字与不同字号，且内容仍是可编辑文本，不是截图。
- 左侧“7天高效复习计划 / Seven-day efficient study plan”，包含效率方法、用时、具体行动、检查点四列表格，三条正文提示。
- 右侧四个业务任务类型、三条可编辑快捷示例；桌面详情只显示前两条。
- 头像、插画、示例图、表格、颜色和字号在完整 YCPS 再导出/导入后保持一致。

包内只有 `plugin.json`、Expert manifest、Entry Skill 和三张 `assets/` 图片；编辑内容以结构化 metadata 保存，无整页截图。三张原始测试资源在 `source-assets/`；`expected-admin-presentation.json` 是由同一生成器同步生成的中台 Detail 测试投影。这些文件都不是额外的包内条目。包大小约 99 KiB，SHA-256：`38c104271e0e94a30fbcc7445d919bfa68019d79c5b84b71e551b58451ba4771`。

重建（在 `system/` 目录执行）：

```powershell
python scripts/render-ai-learning-example-image.py
python scripts/build-ai-learning-assistant-import-fixture.py
python scripts/build-ai-learning-assistant-import-fixture.py --check
```

`render-ai-learning-example-image.py` 现在调用统一的七专家示例图渲染器，从本夹具的中文请求和材料字段生成 1072×280 图片。渲染需要 Node.js、`web-console` 的 Playwright 依赖，以及 Chrome、Edge 或已安装的 Playwright Chromium；打包需要 Python 和 Pillow。打包生成器固定 ZIP 路径顺序、时间戳和压缩设置，并同步生成中台测试投影；`--check` 不写文件，仅核对资源和产物是否逐字节一致。
