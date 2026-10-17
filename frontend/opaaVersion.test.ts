import { describe, expect, it } from 'vitest'
import { resolveOpaaVersion } from './opaaVersion'

describe('resolveOpaaVersion', () => {
  it('falls back to the development placeholder when OPAA_VERSION is missing or empty', () => {
    expect(resolveOpaaVersion(undefined)).toBe('0.0.0-dev')
    expect(resolveOpaaVersion('')).toBe('0.0.0-dev')
  })

  it('takes the version of a release tag as printed by release-version.sh', () => {
    expect(resolveOpaaVersion('0.1.0-rc.2')).toBe('0.1.0-rc.2')
    expect(resolveOpaaVersion('1.2.3')).toBe('1.2.3')
    expect(resolveOpaaVersion('0.0.0-smoke.1')).toBe('0.0.0-smoke.1')
  })

  it('takes the development version with the commit a main image carries', () => {
    expect(resolveOpaaVersion('0.0.0-dev+3f2a9c1')).toBe('0.0.0-dev+3f2a9c1')
    expect(resolveOpaaVersion('1.2.3+build.7')).toBe('1.2.3+build.7')
  })

  it.each([
    'v0.1.0',
    '0.1',
    '01.2.3',
    '1.2.3-',
    '1.2.3+',
    '1.2.3+a+b',
    '0.0.0-dev+3f2a_9c1',
    ' 1.2.3',
    '1.2.3-rc_1',
  ])(
    'rejects %j, which is not X.Y.Z[-<pre>][+<build>]',
    (raw) => {
      expect(() => resolveOpaaVersion(raw)).toThrow(/OPAA_VERSION/)
    },
  )
})
