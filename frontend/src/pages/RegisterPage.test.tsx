import { beforeEach, describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { LOCAL_ACCOUNTS_DISABLED } from '../types/auth'
import { setMockLocalAccounts } from '../mocks/fixtures'
import RegisterPage from './RegisterPage'

const ACKNOWLEDGEMENT = 'Wenn zu dieser Adresse ein Konto besteht, haben wir eine E-Mail geschickt.'

/**
 * ADR-0033, Entscheidung 11: a taken address, a domain outside the list and a free one all answer
 * alike, the page exists only while the installation offers self-registration, and neither the
 * assigned role nor the expiry date is shown.
 */
describe('RegisterPage', () => {
  beforeEach(() => {
    // See ForgotPasswordPage.test.tsx: the mocked endpoint answers 404 while the flow is off.
    setMockLocalAccounts({ enabled: true, selfRegistrationEnabled: true })
    useAuthStore.setState({
      isLoading: false,
      localAccounts: {
        ...LOCAL_ACCOUNTS_DISABLED,
        enabled: true,
        selfRegistrationEnabled: true,
        passwordMinLength: 12,
      },
    })
  })

  function renderPage() {
    return renderWithProviders(
      <Routes>
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/login" element={<div>Anmeldung</div>} />
      </Routes>,
      { withRouter: true, initialRoute: '/register' },
    )
  }

  async function submit({
    name = 'Erika Muster',
    email = 'erika.muster@stadt.example',
    password = 'Sommerregen-42x',
  } = {}) {
    if (name) await userEvent.type(screen.getByLabelText('Name'), name)
    if (email) await userEvent.type(screen.getByLabelText('E-Mail-Adresse'), email)
    if (password) await userEvent.type(screen.getByLabelText('Passwort'), password)
    await userEvent.click(screen.getByRole('button', { name: 'Konto registrieren' }))
  }

  // The one criterion of this page: a taken and a free address are indistinguishable.
  it.each([
    ['a free address', 'neu@stadt.example'],
    ['the address that already has an account', 'erika.muster@stadt.example'],
  ])('answers %s with the same sentence', async (_name, email) => {
    renderPage()
    await submit({ email })

    expect(await screen.findByText(ACKNOWLEDGEMENT)).toBeInTheDocument()
    expect(screen.queryByLabelText('Passwort')).not.toBeInTheDocument()
  })

  it('says nothing about the assigned role or the expiry date', async () => {
    renderPage()
    await submit()
    await screen.findByText(ACKNOWLEDGEMENT)

    expect(screen.queryByText(/Rolle/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Ablauf/)).not.toBeInTheDocument()
  })

  // A second registration of an address that is still unconfirmed sends the link again (#1538), so
  // "no mail arrived" needs no administrator.
  it('offers to send the registration again', async () => {
    renderPage()
    await submit()
    await screen.findByText(ACKNOWLEDGEMENT)

    await userEvent.click(screen.getByRole('button', { name: 'Registrierung erneut absenden' }))

    expect(screen.getByRole('button', { name: 'Konto registrieren' })).toBeInTheDocument()
    expect(screen.getByLabelText<HTMLInputElement>('E-Mail-Adresse').value).toBe(
      'erika.muster@stadt.example',
    )
  })

  it('shows the policy the password has to meet', () => {
    renderPage()
    expect(screen.getByText(/Mindestens 12 Zeichen, höchstens 64/)).toBeInTheDocument()
  })

  /**
   * The backend checks in stages and the first failure answers (LocalUserService.requireAddress,
   * requireText, PasswordPolicy) - so one answer names one field, and the page has to mark that
   * field and take the focus there. A form that expected all three at once would hide two of them.
   */
  it.each([
    [
      'the address',
      { email: 'keine-adresse' },
      'Bitte geben Sie eine gültige E-Mail-Adresse an.',
      'E-Mail-Adresse',
    ],
    ['the name', { name: '' }, 'Bitte geben Sie Ihren Namen an.', 'Name'],
    [
      'the password',
      { password: 'kurz' },
      'Das Passwort muss mindestens 12 Zeichen lang sein.',
      'Passwort',
    ],
  ])('marks a rejected %s at its field and focuses it', async (_what, entry, sentence, label) => {
    renderPage()
    await submit(entry)

    expect(await screen.findByText(sentence)).toBeInTheDocument()
    expect(screen.getByLabelText(label)).toHaveFocus()
  })

  it('generates a password that gets through', async () => {
    renderPage()
    await userEvent.type(screen.getByLabelText('Name'), 'Erika Muster')
    await userEvent.type(screen.getByLabelText('E-Mail-Adresse'), 'neu@stadt.example')
    await userEvent.click(screen.getByRole('button', { name: 'Sicheres Passwort erzeugen' }))
    await userEvent.click(screen.getByRole('button', { name: 'Konto registrieren' }))

    expect(await screen.findByText(ACKNOWLEDGEMENT)).toBeInTheDocument()
  })

  it('redirects to the sign-in page while self-registration is switched off', () => {
    useAuthStore.setState({ localAccounts: { ...LOCAL_ACCOUNTS_DISABLED, enabled: true } })
    renderPage()

    expect(screen.getByText('Anmeldung')).toBeInTheDocument()
  })

  it('decides nothing while the configuration is still loading', () => {
    useAuthStore.setState({ isLoading: true, localAccounts: LOCAL_ACCOUNTS_DISABLED })
    renderPage()

    expect(screen.queryByText('Anmeldung')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Name')).not.toBeInTheDocument()
  })
})
