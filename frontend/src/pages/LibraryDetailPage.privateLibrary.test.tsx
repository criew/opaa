import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '../mocks/server'
import { answerConfirm, renderWithProviders, waitForDialogClosed } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { useIndexingStore } from '../stores/indexingStore'
import { useLibraryStore } from '../stores/libraryStore'
import type { LibraryListResponse, LibraryResponse } from '../types/api'
import { CATALOG_ROUTE } from '../routes'
import LibraryDetailPage from './LibraryDetailPage'

let currentLibraryId = 'library-private'
const navigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useParams: () => ({ libraryId: currentLibraryId }),
    useNavigate: () => navigate,
  }
})

const GIB = 1024 * 1024 * 1024

const privateEntry: LibraryListResponse = {
  id: 'library-private',
  name: 'Meine Ablage',
  description: null,
  ownerType: 'USER',
  reach: { allAccounts: false, groupCount: 0, userCount: 1 },
  myRole: 'OWNER',
  sourceType: 'NEXTCLOUD',
  documentCount: 3,
  privateLibrary: true,
  createdAt: '2026-09-20T08:00:00Z',
  updatedAt: '2026-09-20T08:00:00Z',
}

const sharedEntry: LibraryListResponse = {
  ...privateEntry,
  id: 'library-shared',
  name: 'Projektablage',
  privateLibrary: false,
}

function detailsOf(
  entry: LibraryListResponse,
  overrides: Partial<LibraryResponse> = {},
): LibraryResponse {
  return {
    ...entry,
    ownerId: 'mock-owner-id',
    sourceUrl: 'https://cloud.intern.example/remote.php/dav/files/avogt',
    ...overrides,
  }
}

/** Puts the library into the store and lets the page reload exactly that state from the API. */
function showLibrary(entry: LibraryListResponse, details: LibraryResponse) {
  currentLibraryId = entry.id
  let served = details
  server.use(
    http.get(`/api/v1/libraries/${entry.id}`, () => HttpResponse.json(served)),
    http.get('/api/v1/libraries', () => HttpResponse.json([entry])),
  )
  useLibraryStore.setState({
    libraries: [entry],
    libraryDetails: { [entry.id]: details },
    isLoading: false,
    error: null,
  })
  return {
    serve(next: LibraryResponse) {
      served = next
    },
  }
}

function storage(usedBytes: number, quotaBytes: number) {
  server.use(
    http.get('/api/v1/me/private-storage', () => HttpResponse.json({ usedBytes, quotaBytes })),
  )
}

async function openDeleteAction(user: ReturnType<typeof userEvent.setup>, name: string | RegExp) {
  await user.click(await screen.findByRole('button', { name: 'Weitere Aktionen' }))
  await user.click(await screen.findByRole('menuitem', { name }))
}

