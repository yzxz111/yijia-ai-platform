<script setup lang="ts">
import { LifeBuoy, ShieldAlert, ShieldCheck } from 'lucide-vue-next'
import { onMounted, ref } from 'vue'

import { getAccountState, type AccountState } from '../../api/platform'
import { useAuthStore } from '../../stores/auth'
import { formatDateTime } from '../../utils/dateTime'

const auth = useAuthStore()
const accountState = ref<AccountState | null>(null)

onMounted(async () => {
  try {
    accountState.value = await getAccountState()
    auth.updateAccountStatus(accountState.value.status)
  } catch {
    // 即使详情刷新暂时不可用，受限会话仍然可用。
  }
})
</script>

<template>
  <section class="restricted-page" aria-labelledby="restricted-title">
    <header>
      <span class="status-icon" aria-hidden="true"><ShieldAlert :size="24" /></span>
      <div>
        <p>账号状态</p>
        <h2 id="restricted-title">当前账号已被限制使用</h2>
      </div>
    </header>

    <section class="status-band" aria-label="受限账号说明">
      <dl>
        <div><dt>账号</dt><dd>{{ auth.user?.displayName || auth.user?.email || '当前用户' }}</dd></div>
        <div><dt>当前状态</dt><dd>已停用</dd></div>
        <div><dt>异常原因</dt><dd>{{ accountState?.disabledReason || '账号存在异常，请提交申诉或联系管理员。' }}</dd></div>
        <div><dt>停用时间</dt><dd>{{ accountState?.disabledAt ? formatDateTime(accountState.disabledAt) : '未记录' }}</dd></div>
      </dl>
      <p>普通业务功能暂不可用。你仍可提交安全申诉或支持工单，处理进度可在对应页面查看。</p>
    </section>

    <nav class="restricted-actions" aria-label="账号支持入口">
      <RouterLink to="/app/safety-appeals"><ShieldCheck :size="18" aria-hidden="true" /><span><strong>安全申诉</strong><small>提交申诉并查看处理进度</small></span></RouterLink>
      <RouterLink to="/app/support-tickets"><LifeBuoy :size="18" aria-hidden="true" /><span><strong>支持工单</strong><small>联系支持人员补充问题信息</small></span></RouterLink>
    </nav>
  </section>
</template>

<style scoped>
.restricted-page { width: min(760px, 100%); min-width: 0; margin: 0 auto; }
header { display: flex; align-items: center; gap: 12px; padding-bottom: 18px; border-bottom: 1px solid var(--app-border); }
header p, header h2, .status-band p, dl { margin: 0; }
header p { color: var(--app-danger); font-size: 12px; font-weight: 700; }
header h2 { margin-top: 2px; font-size: 22px; }
.status-icon { display: inline-flex; width: 44px; height: 44px; flex: 0 0 44px; align-items: center; justify-content: center; color: var(--app-danger); background: color-mix(in srgb, var(--app-danger) 9%, var(--app-surface)); border: 1px solid color-mix(in srgb, var(--app-danger) 25%, var(--app-border)); border-radius: 7px; }
.status-band { display: grid; gap: 18px; padding: 22px 0; border-bottom: 1px solid var(--app-border); }
dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
dl div { display: grid; gap: 4px; }
dt, .status-band p, .restricted-actions small { color: var(--app-muted); }
dd { margin: 0; font-weight: 650; overflow-wrap: anywhere; }
.status-band p { line-height: 1.7; }
.restricted-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; padding-top: 22px; }
.restricted-actions a { display: flex; min-height: 72px; align-items: center; gap: 12px; padding: 14px 16px; color: var(--app-fg); border: 1px solid var(--app-border); border-radius: 7px; text-decoration: none; transition: border-color var(--app-motion-fast) ease, background-color var(--app-motion-fast) ease; }
.restricted-actions a:hover { background: var(--app-primary-soft); border-color: var(--app-primary); }
.restricted-actions a:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }
.restricted-actions svg { flex: 0 0 auto; color: var(--app-primary); }
.restricted-actions span, .restricted-actions strong, .restricted-actions small { display: block; min-width: 0; }
.restricted-actions small { margin-top: 3px; line-height: 1.4; }
@media (max-width: 620px) { dl, .restricted-actions { grid-template-columns: minmax(0, 1fr); } }
</style>
