import { render, screen } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import Chip from '@mui/material/Chip'
import { describe, expect, it } from 'vitest'
import { createAppTheme } from '../theme/theme'
import { radius } from '../theme/tokens'
import GlobalBadge from './GlobalBadge'
import MetaBadge from './MetaBadge'

/**
 * Guidelines 5.5: chips and badges are pills with weight 500. The hand-built badges must read the
 * same as the theme's `Chip` beside them - neither square nor in a different weight.
 */
describe('badge shape', () => {
  function shapeOf(element: HTMLElement) {
    const style = getComputedStyle(element)
    return { radius: style.borderRadius, weight: style.fontWeight }
  }

  const pill = { radius: `${radius.pill}px`, weight: '500' }

  it.each(['light', 'dark'] as const)('MetaBadge is a pill in weight 500 (%s)', (scheme) => {
    render(
      <ThemeProvider theme={createAppTheme(scheme)}>
        <MetaBadge>Eigentümer</MetaBadge>
        <MetaBadge accent>Verwalter</MetaBadge>
      </ThemeProvider>,
    )
    expect(shapeOf(screen.getByText('Eigentümer'))).toEqual(pill)
    expect(shapeOf(screen.getByText('Verwalter'))).toEqual(pill)
  })

  it('GlobalBadge is a pill in weight 500', () => {
    render(
      <ThemeProvider theme={createAppTheme('light')}>
        <GlobalBadge />
      </ThemeProvider>,
    )
    expect(shapeOf(screen.getByText('Global'))).toEqual(pill)
  })

  it('matches the radius and weight of the theme chip', () => {
    render(
      <ThemeProvider theme={createAppTheme('light')}>
        <Chip label="Archiviert" size="small" variant="outlined" />
        <MetaBadge>Kurator</MetaBadge>
      </ThemeProvider>,
    )
    const chip = screen.getByText('Archiviert').closest<HTMLElement>('.MuiChip-root')
    expect(chip).not.toBeNull()
    expect(shapeOf(screen.getByText('Kurator'))).toEqual(shapeOf(chip!))
  })
})
