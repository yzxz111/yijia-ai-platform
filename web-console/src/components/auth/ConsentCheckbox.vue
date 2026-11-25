<script setup lang="ts">
import { ref } from 'vue'

import LegalDocumentDialog from './LegalDocumentDialog.vue'
import type { LegalDocumentKind } from '../../consent'

const accepted = defineModel<boolean>({ required: true })

const termsVisible = ref(false)
const privacyVisible = ref(false)

function show(kind: LegalDocumentKind) {
  if (kind === 'TERMS') termsVisible.value = true
  else privacyVisible.value = true
}
</script>

<template>
  <div class="consent-field">
    <el-checkbox v-model="accepted" data-test="login-consent">
      我已阅读并同意
      <button type="button" class="consent-link" data-test="terms-link" @click.stop="show('TERMS')">
        《平台服务协议》
      </button>
      与
      <button type="button" class="consent-link" data-test="privacy-link" @click.stop="show('PRIVACY')">
        《隐私政策》
      </button>
    </el-checkbox>

    <LegalDocumentDialog v-model="termsVisible" kind="TERMS" />
    <LegalDocumentDialog v-model="privacyVisible" kind="PRIVACY" />
  </div>
</template>

<style scoped>
.consent-field {
  margin-top: 2px;
  line-height: 1.7;
}

.consent-field :deep(.el-checkbox) {
  height: auto;
  align-items: flex-start;
  white-space: normal;
}

.consent-field :deep(.el-checkbox__label) {
  color: var(--app-muted-strong);
  font-size: 13px;
  line-height: 1.7;
  white-space: normal;
}

.consent-field :deep(.el-checkbox__input) {
  margin-top: 3px;
}

/* 渲染为按钮，这样在复选框 label 内打开对话框时它仍然合法。 */
.consent-link {
  padding: 0;
  color: var(--app-primary);
  background: none;
  border: 0;
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  line-height: inherit;
  cursor: pointer;
}

.consent-link:hover { text-decoration: underline; }

.consent-link:focus-visible {
  outline: 2px solid var(--app-primary);
  outline-offset: 2px;
  border-radius: 3px;
}
</style>
