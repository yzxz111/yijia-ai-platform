import { createRouter, createWebHistory, type RouterHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'

import ConsoleLayout from '../layouts/ConsoleLayout.vue'
import { restoreSession } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { readOAuthTransaction, recallOAuthTransaction } from '../views/auth/desktopOAuth'
import { recallPendingEmailRegistration } from '../views/auth/pendingEmailRegistration'
import { recallPendingPhoneRegistration } from '../views/auth/pendingPhoneRegistration'

const userChildren: RouteRecordRaw[] = [
  { path: 'dashboard', name: 'dashboard', component: () => import('../views/user/DashboardView.vue'), meta: { title: '工作台', requiresUser: true } },
  { path: 'plans', name: 'plans', component: () => import('../views/user/PlansView.vue'), meta: { title: '套餐管理', requiresUser: true } },
  { path: 'orders', name: 'orders', component: () => import('../views/user/OrdersView.vue'), meta: { title: '订单记录', requiresUser: true } },
  { path: 'subscriptions', name: 'subscriptions', component: () => import('../views/user/SubscriptionsView.vue'), meta: { title: '我的订阅', requiresUser: true } },
  { path: 'wallet', name: 'wallet', component: () => import('../views/user/WalletView.vue'), meta: { title: '余额钱包', requiresUser: true } },
  { path: 'api-keys', name: 'api-keys', component: () => import('../views/user/ApiKeysView.vue'), meta: { title: 'API 密钥', requiresUser: true } },
  { path: 'quota', name: 'quota', component: () => import('../views/user/QuotaView.vue'), meta: { title: '积分额度', requiresUser: true } },
  { path: 'usage', name: 'usage', component: () => import('../views/user/UsageLogsView.vue'), meta: { title: '调用日志', requiresUser: true } },
  { path: 'profile', name: 'profile', component: () => import('../views/user/ProfileView.vue'), meta: { title: '个人中心' } },
  { path: 'notifications', name: 'notifications', component: () => import('../views/user/NotificationsView.vue'), meta: { title: '通知中心' } },
  { path: 'support-tickets', name: 'support-tickets', component: () => import('../views/user/SupportTicketsView.vue'), meta: { title: '支持工单', allowsRestricted: true, requiresUser: true } },
  { path: 'help', name: 'help', component: () => import('../views/user/HelpView.vue'), meta: { title: '帮助文档', requiresUser: true } },
]

const userSafetyRoute: RouteRecordRaw = { path: 'safety-appeals', name: 'safety-appeals', component: () => import('../views/user/SafetyAppealsView.vue'), meta: { title: '安全申诉', allowsRestricted: true, requiresUser: true } }
userChildren.push(userSafetyRoute)

const adminChildren: RouteRecordRaw[] = [
  { path: 'content-safety', name: 'admin-content-safety', component: () => import('../views/admin/ContentSafetyView.vue'), meta: { title: '内容安全', requiresAdmin: true } },
  { path: 'overview', name: 'admin-overview', component: () => import('../views/admin/AdminOverviewView.vue'), meta: { title: '运营概览', requiresAdmin: true } },
  { path: 'operating-reports', name: 'admin-operating-reports', component: () => import('../views/admin/OperatingReportsView.vue'), meta: { title: '经营报表', requiresAdmin: true } },
  { path: 'settlement-deficit', name: 'admin-settlement-deficit', component: () => import('../views/admin/SettlementDeficitView.vue'), meta: { title: '结算垫付', requiresAdmin: true } },
  { path: 'users', name: 'admin-users', component: () => import('../views/admin/UsersInvitationsView.vue'), meta: { title: '用户管理', requiresAdmin: true } },
  { path: 'grants', name: 'admin-grants', component: () => import('../views/admin/GrantPackagesView.vue'), meta: { title: '套餐发放', requiresAdmin: true } },
  { path: 'plans', name: 'admin-plans', component: () => import('../views/admin/PlansAdminView.vue'), meta: { title: '套餐版本', requiresAdmin: true } },
  { path: 'models', name: 'admin-models', component: () => import('../views/admin/ModelsView.vue'), meta: { title: '模型管理', requiresAdmin: true } },
  { path: 'skills', name: 'admin-skills', component: () => import('../views/admin/SkillCatalogView.vue'), meta: { title: 'Skill 管理', requiresAdmin: true } },
  { path: 'experts', name: 'admin-experts', component: () => import('../views/admin/ExpertCatalogView.vue'), meta: { title: '专家管理', requiresAdmin: true } },
  { path: 'teams', name: 'admin-teams', component: () => import('../views/admin/TeamCatalogView.vue'), meta: { title: '专家团管理', requiresAdmin: true } },
  {
    path: 'channels',
    name: 'admin-channels',
    redirect: (to) => ({ path: '/admin/models', query: to.query }),
    meta: { title: '模型管理', requiresAdmin: true },
  },
  { path: 'usage', name: 'admin-usage', component: () => import('../views/admin/UsageQuotaView.vue'), meta: { title: '调用与额度', requiresAdmin: true } },
  { path: 'orders', name: 'admin-orders', component: () => import('../views/admin/OrdersPaymentsView.vue'), meta: { title: '订单与支付', requiresAdmin: true } },
  { path: 'wallet', name: 'admin-wallet', component: () => import('../views/admin/WalletAdminView.vue'), meta: { title: '钱包管理', requiresAdmin: true } },
  { path: 'subscriptions', name: 'admin-subscriptions', component: () => import('../views/admin/SubscriptionsAdminView.vue'), meta: { title: '订阅治理', requiresAdmin: true } },
  { path: 'communications', name: 'admin-communications', component: () => import('../views/admin/AdminCommunicationsView.vue'), meta: { title: '通知与工单', requiresAdmin: true } },
  { path: 'operations', name: 'admin-operations', component: () => import('../views/admin/OperationsView.vue'), meta: { title: '系统运维', requiresAdmin: true } },
  { path: 'legal-documents', name: 'admin-legal-documents', component: () => import('../views/admin/LegalDocumentsView.vue'), meta: { title: '协议管理', requiresAdmin: true } },
  { path: 'audit', name: 'admin-audit', component: () => import('../views/admin/AuditView.vue'), meta: { title: '审计日志', requiresAdmin: true } },
]

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/app/dashboard' },
  { path: '/login', name: 'login', component: () => import('../views/auth/LoginView.vue'), meta: { title: '登录', public: true } },
  {
    path: '/desktop-login',
    name: 'desktop-login',
    component: () => import('../views/auth/DesktopLoginView.vue'),
    meta: { title: '桌面端登录', public: true },
  },
  { path: '/terms', name: 'terms', component: () => import('../views/auth/TermsView.vue'), meta: { title: '平台服务协议', public: true } },
  { path: '/privacy', name: 'privacy', component: () => import('../views/auth/PrivacyView.vue'), meta: { title: '隐私政策', public: true } },
  { path: '/forgot-password', name: 'forgot-password', component: () => import('../views/auth/ForgotPasswordView.vue'), meta: { title: '找回密码', public: true } },
  {
    path: '/login/handoff/:requestId',
    name: 'login-handoff',
    component: () => import('../views/auth/LoginHandoffWaitingView.vue'),
    meta: { title: '登录确认', public: true },
  },
  { path: '/set-password', name: 'set-password', component: () => import('../views/auth/SetPasswordView.vue'), meta: { title: '设置密码', requiresAuth: true } },
  {
    path: '/desktop-set-password',
    name: 'desktop-set-password',
    component: () => import('../views/auth/DesktopSetPasswordView.vue'),
    meta: { title: '桌面端设置密码', requiresAuth: true },
  },
  {
    path: '/register',
    name: 'register',
    // 保留 query：/register?invite=<码> 直接跳登录页时要带上邀请码（否则两级跳转会把码丢掉）。
    redirect: (to) => ({ path: '/login', query: to.query }),
    meta: { title: '注册', public: true },
  },
  // 邀请链接：/invite-register?invite=<码> → 登录页（并保留邀请码，最终落到设密页自动填好）。
  // 原先这里只是 `redirect: '/register'`，两级跳转会把 ?invite= 丢掉。
  { path: '/invite-register', redirect: (to) => ({ path: '/login', query: to.query }) },
  {
    path: '/app',
    component: ConsoleLayout,
    redirect: '/app/dashboard',
    meta: { requiresAuth: true },
    children: userChildren,
  },
  {
    path: '/account-restricted',
    component: ConsoleLayout,
    meta: { requiresAuth: true, allowsRestricted: true },
    children: [
      {
        path: '',
        name: 'account-restricted',
        component: () => import('../views/user/RestrictedAccountView.vue'),
        meta: { title: '账号状态', requiresAuth: true, allowsRestricted: true },
      },
    ],
  },
  {
    path: '/admin',
    component: ConsoleLayout,
    redirect: '/admin/overview',
    meta: { requiresAuth: true, requiresAdmin: true },
    children: adminChildren,
  },
  { path: '/:pathMatch(.*)*', redirect: '/app/dashboard' },
]

