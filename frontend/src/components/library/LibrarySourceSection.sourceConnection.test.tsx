import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../mocks/server'
import { leaveFor } from '../../services/leaveApp'
import { answerConfirm, renderWithProviders } from '../../test/test-utils'
import type { ConnectionAuthorizationStartRequest, LibrarySourceConnection } from '../../types/api'
import LibrarySourceSection from './LibrarySourceSection'
import { SERVICE_ACCOUNT_CONFIRMATION, readConsentIntent } from './sourceConsent'

vi.mock('../../services/leaveApp', () => ({ leaveFor: vi.fn() }))

const consent: LibrarySourceConnection = {
  accountLabel: 'svc@bauamt.example',
  connectedAt: '2026-10-01T08:00:00Z',
  responsible: { type: 'GROUP', id: 'g-1', name: 'Bauamt Verwaltung' },
  endedCause: null,
  endedAt: null,
  expiresAt: null,
}

const library = {
  name: 'Bauamt',
  sourceType: 'NEXTCLOUD',
  sourceUrl: 'https://cloud.example/remote.php/dav/files/svc',
  connectionProfile: {
    id: 'profile-dropbox',
    name: 'Dropbox Bauamt',
    serverUrl: 'https://cloud.example',
    authMethod: 'OAUTH' as const,
  },
  sourceConnection: consent,
}

