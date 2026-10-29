import { StrictMode } from 'react'
import { beforeEach, describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { BrowserRouter, Route, Routes } from 'react-router'
import { renderWithProviders } from '@/test/test-utils'
import { server } from '@/test/server'
import { useAuthStore } from '@/shared/session/authStore'
import { LOCAL_ACCOUNTS_DISABLED } from '@/shared/session/types'
import { MOCK_RATE_LIMITED_TOKEN, MOCK_VERIFY_EMAIL_TOKEN } from '../mocks/localAuthFixtures'
import VerifyEmailPage from './VerifyEmailPage'

const CONFIRMED =
  'E-Mail-Adresse bestätigt und Passwort festgelegt — Sie können sich jetzt anmelden.'

/**
 * ADR-0033, Entscheidung 11: the registration carries no password - the page behind the
 * verification link asks for it, and one request confirms the address and sets it, so only who
 * holds the link decides the password. The link is a plain GET; nothing is sent before the form.
 * Reachable whatever the self-service switches say.
 */
describe('VerifyEmailPage', () => {
  const { expireSession } = useAuthStore.getState()

  beforeEach(() => {
    useAuthStore.setState({
      isLoading: false,
      isAuthenticated: false,
      sessionKind: null,
      localAccounts: { ...LOCAL_ACCOUNTS_DISABLED, enabled: true, passwordMinLength: 12 },
      expireSession,
    })
  })

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
   * its location in React state, where `window.location` never changes and the referrer could not
   * be observed at all.
   */
  function renderInBrowser(search: string) {
    window.history.replaceState({}, '', `/verify-email${search}`)
    return renderWithProviders(<BrowserRouter>{routes}</BrowserRouter>, {
      withNotificationHost: false,
    })
  }

  async function fillAndSubmit(
    password = 'Sommerregen-42x',
    repeated = password,
    name = 'Erika Muster',
  ) {
    if (name) await userEvent.type(screen.getByLabelText('Name'), name)
    await userEvent.type(screen.getByLabelText('Passwort'), password)
    await userEvent.type(screen.getByLabelText('Passwort wiederholen'), repeated)
    await userEvent.click(screen.getByRole('button', { name: 'Adresse bestätigen' }))
  }

  /** The paths of every request the page makes, in order. */
  function recordRequests(): string[] {
    const paths: string[] = []
    server.events.on('request:start', ({ request }) => {
      paths.push(new URL(request.url).pathname)
    })
    return paths
  }

  it('asks for name and password and sends nothing before the form is submitted', () => {
    const paths = recordRequests()
    renderAt(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`, true)

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Passwort festlegen')
    // empty on purpose: the name of the registration came from whoever registered the address
    expect(screen.getByLabelText<HTMLInputElement>('Name').value).toBe('')
    expect(screen.getByText(/bestätigen Sie zugleich Ihre E-Mail-Adresse/)).toBeInTheDocument()
    expect(screen.getByText(/Mindestens 12 Zeichen/)).toBeInTheDocument()
    expect(paths).toEqual([])
    server.events.removeAllListeners('request:start')
  })

  it('confirms the address with the chosen password and points at the sign-in page', async () => {
    const paths = recordRequests()
    renderAt(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`, true)

    await fillAndSubmit()

    expect(await screen.findByText(CONFIRMED)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Zur Anmeldung' })).toBeInTheDocument()
    expect(paths.filter((path) => path === '/api/v1/auth/local/confirm-registration')).toHaveLength(
      1,
    )
    server.events.removeAllListeners('request:start')
  })

  it('marks a missing name at its field and focuses it', async () => {
    renderAt(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`)

    await fillAndSubmit('Sommerregen-42x', 'Sommerregen-42x', '')

    expect(await screen.findByText('Bitte geben Sie Ihren Namen an.')).toBeInTheDocument()
    expect(screen.getByLabelText('Name')).toHaveFocus()
  })

  // A backend that does not know the endpoint yet answers like an unknown route; the link is kept.
  it('says the link is kept when the backend does not offer the confirmation yet', async () => {
    server.use(
      http.post('/api/v1/auth/local/confirm-registration', () =>
        HttpResponse.json({ status: 404 }, { status: 404 }),
      ),
    )
    renderAt(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`)

    await fillAndSubmit()

    expect(await screen.findByText(/Ihr Link ist dadurch nicht verbraucht/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Adresse bestätigen' })).toBeInTheDocument()
  })

  /**
   * ADR-0033, Entscheidung 9: no raw token in any log. The URL has to be clean before the first
   * request leaves, because that is what the browser reads the referrer from.
   */
  it('has no token in the address bar when the request starts', async () => {
    const seen: string[] = []
    server.events.on('request:start', () => seen.push(window.location.search))
    const historyBefore = window.history.length
    renderInBrowser(`?x=1&token=${MOCK_VERIFY_EMAIL_TOKEN}`)

    await fillAndSubmit()
    expect(await screen.findByText(CONFIRMED)).toBeInTheDocument()
    server.events.removeAllListeners('request:start')

    // the token is removed, the rest of the query stays
    expect(seen).toEqual(['?x=1'])
    // replaceState, not pushState: the back button must not lead onto a URL with the token in it.
    expect(window.history.length).toBe(historyBefore)
  })

  it('marks a refused password at its field and keeps the form', async () => {
    renderAt(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`)

    await fillAndSubmit('kurz')

    expect(
      await screen.findByText('Das Passwort muss mindestens 12 Zeichen lang sein.'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Passwort')).toHaveFocus()
  })

  it('refuses two different entries without asking the backend', async () => {
    const paths = recordRequests()
    renderAt(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`)

    await fillAndSubmit('Sommerregen-42x', 'Sommerregen-43x')

    expect(screen.getByText('Die beiden Eingaben stimmen nicht überein.')).toBeInTheDocument()
    expect(paths).toEqual([])
    server.events.removeAllListeners('request:start')
  })

  it('answers an unusable link with the one wording', async () => {
    renderAt('?token=abgelaufen')

    await fillAndSubmit()

    expect(await screen.findByText('Dieser Link ist nicht mehr gültig.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Zur Anmeldung' })).toBeInTheDocument()
  })

  it('treats a link without a token like an invalid one without asking the backend', () => {
    const paths = recordRequests()
    renderAt('')

    expect(screen.getByText('Dieser Link ist nicht mehr gültig.')).toBeInTheDocument()
    expect(paths).toEqual([])
    server.events.removeAllListeners('request:start')
  })

  // A refused rate limit leaves the link untouched - calling it invalid would be wrong.
  it('names the wait of a rate-limited request and keeps the form', async () => {
    renderAt(`?token=${MOCK_RATE_LIMITED_TOKEN}`)

    await fillAndSubmit()

    expect(
      await screen.findByText(
        'Es wurden zu viele Anfragen gestellt. Bitte versuchen Sie es in 2 Minuten erneut.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Adresse bestätigen' })).toBeInTheDocument()
  })

  it('ends a local session of this tab once the backend revoked it', async () => {
    useAuthStore.setState({ isAuthenticated: true, sessionKind: 'local' })
    renderAt(`?token=${MOCK_VERIFY_EMAIL_TOKEN}`)

    await fillAndSubmit()

    expect(await screen.findByText(CONFIRMED)).toBeInTheDocument()
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })
})
