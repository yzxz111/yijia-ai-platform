<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Gift, Copy, RefreshCw } from 'lucide-vue-next'

import {
  ensureReferralCode,
  fetchReferralOverview,
  referralInviteLink,
  rotateReferralCode,
} from '../../api/referral'

/**
 * 「我的邀请码」卡片。
 *
 * 邀请码链路的后端接口本来就有（`/console/platform/referrals/**`），但控制台此前没有任何入口，
 * 用户既看不到自己的码、也没法把邀请链接发出去 —— 被邀请人只能手抄 32 位码。
 * 这张卡片补上入口：显示码、一键复制邀请链接、看已邀请人数、必要时换码。
 */
const code = ref<string | null>(null)
const successCount = ref(0)
const loading = ref(true)
const busy = ref(false)

async function load() {
  loading.value = true
  try {
    const overview = await fetchReferralOverview()
    code.value = overview.activeCode
    successCount.value = overview.successCount
  } catch {
    ElMessage.error('邀请码加载失败，请稍后重试。')
  } finally {
    loading.value = false
  }
}

async function generate() {
  busy.value = true
  try {
    const overview = await ensureReferralCode()
    code.value = overview.activeCode
    successCount.value = overview.successCount
    ElMessage.success('邀请码已生成，复制链接发给好友即可。')
  } catch {
    ElMessage.error('邀请码生成失败，请稍后重试。')
  } finally {
    busy.value = false
  }
}

async function rotate() {
  try {
    await ElMessageBox.confirm(
      '换一个新码后，旧的邀请链接会立即失效（已经通过旧码注册的好友不受影响）。确定要换吗？',
      '换一个邀请码',
      { type: 'warning', confirmButtonText: '换一个', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    const overview = await rotateReferralCode()
    code.value = overview.activeCode
    ElMessage.success('已换新码，请重新复制邀请链接。')
  } catch {
    ElMessage.error('换码失败，请稍后重试。')
  } finally {
    busy.value = false
  }
}

async function copyLink() {
  if (!code.value) return
  const link = referralInviteLink(code.value)
  try {
    await navigator.clipboard.writeText(link)
    ElMessage.success('邀请链接已复制')
  } catch {
    // 非 HTTPS 或浏览器拒绝剪贴板权限时退回到手动复制。
    ElMessageBox.alert(link, '请手动复制邀请链接', { confirmButtonText: '知道了' })
  }
}

onMounted(load)
</script>

<template>
  <section class="invite-card">
    <header class="invite-card__head">
      <h3><Gift :size="16" />我的邀请码</h3>
      <span class="invite-card__count">已邀请 {{ successCount }} 人</span>
    </header>

    <p v-if="loading" class="invite-card__hint">正在加载…</p>

    <template v-else-if="code">
      <p class="invite-card__hint">
        把下面的链接发给好友：对方注册时会自动带上你的邀请码，注册成功后计入你的邀请人数。
      </p>
      <div class="invite-card__code" data-test="referral-code">{{ code }}</div>
      <div class="invite-card__actions">
        <el-button type="primary" data-test="referral-copy" @click="copyLink">
          <Copy :size="14" />复制邀请链接
        </el-button>
        <el-button :loading="busy" data-test="referral-rotate" @click="rotate">
          <RefreshCw :size="14" />换一个码
        </el-button>
      </div>
    </template>

    <template v-else>
      <p class="invite-card__hint">
        还没有邀请码。生成后可以把它发给好友，好友注册时填写即可完成归因。
      </p>
      <el-button type="primary" :loading="busy" data-test="referral-generate" @click="generate">
        生成我的邀请码
      </el-button>
    </template>
  </section>
</template>

<style scoped>
.invite-card {
  margin-bottom: 16px;
  padding: 18px 20px;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  background: var(--app-surface, #fff);
}
.invite-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.invite-card__head h3 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: 15px;
}
.invite-card__count {
  font-size: 12px;
  color: var(--app-muted-strong);
}
.invite-card__hint {
  margin: 6px 0 10px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--app-muted-strong);
}
.invite-card__code {
  padding: 10px 12px;
  margin-bottom: 12px;
  border-radius: 8px;
  background: var(--app-surface-weak, #f6f7f9);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 15px;
  letter-spacing: 1px;
  word-break: break-all;
}
.invite-card__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
