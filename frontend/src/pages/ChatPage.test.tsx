import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import ChatPage from './ChatPage'
import { useChatStore } from '../stores/chatStore'

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
  useChatStore.setState({
    spaceId: null,
    chatId: null,
    title: null,
    messages: [],
    isLoading: false,
    isLoadingChat: false,
    error: null,
    useKnowledge: true,
    referencedLibraryIds: [],
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

    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')
    fireEvent.change(input, { target: { value: 'What is the architecture?' } })
    fireEvent.click(screen.getByLabelText('Nachricht senden'))

    expect(screen.getByText('What is the architecture?')).toBeInTheDocument()

    await waitFor(
      () => {
        expect(screen.queryByText('Denkt nach …')).not.toBeInTheDocument()
      },
      { timeout: 10000 },
    )

    expect(screen.getAllByText(/% relevant/).length).toBeGreaterThanOrEqual(1)
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
})
