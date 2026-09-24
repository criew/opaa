import { describe, expect, it, beforeEach, vi } from 'vitest'
import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
import LibraryCreatePage from './LibraryCreatePage'
import { useLibraryStore } from '../stores/libraryStore'
import { useIndexingStore } from '../stores/indexingStore'
import { capabilityMissingMessage } from '../utils/labels'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return { ...actual, useNavigate: () => mockNavigate }
})

const {
  mockGetMyGroups,
  mockGetMyCapabilities,
  mockTestLibrarySource,
  mockListConfluenceSpaces,
  mockListS3Buckets,
  mockGetUserSummaries,
  mockUpsertAssetGrant,
  mockSearchSelectableGroups,
} = vi.hoisted(() => ({
  mockGetMyGroups: vi.fn().mockResolvedValue([]),
  mockGetMyCapabilities: vi.fn(),
  mockTestLibrarySource: vi.fn(),
  mockListConfluenceSpaces: vi.fn(),
  mockListS3Buckets: vi.fn(),
  mockGetUserSummaries: vi.fn().mockResolvedValue([]),
  mockUpsertAssetGrant: vi.fn(),
  mockSearchSelectableGroups: vi.fn(),
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getMyGroups: mockGetMyGroups,
    getMyCapabilities: mockGetMyCapabilities,
    getUserSummaries: mockGetUserSummaries,
    upsertAssetGrant: mockUpsertAssetGrant,
    searchSelectableGroups: mockSearchSelectableGroups,
    testLibrarySource: mockTestLibrarySource,
    listConfluenceSpaces: mockListConfluenceSpaces,
    listS3Buckets: mockListS3Buckets,
  }
})

const mockCreateNewLibrary = vi.fn().mockResolvedValue('lib-neu')
const mockTriggerIndexing = vi.fn().mockResolvedValue(undefined)

type User = ReturnType<typeof userEvent.setup>

function renderPage() {
  return renderWithProviders(<LibraryCreatePage />, { withRouter: true })
}

function next(user: User) {
  return user.click(screen.getByRole('button', { name: 'Weiter' }))
}

/** Schritt 1 „Art des Wissens": Kachel wählen und weiter - danach steht „Quelle" (oder „Name"). */
async function chooseType(user: User, label: RegExp) {
  await user.click(screen.getByRole('radio', { name: label }))
  await next(user)
}

/** Schritt „Name & Beschreibung": den vorbelegten Namen überschreiben und weiter zu „Freigaben". */
async function nameItAndContinue(user: User, name: string) {
  const field = screen.getByLabelText(/^Name/)
  await user.clear(field)
  await user.type(field, name)
  await next(user)
}

