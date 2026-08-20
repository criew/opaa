import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import Sidebar from './Sidebar'
import { useChatStore } from '../stores/chatStore'
import { useChatListStore } from '../stores/chatListStore'
import { useSpaceStore } from '../stores/spaceStore'

const mockNavigate = vi.fn()
let mockRouteSpaceId: string | undefined

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
    useParams: () => ({ spaceId: mockRouteSpaceId }),
  }
})

describe('Sidebar', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    mockRouteSpaceId = undefined
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
    renderWithProviders(<Sidebar />, { withRouter: true })
    expect(screen.getByText('Spaces')).toBeInTheDocument()
    expect(screen.getByText('Chats')).toBeInTheDocument()
    expect(screen.getByText('Meine Dokumente')).toBeInTheDocument()
    expect(screen.getByText('Einstellungen')).toBeInTheDocument()
  })

  it('renders OPAA branding', () => {
    renderWithProviders(<Sidebar />, { withRouter: true })
    expect(screen.getByText('OPAA')).toBeInTheDocument()
    expect(screen.getByText('KI-Projektassistent')).toBeInTheDocument()
  })

  it('renders New Chat button for the default space', async () => {
    renderWithProviders(<Sidebar />, { withRouter: true })
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /neuer chat/i })).toBeInTheDocument()
    })
  })

  it('creates a new chat in the default space and navigates to it when clicked', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Sidebar />, { withRouter: true })

    await user.click(await screen.findByRole('button', { name: /neuer chat/i }))

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith(
        expect.stringMatching(/^\/spaces\/space-personal\/chats\/.+$/),
      )
    })
  })

  it('lists the active space chats loaded from the API', async () => {
    renderWithProviders(<Sidebar />, { withRouter: true })
    expect(await screen.findByText('Architektur des Projekts')).toBeInTheDocument()
    expect(await screen.findByText('Deployment-Fragen')).toBeInTheDocument()
  })

  it('follows the space selected in the space overview, not the space of the still-open chat (#556)', async () => {
    // The user has an open chat in the personal space (chatStore.spaceId), but has just clicked a
    // different space in the Spaces overview - the route now points at that other space.
    useChatStore.setState({ spaceId: 'space-personal' })
    mockRouteSpaceId = 'space-engineering'

    renderWithProviders(<Sidebar />, { withRouter: true })

    expect(await screen.findByText('Unbenannter Chat')).toBeInTheDocument()
    expect(screen.queryByText('Architektur des Projekts')).not.toBeInTheDocument()
    expect(screen.queryByText('Deployment-Fragen')).not.toBeInTheDocument()
  })
})
