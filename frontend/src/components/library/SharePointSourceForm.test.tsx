import { useState } from 'react'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { server } from '../../mocks/server'
import { renderWithProviders } from '../../test/test-utils'
import SharePointSourceForm from './SharePointSourceForm'
import { connectionFields, type SourceConnection } from './sources/sourceConnection'
import { EMPTY_SHAREPOINT_VALUES, type SharePointSourceValues } from '../../utils/sharePointSource'

const PROFILE: SourceConnection = {
  profileId: 'connection-profile-sharepoint',
  name: 'SharePoint Rheinfurt',
  serverUrl: 'https://graph.microsoft.com',
  authMethod: 'CLIENT_CREDENTIALS',
  defaults: {},
}

type User = ReturnType<typeof userEvent.setup>

/** The bodies the form sent, by path - the MSW handlers answer them like the server. */
let sent: { path: string; body: Record<string, unknown> }[] = []

function bodiesTo(path: string) {
  return sent.filter((request) => request.path === path).map((request) => request.body)
}

function Harness({
  initial,
  mode = 'create',
  connection = PROFILE,
  onValues,
}: {
  initial?: Partial<SharePointSourceValues>
  mode?: 'create' | 'edit'
  connection?: SourceConnection | null
  onValues?: (values: SharePointSourceValues) => void
}) {
  const [values, setValues] = useState<SharePointSourceValues>({
    ...EMPTY_SHAREPOINT_VALUES,
    ...initial,
  })
  return (
    <SharePointSourceForm
      mode={mode}
      idPrefix="test-sp"
      values={values}
      libraryId={mode === 'edit' ? 'library-sp' : undefined}
      connection={connectionFields({
        mode,
        sourceType: 'SHAREPOINT',
        idPrefix: 'test-sp',
        libraryId: mode === 'edit' ? 'library-sp' : undefined,
        credentialsStored: false,
        connection: connection ?? undefined,
      })}
      onChange={(patch) =>
        setValues((prev) => {
          const next = { ...prev, ...patch }
          onValues?.(next)
          return next
        })
      }
    />
  )
}

async function openSite(user: User, address: string) {
  await user.type(screen.getByLabelText('Adresse der Site'), address)
  await user.click(screen.getByRole('button', { name: 'Site öffnen' }))
}

