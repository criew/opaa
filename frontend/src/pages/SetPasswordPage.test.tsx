import { beforeEach, describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { LOCAL_ACCOUNTS_DISABLED } from '../types/auth'
import { MOCK_SET_PASSWORD_TOKEN } from '../mocks/localAuthFixtures'
import { PASSWORD_MAX_LENGTH } from '../utils/passwordStrength'
import SetPasswordPage from './SetPasswordPage'

/**
 * ADR-0033, Entscheidung 11: one page for an invitation and for a reset, a neutral heading, and one
 * wording for every link that does not work. The page is reachable whatever the self-service
 * switches say - a link an administrator handed out must not run into a redirect.
 */
describe('SetPasswordPage', () => {
  beforeEach(() => {
    useAuthStore.setState({
      isLoading: false,
      localAccounts: { ...LOCAL_ACCOUNTS_DISABLED, enabled: true, passwordMinLength: 12 },
    })
  })

  function renderAt(search: string) {
    return renderWithProviders(
      <Routes>
        <Route path="/set-password" element={<SetPasswordPage />} />
        <Route path="/login" element={<div>Anmeldung</div>} />
        <Route path="/forgot-password" element={<div>Link anfordern</div>} />
      </Routes>,
      { withRouter: true, initialRoute: `/set-password${search}` },
    )
  }

  it('names neither an invitation nor a reset in its heading', () => {
    renderAt(`?token=${MOCK_SET_PASSWORD_TOKEN}`)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Passwort festlegen')
  })

  it('shows the policy the new password has to meet', () => {
    renderAt(`?token=${MOCK_SET_PASSWORD_TOKEN}`)
    expect(
      screen.getByText(new RegExp(`Mindestens 12 Zeichen, höchstens ${PASSWORD_MAX_LENGTH}`)),
    ).toBeInTheDocument()
  })

  it('sets the password and points at the sign-in page', async () => {
    renderAt(`?token=${MOCK_SET_PASSWORD_TOKEN}`)

    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'Sommerregen-42x')
    await userEvent.type(screen.getByLabelText('Neues Passwort wiederholen'), 'Sommerregen-42x')
    await userEvent.click(screen.getByRole('button', { name: 'Passwort festlegen' }))

    expect(
      await screen.findByText('Passwort festgelegt — Sie können sich jetzt anmelden.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Zur Anmeldung' })).toBeInTheDocument()
  })

  it('refuses two differing entries without reaching the backend', async () => {
    renderAt(`?token=${MOCK_SET_PASSWORD_TOKEN}`)

    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'Sommerregen-42x')
    await userEvent.type(screen.getByLabelText('Neues Passwort wiederholen'), 'vertippt')
    await userEvent.click(screen.getByRole('button', { name: 'Passwort festlegen' }))

    expect(screen.getByText('Die beiden Eingaben stimmen nicht überein.')).toBeInTheDocument()
    expect(screen.getByLabelText('Neues Passwort')).toBeInTheDocument()
  })

  it('shows a policy violation at the field and keeps the link open', async () => {
    renderAt(`?token=${MOCK_SET_PASSWORD_TOKEN}`)

    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'kurz')
    await userEvent.type(screen.getByLabelText('Neues Passwort wiederholen'), 'kurz')
    await userEvent.click(screen.getByRole('button', { name: 'Passwort festlegen' }))

    expect(
      await screen.findByText('Das Passwort muss mindestens 12 Zeichen lang sein.'),
    ).toBeInTheDocument()
    // The form is still there: a refused password must not cost the person their link.
    expect(screen.getByRole('button', { name: 'Passwort festlegen' })).toBeInTheDocument()
  })

  // Several violated rules arrive in one answer, so the form can name all of them at once.
  it('names every violated rule of one answer', async () => {
    renderAt(`?token=${MOCK_SET_PASSWORD_TOKEN}`)

    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'passwort')
    await userEvent.type(screen.getByLabelText('Neues Passwort wiederholen'), 'passwort')
    await userEvent.click(screen.getByRole('button', { name: 'Passwort festlegen' }))

    const helper = await screen.findByText(/mindestens 12 Zeichen/)
    expect(helper).toHaveTextContent('Dieses Passwort kommt zu häufig vor.')
  })

  it('answers an unknown link with the one wording and a way back', async () => {
    renderAt('?token=voellig-unbekannt')

    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'Sommerregen-42x')
    await userEvent.type(screen.getByLabelText('Neues Passwort wiederholen'), 'Sommerregen-42x')
    await userEvent.click(screen.getByRole('button', { name: 'Passwort festlegen' }))

    expect(await screen.findByText('Dieser Link ist nicht mehr gültig.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Zur Anmeldung' })).toBeInTheDocument()
  })

  // A link that has already been redeemed is indistinguishable from an unknown one.
  it('refuses the same link a second time', async () => {
    const { unmount } = renderAt(`?token=${MOCK_SET_PASSWORD_TOKEN}`)
    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'Sommerregen-42x')
    await userEvent.type(screen.getByLabelText('Neues Passwort wiederholen'), 'Sommerregen-42x')
    await userEvent.click(screen.getByRole('button', { name: 'Passwort festlegen' }))
    await screen.findByText('Passwort festgelegt — Sie können sich jetzt anmelden.')
    unmount()

    renderAt(`?token=${MOCK_SET_PASSWORD_TOKEN}`)
    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'Sommerregen-42x')
    await userEvent.type(screen.getByLabelText('Neues Passwort wiederholen'), 'Sommerregen-42x')
    await userEvent.click(screen.getByRole('button', { name: 'Passwort festlegen' }))

    expect(await screen.findByText('Dieser Link ist nicht mehr gültig.')).toBeInTheDocument()
  })

  it('treats a link without a token like an invalid one', () => {
    renderAt('')
    expect(screen.getByText('Dieser Link ist nicht mehr gültig.')).toBeInTheDocument()
  })

  // The way on has to exist: pointing at /forgot-password where the flow is off would send the
  // reader in a circle.
  it('offers a new link only where the installation resets passwords', () => {
    useAuthStore.setState({
      localAccounts: {
        ...LOCAL_ACCOUNTS_DISABLED,
        enabled: true,
        passwordResetEnabled: true,
        passwordMinLength: 12,
      },
    })
    renderAt('')

    expect(screen.getByRole('link', { name: 'Neuen Link anfordern' })).toBeInTheDocument()
  })

  // ADR-0033, Entscheidung 11: the link targets work whatever the switches say.
  it('stays reachable while every self-service switch is off', () => {
    useAuthStore.setState({ localAccounts: LOCAL_ACCOUNTS_DISABLED })
    renderAt(`?token=${MOCK_SET_PASSWORD_TOKEN}`)

    expect(screen.getByRole('button', { name: 'Passwort festlegen' })).toBeInTheDocument()
    expect(screen.queryByText('Anmeldung')).not.toBeInTheDocument()
  })

  // "Sicheres Passwort erzeugen" has to clear the policy shown above it, and it fills the repeat
  // field too - nobody typed the password, so nobody can confirm it by typing it again.
  it('generates a password the backend accepts', async () => {
    renderAt(`?token=${MOCK_SET_PASSWORD_TOKEN}`)

    await userEvent.click(screen.getByRole('button', { name: 'Sicheres Passwort erzeugen' }))
    const generated = screen.getByLabelText<HTMLInputElement>('Neues Passwort').value
    expect(generated.length).toBeGreaterThanOrEqual(12)
    expect(screen.getByLabelText<HTMLInputElement>('Neues Passwort wiederholen').value).toBe(
      generated,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Passwort festlegen' }))

    expect(
      await screen.findByText('Passwort festgelegt — Sie können sich jetzt anmelden.'),
    ).toBeInTheDocument()
  })
})
