import { expect, test } from '@playwright/test'

import {
  bearer,
  expectStatus,
  listApiKeys,
  provisionUserWithApiKey,
  type ApiKeyView,
} from './support/stack'

test('provisions a user package and manages an API key through the real stack', async ({ request }) => {
  test.setTimeout(60_000)

  const { apiKey, userSession } = await provisionUserWithApiKey(request)

  let listedKey = (await listApiKeys(request, userSession.accessToken))
    .find(({ id }) => id === apiKey.id)
  expect(listedKey).toMatchObject({
    id: apiKey.id,
    keyPrefix: apiKey.keyPrefix,
    status: 'ACTIVE',
  })
  expect(listedKey).not.toHaveProperty('secret')

  const disableResponse = await request.patch(`/console/gateway/api-keys/${apiKey.id}/status`, {
    headers: bearer(userSession.accessToken),
    data: { status: 'DISABLED' },
  })
  await expectStatus(disableResponse, 200, 'disable API key')
  const disabledKey = await disableResponse.json() as ApiKeyView
  expect(disabledKey).toMatchObject({ id: apiKey.id, status: 'DISABLED' })
  expect(disabledKey).not.toHaveProperty('secret')
  listedKey = (await listApiKeys(request, userSession.accessToken))
    .find(({ id }) => id === apiKey.id)
  expect(listedKey).toMatchObject({ id: apiKey.id, status: 'DISABLED' })
  expect(listedKey).not.toHaveProperty('secret')

  const enableResponse = await request.patch(`/console/gateway/api-keys/${apiKey.id}/status`, {
    headers: bearer(userSession.accessToken),
    data: { status: 'ACTIVE' },
  })
  await expectStatus(enableResponse, 200, 're-enable API key')
  const enabledKey = await enableResponse.json() as ApiKeyView
  expect(enabledKey).toMatchObject({ id: apiKey.id, status: 'ACTIVE' })
  expect(enabledKey).not.toHaveProperty('secret')
  listedKey = (await listApiKeys(request, userSession.accessToken))
    .find(({ id }) => id === apiKey.id)
  expect(listedKey).toMatchObject({ id: apiKey.id, status: 'ACTIVE' })
  expect(listedKey).not.toHaveProperty('secret')

  const revokeResponse = await request.delete(`/console/gateway/api-keys/${apiKey.id}`, {
    headers: bearer(userSession.accessToken),
  })
  await expectStatus(revokeResponse, 204, 'revoke API key')
  listedKey = (await listApiKeys(request, userSession.accessToken))
    .find(({ id }) => id === apiKey.id)
  expect(listedKey).toMatchObject({ id: apiKey.id, status: 'REVOKED' })
  expect(listedKey).not.toHaveProperty('secret')
})
