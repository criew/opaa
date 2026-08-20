import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import CssBaseline from '@mui/material/CssBaseline'
import { Link, MemoryRouter, Route, Routes } from 'react-router'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, beforeEach } from 'vitest'
import { createAppTheme } from '../theme/theme'
import { server } from '../mocks/server'
import ChatPage from './ChatPage'
import { useChatStore } from '../stores/chatStore'

// Deliberately does NOT mock react-router (unlike ChatPage.test.tsx): reproducing #548 review
// finding 1 requires an actual route match, so that navigate(..., { replace: true }) genuinely
// changes what useParams().chatId returns and re-triggers ChatPage's effect - a mocked useParams
// would never observe that change and could hide the bug.
function renderChatPageAt(initialRoute: string) {
  const theme = createAppTheme('dark')
  return render(
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <MemoryRouter initialEntries={[initialRoute]}>
        <Routes>
          <Route path="/spaces/:spaceId/chats/:chatId" element={<ChatPage />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  )
}

// A minimal stand-in for "Neuer Chat" (ChatList.tsx: navigate(`/spaces/${spaceId}/chats/new`)) -
// a real <Link>, not a mocked navigate, so the route actually changes and useParams().chatId
// genuinely flips to "new", the same way a click on the real button does. Also links back to
// chat-personal-1 and chat-personal-2 (fixtures), to exercise switching away from "new" again.
function renderChatPageWithNavLinks(initialRoute: string) {
  const theme = createAppTheme('dark')
  return render(
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <MemoryRouter initialEntries={[initialRoute]}>
        <Routes>
          <Route
            path="/spaces/:spaceId/chats/:chatId"
            element={
              <>
                <Link to="/spaces/space-personal/chats/new">Neuer Chat</Link>
                <Link to="/spaces/space-personal/chats/chat-personal-1">Chat 1</Link>
                <Link to="/spaces/space-personal/chats/chat-personal-2">Chat 2</Link>
                <ChatPage />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  )
}

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
    pendingSettingsUpdate: null,
  })
}

