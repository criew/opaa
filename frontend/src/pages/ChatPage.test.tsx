import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import ChatPage from './ChatPage'
import { ANSWER_ARRIVED_ANNOUNCEMENT } from '../components/chat/MessageList'
import { clearRemovedNoteItemCache, useChatStore } from '../stores/chatStore'
import { useSpaceStore } from '../stores/spaceStore'
import { useChatListStore } from '../stores/chatListStore'
import { mockChatArchive } from '../mocks/fixtures'
import { server } from '../mocks/server'

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
    archivedAt: null,
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

    const input = screen.getByPlaceholderText('Nachricht eingeben …')
    fireEvent.change(input, { target: { value: 'What is the architecture?' } })
    fireEvent.click(screen.getByLabelText('Senden'))

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

  // The loading state belongs to the chat waiting for an answer (#1574): leaving it for another
  // chat ends the loading state of the view, which is no arriving answer to announce.
  it('does not announce an arrived answer when leaving a chat that is still waiting for one', async () => {
    currentChatId = 'chat-personal-1'
    useChatStore.setState({
      spaceId: 'space-personal',
      chatId: 'chat-personal-1',
      messages: [{ id: 'q', role: 'user', content: 'Offene Frage', timestamp: new Date() }],
      isLoading: true,
    })
    renderWithProviders(<ChatPage />, { withRouter: true })
    expect(screen.getByText('Denkt nach …')).toBeInTheDocument()

    act(() => {
      useChatStore.getState().startNewChat('space-personal')
    })

    expect(screen.getByText('Womit kann ich Ihnen heute helfen?')).toBeInTheDocument()
    expect(screen.queryByText(ANSWER_ARRIVED_ANNOUNCEMENT)).not.toBeInTheDocument()
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

      await user.click(
        screen.getByRole('button', { name: 'Notizpunkt entfernen: Bezugsjahr 2024' }),
      )

      await waitFor(() => expect(useChatStore.getState().noteItems).toHaveLength(1))
      expect(screen.queryByText('Bezugsjahr 2024')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Gesprächsnotiz · 1' })).toBeInTheDocument()
    })

    // Nails down "completed round" = round with an answer. The fixture chat alone cannot: with
    // three questions and three answers, every conceivable rule is above the threshold. Here the
    // third answer is still coming in - counting messages or questions would show the button now.
    it('keeps the button away while the third answer is still coming in', async () => {
      const now = new Date()
      useChatStore.setState({
        spaceId: 'space-engineering',
        chatId: 'chat-engineering-2',
        title: 'Anwohnerparkausweis Nebenstelle 3',
        noteItems: [
          {
            id: 'note-1',
            text: 'Arbeitet im Bürgerbüro Nebenstelle 3',
            kind: 'RAHMEN',
            createdAt: '2026-03-08T09:00:10Z',
          },
          {
            id: 'note-2',
            text: 'Bezugsjahr 2024',
            kind: 'RAHMEN',
            createdAt: '2026-03-08T09:01:10Z',
          },
        ],
        messages: [
          { id: 'm1', role: 'user', content: 'Erste Frage', timestamp: now },
          { id: 'm2', role: 'assistant', content: 'Erste Antwort', timestamp: now },
          { id: 'm3', role: 'user', content: 'Zweite Frage', timestamp: now },
          { id: 'm4', role: 'assistant', content: 'Zweite Antwort', timestamp: now },
          { id: 'm5', role: 'user', content: 'Dritte Frage', timestamp: now },
        ],
        isLoading: true,
      })

      renderWithProviders(<ChatPage />, { withRouter: true })

      expect(screen.queryByRole('button', { name: /Gesprächsnotiz/ })).not.toBeInTheDocument()

      act(() => {
        useChatStore.setState((state) => ({
          messages: [
            ...state.messages,
            { id: 'm6', role: 'assistant', content: 'Dritte Antwort', timestamp: now },
          ],
          isLoading: false,
        }))
      })

      expect(screen.getByRole('button', { name: 'Gesprächsnotiz · 2' })).toBeInTheDocument()
    })

    it('shows the note without remove buttons while the space is archived', async () => {
      setEngineeringSpace(true)
      const user = userEvent.setup()
      renderWithProviders(<ChatPage />, { withRouter: true })

      await user.click(await screen.findByRole('button', { name: 'Gesprächsnotiz · 2' }))

      expect(screen.getByText('Arbeitet im Bürgerbüro Nebenstelle 3')).toBeVisible()
      expect(
        screen.queryByRole('button', { name: /^Notizpunkt entfernen/ }),
      ).not.toBeInTheDocument()
    })
  })

  describe('Chat-Archiv', () => {
    beforeEach(() => {
      currentSpaceId = 'space-personal'
      currentChatId = 'chat-personal-1'
      mockChatArchive['chat-personal-1'] = '2026-09-18T09:00:00Z'
      useChatListStore.setState({
        chatsBySpaceId: {},
        archiveBySpaceId: {},
        isLoading: false,
        isLoadingArchive: false,
        error: null,
      })
    })

    it('marks a chat opened by link as archived and brings it back on request', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ChatPage />, { withRouter: true })

      expect(await screen.findByText('Archiviert')).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Chat aus dem Archiv zurückholen' }))

      expect(await screen.findByText('Chat aus dem Archiv zurückgeholt')).toBeInTheDocument()
      expect(screen.queryByText('Archiviert')).not.toBeInTheDocument()
      expect(mockChatArchive['chat-personal-1']).toBeUndefined()
    })

    it('brings the chat back with a notice when the person writes in it', async () => {
      renderWithProviders(<ChatPage />, { withRouter: true })
      expect(await screen.findByText('Archiviert')).toBeInTheDocument()

      const input = screen.getByPlaceholderText('Nachricht eingeben …')
      fireEvent.change(input, { target: { value: 'Und wie geht es weiter?' } })
      fireEvent.click(screen.getByLabelText('Senden'))

      expect(
        await screen.findByText('Chat aus dem Archiv zurückgeholt', {}, { timeout: 10000 }),
      ).toBeInTheDocument()
      expect(screen.queryByText('Archiviert')).not.toBeInTheDocument()
      await waitFor(() =>
        expect(
          useChatListStore
            .getState()
            .chatsBySpaceId['space-personal']?.some((chat) => chat.id === 'chat-personal-1'),
        ).toBe(true),
      )
    }, 15000)

    // The server brings the chat back with the turn it writes after the answer - an archive placed
    // while that answer was still being generated does not survive it.
    it('brings back a chat archived while its answer was still coming in', async () => {
      delete mockChatArchive['chat-personal-1']
      let releaseAnswer = () => {}
      const answerGate = new Promise<void>((resolve) => {
        releaseAnswer = resolve
      })
      server.use(
        http.post('/api/v1/query', async () => {
          await answerGate
          delete mockChatArchive['chat-personal-1']
          return HttpResponse.json({
            answer: 'Es geht so weiter.',
            sources: [],
            metadata: { model: 'gpt-4o', tokenCount: 1, durationMs: 1 },
            chatId: 'chat-personal-1',
            chatTitle: null,
            noteItems: [],
          })
        }),
      )
      renderWithProviders(<ChatPage />, { withRouter: true })
      expect(await screen.findByText('Wie ist das Projekt aufgebaut?')).toBeInTheDocument()
      await act(() => useChatListStore.getState().loadChats('space-personal'))

      fireEvent.change(screen.getByPlaceholderText('Nachricht eingeben …'), {
        target: { value: 'Und wie geht es weiter?' },
      })
      fireEvent.click(screen.getByLabelText('Senden'))
      await act(() =>
        useChatListStore.getState().setChatArchived('space-personal', 'chat-personal-1', true),
      )
      expect(screen.getByText('Archiviert')).toBeInTheDocument()

      releaseAnswer()

      expect(await screen.findByText('Chat aus dem Archiv zurückgeholt')).toBeInTheDocument()
      expect(screen.queryByText('Archiviert')).not.toBeInTheDocument()
      await waitFor(() =>
        expect(
          useChatListStore
            .getState()
            .chatsBySpaceId['space-personal']?.some((chat) => chat.id === 'chat-personal-1'),
        ).toBe(true),
      )
    })

    it('shows no archive hint for an active chat', async () => {
      delete mockChatArchive['chat-personal-1']
      renderWithProviders(<ChatPage />, { withRouter: true })

      expect(await screen.findByText('Wie ist das Projekt aufgebaut?')).toBeInTheDocument()
      expect(screen.queryByText('Archiviert')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /zurückholen/ })).not.toBeInTheDocument()
    })
  })
  // #1919: Umbenennen ging bisher nur über das Kontextmenü der Chatliste.
  describe('Titel in der Kopfzeile umbenennen (#1919)', () => {
    beforeEach(() => {
      currentSpaceId = 'space-personal'
      currentChatId = 'chat-personal-1'
      delete mockChatArchive['chat-personal-1']
      useChatListStore.setState({
        chatsBySpaceId: {},
        archiveBySpaceId: {},
        isLoading: false,
        isLoadingArchive: false,
        error: null,
      })
    })

    async function openTitleField(user: ReturnType<typeof userEvent.setup>) {
      const title = await screen.findByRole('button', {
        name: 'Chat-Titel „Architektur des Projekts“ umbenennen',
      })
      await user.click(title)
      return screen.getByLabelText('Chat-Titel')
    }

    it('saves on Enter, updates the chat list and returns focus to the title', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ChatPage />, { withRouter: true })
      await act(() => useChatListStore.getState().loadChats('space-personal'))

      const field = await openTitleField(user)
      await user.clear(field)
      await user.type(field, 'Bauantrag Nordstadt{Enter}')

      const renamed = await screen.findByRole('button', {
        name: 'Chat-Titel „Bauantrag Nordstadt“ umbenennen',
      })
      await waitFor(() => expect(renamed).toHaveFocus())
      await waitFor(() =>
        expect(
          useChatListStore
            .getState()
            .chatsBySpaceId['space-personal']?.find((chat) => chat.id === 'chat-personal-1')?.title,
        ).toBe('Bauantrag Nordstadt'),
      )
    })

    it('discards the edit on Escape', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ChatPage />, { withRouter: true })

      const field = await openTitleField(user)
      await user.clear(field)
      await user.type(field, 'Verworfen{Escape}')

      expect(
        await screen.findByRole('button', {
          name: 'Chat-Titel „Architektur des Projekts“ umbenennen',
        }),
      ).toBeInTheDocument()
    })

    it('takes the title back and says so when the server refuses the rename', async () => {
      server.use(
        http.patch('/api/v1/chats/:chatId', () =>
          HttpResponse.json({ error: 'Umbenennen fehlgeschlagen' }, { status: 500 }),
        ),
      )
      const user = userEvent.setup()
      renderWithProviders(<ChatPage />, { withRouter: true })

      const field = await openTitleField(user)
      await user.clear(field)
      await user.type(field, 'Neuer Titel{Enter}')

      expect(await screen.findByText('Umbenennen fehlgeschlagen')).toBeInTheDocument()
      expect(
        await screen.findByRole('button', {
          name: 'Chat-Titel „Architektur des Projekts“ umbenennen',
        }),
      ).toBeInTheDocument()
    })
  })

  describe('jump to a search hit', () => {
    const hitLink = '/spaces/space-personal/chats/chat-personal-1?message=message-personal-1-2'

    beforeEach(() => {
      currentSpaceId = 'space-personal'
      currentChatId = 'chat-personal-1'
    })

    it('opens the chat at the message named in the link and focuses it', async () => {
      renderWithProviders(<ChatPage />, { withRouter: true, initialRoute: hitLink })

      const target = await screen.findByRole('article', { name: 'Gefundene Nachricht: Antwort' })
      await waitFor(() => expect(target).toHaveFocus())
      expect(screen.getByText('Wie ist das Projekt aufgebaut?')).toBeInTheDocument()
    })

    it('opens an archived chat at the hit message as well', async () => {
      mockChatArchive['chat-personal-1'] = '2026-09-18T09:00:00Z'
      renderWithProviders(<ChatPage />, { withRouter: true, initialRoute: hitLink })

      const target = await screen.findByRole('article', { name: 'Gefundene Nachricht: Antwort' })
      await waitFor(() => expect(target).toHaveFocus())
      expect(screen.getByText('Archiviert')).toBeInTheDocument()
    })

    // regression guard: a chat still held by the store carries client-side ids for the questions
    // of this session, so the hit's server id is only found in a freshly loaded history.
    it('reloads the chat still open in this session to find the hit message', async () => {
      useChatStore.setState({
        spaceId: 'space-personal',
        chatId: 'chat-personal-1',
        messages: [
          {
            id: 'client-side-id',
            role: 'user',
            content: 'Wie ist das Projekt aufgebaut?',
            timestamp: new Date(),
          },
        ],
      })
      renderWithProviders(<ChatPage />, {
        withRouter: true,
        initialRoute: '/spaces/space-personal/chats/chat-personal-1?message=message-personal-1-1',
      })

      const target = await screen.findByRole('article', { name: 'Gefundene Nachricht: Frage' })
      await waitFor(() => expect(target).toHaveFocus())
    })

    it('opens the chat normally when the message is unknown', async () => {
      renderWithProviders(<ChatPage />, {
        withRouter: true,
        initialRoute: '/spaces/space-personal/chats/chat-personal-1?message=gone',
      })

      expect(await screen.findByText('Wie ist das Projekt aufgebaut?')).toBeInTheDocument()
      expect(screen.queryByRole('article')).not.toBeInTheDocument()
    })
  })
})
