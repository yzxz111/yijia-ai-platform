<script setup lang="ts">
import { ref } from 'vue'
import {
  AlertTriangle,
  ArrowRight,
  BookOpen,
  Braces,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock3,
  Copy,
  FileUp,
  Gauge,
  HeadphonesIcon,
  KeyRound,
  MessageSquareText,
} from 'lucide-vue-next'
import { ElMessage } from 'element-plus'

const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim()
const apiBaseUrl = (configuredApiBaseUrl || `${window.location.origin}/v1`).replace(/\/+$/, '')
const authorizationHeader = 'Authorization: Bearer <TOKEN>'
const curlExample = `curl "${apiBaseUrl}/chat/completions" \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer <TOKEN>" \\
  -d '{
    "model": "text-basic",
    "messages": [
      {"role": "system", "content": "你是一个专业的助手"},
      {"role": "user", "content": "你好，请简单介绍一下自己"}
    ]
  }'`

const endpoints = [
  { path: '/chat/completions', label: '聊天补全', description: '使用消息数组发起兼容 OpenAI 格式的对话请求。', icon: MessageSquareText },
  { path: '/responses', label: '统一响应', description: '使用统一输入格式生成文本或结构化响应。', icon: Braces },
  { path: '/files', label: '文件接口', description: '上传并管理 API 请求所需的文件。', icon: FileUp },
]

const navigation = [
  { id: 'authentication', label: '请求认证', icon: KeyRound },
  { id: 'endpoints', label: '支持的端点', icon: MessageSquareText },
  { id: 'errors', label: 'HTTP 错误码', icon: AlertTriangle },
  { id: 'limits', label: '限流与并发', icon: Gauge },
  { id: 'tokens', label: '积分计量', icon: Braces },
  { id: 'timeouts', label: '超时设置', icon: Clock3 },
  { id: 'example', label: '请求示例', icon: Braces },
  { id: 'faq', label: '常见问题', icon: BookOpen },
]

const errorCodes = [
  { code: '400', meaning: '请求格式错误', action: '检查 JSON、字段类型和必填参数。' },
  { code: '401', meaning: '认证失败', action: '检查 Bearer 令牌是否正确、有效。' },
  { code: '403', meaning: '无访问权限', action: '确认套餐状态及功能授权范围。' },
  { code: '404', meaning: '资源不存在', action: '检查接口路径或文件 ID。' },
  { code: '409', meaning: '请求状态冲突', action: '刷新资源状态后再提交。' },
  { code: '413', meaning: '请求内容过大', action: '压缩文件或减小单次请求体积。' },
  { code: '422', meaning: '参数校验失败', action: '根据响应中的 code 和 message 修正参数。' },
  { code: '429', meaning: '请求过于频繁', action: '降低并发并按 Retry-After 退避重试。' },
  { code: '5xx', meaning: '服务暂时异常', action: '保留请求 ID，采用有上限的退避重试。' },
]

const faq = [
  {
    q: '如何计算积分消耗？为什么和我本地估算不一样？',
    a: '以响应体中的 usage 字段为准。服务端会在请求结束后汇总实际消耗的积分数（包括系统提示、用户输入、输出内容），本地按字符或通用分词器估算通常与实际有差距，请以服务端返回数据作为结算依据。',
  },
  {
    q: '接口偶尔返回 429 怎么办？',
    a: '429 表示当前请求频率超过限制。优先读取响应头 Retry-After 字段，按其建议的秒数等待后重试。没有 Retry-After 时使用指数退避（如 1s、2s、4s...）并增加随机抖动，避免瞬间再次打爆。同时建议客户端做并发队列控制。',
  },
  {
    q: '流式响应怎么处理？如何判断请求结束？',
    a: '流式接口返回 SSE（Server-Sent Events）格式，客户端需逐行读取并解析 data: {...} JSON 块。当收到 data: [DONE] 时表示流结束。即使中途断连，服务端也会按实际已处理的积分记录用量。',
  },
  {
    q: '生成内容不符合预期怎么办？',
    a: '建议从以下方向优化：1）完善 system role 设定角色和边界；2）将复杂任务拆分为多轮对话；3）使用 few-shot 示例给更多上下文；4）调整温度参数（较低温度更稳定，较高温度更多样）。',
  },
]

