<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { defaultSkillLogoForCode } from '../../utils/skillDefaultLogos'

/**
 * 技能 / 专家 / 专家团 的图标。
 *
 * 已上传图片优先；指定技能的内置图标其次；最后回退到文本徽标。
 * 图标字节要带管理员 JWT 才能取，所以 `src` 是父层用 composable 换来的本地 objectURL；
 * 本组件只负责渲染与回退，不自己发请求 —— 这样列表里 50 张卡片不会各自打一次接口。
 *
 * 文本回退顺序与后端 `itemBadge()` 保持一致：
 * 文本徽标（`icon` 字段，可填 emoji）优先，其次分类名首字，最后问号。
 */
const props = withDefaults(
  defineProps<{
    /** 已上传图标的本地 objectURL；空串时尝试内置图标，再走文本回退。 */
    src?: string
    /** 技能代码。只在普通技能视图传入，用于选择内置默认图标。 */
    code?: string
    /** 文本徽标，通常是 `icon` 字段（可填 emoji）。 */
    badge?: string | null
    /** 文本徽标为空时的兜底单字，通常是名称首字。 */
    fallback?: string | null
    /** 尺寸档位：列表 32、详情 40、表单预览 56。 */
    size?: 'sm' | 'md' | 'lg'
    /** 无障碍：图片内容说明。装饰性图标传空串即标记为装饰。 */
    alt?: string
  }>(),
  { src: '', code: '', badge: '', fallback: '', size: 'sm', alt: '' },
)

const defaultLogo = computed(() => defaultSkillLogoForCode(props.code))
const failedSources = ref<string[]>([])
watch([() => props.src, defaultLogo], () => {
  failedSources.value = []
})

const imageSrc = computed(() => [props.src, defaultLogo.value]
  .find((candidate) => candidate && !failedSources.value.includes(candidate)) ?? '')

function onImageError(event: Event) {
  const failed = (event.currentTarget as HTMLImageElement).getAttribute('src')
  if (failed && !failedSources.value.includes(failed)) {
    failedSources.value = [...failedSources.value, failed]
  }
}
</script>

<template>
  <span class="skill-avatar" :class="`skill-avatar--${size}`">
    <img v-if="imageSrc" :src="imageSrc" :alt="alt" @error="onImageError" />
    <template v-else>{{ badge || fallback || '?' }}</template>
  </span>
</template>

<style scoped>
.skill-avatar {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-sm);
  color: var(--app-primary);
  font-weight: 700;
  line-height: 1;
}

.skill-avatar--sm {
  width: 32px;
  height: 32px;
  font-size: 16px;
}

.skill-avatar--md {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  font-size: 20px;
}

.skill-avatar--lg {
  width: 56px;
  height: 56px;
  font-size: 24px;
}

/* contain：徽标图多为方形图形，等比缩放比裁切更能保住原始比例 */
.skill-avatar img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
</style>
