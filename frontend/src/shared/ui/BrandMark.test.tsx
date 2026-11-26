import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { ThemeProvider } from '@mui/material/styles'
import { createAppTheme } from '../theme/theme'
import BrandMark from './BrandMark'
import { MARK, WORDMARK } from './brandMarkPaths'
import { OPAA_BRANDING, useBrandingStore } from '../branding/brandingStore'

const theme = createAppTheme('light')

function renderMark(ui: React.ReactElement = <BrandMark />) {
  return render(<ThemeProvider theme={theme}>{ui}</ThemeProvider>)
}

// The sidebar does not render the mark, so the component carries its own contract tests.
describe('BrandMark', () => {
  beforeEach(() => {
    useBrandingStore.setState({ branding: OPAA_BRANDING })
  })

  it('renders the opaa wordmark, named for screen readers, without the claim by default', () => {
    renderMark()

    expect(screen.getByRole('img', { name: 'opaa' })).toBeInTheDocument()
    expect(screen.queryByText('opaa')).not.toBeInTheDocument()
    expect(
      screen.queryByText('opaa weiß, was war, und hilft bei dem, was kommt.'),
    ).not.toBeInTheDocument()
  })

  it('follows a configured branding with name and logo', () => {
    useBrandingStore.setState({
      branding: {
        productName: 'Landesamt-Assistent',
        claim: 'Kurz und klar',
        aiNotice: 'Hinweis der Behörde',
        aiNoticeDefault: 'Standardhinweis',
        primaryColor: '#7A1FA2',
        logoUrl: '/api/v1/branding/logo?v=abc',
      },
    })

    renderMark()

    expect(screen.getByText('Landesamt-Assistent')).toBeInTheDocument()
    expect(screen.queryByText('opaa')).not.toBeInTheDocument()
    // The name sits right next to the logo, so the image stays decorative (WCAG 1.1.1).
    expect(document.querySelector('img[alt=""]')).toHaveAttribute(
      'src',
      '/api/v1/branding/logo?v=abc',
    )
  })

  /**
   * The login page has its own logo because it shows it many times larger.
   * Without its own it falls back to the application's - and without either to the OPAA mark.
   */
  it('prefers the sign-in logo where one is configured, and falls back where none is', () => {
    const branding = {
      productName: 'Landesamt-Assistent',
      claim: 'Kurz und klar',
      aiNotice: 'Hinweis der Behörde',
      aiNoticeDefault: 'Standardhinweis',
      primaryColor: '#7A1FA2',
      logoUrl: '/api/v1/branding/logo?v=abc',
    }

    useBrandingStore.setState({
      branding: { ...branding, loginLogoUrl: '/api/v1/branding/login-logo?v=def' },
    })
    const { unmount } = renderMark(<BrandMark preferLoginLogo />)
    expect(document.querySelector('img[alt=""]')).toHaveAttribute(
      'src',
      '/api/v1/branding/login-logo?v=def',
    )
    unmount()

    useBrandingStore.setState({ branding })
    const withoutOwn = renderMark(<BrandMark preferLoginLogo />)
    expect(withoutOwn.container.querySelector('img[alt=""]')).toHaveAttribute(
      'src',
      '/api/v1/branding/logo?v=abc',
    )
    withoutOwn.unmount()

    // And where nothing is configured, it stays the OPAA mark - an <svg>, not an <img>.
    useBrandingStore.setState({ branding: OPAA_BRANDING })
    const standard = renderMark(<BrandMark preferLoginLogo />)
    expect(standard.container.querySelector('img')).toBeNull()
  })

  /** The sidebar keeps showing the application's logo, even when a login logo exists. */
  it('ignores the sign-in logo where it is not asked for', () => {
    useBrandingStore.setState({
      branding: {
        ...OPAA_BRANDING,
        logoUrl: '/api/v1/branding/logo?v=abc',
        loginLogoUrl: '/api/v1/branding/login-logo?v=def',
      },
    })

    const { container } = renderMark()

    expect(container.querySelector('img[alt=""]')).toHaveAttribute(
      'src',
      '/api/v1/branding/logo?v=abc',
    )
  })

  it('caps a configured logo to the rail tile width in logoOnly mode (review #791)', () => {
    useBrandingStore.setState({
      branding: {
        productName: 'Landesamt-Assistent',
        claim: 'Kurz und klar',
        aiNotice: 'Hinweis der Behörde',
        aiNoticeDefault: 'Standardhinweis',
        primaryColor: '#7A1FA2',
        logoUrl: '/api/v1/branding/logo?v=abc',
      },
    })

    renderMark(<BrandMark logoOnly />)

    // A wide wordmark logo must not bleed out of the 64px rail (sidebar context allows 160px).
    expect(document.querySelector('img[alt=""]')).toHaveStyle({ maxWidth: '40px' })
  })

  it('renders the standard wordmark decorative and rail-wide in logoOnly mode (#786)', () => {
    renderMark(<BrandMark logoOnly />)

    expect(screen.queryByText('opaa')).not.toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    const svg = document.querySelector('svg')
    expect(svg).toHaveAttribute('viewBox', `0 0 ${WORDMARK.width} ${WORDMARK.height}`)
    expect(svg).toHaveStyle({ width: '44px' })
  })

  it('shows the underlined o beside a configured name when no logo is configured', () => {
    useBrandingStore.setState({
      branding: { ...OPAA_BRANDING, productName: 'Landesamt-Assistent' },
    })

    const { container } = renderMark()

    expect(screen.getByText('Landesamt-Assistent')).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'opaa' })).not.toBeInTheDocument()
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })

  it('shows only the decorative underlined o in the rail for a configured name', () => {
    useBrandingStore.setState({
      branding: { ...OPAA_BRANDING, productName: 'Landesamt-Assistent' },
    })
    const { container } = renderMark(<BrandMark logoOnly />)

    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.queryByText('Landesamt-Assistent')).not.toBeInTheDocument()
    expect(container.querySelectorAll('svg')).toHaveLength(1)
    expect(container.querySelector('svg')).toHaveAttribute(
      'viewBox',
      `0 0 ${MARK.width} ${MARK.height}`,
    )
  })

  it('draws the standard wordmark without a tagline at every size', () => {
    renderMark(<BrandMark logoHeight={96} />)

    expect(screen.getByRole('img', { name: 'opaa' })).toHaveAttribute(
      'viewBox',
      `0 0 ${WORDMARK.width} ${WORDMARK.height}`,
    )
  })
})