const openFaq = ref<number | null>(0)

async function copyText(value: string, successMessage: string, failureMessage: string) {
  let copied = false
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
      copied = true
    }
  } catch {
    copied = false
  }

  if (!copied) {
    const copyField = document.createElement('textarea')
    copyField.value = value
    copyField.setAttribute('readonly', '')
    copyField.style.position = 'fixed'
    copyField.style.opacity = '0'
    copyField.style.pointerEvents = 'none'
    document.body.appendChild(copyField)
    copyField.focus()
    copyField.select()
    try {
      copied = document.execCommand?.('copy') ?? false
    } catch {
      copied = false
    } finally {
      copyField.remove()
    }
  }

  if (copied) ElMessage.success(successMessage)
  else ElMessage.error(failureMessage)
}

function copyApiBaseUrl() {
  return copyText(apiBaseUrl, 'API Base URL 已复制', '复制失败，请手动选择并复制 API Base URL')
}

function copyCurlExample() {
  return copyText(curlExample, '请求示例已复制', '复制失败，请手动选择并复制请求示例')
}

function toggleFaq(index: number) {
  openFaq.value = openFaq.value === index ? null : index
}
</script>

<template>
  <section class="help-page" aria-labelledby="help-title">
    <!-- Page heading -->
    <header class="doc-hero">
      <div class="doc-hero-left">
        <div class="doc-hero-icon">
          <BookOpen :size="22" />
        </div>
        <div>
          <h2 id="help-title">帮助文档</h2>
          <p>开发者接入指南 · API 参考 · 常见问题</p>
        </div>
      </div>
      <div class="doc-hero-meta">
        <div class="hero-meta-item">
          <Clock3 :size="14" />
          <span>预计阅读 10 分钟</span>
        </div>
        <div class="hero-meta-item">
          <BookOpen :size="14" />
          <span>最后更新 2026-09</span>
        </div>
      </div>
    </header>

    <!-- API Base URL band -->
    <section class="base-url-band">
      <div class="base-url-left">
        <KeyRound :size="17" />
        <div>
          <span>API Base URL</span>
          <code data-test="help-api-base-url">{{ apiBaseUrl }}</code>
        </div>
      </div>
      <el-tooltip content="复制 API Base URL" placement="top">
        <button type="button" class="copy-btn" data-test="copy-help-api-base-url" aria-label="复制 API Base URL" @click="copyApiBaseUrl">
          <Copy :size="15" />复制
        </button>
      </el-tooltip>
    </section>

    <div class="help-layout">
      <!-- Side navigation -->
      <nav class="help-navigation" aria-label="帮助文档目录">
        <div class="nav-title">
          <BookOpen :size="14" />
          <span>本页目录</span>
        </div>
        <a
          v-for="item in navigation"
          :key="item.id"
          :href="`#${item.id}`"
          class="nav-link"
          @click="() => { /* smooth scroll handled by browser */ }"
        >
          <component :is="item.icon" :size="14" />
          <span>{{ item.label }}</span>
        </a>
      </nav>

      <div class="help-content">
        <!-- Authentication -->
        <section id="authentication" class="doc-section">
          <div class="section-heading-row">
            <span class="section-accent" />
            <KeyRound :size="16" />
            <h3>请求认证</h3>
          </div>
          <p class="section-desc">在每个 API 请求的请求头中携带 Bearer 令牌，占位符需替换为你的访问令牌。</p>
          <div class="code-block">
            <div class="code-lang">http</div>
            <pre><code>{{ authorizationHeader }}</code></pre>
          </div>
          <div class="tip-box">
            <AlertTriangle :size="15" />
            <span>访问令牌等同于账户凭证，切勿提交到 Git 或公开分享。如怀疑泄露请立即更换。</span>
          </div>
        </section>

        <!-- Endpoints -->
        <section id="endpoints" class="doc-section">
          <div class="section-heading-row">
            <span class="section-accent" />
            <MessageSquareText :size="16" />
            <h3>支持的端点</h3>
            <span class="section-sub">路径拼接在 API Base URL 之后</span>
          </div>
          <div class="endpoint-list">
            <article v-for="endpoint in endpoints" :key="endpoint.path" class="endpoint-card">
              <div class="endpoint-icon">
                <component :is="endpoint.icon" :size="16" />
              </div>
              <div class="endpoint-info">
                <strong>{{ endpoint.label }}</strong>
                <p>{{ endpoint.description }}</p>
              </div>
              <code class="endpoint-path">{{ endpoint.path }}</code>
            </article>
          </div>
        </section>

        <!-- Errors -->
        <section id="errors" class="doc-section">
          <div class="section-heading-row">
            <span class="section-accent accent-danger" />
            <AlertTriangle :size="16" />
            <h3>HTTP 错误码</h3>
          </div>
          <div class="error-table" role="table">
            <div class="error-header" role="row">
              <span role="columnheader">状态码</span>
              <span role="columnheader">含义</span>
              <span role="columnheader">处理建议</span>
            </div>
            <div v-for="item in errorCodes" :key="item.code" class="error-row" role="row">
              <code role="cell">{{ item.code }}</code>
              <span role="cell">{{ item.meaning }}</span>
              <span role="cell">{{ item.action }}</span>
            </div>
          </div>
        </section>

        <!-- Limits -->
        <section id="limits" class="doc-section">
          <div class="section-heading-row">
            <span class="section-accent" />
            <Gauge :size="16" />
            <h3>限流与并发</h3>
          </div>
          <ul class="tip-list">
            <li><CheckCircle2 :size="15" /><span>实际请求频率和并发上限以当前套餐及后台配置为准，同一账号下的请求共享配额。</span></li>
            <li><CheckCircle2 :size="15" /><span>达到限制时接口返回 <code>429</code>；优先读取 <code>Retry-After</code>，否则使用指数退避并增加随机抖动。</span></li>
            <li><CheckCircle2 :size="15" /><span>客户端应设置并发队列，避免无上限并发或立即重复重试。</span></li>
          </ul>
        </section>

        <!-- Tokens -->
        <section id="tokens" class="doc-section">
          <div class="section-heading-row">
            <span class="section-accent" />
            <Braces :size="16" />
            <h3>积分计量规则</h3>
          </div>
          <ul class="tip-list">
            <li><CheckCircle2 :size="15" /><span>以响应 <code>usage</code> 为准：聊天补全返回 <code>prompt_tokens</code> / <code>completion_tokens</code>，统一响应返回 <code>input_tokens</code> / <code>output_tokens</code>，两者都包含 <code>total_tokens</code>。</span></li>
            <li><CheckCircle2 :size="15" /><span>分词规则由服务端决定，本地按字符或通用分词器估算的结果不应作为结算依据。</span></li>
            <li><CheckCircle2 :size="15" /><span>流式请求在响应结束后汇总最终用量；请求中断时以服务端已确认的实际用量记录为准。</span></li>
          </ul>
        </section>

        <!-- Timeouts -->
        <section id="timeouts" class="doc-section">
          <div class="section-heading-row">
            <span class="section-accent" />
            <Clock3 :size="16" />
            <h3>超时与重试建议</h3>
          </div>
          <ul class="tip-list">
            <li><CheckCircle2 :size="15" /><span>普通文本请求建议读取超时不低于 120 秒，文件上传或长内容任务建议不低于 300 秒。</span></li>
            <li><CheckCircle2 :size="15" /><span>长响应优先使用流式接口，并分别设置连接超时与读取超时。</span></li>
            <li><CheckCircle2 :size="15" /><span>仅对网络错误、<code>429</code> 和可恢复的 <code>5xx</code> 进行有限重试；保留响应中的请求 ID 便于排查。</span></li>
          </ul>
        </section>

        <!-- Example -->
        <section id="example" class="doc-section">
          <div class="section-heading-row">
            <span class="section-accent accent-success" />
            <Braces :size="16" />
            <h3>最小请求示例</h3>
            <el-button class="copy-example-btn" data-test="copy-help-curl" @click="copyCurlExample">
              <Copy :size="14" />复制示例
            </el-button>
          </div>
          <div class="code-block dark">
            <div class="code-lang">curl</div>
            <pre><code>{{ curlExample }}</code></pre>
          </div>
        </section>

        <!-- FAQ -->
        <section id="faq" class="doc-section">
          <div class="section-heading-row">
            <span class="section-accent" />
            <BookOpen :size="16" />
            <h3>常见问题</h3>
          </div>
          <div class="faq-list">
            <div v-for="(item, idx) in faq" :key="idx" class="faq-item" :class="{ open: openFaq === idx }">
              <button class="faq-q" type="button" @click="toggleFaq(idx)">
                <span>{{ item.q }}</span>
                <ChevronDown v-if="openFaq !== idx" :size="16" />
                <ChevronRight v-else :size="16" />
              </button>
              <div class="faq-a" v-show="openFaq === idx">
                <p>{{ item.a }}</p>
              </div>
            </div>
          </div>
        </section>

        <!-- Footer -->
        <footer class="doc-footer">
          <HeadphonesIcon :size="16" />
          <span>文档未能解答你的问题？</span>
          <router-link to="/app/support-tickets">提交支持工单 <ArrowRight :size="14" /></router-link>
        </footer>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* ========== 页面 ========== */
