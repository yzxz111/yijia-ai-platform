import { expect, test, type Page } from '@playwright/test'

import {
  cleanupLocalStackAdmin,
  provisionLocalStackAdmin,
  type ProvisionedStackAdmin,
} from './support/stack'

let e2eAdmin: ProvisionedStackAdmin | undefined

test.beforeAll(async ({ request }) => {
  e2eAdmin = await provisionLocalStackAdmin(request)
})

test.afterAll(async () => {
  if (e2eAdmin) await cleanupLocalStackAdmin(e2eAdmin)
})

/**
 * Browser-level acceptance for the Expert presentation editor.
 *
 * The curated catalog contains six experts.  We deliberately use one stable
 * fixture here rather than coupling the test to card ordering: the same
 * editor is rendered for every expert and the fixture is restored by the
 * catalog snapshot workflow before the live acceptance suite runs.
 */
test.describe('Expert WYSIWYG editor', () => {
  test('opens a curated expert with the complete 1080x704 editing surface', async ({ page }) => {
    test.setTimeout(120_000)
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.emulateMedia({ reducedMotion: 'reduce' })

    const consoleErrors: string[] = []
    const pageErrors: string[] = []
    page.on('console', message => {
      if (message.type() === 'error') consoleErrors.push(message.text())
    })
    page.on('pageerror', error => pageErrors.push(error.message))

    // The application deliberately computes X-Device-Id from localStorage in
    // its Axios interceptor, so seed the isolated account's device in this
    // browser context before the first page request.
    await page.addInitScript((deviceId) => {
      window.localStorage.setItem('yzh_device_id', deviceId)
    }, e2eAdmin!.deviceId)
    await page.goto('/login')
    await expect(page.locator('[data-test="login-submit"]')).toBeVisible()
    await loginThroughUi(page)
    await expect(page).toHaveURL(/\/admin\/overview$/)

    await page.goto('/admin/experts')
    const editButton = page.locator('[data-test="skill-edit-ai-office-assistant"]')
    await expect(editButton).toBeVisible()
    await editButton.click()

    const editor = page.locator('[data-test="expert-wysiwyg-editor"]')
    await expect(editor).toBeVisible()
    await expect(page.locator('[data-test="expert-wysiwyg-canvas"]')).toBeVisible()

    const canvas = page.locator('[data-test="expert-wysiwyg-canvas"]')
    const canvasBox = await canvas.boundingBox()
    expect(canvasBox?.width).toBeCloseTo(1080, 0)
    expect(canvasBox?.height).toBeCloseTo(704, 0)

    // Blocks 1–7 are in the detail column and block 8 is the two-sided
    // example/output area.  The feature image is embedded in the left input
    // card now; there is deliberately no separate block 9.
    for (const block of ['1', '2', '3', '4', '5', '6', '7']) {
      await expect(editor.locator(`[data-block="${block}"]`)).toHaveCount(1)
    }
    await expect(editor.locator('[data-block="8"]')).toHaveCount(2)
    await expect(editor.locator('[data-block="9"]')).toHaveCount(0)
    await expect(editor.locator('[data-test="expert-block-preview-input"]')).toHaveCount(1)
    await expect(editor.locator('[data-test="expert-block-example-image"]')).toHaveCount(0)

    // The desktop rendering is a separate fixed window.  It must not become
    // a second child of the workbench, otherwise the editing canvas shrinks
    // and the 1080 × 704 surface can no longer be edited at its intended size.
    const workbench = editor.locator('.expert-wysiwyg__workbench')
    const floatingPreview = editor.locator('[data-test="expert-floating-preview"]')
    await expect(floatingPreview).toBeVisible()
    await expect(floatingPreview.locator('[data-test="expert-desktop-preview"]')).toBeVisible()
    expect(await workbench.locator('[data-test="expert-desktop-preview"]').count()).toBe(0)
    expect(await floatingPreview.evaluate(element => getComputedStyle(element).position)).toBe('fixed')
    const workbenchBox = await workbench.boundingBox()
    const canvasBoxBeforePreview = await canvas.boundingBox()
    expect(workbenchBox?.width).toBeGreaterThanOrEqual(canvasBoxBeforePreview?.width ?? 0)

    // The live result is a separate window, so dragging its title bar must not
    // reflow the editor canvas. Its position is also clamped after a viewport
    // resize and survives closing/reopening the preview.
    const dragHandle = floatingPreview.locator('[data-test="expert-preview-drag-handle"]')
    await expect(dragHandle).toBeVisible()
    const initialPreviewBox = await floatingPreview.boundingBox()
    expect(initialPreviewBox).not.toBeNull()
    const dragStart = {
      x: (initialPreviewBox?.x ?? 0) + Math.min(120, (initialPreviewBox?.width ?? 0) / 2),
      y: (initialPreviewBox?.y ?? 0) + 18,
    }
    await page.mouse.move(dragStart.x, dragStart.y)
    await page.mouse.down()
    await page.mouse.move(dragStart.x - 180, dragStart.y + 120, { steps: 6 })
    await page.mouse.up()
    const movedPreviewBox = await floatingPreview.boundingBox()
    expect(movedPreviewBox).not.toBeNull()
    expect(movedPreviewBox!.x).toBeLessThan((initialPreviewBox?.x ?? 0) - 100)
    expect(movedPreviewBox!.y).toBeGreaterThan((initialPreviewBox?.y ?? 0) + 80)

    await page.setViewportSize({ width: 920, height: 620 })
    await expect(floatingPreview).toBeVisible()
    const resizedPreviewBox = await floatingPreview.boundingBox()
    expect(resizedPreviewBox).not.toBeNull()
    expect(resizedPreviewBox!.x).toBeGreaterThanOrEqual(0)
    expect(resizedPreviewBox!.y).toBeGreaterThanOrEqual(0)
    expect(resizedPreviewBox!.x + resizedPreviewBox!.width).toBeLessThanOrEqual(920)
    expect(resizedPreviewBox!.y + resizedPreviewBox!.height).toBeLessThanOrEqual(620)
    await page.setViewportSize({ width: 1440, height: 1000 })
    const previewBoxBeforeClose = await floatingPreview.boundingBox()

    await editor.locator('[data-test="expert-preview-close"]').click()
    await expect(floatingPreview).toHaveCount(0)
    await editor.locator('[data-test="expert-preview-reopen"]').click()
    await expect(floatingPreview).toBeVisible()
    const reopenedPreviewBox = await floatingPreview.boundingBox()
    expect(reopenedPreviewBox).not.toBeNull()
    expect(reopenedPreviewBox!.x).toBeCloseTo(previewBoxBeforeClose?.x ?? 0, 0)
    // Keep the editor baseline unobscured. The preview is independently
    // reopenable and closing it must not resize or remove any canvas content.
    await editor.locator('[data-test="expert-preview-close"]').click()
    await expect(floatingPreview).toHaveCount(0)

    // The advanced drawer is collapsed initially; opening it must expose only
    // operational/package fields and keep the presentation canvas intact.
    const advanced = page.locator('[data-test="expert-advanced-settings"]')
    await expect(advanced).toBeVisible()
    await advanced.locator('.el-collapse-item__header').click()
    await expect(page.locator('[data-test="expert-advanced-code"]')).toBeVisible()
    await expect(page.locator('[data-test="expert-advanced-summary"]')).toBeVisible()
    await expect(page.locator('[data-test="expert-advanced-category"]')).toBeVisible()
    await expect(page.locator('[data-test="expert-advanced-min-client"]')).toBeVisible()
    // Business tags are edited in the canvas. The former duplicated advanced
    // tags field must not reappear and drift away from the visible cards.
    await expect(page.locator('[data-test="expert-advanced-tags"]')).toHaveCount(0)
    await expect(canvas).toBeVisible()

    // Language switching is editor-local and must not navigate or lose the
    // selected expert.  English may fall back to the cloud Chinese text, but
    // its tab remains selectable and reports the active state.
    const languages = editor.locator('[data-test="expert-wysiwyg-language"]')
    await expect(languages).toHaveCount(2)
    await expect(languages.nth(0)).toHaveAttribute('aria-selected', 'true')
    await languages.nth(1).click()
    await expect(languages.nth(1)).toHaveAttribute('aria-selected', 'true')
    await expect(languages.nth(0)).toHaveAttribute('aria-selected', 'false')
    await languages.nth(0).click()

    // Each supported media role exposes the same safe input contract.  The
    // assertions accept either a loaded image or the explicit empty state so
    // the test remains useful while a fresh catalog is being seeded.  Loaded
    // media gets replace/download/clear controls on hover; empty media keeps
    // its direct upload entry point.
    const mediaInputs = editor.locator('input[type="file"][accept="image/png,image/jpeg,image/webp"]')
    await expect(mediaInputs).toHaveCount(3)
    const mediaRoles = [
      {
        role: 'illustration',
        surface: '[data-test="expert-block-illustration"]',
        upload: '[data-test="expert-block-illustration"] .expert-wysiwyg__empty-media',
        actions: ['expert-image-replace-illustration', 'expert-image-download-illustration', 'expert-image-clear-illustration'],
      },
      {
        role: 'icon',
        surface: '[data-test="expert-block-icon"]',
        upload: '[data-test="expert-block-icon"] span',
        actions: ['expert-image-replace-icon', 'expert-image-download-icon', 'expert-image-clear-icon'],
      },
      {
        role: 'exampleImage',
        surface: '[data-test="expert-block-preview-input"]',
        upload: '[data-test="expert-preview-example-image-upload"]',
        actions: ['expert-image-replace-exampleImage', 'expert-image-download-exampleImage', 'expert-preview-example-image-clear'],
      },
    ] as const
    for (const media of mediaRoles) {
      const mediaSurface = editor.locator(media.surface)
      await expect(mediaSurface).toBeVisible()
      const loaded = await mediaSurface.locator('img').count() > 0
      if (loaded) {
        await mediaSurface.hover()
        for (const action of media.actions) {
          await expect(editor.locator(`[data-test="${action}"]`)).toBeAttached()
        }
      } else {
        await expect(editor.locator(media.upload)).toBeAttached()
      }
    }
    for (const selector of ['[data-test="expert-block-illustration"]', '[data-test="expert-block-icon"]']) {
      const mediaSurface = editor.locator(selector)
      expect(
        await mediaSurface.locator('img, .expert-wysiwyg__empty-media').count(),
        `${selector} media state`,
      ).toBeGreaterThan(0)
    }
    const exampleImage = editor.locator('[data-test="expert-block-preview-input"]')
    expect(
      await exampleImage.locator('img, [data-test="expert-preview-example-image-upload"], [aria-label="示例请求"]').count(),
      'example image upload/loaded state',
    ).toBeGreaterThan(0)

    await canvas.locator('img').evaluateAll(images => Promise.all(images.map(image => image.decode().catch(() => undefined))))
    await page.mouse.move(0, 0)

    // Keep this locator snapshot intentionally scoped to the canvas.  It gives
    // reviewers a small, stable visual contract instead of a full-page image
    // polluted by transient navigation/toast state.
    await expect(canvas).toHaveScreenshot('expert-wysiwyg-canvas.png', {
      animations: 'disabled',
      caret: 'hide',
      maxDiffPixelRatio: 0.01,
    })

    // Selecting a range inside one contenteditable surface must target only
    // those characters.  Run this after the screenshot so the interaction
    // check never changes the existing visual baseline; no save is performed,
    // so it also leaves cloud data untouched.
    const titleField = editor.locator('[contenteditable="true"][aria-label="专家名称"]')
    await expect(titleField).toBeVisible()
    const selectedLength = await titleField.evaluate(element => {
      const textNode = Array.from(element.childNodes).find(node => node.nodeType === Node.TEXT_NODE)
        ?? element.querySelector('[data-rich-run]')?.firstChild
      if (!textNode || textNode.nodeType !== Node.TEXT_NODE) return 0
      const length = textNode.textContent?.length ?? 0
      if (length < 3) return 0
      const range = document.createRange()
      range.setStart(textNode, 1)
      range.setEnd(textNode, Math.min(3, length))
      const selection = window.getSelection()
      selection?.removeAllRanges()
      selection?.addRange(range)
      element.dispatchEvent(new KeyboardEvent('keyup', { key: 'ArrowRight', bubbles: true }))
      return Math.min(3, length) - 1
    })
    expect(selectedLength).toBeGreaterThan(0)
    await expect(editor.locator('[data-test="expert-apply-font-color"]')).toBeEnabled()
    await editor.locator('[data-test="expert-font-color"]').evaluate((element) => {
      const input = element as HTMLInputElement
      input.value = '#dc2626'
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await editor.locator('[data-test="expert-apply-font-color"]').click()
    await editor.locator('[data-test="expert-font-size"]').selectOption('24')
    await expect(titleField.locator('[data-rich-run][data-color="#dc2626"][data-font-size="24"]')).toHaveCount(1)

    expect(consoleErrors, 'expert editor console errors').toEqual([])
    expect(pageErrors, 'expert editor page errors').toEqual([])
  })
})

async function loginThroughUi(page: Page) {
  await page.locator('[data-test="login-identifier"]').fill(e2eAdmin!.email)
  await page.locator('[data-test="login-password"]').fill(e2eAdmin!.password)
  await page.locator('[data-test="login-submit"]').click()
}
