<script setup lang="ts">
import { APP_NAME } from '../../brand'
import { computed, type Component } from 'vue'
import { useRoute } from 'vue-router'
import {
  Activity,
  Banknote,
  ChartNoAxesCombined,
  BookOpenCheck,
  BookOpenText,
  Boxes,
  CircleDollarSign,
  ClipboardList,
  ClipboardCheck,
  FileClock,
  FileText,
  Gauge,
  KeyRound,
  LayoutDashboard,
  Bell,
  LifeBuoy,
  PackageCheck,
  ReceiptText,
  Repeat,
  ScrollText,
  ServerCog,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  UserRound,
  UserRoundSearch,
  UsersRound,
  Waypoints,
  WalletCards,
  Zap,
} from 'lucide-vue-next'

import { useAuthStore } from '../../stores/auth'
import logoImg from '../../assets/logo.svg'

const props = withDefaults(defineProps<{
  collapsed?: boolean
  mobile?: boolean
}>(), {
  collapsed: false,
  mobile: false,
})

const emit = defineEmits<{
  navigate: []
}>()

const route = useRoute()
const auth = useAuthStore()
const appName = APP_NAME

interface NavItem {
  id: string
  label: string
  path: string
  icon: Component
  emphasis?: boolean
}

interface NavSection {
  id: string
  label: string
  items: NavItem[]
}

const userSections: NavSection[] = [
  { id: 'overview', label: '概览', items: [
    { id: 'dashboard', label: '工作台', path: '/app/dashboard', icon: LayoutDashboard },
  ] },
  { id: 'business', label: '业务管理', items: [
    { id: 'plans', label: '套餐管理', path: '/app/plans', icon: PackageCheck },
    { id: 'orders', label: '订单记录', path: '/app/orders', icon: ReceiptText },
    { id: 'subscriptions', label: '我的订阅', path: '/app/subscriptions', icon: Repeat },
    { id: 'wallet', label: '余额钱包', path: '/app/wallet', icon: WalletCards },
    { id: 'api-keys', label: 'API 密钥', path: '/app/api-keys', icon: KeyRound },
    { id: 'quota', label: '积分额度', path: '/app/quota', icon: Gauge },
    { id: 'usage', label: '调用日志', path: '/app/usage', icon: Activity },
  ] },
  { id: 'account-security', label: '账户与安全', items: [
    { id: 'profile', label: '个人中心', path: '/app/profile', icon: UserRound },
    { id: 'notifications', label: '通知中心', path: '/app/notifications', icon: Bell },
    { id: 'safety-appeals', label: '安全申诉', path: '/app/safety-appeals', icon: ShieldCheck, emphasis: true },
  ] },
  { id: 'support', label: '服务支持', items: [
    { id: 'support-tickets', label: '支持工单', path: '/app/support-tickets', icon: LifeBuoy },
    { id: 'help', label: '帮助文档', path: '/app/help', icon: BookOpenText },
  ] },
]

