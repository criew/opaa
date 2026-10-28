import { render, screen } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import { describe, expect, it } from 'vitest'
import { createAppTheme } from '../theme/theme'
import { darkRoles, fontSize, lightRoles, radius } from '../theme/tokens'
import GlobalBadge from './GlobalBadge'
import MetaBadge from './MetaBadge'

/**
 * Guidelines 5.5: chips and badges are pills in 12 px and weight 500. The hand-built badges must
 * read the same as the theme's `Chip` beside them - neither square, nor in a different size or
 * weight.
 */
describe('badge shape', () => {
  function shapeOf(element: HTMLElement) {
    const style = getComputedStyle(element)
    return { radius: style.borderRadius, weight: style.fontWeight, size: style.fontSize }
  }

  const pill = { radius: `${radius.pill}px`, weight: '500', size: `${fontSize.xs}px` }

  it.each(['light', 'dark'] as const)('MetaBadge is a pill in 12 px, weight 500 (%s)', (scheme) => {
    render(
      <ThemeProvider theme={createAppTheme(scheme)}>
        <MetaBadge>Eigentümer</MetaBadge>
      </ThemeProvider>,
    )
    expect(shapeOf(screen.getByText('Eigentümer'))).toEqual(pill)
  })

  it('GlobalBadge is a pill in 12 px, weight 500', () => {
    render(
      <ThemeProvider theme={createAppTheme('light')}>
        <GlobalBadge />
      </ThemeProvider>,
    )
    expect(shapeOf(screen.getByText('Global'))).toEqual(pill)
  })

  it('matches the radius, size and weight of the theme chip', () => {
    render(
      <ThemeProvider theme={createAppTheme('light')}>
        <Chip label="Anhang" size="small" variant="outlined" />
        <MetaBadge>Kurator</MetaBadge>
      </ThemeProvider>,
    )
    const chip = screen.getByText('Anhang').closest<HTMLElement>('.MuiChip-root')
    expect(chip).not.toBeNull()
    expect(shapeOf(chip!)).toEqual(pill)
    expect(shapeOf(screen.getByText('Kurator'))).toEqual(shapeOf(chip!))
  })

  it.each(['light', 'dark'] as const)(
    'MetaBadge and GlobalBadge are outlined in border-strong with fg-2 text, never in the accent (%s)',
    (scheme) => {
      const roles = scheme === 'light' ? lightRoles : darkRoles
      render(
        <ThemeProvider theme={createAppTheme(scheme)}>
          <Box component="span" sx={{ color: roles.fg2, border: 1, borderColor: roles.borderStrong }}>
            Bezug
          </Box>
          <MetaBadge>Verwalter</MetaBadge>
          <GlobalBadge />
        </ThemeProvider>,
      )
      const reference = getComputedStyle(screen.getByText('Bezug'))
      for (const text of ['Verwalter', 'Global']) {
        const style = getComputedStyle(screen.getByText(text))
        expect(style.color).toBe(reference.color)
        expect(style.borderTopColor).toBe(reference.borderTopColor)
        expect(style.backgroundColor).toBe('')
      }
    },
  )

  it('lets a badge wrap inside a narrow table cell instead of being cut off', () => {
    render(
      <ThemeProvider theme={createAppTheme('light')}>
        <MetaBadge>Notanker</MetaBadge>
        <MetaBadge wrap>Vom Anbieter geführt</MetaBadge>
      </ThemeProvider>,
    )
    expect(getComputedStyle(screen.getByText('Notanker')).whiteSpace).toBe('nowrap')
    const wrapping = getComputedStyle(screen.getByText('Vom Anbieter geführt'))
    expect(wrapping.whiteSpace).toBe('normal')
    expect(wrapping.maxWidth).toBe('100%')
  })
})
