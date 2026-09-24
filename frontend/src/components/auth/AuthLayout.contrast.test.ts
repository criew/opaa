import { describe, expect, it } from 'vitest'
import { LOGIN_BACKDROP_SCRIM_OPACITY, LOGIN_CLAIM_OPACITY } from './AuthLayout'
import { TEXT_CONTRAST_MINIMUM, compositeOver, contrastRatio } from '../../utils/contrast'
import { navyRoles } from '../../theme/tokens'

/**
 * #1910: Über einem hinterlegten Hintergrundbild steht Text. Welches Bild eine Installation
 * hochlädt, weiß diese Anwendung nicht — prüfbar ist deshalb nur der schlechteste Fall: ein rein
 * weißes und ein rein schwarzes Bild unter dem festen Schleier. Erreicht die Schrift dort die
 * 4,5:1 aus docs/design/accessibility.md, erreicht sie sie über jedem Bild.
 */
describe('Schleier über dem Hintergrundbild der Anmeldeseite', () => {
  const extremes = { 'ein rein weißes Bild': '#FFFFFF', 'ein rein schwarzes Bild': '#000000' }

  function ground(image: string): string {
    const composited = compositeOver(navyRoles.bg1, image, LOGIN_BACKDROP_SCRIM_OPACITY)
    expect(composited).not.toBeNull()
    return composited as string
  }

  function ratio(foreground: string, background: string): number {
    const value = contrastRatio(foreground, background)
    expect(value).not.toBeNull()
    return value as number
  }

  for (const [name, image] of Object.entries(extremes)) {
    it(`trägt den Produktnamen über ${name}`, () => {
      expect(ratio(navyRoles.fg1, ground(image))).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    })

    it(`trägt den Claim über ${name}`, () => {
      // Die Deckkraft des Claims wirkt auf dem schon zusammengerechneten Grund.
      const grundfarbe = ground(image)
      const claim = compositeOver(navyRoles.fg1, grundfarbe, LOGIN_CLAIM_OPACITY) as string

      expect(ratio(claim, grundfarbe)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    })
  }
})
