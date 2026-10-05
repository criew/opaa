import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '../mocks/server'
import { mockMyGroups } from '../mocks/groupFixtures'
import { leaveFor } from '../services/leaveApp'
import { renderWithProviders, setMockAuthState } from '../test/test-utils'
import type {
  ConnectionAuthorizationStartRequest,
  ConnectionProfileOption,
  LibraryRequest,
  SourceBrowseRequest,
} from '../types/api'
import { scheduleValuesFrom } from '../utils/librarySchedule'
import {
  SERVICE_ACCOUNT_CONFIRMATION,
  readConsentIntent,
  rememberConsentIntent,
  type WizardDraft,
} from '../components/library/sourceConsent'
import LibraryCreatePage from './LibraryCreatePage'

vi.mock('../services/leaveApp', () => ({ leaveFor: vi.fn() }))

type User = ReturnType<typeof userEvent.setup>

const DROPBOX: ConnectionProfileOption = {
  id: 'profile-dropbox',
  name: 'Dropbox Bauamt',
  sourceType: 'NEXTCLOUD',
  serverUrl: 'https://cloud.bauamt.example',
  authMethod: 'OAUTH',
  ownership: 'LIBRARY',
  ownAccount: false,
  sourceInsecureSsl: false,
  creatable: true,
}

const PENDING = {
  id: 'pending-1',
  accountLabel: 'svc@bauamt.example',
  expiresAt: new Date(Date.now() + 50 * 60 * 1000).toISOString(),
}

const DRAFT: WizardDraft = {
  sourceType: 'NEXTCLOUD',
  chosenConnections: { NEXTCLOUD: DROPBOX.id },
  sourceValues: {
    sourceUrl: 'https://cloud.bauamt.example',
    username: '',
    sourceProxy: '',
    sourceInsecureSsl: false,
    folders: '/Bauamt',
  },
  schedule: scheduleValuesFrom(null),
  startFirstRun: false,
  name: '',
  nameTouched: false,
  description: '',
  ownerType: 'USER',
  selectedGroup: null,
  pendingGrants: [],
  responsibleIsGroup: false,
}

function Address() {
  const location = useLocation()
  return <div data-testid="address">{`${location.pathname}${location.search}`}</div>
}

function renderWizard() {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/libraries/new" element={<LibraryCreatePage />} />
        <Route path="/libraries/:libraryId" element={<div>Bibliothek</div>} />
        <Route path="/catalog" element={<div>Katalog</div>} />
      </Routes>
      <Address />
    </>,
    { withRouter: true, initialRoute: '/libraries/new' },
  )
}

function next(user: User) {
  return user.click(screen.getByRole('button', { name: 'Weiter' }))
}

/** Chooses Nextcloud, goes on to „Quelle" and picks the profile named `name`. */
async function chooseProfile(user: User, name: RegExp) {
  await user.click(await screen.findByRole('radio', { name: /Nextcloud/ }))
  await next(user)
  await user.click(await screen.findByRole('radio', { name }))
}

