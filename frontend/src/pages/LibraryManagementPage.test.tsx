import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import LibraryManagementPage from './LibraryManagementPage'
import { useLibraryStore } from '../stores/libraryStore'
import { IDLE_RUN_STATE, useIndexingStore } from '../stores/indexingStore'
import type { LibraryListResponse } from '../types/api'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getLibraries: vi.fn(async () => useLibraryStore.getState().libraries),
  }
})

const ownLibrary: LibraryListResponse = {
  id: 'library-mine',
  name: 'Meine Dokumente',
  description: 'Private Dokumente',
  ownerType: 'USER',
  visibility: 'PRIVATE',
  listed: false,
  myRole: 'OWNER',
  sourceType: 'UPLOAD',
  documentCount: 12,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const managerLibrary: LibraryListResponse = {
  id: 'library-team',
  name: 'Rechtsquellen Soziales',
  description: 'SGB II, SGB XII',
  ownerType: 'GROUP',
  ownerName: 'Referat 50',
  visibility: 'SHARED',
  listed: true,
  myRole: 'MANAGER',
  sourceType: 'FILESYSTEM',
  lastIndexedAt: '2026-08-18T06:00:00Z',
  documentCount: 431,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const viewerLibrary: LibraryListResponse = {
  id: 'library-readonly',
  name: 'Dienstanweisungen',
  description: 'Organisationsweit',
  ownerType: 'GROUP',
  visibility: 'ORGANIZATION',
  listed: true,
  myRole: 'VIEWER',
  sourceType: 'UPLOAD',
  documentCount: 87,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

function setLibraryState(libraries: LibraryListResponse[]) {
  useLibraryStore.setState({
    libraries,
    libraryDetails: {},
    isLoading: false,
    error: null,
  })
}

describe('LibraryManagementPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useIndexingStore.setState({ runsByLibrary: {} })
    // Die Übersicht merkt sich die Ansicht je Bestand; ohne Reset trüge ein vorheriger Test
    // seine Wahl in den nächsten.
    window.localStorage.clear()
  })

  it('heads the page like its menu entry and names the figure beside it (#1915, #1913)', async () => {
    setLibraryState([managerLibrary, viewerLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByRole('heading', { level: 1, name: 'Wissen' })).toBeInTheDocument()
    expect(screen.getByText('2 Bibliotheken')).toBeInTheDocument()
  })

  it('renders the table with its six column heads and without the "Global" badge (#1916)', async () => {
    setLibraryState([managerLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    await screen.findByRole('table')
    for (const head of [
      'Name',
      'Herkunft',
      'Dokumente',
      'In der Organisation geteilt',
      'Ihre Rolle',
      'Letzte Aktualisierung',
    ]) {
      expect(screen.getByRole('columnheader', { name: head })).toBeInTheDocument()
    }
    expect(screen.getByText(/Bestände ohne Leserecht erscheinen hier nicht/)).toBeInTheDocument()
    expect(screen.queryByText('Global')).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Umfang' })).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Verteilungsstufe' })).not.toBeInTheDocument()
  })

  it('shows the document count as a plain number without a repeated unit (#1916)', async () => {
    setLibraryState([managerLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText('431')).toBeInTheDocument()
    expect(screen.queryByText('431 Dok.')).not.toBeInTheDocument()
  })

  it('marks a library shared with the whole organization with a tick (#1916)', async () => {
    setLibraryState([managerLibrary, viewerLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    // Nur die ORGANIZATION-Bibliothek trägt den Haken; die Stufe selbst steht nicht mehr da.
    const ticks = await screen.findAllByTitle('ja')
    expect(ticks).toHaveLength(1)
    expect(screen.queryByText('organisationsweit')).not.toBeInTheDocument()
    expect(screen.queryByText('gelistet')).not.toBeInTheDocument()
  })

  it('shows the last update as a bare date, empty for an upload library (#1916)', async () => {
    setLibraryState([managerLibrary, viewerLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText('18.08.2026')).toBeInTheDocument()
    expect(screen.queryByText(/indiziert|abgerufen/)).not.toBeInTheDocument()
    // viewerLibrary is an UPLOAD library - it never runs, so its cell stays empty rather than
    // claiming a missing update.
    expect(screen.queryByText('–')).not.toBeInTheDocument()
  })

  it('shows a dash for a run-based library that never completed a run (#1916)', async () => {
    setLibraryState([{ ...managerLibrary, lastIndexedAt: undefined }])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText('–')).toBeInTheDocument()
  })

  it('shows a running indexing state with progress in the update column', async () => {
    setLibraryState([managerLibrary])
    useIndexingStore.setState({
      runsByLibrary: {
        [managerLibrary.id]: {
          ...IDLE_RUN_STATE,
          status: 'RUNNING',
          documentCount: 62,
          totalDocuments: 100,
        },
      },
    })
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText(/Lauf läuft · 62 %/)).toBeInTheDocument()
  })

  it('names a failed last run instead of the older success date (#1916)', async () => {
    setLibraryState([managerLibrary])
    useIndexingStore.setState({
      runsByLibrary: { [managerLibrary.id]: { ...IDLE_RUN_STATE, status: 'FAILED' } },
    })
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText('Lauf fehlgeschlagen')).toBeInTheDocument()
    expect(screen.queryByText('18.08.2026')).not.toBeInTheDocument()
  })

  it('lists libraries sorted alphabetically by name', async () => {
    setLibraryState([managerLibrary, ownLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    // Der Laden sortiert erst, wenn die Antwort da ist - ohne waitFor prüfte die Zusicherung
    // die ungeordnete Erstausgabe.
    await waitFor(() => {
      const items = screen.getAllByText(/Meine Dokumente|Rechtsquellen Soziales/)
      expect(items[0]).toHaveTextContent('Meine Dokumente')
      expect(items[1]).toHaveTextContent('Rechtsquellen Soziales')
    })
  })

  it('shows the source type per library without a detail round trip', async () => {
    setLibraryState([managerLibrary, viewerLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText('Dateisystem')).toBeInTheDocument()
    expect(screen.getByText('Upload')).toBeInTheDocument()
  })

  it('shows the resolved owner name instead of a generic group label', async () => {
    // #438: the overview previously showed a generic "Gruppen-Bibliothek" label for every
    // group-owned library instead of e.g. "Referat 50" - ownerName lets it show the actual name.
    setLibraryState([managerLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText(/Referat 50/)).toBeInTheDocument()
    expect(screen.queryByText(/Gruppen-Bibliothek/)).not.toBeInTheDocument()
  })

  it('falls back to the generic group label when ownerName is missing', async () => {
    // ownerName is optional (owner deleted, or an org the backend could not resolve a name for) -
    // the row must still render something sensible instead of a blank owner summary.
    setLibraryState([{ ...viewerLibrary, ownerName: undefined }])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText(/Gruppen-Bibliothek/)).toBeInTheDocument()
  })

  it('shows the resolved owner name for a user-owned library too', async () => {
    // PR #601 review, finding 3: both prior ownerName tests only used GROUP fixtures - USER
    // owners take the same ownerName field (the backend deliberately never falls back to the
    // owner's email address there, see KnowledgeLibraryService#resolveOwnerNames).
    setLibraryState([{ ...ownLibrary, ownerName: 'Erika Musterfrau' }])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText(/Erika Musterfrau/)).toBeInTheDocument()
    expect(screen.queryByText(/^eigene/)).not.toBeInTheDocument()
  })

  it('falls back to the generic "eigene" label for a user-owned library without ownerName', async () => {
    setLibraryState([{ ...ownLibrary, ownerName: undefined }])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText(/eigene/)).toBeInTheDocument()
  })

  it('shows an empty state when there are no libraries', async () => {
    setLibraryState([])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText(/noch keine bibliotheken/i)).toBeInTheDocument()
  })

  it('renders each library row as a real link to its detail page', async () => {
    // #506 review, finding 6: a navigate()-triggering button offers none of a real link's
    // affordances (open in new tab, middle-click, hover preview) - the row must carry a genuine
    // href instead.
    setLibraryState([managerLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    const link = await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })
    expect(link).toHaveAttribute('href', '/libraries/library-team')
  })

  it('offers a card view and filters it by name and description (#1913)', async () => {
    const user = userEvent.setup()
    setLibraryState([managerLibrary, viewerLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: 'Kacheln' }))
    expect(screen.queryByRole('table')).not.toBeInTheDocument()

    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'SGB')
    expect(screen.getByRole('link', { name: /Rechtsquellen Soziales/ })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Dienstanweisungen/ })).not.toBeInTheDocument()
  })

  it('navigates to the create wizard from the header button', async () => {
    setLibraryState([])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /neue bibliothek/i }))

    expect(mockNavigate).toHaveBeenCalledWith('/libraries/new')
  })
})
