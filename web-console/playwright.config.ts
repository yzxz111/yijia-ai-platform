import { defineConfig } from '@playwright/test'

export const basePlaywrightConfig = defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:18081',
    trace: 'retain-on-failure',
    // 本地 Windows 工作站可能已经装了受管的 Chrome，而 Playwright 的浏览器缓存
    // 却不可用。除非显式提供这个可选路径，CI 仍然使用正常的 Playwright 浏览器。
    launchOptions: process.env.E2E_CHROME_EXECUTABLE
      ? { executablePath: process.env.E2E_CHROME_EXECUTABLE }
      : undefined,
  },
})

export default basePlaywrightConfig
