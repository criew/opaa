import { describe, expect, it, beforeEach, vi } from 'vitest'
import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../test/test-utils'
import LibraryCreatePage from './LibraryCreatePage'
import { useLibraryStore } from '../stores/libraryStore'
import { useIndexingStore } from '../stores/indexingStore'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return { ...actual, useNavigate: () => mockNavigate }
})

const { mockGetMyGroups, mockTestLibrarySource, mockListConfluenceSpaces, mockListS3Buckets } =
  vi.hoisted(() => ({
    mockGetMyGroups: vi.fn().mockResolvedValue([]),
    mockTestLibrarySource: vi.fn(),
    mockListConfluenceSpaces: vi.fn(),
    mockListS3Buckets: vi.fn(),
  }))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getMyGroups: mockGetMyGroups,
    getUserSummaries: vi.fn().mockResolvedValue([]),
    testLibrarySource: mockTestLibrarySource,
    listConfluenceSpaces: mockListConfluenceSpaces,
    listS3Buckets: mockListS3Buckets,
  }
})

const mockCreateNewLibrary = vi.fn().mockResolvedValue('lib-neu')
const mockTriggerIndexing = vi.fn().mockResolvedValue(undefined)

function renderPage() {
  return renderWithProviders(<LibraryCreatePage />, { withRouter: true })
}

