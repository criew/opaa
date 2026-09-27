import { describe, expect, test } from 'vitest'
import { createAppTheme } from '../../theme/theme'
import { contrastRatio, TEXT_CONTRAST_MINIMUM } from '../../utils/contrast'
import { citationMarkColors } from './citationMark'

/**
 * The mark's digit is 9-10 px bold - small text, so 4.5:1 applies to both of its states. Checked
 * with the token accent and with house colours whose text derivation leaves them close to the
 * threshold: on the mark's own tint they would otherwise slip below it.
 */
const BRANDINGS = [undefined, '#C2410C', '#1153EE', '#61B5F6', '#7A1FA2']

describe('citationMarkColors', () => {
  describe.each(['light', 'dark'] as const)('%s scheme', (mode) => {
    test.each(BRANDINGS)('resting digit on its tint reaches 4.5:1 (branding %s)', (primary) => {
      const theme = createAppTheme(mode, primary ? { primaryColor: primary } : undefined)
      const { text, tint } = citationMarkColors(theme)

      expect(contrastRatio(text, tint)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    })

    test.each(BRANDINGS)('filled digit reaches 4.5:1 (branding %s)', (primary) => {
      const theme = createAppTheme(mode, primary ? { primaryColor: primary } : undefined)
      const { filledText, filledSurface } = citationMarkColors(theme)

      expect(contrastRatio(filledText, filledSurface)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    })
  })

  // regression guard: the accent itself as the filled surface put white at 3.3:1 in the dark scheme
  test('does not fill with the accent text colour in the dark scheme', () => {
    const theme = createAppTheme('dark')
    expect(contrastRatio('#FFFFFF', theme.palette.primary.main)).toBeLessThan(TEXT_CONTRAST_MINIMUM)
    expect(citationMarkColors(theme).filledSurface).not.toBe(theme.palette.primary.main)
  })
})
