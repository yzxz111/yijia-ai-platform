import { afterEach, describe, expect, it } from 'vitest'

import {
  forgetPendingPhoneRegistration,
  recallPendingPhoneRegistration,
  rememberPendingPhoneRegistration,
} from './pendingPhoneRegistration'

const CHALLENGE = '2f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'
const TRANSACTION = '1f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'

describe('pending phone registration context', () => {
  afterEach(() => forgetPendingPhoneRegistration())

  it('retains a verified but unregistered phone challenge only for the current browser tab', () => {
    rememberPendingPhoneRegistration({
      code: 'phone_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, TRANSACTION, '2026-09-23')

    expect(recallPendingPhoneRegistration()).toMatchObject({
      challengeId: CHALLENGE,
      oauthTransaction: TRANSACTION,
      consentVersion: '2026-09-23',
    })
    expect(sessionStorage.getItem('yzh_pending_phone_registration')).toContain(CHALLENGE)
  })

  it('rejects expired or malformed registration results', () => {
    rememberPendingPhoneRegistration({
      code: 'phone_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() - 1000).toISOString(),
    }, null, '2026-09-23')
    expect(recallPendingPhoneRegistration()).toBeNull()

    rememberPendingPhoneRegistration({
      code: 'phone_registration_pending',
      challengeId: 'not-a-uuid',
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23')
    expect(recallPendingPhoneRegistration()).toBeNull()
  })
})
