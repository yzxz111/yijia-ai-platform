import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { expect, test, type APIRequestContext, type APIResponse, type Page } from '@playwright/test'

import {
  cleanupLocalStackAdmin,
  provisionLocalStackAdmin,
  type ProvisionedStackAdmin,
} from './support/stack'

const exampleFile = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../fixtures/portable-import/ai-learning-assistant/source-assets/example-image.png',
)
const replacementFile = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../fixtures/catalog-snapshot/images/skills/ai-office-assistant-example.png',
)

let admin: ProvisionedStackAdmin | undefined
let expertId: string | undefined
let expertCode: string

function headers() {
  return {
    Authorization: `Bearer ${admin!.adminSession.accessToken}`,
    'X-Device-Id': admin!.deviceId,
  }
}

function expertPayload(exampleImage: 'REPLACE' | 'CLEAR', expectedRowVersion?: number) {
  return {
    ...(expectedRowVersion == null ? {} : { expectedRowVersion }),
    code: expertCode,
    name: 'E2E 示例图片持久化专家',
    summary: '用于验证专家编辑器删除示例图片后不会回显。',
    description: '这条独立草稿仅供浏览器回归测试使用。',
    kind: 'EXPERT',
    status: 'DRAFT',
    tags: [],
    taskTypes: ['图片验证'],
    usageGuide: '输入一段文字，查看示例输出。',
    examplePrompts: ['生成一段示例文字'],
    preview: {
      heading: '示例',
      request: '生成一段示例文字',
      outputTitle: '示例输出',
      outputParagraphs: ['示例输出内容。'],
    },
    exampleImageLocale: 'zh',
    images: { icon: 'CLEAR', illustration: 'CLEAR', exampleImage },
  }
}

async function expectOk(response: APIResponse, action: string) {
  expect(response.ok(), `${action}: HTTP ${response.status()} ${await response.text()}`).toBe(true)
}

async function presentation(request: APIRequestContext) {
  const response = await request.get(
    `/console/gateway/admin/skills/${expertId}/expert-presentation`,
    { headers: headers() },
  )
  await expectOk(response, 'read expert presentation')
  return response.json() as Promise<{
    presentation: { rowVersion: number; assets: Record<string, string>; exampleImageLocale: string | null }
  }>
}

async function expectImageAbsent(request: APIRequestContext) {
  const profile = await presentation(request)
  expect(profile.presentation.assets.exampleImage).toBeUndefined()
  expect(profile.presentation.exampleImageLocale).toBeNull()
  const detailResponse = await request.get(`/console/gateway/admin/skills/${expertId}`, {
    headers: headers(),
  })
  await expectOk(detailResponse, 'read expert detail')
  const detail = await detailResponse.json() as { exampleImageUrl: string | null }
  expect(detail.exampleImageUrl).toBeNull()
}

async function openScratchEditor(page: Page) {
  await page.locator('[data-test="skills-query"]').fill(expertCode)
  await page.locator('[data-test="skills-filter"]').click()
  const edit = page.locator(`[data-test="skill-edit-${expertCode}"]`).first()
  await expect(edit).toBeVisible()
  await edit.click()
  const editor = page.locator('[data-test="expert-wysiwyg-editor"]')
  await expect(editor).toBeVisible()
  // The floating desktop preview can cover the image controls at smaller viewports.
  const previewClose = editor.locator('[data-test="expert-preview-close"]')
  if (await previewClose.isVisible()) await previewClose.click()
  return editor
}

async function clearAndSave(page: Page, request: APIRequestContext) {
  const editor = page.locator('[data-test="expert-wysiwyg-editor"]')
  const inputCard = editor.locator('[data-test="expert-block-preview-input"]')
  await expect(inputCard.locator('img')).toBeVisible()
  await inputCard.hover()
  await editor.locator('[data-test="expert-preview-example-image-clear"]').click()
  await expect(inputCard.locator('img')).toHaveCount(0)

  const saveRequest = page.waitForRequest(value =>
    value.method() === 'PUT'
      && value.url().endsWith(`/console/gateway/admin/skills/${expertId}/expert-presentation`),
  )
  await page.locator('[data-test="skill-form-submit"]').click()
  const confirmation = page.locator('.el-message-box')
  await expect(confirmation).toContainText('确认图片改动')
  await expect(confirmation).toContainText('清空已上传的示例图片')
  await confirmation.getByRole('button', { name: '确认并保存' }).click()
  const submitted = await saveRequest
  expect((await submitted.response())?.ok()).toBe(true)
  await expect(editor).toHaveCount(0)
  await expectImageAbsent(request)
}

test.beforeAll(async ({ request }) => {
  admin = await provisionLocalStackAdmin(request)
  expertCode = `e2e-expert-image-${admin.runId}`
  const response = await request.post('/console/gateway/admin/skills/experts', {
    headers: headers(),
    multipart: {
      data: {
        name: 'expert.json',
        mimeType: 'application/json',
        buffer: Buffer.from(JSON.stringify(expertPayload('REPLACE'))),
      },
      exampleImage: {
        name: 'example-image.png',
        mimeType: 'image/png',
        buffer: readFileSync(exampleFile),
      },
    },
  })
  await expectOk(response, 'create scratch expert')
  const created = await response.json() as { skillId: string; presentation: { assets: Record<string, string> } }
  expertId = created.skillId
  expect(created.presentation.assets.exampleImage).toBeTruthy()
  console.info(`[expert-image] created scratch expert ${expertCode}`)
})

