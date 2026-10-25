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
 * The one private way on the profile is chosen already: „Verbindung“ stays open with it and its
 * consequence, and names the OneDrive once it is found.
 */
async function awaitPrivate() {
  expect(
    await within(rowOf('Verbindung')).findByText(
      /„OneDrive“ – das OneDrive Ihres verbundenen Microsoft-Kontos/,
    ),
  ).toBeVisible()
}

/** Opens „Umfang“; „Verbindung“ then names the Zugang and the OneDrive in its summary. */
async function openScope(user: User) {
  await user.click(screen.getByRole('button', { name: /^Ändern\s*Umfang$/ }))
  await screen.findByRole('radio', { name: 'Alle Ordner' })
  expect(rowOf('Verbindung')).toHaveTextContent(
    'Zugang „Microsoft 365 Rheinfurt“, eigenes Konto · OneDrive',
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

  it('takes the one private way as a line, not a choice, and says it is private and final', async () => {
    const user = userEvent.setup()
    renderWizard()
    await chooseOneDrive(user)

    // guidelines 5.11: a single option is no choice - a line without a radio button
    const row = rowOf('Verbindung')
    expect(await within(row).findByText('Microsoft 365 Rheinfurt · privat')).toBeVisible()
    expect(within(row).getByText('nur für Sie, später nicht änderbar')).toBeVisible()
    expect(within(row).getByText('Zugang')).toBeVisible()
    expect(screen.queryByRole('radiogroup', { name: 'Zugang' })).not.toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: /privat|Eigene Adresse/ })).not.toBeInTheDocument()
    await awaitPrivate()

    await next(user)
    await waitFor(() => expect(tested).toHaveLength(1))
    expect(screen.queryByText('Bitte wählen Sie einen Zugang.')).not.toBeInTheDocument()
    expect(tested[0]).toMatchObject({
      connectionProfileId: 'profile-onedrive',
      privateLibrary: true,
    })
    expect(await screen.findByTestId('library-create-private-note')).toBeVisible()
  }, 20000)

  it('finds the OneDrive of the connected account and creates a private library of all of it', async () => {
    const user = userEvent.setup()
    renderWizard()
    await chooseOneDrive(user)
    await awaitPrivate()

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
    await awaitPrivate()
    await openScope(user)

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
    await awaitPrivate()

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

    // the open row „Verbindung“ names the refusal in place of the OneDrive
    expect(await within(rowOf('Verbindung')).findByText(/kein OneDrive for Business/)).toBeVisible()
    await next(user)
    const row = rowOf('Verbindung')
    expect(await within(row).findByText(/kein OneDrive for Business/)).toBeVisible()
    expect(within(row).getByRole('button', { name: 'Erneut ermitteln' })).toBeVisible()
    expect(within(row).getAllByText('OneDrive fehlt').length).toBeGreaterThan(0)
    expect(tested).toEqual([])
    expect(screen.queryByLabelText(/^Name/)).not.toBeInTheDocument()
  }, 20000)

  describe('a change of Zugang', () => {
    const SECOND: ConnectionProfileOption = {
      ...PROFILE,
      id: 'profile-onedrive-2',
      name: 'Microsoft 365 Schulamt',
    }
    let secondAnswer: () => Response

    beforeEach(() => {
      server.use(
        http.get('/api/v1/connection-profiles', () => HttpResponse.json([PROFILE, SECOND])),
        http.post('/api/v1/source-types/ONEDRIVE/browse', async ({ request }) => {
          const body = (await request.clone().json()) as SourceBrowseRequest
          browsed.push(body)
          if (body.connectionProfileId !== SECOND.id || body.query?.drive) return undefined
          return secondAnswer()
        }),
      )
    })

    /** Chooses the first Zugang, narrows to „Vorlagen“, then switches to the second one. */
    async function narrowThenSwitch(user: User) {
      await chooseOneDrive(user)
      // two ways of connection: „Verbindung“ stays open with the choice
      await user.click(
        await screen.findByRole('radio', { name: /Microsoft 365 Rheinfurt · privat/ }),
      )
      expect(
        await within(rowOf('Verbindung')).findByText(/„OneDrive“ – das OneDrive Ihres/),
      ).toBeVisible()
      await user.click(screen.getByRole('button', { name: /^Ändern\s*Umfang$/ }))
      await user.click(screen.getByRole('radio', { name: 'Nur ausgewählte Ordner' }))
      await user.click(await screen.findByRole('checkbox', { name: 'Vorlagen' }))
      await user.click(screen.getByRole('button', { name: /^Ändern\s*Verbindung$/ }))
      await user.click(screen.getByRole('radio', { name: /Microsoft 365 Schulamt · privat/ }))
    }

    it('chooses none of several private ways by itself and asks for one on „Weiter“', async () => {
      const user = userEvent.setup()
      renderWizard()
      await chooseOneDrive(user)

      const group = await screen.findByRole('radiogroup', { name: 'Zugang' })
      const radios = within(group).getAllByRole('radio')
      expect(radios).toHaveLength(2)
      for (const radio of radios) expect(radio).not.toBeChecked()
      await next(user)
      expect(await screen.findByText('Bitte wählen Sie einen Zugang.')).toBeVisible()
      expect(tested).toEqual([])
    }, 20000)

    it('takes the OneDrive of the new account and drops the folders of the old one', async () => {
      secondAnswer = () =>
        HttpResponse.json({
          complete: true,
          entries: [{ key: 'drive:b!onedriveSchulamt', name: 'OneDrive Schulamt' }],
        })
      const user = userEvent.setup()
      renderWizard()
      await narrowThenSwitch(user)

      await waitFor(() => expect(rowOf('Verbindung')).toHaveTextContent('OneDrive Schulamt'))
      expect(rowOf('Umfang')).toHaveTextContent('Alle Ordner')
      await next(user)
      await waitFor(() => expect(tested.length).toBeGreaterThan(0))
      expect(tested[tested.length - 1]).toMatchObject({
        connectionProfileId: 'profile-onedrive-2',
        sourceSettings: { drive: { driveId: 'b!onedriveSchulamt', name: 'OneDrive Schulamt' } },
      })
      expect(tested[tested.length - 1].sourceSettings?.drive).not.toHaveProperty('folders')
    }, 30000)

    it('keeps the folders when the new account owns the same OneDrive', async () => {
      secondAnswer = () =>
        HttpResponse.json({
          complete: true,
          entries: [{ key: 'drive:b!onedriveMaier', name: 'OneDrive' }],
        })
      const user = userEvent.setup()
      renderWizard()
      await narrowThenSwitch(user)

      await waitFor(() =>
        expect(browsed.some((request) => request.connectionProfileId === SECOND.id)).toBe(true),
      )
      await next(user)
      await waitFor(() => expect(tested.length).toBeGreaterThan(0))
      expect(tested[tested.length - 1]).toMatchObject({
        connectionProfileId: 'profile-onedrive-2',
        sourceSettings: {
          drive: { driveId: 'b!onedriveMaier', folders: [{ id: '01VORLAGEN', name: 'Vorlagen' }] },
        },
      })
    }, 30000)

    it('drops the OneDrive of the old account when the new one is refused, and does not go on', async () => {
      secondAnswer = () =>
        HttpResponse.json({ complete: false, entries: [], message: ONEDRIVE_MESSAGES.notBusiness })
      const user = userEvent.setup()
      renderWizard()
      await narrowThenSwitch(user)

      const row = rowOf('Verbindung')
      expect(await within(row).findByText(/kein OneDrive for Business/)).toBeVisible()
      await next(user)
      expect(within(rowOf('Verbindung')).getAllByText('OneDrive fehlt').length).toBeGreaterThan(0)
      expect(screen.queryByLabelText(/^Name/)).not.toBeInTheDocument()
      expect(tested.filter((probe) => probe.connectionProfileId === SECOND.id)).toEqual([])
      expect(rowOf('Umfang')).not.toHaveTextContent('Vorlagen')
      await user.click(screen.getByRole('button', { name: /^Ändern\s*Umfang$/ }))
      expect(screen.queryByRole('checkbox', { name: 'Vorlagen' })).not.toBeInTheDocument()
    }, 30000)
  })
})