describe('LibraryCreatePage - Quelle verbinden (#2169)', () => {
  let started: ConnectionAuthorizationStartRequest[] = []
  let browsed: SourceBrowseRequest[] = []
  let created: LibraryRequest[] = []
  let createAnswer: () => Response

  function serveOptions(options: ConnectionProfileOption[]) {
    server.use(http.get('/api/v1/connection-profiles', () => HttpResponse.json(options)))
  }

  beforeEach(() => {
    setMockAuthState()
    sessionStorage.clear()
    vi.mocked(leaveFor).mockReset()
    started = []
    browsed = []
    created = []
    createAnswer = () =>
      HttpResponse.json(
        {
          id: 'lib-neu',
          name: 'Bauamt',
          ownerType: 'USER',
          ownerId: 'mock-user-id',
          myRole: 'OWNER',
          documentCount: 0,
          sourceType: 'NEXTCLOUD',
          createdAt: '2026-10-05T08:00:00Z',
          updatedAt: '2026-10-05T08:00:00Z',
        },
        { status: 201 },
      )
    serveOptions([DROPBOX])
    server.use(
      http.post('/api/v1/connections/authorizations', async ({ request }) => {
        started.push((await request.json()) as ConnectionAuthorizationStartRequest)
        return HttpResponse.json({
          authorizationUrl: 'https://provider.example/authorize?state=s',
          expiresAt: '2026-10-05T09:10:00Z',
        })
      }),
      http.post('/api/v1/source-types/:sourceType/browse', async ({ request }) => {
        browsed.push((await request.json()) as SourceBrowseRequest)
        return HttpResponse.json({ entries: [{ key: '/Bauamt', name: 'Bauamt' }] })
      }),
      http.post('/api/v1/libraries', async ({ request }) => {
        created.push((await request.json()) as LibraryRequest)
        return createAnswer()
      }),
    )
  })

  it('shows nothing new on a profile whose libraries enter their own secret', async () => {
    serveOptions([{ ...DROPBOX, authMethod: 'PERSONAL_SECRET' }])
    const user = userEvent.setup()
    renderWizard()
    await chooseProfile(user, /Dropbox Bauamt/)

    expect(await screen.findByRole('button', { name: 'Verbindung testen' })).toBeInTheDocument()
    expect(screen.queryByText(SERVICE_ACCOUNT_CONFIRMATION)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Quelle verbinden' })).not.toBeInTheDocument()
  }, 20000)

  it('shows nothing new for a private library on an OAuth profile for persons', async () => {
    serveOptions([{ ...DROPBOX, ownership: 'PERSON', ownAccount: true }])
    const user = userEvent.setup()
    renderWizard()
    await chooseProfile(user, /Dropbox Bauamt · privat/)

    expect(await screen.findByRole('button', { name: 'Verbindung testen' })).toBeInTheDocument()
    expect(screen.queryByText(SERVICE_ACCOUNT_CONFIRMATION)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Quelle verbinden' })).not.toBeInTheDocument()
  }, 20000)

  it('asks to connect the source before the form, and goes on only once it is connected', async () => {
    const user = userEvent.setup()
    renderWizard()
    await chooseProfile(user, /Dropbox Bauamt/)

    expect(await screen.findByRole('button', { name: 'Quelle verbinden' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: SERVICE_ACCOUNT_CONFIRMATION })).not.toBeChecked()
    expect(screen.queryByRole('button', { name: 'Verbindung testen' })).not.toBeInTheDocument()

    await next(user)
    expect(await screen.findByText('Bitte verbinden Sie zuerst die Quelle.')).toBeVisible()
  }, 20000)

  it('starts no consent without the confirmation of the service account', async () => {
    const user = userEvent.setup()
    renderWizard()
    await chooseProfile(user, /Dropbox Bauamt/)

    await user.click(await screen.findByRole('button', { name: 'Quelle verbinden' }))
    expect(
      await screen.findByText('Bitte bestätigen Sie, dass Sie ein Dienstkonto verbinden.'),
    ).toBeVisible()
    expect(started).toEqual([])
    expect(leaveFor).not.toHaveBeenCalled()
    expect(readConsentIntent()).toBeNull()
  }, 20000)

  it('keeps the draft for the return and leaves for the provider', async () => {
    const user = userEvent.setup()
    renderWizard()
    await chooseProfile(user, /Dropbox Bauamt/)

    await user.click(await screen.findByRole('checkbox', { name: SERVICE_ACCOUNT_CONFIRMATION }))
    await user.click(screen.getByRole('button', { name: 'Quelle verbinden' }))

    await waitFor(() =>
      expect(leaveFor).toHaveBeenCalledWith('https://provider.example/authorize?state=s'),
    )
    expect(started).toEqual([
      { profileId: DROPBOX.id, purpose: 'LIBRARY_NEW', serviceAccountConfirmed: true },
    ])
    const intent = readConsentIntent()
    expect(intent).toMatchObject({
      purpose: 'LIBRARY_NEW',
      profileId: DROPBOX.id,
      draft: { sourceType: 'NEXTCLOUD', chosenConnections: { NEXTCLOUD: DROPBOX.id } },
    })
  }, 20000)

  it('comes back on the source step with the connection, its account and its focus', async () => {
    rememberConsentIntent({
      purpose: 'LIBRARY_NEW',
      profileId: DROPBOX.id,
      draft: DRAFT,
      pending: PENDING,
    })
    const user = userEvent.setup()
    renderWizard()

    const heading = await screen.findByRole('heading', { name: 'Woher kommen die Dokumente?' })
    await waitFor(() => expect(heading).toHaveFocus())
    expect(await screen.findByText(/Verbunden als „svc@bauamt\.example“/)).toBeVisible()
    expect(screen.getByText(/60 Minuten/)).toBeVisible()
    // the draft is consumed: a later wizard starts afresh
    expect(readConsentIntent()).toBeNull()

    await user.click(await screen.findByRole('button', { name: 'Ordner laden' }))
    await waitFor(() => expect(browsed).toHaveLength(1))
    expect(browsed[0]).toMatchObject({
      connectionProfileId: DROPBOX.id,
      pendingConnectionId: PENDING.id,
    })
  }, 20000)

  it('creates the library with the pending connection, the creator answering for it', async () => {
    rememberConsentIntent({
      purpose: 'LIBRARY_NEW',
      profileId: DROPBOX.id,
      draft: DRAFT,
      pending: PENDING,
    })
    const user = userEvent.setup()
    renderWizard()
    await screen.findByText(/Verbunden als/)

    await next(user)
    expect(screen.getByLabelText(/^Name/)).toHaveValue('Bauamt')
    await next(user)
    expect(screen.getByText(/Für die Verbindung der Quelle sind Sie verantwortlich/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

    await waitFor(() =>
      expect(screen.getByTestId('address')).toHaveTextContent(/^\/libraries\/lib-neu$/),
    )
    expect(created).toHaveLength(1)
    expect(created[0]).toMatchObject({
      connectionProfileId: DROPBOX.id,
      pendingConnectionId: PENDING.id,
    })
    expect(created[0].sourceConnectionResponsible ?? null).toBeNull()
  }, 20000)

  it('lets the owning group answer for the connection', async () => {
    server.use(http.get('/api/v1/me/groups', () => HttpResponse.json(mockMyGroups)))
    const group = mockMyGroups[0]
    rememberConsentIntent({
      purpose: 'LIBRARY_NEW',
      profileId: DROPBOX.id,
      draft: { ...DRAFT, name: 'Bauamt', ownerType: 'GROUP', selectedGroup: group },
      pending: PENDING,
    })
    const user = userEvent.setup()
    renderWizard()
    await screen.findByText(/Verbunden als/)
    await next(user)
    await next(user)

    const choice = screen.getByRole('radiogroup', {
      name: 'Verantwortlich für die Verbindung der Quelle',
    })
    await user.click(within(choice).getByRole('radio', { name: `Gruppe „${group.name}“` }))
    await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

    await waitFor(() => expect(created).toHaveLength(1))
    expect(created[0]).toMatchObject({
      ownerType: 'GROUP',
      ownerId: group.id,
      pendingConnectionId: PENDING.id,
      sourceConnectionResponsible: { type: 'GROUP', id: group.id },
    })
  }, 20000)

  it('asks to connect again when the pending connection can no longer be used', async () => {
    createAnswer = () =>
      HttpResponse.json(
        {
          error: 'Die ausstehende Verbindung ist abgelaufen.',
          status: 409,
          timestamp: '2026-10-05T08:00:00Z',
          code: 'PENDING_CONNECTION_UNUSABLE',
        },
        { status: 409 },
      )
    rememberConsentIntent({
      purpose: 'LIBRARY_NEW',
      profileId: DROPBOX.id,
      draft: { ...DRAFT, name: 'Bauamt' },
      pending: PENDING,
    })
    const user = userEvent.setup()
    renderWizard()
    await screen.findByText(/Verbunden als/)
    await next(user)
    await next(user)
    await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

    expect(
      await screen.findByText(
        'Die Zustimmung beim Anbieter ist abgelaufen oder schon verwendet. Bitte verbinden Sie die Quelle erneut.',
      ),
    ).toBeVisible()
    expect(screen.getByRole('heading', { name: 'Woher kommen die Dokumente?' })).toBeInTheDocument()
    expect(screen.queryByText(/Verbunden als/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Quelle verbinden' })).toBeInTheDocument()
  }, 20000)
})
