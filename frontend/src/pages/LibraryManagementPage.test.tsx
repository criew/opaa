import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import LibraryManagementPage from './LibraryManagementPage'
import { useAuthStore } from '../stores/authStore'
import { useLibraryStore } from '../stores/libraryStore'
import type {
  GroupListResponse,
  LibraryListResponse,
  LibraryRequest,
  LibraryResponse,
} from '../types/api'

const { mockCreateLibrary, mockUpdateLibrary, mockDeleteLibrary, mockGetMyGroups } = vi.hoisted(
  () => ({
    mockCreateLibrary: vi.fn(async (request: LibraryRequest) => {
      const created: LibraryListResponse = {
        id: `library-${request.name}`,
        name: request.name,
        description: request.description ?? null,
        ownerType: request.ownerType ?? 'USER',
        visibility: request.visibility ?? 'PRIVATE',
        listed: request.listed ?? false,
        personal: false,
        myRole: 'OWNER',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      }
      // Simulates the real backend response influencing the next getLibraries() call, so the AC
      // "erscheint ohne Neuladen in der Liste" is actually exercised instead of assumed.
      useLibraryStore.setState((state) => ({ libraries: [...state.libraries, created] }))
      return { ...created, ownerId: null, documentCount: 0 } as LibraryResponse
    }),
    mockUpdateLibrary: vi.fn(async () => ({}) as LibraryResponse),
    mockDeleteLibrary: vi.fn(async () => undefined),
    mockGetMyGroups: vi.fn(async () => [] as GroupListResponse[]),
  }),
)

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getMyGroups: mockGetMyGroups,
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

const editorLibrary: LibraryListResponse = {
  id: 'library-editor',
  name: 'Vorlagen',
  description: 'Dokumentvorlagen',
  ownerType: 'GROUP',
  visibility: 'SHARED',
  listed: false,
  personal: false,
  myRole: 'EDITOR',
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

function setSystemAdmin() {
  useAuthStore.setState({
    mode: 'dev',
    isAuthenticated: true,
    isLoading: false,
    user: {
      id: 'admin-1',
      email: 'admin@opaa.local',
      displayName: 'Admin',
      systemRole: 'SYSTEM_ADMIN',
    },
    token: null,
    error: null,
    userManager: null,
  })
}

describe('LibraryManagementPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    useAuthStore.setState({ user: null })
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

  it('treats an EDITOR grant as read-only, same as VIEWER', async () => {
    setLibraryState([editorLibrary], {
      'library-editor': detailsOf(editorLibrary, 5),
    })
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(await screen.findByText('Vorlagen'))

    expect(await screen.findByText(/5 dokumente/i)).toBeInTheDocument()
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

  it('does not offer ORGANIZATION visibility for the personal library', async () => {
    setLibraryState([personalLibrary], {
      'library-personal': detailsOf(personalLibrary, 12),
    })
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(await screen.findByText('Meine Dokumente'))
    await user.click(await screen.findByRole('combobox', { name: /sichtbarkeit/i }))

    expect(screen.getByRole('option', { name: 'privat' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'geteilt' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'organisationsweit' })).not.toBeInTheDocument()
    expect(
      screen.getByText(/persönliche bibliothek kann nicht organisationsweit sichtbar sein/i),
    ).toBeInTheDocument()
  })

  it('offers all three visibility levels for a non-personal library', async () => {
    setLibraryState([managerLibrary], {
      'library-team': detailsOf(managerLibrary, 431),
    })
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(await screen.findByText('Rechtsquellen Soziales'))
    await user.click(await screen.findByRole('combobox', { name: /sichtbarkeit/i }))

    expect(screen.getByRole('option', { name: 'organisationsweit' })).toBeInTheDocument()
  })

  it('lets a system admin edit and delete a library without an own grant', async () => {
    setSystemAdmin()
    const orgWideLibrary: LibraryListResponse = { ...viewerLibrary, myRole: 'VIEWER' }
    setLibraryState([orgWideLibrary], {
      'library-readonly': detailsOf(orgWideLibrary, 87),
    })
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(await screen.findByText('Dienstanweisungen'))

    expect(await screen.findByRole('button', { name: /speichern/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /bibliothek löschen/i })).toBeInTheDocument()
    expect(screen.getByText('administrativ')).toBeInTheDocument()
  })

  it('still hides delete for a system admin on the personal library', async () => {
    setSystemAdmin()
    setLibraryState([personalLibrary], {
      'library-personal': detailsOf(personalLibrary, 12),
    })
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(await screen.findByText('Meine Dokumente'))

    expect(await screen.findByRole('button', { name: /speichern/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /bibliothek löschen/i })).not.toBeInTheDocument()
  })

  it('saves changed name, description and visibility together', async () => {
    setLibraryState([managerLibrary], {
      'library-team': detailsOf(managerLibrary, 431),
    })
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(await screen.findByText('Rechtsquellen Soziales'))
    const nameField = screen.getByLabelText(/name der bibliothek/i)
    await user.clear(nameField)
    await user.type(nameField, 'Rechtsquellen Soziales (neu)')
    const descriptionField = screen.getByLabelText(/beschreibung/i)
    await user.clear(descriptionField)
    await user.type(descriptionField, 'Aktualisierte Beschreibung')
    await user.click(await screen.findByRole('combobox', { name: /sichtbarkeit/i }))
    await user.click(await screen.findByRole('option', { name: 'privat' }))
    await user.click(await screen.findByRole('checkbox', { name: /im katalog auffindbar/i }))
    await user.click(screen.getByRole('button', { name: /speichern/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith('library-team', {
        name: 'Rechtsquellen Soziales (neu)',
        description: 'Aktualisierte Beschreibung',
        visibility: 'PRIVATE',
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

  it('creates a new library owned by the caller and shows it without a reload', async () => {
    setLibraryState([])
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /neue bibliothek/i }))
    await user.type(screen.getByLabelText(/^name/i), 'Frisch angelegte Bibliothek')
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    await waitFor(() => {
      expect(mockCreateLibrary).toHaveBeenCalledWith({
        name: 'Frisch angelegte Bibliothek',
        description: undefined,
        ownerType: 'USER',
        ownerId: undefined,
      })
    })
    expect(await screen.findByText('Frisch angelegte Bibliothek')).toBeInTheDocument()
  })

  it('creates a group-owned library, offering only the groups returned for the user', async () => {
    mockGetMyGroups.mockResolvedValueOnce([
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

  it('shows a visible hint instead of a silent empty list when the caller has no groups', async () => {
    mockGetMyGroups.mockResolvedValueOnce([])
    setLibraryState([])
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /neue bibliothek/i }))
    await user.click(screen.getByRole('radio', { name: /eine gruppe/i }))

    expect(await screen.findByText(/keiner gruppe mitglied/i)).toBeInTheDocument()
  })

  it('shows a visible error instead of silently swallowing a failed group lookup', async () => {
    mockGetMyGroups.mockRejectedValueOnce(new Error('Gruppen konnten nicht geladen werden'))
    setLibraryState([])
    renderWithProviders(<LibraryManagementPage />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /neue bibliothek/i }))
    await user.click(screen.getByRole('radio', { name: /eine gruppe/i }))

    const dialog = await screen.findByRole('dialog')
    expect(
      await within(dialog).findByText('Gruppen konnten nicht geladen werden'),
    ).toBeInTheDocument()
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
