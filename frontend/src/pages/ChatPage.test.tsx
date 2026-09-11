import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import ChatPage from './ChatPage'
import { clearRemovedNoteItemCache, useChatStore } from '../stores/chatStore'
import { useSpaceStore } from '../stores/spaceStore'

let currentSpaceId: string | undefined = 'space-personal'
let currentChatId: string | undefined = 'new'
const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useParams: () => ({ spaceId: currentSpaceId, chatId: currentChatId }),
    useNavigate: () => mockNavigate,
  }
})

function resetChatStore() {
  // Module state, not store state (#1488): a removal a test left unconfirmed would otherwise keep
  // filtering that point out of the next test's freshly reset fixture chat.
  clearRemovedNoteItemCache()
  useChatStore.setState({
    spaceId: null,
    chatId: null,
    title: null,
    messages: [],
    isLoading: false,
    isLoadingChat: false,
    error: null,
    scope: 'all',
    referencedLibraryIds: [],
    noteItems: [],
    pendingSettingsUpdate: null,
  })
}

/** The space the Gesprächsnotiz fixture chat lives in (#1488) - `archived` decides whether the
 * panel offers remove buttons. */
function setEngineeringSpace(archived: boolean) {
  useSpaceStore.setState({
    spaces: [
      {
        id: 'space-engineering',
        name: 'Engineering',
        description: null,
        isDefault: false,
        archived,
        visibility: 'PRIVATE',
        memberCount: 1,
        userRole: 'ADMIN',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ],
    isLoadingList: false,
  })
}

describe('ChatPage', () => {
  beforeEach(() => {
    currentSpaceId = 'space-personal'
    currentChatId = 'new'
    mockNavigate.mockReset()
    resetChatStore()
  })

  it('renders empty state for a not-yet-created chat', async () => {
    renderWithProviders(<ChatPage />, { withRouter: true })
    await waitFor(() => {
      expect(screen.getByText('Womit kann ich Ihnen heute helfen?')).toBeInTheDocument()
    })
  })

  it('sends a message, implicitly creating the chat, and displays the response with sources', async () => {
    renderWithProviders(<ChatPage />, { withRouter: true })
    await waitFor(() => expect(useChatStore.getState().spaceId).toBe('space-personal'))

    const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')
    fireEvent.change(input, { target: { value: 'What is the architecture?' } })
    fireEvent.click(screen.getByLabelText('Nachricht senden'))

    expect(screen.getByText('What is the architecture?')).toBeInTheDocument()

    await waitFor(
      () => {
        expect(screen.queryByText('Denkt nach …')).not.toBeInTheDocument()
      },
      { timeout: 10000 },
    )

    expect(screen.getAllByText('Fundstellen').length).toBeGreaterThanOrEqual(1)
    expect(useChatStore.getState().chatId).toBeTruthy()
    // The URL is replaced to point at the now-persisted chat, so a reload restores it.
    expect(mockNavigate).toHaveBeenCalledWith(
      expect.stringMatching(/^\/spaces\/space-personal\/chats\/.+$/),
      { replace: true },
    )
  }, 15000)

  it('loads an existing chat by id from the route', async () => {
    currentChatId = 'chat-personal-1'
    renderWithProviders(<ChatPage />, { withRouter: true })

    await waitFor(() => {
      expect(screen.getByText('Wie ist das Projekt aufgebaut?')).toBeInTheDocument()
    })
    expect(useChatStore.getState().chatId).toBe('chat-personal-1')
  })

  it('shows error alert when present', async () => {
    renderWithProviders(<ChatPage />, { withRouter: true })
    await waitFor(() => expect(useChatStore.getState().spaceId).toBe('space-personal'))

    act(() => {
      useChatStore.setState({ error: 'Etwas ist schiefgelaufen' })
    })

    expect(screen.getByText('Etwas ist schiefgelaufen')).toBeInTheDocument()
  })

  // #1488: the Gesprächsnotiz sits in the chat's header. chat-engineering-2 (fixtures) is the one
  // chat with both prerequisites - two points and three completed rounds.
  describe('Gesprächsnotiz (#1488)', () => {
    beforeEach(() => {
      currentSpaceId = 'space-engineering'
      currentChatId = 'chat-engineering-2'
      setEngineeringSpace(false)
    })

    it('offers the note in the header of a reloaded chat, collapsed, and removes a point', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ChatPage />, { withRouter: true })

      const toggle = await screen.findByRole('button', { name: 'Gesprächsnotiz · 2' })
      expect(toggle).toHaveAttribute('aria-expanded', 'false')

      await user.click(toggle)
      expect(screen.getByRole('region', { name: 'Gesprächsnotiz' })).toBeInTheDocument()
      expect(screen.getByText('Bezugsjahr 2024')).toBeVisible()

      await user.click(screen.getAllByRole('button', { name: 'Notizpunkt entfernen' })[1])

      await waitFor(() => expect(useChatStore.getState().noteItems).toHaveLength(1))
      expect(screen.queryByText('Bezugsjahr 2024')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Gesprächsnotiz · 1' })).toBeInTheDocument()
    })

    it('shows the note without remove buttons while the space is archived', async () => {
      setEngineeringSpace(true)
      const user = userEvent.setup()
      renderWithProviders(<ChatPage />, { withRouter: true })

      await user.click(await screen.findByRole('button', { name: 'Gesprächsnotiz · 2' }))

      expect(screen.getByText('Arbeitet im Bürgerbüro Nebenstelle 3')).toBeVisible()
      expect(screen.queryByRole('button', { name: 'Notizpunkt entfernen' })).not.toBeInTheDocument()
    })
  })
})
