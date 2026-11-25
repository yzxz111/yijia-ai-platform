<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ExternalLink, QrCode } from 'lucide-vue-next'
import QRCode from 'qrcode'

import type { PaymentAction } from '../../api/platform'

const props = defineProps<{ action: PaymentAction }>()

const qrDataUrl = ref('')
const actionError = ref('')
let qrSequence = 0

function httpsTarget(targetUrl: string): string | null {
  try {
    const parsed = new URL(targetUrl)
    return parsed.protocol === 'https:' ? targetUrl : null
  } catch {
    return null
  }
}

const targetIsSecure = computed(() => {
  if (props.action.type !== 'REDIRECT_URL' && props.action.type !== 'HTML_FORM') return true
  return httpsTarget(props.action.targetUrl) !== null
})

watch(
  () => props.action,
  async (action) => {
    const sequence = ++qrSequence
    qrDataUrl.value = ''
    actionError.value = ''
    if (action.type !== 'QR_CODE') return
    try {
      const dataUrl = await QRCode.toDataURL(action.qrContent, {
        errorCorrectionLevel: 'M', margin: 1, width: 220,
      })
      if (sequence === qrSequence) qrDataUrl.value = dataUrl
    } catch {
      if (sequence === qrSequence) actionError.value = '二维码生成失败，请重试支付。'
    }
  },
  { immediate: true },
)

function openRedirect() {
  if (props.action.type !== 'REDIRECT_URL') return
  const target = httpsTarget(props.action.targetUrl)
  if (!target) {
    actionError.value = '支付链接仅支持 HTTPS，请重新发起支付。'
    return
  }
  window.open(target, '_blank', 'noopener,noreferrer')
}

function submitForm() {
  if (props.action.type !== 'HTML_FORM') return
  const target = httpsTarget(props.action.targetUrl)
  if (!target) {
    actionError.value = '支付链接仅支持 HTTPS，请重新发起支付。'
    return
  }

  const form = document.createElement('form')
  form.method = 'post'
  form.action = target
  form.target = '_blank'
  form.setAttribute('rel', 'noopener noreferrer')
  for (const [name, value] of Object.entries(props.action.fields)) {
    const input = document.createElement('input')
    input.type = 'hidden'
    input.name = name
    input.value = value
    form.appendChild(input)
  }
  document.body.appendChild(form)
  try {
    form.submit()
  } finally {
    form.remove()
  }
}
</script>

<template>
  <section class="payment-action" aria-live="polite">
    <TopErrorMessage :message="actionError" />

    <div v-if="action.type === 'QR_CODE'" class="qr-action">
      <div class="action-heading"><QrCode :size="18" aria-hidden="true" />扫码支付</div>
      <div class="qr-frame" :aria-busy="!qrDataUrl && !actionError">
        <img v-if="qrDataUrl" data-test="payment-qr" :src="qrDataUrl" alt="支付二维码" width="220" height="220" />
        <el-skeleton v-else-if="!actionError" :rows="4" animated />
      </div>
    </div>

    <el-button
      v-else-if="action.type === 'REDIRECT_URL'"
      type="primary"
      class="action-button"
      data-test="open-payment"
      :disabled="!targetIsSecure"
      @click="openRedirect"
    >
      <ExternalLink :size="16" aria-hidden="true" />打开支付页面
    </el-button>

    <el-button
      v-else-if="action.type === 'HTML_FORM'"
      type="primary"
      class="action-button"
      data-test="submit-payment-form"
      :disabled="!targetIsSecure"
      @click="submitForm"
    >
      <ExternalLink :size="16" aria-hidden="true" />前往支付
    </el-button>

    <p v-if="!targetIsSecure" class="protocol-error" role="alert">支付链接仅支持 HTTPS，请重新发起支付。</p>
    <p v-else-if="action.type === 'NONE'" class="waiting-copy">支付请求已创建，正在等待渠道确认。</p>
  </section>
</template>

<style scoped>
.payment-action { display: grid; gap: 12px; }
.qr-action { display: grid; justify-items: center; gap: 12px; }
.action-heading { display: inline-flex; align-items: center; gap: 7px; font-weight: 650; }
.qr-frame { width: 236px; min-height: 236px; display: grid; place-items: center; padding: 8px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 8px; }
.qr-frame img { display: block; width: 220px; height: 220px; }
.action-button { width: 100%; min-height: 44px; }
.protocol-error { margin: 0; color: var(--el-color-danger); line-height: 1.5; }
.waiting-copy { margin: 0; color: var(--app-muted); text-align: center; }
</style>
