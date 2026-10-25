import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderWithProviders, setMockAuthState } from '@/test/test-utils'
import type {
  ConnectionProfileOption,
  LibraryRequest,
  SourceBrowseRequest,
  SourceConnectionTestRequest,
} from '@/shared/api/types'
import { ONEDRIVE_MESSAGES } from '../mocks/oneDriveSourceMock'
import LibraryCreatePage from './LibraryCreatePage'

type User = ReturnType<typeof userEvent.setup>

const PROFILE: ConnectionProfileOption = {
  id: 'profile-onedrive',
  name: 'Microsoft 365 Rheinfurt',
  sourceType: 'ONEDRIVE',
  serverUrl: 'https://graph.microsoft.com',
  authMethod: 'OAUTH',
  ownership: 'PERSON',
  ownAccount: true,
  connectorDefaults: null,
}

function renderWizard() {
  return renderWithProviders(
    <Routes>
      <Route path="/libraries/new" element={<LibraryCreatePage />} />
      <Route path="/libraries/:libraryId" element={<div>Bibliothek</div>} />
    </Routes>,
    { withRouter: true, initialRoute: '/libraries/new' },
  )
}

function next(user: User) {
  return user.click(screen.getByRole('button', { name: 'Weiter' }))
}

function rowOf(title: string) {
  return screen.getByRole('region', { name: title })
}

/** An entry of the step „Prüfen“ by its label. */
function reviewOf(label: string) {
  return screen.getByText(label, { selector: 'li > span' }).closest('li')!
}

/** Chooses OneDrive and goes on to „Quelle einrichten“. */
async function chooseOneDrive(user: User) {
  await user.click(await screen.findByRole('radio', { name: /^OneDrive/ }))
  await next(user)
}

/**
 * Chooses the private way on the profile: „Verbindung“ has nothing to enter, so „Umfang“ opens,
 * and the row „Verbindung“ names the OneDrive once it is found.
 */
async function choosePrivate(user: User) {
  await user.click(await screen.findByRole('radio', { name: /Microsoft 365 Rheinfurt · privat/ }))
  await screen.findByRole('radio', { name: 'Alle Ordner' })
  await waitFor(() =>
    expect(rowOf('Verbindung')).toHaveTextContent(
      'Zugang „Microsoft 365 Rheinfurt“, eigenes Konto · OneDrive',
    ),
  )
}