export function createConsoleRouter(
  history: RouterHistory = createWebHistory(import.meta.env.BASE_URL),
  restore: () => Promise<boolean> = restoreSession,
) {
  const router = createRouter({ history, routes })
  let restorationAttempted = false
  let restorationInFlight: Promise<boolean> | null = null
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    const legacyDesktopAuthorization = to.path === '/login'
      && typeof to.query.oauth_transaction === 'string'
      && to.query.oauth_transaction.length > 0
    if (legacyDesktopAuthorization) {
      return { path: '/desktop-login', query: to.query, replace: true }
    }
    // 桌面客户端 OAuth 使用独立页面自行收尾，不能被登录态重定向抢走。
    const desktopAuthorization = to.path === '/desktop-login'
      && typeof to.query.oauth_transaction === 'string'
      && to.query.oauth_transaction.length > 0
    const desktopPasswordTransaction = to.path === '/desktop-set-password'
      ? readOAuthTransaction(to.query) ?? recallOAuthTransaction()
      : null
    const pendingPhoneRegistration = (to.path === '/set-password' || to.path === '/desktop-set-password')
      ? recallPendingPhoneRegistration()
      : null
    const pendingEmailRegistration = (to.path === '/set-password' || to.path === '/desktop-set-password')
      ? recallPendingEmailRegistration()
      : null
    const matchingPhoneRegistration = pendingPhoneRegistration?.oauthTransaction === (
      to.path === '/set-password' ? null : desktopPasswordTransaction
    ) ? pendingPhoneRegistration : null
    const matchingEmailRegistration = pendingEmailRegistration?.oauthTransaction === (
      to.path === '/set-password' ? null : desktopPasswordTransaction
    ) ? pendingEmailRegistration : null
    const pendingRegistration = matchingPhoneRegistration && matchingEmailRegistration
      ? null
      : matchingPhoneRegistration ?? matchingEmailRegistration
    // A verified phone or email is not an account yet; the password screen must be reachable
    // before a browser session exists. Other protected routes still require a session.
    const completingRegistration = Boolean(pendingRegistration && !auth.isAuthenticated
      && (to.path === '/set-password' || desktopPasswordTransaction))
    if (to.meta.requiresAuth && !auth.isAuthenticated && !completingRegistration && !restorationAttempted) {
      restorationInFlight ||= restore().finally(() => {
        restorationAttempted = true
        restorationInFlight = null
      })
      await restorationInFlight
    }
    if (to.meta.requiresAuth && !auth.isAuthenticated && !completingRegistration) {
      if (desktopPasswordTransaction) {
        return {
          path: '/desktop-login',
          query: { oauth_transaction: desktopPasswordTransaction },
        }
      }
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (auth.isAuthenticated && auth.isRestricted && !to.meta.allowsRestricted) {
      return '/account-restricted'
    }
    if (auth.isAuthenticated && !auth.isRestricted
        && auth.user?.passwordSet === false && to.path !== '/set-password'
        && !desktopAuthorization && !desktopPasswordTransaction) {
      // 需要补设密码时把邀请码带到设密页；不带的话邀请链接在重定向后就断了。
      return {
        path: '/set-password',
        query: typeof to.query.invite === 'string' ? { invite: to.query.invite } : {},
      }
    }
    if (auth.isAuthenticated && !auth.isRestricted
        && auth.user?.passwordSet !== false && to.path === '/set-password') {
      return auth.isAdmin ? '/admin/overview' : '/app/dashboard'
    }
    if (to.meta.requiresUser && auth.isAdmin) {
      ElMessage.warning('管理员无权访问普通用户页面。')
      return '/admin/overview'
    }
    if (to.meta.requiresAdmin && !auth.isAdmin) {
      return '/app/dashboard'
    }
    if (to.meta.public && auth.isAuthenticated && to.path === '/login' && !desktopAuthorization) {
      return auth.isRestricted
        ? '/account-restricted'
        : auth.isAdmin ? '/admin/overview' : '/app/dashboard'
    }
    return true
  })
  return router
}

export default createConsoleRouter()
