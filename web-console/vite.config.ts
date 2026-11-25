import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

const apiProxyTarget = process.env.SYSTEM_API_PROXY_TARGET?.trim() || 'https://api.yuzhh.com'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: true,
    proxy: {
      '/console/gateway': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
      '/console': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
      '/v1': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts', 'tests/unit/**/*.spec.ts'],
    setupFiles: ['tests/setup.ts'],
    // 默认 5s 对「首次挂载整棵 ConsoleLayout + 路由守卫」的用例偏紧：这些用例本身是纯逻辑，
    // 但在全量并发下首次 transform 组件树会超过 5s，表现为 STACK_TRACE_ERROR 的假失败。
    // 放宽到 20s 只影响「真的卡住」的用例，不放宽任何断言。
    testTimeout: 20_000,
  },
})