describe('LibrarySourceSection - Quelle verbinden (#2169)', () => {
  let started: ConnectionAuthorizationStartRequest[] = []
  let disconnected: string[] = []
  let reloads = 0

  beforeEach(() => {
    sessionStorage.clear()
    vi.mocked(leaveFor).mockReset()
    started = []
    disconnected = []
    reloads = 0
    server.use(
      http.post('/api/v1/connections/authorizations', async ({ request }) => {
        started.push((await request.json()) as ConnectionAuthorizationStartRequest)
        return HttpResponse.json({
          authorizationUrl: 'https://provider.example/authorize?state=s',
          expiresAt: '2026-10-05T09:10:00Z',
        })
      }),
      http.delete('/api/v1/libraries/:libraryId/source-connection', ({ params }) => {
        disconnected.push(String(params.libraryId))
        return new HttpResponse(null, { status: 204 })
      }),
      http.get('/api/v1/libraries/:libraryId', ({ params }) => {
        reloads += 1
        return HttpResponse.json({
          id: String(params.libraryId),
          ...library,
          ownerType: 'USER',
          ownerId: 'u-1',
          myRole: 'MANAGER',
          documentCount: 0,
          createdAt: '2026-10-01T08:00:00Z',
          updatedAt: '2026-10-01T08:00:00Z',
        })
      }),
    )
  })

  it('names the account, who answers for it and since when it is connected', () => {
    renderWithProviders(
      <LibrarySourceSection libraryId="library-1" library={library} canEditSource />,
      {
        withRouter: true,
      },
    )

    const block = screen.getByTestId('source-connection')
    expect(block).toHaveTextContent('svc@bauamt.example')
    expect(block).toHaveTextContent('Gruppe „Bauamt Verwaltung“')
    expect(block).toHaveTextContent('01.10.2026')
    expect(within(block).getByRole('button', { name: 'Neu verbinden' })).toBeInTheDocument()
    expect(within(block).getByRole('button', { name: 'Trennen' })).toBeInTheDocument()
  })

  it('names why an ended connection ended, and offers no disconnect then', () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...library,
          sourceConnection: {
            ...consent,
            responsible: { type: 'USER', id: 'u-1', name: null },
            endedCause: 'PROVIDER_REJECTED',
            endedAt: '2026-10-03T08:00:00Z',
          },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    const block = screen.getByTestId('source-connection')
    expect(block).toHaveTextContent('Vom Anbieter abgelehnt')
    expect(block).toHaveTextContent('03.10.2026')
    expect(block).toHaveTextContent('eine Person')
    expect(within(block).queryByRole('button', { name: 'Trennen' })).not.toBeInTheDocument()
    expect(within(block).getByRole('button', { name: 'Neu verbinden' })).toBeInTheDocument()
  })

  it('offers no reconnect once the profile is gone, and points to „Zugang zuordnen“', () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...library,
          connectionProfile: null,
          connectionProfileRemoved: true,
          sourceConnection: {
            ...consent,
            endedCause: 'PROFILE_DELETED',
            endedAt: '2026-10-03T08:00:00Z',
          },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    const block = screen.getByTestId('source-connection')
    expect(block).toHaveTextContent('Zugang gelöscht')
    expect(block).toHaveTextContent('über „Zugang zuordnen“ einem anderen Zugang zu')
    expect(within(block).queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows nothing of the connection where the server names none, as for a reader', () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...library, sourceConnection: undefined }}
        canEditSource={false}
      />,
      { withRouter: true },
    )

    expect(screen.queryByTestId('source-connection')).not.toBeInTheDocument()
    expect(screen.queryByText(/svc@bauamt/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /verbinden/ })).not.toBeInTheDocument()
  })

  it('shows nothing new for a library without its own source connection', () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...library,
          connectionProfile: { ...library.connectionProfile, authMethod: 'PERSONAL_SECRET' },
          sourceConnection: null,
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    expect(screen.queryByTestId('source-connection')).not.toBeInTheDocument()
    expect(screen.queryByText(SERVICE_ACCOUNT_CONFIRMATION)).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /Quelle (neu )?verbinden/ }),
    ).not.toBeInTheDocument()
  })

  it('disconnects only after the question, then reloads the library', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection libraryId="library-1" library={library} canEditSource />,
      {
        withRouter: true,
      },
    )

    await user.click(screen.getByRole('button', { name: 'Trennen' }))
    await answerConfirm(user, 'Verbindung der Quelle trennen?', 'Abbrechen')
    expect(disconnected).toEqual([])

    await user.click(screen.getByRole('button', { name: 'Trennen' }))
    await answerConfirm(user, 'Verbindung der Quelle trennen?', 'Trennen')
    await waitFor(() => expect(disconnected).toEqual(['library-1']))
    await waitFor(() => expect(reloads).toBeGreaterThan(0))
    expect(await screen.findByText('Die Verbindung der Quelle ist getrennt.')).toBeVisible()
  })

  it('reconnects only with the service account confirmed, and remembers where to return', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection libraryId="library-1" library={library} canEditSource />,
      {
        withRouter: true,
      },
    )

    await user.click(screen.getByRole('button', { name: 'Neu verbinden' }))
    const dialog = await screen.findByRole('dialog', { name: 'Quelle neu verbinden' })
    await user.click(within(dialog).getByRole('button', { name: 'Weiter zum Anbieter' }))
    expect(
      await within(dialog).findByText('Bitte bestätigen Sie, dass Sie ein Dienstkonto verbinden.'),
    ).toBeVisible()
    expect(started).toEqual([])
    expect(leaveFor).not.toHaveBeenCalled()

    await user.click(within(dialog).getByRole('checkbox', { name: SERVICE_ACCOUNT_CONFIRMATION }))
    await user.click(within(dialog).getByRole('button', { name: 'Weiter zum Anbieter' }))
    await waitFor(() =>
      expect(leaveFor).toHaveBeenCalledWith('https://provider.example/authorize?state=s'),
    )
    expect(started).toEqual([
      {
        profileId: 'profile-dropbox',
        purpose: 'LIBRARY_RECONNECT',
        libraryId: 'library-1',
        serviceAccountConfirmed: true,
      },
    ])
    expect(readConsentIntent()).toEqual({
      purpose: 'LIBRARY_RECONNECT',
      profileId: 'profile-dropbox',
      libraryId: 'library-1',
    })
  })

  it('offers the managers „Quelle neu verbinden“ on the notice of an expired connection', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...library,
          sourceConnection: {
            ...consent,
            endedCause: 'PROVIDER_REJECTED',
            endedAt: '2026-10-03T08:00:00Z',
          },
          sourceBlock: {
            reason: 'EXPIRED',
            responsible: 'Verwaltende der Bibliothek',
            notice:
              'Abgelaufen: Die Verbindung der Quelle wird vom Anbieter nicht mehr angenommen.',
            action: 'CONNECT_SOURCE',
          },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    const notice = screen.getByTestId('source-lock-notice')
    await user.click(within(notice).getByRole('button', { name: 'Quelle neu verbinden' }))
    expect(await screen.findByRole('dialog', { name: 'Quelle neu verbinden' })).toBeVisible()
  })

  it('offers „Quelle verbinden“ on the notice of a library never connected', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...library,
          sourceConnection: null,
          sourceBlock: {
            reason: 'NOT_CONNECTED',
            responsible: 'Verwaltende der Bibliothek',
            notice:
              'Nicht verbunden: Die Quelle ist über den Zugang „Dropbox Bauamt“ nicht verbunden.',
            action: 'CONNECT_SOURCE',
          },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    const notice = screen.getByTestId('source-lock-notice')
    await user.click(within(notice).getByRole('button', { name: 'Quelle verbinden' }))
    expect(await screen.findByRole('dialog', { name: 'Quelle verbinden' })).toBeVisible()
  })

  it('leaves the notice without an action for a reader', () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...library,
          sourceConnection: undefined,
          sourceBlock: {
            reason: 'EXPIRED',
            responsible: 'Verwaltende der Bibliothek',
            notice:
              'Abgelaufen: Die Verbindung der Quelle wird vom Anbieter nicht mehr angenommen.',
            action: 'CONNECT_SOURCE',
          },
        }}
        canEditSource={false}
      />,
      { withRouter: true },
    )

    expect(
      within(screen.getByTestId('source-lock-notice')).queryByRole('button'),
    ).not.toBeInTheDocument()
  })
})
