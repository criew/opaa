import { beforeEach, describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import { LOCAL_ACCOUNTS_DISABLED } from '@/shared/session/types'
import { setMockLocalAccounts } from '@/shared/mocks/authFixtures'
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

  async function submit({ email = 'erika.muster@stadt.example' } = {}) {
    if (email) await userEvent.type(screen.getByLabelText('E-Mail-Adresse'), email)
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
  })

  it('says nothing about the assigned role or the expiry date', async () => {
    renderPage()
    await submit()
    await screen.findByText(ACKNOWLEDGEMENT)

    expect(screen.queryByText(/Rolle/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Ablauf/)).not.toBeInTheDocument()
  })

  // A second registration of an address that is still unconfirmed sends the link again, so
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

  /**
   * Pre-hijacking (ADR-0033, Entscheidung 11): whoever registers somebody else's address must not
   * decide its password or its name, so the registration asks for neither - the verification link
   * sets both.
   */
  it('asks for neither password nor name and says the link sets both', async () => {
    renderPage()
    expect(screen.queryByLabelText('Passwort')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Name')).not.toBeInTheDocument()
    expect(screen.getByText(/legen Ihren Namen und Ihr Passwort fest\./)).toBeInTheDocument()

    await submit()

    expect(
      await screen.findByText(/und legen Ihren Namen und Ihr Passwort fest; der Link gilt/),
    ).toBeInTheDocument()
  })

  it('marks a rejected address at its field and focuses it', async () => {
    renderPage()
    await submit({ email: 'keine-adresse' })

    expect(
      await screen.findByText('Bitte geben Sie eine gültige E-Mail-Adresse an.'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('E-Mail-Adresse')).toHaveFocus()
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
    expect(screen.queryByLabelText('E-Mail-Adresse')).not.toBeInTheDocument()
  })
})
