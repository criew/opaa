import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { render } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import CssBaseline from '@mui/material/CssBaseline'
import { createAppTheme } from '../theme/theme'
import Sidebar from './Sidebar'
import { useChatStore } from '../stores/chatStore'
import { useChatListStore } from '../stores/chatListStore'
import { useSpaceStore } from '../stores/spaceStore'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

const theme = createAppTheme('dark')

/**
 * Renders Sidebar as the element of a real, pathless layout route nested under a matched child
 * route - mirroring how it sits inside AppShell in production (a sibling of the routed page, not
 * itself a route with a :spaceId path). Unlike mocking useParams directly, this exercises React
 * Router's actual param propagation: matchRouteBranch (react-router/lib/router/utils.js) merges
 * every matched segment's params into one object and assigns that same object to every match in
 * the branch, including the pathless layout match - so useParams() in Sidebar genuinely sees the
 * leaf route's :spaceId (#556 review, nit 1).
 */
function renderSidebarAtRoute(initialPath: string) {
  return render(
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route element={<Sidebar />}>
            <Route path="spaces/:spaceId/chats/:chatId" element={null} />
            <Route path="spaces/:spaceId" element={null} />
            <Route path="*" element={null} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  )
}

describe('Sidebar', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    useChatStore.setState({
      spaceId: null,
      chatId: null,
      messages: [],
      isLoading: false,
      isLoadingChat: false,
      error: null,
    })
    useChatListStore.setState({ chatsBySpaceId: {}, isLoading: false, error: null })
    useSpaceStore.setState({
      spaces: [
        {
          id: 'space-personal',
          name: 'Meine Dokumente',
          description: 'Private',
          isDefault: true,
          archived: false,
          visibility: 'PRIVATE',
          memberCount: 1,
          userRole: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
        {
          id: 'space-engineering',
          name: 'Engineering',
          description: 'Dokumente der Entwicklung',
          isDefault: false,
          archived: false,
          visibility: 'PRIVATE',
          memberCount: 3,
          userRole: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
      ],
      isLoadingList: false,
    })
  })

  it('renders navigation items', () => {
    renderSidebarAtRoute('/settings')
    expect(screen.getByText('Spaces')).toBeInTheDocument()
    expect(screen.getByText('Chats')).toBeInTheDocument()
    expect(screen.getByText('Meine Dokumente')).toBeInTheDocument()
    expect(screen.getByText('Einstellungen')).toBeInTheDocument()
  })

  it('renders OPAA branding', () => {
    renderSidebarAtRoute('/settings')
    expect(screen.getByText('OPAA')).toBeInTheDocument()
    expect(screen.getByText('KI-Projektassistent')).toBeInTheDocument()
  })

  it('renders New Chat button for the default space', async () => {
    renderSidebarAtRoute('/settings')
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /neuer chat/i })).toBeInTheDocument()
    })
  })

  it('creates a new chat in the default space and navigates to it when clicked', async () => {
    const user = userEvent.setup()
    renderSidebarAtRoute('/settings')

    await user.click(await screen.findByRole('button', { name: /neuer chat/i }))

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith(
        expect.stringMatching(/^\/spaces\/space-personal\/chats\/.+$/),
      )
    })
  })

  it('lists the active space chats loaded from the API', async () => {
    renderSidebarAtRoute('/settings')
    expect(await screen.findByText('Architektur des Projekts')).toBeInTheDocument()
    expect(await screen.findByText('Deployment-Fragen')).toBeInTheDocument()
  })

  it('follows the space selected in the space overview, not the space of the still-open chat (#556)', async () => {
    // The user has an open chat in the personal space (chatStore.spaceId), but has just clicked a
    // different space in the Spaces overview - the route now points at that other space.
    useChatStore.setState({ spaceId: 'space-personal' })

    renderSidebarAtRoute('/spaces/space-engineering')

    expect(await screen.findByText('Unbenannter Chat')).toBeInTheDocument()
    expect(screen.queryByText('Architektur des Projekts')).not.toBeInTheDocument()
    expect(screen.queryByText('Deployment-Fragen')).not.toBeInTheDocument()
  })

  it('falls back to the space of the still-open chat on routes without a :spaceId (#556 review, nit 2)', async () => {
    // No :spaceId on /settings - the chat still open in the engineering space should keep
    // determining the list, not the default (personal) space.
    useChatStore.setState({ spaceId: 'space-engineering' })

    renderSidebarAtRoute('/settings')

    expect(await screen.findByText('Unbenannter Chat')).toBeInTheDocument()
    expect(screen.queryByText('Architektur des Projekts')).not.toBeInTheDocument()
    expect(screen.queryByText('Deployment-Fragen')).not.toBeInTheDocument()
  })
})
