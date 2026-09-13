import { screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import AuthLayout from './AuthLayout'

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

describe('AuthLayout', () => {
  const originalMatchMedia = window.matchMedia

  beforeEach(() => {
    window.matchMedia = desktopMatchMedia
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
})