.help-page {
  width: 100%;
  min-width: 0;
  scroll-behavior: smooth;
  padding: 0;
}

/* ========== 首屏 ========== */
.doc-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 24px 2px 18px;
}
.doc-hero-left {
  display: flex;
  align-items: center;
  gap: 14px;
}
.doc-hero-icon {
  display: inline-flex;
  width: 48px;
  height: 48px;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  background: var(--app-primary);
  color: #fff;
  box-shadow: 0 4px 14px rgba(37, 99, 235, 0.25);
}
.doc-hero-left h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
}
.doc-hero-left p {
  margin: 3px 0 0;
  font-size: 13px;
  color: var(--app-muted);
}
.doc-hero-meta {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}
.hero-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--app-muted);
}
.hero-meta-item svg { color: var(--app-primary); }

/* ========== 快速开始 ========== */
.doc-section { scroll-margin-top: 24px; }
.section-heading-row {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 6px;
}
.section-accent {
  width: 3px;
  height: 16px;
  border-radius: 2px;
  background: var(--app-primary);
  flex-shrink: 0;
}
.section-accent.accent-danger { background: var(--app-danger-text); }
.section-accent.accent-success { background: var(--app-success); }
.section-heading-row svg { color: var(--app-primary); flex-shrink: 0; }
.section-heading-row h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--app-fg);
}
.section-heading-row .section-sub {
  margin-left: auto;
  font-size: 12px;
  color: var(--app-muted);
  font-weight: 400;
}
.section-desc {
  margin: 0 0 14px 10px;
  font-size: 13px;
  color: var(--app-muted);
  line-height: 1.65;
}

