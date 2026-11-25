<script setup lang="ts">
import { ref } from 'vue'

import HeaderBar from '../components/layout/HeaderBar.vue'
import NotificationBell from '../components/notifications/NotificationBell.vue'
import SidebarNav from '../components/layout/SidebarNav.vue'
import TerminalSessionCoordinator from '../components/auth/TerminalSessionCoordinator.vue'

const collapsed = ref(false)
const mobileNavOpen = ref(false)
</script>

<template>
  <div class="console-layout">
    <TerminalSessionCoordinator />
    <a class="skip-link" data-test="skip-main" href="#main-content">跳到主内容</a>
    <div class="desktop-sidebar">
      <SidebarNav :collapsed="collapsed" />
    </div>
    <section class="workspace">
      <div class="top-bar">
        <HeaderBar
          :collapsed="collapsed"
          @toggle-sidebar="collapsed = !collapsed"
          @open-mobile-nav="mobileNavOpen = true"
        />
        <div class="notification-tool"><NotificationBell /></div>
      </div>
      <main id="main-content" class="main-content" tabindex="-1">
        <div class="content-frame">
          <!-- Skill 管理与专家管理共用同一个视图组件；按路由名重建实例，避免切换页面时
               复用旧实例（列表作用域、筛选、抽屉状态互相串味）。 -->
          <RouterView v-slot="{ Component, route }">
            <component :is="Component" :key="route.name ?? route.path" />
          </RouterView>
        </div>
      </main>
    </section>

    <el-drawer
      v-model="mobileNavOpen"
      class="mobile-nav-drawer"
      direction="ltr"
      :show-close="false"
      :with-header="false"
      size="232px"
    >
      <SidebarNav mobile @navigate="mobileNavOpen = false" />
    </el-drawer>
  </div>
</template>

<style scoped>
.console-layout {
  display: flex;
  width: 100%;
  min-height: 100dvh;
  overflow: hidden;
  background: var(--app-bg);
}

.skip-link {
  position: fixed;
  z-index: calc(var(--app-z-drawer) + 1);
  top: 8px;
  left: 8px;
  padding: 8px 12px;
  color: #fff;
  background: var(--app-primary);
  border-radius: 6px;
  transform: translateY(calc(-100% - 12px));
  transition: transform var(--app-motion-fast) ease;
}

.skip-link:focus-visible {
  transform: translateY(0);
}

.desktop-sidebar {
  height: 100dvh;
  flex: 0 0 auto;
}

.workspace {
  display: flex;
  min-width: 0;
  height: 100dvh;
  flex: 1;
  flex-direction: column;
  overflow: hidden;
  background: transparent;
}

.top-bar {
  position: relative;
  z-index: var(--app-z-header);
  display: flex;
  min-width: 0;
  height: var(--app-header-height);
  flex: 0 0 var(--app-header-height);
  background: var(--app-surface);
  border-bottom: 1px solid var(--app-border);
}

.top-bar :deep(.header-bar) {
  min-width: 0;
  flex: 1;
}

.notification-tool {
  position: relative;
  z-index: var(--app-z-header);
  display: flex;
  width: 60px;
  flex: 0 0 60px;
  align-items: center;
  justify-content: center;
  background: var(--app-surface);
  border-left: 1px solid var(--app-border);
}

.main-content {
  min-width: 0;
  flex: 1;
  overflow: auto;
  padding: 30px 36px 44px;
  scroll-behavior: smooth;
}

.content-frame {
  width: 100%;
  max-width: var(--app-content-width);
  min-width: 0;
  margin: 0 auto;
}

@media (max-width: 767px) {
  .desktop-sidebar {
    display: none;
  }

  .main-content {
    padding: 18px 12px 28px;
    scroll-behavior: auto;
  }
}
</style>
