import { afterEach, describe, expect, it, vi } from 'vitest'
import { CONSENT_INTENT_STORAGE_KEY, forgetConsentIntent } from './consentIntentStorage'

describe('consentIntentStorage', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  it('forgets whatever intent this tab keeps, without touching other entries', () => {
    sessionStorage.setItem(CONSENT_INTENT_STORAGE_KEY, '{"purpose":"LIBRARY_RECONNECT"}')
    sessionStorage.setItem('opaa.other', 'bleibt')

    forgetConsentIntent()

    expect(sessionStorage.getItem(CONSENT_INTENT_STORAGE_KEY)).toBeNull()
    expect(sessionStorage.getItem('opaa.other')).toBe('bleibt')
  })

  it('stays quiet when the tab has no storage', () => {
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('storage disabled')
    })

    expect(() => forgetConsentIntent()).not.toThrow()
  })
})
