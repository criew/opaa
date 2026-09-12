import { StrictMode } from 'react'
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { Route, Routes, useLocation } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import { server } from '../mocks/server'
import { MOCK_RATE_LIMITED_TOKEN, MOCK_VERIFY_EMAIL_TOKEN } from '../mocks/localAuthFixtures'
import VerifyEmailPage from './VerifyEmailPage'

/**
 * ADR-0033, Entscheidung 11: the link is a plain GET, the confirming POST happens on this page - and
 * exactly once, because the token is single-use. Reachable whatever the self-service switches say.
 */
/** Makes the query of the current location assertable - the token must not stay in it. */
function QueryProbe() {
  return <span data-testid="query">{useLocation().search}</span>
}

describe('VerifyEmailPage', () => {
  function renderAt(search: string, strict = false) {
    const page = (
      <>
        <QueryProbe />
        <Routes>
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/login" element={<div>Anmeldung</div>} />
        </Routes>
      </>
    )
    return renderWithProviders(strict ? <StrictMode>{page}</StrictMode> : page, {
      withRouter: true,
      initialRoute: `/verify-email${search}`,
      withNotificationHost: false,
    })
  }

  it('confirms the address and points at the sign-in page', async () => {
    renderAt(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`)

    expect(
      await screen.findByText('E-Mail-Adresse bestätigt — Sie können sich jetzt anmelden.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Zur Anmeldung' })).toBeInTheDocument()
  })

  it('shows that it is checking before the answer arrives', async () => {
    renderAt(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`)
    expect(screen.getByText('Ihre E-Mail-Adresse wird geprüft …')).toBeInTheDocument()

    // Awaited on purpose: the request must settle inside this test, or it would consume the
    // single-use link after the fixtures were reset and spoil the next one.
    expect(
      await screen.findByText('E-Mail-Adresse bestätigt — Sie können sich jetzt anmelden.'),
    ).toBeInTheDocument()
  })

  /**
   * ADR-0033, Entscheidung 9: no raw token in any log. The confirming POST must leave with a clean
   * referrer, which the installation's `Referrer-Policy: same-origin` would otherwise fill with the
   * token - see useLinkToken.
   */
  it('takes the token out of the address bar and still confirms', async () => {
    renderAt(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`)

    expect(
      await screen.findByText('E-Mail-Adresse bestätigt — Sie können sich jetzt anmelden.'),
    ).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('query').textContent).toBe(''))
  })

  it('answers an unusable link with the one wording', async () => {
    renderAt('?token=abgelaufen')

    expect(await screen.findByText('Dieser Link ist nicht mehr gültig.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Zur Anmeldung' })).toBeInTheDocument()
  })

  it('treats a link without a token like an invalid one without asking the backend', () => {
    let requests = 0
    server.events.on('request:start', () => {
      requests++
    })
    renderAt('')

    expect(screen.getByText('Dieser Link ist nicht mehr gültig.')).toBeInTheDocument()
    expect(requests).toBe(0)
    server.events.removeAllListeners('request:start')
  })

  /**
   * The regression this page's ref exists for: StrictMode runs the effect twice, and a second POST
   * would consume nothing and report the link as invalid right after it worked.
   */
  it('sends exactly one request, also under StrictMode', async () => {
    const paths: string[] = []
    server.events.on('request:start', ({ request }) => {
      paths.push(new URL(request.url).pathname)
    })

    renderAt(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`, true)
    expect(
      await screen.findByText('E-Mail-Adresse bestätigt — Sie können sich jetzt anmelden.'),
    ).toBeInTheDocument()

    expect(paths.filter((path) => path === '/api/v1/auth/local/verify-email')).toHaveLength(1)
    server.events.removeAllListeners('request:start')
  })

  // A refused rate limit leaves the link untouched - calling it invalid would be wrong.
  it('names the wait of a rate-limited check and says the link still works', async () => {
    renderAt(`?token=${MOCK_RATE_LIMITED_TOKEN}`)

    expect(
      await screen.findByText(
        'Es wurden zu viele Anfragen gestellt. Bitte versuchen Sie es in 2 Minuten erneut.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByText(/Ihr Link ist dadurch nicht verbraucht/)).toBeInTheDocument()
  })
})
