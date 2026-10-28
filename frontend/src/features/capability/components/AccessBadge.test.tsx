import { render, screen } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import Box from '@mui/material/Box'
import { describe, expect, it } from 'vitest'
import { createAppTheme } from '@/shared/theme/theme'
import { darkRoles, fontSize, lightRoles, radius } from '@/shared/theme/tokens'
import AccessBadge from './AccessBadge'

/**
 * Guidelines 5.5: the state of a right is a label like every other - 12 px, weight 500, quiet.
 * The three states differ in icon, text and outline (filled, solid, dashed), never in the accent.
 */
describe('AccessBadge', () => {
  it.each(['light', 'dark'] as const)('is a quiet pill in 12 px for every state (%s)', (scheme) => {
    const roles = scheme === 'light' ? lightRoles : darkRoles
    render(
      <ThemeProvider theme={createAppTheme(scheme)}>
        <Box component="span" sx={{ color: roles.fg2, bgcolor: roles.bg3 }}>
          Bezug
        </Box>
        <AccessBadge tone="ALL" label="Alle Konten" />
        <AccessBadge tone="SELECTED" label="2 Gruppen" />
        <AccessBadge tone="ADMIN_ONLY" label="Nur Systemverwaltung" />
      </ThemeProvider>,
    )
    const reference = getComputedStyle(screen.getByText('Bezug'))
    for (const label of ['Alle Konten', '2 Gruppen', 'Nur Systemverwaltung']) {
      const style = getComputedStyle(screen.getByText(label))
      expect(style.fontSize).toBe(`${fontSize.xs}px`)
      expect(style.fontWeight).toBe('500')
      expect(style.borderRadius).toBe(`${radius.pill}px`)
      expect(style.color).toBe(reference.color)
    }
    expect(getComputedStyle(screen.getByText('Alle Konten')).backgroundColor).toBe(
      reference.backgroundColor,
    )
    expect(getComputedStyle(screen.getByText('Nur Systemverwaltung')).borderTopStyle).toBe('dashed')
  })
})
