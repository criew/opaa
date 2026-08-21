import { screen } from '@testing-library/react'
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
    // jsdom hat kein matchMedia - ohne Desktop-Stub rendert die Seite die mobile Kartenliste
    // statt der Zielbild-Tabelle (Mockup 1d).
    window.matchMedia = (query: string) =>
      ({
        matches: query.includes('min-width'),
        media: query,
        onchange: null,
        addListener: () => {},
        removeListener: () => {},
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
      }) as MediaQueryList
  })

  it('renders the mockup table with its six column heads (#595)', async () => {
    setLibraryState([managerLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    await screen.findByRole('table')
    for (const head of ['Name', 'Herkunft', 'Umfang', 'Verteilungsstufe', 'Ihre Rolle', 'Stand']) {
      expect(screen.getByRole('columnheader', { name: head })).toBeInTheDocument()
    }
    expect(screen.getByText(/Bestände ohne Leserecht erscheinen hier nicht/)).toBeInTheDocument()
  })

  it('shows a running indexing state with progress in the Stand column (#595)', async () => {
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

  it('lists libraries sorted alphabetically by name', async () => {
    setLibraryState([managerLibrary, ownLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    const items = await screen.findAllByText(/Meine Dokumente|Rechtsquellen Soziales/)
    expect(items[0]).toHaveTextContent('Meine Dokumente')
    expect(items[1]).toHaveTextContent('Rechtsquellen Soziales')
  })

  it('shows the document count and source type per library without a detail round trip', async () => {
    setLibraryState([managerLibrary, viewerLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText('431 Dok.')).toBeInTheDocument()
    expect(await screen.findByText('87 Dok.')).toBeInTheDocument()
    expect(screen.getByText('Dateisystem')).toBeInTheDocument()
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

  it('navigates to the create wizard from the header button', async () => {
    setLibraryState([])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /neue bibliothek/i }))

    expect(mockNavigate).toHaveBeenCalledWith('/libraries/new')
  })
})
