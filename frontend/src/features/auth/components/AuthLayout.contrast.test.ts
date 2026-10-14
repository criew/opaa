import { describe, expect, it } from 'vitest'
import { LOGIN_BACKDROP_SCRIM_OPACITY, LOGIN_CLAIM_OPACITY } from './AuthLayout'
import { TEXT_CONTRAST_MINIMUM, compositeOver, contrastRatio } from '@/shared/utils/contrast'
import { navyRoles } from '@/shared/theme/tokens'

/**
 * Text sits on top of an uploaded background image. This application does not know which image
 * an installation uploads — so only the worst case can be checked: a pure white and a pure black
 * image under the fixed scrim. If the text reaches the 4.5:1 from
 * docs/design/accessibility.md there, it reaches it on every image.
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
      // The claim's opacity acts on the already composited ground.
      const grundfarbe = ground(image)
      const claim = compositeOver(navyRoles.fg1, grundfarbe, LOGIN_CLAIM_OPACITY) as string

      expect(ratio(claim, grundfarbe)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    })
  }
})