describe('LibraryCreatePage - OneDrive (#2467)', () => {
  let browsed: SourceBrowseRequest[] = []
  let tested: SourceConnectionTestRequest[] = []
  let created: LibraryRequest[] = []

  beforeEach(() => {
    setMockAuthState()
    sessionStorage.clear()
    browsed = []
    tested = []
    created = []
    server.use(
      http.get('/api/v1/connection-profiles', () => HttpResponse.json([PROFILE])),
      // record and pass on to the mock connector
      http.post('/api/v1/source-types/ONEDRIVE/browse', async ({ request }) => {
        browsed.push((await request.clone().json()) as SourceBrowseRequest)
        return undefined
      }),
      http.post('/api/v1/libraries/source-test', async ({ request }) => {
        tested.push((await request.clone().json()) as SourceConnectionTestRequest)
        return undefined
      }),
      // the mock profiles of the tests hold no OneDrive Zugang: the creation answers here
      http.post('/api/v1/libraries', async ({ request }) => {
        created.push((await request.json()) as LibraryRequest)
        return HttpResponse.json(
          {
            id: 'lib-onedrive',
            name: 'OneDrive',
            ownerType: 'USER',
            ownerId: 'mock-user-id',
            myRole: 'OWNER',
            documentCount: 0,
            sourceType: 'ONEDRIVE',
            privateLibrary: true,
            createdAt: '2026-10-25T08:00:00Z',
            updatedAt: '2026-10-25T08:00:00Z',
          },
          { status: 201 },
        )
      }),
    )
  })

  it('offers OneDrive only as a private library, never shared and never with an own address', async () => {
    const user = userEvent.setup()
    renderWizard()
    await chooseOneDrive(user)

    const group = await screen.findByRole('radiogroup', { name: 'Zugang' })
    const radios = within(group).getAllByRole('radio')
    expect(radios).toHaveLength(1)
    expect(radios[0]).toHaveAccessibleName(/Microsoft 365 Rheinfurt · privat/)
    expect(radios[0]).not.toBeChecked()
    expect(screen.queryByRole('radio', { name: /Eigene Adresse/ })).not.toBeInTheDocument()

    await next(user)
    expect(await screen.findByText('Bitte wählen Sie einen Zugang.')).toBeVisible()
    expect(browsed).toEqual([])
  }, 20000)

  it('finds the OneDrive of the connected account and creates a private library of all of it', async () => {
    const user = userEvent.setup()
    renderWizard()
    await chooseOneDrive(user)
    await choosePrivate(user)

    expect(browsed[0]).toMatchObject({
      connectionProfileId: 'profile-onedrive',
      privateLibrary: true,
      sourceUrl: 'https://graph.microsoft.com',
    })
    expect(browsed[0].query ?? {}).toEqual({})
    expect(browsed[0].sourceCredentials ?? undefined).toBeUndefined()

    await next(user)
    // „Weiter“ tested the connection with the OneDrive just found
    await waitFor(() => expect(tested).toHaveLength(1))
    expect(tested[0]).toMatchObject({
      sourceType: 'ONEDRIVE',
      connectionProfileId: 'profile-onedrive',
      privateLibrary: true,
      sourceSettings: { drive: { driveId: 'b!onedriveMaier', name: 'OneDrive' } },
    })
    const name = await screen.findByLabelText(/^Name/)
    expect(name).toHaveValue('OneDrive')
    expect(screen.queryByText(/· Freigaben/)).not.toBeInTheDocument()
    await next(user)
    expect(reviewOf('Verbindung')).toHaveTextContent('OneDrive')
    expect(reviewOf('Umfang')).toHaveTextContent('Alle Ordner')
    await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

    await waitFor(() => expect(created).toHaveLength(1))
    expect(created[0]).toMatchObject({
      sourceType: 'ONEDRIVE',
      connectionProfileId: 'profile-onedrive',
      privateLibrary: true,
      ownerType: 'USER',
      sourceUrl: 'https://graph.microsoft.com',
      sourceSettings: { drive: { driveId: 'b!onedriveMaier', name: 'OneDrive' } },
    })
    expect(created[0].sourceCredentials ?? undefined).toBeUndefined()
  }, 30000)

  it('narrows the OneDrive to folders in place and through the shared selection dialog', async () => {
    const user = userEvent.setup()
    renderWizard()
    await chooseOneDrive(user)
    await choosePrivate(user)

    const pair = screen.getByRole('radiogroup', { name: 'Umfang: Ordner' })
    expect(within(pair).getByRole('radio', { name: 'Alle Ordner' })).toBeChecked()
    await user.click(within(pair).getByRole('radio', { name: 'Nur ausgewählte Ordner' }))
    // three folders at the top: checkboxes in the row
    await user.click(await screen.findByRole('checkbox', { name: 'Vorlagen' }))
    await user.click(screen.getByRole('button', { name: 'Ordner auswählen …' }))
    const dialog = await screen.findByRole('dialog', { name: 'Ordner auswählen' })
    await user.click(await within(dialog).findByRole('button', { name: '„Projekte“ öffnen' }))
    await user.click(await within(dialog).findByRole('checkbox', { name: '2026' }))
    await user.click(within(dialog).getByRole('button', { name: 'Übernehmen' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    expect(browsed.map((request) => request.query ?? {})).toEqual(
      expect.arrayContaining([
        { drive: 'b!onedriveMaier' },
        { drive: 'b!onedriveMaier', folder: '01PROJEKTE' },
      ]),
    )
    await next(user)
    expect(await screen.findByLabelText(/^Name/)).toHaveValue('')
    await user.type(screen.getByLabelText(/^Name/), 'Meine Vorlagen')
    await next(user)
    expect(reviewOf('Umfang')).toHaveTextContent('2 Ordner')
    expect(reviewOf('Umfang')).toHaveTextContent('Projekte / 2026')
    await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

    await waitFor(() => expect(created).toHaveLength(1))
    expect(created[0].sourceSettings).toEqual({
      drive: {
        driveId: 'b!onedriveMaier',
        name: 'OneDrive',
        folders: [
          { id: '01VORLAGEN', name: 'Vorlagen' },
          { id: '01PROJEKTE2026', name: 'Projekte / 2026' },
        ],
      },
    })
  }, 30000)

  it('lets a passed test lapse once the folders change', async () => {
    const user = userEvent.setup()
    renderWizard()
    await chooseOneDrive(user)
    await choosePrivate(user)

    await user.click(screen.getByRole('button', { name: /^Ändern\s*Verbindung$/ }))
    expect(rowOf('Verbindung')).toHaveTextContent(
      /„OneDrive“ – das OneDrive Ihres verbundenen Microsoft-Kontos/,
    )
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))
    expect(await screen.findByText(ONEDRIVE_MESSAGES.reachable)).toBeVisible()

    await user.click(screen.getByRole('button', { name: /^Ändern\s*Umfang$/ }))
    expect(within(rowOf('Verbindung')).getByText('verbunden')).toBeVisible()
    await user.click(screen.getByRole('radio', { name: 'Nur ausgewählte Ordner' }))
    await user.click(await screen.findByRole('checkbox', { name: 'Notizen' }))
    expect(within(rowOf('Verbindung')).getByText('nicht geprüft')).toBeVisible()
  }, 30000)

  it('names why an account without OneDrive for Business cannot be read and does not go on', async () => {
    server.use(
      http.post('/api/v1/source-types/ONEDRIVE/browse', () =>
        HttpResponse.json({ complete: false, entries: [], message: ONEDRIVE_MESSAGES.notBusiness }),
      ),
    )
    const user = userEvent.setup()
    renderWizard()
    await chooseOneDrive(user)
    await user.click(await screen.findByRole('radio', { name: /Microsoft 365 Rheinfurt · privat/ }))

    // the open row „Umfang“ names the refusal in place of the folders
    expect(await within(rowOf('Umfang')).findByText(/kein OneDrive for Business/)).toBeVisible()
    expect(screen.queryByRole('radio', { name: 'Alle Ordner' })).not.toBeInTheDocument()
    await next(user)
    const row = rowOf('Verbindung')
    expect(await within(row).findByText(/kein OneDrive for Business/)).toBeVisible()
    expect(within(row).getByRole('button', { name: 'Erneut ermitteln' })).toBeVisible()
    expect(within(row).getAllByText('OneDrive fehlt').length).toBeGreaterThan(0)
    expect(tested).toEqual([])
    expect(screen.queryByLabelText(/^Name/)).not.toBeInTheDocument()
  }, 20000)
})
