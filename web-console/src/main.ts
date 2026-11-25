import { createPinia, setActivePinia } from 'pinia'
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import { ElMessage } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/tokens.css'
import './styles/element-overrides.css'
import './styles/admin.css'
import './styles/dark-overrides.css'
import './styles/legal-document.css'

import App from './App.vue'
import { APP_NAME } from './brand'
import TopErrorMessage from './components/feedback/TopErrorMessage.vue'
import router from './router'
import type { AuthExpiryReason } from './api/http'
import { publishTerminalSessionEvent } from './utils/terminalSessionChannel'
import { useThemeStore } from './stores/theme'

window.addEventListener('auth:expired', (rawEvent) => {
  const event = rawEvent as CustomEvent<AuthExpiryReason>
  if (event.detail === 'session_replaced') {
    publishTerminalSessionEvent({ type: 'SESSION_REPLACED' })
    ElMessage.warning({
      message: '账号已在另一台电脑登录',
      duration: 5000,
      showClose: true,
    })
  }
  const current = router.currentRoute.value
  if (current.path !== '/login') {
    void router.replace({ path: '/login', query: { redirect: current.fullPath } })
  }
})

document.title = APP_NAME
router.afterEach((to) => {
  document.title = to.meta.title ? `${String(to.meta.title)} · ${APP_NAME}` : APP_NAME
})

const pinia = createPinia()
setActivePinia(pinia)
useThemeStore().init()

createApp(App)
  .component('TopErrorMessage', TopErrorMessage)
  .use(pinia)
  .use(router)
  .use(ElementPlus, { locale: zhCn })
  .mount('#app')
