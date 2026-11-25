<script setup lang="ts">
import { computed, ref } from 'vue'
import { Check, RefreshCw, ShieldCheck } from 'lucide-vue-next'

import {
  requestSliderCaptcha,
  verifySliderCaptcha,
  type SliderCaptchaChallenge,
} from '../../api/platform'

const visible = ref(false)
const loading = ref(false)
const verifying = ref(false)
const challenge = ref<SliderCaptchaChallenge | null>(null)
const offsetX = ref(0)
const errorMessage = ref('')
let generation = 0
let settle: ((proof: string | null) => void) | null = null

const maxOffset = computed(() => challenge.value
  ? challenge.value.imageWidth - challenge.value.pieceSize
  : 272)
const pieceStyle = computed(() => {
  const current = challenge.value
  if (!current) return {}
  return {
    width: `${current.pieceSize / current.imageWidth * 100}%`,
    height: `${current.pieceSize / current.imageHeight * 100}%`,
    left: `${offsetX.value / current.imageWidth * 100}%`,
    top: `${current.pieceY / current.imageHeight * 100}%`,
  }
})

function open(): Promise<string | null> {
  settle?.(null)
  const result = new Promise<string | null>((resolve) => {
    settle = resolve
  })
  visible.value = true
  void loadChallenge()
  return result
}

async function loadChallenge() {
  const currentGeneration = ++generation
  loading.value = true
  verifying.value = false
  challenge.value = null
  offsetX.value = 0
  errorMessage.value = ''
  try {
    const issued = await requestSliderCaptcha()
    if (currentGeneration === generation && visible.value) challenge.value = issued
  } catch {
    if (currentGeneration === generation) errorMessage.value = '安全验证加载失败，请刷新重试。'
  } finally {
    if (currentGeneration === generation) loading.value = false
  }
}

async function verify() {
  if (!challenge.value || loading.value || verifying.value) return
  verifying.value = true
  errorMessage.value = ''
  try {
    const verified = await verifySliderCaptcha(challenge.value.id, offsetX.value)
    const resolve = settle
    settle = null
    visible.value = false
    resolve?.(verified.captchaProof)
  } catch (error) {
    const code = (error as { response?: { data?: { code?: string } } }).response?.data?.code
    if (code === 'captcha_expired' || code === 'captcha_attempts_exhausted') {
      errorMessage.value = code === 'captcha_expired'
        ? '验证已过期，正在刷新。'
        : '尝试次数过多，正在刷新。'
      await loadChallenge()
    } else if (code === 'captcha_rate_limited') {
      errorMessage.value = '操作过于频繁，请稍后再试。'
    } else {
      offsetX.value = 0
      errorMessage.value = '拼图位置不正确，请重新拖动。'
    }
  } finally {
    verifying.value = false
  }
}

function closed() {
  generation += 1
  challenge.value = null
  loading.value = false
  verifying.value = false
  errorMessage.value = ''
  const resolve = settle
  settle = null
  resolve?.(null)
}

defineExpose({ open })
</script>

