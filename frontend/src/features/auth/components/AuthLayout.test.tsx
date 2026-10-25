import { screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
import AuthLayout, {
  LOGIN_BACKDROP_DEFAULT_SCRIM_OPACITY,
  LOGIN_BACKDROP_SCRIM_OPACITY,
} from './AuthLayout'
import { OPAA_BRANDING, useBrandingStore } from '@/shared/branding/brandingStore'

// jsdom has no matchMedia, so `useMediaQuery` would always report the narrow branch and the wide
// layout would never be under test. Both branches are exercised explicitly below.
function matchMediaWith(matcher: (query: string) => boolean) {
  return (query: string): MediaQueryList => ({
    matches: matcher(query),
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })
}

const desktopMatchMedia = matchMediaWith((query) => query.includes('min-width'))
const mobileMatchMedia = matchMediaWith(() => false)

/**
 * The brand area is styled via an Emotion class, not via a `style` attribute — the
 * value is therefore in the injected stylesheet, not on the element. What is read is the one
 * `background-image` declaration that belongs to this area; Emotion's stylesheet outlasts the
 * single test case, so the entry with an uploaded image is looked for first, then the one with the
 * bundled backdrop.
 */
function panelBackgroundImage(): string {
  const css = Array.from(document.querySelectorAll('style'))
    .map((element) => element.textContent ?? '')
    .join('')
  const declarations = [...css.matchAll(/background-image:([^;}]*)/g)].map((match) =>
    match[1].trim(),
  )
  const panel =
    declarations.find((value) => value.includes('login-background')) ??
    declarations.find((value) => value.includes('login-backdrop'))
  expect(panel, 'die Markenfläche setzt ein background-image').toBeDefined()
  return panel as string
}

describe('AuthLayout', () => {
  const originalMatchMedia = window.matchMedia

  beforeEach(() => {
    window.matchMedia = desktopMatchMedia
    useBrandingStore.setState({ branding: OPAA_BRANDING })
  })

  afterEach(() => {
    window.matchMedia = originalMatchMedia
  })

  it('shows the mark once beside the form on a wide viewport', () => {
    renderWithProviders(
      <AuthLayout>
        <p>Formular</p>
      </AuthLayout>,
    )

    // Exactly once: building both branches at the same time and hiding one of them would leave
    // the name twice in the tree - for screen readers and for queries by text.
    expect(screen.getAllByText('OPAA')).toHaveLength(1)
    expect(screen.getByText('Formular')).toBeInTheDocument()
  })

  it('keeps the mark on a narrow viewport, where the brand panel is gone', () => {
    window.matchMedia = mobileMatchMedia
    renderWithProviders(
      <AuthLayout>
        <p>Formular</p>
      </AuthLayout>,
    )

    // Setting a password and confirming an email are reached cold from an email - at
    // every width it must stay recognizable whose installation is asking.
    expect(screen.getAllByText('OPAA')).toHaveLength(1)
    expect(screen.getByText('Formular')).toBeInTheDocument()
  })

  it('leaves the mark out of the heading outline', () => {
    renderWithProviders(
      <AuthLayout>
        <h1>Anmelden</h1>
      </AuthLayout>,
    )

    expect(screen.getByRole('heading', { level: 1, name: 'Anmelden' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'OPAA' })).toBeNull()
  })

  /** Without an uploaded image the bundled backdrop carries the area, under its own scrim. */
  it('shows the bundled backdrop under the lighter scrim where no image is configured', () => {
    renderWithProviders(
      <AuthLayout>
        <p>Formular</p>
      </AuthLayout>,
    )

    const background = panelBackgroundImage()
    expect(background).toContain('login-backdrop')
    expect(background).not.toContain('login-background')
    expect(background.indexOf('gradient')).toBeLessThan(background.indexOf('url('))
    expect(background).toContain(String(LOGIN_BACKDROP_DEFAULT_SCRIM_OPACITY))
    expect(background).not.toContain(String(LOGIN_BACKDROP_SCRIM_OPACITY))
  })

  /** On top of an uploaded image lies the fixed scrim — in front of the image, not behind it. */
  it('lays the fixed scrim over a configured background image', () => {
    useBrandingStore.setState({
      branding: {
        ...OPAA_BRANDING,
        loginBackgroundUrl: '/api/v1/branding/login-background?v=abc',
      },
    })

    renderWithProviders(
      <AuthLayout>
        <p>Formular</p>
      </AuthLayout>,
    )

    const background = panelBackgroundImage()
    expect(background).toContain('/api/v1/branding/login-background?v=abc')
    expect(background.indexOf('gradient')).toBeLessThan(background.indexOf('url('))
    expect(background).toContain(String(LOGIN_BACKDROP_SCRIM_OPACITY))
  })
})
