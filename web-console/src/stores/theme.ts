import { defineStore } from 'pinia'
import { ref } from 'vue'

const STORAGE_KEY = 'app-theme'
type Theme = 'light' | 'dark'

function systemPrefersDark() {
  if (typeof window === 'undefined' || !window.matchMedia) return false
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

function readSaved(): Theme | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw === 'light' || raw === 'dark') return raw
  } catch {
    // 忽略存储异常（例如隐私模式）。
  }
  return null
}

function apply(theme: Theme) {
  if (typeof document === 'undefined') return
  const html = document.documentElement
  if (theme === 'dark') {
    html.setAttribute('data-theme', 'dark')
    html.classList.add('dark')
  } else {
    html.removeAttribute('data-theme')
    html.classList.remove('dark')
  }
}

export const useThemeStore = defineStore('theme', () => {
  const theme = ref<Theme>(readSaved() || (systemPrefersDark() ? 'dark' : 'light'))

  function setTheme(next: Theme) {
    theme.value = next
    apply(next)
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // 忽略存储异常。
    }
  }

  function toggle() {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  /** 在应用挂载前调用，避免主题闪烁 */
  function init() {
    apply(theme.value)
  }

  return {
    theme,
    setTheme,
    toggle,
    init,
  }
})
