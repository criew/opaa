import { describe, it, expect, beforeEach, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { FieldValidationError } from '../services/authApi'
import { LOCAL_ACCOUNTS_DISABLED } from '../types/auth'
import ChangePasswordPage from './ChangePasswordPage'

/**
 * ADR-0033, Entscheidung 8: the forced change names its reason as a plain sentence, and a
 * successful change ends in a working session rather than a new sign-in.
 */
describe('ChangePasswordPage', () => {
  // The store actions are real again for every test; a spy from the previous one would silently
  // make the next assertion about nothing.
  const { changePassword, logout } = useAuthStore.getState()

  beforeEach(() => {
    useAuthStore.setState({
      mode: 'oidc',
      user: null,
      token: 'local-token',
      isAuthenticated: true,
      isLoading: false,
      isSigningIn: false,
      error: null,
      providers: [],
      userManager: null,
      activeProviderId: null,
      localAccounts: { ...LOCAL_ACCOUNTS_DISABLED, enabled: true, passwordMinLength: 12 },
      sessionKind: 'local',
      passwordChangeRequired: false,
      passwordChangeReason: null,
      changePassword,
      logout,
    })
  })

  it.each([
    ['INITIAL', 'Bitte legen Sie Ihr erstes Passwort fest.'],
    ['ADMIN_RESET', 'Ihr Passwort wurde von der Systemverwaltung zurückgesetzt.'],
    ['SECURITY', 'Aus Sicherheitsgründen ist ein neues Passwort erforderlich.'],
  ])('names the reason %s of a forced change as a plain sentence', (reason, sentence) => {
    useAuthStore.setState({
      passwordChangeRequired: true,
      passwordChangeReason: reason as never,
    })
    renderWithProviders(<ChangePasswordPage />, { withRouter: true })

    expect(screen.getByText(sentence)).toBeInTheDocument()
  })

  it('shows the policy the new password has to meet', () => {
    renderWithProviders(<ChangePasswordPage />, { withRouter: true })
    expect(screen.getByText(/Mindestens 12 Zeichen, höchstens 64/)).toBeInTheDocument()
  })

  it('refuses two differing entries without calling the backend', async () => {
    const changePassword = vi.fn()
    useAuthStore.setState({ changePassword })
    renderWithProviders(<ChangePasswordPage />, { withRouter: true })

    await userEvent.type(screen.getByLabelText('Aktuelles Passwort'), 'alt')
    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'ein-neues-passwort')
    await userEvent.type(screen.getByLabelText('Neues Passwort wiederholen'), 'vertippt')
    await userEvent.click(screen.getByRole('button', { name: 'Passwort speichern' }))

    expect(changePassword).not.toHaveBeenCalled()
    expect(screen.getByText('Die beiden Eingaben stimmen nicht überein.')).toBeInTheDocument()
  })

  it('sends the change and continues into the application', async () => {
    const changePassword = vi.fn().mockResolvedValue(undefined)
    useAuthStore.setState({ changePassword })
    renderWithProviders(
      <Routes>
        <Route path="/account/password" element={<ChangePasswordPage />} />
        <Route path="/chat" element={<div>Chat</div>} />
      </Routes>,
      { withRouter: true, initialRoute: '/account/password' },
    )

    await userEvent.type(screen.getByLabelText('Aktuelles Passwort'), 'alt')
    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'ein-neues-passwort')
    await userEvent.type(screen.getByLabelText('Neues Passwort wiederholen'), 'ein-neues-passwort')
    await userEvent.click(screen.getByRole('button', { name: 'Passwort speichern' }))

    expect(changePassword).toHaveBeenCalledWith('alt', 'ein-neues-passwort')
    expect(await screen.findByText('Chat')).toBeInTheDocument()
  })

  it('shows a policy violation at the offending field', async () => {
    const changePassword = vi
      .fn()
      .mockRejectedValue(
        new FieldValidationError(
          [{ field: 'newPassword', code: 'TOO_COMMON', message: 'zu häufig' }],
          'Ungültig',
        ),
      )
    useAuthStore.setState({ changePassword })
    renderWithProviders(<ChangePasswordPage />, { withRouter: true })

    await userEvent.type(screen.getByLabelText('Aktuelles Passwort'), 'alt')
    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'passwort1234')
    await userEvent.type(screen.getByLabelText('Neues Passwort wiederholen'), 'passwort1234')
    await userEvent.click(screen.getByRole('button', { name: 'Passwort speichern' }))

    expect(
      await screen.findByText('Dieses Passwort kommt zu häufig vor. Bitte wählen Sie ein anderes.'),
    ).toBeInTheDocument()
  })

  it('falls back to the message when a 400 carries no field errors', async () => {
    const changePassword = vi
      .fn()
      .mockRejectedValue(new FieldValidationError([], 'Das Passwort wurde nicht angenommen.'))
    useAuthStore.setState({ changePassword })
    renderWithProviders(<ChangePasswordPage />, { withRouter: true })

    await userEvent.type(screen.getByLabelText('Aktuelles Passwort'), 'alt')
    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'ein-neues-passwort')
    await userEvent.type(screen.getByLabelText('Neues Passwort wiederholen'), 'ein-neues-passwort')
    await userEvent.click(screen.getByRole('button', { name: 'Passwort speichern' }))

    expect(await screen.findByText('Das Passwort wurde nicht angenommen.')).toBeInTheDocument()
  })

  it('sends a signed-out visitor to the sign-in page', () => {
    useAuthStore.setState({ isAuthenticated: false })
    renderWithProviders(
      <Routes>
        <Route path="/account/password" element={<ChangePasswordPage />} />
        <Route path="/login" element={<div>Anmeldung</div>} />
      </Routes>,
      { withRouter: true, initialRoute: '/account/password' },
    )

    expect(screen.getByText('Anmeldung')).toBeInTheDocument()
  })

  // The forced change locks every other route - without a way out the only escape would be
  // closing the browser.
  it('offers a way out of a forced change', async () => {
    const logoutSpy = vi.fn().mockResolvedValue(undefined)
    useAuthStore.setState({
      passwordChangeRequired: true,
      passwordChangeReason: 'INITIAL',
      logout: logoutSpy,
    })
    renderWithProviders(<ChangePasswordPage />, { withRouter: true })

    await userEvent.click(screen.getByRole('button', { name: 'Abmelden' }))

    expect(logoutSpy).toHaveBeenCalled()
  })

  it('points a provider session at its provider instead of offering a mask', () => {
    useAuthStore.setState({ sessionKind: 'oidc' })
    renderWithProviders(<ChangePasswordPage />, { withRouter: true })

    expect(screen.getByText(/verwaltet Ihr Identitätsanbieter/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Neues Passwort')).not.toBeInTheDocument()
  })
})
