import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import LibraryManagementPage from './LibraryManagementPage'
import { useLibraryStore } from '../stores/libraryStore'
import type { GroupListResponse, LibraryListResponse, LibraryResponse } from '../types/api'

const { mockCreateLibrary, mockUpdateLibrary, mockDeleteLibrary, mockGetGroups } = vi.hoisted(
  () => ({
    mockCreateLibrary: vi.fn(async () => ({}) as LibraryResponse),
    mockUpdateLibrary: vi.fn(async () => ({}) as LibraryResponse),
    mockDeleteLibrary: vi.fn(async () => undefined),
    mockGetGroups: vi.fn(async () => [] as GroupListResponse[]),
  }),
)

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getGroups: mockGetGroups,
    getLibraries: vi.fn(async () => useLibraryStore.getState().libraries),
    getLibrary: vi.fn(
      async (libraryId: string) => useLibraryStore.getState().libraryDetails[libraryId],
    ),
    createLibrary: mockCreateLibrary,
    updateLibrary: mockUpdateLibrary,
    deleteLibrary: mockDeleteLibrary,
  }
})

const personalLibrary: LibraryListResponse = {
  id: 'library-personal',
  name: 'Meine Dokumente',
  description: 'Private Dokumente',
  ownerType: 'USER',
  visibility: 'PRIVATE',
  listed: false,
  personal: true,
  myRole: 'OWNER',
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const managerLibrary: LibraryListResponse = {
  id: 'library-team',
  name: 'Rechtsquellen Soziales',
  description: 'SGB II, SGB XII',
  ownerType: 'GROUP',
  visibility: 'SHARED',
  listed: true,
  personal: false,
  myRole: 'MANAGER',
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const viewerLibrary: LibraryListResponse = {
  id: 'library-readonly',
  name: 'Dienstanweisungen',
  description: 'Organisationsweit',
  ownerType: 'SYSTEM',
  visibility: 'ORGANIZATION',
  listed: true,
  personal: false,
  myRole: 'VIEWER',
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

function detailsOf(library: LibraryListResponse, documentCount: number): LibraryResponse {
  return { ...library, ownerId: null, documentCount }
}

function setLibraryState(
  libraries: LibraryListResponse[],
  details: Record<string, LibraryResponse> = {},
) {
  useLibraryStore.setState({
    libraries,
    libraryDetails: details,
    isLoading: false,
    error: null,
  })
}

describe('LibraryManagementPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('lists libraries with the personal library first and marked as such', async () => {
    setLibraryState([managerLibrary, personalLibrary])
    renderWithProviders(<LibraryManagementPage />)

    const items = await screen.findAllByText(/persönlich|Rechtsquellen Soziales/)
    expect(items[0]).toHaveTextContent('persönlich')
    expect(screen.getByText('Rechtsquellen Soziales')).toBeInTheDocument()
  })

  it('shows an empty state when there are no libraries', async () => {
    setLibraryState([])
    renderWithProviders(<LibraryManagementPage />)

    expect(await screen.findByText(/noch keine bibliotheken/i)).toBeInTheDocument()
  })

  it('shows neither edit nor delete controls for a VIEWER', async () => {
    setLibraryState([viewerLibrary], {
      'library-readonly': detailsOf(viewerLibrary, 87),
    })
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(await screen.findByText('Dienstanweisungen'))

    expect(await screen.findByText(/87 dokumente/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /speichern/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /bibliothek löschen/i })).not.toBeInTheDocument()
  })

  it('offers editing but not deleting for a MANAGER', async () => {
    setLibraryState([managerLibrary], {
      'library-team': detailsOf(managerLibrary, 431),
    })
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(await screen.findByText('Rechtsquellen Soziales'))

    expect(await screen.findByRole('button', { name: /speichern/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /bibliothek löschen/i })).not.toBeInTheDocument()
  })

  it('never offers deleting the personal library, even for its OWNER', async () => {
    setLibraryState([personalLibrary], {
      'library-personal': detailsOf(personalLibrary, 12),
    })
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(await screen.findByText('Meine Dokumente'))

    expect(await screen.findByRole('button', { name: /speichern/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /bibliothek löschen/i })).not.toBeInTheDocument()
  })

  it('saves changed name, description, visibility and listed flag', async () => {
    setLibraryState([managerLibrary], {
      'library-team': detailsOf(managerLibrary, 431),
    })
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(await screen.findByText('Rechtsquellen Soziales'))
    await user.click(await screen.findByRole('checkbox', { name: /im katalog auffindbar/i }))
    await user.click(screen.getByRole('button', { name: /speichern/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith('library-team', {
        name: 'Rechtsquellen Soziales',
        description: 'SGB II, SGB XII',
        visibility: 'SHARED',
        listed: false,
      })
    })
  })

  it('deletes a library with an OWNER role after confirmation', async () => {
    const deletableLibrary: LibraryListResponse = { ...managerLibrary, myRole: 'OWNER' }
    setLibraryState([deletableLibrary], {
      'library-team': detailsOf(deletableLibrary, 431),
    })
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    await user.click(await screen.findByText('Rechtsquellen Soziales'))
    await user.click(await screen.findByRole('button', { name: /bibliothek löschen/i }))

    await waitFor(() => {
      expect(mockDeleteLibrary).toHaveBeenCalledWith('library-team')
    })
  })

  it('creates a new library owned by the caller through the dialog', async () => {
    setLibraryState([])
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /neue bibliothek/i }))
    await user.type(screen.getByLabelText(/^name/i), 'Neue Bibliothek')
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    await waitFor(() => {
      expect(mockCreateLibrary).toHaveBeenCalledWith({
        name: 'Neue Bibliothek',
        description: undefined,
        ownerType: 'USER',
        ownerId: undefined,
      })
    })
  })

  it('creates a group-owned library, offering only the groups returned for the user', async () => {
    mockGetGroups.mockResolvedValueOnce([
      {
        id: 'group-referat-50',
        name: 'Referat 50',
        description: null,
        kind: 'ORG_UNIT',
        externalId: 'directory-guid',
        parentGroupId: null,
        memberCount: 3,
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    setLibraryState([])
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /neue bibliothek/i }))
    await user.type(screen.getByLabelText(/^name/i), 'Team-Bibliothek')
    await user.click(screen.getByRole('radio', { name: /eine gruppe/i }))
    await user.click(await screen.findByLabelText(/^gruppe$/i))
    await user.click(await screen.findByRole('option', { name: 'Referat 50' }))
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    await waitFor(() => {
      expect(mockCreateLibrary).toHaveBeenCalledWith({
        name: 'Team-Bibliothek',
        description: undefined,
        ownerType: 'GROUP',
        ownerId: 'group-referat-50',
      })
    })
  })

  it('shows an API error as a German message while keeping the page usable', async () => {
    setLibraryState([managerLibrary], {
      'library-team': detailsOf(managerLibrary, 431),
    })
    mockUpdateLibrary.mockRejectedValueOnce(new Error('Aktualisierung fehlgeschlagen'))
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(await screen.findByText('Rechtsquellen Soziales'))
    await user.click(await screen.findByRole('button', { name: /speichern/i }))

    expect(await screen.findByText('Aktualisierung fehlgeschlagen')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /speichern/i })).toBeInTheDocument()
  })
})
