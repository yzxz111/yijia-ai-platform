<script setup lang="ts">
/**
 * 独立「专家」页面（`/admin/experts`）。
 *
 * 复用 `SkillCatalogView` 的完整实现，但把类型作用域固定为 `EXPERT`：
 * - 列表只请求专家（后端 A1 支持 `kind=EXPERT`）；
 * - 新建对话框只能创建「专家」；
 * - 专家团在 `/admin/teams`，普通技能在 `/admin/skills`，三页数据互不重叠。
 */
import SkillCatalogView from './SkillCatalogView.vue'
</script>

<template>
  <SkillCatalogView kinds="EXPERT" />
</template>
