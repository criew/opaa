import { afterEach, describe, expect, it } from 'vitest'
import { isDemoModeEnabled } from './runtimeConfig'

afterEach(() => {
  delete window.__OPAA_DEMO_MODE__
})

describe('isDemoModeEnabled', () => {
  it('is off when the flag is missing (default OPAA installation)', () => {
    expect(isDemoModeEnabled()).toBe(false)
  })

  it('is off for any value other than the literal string "true"', () => {
    window.__OPAA_DEMO_MODE__ = 'false'
    expect(isDemoModeEnabled()).toBe(false)

    // The unsubstituted envsubst placeholder, if OPAA_DEMO_MODE were ever unset on the container -
    // must not accidentally read as enabled.
    window.__OPAA_DEMO_MODE__ = '${OPAA_DEMO_MODE}'
    expect(isDemoModeEnabled()).toBe(false)
  })

  it('is on when the container set the flag to "true"', () => {
    window.__OPAA_DEMO_MODE__ = 'true'
    expect(isDemoModeEnabled()).toBe(true)
  })
})
