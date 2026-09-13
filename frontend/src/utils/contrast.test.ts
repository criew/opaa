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
import { OPAA_BRANDING } from '../stores/brandingStore'
import { darkRoles } from '../theme/tokens'

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

  /**
   * #1600: Die beiden Schema-Prüfungen bewerten seither die **abgeleitete** Textfarbe, also das,
   * was tatsächlich rendert. Ein sehr dunkles Blau wird im Dunkelschema so weit aufgehellt, wie
   * die sechs zugelassenen Schritte reichen — kommt es damit über die Schwelle, gibt es nichts zu
   * warnen; bleibt es darunter, meldet die Prüfung es weiterhin.
   */
  it('judges the derived text colour of the dark scheme, not the raw accent', () => {
    const checks = checkAccentContrast('#1153EE')

    const onDark = checks.find((c) => c.label.includes('dunklen Schema'))
    expect(onDark).toBeDefined()
    expect(onDark?.required).toBe(TEXT_CONTRAST_MINIMUM)
    // Die rohe Hausfarbe der Demo läge auf dem dunklen Grund darunter; die Ableitung hebt sie
    // darüber, und genau das meldet die Prüfung - sie bewertet, was rendert.
    expect(contrastRatio('#1153EE', darkRoles.bg3)).toBeLessThan(TEXT_CONTRAST_MINIMUM)
    expect(onDark?.passes).toBe(true)
  })

  /**
   * Die Gegenprobe zur Begrenzung: Dieses sehr dunkle Blau bleibt auch nach den sechs zugelassenen
   * Schritten unter der Schwelle (3,7:1). Die Ableitung hört dann auf, und das Formular sagt es -
   * statt die Farbe bis zur Unkenntlichkeit aufzuhellen oder den Betreiber im Unklaren zu lassen.
   */
  it('still warns when even the bounded derivation cannot reach the threshold', () => {
    const checks = checkAccentContrast('#02305E')

    const onDark = checks.find((c) => c.label.includes('dunklen Schema'))
    expect(onDark?.passes).toBe(false)
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
   * #634 closed the finding this test used to document: white on the raw standard accent
   * (blue-500) reaches only 3,3:1, but filled surfaces render on the derived accent surface -
   * so the button-label check now evaluates that surface and passes.
   */
  it('passes the button-label check for the OPAA standard accent via the derived surface', () => {
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
  const DUNKLE_GRUENDE = ['#09090B', '#18181B', '#27272A']

  it('keeps a colour that already reads on every ground of its scheme', () => {
    expect(deriveAccentText('#0B6FBC', HELLE_GRUENDE)).toBe('#0B6FBC')
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
   * Die Schwelle gilt für jede Fläche des Schemas, nicht nur für die der Seite: Ein Verweis steht
   * genauso in einem Dialog oder einem Tabellenkopf. Gegen den Seitengrund allein reichte
   * `#1153EE` nach drei Schritten; gegen die hellste Fläche braucht es fünf.
   */
  it('measures against the least favourable ground, not just the first', () => {
    const nurSeitengrund = deriveAccentText('#1153EE', [DUNKLE_GRUENDE[0]])
    const alleFlaechen = deriveAccentText('#1153EE', DUNKLE_GRUENDE)

    expect(contrastRatio(nurSeitengrund, DUNKLE_GRUENDE[2])).toBeLessThan(TEXT_CONTRAST_MINIMUM)
    expect(contrastRatio(alleFlaechen, DUNKLE_GRUENDE[2])).toBeGreaterThanOrEqual(
      TEXT_CONTRAST_MINIMUM,
    )
  })

  it('stops after the bounded number of steps instead of repainting an extreme colour', () => {
    const text = deriveAccentText('#000033', DUNKLE_GRUENDE)

    expect(contrastRatio(text, DUNKLE_GRUENDE[2])).toBeLessThan(TEXT_CONTRAST_MINIMUM)
  })

  it('returns unparseable input unchanged', () => {
    expect(deriveAccentText('rgb(1,2,3)', DUNKLE_GRUENDE)).toBe('rgb(1,2,3)')
    expect(deriveAccentText('#1153EE', ['nicht-hex'])).toBe('#1153EE')
  })
})

describe('deriveAccentSurface', () => {
  it('keeps a colour that already carries white text', () => {
    expect(deriveAccentSurface('#0B6FBC')).toBe('#0B6FBC')
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