/* ========== Base URL 条 ========== */
.base-url-band {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 16px;
  padding: 14px 16px;
  background: var(--app-primary-soft);
  border: 1px solid color-mix(in srgb, var(--app-primary) 20%, var(--app-border));
  border-radius: 12px;
}
.base-url-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
}
.base-url-left > svg { color: var(--app-primary); flex-shrink: 0; }
.base-url-left > div { display: grid; gap: 3px; min-width: 0; }
.base-url-left span { color: var(--app-primary); font-size: 12px; font-weight: 700; }
.base-url-left code {
  overflow-wrap: anywhere;
  color: var(--app-fg);
  font-size: 14px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.copy-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 34px;
  padding: 0 14px;
  color: #fff;
  background: var(--app-primary);
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 160ms ease;
}
.copy-btn:hover { background: color-mix(in srgb, var(--app-primary) 88%, black); }

/* ========== 布局 ========== */
.help-layout {
  display: grid;
  min-width: 0;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 28px;
  padding-top: 24px;
}

/* Nav */
.help-navigation {
  position: sticky;
  top: 16px;
  display: flex;
  height: max-content;
  flex-direction: column;
  gap: 2px;
  padding: 14px 10px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
}
.nav-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 8px 8px;
  border-bottom: 1px solid var(--app-border);
  margin-bottom: 4px;
  font-size: 12px;
  font-weight: 700;
  color: var(--app-muted-strong);
}
.nav-title svg { color: var(--app-primary); }
.nav-link {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  color: var(--app-muted-strong);
  border-radius: 8px;
  text-decoration: none;
  font-size: 13px;
  transition: color 140ms ease, background-color 140ms ease;
}
.nav-link:hover {
  color: var(--app-primary);
  background: var(--app-primary-soft);
}
.nav-link svg { color: inherit; flex-shrink: 0; }

