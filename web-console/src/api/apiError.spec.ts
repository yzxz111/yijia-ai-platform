import { describe, expect, it } from 'vitest'

import { readApiErrorCode, readApiErrorMessage } from './apiError'

describe('readApiErrorCode', () => {
  it('returns a non-empty stable code from an API error response', () => {
    expect(readApiErrorCode({ response: { data: { code: 'avatar_file_invalid' } } }))
      .toBe('avatar_file_invalid')
  })

  it.each([
    null,
    undefined,
    {},
    { response: { data: {} } },
    { response: { data: { code: '   ' } } },
    { response: { data: { code: 422 } } },
  ])('returns null for an unsafe or malformed error code: %j', (error) => {
    expect(readApiErrorCode(error)).toBeNull()
  })
})

describe('readApiErrorMessage', () => {
  it('returns a non-empty public message from an API error response', () => {
    expect(readApiErrorMessage({ response: { data: { message: '模型权限无效' } } }))
      .toBe('模型权限无效')
  })

  it.each([
    null,
    undefined,
    {},
    { response: { data: {} } },
    { response: { data: { message: '   ' } } },
    { response: { data: { message: 422 } } },
    { response: { data: { reason: 'invalid_model_permission' } } },
  ])('returns null for an unsafe or malformed error shape: %j', (error) => {
    expect(readApiErrorMessage(error)).toBeNull()
  })
})