describe('ChatPage routing (real router)', () => {
  beforeEach(() => {
    resetChatStore()
  })

  // #548 review, finding 1: sendMessage sets chatId as soon as the implicit chat is created (well
  // before the query resolves), which replaces the URL from ".../chats/new" to the real chat id.
  // That real route change used to re-trigger ChatPage's loadChat effect, which fetched the still
  // empty persisted history (the backend only persists the turn once the LLM has answered) and
  // wiped the just-sent user message back out of the UI.
  it('keeps the just-sent user message visible while the implicit chat creation and query are in flight', async () => {
    server.use(
      http.post('/api/v1/query', async ({ request }) => {
        const body = (await request.json()) as { chatId?: string; question: string }
        // Gives the URL-replace effect time to run (and, on the buggy implementation, to fire off
        // a loadChat that would wipe the optimistic user message) before the query resolves.
        await new Promise((resolve) => setTimeout(resolve, 30))
        return HttpResponse.json({
          answer: 'Die Antwort',
          sources: [],
          metadata: {
            model: 'gpt-4o',
            tokenCount: 5,
            durationMs: 1,
            answeredWithoutKnowledge: false,
          },
          chatId: body.chatId,
        })
      }),
    )

    renderChatPageAt('/spaces/space-personal/chats/new')
    await waitFor(() => expect(useChatStore.getState().spaceId).toBe('space-personal'))

    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')
    fireEvent.change(input, { target: { value: 'Wie ist das Projekt aufgebaut?' } })
    fireEvent.click(screen.getByLabelText('Nachricht senden'))

    // The user message must stay visible throughout - including the moment the store's chatId
    // flips (and the URL replaces) but the query has not resolved yet.
    expect(await screen.findByText('Wie ist das Projekt aufgebaut?')).toBeInTheDocument()
    await waitFor(() => expect(useChatStore.getState().chatId).toBeTruthy())
    expect(screen.getByText('Wie ist das Projekt aufgebaut?')).toBeInTheDocument()

    await waitFor(() => {
      expect(screen.getByText('Die Antwort')).toBeInTheDocument()
    })
    expect(screen.getByText('Wie ist das Projekt aufgebaut?')).toBeInTheDocument()
  })

  // CI follow-up to #548: navigating from an existing, already-loaded chat to ".../chats/new" left
  // the previous chat's messages (and any source card in them) on screen. The URL-replace effect
  // watches storeChatId to redirect out of "new" once sendMessage has implicitly created a chat -
  // but on the very render where the route just became "new", storeChatId still held the *old*
  // chat's id (the routing effect's startNewChat() call only clears it on a later render). That
  // stale-but-truthy id made the effect immediately navigate right back to the old chat's URL,
  // defeating "start a new chat" entirely - the old history, including its cited source, was still
  // exactly what a subsequent question got appended to and rendered alongside.
  it('clears a loaded chat with a cited source when navigating to a new chat', async () => {
    renderChatPageWithNavLinks('/spaces/space-personal/chats/chat-personal-1')

    // chat-personal-1 (fixtures) has a cited source in its history - confirms the old chat is
    // genuinely loaded before we navigate away from it.
    await screen.findAllByTestId('source-card')
    expect(useChatStore.getState().messages.length).toBeGreaterThan(0)

    fireEvent.click(screen.getByRole('link', { name: 'Neuer Chat' }))

    await waitFor(() => {
      expect(useChatStore.getState().messages).toEqual([])
    })
    expect(useChatStore.getState().chatId).toBeNull()
    expect(screen.queryAllByTestId('source-card')).toHaveLength(0)
  })

  // Second half of the CI follow-up: even if the stale-navigation bug above did not exist, a
  // subtler variant of the same root cause would be worse - sendMessage silently reusing the old
  // chatId still sitting in the store instead of creating a genuinely new chat. This asserts what
  // chatId the query request actually carries, not just what the UI shows.
  it('creates a genuinely new chat instead of reusing the previous chat id when sending the first message after navigating to a new chat', async () => {
    let capturedChatId: string | undefined
    server.use(
      http.post('/api/v1/query', async ({ request }) => {
        const body = (await request.json()) as { chatId?: string }
        capturedChatId = body.chatId
        return HttpResponse.json({
          answer: 'Antwort im neuen Chat',
          sources: [],
          metadata: {
            model: 'gpt-4o',
            tokenCount: 5,
            durationMs: 1,
            answeredWithoutKnowledge: false,
          },
          chatId: body.chatId,
        })
      }),
    )

    renderChatPageWithNavLinks('/spaces/space-personal/chats/chat-personal-1')
    await screen.findAllByTestId('source-card')

    fireEvent.click(screen.getByRole('link', { name: 'Neuer Chat' }))
    await waitFor(() => expect(useChatStore.getState().chatId).toBeNull())

    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')
    fireEvent.change(input, { target: { value: 'Eine neue Frage' } })
    fireEvent.click(screen.getByLabelText('Nachricht senden'))

    await waitFor(() => expect(capturedChatId).toBeTruthy())
    expect(capturedChatId).not.toBe('chat-personal-1')
  })

  // Round trip: existing chat -> new chat -> a different existing chat must land on that second
  // chat's own history, not a mix of the two or a state stuck from the "new" detour in between.
  it('restores the correct chat when navigating from a new chat back to a different existing chat', async () => {
    renderChatPageWithNavLinks('/spaces/space-personal/chats/chat-personal-1')
    await screen.findAllByTestId('source-card')

    fireEvent.click(screen.getByRole('link', { name: 'Neuer Chat' }))
    await waitFor(() => expect(useChatStore.getState().chatId).toBeNull())

    fireEvent.click(screen.getByRole('link', { name: 'Chat 2' }))

    await waitFor(() => expect(useChatStore.getState().chatId).toBe('chat-personal-2'))
    expect(useChatStore.getState().messages.length).toBeGreaterThan(0)
    expect(useChatStore.getState().useKnowledge).toBe(false)
    expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-referat-50'])
  })
})
