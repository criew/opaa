import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { ThemeProvider } from '@mui/material/styles'
import { createAppTheme } from '../theme/theme'
import BrandMark from './BrandMark'
import { OPAA_BRANDING, useBrandingStore } from '../stores/brandingStore'

const theme = createAppTheme('light')

function renderMark(ui: React.ReactElement = <BrandMark />) {
  return render(<ThemeProvider theme={theme}>{ui}</ThemeProvider>)
}

// This coverage lived in Sidebar.test until #786; the sidebar no longer renders the mark, so
// the component carries its own contract tests now.
describe('BrandMark', () => {
  beforeEach(() => {
    useBrandingStore.setState({ branding: OPAA_BRANDING })
  })

  it('renders the OPAA standard name without the claim by default', () => {
    renderMark()

    expect(screen.getByText('OPAA')).toBeInTheDocument()
    expect(screen.queryByText('Fragen. Belegen. Entscheiden.')).not.toBeInTheDocument()
  })

  it('follows a configured branding with name and logo', () => {
    useBrandingStore.setState({
      branding: {
        productName: 'Landesamt-Assistent',
        claim: 'Kurz und klar',
        primaryColor: '#7A1FA2',
        defaultColorScheme: 'LIGHT',
        logoUrl: '/api/v1/branding/logo?v=abc',
      },
    })

    renderMark()

    expect(screen.getByText('Landesamt-Assistent')).toBeInTheDocument()
    expect(screen.queryByText('OPAA')).not.toBeInTheDocument()
    // The name sits right next to the logo, so the image stays decorative (WCAG 1.1.1).
    expect(document.querySelector('img[alt=""]')).toHaveAttribute(
      'src',
      '/api/v1/branding/logo?v=abc',
    )
  })

  it('caps a configured logo to the rail tile width in logoOnly mode (review #791)', () => {
    useBrandingStore.setState({
      branding: {
        productName: 'Landesamt-Assistent',
        claim: 'Kurz und klar',
        primaryColor: '#7A1FA2',
        defaultColorScheme: 'LIGHT',
        logoUrl: '/api/v1/branding/logo?v=abc',
      },
    })

    renderMark(<BrandMark logoOnly />)

    // A wide wordmark logo must not bleed out of the 64px rail (sidebar context allows 160px).
    expect(document.querySelector('img[alt=""]')).toHaveStyle({ maxWidth: '40px' })
  })

  it('renders the emblem alone in logoOnly mode - the rail tile (#786)', () => {
    renderMark(<BrandMark logoOnly />)

    expect(screen.queryByText('OPAA')).not.toBeInTheDocument()
    expect(document.querySelector('svg')).toBeInTheDocument()
  })
})
