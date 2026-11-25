# 虚构示例：把按钮颜色迁移到双主题语义 token

这是独立教学样例，不是用户仓库、设计稿或浏览器检查记录。虚构输入：项目有 `--blue-500:#3559a8`、`--blue-hover:#294989`、`--blue-pressed:#213a72`，按钮默认背景直接使用这些变量；`OrdersPage` 和 `ProfilePage` 各有一处 `.primary` 按钮。要求建立浅色/深色语义映射，保留旧变量作为过渡，并让两个页面改用同一按钮。没有 Figma 文件，也没有实际运行的页面。

## 可检查的清查与迁移决定

| 输入事实 | 新规则 | 首批消费方 | 仍待检查 |
| --- | --- | --- | --- |
| 三个蓝色按视觉深浅命名 | 按按钮默认、悬停、按下状态命名 | `ActionButton` | 其他蓝色是否用于链接或图表 |
| 两页直接写 `.primary` | 改为共享按钮类与语义 token | `OrdersPage`、`ProfilePage` | 其余页面引用数 |
| 只有浅色主题 | 给两个主题分别映射 | 根主题与深色主题入口 | 实际主题切换逻辑 |

下面是**拟实施的最小 CSS**，不是宣称写入了虚构项目。深色值是本示例的设计提案，不能凭此声称满足 WCAG；必须在实际前景、背景、字号和交互状态中核验。

~~~css
:root {
  --blue-500: #3559a8; /* 过渡别名：先保留旧调用方 */
  --blue-hover: #294989;
  --blue-pressed: #213a72;
  --action-bg: #3559a8;
  --action-bg-hover: #294989;
  --action-bg-pressed: #213a72;
  --action-fg: #ffffff;
  --action-focus: #111827;
}

[data-theme="dark"] {
  --action-bg: #a9c1ff;
  --action-bg-hover: #c2d3ff;
  --action-bg-pressed: #8ea8e9;
  --action-fg: #16203a;
  --action-focus: #f7d77a;
}

.actionButton {
  color: var(--action-fg);
  background: var(--action-bg);
  border: 2px solid transparent;
  border-radius: 0.5rem;
  padding: 0.65rem 1rem;
  font: inherit;
}
.actionButton:not(:disabled):hover { background: var(--action-bg-hover); }
.actionButton:not(:disabled):active { background: var(--action-bg-pressed); }
.actionButton:focus-visible { outline: 3px solid var(--action-focus); outline-offset: 2px; }
.actionButton:disabled { opacity: 0.55; cursor: not-allowed; }
~~~

两个虚构消费方原本分别使用局部 `.primary` 类；迁移后的核心调用都为 `<button class="actionButton" type="button">保存</button>`，并由调用方的真实事件处理保存。禁用态应同时阻止操作且解释原因，不能只降低透明度。此处没有写保存逻辑，所以不能把按钮外观当成功能验收。

## 可独立运行的映射检查

保存为 `token-map-check.cjs` 后执行 `node token-map-check.cjs`。这只验证两个主题和必需状态有映射、旧别名在浅色主题仍一致；不会读取 CSS、运行浏览器或检验颜色对比度。

~~~js
const assert = require('node:assert/strict');

const themes = {
  light: {
    default: '#3559a8', hover: '#294989', pressed: '#213a72',
    foreground: '#ffffff', focus: '#111827'
  },
  dark: {
    default: '#a9c1ff', hover: '#c2d3ff', pressed: '#8ea8e9',
    foreground: '#16203a', focus: '#f7d77a'
  }
};
const required = ['default', 'hover', 'pressed', 'foreground', 'focus'];
for (const [name, values] of Object.entries(themes)) {
  for (const state of required) {
    assert.match(values[state], /^#[0-9a-f]{6}$/i, `${name}.${state}`);
  }
}
assert.equal(themes.light.default, '#3559a8');
assert.notEqual(themes.light.default, themes.dark.default);
console.log('Token mapping sample passed: 2 themes, 5 fields each');
~~~

预期输出为 `Token mapping sample passed: 2 themes, 5 fields each`。真实项目仍需统计旧引用、将 CSS 写入实际入口、让两页消费共享组件、执行构建、在浏览器用键盘依次访问按钮并检查聚焦/禁用/主题切换。颜色组合可单独按目标 [WCAG 2.2](https://www.w3.org/TR/WCAG22/) 成功准则核算；需再核对缩放、重排、错误反馈等页面行为才能讨论整体符合性。
