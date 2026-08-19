import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import CssBaseline from '@mui/material/CssBaseline'
import { MemoryRouter, Route, Routes } from 'react-router'
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
})
