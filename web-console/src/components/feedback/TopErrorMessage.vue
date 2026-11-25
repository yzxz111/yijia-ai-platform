<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { watch } from 'vue'

const props = withDefaults(defineProps<{
  message?: string | null
}>(), {
  message: '',
})

watch(() => props.message?.trim(), (message) => {
  if (!message) return
  ElMessage.error({
    message,
    duration: 4_000,
    showClose: true,
    grouping: true,
  })
}, { immediate: true })
</script>

<template></template>
