import { describe, expect, it } from 'vitest'
import {
  TEXT_CONTRAST_MINIMUM,
  UI_CONTRAST_MINIMUM,
  checkAccentContrast,
  contrastRatio,
  formatContrastRatio,
  parseHexColor,
} from './contrast'
import { OPAA_BRANDING } from '../stores/brandingStore'

describe('parseHexColor', () => {
  it('accepts six-digit hex in either case', () => {
    expect(parseHexColor('#1292EE')).toEqual([0x12, 0x92, 0xee])
    expect(parseHexColor('#1292ee')).toEqual([0x12, 0x92, 0xee])
    expect(parseHexColor('  #FFFFFF  ')).toEqual([255, 255, 255])
  })

  it('rejects anything that is not a six-digit hex value', () => {
    expect(parseHexColor('#12E')).toBeNull()
    expect(parseHexColor('blau')).toBeNull()
    expect(parseHexColor('')).toBeNull()
    expect(parseHexColor('rgb(1,2,3)')).toBeNull()
  })
})

describe('contrastRatio', () => {
  // The two anchors of the WCAG scale - if these drift, the luminance arithmetic is wrong.
  it('is 21:1 for black on white and 1:1 for a colour against itself', () => {
    expect(contrastRatio('#000000', '#FFFFFF')).toBeCloseTo(21, 5)
    expect(contrastRatio('#1292EE', '#1292EE')).toBeCloseTo(1, 5)
  })

  it('is symmetric', () => {
    expect(contrastRatio('#1292EE', '#FFFFFF')).toBeCloseTo(
      contrastRatio('#FFFFFF', '#1292EE') as number,
      10,
    )
  })

  it('returns null when either colour is unparseable', () => {
    expect(contrastRatio('blau', '#FFFFFF')).toBeNull()
    expect(contrastRatio('#FFFFFF', 'blau')).toBeNull()
  })
})

describe('checkAccentContrast', () => {
  it('flags a pale accent as unreadable behind white button text', () => {
    const checks = checkAccentContrast('#FFF176')

    const buttonLabel = checks.find((c) => c.label.includes('Schaltflächen'))
    expect(buttonLabel?.passes).toBe(false)
    expect(buttonLabel?.required).toBe(TEXT_CONTRAST_MINIMUM)
  })

  it('flags a very dark accent as invisible on the dark scheme', () => {
    const checks = checkAccentContrast('#02305E')

    const onDark = checks.find((c) => c.label.includes('dunklem'))
    expect(onDark?.passes).toBe(false)
    expect(onDark?.required).toBe(UI_CONTRAST_MINIMUM)
  })

  it('reports all three comparisons for a usable accent', () => {
    // The window is narrow, and that is the finding rather than a quirk of this test: white button
    // text wants a dark accent (>= 4,5:1), while the same accent has to stay visible against the
    // dark scheme's navy surface (>= 3:1). #0B6FBC (blue-700 of the token scale) is inside it at
    // 5,2 / 5,2 / 3,1.
    const checks = checkAccentContrast('#0B6FBC')

    expect(checks).toHaveLength(3)
    expect(checks.every((c) => c.passes)).toBe(true)
  })

  /**
   * #634: the OPAA standard accent used to reach only 3,3:1 behind white button text, below the
   * 4,5:1 WCAG asks for normal text, even though docs/design/guidelines.md#24-kontrast claimed the
   * role pairs all satisfy their thresholds. blue[700] (`#0B6FBC`) is now the standard - this test
   * exists so the claim and the arithmetic cannot drift apart silently again.
   */
  it('confirms the OPAA standard accent clears every threshold', () => {
    const checks = checkAccentContrast(OPAA_BRANDING.primaryColor)

    expect(checks).toHaveLength(3)
    expect(checks.every((c) => c.passes)).toBe(true)
  })

  it('returns nothing to warn about for an unparseable colour', () => {
    expect(checkAccentContrast('blau')).toEqual([])
  })
})

describe('formatContrastRatio', () => {
  it('writes the ratio the way WCAG tooling does, with a German decimal comma', () => {
    expect(formatContrastRatio(4.5)).toBe('4,5:1')
    expect(formatContrastRatio(21)).toBe('21,0:1')
  })
})
