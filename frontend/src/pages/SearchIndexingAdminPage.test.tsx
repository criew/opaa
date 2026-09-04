import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { delay, http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { useSearchAdminStore } from '../stores/searchAdminStore'
import {
  MOCK_SATZUNG_DOCUMENT_ID,
  mockDocumentChunks,
  mockSearchDiagnosis,
  mockSearchStatus,
} from '../mocks/fixtures'
import SearchIndexingAdminPage from './SearchIndexingAdminPage'

function signInAs(systemRole: 'SYSTEM_ADMIN' | 'USER') {
  useAuthStore.setState({
    mode: 'dev',
    isAuthenticated: true,
    isLoading: false,
    user: {
      id: 'user-1',
      email: 'admin@opaa.local',
      displayName: 'Admin',
      systemRole,
    },
    token: null,
    error: null,
    userManager: null,
  })
}

async function runDiagnosis(user: ReturnType<typeof userEvent.setup>) {
  await user.type(
    screen.getByRole('textbox', { name: /Testfrage/ }),
    'Was gilt bei Gebührenbefreiung?',
  )
  await user.click(screen.getByRole('button', { name: 'Diagnose ausführen' }))
}

describe('SearchIndexingAdminPage', () => {
  beforeEach(() => {
    useSearchAdminStore.getState().reset()
  })

  it('shows nothing but a note to a user who is not a system administrator', () => {
    signInAs('USER')

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })

    expect(screen.getByText(/nicht freigegeben/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Diagnose ausführen' })).not.toBeInTheDocument()
  })

  it('shows the three model roles and never an access key', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })

    await waitFor(() => {
      expect(screen.getByLabelText('Chat: Aktiv und erreichbar')).toBeInTheDocument()
    })
    expect(screen.getByLabelText('Einbettung: Aktiv und erreichbar')).toBeInTheDocument()
    expect(screen.getByLabelText('Reranking: Ausdrücklich abgeschaltet')).toBeInTheDocument()
    expect(screen.getByText('Endpunkt: http://localhost:11434/v1')).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/Schlüssel|apiKey|Bearer/)
  })

  it('reports a rerank role that is switched on but unbelegt as a fault, not a footnote', async () => {
    signInAs('SYSTEM_ADMIN')
    server.use(
      http.get('/api/v1/admin/search/status', () =>
        HttpResponse.json({
          ...mockSearchStatus,
          modelRoles: [
            {
              role: 'RERANK',
              state: 'UNCONFIGURED',
              endpoint: null,
              modelIdentifier: null,
              faulted: true,
              detail:
                'Reranking ist eingeschaltet, aber es ist keine Rerank-Modellrolle hinterlegt.',
            },
          ],
        }),
      ),
    )

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/keine Rerank-Modellrolle hinterlegt/)
    })
  })

  it('shows the per-library index status including the low-chunk metric', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })

    const table = await screen.findByRole('table', { name: 'Indexstatus je Bibliothek' })
    const row = within(table).getByText('Satzungen & Gebuehrenordnungen').closest('tr')
    expect(row).not.toBeNull()
    // 2 documents geführt as indexed with (almost) no chunks, and 56 chunks the lexical path is
    // still missing - the two numbers the page exists to keep permanently visible.
    expect(within(row as HTMLElement).getByText('2')).toBeInTheDocument()
    expect(within(row as HTMLElement).getByText('56 Abschnitte fehlen')).toBeInTheDocument()
    // Both chunk counts, because a gap between them is itself the finding.
    expect(within(row as HTMLElement).getByText(/236 \/ 240/)).toBeInTheDocument()
    expect(
      within(row as HTMLElement).getByText('Vektorindex und Dokumentzählung weichen ab'),
    ).toBeInTheDocument()

    // A single missing chunk reads as a sentence, not as "1 Abschnitte fehlen".
    const singularRow = within(table).getByText('Protokolle').closest('tr')
    expect(within(singularRow as HTMLElement).getByText('1 Abschnitt fehlt')).toBeInTheDocument()

    // A library with only a permanently skipped chunk (#1093 review, Blocker 2) - nothing
    // missing/pending - must not look flawlessly ready either: its own hint stays visible.
    const skippedRow = within(table).getByText('Formulare').closest('tr')
    expect(
      within(skippedRow as HTMLElement).getByText('1 Abschnitt dauerhaft übersprungen'),
    ).toBeInTheDocument()
  })

  it('shows the core-field extraction state and the fill per field in the library row', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })

    const table = await screen.findByRole('table', { name: 'Indexstatus je Bibliothek' })
    const row = within(table).getByText('Satzungen & Gebuehrenordnungen').closest('tr')
    expect(within(row as HTMLElement).getByText('9 / 11 aktuell')).toBeInTheDocument()
    expect(within(row as HTMLElement).getByText('2 Dokumente ausstehend')).toBeInTheDocument()
    expect(
      within(row as HTMLElement).getByText('1 Dokument zuletzt übersprungen'),
    ).toBeInTheDocument()
    // Absolute and relative per field, so a filter on a 36 % field reads as a different act than
    // one on a 100 % field.
    expect(
      within(row as HTMLElement).getByLabelText(
        'Füllgrad je Kernfeld: Satzungen & Gebuehrenordnungen',
      ),
    ).toHaveTextContent('Titel 11 (100 %) · Dokumentart 4 (36 %) · Datum/Stand 7 (64 %)')
    expect(
      within(row as HTMLElement).getByRole('button', {
        name: 'Kernfelder nachrüsten: Satzungen & Gebuehrenordnungen',
      }),
    ).toBeInTheDocument()

    // A library without pending documents offers nothing to start - a second run would change
    // nothing.
    const completeRow = within(table).getByText('Protokolle').closest('tr')
    expect(within(completeRow as HTMLElement).getByText('3 / 3 aktuell')).toBeInTheDocument()
    expect(within(completeRow as HTMLElement).queryByRole('button')).not.toBeInTheDocument()
  })

  it('drives the backfill in batches until done and refreshes the status after each one', async () => {
    signInAs('SYSTEM_ADMIN')
    const batchCalls: number[] = []
    let statusLoads = 0
    server.use(
      http.post('/api/v1/admin/indexing/metadata-backfill', async ({ request }) => {
        const body = (await request.json()) as { libraryId: string; batchSize: number }
        expect(body.libraryId).toBe('lib-satzungen')
        batchCalls.push(body.batchSize)
        const done = batchCalls.length >= 2
        return HttpResponse.json({
          processedDocuments: done ? 0 : 2,
          markedForNextRun: 0,
          skippedDocuments: 0,
          done,
        })
      }),
      http.get('/api/v1/admin/search/status', () => {
        statusLoads += 1
        const [satzungen, ...rest] = mockSearchStatus.libraries
        const caughtUp = batchCalls.length > 0
        return HttpResponse.json({
          ...mockSearchStatus,
          libraries: [
            {
              ...satzungen,
              metadataBackfill: {
                ...satzungen.metadataBackfill,
                currentDocuments: caughtUp ? 11 : 9,
                pendingDocuments: caughtUp ? 0 : 2,
                lastSkippedDocuments: 0,
                complete: caughtUp,
              },
            },
            ...rest,
          ],
        })
      }),
    )

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(
      await screen.findByRole('button', {
        name: 'Kernfelder nachrüsten: Satzungen & Gebuehrenordnungen',
      }),
    )

    await waitFor(() => {
      expect(screen.getByText('11 / 11 aktuell')).toBeInTheDocument()
    })
    // Two batch calls (the second reported done), and the status was re-read after each of them.
    expect(batchCalls).toEqual([10, 10])
    expect(statusLoads).toBeGreaterThanOrEqual(3)
    expect(
      screen.queryByRole('button', { name: /Satzungen & Gebuehrenordnungen$/ }),
    ).not.toBeInTheDocument()
  })

  it('pausing a run stops after the batch in flight and offers to continue', async () => {
    signInAs('SYSTEM_ADMIN')
    let batchCalls = 0
    server.use(
      http.post('/api/v1/admin/indexing/metadata-backfill', async () => {
        batchCalls += 1
        await delay(150)
        return HttpResponse.json({
          processedDocuments: 1,
          markedForNextRun: 0,
          skippedDocuments: 0,
          done: false,
        })
      }),
    )

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(
      await screen.findByRole('button', {
        name: 'Kernfelder nachrüsten: Satzungen & Gebuehrenordnungen',
      }),
    )
    await user.click(
      await screen.findByRole('button', { name: 'Anhalten: Satzungen & Gebuehrenordnungen' }),
    )

    // Pausing is not calling again: the batch in flight finishes, no further one is started, and
    // the button turns into the resumption of the same run.
    expect(
      await screen.findByRole('button', { name: 'Weiter: Satzungen & Gebuehrenordnungen' }),
    ).toBeInTheDocument()
    await delay(400)
    expect(batchCalls).toBe(1)
  })

  it('offers permission profiles and the own context, never a person', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })
    const user = userEvent.setup()

    const contextSelect = await screen.findByRole('combobox', { name: /Sicht als/ })
    await user.click(contextSelect)

    const options = within(screen.getByRole('listbox')).getAllByRole('option')
    expect(options.map((option) => option.textContent)).toEqual([
      'Eigener Rechtekontext',
      'Rechteprofil „Sachbearbeitung Buergerbuero“ (2 Bibliotheken)',
      'Rechteprofil „Projektbeteiligte Phoenix“ (1 Bibliothek)',
    ])
    expect(screen.getByText(/nicht zur Wahl/)).toBeInTheDocument()
  })

  it('shows every pipeline stage of the run with its own candidate verdicts', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })
    const user = userEvent.setup()
    await screen.findByRole('table', { name: 'Indexstatus je Bibliothek' })
    await runDiagnosis(user)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /Suchbereich/ })).toBeInTheDocument()
    })
    expect(screen.getByRole('heading', { name: /Vektorsuche/ })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /Fusion \(RRF\)/ })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Fusion \(RRF\)/ }))
    const verdicts = await screen.findByRole('table', {
      name: 'Kandidaten der Stufe Fusion (RRF)',
    })
    expect(within(verdicts).getByText('Verdrängt')).toBeInTheDocument()
    expect(
      within(verdicts).getByText('Nach der Fusion unterhalb der Auswahlgrenze'),
    ).toBeInTheDocument()
  })

  it('says plainly whether a tracked document was never found or displaced at a stage', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })
    const user = userEvent.setup()
    await screen.findByRole('table', { name: 'Indexstatus je Bibliothek' })
    await user.type(screen.getByRole('textbox', { name: /Dokument verfolgen/ }), 'doc-formular')
    await runDiagnosis(user)

    await waitFor(() => {
      expect(screen.getByText(/in der Stufe „Fusion \(RRF\)“ verdrängt/)).toBeInTheDocument()
    })
    expect(screen.getByText(/Problem der Rangfolge, nicht der Indexierung/)).toBeInTheDocument()
  })

  it('distinguishes a document no search stage found from a displaced one', async () => {
    signInAs('SYSTEM_ADMIN')
    server.use(
      http.post('/api/v1/admin/search/diagnosis', () =>
        HttpResponse.json({
          ...mockSearchDiagnosis,
          trackedDocument: {
            documentId: 'doc-scan',
            fileName: 'scan-ohne-textebene.pdf',
            libraryId: 'lib-satzungen',
            libraryName: 'Satzungen & Gebuehrenordnungen',
            outcome: 'NOT_RETRIEVED',
            displacedAtStage: null,
            displacedReason: null,
            retrievedChunkCount: 0,
            selectedChunkCount: 0,
          },
        }),
      ),
    )

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })
    const user = userEvent.setup()
    await screen.findByRole('table', { name: 'Indexstatus je Bibliothek' })
    await runDiagnosis(user)

    await waitFor(() => {
      expect(screen.getByText(/von keiner Suchstufe gefunden/)).toBeInTheDocument()
    })
    expect(screen.getByText(/Indexierungs- oder Zuschnittsproblem/)).toBeInTheDocument()
  })

  it('states that the diagnosis reads no conversations and answers only the current state', () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })

    expect(screen.getByText(/liest keine bestehenden Gespräche/)).toBeInTheDocument()
    expect(screen.getByText(/kein Nachweis über zurückliegende Zugriffe/)).toBeInTheDocument()
  })

  it('opens a chunk preview from a stage table with content, metadata and a copyable id', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })
    const user = userEvent.setup()
    await screen.findByRole('table', { name: 'Indexstatus je Bibliothek' })
    await runDiagnosis(user)

    await user.click(await screen.findByRole('button', { name: /^Vektorsuche/ }))
    const verdicts = await screen.findByRole('table', { name: 'Kandidaten der Stufe Vektorsuche' })
    await user.click(within(verdicts).getAllByRole('button', { name: 'Chunk anzeigen' })[0])

    const dialog = await screen.findByRole('dialog', { name: 'Chunk-Vorschau' })
    expect(
      await within(dialog).findByText(
        (_, element) =>
          element?.tagName === 'P' &&
          element.textContent === 'Dokument: verwaltungsgebuehrensatzung.pdf',
      ),
    ).toBeInTheDocument()
    expect(within(dialog).getByText('Chunk-Index: 3')).toBeInTheDocument()
    expect(within(dialog).getByText('chunk-1')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Chunk-ID kopieren' })).toBeInTheDocument()
    // The stored text keeps its line breaks and the context prefix the pipeline prepended.
    expect(
      within(dialog).getByText(/Verwaltungsgebuehrensatzung > § 4 Befreiung/),
    ).toHaveTextContent(/\(1\) Von der Gebuehr wird auf Antrag befreit/)
    const metadata = within(dialog).getByRole('table', { name: 'Chunk-Metadaten' })
    expect(within(metadata).getByText('location')).toBeInTheDocument()
    expect(within(metadata).getByText('Seite 2')).toBeInTheDocument()
    expect(within(dialog).queryByText(/embedding/i)).not.toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Schließen' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it('opens a chunk preview from the final selection and shows the error for an unknown chunk', async () => {
    signInAs('SYSTEM_ADMIN')
    server.use(
      http.get('/api/v1/admin/search/chunks/:chunkId', () =>
        HttpResponse.json({ error: 'Der Chunk wurde nicht gefunden.' }, { status: 404 }),
      ),
    )

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })
    const user = userEvent.setup()
    await screen.findByRole('table', { name: 'Indexstatus je Bibliothek' })
    await runDiagnosis(user)

    const selection = await screen.findByRole('table', { name: 'Endauswahl' })
    await user.click(within(selection).getAllByRole('button', { name: 'Chunk anzeigen' })[0])

    const dialog = await screen.findByRole('dialog', { name: 'Chunk-Vorschau' })
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Der Chunk wurde nicht gefunden.',
    )
  })

  it('lists every stored chunk of a document in order and flags a count mismatch', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })
    const user = userEvent.setup()
    await screen.findByRole('table', { name: 'Indexstatus je Bibliothek' })

    await user.click(screen.getByRole('textbox', { name: 'Dokument-ID' }))
    await user.paste(MOCK_SATZUNG_DOCUMENT_ID)
    await user.click(screen.getByRole('button', { name: 'Chunks laden' }))

    expect(await screen.findByText(/2 gespeicherte Chunks, laut Dokument 3/)).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(
      /weicht von der im Dokument vermerkten Anzahl/,
    )
    const headings = screen.getAllByRole('heading', { level: 4 })
    expect(headings.map((h) => h.textContent)).toEqual([
      expect.stringMatching(/^Chunk 0/),
      expect.stringMatching(/^Chunk 3/),
    ])
    expect(headings[0]).toHaveTextContent('Fundort: Seite 1')
    expect(headings[0]).toHaveTextContent(`${mockDocumentChunks.chunks[0].content.length} Zeichen`)

    await user.click(screen.getByRole('button', { name: /^Chunk 0/ }))
    expect(await screen.findByText(/§ 1 Geltungsbereich/)).toBeInTheDocument()
  })

  it('reports an unknown document id instead of an empty list', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })
    const user = userEvent.setup()
    await screen.findByRole('table', { name: 'Indexstatus je Bibliothek' })

    await user.click(screen.getByRole('textbox', { name: 'Dokument-ID' }))
    await user.paste('99999999-9999-4999-8999-999999999999')
    await user.click(screen.getByRole('button', { name: 'Chunks laden' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Das Dokument wurde nicht gefunden.')
  })

  it('jumps from a document title in the diagnosis to the chunks of that document', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<SearchIndexingAdminPage />, { withRouter: true })
    const user = userEvent.setup()
    await screen.findByRole('table', { name: 'Indexstatus je Bibliothek' })
    await runDiagnosis(user)

    const selection = await screen.findByRole('table', { name: 'Endauswahl' })
    await user.click(
      within(selection).getByRole('button', { name: 'verwaltungsgebuehrensatzung.pdf' }),
    )

    expect(screen.getByRole('textbox', { name: 'Dokument-ID' })).toHaveValue(
      MOCK_SATZUNG_DOCUMENT_ID,
    )
    expect(await screen.findByText(/2 gespeicherte Chunks, laut Dokument 3/)).toBeInTheDocument()
  })
})
