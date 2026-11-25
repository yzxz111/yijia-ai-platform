<script setup lang="ts">
import { APP_NAME } from '../../brand'
import { KeyRound } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import AuthBrandPanel from '../../components/auth/AuthBrandPanel.vue'
import PasswordRecoveryForm from '../../components/auth/PasswordRecoveryForm.vue'
import { useAuthStore } from '../../stores/auth'
import { OAUTH_TRANSACTION_PARAM, readOAuthTransaction } from './desktopOAuth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const appName = APP_NAME
const desktopOAuthTransaction = computed(() => readOAuthTransaction(route.query))
const loginTarget = computed(() => desktopOAuthTransaction.value
  ? {
      path: '/desktop-login',
      query: { [OAUTH_TRANSACTION_PARAM]: desktopOAuthTransaction.value },
    }
  : { path: '/login' })

async function completePasswordRecovery() {
  auth.clearSession()
  ElMessage.success('密码已重置，请使用新密码登录。')
  await router.replace(loginTarget.value)
}
</script>

<template>
  <main class="auth-shell">
    <AuthBrandPanel :app-name="appName" />
    <section class="auth-form-panel" aria-labelledby="forgot-password-title">
      <div class="auth-card">
        <div class="form-heading"><KeyRound :size="22" aria-hidden="true" /><div><p>账号安全</p><h2 id="forgot-password-title">找回密码</h2></div></div>
        <PasswordRecoveryForm @completed="completePasswordRecovery" />
        <router-link class="back-link" :to="loginTarget">返回登录</router-link>
      </div>
    </section>
  </main>
</template>

<style scoped>
.auth-shell { display: grid; min-width: 320px; min-height: 100dvh; grid-template-columns: minmax(360px, 40%) minmax(0, 1fr); background: var(--app-bg); }
.auth-form-panel { display: flex; min-width: 0; align-items: center; justify-content: center; padding: 36px; }
.auth-card { display: flex; width: min(448px, 100%); flex-direction: column; gap: 12px; padding: 28px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--app-radius); box-shadow: var(--app-shadow); }
.form-heading { display: flex; align-items: center; gap: 10px; color: var(--app-primary); }
.form-heading p { margin: 0 0 2px; color: var(--app-accent); font-size: 12px; font-weight: 700; }
.form-heading h2 { margin: 0; color: var(--app-text); font-size: 24px; }
.back-link { color: var(--app-primary); font-size: 13px; text-align: center; text-decoration: none; }
.back-link:hover { text-decoration: underline; }
@media (max-width: 767px) {
  .auth-shell { display: block; }
  .auth-form-panel { min-height: calc(100dvh - 72px); align-items: flex-start; padding: 28px 12px; }
  .auth-card { padding: 22px 18px; }
}
</style>
