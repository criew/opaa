import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import SettingsPage from './SettingsPage'
import { useAuthStore } from '../stores/authStore'
import { useUiStore } from '../stores/uiStore'
import { OPAA_BRANDING, useBrandingStore } from '../stores/brandingStore'

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
    expect(
      screen.getByRole('button', { name: 'Vorgabe des Hauses übernehmen' }),
    ).toBeInTheDocument()
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
})
