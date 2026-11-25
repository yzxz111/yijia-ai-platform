<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { Bell } from 'lucide-vue-next'

import { useNotificationsStore } from '../../stores/notifications'

const notifications = useNotificationsStore()
const accessibleLabel = computed(() => notifications.unreadCount
  ? `通知中心，${notifications.unreadCount} 条未读通知`
  : '通知中心，无未读通知')

onMounted(() => notifications.startPolling())
onBeforeUnmount(() => notifications.stopPolling())
</script>

<template>
  <el-tooltip content="通知中心" placement="bottom">
    <RouterLink
      to="/app/notifications"
      class="notification-bell"
      data-test="notification-bell"
      :aria-label="accessibleLabel"
    >
      <Bell :size="19" aria-hidden="true" />
      <span
        v-if="notifications.unreadCount > 0"
        class="notification-badge"
        data-test="notification-badge"
        aria-hidden="true"
      >{{ notifications.badgeLabel }}</span>
    </RouterLink>
  </el-tooltip>
</template>

<style scoped>
.notification-bell {
  position: relative;
  display: inline-flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  color: var(--app-muted);
  border: 1px solid transparent;
  border-radius: 7px;
  text-decoration: none;
}

.notification-bell:hover {
  color: var(--app-fg);
  background: var(--app-surface-muted);
  border-color: var(--app-border);
}

.notification-badge {
  position: absolute;
  top: 2px;
  /* 右锚定：徽标向左生长，位数变多（99+）也不会越过 44px 的按钮边界。
     此前用 left: 23px + min-width，内容一宽就向右溢出（实测超出容器 3px）。 */
  right: 2px;
  display: inline-flex;
  min-width: 20px;
  height: 18px;
  align-items: center;
  justify-content: center;
  padding: 0 4px;
  color: #fff;
  background: var(--app-danger);
  border: 2px solid var(--app-surface);
  border-radius: 8px;
  font-size: 10px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 14px;
  white-space: nowrap;
}
</style>
