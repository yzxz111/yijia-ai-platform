# startup-partner · 隔离试用

独立文件试用已生成实际成品，主代理复核内容与数字；成品见 validation-fixture/output。

读取包内执行正文与两份 references 后，由 GPT-6-sol（ultra）代理实际完成任务。试用是本地 Codex 环境，未运行云端宿主对话。主代理随后检查结果，未将测试目标交给文档试用代理作为答案。

- [运行记录](trial-result.json)
- [试用回复](trial-response.md)
- [主代理验收](../execution-validation.json)

输入见 input.json，真实输出在 output/，review-contract.json 为主代理的预设审查草案，未提供给运行代理；未被请求的可选指标不作为失败条件。
