import { defineConfig } from '@playwright/test'

import basePlaywrightConfig from './playwright.config'

const currentSourceSmsBaseUrl = 'http://127.0.0.1:4175'

export default defineConfig(basePlaywrightConfig, {
  use: {
    baseURL: currentSourceSmsBaseUrl,
    trace: 'off',
  },
  webServer: {
    command: 'pnpm exec vite --host 127.0.0.1 --port 4175 --strictPort',
    url: currentSourceSmsBaseUrl,
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
