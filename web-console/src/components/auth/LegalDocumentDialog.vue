<script setup lang="ts">
import { computed } from 'vue'

import { usePublishedLegalDocuments } from '../../composables/usePublishedLegalDocuments'
import type { LegalDocumentKind } from '../../consent'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ kind: LegalDocumentKind }>()

const { documents, versions } = usePublishedLegalDocuments()
const document = computed(() => documents.value[props.kind])
const version = computed(() => versions.value[props.kind])
</script>

<template>
  <el-dialog
    v-model="visible"
    class="legal-document-dialog"
    width="min(720px, calc(100vw - 24px))"
    align-center
    append-to-body
    :data-test="`legal-dialog-${kind}`"
  >
    <template #header>
      <div class="legal-document-dialog__heading">
        <strong data-test="legal-dialog-title">{{ document?.title }}</strong>
        <p class="legal-document__meta" data-test="legal-dialog-version">
          版本：{{ version }}
        </p>
      </div>
    </template>

    <article v-if="document" class="legal-document" data-test="legal-dialog-body">
      <section v-for="section in document.sections" :key="section.heading">
        <h2>{{ section.heading }}</h2>
        <p v-for="paragraph in section.paragraphs" :key="paragraph">{{ paragraph }}</p>
        <ul v-if="section.items">
          <li v-for="item in section.items" :key="item">{{ item }}</li>
        </ul>
      </section>
      <p v-if="document.placeholder" class="legal-document__placeholder">
        {{ document.placeholder }}
      </p>
    </article>

    <template #footer>
      <div class="legal-document-dialog__actions">
        <el-button data-test="legal-dialog-close" @click="visible = false">我知道了</el-button>
      </div>
    </template>
  </el-dialog>
</template>
