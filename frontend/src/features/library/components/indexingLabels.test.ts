import { describe, expect, it } from 'vitest'
import { indexingRunEventCategoryLabel } from './indexingLabels'
import { createAppTheme } from '@/shared/theme/theme'
import { TEXT_CONTRAST_MINIMUM, contrastRatio } from '@/shared/utils/contrast'

describe('Kategorie „Auffällige Muster“ im Laufprotokoll', () => {
  it('trägt eine deutsche Beschriftung', () => {
    expect(indexingRunEventCategoryLabel('SUSPICIOUS_PATTERNS')).toBe('Auffällige Muster')
  })

  // The run protocol renders every category as an outlined chip without a colour of its own, so
  // its label is the primary text on the paper surface in either scheme.
  for (const mode of ['light', 'dark'] as const) {
    it(`erreicht im ${mode === 'light' ? 'hellen' : 'dunklen'} Schema 4,5:1`, () => {
      const palette = createAppTheme(mode).palette

      expect(contrastRatio(palette.text.primary, palette.background.paper)).toBeGreaterThanOrEqual(
        TEXT_CONTRAST_MINIMUM,
      )
    })
  }
})

describe('Kategorien der Texterkennung im Laufprotokoll', () => {
  it('tragen deutsche Beschriftungen', () => {
    expect(indexingRunEventCategoryLabel('TEXT_RECOGNIZED')).toBe('Per Texterkennung gelesen')
    expect(indexingRunEventCategoryLabel('TEXT_RECOGNITION_FAILED')).toBe(
      'Texterkennung gescheitert',
    )
    expect(indexingRunEventCategoryLabel('TEXT_RECOGNITION_BLOCKED')).toBe('Texterkennung gesperrt')
    expect(indexingRunEventCategoryLabel('TEXT_RECOGNITION_PENDING')).toBe(
      'Texterkennung ausstehend',
    )
    expect(indexingRunEventCategoryLabel('BUDGET_EXHAUSTED')).toBe('Budget erschöpft')
  })
})
