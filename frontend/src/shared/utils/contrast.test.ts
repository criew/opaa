import { describe, expect, it } from 'vitest'
import {
  TEXT_CONTRAST_MINIMUM,
  checkAccentContrast,
  contrastRatio,
  deriveAccentSurface,
  deriveAccentText,
  formatContrastRatio,
  parseHexColor,
} from './contrast'
import { OPAA_BRANDING } from '../branding/brandingStore'
import { darkRoles } from '../theme/tokens'

describe('parseHexColor', () => {
  it('accepts six-digit hex in either case', () => {
    expect(parseHexColor('#009DDB')).toEqual([0x00, 0x9d, 0xdb])
    expect(parseHexColor('#009ddb')).toEqual([0x00, 0x9d, 0xdb])
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
    expect(contrastRatio('#009DDB', '#009DDB')).toBeCloseTo(1, 5)
  })

  it('is symmetric', () => {
    expect(contrastRatio('#009DDB', '#FFFFFF')).toBeCloseTo(
      contrastRatio('#FFFFFF', '#009DDB') as number,
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

  /**
   * The two scheme checks now evaluate the **derived** text colour, i.e. what
   * actually renders. A very dark blue is lightened in the dark scheme as far as
   * the nine permitted steps reach — if that takes it above the threshold, there is nothing to
   * warn about; if it stays below, the check still reports it.
   */
  it('judges the derived text colour of the dark scheme, not the raw accent', () => {
    const checks = checkAccentContrast('#1153EE')

    const onDark = checks.find((c) => c.label.includes('dunklen Schema'))
    expect(onDark).toBeDefined()
    expect(onDark?.required).toBe(TEXT_CONTRAST_MINIMUM)
    // The demo's raw house colour would be below it on the dark ground; the derivation lifts it
    // above, and exactly that is what the check reports - it evaluates what renders.
    expect(contrastRatio('#1153EE', darkRoles.bg2)).toBeLessThan(TEXT_CONTRAST_MINIMUM)
    expect(onDark?.passes).toBe(true)
  })

  /**
   * The counter-check for the limit: this very dark blue stays below the threshold even after the
   * nine permitted steps. The derivation then stops, and the form says so -
   * instead of lightening the colour beyond recognition or leaving the operator in the dark.
   */
  it('still warns when even the bounded derivation cannot reach the threshold', () => {
    const checks = checkAccentContrast('#02305E')

    const onDark = checks.find((c) => c.label.includes('dunklen Schema'))
    expect(onDark?.passes).toBe(false)
  })

  it('reports all three comparisons for a usable accent', () => {
    // The window is narrow, and that is the finding rather than a quirk of this test: white button
    // text wants a dark accent (>= 4,5:1), while the same accent has to stay visible against the
    // dark scheme's navy surface. #0055A4 (blue-700 of the token scale) carries white text at
    // 7,4:1 and is lightened as text on the dark ground.
    const checks = checkAccentContrast('#0055A4')

    expect(checks).toHaveLength(3)
    expect(checks.every((c) => c.passes)).toBe(true)
  })

  /**
   * The OPAA standard accent (blue-700) carries white text itself; the dark-scheme text check
   * evaluates its lightened derivation and passes as well.
   */
  it('passes the button-label check for the opaa standard accent via the derived surface', () => {
    const checks = checkAccentContrast(OPAA_BRANDING.primaryColor)

    const buttonLabel = checks.find((c) => c.label.includes('Schaltflächen'))
    expect(buttonLabel?.ratio).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    expect(checks.every((c) => c.passes)).toBe(true)
  })

  it('still flags an accent whose bounded darkening cannot rescue white text (#634)', () => {
    const checks = checkAccentContrast('#FFF176')

    const buttonLabel = checks.find((c) => c.label.includes('Schaltflächen'))
    expect(buttonLabel?.passes).toBe(false)
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

describe('deriveAccentText', () => {
  const HELLE_GRUENDE = ['#FFFFFF', '#F7F8FA', '#EFF1F4']
  const DUNKLE_GRUENDE = [darkRoles.bg1, darkRoles.bg2, darkRoles.bg3]

  it('keeps a colour that already reads on every ground of its scheme', () => {
    expect(deriveAccentText('#0055A4', HELLE_GRUENDE)).toBe('#0055A4')
  })

  it('lightens a dark colour until it reads on a dark ground', () => {
    const text = deriveAccentText('#1153EE', DUNKLE_GRUENDE)

    expect(text).not.toBe('#1153EE')
    for (const ground of DUNKLE_GRUENDE) {
      expect(contrastRatio(text, ground)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    }
  })

  it('darkens a light colour until it reads on a light ground', () => {
    const text = deriveAccentText('#61B5F6', HELLE_GRUENDE)

    expect(text).not.toBe('#61B5F6')
    for (const ground of HELLE_GRUENDE) {
      expect(contrastRatio(text, ground)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    }
  })

  /**
   * The threshold applies to every surface of the scheme, not only to the page's: a link
   * appears in a dialog or a table head just the same. Against the page ground alone
   * `#1153EE` needed three steps; against the lightest surface it needs five.
   */
  it('measures against the least favourable ground, not just the first', () => {
    const nurSeitengrund = deriveAccentText('#1153EE', [DUNKLE_GRUENDE[0]])
    const alleFlaechen = deriveAccentText('#1153EE', DUNKLE_GRUENDE)

    // bg-2, the cards, is the lightest dark ground
    expect(contrastRatio(nurSeitengrund, DUNKLE_GRUENDE[1])).toBeLessThan(TEXT_CONTRAST_MINIMUM)
    expect(contrastRatio(alleFlaechen, DUNKLE_GRUENDE[1])).toBeGreaterThanOrEqual(
      TEXT_CONTRAST_MINIMUM,
    )
  })

  it('stops after the bounded number of steps instead of repainting an extreme colour', () => {
    const text = deriveAccentText('#000033', DUNKLE_GRUENDE)

    expect(contrastRatio(text, DUNKLE_GRUENDE[1])).toBeLessThan(TEXT_CONTRAST_MINIMUM)
  })

  it('returns unparseable input unchanged', () => {
    expect(deriveAccentText('rgb(1,2,3)', DUNKLE_GRUENDE)).toBe('rgb(1,2,3)')
    expect(deriveAccentText('#1153EE', ['nicht-hex'])).toBe('#1153EE')
  })
})

describe('deriveAccentSurface', () => {
  it('keeps a colour that already carries white text', () => {
    expect(deriveAccentSurface('#0055A4')).toBe('#0055A4')
  })

  it('darkens a light colour until white text reaches 4.5:1', () => {
    const surface = deriveAccentSurface('#61B5F6')

    expect(surface).not.toBe('#61B5F6')
    expect(contrastRatio('#FFFFFF', surface)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
  })

  it('stops after the bounded number of steps instead of repainting an extreme colour', () => {
    const surface = deriveAccentSurface('#FFF176')

    expect(contrastRatio('#FFFFFF', surface)).toBeLessThan(TEXT_CONTRAST_MINIMUM)
  })

  it('returns unparseable input unchanged', () => {
    expect(deriveAccentSurface('blau')).toBe('blau')
  })
})