/* 正文 */
.help-content {
  min-width: 0;
  display: grid;
  gap: 24px;
}

/* ========== 代码块 ========== */
.code-block {
  position: relative;
  margin: 0;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  overflow: hidden;
}
.code-block.dark { border-color: var(--app-fg); }
.code-lang {
  position: absolute;
  top: 10px;
  right: 12px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  color: var(--app-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.code-block.dark .code-lang { color: var(--app-muted-strong); }
pre {
  margin: 0;
  padding: 16px 18px;
  overflow-x: auto;
  background: var(--app-bg);
  border-radius: 10px;
  line-height: 1.65;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  color: var(--app-fg);
  white-space: pre-wrap;
  word-break: break-word;
}
.code-block.dark pre {
  background: var(--app-fg);
  color: var(--app-border);
}

/* ========== 提示框 ========== */
.tip-box {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 10px;
  padding: 10px 12px;
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.2);
  border-radius: 8px;
  font-size: 12px;
  color: var(--app-warning-text);
  line-height: 1.6;
}
.tip-box svg { color: var(--app-accent); flex-shrink: 0; margin-top: 1px; }

/* ========== 接口地址 ========== */
.endpoint-list { display: grid; gap: 8px; }
.endpoint-card {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 12px 14px;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: var(--app-surface);
  transition: border-color 160ms ease, background-color 160ms ease;
}
.endpoint-card:hover {
  border-color: color-mix(in srgb, var(--app-primary) 30%, var(--app-border));
  background: var(--app-primary-soft);
}
.endpoint-icon {
  display: inline-flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: rgba(37, 99, 235, 0.08);
  color: var(--app-primary);
  flex-shrink: 0;
}
.endpoint-info strong { display: block; font-size: 13px; font-weight: 600; }
.endpoint-info p { margin: 2px 0 0; font-size: 12px; color: var(--app-muted); line-height: 1.5; }
.endpoint-path {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--app-primary);
  font-weight: 600;
  font-size: 13px;
}

