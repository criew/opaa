import type { ReactElement } from 'react'
import { render, screen } from '@testing-library/react'
import { hexToRgb, ThemeProvider } from '@mui/material/styles'
import type { PaletteMode } from '@mui/material'
import { describe, expect, it } from 'vitest'
import { createAppTheme } from '../theme/theme'
import MobileHeader from './MobileHeader'

function renderInMode(mode: PaletteMode, ui: ReactElement) {
  return render(<ThemeProvider theme={createAppTheme(mode)}>{ui}</ThemeProvider>)
}

/** The theme's `text.primary` as jsdom's `getComputedStyle` would report it, not a hardcoded value - so
 * this test only breaks when the actual defect (mismatched foreground) recurs, not when a token changes. */
function expectedForeground(mode: PaletteMode) {
  return hexToRgb(createAppTheme(mode).palette.text.primary)
}

describe.each<PaletteMode>(['light', 'dark'])('MobileHeader (%s mode)', (mode) => {
  // Regression test for #193: the AppBar only set `bgcolor`, so the foreground stayed the
  // inherited white `primary.contrastText` - white icon on a white surface in light mode. The
  // fix touches the foreground in both schemes, so both are covered here.
  it('renders the menu icon with a readable foreground', () => {
    renderInMode(mode, <MobileHeader />)

    const menuButton = screen.getByRole('button', { name: 'Menü öffnen' })

    expect(getComputedStyle(menuButton).color).toBe(expectedForeground(mode))
  })

  it('renders the AppBar itself with the same readable foreground', () => {
    renderInMode(mode, <MobileHeader />)

    const appBar = screen.getByRole('banner')

    expect(getComputedStyle(appBar).color).toBe(expectedForeground(mode))
  })
})
