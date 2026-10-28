import { render, screen } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import { describe, expect, it } from 'vitest'
import { createAppTheme } from '@/shared/theme/theme'
import { radius } from '@/shared/theme/tokens'
import { ASSET_TYPES } from '@/shared/assetTypes'
import { ErasingMark, PrivateMark, TypeBadge } from './assetMarks'

/** Guidelines 5.5: the marks on a tile are pills in weight 500 like every other badge. */
describe('asset marks', () => {
  it.each(['light', 'dark'] as const)('are pills in weight 500 (%s)', (scheme) => {
    const definition = ASSET_TYPES[0]
    render(
      <ThemeProvider theme={createAppTheme(scheme)}>
        <TypeBadge definition={definition} />
        <PrivateMark />
        <ErasingMark />
      </ThemeProvider>,
    )
    const marks = [
      screen.getByText(definition.title),
      screen.getByRole('img', { name: /Private Bibliothek/ }),
      screen.getByRole('img', { name: 'Wird gelöscht' }),
    ]
    for (const mark of marks) {
      const style = getComputedStyle(mark)
      expect(style.borderRadius).toBe(`${radius.pill}px`)
      expect(style.fontWeight).toBe('500')
    }
  })
})
