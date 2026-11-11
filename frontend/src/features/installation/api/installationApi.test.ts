import { AxiosError } from 'axios'
import { describe, expect, it } from 'vitest'
import { checkTimeoutMs, isClientTimeout } from './installationApi'

/** The backend's limit of the staged text recognition test (three minutes). */
const BACKEND_TEXT_RECOGNITION_TIMEOUT_MS = 180_000

describe('checkTimeoutMs', () => {
  it('waits longer than the backend for the text recognition check', () => {
    expect(checkTimeoutMs('TEXT_RECOGNITION_MODEL')).toBeGreaterThan(
      BACKEND_TEXT_RECOGNITION_TIMEOUT_MS,
    )
  })

  it('keeps the common timeout for the other checks', () => {
    expect(checkTimeoutMs('CHAT_MODEL')).toBe(30_000)
    expect(checkTimeoutMs('MAIL')).toBe(30_000)
  })
})

describe('isClientTimeout', () => {
  it('tells an aborted wait from an answer of the backend', () => {
    expect(isClientTimeout(new AxiosError('timeout', 'ECONNABORTED'))).toBe(true)
    expect(isClientTimeout(new AxiosError('timeout', 'ETIMEDOUT'))).toBe(true)
    expect(isClientTimeout(new AxiosError('bad', 'ERR_BAD_REQUEST'))).toBe(false)
    expect(isClientTimeout(new Error('x'))).toBe(false)
  })
})
