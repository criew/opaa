import type { ReactElement } from 'react'
import { render, screen } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import { describe, expect, it } from 'vitest'
import { createAppTheme } from '../theme/theme'
import MobileHeader from './MobileHeader'

function renderInLightMode(ui: ReactElement) {
  return render(<ThemeProvider theme={createAppTheme('light')}>{ui}</ThemeProvider>)
}

describe('MobileHeader', () => {
  // Regression test for #193: the AppBar only set `bgcolor`, so the foreground stayed the
  // inherited white `primary.contrastText` - white icon on a white surface in light mode.
  it('renders the menu icon with a readable foreground in light mode', () => {
    renderInLightMode(<MobileHeader />)

    const menuButton = screen.getByRole('button', { name: 'Menü öffnen' })

    expect(getComputedStyle(menuButton).color).toBe('rgb(1, 33, 66)')
  })

  it('renders the AppBar itself with the same readable foreground', () => {
    renderInLightMode(<MobileHeader />)

    const appBar = screen.getByRole('banner')

    expect(getComputedStyle(appBar).color).toBe('rgb(1, 33, 66)')
  })
})
