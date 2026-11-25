# 虚构微型示例：重试配置中的有效零值

本文件是**虚构的独立教学样例**，用于说明本专家如何区分契约、复现、修复和验证。它不是用户仓库的代码、测试报告或真实线上故障；不能把下方预期输出称为某个用户项目已运行的结果。实际任务必须重新读取项目代码并运行项目自己的检查。

## 已给定的契约

函数名为 createRetryOptions(input = {})，返回 maxRetries 和 delayMs 两个字段。

- maxRetries=0 合法，表示不重试；delayMs=0 合法，表示无额外等待。
- 某字段为 undefined 或 null 时，分别采用默认值 3 或 100。
- 负数、NaN 和字符串是非法值，**上层已经校验**；本次不扩大到输入校验或改动该契约。

虚构的故障代码：

~~~js
function createRetryOptions(input = {}) {
  return {
    maxRetries: input.maxRetries || 3,
    delayMs: input.delayMs || 100,
  };
}
~~~

在 JavaScript 中 0 是假值，所以输入 maxRetries=0、delayMs=0 时，两个逻辑或表达式分别返回 3、100。由此可以对**这段示例代码**静态确定零值会被覆盖；实际用户项目的根因仍须按其代码与运行证据判断。

| 输入 | 契约期望 | 旧表达式的结果 |
| --- | --- | --- |
| {} | {maxRetries: 3, delayMs: 100} | 同期望 |
| {maxRetries: 0, delayMs: 0} | {maxRetries: 0, delayMs: 0} | {maxRetries: 3, delayMs: 100} |
| {maxRetries: null, delayMs: null} | {maxRetries: 3, delayMs: 100} | 同期望 |
| {maxRetries: 2, delayMs: 50} | {maxRetries: 2, delayMs: 50} | 同期望 |

## 最小修复

空值合并运算符只在 null 或 undefined 时回退，符合本例契约：

~~~js
function createRetryOptions(input = {}) {
  return {
    maxRetries: input.maxRetries ?? 3,
    delayMs: input.delayMs ?? 100,
  };
}
~~~

这一步只替换两个表达式，不顺手更改输入校验、重试调度逻辑或依赖版本。在真实仓库中，还应核对调用方是否把 0 另作含义，核对项目所支持的 JavaScript 运行环境是否接受 ??；如不支持，应选择等价且兼容的表达式，而不是未经确认直接引入语法。

## 可独立运行的回归检查

以下代码可保存为 worked-example-check.cjs 并以 node worked-example-check.cjs 运行。这是示例级自检；即使它通过，也不代表任何用户项目的测试、构建或部署已通过。

~~~js
const assert = require("node:assert/strict");

function createRetryOptions(input = {}) {
  return {
    maxRetries: input.maxRetries ?? 3,
    delayMs: input.delayMs ?? 100,
  };
}

assert.deepEqual(createRetryOptions(), { maxRetries: 3, delayMs: 100 });
assert.deepEqual(createRetryOptions({ maxRetries: 0, delayMs: 0 }), { maxRetries: 0, delayMs: 0 });
assert.deepEqual(createRetryOptions({ maxRetries: null, delayMs: null }), { maxRetries: 3, delayMs: 100 });
assert.deepEqual(createRetryOptions({ maxRetries: undefined, delayMs: undefined }), { maxRetries: 3, delayMs: 100 });
assert.deepEqual(createRetryOptions({ maxRetries: 0, delayMs: 50 }), { maxRetries: 0, delayMs: 50 });
assert.deepEqual(createRetryOptions({ maxRetries: 2, delayMs: 0 }), { maxRetries: 2, delayMs: 0 });
~~~

若真实项目的测试框架不同，应把这些输入与预期迁移到现有测试，不创建仅复制实现的断言。交付时分别写清“示例级自检”和“用户项目实际执行的检查”，未执行者标未运行。
