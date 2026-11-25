<script setup lang="ts">
import { CircleCheck, ShieldCheck, BarChart3 } from 'lucide-vue-next'
import logoImg from '../../assets/logo.svg'

defineProps<{ appName: string }>()
const capabilities = [
  { icon: ShieldCheck, text: '统一管理模型接入' },
  { icon: CircleCheck, text: '集中维护 API 密钥' },
  { icon: BarChart3, text: '查看调用与账单' },
]
const summaries = [
  { value: '统一接入', label: '一个入口，多种模型' },
  { value: '用量可视', label: '每次调用，清晰可查' },
  { value: '权限可控', label: '按需配置，安心使用' },
]
</script>

<template>
  <section class="auth-brand-panel" aria-labelledby="auth-brand-title">
    <div class="brand-lockup">
      <img :src="logoImg" alt="" class="brand-logo-img" />
      <span class="brand-copy">
        <strong id="auth-brand-title" class="brand-name">{{ appName }}</strong>
        <small class="brand-sub">YIJIA · AI PLATFORM</small>
      </span>
    </div>

    <div class="brand-content">
      <span class="brand-tag"><span aria-hidden="true" /> 为创造，连接更多可能</span>
      <h1 class="brand-heading">让 AI 能力，<br /><span>触手可及。</span></h1>
      <p class="brand-description">把模型接入、调用与用量管理交给一加。<br />专注你的想法，让每一份创造轻松发生。</p>

      <svg class="gateway-illustration" viewBox="0 0 460 176" role="img" aria-label="文本、图像与音视频通过统一网关连接你的应用">
        <g fill="none" stroke="#43644f" stroke-width="1.5">
          <path d="M116 36H146Q164 36 164 54V72Q164 88 180 88H200" />
          <path d="M116 88H200" /><path d="M116 140H146Q164 140 164 122V104Q164 88 180 88H200" />
          <path d="M260 88H326" stroke="#87dcb0" stroke-dasharray="4 5" />
        </g>
        <g fill="#20362a" stroke="#38533f">
          <rect x="0.5" y="17" width="115" height="38" rx="10" /><rect x="0.5" y="69" width="115" height="38" rx="10" />
          <rect x="0.5" y="121" width="115" height="38" rx="10" /><rect x="326" y="62" width="133" height="52" rx="13" />
        </g>
        <g fill="#c8ddcb" font-size="13" font-family="system-ui, sans-serif" text-anchor="middle">
          <text x="58" y="41">文本对话</text><text x="58" y="93">图像生成</text><text x="58" y="145">音频 / 视频</text>
          <text x="392" y="93" fill="#e8f5ea">你的应用</text>
        </g>
        <rect x="194" y="52" width="72" height="72" rx="22" fill="#304d38" stroke="#6ba77d" />
        <image :href="logoImg" x="206" y="64" width="48" height="48" />
        <circle cx="293" cy="88" r="4" fill="#9aefca" />
        <text x="230" y="151" fill="#8cae92" font-size="11" font-family="system-ui, sans-serif" text-anchor="middle">统一模型网关</text>
      </svg>

      <ul class="brand-features" aria-label="平台能力">
        <li v-for="cap in capabilities" :key="cap.text" class="auth-feature">
          <component :is="cap.icon" :size="15" aria-hidden="true" /><span>{{ cap.text }}</span>
        </li>
      </ul>
    </div>

    <div class="brand-bottom">
      <dl class="brand-stats">
        <div v-for="s in summaries" :key="s.label" class="auth-summary-item">
          <dt class="brand-stat-value">{{ s.value }}</dt><dd class="brand-stat-label">{{ s.label }}</dd>
        </div>
      </dl>
      <div v-if="$slots.footer" class="brand-footer"><slot name="footer" /></div>
    </div>
  </section>
</template>

<style scoped>
.auth-brand-panel {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 100dvh;
  flex-direction: column;
  justify-content: space-between;
  gap: 40px;
  overflow: hidden;
  padding: clamp(32px, 4.5vw, 72px);
  color: #d4e3d5;
  background: #15291d;
  background-image: radial-gradient(ellipse at 95% 5%, #416b3d66, transparent 55%);
}
.auth-brand-panel::after {
  position: absolute;
  right: -180px;
  bottom: -280px;
  width: 620px;
  height: 620px;
  border: 1px solid #8caf8420;
  border-radius: 50%;
  box-shadow: 0 0 0 60px #8caf8408, 0 0 0 120px #8caf8406;
  content: '';
  pointer-events: none;
}
.brand-lockup, .brand-content, .brand-bottom { position: relative; z-index: 1; }
.brand-lockup { display: flex; align-items: center; gap: 12px; }
.brand-logo-img { width: 44px; height: 44px; flex: 0 0 44px; }
.brand-copy { display: grid; gap: 3px; }
.brand-name { color: #f2f8ef; font-size: 21px; font-weight: 750; letter-spacing: .4px; }
.brand-sub { color: #a2baa5; font-size: 10px; letter-spacing: 2px; }
.brand-content { width: min(500px, 100%); padding-block: 12px; }
.brand-tag { display: inline-flex; align-items: center; gap: 8px; color: #bdd5bc; font-size: 12px; letter-spacing: 1.2px; }
.brand-tag > span { width: 6px; height: 6px; border-radius: 50%; background: #b9e9a7; }
.brand-heading { margin: 24px 0 0; color: #f5f8f1; font-size: clamp(36px, 4vw, 58px); font-weight: 650; line-height: 1.3; letter-spacing: -1.5px; }
.brand-heading span { color: #bce9a8; }
.brand-description { margin: 22px 0 0; color: #afc5b3; font-size: 14px; line-height: 1.95; }
.gateway-illustration { display: block; width: min(460px, 100%); height: auto; margin: 28px 0 18px; }
.brand-features { display: flex; flex-wrap: wrap; gap: 10px 20px; margin: 0; padding: 0; list-style: none; }
.auth-feature { display: flex; align-items: center; gap: 7px; color: #c0d5c3; font-size: 11px; }
.auth-feature svg { color: #97cca2; flex-shrink: 0; }
.brand-bottom { width: min(500px, 100%); }
.brand-stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin: 0; padding-top: 24px; border-top: 1px solid #b9dab52b; }
.brand-stat-value { color: #e2eedb; font-size: 16px; font-weight: 600; }
.brand-stat-label { margin: 6px 0 0; color: #a3b9a7; font-size: 10px; line-height: 1.6; }
.brand-footer { margin-top: 14px; }
.brand-footer :deep(a) { display: inline-flex; min-height: 44px; align-items: center; color: #bce9a8; text-decoration: none; }
.brand-footer :deep(a:hover) { color: #fff; }
@media (max-width: 1100px) {
  .auth-brand-panel { padding: 32px; gap: 28px; }
  .brand-features { gap: 10px; }
}
@media (max-width: 767px) {
  .auth-brand-panel { min-height: 84px; max-height: 84px; justify-content: center; padding: 16px 24px; }
  .brand-content, .brand-bottom { display: none; }
  .brand-logo-img { width: 38px; height: 38px; flex-basis: 38px; }
  .brand-name { font-size: 18px; }
}
</style>