describe('SharePointSourceForm (ADR-0040, Nachtrag „SharePoint“)', () => {
  beforeEach(() => {
    sent = []
    server.events.on('request:start', async ({ request }) => {
      if (request.method !== 'POST') return
      sent.push({
        path: new URL(request.url).pathname,
        body: (await request.clone().json()) as Record<string, unknown>,
      })
    })
  })

  afterEach(() => {
    server.events.removeAllListeners()
  })

  it('opens a site by its address, takes a document library and tests it through the profile', async () => {
    const user = userEvent.setup()
    let latest: SharePointSourceValues | undefined
    renderWithProviders(<Harness onValues={(values) => (latest = values)} />)

    expect(screen.getByTestId('sharepoint-profile')).toHaveTextContent('SharePoint Rheinfurt')
    expect(screen.queryByLabelText(/Client-Secret|Passwort|Schlüssel/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Verbindung testen' })).toBeDisabled()

    await openSite(user, 'https://rheinfurt.sharepoint.com/sites/rathaus/')
    const drives = await screen.findByRole('list', {
      name: 'Dokumentbibliotheken der Site Rathaus',
    })
    expect(within(drives).getAllByRole('checkbox')).toHaveLength(2)
    await user.click(within(drives).getByRole('checkbox', { name: 'Dokumente' }))

    const chosen = screen.getByRole('list', { name: 'Gewählte Dokumentbibliotheken' })
    expect(chosen).toHaveTextContent('Dokumente (Rathaus) · ganze Dokumentbibliothek')
    expect(latest?.libraries).toEqual([
      { driveId: 'b!rathausDokumente', name: 'Dokumente (Rathaus)', folders: [] },
    ])

    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))
    expect(await screen.findByTestId('sharepoint-test-status')).toHaveTextContent(
      'Anmeldung erfolgreich; alle 1 Dokumentbibliotheken sind erreichbar.',
    )
    expect(chosen).toHaveTextContent('· erreichbar')

    const [listing] = bodiesTo('/api/v1/source-types/SHAREPOINT/browse')
    expect(listing).toMatchObject({
      connectionProfileId: 'connection-profile-sharepoint',
      query: { siteUrl: 'https://rheinfurt.sharepoint.com/sites/rathaus/' },
    })
    expect(listing).not.toHaveProperty('sourceCredentials')
    const [test] = bodiesTo('/api/v1/libraries/source-test')
    expect(test).toMatchObject({
      sourceType: 'SHAREPOINT',
      sourceUrl: 'https://graph.microsoft.com',
      connectionProfileId: 'connection-profile-sharepoint',
      sourceSettings: {
        libraries: [{ driveId: 'b!rathausDokumente', name: 'Dokumente (Rathaus)' }],
      },
    })
    expect(test).not.toHaveProperty('sourceCredentials')
  }, 20000)

  it('narrows a library to folders below its root and names each with its path', async () => {
    const user = userEvent.setup()
    let latest: SharePointSourceValues | undefined
    renderWithProviders(<Harness onValues={(values) => (latest = values)} />)

    await openSite(user, 'https://rheinfurt.sharepoint.com/sites/bauamt')
    await user.click(await screen.findByRole('checkbox', { name: 'Dokumente' }))
    await user.click(screen.getByRole('button', { name: 'Ordner von „Dokumente (Bauamt)“ wählen' }))

    const root = await screen.findByRole('list', { name: 'Ordner auf der Ebene Stamm' })
    await user.click(within(root).getByRole('checkbox', { name: 'Pläne' }))
    await user.click(within(root).getByRole('button', { name: 'Unterordner von „Akten“ anzeigen' }))
    const akten = await screen.findByRole('list', { name: 'Ordner auf der Ebene Stamm / Akten' })
    await user.click(within(akten).getByRole('checkbox', { name: '2026' }))

    expect(latest?.libraries[0].folders).toEqual([
      { id: '01PLAENE', name: 'Pläne' },
      { id: '01AKTEN2026', name: 'Akten / 2026' },
    ])
    expect(screen.getByTestId('sharepoint-library-b!bauamtDokumente')).toHaveTextContent(
      '2 Ordner: Pläne, Akten / 2026',
    )

    await user.click(screen.getByRole('button', { name: 'Eine Ebene höher' }))
    expect(await screen.findByRole('list', { name: 'Ordner auf der Ebene Stamm' })).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Ordner „Pläne“ abwählen' }))
    expect(latest?.libraries[0].folders).toEqual([{ id: '01AKTEN2026', name: 'Akten / 2026' }])

    await user.click(screen.getByRole('button', { name: 'Fertig' }))
    expect(screen.queryByRole('list', { name: /Ordner auf der Ebene/ })).not.toBeInTheDocument()
  }, 20000)

  it('names a site that is not released to the app under Sites.Selected', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await openSite(user, 'https://rheinfurt.sharepoint.com/sites/personalrat')

    expect(await screen.findByTestId('sharepoint-site-status')).toHaveTextContent(
      'Bei der Berechtigung Sites.Selected muss die Site für die App freigegeben sein.',
    )
    expect(screen.queryByTestId('sharepoint-site-drives')).not.toBeInTheDocument()
  })

  it('names a site Microsoft Graph does not know', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await openSite(user, 'https://rheinfurt.sharepoint.com/sites/gibtsnicht')

    expect(await screen.findByTestId('sharepoint-site-status')).toHaveTextContent(
      'Microsoft Graph kennt die Site oder Bibliothek nicht oder zeigt sie der Anwendung nicht.',
    )
  })

  it('refuses an address that is no https address of a site, with the server wording', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await openSite(user, 'http://rheinfurt.sharepoint.com/sites/rathaus')

    expect(await screen.findByTestId('sharepoint-site-status')).toHaveTextContent(
      'siteUrl ist die https-Adresse einer Site',
    )
  })

  it('says when a site holds no document library and that OneDrive is not read', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await openSite(user, 'https://rheinfurt-my.sharepoint.com/personal/anna_maier_rheinfurt_de')

    const drives = await screen.findByTestId('sharepoint-site-drives')
    await waitFor(() =>
      expect(drives).toHaveTextContent(
        'Die Site „Maier, Anna“ hat keine Dokumentbibliothek, die die Anwendung lesen kann. OneDrive und andere Laufwerke liest der Konnektor nicht.',
      ),
    )
    expect(within(drives).queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('points to the address when the site search needs Sites.Read.All', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.type(screen.getByLabelText('Oder nach Sites suchen'), 'Bau{Enter}')

    expect(await screen.findByTestId('sharepoint-site-status')).toHaveTextContent(
      'Die Suche nach Sites braucht die Berechtigung Sites.Read.All.',
    )
    expect(bodiesTo('/api/v1/source-types/SHAREPOINT/browse')[0]).toMatchObject({
      query: { search: 'Bau' },
    })
  })

  it('shows per library what the test found: OneDrive, a site not released and a missing folder', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Harness
        initial={{
          libraries: [
            { driveId: 'b!rathausDokumente', name: 'Dokumente (Rathaus)', folders: [] },
            { driveId: 'b!onedriveMaier', name: 'OneDrive', folders: [] },
            { driveId: 'b!personalratDokumente', name: 'Dokumente (Personalrat)', folders: [] },
            {
              driveId: 'b!bauamtDokumente',
              name: 'Dokumente (Bauamt)',
              folders: [{ id: '01GELOESCHT', name: 'Alt' }],
            },
          ],
        }}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    const status = await screen.findByTestId('sharepoint-test-status')
    await waitFor(() =>
      expect(status).toHaveTextContent(
        '3 von 4 Dokumentbibliotheken sind für die Anwendung nicht erreichbar.',
      ),
    )
    expect(status).toHaveTextContent(
      'OneDrive: Das Laufwerk ist keine SharePoint-Dokumentbibliothek; OneDrive und andere Laufwerke werden nicht gelesen.',
    )
    expect(status).toHaveTextContent(
      'Dokumente (Personalrat): Die Dokumentbibliothek ist für die Anwendung nicht sichtbar.',
    )
    expect(screen.getByTestId('sharepoint-library-b!bauamtDokumente')).toHaveTextContent(
      'ist in der Dokumentbibliothek nicht mehr vorhanden',
    )
    expect(screen.getByTestId('sharepoint-library-b!rathausDokumente')).toHaveTextContent(
      '· erreichbar',
    )
    expect(bodiesTo('/api/v1/libraries/source-test')[0]).toMatchObject({
      sourceSettings: {
        libraries: expect.arrayContaining([
          {
            driveId: 'b!bauamtDokumente',
            name: 'Dokumente (Bauamt)',
            folders: [{ id: '01GELOESCHT', name: 'Alt' }],
          },
        ]),
      },
    })
  })

  it('drops a test result once the selection changes and removes a library on request', async () => {
    const user = userEvent.setup()
    let latest: SharePointSourceValues | undefined
    renderWithProviders(
      <Harness
        initial={{
          libraries: [
            { driveId: 'b!rathausDokumente', name: 'Dokumente (Rathaus)', folders: [] },
            { driveId: 'b!rathausSatzungen', name: 'Satzungen (Rathaus)', folders: [] },
          ],
        }}
        onValues={(values) => (latest = values)}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))
    await screen.findByText(/alle 2 Dokumentbibliotheken sind erreichbar/)
    await user.click(
      screen.getByRole('button', { name: 'Dokumentbibliothek „Satzungen (Rathaus)“ entfernen' }),
    )

    expect(latest?.libraries.map((library) => library.driveId)).toEqual(['b!rathausDokumente'])
    expect(
      screen.queryByText(/alle 2 Dokumentbibliotheken sind erreichbar/),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: /Gewählte Dokumentbibliotheken/ }),
    ).toHaveTextContent('(1 von höchstens 50)')
  })

  it('asks for a profile first and lists nothing without one', () => {
    renderWithProviders(<Harness connection={null} />)

    expect(screen.getByTestId('sharepoint-needs-profile')).toHaveTextContent(
      'SharePoint ist nur über einen Zugang nutzbar.',
    )
    expect(screen.getByRole('button', { name: 'Site öffnen' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Sites suchen' })).toBeDisabled()
  })

  it('edits through the library: the stored folders keep their names and the test names the library', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Harness
        mode="edit"
        initial={{
          libraries: [
            {
              driveId: 'b!bauamtDokumente',
              name: 'Dokumente (Bauamt)',
              folders: [{ id: '01AKTEN2026', name: 'Akten / 2026' }],
            },
          ],
        }}
      />,
    )

    expect(screen.getByTestId('sharepoint-library-b!bauamtDokumente')).toHaveTextContent(
      'Dokumente (Bauamt) · Ordner: Akten / 2026',
    )
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))
    await screen.findByText(/alle 1 Dokumentbibliotheken sind erreichbar/)

    const [test] = bodiesTo('/api/v1/libraries/source-test')
    expect(test.libraryId).toBe('library-sp')
    expect(test).not.toHaveProperty('connectionProfileId')
  })
})