describe('LibraryCreatePage (#596, Mockup 1e)', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    mockCreateNewLibrary.mockClear()
    mockTriggerIndexing.mockClear()
    mockGetMyGroups.mockResolvedValue([])
    useLibraryStore.setState({ createNewLibrary: mockCreateNewLibrary })
    useIndexingStore.setState({ triggerIndexing: mockTriggerIndexing })
  })

  it('shows the three steps and blocks Weiter without a name', async () => {
    const user = userEvent.setup()
    renderPage()

    expect(screen.getByText('1 · Stammdaten')).toBeInTheDocument()
    expect(screen.getByText('2 · Herkunft')).toBeInTheDocument()
    expect(screen.getByText('3 · Rechte')).toBeInTheDocument()

    expect(screen.getByRole('button', { name: 'Weiter' })).toBeDisabled()
    await user.type(screen.getByLabelText(/Name/), 'Rechtsquellen Soziales')
    expect(screen.getByRole('button', { name: 'Weiter' })).toBeEnabled()
  })

  describe('S3 origin (#1377, ADR-0027)', () => {
    beforeEach(() => {
      mockTestLibrarySource.mockReset()
      mockListS3Buckets.mockReset()
    })

    async function openS3Step() {
      const user = userEvent.setup()
      renderPage()
      await user.type(screen.getByLabelText(/^Name/), 'Protokolle')
      await user.click(screen.getByRole('button', { name: 'Weiter' }))
      await user.click(screen.getByRole('radio', { name: /S3-Objektspeicher/ }))
      return user
    }

    it('offers the S3 card, states the sharing consequence before the scopes, and blocks Weiter until the stages are complete', async () => {
      const user = await openS3Step()

      const consequence = screen.getByTestId('library-create-s3-sharing-consequence')
      const scopes = screen.getByRole('list', { name: 'Geltungsbereiche' })
      expect(consequence).toHaveTextContent(
        'Wer diese Bibliothek lesen darf, sieht alles aus allen Geltungsbereichen.',
      )
      expect(
        consequence.compareDocumentPosition(scopes) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy()

      await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))
      expect(screen.getByText('Endpoint des Objektspeichers ist erforderlich')).toBeInTheDocument()
      await user.type(screen.getByLabelText('Endpoint'), 'https://minio.intern.example:9000')
      await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))
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
      await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))
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
      await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))
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
    }, 20000)

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
      await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))

      expect(
        screen.getByText(
          /Die Geltungsbereiche „dokumente\/2025\/“ und „dokumente\/2025\/q1\/“ überschneiden sich/,
        ),
      ).toBeInTheDocument()
      expect(mockCreateNewLibrary).not.toHaveBeenCalled()
    }, 20000)
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
      await user.type(screen.getByLabelText(/^Name/), 'Wiki Bauamt')
      await user.click(screen.getByRole('button', { name: 'Weiter' }))
      await user.click(screen.getByRole('radio', { name: /Confluence/ }))
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
    })

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

      await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))
      expect(
        screen.getByText(/Bitte die Zugangsdaten mit „Verbindung testen“ prüfen/),
      ).toBeInTheDocument()
      expect(screen.getByRole('radiogroup', { name: 'Herkunft wählen' })).toBeInTheDocument()
    }, 10000)

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
      await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))
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
    }, 15000)

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
      await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))
      await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

      await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu'))
      expect(mockTriggerIndexing).not.toHaveBeenCalled()
    }, 15000)

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
    }, 15000)

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

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent(/OPAA_INDEXING_TARGET_ALLOWLIST freigegeben/)
      expect(alert.className).toMatch(/Error/)
      expect(screen.queryByLabelText(/Token/)).not.toBeInTheDocument()
      expect(screen.queryByTestId('library-create-confluence-edition')).not.toBeInTheDocument()
    }, 15000)

    it('keeps the space picker usable after "Zurück" re-enters the origin step', async () => {
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
      await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))
      await user.click(screen.getByRole('button', { name: 'Zurück' }))

      // the remounted step reloads the listing for the still-verified credentials
      await waitFor(() => expect(mockListConfluenceSpaces).toHaveBeenCalledTimes(2))
      expect(await screen.findByRole('status')).toHaveTextContent(
        '1 von 3 lesbaren Spaces ausgewählt.',
      )
      await user.click(screen.getByLabelText(/Spaces suchen und auswählen/))
      expect(await screen.findByRole('option', { name: /Personal \(HR\)/ })).toBeInTheDocument()
    }, 20000)

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
      await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))
      expect(
        screen.getByText('Bitte zuerst die Edition erkennen lassen („Edition erkennen“)'),
      ).toBeInTheDocument()
    }, 15000)

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
    }, 15000)
  })

  it('switches the connection form with the origin card and validates its fields', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText(/Name/), 'Rechtsquellen Soziales')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    const radiogroup = screen.getByRole('radiogroup', { name: 'Herkunft wählen' })
    expect(radiogroup).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Upload/ })).toBeChecked()

    await user.click(screen.getByRole('radio', { name: /Webverzeichnis/ }))
    expect(screen.getByText('Verbindung zum Webverzeichnis')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))
    expect(screen.getByText('Adresse (URL) ist erforderlich')).toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: /Dateisystem/ }))
    expect(screen.getByLabelText(/Verzeichnispfad/)).toBeInTheDocument()
    await user.type(screen.getByLabelText(/Verzeichnispfad/), 'relativ/pfad')
    await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))
    expect(
      screen.getByText('Verzeichnispfad muss ein absoluter Pfad sein, z. B. /data/dokumente'),
    ).toBeInTheDocument()
  })

  it('keeps entered values when navigating back', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText(/Name/), 'Rechtsquellen Soziales')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('radio', { name: /Webverzeichnis/ }))
    await user.type(
      screen.getByLabelText(/Adresse/),
      'https://intranet.behoerde.example/merkblaetter/',
    )
    await user.click(screen.getByRole('button', { name: 'Zurück' }))
    expect(screen.getByLabelText(/Name/)).toHaveValue('Rechtsquellen Soziales')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    expect(screen.getByLabelText(/Adresse/)).toHaveValue(
      'https://intranet.behoerde.example/merkblaetter/',
    )
  })

  it('creates the library with the chosen visibility and navigates to its detail page', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText(/Name/), 'Rechtsquellen Soziales')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))

    await user.click(screen.getByRole('combobox', { name: /Verteilungsstufe/ }))
    await user.click(screen.getByRole('option', { name: /organisationsweit/ }))
    await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))

    await waitFor(() => {
      expect(mockCreateNewLibrary).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Rechtsquellen Soziales',
          sourceType: 'UPLOAD',
          visibility: 'ORGANIZATION',
        }),
      )
    })
    expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu')
  })

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

    await user.type(screen.getByLabelText(/Name/), 'Team-Bibliothek')
    await user.click(screen.getByRole('radio', { name: /eine gruppe/i }))
    await user.click(await screen.findByLabelText(/^gruppe$/i))
    await user.click(await screen.findByRole('option', { name: 'Referat 50' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))
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
  })

  it('shows a visible hint instead of a silent empty picker when the caller has no groups', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('radio', { name: /eine gruppe/i }))

    expect(await screen.findByText(/keiner gruppe mitglied/i)).toBeInTheDocument()
  })

  it('asks before discarding entered values on cancel', async () => {
    const user = userEvent.setup()
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderPage()

    await user.type(screen.getByLabelText(/Name/), 'R')
    await user.click(screen.getByRole('button', { name: 'Abbrechen' }))

    expect(confirmSpy).toHaveBeenCalled()
    expect(mockNavigate).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })
})
