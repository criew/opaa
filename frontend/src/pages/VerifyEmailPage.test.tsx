import { StrictMode } from 'react'
import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { BrowserRouter, Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import { server } from '../mocks/server'
import { MOCK_RATE_LIMITED_TOKEN, MOCK_VERIFY_EMAIL_TOKEN } from '../mocks/localAuthFixtures'
import VerifyEmailPage from './VerifyEmailPage'

/**
 * ADR-0033, Entscheidung 11: the link is a plain GET, the confirming POST happens on this page - and
 * exactly once, because the token is single-use. Reachable whatever the self-service switches say.
 */
describe('VerifyEmailPage', () => {
  const routes = (
    <Routes>
      <Route path="/verify-email" element={<VerifyEmailPage />} />
      <Route path="/login" element={<div>Anmeldung</div>} />
    </Routes>
  )

  function renderAt(search: string, strict = false) {
    return renderWithProviders(strict ? <StrictMode>{routes}</StrictMode> : routes, {
      withRouter: true,
      initialRoute: `/verify-email${search}`,
      withNotificationHost: false,
    })
  }

  /**
   * The page in a real {@link BrowserRouter} on jsdom's own `window.location`: a MemoryRouter keeps
   * its location in React state, where `window.location` never changes and the regression below
   * could not be seen at all.
   */
  function renderInBrowser(search: string) {
    window.history.replaceState({}, '', `/verify-email${search}`)
    return renderWithProviders(<BrowserRouter>{routes}</BrowserRouter>, {
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
   * ADR-0033, Entscheidung 9: no raw token in any log. This page is where the order is load-bearing -
   * the confirming POST goes out during mount, so the URL has to be clean *before* it, not merely
   * afterwards. Asserted on `window.location` at the moment the request starts, because that is what
   * the browser reads the referrer from.
   */
  it('has no token in the address bar when the confirming request starts', async () => {
    const seen: string[] = []
    server.events.on('request:start', () => seen.push(window.location.search))
    const historyBefore = window.history.length
    renderInBrowser(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`)

    expect(
      await screen.findByText('E-Mail-Adresse bestätigt — Sie können sich jetzt anmelden.'),
    ).toBeInTheDocument()
    server.events.removeAllListeners('request:start')

    expect(seen).toEqual([''])
    // replaceState, not pushState: the back button must not lead onto a URL with the token in it.
    expect(window.history.length).toBe(historyBefore)
  })

  // Whatever else the link carries stays: the token is removed, not the query.
  it('keeps the other query parameters of the link', async () => {
    const seen: string[] = []
    server.events.on('request:start', () => seen.push(window.location.search))
    renderInBrowser(`?x=1&token=${MOCK_VERIFY_EMAIL_TOKEN}`)

    await screen.findByText('E-Mail-Adresse bestätigt — Sie können sich jetzt anmelden.')
    server.events.removeAllListeners('request:start')

    expect(seen).toEqual(['?x=1'])
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
