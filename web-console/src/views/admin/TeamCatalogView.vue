<script setup lang="ts">
/**
 * 独立「专家团」页面（`/admin/teams`）。
 *
 * 复用 `SkillCatalogView` 的完整实现，但把类型作用域固定为 `TEAM`：
 * - 列表只请求专家团（后端 A1 支持 `kind=TEAM`）；
 * - 新建对话框只能创建「专家团」；
 * - 专家团成员来自已上架的专家（`TEAM_MEMBER` → `EXPERT`），专家团不能再作为成员。
 */
import SkillCatalogView from './SkillCatalogView.vue'
</script>

<template>
  <SkillCatalogView kinds="TEAM" />
</template>
