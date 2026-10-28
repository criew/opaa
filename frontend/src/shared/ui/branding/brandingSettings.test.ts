import { describe, expect, it } from 'vitest'
import { isInvalidPrimaryColor, singleLineNotice } from './brandingSettings'

describe('brandingSettings', () => {
  it('turns every kind of line break into one space', () => {
    expect(singleLineNotice('Erste Zeile\r\nzweite\u2028dritte\n\nvierte')).toBe(
      'Erste Zeile zweite dritte vierte',
    )
  })

  it('accepts an empty colour as the standard and only six-digit hex values otherwise', () => {
    expect(isInvalidPrimaryColor('')).toBe(false)
    expect(isInvalidPrimaryColor('  ')).toBe(false)
    expect(isInvalidPrimaryColor(' #7a3e9D ')).toBe(false)
    expect(isInvalidPrimaryColor('#FFF')).toBe(true)
    expect(isInvalidPrimaryColor('blau')).toBe(true)
  })
})