describe('LibraryCreatePage (#596, #1942)', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    mockCreateNewLibrary.mockClear()
    mockTriggerIndexing.mockClear()
    mockGetMyGroups.mockResolvedValue([])
    mockGetMyCapabilities.mockResolvedValue([
      'CREATE_SPACE',
      'CREATE_LIBRARY',
      'CREATE_CONNECTOR_LIBRARY',
    ])
    useLibraryStore.setState({ createNewLibrary: mockCreateNewLibrary })
    useIndexingStore.setState({ triggerIndexing: mockTriggerIndexing })
  })

  // #1942: Jeder Schritt trägt den Namen des Reiters, den er in der Detailansicht bekommt - und
  // eine Upload-Bibliothek hat keine Quelle, also auch keinen Schritt dafür.
  it('names the steps after the tabs and drops "Quelle" for an upload library', async () => {
    const user = userEvent.setup()
    renderPage()

    expect(screen.getByText('1 · Art des Wissens')).toBeInTheDocument()
    expect(screen.getByText('2 · Name & Beschreibung')).toBeInTheDocument()
    expect(screen.getByText('3 · Freigaben')).toBeInTheDocument()
    expect(screen.queryByText(/· Quelle/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: /Confluence/ }))
    expect(screen.getByText('2 · Quelle')).toBeInTheDocument()
    expect(screen.getByText('3 · Name & Beschreibung')).toBeInTheDocument()
    expect(screen.getByText('4 · Freigaben')).toBeInTheDocument()
  })

  it('blocks Weiter without a name and says so', async () => {
    const user = userEvent.setup()
    renderPage()

    await next(user)
    await next(user)
    expect(await screen.findByText('Bitte einen Namen angeben')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Name/)).toBeInTheDocument()
  })

  it('carries the focus to the heading of the step just entered', async () => {
    const user = userEvent.setup()
    renderPage()

    await next(user)

    const heading = screen.getByRole('heading', { level: 2, name: 'Name & Beschreibung' })
    expect(heading).toHaveFocus()
  })

  describe('Anlegerechte (#1813, ADR-0036 Entscheidung 5)', () => {
    it('disables the tiles of a kind the caller may not create, with the reason on the tile', async () => {
      mockGetMyCapabilities.mockResolvedValue(['CREATE_CONNECTOR_LIBRARY'])
      renderPage()

      const upload = await screen.findByRole('radio', { name: /Upload/ })
      expect(upload).toBeDisabled()
      expect(upload).toHaveTextContent(capabilityMissingMessage('CREATE_LIBRARY'))
      expect(screen.getByRole('radio', { name: /Confluence/ })).toBeEnabled()
    })

    it('names the connector right once a connector source is chosen', async () => {
      mockGetMyCapabilities.mockResolvedValue(['CREATE_LIBRARY'])
      const user = userEvent.setup()
      renderPage()

      await waitFor(() => expect(screen.getByRole('radio', { name: /Dateisystem/ })).toBeDisabled())
      expect(screen.getByRole('radio', { name: /Upload/ })).toBeEnabled()
      // Eine gesperrte Kachel wählt nichts aus - der Typ bleibt der zuvor gewählte.
      await user.click(screen.getByRole('radio', { name: /Dateisystem/ }))
      expect(screen.getByRole('radio', { name: /Upload/ })).toBeChecked()
    })
  })

  it('notes a person with the shared subject picker and grants on the asset after creation', async () => {
    mockGetUserSummaries.mockResolvedValue([
      { id: 'user-alice', email: 'alice@example.com', displayName: 'Alice' },
    ])
    mockUpsertAssetGrant.mockResolvedValue({})
    const user = userEvent.setup()
    renderPage()

    await next(user)
    await nameItAndContinue(user, 'Rechtsquellen Soziales')
    await user.type(screen.getByRole('combobox', { name: 'Person suchen' }), 'al')
    await user.click(await screen.findByRole('option', { name: /Alice/ }))
    await user.click(screen.getByRole('button', { name: 'Vormerken' }))
    await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

    await waitFor(() =>
      expect(mockUpsertAssetGrant).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', 'lib-neu', {
        subjectType: 'USER',
        subjectId: 'user-alice',
        role: 'VIEWER',
      }),
    )
  }, 15000)

  // #1942: Der Katalog-Schalter ist neu im Assistenten und wird beim Anlegen mitgesetzt.
  it('sets the catalog switch together with the library', async () => {
    const user = userEvent.setup()
    renderPage()

    await next(user)
    await nameItAndContinue(user, 'Rechtsquellen Soziales')
    await user.click(screen.getByLabelText('Im Katalog auffindbar, auch ohne Berechtigung'))
    await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

    await waitFor(() =>
      expect(mockCreateNewLibrary).toHaveBeenCalledWith(expect.objectContaining({ listed: true })),
    )
  }, 15000)

  // The library exists once the POST succeeded: a refused grant leads to its detail page instead
  // of re-enabling "Bibliothek anlegen", which would create a second library.
  it('goes to the new library when a noted grant is refused, and names the grant', async () => {
    mockGetUserSummaries.mockResolvedValue([
      { id: 'user-alice', email: 'alice@example.com', displayName: 'Alice' },
    ])
    mockUpsertAssetGrant.mockRejectedValueOnce(new Error('abgelehnt'))
    const user = userEvent.setup()
    renderPage()

    await next(user)
    await nameItAndContinue(user, 'Rechtsquellen Soziales')
    await user.type(screen.getByRole('combobox', { name: 'Person suchen' }), 'al')
    await user.click(await screen.findByRole('option', { name: /Alice/ }))
    await user.click(screen.getByRole('button', { name: 'Vormerken' }))
    await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu'))
    expect(mockCreateNewLibrary).toHaveBeenCalledTimes(1)
    expect(await screen.findByText(/nicht gespeichert werden: Alice/)).toBeInTheDocument()
  }, 15000)

  /** ADR-0036, Entscheidung 2: dieselbe Zwischenfrage wie im Abschnitt „Berechtigungen". */
  it('asks before noting a group of an external provider and notes nothing on cancel', async () => {
    mockSearchSelectableGroups.mockResolvedValue([
      {
        id: 'group-partner',
        name: 'Referat 50',
        origin: 'PROVIDER',
        provider: {
          id: 'oidc-provider-partner',
          displayName: 'Verzeichnis Partner',
          external: true,
          enabled: true,
          groupMechanism: 'TOKEN',
        },
        sourcePath: null,
        activeMemberCount: 23,
        smallGroup: false,
        emptyGroup: false,
        protectedGroup: false,
        selectable: true,
        dissolved: false,
        providerDisabled: false,
        unmaintained: false,
      },
    ])
    const user = userEvent.setup()
    renderPage()

    await next(user)
    await nameItAndContinue(user, 'Rechtsquellen Soziales')
    await user.click(await screen.findByRole('radio', { name: /^gruppe$/i }))
    await user.type(await screen.findByLabelText(/^gruppe suchen$/i), 'Referat')
    await user.click(await screen.findByRole('option', { name: /Referat 50/ }))
    await user.click(screen.getByRole('button', { name: 'Vormerken' }))

    const question = 'Sie geben für eine Gruppe eines externen Anbieters frei — fortfahren?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /Verzeichnis Partner/,
    )
    await answerConfirm(user, question, 'Abbrechen')

    await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))
    await waitFor(() => expect(mockCreateNewLibrary).toHaveBeenCalled())
    expect(mockUpsertAssetGrant).not.toHaveBeenCalled()
  }, 20000)

  describe('Zeitplan und Sofortstart im Assistenten (#1942)', () => {
    it('sets the schedule together with the library, for a connector type that never had one here', async () => {
      const user = userEvent.setup()
      renderPage()

      await chooseType(user, /Dateisystem/)
      await user.type(screen.getByLabelText(/Verzeichnispfad/), '/data/dokumente')
      await user.click(screen.getByRole('combobox', { name: 'Zeitplan' }))
      await user.click(await screen.findByRole('option', { name: 'Täglich' }))
      await next(user)
      await nameItAndContinue(user, 'Dienstanweisungen')
      await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

      await waitFor(() =>
        expect(mockCreateNewLibrary).toHaveBeenCalledWith(
          expect.objectContaining({
            sourceType: 'FILESYSTEM',
            schedule: { frequency: 'DAILY', hour: 3, minute: 0, weekday: undefined },
          }),
        ),
      )
      // Sofortstart gilt seit #1942 für jeden Konnektortyp, nicht mehr nur Confluence und S3.
      expect(mockTriggerIndexing).toHaveBeenCalledWith('lib-neu', 'FILESYSTEM')
    }, 25000)

    it('sends no schedule at all for an upload library', async () => {
      const user = userEvent.setup()
      renderPage()

      await next(user)
      await nameItAndContinue(user, 'Handakte')
      await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

      await waitFor(() => expect(mockCreateNewLibrary).toHaveBeenCalled())
      expect(mockCreateNewLibrary.mock.calls[0][0]).not.toHaveProperty('schedule')
      expect(mockTriggerIndexing).not.toHaveBeenCalled()
    }, 15000)
  })

  describe('S3 origin (#1377, ADR-0027)', () => {
    beforeEach(() => {
      mockTestLibrarySource.mockReset()
      mockListS3Buckets.mockReset()
    })

    async function openS3Step() {
      const user = userEvent.setup()
      renderPage()
      await chooseType(user, /S3-Objektspeicher/)
      return user
    }

    it('offers the S3 tile, states the sharing consequence before the scopes, and blocks Weiter until the stages are complete', async () => {
      const user = await openS3Step()

      const consequence = screen.getByTestId('library-create-s3-sharing-consequence')
      const scopes = screen.getByRole('list', { name: 'Geltungsbereiche' })
      expect(consequence).toHaveTextContent(
        'Wer diese Bibliothek lesen darf, sieht alles aus allen Geltungsbereichen.',
      )
      expect(
        consequence.compareDocumentPosition(scopes) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy()

      await next(user)
      expect(screen.getByText('Endpoint des Objektspeichers ist erforderlich')).toBeInTheDocument()
      await user.type(screen.getByLabelText('Endpoint'), 'https://minio.intern.example:9000')
      await next(user)
      expect(screen.getByText('Access Key ist erforderlich')).toBeInTheDocument()
    }, 15000)

    it('creates a MinIO library with the template values and sends s3Settings', async () => {
      // the bucket suggestion itself is covered in S3SourceForm.test.tsx; this flow types by hand
      const user = await openS3Step()
      await user.type(screen.getByLabelText('Endpoint'), 'https://minio.intern.example:9000')
      await user.type(screen.getByLabelText('Access Key'), 'AKIAEXAMPLE')
      await user.type(screen.getByLabelText('Secret Key'), 'geheim')
      await user.type(screen.getByLabelText('Bucket 1'), 'protokolle')
      await user.type(screen.getByLabelText(/^Präfix 1/), '2025/protokolle')
      await user.click(screen.getByRole('button', { name: 'Bereich' }))
      await user.type(screen.getByLabelText('Bucket 2'), 'satzungen')
      await next(user)
      // #1942: Der Name ist aus der Quelle vorbelegt - hier der erste Bucket.
      expect(screen.getByLabelText(/^Name/)).toHaveValue('protokolle')
      await nameItAndContinue(user, 'Protokolle')
      await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

      await waitFor(() =>
        expect(mockCreateNewLibrary).toHaveBeenCalledWith(
          expect.objectContaining({
            name: 'Protokolle',
            sourceType: 'S3',
            sourceUrl: 'https://minio.intern.example:9000',
            sourceCredentials: 'AKIAEXAMPLE:geheim',
            sourceInsecureSsl: false,
            s3Settings: {
              region: 'us-east-1',
              pathStyle: true,
              scopes: [
                { bucket: 'protokolle', prefix: '2025/protokolle/' },
                { bucket: 'satzungen', prefix: null },
              ],
              includePatterns: [],
              excludePatterns: [],
            },
          }),
        ),
      )
      expect(mockCreateNewLibrary.mock.calls[0][0]).not.toHaveProperty('confluenceSpaces')
      // the "Erste Indizierung sofort ..." switch defaults to on: the full sync starts right after
      // creation, before the navigation to the detail page
      expect(mockTriggerIndexing).toHaveBeenCalledWith('lib-neu', 'S3')
      expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu')
    }, 30000)

    it('creates an AWS library from the template with a derived virtual-host endpoint', async () => {
      const user = await openS3Step()
      await user.click(screen.getByRole('radio', { name: 'AWS S3' }))
      expect(screen.getByLabelText('Endpoint')).toHaveValue('https://s3.eu-central-1.amazonaws.com')
      await user.type(screen.getByLabelText('Access Key'), 'AKIAEXAMPLE')
      await user.type(screen.getByLabelText('Secret Key'), 'geheim')
      await user.type(screen.getByLabelText('Bucket 1'), 'verwaltung-dokumente')
      await user.click(
        screen.getByRole('switch', { name: 'Erste Indizierung sofort nach dem Anlegen starten' }),
      )
      await next(user)
      await nameItAndContinue(user, 'Verwaltungsdokumente')
      await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

      await waitFor(() =>
        expect(mockCreateNewLibrary).toHaveBeenCalledWith(
          expect.objectContaining({
            sourceType: 'S3',
            sourceUrl: 'https://s3.eu-central-1.amazonaws.com',
            s3Settings: expect.objectContaining({
              region: 'eu-central-1',
              pathStyle: false,
              scopes: [{ bucket: 'verwaltung-dokumente', prefix: null }],
            }),
          }),
        ),
      )
      await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu'))
      // switched off: no first run, the detail page's "Jetzt indizieren" or the schedule starts it
      expect(mockTriggerIndexing).not.toHaveBeenCalled()
    }, 25000)

    it('refuses overlapping scopes before anything is sent', async () => {
      const user = await openS3Step()
      await user.type(screen.getByLabelText('Endpoint'), 'https://minio.intern.example:9000')
      await user.type(screen.getByLabelText('Access Key'), 'AKIAEXAMPLE')
      await user.type(screen.getByLabelText('Secret Key'), 'geheim')
      await user.type(screen.getByLabelText('Bucket 1'), 'dokumente')
      await user.type(screen.getByLabelText(/^Präfix 1/), '2025')
      await user.click(screen.getByRole('button', { name: 'Bereich' }))
      await user.type(screen.getByLabelText('Bucket 2'), 'dokumente')
      await user.type(screen.getByLabelText(/^Präfix 2/), '2025/q1')
      await next(user)

      expect(
        screen.getByText(
          /Die Geltungsbereiche „dokumente\/2025\/“ und „dokumente\/2025\/q1\/“ überschneiden sich/,
        ),
      ).toBeInTheDocument()
      expect(mockCreateNewLibrary).not.toHaveBeenCalled()
    }, 25000)
  })

  describe('Confluence origin (#1135, ADR-0023)', () => {
    const spaces = [
      { key: 'BAU', name: 'Bauamt' },
      { key: 'HR', name: 'Personal' },
      { key: 'IT', name: 'IT-Betrieb' },
    ]

    beforeEach(() => {
      mockTestLibrarySource.mockReset()
      mockListConfluenceSpaces.mockReset()
      mockListConfluenceSpaces.mockResolvedValue({ spaces })
    })

    async function openConfluenceStep() {
      const user = userEvent.setup()
      renderPage()
      await chooseType(user, /Confluence/)
      return user
    }

    it('detects the edition before asking for credentials and shows Cloud fields for Cloud', async () => {
      mockTestLibrarySource.mockResolvedValueOnce({
        reachable: true,
        confluenceEdition: 'CLOUD',
        credentialsVerified: false,
        message:
          'Confluence Cloud erkannt. Geben Sie E-Mail-Adresse und API-Token des Dienstkontos ein.',
      })
      const user = await openConfluenceStep()

      // no credentials fields before the edition is known - the wizard cannot know which shape
      expect(screen.queryByLabelText(/API-Token/)).not.toBeInTheDocument()
      expect(screen.queryByLabelText(/Personal Access Token/)).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Edition erkennen' })).toBeDisabled()

      await user.type(
        screen.getByLabelText(/Adresse der Confluence-Instanz/),
        'https://site.atlassian.net/wiki',
      )
      await user.click(screen.getByRole('button', { name: 'Edition erkennen' }))

      expect(mockTestLibrarySource).toHaveBeenCalledWith({
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://site.atlassian.net/wiki',
        sourceProxy: undefined,
        sourceInsecureSsl: false,
      })
      expect(await screen.findByTestId('library-create-confluence-edition')).toHaveTextContent(
        'Confluence Cloud',
      )
      expect(screen.getByLabelText(/E-Mail-Adresse/)).toBeInTheDocument()
      expect(screen.getByLabelText(/^API-Token/)).toBeInTheDocument()
      expect(screen.queryByLabelText(/Personal Access Token/)).not.toBeInTheDocument()
      // the consequence stands before the selection, which is not yet offered
      expect(screen.getByTestId('library-create-confluence-sharing-consequence')).toHaveTextContent(
        /sieht alles aus allen ausgewählten Spaces/,
      )
      expect(screen.queryByLabelText(/Spaces suchen und auswählen/)).not.toBeInTheDocument()
    }, 15000)

    it('shows the PAT field for Data Center and refuses to continue without a tested selection', async () => {
      mockTestLibrarySource.mockResolvedValueOnce({
        reachable: true,
        confluenceEdition: 'DATA_CENTER',
        credentialsVerified: false,
        message: 'Confluence Data Center erkannt.',
      })
      const user = await openConfluenceStep()
      await user.type(
        screen.getByLabelText(/Adresse der Confluence-Instanz/),
        'https://wiki.behoerde.example/confluence',
      )
      await user.click(screen.getByRole('button', { name: 'Edition erkennen' }))

      expect(await screen.findByLabelText(/^Personal Access Token/)).toBeInTheDocument()
      expect(screen.queryByLabelText(/E-Mail-Adresse/)).not.toBeInTheDocument()

      await next(user)
      expect(
        screen.getByText(/Bitte die Zugangsdaten mit „Verbindung testen“ prüfen/),
      ).toBeInTheDocument()
      // der Schritt bleibt stehen, die Quellfelder sind weiter da
      expect(screen.getByLabelText(/Adresse der Confluence-Instanz/)).toBeInTheDocument()
    }, 15000)

    it('verifies credentials, loads the spaces, and sends edition, credentials and selection', async () => {
      mockTestLibrarySource
        .mockResolvedValueOnce({
          reachable: true,
          confluenceEdition: 'DATA_CENTER',
          credentialsVerified: false,
          message: 'Confluence Data Center erkannt.',
        })
        .mockResolvedValueOnce({
          reachable: true,
          confluenceEdition: 'DATA_CENTER',
          credentialsVerified: true,
          message: 'Confluence Data Center erreichbar, Zugangsdaten gültig.',
        })
      const user = await openConfluenceStep()
      await user.type(
        screen.getByLabelText(/Adresse der Confluence-Instanz/),
        'https://wiki.behoerde.example/confluence',
      )
      await user.click(screen.getByRole('button', { name: 'Edition erkennen' }))
      await user.type(await screen.findByLabelText(/^Personal Access Token/), 'pat-geheim')
      await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

      expect(mockTestLibrarySource).toHaveBeenLastCalledWith({
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        sourceProxy: undefined,
        sourceInsecureSsl: false,
        confluenceEdition: 'DATA_CENTER',
        sourceCredentials: 'pat-geheim',
        libraryId: undefined,
      })
      const picker = await screen.findByLabelText(/Spaces suchen und auswählen/)
      await waitFor(() => expect(mockListConfluenceSpaces).toHaveBeenCalled())
      await user.click(picker)
      await user.type(picker, 'Bau')
      await user.click(await screen.findByRole('option', { name: /Bauamt \(BAU\)/ }))
      await next(user)
      // #1942: Ein einzelner Space belegt den Namen vor.
      expect(screen.getByLabelText(/^Name/)).toHaveValue('Bauamt')
      await nameItAndContinue(user, 'Wiki Bauamt')
      await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

      await waitFor(() =>
        expect(mockCreateNewLibrary).toHaveBeenCalledWith(
          expect.objectContaining({
            name: 'Wiki Bauamt',
            sourceType: 'CONFLUENCE',
            sourceUrl: 'https://wiki.behoerde.example/confluence',
            sourceCredentials: 'pat-geheim',
            confluenceEdition: 'DATA_CENTER',
            confluenceSpaces: [{ key: 'BAU', name: 'Bauamt' }],
          }),
        ),
      )
      // The "Erste Indizierung sofort ..." switch defaults to on - the first run starts right
      // after creation, before the navigation to the detail page.
      expect(mockTriggerIndexing).toHaveBeenCalledWith('lib-neu', 'CONFLUENCE')
      expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu')
    }, 25000)

    it('skips the first run when the immediate-indexing switch is turned off', async () => {
      mockTestLibrarySource
        .mockResolvedValueOnce({
          reachable: true,
          confluenceEdition: 'DATA_CENTER',
          credentialsVerified: false,
          message: 'Confluence Data Center erkannt.',
        })
        .mockResolvedValueOnce({
          reachable: true,
          confluenceEdition: 'DATA_CENTER',
          credentialsVerified: true,
          message: 'Confluence Data Center erreichbar, Zugangsdaten gültig.',
        })
      const user = await openConfluenceStep()
      await user.type(
        screen.getByLabelText(/Adresse der Confluence-Instanz/),
        'https://wiki.behoerde.example/confluence',
      )
      await user.click(screen.getByRole('button', { name: 'Edition erkennen' }))
      await user.type(await screen.findByLabelText(/^Personal Access Token/), 'pat-geheim')
      await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))
      const picker = await screen.findByLabelText(/Spaces suchen und auswählen/)
      await user.click(picker)
      await user.type(picker, 'Bau')
      await user.click(await screen.findByRole('option', { name: /Bauamt \(BAU\)/ }))

      await user.click(
        screen.getByRole('switch', { name: 'Erste Indizierung sofort nach dem Anlegen starten' }),
      )
      await next(user)
      await nameItAndContinue(user, 'Wiki Bauamt')
      await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

      await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu'))
      expect(mockTriggerIndexing).not.toHaveBeenCalled()
    }, 25000)

    it('joins e-mail and token for Cloud and drops verification when the address changes', async () => {
      mockTestLibrarySource
        .mockResolvedValueOnce({
          reachable: true,
          confluenceEdition: 'CLOUD',
          credentialsVerified: false,
          message: 'Confluence Cloud erkannt.',
        })
        .mockResolvedValueOnce({
          reachable: true,
          confluenceEdition: 'CLOUD',
          credentialsVerified: true,
          message: 'Zugangsdaten gültig.',
        })
      const user = await openConfluenceStep()
      await user.type(
        screen.getByLabelText(/Adresse der Confluence-Instanz/),
        'https://site.atlassian.net',
      )
      await user.click(screen.getByRole('button', { name: 'Edition erkennen' }))
      await user.type(await screen.findByLabelText(/E-Mail-Adresse/), 'dienst@behoerde.example')
      await user.type(screen.getByLabelText(/^API-Token/), 'tok-123')
      await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

      expect(mockTestLibrarySource).toHaveBeenLastCalledWith(
        expect.objectContaining({ sourceCredentials: 'dienst@behoerde.example:tok-123' }),
      )
      await screen.findByLabelText(/Spaces suchen und auswählen/)

      // a changed address invalidates edition, credentials and selection alike
      await user.type(screen.getByLabelText(/Adresse der Confluence-Instanz/), '/wiki')
      expect(screen.queryByTestId('library-create-confluence-edition')).not.toBeInTheDocument()
      expect(screen.queryByLabelText(/Spaces suchen und auswählen/)).not.toBeInTheDocument()
    }, 25000)

    it('shows a blocked or unreachable address as an error with the backend wording, and never a credentials field', async () => {
      mockTestLibrarySource.mockResolvedValueOnce({
        reachable: false,
        confluenceEdition: null,
        credentialsVerified: false,
        message:
          'Die Adresse zeigt auf ein internes Ziel. Interne Ziele müssen in OPAA_INDEXING_TARGET_ALLOWLIST freigegeben sein.',
      })
      const user = await openConfluenceStep()
      await user.type(
        screen.getByLabelText(/Adresse der Confluence-Instanz/),
        'https://10.0.0.5/confluence',
      )
      await user.click(screen.getByRole('button', { name: 'Edition erkennen' }))

      const alert = await screen.findByText(/OPAA_INDEXING_TARGET_ALLOWLIST freigegeben/)
      expect(alert.closest('.MuiAlert-root')?.className).toMatch(/Error/)
      expect(screen.queryByLabelText(/Token/)).not.toBeInTheDocument()
      expect(screen.queryByTestId('library-create-confluence-edition')).not.toBeInTheDocument()
    }, 15000)

    it('keeps the space picker usable after "Zurück" re-enters the source step', async () => {
      mockTestLibrarySource.mockResolvedValue({
        reachable: true,
        confluenceEdition: 'DATA_CENTER',
        credentialsVerified: true,
        message: 'ok',
      })
      const user = await openConfluenceStep()
      await user.type(
        screen.getByLabelText(/Adresse der Confluence-Instanz/),
        'https://wiki.behoerde.example/confluence',
      )
      await user.click(screen.getByRole('button', { name: 'Edition erkennen' }))
      await user.type(await screen.findByLabelText(/^Personal Access Token/), 'pat-geheim')
      await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))
      const picker = await screen.findByLabelText(/Spaces suchen und auswählen/)
      await user.click(picker)
      await user.click(await screen.findByRole('option', { name: /Bauamt \(BAU\)/ }))
      await next(user)
      await user.click(screen.getByRole('button', { name: 'Zurück' }))

      // the remounted step reloads the listing for the still-verified credentials
      await waitFor(() => expect(mockListConfluenceSpaces).toHaveBeenCalledTimes(2))
      expect(await screen.findByRole('status')).toHaveTextContent(
        '1 von 3 lesbaren Spaces ausgewählt.',
      )
      await user.click(screen.getByLabelText(/Spaces suchen und auswählen/))
      expect(await screen.findByRole('option', { name: /Personal \(HR\)/ })).toBeInTheDocument()
    }, 25000)

    it('drops a late test answer once the address changed in the meantime', async () => {
      let answer: (value: unknown) => void = () => {}
      mockTestLibrarySource
        .mockResolvedValueOnce({
          reachable: true,
          confluenceEdition: 'DATA_CENTER',
          credentialsVerified: false,
          message: 'erkannt',
        })
        .mockImplementationOnce(() => new Promise((resolve) => (answer = resolve)))
      const user = await openConfluenceStep()
      await user.type(
        screen.getByLabelText(/Adresse der Confluence-Instanz/),
        'https://wiki.behoerde.example/confluence',
      )
      await user.click(screen.getByRole('button', { name: 'Edition erkennen' }))
      await user.type(await screen.findByLabelText(/^Personal Access Token/), 'pat-geheim')
      await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))
      expect(screen.getByRole('button', { name: 'Verbindung wird getestet …' })).toBeDisabled()

      // the address changes while the answer for the old one is still pending
      await user.type(screen.getByLabelText(/Adresse der Confluence-Instanz/), '/alt')
      await act(async () => {
        answer({
          reachable: true,
          confluenceEdition: 'DATA_CENTER',
          credentialsVerified: true,
          message: 'Zugangsdaten gültig.',
        })
      })

      expect(screen.queryByLabelText(/Spaces suchen und auswählen/)).not.toBeInTheDocument()
      expect(screen.queryByText('Zugangsdaten gültig.')).not.toBeInTheDocument()
      expect(mockListConfluenceSpaces).not.toHaveBeenCalled()
      await next(user)
      expect(
        screen.getByText('Bitte zuerst die Edition erkennen lassen („Edition erkennen“)'),
      ).toBeInTheDocument()
    }, 25000)

    it('offers a retry when the space listing fails, keeping the selection visible', async () => {
      mockTestLibrarySource.mockResolvedValue({
        reachable: true,
        confluenceEdition: 'DATA_CENTER',
        credentialsVerified: true,
        message: 'ok',
      })
      mockListConfluenceSpaces
        .mockRejectedValueOnce(new Error('Confluence antwortete mit HTTP 502'))
        .mockResolvedValueOnce({ spaces })
      const user = await openConfluenceStep()
      await user.type(
        screen.getByLabelText(/Adresse der Confluence-Instanz/),
        'https://wiki.behoerde.example/confluence',
      )
      await user.click(screen.getByRole('button', { name: 'Edition erkennen' }))
      await user.type(await screen.findByLabelText(/^Personal Access Token/), 'pat-geheim')
      await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

      expect(await screen.findByText('Confluence antwortete mit HTTP 502')).toBeInTheDocument()
      expect(screen.getByLabelText(/Spaces suchen und auswählen/)).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Erneut laden' }))
      expect(await screen.findByRole('status')).toHaveTextContent(
        '0 von 3 lesbaren Spaces ausgewählt.',
      )
      expect(screen.queryByText('Confluence antwortete mit HTTP 502')).not.toBeInTheDocument()
    }, 20000)
  })

  it('switches the connection form with the chosen kind and validates its fields', async () => {
    const user = userEvent.setup()
    renderPage()

    const radiogroup = screen.getByRole('radiogroup', { name: 'Art des Wissens wählen' })
    expect(radiogroup).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Upload/ })).toHaveAttribute('aria-checked', 'true')

    await chooseType(user, /Webverzeichnis/)
    expect(screen.getByText('Verbindung zum Webverzeichnis')).toBeInTheDocument()
    await next(user)
    expect(screen.getByText('Adresse (URL) ist erforderlich')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Zurück' }))
    await chooseType(user, /Dateisystem/)
    expect(screen.getByLabelText(/Verzeichnispfad/)).toBeInTheDocument()
    await user.type(screen.getByLabelText(/Verzeichnispfad/), 'relativ/pfad')
    await next(user)
    expect(
      screen.getByText('Verzeichnispfad muss ein absoluter Pfad sein, z. B. /data/dokumente'),
    ).toBeInTheDocument()
  }, 20000)

  // #514: ein Ergebnis gehört zu der Probe, mit der es gemessen wurde - auch zu ihrem Quellentyp.
  it('drops a connection test result when the source type changes (#514)', async () => {
    const user = userEvent.setup()
    mockTestLibrarySource.mockResolvedValueOnce({
      reachable: true,
      message: 'Feed erreichbar, 12 Einträge gefunden.',
    })
    renderPage()

    await chooseType(user, /RSS-Feed/)
    await user.type(screen.getByLabelText(/Adresse/), 'https://example.test/feed.xml')
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))
    expect(await screen.findByText('Feed erreichbar, 12 Einträge gefunden.')).toBeInTheDocument()

    // Derselbe Adresswert, anderer Quellentyp: die Probe lief nie gegen ein Webverzeichnis.
    await user.click(screen.getByRole('button', { name: 'Zurück' }))
    await chooseType(user, /Webverzeichnis/)

    expect(screen.getByLabelText(/Adresse/)).toHaveValue('https://example.test/feed.xml')
    expect(screen.queryByText('Feed erreichbar, 12 Einträge gefunden.')).not.toBeInTheDocument()
  }, 15000)

  it('keeps entered values when navigating back', async () => {
    const user = userEvent.setup()
    renderPage()

    await chooseType(user, /Webverzeichnis/)
    await user.type(
      screen.getByLabelText(/Adresse/),
      'https://intranet.behoerde.example/merkblaetter/',
    )
    await next(user)
    await user.type(screen.getByLabelText(/^Name/), ' Merkblätter')
    await user.click(screen.getByRole('button', { name: 'Zurück' }))
    expect(screen.getByLabelText(/Adresse/)).toHaveValue(
      'https://intranet.behoerde.example/merkblaetter/',
    )
    await next(user)
    expect(screen.getByLabelText(/^Name/)).toHaveValue('intranet.behoerde.example Merkblätter')
  }, 20000)

  // #1931: a library is created with no reach of its own - the wizard no longer picks a release
  // level, because there is none to pick.
  it('creates the library and navigates to its detail page', async () => {
    const user = userEvent.setup()
    renderPage()

    await next(user)
    await nameItAndContinue(user, 'Rechtsquellen Soziales')

    expect(screen.queryByRole('combobox', { name: /Verteilungsstufe/ })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

    await waitFor(() => {
      expect(mockCreateNewLibrary).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'Rechtsquellen Soziales', sourceType: 'UPLOAD' }),
      )
    })
    expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu')
  }, 15000)

  it('creates a group-owned library, offering only the groups returned for the user', async () => {
    mockGetMyGroups.mockResolvedValue([
      {
        id: 'group-referat-50',
        name: 'Referat 50',
        description: null,
        kind: 'ORG_UNIT' as const,
        externalId: 'directory-guid',
        parentGroupId: null,
        memberCount: 3,
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    const user = userEvent.setup()
    renderPage()

    await next(user)
    await nameItAndContinue(user, 'Team-Bibliothek')
    await user.click(screen.getByRole('radio', { name: /eine gruppe/i }))
    await user.click(await screen.findByRole('combobox', { name: 'Gruppe' }))
    await user.click(await screen.findByRole('option', { name: 'Referat 50' }))
    await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

    await waitFor(() => {
      expect(mockCreateNewLibrary).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Team-Bibliothek',
          ownerType: 'GROUP',
          ownerId: 'group-referat-50',
        }),
      )
    })
  }, 20000)

  it('shows a visible hint instead of a silent empty picker when the caller has no groups', async () => {
    const user = userEvent.setup()
    renderPage()

    await next(user)
    await nameItAndContinue(user, 'Rechtsquellen Soziales')
    await user.click(screen.getByRole('radio', { name: /eine gruppe/i }))

    expect(await screen.findByText(/keiner gruppe mitglied/i)).toBeInTheDocument()
  }, 15000)

  it('asks before discarding entered values on cancel', async () => {
    const user = userEvent.setup()
    renderPage()

    await next(user)
    await user.type(screen.getByLabelText(/^Name/), 'R')
    await user.click(screen.getByRole('button', { name: 'Abbrechen' }))
    await answerConfirm(user, 'Eingaben verwerfen und den Assistenten verlassen?', 'Abbrechen')

    expect(mockNavigate).not.toHaveBeenCalled()
  }, 15000)
})
