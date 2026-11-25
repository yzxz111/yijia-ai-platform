import { ElMessageBox } from 'element-plus'

/**
 * 图片改动的**最终确认**。
 *
 * 背景（2026-09-19）：技能图标、介绍插画、分类封面这三处图片位此前是「点一下立即生效」，
 * 尤其是「清空」会当场删掉对象存储里的图并立刻刷新列表 —— 误点没有任何反悔余地。
 * 现在统一改成两段式：
 *
 * 1. 编辑页里的「清空」只做**标记**（显示「待清空」并可撤销），不碰后端；
 * 2. 点「保存修改」时由本函数列出本次全部图片改动，确认后才真正提交。
 *
 * 只读操作（下载、预览）不走这里 —— 它们不改后端状态，不需要确认。
 * 返回 `false` 表示操作者选择「返回修改」，调用方应当直接放弃本次提交（不改任何状态）。
 */
export async function confirmImageChanges(changes: string[]): Promise<boolean> {
  if (changes.length === 0) return true
  const items = changes.map((change) => `<li>${escapeHtml(change)}</li>`).join('')
  try {
    await ElMessageBox.confirm(
      '<p>本次保存会对图片做以下改动，确认后立即生效：</p>'
        + `<ul class="image-change-confirm__list">${items}</ul>`,
      '确认图片改动',
      {
        type: 'warning',
        // 改动清单需要多行展示，纯文本消息会把换行吃掉
        dangerouslyUseHTMLString: true,
        confirmButtonText: '确认并保存',
        cancelButtonText: '返回修改',
        // 「返回修改」是安全的那一侧，回车不该直接落到破坏性按钮上
        distinguishCancelAndClose: true,
        autofocus: false,
      },
    )
    return true
  } catch {
    return false
  }
}

/**
 * 改动条目来自组件内的常量文案，本身不含用户输入；仍然转义，
 * 避免以后有人把记录名之类的字段拼进来时把 HTML 注入带进弹窗。
 */
function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
