import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '../mocks/server'
import { leaveFor } from '../services/leaveApp'
import { useAuthStore } from '../stores/authStore'
import { answerConfirm, renderWithProviders, setMockAuthState } from '../test/test-utils'
import type {
  ConnectionAuthorizationCompleteRequest,
  ConnectionAuthorizationStartRequest,
} from '../types/api'
import { scheduleValuesFrom } from '../utils/librarySchedule'
import {
  CONSENT_INTENT_STORAGE_KEY,
  readConsentIntent,
  rememberConsentIntent,
  type WizardDraft,
} from '../components/library/sourceConsent'
import ConnectionCallbackPage from './ConnectionCallbackPage'

vi.mock('../services/leaveApp', () => ({ leaveFor: vi.fn() }))

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
        <Route path="/libraries/new" element={<div>Assistent</div>} />
        <Route path="/libraries/:libraryId" element={<div>Bibliothek</div>} />
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
  beforeEach(() => {
    setMockAuthState()
    sessionStorage.clear()
    vi.mocked(leaveFor).mockReset()
  })

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

  it.each(['/\\evil.example', '/%5Cevil.example', '//evil.example', 'https://evil.example'])(
    'goes to the connected accounts instead of a return target off this application: %s',
    async (returnTo) => {
      serveComplete(() =>
        HttpResponse.json({ purpose: 'ACCOUNT', profileId: 'dropbox', returnTo, account: null }),
      )
      renderAt(`/connections/callback?code=c&state=s-${encodeURIComponent(returnTo)}`)

      expect(await screen.findByText('Verbundene Konten', { selector: 'div' })).toBeInTheDocument()
      expect(screen.getByTestId('address')).toHaveTextContent(/^\/settings\/accounts$/)
    },
  )

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

  describe('Quelle verbinden (#2169)', () => {
    const draft: WizardDraft = {
      sourceType: 'NEXTCLOUD',
      chosenConnections: { NEXTCLOUD: 'profile-dropbox' },
      sourceValues: { sourceUrl: 'https://cloud.example', folders: '/' },
      schedule: scheduleValuesFrom(null),
      startFirstRun: true,
      name: '',
      nameTouched: false,
      description: '',
      ownerType: 'USER',
      selectedGroup: null,
      pendingGrants: [],
      responsibleIsGroup: false,
    }
    const pending = {
      id: 'pending-1',
      accountLabel: 'svc@bauamt.example',
      expiresAt: '2026-10-05T10:00:00Z',
    }
    const ACCOUNT_CHANGED_MESSAGE =
      'Sie haben sich beim Anbieter als „privat@example.org“ angemeldet; die Quelle war als „svc@bauamt.example“ verbunden. Bestätigen Sie den Wechsel des Kontos und verbinden Sie erneut – der Abgleichstand der Bibliothek wird dann verworfen. Es wurde nichts verbunden.'

    function serveStart() {
      const started: ConnectionAuthorizationStartRequest[] = []
      server.use(
        http.post('/api/v1/connections/authorizations', async ({ request }) => {
          started.push((await request.json()) as ConnectionAuthorizationStartRequest)
          return HttpResponse.json({
            authorizationUrl: 'https://provider.example/authorize?state=neu',
            expiresAt: '2026-10-05T09:10:00Z',
          })
        }),
      )
      return started
    }

    it('hands the pending connection to the waiting wizard and returns there', async () => {
      rememberConsentIntent({ purpose: 'LIBRARY_NEW', profileId: 'profile-dropbox', draft })
      serveComplete(() =>
        HttpResponse.json({
          purpose: 'LIBRARY_NEW',
          profileId: 'profile-dropbox',
          returnTo: '/libraries/new',
          account: null,
          pendingConnection: pending,
        }),
      )
      renderAt('/connections/callback?code=c-5&state=s-5')

      expect(await screen.findByText('Assistent', { selector: 'div' })).toBeInTheDocument()
      expect(screen.getByTestId('address')).toHaveTextContent(/^\/libraries\/new$/)
      expect(
        await screen.findByText('Die Quelle ist verbunden als „svc@bauamt.example“.'),
      ).toBeVisible()
      expect(readConsentIntent()).toEqual({
        purpose: 'LIBRARY_NEW',
        profileId: 'profile-dropbox',
        draft,
        pending,
      })
      // neither the code nor the state is kept anywhere in the tab
      const stored = sessionStorage.getItem(CONSENT_INTENT_STORAGE_KEY) ?? ''
      expect(stored).not.toContain('c-5')
      expect(stored).not.toContain('s-5')
    })

    it('returns to the source of the library connected anew and forgets the intent', async () => {
      rememberConsentIntent({
        purpose: 'LIBRARY_RECONNECT',
        profileId: 'profile-dropbox',
        libraryId: 'lib-1',
      })
      serveComplete(() =>
        HttpResponse.json({
          purpose: 'LIBRARY_RECONNECT',
          profileId: 'profile-dropbox',
          returnTo: '/libraries/lib-1',
          account: null,
          libraryId: 'lib-1',
        }),
      )
      renderAt('/connections/callback?code=c-6&state=s-6')

      expect(await screen.findByText('Bibliothek', { selector: 'div' })).toBeInTheDocument()
      expect(screen.getByTestId('address')).toHaveTextContent(/^\/libraries\/lib-1\?tab=quelle$/)
      expect(await screen.findByText('Die Quelle der Bibliothek ist neu verbunden.')).toBeVisible()
      expect(readConsentIntent()).toBeNull()
    })

    it('connects with the other account only once the loss of the sync state is confirmed', async () => {
      rememberConsentIntent({
        purpose: 'LIBRARY_RECONNECT',
        profileId: 'profile-dropbox',
        libraryId: 'lib-1',
      })
      serveComplete(() => errorBody(409, ACCOUNT_CHANGED_MESSAGE, 'ACCOUNT_CHANGED'))
      const started = serveStart()
      const user = userEvent.setup()
      renderAt('/connections/callback?code=c-7&state=s-7')

      expect(await screen.findByRole('heading', { name: 'Quelle verbinden' })).toBeInTheDocument()
      expect(await screen.findByText(ACCOUNT_CHANGED_MESSAGE)).toBeVisible()
      await user.click(screen.getByRole('button', { name: 'Mit diesem Konto verbinden' }))
      await answerConfirm(user, 'Quelle mit dem anderen Konto verbinden?', 'Abbrechen')
      expect(started).toEqual([])
      expect(leaveFor).not.toHaveBeenCalled()

      await user.click(screen.getByRole('button', { name: 'Mit diesem Konto verbinden' }))
      await answerConfirm(
        user,
        'Quelle mit dem anderen Konto verbinden?',
        'Mit diesem Konto verbinden',
      )
      await waitFor(() =>
        expect(leaveFor).toHaveBeenCalledWith('https://provider.example/authorize?state=neu'),
      )
      expect(started).toEqual([
        {
          profileId: 'profile-dropbox',
          purpose: 'LIBRARY_RECONNECT',
          libraryId: 'lib-1',
          serviceAccountConfirmed: true,
          confirmAccountChange: true,
        },
      ])
    })

    it('offers no change of account without the library it was for', async () => {
      serveComplete(() => errorBody(409, ACCOUNT_CHANGED_MESSAGE, 'ACCOUNT_CHANGED'))
      renderAt('/connections/callback?code=c-8&state=s-8')

      expect(await screen.findByText(ACCOUNT_CHANGED_MESSAGE)).toBeVisible()
      expect(
        screen.queryByRole('button', { name: 'Mit diesem Konto verbinden' }),
      ).not.toBeInTheDocument()
    })

    it('leads back to the wizard after a failed consent, with its draft kept', async () => {
      rememberConsentIntent({ purpose: 'LIBRARY_NEW', profileId: 'profile-dropbox', draft })
      serveComplete(() =>
        errorBody(
          400,
          'Die Zustimmung beim Anbieter wurde abgelehnt oder abgebrochen. Es wurde nichts verbunden.',
        ),
      )
      const user = userEvent.setup()
      renderAt('/connections/callback?error=access_denied&state=s-9')

      expect(await screen.findByRole('alert')).toHaveTextContent('abgelehnt oder abgebrochen')
      await user.click(screen.getByRole('button', { name: 'Zurück zum Assistenten' }))
      await waitFor(() =>
        expect(screen.getByTestId('address')).toHaveTextContent(/^\/libraries\/new$/),
      )
      expect(readConsentIntent()).toEqual({
        purpose: 'LIBRARY_NEW',
        profileId: 'profile-dropbox',
        draft,
      })
    })

    it('leads back to the library after a failed reconnection', async () => {
      rememberConsentIntent({
        purpose: 'LIBRARY_RECONNECT',
        profileId: 'profile-dropbox',
        libraryId: 'lib-1',
      })
      serveComplete(() => errorBody(404, 'Diese Anmeldung beim Anbieter ist unbekannt.'))
      const user = userEvent.setup()
      renderAt('/connections/callback?code=c-10&state=s-10')

      await user.click(await screen.findByRole('button', { name: 'Zurück zur Bibliothek' }))
      await waitFor(() =>
        expect(screen.getByTestId('address')).toHaveTextContent(/^\/libraries\/lib-1\?tab=quelle$/),
      )
      expect(readConsentIntent()).toBeNull()
    })
  })
})
