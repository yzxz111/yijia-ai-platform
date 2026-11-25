<script setup lang="ts">
import { computed } from 'vue'
import { ArrowLeft } from 'lucide-vue-next'

import { usePublishedLegalDocuments } from '../../composables/usePublishedLegalDocuments'

const { documents, versions } = usePublishedLegalDocuments()
const document = computed(() => documents.value.PRIVACY)
const version = computed(() => versions.value.PRIVACY)
</script>

<template>
  <main class="legal-shell">
    <article class="legal-card">
      <header class="legal-header">
        <router-link class="legal-back" to="/login">
          <ArrowLeft :size="15" aria-hidden="true" />
          <span>返回登录</span>
        </router-link>
        <h1>{{ document.title }}</h1>
        <p class="legal-document__meta" data-test="privacy-version">版本：{{ version }}</p>
      </header>

      <div class="legal-document">
        <section v-for="section in document.sections" :key="section.heading">
          <h2>{{ section.heading }}</h2>
          <p v-for="paragraph in section.paragraphs" :key="paragraph">{{ paragraph }}</p>
          <ul v-if="section.items">
            <li v-for="item in section.items" :key="item">{{ item }}</li>
          </ul>
        </section>
        <p v-if="document.placeholder" class="legal-document__placeholder" data-test="privacy-placeholder">
          {{ document.placeholder }}
        </p>
      </div>
    </article>
  </main>
</template>

<style scoped>
.legal-shell {
  display: flex;
  min-height: 100dvh;
  justify-content: center;
  padding: 40px 20px 64px;
  background: #f8fafc;
}

.legal-card {
  width: min(820px, 100%);
  height: fit-content;
  padding: 36px 40px 44px;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 14px;
  box-shadow: 0 1px 3px rgb(15 23 42 / 6%);
}

.legal-header {
  margin-bottom: 12px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--app-border);
}

.legal-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 14px;
  color: var(--app-primary);
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
}

.legal-back:hover { text-decoration: underline; }

.legal-header h1 {
  margin: 0;
  color: var(--app-fg);
  font-size: 26px;
  font-weight: 700;
}

.legal-header .legal-document__meta { margin-top: 8px; }

@media (max-width: 767px) {
  .legal-shell { padding: 20px 12px 40px; }
  .legal-card { padding: 24px 18px 32px; }
  .legal-header h1 { font-size: 22px; }
}
</style>
