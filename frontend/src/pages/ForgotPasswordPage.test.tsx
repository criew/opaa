import { beforeEach, describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { LOCAL_ACCOUNTS_DISABLED } from '../types/auth'
import { setMockLocalAccounts } from '../mocks/fixtures'
import { MOCK_RATE_LIMITED_EMAIL } from '../mocks/localAuthFixtures'
import ForgotPasswordPage from './ForgotPasswordPage'

const ACKNOWLEDGEMENT = 'Wenn zu dieser Adresse ein Konto besteht, haben wir eine E-Mail geschickt.'

/**
 * ADR-0033, Entscheidung 11: the acknowledgement is the same for an address that has an account and
 * one that has none, and the page exists only while the installation offers the flow.
 */
describe('ForgotPasswordPage', () => {
  beforeEach(() => {
    // The mocked endpoint answers 404 while the flow is off, exactly as the backend does - the
    // store alone would let the page render a form whose request is refused as unknown.
    setMockLocalAccounts({ enabled: true, passwordResetEnabled: true })
    useAuthStore.setState({
      isLoading: false,
      localAccounts: {
        ...LOCAL_ACCOUNTS_DISABLED,
        enabled: true,
        passwordResetEnabled: true,
        passwordMinLength: 12,
      },
    })
  })

  function renderPage() {
    return renderWithProviders(
      <Routes>
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/login" element={<div>Anmeldung</div>} />
      </Routes>,
      { withRouter: true, initialRoute: '/forgot-password' },
    )
  }

  async function request(email: string) {
    await userEvent.type(screen.getByLabelText('E-Mail-Adresse'), email)
    await userEvent.click(screen.getByRole('button', { name: 'Link anfordern' }))
  }

  // The one criterion of this page: a known and an unknown address are indistinguishable.
  it.each([
    ['a known address', 'erika.muster@stadt.example'],
    ['an unknown address', 'gibt.es.nicht@stadt.example'],
  ])('answers %s with the same sentence', async (_name, email) => {
    renderPage()
    await request(email)

    expect(await screen.findByText(ACKNOWLEDGEMENT)).toBeInTheDocument()
    // The form is gone: the outcome is a view of its own, not a popup over a form nobody needs now.
    expect(screen.queryByLabelText('E-Mail-Adresse')).not.toBeInTheDocument()
  })

  it('shows a rejected address at the field', async () => {
    renderPage()
    await request('keine-adresse')

    expect(
      await screen.findByText('Bitte geben Sie eine gültige E-Mail-Adresse an.'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('E-Mail-Adresse')).toBeInTheDocument()
  })

  it('names the wait from Retry-After when the limit refuses the request', async () => {
    renderPage()
    await request(MOCK_RATE_LIMITED_EMAIL)

    expect(
      await screen.findByText(
        'Es wurden zu viele Anfragen gestellt. Bitte versuchen Sie es in 2 Minuten erneut.',
      ),
    ).toBeInTheDocument()
  })

  it('redirects to the sign-in page while the flow is switched off', () => {
    useAuthStore.setState({ localAccounts: { ...LOCAL_ACCOUNTS_DISABLED, enabled: true } })
    renderPage()

    expect(screen.getByText('Anmeldung')).toBeInTheDocument()
  })

  // "Not known yet" is not "switched off": deciding while the configuration is still loading would
  // bounce a reload of this page before the answer arrives.
  it('decides nothing while the configuration is still loading', () => {
    useAuthStore.setState({ isLoading: true, localAccounts: LOCAL_ACCOUNTS_DISABLED })
    renderPage()

    expect(screen.queryByText('Anmeldung')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('E-Mail-Adresse')).not.toBeInTheDocument()
  })
})