const adminSections: NavSection[] = [
  { id: 'admin-overview', label: '运营分析', items: [
    { id: 'admin-overview', label: '运营概览', path: '/admin/overview', icon: LayoutDashboard },
    { id: 'admin-operating-reports', label: '经营报表', path: '/admin/operating-reports', icon: ChartNoAxesCombined },
    { id: 'admin-settlement-deficit', label: '结算垫付', path: '/admin/settlement-deficit', icon: Banknote },
  ] },
  { id: 'admin-product', label: '用户与产品', items: [
    { id: 'admin-users', label: '用户管理', path: '/admin/users', icon: UsersRound },
    { id: 'admin-grants', label: '套餐发放', path: '/admin/grants', icon: PackageCheck },
    { id: 'admin-plans', label: '套餐版本', path: '/admin/plans', icon: Boxes },
    { id: 'admin-models', label: '模型管理', path: '/admin/models', icon: Waypoints },
    { id: 'admin-skills', label: 'Skill 管理', path: '/admin/skills', icon: Sparkles },
    { id: 'admin-experts', label: '专家管理', path: '/admin/experts', icon: UserRoundSearch },
    { id: 'admin-teams', label: '专家团管理', path: '/admin/teams', icon: UsersRound },
  ] },
  { id: 'admin-commerce', label: '交易与用量', items: [
    { id: 'admin-usage', label: '调用与额度', path: '/admin/usage', icon: ClipboardList },
    { id: 'admin-orders', label: '订单与支付', path: '/admin/orders', icon: CircleDollarSign },
    { id: 'admin-wallet', label: '钱包管理', path: '/admin/wallet', icon: WalletCards },
    { id: 'admin-subscriptions', label: '订阅治理', path: '/admin/subscriptions', icon: Repeat },
  ] },
  { id: 'admin-governance', label: '治理与运维', items: [
    { id: 'admin-communications', label: '通知与工单', path: '/admin/communications', icon: Bell },
    { id: 'admin-legal-documents', label: '协议管理', path: '/admin/legal-documents', icon: FileText },
    { id: 'admin-content-safety', label: '内容安全', path: '/admin/content-safety', icon: ClipboardCheck },
    { id: 'admin-operations', label: '系统运维', path: '/admin/operations', icon: ServerCog },
    { id: 'admin-audit', label: '审计日志', path: '/admin/audit', icon: ScrollText },
  ] },
]

const restrictedSections: NavSection[] = [
  { id: 'restricted-account', label: '账号服务', items: [
    { id: 'account-restricted', label: '账号状态', path: '/account-restricted', icon: ShieldAlert },
    { id: 'safety-appeals', label: '安全申诉', path: '/app/safety-appeals', icon: ShieldCheck, emphasis: true },
    { id: 'support-tickets', label: '支持工单', path: '/app/support-tickets', icon: LifeBuoy },
  ] },
]

const restricted = computed(() => auth.isRestricted)
const sections = computed(() => restricted.value
  ? restrictedSections
  : auth.isAdmin ? adminSections : userSections)

</script>

<template>
  <aside
    class="sidebar-nav"
    :class="{ 'is-collapsed': collapsed, 'is-mobile': mobile }"
    aria-label="主导航"
  >
    <div class="brand-row">
      <img :src="logoImg" alt="" class="brand-mark-img" />
      <span v-if="!collapsed" class="brand-copy">
        <strong>{{ appName }}</strong>
        <small>{{ auth.isAdmin ? '管理控制台' : '用户控制台' }}</small>
      </span>
    </div>

    <RouterLink
      v-if="!collapsed"
      :to="restricted ? '/account-restricted' : '/app/profile'"
      class="role-label"
      aria-label="个人中心"
      @click="emit('navigate')"
    >
      <ShieldAlert v-if="restricted" :size="14" />
      <ShieldCheck v-else-if="auth.isAdmin" :size="14" />
      <BookOpenCheck v-else :size="14" />
      {{ restricted ? '账号受限' : auth.isAdmin ? '管理员' : '用户' }}
    </RouterLink>

    <nav class="nav-items">
      <section
        v-for="section in sections"
        :key="section.id"
        class="nav-section"
        :data-test="`nav-section-${section.id}`"
      >
        <span v-if="!collapsed" class="nav-section-label">{{ section.label }}</span>
        <el-tooltip
          v-for="item in section.items"
          :key="item.id"
          :content="item.label"
          placement="right"
          :disabled="!collapsed"
        >
          <RouterLink
            :to="item.path"
            class="nav-item"
            :class="{ active: route.path === item.path, 'is-emphasis': item.emphasis }"
            :aria-label="item.label"
            :aria-current="route.path === item.path ? 'page' : undefined"
            :data-test="`${mobile ? 'nav' : 'desktop-nav'}-${item.id}`"
            @click="emit('navigate')"
          >
            <component :is="item.icon" :size="18" aria-hidden="true" />
            <span v-if="!collapsed">{{ item.label }}</span>
          </RouterLink>
        </el-tooltip>
      </section>
    </nav>
    <div v-if="!collapsed" class="sidebar-footer"><Zap :size="15" aria-hidden="true" /><span>让创造，多一种可能</span><small>YIJIA AI</small></div>
  </aside>
