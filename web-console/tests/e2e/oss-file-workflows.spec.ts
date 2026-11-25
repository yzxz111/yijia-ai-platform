import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { expect, test, type Page } from '@playwright/test'

import {
  COMPOSE_ENV_FILE,
  E2E_USER_PASSWORD,
  provisionUserWithApiKey,
} from './support/stack'

const integrationEnabled = process.env.OSS_INTEGRATION_ENABLED === 'true'
const publicEndpoint = process.env.OSS_PUBLIC_ENDPOINT?.trim() ?? ''
const backendEndpoint = process.env.OSS_BACKEND_ENDPOINT?.trim() ?? ''
const publicEndpointHost = publicEndpoint ? new URL(publicEndpoint).hostname : ''
const composeRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../deploy')

test.skip(!integrationEnabled, 'set OSS_INTEGRATION_ENABLED=true for the real OSS browser workflow')

test('uploads an image and PDF directly, scans them, and deletes both', async ({ page, request }) => {
  test.setTimeout(120_000)
  expect(publicEndpoint).not.toBe('')
  expect(backendEndpoint).not.toBe('')
  const provisioned = await provisionUserWithApiKey(request)
  const ossRequests: Array<{ authorization?: string; url: string }> = []
  page.on('request', (browserRequest) => {
    const requestUrl = new URL(browserRequest.url())
    if (requestUrl.hostname === publicEndpointHost
      || requestUrl.hostname.endsWith(`.${publicEndpointHost}`)) {
      ossRequests.push({
        authorization: browserRequest.headers().authorization,
        url: browserRequest.url(),
      })
    }
  })

  await login(page, provisioned.userSession.user.email, E2E_USER_PASSWORD)
  await page.goto('/app/files')
  await page.locator('[data-test="file-api-key"]').fill(provisioned.apiKey.secret)
  await page.locator('[data-test="file-connect"]').click()
  await expect(page.locator('[data-test="file-upload"]')).toBeVisible()
  await observeUploadPhases(page)

  const png = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
    'base64',
  )
  const pdf = Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n'
    + '2 0 obj<</Type/Pages/Count 0/Kids[]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n')
  const imageId = await uploadAndWait(page, 'oss-image.png', 'image/png', png)
  const pdfId = await uploadAndWait(page, 'oss-document.pdf', 'application/pdf', pdf)
  const observedPhases = await page.evaluate(() =>
    (window as unknown as { __ossUploadPhases: string[] }).__ossUploadPhases)
  expect(observedPhases.some((phase) => phase.includes('上传'))).toBe(true)
  expect(observedPhases.some((phase) => phase.includes('扫描'))).toBe(true)

  expect(ossRequests).toHaveLength(2)
  for (const ossRequest of ossRequests) {
    const requestUrl = new URL(ossRequest.url)
    expect(requestUrl.protocol).toBe('https:')
    expect(requestUrl.hostname.endsWith(`.${publicEndpointHost}`)
      || requestUrl.hostname === publicEndpointHost).toBe(true)
    expect(ossRequest.authorization).toBeUndefined()
  }
  assertBackendConfigurationIsRedacted()

  await deleteFile(page, imageId)
  await deleteFile(page, pdfId)
})

async function login(page: Page, email: string, password: string) {
  await page.goto('/login')
  await page.locator('[data-test="login-identifier"]').fill(email)
  await page.locator('[data-test="login-password"]').fill(password)
  await page.locator('[data-test="login-submit"]').click()
  await expect(page).toHaveURL(/\/app\/dashboard$/u)
}

async function uploadAndWait(page: Page, name: string, mimeType: string, buffer: Buffer) {
  await page.locator('[data-test="file-upload"]').setInputFiles({ name, mimeType, buffer })
  const article = page.locator('article').filter({ hasText: name })
  await expect(article).toBeVisible({ timeout: 45_000 })
  await expect(article.locator('.file-status')).toHaveClass(/is-processed/u, { timeout: 45_000 })
  return (await article.locator('code').textContent())!.trim()
}

async function observeUploadPhases(page: Page) {
  await page.evaluate(() => {
    const state = window as unknown as { __ossUploadPhases: string[] }
    state.__ossUploadPhases = []
    new MutationObserver(() => {
      const phase = document.querySelector('[data-test="file-upload-phase"]')?.textContent?.trim()
      if (phase) state.__ossUploadPhases.push(phase)
    }).observe(document.body, { childList: true, characterData: true, subtree: true })
  })
}

async function deleteFile(page: Page, fileId: string) {
  await page.locator(`[data-test="file-delete-${fileId}"]`).click()
  await page.locator('.el-message-box__btns .el-button--primary').click()
  await expect(page.locator(`[data-test="file-${fileId}"]`)).toHaveCount(0)
}

function assertBackendConfigurationIsRedacted() {
  const configuredEndpoint = execFileSync('docker', [
    'compose',
    '--env-file', COMPOSE_ENV_FILE,
    '-f', path.join(composeRoot, 'compose.yaml'),
    '-f', path.join(composeRoot, 'compose.oss.yaml'),
    'exec', '-T', 'gateway-service', 'printenv', 'STORAGE_FILES_BACKEND_ENDPOINT',
  ], { encoding: 'utf8' }).trim()
  expect(configuredEndpoint).toBe(backendEndpoint)
  expect(new URL(configuredEndpoint).username).toBe('')
  expect(new URL(configuredEndpoint).password).toBe('')
  const logs = execFileSync('docker', [
    'compose',
    '--env-file', COMPOSE_ENV_FILE,
    '-f', path.join(composeRoot, 'compose.yaml'),
    '-f', path.join(composeRoot, 'compose.oss.yaml'),
    'logs', '--no-color', 'gateway-service',
  ], { encoding: 'utf8' })
  expect(logs).not.toMatch(
    /OSSAccessKeyId|Signature|(?:^|[\s[{,])["']?Authorization["']?\s*[:=]|\bpolicy\b/imu,
  )
}
