import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { mockSearchDiagnosis, mockSearchStatus } from '../mocks/fixtures'
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
})