<template>
  <el-dialog
    v-model="visible"
    class="slider-captcha-dialog"
    width="min(384px, calc(100vw - 24px))"
    :close-on-click-modal="false"
    append-to-body
    @closed="closed"
  >
    <template #header>
      <div class="captcha-heading">
        <span class="captcha-heading__icon"><ShieldCheck :size="20" aria-hidden="true" /></span>
        <div>
          <strong>完成安全验证</strong>
          <p>拖动拼图块与缺口对齐</p>
        </div>
      </div>
    </template>

    <div class="captcha-body" aria-live="polite">
      <div class="captcha-picture" :class="{ 'is-loading': loading }">
        <template v-if="challenge">
          <img
            class="captcha-background"
            :src="challenge.backgroundImage"
            width="320"
            height="160"
            alt="滑块验证背景图，包含一个拼图缺口"
          />
          <img
            class="captcha-piece"
            :src="challenge.pieceImage"
            alt=""
            aria-hidden="true"
            :style="pieceStyle"
          />
        </template>
        <span v-else-if="loading" class="captcha-loading">正在生成验证图片...</span>
        <button v-else type="button" class="captcha-retry" @click="loadChallenge">重新加载</button>
      </div>

      <div class="captcha-control">
        <input
          v-model.number="offsetX"
          data-test="captcha-slider"
          type="range"
          :min="0"
          :max="maxOffset"
          :step="1"
          :disabled="!challenge || loading || verifying"
          aria-label="拖动拼图块位置"
          @keydown.enter.prevent="verify"
        />
        <span aria-hidden="true">{{ Math.round(offsetX / Math.max(1, maxOffset) * 100) }}%</span>
      </div>

      <p v-if="errorMessage" class="captcha-error" role="alert">{{ errorMessage }}</p>
    </div>

    <template #footer>
      <div class="captcha-actions">
        <el-tooltip content="刷新验证图片" placement="top">
          <button
            type="button"
            class="captcha-refresh"
            aria-label="刷新验证图片"
            :disabled="loading || verifying"
            @click="loadChallenge"
          >
            <RefreshCw :size="17" aria-hidden="true" />
          </button>
        </el-tooltip>
        <el-button @click="visible = false">取消</el-button>
        <el-button
          type="primary"
          data-test="captcha-submit"
          :loading="verifying"
          :disabled="!challenge || loading"
          @click="verify"
        >
          <Check v-if="!verifying" :size="17" aria-hidden="true" />验证
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.captcha-heading { display: flex; align-items: center; gap: 11px; }
.captcha-heading__icon { display: inline-grid; width: 40px; height: 40px; flex: 0 0 40px; place-items: center; color: var(--app-primary); background: var(--app-primary-soft); border-radius: 7px; }
.captcha-heading strong { display: block; color: var(--app-text); font-size: 16px; line-height: 1.35; }
.captcha-heading p { margin: 3px 0 0; color: var(--app-muted); font-size: 12px; }
.captcha-body { display: grid; gap: 14px; }
.captcha-picture { position: relative; width: 100%; aspect-ratio: 2 / 1; overflow: hidden; background: var(--app-surface-muted); border: 1px solid var(--app-border); border-radius: 7px; }
.captcha-picture.is-loading { display: grid; place-items: center; }
.captcha-background { display: block; width: 100%; height: 100%; object-fit: cover; user-select: none; }
.captcha-piece { position: absolute; z-index: 1; object-fit: contain; filter: drop-shadow(0 2px 3px rgb(15 23 42 / 35%)); pointer-events: none; user-select: none; }
.captcha-loading { color: var(--app-muted); font-size: 13px; }
.captcha-retry { align-self: center; min-height: 44px; padding: 0 16px; color: var(--app-primary); background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 6px; cursor: pointer; }
.captcha-control { display: grid; grid-template-columns: minmax(0, 1fr) 42px; align-items: center; gap: 10px; }
.captcha-control input { width: 100%; min-height: 44px; margin: 0; accent-color: var(--app-primary); cursor: grab; touch-action: manipulation; }
.captcha-control input:active { cursor: grabbing; }
.captcha-control input:focus-visible, .captcha-refresh:focus-visible, .captcha-retry:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }
.captcha-control span { color: var(--app-muted-strong); font-variant-numeric: tabular-nums; text-align: right; }
.captcha-error { margin: 0; color: var(--app-danger); font-size: 13px; }
.captcha-actions { display: flex; width: 100%; align-items: center; justify-content: flex-end; gap: 8px; }
.captcha-actions :deep(.el-button) { height: 44px; margin: 0; }
.captcha-refresh { display: inline-grid; width: 44px; height: 44px; flex: 0 0 44px; place-items: center; color: var(--app-primary); background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 7px; cursor: pointer; transition: color var(--app-motion-fast) ease, background-color var(--app-motion-fast) ease; }
.captcha-refresh:hover { color: var(--app-primary-hover); background: var(--app-primary-soft); }
.captcha-refresh:disabled { cursor: not-allowed; opacity: 0.45; }
@media (prefers-reduced-motion: reduce) {
  .captcha-refresh { transition: none; }
}
</style>