/* ========== 错误码表 ========== */
.error-table {
  border: 1px solid var(--app-border);
  border-radius: 10px;
  overflow: hidden;
}
.error-header {
  display: grid;
  grid-template-columns: 110px minmax(120px, 0.8fr) minmax(220px, 1.6fr);
  gap: 12px;
  padding: 10px 16px;
  background: var(--app-surface-subtle);
  font-size: 12px;
  font-weight: 700;
  color: var(--app-muted-strong);
  border-bottom: 1px solid var(--app-border);
}
.error-row {
  display: grid;
  grid-template-columns: 110px minmax(120px, 0.8fr) minmax(220px, 1.6fr);
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--app-border);
  font-size: 13px;
}
.error-row:last-child { border-bottom: none; }
.error-row code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--app-primary);
  font-weight: 600;
}
.error-row > span:nth-child(2) { color: var(--app-fg); font-weight: 500; }
.error-row > span:nth-child(3) { color: var(--app-muted); line-height: 1.6; }

/* ========== 提示列表 ========== */
.tip-list {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.tip-list li {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 14px;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: 10px;
  font-size: 13px;
  color: var(--app-muted-strong);
  line-height: 1.7;
}
.tip-list li > svg {
  flex-shrink: 0;
  color: var(--app-success);
  margin-top: 2px;
}
.tip-list code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--app-primary);
  background: rgba(37, 99, 235, 0.08);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 12px;
}

/* ========== 复制示例按钮 ========== */
.copy-example-btn {
  display: inline-flex !important;
  align-items: center !important;
  gap: 5px;
  margin-left: auto;
  height: 30px !important;
  padding: 0 12px !important;
  font-size: 12px !important;
}

/* ========== 常见问题 ========== */
.faq-list { display: grid; gap: 8px; }
.faq-item {
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: var(--app-surface);
  overflow: hidden;
}
.faq-item.open {
  border-color: color-mix(in srgb, var(--app-primary) 35%, var(--app-border));
  }
.faq-q {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  background: transparent;
  border: none;
  cursor: pointer;
  text-align: left;
  font-size: 14px;
  font-weight: 600;
  color: var(--app-fg);
  transition: color 140ms ease, background-color 140ms ease;
}
.faq-q:hover { background: var(--app-primary-soft); color: var(--app-primary); }
.faq-q svg { color: var(--app-muted); flex-shrink: 0; }
.faq-item.open .faq-q svg { color: var(--app-primary); }
.faq-a {
  padding: 0 16px 14px;
  font-size: 13px;
  color: var(--app-muted-strong);
  line-height: 1.7;
}
.faq-a p { margin: 0; }

/* ========== 页脚 ========== */
.doc-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 28px 20px;
  margin-top: 16px;
  border-top: 1px solid var(--app-border);
  font-size: 13px;
  color: var(--app-muted);
}
.doc-footer svg { color: var(--app-primary); }
.doc-footer a {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--app-primary);
  font-weight: 600;
  text-decoration: none;
  transition: color 140ms ease;
}
.doc-footer a:hover { color: color-mix(in srgb, var(--app-primary) 80%, black); }

/* ========== 响应式 ========== */
@media (max-width: 860px) {
  .help-layout { grid-template-columns: minmax(0, 1fr); gap: 18px; }
  .help-navigation {
    position: static;
    flex-direction: row;
    overflow-x: auto;
    flex-wrap: wrap;
  }
  .nav-title { display: none; }
  .nav-link { flex: 0 0 auto; white-space: nowrap; }
  .error-header, .error-row {
    grid-template-columns: 90px minmax(0, 1fr);
    gap: 6px 12px;
  }
  .error-row > span:nth-child(3), .error-header > span:nth-child(3) {
    grid-column: 2;
  }
  .endpoint-card { grid-template-columns: 32px minmax(0, 1fr); }
  .endpoint-path { grid-column: 2; }
  .doc-hero { flex-direction: column; align-items: flex-start; }
}
@media (max-width: 560px) {
  .base-url-band { flex-direction: column; align-items: flex-start; gap: 12px; }
  .doc-hero-icon { width: 40px; height: 40px; border-radius: 12px; }
  .doc-hero-left h2 { font-size: 19px; }
}
</style>
