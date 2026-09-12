import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import ChangePasswordPage from './ChangePasswordPage'
import SettingsPage from './SettingsPage'
import { useAuthStore } from '../stores/authStore'
import { useUiStore } from '../stores/uiStore'
import { OPAA_BRANDING, useBrandingStore } from '../stores/brandingStore'
import { mockLocalAccount } from '../mocks/fixtures'

function renderPage() {
  return renderWithProviders(<SettingsPage />, { withRouter: true, initialRoute: '/settings' })
}

describe('SettingsPage', () => {
  beforeEach(() => {
    useBrandingStore.setState({ branding: OPAA_BRANDING })
    useUiStore.setState({ themeMode: null })
    useAuthStore.setState({
      user: {
        id: 'user-1',
        email: 'b.wagner@example.de',
        displayName: 'B. Wagner',
        systemRole: 'USER',
      },
      mode: 'oidc',
      isAuthenticated: true,
      isLoading: false,
      sessionKind: 'oidc',
    })
  })

  it('renders as a global page: heading, badge and scope note (mockup 2c, #788)', () => {
    renderPage()

    expect(
      screen.getByRole('heading', { level: 1, name: 'Ihre Einstellungen' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Global')).toBeInTheDocument()
    expect(screen.getByText('Gelten für Sie persönlich in allen Spaces.')).toBeInTheDocument()
  })

  it('shows the profile block with name, address and sign-in method - display only', () => {
    renderPage()

    expect(screen.getByText('B. Wagner')).toBeInTheDocument()
    expect(
      screen.getByText('b.wagner@example.de · über Verzeichnisdienst angemeldet'),
    ).toBeInTheDocument()
    // Editing name, language or picture needs backend support that does not exist (Abgrenzung).
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByText('Bild ändern')).not.toBeInTheDocument()
  })

  it('names the dev sign-in without a technical mode string', () => {
    useAuthStore.setState({ mode: 'dev' })
    renderPage()

    expect(
      screen.getByText('b.wagner@example.de · über Entwicklungsanmeldung angemeldet'),
    ).toBeInTheDocument()
  })

  it('keeps the colour scheme choice including the way back to the operator default', async () => {
    const user = userEvent.setup()
    renderPage()

    expect(screen.getByRole('group', { name: 'Farbschema' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Dunkles Farbschema' }))

    expect(useUiStore.getState().themeMode).toBe('dark')

    // #800 (review #795, finding 2): actually walk the way back - a broken onClick wiring
    // stayed green while only the button's presence was asserted.
    await user.click(screen.getByRole('button', { name: 'Vorgabe des Hauses übernehmen' }))

    expect(useUiStore.getState().themeMode).toBeNull()
    expect(
      screen.queryByRole('button', { name: 'Vorgabe des Hauses übernehmen' }),
    ).not.toBeInTheDocument()
  })

  it('survives an empty display name from the IdP instead of crashing (#800)', () => {
    // review #795 finding 5: `('')[0].toUpperCase()` threw before userInitial guarded it -
    // `??` only skips null/undefined, an empty string walked straight into the indexing.
    useAuthStore.setState({
      user: { id: 'user-4', email: 'x@y.example', displayName: '', systemRole: 'USER' },
      mode: 'oidc',
    })
    renderPage()

    expect(screen.getAllByText(/x@y\.example/)).toHaveLength(1)
  })

  it('does not repeat the e-mail when it already serves as the name line', () => {
    // #800 (review #795, finding 1): without a displayName the name line falls back to the
    // address - the meta line must then carry only the sign-in method.
    useAuthStore.setState({
      user: { id: 'user-3', email: 'x@y.example', displayName: null, systemRole: 'USER' },
      mode: 'oidc',
    })
    renderPage()

    expect(screen.getAllByText(/x@y\.example/)).toHaveLength(1)
    expect(screen.getByText('über Verzeichnisdienst angemeldet')).toBeInTheDocument()
  })

  it('points system administrators at the branding page and everyone else at their admin', () => {
    const { unmount } = renderPage()
    expect(screen.queryByRole('link', { name: 'Branding' })).not.toBeInTheDocument()
    unmount()

    useAuthStore.setState({
      user: {
        id: 'user-2',
        email: 'a@b.example',
        displayName: 'Admin',
        systemRole: 'SYSTEM_ADMIN',
      },
    })
    renderPage()
    expect(screen.getByRole('link', { name: 'Branding' })).toHaveAttribute(
      'href',
      '/admin/branding',
    )
  })
  // ADR-0033, Entscheidung 11: the creation reason belongs to the person's own self-disclosure.
  it('shows the creation reason a system administrator recorded', () => {
    useAuthStore.setState({
      user: {
        id: 'user-1',
        email: 'b.wagner@example.de',
        displayName: 'B. Wagner',
        systemRole: 'USER',
        createdReason: 'Projektbefristung Digitalisierung bis 31.12.2026',
      },
      sessionKind: 'local',
    })
    renderPage()

    expect(screen.getByText('Anlass des Kontos')).toBeInTheDocument()
    expect(screen.getByText('Projektbefristung Digitalisierung bis 31.12.2026')).toBeInTheDocument()
  })

  it('leaves the creation reason out for an account that has none', () => {
    renderPage()
    expect(screen.queryByText('Anlass des Kontos')).not.toBeInTheDocument()
  })

  /**
   * The password section belongs to local accounts only (#1540). Walked rather than asserted on the
   * `href`: what matters is that the button reaches the page **and** hands it the way back, so a
   * voluntary change ends here instead of on the chat page - an `href` assertion would pass even if
   * the return target were missing.
   */
  it('leads a local session to the password page and back again', async () => {
    useAuthStore.setState({ sessionKind: 'local', token: 'lokales-token' })
    renderWithProviders(
      <Routes>
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/account/password" element={<ChangePasswordPage />} />
      </Routes>,
      // The password page brings an AuthLayout with a NotificationHost of its own; a second host
      // from the helper would show every popup twice.
      { withRouter: true, initialRoute: '/settings', withNotificationHost: false },
    )

    await userEvent.click(screen.getByRole('link', { name: 'Passwort ändern' }))
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Passwort ändern' }),
    ).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Aktuelles Passwort'), mockLocalAccount.password)
    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'Sommerregen-42x')
    await userEvent.type(screen.getByLabelText('Neues Passwort wiederholen'), 'Sommerregen-42x')
    await userEvent.click(screen.getByRole('button', { name: 'Passwort speichern' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Ihre Einstellungen' }),
    ).toBeInTheDocument()
  })

  it('points a provider session at its provider instead', () => {
    renderPage()

    expect(screen.queryByRole('link', { name: 'Passwort ändern' })).not.toBeInTheDocument()
    expect(screen.getByText(/Ihr Passwort verwaltet der Identitätsanbieter/)).toBeInTheDocument()
  })

  it('says nothing about passwords in the development sign-in', () => {
    useAuthStore.setState({ mode: 'dev', sessionKind: null })
    renderPage()

    expect(screen.queryByRole('link', { name: 'Passwort ändern' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Ihr Passwort verwaltet/)).not.toBeInTheDocument()
  })
})
