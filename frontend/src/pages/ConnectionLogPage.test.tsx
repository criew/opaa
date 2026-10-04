import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../mocks/server'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import type { ConnectionLogEntryResponse } from '../types/api'
import type { SystemRole } from '../types/auth'
import ConnectionLogPage from './ConnectionLogPage'

const LOG = '/api/v1/audit/connection-log'

function signInAs(systemRole: SystemRole): void {
  useAuthStore.setState({
    user: { id: 'me', email: null, displayName: 'Revision', systemRole },
    isAuthenticated: true,
  })
}

const expired: ConnectionLogEntryResponse = {
  eventId: 'e-1',
  recordedAt: '2026-09-25T03:00:00Z',
  organizationId: 'org-1',
  eventType: 'EXPIRED',
  actorRef: 'SYSTEM',
  ownerKind: 'PERSON',
  personRef: 'p-2c18d0',
  libraryId: null,
  accountLabel: null,
  profileId: 'profile-partner',
  profileName: 'Zugang Nextcloud Partner',
  cause: 'PROVIDER_REJECTED',
}

const sourceConnection: ConnectionLogEntryResponse = {
  eventId: 'e-2',
  recordedAt: '2026-09-26T09:00:00Z',
  organizationId: 'org-1',
  eventType: 'CONNECTED',
  actorRef: 'p-91aa07',
  ownerKind: 'LIBRARY',
  personRef: null,
  libraryId: 'lib-7',
  accountLabel: 'ablage@bauamt.example',
  profileId: 'profile-dropbox',
  profileName: 'Zugang Dropbox',
  cause: null,
}

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.type(screen.getByLabelText('Von'), '2026-09-01T00:00')
  await user.type(screen.getByLabelText('Bis'), '2026-09-30T00:00')
  await user.click(screen.getByLabelText('Anlass'))
  await user.paste('Anfrage Personalrat 12/2026')
  await user.click(screen.getByRole('button', { name: 'Protokoll anzeigen' }))
}

describe('ConnectionLogPage', () => {
  it('lists entries with event, cause, owner kind, pseudonym, profile and "System" as actor', async () => {
    signInAs('AUDITOR')
    server.use(
      http.get(LOG, () =>
        HttpResponse.json({
          entries: [expired, sourceConnection],
          page: 0,
          size: 50,
          hasMore: false,
        }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLogPage />)
    await fillAndSubmit(user)

    const table = await screen.findByRole('table', { name: 'Einträge des Verbindungsprotokolls' })
    const [, first, second] = within(table).getAllByRole('row')
    expect(first).toHaveTextContent('Abgelaufen')
    expect(first).toHaveTextContent('Vom Anbieter abgelehnt')
    expect(first).toHaveTextContent('Person')
    expect(first).toHaveTextContent('p-2c18d0')
    expect(first).toHaveTextContent('Zugang Nextcloud Partner')
    expect(within(first).getByText('System')).toBeInTheDocument()
    expect(second).toHaveTextContent('Bibliothek lib-7 · Konto ablage@bauamt.example')
    expect(second).toHaveTextContent('p-91aa07')
  })

  it('sends window, reason and the chosen filters, never a person', async () => {
    signInAs('AUDITOR')
    const queries: URLSearchParams[] = []
    server.use(
      http.get(LOG, ({ request }) => {
        queries.push(new URL(request.url).searchParams)
        return HttpResponse.json({ entries: [expired], page: 0, size: 50, hasMore: false })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLogPage />)
    await fillAndSubmit(user)
    await screen.findByRole('table')

    await user.click(screen.getByLabelText('Ereignis (optional)'))
    await user.click(screen.getByRole('option', { name: 'Abgelaufen' }))
    // the profile seen in the first answer is now on offer
    await user.click(screen.getByLabelText('Zugang (optional)'))
    await user.click(screen.getByRole('option', { name: 'Zugang Nextcloud Partner' }))
    await user.click(screen.getByRole('button', { name: 'Protokoll anzeigen' }))

    await waitFor(() => expect(queries).toHaveLength(2))
    const last = queries[1]
    expect(last.get('reason')).toBe('Anfrage Personalrat 12/2026')
    expect(last.get('eventType')).toBe('EXPIRED')
    expect(last.get('profileId')).toBe('profile-partner')
    expect(last.get('from')).toBeTruthy()
    expect(last.get('to')).toBeTruthy()
    expect([...last.keys()].some((key) => /person|user/i.test(key))).toBe(false)
  })

  it('pages forward while the backend says there is more', async () => {
    signInAs('AUDITOR')
    const pages: string[] = []
    server.use(
      http.get(LOG, ({ request }) => {
        const page = new URL(request.url).searchParams.get('page') ?? '0'
        pages.push(page)
        return HttpResponse.json({
          entries: [expired],
          page: Number(page),
          size: 50,
          hasMore: page === '0',
        })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLogPage />)
    await fillAndSubmit(user)

    await user.click(await screen.findByRole('button', { name: 'Weiter' }))
    expect(await screen.findByText('Seite 2')).toBeInTheDocument()
    expect(pages).toEqual(['0', '1'])
  })

  it('says honestly that entries only arise with connected accounts when the log is empty', async () => {
    signInAs('AUDITOR')
    server.use(
      http.get(LOG, () => HttpResponse.json({ entries: [], page: 0, size: 50, hasMore: false })),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLogPage />)
    await fillAndSubmit(user)

    expect(
      await screen.findByText(/solange niemand ein Konto verbunden hat, bleibt das Protokoll leer/),
    ).toBeInTheDocument()
  })

  it('shows a refused read (too wide a window) as an error', async () => {
    signInAs('AUDITOR')
    server.use(
      http.get(LOG, () =>
        HttpResponse.json(
          {
            error: 'Der Zeitraum ist zu weit gefasst',
            status: 400,
            timestamp: '2026-10-04T00:00:00Z',
          },
          { status: 400 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLogPage />)
    await fillAndSubmit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent('Der Zeitraum ist zu weit gefasst')
  })

  it('is closed to an account without the audit role', () => {
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<ConnectionLogPage />)

    expect(
      screen.getByText('Das Verbindungsprotokoll ist der Revision vorbehalten.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Protokoll anzeigen' })).not.toBeInTheDocument()
  })
})
