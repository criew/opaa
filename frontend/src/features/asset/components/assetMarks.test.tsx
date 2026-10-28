import { render, screen } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import Box from '@mui/material/Box'
import { describe, expect, it } from 'vitest'
import { createAppTheme } from '@/shared/theme/theme'
import { darkRoles, fontSize, lightRoles, radius } from '@/shared/theme/tokens'
import { ASSET_TYPES } from '@/shared/assetTypes'
import { ErasingMark, PrivateMark, TypeBadge } from './assetMarks'

/** Guidelines 5.5: the marks on a tile are pills in 12 px and weight 500 like every other badge. */
describe('asset marks', () => {
  function renderMarks(scheme: 'light' | 'dark') {
    const definition = ASSET_TYPES[0]
    const roles = scheme === 'light' ? lightRoles : darkRoles
    render(
      <ThemeProvider theme={createAppTheme(scheme)}>
        <Box component="span" sx={{ border: 1, borderColor: roles.borderStrong }}>
          Bezug
        </Box>
        <TypeBadge definition={definition} />
        <PrivateMark />
        <ErasingMark />
      </ThemeProvider>,
    )
    return [
      screen.getByText(definition.title),
      screen.getByRole('img', { name: /Private Bibliothek/ }),
      screen.getByRole('img', { name: 'Wird gelöscht' }),
    ]
  }

  it.each(['light', 'dark'] as const)('are pills in 12 px and weight 500 (%s)', (scheme) => {
    for (const mark of renderMarks(scheme)) {
      const style = getComputedStyle(mark)
      expect(style.borderRadius).toBe(`${radius.pill}px`)
      expect(style.fontWeight).toBe('500')
      expect(style.fontSize).toBe(`${fontSize.xs}px`)
    }
  })

  it.each(['light', 'dark'] as const)(
    'are framed in border-strong, „Wird gelöscht" included - no signal colour (%s)',
    (scheme) => {
      const marks = renderMarks(scheme)
      const reference = getComputedStyle(screen.getByText('Bezug')).borderTopColor
      for (const mark of marks) {
        expect(getComputedStyle(mark).borderTopColor).toBe(reference)
      }
    },
  )
})
