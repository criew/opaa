import { describe, expect, it, beforeEach, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../test/test-utils'
import LibraryCreatePage from './LibraryCreatePage'
import { useLibraryStore } from '../stores/libraryStore'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return { ...actual, useNavigate: () => mockNavigate }
})

const { mockGetMyGroups } = vi.hoisted(() => ({
  mockGetMyGroups: vi.fn().mockResolvedValue([]),
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getMyGroups: mockGetMyGroups,
    getUserSummaries: vi.fn().mockResolvedValue([]),
    testLibrarySource: vi.fn(),
  }
})

const mockCreateNewLibrary = vi.fn().mockResolvedValue('lib-neu')

function renderPage() {
  return renderWithProviders(<LibraryCreatePage />, { withRouter: true })
}

describe('LibraryCreatePage (#596, Mockup 1e)', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    mockCreateNewLibrary.mockClear()
    mockGetMyGroups.mockResolvedValue([])
    useLibraryStore.setState({ createNewLibrary: mockCreateNewLibrary })
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
