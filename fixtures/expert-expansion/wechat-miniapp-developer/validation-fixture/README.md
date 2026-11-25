# 微信小程序开发者：隔离修复演练

本演练让一位新的 GPT6-sol 子代理（最高推理强度）读取最终包的 `SKILL.md`，按约定修复虚构项目。它实际读取文件、查看已有改动、修改控制器并执行 Node 测试。测试使用回调式请求替身，不访问网络，也不是微信运行时。

## 结果

| 阶段 | 总数 | 通过 | 失败 |
| --- | --- | --- | --- |
| 修复前 | 15 | 4 | 11 |
| 修复后 | 15 | 15 | 0 |

测试覆盖正常输入、请求逆序、旧失败及旧完成回调、空关键词、失败后重试、HTTP 401/500、业务拒绝、错误数据形状、空结果、页面离开后的回调失效、同步异常和控制器实例隔离。原实现没有请求序号与退出标志，也未判断 HTTP 或业务结果；修复加入相应检查，并保留原导出与回调接口。

`search-controller.test.mjs`、`package.json`、`AGENTS.md` 和含有未提交用户补充的 `USER-NOTES.md` 四个文件均与冻结基线逐字节一致。修复代理只改控制器，另写两份结果报告，没有新增依赖、提交或发布。

## 保存的证据

- [before/](before/) 与 [after/](after/)：测试前后的完整小项目文件，可分别复制到独立目录运行 `npm test`；before 预期有 11 项失败。
- [baseline-test-output.txt](baseline-test-output.txt)：主任务冻结基线时实际运行的 TAP 输出。
- [baseline.json](baseline.json)：基线退出码、文件哈希和已有工作区改动。
- [trial-response.md](trial-response.md)、[trial-result.json](trial-result.json)：修复代理实际运行的命令与结果。
- [repair.diff](repair.diff)：控制器变更。
- [execution-validation.json](../execution-validation.json)：最终专家包哈希、指令哈希、受保护文件对比及验证范围。

主任务另行检查最终代码和受保护文件哈希，未重复运行已通过的修复后测试。包内示例采用注入 Promise 适配器，本演练采用原生风格的回调接口，两者有相似业务目标；这不是隐藏基准，不能由一次演练推断普遍可靠性。

未验证：Yuzai 实际宿主模型和工具权限、微信开发者工具、真实接口与登录、真机生命周期及渲染、在线导入与发布。模拟代码也不应作为完整小程序直接发布。
