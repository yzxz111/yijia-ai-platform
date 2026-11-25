# 云端目录可复原夹具

这份夹具保存当前环境已上传的 YCPS 专家/技能包、分类封面、专家展示资料和图片。**云端是来源**；本地保存的是从管理端读回的实际字节，不用制作目录中的旧包代替。

2026-10-08 本次更新已完成：**17 个专家包、1 个普通技能包、22 条分类、15 张专家分类封面**。专家图片共 50 张，加分类封面共 **65 张图片**；另含 7 条已有成员记录和 12 个仓库内置 Skill 的身份清单。

## 文件入口

| 文件/目录 | 内容 |
| --- | --- |
| [catalog.json](catalog.json) | 当前分类、YCPS 目录记录、目标包版本/revision、展示资料、图片指针与成员 |
| [packages/](packages/) | 18 个实际云端包；SHA-256、MD5、字节数与云端记录一致 |
| [images/categories/](images/categories/) | 15 张已上传的专家分类封面 |
| [images/skills/](images/skills/) | 17 位专家当前实际配置的头像、横幅及可选示例图 |
| [repro-manifest.json](repro-manifest.json) | 全部包、ZIP 成员、分类封面和专家图片的身份与摘要 |
| [cloud-byte-verification.json](cloud-byte-verification.json) | 导出完成后再次只读请求云端的全量比对记录 |
| [refresh-report.json](refresh-report.json) | 本轮新增/保留/移出项、测试及恢复演练范围 |
| [builtin-skills.json](builtin-skills.json) | 仓库内置 Skill 的身份；源文件仍在 yuzai/SKILLs |
| [external-skills-inventory.json](external-skills-inventory.json) | 5 个外部来源 Skill 的目录清单；没有已上传 YCPS 包，不列入本次包恢复 |

## 本次更新

新增收录 11 个专家：AI 学习助手，以及 expert-expansion 中逐个制作、随后由用户导入的 10 位专家。原先仍在云端的 7 个包逐字节未变，按实际源保留；新增 12 张分类封面，原有 3 张封面未变。

此前快照里的 `ecommerce-ops` 已不在本次复核的云端当前目录，故不再列入活动夹具；没有向云端执行删除。更新前的本地快照另存于工作区 `.scratch/catalog-snapshot-refresh-20261008/before.zip`，用于本次工作回查。

AI 学习助手当前目录没有单独示例图，因此 `example_image_file` 保持空值；它的已发布 ZIP 内仍包含自身示例资产。**目录配置与包内资产分别按云端原状保留**，没有擅自补图或重写包体。

## 专家包清单

| code | 名称 | version | revision |
| --- | --- | --- | ---: |
| `academic-writing-mentor` | 论文写作导师 | 1.0.0 | 1 |
| `adaptive-delivery-expert` | 自适应成果交付专家 | 1.0.0 | 1 |
| `ai-learning-assistant` | 学习助手 | 1.0.0 | 12 |
| `ai-office-assistant` | AI办公助手 | 1.0.0 | 9 |
| `content-summarizer` | 内容总结助手 | 1.0.0 | 7 |
| `content-writing-expert` | 内容创作专家 | 1.0.0 | 4 |
| `design-system-architect` | 设计系统架构师 | 1.0.0 | 1 |
| `document-review-expert` | 文档审校专家 | 1.0.0 | 6 |
| `fullstack-delivery-expert` | 全栈开发专家 | 1.0.0 | 1 |
| `news-briefing-expert` | 资讯速递专家 | 1.0.0 | 1 |
| `sales-analysis-expert` | 销售分析专家 | 1.0.0 | 6 |
| `senior-software-engineer` | 高级开发工程师 | 1.0.0 | 1 |
| `social-media-operations-expert` | 社媒运营专家 | 1.0.0 | 4 |
| `startup-partner` | 创业伙伴 | 1.0.0 | 1 |
| `wechat-miniapp-developer` | 微信小程序开发者 | 1.0.0 | 1 |
| `wechat-official-account-operator` | 微信公众号运营专家 | 1.0.0 | 1 |
| `workspace-builder` | 工作台搭建师 | 1.0.0 | 1 |

## 离线检查

从 system/ 目录执行（Python 需 Pillow）：

```powershell
python scripts/build-cloud-repro-manifest.py --check
python -m unittest scripts/test_export_catalog_snapshot.py scripts/test_restore_catalog_snapshot.py scripts/test_catalog_snapshot.py scripts/test_cloud_repro_manifest.py scripts/test_verify_catalog_snapshot.py
```

清单数量按当前目录计算，不再固定成旧的 8 包/6 专家。保留原六个 curated 专家的专门契约检查，同时对全部新包、引用文件、图像尺寸和摘要做完整性检查。`verify-catalog-snapshot.py` 的在线检查仍专用于原六个 curated 专家；本次全部 18 包与 65 图片的第二次实读比对见 cloud-byte-verification.json。

## 恢复到目标环境

先启动目标环境的数据库、对象存储与服务，再提供该环境管理员配置。恢复通过正式管理端接口完成，不直写数据库或对象存储。

```powershell
python scripts/restore-catalog-snapshot.py --base http://127.0.0.1:18081 --dry-run
python scripts/restore-catalog-snapshot.py --base http://127.0.0.1:18081
```

管理员凭据从 `deploy/env/.env` 的 `LOCAL_ADMIN_EMAIL` / `LOCAL_ADMIN_PASSWORD` 读取；可用 `--env-file` 指定目标环境配置。不要把凭据写进夹具或日志。

新专家按快照原样恢复运营 tags（包括空数组），业务 `taskTypes` 独立保留；原六个 curated 专家的既有标签契约保持。可选示例图缺失时使用 CLEAR，新建不会凭空填图。分类封面会核对字节与 MIME，一致时不重复上传。已有更高 revision 不回退；已有完整的管理员展示资料不擅自覆盖。需要修复旧 curated 图片时才显式使用 `--repair-curated-media`。

本次实际执行的是离线测试、空目标的本地模拟预演，以及当前环境的只读恢复预演；**没有对真实云端执行恢复、重新发布或内容修改**。新环境的数据库 ID、对象存储键、更新时间由目标服务重新生成，不要求与源环境相同；历史 revision 全链、账号、套餐、渠道及桌面已安装副本不属于本目录范围。

检查和预演的结果摘要统一保存在 refresh-report.json。单次运行日志和完整测试运行器报告属于本地生成产物，不随夹具提交；清理后仍可重新运行上述检查。

## 下次从云端更新

```powershell
python scripts/export-catalog-snapshot.py --base http://127.0.0.1:18081
```

导出从本机对应 PostgreSQL 容器读取目录元数据，再通过管理端 GET 下载素材与包体；`--base` 必须与该数据库属于同一环境。默认管理员配置同上，可用 `--env-file` 指定。

所有数据先下载到临时目录，包体 SHA-256/MD5/大小、图片和复现清单校验成功后，才覆盖正式夹具。同名旧包以新字节覆盖，旧目录不再引用的托管图片/包会移出活动夹具；README 等非导出文件保留。无需手工编辑 catalog.json，也不要在本地重打包云端发布文件。

包体和图片统一保存在 system；引用它们的 yuzai 桌面测试清单同步生成，避免保留已失效的旧包路径。

桌面两项检查在临时隔离的 Vitest 4.1.11 / Vite 7.3.1 环境通过。工作区现有 Vitest 4 / Vite 5 组合在启动时缺少 `vite/module-runner`；本轮没有改动项目依赖。71 项 Python 检查及恢复预演不受此问题影响。
