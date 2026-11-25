import { afterEach, describe, expect, it } from 'vitest'

import {
  forgetPendingEmailRegistration,
  recallPendingEmailRegistration,
  rememberPendingEmailRegistration,
} from './pendingEmailRegistration'

const CHALLENGE = '2f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'
const TRANSACTION = '1f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'

describe('pending email registration context', () => {
  afterEach(() => forgetPendingEmailRegistration())

  it('retains a verified but unregistered email challenge only for the current browser tab', () => {
    rememberPendingEmailRegistration({
      code: 'email_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, TRANSACTION, '2026-09-23', ' User@Example.com ')

    expect(recallPendingEmailRegistration()).toMatchObject({
      challengeId: CHALLENGE,
      oauthTransaction: TRANSACTION,
      consentVersion: '2026-09-23',
      email: 'user@example.com',
    })
    expect(sessionStorage.getItem('yzh_pending_email_registration')).toContain(CHALLENGE)
  })

  it('rejects expired or malformed registration results', () => {
    rememberPendingEmailRegistration({
      code: 'email_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() - 1000).toISOString(),
    }, null, '2026-09-23', 'user@example.com')
    expect(recallPendingEmailRegistration()).toBeNull()

    rememberPendingEmailRegistration({
      code: 'email_registration_pending',
      challengeId: 'not-a-uuid',
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23', 'user@example.com')
    expect(recallPendingEmailRegistration()).toBeNull()

    rememberPendingEmailRegistration({
      code: 'email_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23', 'not-an-email')
    expect(recallPendingEmailRegistration()).toBeNull()
  })
})