test.afterAll(async ({ request }) => {
  try {
    if (admin && expertId) {
      // Browser login creates a newer terminal session for the same isolated account.
      // Refresh the API token at cleanup time so even a long failed test can purge its draft.
      const login = await request.post('/console/platform/auth/login', {
        headers: { 'X-Device-Id': admin.deviceId },
        data: { email: admin.email, password: admin.password },
      })
      await expectOk(login, 'refresh isolated admin session for cleanup')
      admin.adminSession.accessToken = (await login.json() as { accessToken: string }).accessToken
      const deleted = await request.delete(`/console/gateway/admin/skills/${expertId}`, {
        headers: headers(),
      })
      await expectOk(deleted, 'delete scratch expert')
      const purged = await request.delete(`/console/gateway/admin/skills/${expertId}/purge`, {
        headers: headers(),
      })
      await expectOk(purged, 'purge scratch expert')
      console.info(`[expert-image] purged scratch expert ${expertCode}`)
    }
  } finally {
    if (admin) await cleanupLocalStackAdmin(admin)
    console.info('[expert-image] cleaned up isolated admin')
  }
})

test('cleared example images stay cleared after save and reopen, including replace then clear', async ({ page, request }) => {
  test.setTimeout(180_000)
  page.setDefaultTimeout(15_000)
  page.setDefaultNavigationTimeout(30_000)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.addInitScript(deviceId => {
    window.localStorage.setItem('yzh_device_id', deviceId)
  }, admin!.deviceId)
  await page.goto('/login')
  await page.locator('[data-test="login-identifier"]').fill(admin!.email)
  await page.locator('[data-test="login-password"]').fill(admin!.password)
  const browserLogin = page.waitForResponse(value =>
    value.request().method() === 'POST'
      && value.url().endsWith('/console/platform/auth/login'),
  )
  await page.locator('[data-test="login-submit"]').click()
  admin!.adminSession.accessToken = (await (await browserLogin).json() as { accessToken: string }).accessToken
  await expect(page).toHaveURL(/\/admin\/overview$/)

  await page.goto('/admin/experts')
  await openScratchEditor(page)
  console.info('[expert-image] opened scratch expert with stored image')
  await clearAndSave(page, request)
  console.info('[expert-image] direct clear saved; API image absent')
  let editor = await openScratchEditor(page)
  await expect(editor.locator('[data-test="expert-block-preview-input"] img')).toHaveCount(0)
  console.info('[expert-image] same-page reopen shows no image')
  await page.locator('[data-test="skill-form-cancel"]').click()

  // Put a persisted image back, then replace it locally and clear that selection before save.
  // This exercises the branch where a pending File and an older stored pointer coexist.
  const current = await presentation(request)
  const replaced = await request.put(
    `/console/gateway/admin/skills/${expertId}/expert-presentation`,
    {
      headers: headers(),
      multipart: {
        data: {
          name: 'expert.json',
          mimeType: 'application/json',
          buffer: Buffer.from(JSON.stringify(expertPayload('REPLACE', current.presentation.rowVersion))),
        },
        exampleImage: {
          name: 'example-image.png',
          mimeType: 'image/png',
          buffer: readFileSync(exampleFile),
        },
      },
    },
  )
  await expectOk(replaced, 'restore scratch image for replace-clear case')
  console.info('[expert-image] restored stored image for replace-clear case')
  editor = await openScratchEditor(page)
  await expect(editor.locator('[data-test="expert-block-preview-input"] img')).toBeVisible()
  await page.evaluate(() => {
    const tracked = window as Window & { __e2eAssetUrls?: string[] }
    tracked.__e2eAssetUrls = []
    const create = URL.createObjectURL.bind(URL)
    URL.createObjectURL = blob => {
      const url = create(blob)
      tracked.__e2eAssetUrls!.push(url)
      return url
    }
  })
  await editor.locator('[data-test="expert-image-input-exampleImage"]').setInputFiles(replacementFile)
  // The editor creates one immediate preview URL, then the parent creates a second
  // URL after async crop/compression and assigns the File that will be submitted.
  await expect.poll(() => page.evaluate(() =>
    (window as Window & { __e2eAssetUrls?: string[] }).__e2eAssetUrls?.length ?? 0,
  )).toBeGreaterThanOrEqual(2)
  await expect.poll(async () => {
    const [shown, urls] = await Promise.all([
      editor.locator('[data-test="expert-block-preview-input"] img').getAttribute('src'),
      page.evaluate(() => (window as Window & { __e2eAssetUrls?: string[] }).__e2eAssetUrls ?? []),
    ])
    return shown === urls.at(-1)
  }).toBe(true)
  console.info('[expert-image] replacement image normalization complete')
  await clearAndSave(page, request)
  console.info('[expert-image] replace-then-clear saved; API image absent')
  editor = await openScratchEditor(page)
  await expect(editor.locator('[data-test="expert-block-preview-input"] img')).toHaveCount(0)
  console.info('[expert-image] final same-page reopen shows no image')
})
