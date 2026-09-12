import { StrictMode } from 'react'
import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import { server } from '../mocks/server'
import { MOCK_RATE_LIMITED_TOKEN, MOCK_VERIFY_EMAIL_TOKEN } from '../mocks/localAuthFixtures'
import VerifyEmailPage from './VerifyEmailPage'

/**
 * ADR-0033, Entscheidung 11: the link is a plain GET, the confirming POST happens on this page - and
 * exactly once, because the token is single-use. Reachable whatever the self-service switches say.
 */
describe('VerifyEmailPage', () => {
  function renderAt(search: string, strict = false) {
    const page = (
      <Routes>
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route path="/login" element={<div>Anmeldung</div>} />
      </Routes>
    )
    return renderWithProviders(strict ? <StrictMode>{page}</StrictMode> : page, {
      withRouter: true,
      initialRoute: `/verify-email${search}`,
    })
  }

  it('confirms the address and points at the sign-in page', async () => {
    renderAt(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`)

    expect(
      await screen.findByText('E-Mail-Adresse bestätigt — Sie können sich jetzt anmelden.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Zur Anmeldung' })).toBeInTheDocument()
  })

  it('shows that it is checking before the answer arrives', () => {
    renderAt(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`)
    expect(screen.getByText('Ihre E-Mail-Adresse wird geprüft …')).toBeInTheDocument()
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
