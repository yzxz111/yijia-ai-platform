<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChevronDown, LogOut, Menu, Moon, PanelLeftClose, PanelLeftOpen, Sun, UserRound } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'

import { getProfile, logout as logoutSession } from '../../api/platform'
import { useAuthStore } from '../../stores/auth'
import { useThemeStore } from '../../stores/theme'
import { syncProfileAvatar } from '../../utils/profileAvatar'

defineProps<{ collapsed: boolean }>()

defineEmits<{
  openMobileNav: []
  toggleSidebar: []
}>()

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const theme = useThemeStore()
const title = computed(() => String(route.meta.title || '控制台'))
const identityLabel = computed(() => {
  if (!auth.user) return '未登录'
  return auth.user.displayName?.trim() || auth.user.email || '用户'
})
const avatarUrl = computed(() => auth.avatarObjectUrl || '')
const loggingOut = ref(false)
const profileLoading = ref(false)

async function loadAvatar() {
  if (!auth.user || profileLoading.value) return
  profileLoading.value = true
  try {
    const profile = await getProfile()
    await syncProfileAvatar(profile)
  } catch {
    // 资料请求不可用时，仍保留默认图标。
  } finally {
    profileLoading.value = false
  }
}

function handleIdentityCommand(command: string) {
  if (command === 'profile') void router.push('/app/profile')
  if (command === 'theme') theme.toggle()
  if (command === 'logout') void logout()
}

async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await logoutSession()
    auth.clearSession()
    await router.replace('/login')
  } catch {
    ElMessage.error('退出登录失败，请重试。')
  } finally {
    loggingOut.value = false
  }
}

onMounted(() => { void loadAvatar() })
watch(() => auth.user?.id, () => { void loadAvatar() })
</script>

<template>
  <header class="header-bar">
    <button
      type="button"
      class="icon-button mobile-menu"
      data-test="open-mobile-nav"
      aria-label="打开导航"
      @click="$emit('openMobileNav')"
    >
      <Menu :size="19" aria-hidden="true" />
    </button>
    <button
      type="button"
      class="icon-button desktop-collapse"
      :aria-label="collapsed ? '展开侧栏' : '折叠侧栏'"
      @click="$emit('toggleSidebar')"
    >
      <PanelLeftOpen v-if="collapsed" :size="19" aria-hidden="true" />
      <PanelLeftClose v-else :size="19" aria-hidden="true" />
    </button>
    <span class="header-context">{{ auth.isAdmin ? '管理空间' : '个人空间' }}</span><span class="breadcrumb-separator" aria-hidden="true">/</span>
    <h1>{{ title }}</h1>
    <div class="header-spacer" />
    <button type="button" class="icon-button theme-button" :aria-label="theme.theme === 'dark' ? '切换日间模式' : '切换夜间模式'" @click="theme.toggle()">
      <Sun v-if="theme.theme === 'dark'" :size="18" aria-hidden="true" /><Moon v-else :size="18" aria-hidden="true" />
    </button>
    <el-dropdown trigger="click" placement="bottom-end" @command="handleIdentityCommand">
      <button type="button" class="identity" aria-label="打开用户菜单" aria-haspopup="menu">
        <img v-if="avatarUrl" :src="avatarUrl" class="avatar avatar-image" alt="用户头像" />
        <span v-else class="avatar"><UserRound :size="17" aria-hidden="true" /></span>
        <span class="identity-copy">
          <strong>{{ identityLabel }}</strong>
          <small>{{ auth.isAdmin ? '管理员' : '用户' }}</small>
        </span>
        <ChevronDown class="identity-chevron" :size="15" aria-hidden="true" />
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="profile"><UserRound :size="16" aria-hidden="true" />个人中心</el-dropdown-item>
          <el-dropdown-item command="theme">
            <Sun v-if="theme.theme === 'dark'" :size="16" aria-hidden="true" />
            <Moon v-else :size="16" aria-hidden="true" />
            {{ theme.theme === 'dark' ? '切换日间模式' : '切换夜间模式' }}
          </el-dropdown-item>
          <el-dropdown-item command="logout" divided :disabled="loggingOut" data-test="logout"><LogOut :size="16" aria-hidden="true" />退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </header>
</template>

<style scoped>
.header-bar {
  position: relative;
  z-index: var(--app-z-header);
  display: flex;
  height: var(--app-header-height);
  flex: 0 0 var(--app-header-height);
  align-items: center;
  gap: 8px;
  padding: 0 16px 0 14px;
  background: var(--app-surface);
}

.header-bar h1 {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-context { color: var(--app-muted); font-size: 12px; white-space: nowrap; }
.breadcrumb-separator { padding: 0 5px; color: var(--app-border-strong); }
.theme-button { margin-right: 10px; }

.header-spacer {
  flex: 1;
}

.icon-button {
  display: inline-flex;
  width: 40px;
  height: 40px;
  align-items: center;
  justify-content: center;
  color: var(--app-muted);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 7px;
  cursor: pointer;
  transition: color var(--app-motion-fast) ease,
    background-color var(--app-motion-fast) ease,
    border-color var(--app-motion-fast) ease;
}

.icon-button:hover {
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border-color: var(--app-border-strong);
}

.mobile-menu {
  display: none;
}

.identity {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 4px 5px;
  color: var(--app-muted);
  background: transparent;
  border: none;
  border-radius: 7px;
  cursor: pointer;
  font: inherit;
  text-align: left;
  text-decoration: none;
  transition: background var(--app-motion-fast) ease, color var(--app-motion-fast) ease;
}

.identity:hover {
  background: var(--app-surface-muted);
}

.identity:focus-visible {
  outline: 2px solid var(--app-primary);
  outline-offset: 2px;
}

.avatar {
  display: inline-flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: 50%;
  font-weight: 700;
}

.avatar-image {
  object-fit: cover;
}

.identity-chevron {
  color: var(--app-muted);
  transition: transform var(--app-motion-fast) ease;
}

.identity-copy strong,
.identity-copy small {
  display: block;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.identity-copy strong {
  color: var(--app-fg);
  font-size: 13px;
}

.identity-copy small {
  font-size: 12px;
}

.identity :deep(svg) { flex: 0 0 auto; }

:global([data-theme="dark"]) .identity:hover,
:global(html.dark) .identity:hover {
  background: var(--app-surface-muted);
}

@media (max-width: 767px) {
  .header-bar {
    padding-inline: 10px 14px;
  }

  .mobile-menu {
    display: inline-flex;
    width: 44px;
    height: 44px;
  }

  .header-context,
  .breadcrumb-separator,
  .desktop-collapse,
  .identity-copy {
    display: none;
  }

  .header-bar h1 {
    font-size: 16px;
  }

  .identity {
    padding: 4px;
    background: transparent;
    border-color: transparent;
  }

  .identity-chevron { display: none; }
}
</style>
