import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { server } from '../mocks/server'
import { useAuthStore } from '../stores/authStore'
import { renderWithProviders, setMockAuthState } from '../test/test-utils'
import type { ConnectionAuthorizationCompleteRequest } from '../types/api'
import ConnectionCallbackPage from './ConnectionCallbackPage'

const COMPLETE = '/api/v1/connections/authorizations/complete'

function errorBody(status: number, error: string, code?: string) {
  return HttpResponse.json({ error, status, timestamp: '2026-10-04T00:00:00Z', code }, { status })
}

function Address() {
  const location = useLocation()
  return <div data-testid="address">{`${location.pathname}${location.search}`}</div>
}

function renderAt(route: string) {
  const routes = (
    <>
      <Routes>
        <Route path="/connections/callback" element={<ConnectionCallbackPage />} />
        <Route path="/settings/accounts" element={<div>Verbundene Konten</div>} />
        <Route path="/login" element={<div>Anmeldeseite</div>} />
      </Routes>
      <Address />
    </>
  )
  return renderWithProviders(routes, {
    withRouter: true,
    initialRoute: route,
  })
}

/** Records every completion sent and answers it with `answer`. */
function serveComplete(answer: () => Response) {
  const sent: ConnectionAuthorizationCompleteRequest[] = []
  server.use(
    http.post(COMPLETE, async ({ request }) => {
      sent.push((await request.json()) as ConnectionAuthorizationCompleteRequest)
      return answer()
    }),
  )
  return sent
}

const DONE = () =>
  HttpResponse.json({
    purpose: 'ACCOUNT',
    profileId: 'dropbox',
    returnTo: '/settings/accounts',
    account: {
      profileId: 'dropbox',
      profileName: 'Zugang Dropbox',
      authMethod: 'OAUTH',
      secretForm: null,
      state: 'CONNECTED',
      accountLabel: null,
      released: true,
      reconnectable: true,
      notice: null,
      responsible: null,
      connectedAt: '2026-10-04T08:00:00Z',
      reconnectedAt: null,
      usedBy: [],
    },
  })

describe('ConnectionCallbackPage', () => {
  beforeEach(() => setMockAuthState())

  it('completes the consent once, cleans the address and goes on to the page the server names', async () => {
    const sent = serveComplete(DONE)
    renderAt('/connections/callback?code=c-1&state=s-1')

    expect(await screen.findByText('Verbundene Konten', { selector: 'div' })).toBeInTheDocument()
    expect(await screen.findByText('Ihr Konto ist mit „Zugang Dropbox“ verbunden.')).toBeVisible()
    expect(sent).toEqual([{ state: 's-1', code: 'c-1', error: null }])
    expect(screen.getByTestId('address')).toHaveTextContent(/^\/settings\/accounts$/)
  })

  it('redeems a state once even when the page mounts again while the first answer is pending', async () => {
    const sent = serveComplete(DONE)
    const first = renderAt('/connections/callback?code=c-4&state=s-4')
    first.unmount()
    renderAt('/connections/callback?code=c-4&state=s-4')

    expect(await screen.findByText('Verbundene Konten', { selector: 'div' })).toBeInTheDocument()
    expect(sent).toHaveLength(1)
  })

  it('passes a refusal at the provider on, so the state is used up, and says nothing was connected', async () => {
    const sent = serveComplete(() =>
      errorBody(
        400,
        'Die Zustimmung beim Anbieter wurde abgelehnt oder abgebrochen. Es wurde nichts verbunden.',
      ),
    )
    renderAt('/connections/callback?error=access_denied&state=s-2')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Die Zustimmung beim Anbieter wurde abgelehnt oder abgebrochen.',
    )
    expect(sent).toEqual([{ state: 's-2', code: null, error: 'access_denied' }])
    // neither the state nor the provider's answer stays in the address
    expect(screen.getByTestId('address')).toHaveTextContent(/^\/connections\/callback$/)
    expect(screen.getByRole('button', { name: 'Zu den verbundenen Konten' })).toBeInTheDocument()
  })

  it.each([
    [
      404,
      'Diese Anmeldung beim Anbieter ist unbekannt, abgelaufen oder schon abgeschlossen. Bitte verbinden Sie erneut.',
      undefined,
    ],
    [
      409,
      'Der Zugang „Zugang Dropbox“ wurde geändert, während Sie beim Anbieter waren. Bitte verbinden Sie erneut.',
      'CONNECTION_AUTHORIZATION_PROFILE_CHANGED',
    ],
  ])('names a %i of the server and connects nothing', async (status, message, code) => {
    serveComplete(() => errorBody(status, message, code))
    renderAt(`/connections/callback?code=c&state=s-${status}`)

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.getByTestId('address')).toHaveTextContent(/^\/connections\/callback$/)
  })

  it('redeems nothing without a state, as after a reload of the cleaned page', async () => {
    const sent = serveComplete(DONE)
    renderAt('/connections/callback')

    expect(await screen.findByRole('alert')).toHaveTextContent('Hier gibt es nichts abzuschließen')
    expect(sent).toEqual([])
  })

  it('redeems nothing without a session and leads to the sign-in without the code', async () => {
    useAuthStore.setState({ isAuthenticated: false, isLoading: false })
    const sent = serveComplete(DONE)
    renderAt('/connections/callback?code=c-3&state=s-3')

    expect(await screen.findByRole('alert')).toHaveTextContent('Ihre Sitzung ist abgelaufen')
    expect(sent).toEqual([])
    screen.getByRole('button', { name: 'Zur Anmeldung' }).click()
    await waitFor(() => expect(screen.getByTestId('address')).toHaveTextContent(/^\/login$/))
  })
})