</template>

<style scoped>
.sidebar-nav {
  display: flex;
  width: var(--app-sidebar-width);
  min-width: var(--app-sidebar-width);
  height: 100%;
  flex-direction: column;
  overflow: hidden;
  color: #e5efe6;
  background: var(--app-sidebar);
  border-right: 1px solid var(--app-sidebar-border);
  transition: width var(--app-motion-base) ease, min-width var(--app-motion-base) ease;
}

.sidebar-nav.is-collapsed {
  width: var(--app-sidebar-collapsed-width);
  min-width: var(--app-sidebar-collapsed-width);
}

.brand-row {
  display: flex;
  min-height: 88px;
  align-items: center;
  flex-shrink: 0;
  gap: 11px;
  padding: 18px;
}

.brand-mark-img {
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  object-fit: contain;
}

.brand-copy {
  min-width: 0;
}

.brand-copy strong,
.brand-copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.brand-copy strong {
  max-width: 160px;
  color: #eef6ed;
  font-size: 17px;
  font-weight: 650;
  letter-spacing: .3px;
}

.brand-copy small {
  margin-top: 3px;
  color: #9bb3a0;
  font-size: 10px;
  letter-spacing: 1.2px;
}

.role-label {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 16px 15px;
  padding: 10px 12px;
  color: #d6e5d7;
  background: #ffffff08;
  border: 1px solid #ffffff12;
  border-radius: 9px;
  cursor: pointer;
  font-size: 12px;
  text-decoration: none;
  transition: background var(--app-motion-fast) ease;
}

.role-label:hover {
  color: #c0f3d3;
  background: #ffffff0f;
  border-color: #ffffff29;
}

.role-label:focus-visible {
  outline: 2px solid var(--app-primary);
  outline-offset: 2px;
}

.nav-items {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 20px;
  overflow-y: auto;
  padding: 0 12px 22px;
  scrollbar-color: #49604f transparent;
}

.is-collapsed .nav-items {
  height: calc(100% - var(--app-header-height));
  padding-inline: 6px;
}

.nav-section {
  display: grid;
  gap: 2px;
}

.nav-section-label {
  padding: 2px 12px 8px;
  color: #8ba392;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 1.5px;
}

.nav-item {
  position: relative;
  display: flex;
  width: 100%;
  min-height: 42px;
  align-items: center;
  justify-content: flex-start;
  gap: 10px;
  padding: 8px 12px;
  overflow: hidden;
  color: var(--app-sidebar-muted);
  background: transparent;
  border: 0;
  border-radius: 7px;
  cursor: pointer;
  text-decoration: none;
  transition: color var(--app-motion-fast) ease,
    background-color var(--app-motion-fast) ease;
  white-space: nowrap;
}

.is-collapsed .nav-item {
  justify-content: center;
  padding-inline: 0;
}

.nav-item:hover {
  color: #edf7ed;
  background: var(--app-sidebar-hover);
}

.nav-item.active {
  color: var(--app-sidebar-active);
  background: #a8e4bb16;
  font-weight: 600;
}

.nav-item.active::before {
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 0;
  width: 3px;
  background: var(--app-sidebar-active);
  border-radius: 3px;
  content: '';
}

.nav-item.is-emphasis:not(.active) {
  color: var(--app-sidebar-muted);
  background: transparent;
}

.sidebar-footer { display: flex; align-items: center; flex-shrink: 0; gap: 7px; margin: 0 16px; padding: 17px 0; color: #8ba392; border-top: 1px solid var(--app-sidebar-border); font-size: 10px; }
.sidebar-footer svg { color: #badba9; }
.sidebar-footer small { margin-left: auto; color: #a5baaa; font-size: 8px; letter-spacing: 1px; }
.is-collapsed .brand-row { padding-inline: 17px; }
.is-collapsed .nav-items { height: auto; padding-top: 12px; }

.is-mobile .nav-item {
  min-height: 44px;
}

.is-mobile {
  border-right: 0;
  box-shadow: none;
}
</style>
