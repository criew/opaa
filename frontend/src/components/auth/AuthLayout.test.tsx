import { screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import AuthLayout, { LOGIN_BACKDROP_SCRIM_OPACITY } from './AuthLayout'
import { OPAA_BRANDING, useBrandingStore } from '../../stores/brandingStore'

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
 * Die Markenfläche ist über eine Emotion-Klasse gestaltet, nicht über ein `style`-Attribut — der
 * Wert steht deshalb im eingefügten Stylesheet, nicht am Element. Gelesen wird die eine
 * `background-image`-Deklaration, die zu dieser Fläche gehört; Emotions Stylesheet überdauert den
 * einzelnen Testfall, deshalb wird der Eintrag mit Bild zuerst gesucht.
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
    declarations.find((value) => value.includes('radial-gradient'))
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

    // Genau einmal: Beide Zweige gleichzeitig zu bauen und einen davon zu verstecken, hinterliesse
    // den Namen doppelt im Baum - fuer Screenreader und fuer Abfragen nach Text.
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

    // #583: Passwort-Festlegen und E-Mail-Bestaetigung werden kalt aus einer E-Mail erreicht - auf
    // jeder Breite muss erkennbar bleiben, wessen Installation fragt.
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

  /** #1910: Ohne hinterlegtes Bild bleibt die Fläche, wie sie war. */
  it('keeps today’s panel where no background image is configured', () => {
    renderWithProviders(
      <AuthLayout>
        <p>Formular</p>
      </AuthLayout>,
    )

    const background = panelBackgroundImage()
    expect(background).toContain('radial-gradient')
    expect(background).not.toContain('url(')
  })

  /** #1910: Über einem hinterlegten Bild liegt der feste Schleier — vor dem Bild, nicht dahinter. */
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