describe('LibraryDetailPage – private Bibliothek: Sofort löschen (#2165)', () => {
  beforeEach(() => {
    navigate.mockReset()
    storage(GIB, 10 * GIB)
  })

  afterEach(() => {
    useAuthStore.setState({ user: null })
    useIndexingStore.getState().stopPolling(currentLibraryId)
  })

  it('offers „Sofort löschen“ and asks with what goes and what stays, focus on „Abbrechen“', async () => {
    showLibrary(privateEntry, detailsOf(privateEntry))
    const user = userEvent.setup()
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    await openDeleteAction(user, 'Sofort löschen')

    const dialog = await screen.findByRole('dialog', { name: '„Meine Ablage“ sofort löschen?' })
    expect(dialog).toHaveTextContent(/alle Dokumente, ihr Index, die abgelegten Originale/)
    expect(dialog).toHaveTextContent(/„Quelle entfernt“/)
    expect(dialog).toHaveTextContent(/der Text der Antworten bleibt stehen/)
    expect(dialog).toHaveTextContent(/nicht rückgängig/)
    expect(within(dialog).getByRole('button', { name: 'Abbrechen' })).toHaveFocus()
  })

  it('erases nothing when the question is cancelled', async () => {
    let deleted = false
    server.use(
      http.delete('/api/v1/libraries/:libraryId', () => {
        deleted = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    showLibrary(privateEntry, detailsOf(privateEntry))
    const user = userEvent.setup()
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    await openDeleteAction(user, 'Sofort löschen')
    await answerConfirm(user, '„Meine Ablage“ sofort löschen?', 'Abbrechen')
    await waitForDialogClosed()

    expect(deleted).toBe(false)
    expect(navigate).not.toHaveBeenCalled()
  })

  it('returns to the catalog with a confirmation once the library is erased (204)', async () => {
    server.use(
      http.delete('/api/v1/libraries/:libraryId', () => new HttpResponse(null, { status: 204 })),
    )
    showLibrary(privateEntry, detailsOf(privateEntry))
    const user = userEvent.setup()
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    await openDeleteAction(user, 'Sofort löschen')
    await answerConfirm(user, '„Meine Ablage“ sofort löschen?', 'Endgültig löschen')

    await waitFor(() => expect(navigate).toHaveBeenCalledWith(CATALOG_ROUTE))
    expect(await screen.findByText('„Meine Ablage“ ist gelöscht.')).toBeInTheDocument()
  })

  it('stays on a library marked for erasure (202), says so without a time promise and focuses the notice', async () => {
    const shown = showLibrary(privateEntry, detailsOf(privateEntry))
    server.use(
      http.delete('/api/v1/libraries/:libraryId', () => {
        shown.serve(detailsOf(privateEntry, { erasureRequestedAt: '2026-10-05T09:00:00Z' }))
        return new HttpResponse(null, { status: 202 })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    await openDeleteAction(user, 'Sofort löschen')
    await answerConfirm(user, '„Meine Ablage“ sofort löschen?', 'Endgültig löschen')

    const notice = await screen.findByTestId('library-erasure-notice')
    expect(notice).toHaveTextContent(/Wird gelöscht/)
    expect(notice).toHaveTextContent(/schließt die Löschung von selbst ab/)
    await waitFor(() => expect(notice).toHaveFocus())
    expect(navigate).not.toHaveBeenCalled()
  })

  it('locks run, deletion and editing while the library is being erased', async () => {
    showLibrary(privateEntry, detailsOf(privateEntry, { erasureRequestedAt: '2026-10-05T09:00:00Z' }))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByTestId('library-erasure-notice')).toBeInTheDocument()
    expect(screen.getByLabelText('Wird gelöscht')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Jetzt indizieren/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Weitere Aktionen' })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Name und Beschreibung bearbeiten' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByText(/nur Leserechte/)).not.toBeInTheDocument()
  })

  it('keeps the plain „Löschen“ of a shared library', async () => {
    showLibrary(sharedEntry, detailsOf(sharedEntry))
    const user = userEvent.setup()
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    await user.click(await screen.findByRole('button', { name: 'Weitere Aktionen' }))
    expect(await screen.findByRole('menuitem', { name: 'Löschen' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Sofort löschen' })).not.toBeInTheDocument()
  })
})

describe('LibraryDetailPage – private Bibliothek: Speicherkontingent (#2276)', () => {
  beforeEach(() => {
    navigate.mockReset()
  })

  afterEach(() => {
    useIndexingStore.getState().stopPolling(currentLibraryId)
  })

  it('shows the owner her use across all private libraries against the limit', async () => {
    storage(1.5 * GIB, 10 * GIB)
    showLibrary(privateEntry, detailsOf(privateEntry))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByTestId('private-storage-figure')).toHaveTextContent(
      '1,5 GB von 10 GB in Ihren privaten Bibliotheken belegt',
    )
  })

  it('says „unbegrenzt“ for a limit of 0', async () => {
    storage(1.5 * GIB, 0)
    showLibrary(privateEntry, detailsOf(privateEntry))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByTestId('private-storage-figure')).toHaveTextContent(
      '1,5 GB in Ihren privaten Bibliotheken belegt (unbegrenzt)',
    )
  })

  it('asks nothing about the personal storage on a shared library', async () => {
    let asked = false
    server.use(
      http.get('/api/v1/me/private-storage', () => {
        asked = true
        return HttpResponse.json({ usedBytes: 0, quotaBytes: 0 })
      }),
    )
    showLibrary(sharedEntry, detailsOf(sharedEntry))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    await screen.findByRole('tab', { name: 'Freigaben' })
    expect(screen.queryByTestId('private-storage-figure')).not.toBeInTheDocument()
    expect(asked).toBe(false)
  })

  it('says in the head when the last run ended at the personal quota, and what frees space', async () => {
    storage(10 * GIB, 10 * GIB)
    server.use(
      http.get('/api/v1/libraries/:libraryId/indexing/runs', () =>
        HttpResponse.json({
          runs: [
            {
              id: 'run-quota',
              status: 'COMPLETED',
              triggeredBy: 'MANUAL',
              runMode: 'FULL',
              documentCount: 3,
              totalDocuments: 40,
              documentsSkipped: 0,
              documentsFailed: 0,
              documentsIndexedTotal: 3,
              message: 'Indizierung abgeschlossen — unvollständig',
              failureCategory: 'QUOTA_EXHAUSTED',
              incomplete: true,
              startedAt: '2026-10-04T10:00:00Z',
              completedAt: '2026-10-04T10:05:00Z',
              events: [],
              eventsTruncatedCount: 0,
            },
          ],
        }),
      ),
    )
    showLibrary(privateEntry, detailsOf(privateEntry))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    const notice = await screen.findByTestId('private-quota-exhausted-notice')
    expect(notice).toHaveTextContent(
      'unvollständig: Speicherkontingent Ihrer privaten Bibliotheken erschöpft',
    )
    expect(notice).toHaveTextContent(/eine ganze private Bibliothek löschen/)
  })
})
