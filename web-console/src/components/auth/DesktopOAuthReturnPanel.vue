<script setup lang="ts">
import { CircleCheckBig, MonitorUp } from 'lucide-vue-next'

defineProps<{
  error?: string
  callbackUrl: string
}>()
</script>

<template>
  <section class="desktop-return" data-test="oauth-return" aria-labelledby="desktop-return-title">
    <span class="desktop-return-icon" aria-hidden="true">
      <CircleCheckBig :size="28" />
    </span>
    <p class="desktop-return-label">桌面端登录</p>
    <h2 id="desktop-return-title">网页授权已完成</h2>
    <p class="desktop-return-summary">正在尝试打开一加AI桌面端，请在桌面端继续。</p>
    <a
      :href="callbackUrl"
      target="_self"
      class="desktop-return-button"
      data-test="oauth-open-desktop"
    >
      <MonitorUp :size="18" aria-hidden="true" />
      打开桌面端
    </a>
    <p class="desktop-return-tip">如果没有自动打开，请点击上方按钮重试。</p>
    <p v-if="error" class="desktop-return-error" data-test="oauth-return-error" role="alert">
      {{ error }}
    </p>
  </section>
</template>

<style scoped>
.desktop-return {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 12px;
  text-align: center;
}

.desktop-return-icon {
  display: inline-flex;
  width: 58px;
  height: 58px;
  align-self: center;
  align-items: center;
  justify-content: center;
  color: var(--app-success, #15803d);
  background: #f0fdf4;
  border-radius: 50%;
}

.desktop-return-label {
  margin: 2px 0 -6px;
  color: var(--app-primary);
  font-size: 12px;
  font-weight: 700;
}

.desktop-return h2 {
  margin: 0;
  color: var(--app-fg);
  font-size: 26px;
}

.desktop-return-summary,
.desktop-return-tip,
.desktop-return-error {
  margin: 0;
  line-height: 1.6;
}

.desktop-return-summary {
  color: var(--app-muted-strong);
  font-size: 14px;
}

.desktop-return-button {
  display: inline-flex;
  width: 100%;
  height: 46px;
  margin-top: 6px;
  box-sizing: border-box;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #fff;
  background: var(--app-primary);
  border: 1px solid var(--app-primary);
  border-radius: 8px;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
  transition: background-color var(--app-motion-fast) ease,
    border-color var(--app-motion-fast) ease,
    box-shadow var(--app-motion-fast) ease;
}

.desktop-return-button:hover {
  background: var(--app-primary-hover);
  border-color: var(--app-primary-hover);
}

.desktop-return-button:focus-visible {
  outline: 0;
  box-shadow: 0 0 0 3px rgb(37 99 235 / 20%);
}

.desktop-return-tip {
  color: var(--app-muted);
  font-size: 12px;
}

.desktop-return-error {
  padding: 10px 12px;
  color: var(--app-danger-text);
  background: var(--app-danger-soft);
  border-radius: 8px;
  font-size: 13px;
}
</style>
