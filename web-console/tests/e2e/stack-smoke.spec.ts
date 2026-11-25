import { expect, test } from '@playwright/test'

test('serves the console and both readiness probes through Nginx', async ({ request }) => {
  const consoleResponse = await request.get('/')
  expect(consoleResponse.status()).toBe(200)
  expect(consoleResponse.headers()['content-type']).toContain('text/html')
  expect(await consoleResponse.text()).toContain('<div id="app"></div>')

  for (const endpoint of ['/health/platform', '/health/gateway']) {
    const response = await request.get(endpoint)
    expect(response.status(), endpoint).toBe(200)
    await expect(response.json()).resolves.toMatchObject({ status: 'UP' })
  }
})
